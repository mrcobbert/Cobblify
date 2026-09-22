//! Typed lobby polling, writer binding, and preflight.

use std::fs;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{Instant, SystemTime};
/// Only the injected-clock helpers and their tests move time by hand.
#[cfg(test)]
use std::time::Duration;

use serde::Serialize;
use serde_json::Value;

use crate::process_liveness::{
    self, bound_writer_presence, jvm_start_matches_os_birth, observe_process, os_birth_at_or_after_baseline,
    system_time_to_ns, BoundPresence, EpochNs, IdentityCheck, Liveness, ProcessObservation,
};

const LIVE_CONTEXTS: &[&str] = &["LOBBY", "QUEUE", "GAME"];
const PLAYER_STATES: &[&str] = &["OK", "NICKED", "NEVER_PLAYED", "ERROR", "LOADING"];
/// Standing in the current game; absent on older mod jars (= ACTIVE). Any other value is malformed.
const PLAYER_PRESENCE: &[&str] = &["ACTIVE", "DISCONNECTED", "ELIMINATED", "MISSING"];
/// Contract versions this launcher admits: v1 (older mod jars; rows are not drawn) and v2.
const CONTRACT_VERSIONS: &[u64] = &[1, 2];

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum LobbyPoll {
    Snapshot {
        snapshot: Value,
        token: u32,
    },
    Unavailable {
        #[serde(skip_serializing_if = "Option::is_none")]
        reason: Option<String>,
    },
    SessionEnded {
        reason: &'static str,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WriterProof {
    pub pid: u32,
    pub jvm_start_ms: i64,
    pub os_birth_ns: EpochNs,
}

#[derive(Debug, Clone)]
struct PendingProof {
    generation: u64,
    classification: SnapshotClass,
    writer: WriterProof,
    token: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SnapshotClass {
    Live,
    NonLive,
}

/// Monotonic clock for the absence machine, the pre-bind scan throttle, and
/// the unproven-abort record. Production reads the real clock; tests drive
/// `Manual` deterministically, so no schedule in this file needs a sleep.
#[derive(Clone)]
pub enum Clock {
    System,
    Manual(Arc<Mutex<Instant>>),
}

impl Default for Clock {
    fn default() -> Self {
        Clock::System
    }
}

impl Clock {
    pub fn now(&self) -> Instant {
        match self {
            Clock::System => Instant::now(),
            Clock::Manual(at) => *at.lock().unwrap_or_else(|e| e.into_inner()),
        }
    }
}

/// Test handle for a `Clock::Manual`: hand `clock()` to the session and move
/// time with `advance`.
#[cfg(test)]
pub struct ManualClock(Arc<Mutex<Instant>>);

#[cfg(test)]
impl ManualClock {
    pub fn new() -> Self {
        ManualClock(Arc::new(Mutex::new(Instant::now())))
    }

    pub fn clock(&self) -> Clock {
        Clock::Manual(Arc::clone(&self.0))
    }

    pub fn advance(&self, by: Duration) {
        let mut at = self.0.lock().unwrap_or_else(|e| e.into_inner());
        *at += by;
    }
}

/// One authoritative observation of this session's game process.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Observation {
    Present,
    Absent,
    Indeterminate,
}

/// D4d. The single absence machine, shared by the bound-writer path and the
/// pre-bind witness path. It latches only on a proven CONSECUTIVE absence
/// that has also held for the grace window: the second consecutive Absent
/// starts the clock, and the latch needs a further Absent at or after the
/// grace. An Indeterminate observation can neither advance a streak nor
/// preserve one, so nothing a refused query touched can ever satisfy the
/// consecutive proof.
#[derive(Default, Debug)]
struct AbsenceMachine {
    streak: u8,
    armed_at: Option<Instant>,
}

impl AbsenceMachine {
    fn clear(&mut self) {
        self.streak = 0;
        self.armed_at = None;
    }

    /// Feeds one observation; true means latch now.
    fn observe(&mut self, observation: Observation, now: Instant) -> bool {
        match observation {
            Observation::Present | Observation::Indeterminate => {
                self.clear();
                false
            }
            Observation::Absent => {
                self.streak = self.streak.saturating_add(1);
                if self.streak == 2 {
                    self.armed_at = Some(now);
                }
                self.armed_at.is_some_and(|at| {
                    now.saturating_duration_since(at).as_millis() >= ABSENCE_GRACE_MS
                })
            }
        }
    }
}

/// Whether a structured scan answered completely. `Uncertain` means the
/// enumeration itself failed or was partial - never "nothing is running".
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScanOutcome {
    Complete,
    Uncertain,
}

/// One game JVM seen by a scan. `birth: None` is a candidate whose identity
/// could not be established: it is KEPT, because dropping it would read as
/// the absence of a replacement.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GameCandidate {
    pub pid: u32,
    pub birth: Option<EpochNs>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Scan {
    pub outcome: ScanOutcome,
    pub candidates: Vec<GameCandidate>,
}

/// Discovery seam for the pre-bind witness and the launch-admission probe.
pub trait GameProbe: Send {
    fn scan(&mut self, home: &Path) -> Scan;
    fn check(&mut self, pid: u32, birth: EpochNs) -> IdentityCheck;
}

/// Production discovery: the exe-identified game JVMs plus each one's OS
/// birth. Privacy contract unchanged - `proc` never requests argv.
pub struct SystemGameProbe;

impl GameProbe for SystemGameProbe {
    fn scan(&mut self, home: &Path) -> Scan {
        let scan = crate::proc::game_jvm_scan(home);
        Scan {
            // A snapshot that failed its self-inclusion completeness proof
            // may have missed a live JVM: it can witness, never prove
            // absence (see `GameJvmScan::complete`).
            outcome: if scan.complete {
                ScanOutcome::Complete
            } else {
                ScanOutcome::Uncertain
            },
            candidates: scan
                .pids
                .into_iter()
                .map(|pid| GameCandidate {
                    pid,
                    birth: process_liveness::process_birth_ns(pid),
                })
                .collect(),
        }
    }

    fn check(&mut self, pid: u32, birth: EpochNs) -> IdentityCheck {
        process_liveness::check_identity(pid, birth)
    }
}

/// A post-baseline game JVM witnessed before any writer bound, by the same
/// pid + OS-birth identity the writer proofs use.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Witness {
    pid: u32,
    birth: EpochNs,
}

/// Aggregate liveness of the witness set.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum MemberStatus {
    Empty,
    AnyAlive,
    AnyIndeterminate,
    AllGone,
}

/// Point-in-time answer for launch admission (D2b layer 2). It never
/// latches anything and never touches the witness set. Live findings and
/// uncertainty are NOT mutually exclusive: a scan can surface a guardable
/// identity AND fail to account for another (unknown birth, uncertain
/// enumeration, indeterminate check) - the consumer must honor both, never
/// let the guard erase the uncertainty (review finding).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AdmissionProbe {
    Live {
        identities: Vec<WriterProof>,
        uncertain: bool,
    },
    Indeterminate,
    Clean,
}

/// A forced abort that produced NO evidence either way: no bound writer and
/// no witnessed game JVM. While this is on record every launch preflight
/// re-probes the aborted launch's route. A clean point-in-time probe never
/// clears it - a clean probe is not proof that the aborted launch produced
/// nothing (contract boundary CB-2).
#[derive(Debug, Clone)]
pub struct UnprovenAbort {
    pub baseline: SystemTime,
    pub at: Instant,
    pub forge: bool,
}

pub struct LobbySession {
    generation: u64,
    baseline: Option<SystemTime>,
    bound_writer: Option<WriterProof>,
    live_seen: bool,
    pending: Option<PendingProof>,
    absence: AbsenceMachine,
    next_token: u32,
    /// Latched terminal reason — later polls cannot escape session-ended/changed.
    terminal_reason: Option<&'static str>,
    /// The verified-alive writer that displaced the bound one, captured at
    /// the `game_session_changed` latch. Its death was never proven.
    replacement_writer: Option<WriterProof>,
    /// Writers known live (or unproven-dead) when a session reset released
    /// the lifecycle. Launch preflight rejects until every entry is
    /// definitively gone; entries are pruned by identity-aware liveness.
    session_guards: Vec<WriterProof>,
    /// D2a runs on Lunar sessions only: Prism has no safe game-JVM identity.
    lunar_session: bool,
    /// Every post-baseline game JVM seen while no writer was bound.
    witness: Vec<Witness>,
    last_scan_at: Option<Instant>,
    unproven_abort: Option<UnprovenAbort>,
    clock: Clock,
    probe: Box<dyn GameProbe>,
}

impl Default for LobbySession {
    fn default() -> Self {
        LobbySession {
            generation: 0,
            baseline: None,
            bound_writer: None,
            live_seen: false,
            pending: None,
            absence: AbsenceMachine::default(),
            next_token: 0,
            terminal_reason: None,
            replacement_writer: None,
            session_guards: Vec::new(),
            lunar_session: false,
            witness: Vec::new(),
            last_scan_at: None,
            unproven_abort: None,
            clock: Clock::System,
            probe: Box::new(SystemGameProbe),
        }
    }
}

const ABSENCE_GRACE_MS: u128 = 1200;
/// Pre-bind full-scan throttle: every other 250 ms frontend poll. A
/// post-baseline game JVM alive for >=1.5 s therefore spans three scheduled
/// scan opportunities (contract boundary CB-1).
const PRE_BIND_SCAN_MS: u128 = 500;

impl LobbySession {
    pub fn reset_for_launch(&mut self, generation: u64, baseline: SystemTime, lunar: bool) {
        self.generation = generation;
        self.baseline = Some(baseline);
        self.bound_writer = None;
        self.live_seen = false;
        self.pending = None;
        self.absence.clear();
        self.terminal_reason = None;
        self.replacement_writer = None;
        self.lunar_session = lunar;
        self.witness.clear();
        self.last_scan_at = None;
        // A successful launch means preflight already pruned or rejected.
        // The unproven-abort record deliberately SURVIVES: it is cleared
        // only by conversion to a concrete guard, or by process restart.
        self.session_guards.clear();
    }

    pub fn clear_baseline(&mut self) {
        self.baseline = None;
    }

    pub fn terminal_reason(&self) -> Option<&'static str> {
        self.terminal_reason
    }

    pub fn now(&self) -> Instant {
        self.clock.now()
    }

    /// Everything a reset or abort clears, guards and abort record aside.
    fn clear_state(&mut self) {
        self.baseline = None;
        self.bound_writer = None;
        self.live_seen = false;
        self.pending = None;
        self.absence.clear();
        self.terminal_reason = None;
        self.replacement_writer = None;
        self.lunar_session = false;
        self.witness.clear();
        self.last_scan_at = None;
    }

    /// Full clear for the session-reset transaction. For a changed session,
    /// the old bound writer's death was never proven and the replacement was
    /// verified live, so BOTH become launch guards; a plain ended session
    /// proved its only writer definitively absent, so no guard remains.
    pub fn clear_for_reset(&mut self) {
        let mut guards = Vec::new();
        if self.terminal_reason == Some("game_session_changed") {
            if let Some(bound) = self.bound_writer.take() {
                guards.push(bound);
            }
            if let Some(replacement) = self.replacement_writer.take() {
                if !guards.iter().any(|g| writers_match(g, &replacement)) {
                    guards.push(replacement);
                }
            }
        }
        self.session_guards = guards;
        self.clear_state();
    }

    /// D2b clear for a FORCED abort, where no terminal was ever proven.
    /// Evidence rules: a bound (or replacement) writer and any witnessed
    /// identity not PROVEN dead become launch guards (an Indeterminate must
    /// never read as gone - same conservatism as guard pruning); a
    /// same-time scan catches a replacement that started after the last
    /// witness pass; only a session whose every observed identity is
    /// definitely gone under a Complete scan leaves nothing at all; and a
    /// session with no observation in either direction records an unproven
    /// abort so admission stays layered.
    pub fn clear_for_abort(&mut self, forge: bool, home: &Path) {
        // No baseline means nothing observable could predate this moment.
        let baseline = self.baseline.unwrap_or_else(SystemTime::now);
        let mut guards = Vec::new();
        let mut unproven = false;
        if let Some(bound) = self.bound_writer.clone() {
            guards.push(bound);
        }
        if let Some(replacement) = self.replacement_writer.clone() {
            if !guards.iter().any(|g| writers_match(g, &replacement)) {
                guards.push(replacement);
            }
        }
        for member in self.witness.clone() {
            if self.probe.check(member.pid, member.birth) != IdentityCheck::DefinitelyGone {
                push_unique_guard(&mut guards, witness_proof(member));
            }
        }
        if forge {
            // No probe can see a Prism game: without a writer there is no
            // proof in either direction.
            unproven = guards.is_empty();
        } else {
            // Same-time scan: a replacement that started after the last
            // 500ms witness pass must be guarded HERE, not discovered after
            // the next launch was already admitted; and an uncertain scan
            // can never prove the field clear.
            match self.probe_post_baseline_games(home, baseline) {
                AdmissionProbe::Live {
                    identities,
                    uncertain,
                } => {
                    for proof in identities {
                        push_unique_guard(&mut guards, proof);
                    }
                    // A guardable identity does not erase coexisting
                    // uncertainty about a DIFFERENT one.
                    unproven |= uncertain;
                }
                AdmissionProbe::Indeterminate => unproven = true,
                AdmissionProbe::Clean => {}
            }
            // Dispatched but never observed at all: the game may still
            // spawn later (CB-2's layered-admission territory).
            if self.witness.is_empty() && guards.is_empty() {
                unproven = true;
            }
        }
        self.unproven_abort = if unproven {
            Some(UnprovenAbort {
                baseline,
                at: self.clock.now(),
                forge,
            })
        } else {
            None
        };
        self.session_guards = guards;
        self.clear_state();
    }

    pub fn unproven_abort(&self) -> Option<UnprovenAbort> {
        self.unproven_abort.clone()
    }

    pub fn clear_unproven_abort(&mut self) {
        self.unproven_abort = None;
    }

    pub fn session_guards(&self) -> Vec<WriterProof> {
        self.session_guards.clone()
    }

    pub fn set_session_guards(&mut self, guards: Vec<WriterProof>) {
        self.session_guards = guards;
    }

    /// Adds guards without disturbing the ones already held.
    pub fn add_session_guards(&mut self, extra: Vec<WriterProof>) {
        for proof in extra {
            if !self
                .session_guards
                .iter()
                .any(|g| g.pid == proof.pid && g.os_birth_ns == proof.os_birth_ns)
            {
                self.session_guards.push(proof);
            }
        }
    }

    /// D2b layer 2 discovery: is there a live post-baseline game JVM, and
    /// could there be one this probe cannot see? Fails closed on either an
    /// uncertain scan or a candidate whose birth cannot be read.
    pub fn probe_post_baseline_games(
        &mut self,
        home: &Path,
        baseline: SystemTime,
    ) -> AdmissionProbe {
        let Some(baseline_ns) = system_time_to_ns(baseline) else {
            return AdmissionProbe::Indeterminate;
        };
        let scan = self.probe.scan(home);
        let mut unknown = scan.outcome == ScanOutcome::Uncertain
            || scan.candidates.iter().any(|c| c.birth.is_none());
        let mut live = Vec::new();
        for candidate in &scan.candidates {
            let Some(birth) = candidate.birth else {
                continue;
            };
            if !os_birth_at_or_after_baseline(birth, baseline_ns) {
                continue;
            }
            match self.probe.check(candidate.pid, birth) {
                IdentityCheck::AliveSameIdentity => live.push(witness_proof(Witness {
                    pid: candidate.pid,
                    birth,
                })),
                IdentityCheck::Indeterminate => unknown = true,
                IdentityCheck::DefinitelyGone => {}
            }
        }
        if !live.is_empty() {
            AdmissionProbe::Live {
                identities: live,
                uncertain: unknown,
            }
        } else if unknown {
            AdmissionProbe::Indeterminate
        } else {
            AdmissionProbe::Clean
        }
    }

    #[cfg(test)]
    pub fn set_clock(&mut self, clock: Clock) {
        self.clock = clock;
    }

    #[cfg(test)]
    pub fn set_probe(&mut self, probe: Box<dyn GameProbe>) {
        self.probe = probe;
    }

    #[cfg(test)]
    pub fn latch_terminal_for_test(&mut self, reason: &'static str) {
        self.terminal_reason = Some(reason);
    }

    #[cfg(test)]
    pub fn has_baseline(&self) -> bool {
        self.baseline.is_some()
    }

    #[cfg(test)]
    fn witness_len(&self) -> usize {
        self.witness.len()
    }

    fn latch_terminal(&mut self, reason: &'static str) -> LobbyPoll {
        self.terminal_reason = Some(reason);
        self.pending = None;
        LobbyPoll::SessionEnded { reason }
    }

    pub fn poll(&mut self, home: &Path, now: SystemTime) -> LobbyPoll {
        if let Some(reason) = self.terminal_reason {
            return LobbyPoll::SessionEnded { reason };
        }
        let Some(baseline) = self.baseline else {
            return LobbyPoll::Unavailable { reason: None };
        };
        let baseline_ns = match system_time_to_ns(baseline) {
            Some(v) => v,
            None => return LobbyPoll::Unavailable { reason: None },
        };
        // D2a: while nothing is bound, a Lunar session's only identity is
        // the game JVM itself. The scan runs FIRST in this poll, so no
        // absence decision below is ever taken from stale discovery.
        if self.bound_writer.is_none() && self.lunar_session {
            if let Some(poll) = self.poll_pre_bind_witness(home, baseline_ns) {
                return poll;
            }
        }
        let path = home.join(".cobblify/lobby.json");
        let meta = match fs::metadata(&path) {
            Ok(m) => m,
            Err(_) => {
                if let Some(bound) = self.bound_writer.clone() {
                    return self.poll_bound_presence(&bound, Some("missing_file"));
                }
                return LobbyPoll::Unavailable {
                    reason: Some("missing_file".into()),
                };
            }
        };
        let mtime = match meta.modified() {
            Ok(m) => m,
            Err(_) => return LobbyPoll::Unavailable { reason: None },
        };
        if mtime < baseline {
            return LobbyPoll::Unavailable {
                reason: Some("stale_mtime".into()),
            };
        }
        let text = match fs::read_to_string(&path) {
            Ok(t) => t,
            Err(_) => return LobbyPoll::Unavailable { reason: None },
        };
        let value: Value = match serde_json::from_str(&text) {
            Ok(v) => v,
            Err(_) => {
                return LobbyPoll::Unavailable {
                    reason: Some("malformed".into()),
                };
            }
        };
        let Some(fields) = parse_writer_fields(&value) else {
            return LobbyPoll::Unavailable {
                reason: Some("no_identity".into()),
            };
        };
        let Some(ProcessObservation { birth_ns: os_birth_ns, liveness }) =
            observe_process(fields.pid)
        else {
            if let Some(bound) = self.bound_writer.clone() {
                if bound.pid == fields.pid {
                    return self.poll_bound_presence(&bound, Some("process_unavailable"));
                }
            }
            return LobbyPoll::Unavailable {
                reason: Some("process_unavailable".into()),
            };
        };
        let writer = WriterProof {
            pid: fields.pid,
            jvm_start_ms: fields.jvm_start_ms,
            os_birth_ns,
        };
        let now_ns = system_time_to_ns(now).unwrap_or(0);
        if !os_birth_at_or_after_baseline(writer.os_birth_ns, baseline_ns) {
            return LobbyPoll::Unavailable {
                reason: Some("predates_baseline".into()),
            };
        }
        if !jvm_start_matches_os_birth(writer.jvm_start_ms, writer.os_birth_ns, now_ns) {
            return LobbyPoll::Unavailable {
                reason: Some("jvm_clock".into()),
            };
        }
        match liveness {
            Liveness::Alive => {
                self.absence.clear();
            }
            Liveness::Dead => {
                if self.bound_writer.as_ref().is_some_and(|b| writers_match(b, &writer)) {
                    return self.handle_absence();
                }
                return LobbyPoll::Unavailable {
                    reason: Some("writer_dead".into()),
                };
            }
            Liveness::Unavailable => {
                if self.bound_writer.as_ref().is_some_and(|b| b.pid == writer.pid) {
                    return self.poll_bound_presence(&writer, Some("process_unavailable"));
                }
                return LobbyPoll::Unavailable {
                    reason: Some("process_unavailable".into()),
                };
            }
        }
        if !validate_snapshot_shape(&value) {
            return LobbyPoll::Unavailable {
                reason: Some("invalid_shape".into()),
            };
        }
        if let Some(bound) = &self.bound_writer {
            if !writers_match(bound, &writer) {
                // `writer` was just observed Alive: capture it so a later
                // session reset can guard against relaunching over it.
                self.replacement_writer = Some(writer);
                return self.latch_terminal("game_session_changed");
            }
        }
        let class = classify(&value);
        let token = self.next_token();
        self.pending = Some(PendingProof {
            generation: self.generation,
            classification: class,
            writer: writer.clone(),
            token,
        });
        LobbyPoll::Snapshot {
            snapshot: value,
            token,
        }
    }

    fn poll_bound_presence(
        &mut self,
        bound: &WriterProof,
        alive_reason: Option<&str>,
    ) -> LobbyPoll {
        match bound_writer_presence(bound.pid, bound.os_birth_ns) {
            BoundPresence::Present => {
                self.observe_bound(Observation::Present);
                LobbyPoll::Unavailable {
                    reason: alive_reason.map(str::to_string),
                }
            }
            BoundPresence::Absent => self.handle_absence(),
            // Not death: an Indeterminate clears the machine outright, so a
            // refused query can never be half of a consecutive proof.
            BoundPresence::Unavailable => {
                self.observe_bound(Observation::Indeterminate);
                LobbyPoll::Unavailable {
                    reason: Some("process_unavailable".into()),
                }
            }
        }
    }

    /// Feeds one observation for the BOUND writer; latching is handled by
    /// the caller for the absent case only.
    fn observe_bound(&mut self, observation: Observation) -> bool {
        let now = self.clock.now();
        self.absence.observe(observation, now)
    }

    fn handle_absence(&mut self) -> LobbyPoll {
        if self.bound_writer.is_none() {
            return LobbyPoll::Unavailable { reason: None };
        }
        if self.observe_bound(Observation::Absent) {
            return self.latch_terminal("game_session_ended");
        }
        LobbyPoll::Unavailable {
            reason: Some("grace".into()),
        }
    }

    /// D2a. Maintains the pre-bind witness set and turns it into at most one
    /// observation per poll. `None` means "no claim": the caller falls
    /// through to the normal file-driven path (which is what binds a writer).
    fn poll_pre_bind_witness(&mut self, home: &Path, baseline_ns: EpochNs) -> Option<LobbyPoll> {
        let now = self.clock.now();
        let due = match self.last_scan_at {
            None => true,
            Some(at) => now.saturating_duration_since(at).as_millis() >= PRE_BIND_SCAN_MS,
        };
        let observation = if due {
            self.last_scan_at = Some(now);
            self.observe_full_scan(home, baseline_ns)
        } else {
            // Between scans only the cheap single-pid identity check runs.
            // With no authoritative scan this poll, the absence of a
            // replacement cannot be claimed, so an all-gone set makes no
            // observation at all: the streak is neither advanced nor cleared.
            match self.member_status() {
                MemberStatus::Empty | MemberStatus::AllGone => None,
                MemberStatus::AnyAlive => Some(Observation::Present),
                MemberStatus::AnyIndeterminate => Some(Observation::Indeterminate),
            }
        }?;
        if self.absence.observe(observation, now) {
            return Some(self.latch_terminal("game_session_ended"));
        }
        if observation == Observation::Absent {
            return Some(LobbyPoll::Unavailable {
                reason: Some("grace".into()),
            });
        }
        None
    }

    /// One full structured scan, folded into the witness set and then into a
    /// single observation.
    fn observe_full_scan(&mut self, home: &Path, baseline_ns: EpochNs) -> Option<Observation> {
        let scan = self.probe.scan(home);
        // Either a failed/partial enumeration or a candidate whose identity
        // cannot be established: it could be a live post-baseline game, so
        // "no replacement exists" may not be claimed from this poll.
        let uncertain = scan.outcome == ScanOutcome::Uncertain
            || scan.candidates.iter().any(|c| c.birth.is_none());
        let mut new_post_baseline = false;
        for candidate in &scan.candidates {
            let Some(birth) = candidate.birth else {
                continue;
            };
            if !os_birth_at_or_after_baseline(birth, baseline_ns) {
                continue;
            }
            let member = Witness {
                pid: candidate.pid,
                birth,
            };
            if !self.witness.contains(&member) {
                self.witness.push(member);
                new_post_baseline = true;
            }
        }
        if uncertain {
            return Some(Observation::Indeterminate);
        }
        match self.member_status() {
            // Never witnessed: absence alone can never latch.
            MemberStatus::Empty => None,
            MemberStatus::AnyAlive => Some(Observation::Present),
            MemberStatus::AnyIndeterminate => Some(Observation::Indeterminate),
            // A handoff/restart JVM joining the set this very poll is a live
            // replacement, not the end of the session.
            MemberStatus::AllGone if new_post_baseline => Some(Observation::Present),
            MemberStatus::AllGone => Some(Observation::Absent),
        }
    }

    fn member_status(&mut self) -> MemberStatus {
        let members: Vec<Witness> = self.witness.clone();
        if members.is_empty() {
            return MemberStatus::Empty;
        }
        let mut indeterminate = false;
        for member in members {
            match self.probe.check(member.pid, member.birth) {
                IdentityCheck::AliveSameIdentity => return MemberStatus::AnyAlive,
                IdentityCheck::Indeterminate => indeterminate = true,
                IdentityCheck::DefinitelyGone => {}
            }
        }
        if indeterminate {
            MemberStatus::AnyIndeterminate
        } else {
            MemberStatus::AllGone
        }
    }

    fn next_token(&mut self) -> u32 {
        let t = self.next_token.wrapping_add(1);
        if t == 0 {
            self.next_token = 1;
            1
        } else {
            self.next_token = t;
            t
        }
    }

    pub fn acknowledge(
        &mut self,
        token: u32,
        generation: u64,
        snapshot: &Value,
    ) -> Result<(), &'static str> {
        if self.terminal_reason.is_some() {
            return Err("session_terminal");
        }
        let pending = self.pending.as_ref().ok_or("no_pending")?;
        if pending.generation != generation || pending.token != token {
            return Err("stale_token");
        }
        let fields = parse_writer_fields(snapshot).ok_or("no_identity")?;
        let Some(ProcessObservation { birth_ns: os_birth_ns, liveness }) =
            observe_process(fields.pid)
        else {
            return Err("writer_unavailable");
        };
        let writer = WriterProof {
            pid: fields.pid,
            jvm_start_ms: fields.jvm_start_ms,
            os_birth_ns,
        };
        if !writers_match(&writer, &pending.writer) {
            return Err("writer_mismatch");
        }
        match liveness {
            Liveness::Alive => {}
            Liveness::Dead => {
                if self.bound_writer.as_ref().is_some_and(|b| writers_match(b, &writer)) {
                    // Same authoritative death observation the poll path
                    // feeds; the latch itself is taken by the next poll.
                    self.observe_bound(Observation::Absent);
                }
                return Err("writer_dead");
            }
            Liveness::Unavailable => return Err("writer_unavailable"),
        }
        if !validate_snapshot_shape(snapshot) {
            return Err("invalid_shape");
        }
        if let Some(bound) = &self.bound_writer {
            if !writers_match(bound, &writer) {
                self.replacement_writer = Some(writer);
                self.terminal_reason = Some("game_session_changed");
                return Err("session_changed");
            }
        } else {
            self.bound_writer = Some(writer);
        }
        if pending.classification == SnapshotClass::Live {
            self.live_seen = true;
        }
        self.pending = None;
        Ok(())
    }
}

fn writers_match(a: &WriterProof, b: &WriterProof) -> bool {
    a.pid == b.pid && a.jvm_start_ms == b.jvm_start_ms && a.os_birth_ns == b.os_birth_ns
}

/// A witnessed game JVM as a launch guard. Guards are pruned by pid + OS
/// birth alone (`check_identity`), and a pre-bind JVM never published a
/// `jvmStartTimeMs`, so the writer-only field is left at zero.
fn witness_proof(member: Witness) -> WriterProof {
    WriterProof {
        pid: member.pid,
        jvm_start_ms: 0,
        os_birth_ns: member.birth,
    }
}

fn push_unique_guard(guards: &mut Vec<WriterProof>, proof: WriterProof) {
    if !guards
        .iter()
        .any(|g| g.pid == proof.pid && g.os_birth_ns == proof.os_birth_ns)
    {
        guards.push(proof);
    }
}

struct WriterFields {
    pid: u32,
    jvm_start_ms: i64,
}

/// The mod reads `jvmPid` out of a Java `int`, and `lobby-validator.js` caps it at the
/// same `2^31 - 1`. Accepting more here would let a snapshot the frontend refuses bind a
/// session in the backend - the two validators have to agree on every field.
const MAX_WRITER_PID: u64 = 2_147_483_647;

fn parse_writer_fields(value: &Value) -> Option<WriterFields> {
    let obj = value.as_object()?;
    let pid = obj.get("jvmPid")?.as_u64()?;
    let jvm_start = obj.get("jvmStartTimeMs")?.as_i64()?;
    if pid == 0 || pid > MAX_WRITER_PID || jvm_start <= 0 {
        return None;
    }
    Some(WriterFields {
        pid: pid as u32,
        jvm_start_ms: jvm_start,
    })
}

pub fn parse_writer_identity(value: &Value) -> Option<WriterProof> {
    verified_writer_from_snapshot(value, SystemTime::now())
}

/// Strict exported-writer predicate for identity-only preflight.
pub fn verified_writer_from_snapshot(value: &Value, now: SystemTime) -> Option<WriterProof> {
    let fields = parse_writer_fields(value)?;
    let ProcessObservation { birth_ns: os_birth_ns, liveness } = observe_process(fields.pid)?;
    let writer = WriterProof {
        pid: fields.pid,
        jvm_start_ms: fields.jvm_start_ms,
        os_birth_ns,
    };
    let now_ns = system_time_to_ns(now).unwrap_or(0);
    if !jvm_start_matches_os_birth(writer.jvm_start_ms, writer.os_birth_ns, now_ns) {
        return None;
    }
    match liveness {
        Liveness::Alive => Some(writer),
        _ => None,
    }
}

/// Read-only preflight: alive writer with valid identity blocks launch.
pub fn preexisting_writer(home: &Path) -> bool {
    let path = home.join(".cobblify/lobby.json");
    let Ok(text) = fs::read_to_string(&path) else {
        return false;
    };
    let Ok(value) = serde_json::from_str::<Value>(&text) else {
        return false;
    };
    verified_writer_from_snapshot(&value, SystemTime::now()).is_some()
}

fn classify(value: &Value) -> SnapshotClass {
    let ctx = value
        .get("context")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let in_hypixel = value
        .get("inHypixel")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    if in_hypixel && LIVE_CONTEXTS.contains(&ctx) {
        SnapshotClass::Live
    } else {
        SnapshotClass::NonLive
    }
}

fn is_safe_int(n: i64) -> bool {
    (-2_147_483_648..=2_147_483_647).contains(&n)
}

fn is_finite_number(v: &Value) -> bool {
    v.as_f64().is_some_and(|f| f.is_finite())
}

/// Fields every contract version shares.
fn valid_common_player(obj: &serde_json::Map<String, Value>) -> bool {
    if !obj.get("name").and_then(|v| v.as_str()).is_some() {
        return false;
    }
    let state = obj.get("state").and_then(|v| v.as_str()).unwrap_or("");
    if !PLAYER_STATES.contains(&state) {
        return false;
    }
    if obj.get("nicked").and_then(|v| v.as_bool()).is_none() {
        return false;
    }
    if !obj.contains_key("realName") {
        return false;
    }
    if let Some(rn) = obj.get("realName") {
        if !rn.is_null() && !rn.is_string() {
            return false;
        }
    }
    if !obj.get("rank").and_then(|v| v.as_str()).is_some() {
        return false;
    }
    for key in ["fkdr", "wlr", "kd"] {
        if !is_finite_number(obj.get(key).unwrap_or(&Value::Null)) {
            return false;
        }
    }
    let fk = obj.get("finalKills").and_then(|v| v.as_i64());
    let threat = obj.get("seraphThreat").and_then(|v| v.as_i64());
    if !fk.is_some_and(is_safe_int) || !threat.is_some_and(is_safe_int) {
        return false;
    }
    if let Some(presence) = obj.get("presence") {
        let Some(p) = presence.as_str() else {
            return false;
        };
        if !PLAYER_PRESENCE.contains(&p) {
            return false;
        }
    }
    true
}

/// v1: tags as display-name string lists (older mod jars).
fn valid_player_v1(p: &Value) -> bool {
    let Some(obj) = p.as_object() else {
        return false;
    };
    if !valid_common_player(obj) {
        return false;
    }
    for tag_key in ["seraphTags", "urchinTags"] {
        let Some(tags) = obj.get(tag_key).and_then(|v| v.as_array()) else {
            return false;
        };
        if !tags.iter().all(|t| t.is_string()) {
            return false;
        }
    }
    true
}

/// One exported chip: badge glyph, § colour, label, positive-or-danger.
fn valid_chip(c: &Value) -> bool {
    let Some(obj) = c.as_object() else {
        return false;
    };
    for key in ["code", "color", "label"] {
        if !obj.get(key).and_then(|v| v.as_str()).is_some() {
            return false;
        }
    }
    obj.get("positive").and_then(|v| v.as_bool()).is_some()
}

/// v2: the mod's decisions travel with the row; the launcher draws them.
fn valid_player_v2(p: &Value) -> bool {
    let Some(obj) = p.as_object() else {
        return false;
    };
    if !valid_common_player(obj) {
        return false;
    }
    if !obj.get("rankCodes").and_then(|v| v.as_str()).is_some() {
        return false;
    }
    if !obj.get("mode").and_then(|v| v.as_str()).is_some() {
        return false;
    }
    match obj.get("fkdrTier").and_then(|v| v.as_i64()) {
        Some(t) if (0..=3).contains(&t) => {}
        _ => return false,
    }
    if obj.get("cheater").and_then(|v| v.as_bool()).is_none() {
        return false;
    }
    let Some(badge) = obj.get("badge") else {
        return false;
    };
    if !badge.is_null() && !valid_chip(badge) {
        return false;
    }
    let Some(chips) = obj.get("chips").and_then(|v| v.as_array()) else {
        return false;
    };
    if !chips.iter().all(valid_chip) {
        return false;
    }
    true
}

fn valid_player_for(version: u64) -> fn(&Value) -> bool {
    if version == 2 {
        valid_player_v2
    } else {
        valid_player_v1
    }
}

fn valid_team(t: &Value, valid_player: fn(&Value) -> bool) -> bool {
    let Some(obj) = t.as_object() else {
        return false;
    };
    if !obj.get("name").and_then(|v| v.as_str()).is_some() {
        return false;
    }
    let Some(players) = obj.get("players").and_then(|v| v.as_array()) else {
        return false;
    };
    players.iter().all(valid_player)
}

/// Complete v1 snapshot shape — matches the frontend exporter contract.
fn validate_snapshot_shape(value: &Value) -> bool {
    let Some(obj) = value.as_object() else {
        return false;
    };
    let Some(version) = obj.get("v").and_then(|v| v.as_u64()) else {
        return false;
    };
    if !CONTRACT_VERSIONS.contains(&version) {
        return false;
    }
    let valid_player = valid_player_for(version);
    let seq = obj.get("seq").and_then(|v| v.as_i64());
    if !seq.is_some_and(is_safe_int) {
        return false;
    }
    if parse_writer_fields(value).is_none() {
        return false;
    }
    let ctx = obj.get("context").and_then(|v| v.as_str()).unwrap_or("");
    if !matches!(ctx, "MENU" | "LOBBY" | "QUEUE" | "GAME") {
        return false;
    }
    if obj.get("inHypixel").and_then(|v| v.as_bool()).is_none() {
        return false;
    }
    // Optional, and the exporter omits it entirely when it has nothing to say. PRESENT it
    // must be a real boolean - `null` included - matching `lobby-validator.js`'s
    // `"dashboardEligible" in d && typeof d.dashboardEligible !== "boolean"`.
    if let Some(eligible) = obj.get("dashboardEligible") {
        if !eligible.is_boolean() {
            return false;
        }
    }
    if !obj.contains_key("self") {
        return false;
    }
    if let Some(self_v) = obj.get("self") {
        if !self_v.is_null() && !self_v.is_string() {
            return false;
        }
    }
    if !obj.contains_key("mode") {
        return false;
    }
    if let Some(mode) = obj.get("mode") {
        if !mode.is_null() && !mode.is_string() {
            return false;
        }
    }
    if !obj.contains_key("partyCount") {
        return false;
    }
    if let Some(pc) = obj.get("partyCount") {
        if !pc.is_null() && !pc.as_i64().is_some_and(is_safe_int) {
            return false;
        }
    }
    for key in ["yourParty", "players", "teams"] {
        let Some(arr) = obj.get(key).and_then(|v| v.as_array()) else {
            return false;
        };
        if key == "teams" {
            if !arr.iter().all(|t| valid_team(t, valid_player)) {
                return false;
            }
        } else if !arr.iter().all(valid_player) {
            return false;
        }
    }
    true
}

pub type SharedLobbySession = Mutex<LobbySession>;

/// Test seam: a scripted structured scan source and identity oracle, shared
/// by the lobby and lifecycle suites. Cloning shares one script.
#[cfg(test)]
#[derive(Clone, Default)]
pub struct ScriptedProbe {
    inner: Arc<Mutex<ScriptState>>,
}

#[cfg(test)]
#[derive(Default)]
struct ScriptState {
    queue: std::collections::VecDeque<Scan>,
    fallback: Option<Scan>,
    checks: std::collections::HashMap<u32, IdentityCheck>,
    scans: u32,
}

#[cfg(test)]
impl ScriptedProbe {
    pub fn new() -> Self {
        ScriptedProbe::default()
    }

    fn state(&self) -> std::sync::MutexGuard<'_, ScriptState> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Queues one scan for the next full-scan tick.
    pub fn push_scan(&self, scan: Scan) -> &Self {
        self.state().queue.push_back(scan);
        self
    }

    /// Queues a `Complete` scan of `(pid, birth)` candidates.
    pub fn push_complete(&self, candidates: &[(u32, Option<EpochNs>)]) -> &Self {
        self.push_scan(complete_scan(candidates))
    }

    /// Scan returned once the queue is empty (default: an empty `Complete`).
    pub fn set_fallback(&self, scan: Scan) -> &Self {
        self.state().fallback = Some(scan);
        self
    }

    pub fn set_check(&self, pid: u32, check: IdentityCheck) -> &Self {
        self.state().checks.insert(pid, check);
        self
    }

    pub fn scan_count(&self) -> u32 {
        self.state().scans
    }

    pub fn boxed(&self) -> Box<dyn GameProbe> {
        Box::new(self.clone())
    }
}

#[cfg(test)]
pub fn complete_scan(candidates: &[(u32, Option<EpochNs>)]) -> Scan {
    Scan {
        outcome: ScanOutcome::Complete,
        candidates: candidates
            .iter()
            .map(|(pid, birth)| GameCandidate {
                pid: *pid,
                birth: *birth,
            })
            .collect(),
    }
}

#[cfg(test)]
pub fn uncertain_scan() -> Scan {
    Scan {
        outcome: ScanOutcome::Uncertain,
        candidates: Vec::new(),
    }
}

#[cfg(test)]
impl GameProbe for ScriptedProbe {
    fn scan(&mut self, _home: &Path) -> Scan {
        let mut state = self.state();
        state.scans += 1;
        if let Some(next) = state.queue.pop_front() {
            return next;
        }
        state
            .fallback
            .clone()
            .unwrap_or_else(|| complete_scan(&[]))
    }

    fn check(&mut self, pid: u32, _birth: EpochNs) -> IdentityCheck {
        self.state()
            .checks
            .get(&pid)
            .copied()
            // A pid nobody scripted was never seen by this OS.
            .unwrap_or(IdentityCheck::DefinitelyGone)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::SystemTime;

    fn write_lobby(dir: &Path, pid: u32, start_ms: i64, ctx: &str, in_hypixel: bool) {
        fs::create_dir_all(dir.join(".cobblify")).unwrap();
        let json = format!(
            "{{\"v\":1,\"seq\":1,\"jvmPid\":{pid},\"jvmStartTimeMs\":{start_ms},\"context\":\"{ctx}\",\"inHypixel\":{in_hypixel},\"self\":null,\"mode\":null,\"partyCount\":null,\"yourParty\":[],\"players\":[],\"teams\":[]}}"
        );
        fs::write(dir.join(".cobblify/lobby.json"), json).unwrap();
    }

    fn spawn_post_baseline_writer() -> (u32, i64, std::process::Child) {
        #[cfg(windows)]
        let mut child = std::process::Command::new("cmd")
            .args(["/C", "ping", "127.0.0.1", "-n", "120", ">", "nul"])
            .spawn()
            .expect("spawn post-baseline writer stand-in");
        #[cfg(not(windows))]
        let mut child = std::process::Command::new("/bin/sleep")
            .arg("120")
            .spawn()
            .expect("spawn post-baseline writer stand-in");
        let pid = child.id();
        std::thread::sleep(std::time::Duration::from_millis(50));
        let birth_ns = process_liveness::process_birth_ns(pid).expect("child birth");
        let start_ms = ((birth_ns / 1_000_000) + 2) as i64;
        (pid, start_ms, child)
    }

    #[test]
    fn preflight_ignores_dead_writer() {
        let dir = tempfile::tempdir().unwrap();
        write_lobby(dir.path(), 999_999_999, 1_700_000_000_000, "LOBBY", true);
        assert!(!preexisting_writer(dir.path()));
    }

    #[test]
    fn lobby_poll_serializes_snake_case_tags() {
        let ended = serde_json::to_value(LobbyPoll::SessionEnded {
            reason: "game_session_ended",
        })
        .unwrap();
        assert_eq!(ended["kind"], "session_ended");
        let unavailable = serde_json::to_value(LobbyPoll::Unavailable { reason: None }).unwrap();
        assert_eq!(unavailable["kind"], "unavailable");
    }

    #[test]
    fn unavailable_when_no_baseline() {
        let mut session = LobbySession::default();
        let dir = tempfile::tempdir().unwrap();
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(matches!(poll, LobbyPoll::Unavailable { .. }));
    }

    #[test]
    fn acknowledge_consumes_pending_token() {
        let dir = tempfile::tempdir().unwrap();
        let mut session = LobbySession::default();
        let baseline = SystemTime::now();
        session.reset_for_launch(1, baseline, false);
        let (pid, start_ms, mut child) = spawn_post_baseline_writer();
        write_lobby(dir.path(), pid, start_ms, "MENU", false);
        let poll = session.poll(dir.path(), SystemTime::now());
        let LobbyPoll::Snapshot { snapshot, token } = poll else {
            panic!("expected snapshot for live writer");
        };
        session.acknowledge(token, 1, &snapshot).expect("first ack");
        assert!(session.acknowledge(token, 1, &snapshot).is_err());
        let _ = child.kill();
    }

    #[test]
    fn stale_mtime_before_baseline_is_unavailable() {
        let dir = tempfile::tempdir().unwrap();
        let mut session = LobbySession::default();
        let baseline = SystemTime::now();
        session.reset_for_launch(1, baseline, false);
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(matches!(poll, LobbyPoll::Unavailable { .. }));
    }

    /// Injected-clock port of the original 2100 ms sleep test; semantics
    /// unchanged: an ALIVE bound writer whose file vanished is never terminal,
    /// however much time passes.
    #[test]
    fn missing_file_after_bind_is_unavailable_not_terminal() {
        let dir = tempfile::tempdir().unwrap();
        let mut session = LobbySession::default();
        let clock = ManualClock::new();
        session.set_clock(clock.clock());
        let baseline = SystemTime::now();
        session.reset_for_launch(1, baseline, false);
        let (pid, start_ms, mut child) = spawn_post_baseline_writer();
        write_lobby(dir.path(), pid, start_ms, "LOBBY", true);
        let poll = session.poll(dir.path(), SystemTime::now());
        let LobbyPoll::Snapshot { snapshot, token } = poll else {
            panic!("expected snapshot");
        };
        session.acknowledge(token, 1, &snapshot).expect("bind writer");
        fs::remove_file(dir.path().join(".cobblify/lobby.json")).unwrap();
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(matches!(poll, LobbyPoll::Unavailable { .. }));
        clock.advance(Duration::from_millis(2100));
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(matches!(poll, LobbyPoll::Unavailable { .. }));
        let _ = child.kill();
    }

    /// Injected-clock port of the original 2100 ms sleep test, now against
    /// the exact D4d schedule: two consecutive authoritative absences arm the
    /// clock and a further absence at or past the grace latches.
    #[test]
    fn bound_writer_exit_reaches_session_ended_after_grace() {
        let dir = tempfile::tempdir().unwrap();
        let mut session = LobbySession::default();
        let clock = ManualClock::new();
        session.set_clock(clock.clock());
        let baseline = SystemTime::now();
        session.reset_for_launch(1, baseline, false);
        let (pid, start_ms, mut child) = spawn_post_baseline_writer();
        write_lobby(dir.path(), pid, start_ms, "LOBBY", true);
        let poll = session.poll(dir.path(), SystemTime::now());
        let LobbyPoll::Snapshot { snapshot, token } = poll else {
            panic!("expected snapshot");
        };
        session.acknowledge(token, 1, &snapshot).expect("bind writer");
        let _ = child.kill();
        let _ = child.wait();
        for _ in 0..2 {
            let poll = session.poll(dir.path(), SystemTime::now());
            assert!(matches!(
                poll,
                LobbyPoll::Unavailable {
                    reason: Some(ref r)
                } if r == "grace" || r == "missing_file"
            ));
            clock.advance(Duration::from_millis(250));
        }
        clock.advance(Duration::from_millis(1200));
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(matches!(
            poll,
            LobbyPoll::SessionEnded {
                reason: "game_session_ended"
            }
        ));
        // Terminal state latched — later polls stay terminal.
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(matches!(
            poll,
            LobbyPoll::SessionEnded {
                reason: "game_session_ended"
            }
        ));
    }

    #[test]
    fn unverified_replacement_does_not_terminalize_bound_session() {
        let dir = tempfile::tempdir().unwrap();
        let mut session = LobbySession::default();
        let baseline = SystemTime::now();
        session.reset_for_launch(1, baseline, false);
        let (pid, start_ms, mut child) = spawn_post_baseline_writer();
        write_lobby(dir.path(), pid, start_ms, "LOBBY", true);
        let poll = session.poll(dir.path(), SystemTime::now());
        let LobbyPoll::Snapshot { snapshot, token } = poll else {
            panic!("expected snapshot");
        };
        session.acknowledge(token, 1, &snapshot).expect("bind");
        write_lobby(dir.path(), 999_999_999, 1, "LOBBY", true);
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(matches!(poll, LobbyPoll::Unavailable { .. }));
        let _ = child.kill();
    }

    #[test]
    fn verified_replacement_terminalizes_and_latches() {
        let dir = tempfile::tempdir().unwrap();
        let mut session = LobbySession::default();
        let baseline = SystemTime::now();
        session.reset_for_launch(1, baseline, false);
        let (pid_a, start_a, mut child_a) = spawn_post_baseline_writer();
        write_lobby(dir.path(), pid_a, start_a, "LOBBY", true);
        let poll = session.poll(dir.path(), SystemTime::now());
        let LobbyPoll::Snapshot { snapshot, token } = poll else {
            panic!("expected snapshot");
        };
        session.acknowledge(token, 1, &snapshot).expect("bind A");
        let (pid_b, start_b, mut child_b) = spawn_post_baseline_writer();
        write_lobby(dir.path(), pid_b, start_b, "LOBBY", true);
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(matches!(
            poll,
            LobbyPoll::SessionEnded {
                reason: "game_session_changed"
            }
        ));
        // Writer A cannot escape terminal state.
        write_lobby(dir.path(), pid_a, start_a, "LOBBY", true);
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(matches!(
            poll,
            LobbyPoll::SessionEnded {
                reason: "game_session_changed"
            }
        ));
        let _ = child_a.kill();
        let _ = child_b.kill();
    }

    /// A changed-session reset must guard BOTH writers: the old bound one
    /// (death never proven) and the verified-live replacement.
    #[test]
    fn changed_session_reset_guards_bound_and_replacement() {
        let dir = tempfile::tempdir().unwrap();
        let mut session = LobbySession::default();
        session.reset_for_launch(1, SystemTime::now(), false);
        let (pid_a, start_a, mut child_a) = spawn_post_baseline_writer();
        write_lobby(dir.path(), pid_a, start_a, "LOBBY", true);
        let LobbyPoll::Snapshot { snapshot, token } = session.poll(dir.path(), SystemTime::now())
        else {
            panic!("expected snapshot");
        };
        session.acknowledge(token, 1, &snapshot).expect("bind A");
        let (pid_b, start_b, mut child_b) = spawn_post_baseline_writer();
        write_lobby(dir.path(), pid_b, start_b, "LOBBY", true);
        assert!(matches!(
            session.poll(dir.path(), SystemTime::now()),
            LobbyPoll::SessionEnded { reason: "game_session_changed" }
        ));
        session.clear_for_reset();
        let guards = session.session_guards();
        assert_eq!(guards.len(), 2, "old bound writer AND replacement guarded");
        assert!(guards.iter().any(|g| g.pid == pid_a));
        assert!(guards.iter().any(|g| g.pid == pid_b));
        assert_eq!(session.terminal_reason(), None, "latch cleared");
        // With no baseline, the next poll is unavailable, not session_ended.
        assert!(matches!(
            session.poll(dir.path(), SystemTime::now()),
            LobbyPoll::Unavailable { .. }
        ));
        let _ = child_a.kill();
        let _ = child_b.kill();
    }

    #[test]
    fn ended_session_reset_leaves_no_guards() {
        let mut session = LobbySession::default();
        session.reset_for_launch(1, SystemTime::now(), false);
        session.latch_terminal_for_test("game_session_ended");
        session.clear_for_reset();
        assert!(session.session_guards().is_empty());
        assert_eq!(session.terminal_reason(), None);
    }

    #[test]
    fn reset_for_launch_clears_stale_guards() {
        let mut session = LobbySession::default();
        session.set_session_guards(vec![WriterProof {
            pid: 1234,
            jvm_start_ms: 1,
            os_birth_ns: 1,
        }]);
        session.reset_for_launch(2, SystemTime::now(), false);
        assert!(session.session_guards().is_empty());
    }

    #[test]
    fn malformed_valid_identity_replacement_is_unavailable_not_terminal() {
        let dir = tempfile::tempdir().unwrap();
        let mut session = LobbySession::default();
        let baseline = SystemTime::now();
        session.reset_for_launch(1, baseline, false);
        let (pid, start_ms, mut child) = spawn_post_baseline_writer();
        write_lobby(dir.path(), pid, start_ms, "LOBBY", true);
        let poll = session.poll(dir.path(), SystemTime::now());
        let LobbyPoll::Snapshot { snapshot, token } = poll else {
            panic!("expected snapshot");
        };
        session.acknowledge(token, 1, &snapshot).expect("bind");
        // Valid identity but missing required nullable roots.
        let bad = format!(
            "{{\"v\":1,\"seq\":1,\"jvmPid\":{pid},\"jvmStartTimeMs\":{start_ms},\"context\":\"LOBBY\",\"inHypixel\":true}}"
        );
        fs::write(dir.path().join(".cobblify/lobby.json"), bad).unwrap();
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(matches!(poll, LobbyPoll::Unavailable { .. }));
        assert!(session.terminal_reason.is_none());
        let _ = child.kill();
    }

    #[test]
    fn changed_jvm_start_is_session_changed_not_same_writer() {
        let dir = tempfile::tempdir().unwrap();
        let mut session = LobbySession::default();
        let baseline = SystemTime::now();
        session.reset_for_launch(1, baseline, false);
        let (pid, start_ms, mut child) = spawn_post_baseline_writer();
        write_lobby(dir.path(), pid, start_ms, "LOBBY", true);
        let poll = session.poll(dir.path(), SystemTime::now());
        let LobbyPoll::Snapshot { snapshot, token } = poll else {
            panic!("expected snapshot");
        };
        session.acknowledge(token, 1, &snapshot).expect("bind");
        write_lobby(dir.path(), pid, start_ms + 500, "LOBBY", true);
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(
            matches!(
                poll,
                LobbyPoll::SessionEnded {
                    reason: "game_session_changed"
                }
            ),
            "expected session_changed, got {poll:?}"
        );
        let _ = child.kill();
    }

    #[test]
    fn preflight_rejects_stale_jvm_start_for_live_pid() {
        let pid = std::process::id();
        let dir = tempfile::tempdir().unwrap();
        write_lobby(dir.path(), pid, 1, "LOBBY", true);
        assert!(!preexisting_writer(dir.path()));
    }

    fn player_json(presence: Option<Value>) -> Value {
        let mut p = serde_json::json!({
            "name": "Alice", "state": "OK", "nicked": false, "realName": null, "rank": "[MVP+]",
            "rankCodes": "§b[MVP§c+§b]", "mode": "Overall",
            "fkdr": 6.8, "wlr": 4.4, "finalKills": 17700, "kd": 3.6, "fkdrTier": 2,
            "cheater": false, "badge": null, "chips": [], "seraphThreat": -1
        });
        if let Some(v) = presence {
            p["presence"] = v;
        }
        p
    }

    fn player_json_v1(presence: Option<Value>) -> Value {
        let mut p = serde_json::json!({
            "name": "Alice", "state": "OK", "nicked": false, "realName": null, "rank": "[MVP+]",
            "fkdr": 6.8, "wlr": 4.4, "finalKills": 17700, "kd": 3.6, "seraphThreat": -1,
            "seraphTags": [], "urchinTags": []
        });
        if let Some(v) = presence {
            p["presence"] = v;
        }
        p
    }

    #[test]
    fn player_presence_is_optional_and_closed() {
        for (valid, mk) in [
            (valid_player_v2 as fn(&Value) -> bool, player_json as fn(Option<Value>) -> Value),
            (valid_player_v1, player_json_v1),
        ] {
            assert!(valid(&mk(None)));
            for ok in ["ACTIVE", "DISCONNECTED", "ELIMINATED", "MISSING"] {
                assert!(valid(&mk(Some(Value::from(ok)))), "{ok}");
            }
            assert!(!valid(&mk(Some(Value::from("DEAD")))));
            assert!(!valid(&mk(Some(Value::from("active")))));
            assert!(!valid(&mk(Some(Value::Null))));
            assert!(!valid(&mk(Some(Value::from(1)))));
        }
        // Shapes do not cross versions.
        assert!(!valid_player_v2(&player_json_v1(None)));
        assert!(!valid_player_v1(&player_json(None)));
    }

    #[test]
    fn incomplete_menu_shape_is_invalid() {
        let value: Value = serde_json::json!({
            "v": 1,
            "seq": 1,
            "jvmPid": 1,
            "jvmStartTimeMs": 1_700_000_000_000i64,
            "context": "MENU",
            "inHypixel": false
        });
        assert!(!validate_snapshot_shape(&value));
    }

    /// The `write_lobby` shape as a value, so a single field can be varied.
    fn snapshot_json(pid: u64) -> Value {
        serde_json::json!({
            "v": 1,
            "seq": 1,
            "jvmPid": pid,
            "jvmStartTimeMs": 1_700_000_000_000i64,
            "context": "LOBBY",
            "inHypixel": true,
            "self": null,
            "mode": null,
            "partyCount": null,
            "yourParty": [],
            "players": [],
            "teams": []
        })
    }

    /// L15: the mod publishes `jvmPid` from a Java `int`, and `lobby-validator.js` caps it
    /// at `2^31 - 1`. This validator accepted anything up to `u32::MAX`, so a snapshot the
    /// frontend refuses could still bind a session here.
    #[test]
    fn a_writer_pid_beyond_the_java_int_range_is_refused() {
        assert!(validate_snapshot_shape(&snapshot_json(2_147_483_647)));
        assert!(!validate_snapshot_shape(&snapshot_json(2_147_483_648)));
        assert!(!validate_snapshot_shape(&snapshot_json(0)));
    }

    /// L15: `lobby-validator.js` rejects a PRESENT `dashboardEligible` that is not a
    /// boolean - `null` included. Absent is fine; the exporter omits it.
    #[test]
    fn a_present_dashboard_eligible_must_be_a_boolean() {
        let with = |v: Value| {
            let mut s = snapshot_json(4242);
            s.as_object_mut()
                .unwrap()
                .insert("dashboardEligible".to_string(), v);
            s
        };
        assert!(
            validate_snapshot_shape(&snapshot_json(4242)),
            "absent is fine"
        );
        assert!(validate_snapshot_shape(&with(Value::Bool(true))));
        assert!(validate_snapshot_shape(&with(Value::Bool(false))));
        assert!(!validate_snapshot_shape(&with(Value::Null)));
        assert!(!validate_snapshot_shape(&with(Value::from("yes"))));
        assert!(!validate_snapshot_shape(&with(Value::from(1))));
    }

    /// A8: the shared fixtures under common/src/test/resources/lobby-contract are the truth
    /// for this validator, the JS validator and the mod's DTO test.
    fn contract_fixtures(sub: &str) -> Vec<(String, Value)> {
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../common/src/test/resources/lobby-contract")
            .join(sub);
        let mut out: Vec<(String, Value)> = fs::read_dir(&dir)
            .unwrap_or_else(|e| panic!("fixture dir {}: {e}", dir.display()))
            .map(|e| e.unwrap().path())
            .filter(|p| p.extension().is_some_and(|x| x == "json"))
            .map(|p| {
                let text = fs::read_to_string(&p).unwrap();
                (p.file_name().unwrap().to_string_lossy().into_owned(), serde_json::from_str(&text).unwrap())
            })
            .collect();
        out.sort_by(|a, b| a.0.cmp(&b.0));
        assert!(!out.is_empty(), "no fixtures in {}", dir.display());
        out
    }

    #[test]
    fn every_valid_v2_fixture_is_accepted() {
        for (name, value) in contract_fixtures("valid") {
            assert!(validate_snapshot_shape(&value), "valid/{name} rejected");
            assert_eq!(value.get("v").and_then(|v| v.as_u64()), Some(2), "valid/{name}");
        }
    }

    #[test]
    fn every_invalid_fixture_is_rejected() {
        for (name, value) in contract_fixtures("invalid") {
            assert!(!validate_snapshot_shape(&value), "invalid/{name} accepted");
        }
    }

    #[test]
    fn a_legacy_v1_fixture_still_binds() {
        for (name, value) in contract_fixtures("valid-v1") {
            assert!(validate_snapshot_shape(&value), "valid-v1/{name} rejected");
            assert_eq!(value.get("v").and_then(|v| v.as_u64()), Some(1), "valid-v1/{name}");
        }
    }

    // ── D4d: the exact absence machine ──────────────────────────────────

    fn ms(n: u64) -> Duration {
        Duration::from_millis(n)
    }

    /// Feeds a scripted `(observation, delay-before-it)` sequence and reports
    /// the poll index that latched, if any.
    fn run_machine(script: &[(Observation, u64)]) -> Option<usize> {
        let mut machine = AbsenceMachine::default();
        let base = Instant::now();
        let mut at = base;
        for (index, (observation, delay)) in script.iter().enumerate() {
            at += ms(*delay);
            if machine.observe(*observation, at) {
                return Some(index);
            }
        }
        None
    }

    #[test]
    fn two_consecutive_absences_then_the_grace_latches() {
        use Observation::Absent;
        // A, A (arms), +1.2 s, A -> latch on the third observation.
        assert_eq!(
            run_machine(&[(Absent, 0), (Absent, 250), (Absent, 1200)]),
            Some(2)
        );
        // One millisecond short of the grace is NOT a latch.
        assert_eq!(
            run_machine(&[(Absent, 0), (Absent, 250), (Absent, 1199)]),
            None
        );
    }

    #[test]
    fn a_present_observation_clears_the_absence_streak() {
        use Observation::{Absent, Present};
        assert_eq!(
            run_machine(&[(Absent, 0), (Present, 250), (Absent, 5000)]),
            None,
            "a live writer resets the proof entirely"
        );
    }

    #[test]
    fn an_indeterminate_arms_fresh_at_the_new_streaks_second_absence() {
        use Observation::{Absent, Indeterminate};
        // A, I, A, A: the clock starts at the SECOND A of the new streak,
        // so nothing latches until a further grace elapses.
        assert_eq!(
            run_machine(&[(Absent, 0), (Indeterminate, 250), (Absent, 250), (Absent, 250)]),
            None
        );
        assert_eq!(
            run_machine(&[
                (Absent, 0),
                (Indeterminate, 250),
                (Absent, 250),
                (Absent, 250),
                (Absent, 1200)
            ]),
            Some(4)
        );
    }

    #[test]
    fn an_indeterminate_never_lets_a_stale_clock_survive() {
        use Observation::{Absent, Indeterminate};
        // A, A (arms), I, A, A: the armed clock is discarded, so the 1.2 s
        // that had already elapsed cannot be reused.
        assert_eq!(
            run_machine(&[
                (Absent, 0),
                (Absent, 250),
                (Indeterminate, 1200),
                (Absent, 250),
                (Absent, 250)
            ]),
            None,
            "no stale clock survives an Indeterminate"
        );
    }

    #[test]
    fn indeterminate_alone_never_arms() {
        use Observation::Indeterminate;
        assert_eq!(
            run_machine(&[
                (Indeterminate, 0),
                (Indeterminate, 5000),
                (Indeterminate, 5000)
            ]),
            None
        );
    }

    #[test]
    fn the_absence_streak_saturates_instead_of_wrapping() {
        let mut machine = AbsenceMachine::default();
        let at = Instant::now();
        for _ in 0..300 {
            machine.observe(Observation::Absent, at);
        }
        assert_eq!(machine.streak, u8::MAX);
        assert_eq!(machine.armed_at, Some(at), "armed once, at the second absence");
    }

    // ── D2a: the structured pre-bind witness ────────────────────────────

    fn base_ns(baseline: SystemTime) -> EpochNs {
        system_time_to_ns(baseline).expect("baseline in ns")
    }

    fn lunar_session(probe: &ScriptedProbe, clock: &ManualClock) -> (LobbySession, SystemTime) {
        let baseline = SystemTime::now();
        let mut session = LobbySession::default();
        session.set_clock(clock.clock());
        session.set_probe(probe.boxed());
        session.reset_for_launch(1, baseline, true);
        (session, baseline)
    }

    /// Poll every 250 ms of injected time, returning every poll's result.
    fn poll_for(
        session: &mut LobbySession,
        home: &Path,
        clock: &ManualClock,
        polls: usize,
    ) -> Vec<LobbyPoll> {
        let mut out = Vec::new();
        for _ in 0..polls {
            out.push(session.poll(home, SystemTime::now()));
            clock.advance(ms(250));
        }
        out
    }

    fn latched(polls: &[LobbyPoll]) -> bool {
        polls
            .iter()
            .any(|p| matches!(p, LobbyPoll::SessionEnded { reason: "game_session_ended" }))
    }

    #[test]
    fn a_candidate_with_unknown_birth_makes_the_observation_indeterminate() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        let pid = 4242;
        let birth = base_ns(baseline) + 1;
        // Witnessed, then dead - but every later scan carries a candidate
        // whose birth cannot be read, so absence may never be claimed.
        probe.push_complete(&[(pid, Some(birth))]);
        probe.set_fallback(complete_scan(&[(9001, None)]));
        probe.set_check(pid, IdentityCheck::AliveSameIdentity);
        let first = session.poll(dir.path(), SystemTime::now());
        assert!(!matches!(first, LobbyPoll::SessionEnded { .. }));
        probe.set_check(pid, IdentityCheck::DefinitelyGone);
        clock.advance(ms(250));
        let polls = poll_for(&mut session, dir.path(), &clock, 40);
        assert!(!latched(&polls), "unknown birth is never 'no replacement'");
    }

    #[test]
    fn an_uncertain_scan_makes_the_observation_indeterminate() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        let pid = 4243;
        let birth = base_ns(baseline) + 1;
        probe.push_complete(&[(pid, Some(birth))]);
        probe.set_check(pid, IdentityCheck::AliveSameIdentity);
        session.poll(dir.path(), SystemTime::now());
        assert_eq!(session.witness_len(), 1, "witnessed while alive");
        // The JVM dies, but every later scan FAILS: fail closed.
        probe.set_check(pid, IdentityCheck::DefinitelyGone);
        probe.set_fallback(uncertain_scan());
        clock.advance(ms(250));
        let polls = poll_for(&mut session, dir.path(), &clock, 40);
        assert!(!latched(&polls), "a failed scan is never proof of absence");
    }

    #[test]
    fn a_witnessed_and_provably_gone_jvm_latches_session_ended() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        let pid = 4244;
        let birth = base_ns(baseline) + 1;
        probe.push_complete(&[(pid, Some(birth))]);
        probe.set_check(pid, IdentityCheck::AliveSameIdentity);
        session.poll(dir.path(), SystemTime::now());
        probe.set_check(pid, IdentityCheck::DefinitelyGone);
        clock.advance(ms(250));
        let polls = poll_for(&mut session, dir.path(), &clock, 40);
        assert!(latched(&polls), "witnessed death latches the pre-bind session");
        assert_eq!(session.terminal_reason(), Some("game_session_ended"));
    }

    /// The scan runs FIRST in a poll, so a handoff JVM discovered on the very
    /// poll that would otherwise conclude death keeps the session alive.
    #[test]
    fn the_full_scan_runs_before_the_death_condition_in_the_same_poll() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        let base = base_ns(baseline);
        let (old, new) = (5001u32, 5002u32);
        probe.push_complete(&[(old, Some(base + 1))]);
        probe.set_check(old, IdentityCheck::AliveSameIdentity);
        session.poll(dir.path(), SystemTime::now());
        assert_eq!(session.witness_len(), 1);
        // The old JVM is gone AND a replacement appears in the same scan.
        probe.set_check(old, IdentityCheck::DefinitelyGone);
        probe.set_check(new, IdentityCheck::AliveSameIdentity);
        probe.set_fallback(complete_scan(&[(new, Some(base + 2))]));
        clock.advance(ms(500));
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(
            !matches!(poll, LobbyPoll::Unavailable { reason: Some(ref r) } if r == "grace"),
            "a replacement discovered this poll is not an absence: {poll:?}"
        );
        assert_eq!(session.witness_len(), 2, "the handoff JVM joined the set");
        let polls = poll_for(&mut session, dir.path(), &clock, 40);
        assert!(!latched(&polls), "a live replacement keeps the session alive");
    }

    #[test]
    fn the_pre_bind_full_scan_is_throttled_to_every_other_poll() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, _) = lunar_session(&probe, &clock);
        session.poll(dir.path(), SystemTime::now());
        assert_eq!(probe.scan_count(), 1, "the first poll always scans");
        clock.advance(ms(250));
        session.poll(dir.path(), SystemTime::now());
        assert_eq!(probe.scan_count(), 1, "250 ms later: no full scan");
        clock.advance(ms(250));
        session.poll(dir.path(), SystemTime::now());
        assert_eq!(probe.scan_count(), 2, "500 ms is the full-scan cadence");
    }

    /// CB-1's coverage promise, deterministically: a JVM present in the scans
    /// at t=500, 1000 and 1500 IS witnessed, so its later death recovers.
    #[test]
    fn a_jvm_present_across_three_scan_opportunities_is_witnessed() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        let pid = 6001;
        let birth = base_ns(baseline) + 1;
        let present = complete_scan(&[(pid, Some(birth))]);
        // Scans land at t=0, 500, 1000, 1500, 2000...: the JVM is visible for
        // the three in the middle and gone from every later one.
        probe.push_scan(complete_scan(&[]));
        probe.push_scan(present.clone());
        probe.push_scan(present.clone());
        probe.push_scan(present);
        probe.set_fallback(complete_scan(&[]));
        probe.set_check(pid, IdentityCheck::AliveSameIdentity);
        let alive = poll_for(&mut session, dir.path(), &clock, 8);
        assert!(!latched(&alive));
        assert_eq!(session.witness_len(), 1, "three opportunities: witnessed");
        probe.set_check(pid, IdentityCheck::DefinitelyGone);
        let polls = poll_for(&mut session, dir.path(), &clock, 40);
        assert!(latched(&polls), "a witnessed JVM's death recovers the session");
    }

    /// CB-1's boundary: never witnessed, never latched - the session polls
    /// forever and the Cancel control is the documented recovery.
    #[test]
    fn a_never_witnessed_lunar_session_never_latches() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, _) = lunar_session(&probe, &clock);
        // Empty scans throughout, and no lobby.json ever appears.
        let polls = poll_for(&mut session, dir.path(), &clock, 240);
        assert!(!latched(&polls), "absence alone never latches");
        assert!(polls.iter().all(|p| matches!(p, LobbyPoll::Unavailable { .. })));
        assert_eq!(session.terminal_reason(), None);
    }

    #[test]
    fn an_indeterminate_member_prevents_absence() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        let base = base_ns(baseline);
        let (a, b) = (7001u32, 7002u32);
        probe.push_complete(&[(a, Some(base + 1)), (b, Some(base + 2))]);
        probe.set_check(a, IdentityCheck::AliveSameIdentity);
        probe.set_check(b, IdentityCheck::AliveSameIdentity);
        session.poll(dir.path(), SystemTime::now());
        assert_eq!(session.witness_len(), 2);
        probe.set_check(a, IdentityCheck::DefinitelyGone);
        probe.set_check(b, IdentityCheck::Indeterminate);
        clock.advance(ms(250));
        let polls = poll_for(&mut session, dir.path(), &clock, 40);
        assert!(!latched(&polls), "a refused query is not a death");
    }

    #[test]
    fn pre_baseline_candidates_are_never_witnessed() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        let old = 7101u32;
        probe.set_fallback(complete_scan(&[(old, Some(base_ns(baseline) - 1))]));
        probe.set_check(old, IdentityCheck::AliveSameIdentity);
        let polls = poll_for(&mut session, dir.path(), &clock, 20);
        assert_eq!(session.witness_len(), 0, "a pre-baseline JVM is not ours");
        assert!(!latched(&polls));
    }

    /// Prism has no safe game-JVM identity, so the witness never runs there.
    #[test]
    fn a_forge_session_never_runs_the_pre_bind_witness() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let mut session = LobbySession::default();
        session.set_clock(clock.clock());
        session.set_probe(probe.boxed());
        session.reset_for_launch(1, SystemTime::now(), false);
        let polls = poll_for(&mut session, dir.path(), &clock, 20);
        assert_eq!(probe.scan_count(), 0, "no scan on a Forge session");
        assert!(!latched(&polls));
    }

    // ── D2b: abort evidence and the admission probe ─────────────────────

    #[test]
    fn abort_guards_a_live_witness_and_records_nothing() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        let pid = 8001;
        let birth = base_ns(baseline) + 1;
        probe.push_complete(&[(pid, Some(birth))]);
        probe.set_check(pid, IdentityCheck::AliveSameIdentity);
        session.poll(dir.path(), SystemTime::now());
        session.clear_for_abort(false, dir.path());
        let guards = session.session_guards();
        assert_eq!(guards.len(), 1);
        assert_eq!(guards[0].pid, pid);
        assert_eq!(guards[0].os_birth_ns, birth);
        assert!(session.unproven_abort().is_none(), "evidence, not a record");
    }

    #[test]
    fn abort_with_every_witness_gone_leaves_no_guard_and_no_record() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        let pid = 8002;
        probe.push_complete(&[(pid, Some(base_ns(baseline) + 1))]);
        probe.set_check(pid, IdentityCheck::AliveSameIdentity);
        session.poll(dir.path(), SystemTime::now());
        probe.set_check(pid, IdentityCheck::DefinitelyGone);
        session.clear_for_abort(false, dir.path());
        assert!(session.session_guards().is_empty(), "death was proven");
        assert!(session.unproven_abort().is_none());
    }

    #[test]
    fn abort_without_any_evidence_records_an_unproven_abort() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        session.clear_for_abort(true, dir.path());
        let record = session.unproven_abort().expect("record stored");
        assert_eq!(record.baseline, baseline);
        assert!(record.forge);
        assert!(session.session_guards().is_empty());
    }

    /// Review fix: an Indeterminate witnessed identity must be GUARDED at
    /// abort, never silently dropped - the same conservatism as pruning.
    #[test]
    fn an_indeterminate_witness_is_guarded_not_discarded() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        let pid = 8005;
        let birth = base_ns(baseline) + 1;
        probe.push_complete(&[(pid, Some(birth))]);
        probe.set_check(pid, IdentityCheck::AliveSameIdentity);
        session.poll(dir.path(), SystemTime::now());
        probe.set_check(pid, IdentityCheck::Indeterminate);
        session.clear_for_abort(false, dir.path());
        let guards = session.session_guards();
        assert_eq!(guards.len(), 1, "indeterminate stays guarded");
        assert_eq!(guards[0].pid, pid);
        assert!(session.unproven_abort().is_none(), "guarded, not recorded");
    }

    /// Review fix: a replacement that started after the last 500ms witness
    /// pass is caught by the abort-time scan, so this interleaving cannot
    /// admit a second launch outside CB-2.
    #[test]
    fn abort_catches_a_replacement_the_witness_never_saw() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        let old_pid = 8006;
        let new_pid = 8007;
        let new_birth = base_ns(baseline) + 9;
        probe.push_complete(&[(old_pid, Some(base_ns(baseline) + 1))]);
        probe.set_check(old_pid, IdentityCheck::AliveSameIdentity);
        session.poll(dir.path(), SystemTime::now());
        // The handoff happens entirely between witness passes.
        probe.set_check(old_pid, IdentityCheck::DefinitelyGone);
        probe.push_complete(&[(new_pid, Some(new_birth))]);
        probe.set_check(new_pid, IdentityCheck::AliveSameIdentity);
        session.clear_for_abort(false, dir.path());
        let guards = session.session_guards();
        assert_eq!(guards.len(), 1, "the unseen replacement is guarded");
        assert_eq!(guards[0].pid, new_pid);
        assert_eq!(guards[0].os_birth_ns, new_birth);
        assert!(session.unproven_abort().is_none());
    }

    /// Review fix: an uncertain abort-time scan can never prove the field
    /// clear - the record keeps admission layered.
    #[test]
    fn an_uncertain_abort_scan_records_an_unproven_abort() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        let pid = 8008;
        probe.push_complete(&[(pid, Some(base_ns(baseline) + 1))]);
        probe.set_check(pid, IdentityCheck::AliveSameIdentity);
        session.poll(dir.path(), SystemTime::now());
        probe.set_check(pid, IdentityCheck::DefinitelyGone);
        probe.push_scan(Scan {
            outcome: ScanOutcome::Uncertain,
            candidates: Vec::new(),
        });
        session.clear_for_abort(false, dir.path());
        assert!(session.session_guards().is_empty());
        assert!(session.unproven_abort().is_some(), "uncertainty fails closed");
    }

    /// Review fix: a candidate whose birth cannot be read is unattributable
    /// - it cannot be guarded, so the abort must stay layered.
    #[test]
    fn an_unattributable_candidate_at_abort_records_an_unproven_abort() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        let pid = 8009;
        probe.push_complete(&[(pid, Some(base_ns(baseline) + 1))]);
        probe.set_check(pid, IdentityCheck::AliveSameIdentity);
        session.poll(dir.path(), SystemTime::now());
        probe.set_check(pid, IdentityCheck::DefinitelyGone);
        probe.push_complete(&[(8010, None)]);
        session.clear_for_abort(false, dir.path());
        assert!(session.session_guards().is_empty());
        assert!(session.unproven_abort().is_some());
    }

    /// Review fix: mixed abort evidence - a live replacement is guarded AND
    /// the unattributable candidate keeps the record, so a later unknown
    /// identity is still re-probed at every admission.
    #[test]
    fn a_live_candidate_with_coexisting_uncertainty_keeps_the_record() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, baseline) = lunar_session(&probe, &clock);
        let seen_pid = 8011;
        let live_pid = 8012;
        let live_birth = base_ns(baseline) + 5;
        probe.push_complete(&[(seen_pid, Some(base_ns(baseline) + 1))]);
        probe.set_check(seen_pid, IdentityCheck::AliveSameIdentity);
        session.poll(dir.path(), SystemTime::now());
        probe.set_check(seen_pid, IdentityCheck::DefinitelyGone);
        probe.push_complete(&[(live_pid, Some(live_birth)), (8013, None)]);
        probe.set_check(live_pid, IdentityCheck::AliveSameIdentity);
        session.clear_for_abort(false, dir.path());
        let guards = session.session_guards();
        assert_eq!(guards.len(), 1, "the attributable live identity is guarded");
        assert_eq!(guards[0].pid, live_pid);
        assert!(
            session.unproven_abort().is_some(),
            "the guard must not erase the unattributable candidate"
        );
    }

    /// The production probe's completeness is live, not scripted: a real
    /// snapshot must pass the reconciliation proof and report `Complete`.
    #[test]
    fn the_production_probe_reports_complete_on_a_real_snapshot() {
        let mut probe = SystemGameProbe;
        let scan = probe.scan(Path::new("/nonexistent-home-for-this-test"));
        assert_eq!(scan.outcome, ScanOutcome::Complete);
        assert!(scan.candidates.is_empty());
    }

    #[test]
    fn abort_guards_the_bound_writer_whose_death_was_never_proven() {
        let dir = tempfile::tempdir().unwrap();
        let mut session = LobbySession::default();
        session.reset_for_launch(1, SystemTime::now(), false);
        let (pid, start_ms, mut child) = spawn_post_baseline_writer();
        write_lobby(dir.path(), pid, start_ms, "LOBBY", true);
        let LobbyPoll::Snapshot { snapshot, token } = session.poll(dir.path(), SystemTime::now())
        else {
            panic!("expected snapshot");
        };
        session.acknowledge(token, 1, &snapshot).expect("bind writer");
        session.clear_for_abort(false, dir.path());
        let guards = session.session_guards();
        assert_eq!(guards.len(), 1);
        assert_eq!(guards[0].pid, pid);
        assert!(session.unproven_abort().is_none());
        let _ = child.kill();
    }

    #[test]
    fn a_successful_launch_keeps_the_unproven_abort_record() {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new();
        let probe = ScriptedProbe::new();
        let (mut session, _) = lunar_session(&probe, &clock);
        session.clear_for_abort(false, dir.path());
        assert!(session.unproven_abort().is_some());
        session.reset_for_launch(2, SystemTime::now(), true);
        assert!(
            session.unproven_abort().is_some(),
            "only conversion or restart clears the record"
        );
        session.clear_unproven_abort();
        assert!(session.unproven_abort().is_none());
    }

    #[test]
    fn the_admission_probe_separates_live_indeterminate_and_clean() {
        let dir = tempfile::tempdir().unwrap();
        let baseline = SystemTime::now();
        let base = base_ns(baseline);
        let probe = ScriptedProbe::new();
        let mut session = LobbySession::default();
        session.set_probe(probe.boxed());

        probe.push_complete(&[(9101, Some(base + 1))]);
        probe.set_check(9101, IdentityCheck::AliveSameIdentity);
        assert_eq!(
            session.probe_post_baseline_games(dir.path(), baseline),
            AdmissionProbe::Live {
                identities: vec![WriterProof {
                    pid: 9101,
                    jvm_start_ms: 0,
                    os_birth_ns: base + 1,
                }],
                uncertain: false,
            }
        );

        // Mixed evidence: a guardable identity must NOT erase coexisting
        // uncertainty about an unattributable one (review finding).
        probe.push_complete(&[(9101, Some(base + 1)), (9104, None)]);
        assert_eq!(
            session.probe_post_baseline_games(dir.path(), baseline),
            AdmissionProbe::Live {
                identities: vec![WriterProof {
                    pid: 9101,
                    jvm_start_ms: 0,
                    os_birth_ns: base + 1,
                }],
                uncertain: true,
            }
        );

        probe.push_scan(uncertain_scan());
        assert_eq!(
            session.probe_post_baseline_games(dir.path(), baseline),
            AdmissionProbe::Indeterminate,
            "a failed scan fails closed"
        );

        probe.push_complete(&[(9102, None)]);
        assert_eq!(
            session.probe_post_baseline_games(dir.path(), baseline),
            AdmissionProbe::Indeterminate,
            "an unknown birth fails closed"
        );

        probe.push_complete(&[(9103, Some(base - 1))]);
        probe.set_check(9103, IdentityCheck::AliveSameIdentity);
        assert_eq!(
            session.probe_post_baseline_games(dir.path(), baseline),
            AdmissionProbe::Clean,
            "a pre-baseline JVM belongs to somebody else"
        );

        probe.push_complete(&[]);
        assert_eq!(
            session.probe_post_baseline_games(dir.path(), baseline),
            AdmissionProbe::Clean
        );
    }
}
