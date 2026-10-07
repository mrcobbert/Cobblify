//! The launch watch: which game JVM this launch started, and whether Cobblify loaded in it.
//! It gathers one poll's facts for `progress::LoadWatch`, which decides the verdict.
//!
//! Privacy (`proc.rs`'s rule): the game JVM is found by its executable path (Lunar: Java
//! under `~/.lunarclient/jre`) or its working directory (Prism: inside the instance
//! folder), and told apart by its OS birth time. Never a command line. Lunar's game
//! version comes only from the fresh Weave log (`progress::weave_wrong_version`).

use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::Value;

use crate::process_liveness::{
    self, jvm_start_matches_os_birth, os_birth_at_or_after_baseline, system_time_to_ns, EpochNs,
    IdentityCheck, ProcessObservation,
};
use crate::progress::{Facts, GameJvm, LoadWatch, LoaderLog, Outcome};

/// While the game JVM is not identified, a full process scan runs at most this often (the
/// progress poll is 600 ms; the verdict's timers are tens of seconds).
const SCAN_EVERY: Duration = Duration::from_millis(1500);

/// A game JVM by pid and OS birth, so a reused pid is never mistaken for it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GameId {
    pub pid: u32,
    pub birth_ns: EpochNs,
}

/// Where this launch's game JVM is looked for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Place {
    /// Java under `<home>/.lunarclient/jre`.
    Lunar(PathBuf),
    /// Java whose working directory is inside this Prism instance folder (none saved: the
    /// game cannot be identified, and no JVM-based verdict is ever taken).
    Prism(Option<PathBuf>),
}

/// One process scan: candidates with their OS birth (None = unknown), and whether "none
/// found" may be trusted.
#[derive(Debug, Clone, Default)]
pub struct Scan {
    pub candidates: Vec<(u32, Option<EpochNs>)>,
    pub complete: bool,
}

/// The world the watch reads. Production is `SystemEnv`; tests inject a clock and processes.
pub trait Env {
    fn now(&self) -> SystemTime;
    fn scan(&mut self, place: &Place) -> Scan;
    fn check(&mut self, game: GameId) -> IdentityCheck;
    fn observe(&mut self, pid: u32) -> Option<ProcessObservation>;
}

pub struct SystemEnv;

impl Env for SystemEnv {
    fn now(&self) -> SystemTime {
        SystemTime::now()
    }

    fn scan(&mut self, place: &Place) -> Scan {
        let scan = match place {
            Place::Lunar(home) => crate::proc::game_jvm_scan(home),
            Place::Prism(Some(instance)) => crate::proc::java_in_dir_scan(instance),
            Place::Prism(None) => return Scan::default(),
        };
        Scan {
            candidates: scan
                .pids
                .into_iter()
                .map(|pid| (pid, process_liveness::process_birth_ns(pid)))
                .collect(),
            complete: scan.complete,
        }
    }

    fn check(&mut self, game: GameId) -> IdentityCheck {
        process_liveness::check_identity(game.pid, game.birth_ns)
    }

    fn observe(&mut self, pid: u32) -> Option<ProcessObservation> {
        process_liveness::observe_process(pid)
    }
}

/// One launch's watch, replaced at every launch and cleared by the session reset.
#[derive(Debug, Default)]
pub struct Watch {
    /// None: no launch is being watched.
    place: Option<Place>,
    /// A Cobblify-made Prism instance's first launch: "Downloading Minecraft" until its
    /// FML log appears (launcher-targets.json `first_launch_pending`, read at the launch).
    pub first_launch_pending: bool,
    game: Option<GameId>,
    /// A game JVM of this launch was identified at some point.
    seen: bool,
    exited: bool,
    last_scan: Option<SystemTime>,
    verdict: LoadWatch,
}

fn ns_to_time(ns: EpochNs) -> SystemTime {
    UNIX_EPOCH + Duration::from_nanos(u64::try_from(ns.max(0)).unwrap_or(u64::MAX))
}

impl Watch {
    pub fn lunar(home: &Path) -> Self {
        Watch {
            place: Some(Place::Lunar(home.to_path_buf())),
            ..Watch::default()
        }
    }

    /// The saved Prism target's watch: its instance folder, and whether this is a made
    /// instance's first launch.
    pub fn prism(home: &Path) -> Self {
        Watch {
            place: Some(Place::Prism(crate::forge::saved_instance_root(home))),
            first_launch_pending: crate::forge::load_target(home)
                .is_some_and(|t| t.first_launch_pending),
            ..Watch::default()
        }
    }

    /// The game JVM this poll. Called BEFORE the loader log is read, so whatever the game
    /// wrote before an exit (Weave's version lines) is on disk when the verdict reads it.
    pub fn observe_game(&mut self, env: &mut dyn Env, baseline: SystemTime, now: SystemTime) -> GameJvm {
        if self.exited {
            return GameJvm::Exited;
        }
        let Some(place) = self.place.clone() else {
            return GameJvm::NotSeen;
        };
        if self.verdict.decided() {
            return GameJvm::NotSeen;
        }
        if let Some(game) = self.game {
            match env.check(game) {
                IdentityCheck::AliveSameIdentity => {
                    return GameJvm::Alive {
                        birth: ns_to_time(game.birth_ns),
                    }
                }
                // A refused query says nothing either way.
                IdentityCheck::Indeterminate => return GameJvm::NotSeen,
                // Gone: look for a replacement right away, which is also the exit proof.
                IdentityCheck::DefinitelyGone => {
                    self.game = None;
                    self.last_scan = None;
                }
            }
        }
        let due = self
            .last_scan
            .is_none_or(|at| now.duration_since(at).unwrap_or(Duration::ZERO) >= SCAN_EVERY);
        if !due {
            return GameJvm::NotSeen;
        }
        self.last_scan = Some(now);
        let Some(baseline_ns) = system_time_to_ns(baseline) else {
            return GameJvm::NotSeen;
        };
        let scan = env.scan(&place);
        let unknown_birth = scan.candidates.iter().any(|(_, birth)| birth.is_none());
        // This launch's game is a JVM born at or after the launch; the earliest one is it.
        let found = scan
            .candidates
            .iter()
            .filter_map(|&(pid, birth)| birth.map(|birth_ns| GameId { pid, birth_ns }))
            .filter(|g| os_birth_at_or_after_baseline(g.birth_ns, baseline_ns))
            .min_by_key(|g| g.birth_ns);
        if let Some(game) = found {
            self.game = Some(game);
            self.seen = true;
            return GameJvm::Alive {
                birth: ns_to_time(game.birth_ns),
            };
        }
        if self.seen && scan.complete && !unknown_birth {
            self.exited = true;
            return GameJvm::Exited;
        }
        GameJvm::NotSeen
    }

    /// Cobblify loaded: a fresh `~/.cobblify/lobby.json` (modified at or after the launch)
    /// whose `jvmPid` / `jvmStartTimeMs` are this launch's game JVM. When no scan identified
    /// the game (an incomplete scan), the writer itself is taken as the game if it was born
    /// after the launch and its JVM start time matches its OS birth - the mod only ever runs
    /// inside the game.
    pub fn loaded(&mut self, env: &mut dyn Env, home: &Path, baseline: SystemTime, now: SystemTime) -> bool {
        if self.place.is_none() || self.verdict.decided() {
            return false;
        }
        let path = home.join(".cobblify/lobby.json");
        let fresh = std::fs::metadata(&path)
            .and_then(|m| m.modified())
            .is_ok_and(|m| m >= baseline);
        if !fresh {
            return false;
        }
        let Some((pid, start_ms)) = std::fs::read_to_string(&path)
            .ok()
            .and_then(|text| serde_json::from_str::<Value>(&text).ok())
            .and_then(|value| crate::lobby::writer_pid_and_start(&value))
        else {
            return false;
        };
        let (Some(now_ns), Some(baseline_ns)) = (system_time_to_ns(now), system_time_to_ns(baseline)) else {
            return false;
        };
        if let Some(game) = self.game {
            return game.pid == pid && jvm_start_matches_os_birth(start_ms, game.birth_ns, now_ns);
        }
        match env.observe(pid) {
            Some(obs)
                if os_birth_at_or_after_baseline(obs.birth_ns, baseline_ns)
                    && jvm_start_matches_os_birth(start_ms, obs.birth_ns, now_ns) =>
            {
                self.game = Some(GameId {
                    pid,
                    birth_ns: obs.birth_ns,
                });
                self.seen = true;
                true
            }
            _ => false,
        }
    }

    /// Cobblify is known to have loaded in this launch's game (latched; see `loaded`).
    pub fn is_loaded(&self) -> bool {
        self.place.is_some() && self.verdict.is_loaded()
    }

    /// The verdict over this poll's facts (see `progress::LoadWatch`). None while no launch
    /// is watched.
    pub fn verdict(
        &mut self,
        forge: bool,
        log: Option<LoaderLog>,
        jvm: GameJvm,
        loaded: bool,
        now: SystemTime,
    ) -> Option<Outcome> {
        self.place.as_ref()?;
        self.verdict.observe(
            &Facts {
                forge,
                log,
                jvm,
                loaded,
            },
            now,
        )
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::collections::HashMap;

    /// A scripted world: a settable clock, the scan's answer, and per-pid identities.
    pub struct FakeEnv {
        pub now: SystemTime,
        pub scan: Scan,
        pub scans: u32,
        /// pid -> (OS birth, alive)
        pub procs: HashMap<u32, (EpochNs, bool)>,
    }

    impl FakeEnv {
        pub fn new(now: SystemTime) -> Self {
            FakeEnv {
                now,
                scan: Scan {
                    candidates: vec![],
                    complete: true,
                },
                scans: 0,
                procs: HashMap::new(),
            }
        }

        /// A live process born at `birth`, listed by the scan.
        pub fn spawn(&mut self, pid: u32, birth: SystemTime) {
            let ns = system_time_to_ns(birth).unwrap();
            self.procs.insert(pid, (ns, true));
            self.scan.candidates.push((pid, Some(ns)));
        }

        pub fn kill(&mut self, pid: u32) {
            if let Some(p) = self.procs.get_mut(&pid) {
                p.1 = false;
            }
            self.scan.candidates.retain(|(p, _)| *p != pid);
        }

        pub fn advance(&mut self, secs: u64) {
            self.now += Duration::from_secs(secs);
        }
    }

    impl Env for FakeEnv {
        fn now(&self) -> SystemTime {
            self.now
        }

        fn scan(&mut self, _place: &Place) -> Scan {
            self.scans += 1;
            self.scan.clone()
        }

        fn check(&mut self, game: GameId) -> IdentityCheck {
            match self.procs.get(&game.pid) {
                Some(&(birth, true)) if birth == game.birth_ns => IdentityCheck::AliveSameIdentity,
                _ => IdentityCheck::DefinitelyGone,
            }
        }

        fn observe(&mut self, pid: u32) -> Option<ProcessObservation> {
            self.procs.get(&pid).map(|&(birth_ns, alive)| ProcessObservation {
                birth_ns,
                liveness: if alive {
                    process_liveness::Liveness::Alive
                } else {
                    process_liveness::Liveness::Dead
                },
            })
        }
    }

    fn game(w: &mut Watch, env: &mut FakeEnv, baseline: SystemTime) -> GameJvm {
        let now = env.now;
        w.observe_game(env, baseline, now)
    }

    fn loaded(w: &mut Watch, env: &mut FakeEnv, home: &Path, baseline: SystemTime) -> bool {
        let now = env.now;
        w.loaded(env, home, baseline, now)
    }

    pub fn t0() -> SystemTime {
        UNIX_EPOCH + Duration::from_secs(1_800_000_000)
    }

    pub fn write_lobby(home: &Path, pid: u32, start: SystemTime) {
        let dir = home.join(".cobblify");
        std::fs::create_dir_all(&dir).unwrap();
        let ms = start.duration_since(UNIX_EPOCH).unwrap().as_millis();
        std::fs::write(
            dir.join("lobby.json"),
            format!("{{\"v\":2,\"jvmPid\":{pid},\"jvmStartTimeMs\":{ms},\"context\":\"MENU\",\"inHypixel\":false}}"),
        )
        .unwrap();
    }

    #[test]
    fn the_earliest_game_born_after_the_launch_is_identified_and_its_exit_proven() {
        let mut env = FakeEnv::new(t0());
        let mut w = Watch::lunar(Path::new("/home"));
        let baseline = t0();
        // An older game (born before the launch) is not this launch's.
        env.spawn(10, t0() - Duration::from_secs(60));
        assert_eq!(game(&mut w, &mut env, baseline), GameJvm::NotSeen);
        env.advance(5);
        env.spawn(11, t0() + Duration::from_secs(4));
        assert_eq!(
            game(&mut w, &mut env, baseline),
            GameJvm::Alive { birth: t0() + Duration::from_secs(4) }
        );
        env.advance(1);
        let scans = env.scans;
        assert!(matches!(game(&mut w, &mut env, baseline), GameJvm::Alive { .. }));
        assert_eq!(env.scans, scans, "a known game is re-checked by identity, not rescanned");
        env.kill(11);
        env.advance(1);
        assert_eq!(game(&mut w, &mut env, baseline), GameJvm::Exited);
    }

    #[test]
    fn an_incomplete_scan_never_proves_an_exit() {
        let mut env = FakeEnv::new(t0());
        let mut w = Watch::lunar(Path::new("/home"));
        env.spawn(11, t0() + Duration::from_secs(1));
        env.advance(2);
        assert!(matches!(game(&mut w, &mut env, t0()), GameJvm::Alive { .. }));
        env.kill(11);
        env.scan.complete = false;
        env.advance(2);
        assert_eq!(game(&mut w, &mut env, t0()), GameJvm::NotSeen);
        env.scan.complete = true;
        env.advance(2);
        assert_eq!(game(&mut w, &mut env, t0()), GameJvm::Exited);
    }

    #[test]
    fn a_game_never_seen_never_exits_and_scans_are_throttled() {
        let mut env = FakeEnv::new(t0());
        let mut w = Watch::prism(Path::new("/nowhere"));
        for _ in 0..10 {
            assert_eq!(game(&mut w, &mut env, t0()), GameJvm::NotSeen);
            env.now += Duration::from_millis(600);
        }
        assert!(env.scans <= 5, "at most one scan per 1.5 s, got {}", env.scans);
    }

    #[test]
    fn lobby_json_from_the_identified_game_is_loaded() {
        let home = tempfile::tempdir().unwrap();
        let mut env = FakeEnv::new(t0());
        let baseline = SystemTime::now() - Duration::from_secs(5);
        env.now = SystemTime::now();
        let birth = baseline + Duration::from_secs(1);
        env.spawn(42, birth);
        let mut w = Watch::lunar(home.path());
        assert!(matches!(game(&mut w, &mut env, baseline), GameJvm::Alive { .. }));
        assert!(!loaded(&mut w, &mut env, home.path(), baseline), "no lobby.json yet");
        // Another process's identity is not this game's.
        write_lobby(home.path(), 43, birth + Duration::from_millis(300));
        assert!(!loaded(&mut w, &mut env, home.path(), baseline));
        write_lobby(home.path(), 42, birth + Duration::from_millis(300));
        assert!(loaded(&mut w, &mut env, home.path(), baseline));
    }

    #[test]
    fn a_stale_lobby_json_is_not_loaded() {
        let home = tempfile::tempdir().unwrap();
        let mut env = FakeEnv::new(SystemTime::now());
        let birth = SystemTime::now() - Duration::from_secs(1);
        env.spawn(42, birth);
        write_lobby(home.path(), 42, birth + Duration::from_millis(300));
        let mut w = Watch::lunar(home.path());
        // The launch is later than the file.
        let baseline = SystemTime::now() + Duration::from_secs(60);
        assert!(!loaded(&mut w, &mut env, home.path(), baseline));
    }

    #[test]
    fn an_unidentified_game_is_taken_from_a_matching_writer() {
        let home = tempfile::tempdir().unwrap();
        let baseline = SystemTime::now() - Duration::from_secs(5);
        let mut env = FakeEnv::new(SystemTime::now());
        let birth = baseline + Duration::from_secs(1);
        env.procs.insert(7, (system_time_to_ns(birth).unwrap(), true));
        let mut w = Watch::prism(home.path());
        write_lobby(home.path(), 7, birth + Duration::from_millis(500));
        assert!(loaded(&mut w, &mut env, home.path(), baseline));
        // A writer born before the launch is an older game, not this launch's.
        let mut older = FakeEnv::new(SystemTime::now());
        let old_birth = baseline - Duration::from_secs(30);
        older.procs.insert(7, (system_time_to_ns(old_birth).unwrap(), true));
        write_lobby(home.path(), 7, old_birth + Duration::from_millis(500));
        let mut w = Watch::prism(home.path());
        assert!(!loaded(&mut w, &mut older, home.path(), baseline));
    }

    #[test]
    fn no_launch_watched_means_no_verdict() {
        let mut w = Watch::default();
        assert_eq!(w.verdict(false, None, GameJvm::Exited, false, t0()), None);
    }
}
