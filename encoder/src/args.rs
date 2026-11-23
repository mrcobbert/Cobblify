//! Command-line parsing. Every rejection carries a short error code that goes to stdout as
//! `{"error":"<code>"}` with exit code 2.

use std::ffi::OsString;
use std::path::PathBuf;

/// The four export sizes the mod offers (16:9).
pub const PRESET_SIZES: [(u32, u32); 4] = [(1280, 720), (1920, 1080), (2560, 1440), (3840, 2160)];
/// The four export frame rates the mod offers.
pub const RATES: [u32; 4] = [30, 60, 120, 240];
/// Smallest and largest accepted width or height.
pub const MIN_SIDE: u32 = 128;
pub const MAX_SIDE: u32 = 8192;
/// Largest accepted bit rate in bits per second.
pub const MAX_BITRATE: u32 = 400_000_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Codec {
    H264,
    Hevc,
}

impl Codec {
    pub const ALL: [Codec; 2] = [Codec::H264, Codec::Hevc];
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncodeArgs {
    pub out: PathBuf,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub codec: Codec,
    pub bitrate: u32,
}

impl EncodeArgs {
    /// Bytes in one BGRA frame.
    pub fn frame_len(&self) -> usize {
        self.width as usize * self.height as usize * 4
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Command {
    Probe { sizes: Vec<(u32, u32)> },
    Encode(EncodeArgs),
}

/// A rejected command line. The string is the error code.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ArgError(pub &'static str);

/// Parses the arguments after the program name.
pub fn parse<I: IntoIterator<Item = OsString>>(args: I) -> Result<Command, ArgError> {
    let mut it = args.into_iter();
    let command = it.next().ok_or(ArgError("no_command"))?;
    match command.to_str() {
        Some("probe") => parse_probe(it),
        Some("encode") => parse_encode(it).map(Command::Encode),
        _ => Err(ArgError("unknown_command")),
    }
}

fn parse_probe<I: Iterator<Item = OsString>>(mut it: I) -> Result<Command, ArgError> {
    let mut sizes = Vec::new();
    while let Some(flag) = it.next() {
        match flag.to_str() {
            Some("--size") => {
                let value = it.next().ok_or(ArgError("missing_value"))?;
                sizes.push(parse_size(value.to_str().ok_or(ArgError("bad_size"))?)?);
            }
            _ => return Err(ArgError("unknown_flag")),
        }
    }
    Ok(Command::Probe { sizes })
}

fn parse_encode<I: Iterator<Item = OsString>>(mut it: I) -> Result<EncodeArgs, ArgError> {
    const FLAGS: [&str; 6] = ["--out", "--width", "--height", "--fps", "--codec", "--bitrate"];
    let mut values: [Option<OsString>; 6] = Default::default();
    while let Some(flag) = it.next() {
        let slot = flag
            .to_str()
            .and_then(|f| FLAGS.iter().position(|known| *known == f))
            .ok_or(ArgError("unknown_flag"))?;
        let value = it.next().ok_or(ArgError("missing_value"))?;
        if values[slot].is_some() {
            return Err(ArgError("duplicate_flag"));
        }
        values[slot] = Some(value);
    }
    let [out, width, height, fps, codec, bitrate] = values;
    let (out, width, height, fps, codec, bitrate) = (
        out.ok_or(ArgError("missing_flag"))?,
        width.ok_or(ArgError("missing_flag"))?,
        height.ok_or(ArgError("missing_flag"))?,
        fps.ok_or(ArgError("missing_flag"))?,
        codec.ok_or(ArgError("missing_flag"))?,
        bitrate.ok_or(ArgError("missing_flag"))?,
    );

    let out = PathBuf::from(out);
    let is_mp4 = out.extension().and_then(|e| e.to_str()).is_some_and(|e| e.eq_ignore_ascii_case("mp4"));
    if !out.is_absolute() || !is_mp4 {
        return Err(ArgError("bad_out"));
    }
    let side = |v: &OsString| v.to_str().and_then(parse_digits).filter(|n| side_ok(*n)).ok_or(ArgError("bad_size"));
    let width = side(&width)?;
    let height = side(&height)?;
    let fps = fps
        .to_str()
        .and_then(parse_digits)
        .filter(|n| RATES.contains(n))
        .ok_or(ArgError("bad_fps"))?;
    let codec = match codec.to_str() {
        Some("h264") => Codec::H264,
        Some("hevc") => Codec::Hevc,
        _ => return Err(ArgError("bad_codec")),
    };
    let bitrate = bitrate
        .to_str()
        .and_then(parse_digits)
        .filter(|n| (1..=MAX_BITRATE).contains(n))
        .ok_or(ArgError("bad_bitrate"))?;
    Ok(EncodeArgs { out, width, height, fps, codec, bitrate })
}

/// Parses `WxH`; both sides even and inside `MIN_SIDE..=MAX_SIDE`.
pub fn parse_size(text: &str) -> Result<(u32, u32), ArgError> {
    let (w, h) = text.split_once('x').ok_or(ArgError("bad_size"))?;
    match (parse_digits(w), parse_digits(h)) {
        (Some(w), Some(h)) if side_ok(w) && side_ok(h) => Ok((w, h)),
        _ => Err(ArgError("bad_size")),
    }
}

/// Plain decimal digits only (no sign, no spaces), at most nine of them.
fn parse_digits(text: &str) -> Option<u32> {
    if text.is_empty() || text.len() > 9 || !text.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    text.parse().ok()
}

fn side_ok(n: u32) -> bool {
    n % 2 == 0 && (MIN_SIDE..=MAX_SIDE).contains(&n)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(list: &[&str]) -> Vec<OsString> {
        list.iter().map(OsString::from).collect()
    }

    fn out() -> String {
        if cfg!(windows) {
            "C:\\Users\\x\\Videos\\Cobblify\\a b.partial.mp4".to_string()
        } else {
            "/Users/x/Movies/Cobblify/a b.partial.mp4".to_string()
        }
    }

    fn encode_line(extra: &[(&str, &str)]) -> Vec<OsString> {
        let o = out();
        let mut base: Vec<(&str, &str)> = vec![
            ("--out", o.as_str()),
            ("--width", "1920"),
            ("--height", "1080"),
            ("--fps", "60"),
            ("--codec", "h264"),
            ("--bitrate", "16000000"),
        ];
        for (k, v) in extra {
            if let Some(slot) = base.iter_mut().find(|(bk, _)| bk == k) {
                slot.1 = v;
            } else {
                base.push((k, v));
            }
        }
        let mut list = vec![OsString::from("encode")];
        for (k, v) in base {
            list.push(OsString::from(k));
            list.push(OsString::from(v));
        }
        list
    }

    #[test]
    fn encode_parses_every_flag() {
        let cmd = parse(encode_line(&[])).unwrap();
        assert_eq!(
            cmd,
            Command::Encode(EncodeArgs {
                out: PathBuf::from(out()),
                width: 1920,
                height: 1080,
                fps: 60,
                codec: Codec::H264,
                bitrate: 16_000_000,
            })
        );
        let cmd = parse(encode_line(&[("--codec", "hevc"), ("--fps", "240"), ("--width", "1366"), ("--height", "768")])).unwrap();
        match cmd {
            Command::Encode(a) => {
                assert_eq!((a.codec, a.fps, a.width, a.height), (Codec::Hevc, 240, 1366, 768));
                assert_eq!(a.frame_len(), 1366 * 768 * 4);
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn encode_flags_in_any_order() {
        let o = out();
        let cmd = parse(args(&[
            "encode", "--bitrate", "8000000", "--codec", "hevc", "--fps", "30", "--height", "720", "--width",
            "1280", "--out", &o,
        ]))
        .unwrap();
        assert!(matches!(cmd, Command::Encode(EncodeArgs { width: 1280, height: 720, fps: 30, .. })));
    }

    #[test]
    fn unknown_flag_is_rejected() {
        assert_eq!(parse(encode_line(&[("--crf", "20")])), Err(ArgError("unknown_flag")));
        assert_eq!(parse(args(&["probe", "--fast"])), Err(ArgError("unknown_flag")));
    }

    #[test]
    fn odd_or_out_of_range_sizes_are_rejected() {
        assert_eq!(parse(encode_line(&[("--width", "1921")])), Err(ArgError("bad_size")));
        assert_eq!(parse(encode_line(&[("--height", "1081")])), Err(ArgError("bad_size")));
        assert_eq!(parse(encode_line(&[("--width", "0")])), Err(ArgError("bad_size")));
        assert_eq!(parse(encode_line(&[("--width", "16")])), Err(ArgError("bad_size")));
        assert_eq!(parse(encode_line(&[("--width", "9000")])), Err(ArgError("bad_size")));
        assert_eq!(parse(encode_line(&[("--width", "-2")])), Err(ArgError("bad_size")));
        assert_eq!(parse(encode_line(&[("--width", "abc")])), Err(ArgError("bad_size")));
    }

    #[test]
    fn fps_outside_the_four_rates_is_rejected() {
        for bad in ["0", "25", "59", "144", "300", "x"] {
            assert_eq!(parse(encode_line(&[("--fps", bad)])), Err(ArgError("bad_fps")), "{bad}");
        }
        for good in ["30", "60", "120", "240"] {
            assert!(parse(encode_line(&[("--fps", good)])).is_ok(), "{good}");
        }
    }

    #[test]
    fn bitrate_zero_or_huge_is_rejected() {
        assert_eq!(parse(encode_line(&[("--bitrate", "0")])), Err(ArgError("bad_bitrate")));
        assert_eq!(parse(encode_line(&[("--bitrate", "-5")])), Err(ArgError("bad_bitrate")));
        assert_eq!(parse(encode_line(&[("--bitrate", "400000001")])), Err(ArgError("bad_bitrate")));
        assert_eq!(parse(encode_line(&[("--bitrate", "1.5")])), Err(ArgError("bad_bitrate")));
    }

    #[test]
    fn codec_must_be_h264_or_hevc() {
        assert_eq!(parse(encode_line(&[("--codec", "av1")])), Err(ArgError("bad_codec")));
        assert_eq!(parse(encode_line(&[("--codec", "H264")])), Err(ArgError("bad_codec")));
    }

    #[test]
    fn missing_duplicate_and_valueless_flags_are_rejected() {
        let mut line = encode_line(&[]);
        line.truncate(line.len() - 2); // drop --bitrate
        assert_eq!(parse(line), Err(ArgError("missing_flag")));
        let mut line = encode_line(&[]);
        line.push(OsString::from("--fps"));
        line.push(OsString::from("60"));
        assert_eq!(parse(line), Err(ArgError("duplicate_flag")));
        let mut line = encode_line(&[]);
        line.push(OsString::from("--fps"));
        assert_eq!(parse(line), Err(ArgError("missing_value")));
    }

    #[test]
    fn out_must_be_an_absolute_mp4_path() {
        assert_eq!(parse(encode_line(&[("--out", "relative.mp4")])), Err(ArgError("bad_out")));
        let not_mp4 = if cfg!(windows) { "C:\\x\\a.mov" } else { "/x/a.mov" };
        assert_eq!(parse(encode_line(&[("--out", not_mp4)])), Err(ArgError("bad_out")));
        let upper = if cfg!(windows) { "C:\\x\\A.MP4" } else { "/x/A.MP4" };
        assert!(parse(encode_line(&[("--out", upper)])).is_ok());
    }

    #[test]
    fn commands() {
        assert_eq!(parse(args(&[])), Err(ArgError("no_command")));
        assert_eq!(parse(args(&["transcode"])), Err(ArgError("unknown_command")));
        assert_eq!(parse(args(&["probe"])), Ok(Command::Probe { sizes: vec![] }));
        assert_eq!(
            parse(args(&["probe", "--size", "1366x768", "--size", "1920x1080"])),
            Ok(Command::Probe { sizes: vec![(1366, 768), (1920, 1080)] })
        );
        assert_eq!(parse(args(&["probe", "--size", "1367x768"])), Err(ArgError("bad_size")));
        assert_eq!(parse(args(&["probe", "--size", "16x16"])), Err(ArgError("bad_size")));
        assert_eq!(parse(args(&["probe", "--size"])), Err(ArgError("missing_value")));
    }

    #[test]
    fn size_text() {
        assert_eq!(parse_size("1366x768"), Ok((1366, 768)));
        assert_eq!(parse_size("128x128"), Ok((128, 128)));
        assert_eq!(parse_size("8192x8192"), Ok((8192, 8192)));
        for bad in ["1366", "x768", "1366x", "1366X768", "1366x768x2", "126x128", "8194x128", "+1366x768", " 1366x768"] {
            assert_eq!(parse_size(bad), Err(ArgError("bad_size")), "{bad}");
        }
    }
}
