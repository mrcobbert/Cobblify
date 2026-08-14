// Windows: link as a GUI binary so no console window is created for the app.
// Without this the exe is built into the CONSOLE subsystem and Windows opens a
// conhost window alongside the launcher - one the user cannot close, because
// closing the console terminates the process attached to it. Kept off debug
// builds so `cargo run`/`tauri dev` still print to the terminal. No effect on
// macOS. (Verified against the shipped 0.9.0 exe: PE Subsystem = 3, CONSOLE.)
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

#[cfg(target_os = "macos")]
mod hide;
#[cfg(windows)]
#[path = "hide_windows.rs"]
mod hide;
/// Everything else (Linux dev builds) has no hiding; the no-op keeps the
/// launch path's call site clean.
#[cfg(not(any(target_os = "macos", windows)))]
mod hide {
    pub fn spawn_worker() {}
}
mod install;
mod lunar_config;
mod proc;
mod progress;
mod resources;

use std::path::PathBuf;
use std::sync::Mutex;
use std::time::SystemTime;

use serde::Serialize;
use tauri::Manager;

/// Readiness is a static property: the right jars are in `~/.weave/` and Lunar's
/// `launcher.json` names our agent. It is computed once at startup and reported as-is.
#[derive(Clone, Serialize)]
struct Status {
    /// "ready" | "blocked" | "error"
    state: &'static str,
    message: String,
    mod_version: Option<String>,
    /// Other `Cobblify-Lunar-*.jar` files we found but will never delete.
    conflicts: Vec<String>,
}

impl Status {
    fn error(message: String) -> Self {
        Status {
            state: "error",
            message,
            mod_version: None,
            conflicts: Vec::new(),
        }
    }
}

#[cfg(not(windows))]
fn home() -> Result<PathBuf, String> {
    std::env::var("HOME")
        .map(PathBuf::from)
        .map_err(|_| "HOME is not set.".to_string())
}

/// `HOME` is unset on Windows; the profile directory lives in `USERPROFILE`.
#[cfg(windows)]
fn home() -> Result<PathBuf, String> {
    std::env::var("USERPROFILE")
        .map(PathBuf::from)
        .map_err(|_| "USERPROFILE is not set.".to_string())
}

/// Shown under the "Quit Lunar" heading. Closing Lunar's WINDOW is not
/// quitting it on Windows: `computerStartup: "DOCKED"` in Lunar's own
/// launcher.json starts it with Windows and leaves it in the notification
/// area, windows created but hidden - measured 2026-08-14, seven live
/// processes and not one visible window. A user in that state reads "quit
/// Lunar", sees no Lunar, and is stuck, so the tray is named explicitly.
#[cfg(windows)]
const LUNAR_RUNNING_HINT: &str =
    "Lunar may be docked in the system tray - click the ^ arrow next to the clock, \
     right-click Lunar Client, then Quit. Then reopen Cobblify.";
#[cfg(not(windows))]
const LUNAR_RUNNING_HINT: &str = "Then reopen Cobblify.";

fn start_up(app: &tauri::AppHandle) -> Status {
    let resource_dir = match app.path().resource_dir() {
        Ok(dir) => dir.join("resources"),
        Err(e) => return Status::error(format!("Cannot locate the bundled resources: {e}")),
    };
    let verified = match resources::verify(&resource_dir) {
        Ok(v) => v,
        Err(e) => return Status::error(e),
    };
    let version = verified.manifest.mod_version.clone();

    let home = match home() {
        Ok(h) => h,
        Err(e) => return Status::error(e),
    };
    let installed = match install::install(&verified, &home.join(".weave")) {
        Ok(i) => i,
        Err(e) => return Status::error(e),
    };
    if !installed.conflicts.is_empty() {
        return Status {
            state: "blocked",
            message: "Remove the extra one, then reopen.".to_string(),
            mod_version: Some(version),
            conflicts: installed
                .conflicts
                .iter()
                .map(|p| p.display().to_string())
                .collect(),
        };
    }

    let launcher_json = home.join(".lunarclient/settings/launcher.json");
    match lunar_config::register(&launcher_json, &installed.agent_path) {
        Ok(()) => Status {
            state: "ready",
            message: format!("Cobblify v{version} ready - Right Shift for settings in game"),
            mod_version: Some(version),
            conflicts: Vec::new(),
        },
        Err(lunar_config::RegisterError::LunarRunning) => Status {
            state: "blocked",
            message: LUNAR_RUNNING_HINT.to_string(),
            mod_version: Some(version),
            conflicts: Vec::new(),
        },
        Err(lunar_config::RegisterError::Failed(e)) => Status::error(e),
    }
}

#[tauri::command]
fn status(state: tauri::State<'_, Status>) -> Status {
    state.inner().clone()
}

/// Reads the mod's `~/.cobblify/lobby.json` and hands the parsed JSON straight
/// to the frontend, which renders the lobby dashboard from it. Fully fail-soft
/// by contract: a missing, unreadable, half-written, or non-object file
/// degrades to a calm idle value (`{context:"MENU", inHypixel:false}`) so the
/// launcher shows the "Joining Hypixel" interstitial and never an error. The
/// writer renames a temp file into place, so a partial read is transient and
/// simply resolves on the next poll.
///
/// Staleness, two independent gates, because lobby.json outlives the world it
/// describes in two ways:
///  - Between sessions: the file survives on disk, so anything whose mtime
///    predates this session's launch baseline is a PRIOR session's roster -
///    the same defence `launch_progress` applies to the hard-linked
///    `latest.log`. No baseline (launch never fired) is idle too.
///  - After a quit: the mod only writes on change, so when the game dies the
///    last roster stays on disk looking fresh. A roster is only real while
///    Lunar's game JVM is actually running.
#[tauri::command]
fn lobby_state(state: tauri::State<'_, Mutex<ProgressState>>) -> serde_json::Value {
    let idle = || serde_json::json!({ "context": "MENU", "inHypixel": false });
    let baseline = {
        let st = state.lock().unwrap_or_else(|e| e.into_inner());
        st.baseline
    };
    let Some(baseline) = baseline else {
        return idle();
    };
    let Ok(home) = home() else {
        return idle();
    };
    if !proc::game_jvm_running(&home) {
        return idle();
    }
    let path = home.join(".cobblify/lobby.json");
    let fresh = matches!(
        std::fs::metadata(&path).and_then(|m| m.modified()),
        Ok(m) if m >= baseline
    );
    if !fresh {
        return idle();
    }
    let Ok(text) = std::fs::read_to_string(&path) else {
        return idle();
    };
    match serde_json::from_str::<serde_json::Value>(&text) {
        Ok(value) if value.is_object() => value,
        _ => idle(),
    }
}

/// Launch-progress session state, guarded by a `Mutex`. `baseline` is stamped
/// when the deep link fires so a STALE `latest.log` (hard-linked from a prior
/// session and already on disk) is never mistaken for this run's log.
/// `last_mtime`/`steady_polls` track when the log has gone quiet after mixins.
#[derive(Default)]
struct ProgressState {
    baseline: Option<SystemTime>,
    last_mtime: Option<SystemTime>,
    steady_polls: u32,
}

/// The official Lunar play deep link: boots the game on the ACTIVE version
/// profile and auto-joins Hypixel. Never add `forceRecommendedVersion` - it
/// can silently switch the profile off 1.8.9.
///
/// Docs: https://lunarclient.dev/deep-links/play
const PLAY: &str = "lunarclient://play?serverAddress=play.hypixel.net";

/// Fires the play deep link (on macOS via `open -g`, no focus steal; on
/// Windows via ShellExecute, which has no no-focus equivalent), then gets
/// Lunar's own windows off the screen as soon as they appear - `hide.rs` on
/// macOS (app-level hide), `hide_windows.rs` on Windows (per-window
/// minimize, plus the game's console window). The link drives
/// Lunar's OWN launcher - cold start, an already-running instance, login, and
/// the agent registered in `launcher.json` all behave exactly as a manual
/// launch.
#[tauri::command]
fn launch_lunar(state: tauri::State<'_, Mutex<ProgressState>>) -> Result<(), String> {
    // Stamp the freshness baseline the instant we fire, before anything can
    // write the log. A latest.log older than this is a stale prior session.
    {
        let mut st = state.lock().unwrap_or_else(|e| e.into_inner());
        st.baseline = Some(SystemTime::now());
        st.last_mtime = None;
        st.steady_polls = 0;
    }
    open_play()?;
    hide::spawn_worker();
    Ok(())
}

#[cfg(target_os = "macos")]
fn open_play() -> Result<(), String> {
    let status = std::process::Command::new("/usr/bin/open")
        .args(["-g", PLAY])
        .status()
        .map_err(|e| format!("Cannot start Lunar Client: {e}"))?;
    if !status.success() {
        return Err("Cannot start Lunar Client - is Lunar installed?".to_string());
    }
    Ok(())
}

/// ShellExecute-based open: no console flash and `&`-safe, the two reasons
/// `cmd /C start` is banned here. Fails when no `lunarclient://` handler is
/// registered - i.e. Lunar was never installed on this machine.
#[cfg(windows)]
fn open_play() -> Result<(), String> {
    tauri_plugin_opener::open_url(PLAY, None::<&str>)
        .map_err(|_| "Cannot start Lunar Client - is Lunar installed?".to_string())
}

/// Cosmetic, fail-soft stepped progress for the launch button. Stats the weave
/// log, ignores it unless its mtime is at or after this session's baseline
/// (defeating the stale hard-linked latest.log), reads it, and maps the highest
/// milestone line to a bar stage. Any missing/unreadable log or unset baseline
/// reports the "fired" baseline - never an error. Only milestone substrings are
/// inspected; full log contents are never returned or logged.
#[tauri::command]
fn launch_progress(state: tauri::State<'_, Mutex<ProgressState>>) -> progress::Progress {
    let mut st = state.lock().unwrap_or_else(|e| e.into_inner());
    let waiting = || progress::progress_for(progress::Milestone::Fired, false);

    let Some(baseline) = st.baseline else {
        return waiting();
    };
    let Ok(path) = home().map(|h| h.join(".weave/logs/latest.log")) else {
        return waiting();
    };
    // Re-stat by path every poll: recreation/truncation mid-session is fine.
    let mtime = std::fs::metadata(&path).and_then(|m| m.modified()).ok();
    let fresh = matches!(mtime, Some(m) if m >= baseline);
    if !fresh {
        st.last_mtime = None;
        st.steady_polls = 0;
        return waiting();
    }
    let log = std::fs::read_to_string(&path).unwrap_or_default();
    let milestone = progress::milestone_from_log(&log);

    // "Settled" = mixins began AND the log mtime held steady two polls running,
    // i.e. Cobblify's log activity stopped. Honest, not a timer.
    let steady = match (st.last_mtime, mtime) {
        (Some(prev), Some(now)) if prev == now => st.steady_polls + 1,
        _ => 0,
    };
    st.steady_polls = steady;
    st.last_mtime = mtime;
    let settled = milestone == progress::Milestone::Mixing && steady >= 2;
    progress::progress_for(milestone, settled)
}

#[cfg(test)]
mod tests {
    /// Regression lock: the forbidden parameter shipped once (Aug 5) and
    /// could silently move a friend off 1.8.9.
    #[test]
    fn play_deep_link_pins_hypixel_and_bans_force_recommended() {
        assert!(super::PLAY.starts_with("lunarclient://play?"));
        assert!(super::PLAY.contains("serverAddress=play.hypixel.net"));
        assert!(!super::PLAY.contains("forceRecommendedVersion"));
    }
}

fn main() {
    tauri::Builder::default()
        .setup(|app| {
            let status = start_up(app.handle());
            app.manage(status);
            app.manage(Mutex::new(ProgressState::default()));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            status,
            lobby_state,
            launch_lunar,
            launch_progress
        ])
        .run(tauri::generate_context!())
        .expect("failed to start the Cobblify launcher");
}
