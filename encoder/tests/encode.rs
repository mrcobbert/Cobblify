//! End-to-end tests against the real OS encoder. Output files stay in `out_dir()` (with `.expect`
//! side files) so `tests/verify.sh` can check them with ffprobe/ffmpeg, also when they were made on
//! Windows and copied to the Mac.

mod common;

use common::*;
use std::path::Path;
use std::time::{Duration, Instant};

const DONE_120: &str = r#"{"done":true,"frames":120}"#;

fn pattern_clip<'a>(label: &'a str, codec: &'a str, w: u32, h: u32, bitrate: u32) -> Clip<'a> {
    Clip {
        label,
        codec,
        width: w,
        height: h,
        fps: 60,
        bitrate,
        frames: 120,
        samples: 120 * 800,
        content: Content::Pattern,
        tone: true,
    }
}

#[test]
fn pattern_clips_encode_with_every_codec_the_probe_lists() {
    let _g = serial();
    let probe = base_probe();
    assert!(!probe.h264.is_empty(), "the probe lists H.264");
    for codec in probe.codecs() {
        for (w, h, rate) in [(640u32, 360u32, 6_000_000u32), (1920, 1080, 24_000_000)] {
            let r = encode(&pattern_clip("pattern", codec, w, h, rate));
            println!(
                "pattern {codec} {w}x{h}: exit {:?}, encoder {}, {:.1} frames/s end to end",
                r.code,
                r.encoder,
                120.0 / r.seconds
            );
            assert_eq!(r.code, Some(0), "{codec} {w}x{h}: {:?}", r.lines);
            assert_eq!(last_line(&r.lines), DONE_120, "{codec} {w}x{h}");
            assert!(r.lines.iter().any(|l| l == r#"{"frames":120}"#), "progress lines: {:?}", r.lines);
            assert!(r.path.is_file(), "{} exists", r.path.display());
            let name = r.path.file_name().unwrap().to_string_lossy().into_owned();
            let leftovers: Vec<String> = std::fs::read_dir(out_dir())
                .unwrap()
                .flatten()
                .map(|e| e.file_name().to_string_lossy().into_owned())
                .filter(|n| n.starts_with(&name) && n != &name)
                .collect();
            assert!(leftovers.is_empty(), "staging files left: {leftovers:?}");
            if ffprobe_available() {
                assert_eq!(ffprobe_entry(&r.path, "v:0", "width"), w.to_string());
                assert_eq!(ffprobe_entry(&r.path, "v:0", "height"), h.to_string());
            }
        }
    }
    if probe.hevc.is_empty() {
        println!("NOTE: the probe lists no HEVC encoder on this machine; HEVC clips skipped");
    }
}

#[test]
fn closing_input_after_30_frames_cancels_within_2_s_and_deletes_the_file() {
    let _g = serial();
    let dir = scratch("cancel");
    let out = dir.join("cancel.partial.mp4");
    let args: Vec<String> = [
        "encode", "--out", &out.to_string_lossy(), "--width", "640", "--height", "360", "--fps", "60",
        "--codec", "h264", "--bitrate", "4000000",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    let mut helper = Helper::start(&args);
    let ready = helper.line(Duration::from_secs(20)).expect("ready");
    assert!(ready.starts_with(r#"{"ready":true"#), "{ready}");
    for i in 0..30u32 {
        helper.send(1, &to_protocol_bgra(&pattern_rgb(640, 360, i), 640, 360)).unwrap();
        helper.send(2, &tone_left(i as u64 * 800, (i as u64 + 1) * 800)).unwrap();
    }
    std::thread::sleep(Duration::from_millis(300));
    helper.close_stdin();
    let (status, took) = helper.wait(Duration::from_secs(10));
    println!("cancel: exit {:?} after {:?}", status.code(), took);
    assert_eq!(status.code(), Some(3), "{:?}", helper.seen);
    assert!(took <= Duration::from_secs(2), "exit took {took:?}");
    assert!(!out.exists(), "partial file removed");
    assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 0, "nothing left in the folder");
}

#[test]
fn closing_input_after_finish_but_before_done_cancels_and_deletes_the_file() {
    let _g = serial();
    let dir = scratch("cancel-finishing");
    let out = dir.join("finishing.partial.mp4");
    let args: Vec<String> = [
        "encode", "--out", &out.to_string_lossy(), "--width", "1920", "--height", "1080", "--fps", "60",
        "--codec", "h264", "--bitrate", "16000000",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    let mut helper = Helper::start(&args);
    helper.line(Duration::from_secs(20)).expect("ready");
    for i in 0..60u32 {
        helper.send(1, &to_protocol_bgra(&pattern_rgb(1920, 1080, i), 1920, 1080)).unwrap();
        helper.send(2, &tone_left(i as u64 * 800, (i as u64 + 1) * 800)).unwrap();
    }
    helper.send(3, &[]).unwrap();
    helper.close_stdin();
    let (status, took) = helper.wait(Duration::from_secs(10));
    println!("cancel while finishing: exit {:?} after {:?}", status.code(), took);
    assert_eq!(status.code(), Some(3), "{:?}", helper.seen);
    assert!(took <= Duration::from_secs(2), "exit took {took:?}");
    assert!(!helper.seen.iter().any(|l| l.contains("\"done\"")), "no done line");
    assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 0, "nothing left in the folder");
}

/// Code review 1 I2 (TASK X3): the native finalization blocks for 5 s without looking at the
/// input (the test hook `COBBLIFY_ENCODE_TEST_FINISH_STALL_MS` holds it, as a slow
/// `finishWriting` or `Finalize` would) and the input closes 300 ms into it: the helper still
/// exits within 2 s and leaves nothing behind, also on Windows where the writer holds the file.
#[test]
fn closing_input_during_a_slow_native_finish_exits_within_2_s_and_deletes_the_file() {
    let _g = serial();
    let dir = scratch("cancel-slow-finish");
    let out = dir.join("slow.partial.mp4");
    let args: Vec<String> = [
        "encode", "--out", &out.to_string_lossy(), "--width", "640", "--height", "360", "--fps", "60",
        "--codec", "h264", "--bitrate", "4000000",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    let mut helper = Helper::start_env(&args, &[("COBBLIFY_ENCODE_TEST_FINISH_STALL_MS", "5000")]);
    helper.line(Duration::from_secs(20)).expect("ready");
    for i in 0..30u32 {
        helper.send(1, &to_protocol_bgra(&pattern_rgb(640, 360, i), 640, 360)).unwrap();
        helper.send(2, &tone_left(i as u64 * 800, (i as u64 + 1) * 800)).unwrap();
    }
    helper.send(3, &[]).unwrap();
    std::thread::sleep(Duration::from_millis(300));
    helper.close_stdin();
    let (status, took) = helper.wait(Duration::from_secs(15));
    println!("cancel during a slow finish: exit {:?} after {:?}", status.code(), took);
    assert_eq!(status.code(), Some(3), "{:?}", helper.seen);
    assert!(took <= Duration::from_secs(2), "exit took {took:?}");
    assert!(!helper.seen.iter().any(|l| l.contains("\"done\"")), "no done line");
    assert!(!out.exists(), "partial file removed");
    assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 0, "nothing left in the folder");
}

/// Code review 2 I2 (TASK X3): every frame write blocks for 10 s without looking at the input (the
/// test hook `COBBLIFY_ENCODE_TEST_FRAME_STALL_MS`, as a readiness wait or a `WriteSample` that does
/// not return would) and the input closes while the first one is blocked: with nothing queued
/// behind it, and with frames queued behind it and more bytes still unread in the pipe (the game
/// crashed while the helper was behind). Either way the helper exits within 2 s with the cancel
/// code and leaves nothing behind.
#[test]
fn closing_input_while_a_frame_write_blocks_exits_within_2_s_and_deletes_the_file() {
    let _g = serial();
    for (case, frames, extra) in [("one frame", 1usize, 0usize), ("frames queued and bytes unread", 4, 1000)] {
        let dir = scratch(if frames == 1 { "cancel-stalled-frame" } else { "cancel-stalled-queue" });
        let out = dir.join("stalled.partial.mp4");
        let args: Vec<String> = [
            "encode", "--out", &out.to_string_lossy(), "--width", "640", "--height", "360", "--fps", "60",
            "--codec", "h264", "--bitrate", "4000000",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        let mut helper = Helper::start_env(&args, &[("COBBLIFY_ENCODE_TEST_FRAME_STALL_MS", "10000")]);
        helper.line(Duration::from_secs(20)).expect("ready");
        // The writes block once the helper stops taking input, so they run on their own thread, as
        // the game's writer does; the input closes when they are all written.
        let mut stdin = helper.take_stdin();
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            use std::io::Write;
            let frame = to_protocol_bgra(&pattern_rgb(640, 360, 0), 640, 360);
            let mut ok = true;
            for _ in 0..frames {
                ok &= stdin.write_all(&[1]).is_ok()
                    && stdin.write_all(&(frame.len() as u32).to_le_bytes()).is_ok()
                    && stdin.write_all(&frame).is_ok();
            }
            if extra > 0 {
                // the start of one more frame that never completes
                let mut partial = vec![1u8];
                partial.extend_from_slice(&(frame.len() as u32).to_le_bytes());
                partial.extend_from_slice(&frame[..extra - 5]);
                ok &= stdin.write_all(&partial).is_ok();
            }
            ok &= stdin.flush().is_ok();
            drop(stdin);
            let _ = tx.send((ok, Instant::now()));
        });
        let (written, closed_at) = rx
            .recv_timeout(Duration::from_secs(15))
            .unwrap_or_else(|_| panic!("{case}: the helper stopped taking input"));
        assert!(written, "{case}: the helper took the input");
        let (status, _) = helper.wait(Duration::from_secs(15));
        let took = closed_at.elapsed();
        println!("cancel while a frame write blocks ({case}): exit {:?} after {:?}", status.code(), took);
        assert_eq!(status.code(), Some(3), "{case}: {:?}", helper.seen);
        assert!(took <= Duration::from_secs(2), "{case}: exit took {took:?} after the input closed");
        assert!(!out.exists(), "{case}: partial file removed");
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 0, "{case}: nothing left in the folder");
    }
}

#[test]
fn a_frame_of_the_wrong_size_fails_with_exit_2_and_no_file() {
    let _g = serial();
    let dir = scratch("bad-frame");
    let out = dir.join("bad.partial.mp4");
    let args: Vec<String> = [
        "encode", "--out", &out.to_string_lossy(), "--width", "640", "--height", "360", "--fps", "30",
        "--codec", "h264", "--bitrate", "4000000",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    let mut helper = Helper::start(&args);
    helper.line(Duration::from_secs(20)).expect("ready");
    helper.send(1, &to_protocol_bgra(&pattern_rgb(640, 360, 0), 640, 360)).unwrap();
    let _ = helper.send(1, &vec![0u8; 640 * 360 * 4 - 4]);
    let (status, _) = helper.wait(Duration::from_secs(10));
    assert_eq!(status.code(), Some(2), "{:?}", helper.seen);
    assert_eq!(last_line(&helper.seen), r#"{"error":"bad_packet"}"#);
    assert!(!out.exists());
}

#[test]
fn unwritable_path_exits_2_and_leaves_no_file() {
    let _g = serial();
    let dir = scratch("unwritable");
    let out = dir.join("no-such-dir").join("sub").join("out.partial.mp4");
    let out_s = out.to_string_lossy().into_owned();
    let (code, lines) = run(&[
        "encode", "--out", &out_s, "--width", "640", "--height", "360", "--fps", "60", "--codec", "h264",
        "--bitrate", "4000000",
    ]);
    assert_eq!(code, Some(2), "{lines:?}");
    let last = lines.last().expect("an error line");
    assert!(last.starts_with(r#"{"error":"#), "{lines:?}");
    assert!(!lines.iter().any(|l| l.contains("\"ready\"")), "no ready line: {lines:?}");
    assert!(!out.exists());
    assert!(!dir.join("no-such-dir").exists(), "no folder created");
}

#[test]
fn probe_prints_one_line_with_the_videos_folder_and_h264_rows() {
    let _g = serial();
    let start = Instant::now();
    let (code, lines) = probe(&[]);
    println!("probe took {:?}: {}", start.elapsed(), lines.join(" | "));
    assert_eq!(code, Some(0));
    assert_eq!(lines.len(), 1, "{lines:?}");
    let p = parse_probe(&lines[0]);
    let dir = p.videos_dir.as_deref().expect("videosDir is a string");
    assert!(Path::new(dir).is_absolute(), "{dir}");
    assert!(Path::new(dir).is_dir(), "{dir} exists");
    assert!(!p.h264.is_empty());
    let presets = [(1280, 720), (1920, 1080), (2560, 1440), (3840, 2160)];
    for &(w, h, fps) in p.h264.iter().chain(p.hevc.iter()) {
        assert!(presets.contains(&(w, h)), "row size {w}x{h}");
        assert!([30, 60, 120, 240].contains(&fps), "row rate {fps}");
    }
    assert!(p.has("h264", 1920, 1080, 60), "1080p60 H.264 opens");
}

#[test]
fn probe_size_adds_rows_for_that_size_and_it_encodes() {
    let _g = serial();
    let (code, lines) = probe(&["1366x768"]);
    assert_eq!(code, Some(0), "{lines:?}");
    let p = parse_probe(&lines[0]);
    let rates: Vec<u32> = p.h264.iter().filter(|r| (r.0, r.1) == (1366, 768)).map(|r| r.2).collect();
    println!("1366x768 H.264 rates: {rates:?}");
    assert!(!rates.is_empty(), "rows for 1366x768: {}", lines[0]);
    let fps = if rates.contains(&60) { 60 } else { rates[0] };
    let r = encode(&Clip {
        label: "window",
        codec: "h264",
        width: 1366,
        height: 768,
        fps,
        bitrate: 8_000_000,
        frames: 120,
        samples: sample_at(120, fps),
        content: Content::Pattern,
        tone: true,
    });
    assert_eq!(r.code, Some(0), "{:?}", r.lines);
    assert_eq!(last_line(&r.lines), DONE_120);
    if ffprobe_available() {
        assert_eq!(ffprobe_entry(&r.path, "v:0", "width"), "1366");
        assert_eq!(ffprobe_entry(&r.path, "v:0", "height"), "768");
    } else {
        println!("NOTE: no ffprobe here; tests/verify.sh checks {}", r.path.display());
    }
}

#[test]
fn probe_rejects_odd_and_tiny_sizes_without_crashing() {
    let _g = serial();
    for size in ["1367x768", "16x16", "1366x767", "0x0"] {
        let (code, lines) = probe(&[size]);
        assert_eq!(code, Some(2), "{size}: {lines:?}");
        assert_eq!(lines, vec![r#"{"error":"bad_size"}"#.to_string()], "{size}");
    }
}

#[test]
fn sound_may_run_up_to_one_frame_past_the_picture() {
    let _g = serial();
    let r = encode(&Clip {
        label: "soundlong",
        codec: "h264",
        width: 640,
        height: 360,
        fps: 60,
        bitrate: 4_000_000,
        frames: 60,
        samples: 48_480,
        content: Content::Pattern,
        tone: true,
    });
    assert_eq!(r.code, Some(0), "{:?}", r.lines);
    assert_eq!(last_line(&r.lines), r#"{"done":true,"frames":60}"#);
    if ffprobe_available() {
        let a: f64 = ffprobe_entry(&r.path, "a:0", "duration").parse().unwrap();
        let v: f64 = ffprobe_entry(&r.path, "v:0", "duration").parse().unwrap();
        println!("soundlong: audio {a:.4} s, video {v:.4} s");
        assert!((a - 1.010).abs() <= 0.025, "audio {a}");
        assert!((v - 1.000).abs() <= 0.009, "video {v}");
    } else {
        println!("NOTE: no ffprobe here; tests/verify.sh checks {}", r.path.display());
    }
}

#[test]
fn three_qualities_give_strictly_growing_files_near_their_rates() {
    let _g = serial();
    let probe = base_probe();
    for codec in probe.codecs() {
        if !probe.has(codec, 1920, 1080, 60) {
            println!("NOTE: {codec} cannot open 1920x1080 60; skipped");
            continue;
        }
        let mut sizes = vec![];
        for (label, rate) in [("q-standard", 8_000_000u32), ("q-high", 16_000_000), ("q-max", 30_000_000)] {
            let r = encode(&Clip {
                label,
                codec,
                width: 1920,
                height: 1080,
                fps: 60,
                bitrate: rate,
                frames: 120,
                samples: 120 * 800,
                content: Content::Noise,
                tone: false,
            });
            assert_eq!(r.code, Some(0), "{codec} {label}: {:?}", r.lines);
            let bytes = std::fs::metadata(&r.path).unwrap().len();
            // Two seconds of picture; take off the 192 kbit/s sound track.
            let video_rate = bytes as f64 * 8.0 / 2.0 - 192_000.0;
            let ratio = video_rate / rate as f64;
            println!("{codec} {label}: requested {rate}, measured {video_rate:.0} ({ratio:.3}), encoder {}", r.encoder);
            assert!((0.75..=1.25).contains(&ratio), "{codec} {label} rate ratio {ratio:.3}");
            sizes.push(bytes);
        }
        assert!(sizes[0] < sizes[1] && sizes[1] < sizes[2], "{codec} sizes {sizes:?}");
    }
}

#[test]
fn encode_speed_report() {
    let _g = serial();
    let probe = base_probe();
    for (w, h) in [(1920u32, 1080u32), (3840, 2160)] {
        if !probe.has("h264", w, h, 60) {
            println!("speed: H.264 {w}x{h} 60 not offered by the probe; skipped");
            continue;
        }
        let frames: Vec<Vec<u8>> = (0..8).map(|i| to_protocol_bgra(&pattern_rgb(w, h, i * 7), w, h)).collect();
        let dir = scratch(&format!("speed-{w}"));
        let out = dir.join("speed.partial.mp4");
        let args: Vec<String> = [
            "encode", "--out", &out.to_string_lossy(), "--width", &w.to_string(), "--height", &h.to_string(),
            "--fps", "60", "--codec", "h264", "--bitrate", "30000000",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        let mut helper = Helper::start(&args);
        let ready = helper.line(Duration::from_secs(20)).expect("ready");
        let start = Instant::now();
        for i in 0..120u64 {
            helper.send(1, &frames[(i % 8) as usize]).unwrap();
            helper.send(2, &silence(i * 800, (i + 1) * 800)).unwrap();
            helper.drain_lines();
        }
        helper.send(3, &[]).unwrap();
        helper.await_final_line(Duration::from_secs(300));
        helper.close_stdin();
        let (status, _) = helper.wait(Duration::from_secs(30));
        let secs = start.elapsed().as_secs_f64();
        println!("speed: H.264 {w}x{h} 60 fps, 120 frames in {secs:.2} s = {:.1} frames/s ({ready})", 120.0 / secs);
        assert_eq!(status.code(), Some(0), "{:?}", helper.seen);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
