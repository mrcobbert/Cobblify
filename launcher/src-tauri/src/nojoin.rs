//! Bare-link Lunar dispatch (auto-join off): fire `lunarclient://play` once
//! against a warmed launcher and confirm it from Lunar's launcher log.
//!
//! Post-fire there are exactly TWO outcomes: a positively observed success
//! marker, or `Unconfirmed`. No deadline, silence, unreadable log, or cap
//! is ever treated as proof of failure - a success can arrive after any
//! observation window, so the caller must never direct the user into a
//! second launch on an unconfirmed result.
//!
//! Privacy: log bytes are scanned only for the success marker substring;
//! contents are never logged, stored, or returned.

use std::time::Duration;

/// The narrow success marker exactly as observed live (2026-08-20).
pub const SUCCESS_MARKER: &[u8] = b"[Launch] Starting new session";

/// Total bytes scanned per click before observation is declared lost.
pub const SCAN_CAP: usize = 1024 * 1024;
/// Confirmation window and poll cadence after firing the link.
pub const OBSERVE_POLLS: u32 = 40;
pub const POLL_INTERVAL: Duration = Duration::from_millis(250);
/// Warm-up after ensuring the launcher app is up (>4x the measured ~700ms
/// cold-readiness bound).
pub const WARM_UP: Duration = Duration::from_secs(3);

/// Incremental scanner with a rolling overlap so a marker split across two
/// reads is still seen.
pub struct MarkerScanner {
    carry: Vec<u8>,
    seen: bool,
    scanned: usize,
}

impl MarkerScanner {
    pub fn new() -> Self {
        MarkerScanner {
            carry: Vec::new(),
            seen: false,
            scanned: 0,
        }
    }

    pub fn feed(&mut self, chunk: &[u8]) {
        if self.seen {
            return;
        }
        // Pre-scan budget: bytes past the cap are never scanned, so a
        // marker beyond byte SCAN_CAP can never mint a confirmation.
        let remaining = SCAN_CAP.saturating_sub(self.scanned);
        let take = chunk.len().min(remaining);
        self.scanned = self.scanned.saturating_add(chunk.len());
        if take == 0 {
            return;
        }
        let mut window = std::mem::take(&mut self.carry);
        window.extend_from_slice(&chunk[..take]);
        if window
            .windows(SUCCESS_MARKER.len())
            .any(|w| w == SUCCESS_MARKER)
        {
            self.seen = true;
            return;
        }
        let keep = SUCCESS_MARKER.len().saturating_sub(1).min(window.len());
        self.carry = window[window.len() - keep..].to_vec();
    }

    pub fn success(&self) -> bool {
        self.seen
    }

    pub fn over_cap(&self) -> bool {
        self.scanned > SCAN_CAP
    }
}

/// Outcome of one no-join click.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NoJoinOutcome {
    /// Success marker observed: the game launch started.
    Confirmed,
    /// Link fired but not confirmable (silence, unreadable, cap, or only an
    /// error marker). The caller cancels hiding and re-presents.
    Unconfirmed,
    /// No link was ever fired (log unopenable before firing); the launcher
    /// is open from the ensure step - today's open-only flow, truthfully.
    OpenOnlyNeverFired,
}

/// Injected effects for one click. Everything the orchestration touches
/// goes through these seams so tests can drive every ordering.
pub struct NoJoinDeps<'a> {
    /// Ensure Lunar's launcher app is up (idempotent). Err = nothing opened.
    pub ensure_open: &'a mut dyn FnMut() -> Result<(), String>,
    /// Bounded warm-up wait.
    pub warm_up: &'a mut dyn FnMut(),
    /// Open the launcher log by handle, positioned at end. None = degrade
    /// without firing. The reader owns its captures (held file handle).
    pub open_log: &'a mut dyn FnMut() -> Option<Box<dyn FnMut() -> Option<Vec<u8>>>>,
    /// Fire the bare link once. Err = link never fired.
    pub fire_link: &'a mut dyn FnMut() -> Result<(), String>,
    /// Start the registered cancellable hide worker (insta-hide).
    pub start_hide: &'a mut dyn FnMut(),
    /// Cancel all hide workers with a bounded join; returns whether every
    /// worker drained inside the bound (a timeout still proceeds - the
    /// outcome is already unconfirmed and nothing further is scheduled).
    pub cancel_hide: &'a mut dyn FnMut() -> bool,
    /// Post window restores (Windows FIFO supersession; no-op on macOS).
    /// Strictly between cancel and re-present.
    pub restore_windows: &'a mut dyn FnMut(),
    /// Re-present the launcher. A failure never changes the outcome.
    pub represent: &'a mut dyn FnMut() -> Result<(), String>,
    /// Wait one poll interval.
    pub poll_wait: &'a mut dyn FnMut(),
}

/// Run one no-join click. Returns Err only when nothing was opened at all.
pub fn run(deps: &mut NoJoinDeps) -> Result<NoJoinOutcome, String> {
    (deps.ensure_open)()?;
    // Insta-hide from the CLICK, not the fire: on a warm relaunch the
    // ensure step un-hides Lunar's launcher, and without this the window
    // sits on screen for the whole warm-up. Every degrade path below now
    // cancels and re-presents, because something IS hidden from here on.
    (deps.start_hide)();
    // Never-fired degrade: `OpenOnlyNeverFired` (whose overlay-off route
    // quits immediately) is only truthful when cleanup PROVED a presented,
    // quiescent launcher - the hide worker drained AND the re-present
    // succeeded. Anything less falls back to `Unconfirmed`, whose route
    // keeps the app open as the recovery surface instead of exiting with
    // Lunar possibly still hidden.
    let degrade_never_fired = |deps: &mut NoJoinDeps| {
        let drained = (deps.cancel_hide)();
        (deps.restore_windows)();
        let presented = (deps.represent)().is_ok();
        if drained && presented {
            NoJoinOutcome::OpenOnlyNeverFired
        } else {
            NoJoinOutcome::Unconfirmed
        }
    };
    (deps.warm_up)();
    let Some(mut read_appended) = (deps.open_log)() else {
        return Ok(degrade_never_fired(deps));
    };
    if (deps.fire_link)().is_err() {
        // The link never fired; the launcher is open from the ensure step.
        return Ok(degrade_never_fired(deps));
    }
    let mut scanner = MarkerScanner::new();
    for poll in 0..OBSERVE_POLLS {
        match read_appended() {
            Some(chunk) => scanner.feed(&chunk),
            None => break, // observation lost: unconfirmed, never failure
        }
        if scanner.success() {
            return Ok(NoJoinOutcome::Confirmed);
        }
        if scanner.over_cap() {
            break;
        }
        if poll + 1 < OBSERVE_POLLS {
            (deps.poll_wait)();
        }
    }
    if scanner.success() {
        return Ok(NoJoinOutcome::Confirmed);
    }
    // Unconfirmed: cancel -> restore-post -> re-present, in that order.
    // A cancel timeout or a failed re-present never changes the outcome,
    // and nothing is deferred past this return.
    let _ = (deps.cancel_hide)();
    (deps.restore_windows)();
    let _ = (deps.represent)();
    Ok(NoJoinOutcome::Unconfirmed)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::rc::Rc;

    #[derive(Default, Clone)]
    struct Trace {
        events: Rc<RefCell<Vec<&'static str>>>,
    }

    impl Trace {
        fn push(&self, e: &'static str) {
            self.events.borrow_mut().push(e);
        }
        fn events(&self) -> Vec<&'static str> {
            self.events.borrow().clone()
        }
    }

    /// Drive one click: `reads` yields per-poll chunks (None = read error).
    fn drive_with(
        ensure_ok: bool,
        log_opens: bool,
        fire_ok: bool,
        cancel_drains: bool,
        represent_ok: bool,
        reads: Vec<Option<Vec<u8>>>,
    ) -> (Result<NoJoinOutcome, String>, Trace) {
        let trace = Trace::default();
        let t = trace.clone();
        let mut ensure_open = move || {
            t.push("ensure_open");
            if ensure_ok { Ok(()) } else { Err("no launcher".into()) }
        };
        let t = trace.clone();
        let mut warm_up = move || t.push("warm_up");
        let reads = Rc::new(RefCell::new(reads.into_iter()));
        let t = trace.clone();
        let mut open_log = move || {
            t.push("open_log");
            if !log_opens {
                return None;
            }
            let reads = Rc::clone(&reads);
            let reader: Box<dyn FnMut() -> Option<Vec<u8>>> = Box::new(move || {
                reads.borrow_mut().next().unwrap_or(Some(Vec::new()))
            });
            Some(reader)
        };
        let t = trace.clone();
        let mut fire_link = move || {
            t.push("fire_link");
            if fire_ok { Ok(()) } else { Err("fire failed".into()) }
        };
        let t = trace.clone();
        let mut start_hide = move || t.push("start_hide");
        let t = trace.clone();
        let mut cancel_hide = move || {
            t.push("cancel_hide");
            cancel_drains
        };
        let t = trace.clone();
        let mut restore_windows = move || t.push("restore_windows");
        let t = trace.clone();
        let mut represent = move || {
            t.push("represent");
            if represent_ok { Ok(()) } else { Err("open failed".into()) }
        };
        let mut poll_wait = || {};
        let result = run(&mut NoJoinDeps {
            ensure_open: &mut ensure_open,
            warm_up: &mut warm_up,
            open_log: &mut open_log,
            fire_link: &mut fire_link,
            start_hide: &mut start_hide,
            cancel_hide: &mut cancel_hide,
            restore_windows: &mut restore_windows,
            represent: &mut represent,
            poll_wait: &mut poll_wait,
        });
        (result, trace)
    }

    fn drive(
        ensure_ok: bool,
        log_opens: bool,
        fire_ok: bool,
        reads: Vec<Option<Vec<u8>>>,
    ) -> (Result<NoJoinOutcome, String>, Trace) {
        drive_with(ensure_ok, log_opens, fire_ok, true, true, reads)
    }

    #[test]
    fn confirmed_success_keeps_hide_running() {
        let (result, trace) = drive(
            true,
            true,
            true,
            vec![Some(b"noise\n".to_vec()), Some(b"x [Launch] Starting new session y".to_vec())],
        );
        assert_eq!(result.unwrap(), NoJoinOutcome::Confirmed);
        let events = trace.events();
        assert!(!events.contains(&"cancel_hide"));
        assert!(!events.contains(&"represent"));
        // Insta-hide from the CLICK: the worker starts right after the
        // ensure step, BEFORE the warm-up and the fire, so a warm
        // relaunch's un-hidden launcher never sits on screen.
        let ensure = events.iter().position(|e| *e == "ensure_open").unwrap();
        let hide = events.iter().position(|e| *e == "start_hide").unwrap();
        let warm = events.iter().position(|e| *e == "warm_up").unwrap();
        let fire = events.iter().position(|e| *e == "fire_link").unwrap();
        assert_eq!(hide, ensure + 1);
        assert!(hide < warm && warm < fire);
    }

    /// The round-3/4 case: an error marker followed by a late success within
    /// the window is SUCCESS - nothing is discarded, nothing refires.
    #[test]
    fn failure_marker_then_late_success_is_confirmed() {
        let (result, trace) = drive(
            true,
            true,
            true,
            vec![
                Some(b"[NO_PROFILE_SELECTED/null] No profile selected\n".to_vec()),
                Some(Vec::new()),
                Some(b"[Launch] Starting new session\n".to_vec()),
            ],
        );
        assert_eq!(result.unwrap(), NoJoinOutcome::Confirmed);
        assert_eq!(
            trace.events().iter().filter(|e| **e == "fire_link").count(),
            1,
            "there is no second fire in the design"
        );
    }

    /// An error marker with no success by the deadline is UNCONFIRMED, never
    /// a positive open-only failure - no deadline proves the link failed.
    #[test]
    fn failure_marker_alone_is_unconfirmed_not_open_only() {
        let (result, trace) = drive(
            true,
            true,
            true,
            vec![Some(b"NO_PROFILE_SELECTED".to_vec())],
        );
        assert_eq!(result.unwrap(), NoJoinOutcome::Unconfirmed);
        assert!(trace.events().contains(&"cancel_hide"));
    }

    /// The unconfirmed path is strictly cancel -> restore-post ->
    /// re-present, all after the fire and nothing after the return.
    #[test]
    fn silent_window_is_unconfirmed_with_ordered_cancel_restore_represent() {
        let (result, trace) = drive(true, true, true, vec![]);
        assert_eq!(result.unwrap(), NoJoinOutcome::Unconfirmed);
        let events = trace.events();
        let fire = events.iter().position(|e| *e == "fire_link").unwrap();
        let cancel = events.iter().position(|e| *e == "cancel_hide").unwrap();
        let restore = events.iter().position(|e| *e == "restore_windows").unwrap();
        let represent = events.iter().position(|e| *e == "represent").unwrap();
        assert!(fire < cancel && cancel < restore && restore < represent);
        assert_eq!(events.len(), represent + 1, "nothing deferred past the return");
    }

    /// A cancel-join timeout still proceeds and schedules nothing further.
    #[test]
    fn cancel_timeout_still_represents_and_defers_nothing() {
        let (result, trace) = drive_with(true, true, true, false, true, vec![]);
        assert_eq!(result.unwrap(), NoJoinOutcome::Unconfirmed);
        let events = trace.events();
        assert!(events.contains(&"restore_windows"));
        assert_eq!(*events.last().unwrap(), "represent");
    }

    /// A failed re-present never changes the outcome.
    #[test]
    fn represent_failure_preserves_unconfirmed() {
        let (result, _) = drive_with(true, true, true, true, false, vec![]);
        assert_eq!(result.unwrap(), NoJoinOutcome::Unconfirmed);
    }

    #[test]
    fn read_error_after_fire_is_unconfirmed() {
        let (result, _) = drive(true, true, true, vec![Some(b"partial".to_vec()), None]);
        assert_eq!(result.unwrap(), NoJoinOutcome::Unconfirmed);
    }

    #[test]
    fn cap_exceeded_is_unconfirmed() {
        let big = vec![b'x'; SCAN_CAP + 1];
        let (result, _) = drive(true, true, true, vec![Some(big)]);
        assert_eq!(result.unwrap(), NoJoinOutcome::Unconfirmed);
    }

    /// The cap is a pre-scan bound: a marker BEYOND byte SCAN_CAP is never
    /// scanned and can never mint a confirmation.
    #[test]
    fn marker_beyond_the_cap_never_confirms() {
        let mut big = vec![b'x'; SCAN_CAP + 1];
        big.extend_from_slice(SUCCESS_MARKER);
        let (result, _) = drive(true, true, true, vec![Some(big)]);
        assert_eq!(result.unwrap(), NoJoinOutcome::Unconfirmed);
        // Same shape split across reads: the marker arrives after the cap.
        let (result, _) = drive(
            true,
            true,
            true,
            vec![Some(vec![b'x'; SCAN_CAP + 1]), Some(SUCCESS_MARKER.to_vec())],
        );
        assert_eq!(result.unwrap(), NoJoinOutcome::Unconfirmed);
    }

    /// A marker fully inside the cap still confirms at the boundary.
    #[test]
    fn marker_just_inside_the_cap_confirms() {
        let mut chunk = vec![b'x'; SCAN_CAP - SUCCESS_MARKER.len()];
        chunk.extend_from_slice(SUCCESS_MARKER);
        let (result, _) = drive(true, true, true, vec![Some(chunk)]);
        assert_eq!(result.unwrap(), NoJoinOutcome::Confirmed);
    }

    /// Unreadable BEFORE firing: degrade without firing; the click-start
    /// hide is cancelled and the launcher re-presented (it was hidden).
    #[test]
    fn unopenable_log_never_fires_and_cancels_the_click_hide() {
        let (result, trace) = drive(true, false, true, vec![]);
        assert_eq!(result.unwrap(), NoJoinOutcome::OpenOnlyNeverFired);
        let events = trace.events();
        assert!(!events.contains(&"fire_link"));
        let hide = events.iter().position(|e| *e == "start_hide").unwrap();
        let cancel = events.iter().position(|e| *e == "cancel_hide").unwrap();
        let restore = events.iter().position(|e| *e == "restore_windows").unwrap();
        let represent = events.iter().position(|e| *e == "represent").unwrap();
        assert!(hide < cancel && cancel < restore && restore < represent);
        assert_eq!(events.len(), represent + 1, "nothing deferred past the return");
    }

    #[test]
    fn ensure_open_failure_is_a_rejection_with_no_hide() {
        let (result, trace) = drive(false, true, true, vec![]);
        assert!(result.is_err());
        assert!(!trace.events().contains(&"fire_link"));
        assert!(!trace.events().contains(&"start_hide"));
    }

    #[test]
    fn failed_fire_is_open_only_and_cancels_the_click_hide() {
        let (result, trace) = drive(true, true, false, vec![]);
        assert_eq!(result.unwrap(), NoJoinOutcome::OpenOnlyNeverFired);
        let events = trace.events();
        assert!(events.contains(&"cancel_hide"));
        assert_eq!(*events.last().unwrap(), "represent");
    }

    /// A never-fired degrade whose cleanup could NOT prove a presented,
    /// quiescent launcher must not claim open-only (whose overlay-off
    /// route exits immediately): it degrades to Unconfirmed so the app
    /// stays open as the recovery surface.
    #[test]
    fn never_fired_with_incomplete_cleanup_is_unconfirmed_not_open_only() {
        // Cancel timed out: a hide pass may still re-hide after the
        // best-effort re-present.
        let (result, _) = drive_with(true, false, true, false, true, vec![]);
        assert_eq!(result.unwrap(), NoJoinOutcome::Unconfirmed);
        // Re-present failed: on macOS restore is a no-op, so the launcher
        // may still be hidden.
        let (result, _) = drive_with(true, false, true, true, false, vec![]);
        assert_eq!(result.unwrap(), NoJoinOutcome::Unconfirmed);
        // Failed-fire variants of the same two cases.
        let (result, _) = drive_with(true, true, false, false, true, vec![]);
        assert_eq!(result.unwrap(), NoJoinOutcome::Unconfirmed);
        let (result, _) = drive_with(true, true, false, true, false, vec![]);
        assert_eq!(result.unwrap(), NoJoinOutcome::Unconfirmed);
    }

    #[test]
    fn split_marker_across_reads_is_seen() {
        let marker = SUCCESS_MARKER;
        let (a, b) = marker.split_at(10);
        let (result, _) = drive(true, true, true, vec![Some(a.to_vec()), Some(b.to_vec())]);
        assert_eq!(result.unwrap(), NoJoinOutcome::Confirmed);
    }

    #[test]
    fn marker_at_the_final_poll_still_wins() {
        let mut reads: Vec<Option<Vec<u8>>> =
            (0..OBSERVE_POLLS - 1).map(|_| Some(Vec::new())).collect();
        reads.push(Some(SUCCESS_MARKER.to_vec()));
        let (result, _) = drive(true, true, true, reads);
        assert_eq!(result.unwrap(), NoJoinOutcome::Confirmed);
    }

    #[test]
    fn scanner_ignores_unrelated_lines() {
        let mut s = MarkerScanner::new();
        s.feed(b"[Launch] Starting new sessio\n"); // near miss
        s.feed(b"Starting new session\n"); // missing the [Launch] prefix
        assert!(!s.success());
        s.feed(b"[Launch] Starting new session");
        assert!(s.success());
    }
}
