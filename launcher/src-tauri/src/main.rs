// Windows: link as a GUI binary so no console window is created for the app.
// Without this the exe is built into the CONSOLE subsystem and Windows opens a
// conhost window alongside the launcher - one the user cannot close, because
// closing the console terminates the process attached to it. Kept off debug
// builds so `cargo run`/`tauri dev` still print to the terminal. No effect on
// macOS. (Verified against the shipped 0.9.0 exe: PE Subsystem = 3, CONSOLE.)
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod forge;
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
    pub fn spawn_prism_worker() {}
}
mod install;
mod lunar_config;
mod proc;
mod progress;
mod resources;

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::SystemTime;

use serde::Serialize;
use tauri::Manager;
use tauri_plugin_dialog::DialogExt;

use forge::{Candidate, CandidateView, Compat, SavedTarget, ValidatedBy};
use resources::ForgeJar;

/// One install target's own outcome. The two targets are reported independently and on
/// purpose: a friend with Forge but no Lunar, or the reverse, must not be shown the other
/// one's failure as the state of the whole app.
#[derive(Clone, Serialize)]
struct TargetStatus {
    /// "lunar" | "forge"
    kind: &'static str,
    /// "ready" | "blocked" | "absent" | "error"
    state: &'static str,
    message: String,
    /// Files we refused to touch. Their presence blocks.
    conflicts: Vec<String>,
    /// `"<old path> -> <new name>"` for anything we set aside, so it is never silent.
    quarantined: Vec<String>,
    /// Where our jar now lives.
    path: Option<String>,
    /// "none" | "choose" - whether the UI should offer instance selection. An old bundle
    /// with no Forge jar is "none": a picker there could never succeed.
    action: &'static str,
    candidates: Vec<CandidateView>,
}

impl TargetStatus {
    fn new(kind: &'static str, state: &'static str, message: impl Into<String>) -> Self {
        TargetStatus {
            kind,
            state,
            message: message.into(),
            conflicts: Vec::new(),
            quarantined: Vec::new(),
            path: None,
            action: "none",
            candidates: Vec::new(),
        }
    }
}

/// Readiness is a static property, computed at startup and reported as-is. It is never
/// recomputed behind the user's back - there is deliberately no Retry button, which would
/// report stale state. It DOES change in response to an explicit user action (choosing a
/// Forge instance), which is why it now lives behind a `Mutex`.
#[derive(Clone, Serialize)]
struct Status {
    /// "ready" | "blocked" | "error"
    state: &'static str,
    message: String,
    mod_version: Option<String>,
    /// Kept for the existing blocked-jars UI: the union across targets.
    conflicts: Vec<String>,
    targets: Vec<TargetStatus>,
}

impl Status {
    fn error(message: String) -> Self {
        Status {
            state: "error",
            message,
            mod_version: None,
            conflicts: Vec::new(),
            targets: Vec::new(),
        }
    }
}

/// Everything the choice commands need after startup. `candidates` is backend-owned and
/// keyed by an opaque id: the frontend never sends us a filesystem path, so a chosen target
/// can only ever be one this process itself detected or the user picked in a native dialog.
#[derive(Default)]
struct ForgeState {
    jar: Option<ForgeJar>,
    candidates: HashMap<String, Candidate>,
    next_pick: u32,
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

const NEVER_RUN_LUNAR: &str =
    "Lunar Client has never been run on this computer - open it and log in once, \
     then reopen Cobblify.";

// ── Lunar ───────────────────────────────────────────────────────────────────────

/// Lunar setup, now three-state. The only change to the proven path is its ENTRY: when
/// Lunar is not installed we report `absent` and write nothing at all - which also stops
/// `~/.weave/` being created on a machine that has no Lunar. Once `launcher.json` exists,
/// everything below is what it has always been.
fn set_up_lunar(res: &resources::Resources, home: &Path) -> TargetStatus {
    if !home.join(".lunarclient").exists() {
        return TargetStatus::new("lunar", "absent", "Lunar Client is not installed.");
    }
    let launcher_json = home.join(".lunarclient/settings/launcher.json");
    if !launcher_json.exists() {
        return TargetStatus::new("lunar", "blocked", NEVER_RUN_LUNAR);
    }
    let jars = match &res.lunar {
        Ok(j) => j,
        Err(e) => return TargetStatus::new("lunar", "error", e.clone()),
    };

    let installed = match install::install_lunar(jars, &home.join(".weave")) {
        Ok(i) => i,
        Err(e) => return TargetStatus::new("lunar", "error", e),
    };
    if !installed.conflicts.is_empty() {
        let mut t = TargetStatus::new("lunar", "blocked", "Remove the extra one, then reopen.");
        t.conflicts = installed
            .conflicts
            .iter()
            .map(|p| p.display().to_string())
            .collect();
        return t;
    }

    match lunar_config::register(&launcher_json, &installed.agent_path) {
        Ok(()) => {
            let mut t = TargetStatus::new("lunar", "ready", "Lunar Client");
            t.path = Some(installed.agent_path.display().to_string());
            t
        }
        Err(lunar_config::RegisterError::LunarRunning) => {
            TargetStatus::new("lunar", "blocked", LUNAR_RUNNING_HINT)
        }
        Err(lunar_config::RegisterError::Failed(e)) => TargetStatus::new("lunar", "error", e),
    }
}

// ── Forge ───────────────────────────────────────────────────────────────────────

/// Turns a completed Forge install into a target status.
fn forge_ready(out: forge::Outcome) -> TargetStatus {
    let mut t = if out.blocked {
        TargetStatus::new(
            "forge",
            "blocked",
            "Another Cobblify jar is already in that mods folder, and Cobblify will not \
             touch a file it did not put there. Forge refuses to start with two copies, so \
             nothing was installed - move or rename that jar, then reopen Cobblify.",
        )
    } else {
        TargetStatus::new("forge", "ready", "Forge")
    };
    t.path = out.path;
    t.conflicts = out.conflicts;
    t.quarantined = out.quarantined;
    t
}

/// A failure that may have already moved files. The moves are carried onto the status so
/// the user is still told what was set aside and how to put it back - reporting the error
/// alone would leave them with renamed files and nothing naming them.
fn forge_failed(e: forge::ForgeError) -> TargetStatus {
    let mut t = TargetStatus::new("forge", "error", e.message);
    if !e.quarantined.is_empty() {
        t.message = format!(
            "{} Some files were already moved aside - they are listed below, and renaming \
             one back to its original name restores it exactly.",
            t.message
        );
    }
    t.quarantined = e.quarantined;
    t
}

/// The "nothing chosen yet" status. `action` is what stops an old bundle from showing a
/// picker that could not possibly install anything.
fn forge_choose(candidates: &[Candidate]) -> TargetStatus {
    let installable = candidates.iter().filter(|c| c.compat.is_installable()).count();
    let message = if candidates.is_empty() {
        "No Minecraft folder found yet - choose yours to set up Forge.".to_string()
    } else if installable == 0 {
        "None of the Minecraft folders found can run Cobblify - choose another.".to_string()
    } else {
        "Choose which Minecraft folder to set Forge up in.".to_string()
    };
    let mut t = TargetStatus::new("forge", "absent", message);
    t.action = "choose";
    t.candidates = candidates.iter().map(Candidate::view).collect();
    t
}

/// Installs into a candidate and remembers it, but only once the install succeeded.
fn adopt(jar: &ForgeJar, c: &Candidate, home: &Path) -> TargetStatus {
    let out = match forge::install(jar, &c.game_dir) {
        Ok(o) => o,
        Err(e) => return forge_failed(e),
    };
    // A blocked outcome installed nothing, so there is nothing to remember.
    if out.blocked {
        return forge_ready(out);
    }
    let saved = SavedTarget {
        game_dir: c.game_dir.display().to_string(),
        validated_by: if c.marker.is_some() {
            ValidatedBy::Marker
        } else {
            ValidatedBy::User
        },
        marker: c.marker.clone(),
    };
    // A failure to remember is not a failure to install; say so rather than pretending
    // the whole thing broke.
    let mut t = forge_ready(out);
    if let Err(e) = forge::save_target(home, &saved) {
        t.message = format!("{} (could not remember this folder: {e})", t.message);
    }
    t
}

// ── aggregation ─────────────────────────────────────────────────────────────────

/// The overall state, total over every combination (PLAN 2.7).
///
/// `absent` never surfaces at the top level, so the frontend's coercion of an unknown
/// state to `error` is unreachable. Any ready target makes the app ready: that is the
/// whole point of running the two independently.
fn aggregate(version: Option<String>, targets: Vec<TargetStatus>) -> Status {
    let ready: Vec<&TargetStatus> = targets.iter().filter(|t| t.state == "ready").collect();
    let conflicts: Vec<String> = targets
        .iter()
        .flat_map(|t| t.conflicts.iter().cloned())
        .collect();

    if !ready.is_empty() {
        let names: Vec<&str> = ready.iter().map(|t| t.message.as_str()).collect();
        let v = version.clone().unwrap_or_default();
        return Status {
            state: "ready",
            message: format!(
                "Cobblify v{v} ready for {} - Right Shift for settings in game",
                names.join(" and ")
            ),
            mod_version: version,
            conflicts,
            targets,
        };
    }

    if let Some(blocked) = targets.iter().find(|t| t.state == "blocked") {
        return Status {
            state: "blocked",
            message: blocked.message.clone(),
            mod_version: version,
            conflicts,
            targets,
        };
    }

    // Nothing ready, nothing blocked: either everything is absent, or something errored.
    if let Some(err) = targets.iter().find(|t| t.state == "error") {
        return Status {
            state: "error",
            message: err.message.clone(),
            mod_version: version,
            conflicts,
            targets,
        };
    }

    // All absent. Whether that is actionable depends on whether a Forge jar even shipped.
    let can_choose = targets.iter().any(|t| t.action == "choose");
    let message = if can_choose {
        "No Lunar Client found. Choose your Minecraft folder to set up Forge.".to_string()
    } else {
        "No Lunar Client found, and this copy does not include Forge.".to_string()
    };
    Status {
        state: "blocked",
        message,
        mod_version: version,
        conflicts,
        targets,
    }
}

fn start_up(app: &tauri::AppHandle) -> (Status, ForgeState) {
    let none = ForgeState::default();
    let resource_dir = match app.path().resource_dir() {
        Ok(dir) => dir.join("resources"),
        Err(e) => {
            return (
                Status::error(format!("Cannot locate the bundled resources: {e}")),
                none,
            )
        }
    };
    let verified = match resources::verify(&resource_dir) {
        Ok(v) => v,
        Err(e) => return (Status::error(e), none),
    };
    let version = verified.manifest.mod_version.clone();

    let home = match home() {
        Ok(h) => h,
        Err(e) => return (Status::error(e), none),
    };

    let lunar = set_up_lunar(&verified, &home);

    let mut state = ForgeState::default();
    let forge_target = match &verified.forge {
        None => TargetStatus::new("forge", "absent", "This copy does not include Forge."),
        Some(Err(e)) => TargetStatus::new("forge", "error", e.clone()),
        Some(Ok(jar)) => {
            state.jar = Some(jar.clone());
            // A remembered instance re-installs straight away - idempotent and
            // self-healing, exactly the property the Lunar path already has.
            match forge::load_target(&home).as_ref().and_then(forge::revalidate) {
                Some(c) => adopt(jar, &c, &home),
                None => {
                    let candidates = forge::Env::from_process()
                        .map(|env| forge::detect(&env))
                        .unwrap_or_default();
                    let t = forge_choose(&candidates);
                    for c in candidates {
                        state.candidates.insert(c.id.clone(), c);
                    }
                    t
                }
            }
        }
    };

    (aggregate(Some(version), vec![lunar, forge_target]), state)
}

// ── commands ────────────────────────────────────────────────────────────────────

#[tauri::command]
fn status(state: tauri::State<'_, Mutex<Status>>) -> Status {
    state.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

/// Rebuilds the whole status after a Forge choice, so the UI re-renders from one shape.
fn republish(
    status: &tauri::State<'_, Mutex<Status>>,
    forge_target: TargetStatus,
) -> Status {
    let mut guard = status.lock().unwrap_or_else(|e| e.into_inner());
    let lunar = guard
        .targets
        .iter()
        .find(|t| t.kind == "lunar")
        .cloned()
        .unwrap_or_else(|| TargetStatus::new("lunar", "absent", "Lunar Client is not installed."));
    let next = aggregate(guard.mod_version.clone(), vec![lunar, forge_target]);
    *guard = next.clone();
    next
}

/// Installs into a candidate the backend already knows. `confirm` is required for an
/// `Unknown` folder - one with no launcher metadata at all - and refused for a `Confirmed`
/// one, so the two commands cannot be used interchangeably to skip a confirmation.
fn install_into(
    id: &str,
    confirmed_by_user: bool,
    status: tauri::State<'_, Mutex<Status>>,
    forge: tauri::State<'_, Mutex<ForgeState>>,
) -> Result<Status, String> {
    let (jar, candidate) = {
        let guard = forge.lock().unwrap_or_else(|e| e.into_inner());
        let jar = guard
            .jar
            .clone()
            .ok_or("This copy of Cobblify does not include Forge.")?;
        let c = guard
            .candidates
            .get(id)
            .cloned()
            .ok_or("That folder is no longer available - reopen Cobblify and try again.")?;
        (jar, c)
    };

    match &candidate.compat {
        Compat::Incompatible(why) => return Err(why.clone()),
        Compat::Unknown if !confirmed_by_user => {
            return Err("That folder needs to be confirmed first.".to_string())
        }
        Compat::Confirmed if confirmed_by_user => {
            return Err("That folder does not need confirming.".to_string())
        }
        _ => {}
    }

    // Re-validate NOW: the folder may have changed between being listed and being
    // clicked. The FRESH candidate is what gets installed - re-reading the metadata and
    // then installing into the stale one would make the re-check theatre.
    let fresh = forge::classify_picked(&candidate.game_dir)?;
    if !fresh.compat.is_installable() {
        return Err(fresh.compat_reason());
    }
    // Preserve the provenance of the offer. A user-confirmed bare folder must remain
    // bare, while a launcher-backed offer must retain the same marker and still confirm
    // 1.8.9 Forge. Otherwise metadata could change between rendering and clicking and
    // silently change what the click means.
    if confirmed_by_user {
        if fresh.compat != forge::Compat::Unknown || fresh.marker.is_some() {
            return Err("That folder changed since it was listed - reopen Cobblify and try again."
                .to_string());
        }
    } else if fresh.compat != forge::Compat::Confirmed || fresh.marker != candidate.marker {
        return Err(
            "That Minecraft instance changed since it was listed - reopen Cobblify and try again."
                .to_string(),
        );
    }

    let home = home()?;
    let target = adopt(&jar, &fresh, &home);

    // The id is spent either way; a second click must re-detect rather than re-run.
    forge
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .candidates
        .remove(id);

    Ok(republish(&status, target))
}

#[tauri::command]
fn choose_forge_target(
    id: String,
    status: tauri::State<'_, Mutex<Status>>,
    forge: tauri::State<'_, Mutex<ForgeState>>,
) -> Result<Status, String> {
    install_into(&id, false, status, forge)
}

#[tauri::command]
fn confirm_forge_target(
    id: String,
    status: tauri::State<'_, Mutex<Status>>,
    forge: tauri::State<'_, Mutex<ForgeState>>,
) -> Result<Status, String> {
    install_into(&id, true, status, forge)
}

/// Opens the native folder picker and, on a usable selection, ADDS it to the backend's own
/// candidate list under a fresh id. It never installs: a second explicit command always
/// follows, even when the picked folder is the only candidate on screen.
///
/// `async` is load-bearing - `blocking_pick_folder` must not run on the main thread, and an
/// async command runs on Tauri's runtime instead.
#[tauri::command]
async fn pick_forge_folder(
    app: tauri::AppHandle,
    status: tauri::State<'_, Mutex<Status>>,
    forge: tauri::State<'_, Mutex<ForgeState>>,
) -> Result<Status, String> {
    let picked = app.dialog().file().blocking_pick_folder();
    let Some(picked) = picked else {
        // Cancelling is not an error and must change nothing.
        return Ok(status.lock().unwrap_or_else(|e| e.into_inner()).clone());
    };
    let path = picked
        .into_path()
        .map_err(|_| "That folder could not be read.".to_string())?;

    let mut candidate = forge::classify_picked(&path)?;
    if !candidate.compat.is_installable() {
        return Err(candidate.compat_reason());
    }

    let mut guard = forge.lock().unwrap_or_else(|e| e.into_inner());
    if guard.jar.is_none() {
        return Err("This copy of Cobblify does not include Forge.".to_string());
    }
    guard.next_pick += 1;
    candidate.id = format!("p{}", guard.next_pick);
    let id = candidate.id.clone();
    guard.candidates.insert(id.clone(), candidate);
    let listed: Vec<Candidate> = guard.candidates.values().cloned().collect();
    drop(guard);

    let mut target = forge_choose(&listed);
    // Put the folder they just chose at the top of the list.
    target.candidates.sort_by_key(|c| (c.id != id) as u8);
    Ok(republish(&status, target))
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
///
/// This gate is LUNAR-ONLY, deliberately. Recognising a Forge game process would mean
/// reading more than an executable path, and `proc.rs`'s privacy contract does not allow
/// it - the game JVM carries a live Minecraft access token. So on Forge the dashboard
/// stays idle, and the README and friend instructions say so plainly rather than leaving
/// it looking broken.
#[tauri::command]
fn lobby_state(state: tauri::State<'_, Mutex<ProgressState>>) -> serde_json::Value {
    let idle = || serde_json::json!({ "context": "MENU", "inHypixel": false });
    let (baseline, forge_session) = {
        let st = state.lock().unwrap_or_else(|e| e.into_inner());
        (st.baseline, st.forge_session)
    };
    let Some(baseline) = baseline else {
        return idle();
    };
    let Ok(home) = home() else {
        return idle();
    };
    // Lunar has a unique bundled-JRE executable identity. Prism may use any system JRE,
    // so exe-only inspection cannot distinguish its Minecraft process safely. An explicit
    // Launch Forge click instead establishes a session, and the mtime gate below proves
    // the export belongs to that click rather than a stale prior game.
    if !forge_session && !proc::game_jvm_running(&home) {
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
    forge_session: bool,
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
        st.forge_session = false;
        st.last_mtime = None;
        st.steady_polls = 0;
    }
    open_play()?;
    hide::spawn_worker();
    Ok(())
}

/// Launches the remembered Prism instance and asks Prism to join Hypixel. Prism owns
/// Microsoft authentication; Cobblify never reads or handles account material.
#[tauri::command]
fn launch_forge(state: tauri::State<'_, Mutex<ProgressState>>) -> Result<(), String> {
    let saved = forge::load_target(&home()?)
        .ok_or("Choose a Prism Forge instance before launching.")?;
    if saved.marker.as_deref() != Some("mmc-pack.json") {
        return Err("The saved Forge target is not a Prism instance - set up Prism first."
            .to_string());
    }
    let fresh = forge::revalidate(&saved)
        .ok_or("That Prism instance changed - reopen Cobblify and set it up again.")?;
    let instance_id = fresh
        .game_dir
        .parent()
        .and_then(Path::file_name)
        .and_then(|n| n.to_str())
        .filter(|n| !n.is_empty())
        .ok_or("Cannot determine the Prism instance id.")?;

    #[cfg(windows)]
    let exe = PathBuf::from(
        std::env::var_os("LOCALAPPDATA").ok_or("LOCALAPPDATA is not set.")?,
    )
    .join("Programs/PrismLauncher/prismlauncher.exe");
    #[cfg(target_os = "macos")]
    let exe = PathBuf::from("/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher");
    #[cfg(not(any(windows, target_os = "macos")))]
    let exe = PathBuf::from("prismlauncher");

    if !exe.is_file() {
        return Err("Prism Launcher is not installed in its standard location.".to_string());
    }
    {
        let mut st = state.lock().unwrap_or_else(|e| e.into_inner());
        st.baseline = Some(SystemTime::now());
        st.forge_session = true;
        st.last_mtime = None;
        st.steady_polls = 0;
    }
    std::process::Command::new(&exe)
        .args(["--launch", instance_id, "--server", "play.hypixel.net"])
        .spawn()
        .map_err(|e| format!("Cannot start Prism Launcher: {e}"))?;
    hide::spawn_prism_worker();
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
    let forge_session = st.forge_session;
    let waiting = || {
        if forge_session {
            progress::progress_for_forge(progress::Milestone::Fired, false)
        } else {
            progress::progress_for(progress::Milestone::Fired, false)
        }
    };

    let Some(baseline) = st.baseline else {
        return waiting();
    };
    let Ok(home) = home() else {
        return waiting();
    };
    let path = if forge_session {
        let Some(saved) = forge::load_target(&home) else {
            return waiting();
        };
        PathBuf::from(saved.game_dir).join("logs/fml-client-latest.log")
    } else {
        home.join(".weave/logs/latest.log")
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
    let milestone = if forge_session {
        progress::milestone_from_forge_log(&log)
    } else {
        progress::milestone_from_log(&log)
    };

    // "Settled" = mixins began AND the log mtime held steady two polls running,
    // i.e. Cobblify's log activity stopped. Honest, not a timer.
    let steady = match (st.last_mtime, mtime) {
        (Some(prev), Some(now)) if prev == now => st.steady_polls + 1,
        _ => 0,
    };
    st.steady_polls = steady;
    st.last_mtime = mtime;
    let settled = milestone == progress::Milestone::Mixing
        && if forge_session { progress::forge_loaded(&log) } else { steady >= 2 };
    if forge_session {
        progress::progress_for_forge(milestone, settled)
    } else {
        progress::progress_for(milestone, settled)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Regression lock: the forbidden parameter shipped once (Aug 5) and
    /// could silently move a friend off 1.8.9.
    #[test]
    fn play_deep_link_pins_hypixel_and_bans_force_recommended() {
        assert!(super::PLAY.starts_with("lunarclient://play?"));
        assert!(super::PLAY.contains("serverAddress=play.hypixel.net"));
        assert!(!super::PLAY.contains("forceRecommendedVersion"));
    }

    fn t(kind: &'static str, state: &'static str) -> TargetStatus {
        TargetStatus::new(kind, state, format!("{kind}-{state}"))
    }

    fn choosable() -> TargetStatus {
        let mut t = TargetStatus::new("forge", "absent", "pick one");
        t.action = "choose";
        t
    }

    fn agg(lunar: TargetStatus, forge: TargetStatus) -> Status {
        aggregate(Some("0.9.0".into()), vec![lunar, forge])
    }

    /// PLAN 2.7. The property that matters most: a ready target is never hidden behind a
    /// failed peer, in either direction.
    #[test]
    fn any_ready_target_makes_the_app_ready() {
        for other in ["ready", "blocked", "absent", "error"] {
            assert_eq!(agg(t("lunar", "ready"), t("forge", other)).state, "ready");
            assert_eq!(agg(t("lunar", other), t("forge", "ready")).state, "ready");
        }
    }

    #[test]
    fn a_blocked_target_wins_over_absent_and_error() {
        assert_eq!(agg(t("lunar", "blocked"), t("forge", "absent")).state, "blocked");
        assert_eq!(agg(t("lunar", "error"), t("forge", "blocked")).state, "blocked");
        assert_eq!(
            agg(t("lunar", "blocked"), t("forge", "error")).message,
            "lunar-blocked"
        );
    }

    #[test]
    fn an_error_surfaces_only_when_nothing_is_ready_or_blocked() {
        assert_eq!(agg(t("lunar", "error"), t("forge", "absent")).state, "error");
        assert_eq!(agg(t("lunar", "absent"), t("forge", "error")).state, "error");
        assert_eq!(
            agg(t("lunar", "absent"), t("forge", "error")).message,
            "forge-error"
        );
    }

    /// A Forge-only friend: no Lunar at all must NOT be an app-wide error, which is
    /// exactly what happens today (acceptance criterion 2).
    #[test]
    fn a_forge_only_machine_is_ready_not_errored() {
        let s = agg(t("lunar", "absent"), t("forge", "ready"));
        assert_eq!(s.state, "ready");
        assert!(s.message.contains("forge-ready"), "{}", s.message);
    }

    /// Both absent, but a Forge jar shipped: offer the picker.
    #[test]
    fn all_absent_with_a_forge_jar_offers_a_choice() {
        let s = agg(t("lunar", "absent"), choosable());
        assert_eq!(s.state, "blocked");
        assert!(s.message.contains("Choose your Minecraft folder"), "{}", s.message);
    }

    /// Round-2 I2: an old five-key bundle on a Lunar-less machine must not show a picker
    /// that cannot succeed.
    #[test]
    fn all_absent_without_a_forge_jar_offers_nothing() {
        let s = agg(t("lunar", "absent"), t("forge", "absent"));
        assert_eq!(s.state, "blocked");
        assert!(s.message.contains("does not include Forge"), "{}", s.message);
        assert!(s.targets.iter().all(|t| t.action == "none"));
    }

    /// `absent` is a target-level state only. The frontend coerces any unknown top-level
    /// state to `error`, so leaking it would show a spurious failure.
    #[test]
    fn absent_never_reaches_the_top_level() {
        for a in ["ready", "blocked", "absent", "error"] {
            for b in ["ready", "blocked", "absent", "error"] {
                let s = agg(t("lunar", a), t("forge", b));
                assert!(
                    matches!(s.state, "ready" | "blocked" | "error"),
                    "{a}/{b} produced {}",
                    s.state
                );
            }
        }
    }

    #[test]
    fn conflicts_are_unioned_across_targets() {
        let mut lunar = t("lunar", "blocked");
        lunar.conflicts = vec!["a.jar".into()];
        let mut forge = t("forge", "blocked");
        forge.conflicts = vec!["b.jar".into()];
        let s = agg(lunar, forge);
        assert_eq!(s.conflicts, vec!["a.jar".to_string(), "b.jar".to_string()]);
    }
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let (status, forge_state) = start_up(app.handle());
            app.manage(Mutex::new(status));
            app.manage(Mutex::new(forge_state));
            app.manage(Mutex::new(ProgressState::default()));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            status,
            lobby_state,
            launch_lunar,
            launch_forge,
            launch_progress,
            pick_forge_folder,
            choose_forge_target,
            confirm_forge_target
        ])
        .run(tauri::generate_context!())
        .expect("failed to start the Cobblify launcher");
}
