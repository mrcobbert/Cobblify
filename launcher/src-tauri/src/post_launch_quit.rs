//! Optional, launch-scoped Lunar cleanup. The action gate belongs only to cleanup;
//! no lifecycle, lobby or progress lock is held while sending or observing a quit.
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Mutex,
};
use std::time::{Duration, Instant, SystemTime};

use crate::launch_watch::GameId;
use crate::proc::LunarIdentity;
use crate::process_liveness::{self, IdentityCheck};

const DEADLINE: Duration = Duration::from_secs(15);
const OBSERVE: Duration = Duration::from_millis(250);
const HELPER_LIMIT: Duration = Duration::from_secs(2);
pub const HELPER_ARG: &str = "--quit-lunar-helper";

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Outcome {
    NotReady,
    Skipped,
    AlreadyGone,
    Exited,
    Refused,
    TimedOut,
    Cancelled,
}

enum Capture {
    Waiting,
    Bound(LunarIdentity),
    Skipped,
}
struct Action {
    capture: Capture,
    result: Option<Outcome>,
}

fn record_capture(
    capture: &mut Capture,
    baseline: SystemTime,
    observed: Result<Option<LunarIdentity>, ()>,
) {
    if !matches!(capture, Capture::Waiting) {
        return;
    }
    *capture = match observed {
        Ok(Some(id))
            if process_liveness::system_time_to_ns(baseline)
                .is_some_and(|base| id.birth_ns >= base) =>
        {
            Capture::Bound(id)
        }
        Ok(None) => return,
        _ => Capture::Skipped,
    };
}

pub struct Session {
    pub generation: u64,
    home: PathBuf,
    baseline: SystemTime,
    cancelled: AtomicBool,
    action: Mutex<Action>,
}

/// Explicit true only: disk state is an eligibility hint, never proof of the actual
/// spawn flags of a running JVM. Unknown schema/defaults are deliberately ineligible.
fn detached(home: &Path) -> bool {
    std::fs::read(crate::lunar_detect::launcher_json(home))
        .ok()
        .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
        .and_then(|v| v.get("settings")?.get("detachGameProcess")?.as_bool())
        == Some(true)
}

impl Session {
    pub fn prepare(home: &Path, generation: u64, enabled: bool) -> Self {
        let capture = if enabled && detached(home) {
            match crate::proc::lunar_main_identity() {
                Ok(Some(id)) => Capture::Bound(id),
                Ok(None) => Capture::Waiting,
                Err(()) => Capture::Skipped,
            }
        } else {
            Capture::Skipped
        };
        Self {
            generation,
            home: home.into(),
            baseline: SystemTime::now(),
            cancelled: AtomicBool::new(false),
            action: Mutex::new(Action {
                capture,
                result: None,
            }),
        }
    }

    /// Called during launcher warm-up, before the play link, and before loaded proof.
    /// Once captured, an updated/restarted Lunar can never replace the original target.
    pub fn observe_launcher(&self) {
        if self.cancelled.load(Ordering::Acquire) {
            return;
        }
        let Ok(mut action) = self.action.try_lock() else {
            return;
        };
        if !matches!(action.capture, Capture::Waiting) || action.result.is_some() {
            return;
        }
        record_capture(
            &mut action.capture,
            self.baseline,
            crate::proc::lunar_main_identity(),
        );
    }

    pub fn finish(&self, game: GameId) -> Outcome {
        self.finish_in(
            game,
            &mut Native {
                home: &self.home,
                start: Instant::now(),
            },
        )
    }

    fn finish_in(&self, game: GameId, env: &mut dyn Env) -> Outcome {
        let mut action = self.action.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(result) = action.result {
            return result;
        }
        let result = match &action.capture {
            Capture::Bound(target) => run(env, target, game, &self.cancelled),
            _ => Outcome::Skipped,
        };
        action.result = Some(result);
        result
    }

    /// Reset calls this before it clears state or publishes Idle. Only the owned helper
    /// can be killed; a graceful request already delivered cannot be undone.
    pub fn cancel_and_drain(&self) {
        self.cancelled.store(true, Ordering::Release);
        let mut action = self.action.lock().unwrap_or_else(|e| e.into_inner());
        action.result.get_or_insert(Outcome::Cancelled);
    }

    pub fn is_drained(&self) -> bool {
        self.action
            .try_lock()
            .is_ok_and(|action| action.result.is_some())
    }
}

trait Env {
    fn now(&self) -> Duration;
    fn eligible(&mut self, target: &LunarIdentity, game: GameId) -> bool;
    fn identity(&mut self, target: &LunarIdentity) -> IdentityCheck;
    fn send(
        &mut self,
        target: &LunarIdentity,
        game: GameId,
        cancel: &AtomicBool,
        remaining: Duration,
    ) -> bool;
    fn wait(&mut self, duration: Duration, cancel: &AtomicBool);
}

fn run(env: &mut dyn Env, target: &LunarIdentity, game: GameId, cancel: &AtomicBool) -> Outcome {
    let start = env.now();
    if cancel.load(Ordering::Acquire) {
        return Outcome::Cancelled;
    }
    match env.identity(target) {
        IdentityCheck::DefinitelyGone => return Outcome::AlreadyGone,
        IdentityCheck::Indeterminate => return Outcome::Skipped,
        IdentityCheck::AliveSameIdentity => {}
    }
    if !env.eligible(target, game) {
        return Outcome::Skipped;
    }
    if cancel.load(Ordering::Acquire) {
        return Outcome::Cancelled;
    }
    let remaining = DEADLINE.saturating_sub(env.now().saturating_sub(start));
    if remaining.is_zero() {
        return Outcome::TimedOut;
    }
    if !env.send(target, game, cancel, remaining.min(HELPER_LIMIT)) {
        return if cancel.load(Ordering::Acquire) {
            Outcome::Cancelled
        } else if env.identity(target) == IdentityCheck::DefinitelyGone {
            Outcome::AlreadyGone
        } else {
            Outcome::Refused
        };
    }
    loop {
        if cancel.load(Ordering::Acquire) {
            return Outcome::Cancelled;
        }
        match env.identity(target) {
            IdentityCheck::DefinitelyGone => return Outcome::Exited,
            IdentityCheck::Indeterminate => return Outcome::Skipped,
            IdentityCheck::AliveSameIdentity => {}
        }
        let elapsed = env.now().saturating_sub(start);
        if elapsed >= DEADLINE {
            return Outcome::TimedOut;
        }
        env.wait(OBSERVE.min(DEADLINE - elapsed), cancel);
    }
}

struct Native<'a> {
    home: &'a Path,
    start: Instant,
}
impl Native<'_> {
    fn eligible_full(&self, target: &LunarIdentity, game: GameId) -> bool {
        if !detached(self.home)
            || process_liveness::check_identity(game.pid, game.birth_ns)
                != IdentityCheck::AliveSameIdentity
        {
            return false;
        }
        if crate::proc::lunar_main_identity().ok().flatten().as_ref() != Some(target) {
            return false;
        }
        let scan = crate::proc::game_jvm_scan(self.home);
        scan.complete
            && scan.pids.contains(&game.pid)
            && scan.pids.iter().all(|pid| *pid == game.pid)
            && process_liveness::check_identity(game.pid, game.birth_ns)
                == IdentityCheck::AliveSameIdentity
            && process_liveness::check_identity(target.pid, target.birth_ns)
                == IdentityCheck::AliveSameIdentity
    }
}
impl Env for Native<'_> {
    fn now(&self) -> Duration {
        self.start.elapsed()
    }
    fn identity(&mut self, id: &LunarIdentity) -> IdentityCheck {
        process_liveness::check_identity(id.pid, id.birth_ns)
    }
    fn eligible(&mut self, _: &LunarIdentity, game: GameId) -> bool {
        // Full scans/config reads run inside the owned, deadline-bound helper.
        process_liveness::check_identity(game.pid, game.birth_ns)
            == IdentityCheck::AliveSameIdentity
    }
    fn send(
        &mut self,
        target: &LunarIdentity,
        game: GameId,
        cancel: &AtomicBool,
        remaining: Duration,
    ) -> bool {
        let Ok(exe) = std::env::current_exe() else {
            return false;
        };
        let Ok(helper) = Command::new(exe)
            .arg(HELPER_ARG)
            .arg(target.pid.to_string())
            .arg(target.birth_ns.to_string())
            .arg(&target.exe)
            .arg(game.pid.to_string())
            .arg(game.birth_ns.to_string())
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
        else {
            return false;
        };
        wait_helper(helper, cancel, remaining)
    }
    fn wait(&mut self, duration: Duration, cancel: &AtomicBool) {
        let deadline = Instant::now() + duration;
        while !cancel.load(Ordering::Acquire) && Instant::now() < deadline {
            std::thread::sleep(
                Duration::from_millis(20).min(deadline.saturating_duration_since(Instant::now())),
            );
        }
    }
}

fn wait_helper(mut helper: std::process::Child, cancel: &AtomicBool, remaining: Duration) -> bool {
    let deadline = Instant::now() + remaining;
    loop {
        match helper.try_wait() {
            Ok(Some(status)) => return status.success(),
            Err(_) => {
                let _ = helper.kill();
                let _ = helper.wait();
                return false;
            }
            Ok(None) => {}
        }
        if cancel.load(Ordering::Acquire) || Instant::now() >= deadline {
            let _ = helper.kill();
            let _ = helper.wait();
            return false;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

/// CLI-only child entry point, before Tauri initialization or single-instance forwarding.
/// URL routing on Windows remains best effort: a process replacement between this final
/// check and shell dispatch cannot be atomically excluded. Never retry the URI.
pub fn helper(args: &[std::ffi::OsString]) -> i32 {
    if args.len() != 7 {
        return 1;
    }
    let Some(pid) = args[2].to_str().and_then(|s| s.parse().ok()) else {
        return 1;
    };
    let Some(birth_ns) = args[3].to_str().and_then(|s| s.parse().ok()) else {
        return 1;
    };
    let target = LunarIdentity {
        pid,
        birth_ns,
        exe: PathBuf::from(&args[4]),
    };
    let Some(game_pid) = args[5].to_str().and_then(|s| s.parse().ok()) else {
        return 1;
    };
    let Some(game_birth) = args[6].to_str().and_then(|s| s.parse().ok()) else {
        return 1;
    };
    let Ok(home) = crate::home() else {
        return 1;
    };
    if !(Native {
        home: &home,
        start: Instant::now(),
    })
    .eligible_full(
        &target,
        GameId {
            pid: game_pid,
            birth_ns: game_birth,
        },
    ) {
        return 1;
    }
    if process_liveness::check_identity(game_pid, game_birth) != IdentityCheck::AliveSameIdentity
        || process_liveness::check_identity(pid, birth_ns) != IdentityCheck::AliveSameIdentity
    {
        return 1;
    }
    #[cfg(target_os = "macos")]
    {
        return if native_mac_quit(pid, birth_ns) { 0 } else { 1 };
    }
    #[cfg(windows)]
    {
        return if tauri_plugin_opener::open_url(crate::launch::CLOSE_LAUNCHER, None::<&str>).is_ok()
        {
            0
        } else {
            1
        };
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        1
    }
}

/// Direct AppKit in the owned helper: spawning osascript here would leave an
/// unbounded grandchild after a helper timeout. Native calls leave no child behind.
#[cfg(target_os = "macos")]
fn native_mac_quit(pid: u32, birth: process_liveness::EpochNs) -> bool {
    use objc2::{
        class, msg_send,
        rc::autoreleasepool,
        runtime::{AnyObject, Bool},
    };
    if process_liveness::check_identity(pid, birth) != IdentityCheck::AliveSameIdentity {
        return false;
    }
    let Ok(pid) = i32::try_from(pid) else {
        return false;
    };
    autoreleasepool(|_| unsafe {
        let app: *mut AnyObject =
            msg_send![class!(NSRunningApplication), runningApplicationWithProcessIdentifier: pid];
        if app.is_null() {
            return false;
        }
        let bundle: *mut AnyObject = msg_send![app, bundleIdentifier];
        if bundle.is_null() {
            return false;
        }
        let name: *const std::ffi::c_char = msg_send![bundle, UTF8String];
        if name.is_null() || std::ffi::CStr::from_ptr(name).to_bytes() != b"com.moonsworth.client" {
            return false;
        }
        let actual: i32 = msg_send![app, processIdentifier];
        if actual != pid
            || process_liveness::check_identity(pid as u32, birth)
                != IdentityCheck::AliveSameIdentity
        {
            return false;
        }
        let delivered: Bool = msg_send![app, terminate];
        delivered.as_bool()
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Fake {
        at: Duration,
        sends: usize,
        eligible: bool,
        identity: IdentityCheck,
        exit_at: Option<Duration>,
    }
    impl Env for Fake {
        fn now(&self) -> Duration {
            self.at
        }
        fn eligible(&mut self, _: &LunarIdentity, _: GameId) -> bool {
            self.eligible
        }
        fn identity(&mut self, _: &LunarIdentity) -> IdentityCheck {
            if self.exit_at.is_some_and(|at| self.at >= at) {
                IdentityCheck::DefinitelyGone
            } else {
                self.identity
            }
        }
        fn send(&mut self, _: &LunarIdentity, _: GameId, _: &AtomicBool, _: Duration) -> bool {
            self.sends += 1;
            true
        }
        fn wait(&mut self, d: Duration, _: &AtomicBool) {
            self.at += d;
        }
    }
    fn world() -> (Fake, LunarIdentity, GameId, AtomicBool) {
        (
            Fake {
                at: Duration::ZERO,
                sends: 0,
                eligible: true,
                identity: IdentityCheck::AliveSameIdentity,
                exit_at: None,
            },
            LunarIdentity {
                pid: 10,
                birth_ns: 1,
                exe: "Lunar".into(),
            },
            GameId {
                pid: 20,
                birth_ns: 2,
            },
            AtomicBool::new(false),
        )
    }
    #[test]
    fn exits_on_first_observation_without_a_pre_quit_delay() {
        let (mut env, id, game, cancel) = world();
        env.exit_at = Some(Duration::from_millis(250));
        assert_eq!(run(&mut env, &id, game, &cancel), Outcome::Exited);
        assert_eq!(env.sends, 1);
        assert_eq!(env.at, Duration::from_millis(250));
    }
    #[test]
    fn uncertain_or_gone_launchers_never_dispatch() {
        for (identity, outcome) in [
            (IdentityCheck::Indeterminate, Outcome::Skipped),
            (IdentityCheck::DefinitelyGone, Outcome::AlreadyGone),
        ] {
            let (mut env, id, game, cancel) = world();
            env.identity = identity;
            assert_eq!(run(&mut env, &id, game, &cancel), outcome);
            assert_eq!(env.sends, 0);
        }
    }
    #[test]
    fn cancellation_and_failed_eligibility_never_send() {
        let (mut env, id, game, cancel) = world();
        cancel.store(true, Ordering::Release);
        assert_eq!(run(&mut env, &id, game, &cancel), Outcome::Cancelled);
        assert_eq!(env.sends, 0);
        cancel.store(false, Ordering::Release);
        env.eligible = false;
        assert_eq!(run(&mut env, &id, game, &cancel), Outcome::Skipped);
        assert_eq!(env.sends, 0);
    }
    #[test]
    fn a_delivered_request_is_not_exit_and_never_retries() {
        let (mut env, id, game, cancel) = world();
        assert_eq!(run(&mut env, &id, game, &cancel), Outcome::TimedOut);
        assert_eq!(env.at, DEADLINE);
        assert_eq!(env.sends, 1);
    }

    #[test]
    fn duplicate_finalization_and_reset_are_terminal() {
        let (mut env, id, game, _) = world();
        env.exit_at = Some(Duration::ZERO);
        let session = Session {
            generation: 5,
            home: PathBuf::new(),
            baseline: SystemTime::now(),
            cancelled: AtomicBool::new(false),
            action: Mutex::new(Action {
                capture: Capture::Bound(id),
                result: None,
            }),
        };
        assert_eq!(session.finish_in(game, &mut env), Outcome::AlreadyGone);
        env.identity = IdentityCheck::AliveSameIdentity;
        env.exit_at = None;
        assert_eq!(session.finish_in(game, &mut env), Outcome::AlreadyGone);
        session.cancel_and_drain();
        assert_eq!(session.finish_in(game, &mut env), Outcome::AlreadyGone);
        assert_eq!(env.sends, 0, "a later reopened launcher never gets a retry");
    }

    #[test]
    fn detach_requires_explicit_true_in_the_current_settings_shape() {
        let home = tempfile::tempdir().unwrap();
        let path = crate::lunar_detect::launcher_json(home.path());
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        for text in [
            "{}",
            r#"{"settings":{}}"#,
            r#"{"settings":{"detachGameProcess":false}}"#,
            r#"{"settings":{"detachGameProcess":"true"}}"#,
            "{broken",
        ] {
            std::fs::write(&path, text).unwrap();
            assert!(!detached(home.path()));
        }
        std::fs::write(&path, r#"{"settings":{"detachGameProcess":true}}"#).unwrap();
        assert!(detached(home.path()));
    }

    #[test]
    fn cold_capture_rejects_incomplete_old_and_replacement_observations() {
        let (_, id, _, _) = world();
        let baseline = std::time::UNIX_EPOCH;
        let mut capture = Capture::Waiting;
        record_capture(&mut capture, baseline, Ok(None));
        assert!(matches!(capture, Capture::Waiting));
        record_capture(&mut capture, baseline, Ok(Some(id.clone())));
        let replacement = LunarIdentity {
            pid: id.pid + 1,
            ..id.clone()
        };
        record_capture(&mut capture, baseline, Ok(Some(replacement)));
        assert!(matches!(capture, Capture::Bound(ref original) if original == &id));
        let mut uncertain = Capture::Waiting;
        record_capture(&mut uncertain, baseline, Err(()));
        record_capture(&mut uncertain, baseline, Ok(Some(id.clone())));
        assert!(matches!(uncertain, Capture::Skipped));
        let mut old = Capture::Waiting;
        record_capture(&mut old, baseline + Duration::from_secs(1), Ok(Some(id)));
        assert!(matches!(old, Capture::Skipped));
    }

    #[test]
    fn reset_cancels_and_drains_an_active_send_before_returning() {
        use std::sync::{mpsc, Arc};
        struct Sending {
            entered: mpsc::SyncSender<()>,
            finished: Arc<AtomicBool>,
        }
        impl Env for Sending {
            fn now(&self) -> Duration {
                Duration::ZERO
            }
            fn eligible(&mut self, _: &LunarIdentity, _: GameId) -> bool {
                true
            }
            fn identity(&mut self, _: &LunarIdentity) -> IdentityCheck {
                IdentityCheck::AliveSameIdentity
            }
            fn send(
                &mut self,
                _: &LunarIdentity,
                _: GameId,
                cancel: &AtomicBool,
                _: Duration,
            ) -> bool {
                self.entered.send(()).unwrap();
                let start = Instant::now();
                while !cancel.load(Ordering::Acquire) {
                    assert!(
                        start.elapsed() < Duration::from_secs(2),
                        "reset did not cancel pending send"
                    );
                    std::thread::yield_now();
                }
                self.finished.store(true, Ordering::Release);
                false
            }
            fn wait(&mut self, _: Duration, _: &AtomicBool) {
                unreachable!()
            }
        }
        let (_, id, game, _) = world();
        let session = Arc::new(Session {
            generation: 5,
            home: PathBuf::new(),
            baseline: SystemTime::now(),
            cancelled: AtomicBool::new(false),
            action: Mutex::new(Action {
                capture: Capture::Bound(id),
                result: None,
            }),
        });
        let (send, receive) = mpsc::sync_channel(0);
        let finished = Arc::new(AtomicBool::new(false));
        let worker_session = Arc::clone(&session);
        let worker_finished = Arc::clone(&finished);
        let worker = std::thread::spawn(move || {
            worker_session.finish_in(
                game,
                &mut Sending {
                    entered: send,
                    finished: worker_finished,
                },
            )
        });
        receive.recv_timeout(Duration::from_secs(2)).unwrap();
        session.cancel_and_drain();
        assert!(
            finished.load(Ordering::Acquire),
            "drain cannot return while the send is still active"
        );
        assert_eq!(worker.join().unwrap(), Outcome::Cancelled);
        assert!(session.is_drained());
    }

    fn sleeper() -> std::process::Child {
        #[cfg(unix)]
        let mut command = Command::new("/bin/sleep");
        #[cfg(unix)]
        command.arg("30");
        #[cfg(windows)]
        let mut command = {
            use std::os::windows::process::CommandExt;
            let mut c = Command::new("powershell.exe");
            c.args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Start-Sleep -Seconds 30",
            ]);
            c.creation_flags(0x08000000);
            c
        };
        command
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .unwrap()
    }

    #[test]
    fn deadline_and_cancellation_reap_only_the_owned_helper() {
        struct Canary(std::process::Child);
        impl Drop for Canary {
            fn drop(&mut self) {
                let _ = self.0.kill();
                let _ = self.0.wait();
            }
        }
        let mut unrelated = Canary(sleeper());
        for cancelled in [false, true] {
            let owned = sleeper();
            let pid = owned.id();
            let birth = process_liveness::process_birth_ns(pid).unwrap();
            assert!(!wait_helper(
                owned,
                &AtomicBool::new(cancelled),
                Duration::from_millis(50)
            ));
            assert_eq!(
                process_liveness::check_identity(pid, birth),
                IdentityCheck::DefinitelyGone
            );
            assert!(
                unrelated.0.try_wait().unwrap().is_none(),
                "same-name unrelated process must survive"
            );
        }
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn native_quit_never_targets_this_unbundled_test_process() {
        let pid = std::process::id();
        let birth = process_liveness::process_birth_ns(pid).unwrap();
        assert!(!native_mac_quit(pid, birth));
    }
}
