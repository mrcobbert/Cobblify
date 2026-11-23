//! cobblify-encode: writes an MP4 (H.264 or HEVC video, AAC sound) from raw frames and PCM sound
//! with the operating system's own encoders (AVFoundation on macOS, Media Foundation on Windows).
//!
//! `cobblify-encode probe [--size WxH]...` prints one JSON line with the folder for videos and the
//! sizes and frame rates each codec can open.
//! `cobblify-encode encode --out P --width W --height H --fps N --codec h264|hevc --bitrate BPS`
//! reads packets from stdin (see `protocol`). Exit 0 done, 2 error, 3 cancelled. Stdin must stay
//! open until the final `done` or `error` line: closing it earlier cancels, at any time (within
//! 2 s, also while the encoder blocks), also after the finish packet. On error or cancel the
//! output file is deleted.
//!
//! No network code: the dependency tree holds only OS framework bindings.

mod args;
mod faststart;
mod protocol;
mod yuv;

#[cfg(target_os = "macos")]
mod mac;
#[cfg(target_os = "macos")]
use self::mac as platform;

#[cfg(windows)]
mod windows;
#[cfg(windows)]
use self::windows as platform;

#[cfg(not(any(target_os = "macos", windows)))]
mod platform {
    //! No encoder on other systems; argument and protocol code still builds and tests.
    use crate::args::EncodeArgs;
    use crate::Failure;
    pub fn init() -> Result<(), Failure> {
        Ok(())
    }
    pub fn videos_dir() -> Option<std::path::PathBuf> {
        None
    }
    pub struct Encoder;
    impl Encoder {
        pub fn open(_: &EncodeArgs, _: bool) -> Result<Encoder, Failure> {
            Err(Failure::new("unsupported_os"))
        }
        pub fn kind(&self) -> &'static str {
            "none"
        }
        pub fn frame(&mut self, _: &[u8], _: &dyn Fn() -> bool) -> Result<(), Failure> {
            Err(Failure::new("unsupported_os"))
        }
        pub fn sound(&mut self, _: &[u8], _: &dyn Fn() -> bool) -> Result<(), Failure> {
            Err(Failure::new("unsupported_os"))
        }
        pub fn finish(self, _: &dyn Fn() -> bool) -> Result<(), Failure> {
            Err(Failure::new("unsupported_os"))
        }
        pub fn abort(self) {}
        pub fn release(&self) -> Release {
            Release
        }
    }
    pub struct Release;
    impl Release {
        pub fn release(&self) {}
    }
    pub fn input_closed() -> bool {
        false
    }
}

use args::{Codec, Command, EncodeArgs};
use protocol::{Packet, PacketReader, ReadError};
use std::io::{BufReader, Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, SyncSender, TrySendError};
use std::sync::{Arc, Mutex};
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::{Duration, Instant};

/// An encoder failure: a short code plus the OS status number when there is one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Failure {
    pub code: &'static str,
    pub status: Option<i64>,
}

impl Failure {
    pub fn new(code: &'static str) -> Failure {
        Failure { code, status: None }
    }
    pub fn with(code: &'static str, status: i64) -> Failure {
        Failure { code, status: Some(status) }
    }
}

const EXIT_DONE: u8 = 0;
const EXIT_ERROR: u8 = 2;
const EXIT_CANCELLED: u8 = 3;

fn say(line: &str) {
    let mut out = std::io::stdout().lock();
    // A closed stdout means the game is gone; stdin closing will end the run.
    let _ = out.write_all(line.as_bytes());
    let _ = out.write_all(b"\n");
    let _ = out.flush();
}

fn main() -> ExitCode {
    let command = match args::parse(std::env::args_os().skip(1)) {
        Ok(c) => c,
        Err(e) => {
            say(&protocol::error_line(e.0, None));
            return ExitCode::from(EXIT_ERROR);
        }
    };
    if let Err(f) = platform::init() {
        say(&protocol::error_line(f.code, f.status));
        return ExitCode::from(EXIT_ERROR);
    }
    ExitCode::from(match command {
        Command::Probe { sizes } => probe(&sizes),
        Command::Encode(a) => encode(&a),
    })
}

fn encode(a: &EncodeArgs) -> u8 {
    // The mod always passes its own `.partial.mp4`; a stale one from a crash is replaced.
    let _ = std::fs::remove_file(&a.out);
    let mut enc = match platform::Encoder::open(a, true) {
        Ok(e) => e,
        Err(f) => {
            remove(&a.out);
            say(&protocol::error_line(f.code, f.status));
            return EXIT_ERROR;
        }
    };
    say(&protocol::ready_line(enc.kind()));
    // The input is read on its own thread from the start (code review 2 I2): input that closes
    // before our final line is a cancel at any time, also while a frame write or the finish blocks
    // without looking at the flag; the input thread then ends the helper itself.
    let ended = Arc::new(Mutex::new(false));
    let closed = Arc::new(AtomicBool::new(false));
    let (tx, rx) = mpsc::sync_channel::<Input>(INPUT_QUEUE);
    let (recycle_tx, recycle_rx) = mpsc::channel::<Vec<u8>>();
    {
        let input = InputThread {
            frame_len: a.frame_len(),
            tx,
            recycle: recycle_rx,
            out: a.out.clone(),
            release: enc.release(),
            ended: ended.clone(),
            closed: closed.clone(),
        };
        std::thread::spawn(move || input.run());
    }
    let cancelled = || closed.load(Ordering::SeqCst);
    let mut frames: u64 = 0;
    loop {
        if cancelled() {
            return cancel(enc, &a.out);
        }
        let input = match rx.recv_timeout(Duration::from_millis(20)) {
            Ok(i) => i,
            Err(RecvTimeoutError::Timeout) => continue,
            // the input thread ends only after the input closed (it set the flag first)
            Err(RecvTimeoutError::Disconnected) => return cancel(enc, &a.out),
        };
        let step = match input {
            Input::Frame(bgra) => {
                let r = enc.frame(&bgra, &cancelled).map(|_| {
                    frames += 1;
                    say(&protocol::frames_line(frames));
                });
                let _ = recycle_tx.send(bgra);
                r
            }
            Input::Sound(pcm) => enc.sound(&pcm, &cancelled),
            Input::Finish => break,
            Input::Bad(code) => Err(Failure::new(code)),
        };
        if let Err(f) = step {
            if cancelled() {
                return cancel(enc, &a.out);
            }
            enc.abort();
            remove(&a.out);
            end(&ended);
            say(&protocol::error_line(f.code, f.status));
            return EXIT_ERROR;
        }
    }
    if frames == 0 {
        enc.abort();
        remove(&a.out);
        end(&ended);
        say(&protocol::error_line("no_frames", None));
        return EXIT_ERROR;
    }
    let result = enc.finish(&cancelled);
    // From here the input thread leaves the ending to us (it holds this lock while it ends the
    // helper).
    end(&ended);
    if cancelled() {
        remove(&a.out);
        return EXIT_CANCELLED;
    }
    match result {
        Ok(()) => {
            remove_side_files(&a.out);
            say(&protocol::done_line(frames));
            EXIT_DONE
        }
        Err(f) => {
            remove(&a.out);
            say(&protocol::error_line(f.code, f.status));
            EXIT_ERROR
        }
    }
}

/// Test hook (encoder/tests): `COBBLIFY_ENCODE_TEST_FINISH_STALL_MS` blocks the native finish for
/// that long without looking at the input, as a slow `finishWriting` or `Finalize` would. Unset in
/// the game; values over 60 s are ignored.
pub fn test_finish_stall() {
    if let Some(ms) = std::env::var("COBBLIFY_ENCODE_TEST_FINISH_STALL_MS").ok().and_then(|v| v.parse::<u64>().ok()) {
        if ms <= 60_000 {
            std::thread::sleep(std::time::Duration::from_millis(ms));
        }
    }
}

/// Test hook (encoder/tests): `COBBLIFY_ENCODE_TEST_FRAME_STALL_MS` blocks every frame write for
/// that long without looking at the input, as a readiness wait or a `WriteSample` that does not
/// return would (code review 2 I2). Unset in the game; values over 60 s are ignored.
pub fn test_frame_stall() {
    if let Some(ms) = std::env::var("COBBLIFY_ENCODE_TEST_FRAME_STALL_MS").ok().and_then(|v| v.parse::<u64>().ok()) {
        if ms <= 60_000 {
            std::thread::sleep(std::time::Duration::from_millis(ms));
        }
    }
}

/// The input closed: the writer is dropped unfinished and the file removed (a blocking native
/// cancel is ended by the input thread).
fn cancel(enc: platform::Encoder, out: &Path) -> u8 {
    enc.abort();
    remove(out);
    EXIT_CANCELLED
}

/// The main thread is about to report its own ending: the input thread no longer ends the run.
fn end(ended: &Mutex<bool>) {
    *ended.lock().unwrap_or_else(|e| e.into_inner()) = true;
}

/// After the input closes, the main thread has this long to see the flag and end the run itself;
/// then the input thread ends it (TASK X3: gone within 2 s, its file removed).
const CANCEL_GRACE: Duration = Duration::from_millis(700);

/// Packets read ahead of the encoder. While the queue is full the input thread waits without
/// reading, so a slow encoder still holds the game back through the pipe.
const INPUT_QUEUE: usize = 2;

/// What the input thread hands the main thread.
enum Input {
    Frame(Vec<u8>),
    Sound(Vec<u8>),
    Finish,
    /// A packet that breaks the protocol (its error code).
    Bad(&'static str),
}

/// Reads stdin for the whole run (code review 2 I2). Packets go to the main thread through a
/// queue of `INPUT_QUEUE`; while it is full the thread asks the system whether the input's other
/// end has closed (`platform::input_closed`, which sees it also with unread bytes left in the
/// pipe) instead of reading on. After the finish packet (or a bad one) it reads on until the input
/// closes. Once it has closed: the flag turns true, and if the main thread has not ended the run
/// `CANCEL_GRACE` later (a native call that blocks without looking at the flag: a readiness wait,
/// `WriteSample`, `cancelWriting`, `finishWriting`, `Finalize`), this thread releases the writer's
/// file (`release`), removes the output and its side files and exits the process with the cancel
/// code, holding `ended`'s lock so the main thread cannot report done meanwhile.
struct InputThread {
    frame_len: usize,
    tx: SyncSender<Input>,
    recycle: Receiver<Vec<u8>>,
    out: PathBuf,
    release: platform::Release,
    ended: Arc<Mutex<bool>>,
    closed: Arc<AtomicBool>,
}

/// How handing a packet over went.
enum Handed {
    Yes,
    InputClosed,
    MainGone,
}

impl InputThread {
    fn run(self) {
        let stdin = std::io::stdin().lock();
        let mut reader = PacketReader::new(BufReader::with_capacity(1 << 20, stdin), self.frame_len);
        loop {
            let item = match reader.next() {
                Ok(Packet::Frame(bgra)) => {
                    let mut v = self.recycle.try_recv().unwrap_or_default();
                    v.clear();
                    v.extend_from_slice(bgra);
                    Input::Frame(v)
                }
                Ok(Packet::Sound(pcm)) => Input::Sound(pcm.to_vec()),
                Ok(Packet::Finish) => Input::Finish,
                Err(ReadError::Cancelled) => break,
                Err(e) => Input::Bad(e.code()),
            };
            let last = matches!(item, Input::Finish | Input::Bad(_));
            match self.hand_over(item) {
                Handed::Yes if last => {
                    drain(reader.get_mut());
                    break;
                }
                Handed::Yes => {}
                Handed::InputClosed => break,
                Handed::MainGone => return,
            }
        }
        self.closed.store(true, Ordering::SeqCst);
        let InputThread { tx, out, release, ended, .. } = self;
        drop(tx);
        let deadline = Instant::now() + CANCEL_GRACE;
        while Instant::now() < deadline {
            if *ended.lock().unwrap_or_else(|e| e.into_inner()) {
                return;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        let guard = ended.lock().unwrap_or_else(|e| e.into_inner());
        if *guard {
            return;
        }
        release.release();
        delete_tries(&out, 15);
        remove_side_files(&out);
        std::process::exit(EXIT_CANCELLED as i32);
    }

    /// Queues `item` for the main thread; while the queue is full, polls whether the input closed.
    fn hand_over(&self, mut item: Input) -> Handed {
        loop {
            match self.tx.try_send(item) {
                Ok(()) => return Handed::Yes,
                Err(TrySendError::Disconnected(_)) => return Handed::MainGone,
                Err(TrySendError::Full(back)) => {
                    if platform::input_closed() {
                        return Handed::InputClosed;
                    }
                    item = back;
                    std::thread::sleep(Duration::from_millis(5));
                }
            }
        }
    }
}

/// Reads and drops input until it closes.
fn drain<R: Read>(input: &mut R) {
    let mut buf = [0u8; 4096];
    loop {
        match input.read(&mut buf) {
            Ok(0) => return,
            Ok(_) => {}
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
            Err(_) => return,
        }
    }
}

/// Deletes the output file and the writer's staging files beside it, retrying briefly while the
/// OS still holds a file open.
fn remove(path: &Path) {
    delete(path);
    remove_side_files(path);
}

/// AVFoundation stages samples in `<name>.sb-*` beside the file while it writes the index first;
/// both a cancel and a finished file can leave it behind (seen on macOS 27). On Windows the index
/// rewrite uses `faststart::temp_path`.
fn remove_side_files(path: &Path) {
    let mut doomed = vec![faststart::temp_path(path)];
    if let (Some(dir), Some(name)) = (path.parent(), path.file_name().and_then(|n| n.to_str())) {
        let prefix = format!("{name}.sb-");
        if let Ok(entries) = std::fs::read_dir(dir) {
            for entry in entries.flatten() {
                if entry.file_name().to_str().is_some_and(|n| n.starts_with(&prefix)) {
                    doomed.push(entry.path());
                }
            }
        }
    }
    for file in doomed {
        delete(&file);
    }
}

fn delete(file: &Path) {
    delete_tries(file, 50);
}

fn delete_tries(file: &Path, tries: u32) {
    for _ in 0..tries {
        match std::fs::remove_file(file) {
            Ok(()) => return,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return,
            Err(_) => std::thread::sleep(std::time::Duration::from_millis(20)),
        }
    }
}

fn probe(extra: &[(u32, u32)]) -> u8 {
    let mut sizes: Vec<(u32, u32)> = args::PRESET_SIZES.to_vec();
    for s in extra {
        if !sizes.contains(s) {
            sizes.push(*s);
        }
    }
    let dir = std::env::temp_dir();
    let mut rows: [Vec<protocol::Row>; 2] = [Vec::new(), Vec::new()];
    let mut n = 0u32;
    for (ci, codec) in Codec::ALL.iter().enumerate() {
        for &(w, h) in &sizes {
            for &fps in &args::RATES {
                n += 1;
                let out = dir.join(format!("cobblify-encode-probe-{}-{n}.mp4", std::process::id()));
                if can_open(&out, *codec, w, h, fps) {
                    rows[ci].push((w, h, fps));
                }
                remove(&out);
            }
        }
    }
    let videos = platform::videos_dir().filter(|d| d.is_absolute()).map(path_text);
    say(&protocol::probe_line(videos.as_deref(), &rows[0], &rows[1]));
    EXIT_DONE
}

/// Opens an encoder and encodes one frame: a size or rate the encoder refuses fails here.
fn can_open(out: &Path, codec: Codec, w: u32, h: u32, fps: u32) -> bool {
    let a = EncodeArgs { out: out.to_path_buf(), width: w, height: h, fps, codec, bitrate: probe_bitrate(w, h, fps) };
    let _ = std::fs::remove_file(out);
    let mut enc = match platform::Encoder::open(&a, false) {
        Ok(e) => e,
        Err(_) => return false,
    };
    let frame = vec![0u8; a.frame_len()];
    if enc.frame(&frame, &|| false).is_err() {
        enc.abort();
        return false;
    }
    enc.finish(&|| false).is_ok()
}

/// A mid-quality rate for the probe: 16 Mbit/s at 1080p60, scaled like the mod's table.
fn probe_bitrate(w: u32, h: u32, fps: u32) -> u32 {
    let pixels = (w as f64 * h as f64) / (1920.0 * 1080.0);
    let rate = 16_000_000.0 * pixels.powf(0.85) * (fps as f64 / 60.0).powf(0.6);
    rate.clamp(1_000_000.0, 150_000_000.0) as u32
}

fn path_text(p: PathBuf) -> String {
    p.to_string_lossy().into_owned()
}
