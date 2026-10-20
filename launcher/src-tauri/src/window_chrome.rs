//! Native title-bar geometry. Only main HTML content reserves this inset.
use serde::Serialize;
use std::sync::{Arc, Mutex};

pub const EVENT: &str = "window-chrome://changed";

#[derive(Clone, Copy, Debug, Serialize)]
pub struct Snapshot {
    pub revision: u64,
    pub inset: f64,
    pub phase: &'static str,
}

struct State {
    snapshot: Snapshot,
    normal: f64,
    transition: Option<bool>, // target fullscreen state
    epoch: u64,
    alive: bool,
}

impl State {
    fn new(normal: f64) -> Self {
        Self { snapshot: Snapshot { revision: 0, inset: normal, phase: "stable" }, normal,
            transition: None, epoch: 0, alive: true }
    }
    fn set(&mut self, inset: f64, phase: &'static str) -> Option<Snapshot> {
        if !self.alive || !inset.is_finite() || inset < 0.0 { return None; }
        if self.snapshot.inset == inset && self.snapshot.phase == phase { return None; }
        self.snapshot = Snapshot { revision: self.snapshot.revision + 1, inset, phase };
        Some(self.snapshot)
    }
    fn begin(&mut self, full: bool) -> Option<Snapshot> {
        self.epoch += 1;
        self.transition = Some(full);
        self.set(if full { 0.0 } else { self.normal }, if full { "entering" } else { "exiting" })
    }
    fn reconcile(&mut self, geometry: Option<f64>) -> Option<Snapshot> {
        self.epoch += 1;
        self.transition = None;
        let inset = geometry.unwrap_or(self.snapshot.inset);
        if inset > 0.0 { self.normal = inset; }
        self.set(inset, "stable")
    }
    fn recover(&mut self, epoch: u64, geometry: Option<f64>) -> Option<Snapshot> {
        if !self.alive || self.epoch != epoch || self.transition.is_none() { return None; }
        self.reconcile(geometry)
    }
    fn resized(&mut self, geometry: Option<f64>) -> Option<Snapshot> {
        // AppKit may change styleMask before its layout catches up. The early
        // target owns spacing until completion (or bounded failure recovery).
        if self.transition.is_some() { return None; }
        self.reconcile(geometry)
    }

}

pub struct WindowChrome(Arc<Mutex<State>>);
impl WindowChrome {
    pub fn snapshot(&self) -> Snapshot { self.0.lock().unwrap().snapshot }
}

pub fn install(window: &tauri::WebviewWindow, normal: f64) -> Result<WindowChrome, Box<dyn std::error::Error>> {
    let chrome = WindowChrome(Arc::new(Mutex::new(State::new(normal))));
    #[cfg(target_os = "macos")]
    native::install(window, Arc::clone(&chrome.0))?;
    #[cfg(not(target_os = "macos"))]
    let _ = window;
    Ok(chrome)
}

#[cfg(target_os = "macos")]
mod native {
    use super::*;
    use block2::RcBlock;
    use objc2::{rc::Retained, runtime::ProtocolObject, MainThreadMarker};
    use objc2_app_kit::{NSWindow, NSWindowWillEnterFullScreenNotification,
        NSWindowDidEnterFullScreenNotification, NSWindowWillExitFullScreenNotification,
        NSWindowDidExitFullScreenNotification, NSWindowDidResizeNotification,
        NSWindowDidChangeScreenNotification, NSWindowDidChangeBackingPropertiesNotification,
        NSWindowWillCloseNotification};
    use objc2_foundation::{NSNotification, NSNotificationCenter, NSObjectProtocol};
    use std::{cell::RefCell, collections::HashMap, ptr::NonNull, time::Duration};
    use tauri::{Emitter, Manager};

    // ObjC tokens never cross threads. Removed on this NSWindow's will-close notification.
    struct Observers(Vec<Retained<ProtocolObject<dyn NSObjectProtocol>>>);
    impl Drop for Observers {
        fn drop(&mut self) {
            let center = NSNotificationCenter::defaultCenter();
            for token in &self.0 { unsafe { center.removeObserver(token.as_ref()); } }
        }
    }
    thread_local! { static OBSERVERS: RefCell<HashMap<usize, Observers>> = RefCell::new(HashMap::new()); }

    fn geometry(window: &NSWindow) -> Option<f64> {
        let view = window.contentView()?;
        let bounds = view.convertRect_toView(view.bounds(), None);
        let layout = window.contentLayoutRect();
        let top = (bounds.origin.y + bounds.size.height) - (layout.origin.y + layout.size.height);
        (top.is_finite() && top >= 0.0 && top <= bounds.size.height).then_some(top)
    }
    fn publish(app: &tauri::AppHandle, label: &str, snapshot: Option<Snapshot>) {
        if let Some(snapshot) = snapshot { let _ = app.emit_to(label, EVENT, snapshot); }
    }
    fn recover_later(app: tauri::AppHandle, label: String, state: Arc<Mutex<State>>, epoch: u64) {
        // AppKit exposes failure delegate callbacks, not failure notifications.
        // Reconcile a missing completion without taking over Tao's delegate.
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_secs(3));
            let handle = app.clone();
            let _ = app.run_on_main_thread(move || {
                { let s = state.lock().unwrap();
                  if !s.alive || s.epoch != epoch || s.transition.is_none() { return; } }
                let Some(window) = handle.get_webview_window(&label) else { return; };
                let Ok(ptr) = window.ns_window() else { return; };
                // The live Tauri window owns this NSWindow; this closure is on AppKit's main thread.
                let measured = geometry(unsafe { &*(ptr as *const NSWindow) });
                let snapshot = { let mut s = state.lock().unwrap();
                    s.recover(epoch, measured)
                };
                publish(&handle, &label, snapshot);
            });
        });
    }

    pub(super) fn install(window: &tauri::WebviewWindow, state: Arc<Mutex<State>>) -> Result<(), Box<dyn std::error::Error>> {
        let _main = MainThreadMarker::new().ok_or_else(|| std::io::Error::other("window chrome must install on the main thread"))?;
        let ptr = window.ns_window()?;
        // Tauri owns the live NSWindow. Only borrow it during main-thread registration.
        let ns = unsafe { &*(ptr as *const NSWindow) };
        let key = ptr as usize;
        let center = NSNotificationCenter::defaultCenter();
        let mut tokens = Vec::new();
        // The object filter ensures callbacks belong to this exact window.
        let notifications = unsafe { [
            (NSWindowWillEnterFullScreenNotification, 0), (NSWindowWillExitFullScreenNotification, 1),
            (NSWindowDidEnterFullScreenNotification, 2), (NSWindowDidExitFullScreenNotification, 2),
            (NSWindowDidResizeNotification, 3), (NSWindowDidChangeScreenNotification, 3),
            (NSWindowDidChangeBackingPropertiesNotification, 3), (NSWindowWillCloseNotification, 4),
        ] };
        for (name, kind) in notifications {
            let app = window.app_handle().clone();
            let label = window.label().to_owned();
            let shared = Arc::clone(&state);
            let callback = RcBlock::new(move |note: NonNull<NSNotification>| {
                // NSWindow notifications are delivered synchronously on AppKit's main thread.
                if MainThreadMarker::new().is_none() { return; }
                let Some(object) = (unsafe { note.as_ref() }).object() else { return; };
                if (&*object as *const _ as usize) != key { return; }
                if kind == 4 {
                    shared.lock().unwrap().alive = false;
                    OBSERVERS.with(|all| { all.borrow_mut().remove(&key); });
                    return;
                }
                // The scoped notification retains its NSWindow during this callback.
                let native = unsafe { &*((&*object as *const _) as *const NSWindow) };
                let measured = geometry(native);
                let (snapshot, epoch) = {
                    let mut s = shared.lock().unwrap();
                    if !s.alive { return; }
                    let value = match kind { 0 => s.begin(true), 1 => s.begin(false),
                        2 => s.reconcile(measured), _ => s.resized(measured) };
                    (value, s.epoch)
                };
                // Never hold the cache mutex across IPC emission or main-thread scheduling.
                publish(&app, &label, snapshot);
                if kind < 2 { recover_later(app.clone(), label.clone(), Arc::clone(&shared), epoch); }
            });
            // No queue: keep notification ordering on the posting (main) thread. The block
            // captures only thread-safe Rust values, never a retained NSWindow or observer.
            let token = unsafe { center.addObserverForName_object_queue_usingBlock(Some(name), Some(ns.as_ref()), None, &callback) };
            tokens.push(token);
        }
        let measured = geometry(ns);
        state.lock().unwrap().reconcile(measured);
        OBSERVERS.with(|all| { all.borrow_mut().insert(key, Observers(tokens)); });
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exit_target_precedes_old_fullscreen_geometry() {
        let mut s = State::new(32.0);
        s.begin(true);
        assert!(s.resized(Some(32.0)).is_none(), "intermediate geometry cannot undo entry");
        assert_eq!(s.snapshot.inset, 0.0);
        s.reconcile(Some(0.0));
        let early = s.begin(false).unwrap();
        assert_eq!(early.inset, 32.0);
        assert_eq!(early.phase, "exiting");
        assert!(s.resized(Some(0.0)).is_none());
        assert_eq!(s.snapshot.inset, 32.0);
        s.resized(Some(32.0));
        let done = s.reconcile(Some(32.0)).unwrap();
        assert_eq!(done.inset, early.inset);
        assert!(done.revision > early.revision);
    }
    #[test]
    fn reversed_or_failed_transition_can_reconcile() {
        let mut s = State::new(32.0);
        s.begin(true); let old = s.epoch;
        s.begin(false);
        assert!(s.recover(old, Some(0.0)).is_none(), "old recovery cannot own a later transition");
        assert_eq!(s.snapshot.inset, 32.0);
        s.recover(s.epoch, Some(0.0));
        assert_eq!(s.snapshot.phase, "stable");
        assert_eq!(s.snapshot.inset, 0.0);
        assert_eq!(s.begin(false).unwrap().inset, 32.0);
        s.alive = false;
        assert!(s.recover(s.epoch, Some(0.0)).is_none(), "closed windows cannot recover");
    }
}
