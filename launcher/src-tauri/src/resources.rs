//! Verification of the jars bundled into the .app against the injected manifest.
//!
//! `tools/package-owner-bundle.sh` writes `manifest.json` next to the jars when it injects
//! the token-baked build (Phase 4). A development build has no manifest and no jars; that is
//! reported as a normal error state, never a panic.

use std::fs;
use std::path::{Path, PathBuf};

use serde::Deserialize;
use sha2::{Digest, Sha256};

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    pub mod_jar: String,
    pub agent_jar: String,
    pub mod_version: String,
    pub mod_sha256: String,
    pub agent_sha256: String,
}

#[derive(Debug)]
pub struct Resources {
    pub manifest: Manifest,
    pub mod_jar: PathBuf,
    pub agent_jar: PathBuf,
}

/// What a friend is told when the bundled build is missing, incomplete or tampered with.
/// Every such failure has the same remedy for them - get a clean copy - and none of the
/// internal detail is anything they can act on. A debug build has no non-test reader for
/// it, which is the whole point of the split.
#[cfg_attr(debug_assertions, allow(dead_code))]
const INCOMPLETE_COPY: &str = "This copy is incomplete. Re-download Cobblify.";

/// Debug builds keep the exact path and OS error: that detail is the whole value of the
/// message to whoever is developing or packaging the app.
#[cfg(debug_assertions)]
fn bundle_error(detail: String) -> String {
    detail
}

/// Release builds - the only builds a friend ever runs - get the actionable sentence
/// instead. The detail would name a path on the owner's machine and ask them to "fix" it.
#[cfg(not(debug_assertions))]
fn bundle_error(_detail: String) -> String {
    INCOMPLETE_COPY.to_string()
}

/// Fails closed: a missing manifest, a missing jar, an unexpected extra jar or a hash
/// mismatch all return an error rather than installing anything.
pub fn verify(dir: &Path) -> Result<Resources, String> {
    verify_detailed(dir).map_err(bundle_error)
}

fn verify_detailed(dir: &Path) -> Result<Resources, String> {
    let manifest_path = dir.join("manifest.json");
    let raw = fs::read(&manifest_path).map_err(|e| {
        format!(
            "No bundled Cobblify build found ({}: {e}).",
            manifest_path.display()
        )
    })?;
    let manifest: Manifest = serde_json::from_slice(&raw)
        .map_err(|e| format!("{} is not a valid manifest: {e}", manifest_path.display()))?;

    let mod_jar = dir.join(&manifest.mod_jar);
    let agent_jar = dir.join(&manifest.agent_jar);
    check_hash(&mod_jar, &manifest.mod_sha256)?;
    check_hash(&agent_jar, &manifest.agent_sha256)?;
    reject_unexpected_jars(dir, &manifest)?;

    Ok(Resources {
        manifest,
        mod_jar,
        agent_jar,
    })
}

pub fn sha256_file(path: &Path) -> Result<String, String> {
    let bytes = fs::read(path).map_err(|e| format!("Cannot read {}: {e}", path.display()))?;
    Ok(hex(&Sha256::digest(&bytes)))
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().fold(String::new(), |mut acc, b| {
        use std::fmt::Write;
        let _ = write!(acc, "{b:02x}");
        acc
    })
}

fn check_hash(path: &Path, expected: &str) -> Result<(), String> {
    let actual = sha256_file(path)?;
    if actual.eq_ignore_ascii_case(expected) {
        Ok(())
    } else {
        Err(format!(
            "{} does not match the manifest (expected {expected}, found {actual}).",
            path.display()
        ))
    }
}

fn reject_unexpected_jars(dir: &Path, manifest: &Manifest) -> Result<(), String> {
    let entries =
        fs::read_dir(dir).map_err(|e| format!("Cannot read {}: {e}", dir.display()))?;
    for entry in entries {
        let entry = entry.map_err(|e| format!("Cannot read {}: {e}", dir.display()))?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if name.ends_with(".jar") && name != manifest.mod_jar && name != manifest.agent_jar {
            return Err(format!(
                "Unexpected jar {name} in the bundled resources. Refusing to install."
            ));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write_bundle(dir: &Path, mod_bytes: &[u8], agent_bytes: &[u8]) {
        fs::write(dir.join("Cobblify-Lunar-0.8.1.jar"), mod_bytes).unwrap();
        fs::write(dir.join("Weave-Loader-Agent-1.3.3.jar"), agent_bytes).unwrap();
        let manifest = format!(
            r#"{{"mod_jar":"Cobblify-Lunar-0.8.1.jar","agent_jar":"Weave-Loader-Agent-1.3.3.jar","mod_version":"0.8.1","mod_sha256":"{}","agent_sha256":"{}"}}"#,
            hex(&Sha256::digest(mod_bytes)),
            hex(&Sha256::digest(agent_bytes))
        );
        fs::write(dir.join("manifest.json"), manifest).unwrap();
    }

    // The detail assertions below go through `verify_detailed`, because `verify` deliberately
    // discards that detail in a release build and `cargo test --release` would fail otherwise.

    #[test]
    fn missing_manifest_is_a_clean_error() {
        let dir = tempfile::tempdir().unwrap();
        let err = verify_detailed(dir.path()).unwrap_err();
        assert!(err.contains("No bundled Cobblify build found"), "{err}");
    }

    #[test]
    fn matching_bundle_verifies() {
        let dir = tempfile::tempdir().unwrap();
        write_bundle(dir.path(), b"mod jar bytes", b"agent jar bytes");
        let res = verify(dir.path()).unwrap();
        assert_eq!(res.manifest.mod_version, "0.8.1");
    }

    #[test]
    fn hash_mismatch_fails_closed() {
        let dir = tempfile::tempdir().unwrap();
        write_bundle(dir.path(), b"mod jar bytes", b"agent jar bytes");
        fs::write(dir.path().join("Cobblify-Lunar-0.8.1.jar"), b"tampered").unwrap();
        let err = verify_detailed(dir.path()).unwrap_err();
        assert!(err.contains("does not match the manifest"), "{err}");
    }

    #[test]
    fn extra_jar_fails_closed() {
        let dir = tempfile::tempdir().unwrap();
        write_bundle(dir.path(), b"mod jar bytes", b"agent jar bytes");
        fs::write(dir.path().join("Something-Else.jar"), b"x").unwrap();
        let err = verify_detailed(dir.path()).unwrap_err();
        assert!(err.contains("Unexpected jar"), "{err}");
    }

    #[test]
    fn missing_jar_fails_closed() {
        let dir = tempfile::tempdir().unwrap();
        write_bundle(dir.path(), b"mod jar bytes", b"agent jar bytes");
        fs::remove_file(dir.path().join("Weave-Loader-Agent-1.3.3.jar")).unwrap();
        let err = verify_detailed(dir.path()).unwrap_err();
        assert!(err.contains("Cannot read"), "{err}");
    }

    /// A friend must never be shown a path from the owner's machine, and a developer must
    /// never lose it. Written to pass under both `cargo test` and `cargo test --release`,
    /// so the release branch is actually exercised rather than argued about.
    #[test]
    fn a_release_build_shows_the_friend_facing_message_only() {
        let detail = "/Users/owner/Cobblify.app/Contents/Resources/resources/manifest.json: \
                      No such file or directory (os error 2)"
            .to_string();
        let shown = bundle_error(detail.clone());
        if cfg!(debug_assertions) {
            assert_eq!(shown, detail, "a debug build must keep the exact detail");
        } else {
            assert_eq!(shown, INCOMPLETE_COPY);
            assert!(!shown.contains('/'), "a release build must not leak a path: {shown}");
            assert!(!shown.contains("os error"), "{shown}");
        }
    }

    /// The whole point of the split: the error a friend actually reaches on a partial
    /// download carries no internal detail in a release build.
    #[test]
    fn a_missing_manifest_reaches_the_friend_facing_message() {
        let dir = tempfile::tempdir().unwrap();
        let err = verify(dir.path()).unwrap_err();
        if cfg!(debug_assertions) {
            assert!(err.contains("No bundled Cobblify build found"), "{err}");
        } else {
            assert_eq!(err, INCOMPLETE_COPY);
        }
    }
}
