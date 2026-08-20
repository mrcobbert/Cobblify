// Windows: link as a GUI binary so no console window is created for the app.
// Without this the exe is built into the CONSOLE subsystem and Windows opens a
// conhost window alongside the launcher - one the user cannot close, because
// closing the console terminates the process attached to it. Kept off debug
// builds so `cargo run`/`tauri dev` still print to the terminal. No effect on
// macOS. (Verified against the shipped 0.9.0 exe: PE Subsystem = 3, CONSOLE.)
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod forge;
mod launch;
mod lifecycle;
mod lobby;
mod preferences;
mod process_liveness;
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
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

use serde::Serialize;
use tauri::Manager;

use forge::{Candidate, CandidateView, Compat, SavedTarget, ValidatedBy};
use launch::{LaunchKind, LaunchReply};
use lifecycle::{Coordinator, LifecycleError};
use lobby::{LobbyPoll, LobbySession};
use preferences::{LaunchPreferencesView, PreferenceSaveReply};
use resources::ForgeJar;

/// Stable issue codes the frontend maps to setup UI. Never infer state from message text.
mod issue {
    pub const MISSING_LAUNCHER: &str = "missing_launcher";
    pub const UNINITIALIZED_LUNAR: &str = "uninitialized_lunar";
    pub const RUNNING_LUNAR: &str = "running_lunar";
    pub const CONFLICTS: &str = "conflicts";
    pub const NO_COMPATIBLE_PRISM: &str = "no_compatible_prism_instance";
    pub const MISSING_BUNDLED_FORGE: &str = "missing_bundled_forge_jar";
    pub const SETUP_ERROR: &str = "setup_error";
    pub const RENAMED_JAR: &str = "renamed_jar";
}

/// One install target's own outcome. The two targets are reported independently and on
/// purpose: a friend with Forge but no Lunar, or the reverse, must not be shown the other
/// one's failure as the state of the whole app.
#[derive(Clone, Serialize)]
struct TargetStatus {
    /// "lunar" | "forge"
    kind: &'static str,
    /// "ready" | "blocked" | "absent" | "error"
    state: &'static str,
    /// Typed setup issue for the frontend. Omitted when there is nothing to act on.
    #[serde(skip_serializing_if = "Option::is_none")]
    issue: Option<&'static str>,
    message: String,
    /// Optional semantic detail (exact error text, renamed jar filenames).
    #[serde(skip_serializing_if = "Option::is_none")]
    detail: Option<String>,
    /// "none" | "choose" - whether the UI should offer instance selection.
    action: &'static str,
    candidates: Vec<CandidateView>,
    /// Filenames only, for optional renamed-jar diagnostics.
    quarantined_names: Vec<String>,
    /// Whether `open_setup_location` can reveal a folder for this target.
    has_setup_folder: bool,
    /// Backend-owned paths; never sent to the frontend.
    #[serde(skip)]
    conflict_dir: Option<PathBuf>,
    #[serde(skip)]
    install_path: Option<PathBuf>,
}

impl TargetStatus {
    fn new(kind: &'static str, state: &'static str, message: impl Into<String>) -> Self {
        TargetStatus {
            kind,
            state,
            issue: None,
            message: message.into(),
            detail: None,
            action: "none",
            candidates: Vec::new(),
            quarantined_names: Vec::new(),
            has_setup_folder: false,
            conflict_dir: None,
            install_path: None,
        }
    }

    fn with_issue(mut self, issue: &'static str) -> Self {
        self.issue = Some(issue);
        self
    }

    fn with_detail(mut self, detail: impl Into<String>) -> Self {
        self.detail = Some(detail.into());
        self
    }
}

/// Readiness is computed at startup and on explicit refresh. It DOES change in response to
/// an explicit user action (choosing a Prism instance), which is why it lives behind a
/// `Mutex`.
#[derive(Clone, Serialize)]
struct Status {
    /// "ready" | "blocked" | "error"
    state: &'static str,
    message: String,
    mod_version: Option<String>,
    targets: Vec<TargetStatus>,
}

impl Status {
    fn error(message: String) -> Self {
        Status {
            state: "error",
            message,
            mod_version: None,
            targets: Vec::new(),
        }
    }
}

fn quarantined_filenames(entries: &[String]) -> Vec<String> {
    entries
        .iter()
        .filter_map(|entry| {
            let trimmed = entry.trim();
            if trimmed.is_empty() {
                return None;
            }
            let tail = trimmed.rsplit(" -> ").next().unwrap_or(trimmed).trim();
            Path::new(tail)
                .file_name()
                .and_then(|name| name.to_str())
                .map(|name| name.to_string())
                .filter(|name| !name.is_empty())
        })
        .collect()
}

/// Everything the choice command needs after startup. `candidates` is backend-owned and
/// keyed by an opaque id: the frontend never sends us a filesystem path.
#[derive(Default)]
struct ForgeState {
    jar: Option<ForgeJar>,
    candidates: HashMap<String, Candidate>,
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
     right-click Lunar Client, then Quit. Then return to Cobblify.";
#[cfg(target_os = "macos")]
const LUNAR_RUNNING_HINT: &str =
    "Quit Lunar completely with ⌘Q, then return to Cobblify.";
#[cfg(not(any(windows, target_os = "macos")))]
const LUNAR_RUNNING_HINT: &str =
    "Quit Lunar completely, then return to Cobblify.";

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
        return TargetStatus::new("lunar", "absent", "Lunar Client is not installed.")
            .with_issue(issue::MISSING_LAUNCHER);
    }
    let launcher_json = home.join(".lunarclient/settings/launcher.json");
    if !launcher_json.exists() {
        return TargetStatus::new("lunar", "blocked", NEVER_RUN_LUNAR)
            .with_issue(issue::UNINITIALIZED_LUNAR);
    }
    let jars = match &res.lunar {
        Ok(j) => j,
        Err(e) => {
            return TargetStatus::new("lunar", "error", e.clone())
                .with_issue(issue::SETUP_ERROR)
                .with_detail(e.clone());
        }
    };

    let installed = match install::install_lunar(jars, &home.join(".weave")) {
        Ok(i) => i,
        Err(e) => {
            return TargetStatus::new("lunar", "error", e.clone())
                .with_issue(issue::SETUP_ERROR)
                .with_detail(e);
        }
    };
    if !installed.conflicts.is_empty() {
        let mut t =
            TargetStatus::new("lunar", "blocked", "Conflicting jars.").with_issue(issue::CONFLICTS);
        t.has_setup_folder = true;
        t.conflict_dir = Some(home.join(".weave/mods"));
        return t;
    }

    match lunar_config::register(&launcher_json, &installed.agent_path) {
        Ok(()) => {
            let mut t = TargetStatus::new("lunar", "ready", "Lunar Client");
            t.install_path = Some(installed.agent_path);
            t
        }
        Err(lunar_config::RegisterError::LunarRunning) => {
            TargetStatus::new("lunar", "blocked", LUNAR_RUNNING_HINT)
                .with_issue(issue::RUNNING_LUNAR)
        }
        Err(lunar_config::RegisterError::Failed(e)) => {
            TargetStatus::new("lunar", "error", e.clone())
                .with_issue(issue::SETUP_ERROR)
                .with_detail(e)
        }
    }
}

// ── Forge ───────────────────────────────────────────────────────────────────────

/// Turns a completed Forge install into a target status.
fn forge_ready(out: forge::Outcome, game_dir: &Path) -> TargetStatus {
    let mut t = if out.blocked {
        TargetStatus::new("forge", "blocked", "Conflicting jars.").with_issue(issue::CONFLICTS)
    } else {
        TargetStatus::new("forge", "ready", "Prism Forge 1.8.9")
    };
    if out.blocked {
        t.has_setup_folder = true;
        t.conflict_dir = Some(game_dir.join("mods"));
    } else if let Some(path) = out.path {
        t.install_path = Some(PathBuf::from(path));
    }
    let names = quarantined_filenames(&out.quarantined);
    if !names.is_empty() {
        t.quarantined_names = names.clone();
        t.has_setup_folder = true;
        t.conflict_dir = Some(game_dir.join("mods"));
        if !out.blocked {
            t.issue = Some(issue::RENAMED_JAR);
            t.detail = Some(names.join(", "));
        }
    }
    t
}

fn forge_failed(e: forge::ForgeError, game_dir: Option<&Path>) -> TargetStatus {
    let names = quarantined_filenames(&e.quarantined);
    let mut t = TargetStatus::new("forge", "error", "Setup failed.")
        .with_issue(issue::SETUP_ERROR)
        .with_detail(e.message);
    if !names.is_empty() {
        t.quarantined_names = names;
        if let Some(dir) = game_dir {
            t.has_setup_folder = true;
            t.conflict_dir = Some(dir.join("mods"));
        }
    }
    t
}

fn forge_choose_with_prism(candidates: &[Candidate], prism_installed: bool) -> TargetStatus {
    let confirmed: Vec<&Candidate> = candidates
        .iter()
        .filter(|c| c.compat == Compat::Confirmed)
        .collect();

    if !prism_installed {
        return TargetStatus::new("forge", "absent", "Prism Launcher is not installed.")
            .with_issue(issue::MISSING_LAUNCHER);
    }

    if confirmed.is_empty() {
        return TargetStatus::new("forge", "absent", "No compatible Prism instance was found.")
            .with_issue(issue::NO_COMPATIBLE_PRISM);
    }

    let mut t = TargetStatus::new("forge", "absent", "Choose a Prism instance.");
    t.action = "choose";
    t.candidates = confirmed.iter().map(|c| c.view()).collect();
    t
}

fn forge_choose(candidates: &[Candidate]) -> TargetStatus {
    forge_choose_with_prism(candidates, forge::prism_installed())
}

/// Installs into a candidate and remembers it, but only once the install succeeded.
fn adopt(jar: &ForgeJar, c: &Candidate, home: &Path) -> TargetStatus {
    let out = match forge::install(jar, &c.game_dir) {
        Ok(o) => o,
        Err(e) => return forge_failed(e, Some(&c.game_dir)),
    };
    if out.blocked {
        return forge_ready(out, &c.game_dir);
    }
    let saved = SavedTarget {
        game_dir: c.game_dir.display().to_string(),
        validated_by: ValidatedBy::Marker,
        marker: c.marker.clone(),
    };
    let mut t = forge_ready(out, &c.game_dir);
    if let Err(e) = forge::save_target(home, &saved) {
        t.message = format!(
            "{} (could not remember this Prism instance: {e})",
            t.message
        );
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
            targets,
        };
    }

    if let Some(blocked) = targets.iter().find(|t| t.state == "blocked") {
        return Status {
            state: "blocked",
            message: blocked.message.clone(),
            mod_version: version,
            targets,
        };
    }

    if let Some(err) = targets.iter().find(|t| t.state == "error") {
        return Status {
            state: "error",
            message: err.message.clone(),
            mod_version: version,
            targets,
        };
    }

    let forge = targets.iter().find(|t| t.kind == "forge");
    let message = if forge
        .and_then(|t| t.issue)
        .is_some_and(|i| i == issue::MISSING_LAUNCHER)
    {
        "Install Prism Launcher to set up Forge.".to_string()
    } else if forge
        .and_then(|t| t.issue)
        .is_some_and(|i| i == issue::NO_COMPATIBLE_PRISM)
    {
        "No compatible Prism instance was found.".to_string()
    } else if forge.map(|t| t.action == "choose").unwrap_or(false) {
        "Choose a Prism instance.".to_string()
    } else if forge
        .and_then(|t| t.issue)
        .is_some_and(|i| i == issue::MISSING_BUNDLED_FORGE)
    {
        "This copy does not include Forge.".to_string()
    } else {
        "Setup needed.".to_string()
    };

    Status {
        state: "blocked",
        message,
        mod_version: version,
        targets,
    }
}

/// Cached verified resources so `refresh_setup` can rebuild without re-reading the manifest path.
struct SetupContext {
    resource_dir: PathBuf,
    mod_version: Option<String>,
}

fn rebuild_setup(home: &Path, ctx: &SetupContext) -> (Status, ForgeState) {
    let none = ForgeState::default();
    let verified = match resources::verify(&ctx.resource_dir) {
        Ok(v) => v,
        Err(e) => return (Status::error(e), none),
    };

    let lunar = set_up_lunar(&verified, home);

    let mut state = ForgeState::default();
    let forge_target = match &verified.forge {
        None => TargetStatus::new("forge", "absent", "This copy does not include Forge.")
            .with_issue(issue::MISSING_BUNDLED_FORGE),
        Some(Err(e)) => TargetStatus::new("forge", "error", "Setup failed.")
            .with_issue(issue::SETUP_ERROR)
            .with_detail(e.clone()),
        Some(Ok(jar)) => {
            state.jar = Some(jar.clone());
            match forge::load_target(home)
                .as_ref()
                .and_then(forge::revalidate)
            {
                Some(c) => adopt(jar, &c, home),
                None => {
                    let candidates = forge::Env::from_process()
                        .map(|env| forge::detect(&env))
                        .unwrap_or_default();
                    let t = forge_choose(&candidates);
                    for c in candidates
                        .into_iter()
                        .filter(|c| c.compat == Compat::Confirmed)
                    {
                        state.candidates.insert(c.id.clone(), c);
                    }
                    t
                }
            }
        }
    };

    (
        aggregate(ctx.mod_version.clone(), vec![lunar, forge_target]),
        state,
    )
}

fn start_up(app: &tauri::AppHandle) -> (Status, ForgeState, SetupContext) {
    let none = ForgeState::default();
    let resource_dir = match app.path().resource_dir() {
        Ok(dir) => dir.join("resources"),
        Err(e) => {
            return (
                Status::error(format!("Cannot locate the bundled resources: {e}")),
                none,
                SetupContext {
                    resource_dir: PathBuf::new(),
                    mod_version: None,
                },
            )
        }
    };
    let verified = match resources::verify(&resource_dir) {
        Ok(v) => v,
        Err(e) => {
            return (
                Status::error(e),
                none,
                SetupContext {
                    resource_dir,
                    mod_version: None,
                },
            )
        }
    };
    let version = verified.manifest.mod_version.clone();

    let home = match home() {
        Ok(h) => h,
        Err(e) => {
            return (
                Status::error(e),
                none,
                SetupContext {
                    resource_dir,
                    mod_version: Some(version),
                },
            )
        }
    };

    let ctx = SetupContext {
        resource_dir: resource_dir.clone(),
        mod_version: Some(version.clone()),
    };
    let (status, forge_state) = rebuild_setup(&home, &ctx);
    (status, forge_state, ctx)
}

// ── commands ────────────────────────────────────────────────────────────────────

#[tauri::command]
fn status(state: tauri::State<'_, Mutex<Status>>) -> Status {
    state.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

#[tauri::command]
fn refresh_setup(
    status: tauri::State<'_, Mutex<Status>>,
    forge: tauri::State<'_, Mutex<ForgeState>>,
    ctx: tauri::State<'_, SetupContext>,
    session: tauri::State<'_, SessionParts>,
) -> Result<Status, String> {
    session
        .coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .try_refresh()
        .map_err(lifecycle_err)?;
    let home = home()?;
    let (next, forge_state) = rebuild_setup(&home, &ctx);
    *forge.lock().unwrap_or_else(|e| e.into_inner()) = forge_state;
    *status.lock().unwrap_or_else(|e| e.into_inner()) = next.clone();
    session
        .coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .release();
    Ok(next)
}

#[derive(Serialize)]
struct LauncherInfo {
    installed: bool,
    download_url: &'static str,
}

fn parse_launcher_kind(kind: &str) -> Result<&'static str, String> {
    match kind {
        "lunar" => Ok("lunar"),
        "forge" => Ok("forge"),
        _ => Err("Unknown launcher kind.".to_string()),
    }
}

fn launcher_download_url(kind: &str) -> &'static str {
    match kind {
        "lunar" => "https://www.lunarclient.com/download",
        "forge" => "https://prismlauncher.org/download/",
        _ => unreachable!(),
    }
}

fn open_download_url(url: &str) -> Result<(), String> {
    #[cfg(windows)]
    {
        tauri_plugin_opener::open_url(url, None::<&str>)
            .map_err(|e| format!("Cannot open download page: {e}"))
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("/usr/bin/open")
            .arg(url)
            .status()
            .map_err(|e| format!("Cannot open download page: {e}"))?;
        Ok(())
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        let _ = url;
        Err("Opening download pages is not supported on this platform.".to_string())
    }
}

#[tauri::command]
fn get_launcher(
    kind: String,
    session: tauri::State<'_, SessionParts>,
) -> Result<LauncherInfo, String> {
    session
        .coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .try_setup()
        .map_err(lifecycle_err)?;
    let kind = parse_launcher_kind(&kind)?;
    let home = home()?;
    let installed = match kind {
        "lunar" => home.join(".lunarclient").exists(),
        "forge" => forge::prism_installed(),
        _ => unreachable!(),
    };
    let download_url = launcher_download_url(kind);
    let result = open_download_url(download_url).map(|()| LauncherInfo {
        installed,
        download_url,
    });
    session
        .coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .release();
    result
}

fn open_path(path: &Path) -> Result<(), String> {
    #[cfg(windows)]
    {
        tauri_plugin_opener::open_path(path, None::<&str>)
            .map_err(|e| format!("Cannot open folder: {e}"))
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("/usr/bin/open")
            .arg(path)
            .status()
            .map_err(|e| format!("Cannot open folder: {e}"))?;
        Ok(())
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        let _ = path;
        Err("Opening folders is not supported on this platform.".to_string())
    }
}

#[tauri::command]
fn open_launcher(
    kind: String,
    session: tauri::State<'_, SessionParts>,
) -> Result<(), String> {
    session
        .coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .try_setup()
        .map_err(lifecycle_err)?;
    let result = (|| -> Result<(), String> {
        parse_launcher_kind(&kind)?;
        match kind.as_str() {
            "lunar" => {
                #[cfg(target_os = "macos")]
                {
                    std::process::Command::new("/usr/bin/open")
                        .args(["-a", "Lunar Client"])
                        .status()
                        .map_err(|e| format!("Cannot open Lunar Client: {e}"))?;
                }
                #[cfg(windows)]
                {
                    let local =
                        std::env::var_os("LOCALAPPDATA").ok_or("LOCALAPPDATA is not set.")?;
                    let exe = PathBuf::from(local).join("Programs/lunarclient/Lunar Client.exe");
                    if !exe.is_file() {
                        return Err("Lunar Client is not installed.".to_string());
                    }
                    std::process::Command::new(&exe)
                        .spawn()
                        .map_err(|e| format!("Cannot open Lunar Client: {e}"))?;
                }
                #[cfg(not(any(windows, target_os = "macos")))]
                {
                    return Err(
                        "Opening Lunar Client is not supported on this platform.".to_string(),
                    );
                }
                Ok(())
            }
            "forge" => {
                let exe = forge::prism_exe();
                if !exe.is_file() {
                    return Err("Prism Launcher is not installed.".to_string());
                }
                std::process::Command::new(&exe)
                    .spawn()
                    .map_err(|e| format!("Cannot open Prism Launcher: {e}"))?;
                Ok(())
            }
            _ => unreachable!(),
        }
    })();
    session
        .coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .release();
    result
}

#[tauri::command]
fn open_setup_location(
    kind: String,
    status: tauri::State<'_, Mutex<Status>>,
    session: tauri::State<'_, SessionParts>,
) -> Result<(), String> {
    session
        .coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .try_setup()
        .map_err(lifecycle_err)?;
    let result = (|| -> Result<(), String> {
        parse_launcher_kind(&kind)?;
        let guard = status.lock().unwrap_or_else(|e| e.into_inner());
        let target = guard
            .targets
            .iter()
            .find(|t| t.kind == kind)
            .ok_or("That setup location is not available.")?;
        if !target.has_setup_folder {
            return Err("That setup location is not available.".to_string());
        }
        let dir = target
            .conflict_dir
            .as_ref()
            .ok_or("That setup location is not available.")?;
        open_path(dir)
    })();
    session
        .coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .release();
    result
}

/// Rebuilds the whole status after a Forge choice, so the UI re-renders from one shape.
fn republish(status: &tauri::State<'_, Mutex<Status>>, forge_target: TargetStatus) -> Status {
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

/// Installs into a Prism candidate the backend already detected.
fn install_into(
    id: &str,
    status: tauri::State<'_, Mutex<Status>>,
    forge: tauri::State<'_, Mutex<ForgeState>>,
) -> Result<Status, String> {
    let (jar, candidate) = {
        let guard = forge.lock().unwrap_or_else(|e| e.into_inner());
        let jar = guard
            .jar
            .clone()
            .ok_or("This copy of Cobblify does not include Forge.")?;
        let c =
            guard.candidates.get(id).cloned().ok_or(
                "That Prism instance is no longer available - reopen Cobblify and try again.",
            )?;
        (jar, c)
    };

    match &candidate.compat {
        Compat::Incompatible(why) => return Err(why.clone()),
        Compat::Unknown => {
            return Err(
                "That Prism instance could not be verified - fix or recreate it in Prism Launcher first."
                    .to_string(),
            )
        }
        Compat::Confirmed => {}
    }

    // Re-validate NOW: the instance may have changed between being listed and being clicked.
    let fresh = forge::classify_picked(&candidate.game_dir)?;
    if fresh.compat != Compat::Confirmed || fresh.marker != candidate.marker {
        return Err(
            "That Prism instance changed since it was listed - reopen Cobblify and try again."
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
    session: tauri::State<'_, SessionParts>,
) -> Result<Status, String> {
    session
        .coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .try_setup()
        .map_err(lifecycle_err)?;
    let result = install_into(&id, status, forge);
    session
        .coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .release();
    result
}

/// Launch-progress session state for cosmetic milestone polling.
#[derive(Default)]
struct ProgressState {
    baseline: Option<SystemTime>,
    forge_session: bool,
    last_mtime: Option<SystemTime>,
    steady_polls: u32,
}

/// Split mutexes so stalled lobby polling cannot block lifecycle admission.
#[derive(Clone)]
struct SessionParts {
    coordinator: Arc<Mutex<Coordinator>>,
    lobby: Arc<Mutex<LobbySession>>,
    progress: Arc<Mutex<ProgressState>>,
}

impl SessionParts {
    fn new() -> Self {
        SessionParts {
            coordinator: Arc::new(Mutex::new(Coordinator::default())),
            lobby: Arc::new(Mutex::new(LobbySession::default())),
            progress: Arc::new(Mutex::new(ProgressState::default())),
        }
    }
}

fn lifecycle_err(e: LifecycleError) -> String {
    match e {
        LifecycleError::Busy => "busy".into(),
        LifecycleError::Active => "active".into(),
        LifecycleError::PreferenceUncertain => "preference_uncertain".into(),
    }
}

fn launch_rejection(code: &'static str) -> LaunchReply {
    LaunchReply::Rejected {
        code,
        preferences: None,
        message: Some(code.into()),
    }
}

fn lifecycle_to_launch(e: LifecycleError) -> LaunchReply {
    match e {
        LifecycleError::Busy => launch_rejection("busy"),
        LifecycleError::Active => launch_rejection("active"),
        LifecycleError::PreferenceUncertain => launch_rejection("preference_uncertain"),
    }
}

#[tauri::command]
async fn launch_preferences(
    session: tauri::State<'_, SessionParts>,
) -> Result<LaunchPreferencesView, String> {
    let home = home()?;
    session
        .coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .try_refresh()
        .map_err(lifecycle_err)?;
    let coordinator = Arc::clone(&session.coordinator);
    match tauri::async_runtime::spawn_blocking(move || preferences::launch_preferences(&home)).await
    {
        Ok(Ok(view)) => {
            coordinator
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .release();
            Ok(view)
        }
        Ok(Err(e)) => {
            coordinator
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .release();
            Err(format!("{e:?}"))
        }
        Err(e) => {
            coordinator
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .release();
            Err(e.to_string())
        }
    }
}

#[tauri::command]
async fn set_auto_join_hypixel(
    enabled: bool,
    session: tauri::State<'_, SessionParts>,
) -> Result<PreferenceSaveReply, String> {
    let home = home()?;
    let coordinator = Arc::clone(&session.coordinator);
    let uncertain_retry = {
        let mut guard = coordinator.lock().unwrap_or_else(|e| e.into_inner());
        let uncertain = matches!(
            guard.state(),
            lifecycle::Lifecycle::PreferenceUncertain { desired }
                if desired == enabled
        );
        if uncertain {
            guard
                .try_save_while_uncertain(enabled)
                .map_err(lifecycle_err)?;
        } else if matches!(
            guard.state(),
            lifecycle::Lifecycle::PreferenceUncertain { .. }
        ) {
            return Err(lifecycle_err(LifecycleError::PreferenceUncertain));
        } else {
            guard.try_save().map_err(lifecycle_err)?;
        }
        uncertain
    };
    let reply = match tauri::async_runtime::spawn_blocking(move || {
        preferences::set_auto_join_hypixel(&home, enabled)
    })
    .await
    {
        Ok(Ok(r)) => r,
        Ok(Err(e)) => {
            let mut guard = coordinator.lock().unwrap_or_else(|e| e.into_inner());
            if uncertain_retry {
                guard.mark_uncertain(enabled);
            } else {
                guard.release();
            }
            return Err(format!("{e:?}"));
        }
        Err(e) => {
            let mut guard = coordinator.lock().unwrap_or_else(|e| e.into_inner());
            if uncertain_retry {
                guard.mark_uncertain(enabled);
            } else {
                guard.release();
            }
            return Err(e.to_string());
        }
    };
    let mut guard = coordinator.lock().unwrap_or_else(|e| e.into_inner());
    match &reply {
        PreferenceSaveReply::Indeterminate => {
            guard.mark_uncertain(enabled);
        }
        PreferenceSaveReply::Saved { .. } | PreferenceSaveReply::Reconciled { .. } => {
            guard.clear_uncertain_on_success();
            guard.release();
        }
        PreferenceSaveReply::NotSaved { .. } => {
            if uncertain_retry {
                guard.mark_uncertain(enabled);
            } else {
                guard.release();
            }
        }
    }
    Ok(reply)
}

#[tauri::command]
async fn lobby_state(session: tauri::State<'_, SessionParts>) -> Result<LobbyPoll, String> {
    let home = home()?;
    let lobby = Arc::clone(&session.lobby);
    let progress = Arc::clone(&session.progress);
    tauri::async_runtime::spawn_blocking(move || {
        if progress.lock().unwrap_or_else(|e| e.into_inner()).baseline.is_none() {
            return Ok(LobbyPoll::Unavailable { reason: None });
        }
        let now = SystemTime::now();
        Ok(lobby
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .poll(&home, now))
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn acknowledge_lobby_snapshot(
    token: u32,
    generation: u64,
    snapshot: serde_json::Value,
    session: tauri::State<'_, SessionParts>,
) -> Result<(), String> {
    let lobby = Arc::clone(&session.lobby);
    tauri::async_runtime::spawn_blocking(move || {
        lobby
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .acknowledge(token, generation, &snapshot)
            .map_err(|e| e.to_string())
    })
    .await
    .map_err(|e| e.to_string())?
}

async fn launch_target(
    kind: LaunchKind,
    expected_auto_join_hypixel: bool,
    session: tauri::State<'_, SessionParts>,
) -> Result<LaunchReply, String> {
    let home = home()?;
    let coordinator = Arc::clone(&session.coordinator);
    let generation = match coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .try_launch()
    {
        Ok(g) => g,
        Err(e) => return Ok(lifecycle_to_launch(e)),
    };

    let attempt = match tauri::async_runtime::spawn_blocking(move || {
        launch::launch_with_preflight(&home, kind, expected_auto_join_hypixel, generation)
    })
    .await
    {
        Ok(a) => a,
        Err(_) => {
            coordinator
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .release();
            return Ok(launch_rejection("native_launch_error"));
        }
    };

    match &attempt.reply {
        LaunchReply::Launched { .. } => {
            if let Some(baseline) = attempt.baseline {
                let mut progress = session.progress.lock().unwrap_or_else(|e| e.into_inner());
                progress.baseline = Some(baseline);
                progress.forge_session = matches!(kind, LaunchKind::Forge);
                progress.last_mtime = None;
                progress.steady_polls = 0;
                session
                    .lobby
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .reset_for_launch(generation, baseline);
            }
            coordinator
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .launch_active();
        }
        LaunchReply::PreexistingGame { .. } => {
            coordinator
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .release();
        }
        LaunchReply::Rejected { .. } => {
            coordinator
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .release();
            session
                .lobby
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clear_baseline();
            session.progress.lock().unwrap_or_else(|e| e.into_inner()).baseline = None;
        }
    }
    Ok(attempt.reply)
}

#[tauri::command(rename_all = "camelCase")]
async fn launch_lunar(
    expected_auto_join_hypixel: bool,
    session: tauri::State<'_, SessionParts>,
) -> Result<LaunchReply, String> {
    launch_target(LaunchKind::Lunar, expected_auto_join_hypixel, session).await
}

#[tauri::command(rename_all = "camelCase")]
async fn launch_forge(
    expected_auto_join_hypixel: bool,
    session: tauri::State<'_, SessionParts>,
) -> Result<LaunchReply, String> {
    launch_target(LaunchKind::Forge, expected_auto_join_hypixel, session).await
}

/// Cosmetic, fail-soft stepped progress for the launch button. Stats the weave
/// log, ignores it unless its mtime is at or after this session's baseline
/// (defeating the stale hard-linked latest.log), reads it, and maps the highest
/// milestone line to a bar stage. Any missing/unreadable log or unset baseline
/// reports the "fired" baseline - never an error. Only milestone substrings are
/// inspected; full log contents are never returned or logged.
fn compute_launch_progress(progress: &mut ProgressState) -> progress::Progress {
    let forge_session = progress.forge_session;
    let waiting = || {
        if forge_session {
            progress::progress_for_forge(progress::Milestone::Fired, false)
        } else {
            progress::progress_for(progress::Milestone::Fired, false)
        }
    };

    let Some(baseline) = progress.baseline else {
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
        progress.last_mtime = None;
        progress.steady_polls = 0;
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
    let steady = match (progress.last_mtime, mtime) {
        (Some(prev), Some(now)) if prev == now => progress.steady_polls + 1,
        _ => 0,
    };
    progress.steady_polls = steady;
    progress.last_mtime = mtime;
    let settled = milestone == progress::Milestone::Mixing
        && if forge_session {
            progress::forge_loaded(&log)
        } else {
            steady >= 2
        };
    if forge_session {
        progress::progress_for_forge(milestone, settled)
    } else {
        progress::progress_for(milestone, settled)
    }
}

#[tauri::command]
async fn launch_progress(
    session: tauri::State<'_, SessionParts>,
) -> Result<progress::Progress, String> {
    let progress = Arc::clone(&session.progress);
    match tauri::async_runtime::spawn_blocking(move || {
        let mut st = progress.lock().unwrap_or_else(|e| e.into_inner());
        compute_launch_progress(&mut st)
    })
    .await
    {
        Ok(progress) => Ok(progress),
        Err(_) => Ok(progress::progress_for(progress::Milestone::Fired, false)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Regression lock: the forbidden parameter shipped once (Aug 5) and
    /// could silently move a friend off 1.8.9.
    #[test]
    fn play_deep_link_pins_hypixel_and_bans_force_recommended() {
        assert!(launch::PLAY_HYPIXEL.starts_with("lunarclient://play?"));
        assert!(launch::PLAY_HYPIXEL.contains("serverAddress=play.hypixel.net"));
        assert!(!launch::PLAY_HYPIXEL.contains("forceRecommendedVersion"));
    }

    fn t(kind: &'static str, state: &'static str) -> TargetStatus {
        TargetStatus::new(kind, state, format!("{kind}-{state}"))
    }

    fn choosable() -> TargetStatus {
        let mut t = TargetStatus::new("forge", "absent", "Choose a Prism instance.");
        t.action = "choose";
        t.candidates.push(CandidateView {
            id: "d0".to_string(),
            name: "Hypixel".to_string(),
        });
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
        assert_eq!(
            agg(t("lunar", "blocked"), t("forge", "absent")).state,
            "blocked"
        );
        assert_eq!(
            agg(t("lunar", "error"), t("forge", "blocked")).state,
            "blocked"
        );
        assert_eq!(
            agg(t("lunar", "blocked"), t("forge", "error")).message,
            "lunar-blocked"
        );
    }

    #[test]
    fn an_error_surfaces_only_when_nothing_is_ready_or_blocked() {
        assert_eq!(
            agg(t("lunar", "error"), t("forge", "absent")).state,
            "error"
        );
        assert_eq!(
            agg(t("lunar", "absent"), t("forge", "error")).state,
            "error"
        );
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
        assert!(s.message.contains("Choose a Prism"), "{}", s.message);
    }

    /// Round-2 I2: an old five-key bundle on a Lunar-less machine must not show a picker
    /// that cannot succeed.
    #[test]
    fn all_absent_without_a_forge_jar_offers_nothing() {
        let mut forge = t("forge", "absent");
        forge.issue = Some(issue::MISSING_BUNDLED_FORGE);
        let s = agg(t("lunar", "absent"), forge);
        assert_eq!(s.state, "blocked");
        assert!(
            s.message.contains("does not include Forge"),
            "{}",
            s.message
        );
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
    fn conflicts_are_tracked_per_target() {
        let mut lunar = t("lunar", "blocked");
        lunar.issue = Some(issue::CONFLICTS);
        lunar.has_setup_folder = true;
        let mut forge = t("forge", "blocked");
        forge.issue = Some(issue::CONFLICTS);
        forge.has_setup_folder = true;
        let s = agg(lunar, forge);
        assert_eq!(s.state, "blocked");
        assert!(s.targets.iter().all(|t| t.issue == Some(issue::CONFLICTS)));
    }

    #[test]
    fn parse_launcher_kind_rejects_unknown_values() {
        assert!(parse_launcher_kind("lunar").is_ok());
        assert!(parse_launcher_kind("forge").is_ok());
        assert!(parse_launcher_kind("curseforge").is_err());
        assert!(parse_launcher_kind("../etc").is_err());
    }

    #[test]
    fn quarantined_filenames_extracts_only_names() {
        let names = quarantined_filenames(&[
            "/tmp/mods/Cobblify.jar -> Cobblify.jar.cobblify-disabled".to_string(),
        ]);
        assert_eq!(names, vec!["Cobblify.jar.cobblify-disabled".to_string()]);

        let bare_path = quarantined_filenames(&[
            "/Users/foo/Library/Application Support/PrismLauncher/instances/foo/mods/Other.jar"
                .to_string(),
        ]);
        assert_eq!(bare_path, vec!["Other.jar".to_string()]);

        let basename_only = quarantined_filenames(&["Cobblify.jar.cobblify-disabled".to_string()]);
        assert_eq!(
            basename_only,
            vec!["Cobblify.jar.cobblify-disabled".to_string()]
        );
    }

    #[test]
    fn forge_choose_reports_missing_prism() {
        let missing = forge_choose_with_prism(&[], false);
        assert_eq!(missing.issue, Some(issue::MISSING_LAUNCHER));
        assert!(missing.candidates.is_empty());
    }

    #[test]
    fn forge_choose_reports_no_compatible_instance() {
        let incompatible = forge_choose_with_prism(
            &[Candidate {
                id: "d0".into(),
                launcher: "Prism Launcher".into(),
                name: "Wrong".into(),
                game_dir: PathBuf::from("/tmp/wrong"),
                compat: Compat::Incompatible("nope".into()),
                marker: Some("mmc-pack.json".into()),
            }],
            true,
        );
        assert_eq!(incompatible.issue, Some(issue::NO_COMPATIBLE_PRISM));
        assert!(incompatible.candidates.is_empty());
    }

    #[test]
    fn forge_choose_publishes_confirmed_candidates_only() {
        let candidates = vec![
            Candidate {
                id: "d0".into(),
                launcher: "Prism Launcher".into(),
                name: "Right".into(),
                game_dir: PathBuf::from("/tmp/right"),
                compat: Compat::Confirmed,
                marker: Some("mmc-pack.json".into()),
            },
            Candidate {
                id: "d1".into(),
                launcher: "Prism Launcher".into(),
                name: "Unreadable".into(),
                game_dir: PathBuf::from("/tmp/unreadable"),
                compat: Compat::Unknown,
                marker: Some("mmc-pack.json".into()),
            },
        ];
        let t = forge_choose_with_prism(&candidates, true);
        assert_eq!(t.action, "choose");
        assert_eq!(t.candidates.len(), 1);
        assert_eq!(t.candidates[0].name, "Right");
    }
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        .setup(|app| {
            let (status, forge_state, ctx) = start_up(app.handle());
            app.manage(Mutex::new(status));
            app.manage(Mutex::new(forge_state));
            app.manage(ctx);
            app.manage(SessionParts::new());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            status,
            refresh_setup,
            get_launcher,
            open_launcher,
            open_setup_location,
            launch_preferences,
            set_auto_join_hypixel,
            lobby_state,
            acknowledge_lobby_snapshot,
            launch_lunar,
            launch_forge,
            launch_progress,
            choose_forge_target
        ])
        .run(tauri::generate_context!())
        .expect("failed to start the Cobblify launcher");
}
