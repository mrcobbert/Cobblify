//! Bad command lines exit 2 with an error code and leave no file (U6). No encoder is opened.

mod common;

use common::*;

fn encode_args(out: &str, over: &[(&str, &str)]) -> Vec<String> {
    let mut flags: Vec<(String, String)> = vec![
        ("--out".into(), out.into()),
        ("--width".into(), "640".into()),
        ("--height".into(), "360".into()),
        ("--fps".into(), "60".into()),
        ("--codec".into(), "h264".into()),
        ("--bitrate".into(), "4000000".into()),
    ];
    for (k, v) in over {
        match flags.iter_mut().find(|(fk, _)| fk == k) {
            Some(slot) => slot.1 = v.to_string(),
            None => flags.push((k.to_string(), v.to_string())),
        }
    }
    let mut args = vec!["encode".to_string()];
    for (k, v) in flags {
        args.push(k);
        args.push(v);
    }
    args
}

#[test]
fn bad_arguments_exit_2_with_a_code_and_no_file() {
    let dir = scratch("cli-bad-args");
    let out = dir.join("x.partial.mp4");
    let out_s = out.to_string_lossy().into_owned();
    let cases: &[(&[(&str, &str)], &str)] = &[
        (&[("--crf", "20")], "unknown_flag"),
        (&[("--width", "641")], "bad_size"),
        (&[("--height", "361")], "bad_size"),
        (&[("--fps", "50")], "bad_fps"),
        (&[("--fps", "144")], "bad_fps"),
        (&[("--bitrate", "0")], "bad_bitrate"),
        (&[("--codec", "vp9")], "bad_codec"),
    ];
    for (over, code) in cases {
        let args = encode_args(&out_s, over);
        let argv: Vec<&str> = args.iter().map(String::as_str).collect();
        let (exit, lines) = run(&argv);
        assert_eq!(exit, Some(2), "{over:?}: {lines:?}");
        assert_eq!(lines, vec![format!(r#"{{"error":"{code}"}}"#)], "{over:?}");
        assert!(!out.exists(), "{over:?} left a file");
    }
    assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 0, "scratch dir stays empty");
}

#[test]
fn no_command_or_unknown_command_exits_2() {
    assert_eq!(run(&[]), (Some(2), vec![r#"{"error":"no_command"}"#.to_string()]));
    assert_eq!(run(&["play"]), (Some(2), vec![r#"{"error":"unknown_command"}"#.to_string()]));
}
