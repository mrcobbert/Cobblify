//! macOS: AVAssetWriter with a pixel buffer adaptor (BGRA in) and an LPCM sample-buffer input.
//!
//! Apple documentation: AVAssetWriter (shouldOptimizeForNetworkUse writes the index first,
//! startWriting, startSession(atSourceTime:), finishWriting, cancelWriting deletes the file),
//! AVAssetWriterInput (isReadyForMoreMediaData; with expectsMediaDataInRealTime it turns false
//! only when the input cannot keep up),
//! AVAssetWriterInputPixelBufferAdaptor (pixelBufferPool after startWriting; 32BGRA recommended
//! for RGB sources).

use crate::args::{Codec, EncodeArgs};
use crate::protocol::{SAMPLE_RATE, SOUND_FRAME_BYTES};
use crate::Failure;

use std::collections::VecDeque;
use std::ffi::c_void;
use std::path::PathBuf;
use std::ptr::{self, NonNull};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use block2::RcBlock;
use objc2::rc::Retained;
use objc2::runtime::AnyObject;
use objc2_av_foundation::{
    AVAssetWriter, AVAssetWriterInput, AVAssetWriterInputPixelBufferAdaptor, AVAssetWriterStatus,
    AVFileTypeMPEG4, AVMediaTypeAudio, AVMediaTypeVideo, AVVideoAverageBitRateKey, AVVideoCodecKey,
    AVVideoCodecTypeH264, AVVideoCodecTypeHEVC, AVVideoColorPrimariesKey, AVVideoColorPrimaries_ITU_R_709_2,
    AVVideoColorPropertiesKey, AVVideoCompressionPropertiesKey, AVVideoExpectedSourceFrameRateKey,
    AVVideoHeightKey, AVVideoMaxKeyFrameIntervalDurationKey, AVVideoMaxKeyFrameIntervalKey,
    AVVideoProfileLevelH264HighAutoLevel, AVVideoProfileLevelKey, AVVideoTransferFunctionKey,
    AVVideoTransferFunction_ITU_R_709_2, AVVideoWidthKey, AVVideoYCbCrMatrixKey, AVVideoYCbCrMatrix_ITU_R_709_2,
};
use objc2_avf_audio::{AVEncoderBitRateKey, AVFormatIDKey, AVNumberOfChannelsKey, AVSampleRateKey};
use objc2_core_audio_types::{
    kAudioFormatFlagIsPacked, kAudioFormatFlagIsSignedInteger, kAudioFormatLinearPCM, kAudioFormatMPEG4AAC,
    AudioStreamBasicDescription,
};
use objc2_core_foundation::{CFRetained, CFString};
use objc2_core_media::{
    kCMBlockBufferAssureMemoryNowFlag, CMAudioFormatDescription, CMAudioFormatDescriptionCreate,
    CMAudioSampleBufferCreateReadyWithPacketDescriptions, CMBlockBuffer, CMSampleBuffer, CMTime,
};
use objc2_core_video::{
    kCVPixelBufferHeightKey, kCVPixelBufferPixelFormatTypeKey, kCVPixelBufferWidthKey, kCVPixelFormatType_32BGRA,
    CVPixelBuffer, CVPixelBufferGetBaseAddress, CVPixelBufferGetBytesPerRow, CVPixelBufferLockBaseAddress,
    CVPixelBufferLockFlags, CVPixelBufferPool, CVPixelBufferUnlockBaseAddress,
};
use objc2_foundation::{
    NSDictionary, NSError, NSFileManager, NSNumber, NSSearchPathDirectory, NSSearchPathDomainMask, NSString, NSURL,
};

/// Frames held while the writer is busy before we wait for it.
const MAX_PENDING_VIDEO: usize = 4;
/// No progress for this long while waiting is an error.
const STALL: Duration = Duration::from_secs(10);
const AAC_BITRATE: i32 = 192_000;

pub fn init() -> Result<(), Failure> {
    Ok(())
}

pub fn videos_dir() -> Option<PathBuf> {
    let urls = NSFileManager::defaultManager()
        .URLsForDirectory_inDomains(NSSearchPathDirectory::MoviesDirectory, NSSearchPathDomainMask::UserDomainMask);
    let url = urls.firstObject()?;
    let path = url.path()?;
    Some(PathBuf::from(path.to_string()))
}

pub struct Encoder {
    writer: Retained<AVAssetWriter>,
    video: Retained<AVAssetWriterInput>,
    adaptor: Retained<AVAssetWriterInputPixelBufferAdaptor>,
    audio: Option<Retained<AVAssetWriterInput>>,
    audio_format: Option<CFRetained<CMAudioFormatDescription>>,
    width: usize,
    height: usize,
    fps: i32,
    frames_in: i64,
    samples_in: i64,
    pending_video: VecDeque<(CFRetained<CVPixelBuffer>, i64)>,
    pending_audio: VecDeque<CFRetained<CMSampleBuffer>>,
    video_done: bool,
    audio_done: bool,
}

fn key(k: Option<&'static NSString>) -> Result<&'static NSString, Failure> {
    k.ok_or(Failure::new("unsupported_os"))
}

fn cf_key(k: &'static CFString) -> &'static NSString {
    // CFString and NSString are toll-free bridged.
    unsafe { &*(k as *const CFString as *const NSString) }
}

fn dict(pairs: &[(&NSString, &AnyObject)]) -> Retained<NSDictionary<NSString, AnyObject>> {
    let keys: Vec<&NSString> = pairs.iter().map(|p| p.0).collect();
    let values: Vec<&AnyObject> = pairs.iter().map(|p| p.1).collect();
    NSDictionary::from_slices(&keys, &values)
}

fn ns_error(e: &NSError) -> i64 {
    e.code() as i64
}

impl Encoder {
    pub fn open(a: &EncodeArgs, with_audio: bool) -> Result<Encoder, Failure> {
        unsafe { Self::open_inner(a, with_audio) }
    }

    unsafe fn open_inner(a: &EncodeArgs, with_audio: bool) -> Result<Encoder, Failure> {
        let path = NSString::from_str(&a.out.to_string_lossy());
        let url = NSURL::fileURLWithPath(&path);
        let writer = AVAssetWriter::assetWriterWithURL_fileType_error(&url, key(AVFileTypeMPEG4)?)
            .map_err(|e| Failure::with("open_failed", ns_error(&e)))?;
        writer.setShouldOptimizeForNetworkUse(true);

        // Video: H.264 High or HEVC, BT.709, a key frame at least every 2 s.
        let fps = a.fps as i32;
        let bitrate = NSNumber::new_u32(a.bitrate);
        let gop = NSNumber::new_i32(fps * 2);
        let gop_secs = NSNumber::new_f64(2.0);
        let rate = NSNumber::new_i32(fps);
        let mut compression: Vec<(&NSString, &AnyObject)> = vec![
            (key(AVVideoAverageBitRateKey)?, bitrate.as_ref()),
            (key(AVVideoMaxKeyFrameIntervalKey)?, gop.as_ref()),
            (key(AVVideoMaxKeyFrameIntervalDurationKey)?, gop_secs.as_ref()),
            (key(AVVideoExpectedSourceFrameRateKey)?, rate.as_ref()),
        ];
        let codec = match a.codec {
            Codec::H264 => {
                compression.push((key(AVVideoProfileLevelKey)?, key(AVVideoProfileLevelH264HighAutoLevel)?.as_ref()));
                key(AVVideoCodecTypeH264)?
            }
            Codec::Hevc => key(AVVideoCodecTypeHEVC)?,
        };
        let compression = dict(&compression);
        let colour = dict(&[
            (key(AVVideoColorPrimariesKey)?, key(AVVideoColorPrimaries_ITU_R_709_2)?.as_ref()),
            (key(AVVideoTransferFunctionKey)?, key(AVVideoTransferFunction_ITU_R_709_2)?.as_ref()),
            (key(AVVideoYCbCrMatrixKey)?, key(AVVideoYCbCrMatrix_ITU_R_709_2)?.as_ref()),
        ]);
        let width = NSNumber::new_u32(a.width);
        let height = NSNumber::new_u32(a.height);
        let settings = dict(&[
            (key(AVVideoCodecKey)?, codec.as_ref()),
            (key(AVVideoWidthKey)?, width.as_ref()),
            (key(AVVideoHeightKey)?, height.as_ref()),
            (key(AVVideoCompressionPropertiesKey)?, compression.as_ref()),
            (key(AVVideoColorPropertiesKey)?, colour.as_ref()),
        ]);
        let media_video = key(AVMediaTypeVideo)?;
        if !writer.canApplyOutputSettings_forMediaType(Some(&settings), media_video) {
            return Err(Failure::new("settings_refused"));
        }
        let video = AVAssetWriterInput::assetWriterInputWithMediaType_outputSettings(media_video, Some(&settings));
        // Real-time readiness: in the default mode the writer holds video back until sound for a
        // whole interleave chunk has arrived, but our sound comes one frame behind the picture in
        // the same pipe, so a busy encoder deadlocks (measured: video not ready for 10 s with sound
        // ready and caught up). In real-time mode readiness only reflects encoder throughput.
        video.setExpectsMediaDataInRealTime(true);
        let bgra = NSNumber::new_u32(kCVPixelFormatType_32BGRA);
        let source = dict(&[
            (cf_key(kCVPixelBufferPixelFormatTypeKey), bgra.as_ref()),
            (cf_key(kCVPixelBufferWidthKey), width.as_ref()),
            (cf_key(kCVPixelBufferHeightKey), height.as_ref()),
        ]);
        let adaptor =
            AVAssetWriterInputPixelBufferAdaptor::assetWriterInputPixelBufferAdaptorWithAssetWriterInput_sourcePixelBufferAttributes(
                &video,
                Some(&source),
            );
        if !writer.canAddInput(&video) {
            return Err(Failure::new("settings_refused"));
        }
        writer.addInput(&video);

        // Sound: AAC-LC 192 kbit/s, 48 kHz stereo, from s16 interleaved PCM.
        let (audio, audio_format) = if with_audio {
            let format_id = NSNumber::new_u32(kAudioFormatMPEG4AAC);
            let sample_rate = NSNumber::new_f64(SAMPLE_RATE as f64);
            let channels = NSNumber::new_i32(2);
            let aac_rate = NSNumber::new_i32(AAC_BITRATE);
            let settings = dict(&[
                (key(AVFormatIDKey)?, format_id.as_ref()),
                (key(AVSampleRateKey)?, sample_rate.as_ref()),
                (key(AVNumberOfChannelsKey)?, channels.as_ref()),
                (key(AVEncoderBitRateKey)?, aac_rate.as_ref()),
            ]);
            let media_audio = key(AVMediaTypeAudio)?;
            let input = AVAssetWriterInput::assetWriterInputWithMediaType_outputSettings(media_audio, Some(&settings));
            input.setExpectsMediaDataInRealTime(true);
            if !writer.canAddInput(&input) {
                return Err(Failure::new("settings_refused"));
            }
            writer.addInput(&input);
            (Some(input), Some(pcm_format()?))
        } else {
            (None, None)
        };

        if !writer.startWriting() {
            let status = writer.error().map(|e| ns_error(&e)).unwrap_or(0);
            writer.cancelWriting();
            return Err(Failure::with("open_failed", status));
        }
        writer.startSessionAtSourceTime(CMTime::new(0, fps));
        Ok(Encoder {
            writer,
            video,
            adaptor,
            audio,
            audio_format,
            width: a.width as usize,
            height: a.height as usize,
            fps,
            frames_in: 0,
            samples_in: 0,
            pending_video: VecDeque::new(),
            pending_audio: VecDeque::new(),
            video_done: false,
            audio_done: false,
        })
    }

    pub fn kind(&self) -> &'static str {
        // AVFoundation picks the encoder itself and does not say which one it used.
        "unknown"
    }

    pub fn frame(&mut self, bgra_bottom_up: &[u8]) -> Result<(), Failure> {
        let buffer = unsafe { self.pixel_buffer(bgra_bottom_up)? };
        self.pending_video.push_back((buffer, self.frames_in));
        self.frames_in += 1;
        self.pump(false, &|| false)
    }

    pub fn sound(&mut self, pcm: &[u8]) -> Result<(), Failure> {
        if pcm.is_empty() || self.audio.is_none() {
            return Ok(());
        }
        let samples = (pcm.len() / SOUND_FRAME_BYTES) as i64;
        let buffer = unsafe { self.sample_buffer(pcm, samples)? };
        self.samples_in += samples;
        self.pending_audio.push_back(buffer);
        self.pump(false, &|| false)
    }

    /// Drains and finishes the file. A closed input (`cancelled`) ends the wait at once: the caller
    /// deletes the file and exits, so the writer is not cancelled here (`cancelWriting` blocks
    /// until pending writes complete). A native call that blocks anyway is ended by main.rs's
    /// watcher.
    pub fn finish(mut self, cancelled: &dyn Fn() -> bool) -> Result<(), Failure> {
        self.pump(true, cancelled)?;
        crate::test_finish_stall();
        let (tx, rx) = mpsc::channel::<()>();
        let block = RcBlock::new(move || {
            let _ = tx.send(());
        });
        unsafe { self.writer.finishWritingWithCompletionHandler(&block) };
        let deadline = Instant::now() + Duration::from_secs(120);
        loop {
            match rx.recv_timeout(Duration::from_millis(10)) {
                Ok(()) => break,
                Err(mpsc::RecvTimeoutError::Disconnected) => break,
                Err(mpsc::RecvTimeoutError::Timeout) => {}
            }
            if cancelled() {
                return Err(Failure::new("cancelled"));
            }
            if Instant::now() > deadline {
                unsafe { self.writer.cancelWriting() };
                return Err(Failure::new("finish_timeout"));
            }
        }
        let status = unsafe { self.writer.status() };
        if status != AVAssetWriterStatus::Completed {
            let code = unsafe { self.writer.error() }.map(|e| ns_error(&e)).unwrap_or(status.0 as i64);
            return Err(Failure::with("finish_failed", code));
        }
        Ok(())
    }

    /// Nothing to let go of when the watcher ends the run: macOS deletes a file that is still open.
    pub fn release(&self) -> Release {
        Release
    }

    pub fn abort(self) {
        unsafe {
            let status = self.writer.status();
            if status == AVAssetWriterStatus::Writing || status == AVAssetWriterStatus::Unknown {
                self.writer.cancelWriting();
            }
        }
    }

    /// Appends whatever the inputs accept. With `drain` it waits until everything is written and
    /// marks the inputs finished; otherwise it waits only while too many frames are held. Stops
    /// waiting when `cancelled` turns true.
    fn pump(&mut self, drain: bool, cancelled: &dyn Fn() -> bool) -> Result<(), Failure> {
        let mut last_progress = Instant::now();
        loop {
            if cancelled() {
                return Err(Failure::new("cancelled"));
            }
            let mut progressed = false;
            unsafe {
                if let Some(audio) = &self.audio {
                    while let Some(buffer) = self.pending_audio.front() {
                        if !audio.isReadyForMoreMediaData() {
                            break;
                        }
                        if !audio.appendSampleBuffer(buffer) {
                            return Err(self.failure("sound_failed"));
                        }
                        self.pending_audio.pop_front();
                        progressed = true;
                    }
                    if drain && self.pending_audio.is_empty() && !self.audio_done {
                        audio.markAsFinished();
                        self.audio_done = true;
                        progressed = true;
                    }
                }
                while let Some((buffer, index)) = self.pending_video.front() {
                    if !self.video.isReadyForMoreMediaData() {
                        break;
                    }
                    if !self.adaptor.appendPixelBuffer_withPresentationTime(buffer, CMTime::new(*index, self.fps)) {
                        return Err(self.failure("frame_failed"));
                    }
                    self.pending_video.pop_front();
                    progressed = true;
                }
                if drain && self.pending_video.is_empty() && !self.video_done {
                    self.video.markAsFinished();
                    self.video_done = true;
                    progressed = true;
                }
                if self.writer.status() == AVAssetWriterStatus::Failed {
                    return Err(self.failure("encode_failed"));
                }
            }
            let waiting = if drain {
                !self.pending_video.is_empty() || !self.pending_audio.is_empty()
            } else {
                self.pending_video.len() > MAX_PENDING_VIDEO
            };
            if !waiting {
                return Ok(());
            }
            if progressed {
                last_progress = Instant::now();
            } else if last_progress.elapsed() > STALL {
                return Err(Failure::new("encoder_stall"));
            }
            std::thread::sleep(Duration::from_millis(1));
        }
    }

    fn failure(&self, code: &'static str) -> Failure {
        match unsafe { self.writer.error() } {
            Some(e) => Failure::with(code, ns_error(&e)),
            None => Failure::new(code),
        }
    }

    /// Copies one bottom-up BGRA frame into a pool buffer, top row first.
    unsafe fn pixel_buffer(&self, bgra: &[u8]) -> Result<CFRetained<CVPixelBuffer>, Failure> {
        let pool = self.adaptor.pixelBufferPool().ok_or(self.failure("no_pixel_pool"))?;
        let mut raw: *mut CVPixelBuffer = ptr::null_mut();
        let rc = CVPixelBufferPool::create_pixel_buffer(None, &pool, NonNull::from(&mut raw));
        let raw = NonNull::new(raw).filter(|_| rc == 0).ok_or(Failure::with("pixel_buffer_failed", rc as i64))?;
        let buffer = CFRetained::from_raw(raw);
        if CVPixelBufferLockBaseAddress(&buffer, CVPixelBufferLockFlags(0)) != 0 {
            return Err(Failure::new("pixel_buffer_failed"));
        }
        let base = CVPixelBufferGetBaseAddress(&buffer) as *mut u8;
        let stride = CVPixelBufferGetBytesPerRow(&buffer);
        let row = self.width * 4;
        if base.is_null() || stride < row {
            CVPixelBufferUnlockBaseAddress(&buffer, CVPixelBufferLockFlags(0));
            return Err(Failure::new("pixel_buffer_failed"));
        }
        for y in 0..self.height {
            let src = &bgra[(self.height - 1 - y) * row..(self.height - y) * row];
            ptr::copy_nonoverlapping(src.as_ptr(), base.add(y * stride), row);
        }
        CVPixelBufferUnlockBaseAddress(&buffer, CVPixelBufferLockFlags(0));
        Ok(buffer)
    }

    unsafe fn sample_buffer(&self, pcm: &[u8], samples: i64) -> Result<CFRetained<CMSampleBuffer>, Failure> {
        let format = self.audio_format.as_ref().ok_or(Failure::new("sound_failed"))?;
        let mut block: *mut CMBlockBuffer = ptr::null_mut();
        let rc = CMBlockBuffer::create_with_memory_block(
            None,
            ptr::null_mut(),
            pcm.len(),
            None,
            ptr::null(),
            0,
            pcm.len(),
            kCMBlockBufferAssureMemoryNowFlag,
            NonNull::from(&mut block),
        );
        let block = NonNull::new(block).filter(|_| rc == 0).ok_or(Failure::with("sound_failed", rc as i64))?;
        let block = CFRetained::from_raw(block);
        let rc = CMBlockBuffer::replace_data_bytes(NonNull::from(pcm).cast::<c_void>(), &block, 0, pcm.len());
        if rc != 0 {
            return Err(Failure::with("sound_failed", rc as i64));
        }
        let mut sample: *mut CMSampleBuffer = ptr::null_mut();
        let rc = CMAudioSampleBufferCreateReadyWithPacketDescriptions(
            None,
            &block,
            format,
            samples as _,
            CMTime::new(self.samples_in, SAMPLE_RATE as i32),
            ptr::null(),
            NonNull::from(&mut sample),
        );
        let sample = NonNull::new(sample).filter(|_| rc == 0).ok_or(Failure::with("sound_failed", rc as i64))?;
        Ok(CFRetained::from_raw(sample))
    }
}

/// What main.rs's watcher lets go of before it deletes the file (nothing on macOS).
pub struct Release;

impl Release {
    pub fn release(&self) {}
}

/// s16 little-endian interleaved stereo at 48 kHz.
fn pcm_format() -> Result<CFRetained<CMAudioFormatDescription>, Failure> {
    let asbd = AudioStreamBasicDescription {
        mSampleRate: SAMPLE_RATE as f64,
        mFormatID: kAudioFormatLinearPCM,
        mFormatFlags: kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked,
        mBytesPerPacket: SOUND_FRAME_BYTES as u32,
        mFramesPerPacket: 1,
        mBytesPerFrame: SOUND_FRAME_BYTES as u32,
        mChannelsPerFrame: 2,
        mBitsPerChannel: 16,
        mReserved: 0,
    };
    let mut out: *const CMAudioFormatDescription = ptr::null();
    let rc = unsafe {
        CMAudioFormatDescriptionCreate(None, NonNull::from(&asbd), 0, ptr::null(), 0, ptr::null(), None, NonNull::from(&mut out))
    };
    let out = NonNull::new(out as *mut CMAudioFormatDescription)
        .filter(|_| rc == 0)
        .ok_or(Failure::with("sound_format_failed", rc as i64))?;
    Ok(unsafe { CFRetained::from_raw(out) })
}
