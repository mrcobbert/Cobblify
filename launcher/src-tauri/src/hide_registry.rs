//! App-wide registry of cancellable hide workers.
//!
//! Every hide-worker spawn registers here so a new dispatch can cancel any
//! prior click's still-running worker before it spawns or re-presents
//! anything (cross-generation isolation), and so the bare-link route can
//! cancel its own worker before re-presenting the launcher. The registry
//! holds no deferred work of its own: cancelling is flag + join, nothing is
//! ever scheduled.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

struct Entry {
    cancel: Arc<AtomicBool>,
    handle: JoinHandle<()>,
}

static REGISTRY: OnceLock<Mutex<Vec<Entry>>> = OnceLock::new();

fn registry() -> &'static Mutex<Vec<Entry>> {
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

pub fn register(cancel: Arc<AtomicBool>, handle: JoinHandle<()>) {
    registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .push(Entry { cancel, handle });
}

#[cfg(test)]
pub fn registered_count() -> usize {
    registry().lock().unwrap_or_else(|e| e.into_inner()).len()
}

/// Serializes tests that touch the process-global registry.
#[cfg(test)]
pub static REGISTRY_TEST_LOCK: Mutex<()> = Mutex::new(());

/// Run one native pass on a helper thread and wait at most `bound` for it.
/// Returns true when the pass finished inside the bound. The caller's reply
/// path never blocks on a hung native call (the stray thread finishes on
/// its own - hung-pass residual, cosmetic); nothing is scheduled beyond the
/// pass itself.
pub fn run_pass_bounded(pass: impl FnOnce() + Send + 'static, bound: Duration) -> bool {
    let (tx, rx) = std::sync::mpsc::channel();
    let spawned = std::thread::Builder::new()
        .name("bounded-pass".into())
        .spawn(move || {
            pass();
            let _ = tx.send(());
        })
        .is_ok();
    if !spawned {
        return false;
    }
    rx.recv_timeout(bound).is_ok()
}

const DP_INIT: u8 = 0;
const DP_CANCELLED: u8 = 1;
const DP_COMMITTED: u8 = 2;

/// Run a DESTRUCTIVE pass with a strict no-late-execution contract. The
/// pass must call `commit` immediately before its destructive step and
/// proceed only when it returns true. On timeout the caller cancels: a
/// pass that has not yet committed can then NEVER act - its commit gate
/// returns false forever. If the pass had already committed, the caller
/// waits `grace` more for completion (destructive passes are internally
/// bounded); only a doubly-pathological hang (committed AND blowing its
/// own internal bounds) can leave work running past the return, and the
/// false return reports exactly that.
pub fn run_destructive_bounded(
    pass: impl FnOnce(&dyn Fn() -> bool) + Send + 'static,
    bound: Duration,
    grace: Duration,
) -> bool {
    use std::sync::atomic::AtomicU8;
    let state = Arc::new(AtomicU8::new(DP_INIT));
    let (tx, rx) = std::sync::mpsc::channel();
    let thread_state = Arc::clone(&state);
    let spawned = std::thread::Builder::new()
        .name("destructive-pass".into())
        .spawn(move || {
            let gate = || {
                thread_state
                    .compare_exchange(DP_INIT, DP_COMMITTED, Ordering::SeqCst, Ordering::SeqCst)
                    .is_ok()
                    || thread_state.load(Ordering::SeqCst) == DP_COMMITTED
            };
            pass(&gate);
            let _ = tx.send(());
        })
        .is_ok();
    if !spawned {
        return false;
    }
    if rx.recv_timeout(bound).is_ok() {
        return true;
    }
    if state
        .compare_exchange(DP_INIT, DP_CANCELLED, Ordering::SeqCst, Ordering::SeqCst)
        .is_ok()
    {
        // Never committed, never can: no destructive work runs after this.
        return false;
    }
    // Already committed: wait out the pass's own internal bounds.
    rx.recv_timeout(grace).is_ok()
}

/// Set every registered worker's cancel flag and wait up to `bound` for the
/// threads to exit, reaping finished ones. Returns true when the registry
/// drained within the bound; false leaves the cancelled stragglers
/// registered so a later call can reap them. Quiescence (happens-before) is
/// established only for workers actually joined here.
pub fn cancel_all_and_wait(bound: Duration) -> bool {
    let deadline = Instant::now() + bound;
    {
        let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
        for entry in reg.iter() {
            entry.cancel.store(true, Ordering::Relaxed);
        }
    }
    loop {
        {
            let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
            let mut idx = 0;
            while idx < reg.len() {
                if reg[idx].handle.is_finished() {
                    let entry = reg.swap_remove(idx);
                    let _ = entry.handle.join();
                } else {
                    idx += 1;
                }
            }
            if reg.is_empty() {
                return true;
            }
        }
        if Instant::now() >= deadline {
            return false;
        }
        std::thread::sleep(Duration::from_millis(25));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cancel_drains_a_cooperative_worker_and_empty_registry_is_instant() {
        let _guard = REGISTRY_TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        assert!(cancel_all_and_wait(Duration::from_millis(50)));
        let cancel = Arc::new(AtomicBool::new(false));
        let flag = Arc::clone(&cancel);
        let handle = std::thread::spawn(move || {
            while !flag.load(Ordering::Relaxed) {
                std::thread::sleep(Duration::from_millis(10));
            }
        });
        register(cancel, handle);
        assert!(cancel_all_and_wait(Duration::from_secs(2)));
        assert_eq!(registered_count(), 0);
    }

    #[test]
    fn bounded_pass_replies_by_the_deadline_even_when_the_native_call_hangs() {
        assert!(run_pass_bounded(|| {}, Duration::from_secs(2)), "fast pass completes");
        let started = Instant::now();
        let finished = run_pass_bounded(
            || std::thread::sleep(Duration::from_secs(10)),
            Duration::from_millis(100),
        );
        assert!(!finished, "hung pass reports unfinished");
        assert!(
            started.elapsed() < Duration::from_secs(5),
            "the caller is released at the bound, not the pass duration"
        );
    }

    #[test]
    fn destructive_pass_commits_and_completes_on_the_fast_path() {
        let acted = Arc::new(AtomicBool::new(false));
        let a = Arc::clone(&acted);
        let finished = run_destructive_bounded(
            move |commit| {
                if commit() {
                    a.store(true, Ordering::SeqCst);
                }
            },
            Duration::from_secs(2),
            Duration::from_secs(2),
        );
        assert!(finished);
        assert!(acted.load(Ordering::SeqCst));
    }

    /// The no-late-execution contract: a pass that has not committed when
    /// the bound expires is refused at its gate and NEVER acts.
    #[test]
    fn timed_out_uncommitted_pass_can_never_act() {
        let acted = Arc::new(AtomicBool::new(false));
        let release = Arc::new(AtomicBool::new(false));
        let (done_tx, done_rx) = std::sync::mpsc::channel();
        let a = Arc::clone(&acted);
        let r = Arc::clone(&release);
        let finished = run_destructive_bounded(
            move |commit| {
                // Hung BEFORE the commit point (e.g. a stuck pid lookup).
                while !r.load(Ordering::SeqCst) {
                    std::thread::sleep(Duration::from_millis(10));
                }
                if commit() {
                    a.store(true, Ordering::SeqCst);
                }
                let _ = done_tx.send(());
            },
            Duration::from_millis(80),
            Duration::from_millis(80),
        );
        assert!(!finished, "bound expired before commit");
        release.store(true, Ordering::SeqCst);
        done_rx
            .recv_timeout(Duration::from_secs(2))
            .expect("pass finishes after release");
        assert!(!acted.load(Ordering::SeqCst), "gate refused: no late destruction");
    }

    /// A committed pass that runs past the bound is waited out by the
    /// grace window rather than abandoned mid-destruction.
    #[test]
    fn committed_pass_is_waited_out_by_the_grace_window() {
        let acted = Arc::new(AtomicBool::new(false));
        let a = Arc::clone(&acted);
        let finished = run_destructive_bounded(
            move |commit| {
                if commit() {
                    std::thread::sleep(Duration::from_millis(150));
                    a.store(true, Ordering::SeqCst);
                }
            },
            Duration::from_millis(30),
            Duration::from_secs(5),
        );
        assert!(finished, "completed within the grace window");
        assert!(acted.load(Ordering::SeqCst));
    }

    #[test]
    fn a_blocked_worker_times_out_but_stays_registered_for_later_reaping() {
        let _guard = REGISTRY_TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let cancel = Arc::new(AtomicBool::new(false));
        let gate = Arc::new(AtomicBool::new(false));
        let gate2 = Arc::clone(&gate);
        let handle = std::thread::spawn(move || {
            // Ignores its cancel flag until the gate opens - a stand-in for
            // a pass hung inside a native call.
            while !gate2.load(Ordering::Relaxed) {
                std::thread::sleep(Duration::from_millis(10));
            }
        });
        register(cancel, handle);
        assert!(!cancel_all_and_wait(Duration::from_millis(100)));
        gate.store(true, Ordering::Relaxed);
        assert!(cancel_all_and_wait(Duration::from_secs(2)));
    }
}
