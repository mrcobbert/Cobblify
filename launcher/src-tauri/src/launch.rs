//! Launch routes, preflight, and native dispatch.

use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::SystemTime;

use serde::Serialize;

use crate::forge;
use crate::lobby::preexisting_writer;
use crate::preferences::{LaunchPreferencesView, LockedPrefs};

/// The only Lunar route: the bare play link, which launches to the title
/// screen against a WARM launcher and never joins a server (0.18 has no
/// auto-join; the player joins Hypixel themselves). Undocumented handler
/// behavior, verified live 2026-08-20; must never carry parameters - the
/// documented server parameters would join a server, and
/// `forceRecommendedVersion` only works together with them.
pub const PLAY_BARE: &str = "lunarclient://play";

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchOutcome {
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
    use_external_overlay: bool,
) -> Result<DispatchResult, String> {
    // Cross-generation isolation: a previous click's still-running hide
    // worker (Windows sweeps up to 150s) must never act on this click's
    // windows. Runs on every admission, including launches later rejected.
    crate::hide_registry::cancel_all_and_wait(std::time::Duration::from_secs(2));
    match kind {
        LaunchKind::Lunar => launch_lunar_no_join(use_external_overlay),
        LaunchKind::Forge => dispatch_forge(home, use_external_overlay),
    }
}

/// Lunar starts the profile `settings.gameProfile` names, read when it starts: while it is fully
/// quit (rarely - it stays in the Dock), make that its plain 1.8.9 profile before opening it
/// (`lunar_profile.rs`). Best effort and silent; a running Lunar is never touched.
fn select_1_8_9_if_lunar_is_quit(home: &Path) {
    #[cfg(target_os = "macos")]
    {
        if crate::proc::lunar_running_checked() != Ok(false) {
            return;
        }
        if let Some(id) = crate::lunar_profile::to_select(home) {
            let _ = crate::lunar_config::select_profile_with(
                crate::proc::lunar_running_checked,
                &crate::lunar_detect::launcher_json(home),
                &id,
            );
        }
    }
    #[cfg(not(target_os = "macos"))]
    let _ = home;
}

/// The selected profile a Launch already stopped at ("Set Lunar to 1.8.9"): pressing Launch
/// again with the same selection launches anyway.
static ASKED: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);

/// Whether this Lunar launch should stop at "Set Lunar to 1.8.9" before opening anything: only
/// when SURE - the selected profile is a saved one on another version. Lunar keeps its selection
/// in memory while it runs, so a version picked there but never launched is not in the file; the
/// second press of Launch therefore always launches, and the launch watch has the last word.
fn lunar_needs_1_8_9_first(home: &Path) -> bool {
    let selected = crate::lunar_config::selected_profile(&crate::lunar_detect::launcher_json(home));
    let on_1_8_9 = selected
        .as_deref()
        .and_then(|id| crate::lunar_profile::is_1_8_9(home, id));
    let mut asked = ASKED.lock().unwrap_or_else(|e| e.into_inner());
    stop_for_version(selected, on_1_8_9, &mut asked)
}

/// `lunar_needs_1_8_9_first`'s decision: stop once per selection that is surely not 1.8.9.
fn stop_for_version(selected: Option<String>, on_1_8_9: Option<bool>, asked: &mut Option<String>) -> bool {
    match (selected, on_1_8_9) {
        (Some(id), Some(false)) => {
            if asked.as_deref() == Some(id.as_str()) {
                *asked = None;
                false
            } else {
                *asked = Some(id);
                true
            }
        }
        _ => {
            *asked = None;
            false
        }
    }
}

/// What the "Set Lunar to 1.8.9" row's wait sees (`lunar_version_ready`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum VersionWait {
    /// A Lunar game is running: the player is playing, the wait ends.
    Game,
    /// The selected profile is a saved one on another version.
    Other,
    /// The selected profile is a 1.8.9 one and no Lunar game runs.
    Ready,
    /// Anything that cannot be read.
    Unknown,
}

/// The row's wait, read-only. A running game wins over everything (the frontend then stops
/// waiting for good), so a game the player starts from Lunar is never followed by another.
pub fn lunar_version_ready(home: &Path) -> VersionWait {
    let scan = crate::proc::game_jvm_scan(home);
    if !scan.pids.is_empty() {
        return VersionWait::Game;
    }
    let selected =
        crate::lunar_config::selected_profile(&crate::lunar_detect::launcher_json(home));
    match selected.as_deref().and_then(|id| crate::lunar_profile::is_1_8_9(home, id)) {
        Some(false) => VersionWait::Other,
        Some(true) if scan.complete => VersionWait::Ready,
        _ => VersionWait::Unknown,
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

fn dispatch_forge(home: &Path, use_external_overlay: bool) -> Result<DispatchResult, String> {
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
    for arg in forge_launch_args(instance_id) {
        cmd.arg(arg);
    }
    cmd.spawn()
        .map_err(|e| format!("Cannot start Prism Launcher: {e}"))?;
    crate::hide::spawn_prism_worker();
    Ok(DispatchResult {
        baseline,
        outcome: LaunchOutcome {
            use_external_overlay,
            action: LaunchAction::GameLaunchRequested,
        },
    })
}

/// Prism argv shape - one helper for production dispatch and tests. Never a
/// `--server`: the player joins Hypixel themselves.
pub fn forge_launch_args(instance_id: &str) -> Vec<String> {
    vec!["--launch".to_string(), instance_id.to_string()]
}

pub fn launch_with_preflight(
    home: &Path,
    kind: LaunchKind,
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
    let strict_overlay = match locked.read_strict() {
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
    if strict_overlay != expected_use_external_overlay {
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
    if kind == LaunchKind::Lunar {
        // Weave needs Mojang's 1.8.9 jar on every launch; put back one that went missing.
        if let Err(message) = crate::vanilla::ensure(home, &crate::prism_java::host_fetch) {
            return LaunchAttempt {
                reply: LaunchReply::Rejected {
                    code: "native_launch_error",
                    preferences: Some(prefs),
                    message: Some(message),
                },
                baseline: None,
            };
        }
        select_1_8_9_if_lunar_is_quit(home);
        if lunar_needs_1_8_9_first(home) {
            return LaunchAttempt {
                reply: LaunchReply::Rejected {
                    code: "wrong_version",
                    preferences: None,
                    message: None,
                },
                baseline: None,
            };
        }
    }
    match dispatch(home, kind, strict_overlay) {
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

/// The Lunar Client executable under a `%LOCALAPPDATA%\Programs` directory,
/// or `None` when neither observed layout is present.
///
/// TWO layouts exist in the field (L6): the spaced `Lunar Client` directory
/// and the squashed `lunarclient` one, in that order of preference. Every
/// caller must try both - `open_launcher` hard-coded the squashed name and
/// told anyone with the other layout that Lunar was not installed. Pure path
/// logic, compiled on every platform so its test runs on the Mac too; only
/// the Windows call sites consult it.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn lunar_exe_in(programs: &Path) -> Option<PathBuf> {
    ["Lunar Client", "lunarclient"]
        .into_iter()
        .map(|dir| programs.join(dir).join("Lunar Client.exe"))
        .find(|candidate| candidate.is_file())
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
        let programs = PathBuf::from(local).join("Programs");
        let exe = lunar_exe_in(&programs).ok_or("Lunar Client is not installed.")?;
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

    /// L6. Both layouts have been seen under `%LOCALAPPDATA%\Programs`:
    /// the spaced `Lunar Client` directory and the squashed `lunarclient`
    /// one. The lookup is pure path logic, so it is proven on every
    /// platform against a temp directory rather than only on Windows.
    #[test]
    fn lunar_exe_in_finds_either_observed_install_layout() {
        let empty = tempfile::tempdir().unwrap();
        assert_eq!(lunar_exe_in(empty.path()), None, "neither layout present");

        let spaced = tempfile::tempdir().unwrap();
        let spaced_exe = spaced.path().join("Lunar Client").join("Lunar Client.exe");
        std::fs::create_dir_all(spaced_exe.parent().unwrap()).unwrap();
        std::fs::write(&spaced_exe, b"exe").unwrap();
        assert_eq!(lunar_exe_in(spaced.path()), Some(spaced_exe));

        let squashed = tempfile::tempdir().unwrap();
        let squashed_exe = squashed.path().join("lunarclient").join("Lunar Client.exe");
        std::fs::create_dir_all(squashed_exe.parent().unwrap()).unwrap();
        std::fs::write(&squashed_exe, b"exe").unwrap();
        assert_eq!(lunar_exe_in(squashed.path()), Some(squashed_exe));
    }

    #[test]
    fn launch_reply_serializes_snake_case_tags() {
        use crate::preferences::{LaunchPreferencesView, PreferenceHealth};
        let pre = serde_json::to_value(LaunchReply::PreexistingGame {
            preferences: LaunchPreferencesView {
                use_external_overlay: true,
                health: PreferenceHealth::Valid,
                diagnostic: None,
            },
        })
        .unwrap();
        assert_eq!(pre["status"], "preexisting_game");
        assert_eq!(pre["preferences"]["useExternalOverlay"], true);
        assert!(pre["preferences"].get("autoJoinHypixel").is_none(), "no auto-join on the wire");
        let rejected = serde_json::to_value(LaunchReply::Rejected {
            code: "busy",
            preferences: None,
            message: None,
        })
        .unwrap();
        assert_eq!(rejected["status"], "rejected");
    }

    /// No auto-join: a Prism launch never carries `--server`.
    #[test]
    fn forge_launch_args_never_carry_a_server() {
        let args = forge_launch_args("instance-1");
        assert_eq!(args, vec!["--launch", "instance-1"]);
    }

    /// The only Lunar route never grows parameters: any server parameter
    /// would join a server by itself.
    #[test]
    fn a_surely_wrong_selection_stops_once_then_launches() {
        let mut asked = None;
        let other = || Some("p-26".to_string());
        assert!(stop_for_version(other(), Some(false), &mut asked), "first press stops");
        assert!(!stop_for_version(other(), Some(false), &mut asked), "second press launches");
        assert!(stop_for_version(other(), Some(false), &mut asked), "a later launch asks again");
        assert!(!stop_for_version(Some("p-189".into()), Some(true), &mut asked));
        assert_eq!(asked, None, "1.8.9 clears it");
        assert!(stop_for_version(other(), Some(false), &mut asked));
        assert!(stop_for_version(Some("p-21".into()), Some(false), &mut asked), "another wrong one asks");
        for unknown in [(None, None), (Some("v".into()), None)] {
            let mut asked = None;
            assert!(!stop_for_version(unknown.0, unknown.1, &mut asked), "unknown never stops");
        }
    }

    #[test]
    fn bare_play_link_carries_no_parameters() {
        assert_eq!(PLAY_BARE, "lunarclient://play");
        assert!(!PLAY_BARE.contains('?'));
        assert!(!PLAY_BARE.contains("forceRecommendedVersion"));
    }

    #[test]
    fn launch_unconfirmed_serializes_snake_case() {
        let v = serde_json::to_value(LaunchAction::LaunchUnconfirmed).unwrap();
        assert_eq!(v, "launch_unconfirmed");
    }

    /// Preflight compares the overlay preference against one strict locked
    /// read: a mismatch rejects stale_preference and returns the confirmed
    /// value for resynchronization. The stored 0.17 auto-join key, whatever
    /// it holds, plays no part.
    #[test]
    fn preflight_rejects_a_stale_overlay_and_ignores_the_stored_auto_join_key() {
        let home = tempfile::tempdir().unwrap().into_path();
        let dir = crate::preferences::ensure_cobblify_dir(&home).unwrap();
        std::fs::write(
            dir.join("launcher-preferences.json"),
            r#"{"auto_join_hypixel":false,"use_external_overlay":false}"#,
        )
        .unwrap();

        let overlay_stale = launch_with_preflight(&home, LaunchKind::Lunar, true, 1);
        match overlay_stale.reply {
            LaunchReply::Rejected { code, preferences, .. } => {
                assert_eq!(code, "stale_preference");
                let prefs = preferences.expect("confirmed values returned");
                assert_eq!(prefs.use_external_overlay, false);
                assert_eq!(prefs.health, crate::preferences::PreferenceHealth::Valid);
            }
            other => panic!("expected rejection, got {other:?}"),
        }
    }
}
