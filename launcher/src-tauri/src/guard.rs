//! The version guard: the javaagent Cobblify registers in Lunar instead of the Weave loader.
//!
//! Lunar passes its JVM arguments to every game it starts, whatever the version, and Weave
//! stops a game whose version it does not support (on 26.x the JVM aborts while processing
//! the agent). The guard (`launcher/guard`, Java 8 bytecode) reads the game version and starts
//! Weave only on 1.8.9, so every other version starts as if Cobblify were not installed.
//!
//! Registered as `-javaagent:<weave dir>/Cobblify-Guard.jar=<Weave jar file name>`: the option
//! names the Weave jar beside it. The jar is embedded in the launcher and pinned by hash.
//!
//! On a version it does not start Weave for, the guard writes one line to
//! `<weave dir>/logs/cobblify-guard.log`; the launch watch reads it to tell "Lunar is not on
//! 1.8.9" from "Cobblify didn't load" (Weave writes nothing on those versions any more).

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

use crate::resources::sha256_file;

/// The guard's file name in `~/.weave`. Not a `Weave-Loader-Agent` name, so an older launcher
/// reads a guard registration as "not set up" rather than as its own Weave agent.
pub const FILE_NAME: &str = "Cobblify-Guard.jar";
/// Built by `launcher/guard/build.sh` (reproducible).
const JAR: &[u8] = include_bytes!("../../guard/Cobblify-Guard.jar");
const SHA256: &str = "0fa0d9e0da16b359bcbb210f115798e2a84b7f48fc39252b8d1500fc737450b8";
/// The guard's log, relative to the weave dir.
pub const LOG: &str = "logs/cobblify-guard.log";

const LINE: &str = "Cobblify guard: Minecraft ";
const NOT_STARTED: &str = ", Weave not started";

/// Whether a file name is the guard's (caseless, like every jar match in `install.rs`).
pub fn is_guard_name(name: &str) -> bool {
    name.eq_ignore_ascii_case(FILE_NAME)
}

/// Whether `<weave_dir>` holds exactly this launcher's guard.
pub fn is_current(weave_dir: &Path) -> bool {
    sha256_file(&weave_dir.join(FILE_NAME)).is_ok_and(|have| have.eq_ignore_ascii_case(SHA256))
}

/// Writes the guard into `<weave_dir>` unless it is already there byte for byte: written beside
/// the destination, flushed, hash-checked, then renamed into place (`install.rs`'s rule).
pub fn install(weave_dir: &Path) -> Result<PathBuf, String> {
    let dest = weave_dir.join(FILE_NAME);
    if is_current(weave_dir) {
        return Ok(dest);
    }
    fs::create_dir_all(weave_dir)
        .map_err(|e| format!("Cannot create {}: {e}", weave_dir.display()))?;
    let tmp = weave_dir.join(format!(".{FILE_NAME}.cobblify-tmp"));
    let result = (|| {
        let mut file =
            fs::File::create(&tmp).map_err(|e| format!("Cannot write {}: {e}", tmp.display()))?;
        file.write_all(JAR)
            .and_then(|()| file.sync_all())
            .map_err(|e| format!("Cannot write {}: {e}", tmp.display()))?;
        drop(file);
        let have = sha256_file(&tmp)?;
        if !have.eq_ignore_ascii_case(SHA256) {
            return Err(format!("{} did not verify after writing.", tmp.display()));
        }
        fs::rename(&tmp, &dest).map_err(|e| format!("Cannot write {}: {e}", dest.display()))
    })();
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result.map(|()| dest)
}

/// The guard's line says the game is a version other than 1.8.9, so Weave was not started.
/// An unreadable version ("unknown") is not a verdict.
pub fn says_other_version(log: &str) -> bool {
    log.lines().any(|line| {
        let Some(rest) = line.strip_prefix(LINE) else {
            return false;
        };
        let Some(version) = rest.strip_suffix(NOT_STARTED) else {
            return false;
        };
        !version.is_empty() && version != "unknown" && version != "1.8.9"
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_embedded_jar_matches_its_pin() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("g.jar"), JAR).unwrap();
        assert_eq!(sha256_file(&dir.path().join("g.jar")).unwrap(), SHA256);
    }

    #[test]
    fn install_writes_once_and_replaces_a_changed_copy() {
        let dir = tempfile::tempdir().unwrap();
        let weave = dir.path().join(".weave");
        assert!(!is_current(&weave));
        let path = install(&weave).unwrap();
        assert_eq!(path, weave.join(FILE_NAME));
        assert_eq!(fs::read(&path).unwrap(), JAR);
        assert!(is_current(&weave));
        fs::write(&path, b"tampered").unwrap();
        assert!(!is_current(&weave));
        install(&weave).unwrap();
        assert!(is_current(&weave));
        let left: Vec<_> = fs::read_dir(&weave).unwrap().map(|e| e.unwrap().file_name()).collect();
        assert_eq!(left, vec![std::ffi::OsString::from(FILE_NAME)], "no temp file left");
    }

    #[test]
    fn only_a_named_other_version_is_a_wrong_version() {
        assert!(says_other_version("Cobblify guard: Minecraft 26.3, Weave not started\n"));
        assert!(says_other_version("Cobblify guard: Minecraft 1.21.11, Weave not started"));
        assert!(!says_other_version("Cobblify guard: Minecraft unknown, Weave not started"));
        assert!(!says_other_version("Cobblify guard: Minecraft 1.8.9, starting Weave"));
        assert!(!says_other_version("Cobblify guard: Minecraft 1.8.9, Weave jar missing"));
        assert!(!says_other_version(
            "Cobblify guard: Minecraft 26.3, Weave not started (guard error)"
        ));
        assert!(!says_other_version(""));
    }

    #[test]
    fn the_name_match_is_caseless_and_exact() {
        assert!(is_guard_name("Cobblify-Guard.jar"));
        assert!(is_guard_name("COBBLIFY-GUARD.JAR"));
        assert!(!is_guard_name("Cobblify-Guard.jar.bak"));
    }
}
