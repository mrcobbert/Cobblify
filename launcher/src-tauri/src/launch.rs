//! Launch routes, preflight, and native dispatch.

use std::path::Path;
use std::process::Command;
use std::time::SystemTime;

use serde::Serialize;

use crate::forge;
use crate::lobby::preexisting_writer;
use crate::preferences::{LaunchPreferencesView, LockedPrefs};

/// Official Lunar play deep link when auto-join is on.
pub const PLAY_HYPIXEL: &str = "lunarclient://play?serverAddress=play.hypixel.net";

/// Bare play link (auto-join off): launches to the title screen against a
/// WARM launcher. Undocumented handler behavior, verified live 2026-08-20;
/// must never carry parameters and never replaces the serverAddress route.
pub const PLAY_BARE: &str = "lunarclient://play";

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchOutcome {
    pub auto_join_hypixel: bool,
    pub use_external_overlay: bool,
    pub action: LaunchAction,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum LaunchAction {
    GameLaunchRequested,
    LauncherOpened,
    /// Bare link fired but not confirmed from the log. Truthful third state:
    /// the game may or may not be launching; the frontend shows conditional
    /// copy and never an unconditional press-Play instruction.
    LaunchUnconfirmed,
}

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum LaunchReply {
    Launched {
        outcome: LaunchOutcome,
        generation: u64,
    },
    PreexistingGame {
        preferences: LaunchPreferencesView,
    },
    Rejected {
        code: &'static str,
        #[serde(skip_serializing_if = "Option::is_none")]
        preferences: Option<LaunchPreferencesView>,
        #[serde(skip_serializing_if = "Option::is_none")]
        message: Option<String>,
    },
}

/// Internal launch result carrying the pre-dispatch freshness baseline.
pub struct LaunchAttempt {
    pub reply: LaunchReply,
    pub baseline: Option<SystemTime>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LaunchKind {
    Lunar,
    Forge,
}

pub struct DispatchResult {
    pub baseline: SystemTime,
    pub outcome: LaunchOutcome,
}

pub fn dispatch(
    home: &Path,
    kind: LaunchKind,
    auto_join: bool,
    use_external_overlay: bool,
) -> Result<DispatchResult, String> {
    // Cross-generation isolation: a previous click's still-running hide
    // worker (Windows sweeps up to 150s) must never act on this click's
    // windows. Runs on every admission, including launches later rejected.
    crate::hide_registry::cancel_all_and_wait(std::time::Duration::from_secs(2));
    match kind {
        LaunchKind::Lunar => {
            let baseline = SystemTime::now();
            dispatch_lunar(auto_join, use_external_overlay, baseline)
        }
        LaunchKind::Forge => dispatch_forge(home, auto_join, use_external_overlay),
    }
}

/// Injected effects for one auto-join click, so the ordering and the error
/// branch are pinned by tests without firing a real deep link.
pub struct AutoJoinDeps<'a> {
    /// Start the registered cancellable hide worker.
    pub spawn_hide: &'a mut dyn FnMut(),
    /// Fire the auto-join deep link once. Err = nothing was started.
    pub open_link: &'a mut dyn FnMut() -> Result<(), String>,
    /// Cancel the just-spawned worker; error path only.
    pub cancel_hide: &'a mut dyn FnMut(),
}

/// Hide FIRST, then open. Opening the deep link brings Lunar's launcher
/// window up, and a worker started afterwards only sights it a search
/// interval later - the window is on screen in between. A failed open
/// cancels the worker it just spawned, so a 60 s search can never hide a
/// launcher the user starts by hand after the failure.
fn run_auto_join(deps: &mut AutoJoinDeps) -> Result<(), String> {
    (deps.spawn_hide)();
    if let Err(e) = (deps.open_link)() {
        (deps.cancel_hide)();
        return Err(e);
    }
    Ok(())
}

fn dispatch_lunar(
    auto_join: bool,
    use_external_overlay: bool,
    baseline: SystemTime,
) -> Result<DispatchResult, String> {
    if auto_join {
        let mut spawn_hide = || crate::hide::spawn_worker();
        let mut open_link = open_play_hypixel;
        let mut cancel_hide = || {
            crate::hide_registry::cancel_all_and_wait(std::time::Duration::from_secs(2));
        };
        run_auto_join(&mut AutoJoinDeps {
            spawn_hide: &mut spawn_hide,
            open_link: &mut open_link,
            cancel_hide: &mut cancel_hide,
        })?;
        Ok(DispatchResult {
            baseline,
            outcome: LaunchOutcome {
                auto_join_hypixel: true,
                use_external_overlay,
                action: LaunchAction::GameLaunchRequested,
            },
        })
    } else {
        launch_lunar_no_join(use_external_overlay)
    }
}

/// One warmed bare-link fire; see `nojoin` for the confirmation contract.
fn launch_lunar_no_join(use_external_overlay: bool) -> Result<DispatchResult, String> {
    let mut ensure_open = ensure_lunar_launcher_open;
    let mut warm_up = || std::thread::sleep(crate::nojoin::WARM_UP);
    let mut open_log = open_launcher_log_tail;
    let mut fire_link = open_bare_play_link;
    let mut start_hide = || crate::hide::spawn_worker();
    // Quiescence is claimed only for workers joined within the bound; a
    // pass hung past it can at worst re-hide once (cosmetic; the app stays
    // open as the recovery surface on this path).
    let mut cancel_hide =
        || crate::hide_registry::cancel_all_and_wait(std::time::Duration::from_secs(8));
    // Windows: posted to the same queues as any still-queued minimize, so
    // it is processed after and supersedes it. No-op on macOS.
    let mut restore_windows = || crate::hide::restore_lunar_windows();
    let mut represent = open_lunar_app_only;
    let mut poll_wait = || std::thread::sleep(crate::nojoin::POLL_INTERVAL);
    // Baseline at fire time: the progress pipeline watches for the game on
    // confirmed AND unconfirmed outcomes alike.
    let baseline = SystemTime::now();
    let outcome = crate::nojoin::run(&mut crate::nojoin::NoJoinDeps {
        ensure_open: &mut ensure_open,
        warm_up: &mut warm_up,
        open_log: &mut open_log,
        fire_link: &mut fire_link,
        start_hide: &mut start_hide,
        cancel_hide: &mut cancel_hide,
        restore_windows: &mut restore_windows,
        represent: &mut represent,
        poll_wait: &mut poll_wait,
    })?;
    let action = match outcome {
        crate::nojoin::NoJoinOutcome::Confirmed => LaunchAction::GameLaunchRequested,
        crate::nojoin::NoJoinOutcome::Unconfirmed => LaunchAction::LaunchUnconfirmed,
        crate::nojoin::NoJoinOutcome::OpenOnlyNeverFired => LaunchAction::LauncherOpened,
    };
    Ok(DispatchResult {
        baseline,
        outcome: LaunchOutcome {
            auto_join_hypixel: false,
            use_external_overlay,
            action,
        },
    })
}

/// Ensure the launcher app is up without focusing it (macOS); the Windows
/// exe spawn's single-instance forward may focus Lunar (manual-gate note).
fn ensure_lunar_launcher_open() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        let status = Command::new("/usr/bin/open")
            .args(["-g", "-a", "Lunar Client"])
            .status()
            .map_err(|e| format!("Cannot open Lunar Client: {e}"))?;
        if !status.success() {
            return Err("Cannot open Lunar Client - is Lunar installed?".to_string());
        }
        return Ok(());
    }
    #[cfg(not(target_os = "macos"))]
    {
        open_lunar_app_only()
    }
}

/// The launcher's own log, opened by HANDLE and held for the whole click:
/// rotation/replacement makes the handle go silent instead of feeding
/// unrelated content. Positioned at end - the cursor is taken here,
/// immediately before firing, so warm-up activity is never attributed to
/// the click.
fn open_launcher_log_tail() -> Option<Box<dyn FnMut() -> Option<Vec<u8>>>> {
    use std::io::{Read, Seek, SeekFrom};
    let path = crate::home()
        .ok()?
        .join(".lunarclient/logs/launcher/main.log");
    let mut file = std::fs::File::open(path).ok()?;
    file.seek(SeekFrom::End(0)).ok()?;
    Some(Box::new(move || {
        let mut buf = vec![0u8; 64 * 1024];
        match file.read(&mut buf) {
            Ok(n) => {
                buf.truncate(n);
                Some(buf)
            }
            Err(_) => None,
        }
    }))
}

fn open_bare_play_link() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        let status = Command::new("/usr/bin/open")
            .args(["-g", PLAY_BARE])
            .status()
            .map_err(|e| format!("Cannot start Lunar Client: {e}"))?;
        if !status.success() {
            return Err("Cannot start Lunar Client - is Lunar installed?".to_string());
        }
        return Ok(());
    }
    #[cfg(windows)]
    {
        tauri_plugin_opener::open_url(PLAY_BARE, None::<&str>)
            .map_err(|_| "Cannot start Lunar Client - is Lunar installed?".to_string())
    }
    #[cfg(not(any(target_os = "macos", windows)))]
    {
        Err("Lunar launch is not supported on this platform.".to_string())
    }
}

fn dispatch_forge(
    home: &Path,
    auto_join: bool,
    use_external_overlay: bool,
) -> Result<DispatchResult, String> {
    let saved = forge::load_target(home).ok_or("Choose a Prism Forge instance before launching.")?;
    if saved.marker.as_deref() != Some("mmc-pack.json") {
        return Err(
            "The saved Forge target is not a Prism instance - set up Prism first.".to_string(),
        );
    }
    let fresh = forge::revalidate(&saved)
        .ok_or("That Prism instance changed - reopen Cobblify and set it up again.")?;
    let instance_id = fresh
        .game_dir
        .parent()
        .and_then(std::path::Path::file_name)
        .and_then(|n| n.to_str())
        .filter(|n| !n.is_empty())
        .ok_or("Cannot determine the Prism instance id.")?;

    let exe = forge::prism_exe();
    if !exe.is_file() {
        return Err("Prism Launcher is not installed in its standard location.".to_string());
    }

    let baseline = SystemTime::now();
    let mut cmd = Command::new(&exe);
    for arg in forge_launch_args(instance_id, auto_join) {
        cmd.arg(arg);
    }
    cmd.spawn()
        .map_err(|e| format!("Cannot start Prism Launcher: {e}"))?;
    crate::hide::spawn_prism_worker();
    Ok(DispatchResult {
        baseline,
        outcome: LaunchOutcome {
            auto_join_hypixel: auto_join,
            use_external_overlay,
            action: LaunchAction::GameLaunchRequested,
        },
    })
}

/// Prism argv shape — one helper for production dispatch and tests.
pub fn forge_launch_args(instance_id: &str, auto_join: bool) -> Vec<String> {
    let mut args = vec!["--launch".to_string(), instance_id.to_string()];
    if auto_join {
        args.push("--server".to_string());
        args.push("play.hypixel.net".to_string());
    }
    args
}

pub fn launch_with_preflight(
    home: &Path,
    kind: LaunchKind,
    expected_auto_join: bool,
    expected_use_external_overlay: bool,
    generation: u64,
) -> LaunchAttempt {
    let locked = match LockedPrefs::acquire(home) {
        Ok(l) => l,
        Err(e) => {
            return LaunchAttempt {
                reply: LaunchReply::Rejected {
                    code: "preference_error",
                    preferences: None,
                    message: Some(format!("{e:?}")),
                },
                baseline: None,
            };
        }
    };
    let prefs = locked.read_view();
    let (strict_auto_join, strict_overlay) = match locked.read_strict() {
        Ok(v) => v,
        Err(msg) => {
            return LaunchAttempt {
                reply: LaunchReply::Rejected {
                    code: "preference_error",
                    preferences: Some(prefs.clone()),
                    message: Some(msg),
                },
                baseline: None,
            };
        }
    };
    if strict_auto_join != expected_auto_join || strict_overlay != expected_use_external_overlay {
        return LaunchAttempt {
            reply: LaunchReply::Rejected {
                code: "stale_preference",
                preferences: Some(prefs),
                message: None,
            },
            baseline: None,
        };
    }
    if preexisting_writer(home) {
        return LaunchAttempt {
            reply: LaunchReply::PreexistingGame { preferences: prefs },
            baseline: None,
        };
    }
    match dispatch(home, kind, strict_auto_join, strict_overlay) {
        Ok(result) => LaunchAttempt {
            reply: LaunchReply::Launched {
                outcome: result.outcome,
                generation,
            },
            baseline: Some(result.baseline),
        },
        Err(message) => LaunchAttempt {
            reply: LaunchReply::Rejected {
                code: "native_launch_error",
                preferences: Some(prefs),
                message: Some(message),
            },
            baseline: None,
        },
    }
}

#[cfg(target_os = "macos")]
fn open_play_hypixel() -> Result<(), String> {
    let status = Command::new("/usr/bin/open")
        .args(["-g", PLAY_HYPIXEL])
        .status()
        .map_err(|e| format!("Cannot start Lunar Client: {e}"))?;
    if !status.success() {
        return Err("Cannot start Lunar Client - is Lunar installed?".to_string());
    }
    Ok(())
}

#[cfg(windows)]
fn open_play_hypixel() -> Result<(), String> {
    tauri_plugin_opener::open_url(PLAY_HYPIXEL, None::<&str>)
        .map_err(|_| "Cannot start Lunar Client - is Lunar installed?".to_string())
}

#[cfg(not(any(target_os = "macos", windows)))]
fn open_play_hypixel() -> Result<(), String> {
    Err("Lunar launch is not supported on this platform.".to_string())
}

pub fn open_lunar_app_only() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        let status = Command::new("/usr/bin/open")
            .args(["-a", "Lunar Client"])
            .status()
            .map_err(|e| format!("Cannot open Lunar Client: {e}"))?;
        if !status.success() {
            return Err("Cannot open Lunar Client - is Lunar installed?".to_string());
        }
        return Ok(());
    }
    #[cfg(windows)]
    {
        let local = std::env::var_os("LOCALAPPDATA").ok_or("LOCALAPPDATA is not set.")?;
        let programs = std::path::PathBuf::from(local).join("Programs");
        let candidates = [
            programs.join("Lunar Client/Lunar Client.exe"),
            programs.join("lunarclient/Lunar Client.exe"),
        ];
        let exe = candidates
            .iter()
            .find(|p| p.is_file())
            .ok_or("Lunar Client is not installed.")?;
        Command::new(exe)
            .spawn()
            .map_err(|e| format!("Cannot open Lunar Client: {e}"))?;
        return Ok(());
    }
    #[cfg(not(any(target_os = "macos", windows)))]
    {
        Err("Opening Lunar Client is not supported on this platform.".to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::rc::Rc;

    /// Drive one auto-join click, recording the effects in order.
    fn drive_auto_join(open_ok: bool) -> (Result<(), String>, Vec<&'static str>) {
        let events = Rc::new(RefCell::new(Vec::new()));
        let e = Rc::clone(&events);
        let mut spawn_hide = move || e.borrow_mut().push("spawn_hide");
        let e = Rc::clone(&events);
        let mut open_link = move || {
            e.borrow_mut().push("open_link");
            if open_ok { Ok(()) } else { Err("no lunar".to_string()) }
        };
        let e = Rc::clone(&events);
        let mut cancel_hide = move || e.borrow_mut().push("cancel_hide");
        let result = run_auto_join(&mut AutoJoinDeps {
            spawn_hide: &mut spawn_hide,
            open_link: &mut open_link,
            cancel_hide: &mut cancel_hide,
        });
        let recorded = events.borrow().clone();
        (result, recorded)
    }

    /// The quit-flash fix: the hide worker is running BEFORE the deep link
    /// brings Lunar's window up, and a success never cancels it.
    #[test]
    fn auto_join_hides_before_it_opens_and_keeps_the_worker_on_success() {
        let (result, events) = drive_auto_join(true);
        assert!(result.is_ok());
        assert_eq!(events, vec!["spawn_hide", "open_link"]);
    }

    /// A failed open must not leave a 60 s searching worker behind: it would
    /// hide a launcher the user starts by hand afterwards.
    #[test]
    fn a_failed_open_cancels_the_worker_once_and_propagates_the_error() {
        let (result, events) = drive_auto_join(false);
        assert_eq!(result, Err("no lunar".to_string()));
        assert_eq!(events, vec!["spawn_hide", "open_link", "cancel_hide"]);
        assert_eq!(events.iter().filter(|e| **e == "cancel_hide").count(), 1);
    }

    #[test]
    fn launch_reply_serializes_snake_case_tags() {
        use crate::preferences::{LaunchPreferencesView, PreferenceHealth};
        let pre = serde_json::to_value(LaunchReply::PreexistingGame {
            preferences: LaunchPreferencesView {
                auto_join_hypixel: true,
                use_external_overlay: true,
                health: PreferenceHealth::Valid,
                diagnostic: None,
            },
        })
        .unwrap();
        assert_eq!(pre["status"], "preexisting_game");
        assert_eq!(pre["preferences"]["useExternalOverlay"], true);
        let rejected = serde_json::to_value(LaunchReply::Rejected {
            code: "busy",
            preferences: None,
            message: None,
        })
        .unwrap();
        assert_eq!(rejected["status"], "rejected");
    }

    #[test]
    fn forge_on_includes_hypixel_server_once() {
        let args = forge_launch_args("instance-1", true);
        assert_eq!(args, vec!["--launch", "instance-1", "--server", "play.hypixel.net"]);
    }

    #[test]
    fn forge_off_omits_server_argument() {
        let args = forge_launch_args("instance-1", false);
        assert_eq!(args, vec!["--launch", "instance-1"]);
    }

    #[test]
    fn lunar_on_deep_link_is_fixed() {
        assert!(PLAY_HYPIXEL.contains("play.hypixel.net"));
        assert!(!PLAY_HYPIXEL.contains("forceRecommendedVersion"));
    }

    /// The bare route never grows parameters and never replaces the
    /// documented serverAddress route.
    #[test]
    fn bare_play_link_carries_no_parameters() {
        assert_eq!(PLAY_BARE, "lunarclient://play");
        assert!(!PLAY_BARE.contains('?'));
        assert!(!PLAY_BARE.contains("forceRecommendedVersion"));
        assert!(PLAY_HYPIXEL.starts_with("lunarclient://play?"));
    }

    #[test]
    fn launch_unconfirmed_serializes_snake_case() {
        let v = serde_json::to_value(LaunchAction::LaunchUnconfirmed).unwrap();
        assert_eq!(v, "launch_unconfirmed");
    }

    /// Preflight compares BOTH booleans against one strict locked read: a
    /// mismatch on either rejects stale_preference and returns both
    /// confirmed values for resynchronization.
    #[test]
    fn preflight_rejects_stale_on_either_boolean() {
        let home = tempfile::tempdir().unwrap().into_path();
        crate::preferences::set_auto_join_hypixel(&home, true).unwrap();
        crate::preferences::set_use_external_overlay(&home, false).unwrap();

        let overlay_stale =
            launch_with_preflight(&home, LaunchKind::Lunar, true, true, 1);
        match overlay_stale.reply {
            LaunchReply::Rejected { code, preferences, .. } => {
                assert_eq!(code, "stale_preference");
                let prefs = preferences.expect("confirmed values returned");
                assert_eq!(prefs.auto_join_hypixel, true);
                assert_eq!(prefs.use_external_overlay, false);
            }
            other => panic!("expected rejection, got {other:?}"),
        }

        let auto_join_stale =
            launch_with_preflight(&home, LaunchKind::Lunar, false, false, 1);
        match auto_join_stale.reply {
            LaunchReply::Rejected { code, .. } => assert_eq!(code, "stale_preference"),
            other => panic!("expected rejection, got {other:?}"),
        }
    }
}
