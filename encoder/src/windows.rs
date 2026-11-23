//! Windows: the Media Foundation Sink Writer, NV12 in (converted here, BT.709 limited range), H.264
//! or HEVC plus AAC out, MPEG-4 container; the index is moved to the front after Finalize by
//! `faststart` (plan deviations decided by the coordinator on 2026-11-22, see below).
//!
//! Microsoft documentation: "Tutorial: Using the Sink Writer to Encode Video" (AddStream,
//! SetInputMediaType, BeginWriting, WriteSample, Finalize), "H.264 Video Encoder" and "H.265 / HEVC
//! Video Encoder" (output type attributes, ICodecAPI properties), "AAC Encoder" (24000 bytes per
//! second = 192 kbit/s), MF_MPEG4SINK_MOOV_BEFORE_MDAT, MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS,
//! IMFSinkWriter::SetInputMediaType (pEncodingParameters configures the encoder),
//! IMFSinkWriterEx::GetTransformForStream, "Image Stride" (a positive stride is top-down).

use crate::args::{Codec, EncodeArgs};
use crate::faststart;
use crate::yuv;
use crate::protocol::{SAMPLE_RATE, SOUND_FRAME_BYTES};
use crate::Failure;

use std::path::PathBuf;

use ::windows::core::{Interface, GUID, HSTRING};
use ::windows::Win32::Media::MediaFoundation::*;
use ::windows::Win32::System::Com::{CoInitializeEx, CoTaskMemFree, COINIT_MULTITHREADED};
use ::windows::Win32::UI::Shell::{SHGetKnownFolderPath, FOLDERID_Videos, KF_FLAG_DEFAULT};

/// AAC-LC at 192 kbit/s, one of the four rates the Microsoft AAC encoder accepts.
const AAC_BYTES_PER_SECOND: u32 = 24_000;
const HNS_PER_SECOND: i64 = 10_000_000;
const RATE_CONTROL: eAVEncCommonRateControlMode = eAVEncCommonRateControlMode_CBR;

fn hr(code: &'static str) -> impl Fn(::windows::core::Error) -> Failure {
    move |e| Failure::with(code, e.code().0 as i64)
}

pub fn init() -> Result<(), Failure> {
    unsafe {
        CoInitializeEx(None, COINIT_MULTITHREADED).ok().map_err(hr("com_failed"))?;
        MFStartup(MF_VERSION, MFSTARTUP_FULL).map_err(hr("mf_failed"))?;
    }
    Ok(())
}

pub fn videos_dir() -> Option<PathBuf> {
    unsafe {
        let p = SHGetKnownFolderPath(&FOLDERID_Videos, KF_FLAG_DEFAULT, None).ok()?;
        let text = p.to_string();
        CoTaskMemFree(Some(p.0 as *const _));
        text.ok().map(PathBuf::from)
    }
}

pub struct Encoder {
    out: PathBuf,
    writer: IMFSinkWriter,
    file: IMFByteStream,
    video: u32,
    audio: Option<u32>,
    width: usize,
    height: usize,
    fps: i64,
    frames_in: i64,
    samples_in: i64,
    kind: &'static str,
}

fn ratio(hi: u32, lo: u32) -> u64 {
    ((hi as u64) << 32) | lo as u64
}

fn media_type() -> Result<IMFMediaType, Failure> {
    unsafe { MFCreateMediaType().map_err(hr("open_failed")) }
}

fn set32(t: &IMFAttributes, key: &GUID, v: u32) -> Result<(), Failure> {
    unsafe { t.SetUINT32(key, v).map_err(hr("open_failed")) }
}

fn set64(t: &IMFAttributes, key: &GUID, v: u64) -> Result<(), Failure> {
    unsafe { t.SetUINT64(key, v).map_err(hr("open_failed")) }
}

fn set_guid(t: &IMFAttributes, key: &GUID, v: &GUID) -> Result<(), Failure> {
    unsafe { t.SetGUID(key, v).map_err(hr("open_failed")) }
}

fn attributes(size: u32) -> Result<IMFAttributes, Failure> {
    let mut a: Option<IMFAttributes> = None;
    unsafe { MFCreateAttributes(&mut a, size).map_err(hr("open_failed"))? };
    a.ok_or(Failure::new("open_failed"))
}

impl Encoder {
    pub fn open(a: &EncodeArgs, with_audio: bool) -> Result<Encoder, Failure> {
        unsafe { Self::open_inner(a, with_audio) }
    }

    unsafe fn open_inner(a: &EncodeArgs, with_audio: bool) -> Result<Encoder, Failure> {
        let fps = a.fps;
        let create = attributes(4)?;
        set32(&create, &MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS, 1)?;
        // MF_MPEG4SINK_MOOV_BEFORE_MDAT is not used: on the Windows box it broke the file (URL only:
        // Finalize failed with E_ACCESSDENIED; own read/write byte stream: moov written first but
        // mdat left half moved, undecodable). `faststart` moves the index after Finalize instead.
        set_guid(&create, &MF_TRANSCODE_CONTAINERTYPE, &MFTranscodeContainerType_MPEG4)?;
        let path = HSTRING::from(a.out.as_os_str());
        let file = MFCreateFile(MF_ACCESSMODE_READWRITE, MF_OPENMODE_DELETE_IF_EXIST, MF_FILEFLAGS_NONE, &path)
            .map_err(hr("open_failed"))?;
        let writer = MFCreateSinkWriterFromURL(None, &file, &create).map_err(hr("open_failed"))?;

        // Video out: H.264 High or HEVC Main, BT.709 limited range.
        let out = media_type()?;
        set_guid(&out, &MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
        let (subtype, profile) = match a.codec {
            Codec::H264 => (MFVideoFormat_H264, eAVEncH264VProfile_High.0 as u32),
            Codec::Hevc => (MFVideoFormat_HEVC, eAVEncH265VProfile_Main_420_8.0 as u32),
        };
        set_guid(&out, &MF_MT_SUBTYPE, &subtype)?;
        set32(&out, &MF_MT_MPEG2_PROFILE, profile)?;
        set32(&out, &MF_MT_AVG_BITRATE, a.bitrate)?;
        set32(&out, &MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
        set64(&out, &MF_MT_FRAME_SIZE, ratio(a.width, a.height))?;
        set64(&out, &MF_MT_FRAME_RATE, ratio(fps, 1))?;
        set64(&out, &MF_MT_PIXEL_ASPECT_RATIO, ratio(1, 1))?;
        set32(&out, &MF_MT_VIDEO_PRIMARIES, MFVideoPrimaries_BT709.0 as u32)?;
        set32(&out, &MF_MT_TRANSFER_FUNCTION, MFVideoTransFunc_709.0 as u32)?;
        set32(&out, &MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709.0 as u32)?;
        set32(&out, &MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235.0 as u32)?;
        let video = writer.AddStream(&out).map_err(hr("open_failed"))?;

        // Video in: NV12, BT.709 limited range, top row first. Fed as RGB32, the sink writer's
        // colour converter used BT.601 below 720 lines although every type said BT.709 (640x360
        // decoded as tagged: 28.3 dB), so the helper converts itself (`yuv`).
        let input = media_type()?;
        set_guid(&input, &MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
        set_guid(&input, &MF_MT_SUBTYPE, &MFVideoFormat_NV12)?;
        set32(&input, &MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
        set64(&input, &MF_MT_FRAME_SIZE, ratio(a.width, a.height))?;
        set64(&input, &MF_MT_FRAME_RATE, ratio(fps, 1))?;
        set64(&input, &MF_MT_PIXEL_ASPECT_RATIO, ratio(1, 1))?;
        set32(&input, &MF_MT_DEFAULT_STRIDE, a.width)?;
        set32(&input, &MF_MT_VIDEO_PRIMARIES, MFVideoPrimaries_BT709.0 as u32)?;
        set32(&input, &MF_MT_TRANSFER_FUNCTION, MFVideoTransFunc_709.0 as u32)?;
        set32(&input, &MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709.0 as u32)?;
        set32(&input, &MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235.0 as u32)?;
        // Encoder settings: a key frame every 2 s and the requested mean rate. The rate control
        // mode is set because a hardware encoder's default need not follow the mean rate
        // (measured: 1.9x the request on noise with the default).
        let enc = attributes(4)?;
        set32(&enc, &CODECAPI_AVEncCommonBufferSize, a.bitrate / 8)?;
        set32(&enc, &CODECAPI_AVEncCommonRateControlMode, RATE_CONTROL.0 as u32)?;
        set32(&enc, &CODECAPI_AVEncMPVGOPSize, fps * 2)?;
        set32(&enc, &CODECAPI_AVEncCommonMeanBitRate, a.bitrate)?;
        writer.SetInputMediaType(video, &input, &enc).map_err(hr("encoder_refused"))?;

        let audio = if with_audio {
            let out = media_type()?;
            set_guid(&out, &MF_MT_MAJOR_TYPE, &MFMediaType_Audio)?;
            set_guid(&out, &MF_MT_SUBTYPE, &MFAudioFormat_AAC)?;
            set32(&out, &MF_MT_AUDIO_BITS_PER_SAMPLE, 16)?;
            set32(&out, &MF_MT_AUDIO_SAMPLES_PER_SECOND, SAMPLE_RATE)?;
            set32(&out, &MF_MT_AUDIO_NUM_CHANNELS, 2)?;
            set32(&out, &MF_MT_AUDIO_AVG_BYTES_PER_SECOND, AAC_BYTES_PER_SECOND)?;
            let stream = writer.AddStream(&out).map_err(hr("open_failed"))?;
            let input = media_type()?;
            set_guid(&input, &MF_MT_MAJOR_TYPE, &MFMediaType_Audio)?;
            set_guid(&input, &MF_MT_SUBTYPE, &MFAudioFormat_PCM)?;
            set32(&input, &MF_MT_AUDIO_BITS_PER_SAMPLE, 16)?;
            set32(&input, &MF_MT_AUDIO_SAMPLES_PER_SECOND, SAMPLE_RATE)?;
            set32(&input, &MF_MT_AUDIO_NUM_CHANNELS, 2)?;
            set32(&input, &MF_MT_AUDIO_BLOCK_ALIGNMENT, SOUND_FRAME_BYTES as u32)?;
            set32(&input, &MF_MT_AUDIO_AVG_BYTES_PER_SECOND, SAMPLE_RATE * SOUND_FRAME_BYTES as u32)?;
            writer.SetInputMediaType(stream, &input, None).map_err(hr("encoder_refused"))?;
            Some(stream)
        } else {
            None
        };

        let kind = encoder_kind(&writer, video);
        writer.BeginWriting().map_err(hr("open_failed"))?;
        Ok(Encoder {
            out: a.out.clone(),
            writer,
            file,
            video,
            audio,
            width: a.width as usize,
            height: a.height as usize,
            fps: fps as i64,
            frames_in: 0,
            samples_in: 0,
            kind,
        })
    }

    pub fn kind(&self) -> &'static str {
        self.kind
    }

    /// Converts and writes one frame. `WriteSample` may block while the encoder is behind; a
    /// closed input is then seen by main.rs's input thread, which ends the run.
    pub fn frame(&mut self, bgra_bottom_up: &[u8], _cancelled: &dyn Fn() -> bool) -> Result<(), Failure> {
        crate::test_frame_stall();
        let len = yuv::nv12_len(self.width, self.height);
        let start = self.frames_in * HNS_PER_SECOND / self.fps;
        let end = (self.frames_in + 1) * HNS_PER_SECOND / self.fps;
        unsafe {
            let buffer = MFCreateMemoryBuffer(len as u32).map_err(hr("frame_failed"))?;
            let mut data: *mut u8 = std::ptr::null_mut();
            buffer.Lock(&mut data, None, None).map_err(hr("frame_failed"))?;
            yuv::bgra_bottom_up_to_nv12(bgra_bottom_up, self.width, self.height, std::slice::from_raw_parts_mut(data, len));
            buffer.Unlock().map_err(hr("frame_failed"))?;
            buffer.SetCurrentLength(len as u32).map_err(hr("frame_failed"))?;
            self.write(self.video, &buffer, start, end - start, "frame_failed")?;
        }
        self.frames_in += 1;
        Ok(())
    }

    pub fn sound(&mut self, pcm: &[u8], _cancelled: &dyn Fn() -> bool) -> Result<(), Failure> {
        let Some(stream) = self.audio else { return Ok(()) };
        if pcm.is_empty() {
            return Ok(());
        }
        let samples = (pcm.len() / SOUND_FRAME_BYTES) as i64;
        let rate = SAMPLE_RATE as i64;
        let start = self.samples_in * HNS_PER_SECOND / rate;
        let end = (self.samples_in + samples) * HNS_PER_SECOND / rate;
        unsafe {
            let buffer = MFCreateMemoryBuffer(pcm.len() as u32).map_err(hr("sound_failed"))?;
            let mut data: *mut u8 = std::ptr::null_mut();
            buffer.Lock(&mut data, None, None).map_err(hr("sound_failed"))?;
            std::ptr::copy_nonoverlapping(pcm.as_ptr(), data, pcm.len());
            buffer.Unlock().map_err(hr("sound_failed"))?;
            buffer.SetCurrentLength(pcm.len() as u32).map_err(hr("sound_failed"))?;
            self.write(stream, &buffer, start, end - start, "sound_failed")?;
        }
        self.samples_in += samples;
        Ok(())
    }

    unsafe fn write(&self, stream: u32, buffer: &IMFMediaBuffer, time: i64, duration: i64, code: &'static str) -> Result<(), Failure> {
        let sample = MFCreateSample().map_err(hr(code))?;
        sample.AddBuffer(buffer).map_err(hr(code))?;
        sample.SetSampleTime(time).map_err(hr(code))?;
        sample.SetSampleDuration(duration).map_err(hr(code))?;
        self.writer.WriteSample(stream, &sample).map_err(hr(code))
    }

    pub fn finish(self, cancelled: &dyn Fn() -> bool) -> Result<(), Failure> {
        crate::test_finish_stall();
        let result = unsafe { self.writer.Finalize().map_err(hr("finish_failed")) };
        drop(self.writer);
        let _ = unsafe { self.file.Close() };
        drop(self.file);
        result?;
        faststart::move_index_first(&self.out, cancelled).map(|_| ()).map_err(|e| Failure::new(e.code()))
    }

    /// The file's byte stream, for main.rs's input thread: when a closed input finds a native call
    /// still running it closes the stream so the file can be deleted before the process exits.
    pub fn release(&self) -> Release {
        Release(self.file.clone())
    }

    /// Drops the writer without finalizing and closes the file so the caller can delete it.
    pub fn abort(self) {
        drop(self.writer);
        let _ = unsafe { self.file.Close() };
    }
}

/// The output's byte stream, closed from the input thread. COM is initialised multithreaded
/// (`init`), so the stream may be called from any thread of the process.
pub struct Release(IMFByteStream);

// SAFETY: an MTA object; `init` joins the process to the multithreaded apartment and the input
// thread belongs to it implicitly.
unsafe impl Send for Release {}

impl Release {
    pub fn release(&self) {
        let _ = unsafe { self.0.Close() };
    }
}

/// The input's other end has closed (the game quit, crashed or cancelled), also while unread bytes
/// are still in the pipe (code review 2 I2). Anonymous pipes are named pipes underneath:
/// `NtQueryInformationFile(FilePipeLocalInformation)` reports `FILE_PIPE_CLOSING_STATE` once the
/// other end is closed. If that call is unavailable, `PeekNamedPipe` failing with
/// `ERROR_BROKEN_PIPE` (other end closed, nothing left) is the answer. Only asked by main.rs's
/// input thread while it does not read (a synchronous read would hold the pipe meanwhile).
pub fn input_closed() -> bool {
    use std::ffi::c_void;
    use std::os::windows::io::AsRawHandle;

    #[repr(C)]
    #[allow(dead_code)]
    struct IoStatusBlock {
        status: usize,
        information: usize,
    }
    #[repr(C)]
    #[derive(Default)]
    #[allow(dead_code)]
    struct FilePipeLocalInformation {
        named_pipe_type: u32,
        named_pipe_configuration: u32,
        maximum_instances: u32,
        current_instances: u32,
        inbound_quota: u32,
        read_data_available: u32,
        outbound_quota: u32,
        write_quota_available: u32,
        named_pipe_state: u32,
        named_pipe_end: u32,
    }
    type NtQueryInformationFile =
        unsafe extern "system" fn(*mut c_void, *mut IoStatusBlock, *mut c_void, u32, i32) -> i32;
    #[link(name = "kernel32")]
    extern "system" {
        fn GetModuleHandleW(name: *const u16) -> *mut c_void;
        fn GetProcAddress(module: *mut c_void, name: *const u8) -> *mut c_void;
        fn PeekNamedPipe(pipe: *mut c_void, buf: *mut c_void, size: u32, read: *mut u32, avail: *mut u32, left: *mut u32) -> i32;
        fn GetLastError() -> u32;
    }
    const FILE_PIPE_LOCAL_INFORMATION: i32 = 24;
    const FILE_PIPE_CLOSING_STATE: u32 = 4;
    const ERROR_BROKEN_PIPE: u32 = 109;

    let handle = std::io::stdin().as_raw_handle() as *mut c_void;
    // SAFETY: ntdll is loaded in every process; the function pointer has NtQueryInformationFile's
    // documented signature; the buffers are ours and sized as passed.
    unsafe {
        let ntdll: Vec<u16> = "ntdll.dll\0".encode_utf16().collect();
        let module = GetModuleHandleW(ntdll.as_ptr());
        if !module.is_null() {
            let f = GetProcAddress(module, b"NtQueryInformationFile\0".as_ptr());
            if !f.is_null() {
                let query: NtQueryInformationFile = std::mem::transmute(f);
                let mut io = IoStatusBlock { status: 0, information: 0 };
                let mut info = FilePipeLocalInformation::default();
                let status = query(
                    handle,
                    &mut io,
                    &mut info as *mut _ as *mut c_void,
                    std::mem::size_of::<FilePipeLocalInformation>() as u32,
                    FILE_PIPE_LOCAL_INFORMATION,
                );
                if status >= 0 {
                    return info.named_pipe_state == FILE_PIPE_CLOSING_STATE;
                }
            }
        }
        let mut avail = 0u32;
        let ok = PeekNamedPipe(handle, std::ptr::null_mut(), 0, std::ptr::null_mut(), &mut avail, std::ptr::null_mut());
        ok == 0 && GetLastError() == ERROR_BROKEN_PIPE
    }
}

/// Which encoder the sink writer loaded for the video stream: a hardware MFT carries
/// MFT_ENUM_HARDWARE_URL_Attribute.
unsafe fn encoder_kind(writer: &IMFSinkWriter, stream: u32) -> &'static str {
    let Ok(ex) = writer.cast::<IMFSinkWriterEx>() else { return "unknown" };
    for index in 0..8 {
        let mut category = GUID::zeroed();
        let mut transform: Option<IMFTransform> = None;
        if ex.GetTransformForStream(stream, index, Some(&mut category), &mut transform).is_err() {
            break;
        }
        if category != MFT_CATEGORY_VIDEO_ENCODER {
            continue;
        }
        let Some(t) = transform else { continue };
        return match t.GetAttributes() {
            Ok(attrs) if attrs.GetStringLength(&MFT_ENUM_HARDWARE_URL_Attribute).is_ok() => "hardware",
            Ok(_) => "software",
            Err(_) => "software",
        };
    }
    "unknown"
}
