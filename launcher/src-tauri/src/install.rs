//! Atomic, idempotent installation of a verified jar, and Lunar's use of it.
//!
//! Unconditional by design on macOS (original TASK.md expected outcome 4): there is no
//! detect-and-skip, so a stale or corrupt install self-heals. Writes go to a temporary file
//! in the SAME directory as the destination, are hash-checked, and only then `rename()`d
//! into place - a running game may be lazily reading classes out of the jar we are
//! replacing, and a truncating write would corrupt it (acceptance criterion 11).
//!
//! Windows exception (Windows-port PLAN Phase 3): `rename()` over a file the running game
//! holds open FAILS on Windows, so a destination whose hash already equals the manifest is
//! skipped - not a blind skip, a hash-verified identity. Any mismatch still takes the full
//! staging path, and a mismatched-but-locked jar still errors, correctly: the game must be
//! closed to update.
//!
//! The install is split into `stage_verified` + `commit` (Forge PLAN 2.6) so a caller can
//! do work BETWEEN the two - Forge quarantines a foreign jar sitting at its destination
//! name after staging succeeds but before the rename, so a failed stage can never cost the
//! user a file. Lunar calls the two back to back and behaves exactly as it always has;
//! in particular `Ok(None)` remains WINDOWS-ONLY, because the non-Windows path
//! deliberately stages even over a hash-equal destination so a tampered source is caught
//! (see `corrupt_source_leaves_the_previous_jar_intact`).

use std::fs;
use std::path::{Path, PathBuf};

use crate::resources::{sha256_file, LunarJars};

const MOD_JAR_PREFIX: &str = "Cobblify-Lunar-";

/// A jar copied into place beside its destination and hash-verified, not yet visible under
/// its real name. Dropping one without committing removes the temp file, which is what
/// preserves the old guarantee that a failed install leaves nothing behind.
#[derive(Debug)]
pub struct StagedJar {
    tmp: PathBuf,
    dest: PathBuf,
    committed: bool,
}

impl Drop for StagedJar {
    fn drop(&mut self) {
        if !self.committed {
            // Leave the previous known-good jar in place.
            let _ = fs::remove_file(&self.tmp);
        }
    }
}

fn tmp_for(dest: &Path) -> Result<PathBuf, String> {
    let name = dest
        .file_name()
        .ok_or_else(|| format!("{} has no file name", dest.display()))?
        .to_string_lossy()
        .into_owned();
    Ok(dest.with_file_name(format!(".{name}.cobblify-tmp")))
}

/// Copies `src` beside `dest`, hashes the COPY, and hands back a committable handle.
///
/// `Ok(None)` means no commit is needed because the destination is already byte-identical
/// to what we would write. That case is Windows-only, by design - see the module header.
/// It does NOT mean "there is nothing left to do": Forge still has to scan for stale peers
/// in that situation, which is the single most likely real upgrade (PLAN 2.6, round-3 B2).
pub fn stage_verified(
    src: &Path,
    dest: &Path,
    expected_sha256: &str,
) -> Result<Option<StagedJar>, String> {
    let tmp = tmp_for(dest)?;

    // Windows: skip on hash-verified identity (see module doc). The crash-
    // leftover temp is cleared FIRST, and only NotFound is ignored - any
    // other failure (locked, read-only) must surface, or the leftover would
    // become permanent-but-silent behind the skip.
    #[cfg(windows)]
    {
        match fs::remove_file(&tmp) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => {
                return Err(format!(
                    "Cannot clear the stale temp file {}: {e}. Delete it, then reopen Cobblify.",
                    tmp.display()
                ))
            }
        }
        if let Ok(actual) = sha256_file(dest) {
            if actual.eq_ignore_ascii_case(expected_sha256) {
                return Ok(None);
            }
        }
    }

    let staged = StagedJar {
        tmp: tmp.clone(),
        dest: dest.to_path_buf(),
        committed: false,
    };

    fs::copy(src, &tmp)
        .map_err(|e| format!("Cannot stage {} at {}: {e}", src.display(), tmp.display()))?;
    let actual = sha256_file(&tmp)?;
    if !actual.eq_ignore_ascii_case(expected_sha256) {
        let name = dest.file_name().unwrap_or_default().to_string_lossy();
        return Err(format!(
            "Staged copy of {name} is corrupt (expected {expected_sha256}, found {actual})."
        ));
    }
    Ok(Some(staged))
}

/// The atomic rename. On failure the `Drop` above clears the temp file.
pub fn commit(mut staged: StagedJar) -> Result<(), String> {
    fs::rename(&staged.tmp, &staged.dest)
        .map_err(|e| format!("Cannot install {}: {e}", staged.dest.display()))?;
    staged.committed = true;
    Ok(())
}

/// Stage and commit in one step - the shape every caller wanted before Forge needed to
/// interleave work between the halves.
pub fn install_jar(src: &Path, dest: &Path, expected_sha256: &str) -> Result<(), String> {
    match stage_verified(src, dest, expected_sha256)? {
        Some(staged) => commit(staged),
        None => Ok(()),
    }
}

#[derive(Debug)]
pub struct Installed {
    /// Where the Weave agent now lives - this is the path registered in Lunar's jvm-args.
    pub agent_path: PathBuf,
    /// Other `Cobblify-Lunar-*.jar` files found in `~/.weave/mods/`. Never deleted: they may
    /// be the user's own build. The UI blocks on them.
    pub conflicts: Vec<PathBuf>,
}

pub fn install_lunar(jars: &LunarJars, weave_dir: &Path) -> Result<Installed, String> {
    let mods_dir = weave_dir.join("mods");
    fs::create_dir_all(&mods_dir)
        .map_err(|e| format!("Cannot create {}: {e}", mods_dir.display()))?;

    let agent_path = weave_dir.join(&jars.agent_name);
    install_jar(&jars.agent_src, &agent_path, &jars.agent_sha256)?;

    let mod_path = mods_dir.join(&jars.mod_name);
    install_jar(&jars.mod_src, &mod_path, &jars.mod_sha256)?;

    Ok(Installed {
        agent_path,
        conflicts: conflicting_mod_jars(&mods_dir, &jars.mod_name)?,
    })
}

fn conflicting_mod_jars(mods_dir: &Path, ours: &str) -> Result<Vec<PathBuf>, String> {
    let entries =
        fs::read_dir(mods_dir).map_err(|e| format!("Cannot read {}: {e}", mods_dir.display()))?;
    let mut conflicts = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|e| format!("Cannot read {}: {e}", mods_dir.display()))?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if is_conflicting_name(&name, ours) {
            conflicts.push(entry.path());
        }
    }
    conflicts.sort();
    Ok(conflicts)
}

/// Filename identity is per-platform. NTFS is case-insensitive, so on Windows
/// `cobblify-lunar-0.7.0.jar` and `Cobblify-Lunar-0.7.0.JAR` are Cobblify
/// jars Weave WILL load, and a case-only spelling of the current name is the
/// same file as the destination, not a conflict. (A per-directory
/// case-sensitive NTFS layout can break that identity - accepted residual,
/// PLAN Phase 3.) Jar names are ASCII by construction, so ASCII folding is
/// exact.
///
/// Forge does NOT reuse this rule - it needs caseless classification on both platforms
/// plus real file identity, because a missed duplicate there is a client that refuses to
/// boot rather than an unreported conflict. See `forge::stale_jars`.
#[cfg(not(windows))]
fn is_conflicting_name(name: &str, ours: &str) -> bool {
    name.starts_with(MOD_JAR_PREFIX) && name.ends_with(".jar") && name != ours
}

#[cfg(windows)]
fn is_conflicting_name(name: &str, ours: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    lower.starts_with(&MOD_JAR_PREFIX.to_ascii_lowercase())
        && lower.ends_with(".jar")
        && !name.eq_ignore_ascii_case(ours)
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::{Digest, Sha256};

    fn sha(bytes: &[u8]) -> String {
        Sha256::digest(bytes)
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect()
    }

    fn bundle(dir: &Path, mod_bytes: &[u8], agent_bytes: &[u8]) -> LunarJars {
        let mod_src = dir.join("Cobblify-Lunar-0.8.1.jar");
        let agent_src = dir.join("Weave-Loader-Agent-1.3.3.jar");
        fs::write(&mod_src, mod_bytes).unwrap();
        fs::write(&agent_src, agent_bytes).unwrap();
        LunarJars {
            mod_src,
            mod_name: "Cobblify-Lunar-0.8.1.jar".into(),
            mod_sha256: sha(mod_bytes),
            agent_src,
            agent_name: "Weave-Loader-Agent-1.3.3.jar".into(),
            agent_sha256: sha(agent_bytes),
        }
    }

    #[test]
    fn installs_both_jars_and_is_idempotent() {
        let src = tempfile::tempdir().unwrap();
        let weave = tempfile::tempdir().unwrap();
        let res = bundle(src.path(), b"mod", b"agent");

        let first = install_lunar(&res, weave.path()).unwrap();
        assert_eq!(
            first.agent_path,
            weave.path().join("Weave-Loader-Agent-1.3.3.jar")
        );
        install_lunar(&res, weave.path()).unwrap();

        assert_eq!(
            fs::read(weave.path().join("Weave-Loader-Agent-1.3.3.jar")).unwrap(),
            b"agent"
        );
        assert_eq!(
            fs::read(weave.path().join("mods/Cobblify-Lunar-0.8.1.jar")).unwrap(),
            b"mod"
        );
        // No temp files left behind.
        let leftovers: Vec<_> = fs::read_dir(weave.path().join("mods"))
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .filter(|n| n.contains("cobblify-tmp"))
            .collect();
        assert!(leftovers.is_empty(), "{leftovers:?}");
    }

    #[test]
    fn overwrites_a_stale_copy() {
        let src = tempfile::tempdir().unwrap();
        let weave = tempfile::tempdir().unwrap();
        let res = bundle(src.path(), b"new mod", b"new agent");
        fs::create_dir_all(weave.path().join("mods")).unwrap();
        fs::write(weave.path().join("mods/Cobblify-Lunar-0.8.1.jar"), b"stale").unwrap();

        install_lunar(&res, weave.path()).unwrap();
        assert_eq!(
            fs::read(weave.path().join("mods/Cobblify-Lunar-0.8.1.jar")).unwrap(),
            b"new mod"
        );
    }

    /// Mac semantics: the install is unconditional, so a hash-equal
    /// destination plus a tampered source must still stage, fail the hash,
    /// and leave the previous jar. On Windows the same input legitimately
    /// SKIPS (hash-verified identity); the Windows twin below covers the
    /// staging boundary with a non-matching destination instead.
    ///
    /// This is the regression that pins `Ok(None)` to Windows only (round-3 I2).
    #[cfg(not(windows))]
    #[test]
    fn corrupt_source_leaves_the_previous_jar_intact() {
        let src = tempfile::tempdir().unwrap();
        let weave = tempfile::tempdir().unwrap();
        let mut res = bundle(src.path(), b"mod", b"agent");
        install_lunar(&res, weave.path()).unwrap();

        // Simulate a bundle whose bytes no longer match the manifest.
        fs::write(&res.mod_src, b"tampered").unwrap();
        res.mod_sha256 = sha(b"mod");
        let err = install_lunar(&res, weave.path()).unwrap_err();
        assert!(err.contains("is corrupt"), "{err}");

        assert_eq!(
            fs::read(weave.path().join("mods/Cobblify-Lunar-0.8.1.jar")).unwrap(),
            b"mod"
        );
        assert!(!weave
            .path()
            .join("mods/.Cobblify-Lunar-0.8.1.jar.cobblify-tmp")
            .exists());
    }

    #[cfg(windows)]
    #[test]
    fn corrupt_source_with_a_stale_destination_still_errors_and_keeps_it() {
        let src = tempfile::tempdir().unwrap();
        let weave = tempfile::tempdir().unwrap();
        // Manifest expects "new mod" but the source bytes are tampered; the
        // stale destination cannot hash-match, so staging must run and fail.
        let mut res = bundle(src.path(), b"new mod", b"agent");
        fs::write(&res.mod_src, b"tampered").unwrap();
        res.mod_sha256 = sha(b"new mod");
        fs::create_dir_all(weave.path().join("mods")).unwrap();
        fs::write(weave.path().join("mods/Cobblify-Lunar-0.8.1.jar"), b"stale").unwrap();

        let err = install_lunar(&res, weave.path()).unwrap_err();
        assert!(err.contains("is corrupt"), "{err}");
        assert_eq!(
            fs::read(weave.path().join("mods/Cobblify-Lunar-0.8.1.jar")).unwrap(),
            b"stale"
        );
        assert!(!weave
            .path()
            .join("mods/.Cobblify-Lunar-0.8.1.jar.cobblify-tmp")
            .exists());
    }

    #[cfg(windows)]
    #[test]
    fn hash_equal_destination_is_skipped_untouched() {
        let src = tempfile::tempdir().unwrap();
        let weave = tempfile::tempdir().unwrap();
        let res = bundle(src.path(), b"mod", b"agent");
        install_lunar(&res, weave.path()).unwrap();

        let dest = weave.path().join("mods/Cobblify-Lunar-0.8.1.jar");
        let before = fs::metadata(&dest).unwrap().modified().unwrap();
        install_lunar(&res, weave.path()).unwrap();
        assert_eq!(fs::read(&dest).unwrap(), b"mod");
        assert_eq!(
            fs::metadata(&dest).unwrap().modified().unwrap(),
            before,
            "a hash-equal destination must not be rewritten"
        );
    }

    #[cfg(windows)]
    #[test]
    fn removable_crash_leftover_is_cleaned_on_the_skip_path() {
        let src = tempfile::tempdir().unwrap();
        let weave = tempfile::tempdir().unwrap();
        let res = bundle(src.path(), b"mod", b"agent");
        install_lunar(&res, weave.path()).unwrap();

        let tmp = weave
            .path()
            .join("mods/.Cobblify-Lunar-0.8.1.jar.cobblify-tmp");
        fs::write(&tmp, b"crash leftover").unwrap();
        install_lunar(&res, weave.path()).unwrap();
        assert!(!tmp.exists(), "the leftover must be cleared before the skip");
    }

    #[cfg(windows)]
    #[test]
    fn undeletable_crash_leftover_fails_loudly_not_silently() {
        use std::os::windows::fs::OpenOptionsExt;

        let src = tempfile::tempdir().unwrap();
        let weave = tempfile::tempdir().unwrap();
        let res = bundle(src.path(), b"mod", b"agent");
        install_lunar(&res, weave.path()).unwrap();

        let tmp = weave
            .path()
            .join("mods/.Cobblify-Lunar-0.8.1.jar.cobblify-tmp");
        fs::write(&tmp, b"crash leftover").unwrap();
        // A read-only attribute no longer blocks deletion (modern Rust uses
        // POSIX delete semantics on Windows 10+ and clears it - measured on
        // the CI runner), so simulate the REAL undeletable case: a handle
        // held open with no share access, like a file another process owns.
        let lock = fs::OpenOptions::new()
            .read(true)
            .share_mode(0)
            .open(&tmp)
            .unwrap();

        let err = install_lunar(&res, weave.path()).unwrap_err();
        assert!(err.contains("cobblify-tmp"), "{err}");
        drop(lock);
    }

    #[cfg(windows)]
    #[test]
    fn windows_conflict_identity_is_ascii_caseless() {
        const OURS: &str = "Cobblify-Lunar-0.8.1.jar";
        // Mixed-case prefix and extension are still Cobblify jars Weave loads.
        assert!(is_conflicting_name("cobblify-lunar-0.7.0.jar", OURS));
        assert!(is_conflicting_name("Cobblify-Lunar-0.7.0.JAR", OURS));
        // A case-only spelling of the current name is the destination itself.
        assert!(!is_conflicting_name("COBBLIFY-Lunar-0.8.1.JAR", OURS));
        assert!(!is_conflicting_name("SomeOtherMod.jar", OURS));
    }

    #[test]
    fn reports_other_cobblify_jars_without_deleting_them() {
        let src = tempfile::tempdir().unwrap();
        let weave = tempfile::tempdir().unwrap();
        let res = bundle(src.path(), b"mod", b"agent");
        fs::create_dir_all(weave.path().join("mods")).unwrap();
        let other = weave.path().join("mods/Cobblify-Lunar-0.7.0.jar");
        fs::write(&other, b"friend build").unwrap();
        fs::write(weave.path().join("mods/SomeOtherMod.jar"), b"unrelated").unwrap();

        let installed = install_lunar(&res, weave.path()).unwrap();
        assert_eq!(installed.conflicts, vec![other.clone()]);
        assert!(other.exists(), "conflicting jars must never be deleted");
    }

    // ── the split primitive itself (PLAN 2.6, round-3 I2) ──────────────────────

    /// A staged jar that is never committed must leave nothing behind - this is the
    /// guarantee the old combined function gave via its error branch, now carried by
    /// `Drop`.
    #[test]
    fn a_dropped_stage_removes_its_temp_file() {
        let src = tempfile::tempdir().unwrap();
        let dst = tempfile::tempdir().unwrap();
        let s = src.path().join("x.jar");
        fs::write(&s, b"payload").unwrap();
        let dest = dst.path().join("x.jar");

        let tmp = {
            let staged = stage_verified(&s, &dest, &sha(b"payload")).unwrap().unwrap();
            let tmp = staged.tmp.clone();
            assert!(tmp.exists(), "the stage must exist before the drop");
            tmp
        };
        assert!(!tmp.exists(), "dropping without committing must clean up");
        assert!(!dest.exists(), "and must not create the destination");
    }

    #[test]
    fn stage_then_commit_installs_and_clears_the_temp() {
        let src = tempfile::tempdir().unwrap();
        let dst = tempfile::tempdir().unwrap();
        let s = src.path().join("x.jar");
        fs::write(&s, b"payload").unwrap();
        let dest = dst.path().join("x.jar");

        let staged = stage_verified(&s, &dest, &sha(b"payload")).unwrap().unwrap();
        let tmp = staged.tmp.clone();
        commit(staged).unwrap();

        assert_eq!(fs::read(&dest).unwrap(), b"payload");
        assert!(!tmp.exists());
    }

    #[test]
    fn a_corrupt_source_never_yields_a_stage() {
        let src = tempfile::tempdir().unwrap();
        let dst = tempfile::tempdir().unwrap();
        let s = src.path().join("x.jar");
        fs::write(&s, b"tampered").unwrap();
        let dest = dst.path().join("x.jar");

        let err = stage_verified(&s, &dest, &sha(b"payload")).unwrap_err();
        assert!(err.contains("is corrupt"), "{err}");
        assert!(!dest.exists());
        assert!(!dst.path().join(".x.jar.cobblify-tmp").exists());
    }

    /// Round-3 I2: `Ok(None)` is Windows-only. Off Windows a hash-equal destination must
    /// still stage, which is what makes the tampered-source regression above possible.
    #[test]
    fn hash_equal_destination_staging_is_platform_specific() {
        let src = tempfile::tempdir().unwrap();
        let dst = tempfile::tempdir().unwrap();
        let s = src.path().join("x.jar");
        fs::write(&s, b"payload").unwrap();
        let dest = dst.path().join("x.jar");
        fs::write(&dest, b"payload").unwrap();

        let staged = stage_verified(&s, &dest, &sha(b"payload")).unwrap();
        if cfg!(windows) {
            assert!(staged.is_none(), "windows skips a hash-equal destination");
        } else {
            assert!(staged.is_some(), "other platforms always stage");
        }
    }
}
