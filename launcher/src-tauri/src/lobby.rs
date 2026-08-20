//! Typed lobby polling, writer binding, and preflight.

use std::fs;
use std::path::Path;
use std::sync::Mutex;
use std::time::SystemTime;

use serde::Serialize;
use serde_json::Value;

use crate::process_liveness::{
    self, bound_writer_presence, jvm_start_matches_os_birth, observe_process, os_birth_at_or_after_baseline,
    system_time_to_ns, BoundPresence, EpochNs, Liveness, ProcessObservation,
};

const LIVE_CONTEXTS: &[&str] = &["LOBBY", "QUEUE", "GAME"];
const PLAYER_STATES: &[&str] = &["OK", "NICKED", "NEVER_PLAYED", "ERROR", "LOADING"];

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

#[derive(Default)]
pub struct LobbySession {
    generation: u64,
    baseline: Option<SystemTime>,
    bound_writer: Option<WriterProof>,
    live_seen: bool,
    pending: Option<PendingProof>,
    absence_since: Option<std::time::Instant>,
    next_token: u32,
    /// Latched terminal reason — later polls cannot escape session-ended/changed.
    terminal_reason: Option<&'static str>,
}

const ABSENCE_GRACE_MS: u128 = 2000;

impl LobbySession {
    pub fn reset_for_launch(&mut self, generation: u64, baseline: SystemTime) {
        self.generation = generation;
        self.baseline = Some(baseline);
        self.bound_writer = None;
        self.live_seen = false;
        self.pending = None;
        self.absence_since = None;
        self.terminal_reason = None;
    }

    pub fn clear_baseline(&mut self) {
        self.baseline = None;
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
                self.absence_since = None;
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
            BoundPresence::Present => LobbyPoll::Unavailable {
                reason: alive_reason.map(str::to_string),
            },
            BoundPresence::Absent => self.handle_absence(),
            BoundPresence::Unavailable => LobbyPoll::Unavailable {
                reason: Some("process_unavailable".into()),
            },
        }
    }

    fn handle_absence(&mut self) -> LobbyPoll {
        if self.bound_writer.is_none() {
            return LobbyPoll::Unavailable { reason: None };
        }
        let now = std::time::Instant::now();
        if self.absence_since.is_none() {
            self.absence_since = Some(now);
            return LobbyPoll::Unavailable {
                reason: Some("grace".into()),
            };
        }
        let since = self.absence_since.unwrap();
        if now.duration_since(since).as_millis() >= ABSENCE_GRACE_MS {
            return self.latch_terminal("game_session_ended");
        }
        LobbyPoll::Unavailable {
            reason: Some("grace".into()),
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
                    self.absence_since.get_or_insert_with(std::time::Instant::now);
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

struct WriterFields {
    pid: u32,
    jvm_start_ms: i64,
}

fn parse_writer_fields(value: &Value) -> Option<WriterFields> {
    let obj = value.as_object()?;
    let pid = obj.get("jvmPid")?.as_u64()?;
    let jvm_start = obj.get("jvmStartTimeMs")?.as_i64()?;
    if pid == 0 || pid > u32::MAX as u64 || jvm_start <= 0 {
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

fn valid_player(p: &Value) -> bool {
    let Some(obj) = p.as_object() else {
        return false;
    };
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

fn valid_team(t: &Value) -> bool {
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
    if obj.get("v").and_then(|v| v.as_u64()) != Some(1) {
        return false;
    }
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
            if !arr.iter().all(valid_team) {
                return false;
            }
        } else if !arr.iter().all(valid_player) {
            return false;
        }
    }
    true
}

pub type SharedLobbySession = Mutex<LobbySession>;

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
        session.reset_for_launch(1, baseline);
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
        session.reset_for_launch(1, baseline);
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(matches!(poll, LobbyPoll::Unavailable { .. }));
    }

    #[test]
    fn missing_file_after_bind_is_unavailable_not_terminal() {
        let dir = tempfile::tempdir().unwrap();
        let mut session = LobbySession::default();
        let baseline = SystemTime::now();
        session.reset_for_launch(1, baseline);
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
        std::thread::sleep(std::time::Duration::from_millis(2100));
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(matches!(poll, LobbyPoll::Unavailable { .. }));
        let _ = child.kill();
    }

    #[test]
    fn bound_writer_exit_reaches_session_ended_after_grace() {
        let dir = tempfile::tempdir().unwrap();
        let mut session = LobbySession::default();
        let baseline = SystemTime::now();
        session.reset_for_launch(1, baseline);
        let (pid, start_ms, mut child) = spawn_post_baseline_writer();
        write_lobby(dir.path(), pid, start_ms, "LOBBY", true);
        let poll = session.poll(dir.path(), SystemTime::now());
        let LobbyPoll::Snapshot { snapshot, token } = poll else {
            panic!("expected snapshot");
        };
        session.acknowledge(token, 1, &snapshot).expect("bind writer");
        let _ = child.kill();
        let _ = child.wait();
        let poll = session.poll(dir.path(), SystemTime::now());
        assert!(matches!(
            poll,
            LobbyPoll::Unavailable {
                reason: Some(ref r)
            } if r == "grace" || r == "missing_file"
        ));
        std::thread::sleep(std::time::Duration::from_millis(2100));
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
        session.reset_for_launch(1, baseline);
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
        session.reset_for_launch(1, baseline);
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

    #[test]
    fn malformed_valid_identity_replacement_is_unavailable_not_terminal() {
        let dir = tempfile::tempdir().unwrap();
        let mut session = LobbySession::default();
        let baseline = SystemTime::now();
        session.reset_for_launch(1, baseline);
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
        session.reset_for_launch(1, baseline);
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
}
