//! The stdin packet stream and the stdout status lines.
//!
//! Packet: `u8 kind, u32 little-endian length, payload`.
//! Kind 1 = one frame, BGRA, bottom row first, exactly `width x height x 4` bytes.
//! Kind 2 = sound, s16 little-endian stereo 48 kHz, a whole number of 4-byte sample frames.
//! Kind 3 = finish, empty payload.
//! Input that ends anywhere without kind 3 is a cancel, and so is input that closes after kind 3
//! but before the final `done` or `error` line (the caller keeps stdin open until then).

use std::io::{self, Read};

pub const PROTOCOL: u32 = 1;

pub const KIND_FRAME: u8 = 1;
pub const KIND_SOUND: u8 = 2;
pub const KIND_FINISH: u8 = 3;

/// Sound sample rate and bytes per stereo sample frame.
pub const SAMPLE_RATE: u32 = 48_000;
pub const SOUND_FRAME_BYTES: usize = 4;
/// Largest sound packet: ten seconds.
pub const MAX_SOUND_BYTES: u32 = SAMPLE_RATE * SOUND_FRAME_BYTES as u32 * 10;

#[derive(Debug, PartialEq, Eq)]
pub enum Packet<'a> {
    Frame(&'a [u8]),
    Sound(&'a [u8]),
    Finish,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReadError {
    /// Input ended before a finish packet.
    Cancelled,
    /// A kind byte that is not 1, 2 or 3.
    BadKind(u8),
    /// A length that does not fit the kind.
    BadLength { kind: u8, len: u32 },
    /// Reading stdin failed for another reason.
    Io(io::ErrorKind),
}

impl ReadError {
    pub fn code(self) -> &'static str {
        match self {
            ReadError::Cancelled => "cancelled",
            ReadError::BadKind(_) => "bad_packet",
            ReadError::BadLength { .. } => "bad_packet",
            ReadError::Io(_) => "read_failed",
        }
    }
}

pub struct PacketReader<R> {
    inner: R,
    frame_len: usize,
    buf: Vec<u8>,
}

impl<R: Read> PacketReader<R> {
    pub fn new(inner: R, frame_len: usize) -> Self {
        PacketReader { inner, frame_len, buf: Vec::new() }
    }

    /// The input underneath (to read on after the last packet).
    pub fn get_mut(&mut self) -> &mut R {
        &mut self.inner
    }

    /// Reads the next packet. The returned slice is valid until the next call.
    pub fn next(&mut self) -> Result<Packet<'_>, ReadError> {
        let mut header = [0u8; 5];
        fill(&mut self.inner, &mut header)?;
        let kind = header[0];
        let len = u32::from_le_bytes([header[1], header[2], header[3], header[4]]);
        let ok = match kind {
            KIND_FRAME => len as usize == self.frame_len,
            KIND_SOUND => len <= MAX_SOUND_BYTES && len as usize % SOUND_FRAME_BYTES == 0,
            KIND_FINISH => len == 0,
            other => return Err(ReadError::BadKind(other)),
        };
        if !ok {
            return Err(ReadError::BadLength { kind, len });
        }
        self.buf.resize(len as usize, 0);
        fill(&mut self.inner, &mut self.buf)?;
        Ok(match kind {
            KIND_FRAME => Packet::Frame(&self.buf),
            KIND_SOUND => Packet::Sound(&self.buf),
            _ => Packet::Finish,
        })
    }
}

/// `read_exact` with end of input (anywhere) and a broken pipe mapped to a cancel.
fn fill<R: Read>(inner: &mut R, out: &mut [u8]) -> Result<(), ReadError> {
    let mut done = 0;
    while done < out.len() {
        match inner.read(&mut out[done..]) {
            Ok(0) => return Err(ReadError::Cancelled),
            Ok(n) => done += n,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
            Err(e) if matches!(e.kind(), io::ErrorKind::BrokenPipe | io::ErrorKind::UnexpectedEof) => {
                return Err(ReadError::Cancelled)
            }
            Err(e) => return Err(ReadError::Io(e.kind())),
        }
    }
    Ok(())
}

/// Appends `text` to `out` as a JSON string literal.
pub fn push_json_string(out: &mut String, text: &str) {
    out.push('"');
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

pub fn ready_line(encoder: &str) -> String {
    let mut s = format!("{{\"ready\":true,\"protocol\":{PROTOCOL},\"encoder\":");
    push_json_string(&mut s, encoder);
    s.push('}');
    s
}

pub fn frames_line(frames: u64) -> String {
    format!("{{\"frames\":{frames}}}")
}

pub fn done_line(frames: u64) -> String {
    format!("{{\"done\":true,\"frames\":{frames}}}")
}

/// `status` is an OS status number (HRESULT, OSStatus, NSError code) when one is known.
pub fn error_line(code: &str, status: Option<i64>) -> String {
    let mut s = String::from("{\"error\":");
    push_json_string(&mut s, code);
    if let Some(status) = status {
        s.push_str(&format!(",\"status\":{status}"));
    }
    s.push('}');
    s
}

/// One probe row: width, height, frame rate.
pub type Row = (u32, u32, u32);

pub fn probe_line(videos_dir: Option<&str>, h264: &[Row], hevc: &[Row]) -> String {
    let mut s = format!("{{\"protocol\":{PROTOCOL},\"videosDir\":");
    match videos_dir {
        Some(dir) => push_json_string(&mut s, dir),
        None => s.push_str("null"),
    }
    for (key, rows) in [("h264", h264), ("hevc", hevc)] {
        s.push_str(&format!(",\"{key}\":["));
        let cells: Vec<String> = rows.iter().map(|(w, h, f)| format!("[{w},{h},{f}]")).collect();
        s.push_str(&cells.join(","));
        s.push(']');
    }
    s.push('}');
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    fn packet(kind: u8, payload: &[u8]) -> Vec<u8> {
        let mut v = vec![kind];
        v.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        v.extend_from_slice(payload);
        v
    }

    /// Hands out at most `step` bytes per read call.
    struct Trickle {
        data: Vec<u8>,
        pos: usize,
        step: usize,
    }

    impl Read for Trickle {
        fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
            let n = self.step.min(out.len()).min(self.data.len() - self.pos);
            out[..n].copy_from_slice(&self.data[self.pos..self.pos + n]);
            self.pos += n;
            Ok(n)
        }
    }

    /// Fails with `Interrupted` before every successful read.
    struct Interrupting {
        data: Vec<u8>,
        pos: usize,
        flip: bool,
    }

    impl Read for Interrupting {
        fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
            self.flip = !self.flip;
            if self.flip {
                return Err(io::Error::from(io::ErrorKind::Interrupted));
            }
            let n = 3.min(out.len()).min(self.data.len() - self.pos);
            out[..n].copy_from_slice(&self.data[self.pos..self.pos + n]);
            self.pos += n;
            Ok(n)
        }
    }

    fn stream() -> (Vec<u8>, Vec<u8>, Vec<u8>) {
        let frame: Vec<u8> = (0..4 * 4 * 2).map(|i| i as u8).collect(); // 4x2 frame
        let sound: Vec<u8> = (0..12).map(|i| 200 - i as u8).collect(); // 3 sample frames
        let mut bytes = packet(KIND_FRAME, &frame);
        bytes.extend(packet(KIND_SOUND, &sound));
        bytes.extend(packet(KIND_SOUND, &[]));
        bytes.extend(packet(KIND_FRAME, &frame));
        bytes.extend(packet(KIND_FINISH, &[]));
        (bytes, frame, sound)
    }

    fn read_all<R: Read>(reader: &mut PacketReader<R>, frame: &[u8], sound: &[u8]) {
        assert_eq!(reader.next().unwrap(), Packet::Frame(frame));
        assert_eq!(reader.next().unwrap(), Packet::Sound(sound));
        assert_eq!(reader.next().unwrap(), Packet::Sound(&[]));
        assert_eq!(reader.next().unwrap(), Packet::Frame(frame));
        assert_eq!(reader.next().unwrap(), Packet::Finish);
    }

    #[test]
    fn reads_whole_packets() {
        let (bytes, frame, sound) = stream();
        let mut r = PacketReader::new(&bytes[..], 32);
        read_all(&mut r, &frame, &sound);
    }

    #[test]
    fn split_reads_give_the_same_packets() {
        let (bytes, frame, sound) = stream();
        for step in [1, 2, 3, 5, 7, 31] {
            let mut r = PacketReader::new(Trickle { data: bytes.clone(), pos: 0, step }, 32);
            read_all(&mut r, &frame, &sound);
        }
        let mut r = PacketReader::new(Interrupting { data: bytes, pos: 0, flip: false }, 32);
        read_all(&mut r, &frame, &sound);
    }

    #[test]
    fn frame_of_the_wrong_length_is_an_error() {
        let bytes = packet(KIND_FRAME, &[0u8; 28]);
        let mut r = PacketReader::new(&bytes[..], 32);
        assert_eq!(r.next(), Err(ReadError::BadLength { kind: KIND_FRAME, len: 28 }));
        let bytes = packet(KIND_FRAME, &[0u8; 36]);
        let mut r = PacketReader::new(&bytes[..], 32);
        assert_eq!(r.next(), Err(ReadError::BadLength { kind: KIND_FRAME, len: 36 }));
        assert_eq!(ReadError::BadLength { kind: 1, len: 36 }.code(), "bad_packet");
    }

    #[test]
    fn huge_frame_length_is_rejected_before_reading_it() {
        let mut bytes = vec![KIND_FRAME];
        bytes.extend_from_slice(&u32::MAX.to_le_bytes());
        let mut r = PacketReader::new(&bytes[..], 32);
        assert_eq!(r.next(), Err(ReadError::BadLength { kind: KIND_FRAME, len: u32::MAX }));
    }

    #[test]
    fn sound_must_be_whole_sample_frames_and_bounded() {
        let bytes = packet(KIND_SOUND, &[0u8; 6]);
        let mut r = PacketReader::new(&bytes[..], 32);
        assert_eq!(r.next(), Err(ReadError::BadLength { kind: KIND_SOUND, len: 6 }));
        let mut bytes = vec![KIND_SOUND];
        bytes.extend_from_slice(&(MAX_SOUND_BYTES + 4).to_le_bytes());
        let mut r = PacketReader::new(&bytes[..], 32);
        assert_eq!(r.next(), Err(ReadError::BadLength { kind: KIND_SOUND, len: MAX_SOUND_BYTES + 4 }));
    }

    #[test]
    fn finish_with_payload_and_unknown_kinds_are_errors() {
        let bytes = packet(KIND_FINISH, &[1]);
        let mut r = PacketReader::new(&bytes[..], 32);
        assert_eq!(r.next(), Err(ReadError::BadLength { kind: KIND_FINISH, len: 1 }));
        for kind in [0u8, 4, 255] {
            let bytes = packet(kind, &[]);
            let mut r = PacketReader::new(&bytes[..], 32);
            assert_eq!(r.next(), Err(ReadError::BadKind(kind)));
        }
        assert_eq!(ReadError::BadKind(9).code(), "bad_packet");
    }

    #[test]
    fn end_of_input_without_finish_is_a_cancel() {
        let (bytes, frame, _) = stream();
        let finish_at = bytes.len() - 5;
        // Clean end after a packet.
        let mut r = PacketReader::new(&bytes[..finish_at], 32);
        for _ in 0..4 {
            r.next().unwrap();
        }
        assert_eq!(r.next(), Err(ReadError::Cancelled));
        // Empty input.
        let mut r = PacketReader::new(&[][..], 32);
        assert_eq!(r.next(), Err(ReadError::Cancelled));
        // Ends inside a header and inside a payload.
        for cut in [1, 3, 5, 6, 20, 36] {
            let mut r = PacketReader::new(&bytes[..cut], 32);
            assert_eq!(r.next(), Err(ReadError::Cancelled), "cut {cut}");
        }
        let mut r = PacketReader::new(Trickle { data: bytes[..40].to_vec(), pos: 0, step: 3 }, 32);
        assert_eq!(r.next().unwrap(), Packet::Frame(&frame));
        assert_eq!(r.next(), Err(ReadError::Cancelled));
        assert_eq!(ReadError::Cancelled.code(), "cancelled");
    }

    #[test]
    fn broken_pipe_counts_as_a_cancel() {
        struct Broken;
        impl Read for Broken {
            fn read(&mut self, _: &mut [u8]) -> io::Result<usize> {
                Err(io::Error::from(io::ErrorKind::BrokenPipe))
            }
        }
        let mut r = PacketReader::new(Broken, 32);
        assert_eq!(r.next(), Err(ReadError::Cancelled));
        struct Denied;
        impl Read for Denied {
            fn read(&mut self, _: &mut [u8]) -> io::Result<usize> {
                Err(io::Error::from(io::ErrorKind::PermissionDenied))
            }
        }
        let mut r = PacketReader::new(Denied, 32);
        assert_eq!(r.next(), Err(ReadError::Io(io::ErrorKind::PermissionDenied)));
        assert_eq!(ReadError::Io(io::ErrorKind::Other).code(), "read_failed");
    }

    #[test]
    fn status_lines() {
        assert_eq!(ready_line("software"), r#"{"ready":true,"protocol":1,"encoder":"software"}"#);
        assert_eq!(frames_line(7), r#"{"frames":7}"#);
        assert_eq!(done_line(120), r#"{"done":true,"frames":120}"#);
        assert_eq!(error_line("open_failed", None), r#"{"error":"open_failed"}"#);
        assert_eq!(error_line("open_failed", Some(-2147024893)), r#"{"error":"open_failed","status":-2147024893}"#);
    }

    #[test]
    fn json_strings_are_escaped() {
        let mut s = String::new();
        push_json_string(&mut s, "C:\\Users\\a \"b\"\n\u{1}é");
        assert_eq!(s, "\"C:\\\\Users\\\\a \\\"b\\\"\\n\\u0001é\"");
    }

    #[test]
    fn probe_line_shape() {
        assert_eq!(
            probe_line(Some("C:\\Users\\a\\Videos"), &[(1280, 720, 30), (1280, 720, 60)], &[]),
            r#"{"protocol":1,"videosDir":"C:\\Users\\a\\Videos","h264":[[1280,720,30],[1280,720,60]],"hevc":[]}"#
        );
        assert_eq!(
            probe_line(None, &[], &[(1920, 1080, 240)]),
            r#"{"protocol":1,"videosDir":null,"h264":[],"hevc":[[1920,1080,240]]}"#
        );
    }
}
