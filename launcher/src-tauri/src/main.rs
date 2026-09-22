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
    pub fn rehide_once() {}
    pub fn restore_lunar_windows() {}
    pub fn quit_lunar_launcher(_commit: &dyn Fn() -> bool) {}
}
mod hide_registry;
mod install;
mod nojoin;
mod lunar_config;
mod proc;
mod progress;
mod resources;
mod updater;

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
use preferences::{
    LaunchPreferencesView, PreferenceSaveReply, UpdatePreferenceSaveReply, UpdatePreferencesView,
};
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
    set_up_lunar_with(proc::lunar_running_checked, res, home)
}

/// The running check is injected so its POSITION is testable. It has to come
/// before `install_lunar` (L7): `register` holds a check of its own, but by
/// the time it runs the two jars are already in `~/.weave` and the directory
/// has been created on a machine whose setup then reports `blocked`. The
/// check here is the gate on writing anything at all; `register` keeps its
/// own (the state can change in between, and it is the one guarding the
/// `launcher.json` write itself).
fn set_up_lunar_with(
    lunar_running: impl Fn() -> Result<bool, String>,
    res: &resources::Resources,
    home: &Path,
) -> TargetStatus {
    if !home.join(".lunarclient").exists() {
        return TargetStatus::new("lunar", "absent", "Lunar Client is not installed.")
            .with_issue(issue::MISSING_LAUNCHER);
    }
    let launcher_json = home.join(".lunarclient/settings/launcher.json");
    if !launcher_json.exists() {
        return TargetStatus::new("lunar", "blocked", NEVER_RUN_LUNAR)
            .with_issue(issue::UNINITIALIZED_LUNAR);
    }
    match lunar_running() {
        Err(e) => {
            return TargetStatus::new("lunar", "error", e.clone())
                .with_issue(issue::SETUP_ERROR)
                .with_detail(e);
        }
        Ok(true) => {
            return TargetStatus::new("lunar", "blocked", LUNAR_RUNNING_HINT)
                .with_issue(issue::RUNNING_LUNAR);
        }
        Ok(false) => {}
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

/// `--uninstall-lunar-integration`: undo what Lunar setup did, for the NSIS
/// uninstaller (R3). Uninstalling the launcher otherwise leaves Lunar
/// pointed at a `-javaagent:` whose jar is about to be deleted, which is a
/// Lunar that will not start.
///
/// The return value is an EXIT CODE, and it is the hook's whole contract:
///
///   * 0 - the agent is unregistered and our jars are gone.
///   * 3 - Lunar is running, or it cannot be determined whether it is. Lunar
///     rewrites `launcher.json` on exit and would put the javaagent back,
///     pointing at a jar that no longer exists.
///   * 4 - another copy of this launcher is alive and can re-register the
///     agent behind us.
///   * 1 - an I/O error; the message is on stderr.
///
/// The two REFUSALS (3, 4) are taken before the first write, so they alone
/// guarantee nothing was changed. A 1 can follow a rewritten `launcher.json`
/// whose jar deletion then failed, which is "partially undone, look at
/// `~/.weave`". The hook aborts the uninstall on any non-zero exit either way.
/// Never shows a dialog: the installer owns the UI, and this runs headless.
pub(crate) fn uninstall_lunar_integration_with(
    lunar_running: impl Fn() -> Result<bool, String>,
    other_launcher_running: impl Fn() -> bool,
    home: &Path,
) -> i32 {
    match lunar_running() {
        Err(e) => {
            eprintln!("Cannot tell whether Lunar Client is running: {e}");
            return 3;
        }
        Ok(true) => {
            eprintln!("Lunar Client is running.");
            return 3;
        }
        Ok(false) => {}
    }
    if other_launcher_running() {
        eprintln!("Another Cobblify launcher process is running.");
        return 4;
    }

    if let Err(e) = lunar_config::unregister(&home.join(".lunarclient/settings/launcher.json")) {
        eprintln!("{e}");
        return 1;
    }
    if let Err(e) = install::uninstall_lunar(&home.join(".weave")) {
        eprintln!("{e}");
        return 1;
    }
    0
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
    with_claim(&session.coordinator, Coordinator::try_refresh, || {
        let home = home()?;
        let (next, forge_state) = rebuild_setup(&home, &ctx);
        *forge.lock().unwrap_or_else(|e| e.into_inner()) = forge_state;
        *status.lock().unwrap_or_else(|e| e.into_inner()) = next.clone();
        Ok(next)
    })
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
    with_claim(&session.coordinator, Coordinator::try_setup, || {
        let kind = parse_launcher_kind(&kind)?;
        let home = home()?;
        let installed = match kind {
            "lunar" => home.join(".lunarclient").exists(),
            "forge" => forge::prism_installed(),
            _ => unreachable!(),
        };
        let download_url = launcher_download_url(kind);
        open_download_url(download_url).map(|()| LauncherInfo {
            installed,
            download_url,
        })
    })
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
                    let programs = PathBuf::from(local).join("Programs");
                    // L6: both install layouts, exactly as `open_lunar_app_only` sees them.
                    let exe = launch::lunar_exe_in(&programs)
                        .ok_or("Lunar Client is not installed.")?;
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
    /// D1c fires at most once per launch generation.
    early_hide_fired: bool,
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

    /// Same parts around a pre-built lobby session (injected clock/probe).
    #[cfg(test)]
    fn with_lobby(lobby: LobbySession) -> Self {
        SessionParts {
            coordinator: Arc::new(Mutex::new(Coordinator::default())),
            lobby: Arc::new(Mutex::new(lobby)),
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

/// Runs `body` under a lifecycle claim (`try_refresh` or `try_setup`) that is released on EVERY
/// exit path.
///
/// The lock is held only to claim and to release, never across the body, so a slow setup
/// cannot block admission checks. What it buys is that the release is not on the happy
/// path: a `?` between the claim and the release - which is what these commands used to
/// do - leaves the coordinator out of `Idle` forever, and every later setup, save or
/// launch is refused as busy until the app restarts.
fn with_claim<T>(
    coordinator: &Mutex<Coordinator>,
    claim: impl FnOnce(&mut Coordinator) -> Result<(), LifecycleError>,
    body: impl FnOnce() -> Result<T, String>,
) -> Result<T, String> {
    claim(&mut coordinator.lock().unwrap_or_else(|e| e.into_inner())).map_err(lifecycle_err)?;
    let result = body();
    coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .release();
    result
}

fn launch_rejection(code: &'static str) -> LaunchReply {
    LaunchReply::Rejected {
        code,
        preferences: None,
        message: Some(code.into()),
    }
}

fn launch_rejection_msg(code: &'static str, message: String) -> LaunchReply {
    LaunchReply::Rejected {
        code,
        preferences: None,
        message: Some(message),
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
async fn update_preferences() -> Result<UpdatePreferencesView, String> {
    let home = home()?;
    tauri::async_runtime::spawn_blocking(move || preferences::update_preferences(&home))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| format!("{e:?}"))
}

#[tauri::command]
async fn set_auto_update(enabled: bool) -> Result<UpdatePreferenceSaveReply, String> {
    let home = home()?;
    tauri::async_runtime::spawn_blocking(move || preferences::set_auto_update(&home, enabled))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| format!("{e:?}"))
}

#[tauri::command]
async fn set_update_channel(channel: String) -> Result<UpdatePreferenceSaveReply, String> {
    let home = home()?;
    let channel = match channel.as_str() {
        "stable" => preferences::UpdateChannel::Stable,
        "dev" => preferences::UpdateChannel::Dev,
        other => return Err(format!("unknown update channel: {other}")),
    };
    tauri::async_runtime::spawn_blocking(move || preferences::set_update_channel(&home, channel))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| format!("{e:?}"))
}

async fn set_preference(
    key: lifecycle::PrefKey,
    enabled: bool,
    session: tauri::State<'_, SessionParts>,
) -> Result<PreferenceSaveReply, String> {
    let home = home()?;
    let coordinator = Arc::clone(&session.coordinator);
    let uncertain_retry = {
        let mut guard = coordinator.lock().unwrap_or_else(|e| e.into_inner());
        let uncertain = matches!(
            guard.state(),
            lifecycle::Lifecycle::PreferenceUncertain { key: k, desired }
                if k == key && desired == enabled
        );
        if uncertain {
            guard
                .try_save_while_uncertain(key, enabled)
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
    let reply = match tauri::async_runtime::spawn_blocking(move || match key {
        lifecycle::PrefKey::AutoJoin => preferences::set_auto_join_hypixel(&home, enabled),
        lifecycle::PrefKey::Overlay => preferences::set_use_external_overlay(&home, enabled),
    })
    .await
    {
        Ok(Ok(r)) => r,
        Ok(Err(e)) => {
            let mut guard = coordinator.lock().unwrap_or_else(|e| e.into_inner());
            if uncertain_retry {
                guard.mark_uncertain(key, enabled);
            } else {
                guard.release();
            }
            return Err(format!("{e:?}"));
        }
        Err(e) => {
            let mut guard = coordinator.lock().unwrap_or_else(|e| e.into_inner());
            if uncertain_retry {
                guard.mark_uncertain(key, enabled);
            } else {
                guard.release();
            }
            return Err(e.to_string());
        }
    };
    let mut guard = coordinator.lock().unwrap_or_else(|e| e.into_inner());
    match &reply {
        PreferenceSaveReply::Indeterminate => {
            guard.mark_uncertain(key, enabled);
        }
        PreferenceSaveReply::Saved { .. } | PreferenceSaveReply::Reconciled { .. } => {
            guard.clear_uncertain_on_success();
            guard.release();
        }
        PreferenceSaveReply::NotSaved { .. } => {
            if uncertain_retry {
                guard.mark_uncertain(key, enabled);
            } else {
                guard.release();
            }
        }
    }
    Ok(reply)
}

#[tauri::command]
async fn set_auto_join_hypixel(
    enabled: bool,
    session: tauri::State<'_, SessionParts>,
) -> Result<PreferenceSaveReply, String> {
    set_preference(lifecycle::PrefKey::AutoJoin, enabled, session).await
}

#[tauri::command]
async fn set_use_external_overlay(
    enabled: bool,
    session: tauri::State<'_, SessionParts>,
) -> Result<PreferenceSaveReply, String> {
    set_preference(lifecycle::PrefKey::Overlay, enabled, session).await
}

/// D1c trigger, as a pure decision. The FIRST poll of a Lunar session that
/// shows an armed absence (the session's own `grace` reason) or a proven
/// session end starts the early hide - Lunar re-shows its launcher window
/// the moment the game exits, and the reset-time terminate is ~1 s later.
/// `game_session_changed` is excluded on purpose: a verified-live
/// replacement writer exists, so no launcher window is coming.
fn early_hide_trigger(poll: &LobbyPoll, lunar_session: bool, already_fired: bool) -> bool {
    if !lunar_session || already_fired {
        return false;
    }
    match poll {
        LobbyPoll::Unavailable { reason: Some(reason) } => reason == "grace",
        LobbyPoll::SessionEnded { reason } => *reason == "game_session_ended",
        _ => false,
    }
}

/// Captures the CURRENT launcher identity and starts the registered,
/// identity-bound early-hide worker. Skips silently when the identity is
/// unavailable - an Indeterminate never acts.
#[cfg(target_os = "macos")]
fn start_early_hide() {
    let Some(pid) = proc::lunar_launcher_pid() else {
        return;
    };
    let Some(birth) = process_liveness::process_birth_ns(pid) else {
        return;
    };
    hide::spawn_bound_rehide(pid, birth);
}

#[cfg(not(target_os = "macos"))]
fn start_early_hide() {}

#[tauri::command]
async fn lobby_state(session: tauri::State<'_, SessionParts>) -> Result<LobbyPoll, String> {
    let home = home()?;
    let lobby = Arc::clone(&session.lobby);
    let progress = Arc::clone(&session.progress);
    tauri::async_runtime::spawn_blocking(move || {
        let lunar_session = {
            let progress = progress.lock().unwrap_or_else(|e| e.into_inner());
            if progress.baseline.is_none() {
                return Ok(LobbyPoll::Unavailable { reason: None });
            }
            !progress.forge_session
        };
        let now = SystemTime::now();
        let poll = lobby
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .poll(&home, now);
        let fire = {
            let mut progress = progress.lock().unwrap_or_else(|e| e.into_inner());
            let fire = early_hide_trigger(&poll, lunar_session, progress.early_hide_fired);
            if fire {
                progress.early_hide_fired = true;
            }
            fire
        };
        if fire {
            start_early_hide();
        }
        Ok(poll)
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

/// Session-reset reply. Both success variants ALWAYS carry a preference
/// view so the home page can resync its checkboxes atomically; an
/// unreadable file degrades to the same defaults-with-invalid-health view
/// the normal preference read produces.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "status", rename_all = "snake_case")]
enum ResetReply {
    Ok { preferences: LaunchPreferencesView },
    AlreadyReset { preferences: LaunchPreferencesView },
    NotTerminal,
    Busy,
}

fn prefs_view_or_fallback(home: &Path) -> LaunchPreferencesView {
    match preferences::launch_preferences(home) {
        Ok(view) => view,
        Err(e) => LaunchPreferencesView {
            auto_join_hypixel: true,
            use_external_overlay: true,
            health: preferences::PreferenceHealth::Invalid,
            diagnostic: Some(format!("{e:?}")),
        },
    }
}

/// Whether the reset must prove a terminal first. `Abort` is the forcing
/// admission (D2b): it skips ONLY the not-terminal refusal, and clears the
/// lobby through the abort evidence rule instead of the terminal one.
/// Everything else - serialization, Busy-from-Launching, the Lunar quit,
/// Idle published last - is identical.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ResetMode {
    Terminal,
    Abort,
}

/// The reset transaction. Locks are taken one at a time; the exclusive
/// `Resetting` lifecycle state is the mutual exclusion for the whole
/// sequence, and Idle is published last.
/// `quit_lunar` runs (bounded, fail-soft) for a LUNAR session only, after
/// the transaction commits: Lunar's launcher re-shows itself when the game
/// exits and, while it runs, the setup refresh cannot re-register the
/// agent - quitting it is what makes home immediately launchable again.
fn reset_session_end_inner(
    parts: &SessionParts,
    home: &Path,
    mode: ResetMode,
    quit_lunar: impl FnOnce(),
) -> ResetReply {
    let prefs_view = || prefs_view_or_fallback(home);
    match parts
        .coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .try_begin_reset()
    {
        lifecycle::ResetBegin::AlreadyIdle => {
            return ResetReply::AlreadyReset {
                preferences: prefs_view(),
            };
        }
        lifecycle::ResetBegin::Busy => return ResetReply::Busy,
        lifecycle::ResetBegin::Begun => {}
    }
    let forge_session = {
        parts
            .progress
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .forge_session
    };
    {
        let mut lobby = parts.lobby.lock().unwrap_or_else(|e| e.into_inner());
        if mode == ResetMode::Terminal && lobby.terminal_reason().is_none() {
            drop(lobby);
            parts
                .coordinator
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .abort_reset();
            return ResetReply::NotTerminal;
        }
        match mode {
            ResetMode::Terminal => lobby.clear_for_reset(),
            ResetMode::Abort => lobby.clear_for_abort(forge_session, home),
        }
    }
    let was_lunar_session = {
        let mut progress = parts.progress.lock().unwrap_or_else(|e| e.into_inner());
        let lunar = progress.baseline.is_some() && !progress.forge_session;
        progress.baseline = None;
        progress.forge_session = false;
        progress.last_mtime = None;
        progress.steady_polls = 0;
        progress.early_hide_fired = false;
        lunar
    };
    // The destructive Lunar quit runs INSIDE the exclusive `Resetting`
    // state, before Idle is published: no fresh launch can be admitted
    // while termination can still act. (Production wraps this in
    // run_destructive_bounded, whose commit gate guarantees a timed-out
    // pass never acts late; only a committed pass that also blows its own
    // internal bounds can linger, and that is reported, not silent.)
    if was_lunar_session {
        quit_lunar();
    }
    parts
        .coordinator
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .finish_reset();
    ResetReply::Ok {
        preferences: prefs_view(),
    }
}

/// Late-success rehide: invoked exactly once by the frontend when a
/// fired-but-unconfirmed launch is proven real (settled or a live lobby
/// snapshot). Always replies promptly; hide is fail-soft by contract, so
/// native failure never surfaces as an error.
fn rehide_inner(use_external_overlay: bool) {
    if use_external_overlay {
        // Fresh registered finite worker: full repeated coverage restored.
        hide::spawn_worker();
    } else {
        // One pass, ordered strictly before the reply so an overlay-off
        // exit cannot kill the corrective actor - but BOUNDED: the command
        // must always reply so the exit can proceed even if the native
        // call hangs (hung-pass residual, cosmetic).
        hide_registry::run_pass_bounded(
            || hide::rehide_once(),
            std::time::Duration::from_secs(3),
        );
    }
}

/// Overlay-off exit. The game, Lunar, and Prism are separate processes and
/// survive; in-process hide threads die with the app (accepted narrowing).
#[tauri::command]
fn quit_app(app_handle: tauri::AppHandle) {
    app_handle.exit(0);
}

#[tauri::command(rename_all = "camelCase")]
async fn rehide_after_confirmation(use_external_overlay: bool) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || rehide_inner(use_external_overlay))
        .await
        .map_err(|e| e.to_string())
}

async fn run_reset(session: &tauri::State<'_, SessionParts>, mode: ResetMode) -> Result<ResetReply, String> {
    let home = home()?;
    let parts = SessionParts {
        coordinator: Arc::clone(&session.coordinator),
        lobby: Arc::clone(&session.lobby),
        progress: Arc::clone(&session.progress),
    };
    tauri::async_runtime::spawn_blocking(move || {
        reset_session_end_inner(&parts, &home, mode, || {
            // Destructive gate: a hung osascript can only delay the reply
            // up to bound+grace, and a pass that missed the bound before
            // committing can never terminate anything afterwards.
            hide_registry::run_destructive_bounded(
                |commit| hide::quit_lunar_launcher(commit),
                std::time::Duration::from_secs(3),
                std::time::Duration::from_secs(3),
            );
        })
    })
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
async fn reset_session_end(session: tauri::State<'_, SessionParts>) -> Result<ResetReply, String> {
    run_reset(&session, ResetMode::Terminal).await
}

/// The forcing abort (D2b): the user cancelling a launch that never proved a
/// terminal. Same wire shape as `reset_session_end`; `not_terminal` is
/// simply never returned here.
#[tauri::command]
async fn abort_launch_session(
    session: tauri::State<'_, SessionParts>,
) -> Result<ResetReply, String> {
    run_reset(&session, ResetMode::Abort).await
}

/// Guard pruning as a pure function over identity checks: only
/// `DefinitelyGone` prunes; `AliveSameIdentity` AND `Indeterminate` (a
/// query the OS refused) conservatively keep blocking.
fn prune_guards(
    guards: Vec<lobby::WriterProof>,
    mut check: impl FnMut(&lobby::WriterProof) -> process_liveness::IdentityCheck,
) -> (Vec<lobby::WriterProof>, bool) {
    let mut remaining = Vec::new();
    for guard in guards {
        match check(&guard) {
            process_liveness::IdentityCheck::DefinitelyGone => {}
            _ => remaining.push(guard),
        }
    }
    let blocked = !remaining.is_empty();
    (remaining, blocked)
}

/// A Forge log touched at or after the aborted launch's baseline and this
/// recently means a game from that launch is actively booting.
const FML_ACTIVE_WINDOW: std::time::Duration = std::time::Duration::from_secs(15);

/// Post-abort admission decision, pure over one probe result so the whole
/// matrix is testable. There is deliberately NO time-based cooldown layer:
/// the user overturned it (2026-08-21, recorded in TASK.md) - only
/// evidence may refuse a click, never a blind timer. The residual double-
/// launch risk of an unobservable pending launch is accepted.
#[derive(Debug, Clone, PartialEq)]
enum AbortAdmission {
    /// Fail-closed: the route probe could not prove safety.
    Unproven,
    /// Conversion: concrete live identities become launch guards.
    /// The record survives when the same probe ALSO carried uncertainty
    /// about an identity it could not attribute (review finding).
    Guard {
        identities: Vec<lobby::WriterProof>,
        keep_record: bool,
    },
    /// Admitted - and the record is deliberately KEPT (see CB-2).
    Admit,
}

fn abort_admission(probe: lobby::AdmissionProbe) -> AbortAdmission {
    match probe {
        lobby::AdmissionProbe::Live {
            identities,
            uncertain,
        } => AbortAdmission::Guard {
            identities,
            keep_record: uncertain,
        },
        lobby::AdmissionProbe::Indeterminate => AbortAdmission::Unproven,
        lobby::AdmissionProbe::Clean => AbortAdmission::Admit,
    }
}

/// Forge activity probe: MTIME ONLY, no contents are read. A log touched at
/// or after the aborted launch's baseline and still recent means that
/// launch's game is booting right now.
fn fml_log_active(
    mtime: Option<SystemTime>,
    baseline: SystemTime,
    now: SystemTime,
) -> bool {
    let Some(mtime) = mtime else {
        return false;
    };
    if mtime < baseline {
        return false;
    }
    match now.duration_since(mtime) {
        Ok(age) => age < FML_ACTIVE_WINDOW,
        // A log stamped in the future is fresher than now, not stale.
        Err(_) => true,
    }
}

fn fml_log_mtime(home: &Path) -> Option<SystemTime> {
    let saved = forge::load_target(home)?;
    let path = PathBuf::from(saved.game_dir).join("logs/fml-client-latest.log");
    std::fs::metadata(path).and_then(|m| m.modified()).ok()
}

/// Applies the layered admission for a recorded unproven abort. Returns the
/// rejection message when the launch must be refused; `None` admits.
///
/// A clean probe ADMITS but never clears the record: a point-in-time probe
/// is not proof that the aborted launch produced nothing, so every later
/// preflight repeats the check (binding review condition; the residual
/// itself is contract boundary CB-2).
fn apply_abort_admission(
    parts: &SessionParts,
    probe: impl FnOnce(&lobby::UnprovenAbort) -> lobby::AdmissionProbe,
) -> Option<String> {
    let record = {
        let lobby = parts.lobby.lock().unwrap_or_else(|e| e.into_inner());
        lobby.unproven_abort()?
    };
    match abort_admission(probe(&record)) {
        AbortAdmission::Unproven => Some(
            "Cobblify cannot confirm the cancelled launch has finished - try again in a moment."
                .to_string(),
        ),
        AbortAdmission::Guard {
            identities,
            keep_record,
        } => {
            let mut lobby = parts.lobby.lock().unwrap_or_else(|e| e.into_inner());
            lobby.add_session_guards(identities);
            if keep_record {
                // Coexisting uncertainty existed AT this probe, so THIS
                // click must refuse too - the guards alone cannot carry
                // that refusal (the guarded identity could die before the
                // guard check and admit a launch over the unproven one).
                return Some(
                    "Cobblify cannot confirm the cancelled launch has finished - try again in a moment."
                        .to_string(),
                );
            }
            // Fully converted to concrete guards: the record has done its
            // job.
            lobby.clear_unproven_abort();
            None
        }
        AbortAdmission::Admit => None,
    }
}

/// Production route probe for a recorded abort: the Lunar structured scan or
/// the Forge fml-activity probe, chosen by the ABORTED launch's route.
fn route_probe(
    parts: &SessionParts,
    home: &Path,
    record: &lobby::UnprovenAbort,
) -> lobby::AdmissionProbe {
    if record.forge {
        if fml_log_active(fml_log_mtime(home), record.baseline, SystemTime::now()) {
            lobby::AdmissionProbe::Indeterminate
        } else {
            lobby::AdmissionProbe::Clean
        }
    } else {
        parts
            .lobby
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .probe_post_baseline_games(home, record.baseline)
    }
}

/// File-independent guard against relaunching over a writer a session reset
/// released while it was (or might still be) alive.
fn session_guards_block_launch(lobby: &Mutex<LobbySession>) -> bool {
    let guards = lobby.lock().unwrap_or_else(|e| e.into_inner()).session_guards();
    if guards.is_empty() {
        return false;
    }
    let (remaining, blocked) = prune_guards(guards, |g| {
        process_liveness::check_identity(g.pid, g.os_birth_ns)
    });
    lobby
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .set_session_guards(remaining);
    blocked
}

async fn launch_target(
    kind: LaunchKind,
    expected_auto_join_hypixel: bool,
    expected_use_external_overlay: bool,
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

    // D2b: while an unproven abort is on record EVERY preflight re-probes
    // the aborted launch's route. A conversion pushes concrete guards, which
    // the existing guard mechanism below turns into `preexisting_game`.
    {
        let parts = SessionParts {
            coordinator: Arc::clone(&session.coordinator),
            lobby: Arc::clone(&session.lobby),
            progress: Arc::clone(&session.progress),
        };
        let probe_home = home.clone();
        if let Some(message) =
            apply_abort_admission(&parts, |record| route_probe(&parts, &probe_home, record))
        {
            coordinator
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .release();
            return Ok(launch_rejection_msg("launch_cooldown", message));
        }
    }

    if session_guards_block_launch(&session.lobby) {
        coordinator
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .release();
        let guard_home = home.clone();
        let prefs = tauri::async_runtime::spawn_blocking(move || {
            preferences::launch_preferences(&guard_home)
        })
        .await;
        return match prefs {
            Ok(Ok(view)) => Ok(LaunchReply::PreexistingGame { preferences: view }),
            Ok(Err(e)) => Ok(launch_rejection_msg("preference_error", format!("{e:?}"))),
            Err(e) => Ok(launch_rejection_msg("preference_error", e.to_string())),
        };
    }

    let attempt = match tauri::async_runtime::spawn_blocking(move || {
        launch::launch_with_preflight(
            &home,
            kind,
            expected_auto_join_hypixel,
            expected_use_external_overlay,
            generation,
        )
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
                progress.early_hide_fired = false;
                session
                    .lobby
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .reset_for_launch(generation, baseline, matches!(kind, LaunchKind::Lunar));
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
    expected_use_external_overlay: bool,
    session: tauri::State<'_, SessionParts>,
    updater_service: tauri::State<'_, updater::UpdaterService>,
) -> Result<LaunchReply, String> {
    if updater_service.blocks_launch() {
        return Ok(launch_rejection_msg(
            "critical_update_required",
            "Install the required Cobblify update before starting another game.".into(),
        ));
    }
    launch_target(
        LaunchKind::Lunar,
        expected_auto_join_hypixel,
        expected_use_external_overlay,
        session,
    )
    .await
}

#[tauri::command(rename_all = "camelCase")]
async fn launch_forge(
    expected_auto_join_hypixel: bool,
    expected_use_external_overlay: bool,
    session: tauri::State<'_, SessionParts>,
    updater_service: tauri::State<'_, updater::UpdaterService>,
) -> Result<LaunchReply, String> {
    if updater_service.blocks_launch() {
        return Ok(launch_rejection_msg(
            "critical_update_required",
            "Install the required Cobblify update before starting another game.".into(),
        ));
    }
    launch_target(
        LaunchKind::Forge,
        expected_auto_join_hypixel,
        expected_use_external_overlay,
        session,
    )
    .await
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

    // R3: `--uninstall-lunar-integration`. The exit code IS the contract -
    // the NSIS pre-uninstall hook aborts the whole uninstall on anything
    // non-zero - and every refusal has to happen before the first write.

    /// A home that looks exactly like a completed Lunar setup.
    fn installed_lunar_home() -> tempfile::TempDir {
        let home = tempfile::tempdir().unwrap();
        let settings = home.path().join(".lunarclient/settings");
        std::fs::create_dir_all(&settings).unwrap();
        let agent = home.path().join(".weave/Weave-Loader-Agent-1.3.3.jar");
        let args = format!("-Xmx4G -javaagent:{}", agent.display());
        let config = serde_json::json!({"settings": {"jvm-args": args, "jvmArgs": args}});
        std::fs::write(
            settings.join("launcher.json"),
            serde_json::to_string_pretty(&config).unwrap(),
        )
        .unwrap();
        std::fs::create_dir_all(home.path().join(".weave/mods")).unwrap();
        std::fs::write(&agent, b"agent").unwrap();
        std::fs::write(
            home.path().join(".weave/mods/Cobblify-Lunar-0.8.1.jar"),
            b"mod",
        )
        .unwrap();
        home
    }

    fn launcher_json_text(home: &Path) -> String {
        std::fs::read_to_string(home.join(".lunarclient/settings/launcher.json")).unwrap()
    }

    /// Nothing at all was written: the agent is still registered and both
    /// jars are still on disk.
    fn assert_integration_intact(home: &Path) {
        assert!(launcher_json_text(home).contains("-javaagent:"));
        assert!(home.join(".weave/Weave-Loader-Agent-1.3.3.jar").exists());
        assert!(home.join(".weave/mods/Cobblify-Lunar-0.8.1.jar").exists());
    }

    /// Lunar rewrites `launcher.json` when it exits, so stripping the agent
    /// under a running Lunar would be reverted - resurrecting a javaagent
    /// whose jar this uninstall is about to delete, which is a Lunar that
    /// will not start.
    #[test]
    fn uninstall_integration_refuses_while_lunar_runs() {
        let home = installed_lunar_home();
        let code = uninstall_lunar_integration_with(|| Ok(true), || false, home.path());
        assert_eq!(code, 3);
        assert_integration_intact(home.path());
    }

    #[test]
    fn uninstall_integration_refuses_when_undeterminable() {
        let home = installed_lunar_home();
        let code = uninstall_lunar_integration_with(
            || Err("Cannot tell whether Lunar is running.".to_string()),
            || false,
            home.path(),
        );
        assert_eq!(code, 3);
        assert_integration_intact(home.path());
    }

    /// The NSIS template's own running-app check happens AFTER the
    /// pre-uninstall hook, so a launcher still running could re-register the
    /// agent between our write and the uninstaller removing the files.
    #[test]
    fn uninstall_integration_refuses_with_another_launcher() {
        let home = installed_lunar_home();
        let code = uninstall_lunar_integration_with(|| Ok(false), || true, home.path());
        assert_eq!(code, 4);
        assert_integration_intact(home.path());
    }

    #[test]
    fn uninstall_integration_strips_the_agent_and_removes_our_jars() {
        let home = installed_lunar_home();
        std::fs::write(home.path().join(".weave/mods/Other.jar"), b"theirs").unwrap();

        let code = uninstall_lunar_integration_with(|| Ok(false), || false, home.path());
        assert_eq!(code, 0);

        let config = launcher_json_text(home.path());
        assert!(!config.contains("-javaagent:"), "{config}");
        assert!(config.contains("-Xmx4G"), "the user's own flags survive");
        assert!(!home.path().join(".weave/Weave-Loader-Agent-1.3.3.jar").exists());
        assert!(!home.path().join(".weave/mods/Cobblify-Lunar-0.8.1.jar").exists());
        assert!(
            home.path().join(".weave/mods/Other.jar").exists(),
            "another Weave mod is never removed"
        );
    }

    /// L7. The running check must gate the jar INSTALL, not only the
    /// `launcher.json` write. `install_lunar` creates `~/.weave` and drops
    /// two jars into it before `register` ever asks whether Lunar is up, so
    /// a user who ran setup with Lunar open ended with files on disk, no
    /// agent registered, and a "quit Lunar" message - and `~/.weave` created
    /// on a machine whose Lunar setup never completed.
    #[test]
    fn lunar_setup_reports_running_lunar_before_writing_any_jar() {
        fn lunar_home() -> tempfile::TempDir {
            let home = tempfile::tempdir().unwrap();
            let settings = home.path().join(".lunarclient/settings");
            std::fs::create_dir_all(&settings).unwrap();
            std::fs::write(settings.join("launcher.json"), r#"{"settings":{}}"#).unwrap();
            home
        }
        let bundle = tempfile::tempdir().unwrap();
        resources::tests::write_bundle(bundle.path(), b"mod", b"agent", b"forge");
        let res = resources::verify(bundle.path()).unwrap();

        let home = lunar_home();
        let status = set_up_lunar_with(|| Ok(true), &res, home.path());
        assert_eq!(status.state, "blocked");
        assert_eq!(status.issue, Some(issue::RUNNING_LUNAR));
        assert!(
            !home.path().join(".weave").exists(),
            "no jar may be written while Lunar is running"
        );

        // An undeterminable state is an error, and equally writes nothing.
        let home = lunar_home();
        let status = set_up_lunar_with(
            || Err("Cannot tell whether Lunar is running.".to_string()),
            &res,
            home.path(),
        );
        assert_eq!(status.state, "error");
        assert_eq!(status.issue, Some(issue::SETUP_ERROR));
        assert!(!home.path().join(".weave").exists());
    }

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

    fn temp_home() -> PathBuf {
        tempfile::tempdir().unwrap().into_path()
    }

    fn active_with_latch(parts: &SessionParts, generation: u64) {
        let mut c = parts.coordinator.lock().unwrap();
        c.try_launch().unwrap();
        c.launch_active();
        drop(c);
        let mut lobby = parts.lobby.lock().unwrap();
        lobby.reset_for_launch(generation, SystemTime::now(), true);
        lobby.latch_terminal_for_test("game_session_ended");
        drop(lobby);
        parts.progress.lock().unwrap().baseline = Some(SystemTime::now());
    }

    /// L9: `refresh_setup` and `get_launcher` claimed the coordinator and then used `?`
    /// before releasing it. One failing `home()` - or a download page that will not open -
    /// left the lifecycle stuck out of `Idle`, and every later setup, save or launch was
    /// refused as busy until the app was restarted.
    #[test]
    fn a_failing_setup_body_still_releases_the_claim() {
        let coordinator = Mutex::new(Coordinator::default());

        let failed: Result<(), String> =
            with_claim(&coordinator, Coordinator::try_setup, || Err("no home directory".to_string()));

        assert_eq!(failed, Err("no home directory".to_string()));
        assert_eq!(
            coordinator.lock().unwrap().state(),
            lifecycle::Lifecycle::Idle,
            "the claim must be released on the error path"
        );
        assert!(
            coordinator.lock().unwrap().try_setup().is_ok(),
            "the next setup must be admissible"
        );
        coordinator.lock().unwrap().release();

        // And the success path still returns its value and releases.
        assert_eq!(with_claim(&coordinator, Coordinator::try_refresh, || Ok(7)), Ok(7));
        assert_eq!(
            coordinator.lock().unwrap().state(),
            lifecycle::Lifecycle::Idle
        );
    }

    #[test]
    fn reset_when_idle_is_already_reset() {
        let parts = SessionParts::new();
        let reply = reset_session_end_inner(&parts, &temp_home(), ResetMode::Terminal, || {});
        assert!(matches!(reply, ResetReply::AlreadyReset { .. }));
    }

    #[test]
    fn reset_clears_latch_progress_and_publishes_idle_last() {
        let parts = SessionParts::new();
        let home = temp_home();
        active_with_latch(&parts, 1);
        let reply = reset_session_end_inner(&parts, &home, ResetMode::Terminal, || {});
        assert!(matches!(reply, ResetReply::Ok { .. }));
        assert_eq!(parts.lobby.lock().unwrap().terminal_reason(), None);
        assert!(parts.progress.lock().unwrap().baseline.is_none());
        // Fresh launch admissible with a fresh generation.
        let gen = parts.coordinator.lock().unwrap().try_launch().unwrap();
        assert!(gen >= 2);
    }

    #[test]
    fn reset_without_latch_restores_active() {
        let parts = SessionParts::new();
        let home = temp_home();
        {
            let mut c = parts.coordinator.lock().unwrap();
            c.try_launch().unwrap();
            c.launch_active();
        }
        let reply = reset_session_end_inner(&parts, &home, ResetMode::Terminal, || {});
        assert!(matches!(reply, ResetReply::NotTerminal));
        assert_eq!(
            parts.coordinator.lock().unwrap().state(),
            lifecycle::Lifecycle::Active
        );
    }

    #[test]
    fn reset_busy_while_launching() {
        let parts = SessionParts::new();
        parts.coordinator.lock().unwrap().try_launch().unwrap();
        let reply = reset_session_end_inner(&parts, &temp_home(), ResetMode::Terminal, || {});
        assert!(matches!(reply, ResetReply::Busy));
    }

    /// The round-2/round-4 interleaving: after one reset commits, a fresh
    /// launch's state can never be cleared or demoted by another reset.
    #[test]
    fn second_reset_never_touches_a_fresh_launch() {
        let parts = SessionParts::new();
        let home = temp_home();
        active_with_latch(&parts, 1);
        assert!(matches!(
            reset_session_end_inner(&parts, &home, ResetMode::Terminal, || {}),
            ResetReply::Ok { .. }
        ));
        // Fresh launch installs its state and is admitted mid-way.
        let gen = parts.coordinator.lock().unwrap().try_launch().unwrap();
        {
            let mut lobby = parts.lobby.lock().unwrap();
            lobby.reset_for_launch(gen, SystemTime::now(), true);
            parts.progress.lock().unwrap().baseline = Some(SystemTime::now());
        }
        // A reset arriving while Launching is refused outright.
        assert!(matches!(
            reset_session_end_inner(&parts, &home, ResetMode::Terminal, || {}),
            ResetReply::Busy
        ));
        parts.coordinator.lock().unwrap().launch_active();
        // Active with NO latch: refused, still Active, state intact.
        assert!(matches!(
            reset_session_end_inner(&parts, &home, ResetMode::Terminal, || {}),
            ResetReply::NotTerminal
        ));
        assert_eq!(
            parts.coordinator.lock().unwrap().state(),
            lifecycle::Lifecycle::Active
        );
        assert!(parts.lobby.lock().unwrap().has_baseline());
        assert!(parts.progress.lock().unwrap().baseline.is_some());
    }

    /// Round-10 I2: the backend halves of the rehide command. Overlay-on
    /// registers exactly one fresh finite worker; overlay-off completes its
    /// single pass before returning (nothing is scheduled).
    #[test]
    fn rehide_overlay_on_registers_and_off_completes_inline() {
        let _guard = hide_registry::REGISTRY_TEST_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let before = hide_registry::registered_count();
        rehide_inner(true);
        assert_eq!(hide_registry::registered_count(), before + 1);
        assert!(hide_registry::cancel_all_and_wait(std::time::Duration::from_secs(5)));
        // The synchronous branch: only exercised when no real Lunar launcher
        // is running (it would cosmetically hide the user's window).
        #[cfg(target_os = "macos")]
        if proc::lunar_launcher_pid().is_none() {
            rehide_inner(false);
            assert_eq!(hide_registry::registered_count(), 0, "nothing scheduled");
        }
    }

    /// TWO genuinely concurrent resets on the same SessionParts: exactly
    /// one may perform the transaction; the other must observe `busy` or
    /// `already_reset`, and the end state is a clean Idle.
    #[test]
    fn concurrent_resets_serialize_through_the_resetting_state() {
        for _ in 0..25 {
            let parts = SessionParts::new();
            let home = temp_home();
            active_with_latch(&parts, 1);
            let (a, b) = std::thread::scope(|s| {
                let pa = parts.clone();
                let ha = home.clone();
                let ta = s.spawn(move || reset_session_end_inner(&pa, &ha, ResetMode::Terminal, || {}));
                let pb = parts.clone();
                let hb = home.clone();
                let tb = s.spawn(move || reset_session_end_inner(&pb, &hb, ResetMode::Terminal, || {}));
                (ta.join().unwrap(), tb.join().unwrap())
            });
            let oks = [&a, &b]
                .iter()
                .filter(|r| matches!(r, ResetReply::Ok { .. }))
                .count();
            assert_eq!(oks, 1, "exactly one reset performs the transaction");
            assert!(
                [&a, &b].iter().all(|r| matches!(
                    r,
                    ResetReply::Ok { .. } | ResetReply::Busy | ResetReply::AlreadyReset { .. }
                )),
                "the loser is busy or already_reset, never not_terminal"
            );
            assert_eq!(
                parts.coordinator.lock().unwrap().state(),
                lifecycle::Lifecycle::Idle
            );
            assert_eq!(parts.lobby.lock().unwrap().terminal_reason(), None);
        }
    }

    /// DETERMINISTIC interleaving: the test holds the lobby lock so reset
    /// A provably sits INSIDE the transaction (state `Resetting`) when
    /// reset B arrives; B must observe `busy`. After A commits, a fresh
    /// launch is admitted with a fresh generation and a late reset can
    /// never demote or clear it - the PLAN Phase 2 sequence end to end.
    #[test]
    fn gated_reset_interleaving_and_fresh_launch_survival() {
        let parts = SessionParts::new();
        let home = temp_home();
        active_with_latch(&parts, 1);
        // Gate: hold the lobby lock; A will block at transaction step 2.
        let lobby_gate = parts.lobby.lock().unwrap();
        let pa = parts.clone();
        let ha = home.clone();
        let a = std::thread::spawn(move || reset_session_end_inner(&pa, &ha, ResetMode::Terminal, || {}));
        // Wait until A has claimed the exclusive Resetting state.
        while parts.coordinator.lock().unwrap().state() != lifecycle::Lifecycle::Resetting {
            std::thread::sleep(std::time::Duration::from_millis(2));
        }
        // B arrives while A provably holds the transaction: busy.
        assert!(matches!(
            reset_session_end_inner(&parts, &home, ResetMode::Terminal, || {}),
            ResetReply::Busy
        ));
        drop(lobby_gate);
        assert!(matches!(a.join().unwrap(), ResetReply::Ok { .. }));
        assert_eq!(
            parts.coordinator.lock().unwrap().state(),
            lifecycle::Lifecycle::Idle
        );
        // Fresh launch admitted after the commit, with a fresh generation.
        let gen = parts.coordinator.lock().unwrap().try_launch().unwrap();
        assert!(gen >= 2);
        {
            let mut lobby = parts.lobby.lock().unwrap();
            lobby.reset_for_launch(gen, SystemTime::now(), true);
            parts.progress.lock().unwrap().baseline = Some(SystemTime::now());
        }
        // A late reset while Launching is refused and touches nothing.
        assert!(matches!(
            reset_session_end_inner(&parts, &home, ResetMode::Terminal, || {}),
            ResetReply::Busy
        ));
        parts.coordinator.lock().unwrap().launch_active();
        // Active with no latch: refused; the fresh session stays intact.
        assert!(matches!(
            reset_session_end_inner(&parts, &home, ResetMode::Terminal, || {}),
            ResetReply::NotTerminal
        ));
        assert!(parts.lobby.lock().unwrap().has_baseline());
        assert!(parts.progress.lock().unwrap().baseline.is_some());
    }

    /// The Lunar-launcher quit runs exactly for a committed LUNAR session
    /// reset - never for Forge sessions, never on refused resets.
    #[test]
    fn reset_quits_lunar_launcher_only_for_lunar_sessions() {
        use std::cell::Cell;
        let quits = Cell::new(0u32);
        let quit = || quits.set(quits.get() + 1);

        // Lunar session (baseline set, forge_session false): quit invoked.
        let parts = SessionParts::new();
        let home = temp_home();
        active_with_latch(&parts, 1);
        assert!(matches!(
            reset_session_end_inner(&parts, &home, ResetMode::Terminal, quit),
            ResetReply::Ok { .. }
        ));
        assert_eq!(quits.get(), 1);

        // Forge session: no quit.
        let parts = SessionParts::new();
        active_with_latch(&parts, 1);
        parts.progress.lock().unwrap().forge_session = true;
        assert!(matches!(
            reset_session_end_inner(&parts, &home, ResetMode::Terminal, quit),
            ResetReply::Ok { .. }
        ));
        assert_eq!(quits.get(), 1);

        // Refused resets never quit: not_terminal and busy.
        let parts = SessionParts::new();
        {
            let mut c = parts.coordinator.lock().unwrap();
            c.try_launch().unwrap();
            c.launch_active();
        }
        parts.progress.lock().unwrap().baseline = Some(SystemTime::now());
        assert!(matches!(
            reset_session_end_inner(&parts, &home, ResetMode::Terminal, quit),
            ResetReply::NotTerminal
        ));
        assert_eq!(quits.get(), 1);
        let parts = SessionParts::new();
        parts.coordinator.lock().unwrap().try_launch().unwrap();
        assert!(matches!(
            reset_session_end_inner(&parts, &home, ResetMode::Terminal, quit),
            ResetReply::Busy
        ));
        assert_eq!(quits.get(), 1);
    }

    /// DETERMINISTIC quit-overlap exclusion: the quit seam is held open
    /// and, while it runs, the coordinator is provably still `Resetting`,
    /// so a fresh launch cannot be admitted until termination can no
    /// longer act.
    #[test]
    fn no_launch_can_be_admitted_while_the_lunar_quit_runs() {
        use std::sync::mpsc;
        let parts = SessionParts::new();
        let home = temp_home();
        active_with_latch(&parts, 1);
        let (entered_tx, entered_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel::<()>();
        let pa = parts.clone();
        let ha = home.clone();
        let worker = std::thread::spawn(move || {
            reset_session_end_inner(&pa, &ha, ResetMode::Terminal, move || {
                entered_tx.send(()).unwrap();
                release_rx.recv().unwrap();
            })
        });
        entered_rx
            .recv_timeout(std::time::Duration::from_secs(5))
            .expect("quit seam entered");
        // The quit is mid-flight: still Resetting, launch refused.
        assert_eq!(
            parts.coordinator.lock().unwrap().state(),
            lifecycle::Lifecycle::Resetting
        );
        assert!(parts.coordinator.lock().unwrap().try_launch().is_err());
        release_tx.send(()).unwrap();
        assert!(matches!(worker.join().unwrap(), ResetReply::Ok { .. }));
        // Only after the quit finished is a fresh launch admitted.
        assert!(parts.coordinator.lock().unwrap().try_launch().is_ok());
    }

    #[test]
    fn prune_guards_retains_indeterminate_and_blocks() {
        use lobby::WriterProof;
        use process_liveness::IdentityCheck;
        let g = |pid: u32| WriterProof { pid, jvm_start_ms: 1, os_birth_ns: 1 };
        // An OS-refused query is NOT death: guard retained, launch blocked.
        let (remaining, blocked) = prune_guards(vec![g(1), g(2)], |guard| {
            if guard.pid == 1 {
                IdentityCheck::Indeterminate
            } else {
                IdentityCheck::DefinitelyGone
            }
        });
        assert!(blocked);
        assert_eq!(remaining.len(), 1);
        assert_eq!(remaining[0].pid, 1);
        let (remaining, blocked) =
            prune_guards(vec![g(3)], |_| IdentityCheck::AliveSameIdentity);
        assert!(blocked);
        assert_eq!(remaining.len(), 1);
    }

    #[test]
    fn reset_success_always_carries_a_preferences_view() {
        let parts = SessionParts::new();
        // Unreadable preference environment: a home whose .cobblify is a
        // FILE, so the locked read fails - the reply still carries a view.
        let base = tempfile::tempdir().unwrap().into_path();
        let home = base.join("home");
        std::fs::create_dir(&home).unwrap();
        std::fs::write(home.join(".cobblify"), "not a dir").unwrap();
        active_with_latch(&parts, 1);
        match reset_session_end_inner(&parts, &home, ResetMode::Terminal, || {}) {
            ResetReply::Ok { preferences } => {
                assert_eq!(preferences.auto_join_hypixel, true);
                assert_eq!(preferences.use_external_overlay, true);
                assert_eq!(preferences.health, preferences::PreferenceHealth::Invalid);
            }
            other => panic!("expected ok, got {other:?}"),
        }
        match reset_session_end_inner(&parts, &home, ResetMode::Terminal, || {}) {
            ResetReply::AlreadyReset { .. } => {}
            other => panic!("expected already_reset, got {other:?}"),
        }
    }

    #[test]
    fn session_guards_prune_dead_and_block_on_alive_or_indeterminate() {
        use lobby::WriterProof;
        let parts = SessionParts::new();
        let pid = std::process::id();
        let birth = process_liveness::process_birth_ns(pid).expect("self birth");
        // A-live (self) plus B-dead (self with reused-pid birth mismatch).
        parts.lobby.lock().unwrap().set_session_guards(vec![
            WriterProof { pid, jvm_start_ms: 1, os_birth_ns: birth },
            WriterProof { pid, jvm_start_ms: 1, os_birth_ns: birth - 1 },
        ]);
        assert!(session_guards_block_launch(&parts.lobby));
        let remaining = parts.lobby.lock().unwrap().session_guards();
        assert_eq!(remaining.len(), 1, "dead guard pruned, live one kept");
        assert_eq!(remaining[0].os_birth_ns, birth);
        // Both definitively gone: guard set empties and launch unblocks.
        parts.lobby.lock().unwrap().set_session_guards(vec![WriterProof {
            pid: 999_999_999,
            jvm_start_ms: 1,
            os_birth_ns: 1,
        }]);
        assert!(!session_guards_block_launch(&parts.lobby));
        assert!(parts.lobby.lock().unwrap().session_guards().is_empty());
    }

    // ── D2b: the forcing abort ──────────────────────────────────────────

    /// The abort twin of `reset_without_latch_restores_active`: the same
    /// Active-with-no-latch session that the terminal reset refuses is
    /// admitted by the abort, which then publishes Idle.
    #[test]
    fn abort_without_latch_begins_from_active_and_publishes_idle() {
        let parts = SessionParts::new();
        let home = temp_home();
        {
            let mut c = parts.coordinator.lock().unwrap();
            c.try_launch().unwrap();
            c.launch_active();
        }
        parts.progress.lock().unwrap().baseline = Some(SystemTime::now());
        let reply = reset_session_end_inner(&parts, &home, ResetMode::Abort, || {});
        assert!(matches!(reply, ResetReply::Ok { .. }));
        assert_eq!(
            parts.coordinator.lock().unwrap().state(),
            lifecycle::Lifecycle::Idle
        );
        assert!(parts.progress.lock().unwrap().baseline.is_none());
    }

    /// Abort keeps every other admission rule: a launch still mid-flight is
    /// Busy, and an Idle app is already reset.
    #[test]
    fn abort_is_busy_from_launching_and_already_reset_from_idle() {
        let parts = SessionParts::new();
        let home = temp_home();
        parts.coordinator.lock().unwrap().try_launch().unwrap();
        assert!(matches!(
            reset_session_end_inner(&parts, &home, ResetMode::Abort, || {}),
            ResetReply::Busy
        ));
        let idle = SessionParts::new();
        assert!(matches!(
            reset_session_end_inner(&idle, &home, ResetMode::Abort, || {}),
            ResetReply::AlreadyReset { .. }
        ));
    }

    /// The abort runs through the SAME exclusive transaction: a reset that
    /// arrives while an abort provably holds it observes `busy`.
    #[test]
    fn abort_serializes_against_a_concurrent_reset() {
        let parts = SessionParts::new();
        let home = temp_home();
        active_with_latch(&parts, 1);
        let lobby_gate = parts.lobby.lock().unwrap();
        let pa = parts.clone();
        let ha = home.clone();
        let abort =
            std::thread::spawn(move || reset_session_end_inner(&pa, &ha, ResetMode::Abort, || {}));
        while parts.coordinator.lock().unwrap().state() != lifecycle::Lifecycle::Resetting {
            std::thread::sleep(std::time::Duration::from_millis(2));
        }
        assert!(matches!(
            reset_session_end_inner(&parts, &home, ResetMode::Terminal, || {}),
            ResetReply::Busy
        ));
        drop(lobby_gate);
        assert!(matches!(abort.join().unwrap(), ResetReply::Ok { .. }));
        assert_eq!(
            parts.coordinator.lock().unwrap().state(),
            lifecycle::Lifecycle::Idle
        );
    }

    /// The abort quits Lunar for a Lunar session exactly like the terminal
    /// reset does, and never for a Forge one.
    #[test]
    fn abort_quits_lunar_only_for_lunar_sessions() {
        use std::cell::Cell;
        let quits = Cell::new(0u32);
        let quit = || quits.set(quits.get() + 1);
        let home = temp_home();

        let parts = SessionParts::new();
        active_with_latch(&parts, 1);
        assert!(matches!(
            reset_session_end_inner(&parts, &home, ResetMode::Abort, quit),
            ResetReply::Ok { .. }
        ));
        assert_eq!(quits.get(), 1);

        let parts = SessionParts::new();
        active_with_latch(&parts, 1);
        parts.progress.lock().unwrap().forge_session = true;
        assert!(matches!(
            reset_session_end_inner(&parts, &home, ResetMode::Abort, quit),
            ResetReply::Ok { .. }
        ));
        assert_eq!(quits.get(), 1);
    }

    // ── D2b: layered launch admission ───────────────────────────────────

    fn abort_parts(clock: &lobby::ManualClock, forge: bool) -> SessionParts {
        let mut lobby = LobbySession::default();
        lobby.set_clock(clock.clock());
        // No writer and no witness: the "no evidence either way" case.
        lobby.reset_for_launch(1, SystemTime::now(), !forge);
        lobby.clear_for_abort(forge, Path::new("/nonexistent-home-for-this-test"));
        assert!(lobby.unproven_abort().is_some(), "record stored");
        SessionParts::with_lobby(lobby)
    }

    fn live_identity() -> lobby::WriterProof {
        lobby::WriterProof {
            pid: 4242,
            jvm_start_ms: 0,
            os_birth_ns: 99,
        }
    }

    #[test]
    fn abort_admission_probe_outcomes_are_exact() {
        assert_eq!(
            abort_admission(lobby::AdmissionProbe::Clean),
            AbortAdmission::Admit
        );
        assert_eq!(
            abort_admission(lobby::AdmissionProbe::Indeterminate),
            AbortAdmission::Unproven,
            "uncertainty fails closed"
        );
        assert_eq!(
            abort_admission(
                lobby::AdmissionProbe::Live { identities: vec![live_identity()], uncertain: false }
            ),
            AbortAdmission::Guard { identities: vec![live_identity()], keep_record: false }
        );
    }

    #[test]
    fn every_preflight_probes_immediately_without_a_blind_cooldown() {
        let clock = lobby::ManualClock::new();
        let parts = abort_parts(&clock, false);
        let probed = std::cell::Cell::new(0u32);
        let message = apply_abort_admission(&parts, |_| {
            probed.set(probed.get() + 1);
            lobby::AdmissionProbe::Clean
        });
        assert_eq!(message, None, "clean evidence admits immediately");
        assert_eq!(probed.get(), 1, "the route is probed on the first preflight");
        assert!(parts.lobby.lock().unwrap().unproven_abort().is_some());
    }

    /// The binding review condition: a clean probe ADMITS but NEVER clears
    /// the record, so every later preflight probes again.
    #[test]
    fn a_clean_probe_admits_and_keeps_the_record() {
        let clock = lobby::ManualClock::new();
        let parts = abort_parts(&clock, false);
        let probed = std::cell::Cell::new(0u32);
        for _ in 0..4 {
            clock.advance(std::time::Duration::from_secs(5));
            let message = apply_abort_admission(&parts, |_| {
                probed.set(probed.get() + 1);
                lobby::AdmissionProbe::Clean
            });
            assert_eq!(message, None, "a clean probe admits");
            assert!(
                parts.lobby.lock().unwrap().unproven_abort().is_some(),
                "a clean probe is never proof: the record survives"
            );
        }
        assert_eq!(probed.get(), 4, "every preflight probes, not just the first");
    }

    #[test]
    fn an_indeterminate_probe_refuses_and_keeps_the_record() {
        let clock = lobby::ManualClock::new();
        let parts = abort_parts(&clock, false);
        let message = apply_abort_admission(&parts, |_| lobby::AdmissionProbe::Indeterminate);
        assert!(message.is_some(), "fail closed on uncertainty");
        assert!(parts.lobby.lock().unwrap().unproven_abort().is_some());
    }

    /// Conversion: a live post-baseline candidate becomes a concrete guard,
    /// the record is spent, and the existing guard mechanism refuses the
    /// launch until that identity is provably dead.
    #[test]
    fn a_live_candidate_converts_the_record_into_a_session_guard() {
        let clock = lobby::ManualClock::new();
        let parts = abort_parts(&clock, false);
        let pid = std::process::id();
        let birth = process_liveness::process_birth_ns(pid).expect("self birth");
        let identity = lobby::WriterProof {
            pid,
            jvm_start_ms: 0,
            os_birth_ns: birth,
        };
        let message =
            apply_abort_admission(&parts, |_| lobby::AdmissionProbe::Live { identities: vec![identity.clone()], uncertain: false });
        assert_eq!(message, None, "the guard mechanism takes over from here");
        assert!(
            parts.lobby.lock().unwrap().unproven_abort().is_none(),
            "converted: only this clears the record"
        );
        assert!(
            session_guards_block_launch(&parts.lobby),
            "a live identity blocks the launch"
        );
        // Proven dead: the guard prunes itself and home is launchable again.
        parts
            .lobby
            .lock()
            .unwrap()
            .set_session_guards(vec![lobby::WriterProof {
                pid: 999_999_999,
                jvm_start_ms: 0,
                os_birth_ns: 1,
            }]);
        assert!(!session_guards_block_launch(&parts.lobby));
    }

    /// Review fix: a probe that finds a live identity AND coexisting
    /// uncertainty guards the identity but KEEPS the record - a later
    /// unknown identity must still be re-probed at every admission.
    #[test]
    fn a_live_candidate_with_uncertainty_guards_but_keeps_the_record() {
        let clock = lobby::ManualClock::new();
        let parts = abort_parts(&clock, false);
        let pid = std::process::id();
        let birth = process_liveness::process_birth_ns(pid).expect("self birth");
        let identity = lobby::WriterProof {
            pid,
            jvm_start_ms: 0,
            os_birth_ns: birth,
        };
        let message = apply_abort_admission(&parts, |_| lobby::AdmissionProbe::Live {
            identities: vec![identity.clone()],
            uncertain: true,
        });
        assert!(
            message.is_some(),
            "uncertainty at the probe refuses THIS click - the guard alone \
             cannot (the guarded identity could die before the guard check)"
        );
        assert!(
            session_guards_block_launch(&parts.lobby),
            "the live identity is guarded for later clicks too"
        );
        assert!(
            parts.lobby.lock().unwrap().unproven_abort().is_some(),
            "coexisting uncertainty keeps the record"
        );
    }

    /// Forge's route probe is mtime-only: a log touched at or after the
    /// aborted launch's baseline and still recent means a game is booting.
    #[test]
    fn the_forge_activity_probe_reads_only_mtimes() {
        let baseline = SystemTime::now();
        let now = baseline + std::time::Duration::from_secs(5);
        assert!(!fml_log_active(None, baseline, now), "no log: quiet");
        assert!(
            !fml_log_active(
                Some(baseline - std::time::Duration::from_secs(1)),
                baseline,
                now
            ),
            "a pre-baseline log belongs to an older launch"
        );
        assert!(
            fml_log_active(Some(baseline), baseline, now),
            "touched at the baseline, 5 s ago: actively booting"
        );
        assert!(
            !fml_log_active(
                Some(baseline),
                baseline,
                baseline + FML_ACTIVE_WINDOW
            ),
            "15 s of silence is quiet"
        );
    }

    #[test]
    fn the_forge_route_refuses_while_its_log_is_active_and_admits_when_quiet() {
        let clock = lobby::ManualClock::new();
        let parts = abort_parts(&clock, true);
        assert!(
            apply_abort_admission(&parts, |_| lobby::AdmissionProbe::Indeterminate).is_some(),
            "an active fml log refuses"
        );
        assert_eq!(
            apply_abort_admission(&parts, |_| lobby::AdmissionProbe::Clean),
            None,
            "a quiet fml log admits"
        );
        assert!(
            parts.lobby.lock().unwrap().unproven_abort().is_some(),
            "the record survives a quiet log too"
        );
    }

    /// CB-2, DOCUMENTED not prevented: a spawn that begins strictly after a
    /// clean probe still gets a second launch admitted. The evidence checks
    /// narrow the window; they cannot close it. The record surviving
    /// means the NEXT preflight catches the spawn - after the race is lost.
    #[test]
    fn a_spawn_after_a_clean_probe_still_admits_a_second_launch_cb2() {
        let clock = lobby::ManualClock::new();
        let parts = abort_parts(&clock, false);
        // Nothing observable exists at check time: the launch is admitted.
        assert_eq!(
            apply_abort_admission(&parts, |_| lobby::AdmissionProbe::Clean),
            None,
            "documents CB-2: admission cannot see work that has not started"
        );
        // The aborted launch's game only now begins to spawn. Because the
        // record survived, the next preflight does convert it to a guard -
        // but the second launch above already went through.
        let identity = live_identity();
        assert_eq!(
            apply_abort_admission(&parts, |_| lobby::AdmissionProbe::Live { identities: vec![identity.clone()], uncertain: false }),
            None
        );
        assert_eq!(parts.lobby.lock().unwrap().session_guards(), vec![identity]);
        assert!(parts.lobby.lock().unwrap().unproven_abort().is_none());
    }

    // ── D1c: the identity-bound early hide trigger ──────────────────────

    fn grace_poll() -> LobbyPoll {
        LobbyPoll::Unavailable {
            reason: Some("grace".into()),
        }
    }

    #[test]
    fn the_early_hide_trigger_reuses_the_sessions_own_signals() {
        assert!(early_hide_trigger(&grace_poll(), true, false));
        assert!(early_hide_trigger(
            &LobbyPoll::SessionEnded {
                reason: "game_session_ended"
            },
            true,
            false
        ));
        // A live replacement writer exists: no launcher window is coming.
        assert!(!early_hide_trigger(
            &LobbyPoll::SessionEnded {
                reason: "game_session_changed"
            },
            true,
            false
        ));
        // Other unavailable reasons are not an armed absence.
        for reason in ["missing_file", "stale_mtime", "process_unavailable", "malformed"] {
            assert!(!early_hide_trigger(
                &LobbyPoll::Unavailable {
                    reason: Some(reason.into())
                },
                true,
                false
            ));
        }
        assert!(!early_hide_trigger(
            &LobbyPoll::Unavailable { reason: None },
            true,
            false
        ));
        // Forge sessions never trigger it: there is no Lunar launcher.
        assert!(!early_hide_trigger(&grace_poll(), false, false));
    }

    /// Mirrors `lobby_state`'s flag handling: at most one worker per launch
    /// generation, re-armed by the next launch or reset.
    #[test]
    fn the_early_hide_fires_once_per_launch_generation() {
        let mut progress = ProgressState::default();
        let polls = [
            grace_poll(),
            grace_poll(),
            LobbyPoll::SessionEnded {
                reason: "game_session_ended",
            },
        ];
        let mut fired = 0;
        for poll in &polls {
            if early_hide_trigger(poll, true, progress.early_hide_fired) {
                progress.early_hide_fired = true;
                fired += 1;
            }
        }
        assert_eq!(fired, 1, "one worker per generation, not one per poll");
        // A fresh launch (or a reset) re-arms it.
        progress.early_hide_fired = false;
        assert!(early_hide_trigger(&grace_poll(), true, progress.early_hide_fired));
    }

    /// Both lifecycle edges that install a new generation re-arm the flag.
    #[test]
    fn a_reset_rearms_the_early_hide_flag() {
        let parts = SessionParts::new();
        active_with_latch(&parts, 1);
        parts.progress.lock().unwrap().early_hide_fired = true;
        assert!(matches!(
            reset_session_end_inner(&parts, &temp_home(), ResetMode::Terminal, || {}),
            ResetReply::Ok { .. }
        ));
        assert!(!parts.progress.lock().unwrap().early_hide_fired);
    }
}

fn main() {
    // R3: the NSIS pre-uninstall hook runs this exe with one argument and
    // acts on its exit code. It must never reach `tauri::Builder` - there is
    // no window, no event loop and nobody to see a dialog.
    if std::env::args().nth(1).as_deref() == Some("--uninstall-lunar-integration") {
        let code = match home() {
            Ok(home) => uninstall_lunar_integration_with(
                proc::lunar_running_checked,
                proc::another_launcher_running,
                &home,
            ),
            Err(e) => {
                eprintln!("{e}");
                1
            }
        };
        std::process::exit(code);
    }

    tauri::Builder::default()
        .plugin(updater::plugin())
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
            let updater_home = home().map_err(std::io::Error::other)?;
            let updater_service = updater::UpdaterService::new(
                updater_home,
                app.package_info().version.to_string(),
            );
            updater_service.report_completed_install();
            app.manage(updater_service);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            status,
            refresh_setup,
            get_launcher,
            open_launcher,
            open_setup_location,
            launch_preferences,
            update_preferences,
            set_auto_join_hypixel,
            set_use_external_overlay,
            set_auto_update,
            set_update_channel,
            updater::update_status,
            updater::check_for_update,
            updater::start_update,
            updater::pause_update,
            updater::resume_update,
            updater::install_update,
            updater::defer_update,
            lobby_state,
            acknowledge_lobby_snapshot,
            reset_session_end,
            abort_launch_session,
            rehide_after_confirmation,
            quit_app,
            launch_lunar,
            launch_forge,
            launch_progress,
            choose_forge_target
        ])
        .run(tauri::generate_context!())
        .expect("failed to start the Cobblify launcher");
}
