//! The setup engine: one client's setup, started by the player, reported as six checks.
//!
//! `begin_setup` / `resume_setup` claim the lifecycle coordinator (`Lifecycle::Setup`) and
//! run [`run`] on a blocking thread. Every change of state is a [`Snapshot`], stored for
//! `setup_state` and emitted as `setup://progress` (the `updater.rs` pattern).
//!
//! Lunar's six checks (PLAN A):
//!   1. Cobblify's own files - `resources::verify`.
//!   2. Find Lunar - the app bundle (`lunar_detect`).
//!   3. Signed in - Lunar's `accounts.json` (presence only) and its `launcher.json`.
//!      Otherwise STOP `signin` until both exist (polled every second); then bring the
//!      Cobblify window forward and wait a half-second beat.
//!   4. Lunar closed - a Lunar game running is STOP `game_open`; Lunar's launcher running
//!      is STOP `closing_lunar`: a polite quit, then up to 15 s for it to exit, or the
//!      check fails.
//!   5. Old Cobblify files - the stale-jar scan split out of `install_lunar`.
//!   6. Add Cobblify - install the jars and register the agent.
//!
//! Rules that hold everywhere below:
//!   * A FRESH game scan runs immediately before every write (the Lunar quit, the old-jar
//!     removal, the install/register), on a first run and on a resume alike. A running game
//!     is the `game_open` stop; an incomplete scan waits and rescans - it never proceeds.
//!   * The run owns the coordinator from begin to done/failed, stops included, and every
//!     wake-up re-checks that it still does (`holds_setup(gen)`). A run that lost it - a
//!     stale generation - stops at once and writes nothing more.
//!   * Each check is held to `CHECK_MS` from its start (the prototype's pacing). Stops and
//!     the quit take their real time.
//!   * Any error fails the run AT that check; `resume_setup` restarts there. Earlier checks
//!     are not re-run, but the game gate still runs before every write.
//!   * Nothing here reads a process command line or keeps an account value.
//!
//! Prism (`kind: "forge"`) is Phase 2. For now checks 1-2 and 6 reuse the existing
//! adopt/choose path (verify the Forge jar, find the instance, `forge::install` + save the
//! target); checks 3-5 are TODOs that pass.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::Serialize;

use crate::forge::{self, Candidate, Compat, SavedTarget, ValidatedBy};
use crate::install;
use crate::lifecycle::{Coordinator, LifecycleError};
use crate::lunar_config;
use crate::lunar_detect;
use crate::resources::{self, ForgeJar, LunarJars};

pub use crate::lifecycle::SetupKind as Kind;

pub const EVENT_NAME: &str = "setup://progress";
/// The prototype's per-check minimum (`CHECK_MS`, spec 1.4), so the island visibly builds.
pub const CHECK_MS: Duration = Duration::from_millis(900);
/// Polling for the stops: sign-in, game open.
pub const STOP_POLL: Duration = Duration::from_secs(1);
/// How long a politely-quit Lunar may take to exit before the check fails (default 3).
pub const QUIT_DEADLINE: Duration = Duration::from_secs(15);
pub const QUIT_POLL: Duration = Duration::from_millis(250);
/// The beat after Cobblify comes forward at sign-in, before "Closing Lunar".
pub const HANDOFF_BEAT: Duration = Duration::from_millis(500);
/// Sign-in must be seen on this many consecutive polls (a half-written file reads as
/// malformed, but a file Lunar rewrites in two steps should not end the stop early).
const SIGNIN_CONFIRM_POLLS: u32 = 2;
pub const CHECKS: u8 = 6;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Phase {
    Running,
    Stop,
    Failed,
    Done,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Stop {
    Signin,
    GameOpen,
    ClosingLunar,
}

/// The long-check labels. `MakingInstance` and `Java` are Prism's (Phase 2).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
#[allow(dead_code)]
pub enum Label {
    ClosingLunar,
    MakingInstance,
    Java,
}

/// The whole setup state, sent whole every time it changes.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    pub gen: u64,
    pub kind: Kind,
    /// Checks passed, 0..=6. While check k runs (or is stopped), this is k - 1.
    pub check: u8,
    pub phase: Phase,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stop: Option<Stop>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub label: Option<Label>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub failed_at: Option<u8>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

impl Snapshot {
    fn running(gen: u64, kind: Kind, check: u8) -> Self {
        Snapshot {
            gen,
            kind,
            check,
            phase: Phase::Running,
            stop: None,
            label: None,
            failed_at: None,
            message: None,
        }
    }
}

/// One game-process scan. `complete: false` means "no game found" cannot be trusted.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GameScan {
    pub running: bool,
    pub complete: bool,
}

/// Everything the engine asks of the machine apart from files: processes, the window, the
/// clock. Injected so tests drive every stop with fake processes and a fake clock.
pub trait World: Send + Sync {
    /// The installed Lunar app, if any.
    fn lunar_app(&self) -> Option<PathBuf>;
    /// A fresh scan for a game of `kind` (Lunar: a game JVM under `~/.lunarclient/jre`).
    fn game_scan(&self, kind: Kind) -> GameScan;
    /// Whether Lunar's launcher runs; `Err` = cannot tell (treated as a failure, never as
    /// "not running").
    fn lunar_running(&self) -> Result<bool, String>;
    /// Ask Lunar's launcher to quit, politely. Does not wait.
    fn quit_lunar(&self) -> Result<(), String>;
    /// Unminimize, show and focus the Cobblify window, and bounce the Dock icon once.
    fn bring_forward(&self);
    fn prism_installed(&self) -> bool;
    /// Detected Prism instances (only consulted when none was chosen or saved).
    fn forge_candidates(&self) -> Vec<Candidate>;
    /// A monotonic clock.
    fn now(&self) -> Duration;
    fn sleep(&self, d: Duration);
}

/// The files a run works on.
#[derive(Debug, Clone)]
pub struct Paths {
    pub home: PathBuf,
    pub resource_dir: PathBuf,
}

/// A claimed run, ready to go.
pub struct Run {
    pub(crate) gen: u64,
    pub(crate) kind: Kind,
    /// The first check to run, 1..=6.
    pub(crate) from: u8,
    pub(crate) forge: Option<Candidate>,
}

/// How a run ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Done,
    Failed,
    /// The run no longer owned the coordinator; it stopped without publishing.
    Stale,
}

struct Resume {
    kind: Kind,
    failed_at: u8,
    forge: Option<Candidate>,
}

#[derive(Default)]
struct Board {
    latest: Option<Snapshot>,
    resume: Option<Resume>,
}

type Emit = Box<dyn Fn(&Snapshot) + Send + Sync>;

/// Shared between the commands and the running task. Lock order: the coordinator first,
/// then the board - never the reverse.
pub struct Service {
    coordinator: Arc<Mutex<Coordinator>>,
    board: Mutex<Board>,
    emit: Emit,
    check_ms: Duration,
}

pub fn lifecycle_code(e: LifecycleError) -> String {
    match e {
        LifecycleError::Busy => "busy".into(),
        LifecycleError::Active => "active".into(),
        LifecycleError::PreferenceUncertain => "preference_uncertain".into(),
    }
}

impl Service {
    pub fn new(coordinator: Arc<Mutex<Coordinator>>, emit: Emit) -> Self {
        Service {
            coordinator,
            board: Mutex::new(Board::default()),
            emit,
            check_ms: CHECK_MS,
        }
    }

    /// Tests run without the per-check pacing.
    #[cfg(test)]
    pub(crate) fn unpaced(mut self) -> Self {
        self.check_ms = Duration::ZERO;
        self
    }

    /// The current snapshot, or `None` before any setup ran.
    pub fn latest(&self) -> Option<Snapshot> {
        self.board.lock().unwrap_or_else(|e| e.into_inner()).latest.clone()
    }

    /// Claims the coordinator for a fresh run of `kind` from check 1. Refused ("busy",
    /// "active", ...) while anything else - another setup included, stopped or not - holds
    /// it. A fresh begin forgets any earlier failure.
    pub fn begin(&self, kind: Kind, forge: Option<Candidate>) -> Result<Run, String> {
        let mut coordinator = self.coordinator.lock().unwrap_or_else(|e| e.into_inner());
        let gen = coordinator.try_begin_setup(kind).map_err(lifecycle_code)?;
        let mut board = self.board.lock().unwrap_or_else(|e| e.into_inner());
        board.resume = None;
        let snap = Snapshot::running(gen, kind, 0);
        board.latest = Some(snap.clone());
        (self.emit)(&snap);
        Ok(Run {
            gen,
            kind,
            from: 1,
            forge,
        })
    }

    /// Claims the coordinator to run `kind` again from the check it failed at. Only a
    /// failed run of that same kind can be resumed.
    pub fn resume(&self, kind: Kind) -> Result<Run, String> {
        let mut coordinator = self.coordinator.lock().unwrap_or_else(|e| e.into_inner());
        let mut board = self.board.lock().unwrap_or_else(|e| e.into_inner());
        if !board.resume.as_ref().is_some_and(|r| r.kind == kind) {
            return Err("nothing_to_resume".into());
        }
        let gen = coordinator.try_begin_setup(kind).map_err(lifecycle_code)?;
        let resume = board.resume.take().expect("checked above");
        let snap = Snapshot::running(gen, kind, resume.failed_at - 1);
        board.latest = Some(snap.clone());
        (self.emit)(&snap);
        Ok(Run {
            gen,
            kind,
            from: resume.failed_at,
            forge: resume.forge,
        })
    }

    fn holds(&self, gen: u64) -> bool {
        self.coordinator
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .holds_setup(gen)
    }

    /// Stores and emits `snap` if its run still owns the coordinator; an unchanged
    /// snapshot is not re-emitted. `false` = stale, dropped.
    pub(crate) fn publish(&self, snap: Snapshot) -> bool {
        let coordinator = self.coordinator.lock().unwrap_or_else(|e| e.into_inner());
        if !coordinator.holds_setup(snap.gen) {
            return false;
        }
        let mut board = self.board.lock().unwrap_or_else(|e| e.into_inner());
        if board.latest.as_ref() != Some(&snap) {
            board.latest = Some(snap.clone());
            (self.emit)(&snap);
        }
        true
    }

    /// Publishes a terminal snapshot and releases the coordinator to Idle, atomically with
    /// respect to every other claim. A failure remembers where to resume.
    fn finish(&self, snap: Snapshot, resume: Option<Resume>) -> bool {
        let mut coordinator = self.coordinator.lock().unwrap_or_else(|e| e.into_inner());
        if !coordinator.holds_setup(snap.gen) {
            return false;
        }
        let mut board = self.board.lock().unwrap_or_else(|e| e.into_inner());
        board.latest = Some(snap.clone());
        board.resume = resume;
        coordinator.finish_setup(snap.gen);
        (self.emit)(&snap);
        true
    }
}

enum Halt {
    Stale,
    Fail(String),
}

type Step = Result<(), Halt>;

fn fail(message: impl Into<String>) -> Halt {
    Halt::Fail(message.into())
}

/// Runs a claimed setup to done, failed or stale. Blocking: call it off the main thread.
pub fn run(service: &Service, world: &dyn World, paths: &Paths, run: Run) -> Outcome {
    let mut runner = Runner {
        svc: service,
        world,
        paths,
        gen: run.gen,
        kind: run.kind,
        passed: run.from.saturating_sub(1),
        lunar_jars: None,
        forge_jar: None,
        forge: run.forge,
    };
    runner.go(run.from)
}

/// [`run`], but a panic inside it can never leave the coordinator claimed: the run is
/// failed at the check it was on (resumable like any failure) and Idle is published.
pub fn run_guarded(service: &Service, world: &dyn World, paths: &Paths, claimed: Run) -> Outcome {
    let gen = claimed.gen;
    let kind = claimed.kind;
    let forge = claimed.forge.clone();
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        run(service, world, paths, claimed)
    })) {
        Ok(outcome) => outcome,
        Err(_) => {
            let at = service
                .latest()
                .filter(|s| s.gen == gen)
                .map(|s| (s.check + 1).min(CHECKS))
                .unwrap_or(1);
            let snap = Snapshot {
                phase: Phase::Failed,
                failed_at: Some(at),
                message: Some("Setup stopped unexpectedly.".into()),
                ..Snapshot::running(gen, kind, at - 1)
            };
            let resume = Resume {
                kind,
                failed_at: at,
                forge,
            };
            if service.finish(snap, Some(resume)) {
                Outcome::Failed
            } else {
                Outcome::Stale
            }
        }
    }
}

struct Runner<'a> {
    svc: &'a Service,
    world: &'a dyn World,
    paths: &'a Paths,
    gen: u64,
    kind: Kind,
    passed: u8,
    lunar_jars: Option<LunarJars>,
    forge_jar: Option<ForgeJar>,
    forge: Option<Candidate>,
}

impl Runner<'_> {
    fn go(&mut self, from: u8) -> Outcome {
        for k in from.max(1)..=CHECKS {
            self.passed = k - 1;
            if self.show(None, None).is_err() {
                return Outcome::Stale;
            }
            let started = self.world.now();
            let result = match self.kind {
                Kind::Lunar => self.lunar_check(k),
                Kind::Forge => self.forge_check(k),
            };
            match result {
                Ok(()) => {}
                Err(Halt::Stale) => return Outcome::Stale,
                Err(Halt::Fail(message)) => return self.fail_at(k, message),
            }
            let spent = self.world.now().saturating_sub(started);
            if spent < self.svc.check_ms {
                self.world.sleep(self.svc.check_ms - spent);
            }
            if !self.svc.holds(self.gen) {
                return Outcome::Stale;
            }
        }
        self.passed = CHECKS;
        let done = Snapshot {
            phase: Phase::Done,
            ..Snapshot::running(self.gen, self.kind, CHECKS)
        };
        if self.svc.finish(done, None) {
            Outcome::Done
        } else {
            Outcome::Stale
        }
    }

    fn fail_at(&mut self, k: u8, message: String) -> Outcome {
        let snap = Snapshot {
            phase: Phase::Failed,
            failed_at: Some(k),
            message: Some(message),
            ..Snapshot::running(self.gen, self.kind, k - 1)
        };
        let resume = Resume {
            kind: self.kind,
            failed_at: k,
            forge: self.forge.clone(),
        };
        if self.svc.finish(snap, Some(resume)) {
            Outcome::Failed
        } else {
            Outcome::Stale
        }
    }

    fn alive(&self) -> Step {
        if self.svc.holds(self.gen) {
            Ok(())
        } else {
            Err(Halt::Stale)
        }
    }

    fn show(&mut self, stop: Option<Stop>, label: Option<Label>) -> Step {
        let snap = Snapshot {
            phase: if stop.is_some() {
                Phase::Stop
            } else {
                Phase::Running
            },
            stop,
            label,
            ..Snapshot::running(self.gen, self.kind, self.passed)
        };
        if self.svc.publish(snap) {
            Ok(())
        } else {
            Err(Halt::Stale)
        }
    }

    // ── shared gates ────────────────────────────────────────────────────────────

    /// The fresh game gate. Returns once a COMPLETE scan finds no game of this client;
    /// `Ok(true)` if it had to wait. A running game shows the `game_open` stop; an
    /// incomplete scan waits and rescans without one.
    fn game_gate(&mut self) -> Result<bool, Halt> {
        let mut waited = false;
        let mut shown = false;
        loop {
            self.alive()?;
            let scan = self.world.game_scan(self.kind);
            if !scan.running && scan.complete {
                if shown {
                    self.show(None, None)?;
                }
                return Ok(waited);
            }
            if scan.running && !shown {
                self.show(Some(Stop::GameOpen), None)?;
                shown = true;
            }
            waited = true;
            self.world.sleep(STOP_POLL);
        }
    }

    // ── Lunar ───────────────────────────────────────────────────────────────────

    fn lunar_check(&mut self, k: u8) -> Step {
        match k {
            1 => {
                self.lunar_jars = None;
                self.lunar_jars().map(|_| ())
            }
            2 => self
                .world
                .lunar_app()
                .map(|_| ())
                .ok_or_else(|| fail("Lunar Client is not installed.")),
            3 => self.wait_signed_in(),
            4 => {
                self.game_gate()?;
                self.close_lunar()
            }
            5 => self.clear_old_lunar_files(),
            6 => self.add_to_lunar(),
            _ => Ok(()),
        }
    }

    fn lunar_jars(&mut self) -> Result<LunarJars, Halt> {
        if let Some(jars) = &self.lunar_jars {
            return Ok(jars.clone());
        }
        let verified = resources::verify(&self.paths.resource_dir).map_err(fail)?;
        let jars = verified.lunar.map_err(fail)?;
        self.lunar_jars = Some(jars.clone());
        Ok(jars)
    }

    fn lunar_ready_to_set_up(&self) -> bool {
        lunar_detect::signed_in(&self.paths.home) && lunar_detect::opened_once(&self.paths.home)
    }

    /// Check 3. Lunar's first run creates `launcher.json` and sign-in writes
    /// `accounts.json`; setup needs both (the first to register into, the second because
    /// "setup finishes when Launch will work").
    fn wait_signed_in(&mut self) -> Step {
        if self.lunar_ready_to_set_up() {
            return Ok(());
        }
        self.show(Some(Stop::Signin), None)?;
        let mut seen = 0;
        while seen < SIGNIN_CONFIRM_POLLS {
            self.alive()?;
            self.world.sleep(STOP_POLL);
            self.alive()?;
            seen = if self.lunar_ready_to_set_up() { seen + 1 } else { 0 };
        }
        // The hand-off: Lunar is probably frontmost, with its sign-in window.
        self.world.bring_forward();
        self.world.sleep(HANDOFF_BEAT);
        self.alive()?;
        self.show(None, None)
    }

    /// Check 4's quit, and check 6's re-check before writing. Lunar rewrites
    /// `launcher.json` on exit, so it must be gone before the registration is written.
    fn close_lunar(&mut self) -> Step {
        let mut shown = false;
        loop {
            self.alive()?;
            let running = self.world.lunar_running().map_err(fail)?;
            if !running {
                if shown {
                    self.show(None, None)?;
                }
                return Ok(());
            }
            // Fresh game gate immediately before the quit. A game seen here means Lunar
            // must not be quit now; once it closes, everything is re-evaluated.
            if self.game_gate()? {
                shown = false;
                continue;
            }
            self.show(Some(Stop::ClosingLunar), Some(Label::ClosingLunar))?;
            shown = true;
            if let Err(e) = self.world.quit_lunar() {
                // Not delivered - fatal only if Lunar is in fact still there.
                if self.world.lunar_running().map_err(fail)? {
                    return Err(fail(e));
                }
                continue;
            }
            let started = self.world.now();
            loop {
                self.alive()?;
                if !self.world.lunar_running().map_err(fail)? {
                    break;
                }
                if self.world.now().saturating_sub(started) >= QUIT_DEADLINE {
                    return Err(fail("Lunar didn't quit."));
                }
                self.world.sleep(QUIT_POLL);
            }
        }
    }

    /// Check 5: old Cobblify releases in `~/.weave/mods` go; a Cobblify jar we may not
    /// touch fails the check (decision 45). Nothing is removed unless nothing blocks.
    fn clear_old_lunar_files(&mut self) -> Step {
        self.game_gate()?;
        let jars = self.lunar_jars()?;
        let weave = self.paths.home.join(".weave");
        let old = install::scan_old_lunar_jars(&jars, &weave).map_err(fail)?;
        if let Some(first) = old.conflicts.first() {
            return Err(fail(format!(
                "Lunar's mods folder has a Cobblify file Cobblify may not replace: {}.",
                first.file_name().unwrap_or_default().to_string_lossy()
            )));
        }
        let left = install::remove_old_lunar_jars(&old.stale);
        if let Some(first) = left.first() {
            return Err(fail(format!(
                "Cobblify couldn't remove an old copy of itself: {}.",
                first.file_name().unwrap_or_default().to_string_lossy()
            )));
        }
        Ok(())
    }

    /// Check 6: install both jars and register the agent, with Lunar closed and no game.
    fn add_to_lunar(&mut self) -> Step {
        self.close_lunar()?;
        self.game_gate()?;
        let jars = self.lunar_jars()?;
        let installed =
            install::install_lunar(&jars, &self.paths.home.join(".weave")).map_err(fail)?;
        if !installed.conflicts.is_empty() {
            return Err(fail(
                "Cobblify couldn't copy its mod into Lunar's mods folder.",
            ));
        }
        let world = self.world;
        match lunar_config::register_with(
            || world.lunar_running(),
            &lunar_detect::launcher_json(&self.paths.home),
            &installed.agent_path,
        ) {
            Ok(()) => Ok(()),
            Err(lunar_config::RegisterError::LunarRunning) => {
                Err(fail("Lunar opened again before Cobblify finished."))
            }
            Err(lunar_config::RegisterError::Failed(e)) => Err(fail(e)),
        }
    }

    // ── Prism (Phase 1: the existing adopt/choose path) ─────────────────────────

    fn forge_check(&mut self, k: u8) -> Step {
        match k {
            1 => {
                self.forge_jar = None;
                self.forge_jar().map(|_| ())
            }
            2 => {
                if !self.world.prism_installed() {
                    return Err(fail("Prism Launcher is not installed."));
                }
                let target = self.resolve_forge_target()?;
                self.forge = Some(target);
                Ok(())
            }
            // TODO(Phase 2): Prism sign-in - accounts.json (formatVersion 3) with an owning,
            // active, non-Offline account; otherwise the `signin` stop.
            3 => Ok(()),
            // TODO(Phase 2): the chosen instance must be closed (a Java process whose
            // working directory is inside its game folder -> `game_open`), or "Making a
            // 1.8.9 instance" for a made one.
            4 => Ok(()),
            // TODO(Phase 2): split forge::install's stale-release scan into this step (and
            // "Downloading Java 8" for a made instance). Until then check 6's
            // forge::install still clears superseded releases itself.
            5 => Ok(()),
            6 => self.add_to_instance(),
            _ => Ok(()),
        }
    }

    fn forge_jar(&mut self) -> Result<ForgeJar, Halt> {
        if let Some(jar) = &self.forge_jar {
            return Ok(jar.clone());
        }
        let verified = resources::verify(&self.paths.resource_dir).map_err(fail)?;
        let jar = match verified.forge {
            Some(Ok(jar)) => jar,
            Some(Err(e)) => return Err(fail(e)),
            None => return Err(fail("This copy of Cobblify does not include Forge.")),
        };
        self.forge_jar = Some(jar.clone());
        Ok(jar)
    }

    /// The chosen instance (re-validated now), else the saved one, else the only
    /// compatible one detected.
    fn resolve_forge_target(&self) -> Result<Candidate, Halt> {
        if let Some(chosen) = &self.forge {
            let fresh = forge::classify_picked(&chosen.game_dir).map_err(fail)?;
            if fresh.compat != Compat::Confirmed || fresh.marker != chosen.marker {
                return Err(fail("That Prism instance changed since it was listed."));
            }
            return Ok(fresh);
        }
        if let Some(saved) = forge::load_target(&self.paths.home)
            .as_ref()
            .and_then(forge::revalidate)
        {
            return Ok(saved);
        }
        let mut confirmed: Vec<Candidate> = self
            .world
            .forge_candidates()
            .into_iter()
            .filter(|c| c.compat == Compat::Confirmed)
            .collect();
        match confirmed.len() {
            0 => Err(fail("No compatible Prism instance was found.")),
            1 => Ok(confirmed.remove(0)),
            _ => Err(fail("Choose a Prism instance.")),
        }
    }

    fn add_to_instance(&mut self) -> Step {
        // TODO(Phase 2): Prism's game gate is the instance's own game (by working
        // directory); the production World reports none until then.
        self.game_gate()?;
        let jar = self.forge_jar()?;
        let target = self.resolve_forge_target()?;
        let out = forge::install(&jar, &target.game_dir).map_err(fail)?;
        if out.blocked {
            return Err(fail(
                "The instance's mods folder has a Cobblify file Cobblify may not replace.",
            ));
        }
        let saved = SavedTarget {
            game_dir: target.game_dir.display().to_string(),
            validated_by: ValidatedBy::Marker,
            marker: target.marker.clone(),
        };
        forge::save_target(&self.paths.home, &saved).map_err(fail)?;
        self.forge = Some(target);
        Ok(())
    }
}

// ── production World ────────────────────────────────────────────────────────────

/// The real machine. The window hand-off is injected by `main.rs` (it owns the app handle).
pub struct ProdWorld {
    home: PathBuf,
    started: Instant,
    bring_forward: Box<dyn Fn() + Send + Sync>,
}

impl ProdWorld {
    pub fn new(home: PathBuf, bring_forward: Box<dyn Fn() + Send + Sync>) -> Self {
        ProdWorld {
            home,
            started: Instant::now(),
            bring_forward,
        }
    }
}

impl World for ProdWorld {
    fn lunar_app(&self) -> Option<PathBuf> {
        lunar_detect::find_app(&self.home)
    }

    fn game_scan(&self, kind: Kind) -> GameScan {
        match kind {
            Kind::Lunar => {
                let scan = crate::proc::game_jvm_scan(&self.home);
                GameScan {
                    running: !scan.pids.is_empty(),
                    complete: scan.complete,
                }
            }
            // TODO(Phase 2): the chosen instance's game, by working directory.
            Kind::Forge => GameScan {
                running: false,
                complete: true,
            },
        }
    }

    fn lunar_running(&self) -> Result<bool, String> {
        #[cfg(any(target_os = "macos", windows))]
        {
            crate::proc::lunar_running_checked()
        }
        #[cfg(not(any(target_os = "macos", windows)))]
        {
            Ok(false)
        }
    }

    fn quit_lunar(&self) -> Result<(), String> {
        #[cfg(target_os = "macos")]
        {
            crate::hide::request_lunar_quit()
        }
        #[cfg(not(target_os = "macos"))]
        {
            Err("Cobblify can't quit Lunar on this platform. Quit Lunar, then try again.".into())
        }
    }

    fn bring_forward(&self) {
        (self.bring_forward)();
    }

    fn prism_installed(&self) -> bool {
        forge::prism_installed()
    }

    fn forge_candidates(&self) -> Vec<Candidate> {
        forge::Env::from_process()
            .map(|env| forge::detect(&env))
            .unwrap_or_default()
    }

    fn now(&self) -> Duration {
        self.started.elapsed()
    }

    fn sleep(&self, d: Duration) {
        std::thread::sleep(d);
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::lifecycle::Lifecycle;
    use std::fs;
    use std::path::Path;

    // ── fakes ───────────────────────────────────────────────────────────────────

    pub(crate) struct FakeState {
        pub app: bool,
        pub game_running: bool,
        pub scan_complete: bool,
        pub lunar_running: bool,
        /// A delivered quit makes Lunar exit at once.
        pub exits_on_quit: bool,
        pub quit_error: bool,
        pub prism: bool,
        pub candidates: Vec<Candidate>,
        pub now: Duration,
        pub sleeps: u32,
        pub scans: u32,
        pub quits: u32,
        pub forwards: u32,
    }

    impl Default for FakeState {
        fn default() -> Self {
            FakeState {
                app: true,
                game_running: false,
                scan_complete: true,
                lunar_running: false,
                exits_on_quit: true,
                quit_error: false,
                prism: true,
                candidates: Vec::new(),
                now: Duration::ZERO,
                sleeps: 0,
                scans: 0,
                quits: 0,
                forwards: 0,
            }
        }
    }

    type Hook = Box<dyn FnMut(&mut FakeState) + Send>;

    pub(crate) struct Fake {
        pub st: Mutex<FakeState>,
        /// Runs after every sleep, with the clock already advanced: the "world" moving on
        /// while setup waits.
        on_sleep: Mutex<Hook>,
    }

    impl Fake {
        pub(crate) fn new() -> Self {
            Fake {
                st: Mutex::new(FakeState::default()),
                on_sleep: Mutex::new(Box::new(|_| {})),
            }
        }

        pub(crate) fn with(self, f: impl FnOnce(&mut FakeState)) -> Self {
            {
                let mut st = self.st.lock().unwrap();
                f(&mut st);
            }
            self
        }

        pub(crate) fn on_sleep(self, hook: impl FnMut(&mut FakeState) + Send + 'static) -> Self {
            *self.on_sleep.lock().unwrap() = Box::new(hook);
            self
        }

        fn state(&self) -> std::sync::MutexGuard<'_, FakeState> {
            self.st.lock().unwrap()
        }
    }

    impl World for Fake {
        fn lunar_app(&self) -> Option<PathBuf> {
            self.state()
                .app
                .then(|| PathBuf::from("/Applications/Lunar Client.app"))
        }
        fn game_scan(&self, _kind: Kind) -> GameScan {
            let mut st = self.state();
            st.scans += 1;
            GameScan {
                running: st.game_running,
                complete: st.scan_complete,
            }
        }
        fn lunar_running(&self) -> Result<bool, String> {
            Ok(self.state().lunar_running)
        }
        fn quit_lunar(&self) -> Result<(), String> {
            let mut st = self.state();
            st.quits += 1;
            if st.quit_error {
                return Err("not delivered".into());
            }
            if st.exits_on_quit {
                st.lunar_running = false;
            }
            Ok(())
        }
        fn bring_forward(&self) {
            self.state().forwards += 1;
        }
        fn prism_installed(&self) -> bool {
            self.state().prism
        }
        fn forge_candidates(&self) -> Vec<Candidate> {
            self.state().candidates.clone()
        }
        fn now(&self) -> Duration {
            self.state().now
        }
        fn sleep(&self, d: Duration) {
            let mut st = self.state();
            st.now += d;
            st.sleeps += 1;
            let mut hook = self.on_sleep.lock().unwrap();
            (*hook)(&mut st);
        }
    }

    pub(crate) struct Rig {
        pub home: tempfile::TempDir,
        pub bundle: tempfile::TempDir,
        pub coordinator: Arc<Mutex<Coordinator>>,
        pub svc: Arc<Service>,
        pub events: Arc<Mutex<Vec<Snapshot>>>,
    }

    impl Rig {
        pub(crate) fn paths(&self) -> Paths {
            Paths {
                home: self.home.path().to_path_buf(),
                resource_dir: self.bundle.path().to_path_buf(),
            }
        }

        pub(crate) fn events(&self) -> Vec<Snapshot> {
            self.events.lock().unwrap().clone()
        }

        pub(crate) fn run(&self, world: &dyn World, kind: Kind) -> Outcome {
            let r = self.svc.begin(kind, None).expect("admitted");
            run(&self.svc, world, &self.paths(), r)
        }

        pub(crate) fn resume(&self, world: &dyn World, kind: Kind) -> Outcome {
            let r = self.svc.resume(kind).expect("resumable");
            run(&self.svc, world, &self.paths(), r)
        }

        pub(crate) fn launcher_json(&self) -> PathBuf {
            lunar_detect::launcher_json(self.home.path())
        }
    }

    /// A machine with Lunar opened once and signed in, no Cobblify files, and a verified
    /// bundle. Pacing off unless `paced`.
    pub(crate) fn rig_with(paced: bool) -> Rig {
        let home = tempfile::tempdir().unwrap();
        let settings = home.path().join(".lunarclient/settings");
        fs::create_dir_all(&settings).unwrap();
        fs::write(settings.join("launcher.json"), r#"{"settings":{"jvmArgs":"-Xmx4G"}}"#)
            .unwrap();
        crate::lunar_detect::tests::write_accounts(
            home.path(),
            crate::lunar_detect::tests::SIGNED_IN,
        );
        let bundle = tempfile::tempdir().unwrap();
        resources::tests::write_bundle(bundle.path(), b"mod", b"agent", b"forge");
        let coordinator = Arc::new(Mutex::new(Coordinator::default()));
        let events = Arc::new(Mutex::new(Vec::new()));
        let sink = Arc::clone(&events);
        let svc = Service::new(
            Arc::clone(&coordinator),
            Box::new(move |s: &Snapshot| sink.lock().unwrap().push(s.clone())),
        );
        let svc = Arc::new(if paced { svc } else { svc.unpaced() });
        Rig {
            home,
            bundle,
            coordinator,
            svc,
            events,
        }
    }

    pub(crate) fn rig() -> Rig {
        rig_with(false)
    }

    fn sorted_tree(root: &Path) -> Vec<(String, Vec<u8>)> {
        fn walk(dir: &Path, root: &Path, out: &mut Vec<(String, Vec<u8>)>) {
            let Ok(rd) = fs::read_dir(dir) else { return };
            for e in rd {
                let p = e.unwrap().path();
                if p.is_dir() {
                    walk(&p, root, out);
                } else {
                    let rel = p.strip_prefix(root).unwrap().display().to_string();
                    out.push((rel, fs::read(&p).unwrap_or_default()));
                }
            }
        }
        let mut out = Vec::new();
        walk(root, root, &mut out);
        out.sort();
        out
    }

    /// Every file under the home, with its bytes: "nothing written" is equality of two.
    pub(crate) fn home_tree(rig: &Rig) -> Vec<(String, Vec<u8>)> {
        sorted_tree(rig.home.path())
    }

    fn agent_registered(rig: &Rig) -> bool {
        lunar_config::registration_is(
            &rig.launcher_json(),
            &rig.home.path().join(".weave/Weave-Loader-Agent-1.3.3.jar"),
        )
    }

    fn assert_set_up(rig: &Rig) {
        assert!(agent_registered(rig), "the agent is registered");
        assert_eq!(
            fs::read(rig.home.path().join(".weave/mods/Cobblify-Lunar-0.8.1.jar")).unwrap(),
            b"mod"
        );
        assert_eq!(
            fs::read(rig.home.path().join(".weave/Weave-Loader-Agent-1.3.3.jar")).unwrap(),
            b"agent"
        );
    }

    fn stops(events: &[Snapshot]) -> Vec<(u8, Stop)> {
        let mut out: Vec<(u8, Stop)> = events
            .iter()
            .filter_map(|s| s.stop.map(|stop| (s.check, stop)))
            .collect();
        out.dedup();
        out
    }

    // ── the straight run and each check ─────────────────────────────────────────

    #[test]
    fn a_clean_lunar_setup_passes_six_checks_and_registers() {
        let rig = rig();
        let world = Fake::new();
        assert_eq!(rig.run(&world, Kind::Lunar), Outcome::Done);
        assert_set_up(&rig);

        let events = rig.events();
        let checks: Vec<u8> = events.iter().map(|s| s.check).collect();
        assert_eq!(checks, vec![0, 1, 2, 3, 4, 5, 6], "one snapshot per check");
        assert!(events[..6].iter().all(|s| s.phase == Phase::Running));
        assert_eq!(events[6].phase, Phase::Done);
        assert!(events.iter().all(|s| s.gen == events[0].gen && s.kind == Kind::Lunar));
        assert_eq!(rig.svc.latest(), Some(events[6].clone()));
        assert_eq!(rig.coordinator.lock().unwrap().state(), Lifecycle::Idle);
        let st = world.state();
        assert_eq!((st.quits, st.forwards), (0, 0), "no stop, no quit, no hand-off");
    }

    #[test]
    fn the_snapshot_wire_shape_is_camel_case_with_optional_fields_omitted() {
        let snap = Snapshot {
            phase: Phase::Stop,
            stop: Some(Stop::ClosingLunar),
            label: Some(Label::ClosingLunar),
            ..Snapshot::running(3, Kind::Lunar, 3)
        };
        assert_eq!(
            serde_json::to_value(&snap).unwrap(),
            serde_json::json!({"gen": 3, "kind": "lunar", "check": 3, "phase": "stop",
                               "stop": "closing_lunar", "label": "closing_lunar"})
        );
        let failed = Snapshot {
            phase: Phase::Failed,
            failed_at: Some(5),
            message: Some("x".into()),
            ..Snapshot::running(1, Kind::Forge, 4)
        };
        assert_eq!(
            serde_json::to_value(&failed).unwrap(),
            serde_json::json!({"gen": 1, "kind": "forge", "check": 4, "phase": "failed",
                               "failedAt": 5, "message": "x"})
        );
        assert_eq!(serde_json::to_value(Stop::GameOpen).unwrap(), "game_open");
        assert_eq!(serde_json::to_value(Stop::Signin).unwrap(), "signin");
        assert_eq!(serde_json::to_value(Label::MakingInstance).unwrap(), "making_instance");
        assert_eq!(serde_json::to_value(Label::Java).unwrap(), "java");
        assert_eq!(serde_json::to_value(Phase::Done).unwrap(), "done");
    }

    /// Each check holds to CHECK_MS from its start; the fake clock proves the total.
    #[test]
    fn each_check_is_held_to_the_prototype_pacing() {
        let rig = rig_with(true);
        let world = Fake::new();
        assert_eq!(rig.run(&world, Kind::Lunar), Outcome::Done);
        let st = world.state();
        assert_eq!(st.now, CHECK_MS * 6, "six checks, none faster than CHECK_MS");
        assert_eq!(st.sleeps, 6);
    }

    #[test]
    fn check_1_fails_on_a_bad_bundle_and_writes_nothing() {
        let rig = rig();
        fs::write(rig.bundle.path().join("Cobblify-Lunar-0.8.1.jar"), b"tampered").unwrap();
        let before = home_tree(&rig);
        assert_eq!(rig.run(&Fake::new(), Kind::Lunar), Outcome::Failed);
        let last = rig.svc.latest().unwrap();
        assert_eq!((last.phase, last.failed_at, last.check), (Phase::Failed, Some(1), 0));
        assert!(last.message.is_some());
        assert_eq!(home_tree(&rig), before);
        assert_eq!(rig.coordinator.lock().unwrap().state(), Lifecycle::Idle);
    }

    /// Check 2 fails without the app; Try again resumes AT check 2 (check 1 is not re-run)
    /// once Lunar is installed.
    #[test]
    fn check_2_fails_without_lunar_and_resumes_there() {
        let rig = rig();
        let world = Fake::new().with(|st| st.app = false);
        assert_eq!(rig.run(&world, Kind::Lunar), Outcome::Failed);
        assert_eq!(rig.svc.latest().unwrap().failed_at, Some(2));
        assert!(!rig.home.path().join(".weave").exists());

        world.st.lock().unwrap().app = true;
        let before = rig.events().len();
        assert_eq!(rig.resume(&world, Kind::Lunar), Outcome::Done);
        let after: Vec<u8> = rig.events()[before..].iter().map(|s| s.check).collect();
        assert_eq!(after, vec![1, 2, 3, 4, 5, 6], "the resume starts at check 2");
        assert_set_up(&rig);
    }

    // ── stops ───────────────────────────────────────────────────────────────────

    /// Sign-in stop: holds at check 3 until accounts.json shows an account (confirmed on
    /// consecutive polls), then the window hand-off and the beat, then on.
    #[test]
    fn the_signin_stop_waits_for_an_account_then_hands_off() {
        let rig = rig();
        fs::remove_file(rig.home.path().join(".lunarclient/settings/game/accounts.json"))
            .unwrap();
        let home = rig.home.path().to_path_buf();
        let world = Fake::new().on_sleep(move |st| {
            if st.sleeps == 3 {
                crate::lunar_detect::tests::write_accounts(
                    &home,
                    crate::lunar_detect::tests::SIGNED_IN,
                );
            }
        });
        assert_eq!(rig.run(&world, Kind::Lunar), Outcome::Done);
        assert_eq!(stops(&rig.events()), vec![(2, Stop::Signin)]);
        let st = world.state();
        assert_eq!(st.forwards, 1, "Cobblify comes forward once");
        // 3 polls until written, 1 more to confirm, then the beat.
        assert_eq!(st.now, STOP_POLL * 4 + HANDOFF_BEAT);
        assert_set_up(&rig);
    }

    /// A never-opened Lunar (no launcher.json, no account) stops at sign-in too.
    #[test]
    fn a_never_opened_lunar_stops_at_signin() {
        let rig = rig();
        fs::remove_dir_all(rig.home.path().join(".lunarclient")).unwrap();
        let home = rig.home.path().to_path_buf();
        let world = Fake::new().on_sleep(move |st| {
            if st.sleeps == 2 {
                // Lunar's first run creates its settings, then the user signs in.
                let settings = home.join(".lunarclient/settings");
                fs::create_dir_all(&settings).unwrap();
                fs::write(settings.join("launcher.json"), r#"{"settings":{}}"#).unwrap();
                crate::lunar_detect::tests::write_accounts(
                    &home,
                    crate::lunar_detect::tests::SIGNED_IN,
                );
            }
        });
        assert_eq!(rig.run(&world, Kind::Lunar), Outcome::Done);
        assert_eq!(stops(&rig.events()), vec![(2, Stop::Signin)]);
        assert_set_up(&rig);
    }

    /// Game-open stop at check 4, re-checked every second; once the game closes the check
    /// runs again and setup continues. Nothing is written while it waits.
    #[test]
    fn the_game_open_stop_waits_for_the_game_then_continues() {
        let rig = rig();
        let before = home_tree(&rig);
        let snapshot = Arc::new(Mutex::new(None));
        let seen = Arc::clone(&snapshot);
        let home = rig.home.path().to_path_buf();
        let world = Fake::new()
            .with(|st| st.game_running = true)
            .on_sleep(move |st| {
                if st.sleeps == 3 {
                    *seen.lock().unwrap() = Some(sorted_tree(&home));
                    st.game_running = false;
                }
            });
        assert_eq!(rig.run(&world, Kind::Lunar), Outcome::Done);
        assert_eq!(stops(&rig.events()), vec![(3, Stop::GameOpen)]);
        assert_eq!(snapshot.lock().unwrap().clone(), Some(before), "nothing written while open");
        assert_set_up(&rig);
    }

    /// An incomplete scan never proceeds: it waits and rescans, without a stop.
    #[test]
    fn an_incomplete_scan_waits_and_never_proceeds() {
        let rig = rig();
        let before = home_tree(&rig);
        let seen = Arc::new(Mutex::new(None));
        let probe = Arc::clone(&seen);
        let home = rig.home.path().to_path_buf();
        let world = Fake::new()
            .with(|st| st.scan_complete = false)
            .on_sleep(move |st| {
                if st.sleeps == 5 {
                    *probe.lock().unwrap() = Some(sorted_tree(&home));
                    st.scan_complete = true;
                }
            });
        assert_eq!(rig.run(&world, Kind::Lunar), Outcome::Done);
        assert_eq!(seen.lock().unwrap().clone(), Some(before));
        assert!(stops(&rig.events()).is_empty(), "uncertainty is not shown as a game");
        assert_set_up(&rig);
    }

    /// Closing-Lunar stop: a polite quit, then setup goes on once Lunar has exited.
    #[test]
    fn the_closing_lunar_stop_quits_lunar_then_continues() {
        let rig = rig();
        let world = Fake::new().with(|st| {
            st.lunar_running = true;
            st.exits_on_quit = false;
        });
        let world = world.on_sleep(|st| {
            if st.sleeps == 4 {
                st.lunar_running = false; // Lunar finishes quitting a second later
            }
        });
        assert_eq!(rig.run(&world, Kind::Lunar), Outcome::Done);
        let events = rig.events();
        assert_eq!(stops(&events), vec![(3, Stop::ClosingLunar)]);
        let closing = events
            .iter()
            .find(|s| s.stop == Some(Stop::ClosingLunar))
            .unwrap();
        assert_eq!((closing.phase, closing.label), (Phase::Stop, Some(Label::ClosingLunar)));
        let st = world.state();
        assert_eq!(st.quits, 1);
        assert_eq!(st.now, QUIT_POLL * 4);
        assert_set_up(&rig);
    }

    /// The 15 s quit timeout: Lunar never exits, so check 4 fails and nothing is written.
    #[test]
    fn a_lunar_that_will_not_quit_fails_check_4_after_15_seconds() {
        let rig = rig();
        let before = home_tree(&rig);
        let world = Fake::new().with(|st| {
            st.lunar_running = true;
            st.exits_on_quit = false;
        });
        assert_eq!(rig.run(&world, Kind::Lunar), Outcome::Failed);
        let last = rig.svc.latest().unwrap();
        assert_eq!((last.phase, last.failed_at, last.check), (Phase::Failed, Some(4), 3));
        let st = world.state();
        assert_eq!(st.quits, 1, "one polite quit, never a kill");
        assert!(st.now >= QUIT_DEADLINE && st.now < QUIT_DEADLINE + QUIT_POLL, "{:?}", st.now);
        drop(st);
        assert_eq!(home_tree(&rig), before, "nothing written");
        assert_eq!(rig.coordinator.lock().unwrap().state(), Lifecycle::Idle);
    }

    /// A game is never left without its Lunar: a game seen right before the quit is the
    /// game_open stop, and the quit waits for it.
    #[test]
    fn lunar_is_never_quit_while_its_game_runs() {
        let rig = rig();
        let quits_while_open = Arc::new(Mutex::new(Vec::new()));
        let log = Arc::clone(&quits_while_open);
        let world = Fake::new()
            .with(|st| {
                st.lunar_running = true;
                st.game_running = true;
            })
            .on_sleep(move |st| {
                log.lock().unwrap().push(st.quits);
                if st.sleeps == 2 {
                    st.game_running = false;
                }
            });
        assert_eq!(rig.run(&world, Kind::Lunar), Outcome::Done);
        assert_eq!(quits_while_open.lock().unwrap()[..2], [0, 0], "no quit while the game ran");
        assert_eq!(
            stops(&rig.events()),
            vec![(3, Stop::GameOpen), (3, Stop::ClosingLunar)]
        );
        assert_set_up(&rig);
    }

    // ── failure, resume and the fresh gate ──────────────────────────────────────

    /// Round 2 I1's interleaving: check 5 fails, a game starts, Try again -> game_open
    /// stop at check 5 with nothing written; once the game closes, setup finishes.
    #[test]
    fn a_resume_meets_the_fresh_game_gate_before_any_write() {
        let rig = rig();
        let mods = rig.home.path().join(".weave/mods");
        fs::create_dir_all(&mods).unwrap();
        fs::write(mods.join("Cobblify-Lunar-0.8.1-dev.jar"), b"dev").unwrap();
        fs::write(mods.join("Cobblify-Lunar-0.8.0.jar"), b"old").unwrap();

        let world = Fake::new();
        assert_eq!(rig.run(&world, Kind::Lunar), Outcome::Failed);
        let failed = rig.svc.latest().unwrap();
        assert_eq!(failed.failed_at, Some(5));
        assert!(mods.join("Cobblify-Lunar-0.8.0.jar").exists(), "nothing removed on a block");

        // The user clears the dev jar; then a game starts before Try again.
        fs::remove_file(mods.join("Cobblify-Lunar-0.8.1-dev.jar")).unwrap();
        let before = home_tree(&rig);
        let during = Arc::new(Mutex::new(None));
        let probe = Arc::clone(&during);
        let home = rig.home.path().to_path_buf();
        let world = Fake::new()
            .with(|st| st.game_running = true)
            .on_sleep(move |st| {
                if st.sleeps == 2 {
                    *probe.lock().unwrap() = Some(sorted_tree(&home));
                    st.game_running = false;
                }
            });
        let seen = rig.events().len();
        assert_eq!(rig.resume(&world, Kind::Lunar), Outcome::Done);
        let resumed = &rig.events()[seen..];
        assert_eq!(resumed[0].check, 4, "resumes at check 5");
        assert_eq!(stops(resumed), vec![(4, Stop::GameOpen)]);
        assert_eq!(during.lock().unwrap().clone(), Some(before), "nothing written");
        assert!(!mods.join("Cobblify-Lunar-0.8.0.jar").exists(), "removed after the game");
        assert_set_up(&rig);
    }

    /// The gate also runs before check 6's install/register on a resume.
    #[test]
    fn check_6_fails_resumes_and_still_gates_before_writing() {
        let rig = rig();
        fs::write(rig.launcher_json(), "{not json").unwrap();
        let world = Fake::new();
        assert_eq!(rig.run(&world, Kind::Lunar), Outcome::Failed);
        assert_eq!(rig.svc.latest().unwrap().failed_at, Some(6));
        assert!(!agent_registered(&rig));

        fs::write(rig.launcher_json(), r#"{"settings":{}}"#).unwrap();
        let world = Fake::new()
            .with(|st| st.game_running = true)
            .on_sleep(|st| st.game_running = st.sleeps < 2);
        let seen = rig.events().len();
        assert_eq!(rig.resume(&world, Kind::Lunar), Outcome::Done);
        assert_eq!(stops(&rig.events()[seen..]), vec![(5, Stop::GameOpen)]);
        assert_set_up(&rig);
    }

    /// Lunar reopened between check 4 and check 6 is quit again before the write.
    #[test]
    fn check_6_rechecks_lunar_before_writing() {
        let rig = rig();
        let world = Fake::new();
        // Lunar comes up during check 5 (after check 4 passed).
        let run = rig.svc.begin(Kind::Lunar, None).unwrap();
        let mut runner = Runner {
            svc: &rig.svc,
            world: &world,
            paths: &rig.paths(),
            gen: run.gen,
            kind: Kind::Lunar,
            passed: 0,
            lunar_jars: None,
            forge_jar: None,
            forge: None,
        };
        for k in 1..=5 {
            runner.passed = k - 1;
            assert!(runner.lunar_check(k).is_ok());
        }
        world.st.lock().unwrap().lunar_running = true;
        runner.passed = 5;
        assert!(runner.lunar_check(6).is_ok());
        assert_eq!(world.state().quits, 1, "quit again before registering");
        assert_set_up(&rig);
    }

    // ── ownership ───────────────────────────────────────────────────────────────

    /// Round 1 I1: while a setup is STOPPED it still owns the coordinator: another setup
    /// and a launch are refused, and the refusal changes nothing.
    #[test]
    fn a_stopped_setup_refuses_other_setups_and_launches() {
        let rig = rig();
        fs::remove_file(rig.home.path().join(".lunarclient/settings/game/accounts.json"))
            .unwrap();
        let svc = Arc::clone(&rig.svc);
        let coordinator = Arc::clone(&rig.coordinator);
        let home = rig.home.path().to_path_buf();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let log = Arc::clone(&seen);
        let world = Fake::new().on_sleep(move |st| {
            if st.sleeps == 1 {
                let stopped = svc.latest().unwrap();
                assert_eq!(stopped.stop, Some(Stop::Signin));
                log.lock().unwrap().push(svc.begin(Kind::Forge, None).err());
                log.lock().unwrap().push(svc.begin(Kind::Lunar, None).err());
                log.lock().unwrap().push(
                    coordinator.lock().unwrap().try_launch().err().map(lifecycle_code),
                );
                log.lock().unwrap().push(
                    coordinator.lock().unwrap().try_save().err().map(lifecycle_code),
                );
                log.lock().unwrap().push(svc.resume(Kind::Lunar).err());
                crate::lunar_detect::tests::write_accounts(
                    &home,
                    crate::lunar_detect::tests::SIGNED_IN,
                );
            }
        });
        assert_eq!(rig.run(&world, Kind::Lunar), Outcome::Done);
        assert_eq!(
            seen.lock().unwrap().clone(),
            vec![
                Some("busy".to_string()),
                Some("busy".to_string()),
                Some("busy".to_string()),
                Some("busy".to_string()),
                Some("nothing_to_resume".to_string()),
            ]
        );
        assert!(rig.events().iter().all(|s| s.kind == Kind::Lunar), "B never published");
        assert_set_up(&rig);
    }

    /// A wake-up from an earlier generation - after that run failed and a resume took a new
    /// generation - is dropped: it publishes nothing and writes nothing.
    #[test]
    fn a_stale_generation_wake_up_is_ignored() {
        let rig = rig();
        let mods = rig.home.path().join(".weave/mods");
        fs::create_dir_all(&mods).unwrap();
        fs::write(mods.join("Cobblify-Lunar-0.8.1-dev.jar"), b"dev").unwrap();
        fs::write(mods.join("Cobblify-Lunar-0.8.0.jar"), b"old").unwrap();
        assert_eq!(rig.run(&Fake::new(), Kind::Lunar), Outcome::Failed);
        let old_gen = rig.svc.latest().unwrap().gen;
        fs::remove_file(mods.join("Cobblify-Lunar-0.8.1-dev.jar")).unwrap();

        let resumed = rig.svc.resume(Kind::Lunar).unwrap();
        assert!(resumed.gen > old_gen);
        let latest = rig.svc.latest().unwrap();
        let before = home_tree(&rig);
        let events = rig.events().len();

        // The old run wakes up (as if from a sleep) and tries to carry on at check 5.
        let stale = Run {
            gen: old_gen,
            kind: Kind::Lunar,
            from: 5,
            forge: None,
        };
        assert_eq!(run(&rig.svc, &Fake::new(), &rig.paths(), stale), Outcome::Stale);
        assert!(!rig.svc.publish(Snapshot::running(old_gen, Kind::Lunar, 5)));
        assert_eq!(home_tree(&rig), before, "the stale run wrote nothing");
        assert_eq!(rig.events().len(), events, "and published nothing");
        assert_eq!(rig.svc.latest(), Some(latest));

        // The live run is unaffected.
        assert_eq!(run(&rig.svc, &Fake::new(), &rig.paths(), resumed), Outcome::Done);
        assert_set_up(&rig);
    }

    /// Failure releases the claim (launches are admitted again) but remembers failedAt; a
    /// fresh begin forgets it.
    #[test]
    fn failure_releases_to_idle_and_remembers_where_to_resume() {
        let rig = rig();
        let world = Fake::new().with(|st| st.app = false);
        assert_eq!(rig.run(&world, Kind::Lunar), Outcome::Failed);
        assert_eq!(rig.coordinator.lock().unwrap().state(), Lifecycle::Idle);
        assert_eq!(rig.svc.resume(Kind::Forge).err().as_deref(), Some("nothing_to_resume"));
        let gen = rig.coordinator.lock().unwrap().try_launch().unwrap();
        assert!(gen > 0);
        assert_eq!(rig.svc.resume(Kind::Lunar).err().as_deref(), Some("busy"));
        rig.coordinator.lock().unwrap().release();
        // Still resumable after the refused attempt.
        let r = rig.svc.resume(Kind::Lunar).unwrap();
        assert_eq!(r.from, 2);
        rig.coordinator.lock().unwrap().finish_setup(r.gen);
        // A fresh begin forgets the failure.
        let r = rig.svc.begin(Kind::Lunar, None).unwrap();
        rig.coordinator.lock().unwrap().finish_setup(r.gen);
        assert_eq!(rig.svc.resume(Kind::Lunar).err().as_deref(), Some("nothing_to_resume"));
    }

    // ── Prism, Phase 1 ──────────────────────────────────────────────────────────

    const CONFIRMED_PACK: &str = r#"{"components":[{"uid":"net.minecraft","version":"1.8.9"},{"uid":"net.minecraftforge","version":"11.15.1.2318"}]}"#;

    fn prism_instance(root: &Path) -> Candidate {
        let instance = root.join("PrismLauncher/instances/Hypixel");
        fs::create_dir_all(instance.join(".minecraft")).unwrap();
        fs::write(instance.join("mmc-pack.json"), CONFIRMED_PACK).unwrap();
        forge::classify_picked(&instance.join(".minecraft")).unwrap()
    }

    #[test]
    fn a_forge_setup_adopts_the_only_compatible_instance() {
        let rig = rig();
        let candidate = prism_instance(rig.home.path());
        let world = Fake::new().with(|st| st.candidates = vec![candidate.clone()]);
        assert_eq!(rig.run(&world, Kind::Forge), Outcome::Done);
        assert_eq!(
            fs::read(candidate.game_dir.join("mods/Cobblify-1.8.9-forge-0.8.1.jar")).unwrap(),
            b"forge"
        );
        let saved = forge::load_target(rig.home.path()).expect("target saved");
        assert_eq!(PathBuf::from(saved.game_dir), candidate.game_dir);
        assert!(!rig.home.path().join(".weave").exists(), "Lunar untouched");
    }

    #[test]
    fn a_forge_setup_without_prism_or_a_unique_instance_fails_at_check_2() {
        let rig = rig();
        let world = Fake::new().with(|st| st.prism = false);
        assert_eq!(rig.run(&world, Kind::Forge), Outcome::Failed);
        assert_eq!(rig.svc.latest().unwrap().failed_at, Some(2));

        let candidate = prism_instance(rig.home.path());
        let mut second = candidate.clone();
        second.id = "d1".into();
        let world = Fake::new().with(|st| st.candidates = vec![candidate.clone(), second]);
        assert_eq!(rig.run(&world, Kind::Forge), Outcome::Failed);
        assert_eq!(rig.svc.latest().unwrap().failed_at, Some(2));

        // Choosing one resolves it.
        let r = rig.svc.begin(Kind::Forge, Some(candidate.clone())).unwrap();
        assert_eq!(run(&rig.svc, &world, &rig.paths(), r), Outcome::Done);
        assert!(forge::load_target(rig.home.path()).is_some());
    }
}
