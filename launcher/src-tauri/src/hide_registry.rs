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
