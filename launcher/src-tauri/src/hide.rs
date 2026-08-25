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
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::hide_registry;
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
/// Post-terminate wait for the launcher process to actually go away, and its
/// poll cadence. The deadline is load-bearing, not cosmetic:
/// `lunar_config::register` refuses to write while Lunar is running, so the
/// setup refresh that follows a quit needs the process really gone.
const QUIT_EXIT_DEADLINE: Duration = Duration::from_millis(1500);
const QUIT_EXIT_POLL: Duration = Duration::from_millis(100);
/// D1c lifetime: long enough to cover the gap between the game's death and
/// the reset-time hide+terminate, short enough that it is gone well before
/// anything else could start a launcher.
const EARLY_HIDE_WINDOW: Duration = Duration::from_secs(3);

/// Spawns the detached worker, registered app-wide so a later dispatch (or
/// the bare-link route's cancel) can stop it. Thread-creation failure is
/// discarded: the launch has already succeeded and hiding is cosmetic
/// (`thread::spawn` would panic here instead).
pub fn spawn_worker() {
    let cancel = Arc::new(AtomicBool::new(false));
    let flag = Arc::clone(&cancel);
    if let Ok(handle) = std::thread::Builder::new()
        .name("lunar-hide".into())
        .spawn(move || worker(flag))
    {
        hide_registry::register(cancel, handle);
    }
}

pub fn spawn_prism_worker() {}

/// D1c early hide, spawned when a Lunar session's poll first shows an armed
/// absence or a proven session end - roughly a second before the reset-time
/// terminate would run. REGISTERED (so a dispatch's `cancel_all_and_wait`
/// reaps it) and IDENTITY-BOUND to the launcher that was running at trigger
/// time: a new generation's launcher is a new pid+birth by construction, so
/// a straggler thread can never touch it.
pub fn spawn_bound_rehide(pid: u32, birth: crate::process_liveness::EpochNs) {
    let cancel = Arc::new(AtomicBool::new(false));
    let flag = Arc::clone(&cancel);
    if let Ok(handle) = std::thread::Builder::new()
        .name("lunar-early-hide".into())
        .spawn(move || {
            let started = Instant::now();
            bound_rehide_phase(
                move || crate::process_liveness::check_identity(pid, birth),
                move || {
                    let _ = hide_pass(pid);
                },
                std::thread::sleep,
                move || started.elapsed(),
                move || flag.load(Ordering::Relaxed),
            )
        })
    {
        hide_registry::register(cancel, handle);
    }
}

/// Per-pass decision for the identity-bound early hide: act only while the
/// captured launcher is still alive under the SAME OS birth. A reused pid
/// (`DefinitelyGone`) and a refused query (`Indeterminate`) are both no-ops.
fn bound_pass_acts(identity: crate::process_liveness::IdentityCheck) -> bool {
    identity == crate::process_liveness::IdentityCheck::AliveSameIdentity
}

/// Bounded, cancellable, identity-checked re-hide passes. Parameterized so
/// tests drive every transition with a fake clock.
fn bound_rehide_phase(
    mut identity: impl FnMut() -> crate::process_liveness::IdentityCheck,
    mut pass: impl FnMut(),
    mut sleep: impl FnMut(Duration),
    mut elapsed: impl FnMut() -> Duration,
    mut cancelled: impl FnMut() -> bool,
) {
    while elapsed() < EARLY_HIDE_WINDOW {
        if cancelled() {
            return;
        }
        if bound_pass_acts(identity()) {
            pass();
        }
        if elapsed() + REHIDE_INTERVAL >= EARLY_HIDE_WINDOW {
            return;
        }
        sleep(REHIDE_INTERVAL);
    }
}

/// One immediate, bounded hide pass against the current launcher pid - the
/// late-success rehide for an overlay-off exit. No thread, no schedule.
pub fn rehide_once() {
    if let Some(pid) = proc::lunar_launcher_pid() {
        let _ = hide_pass(pid);
    }
}

/// No queued native window events on macOS; `open -a` re-presenting after a
/// joined worker needs no restore pass first.
pub fn restore_lunar_windows() {}

/// Gracefully quit Lunar's launcher (session-ended reset): same dual
/// identity as hiding - exe-verified pid, bundle-id re-check in the
/// snippet at execution time - but `hide` then `terminate` instead of a
/// bare `hide`. The hide comes first because Lunar SHOWS its launcher
/// window when the game exits: terminating alone leaves that window on
/// screen for the whole AppleEvent quit, which is the visible flash. The
/// terminate itself is a normal AppleEvent quit, the same thing Lunar's own
/// "close launcher after launch" setting does. Then wait briefly for the
/// process to actually exit so the setup refresh that follows sees a clean
/// state.
///
/// Identity is bound to the OS birth for the destructive step, exactly like
/// the Windows close: the pid's birth is captured up front and revalidated
/// immediately before the send, so a pid reused between the lookup and the
/// send can never be terminated. An unavailable birth fails CLOSED - the
/// pass returns without committing, because there is nothing to revalidate
/// against.
///
/// `commit` is the destructive gate (see
/// `hide_registry::run_destructive_bounded`): it is called immediately
/// before the terminate and the pass aborts on false, so a timed-out
/// caller can guarantee no late termination. Fail-soft: errors ignored.
pub fn quit_lunar_launcher(commit: &dyn Fn() -> bool) {
    let Some(pid) = proc::lunar_launcher_pid() else {
        return;
    };
    let Some(birth) = crate::process_liveness::process_birth_ns(pid) else {
        return;
    };
    if !commit() {
        return;
    }
    if !confirmed_quit_target(crate::process_liveness::check_identity(pid, birth)) {
        return;
    }
    let _ = terminate_pass(pid);
    let started = Instant::now();
    wait_for_exit(
        proc::lunar_launcher_pid,
        std::thread::sleep,
        move || started.elapsed(),
    );
}

/// Send-time gate for the destructive terminate: the pid must still be the
/// same exe-verified process (same OS birth identity). Anything else -
/// reuse, exit, indeterminate - is refused.
fn confirmed_quit_target(identity: crate::process_liveness::IdentityCheck) -> bool {
    identity == crate::process_liveness::IdentityCheck::AliveSameIdentity
}

/// Wait for the launcher process to disappear, parameterized so tests can
/// drive the schedule with a fake clock. Bounded by `QUIT_EXIT_DEADLINE`:
/// a launcher that refuses to exit must never hold the reset open.
fn wait_for_exit(
    mut current_pid: impl FnMut() -> Option<u32>,
    mut sleep: impl FnMut(Duration),
    mut elapsed: impl FnMut() -> Duration,
) {
    while elapsed() < QUIT_EXIT_DEADLINE {
        if current_pid().is_none() {
            return;
        }
        sleep(QUIT_EXIT_POLL);
    }
}

fn terminate_pass(pid: u32) -> Option<u32> {
    let out = Command::new("/usr/bin/osascript")
        .args(["-l", "JavaScript", "-e", &terminate_snippet(pid)])
        .stdin(Stdio::null())
        .output()
        .ok()?;
    parse_count(&out.stdout)
}

fn terminate_snippet(pid: u32) -> String {
    format!(
        r#"ObjC.import("AppKit"); const a = $.NSRunningApplication.runningApplicationsWithBundleIdentifier("com.moonsworth.client"); let n = 0; for (let i = 0; i < a.count; i++) {{ const p = a.objectAtIndex(i); if (p.processIdentifier === {pid}) {{ p.hide; p.terminate; n = 1; }} }} n"#
    )
}

fn worker(cancel: Arc<AtomicBool>) {
    let started = Instant::now();
    loop {
        if cancel.load(Ordering::Relaxed) {
            return;
        }
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
        move || cancel.load(Ordering::Relaxed),
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
    mut cancelled: impl FnMut() -> bool,
) {
    while elapsed() < REHIDE_WINDOW {
        if cancelled() {
            return;
        }
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
            || false,
        );
        (calls.into_inner(), clock.get())
    }

    #[test]
    fn cancellation_stops_the_rehide_loop_before_the_next_pass() {
        let calls = std::cell::RefCell::new(0u32);
        let cancelled = std::cell::Cell::new(false);
        rehide_phase(
            || Some(7),
            |_| {
                *calls.borrow_mut() += 1;
                cancelled.set(true); // cancel arrives mid-window
                Some(1)
            },
            |_| {},
            || Duration::ZERO,
            || cancelled.get(),
        );
        assert_eq!(*calls.borrow(), 1, "no pass after the cancel flag is seen");
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

    /// The quit snippet hides BEFORE it terminates - the window Lunar shows
    /// on game exit must be off screen for the whole AppleEvent quit - and
    /// still acts on nothing but the exact exe-verified pid.
    #[test]
    fn the_quit_snippet_hides_before_it_terminates_and_keeps_the_pid_guard() {
        let s = terminate_snippet(4242);
        let hide = s.find("p.hide").expect("the quit snippet hides");
        let terminate = s.find("p.terminate").expect("the quit snippet terminates");
        assert!(hide < terminate, "hide must precede terminate");
        assert!(s.contains("p.processIdentifier === 4242"), "pid guard kept");
        assert!(s.contains("com.moonsworth.client"), "bundle-id gate kept");
    }

    /// Same canary as `snippet_reports_zero_for_a_dead_pid` for the quit
    /// snippet: a pid outside macOS's range matches nothing, so this is
    /// safe to run live and proves the two-statement body still parses.
    #[test]
    fn quit_snippet_reports_zero_for_a_dead_pid() {
        assert_eq!(terminate_pass(99_999_999), Some(0));
    }

    /// The destructive gate: only a pid that is still alive under the SAME
    /// OS birth may be terminated. Reuse, exit, and a refused query are all
    /// refused.
    #[test]
    fn quit_is_refused_unless_the_identity_still_matches() {
        use crate::process_liveness::IdentityCheck;
        assert!(confirmed_quit_target(IdentityCheck::AliveSameIdentity));
        assert!(!confirmed_quit_target(IdentityCheck::DefinitelyGone));
        assert!(!confirmed_quit_target(IdentityCheck::Indeterminate));
    }

    /// Drives the real `wait_for_exit` with scripted pid lookups, recording
    /// the lookup count and the end time. The fake clock advances when the
    /// loop SLEEPS, exactly like real time.
    fn drive_wait(pids: &[Option<u32>]) -> (usize, Duration) {
        let looked_up = std::cell::Cell::new(0usize);
        let mut pid_script = pids.iter().copied();
        let clock = std::cell::Cell::new(Duration::ZERO);
        wait_for_exit(
            || {
                looked_up.set(looked_up.get() + 1);
                pid_script.next().unwrap_or(Some(7))
            },
            |d| clock.set(clock.get() + d),
            || clock.get(),
        );
        (looked_up.get(), clock.get())
    }

    #[test]
    fn the_wait_ends_as_soon_as_the_launcher_is_gone() {
        let (looked_up, end) = drive_wait(&[Some(7), Some(7), None]);
        assert_eq!(looked_up, 3, "stop on the first lookup that finds nothing");
        assert_eq!(end, QUIT_EXIT_POLL * 2, "two polls, then done");
    }

    #[test]
    fn a_launcher_that_never_exits_is_bounded_by_the_deadline() {
        let (looked_up, end) = drive_wait(&[]);
        let expected = (QUIT_EXIT_DEADLINE.as_millis() / QUIT_EXIT_POLL.as_millis()) as usize;
        assert_eq!(looked_up, expected, "one lookup per poll for the whole wait");
        assert!(end <= QUIT_EXIT_DEADLINE, "never sleeps past the deadline (ended {end:?})");
    }

    /// A launcher already gone when the wait starts costs no sleep at all.
    #[test]
    fn an_already_dead_launcher_never_sleeps() {
        let (looked_up, end) = drive_wait(&[None]);
        assert_eq!(looked_up, 1);
        assert_eq!(end, Duration::ZERO);
    }

    /// D1c's whole safety argument in one table: only the captured launcher,
    /// still alive under the same OS birth, is ever touched.
    #[test]
    fn the_early_hide_acts_on_one_identity_only() {
        use crate::process_liveness::IdentityCheck;
        assert!(bound_pass_acts(IdentityCheck::AliveSameIdentity));
        assert!(!bound_pass_acts(IdentityCheck::DefinitelyGone));
        assert!(!bound_pass_acts(IdentityCheck::Indeterminate));
    }

    /// Drives the real `bound_rehide_phase` with a scripted identity script,
    /// recording how many passes actually ran and when the loop ended.
    fn drive_bound(identities: &[crate::process_liveness::IdentityCheck]) -> (u32, Duration) {
        use crate::process_liveness::IdentityCheck;
        let passes = std::cell::Cell::new(0u32);
        let mut script = identities.iter().copied();
        let clock = std::cell::Cell::new(Duration::ZERO);
        bound_rehide_phase(
            || script.next().unwrap_or(IdentityCheck::AliveSameIdentity),
            || passes.set(passes.get() + 1),
            |d| clock.set(clock.get() + d),
            || clock.get(),
            || false,
        );
        (passes.get(), clock.get())
    }

    #[test]
    fn the_early_hide_covers_its_window_at_the_rehide_cadence() {
        let (passes, end) = drive_bound(&[]);
        let expected = (EARLY_HIDE_WINDOW.as_millis() / REHIDE_INTERVAL.as_millis()) as u32;
        assert_eq!(passes, expected, "one pass per interval for the window");
        assert!(end < EARLY_HIDE_WINDOW, "no sleep crosses the window ({end:?})");
    }

    /// A stale thread whose captured launcher was replaced keeps SCHEDULING
    /// (it is reaped by cancel/registry) but never hides anything.
    #[test]
    fn a_replaced_or_unreadable_launcher_is_never_touched() {
        use crate::process_liveness::IdentityCheck;
        let (passes, _) = drive_bound(&[IdentityCheck::DefinitelyGone; 32]);
        assert_eq!(passes, 0, "a reused pid is never hidden");
        let (passes, _) = drive_bound(&[IdentityCheck::Indeterminate; 32]);
        assert_eq!(passes, 0, "a refused query is never hidden");
    }

    #[test]
    fn cancellation_stops_the_early_hide_before_the_next_pass() {
        use crate::process_liveness::IdentityCheck;
        let passes = std::cell::Cell::new(0u32);
        let cancelled = std::cell::Cell::new(false);
        bound_rehide_phase(
            || IdentityCheck::AliveSameIdentity,
            || {
                passes.set(passes.get() + 1);
                cancelled.set(true);
            },
            |_| {},
            || Duration::ZERO,
            || cancelled.get(),
        );
        assert_eq!(passes.get(), 1, "no pass after the cancel flag is seen");
    }

    /// The worker is REGISTERED, so a later dispatch's `cancel_all_and_wait`
    /// reaps it - the failure mode D1c exists to avoid.
    #[test]
    fn the_early_hide_worker_is_registered_and_cancellable() {
        let _guard = hide_registry::REGISTRY_TEST_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let before = hide_registry::registered_count();
        // A pid outside macOS's range: alive-same-identity can never hold,
        // so every pass no-ops and nothing on this machine is touched.
        spawn_bound_rehide(99_999_999, 1);
        assert_eq!(hide_registry::registered_count(), before + 1);
        assert!(hide_registry::cancel_all_and_wait(Duration::from_secs(5)));
        assert_eq!(hide_registry::registered_count(), 0);
    }
}
