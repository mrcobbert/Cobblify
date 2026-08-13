//! Registering the Weave agent in Lunar's own launcher config.
//!
//! `~/.lunarclient/settings/launcher.json` holds ALL of Lunar's settings, so this is the
//! highest-risk operation in the launcher. Verified 2026-08-04: the file is JSON, the JVM
//! arguments live in `settings.jvm-args` AND `settings.jvmArgs` (both present, same value),
//! and the field is global rather than per-profile.
//!
//! Rules, all enforced below: back up once and never overwrite the backup; refuse to write
//! while Lunar is running (it rewrites this file on exit and would clobber the edit); parse
//! and preserve every unrelated argument; replace rather than duplicate a stale Weave
//! javaagent; write both keys; write atomically; refuse outright on unparseable JSON.

use std::ffi::OsStr;
use std::fs;
use std::path::Path;

use serde_json::{Map, Value};

const JVM_ARGS_KEYS: [&str; 2] = ["jvm-args", "jvmArgs"];
const BACKUP_SUFFIX: &str = ".bak-cobblify";

pub enum RegisterError {
    /// Lunar is up. Setup must wait; writing now would be clobbered on Lunar's exit.
    LunarRunning,
    Failed(String),
}

pub fn register(launcher_json: &Path, agent_path: &Path) -> Result<(), RegisterError> {
    register_with(crate::proc::lunar_running_checked, launcher_json, agent_path)
}

/// The running-state checker is injected so tests can prove the refusal
/// ORDER: an undeterminable state (`Err` - e.g. Windows without a usable
/// `LOCALAPPDATA`) must block before any read or write, exactly like a
/// running Lunar. `Ok(false)` alone reaches `apply`.
fn register_with(
    lunar_running: impl Fn() -> Result<bool, String>,
    launcher_json: &Path,
    agent_path: &Path,
) -> Result<(), RegisterError> {
    match lunar_running() {
        Err(e) => return Err(RegisterError::Failed(e)),
        Ok(true) => return Err(RegisterError::LunarRunning),
        Ok(false) => {}
    }
    apply(launcher_json, agent_path).map_err(RegisterError::Failed)
}

/// The write itself, with no process check, so it can be unit tested against temp files.
fn apply(launcher_json: &Path, agent_path: &Path) -> Result<(), String> {
    let raw = match fs::read_to_string(launcher_json) {
        Ok(raw) => raw,
        #[cfg(windows)]
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            return Err(format!(
                "{} does not exist. Lunar has never been run on this computer - open Lunar Client, log in once, then reopen Cobblify.",
                launcher_json.display()
            ))
        }
        Err(e) => return Err(format!("Cannot read {}: {e}", launcher_json.display())),
    };
    let mut root: Value = serde_json::from_str(&raw).map_err(|e| {
        format!(
            "{} is not valid JSON ({e}). Cobblify will not overwrite it - restore or fix the file, then reopen Cobblify.",
            launcher_json.display()
        )
    })?;

    // The Windows launcher.json schema has never been observed on a real
    // machine (the Mac schema was verified 2026-08-04). Refuse ANY shape that
    // premise does not cover BEFORE the first mutation, backup, or write -
    // the messages are the field diagnostic a friend can screenshot.
    #[cfg(windows)]
    windows_schema_guard(&root, launcher_json)?;

    let updated = {
        let obj = root
            .as_object_mut()
            .ok_or_else(|| not_an_object(launcher_json))?;
        let settings = obj
            .entry("settings")
            .or_insert_with(|| Value::Object(Map::new()))
            .as_object_mut()
            .ok_or_else(|| not_an_object(launcher_json))?;

        let existing = read_jvm_args(settings, launcher_json)?;
        let mut args: Vec<String> = split_args(&existing)
            .into_iter()
            .filter(|token| !is_weave_javaagent(token))
            .collect();
        args.push(format!(
            "-javaagent:{}",
            quote_if_needed(&agent_path.to_string_lossy())
        ));
        let value = args.join(" ");

        for key in JVM_ARGS_KEYS {
            settings.insert(key.to_string(), Value::String(value.clone()));
        }
        serde_json::to_string_pretty(&root)
            .map_err(|e| format!("Cannot serialise {}: {e}", launcher_json.display()))?
    };

    back_up_once(launcher_json)?;
    write_atomic(launcher_json, &updated)
}

fn not_an_object(path: &Path) -> String {
    format!(
        "{} does not have the expected shape. Cobblify will not overwrite it.",
        path.display()
    )
}

/// Fail-closed pre-write gate for the never-observed Windows schema. The Mac
/// writer tolerates absent `settings`/keys because that shape was VERIFIED
/// there; on Windows the same tolerance would rewrite an unknown schema and
/// report ready. Defined on every platform so its tests run everywhere;
/// called only on Windows, before `back_up_once` and `write_atomic`, so a
/// refusal provably leaves the file and any backup state untouched.
#[cfg_attr(not(windows), allow(dead_code))]
fn windows_schema_guard(root: &Value, path: &Path) -> Result<(), String> {
    let obj = root.as_object().ok_or_else(|| not_an_object(path))?;
    let settings = obj.get("settings").ok_or_else(|| {
        format!(
            "{} has no settings section, which this version of Cobblify has never seen on Windows. Cobblify will not overwrite it - send a screenshot of this message.",
            path.display()
        )
    })?;
    let settings = settings.as_object().ok_or_else(|| {
        format!(
            "settings in {} is not an object. Cobblify will not overwrite it - send a screenshot of this message.",
            path.display()
        )
    })?;

    let mut values: Vec<&str> = Vec::new();
    for key in JVM_ARGS_KEYS {
        match settings.get(key) {
            None | Some(Value::Null) => {}
            Some(Value::String(s)) => values.push(s),
            Some(_) => {
                return Err(format!(
                    "settings.{key} in {} is not a string. Cobblify will not overwrite it.",
                    path.display()
                ))
            }
        }
    }
    if values.is_empty() {
        return Err(format!(
            "{} has no jvm-args or jvmArgs entry, which this version of Cobblify has never seen on Windows. Cobblify will not overwrite it - send a screenshot of this message.",
            path.display()
        ));
    }
    let nonempty: Vec<&&str> = values.iter().filter(|s| !s.is_empty()).collect();
    if nonempty.len() == 2 && nonempty[0] != nonempty[1] {
        return Err(format!(
            "jvm-args and jvmArgs disagree in {}. Cobblify will not overwrite it - send a screenshot of this message.",
            path.display()
        ));
    }
    Ok(())
}

fn read_jvm_args(settings: &Map<String, Value>, path: &Path) -> Result<String, String> {
    let mut found = String::new();
    for key in JVM_ARGS_KEYS {
        match settings.get(key) {
            None | Some(Value::Null) => {}
            Some(Value::String(s)) => {
                if found.is_empty() {
                    found = s.clone();
                }
            }
            Some(_) => {
                return Err(format!(
                    "settings.{key} in {} is not a string. Cobblify will not overwrite it.",
                    path.display()
                ))
            }
        }
    }
    Ok(found)
}

/// Splits on unquoted whitespace, keeping each token exactly as written (quotes included) so
/// arguments the user set survive the round trip untouched.
fn split_args(value: &str) -> Vec<String> {
    let mut tokens = Vec::new();
    let mut current = String::new();
    let mut in_quotes = false;
    for c in value.chars() {
        match c {
            '"' => {
                in_quotes = !in_quotes;
                current.push(c);
            }
            c if c.is_whitespace() && !in_quotes => {
                if !current.is_empty() {
                    tokens.push(std::mem::take(&mut current));
                }
            }
            c => current.push(c),
        }
    }
    if !current.is_empty() {
        tokens.push(current);
    }
    tokens
}

/// True for a `-javaagent:` pointing at a Weave loader jar - either living in `~/.weave` or
/// named like the loader. Only those are dropped; any other agent the user runs is kept.
fn is_weave_javaagent(token: &str) -> bool {
    let Some(rest) = token.strip_prefix("-javaagent:") else {
        return false;
    };
    let path = Path::new(rest.trim_matches('"'));
    let in_weave_dir = path
        .parent()
        .and_then(|parent| parent.file_name())
        .is_some_and(|name| name == OsStr::new(".weave"));
    let weave_name = path
        .file_name()
        .and_then(OsStr::to_str)
        .is_some_and(|name| name.starts_with("Weave-Loader-Agent"));
    in_weave_dir || weave_name
}

/// The JVM tokenises its argument string on whitespace, so a home directory containing a
/// space needs embedded quotes - the same reason the old `.command` installer quoted the
/// path it put in `JAVA_TOOL_OPTIONS`.
fn quote_if_needed(path: &str) -> String {
    if path.chars().any(char::is_whitespace) {
        format!("\"{path}\"")
    } else {
        path.to_string()
    }
}

/// The backup is the pristine pre-Cobblify state and is what makes uninstall possible
/// (acceptance criterion 6), so an existing one is never replaced.
fn back_up_once(launcher_json: &Path) -> Result<(), String> {
    let name = launcher_json
        .file_name()
        .ok_or_else(|| format!("{} has no file name", launcher_json.display()))?
        .to_string_lossy()
        .into_owned();
    let backup = launcher_json.with_file_name(format!("{name}{BACKUP_SUFFIX}"));
    if backup.exists() {
        return Ok(());
    }
    fs::copy(launcher_json, &backup)
        .map(|_| ())
        .map_err(|e| format!("Cannot back up {} to {}: {e}", launcher_json.display(), backup.display()))
}

fn write_atomic(path: &Path, contents: &str) -> Result<(), String> {
    let name = path
        .file_name()
        .ok_or_else(|| format!("{} has no file name", path.display()))?
        .to_string_lossy()
        .into_owned();
    let tmp = path.with_file_name(format!(".{name}.cobblify-tmp"));
    let result = fs::write(&tmp, contents)
        .map_err(|e| format!("Cannot write {}: {e}", tmp.display()))
        .and_then(|()| {
            fs::rename(&tmp, path).map_err(|e| format!("Cannot write {}: {e}", path.display()))
        });
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    struct Fixture {
        _dir: tempfile::TempDir,
        json: PathBuf,
    }

    impl Fixture {
        fn new(contents: &str) -> Self {
            let dir = tempfile::tempdir().unwrap();
            let json = dir.path().join("launcher.json");
            fs::write(&json, contents).unwrap();
            Fixture { _dir: dir, json }
        }

        fn backup(&self) -> PathBuf {
            self.json.with_file_name("launcher.json.bak-cobblify")
        }

        fn jvm_args(&self) -> (String, String) {
            let value: Value =
                serde_json::from_str(&fs::read_to_string(&self.json).unwrap()).unwrap();
            let settings = &value["settings"];
            (
                settings["jvm-args"].as_str().unwrap().to_string(),
                settings["jvmArgs"].as_str().unwrap().to_string(),
            )
        }
    }

    const AGENT: &str = "/Users/tester/.weave/Weave-Loader-Agent-1.3.3.jar";

    /// Mac semantics, VERIFIED 2026-08-04: absent keys are valid empty input.
    /// On Windows the schema guard refuses this same shape (never observed
    /// there); the twin below locks that refusal.
    #[cfg(not(windows))]
    #[test]
    fn absent_jvm_args_gets_only_our_agent() {
        let f = Fixture::new(r#"{"settings":{"resolution":"1920x1080"}}"#);
        apply(&f.json, Path::new(AGENT)).unwrap();
        let (dashed, camel) = f.jvm_args();
        assert_eq!(dashed, format!("-javaagent:{AGENT}"));
        assert_eq!(camel, dashed);
        // Unrelated settings survive.
        let value: Value = serde_json::from_str(&fs::read_to_string(&f.json).unwrap()).unwrap();
        assert_eq!(value["settings"]["resolution"], "1920x1080");
    }

    #[cfg(windows)]
    #[test]
    fn windows_refuses_absent_jvm_keys_and_changes_nothing() {
        let original = r#"{"settings":{"resolution":"1920x1080"}}"#;
        let f = Fixture::new(original);
        let err = apply(&f.json, Path::new(AGENT)).unwrap_err();
        assert!(err.contains("no jvm-args or jvmArgs"), "{err}");
        assert_eq!(fs::read_to_string(&f.json).unwrap(), original);
        assert!(!f.backup().exists(), "a refused file must not be backed up");
    }

    #[cfg(not(windows))]
    #[test]
    fn missing_settings_object_is_created() {
        let f = Fixture::new(r#"{"version":3}"#);
        apply(&f.json, Path::new(AGENT)).unwrap();
        assert_eq!(f.jvm_args().0, format!("-javaagent:{AGENT}"));
    }

    #[cfg(windows)]
    #[test]
    fn windows_refuses_a_missing_settings_object_and_changes_nothing() {
        let original = r#"{"version":3}"#;
        let f = Fixture::new(original);
        let err = apply(&f.json, Path::new(AGENT)).unwrap_err();
        assert!(err.contains("no settings section"), "{err}");
        assert_eq!(fs::read_to_string(&f.json).unwrap(), original);
        assert!(!f.backup().exists(), "a refused file must not be backed up");
    }

    #[test]
    fn empty_jvm_args_gets_only_our_agent() {
        let f = Fixture::new(r#"{"settings":{"jvm-args":"","jvmArgs":""}}"#);
        apply(&f.json, Path::new(AGENT)).unwrap();
        assert_eq!(f.jvm_args().0, format!("-javaagent:{AGENT}"));
    }

    #[test]
    fn unrelated_args_are_preserved() {
        let f = Fixture::new(
            r#"{"settings":{"jvm-args":"-Xmx4G -XX:+UseG1GC","jvmArgs":"-Xmx4G -XX:+UseG1GC"}}"#,
        );
        apply(&f.json, Path::new(AGENT)).unwrap();
        assert_eq!(
            f.jvm_args().0,
            format!("-Xmx4G -XX:+UseG1GC -javaagent:{AGENT}")
        );
    }

    #[test]
    fn a_non_weave_javaagent_is_kept() {
        let f = Fixture::new(
            r#"{"settings":{"jvm-args":"-javaagent:/opt/other/profiler.jar","jvmArgs":"-javaagent:/opt/other/profiler.jar"}}"#,
        );
        apply(&f.json, Path::new(AGENT)).unwrap();
        assert_eq!(
            f.jvm_args().0,
            format!("-javaagent:/opt/other/profiler.jar -javaagent:{AGENT}")
        );
    }

    #[test]
    fn a_stale_weave_agent_is_replaced_not_duplicated() {
        let stale = "-javaagent:/Users/tester/.weave/Weave-Loader-Agent-1.2.0.jar";
        let f = Fixture::new(&format!(
            r#"{{"settings":{{"jvm-args":"-Xmx4G {stale}","jvmArgs":"-Xmx4G {stale}"}}}}"#
        ));
        apply(&f.json, Path::new(AGENT)).unwrap();
        let (dashed, _) = f.jvm_args();
        assert_eq!(dashed, format!("-Xmx4G -javaagent:{AGENT}"));
        assert_eq!(dashed.matches("-javaagent:").count(), 1);
    }

    #[test]
    fn running_twice_is_idempotent() {
        let f = Fixture::new(r#"{"settings":{"jvm-args":"-Xmx4G","jvmArgs":"-Xmx4G"}}"#);
        apply(&f.json, Path::new(AGENT)).unwrap();
        let after_first = f.jvm_args();
        apply(&f.json, Path::new(AGENT)).unwrap();
        assert_eq!(f.jvm_args(), after_first);
        assert_eq!(f.jvm_args().0.matches("-javaagent:").count(), 1);
    }

    #[test]
    fn malformed_json_refuses_to_write_and_changes_nothing() {
        let broken = "{ this is not json";
        let f = Fixture::new(broken);
        let err = apply(&f.json, Path::new(AGENT)).unwrap_err();
        assert!(err.contains("is not valid JSON"), "{err}");
        assert_eq!(fs::read_to_string(&f.json).unwrap(), broken);
        assert!(!f.backup().exists(), "a corrupt file must not be backed up");
    }

    #[test]
    fn a_non_string_jvm_args_value_refuses_to_write() {
        let original = r#"{"settings":{"jvm-args":["-Xmx4G"]}}"#;
        let f = Fixture::new(original);
        let err = apply(&f.json, Path::new(AGENT)).unwrap_err();
        assert!(err.contains("is not a string"), "{err}");
        assert_eq!(fs::read_to_string(&f.json).unwrap(), original);
    }

    #[test]
    fn the_pristine_backup_is_taken_once_and_never_overwritten() {
        let original = r#"{"settings":{"jvm-args":"-Xmx4G","jvmArgs":"-Xmx4G"}}"#;
        let f = Fixture::new(original);
        apply(&f.json, Path::new(AGENT)).unwrap();
        assert_eq!(fs::read_to_string(f.backup()).unwrap(), original);
        apply(&f.json, Path::new(AGENT)).unwrap();
        assert_eq!(
            fs::read_to_string(f.backup()).unwrap(),
            original,
            "the backup must stay the pre-Cobblify state"
        );
    }

    #[test]
    fn a_path_with_a_space_is_quoted() {
        let spaced = "/Users/Jane Smith/.weave/Weave-Loader-Agent-1.3.3.jar";
        let f = Fixture::new(r#"{"settings":{"jvm-args":"-Xmx4G","jvmArgs":"-Xmx4G"}}"#);
        apply(&f.json, Path::new(spaced)).unwrap();
        assert_eq!(
            f.jvm_args().0,
            format!("-Xmx4G -javaagent:\"{spaced}\"")
        );

        // ... and a second run still recognises and replaces it, rather than duplicating.
        apply(&f.json, Path::new(spaced)).unwrap();
        assert_eq!(f.jvm_args().0.matches("-javaagent:").count(), 1);
    }

    #[test]
    fn both_keys_are_written_even_if_only_one_existed() {
        let f = Fixture::new(r#"{"settings":{"jvm-args":"-Xmx4G"}}"#);
        apply(&f.json, Path::new(AGENT)).unwrap();
        let (dashed, camel) = f.jvm_args();
        assert_eq!(dashed, camel);
        assert_eq!(dashed, format!("-Xmx4G -javaagent:{AGENT}"));
    }

    #[cfg(not(windows))]
    #[test]
    fn no_temp_file_is_left_behind() {
        let f = Fixture::new(r#"{"settings":{}}"#);
        apply(&f.json, Path::new(AGENT)).unwrap();
        assert!(!f
            .json
            .with_file_name(".launcher.json.cobblify-tmp")
            .exists());
    }

    /// The exact bytes of the Mac `no_temp_file_is_left_behind` fixture,
    /// through the guarded `apply` path: `{"settings":{}}` has both keys
    /// absent, so Windows must refuse it, changing nothing.
    #[cfg(windows)]
    #[test]
    fn windows_refuses_an_empty_settings_object_and_changes_nothing() {
        let original = r#"{"settings":{}}"#;
        let f = Fixture::new(original);
        let err = apply(&f.json, Path::new(AGENT)).unwrap_err();
        assert!(err.contains("no jvm-args or jvmArgs"), "{err}");
        assert_eq!(fs::read_to_string(&f.json).unwrap(), original);
        assert!(!f.backup().exists(), "a refused file must not be backed up");
    }

    /// The Windows guard refuses `{"settings":{}}` (both keys absent), so the
    /// no-temp invariant is asserted on an ACCEPTED shape instead.
    #[cfg(windows)]
    #[test]
    fn no_temp_file_is_left_behind_on_an_accepted_shape() {
        let f = Fixture::new(r#"{"settings":{"jvm-args":"-Xmx4G","jvmArgs":"-Xmx4G"}}"#);
        apply(&f.json, Path::new(AGENT)).unwrap();
        assert!(!f
            .json
            .with_file_name(".launcher.json.cobblify-tmp")
            .exists());
    }

    // The Windows schema guard's refusal matrix, testable on every platform
    // because the guard itself is platform-neutral pure logic.

    fn guard(json: &str) -> Result<(), String> {
        let root: Value = serde_json::from_str(json).unwrap();
        windows_schema_guard(&root, Path::new("launcher.json"))
    }

    #[test]
    fn schema_guard_refuses_every_unverified_shape() {
        assert!(guard(r#"{"version":3}"#).is_err(), "absent settings");
        assert!(guard(r#"{"settings":"nope"}"#).is_err(), "non-object settings");
        assert!(guard(r#"{"settings":{}}"#).is_err(), "both keys absent");
        assert!(
            guard(r#"{"settings":{"jvm-args":null,"jvmArgs":null}}"#).is_err(),
            "null-only keys are absent keys"
        );
        assert!(
            guard(r#"{"settings":{"jvm-args":["-Xmx4G"]}}"#).is_err(),
            "non-string key"
        );
        assert!(
            guard(r#"{"settings":{"jvm-args":"-Xmx4G","jvmArgs":"-Xmx8G"}}"#).is_err(),
            "divergent non-empty keys must not be silently collapsed"
        );
    }

    #[test]
    fn schema_guard_accepts_the_verified_shapes() {
        assert!(guard(r#"{"settings":{"jvm-args":"-Xmx4G","jvmArgs":"-Xmx4G"}}"#).is_ok());
        assert!(guard(r#"{"settings":{"jvm-args":"-Xmx4G"}}"#).is_ok(), "one key");
        assert!(
            guard(r#"{"settings":{"jvm-args":"","jvmArgs":"-Xmx4G"}}"#).is_ok(),
            "empty-vs-value matches the Mac read semantics"
        );
        assert!(guard(r#"{"settings":{"jvm-args":"","jvmArgs":""}}"#).is_ok());
    }

    // The register seam: refusal ORDER is the contract. An undeterminable
    // running state must block before any read or write.

    #[test]
    fn an_undeterminable_lunar_state_blocks_before_any_write() {
        let original = r#"{"settings":{"jvm-args":"-Xmx4G","jvmArgs":"-Xmx4G"}}"#;
        let f = Fixture::new(original);
        let result = register_with(
            || Err("Cannot tell whether Lunar is running.".to_string()),
            &f.json,
            Path::new(AGENT),
        );
        assert!(matches!(
            result,
            Err(RegisterError::Failed(ref e)) if e.contains("Cannot tell")
        ));
        assert_eq!(fs::read_to_string(&f.json).unwrap(), original);
        assert!(!f.backup().exists());
    }

    #[test]
    fn a_running_lunar_still_blocks_and_a_clean_check_still_applies() {
        let f = Fixture::new(r#"{"settings":{"jvm-args":"-Xmx4G","jvmArgs":"-Xmx4G"}}"#);
        assert!(matches!(
            register_with(|| Ok(true), &f.json, Path::new(AGENT)),
            Err(RegisterError::LunarRunning)
        ));
        assert!(matches!(
            register_with(|| Ok(false), &f.json, Path::new(AGENT)),
            Ok(())
        ));
        assert_eq!(
            f.jvm_args().0,
            format!("-Xmx4G -javaagent:{AGENT}")
        );
    }
}
