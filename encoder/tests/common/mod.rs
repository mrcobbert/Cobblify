//! Shared helpers for the helper's end-to-end tests: start the binary, feed packets, read status
//! lines, generate test frames and sound, a tiny JSON reader, and the side files `verify.sh` reads.
#![allow(dead_code)]

use std::collections::BTreeMap;
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, ExitStatus, Stdio};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError};
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant};

pub const BIN: &str = env!("CARGO_BIN_EXE_cobblify-encode");

/// Encoders are a shared resource (hardware sessions, timing); tests take turns.
static SERIAL: Mutex<()> = Mutex::new(());

pub fn serial() -> MutexGuard<'static, ()> {
    SERIAL.lock().unwrap_or_else(|e| e.into_inner())
}

/// Where test MP4s and their side files are kept for `tests/verify.sh`.
pub fn out_dir() -> PathBuf {
    let dir = match std::env::var_os("COBBLIFY_ENCODE_TEST_OUT") {
        Some(d) => PathBuf::from(d),
        None => PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join("encode-out"),
    };
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

/// A fresh scratch directory for files that must not survive.
pub fn scratch(name: &str) -> PathBuf {
    let dir = PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join("scratch").join(name);
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

// ---------------------------------------------------------------- process

pub struct Helper {
    child: Child,
    stdin: Option<ChildStdin>,
    lines: Receiver<String>,
    pub seen: Vec<String>,
}

impl Helper {
    pub fn start(args: &[String]) -> Helper {
        let mut child = Command::new(BIN)
            .args(args)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .expect("start helper");
        let stdout = child.stdout.take().unwrap();
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            for line in BufReader::new(stdout).lines() {
                match line {
                    Ok(l) => {
                        if tx.send(l).is_err() {
                            break;
                        }
                    }
                    Err(_) => break,
                }
            }
        });
        let stdin = child.stdin.take();
        Helper { child, stdin, lines: rx, seen: Vec::new() }
    }

    /// Next status line, or None when stdout closed or the wait timed out.
    pub fn line(&mut self, timeout: Duration) -> Option<String> {
        match self.lines.recv_timeout(timeout) {
            Ok(l) => {
                self.seen.push(l.clone());
                Some(l)
            }
            Err(RecvTimeoutError::Timeout) | Err(RecvTimeoutError::Disconnected) => None,
        }
    }

    pub fn send(&mut self, kind: u8, payload: &[u8]) -> std::io::Result<()> {
        let stdin = self.stdin.as_mut().expect("stdin open");
        stdin.write_all(&[kind])?;
        stdin.write_all(&(payload.len() as u32).to_le_bytes())?;
        stdin.write_all(payload)
    }

    /// Reads status lines until `done` or `error` (or stdout closes). The helper treats input
    /// that closes before its final line as a cancel, so callers keep stdin open until then.
    pub fn await_final_line(&mut self, timeout: Duration) {
        let start = Instant::now();
        while start.elapsed() < timeout {
            match self.line(Duration::from_millis(200)) {
                Some(l) if l.contains("\"done\"") || l.contains("\"error\"") => return,
                Some(_) => {}
                None => {
                    if self.child.try_wait().ok().flatten().is_some() {
                        return;
                    }
                }
            }
        }
    }

    pub fn close_stdin(&mut self) {
        self.stdin.take();
    }

    /// Waits for exit; panics after `timeout`. Collects the remaining status lines.
    pub fn wait(&mut self, timeout: Duration) -> (ExitStatus, Duration) {
        let start = Instant::now();
        loop {
            if let Some(status) = self.child.try_wait().unwrap() {
                while let Ok(l) = self.lines.recv_timeout(Duration::from_millis(200)) {
                    self.seen.push(l);
                }
                return (status, start.elapsed());
            }
            if start.elapsed() > timeout {
                let _ = self.child.kill();
                panic!("helper did not exit within {timeout:?}; lines so far {:?}", self.seen);
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    pub fn drain_lines(&mut self) {
        while let Ok(l) = self.lines.try_recv() {
            self.seen.push(l);
        }
    }
}

impl Drop for Helper {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// Runs the helper to completion with no stdin data and returns (exit code, stdout lines).
pub fn run(args: &[&str]) -> (Option<i32>, Vec<String>) {
    let out = Command::new(BIN).args(args).stdin(Stdio::null()).output().expect("run helper");
    let text = String::from_utf8_lossy(&out.stdout).to_string();
    (out.status.code(), text.lines().map(str::to_string).collect())
}

// ---------------------------------------------------------------- probe

pub struct Probe {
    pub videos_dir: Option<String>,
    pub h264: Vec<(u32, u32, u32)>,
    pub hevc: Vec<(u32, u32, u32)>,
}

impl Probe {
    pub fn rows(&self, codec: &str) -> &[(u32, u32, u32)] {
        if codec == "h264" {
            &self.h264
        } else {
            &self.hevc
        }
    }

    pub fn codecs(&self) -> Vec<&'static str> {
        let mut v = vec![];
        if !self.h264.is_empty() {
            v.push("h264");
        }
        if !self.hevc.is_empty() {
            v.push("hevc");
        }
        v
    }

    pub fn has(&self, codec: &str, w: u32, h: u32, fps: u32) -> bool {
        self.rows(codec).contains(&(w, h, fps))
    }
}

pub fn parse_probe(line: &str) -> Probe {
    let v = Json::parse(line).unwrap_or_else(|e| panic!("probe line is not JSON ({e}): {line}"));
    let obj = v.obj().expect("probe line is an object");
    assert_eq!(obj.get("protocol").and_then(Json::num), Some(1.0), "protocol 1: {line}");
    let rows = |key: &str| -> Vec<(u32, u32, u32)> {
        obj.get(key)
            .and_then(Json::arr)
            .unwrap_or_else(|| panic!("{key} array: {line}"))
            .iter()
            .map(|r| {
                let r = r.arr().expect("row array");
                assert_eq!(r.len(), 3, "row of three: {line}");
                let n = |i: usize| r[i].num().expect("number") as u32;
                (n(0), n(1), n(2))
            })
            .collect()
    };
    Probe {
        videos_dir: obj.get("videosDir").and_then(Json::str).map(str::to_string),
        h264: rows("h264"),
        hevc: rows("hevc"),
    }
}

pub fn probe(sizes: &[&str]) -> (Option<i32>, Vec<String>) {
    let mut args = vec!["probe"];
    for s in sizes {
        args.push("--size");
        args.push(s);
    }
    run(&args)
}

/// The probe without extra sizes, cached per test binary.
pub fn base_probe() -> &'static Probe {
    static CELL: std::sync::OnceLock<Probe> = std::sync::OnceLock::new();
    CELL.get_or_init(|| {
        let (code, lines) = probe(&[]);
        assert_eq!(code, Some(0), "probe exit: {lines:?}");
        assert_eq!(lines.len(), 1, "one line: {lines:?}");
        parse_probe(&lines[0])
    })
}

// ---------------------------------------------------------------- tiny JSON

#[derive(Debug, Clone, PartialEq)]
pub enum Json {
    Null,
    Bool(bool),
    Num(f64),
    Str(String),
    Arr(Vec<Json>),
    Obj(BTreeMap<String, Json>),
}

impl Json {
    pub fn parse(text: &str) -> Result<Json, String> {
        let b = text.as_bytes();
        let mut i = 0;
        let v = value(b, &mut i)?;
        skip_ws(b, &mut i);
        if i != b.len() {
            return Err(format!("trailing data at {i}"));
        }
        Ok(v)
    }
    pub fn obj(&self) -> Option<&BTreeMap<String, Json>> {
        if let Json::Obj(m) = self { Some(m) } else { None }
    }
    pub fn arr(&self) -> Option<&Vec<Json>> {
        if let Json::Arr(a) = self { Some(a) } else { None }
    }
    pub fn num(&self) -> Option<f64> {
        if let Json::Num(n) = self { Some(*n) } else { None }
    }
    pub fn str(&self) -> Option<&str> {
        if let Json::Str(s) = self { Some(s) } else { None }
    }
    pub fn bool(&self) -> Option<bool> {
        if let Json::Bool(b) = self { Some(*b) } else { None }
    }
}

fn skip_ws(b: &[u8], i: &mut usize) {
    while *i < b.len() && (b[*i] as char).is_ascii_whitespace() {
        *i += 1;
    }
}

fn value(b: &[u8], i: &mut usize) -> Result<Json, String> {
    skip_ws(b, i);
    match b.get(*i) {
        None => Err("end of input".into()),
        Some(b'{') => {
            *i += 1;
            let mut m = BTreeMap::new();
            skip_ws(b, i);
            if b.get(*i) == Some(&b'}') {
                *i += 1;
                return Ok(Json::Obj(m));
            }
            loop {
                skip_ws(b, i);
                let k = match value(b, i)? {
                    Json::Str(s) => s,
                    _ => return Err("key".into()),
                };
                skip_ws(b, i);
                if b.get(*i) != Some(&b':') {
                    return Err("colon".into());
                }
                *i += 1;
                let v = value(b, i)?;
                m.insert(k, v);
                skip_ws(b, i);
                match b.get(*i) {
                    Some(b',') => *i += 1,
                    Some(b'}') => {
                        *i += 1;
                        return Ok(Json::Obj(m));
                    }
                    _ => return Err("object".into()),
                }
            }
        }
        Some(b'[') => {
            *i += 1;
            let mut a = vec![];
            skip_ws(b, i);
            if b.get(*i) == Some(&b']') {
                *i += 1;
                return Ok(Json::Arr(a));
            }
            loop {
                a.push(value(b, i)?);
                skip_ws(b, i);
                match b.get(*i) {
                    Some(b',') => *i += 1,
                    Some(b']') => {
                        *i += 1;
                        return Ok(Json::Arr(a));
                    }
                    _ => return Err("array".into()),
                }
            }
        }
        Some(b'"') => {
            *i += 1;
            let mut s = String::new();
            loop {
                let c = *b.get(*i).ok_or("string end")?;
                *i += 1;
                match c {
                    b'"' => return Ok(Json::Str(s)),
                    b'\\' => {
                        let e = *b.get(*i).ok_or("escape")?;
                        *i += 1;
                        match e {
                            b'"' => s.push('"'),
                            b'\\' => s.push('\\'),
                            b'/' => s.push('/'),
                            b'n' => s.push('\n'),
                            b'r' => s.push('\r'),
                            b't' => s.push('\t'),
                            b'b' => s.push('\u{8}'),
                            b'f' => s.push('\u{c}'),
                            b'u' => {
                                let hex = std::str::from_utf8(b.get(*i..*i + 4).ok_or("u escape")?).map_err(|_| "hex")?;
                                let cp = u32::from_str_radix(hex, 16).map_err(|_| "hex")?;
                                *i += 4;
                                s.push(char::from_u32(cp).ok_or("code point")?);
                            }
                            _ => return Err("bad escape".into()),
                        }
                    }
                    _ => {
                        // Copy one UTF-8 sequence.
                        let start = *i - 1;
                        let len = match c {
                            0x00..=0x7f => 1,
                            0xc0..=0xdf => 2,
                            0xe0..=0xef => 3,
                            _ => 4,
                        };
                        let chunk = std::str::from_utf8(b.get(start..start + len).ok_or("utf8")?).map_err(|_| "utf8")?;
                        s.push_str(chunk);
                        *i = start + len;
                    }
                }
            }
        }
        Some(b't') if b[*i..].starts_with(b"true") => {
            *i += 4;
            Ok(Json::Bool(true))
        }
        Some(b'f') if b[*i..].starts_with(b"false") => {
            *i += 5;
            Ok(Json::Bool(false))
        }
        Some(b'n') if b[*i..].starts_with(b"null") => {
            *i += 4;
            Ok(Json::Null)
        }
        Some(_) => {
            let start = *i;
            while *i < b.len() && matches!(b[*i], b'-' | b'+' | b'.' | b'e' | b'E' | b'0'..=b'9') {
                *i += 1;
            }
            let t = std::str::from_utf8(&b[start..*i]).unwrap();
            t.parse::<f64>().map(Json::Num).map_err(|_| format!("number '{t}' at {start}"))
        }
    }
}

// ---------------------------------------------------------------- content

/// Small fast PRNG (xorshift64*), deterministic per seed.
pub struct Rng(u64);

impl Rng {
    pub fn new(seed: u64) -> Rng {
        Rng(seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1)
    }
    pub fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.0 = x;
        x.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }
}

/// The test pattern as RGB, top row first: four colour quadrants whose meeting point moves with
/// the frame index, a white marker square in the top-left corner, and low-amplitude grey noise in
/// the middle ninth of the picture.
pub fn pattern_rgb(w: u32, h: u32, index: u32) -> Vec<u8> {
    let (w, h) = (w as usize, h as usize);
    let mut rgb = vec![0u8; w * h * 3];
    let split_x = w / 2 + (index as usize % 60) * w / 240;
    let split_y = h / 2 + (index as usize % 40) * h / 240;
    let marker = (h / 8).max(8);
    let mut rng = Rng::new(index as u64 + 1);
    for y in 0..h {
        for x in 0..w {
            let c: [u8; 3] = if x < marker && y < marker {
                [255, 255, 255]
            } else if x >= w / 3 && x < 2 * w / 3 && y >= h / 3 && y < 2 * h / 3 {
                let n = 112 + (rng.next() >> 59) as u8; // 112..143
                [n, n, n]
            } else {
                match (x < split_x, y < split_y) {
                    (true, true) => [200, 40, 40],
                    (false, true) => [40, 180, 60],
                    (true, false) => [40, 70, 200],
                    (false, false) => [220, 200, 40],
                }
            };
            let o = (y * w + x) * 3;
            rgb[o..o + 3].copy_from_slice(&c);
        }
    }
    rgb
}

/// RGB top row first -> the protocol's BGRA, bottom row first.
pub fn to_protocol_bgra(rgb: &[u8], w: u32, h: u32) -> Vec<u8> {
    let (w, h) = (w as usize, h as usize);
    let mut out = vec![0u8; w * h * 4];
    for y in 0..h {
        let src = &rgb[(h - 1 - y) * w * 3..(h - y) * w * 3];
        let dst = &mut out[y * w * 4..(y + 1) * w * 4];
        for x in 0..w {
            dst[x * 4] = src[x * 3 + 2];
            dst[x * 4 + 1] = src[x * 3 + 1];
            dst[x * 4 + 2] = src[x * 3];
            dst[x * 4 + 3] = 255;
        }
    }
    out
}

/// A full-picture noise frame in protocol layout (orientation does not matter for noise): every
/// colour component of every pixel is mid grey plus fresh uniform noise in -10..=10. That needs
/// more bits than the highest test rate, yet stays within reach of the lowest: full-range noise
/// cannot be squeezed into 8 Mbit/s at 1080p60 by any encoder (measured with VideoToolbox:
/// +-50 noise came out at 8x the request, +-10 within 5%).
pub fn noise_bgra(w: u32, h: u32, index: u32) -> Vec<u8> {
    let len = w as usize * h as usize * 4;
    let mut out = vec![0u8; len];
    let mut rng = Rng::new(0xC0FFEE ^ index as u64);
    for px in out.chunks_exact_mut(4) {
        let r = rng.next();
        px[0] = 118 + (r % 21) as u8;
        px[1] = 118 + ((r >> 16) % 21) as u8;
        px[2] = 118 + ((r >> 32) % 21) as u8;
        px[3] = 255;
    }
    out
}

/// Sound sample index where frame `i` starts at `fps`.
pub fn sample_at(i: u64, fps: u32) -> u64 {
    (i * 48_000 + fps as u64 / 2) / fps as u64
}

/// 440 Hz in the left channel only, half scale, samples `[from, to)`.
pub fn tone_left(from: u64, to: u64) -> Vec<u8> {
    let mut out = Vec::with_capacity(((to - from) * 4) as usize);
    for n in from..to {
        let t = n as f64 / 48_000.0;
        let l = ((t * 440.0 * 2.0 * std::f64::consts::PI).sin() * 16_000.0) as i16;
        out.extend_from_slice(&l.to_le_bytes());
        out.extend_from_slice(&0i16.to_le_bytes());
    }
    out
}

pub fn silence(from: u64, to: u64) -> Vec<u8> {
    vec![0u8; ((to - from) * 4) as usize]
}

// ---------------------------------------------------------------- encode one clip

pub enum Content {
    Pattern,
    Noise,
}

pub struct Clip<'a> {
    pub label: &'a str,
    pub codec: &'a str,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub bitrate: u32,
    pub frames: u32,
    /// Total sound samples sent; sound follows each frame and the rest goes before finish.
    pub samples: u64,
    pub content: Content,
    pub tone: bool,
}

pub struct Encoded {
    pub path: PathBuf,
    pub code: Option<i32>,
    pub lines: Vec<String>,
    pub encoder: String,
    pub seconds: f64,
}

pub fn file_name(c: &Clip) -> String {
    format!("{}-{}-{}x{}-{}.mp4", c.label, c.codec, c.width, c.height, c.fps)
}

/// Encodes a clip into `out_dir()`, writing the `.expect` side file and, for pattern clips, the
/// RGB reference of frame 60.
pub fn encode(c: &Clip) -> Encoded {
    let path = out_dir().join(file_name(c));
    let _ = std::fs::remove_file(&path);
    let args: Vec<String> = vec![
        "encode".into(),
        "--out".into(),
        path.to_string_lossy().into_owned(),
        "--width".into(),
        c.width.to_string(),
        "--height".into(),
        c.height.to_string(),
        "--fps".into(),
        c.fps.to_string(),
        "--codec".into(),
        c.codec.into(),
        "--bitrate".into(),
        c.bitrate.to_string(),
    ];
    let start = Instant::now();
    let mut helper = Helper::start(&args);
    let ready = helper.line(Duration::from_secs(20)).expect("ready line");
    let v = Json::parse(&ready).unwrap_or_else(|e| panic!("ready not JSON ({e}): {ready}"));
    let obj = v.obj().unwrap();
    assert_eq!(obj.get("ready").and_then(Json::bool), Some(true), "{ready}");
    assert_eq!(obj.get("protocol").and_then(Json::num), Some(1.0), "{ready}");
    let encoder = obj.get("encoder").and_then(Json::str).unwrap_or("?").to_string();
    let mut sent_samples = 0u64;
    for i in 0..c.frames {
        let frame = match c.content {
            Content::Pattern => to_protocol_bgra(&pattern_rgb(c.width, c.height, i), c.width, c.height),
            Content::Noise => noise_bgra(c.width, c.height, i),
        };
        if helper.send(1, &frame).is_err() {
            let (status, _) = helper.wait(Duration::from_secs(10));
            panic!("helper quit at frame {i}: exit {:?}, lines {:?}", status.code(), helper.seen);
        }
        let end = sample_at(i as u64 + 1, c.fps).min(c.samples);
        if end > sent_samples {
            let pcm = if c.tone { tone_left(sent_samples, end) } else { silence(sent_samples, end) };
            helper.send(2, &pcm).expect("send sound");
            sent_samples = end;
        }
        helper.drain_lines();
    }
    if c.samples > sent_samples {
        let pcm = if c.tone { tone_left(sent_samples, c.samples) } else { silence(sent_samples, c.samples) };
        helper.send(2, &pcm).expect("send sound tail");
    }
    helper.send(3, &[]).expect("send finish");
    helper.await_final_line(Duration::from_secs(300));
    helper.close_stdin();
    let (status, _) = helper.wait(Duration::from_secs(30));
    let seconds = start.elapsed().as_secs_f64();
    if matches!(c.content, Content::Pattern) && c.frames > 60 {
        std::fs::write(path.with_extension("ref60.rgb"), pattern_rgb(c.width, c.height, 60)).unwrap();
    }
    let mut expect = format!(
        "codec={}\nwidth={}\nheight={}\nfps={}\nframes={}\nbitrate={}\naudio_seconds={:.6}\ntone={}\nencoder={}\n",
        c.codec,
        c.width,
        c.height,
        c.fps,
        c.frames,
        c.bitrate,
        c.samples as f64 / 48_000.0,
        c.tone as u8,
        encoder
    );
    if matches!(c.content, Content::Pattern) && c.frames > 60 {
        expect.push_str("ref60=1\n");
    }
    if matches!(c.content, Content::Noise) {
        expect.push_str(&format!("rate_group={}-{}x{}-{}\n", c.codec, c.width, c.height, c.fps));
    }
    std::fs::write(path.with_extension("expect"), expect).unwrap();
    Encoded { path, code: status.code(), lines: helper.seen.clone(), encoder, seconds }
}

pub fn last_line(lines: &[String]) -> &str {
    lines.last().map(String::as_str).unwrap_or("")
}

pub fn ffprobe_available() -> bool {
    Command::new("ffprobe").arg("-version").stdout(Stdio::null()).stderr(Stdio::null()).status().map(|s| s.success()).unwrap_or(false)
}

/// `ffprobe` one entry of one stream, e.g. ("v:0", "width").
pub fn ffprobe_entry(path: &Path, stream: &str, entry: &str) -> String {
    let out = Command::new("ffprobe")
        .args(["-v", "error", "-select_streams", stream, "-show_entries"])
        .arg(format!("stream={entry}"))
        .args(["-of", "default=nw=1:nk=1"])
        .arg(path)
        .output()
        .expect("ffprobe");
    String::from_utf8_lossy(&out.stdout).trim().to_string()
}
