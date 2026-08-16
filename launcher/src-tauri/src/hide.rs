//! Best-effort hiding of the Lunar launcher window after a deep-link launch.
//!
//! Cosmetic only, fail-soft by contract: nothing here may panic, block the
//! Tauri command, or surface output. Every failure path is ignored - a launch
//! must never break because hiding did.
//!
//! Identity is verified twice before anything is hidden: the pid comes from
//! `proc::lunar_launcher_pid()` (exact executable-path match) and the JXA
//! snippet re-checks that pid's bundle id before calling `hide`. The game JVM
//! fails both checks by construction: it runs from a bare
//! `~/.lunarclient/jre/<...>/bin/java` binary with no bundle.

use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use crate::proc;

const POLL: Duration = Duration::from_millis(500);
/// Bounds the SEARCH phase only, evaluated between polls.
const SEARCH_DEADLINE: Duration = Duration::from_secs(60);
/// Re-hide cadence. Measured (2026-08-10): Lunar's launcher window paints a
/// second or two AFTER the process appears, and RE-SHOWS itself during boot
/// (a hidden->visible->hidden->visible flip observed at ~0.3s resolution).
/// A single hide at process-birth is therefore missed entirely - the field
/// failure. Re-hiding at this cadence catches the window the moment it
/// paints; the launcher is visible for at most one interval.
const REHIDE_INTERVAL: Duration = Duration::from_millis(250);
/// How long to keep re-hiding after the launcher is first sighted - long
/// enough to cover boot and the handoff to the game. Measured: once settled
/// (~1.5 s in) the window stayed hidden, but the window covers late
/// re-shows too.
const REHIDE_WINDOW: Duration = Duration::from_secs(20);

/// Spawns the detached worker. Thread-creation failure is discarded: the
/// launch has already succeeded and hiding is cosmetic (`thread::spawn`
/// would panic here instead).
pub fn spawn_worker() {
    let _ = std::thread::Builder::new()
        .name("lunar-hide".into())
        .spawn(worker);
}

pub fn spawn_prism_worker() {}

fn worker() {
    let started = Instant::now();
    loop {
        match search_decision(proc::lunar_launcher_pid(), started.elapsed()) {
            SearchDecision::Hide(_) => break,
            SearchDecision::GiveUp => return,
            SearchDecision::Poll => std::thread::sleep(POLL),
        }
    }
    let hide_started = Instant::now();
    rehide_phase(
        proc::lunar_launcher_pid,
        hide_pass,
        std::thread::sleep,
        move || hide_started.elapsed(),
    );
}

/// Re-hide the launcher until boot settles, parameterized so tests can drive
/// every transition with a fake clock.
///
/// The launcher window paints a beat after the process appears and re-shows
/// itself during boot (measured), so a single hide is missed. Each pass
/// re-queries the current exe-matched pid (Lunar's update wrapper can
/// replace the process) and hides it; the window is on screen for at most
/// one `REHIDE_INTERVAL`. A pass returning 0 means the pid no longer
/// resolves to Lunar - the launcher quit, nothing left to hide, stop.
/// Errors (None) are ignored and the loop continues, all bounded by
/// `REHIDE_WINDOW`.
fn rehide_phase(
    mut current_pid: impl FnMut() -> Option<u32>,
    mut pass: impl FnMut(u32) -> Option<u32>,
    mut sleep: impl FnMut(Duration),
    mut elapsed: impl FnMut() -> Duration,
) {
    while elapsed() < REHIDE_WINDOW {
        match current_pid() {
            Some(pid) if pass(pid) == Some(0) => return,
            Some(_) => {}
            // The launcher process vanished between passes; nothing to hide.
            None => return,
        }
        if elapsed() + REHIDE_INTERVAL >= REHIDE_WINDOW {
            return;
        }
        sleep(REHIDE_INTERVAL);
    }
}

#[derive(Debug, PartialEq)]
enum SearchDecision {
    Hide(u32),
    Poll,
    GiveUp,
}

/// The search schedule as a pure function. A sighted launcher always wins,
/// even at the deadline edge: the deadline only ever stops the search.
fn search_decision(found: Option<u32>, elapsed: Duration) -> SearchDecision {
    match found {
        Some(pid) => SearchDecision::Hide(pid),
        None if elapsed >= SEARCH_DEADLINE => SearchDecision::GiveUp,
        None => SearchDecision::Poll,
    }
}

/// One hide pass against exactly `pid`. Returns the snippet's printed count:
/// 1 = the pid resolved, carried Lunar's bundle id, and a hide was
/// requested; 0 = no such target; None = any failure, all ignorable.
fn hide_pass(pid: u32) -> Option<u32> {
    let out = Command::new("/usr/bin/osascript")
        .args(["-l", "JavaScript", "-e", &snippet(pid)])
        .stdin(Stdio::null())
        .output() // pipes stdout/stderr - nothing is inherited
        .ok()?;
    parse_count(&out.stdout)
}

/// `pid` is an integer straight from sysinfo - no injection surface.
///
/// Enumerates Lunar's bundle-matched applications and hides ONLY the entry
/// whose pid equals the exe-verified one - the same dual identity, through
/// the query that works. (`runningApplicationWithProcessIdentifier` returns
/// nil for EVERY process via this bridge - measured against a live Lunar
/// pid and against Finder - which is exactly how the rev-7 snippet failed
/// in the field.)
fn snippet(pid: u32) -> String {
    format!(
        r#"ObjC.import("AppKit"); const a = $.NSRunningApplication.runningApplicationsWithBundleIdentifier("com.moonsworth.client"); let n = 0; for (let i = 0; i < a.count; i++) {{ const p = a.objectAtIndex(i); if (p.processIdentifier === {pid}) {{ p.hide; n = 1; }} }} n"#
    )
}

fn parse_count(stdout: &[u8]) -> Option<u32> {
    std::str::from_utf8(stdout).ok()?.trim().parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_count_reads_the_snippet_output() {
        assert_eq!(parse_count(b"0\n"), Some(0));
        assert_eq!(parse_count(b"1\n"), Some(1));
        assert_eq!(parse_count(b" 1 \n"), Some(1));
        assert_eq!(parse_count(b""), None);
        assert_eq!(parse_count(b"garbage"), None);
        assert_eq!(parse_count(&[0xFF, 0xFE]), None);
    }

    #[test]
    fn search_stops_at_the_deadline_only_when_nothing_was_found() {
        let before = SEARCH_DEADLINE - Duration::from_millis(1);
        assert_eq!(search_decision(None, before), SearchDecision::Poll);
        assert_eq!(search_decision(None, SEARCH_DEADLINE), SearchDecision::GiveUp);
    }

    #[test]
    fn a_sighted_launcher_wins_even_past_the_deadline() {
        let late = SEARCH_DEADLINE + Duration::from_secs(5);
        assert_eq!(search_decision(Some(42), late), SearchDecision::Hide(42));
    }

    /// Drives the real `rehide_phase` with scripted pid lookups and pass
    /// results, recording every pass invocation and the end time. The fake
    /// clock advances when the loop SLEEPS, exactly like real time, so the
    /// window edge is testable.
    fn drive(pids: &[Option<u32>], results: &[Option<u32>]) -> (Vec<u32>, Duration) {
        let calls = std::cell::RefCell::new(Vec::new());
        let mut pid_script = pids.iter().copied();
        let mut pass_script = results.iter().copied();
        let clock = std::cell::Cell::new(Duration::ZERO);
        rehide_phase(
            || pid_script.next().unwrap_or(Some(7)),
            |pid| {
                calls.borrow_mut().push(pid);
                pass_script.next().unwrap_or(Some(1))
            },
            |d| clock.set(clock.get() + d),
            || clock.get(),
        );
        (calls.into_inner(), clock.get())
    }

    #[test]
    fn it_rehides_every_interval_across_the_whole_window() {
        let (calls, end) = drive(&[], &[]);
        // 20 s window at 250 ms, never starting a sleep that crosses it.
        let expected = (REHIDE_WINDOW.as_millis() / REHIDE_INTERVAL.as_millis()) as usize;
        assert_eq!(calls.len(), expected, "one hide per interval for the window");
        assert!(calls.iter().all(|&p| p == 7), "every pass hits the current pid");
        assert!(end < REHIDE_WINDOW, "no sleep crosses the window (ended {end:?})");
    }

    #[test]
    fn a_vanished_launcher_stops_the_loop_immediately() {
        // pid gone -> None on the second lookup.
        let (calls, _) = drive(&[Some(7), None], &[Some(1)]);
        assert_eq!(calls.len(), 1, "hide once, then the launcher is gone: stop");
    }

    #[test]
    fn a_zero_pass_means_the_launcher_quit_and_stops_the_loop() {
        let (calls, _) = drive(&[Some(7)], &[Some(0)]);
        assert_eq!(calls.len(), 1, "0 = pid no longer Lunar: stop");
    }

    #[test]
    fn the_pid_is_requeried_every_pass_so_a_replaced_process_is_followed() {
        let (calls, _) = drive(&[Some(7), Some(9), Some(9)], &[Some(1), Some(1), Some(1)]);
        assert_eq!(&calls[..3], &[7, 9, 9], "an update-replaced process is picked up");
    }

    #[test]
    fn errors_are_ignored_and_the_loop_keeps_rehiding() {
        // None (osascript failure) must neither stop nor extend the loop.
        let (calls, _) = drive(&[], &[None, None, Some(1)]);
        let expected = (REHIDE_WINDOW.as_millis() / REHIDE_INTERVAL.as_millis()) as usize;
        assert_eq!(calls.len(), expected, "errors are transparent to the schedule");
    }

    /// End-to-end check of the JXA-to-Rust numeric contract without Lunar:
    /// a pid far outside macOS's pid range resolves to nothing, so the real
    /// snippet must print a parseable 0. (The positive case - a real
    /// launcher pid printing 1 and the window hiding - is smoke gate 3.)
    #[test]
    fn snippet_reports_zero_for_a_dead_pid() {
        assert_eq!(hide_pass(99_999_999), Some(0));
    }
}
