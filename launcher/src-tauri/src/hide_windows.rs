//! Windows counterpart of `hide.rs`: gets Lunar's leftover windows off the
//! screen after a deep-link launch.
//!
//! Cosmetic only, fail-soft by contract - exactly like the macOS worker.
//! Nothing here may panic, block the Tauri command, or surface output; every
//! failure path is ignored, because a launch must never break because hiding
//! did.
//!
//! macOS gets this for free: one `NSRunningApplication.hide` hides an entire
//! app. Windows has no app-level hide, so this walks the top-level windows
//! instead and acts per window, which makes IDENTITY the whole problem. Two
//! independent gates, and everything not named is left alone:
//!
//!  1. Lunar's own launcher windows - every process whose executable is
//!     `Lunar Client.exe` under `%LOCALAPPDATA%\Programs` (Electron helpers
//!     share that path on Windows and any of them can own a window). These are
//!     MINIMIZED, not hidden: minimize is reversible from the taskbar, and it
//!     is the closest analogue to what the Dock leaves the user on macOS.
//!  2. The game JVM's CONSOLE window, when Lunar starts the game on a console
//!     binary (`java.exe` rather than `javaw.exe`) - the black log window.
//!     A console window belongs to `conhost.exe`, NOT to the JVM, so its pid
//!     can never identify it; ownership is established through the console API
//!     instead (`AttachConsole(game pid)` -> `GetConsoleWindow`). That is
//!     hidden outright - a player has no use for it, and a hidden window
//!     cannot be closed by accident, which on a console would kill the game.
//!
//! The Minecraft window itself is never touched: it belongs to the game JVM,
//! and a game-JVM window is only ever acted on when the console API named that
//! exact `HWND` (`decide` below, and the tests under it).

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use windows_sys::Win32::System::Console::{AttachConsole, FreeConsole, GetConsoleWindow};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    EnumWindows, GetWindowThreadProcessId, IsWindowVisible, PostMessageW, ShowWindowAsync,
    SW_HIDE, SW_RESTORE, SW_SHOWMINNOACTIVE, WM_CLOSE,
};

use crate::hide_registry;
use crate::proc;

/// Search cadence and bound, same numbers the macOS worker uses.
const POLL: Duration = Duration::from_millis(500);
const SEARCH_DEADLINE: Duration = Duration::from_secs(60);

/// Sweep cadence. Lunar's windows paint a beat after their process appears and
/// can re-show themselves during boot (measured on macOS), so a single pass at
/// process-birth is missed. At this cadence a window is on screen for at most
/// one interval.
const SWEEP_INTERVAL: Duration = Duration::from_millis(250);
/// How long to keep sweeping. Much longer than the macOS worker's 20 s because
/// this one also waits for the GAME JVM to appear, which on a cold start (Lunar
/// update check, asset verification, JVM boot) is well over a minute after the
/// click.
const SWEEP_WINDOW: Duration = Duration::from_secs(150);
/// Process lookups are the expensive part of a pass, so they run at 1 Hz while
/// window enumeration stays at `SWEEP_INTERVAL`.
const PID_REFRESH: Duration = Duration::from_secs(1);
/// Stop early once nothing Lunar-shaped has been running for this long - the
/// user cancelled, or quit everything.
const IDLE_GIVE_UP: Duration = Duration::from_secs(5);
/// Times a single window may be acted on. Without a cap, a user who
/// deliberately restores a window would be fighting a loop that re-minimizes it
/// four times a second for the rest of the sweep. Three passes cover the
/// paint-then-re-show flicker; after that the window is the user's.
const ACTION_BUDGET: u8 = 3;

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

pub fn spawn_prism_worker() {
    let cancel = Arc::new(AtomicBool::new(false));
    let flag = Arc::clone(&cancel);
    if let Ok(handle) = std::thread::Builder::new()
        .name("prism-hide".into())
        .spawn(move || launcher_only_worker(crate::proc::prism_launcher_pids, flag))
    {
        hide_registry::register(cancel, handle);
    }
}

/// One immediate minimize pass over Lunar's launcher windows - the
/// late-success rehide for an overlay-off exit. No thread, no schedule, no
/// console handling (a single pass cannot own the budget/consoles state).
pub fn rehide_once() {
    let launcher = launcher_pids();
    if launcher.is_empty() {
        return;
    }
    for window in top_level_windows() {
        if decide(&window, &launcher, &[]) == Action::Minimize {
            apply(window.hwnd, Action::Minimize);
        }
    }
}

/// Post a restore to every Lunar launcher window. Posted to the same
/// per-thread queues as the workers' minimizes, so after those workers are
/// joined (no new posts possible) this restore is processed after - and
/// supersedes - any still-queued minimize.
pub fn restore_lunar_windows() {
    let launcher = launcher_pids();
    if launcher.is_empty() {
        return;
    }
    for window in top_level_windows() {
        if launcher.contains(&window.pid) {
            unsafe { ShowWindowAsync(window.hwnd as _, SW_RESTORE) };
        }
    }
}

/// Gracefully quit Lunar's launcher (session-ended reset): post `WM_CLOSE`
/// to every exe-verified launcher window - the normal close request an
/// Electron app handles by quitting - then wait briefly for the processes
/// to exit so the setup refresh that follows sees a clean state.
///
/// `commit` is the destructive gate (see
/// `hide_registry::run_destructive_bounded`), called once immediately
/// before the first post. Because WM_CLOSE is destructive, each window's
/// owner is REVALIDATED at send time: the pid captured at enumeration must
/// still own the hwnd AND still be the same exe-verified process by birth
/// identity, so a pid reused by an unrelated process between snapshot and
/// send can never receive the close. Fail-soft: errors ignored.
pub fn quit_lunar_launcher(commit: &dyn Fn() -> bool) {
    let targets = verified_targets();
    if targets.is_empty() {
        return;
    }
    let windows = top_level_windows();
    if !commit() {
        return;
    }
    for window in windows {
        let Some((pid, birth)) = targets.iter().copied().find(|(p, _)| *p == window.pid)
        else {
            continue;
        };
        let mut owner_now: u32 = 0;
        unsafe { GetWindowThreadProcessId(window.hwnd as _, &mut owner_now) };
        let identity = crate::process_liveness::check_identity(pid, birth);
        if confirmed_close_target(window.pid, owner_now, identity) {
            unsafe { PostMessageW(window.hwnd as _, WM_CLOSE, 0, 0) };
        }
    }
    let deadline = Instant::now() + Duration::from_millis(1500);
    while !launcher_pids().is_empty() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(100));
    }
}

/// Destructive targets with exe verification and birth identity BOUND to
/// the same process instance. The observations are ordered exe-scan ->
/// birth -> exe-scan -> birth per pid, and a target is accepted only when
/// both scans verify the pid and both births agree: a pid reused between
/// any two observations carries a different birth (or fails the second
/// exe scan) and is refused, so an unrelated replacement can never have
/// its birth bound to a stale verification.
fn verified_targets() -> Vec<(u32, crate::process_liveness::EpochNs)> {
    let first = launcher_pids();
    if first.is_empty() {
        return Vec::new();
    }
    let births: Vec<(u32, crate::process_liveness::EpochNs)> = first
        .iter()
        .filter_map(|&pid| crate::process_liveness::process_birth_ns(pid).map(|b| (pid, b)))
        .collect();
    let second = launcher_pids();
    births
        .into_iter()
        .filter_map(|(pid, birth)| {
            bind_target(
                second.contains(&pid),
                birth,
                crate::process_liveness::process_birth_ns(pid),
            )
            .map(|b| (pid, b))
        })
        .collect()
}

/// Pure binding rule for one candidate: the second exe scan must still
/// verify the pid and the re-read birth must equal the first. Equal births
/// across the second scan prove the exe-verified instance IS the
/// birth-bound instance (a reused pid always carries a later birth).
fn bind_target(
    exe_verified_again: bool,
    birth_first: crate::process_liveness::EpochNs,
    birth_again: Option<crate::process_liveness::EpochNs>,
) -> Option<crate::process_liveness::EpochNs> {
    match birth_again {
        Some(b) if exe_verified_again && b == birth_first => Some(birth_first),
        _ => None,
    }
}

/// Send-time gate for a destructive close: the enumerated owner must still
/// own the window now, and the pid must still be the same exe-verified
/// process (same OS birth identity). Anything else - reuse, exit,
/// indeterminate - is refused.
fn confirmed_close_target(
    enum_pid: u32,
    owner_now: u32,
    identity: crate::process_liveness::IdentityCheck,
) -> bool {
    enum_pid == owner_now
        && identity == crate::process_liveness::IdentityCheck::AliveSameIdentity
}

fn launcher_only_worker(pids: fn() -> Vec<u32>, cancel: Arc<AtomicBool>) {
    let started = Instant::now();
    while started.elapsed() < SEARCH_DEADLINE {
        if cancel.load(Ordering::Relaxed) {
            return;
        }
        let launcher = pids();
        if !launcher.is_empty() {
            let sweep_started = Instant::now();
            let mut budget = HashMap::new();
            while sweep_started.elapsed() < SWEEP_WINDOW {
                if cancel.load(Ordering::Relaxed) {
                    return;
                }
                let launcher = pids();
                if launcher.is_empty() {
                    return;
                }
                for window in top_level_windows() {
                    if decide(&window, &launcher, &[]) == Action::Minimize
                        && spend(&mut budget, window.hwnd)
                    {
                        apply(window.hwnd, Action::Minimize);
                    }
                }
                std::thread::sleep(SWEEP_INTERVAL);
            }
            return;
        }
        std::thread::sleep(POLL);
    }
}

fn worker(cancel: Arc<AtomicBool>) {
    let started = Instant::now();
    loop {
        if cancel.load(Ordering::Relaxed) {
            return;
        }
        match search_decision(!launcher_pids().is_empty(), started.elapsed()) {
            SearchDecision::Sweep => break,
            SearchDecision::GiveUp => return,
            SearchDecision::Poll => std::thread::sleep(POLL),
        }
    }
    sweep(Instant::now(), cancel);
}

#[derive(Debug, PartialEq)]
enum SearchDecision {
    Sweep,
    Poll,
    GiveUp,
}

/// The search schedule as a pure function. A sighted launcher always wins, even
/// at the deadline edge: the deadline only ever stops the search.
fn search_decision(found: bool, elapsed: Duration) -> SearchDecision {
    match (found, elapsed >= SEARCH_DEADLINE) {
        (true, _) => SearchDecision::Sweep,
        (false, true) => SearchDecision::GiveUp,
        (false, false) => SearchDecision::Poll,
    }
}

/// What to do with one window. `Leave` is the default for everything this
/// worker has not positively identified.
#[derive(Clone, Copy, Debug, PartialEq)]
enum Action {
    Minimize,
    Hide,
    Leave,
}

/// One enumerated top-level window. `hwnd` is carried as `isize` so the whole
/// decision layer stays plain data and unit-testable.
#[derive(Clone, Debug)]
struct Window {
    hwnd: isize,
    pid: u32,
    visible: bool,
}

/// The identity gates, as a pure function.
///
/// A console `HWND` wins over the pid check: the console belongs to
/// `conhost.exe`, whose pid means nothing here, so `consoles` is the only
/// evidence that window is the game's log window.
///
/// Note what is NOT reachable: any other window of the game JVM - the
/// Minecraft window - falls through to `Leave`, because game pids are never in
/// `launcher`.
fn decide(window: &Window, launcher: &[u32], consoles: &[isize]) -> Action {
    if !window.visible {
        return Action::Leave;
    }
    if consoles.contains(&window.hwnd) {
        return Action::Hide;
    }
    if launcher.contains(&window.pid) {
        return Action::Minimize;
    }
    Action::Leave
}

/// True while this window's budget allows another pass, spending one.
fn spend(budget: &mut HashMap<isize, u8>, hwnd: isize) -> bool {
    let used = budget.entry(hwnd).or_insert(0);
    if *used >= ACTION_BUDGET {
        return false;
    }
    *used += 1;
    true
}

/// The sweep loop. Enumerates every `SWEEP_INTERVAL`, re-resolves pids at
/// `PID_REFRESH`, and stops early when nothing Lunar-shaped has been alive for
/// `IDLE_GIVE_UP`.
fn sweep(start: Instant, cancel: Arc<AtomicBool>) {
    let home = crate::home().ok();
    let mut budget: HashMap<isize, u8> = HashMap::new();
    // Game pid -> its console window (0 = looked up, none exists). Resolved
    // once per pid: `AttachConsole` is a real syscall pair, not something to
    // run four times a second.
    let mut consoles: HashMap<u32, isize> = HashMap::new();
    let mut launcher: Vec<u32> = Vec::new();
    let mut refreshed: Option<Instant> = None;
    let mut idle_since: Option<Instant> = None;

    while start.elapsed() < SWEEP_WINDOW {
        if cancel.load(Ordering::Relaxed) {
            return;
        }
        if refreshed.is_none_or(|at| at.elapsed() >= PID_REFRESH) {
            launcher = launcher_pids();
            let game = home.as_deref().map(proc::game_jvm_pids).unwrap_or_default();
            for pid in &game {
                consoles
                    .entry(*pid)
                    .or_insert_with(|| console_window_of(*pid));
            }
            refreshed = Some(Instant::now());
            idle_since = match (launcher.is_empty() && game.is_empty(), idle_since) {
                (false, _) => None,
                (true, Some(since)) => Some(since),
                (true, None) => Some(Instant::now()),
            };
        }
        if idle_since.is_some_and(|since| since.elapsed() >= IDLE_GIVE_UP) {
            return;
        }

        let console_hwnds: Vec<isize> = consoles.values().copied().filter(|h| *h != 0).collect();
        for window in top_level_windows() {
            let action = decide(&window, &launcher, &console_hwnds);
            if action == Action::Leave || !spend(&mut budget, window.hwnd) {
                continue;
            }
            apply(window.hwnd, action);
        }
        std::thread::sleep(SWEEP_INTERVAL);
    }
}

/// Lunar launcher pids, with "cannot determine" collapsed to "none". Unlike
/// `lunar_config`, this caller has nothing to lose by guessing wrong: an empty
/// list means the worker hides nothing.
fn launcher_pids() -> Vec<u32> {
    proc::lunar_launcher_pids().unwrap_or_default()
}

/// `SW_SHOWMINNOACTIVE` rather than `SW_MINIMIZE`: it minimizes without
/// activating the next window, so nothing steals focus from the game.
/// `ShowWindowAsync` rather than `ShowWindow`: the target window belongs to
/// another process, and this worker must never block on a busy Lunar UI thread.
fn apply(hwnd: isize, action: Action) {
    let cmd = match action {
        Action::Minimize => SW_SHOWMINNOACTIVE,
        Action::Hide => SW_HIDE,
        Action::Leave => return,
    };
    unsafe { ShowWindowAsync(hwnd as _, cmd) };
}

/// Every top-level window, with the pid that owns it and whether it is on
/// screen. No window text is read: titles can carry a player's name or server,
/// and identity here comes from pids and the console API instead.
fn top_level_windows() -> Vec<Window> {
    let mut found: Vec<Window> = Vec::new();
    // A failed enumeration yields whatever was collected before it stopped,
    // which is exactly as useful as a complete one here.
    unsafe { EnumWindows(Some(collect), &mut found as *mut Vec<Window> as isize) };
    found
}

/// Safety: `lparam` is the `&mut Vec<Window>` passed by `top_level_windows`,
/// which outlives the enumeration it is driving. (Edition 2021: the body of an
/// `unsafe fn` is already an unsafe context, hence no inner blocks.)
unsafe extern "system" fn collect(hwnd: *mut core::ffi::c_void, lparam: isize) -> i32 {
    let found = &mut *(lparam as *mut Vec<Window>);
    let mut pid: u32 = 0;
    GetWindowThreadProcessId(hwnd, &mut pid);
    if pid != 0 {
        found.push(Window {
            hwnd: hwnd as isize,
            pid,
            visible: IsWindowVisible(hwnd) != 0,
        });
    }
    1 // keep enumerating
}

/// The console window owned by `pid`, or 0 when it has none.
///
/// A console window is owned by `conhost.exe`, so window enumeration can never
/// attribute it to the JVM. Attaching to that process's console and asking for
/// its window is the only way to establish the link. The attachment is released
/// immediately; it fails harmlessly (returning 0) when the target has no
/// console - the ordinary `javaw.exe` case - or when this process already has
/// one, which is only true of a debug build.
fn console_window_of(pid: u32) -> isize {
    unsafe {
        if AttachConsole(pid) == 0 {
            return 0;
        }
        let hwnd = GetConsoleWindow();
        FreeConsole();
        hwnd as isize
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn win(hwnd: isize, pid: u32, visible: bool) -> Window {
        Window { hwnd, pid, visible }
    }

    /// The target-formation race: a pid reused between the exe scan and
    /// the birth read binds an UNRELATED birth to a stale verification and
    /// must be refused - by the second scan (unrelated exe) or by the
    /// birth disagreement (Lunar-exe replacement).
    #[test]
    fn target_binding_refuses_reuse_between_observations() {
        // Stable instance: verified twice, same birth -> bound.
        assert_eq!(bind_target(true, 100, Some(100)), Some(100));
        // Reused by an unrelated process: second exe scan fails.
        assert_eq!(bind_target(false, 200, Some(200)), None);
        // Reused by ANOTHER Lunar launcher: births disagree.
        assert_eq!(bind_target(true, 100, Some(300)), None);
        // Vanished before the re-read.
        assert_eq!(bind_target(true, 100, None), None);
    }

    /// The identity-change seam for the destructive close: a pid reused by
    /// an unrelated process, a vanished process, an indeterminate query,
    /// or a window whose owner changed must all be refused at send time.
    #[test]
    fn close_is_refused_unless_owner_and_identity_still_match() {
        use crate::process_liveness::IdentityCheck;
        assert!(confirmed_close_target(10, 10, IdentityCheck::AliveSameIdentity));
        assert!(!confirmed_close_target(10, 10, IdentityCheck::DefinitelyGone));
        assert!(!confirmed_close_target(10, 10, IdentityCheck::Indeterminate));
        assert!(!confirmed_close_target(10, 11, IdentityCheck::AliveSameIdentity));
    }

    #[test]
    fn search_stops_at_the_deadline_only_when_nothing_was_found() {
        let before = SEARCH_DEADLINE - Duration::from_millis(1);
        assert_eq!(search_decision(false, before), SearchDecision::Poll);
        assert_eq!(search_decision(false, SEARCH_DEADLINE), SearchDecision::GiveUp);
    }

    #[test]
    fn a_sighted_launcher_wins_even_past_the_deadline() {
        let late = SEARCH_DEADLINE + Duration::from_secs(5);
        assert_eq!(search_decision(true, late), SearchDecision::Sweep);
    }

    #[test]
    fn launcher_windows_are_minimized_and_console_windows_hidden() {
        let launcher = [4242];
        let consoles = [0x900];
        assert_eq!(
            decide(&win(0x100, 4242, true), &launcher, &consoles),
            Action::Minimize
        );
        // The console's owner is conhost, a pid this worker never resolves -
        // the HWND is the whole identity.
        assert_eq!(
            decide(&win(0x900, 777, true), &launcher, &consoles),
            Action::Hide
        );
    }

    /// The load-bearing negative: the game's own window must survive. It is
    /// owned by the game JVM, whose pid is never in `launcher`, and its HWND is
    /// never the console's.
    #[test]
    fn the_minecraft_window_and_every_stranger_are_left_alone() {
        let launcher = [4242];
        let consoles = [0x900];
        let game_jvm_pid = 5150;
        assert_eq!(
            decide(&win(0x200, game_jvm_pid, true), &launcher, &consoles),
            Action::Leave
        );
        assert_eq!(
            decide(&win(0x300, 1, true), &launcher, &consoles),
            Action::Leave
        );
    }

    #[test]
    fn windows_that_are_not_on_screen_are_left_alone() {
        // Electron keeps offscreen helper windows alive; acting on them is
        // pointless work and would burn the budget of a window that matters.
        assert_eq!(
            decide(&win(0x100, 4242, false), &[4242], &[0x100]),
            Action::Leave
        );
    }

    #[test]
    fn a_window_is_acted_on_at_most_the_budget_and_each_window_is_separate() {
        let mut budget = HashMap::new();
        let spent: Vec<bool> = (0..ACTION_BUDGET + 2).map(|_| spend(&mut budget, 0x100)).collect();
        assert_eq!(
            spent.iter().filter(|allowed| **allowed).count(),
            ACTION_BUDGET as usize,
            "a window is acted on exactly ACTION_BUDGET times, then left to the user"
        );
        assert!(spend(&mut budget, 0x200), "a different window has its own budget");
    }

    /// The console lookup must be safe to call against anything, including a
    /// pid that cannot exist - it runs against every game-JVM pid the sweep
    /// sees, on a machine where the JVM may already have exited.
    #[test]
    fn console_lookup_is_side_effect_free_for_a_dead_pid() {
        assert_eq!(console_window_of(0xFFFF_FFF0), 0);
    }

    /// Runs the real enumeration against this machine and proves the empty
    /// target sets are load-bearing: with no launcher pid and no console HWND,
    /// nothing that exists on this desktop is actionable.
    #[test]
    fn enumeration_targets_nothing_when_there_is_nothing_to_target() {
        assert!(
            top_level_windows()
                .iter()
                .all(|w| decide(w, &[], &[]) == Action::Leave),
            "an empty target set must never act on a window"
        );
    }
}
