//! Registering the Weave agent in Lunar's own launcher config.
//!
//! `~/.lunarclient/settings/launcher.json` holds ALL of Lunar's settings, so this is the
//! highest-risk operation in the launcher. Verified 2026-08-04: the file is JSON, the JVM
//! arguments live in `settings.jvm-args` AND `settings.jvmArgs` (both present, same value
//! on a file this writer last touched), and the field is global rather than per-profile.
//!
//! The two spellings DIVERGE in normal use (L1, 2026-09-22): Lunar's own launcher UI
//! writes `jvmArgs` and never the dashed key, so once a user edits their arguments the
//! dashed key still holds what Cobblify wrote last. `read_jvm_args` therefore treats a
//! present `jvmArgs` - empty included - as the authoritative value and only falls back to
//! `jvm-args` when the camelCase key is absent. Both keys are still written, so a file
//! this launcher has touched converges back to agreeing.
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

/// Removes Cobblify's Weave javaagent from Lunar's config - the uninstall
/// counterpart of `apply`, and the only writer here that takes a token away.
///
/// Deliberately narrower than `apply`: it strips `is_weave_javaagent` tokens
/// from whichever of the two keys is PRESENT as a string and leaves every
/// other key, value and spelling exactly as it found them, because by the
/// time this runs Cobblify is being removed and the file belongs entirely to
/// Lunar again. A key Lunar never had is never created; a file that holds no
/// agent of ours is not rewritten at all; the `.bak-cobblify` copy is left in
/// place as the user's pristine pre-Cobblify state.
///
/// A missing file is success (nothing of ours is registered). Unparseable
/// JSON is still a hard refusal - the same rule as `apply`, for the same
/// reason: this file holds ALL of Lunar's settings.
pub fn unregister(launcher_json: &Path) -> Result<(), String> {
    let raw = match fs::read_to_string(launcher_json) {
        Ok(raw) => raw,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(format!("Cannot read {}: {e}", launcher_json.display())),
    };
    let mut root: Value = serde_json::from_str(&raw).map_err(|e| {
        format!(
            "{} is not valid JSON ({e}). Cobblify will not overwrite it.",
            launcher_json.display()
        )
    })?;
    // No settings object means nothing of ours can be registered, so there is
    // nothing to remove and no reason to touch the file.
    let Some(settings) = root
        .get_mut("settings")
        .and_then(|settings| settings.as_object_mut())
    else {
        return Ok(());
    };

    let mut changed = false;
    for key in JVM_ARGS_KEYS {
        let stripped = match settings.get(key) {
            Some(Value::String(existing)) => {
                let kept = split_args(existing)
                    .into_iter()
                    .filter(|token| !is_weave_javaagent(token))
                    .collect::<Vec<String>>()
                    .join(" ");
                if kept == *existing {
                    continue;
                }
                kept
            }
            _ => continue,
        };
        settings.insert(key.to_string(), Value::String(stripped));
        changed = true;
    }
    if !changed {
        return Ok(());
    }

    let updated = serde_json::to_string_pretty(&root)
        .map_err(|e| format!("Cannot serialise {}: {e}", launcher_json.display()))?;
    write_atomic(launcher_json, &updated)
}

fn not_an_object(path: &Path) -> String {
    format!(
        "{} does not have the expected shape. Cobblify will not overwrite it.",
        path.display()
    )
}

/// Fail-closed pre-write gate for the Windows schema. Defined on every
/// platform so its tests run everywhere; called only on Windows, before
/// `back_up_once` and `write_atomic`, so a refusal provably leaves the file
/// and any backup state untouched.
///
/// OBSERVED on a real Windows machine 2026-08-13 (the first sighting): the
/// file exists at the expected path, parses, and holds a populated `settings`
/// object - but a fresh install has NO jvm keys at all; Lunar's launcher UI
/// creates `jvmArgs` (camelCase only) the first time arguments are set.
/// Absent keys are therefore a verified-legitimate state and are accepted
/// exactly as on the Mac (the writer creates both spellings).
///
/// Divergent values were refused here until 2026-09-22 (L1). That refusal
/// was wrong about the field: because Lunar edits `jvmArgs` alone, ANY user
/// who changes their arguments after a Cobblify setup leaves the two keys
/// disagreeing, and the launcher would then refuse Lunar setup forever.
/// `read_jvm_args` resolves the disagreement deterministically (camelCase
/// wins) instead. A missing `settings` object and non-string keys remain
/// refused - those shapes have still never been seen.
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

    for key in JVM_ARGS_KEYS {
        match settings.get(key) {
            None | Some(Value::Null) | Some(Value::String(_)) => {}
            Some(_) => {
                return Err(format!(
                    "settings.{key} in {} is not a string. Cobblify will not overwrite it.",
                    path.display()
                ))
            }
        }
    }
    Ok(())
}

/// Which of the two spellings is the user's CURRENT intent.
///
/// `jvmArgs` wins whenever it is present as a string, even an empty one
/// (L1): Lunar's own launcher UI writes that key and only that key, so it is
/// the live value, while `jvm-args` holds whatever Cobblify wrote there last
/// - a stale agent path and none of the flags the user has set since. An
/// empty `jvmArgs` is the user having cleared their arguments, not a missing
/// value, so it must not fall back. Only an absent (or null) `jvmArgs` reads
/// the dashed key, which is what an older Cobblify-only file looks like.
///
/// Both keys are still type-checked before either is used: a non-string in
/// EITHER spelling is a shape this writer refuses, whichever one it would
/// have read.
fn read_jvm_args(settings: &Map<String, Value>, path: &Path) -> Result<String, String> {
    let mut chosen: Option<&String> = None;
    for key in JVM_ARGS_KEYS {
        match settings.get(key) {
            None | Some(Value::Null) => {}
            Some(Value::String(s)) => {
                // JVM_ARGS_KEYS is ["jvm-args", "jvmArgs"], so the camelCase
                // key is seen second and overwrites the dashed one.
                chosen = Some(s);
            }
            Some(_) => {
                return Err(format!(
                    "settings.{key} in {} is not a string. Cobblify will not overwrite it.",
                    path.display()
                ))
            }
        }
    }
    Ok(chosen.cloned().unwrap_or_default())
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
    // Caseless on every platform, like the jar matching in `install.rs`: on NTFS and on a
    // case-folding Mac volume `.WEAVE\WEAVE-LOADER-AGENT-1.3.3.JAR` names the jar the
    // launcher installed, and uninstall must strip the argument for the same jar it deletes.
    let path = Path::new(rest.trim_matches('"'));
    let in_weave_dir = path
        .parent()
        .and_then(|parent| parent.file_name())
        .and_then(OsStr::to_str)
        .is_some_and(|name| name.eq_ignore_ascii_case(".weave"));
    let weave_name = path
        .file_name()
        .and_then(OsStr::to_str)
        .is_some_and(|name| {
            name.len() >= "Weave-Loader-Agent".len()
                && name[.."Weave-Loader-Agent".len()].eq_ignore_ascii_case("Weave-Loader-Agent")
        });
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

    /// Absent keys are valid empty input on BOTH platforms: verified on Mac
    /// 2026-08-04, and observed as the normal fresh-install state on a real
    /// Windows machine 2026-08-13 (Lunar creates `jvmArgs` only when args
    /// are first set).
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

    /// L1. The two keys diverge in the field: Lunar's own launcher UI writes
    /// `jvmArgs`, so the user's real arguments live there while `jvm-args`
    /// keeps whatever Cobblify wrote last - a STALE agent path and none of
    /// the user's flags. Taking the dashed key first (the old rule) silently
    /// reverted `-Xmx8G`. The camelCase key is authoritative.
    #[test]
    fn divergent_keys_prefer_jvm_args_and_keep_user_flags() {
        let stale = "-javaagent:/Users/tester/.weave/Weave-Loader-Agent-1.2.0.jar";
        let f = Fixture::new(&format!(
            r#"{{"settings":{{"jvm-args":"{stale}","jvmArgs":"-Xmx8G {stale}"}}}}"#
        ));
        apply(&f.json, Path::new(AGENT)).unwrap();
        let (dashed, camel) = f.jvm_args();
        assert_eq!(dashed, format!("-Xmx8G -javaagent:{AGENT}"));
        assert_eq!(camel, dashed);
        assert_eq!(dashed.matches("-javaagent:").count(), 1);
    }

    /// L1 / round-1 I3. An empty `jvmArgs` is the user having CLEARED their
    /// arguments in Lunar's UI. A present key is authoritative even when
    /// empty, so a stale dashed key cannot resurrect what they removed.
    #[test]
    fn cleared_jvm_args_is_authoritative_over_a_stale_dashed_key() {
        let f = Fixture::new(r#"{"settings":{"jvm-args":"-Xmx8G","jvmArgs":""}}"#);
        apply(&f.json, Path::new(AGENT)).unwrap();
        let (dashed, camel) = f.jvm_args();
        assert_eq!(dashed, format!("-javaagent:{AGENT}"));
        assert_eq!(camel, dashed);
    }

    /// L1 / round-1 I5. Preferring `jvmArgs` must not cost the existing rule
    /// that a foreign agent is never dropped - and that rule is only
    /// observable through the key we now read.
    #[test]
    fn a_foreign_javaagent_in_jvm_args_is_preserved_over_the_dashed_key() {
        let f = Fixture::new(
            r#"{"settings":{"jvm-args":"-Xmx4G","jvmArgs":"-javaagent:/opt/other/profiler.jar"}}"#,
        );
        apply(&f.json, Path::new(AGENT)).unwrap();
        let (dashed, camel) = f.jvm_args();
        assert_eq!(
            dashed,
            format!("-javaagent:/opt/other/profiler.jar -javaagent:{AGENT}")
        );
        assert_eq!(camel, dashed);
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

    #[test]
    fn no_temp_file_is_left_behind() {
        let f = Fixture::new(r#"{"settings":{}}"#);
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
        assert!(
            guard(r#"{"settings":{"jvm-args":["-Xmx4G"]}}"#).is_err(),
            "non-string key"
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
        assert!(
            guard(r#"{"settings":{"jvm-args":"-Xmx4G","jvmArgs":"-Xmx8G"}}"#).is_ok(),
            "L1: divergence is the NORMAL field state - Lunar's UI writes jvmArgs \
             while jvm-args keeps what Cobblify last wrote; jvmArgs wins"
        );
        assert!(guard(r#"{"settings":{"jvm-args":"","jvmArgs":""}}"#).is_ok());
        assert!(
            guard(r#"{"settings":{}}"#).is_ok(),
            "fresh install: no jvm keys yet - OBSERVED on real Windows 2026-08-13"
        );
        assert!(
            guard(r#"{"settings":{"jvm-args":null,"jvmArgs":null}}"#).is_ok(),
            "null keys read as absent"
        );
    }

    // Uninstall (R3). `unregister` is the only writer that REMOVES a token,
    // and it must be as conservative about the user's file as `apply` is.

    /// Code review round 1, I1: `uninstall_lunar` matches the jar names caselessly, so the
    /// argument that names them must be stripped caselessly too - or an upper-cased alias
    /// keeps pointing Lunar at a jar that is gone.
    #[test]
    fn an_upper_cased_weave_agent_path_is_recognised() {
        // The path separator is the host's: a backslash is a file-name byte on Unix.
        #[cfg(windows)]
        let alias = "-javaagent:C:\\Users\\Alice\\.WEAVE\\WEAVE-LOADER-AGENT-1.3.3.JAR";
        #[cfg(not(windows))]
        let alias = "-javaagent:/Users/alice/.WEAVE/WEAVE-LOADER-AGENT-1.3.3.JAR";
        assert!(is_weave_javaagent(alias));
        assert!(is_weave_javaagent("-javaagent:/Users/t/.Weave/weave-loader-agent-1.2.0.jar"));
        assert!(!is_weave_javaagent("-javaagent:/opt/other/Profiler.jar"));

        // Inside the JSON fixture a backslash must be escaped; the parsed value is `alias`.
        let alias_json = alias.replace('\\', "\\\\");
        let f = Fixture::new(&format!(
            r#"{{"settings":{{"jvm-args":"-Xmx4G {alias_json}","jvmArgs":"-Xmx4G {alias_json}"}}}}"#
        ));
        unregister(&f.json).unwrap();
        let (dashed, camel) = f.jvm_args();
        assert_eq!(dashed, "-Xmx4G");
        assert_eq!(camel, "-Xmx4G");

        // And setup replaces it instead of adding a second agent beside it.
        let f = Fixture::new(&format!(
            r#"{{"settings":{{"jvm-args":"-Xmx4G {alias_json}","jvmArgs":"-Xmx4G {alias_json}"}}}}"#
        ));
        apply(&f.json, Path::new(AGENT)).unwrap();
        assert_eq!(f.jvm_args().0, format!("-Xmx4G -javaagent:{AGENT}"));
    }

    #[test]
    fn unregister_strips_only_weave_agents() {
        let stale = "/Users/tester/.weave/Weave-Loader-Agent-1.2.0.jar";
        let f = Fixture::new(&format!(
            r#"{{"settings":{{"resolution":"1920x1080","jvm-args":"-Xmx4G -javaagent:/opt/other/profiler.jar -javaagent:{AGENT}","jvmArgs":"-Xmx4G -javaagent:/opt/other/profiler.jar -javaagent:{stale}"}}}}"#
        ));
        unregister(&f.json).unwrap();
        let (dashed, camel) = f.jvm_args();
        assert_eq!(dashed, "-Xmx4G -javaagent:/opt/other/profiler.jar");
        assert_eq!(camel, dashed);
        let value: Value = serde_json::from_str(&fs::read_to_string(&f.json).unwrap()).unwrap();
        assert_eq!(value["settings"]["resolution"], "1920x1080");
    }

    #[test]
    fn unregister_leaves_an_absent_key_absent() {
        let f = Fixture::new(&format!(
            r#"{{"settings":{{"jvmArgs":"-Xmx4G -javaagent:{AGENT}"}}}}"#
        ));
        unregister(&f.json).unwrap();
        let value: Value = serde_json::from_str(&fs::read_to_string(&f.json).unwrap()).unwrap();
        assert_eq!(value["settings"]["jvmArgs"], "-Xmx4G");
        assert!(
            value["settings"].get("jvm-args").is_none(),
            "uninstall must not invent a key Lunar never had"
        );
    }

    #[test]
    fn unregister_on_missing_file_is_ok() {
        let dir = tempfile::tempdir().unwrap();
        assert!(unregister(&dir.path().join("launcher.json")).is_ok());
        assert!(!dir.path().join("launcher.json").exists());
    }

    #[test]
    fn unregister_refuses_malformed_json() {
        let broken = "{ this is not json";
        let f = Fixture::new(broken);
        let err = unregister(&f.json).unwrap_err();
        assert!(err.contains("is not valid JSON"), "{err}");
        assert_eq!(fs::read_to_string(&f.json).unwrap(), broken);
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
