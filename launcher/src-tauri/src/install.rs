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
//! do work BETWEEN the two - both Lunar and Forge delete the old releases a new jar
//! supersedes after staging succeeds but before the rename, so a failed stage can never
//! cost the user a file. `Ok(None)` remains WINDOWS-ONLY, because the non-Windows path
//! deliberately stages even over a hash-equal destination so a tampered source is caught
//! (see `corrupt_source_leaves_the_previous_jar_intact`).

use std::fs;
use std::path::{Path, PathBuf};

use crate::resources::{sha256_file, LunarJars};

const MOD_JAR_PREFIX: &str = "Cobblify-Lunar-";
const AGENT_JAR_PREFIX: &str = "Weave-Loader-Agent-";

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
    /// `Cobblify-Lunar-*` entries left in `~/.weave/mods/` beside ours: a name that is not a
    /// plain release (a `-dev` or hand-renamed build), a symlink, or an old release that could
    /// not be deleted. Never touched. The UI blocks on them.
    pub conflicts: Vec<PathBuf>,
}

/// Installs both jars and deletes the releases ours replaces.
///
/// An update installs `Cobblify-Lunar-<new>.jar` beside `Cobblify-Lunar-<old>.jar`, and Weave
/// loads every jar in `mods/`, so the old one has to go. Only plain release names are deleted.
/// The order matches `forge::install`: the new jar is staged and hash-checked first, so a
/// corrupt bundle deletes nothing, and old copies go before the commit, so on a case-folding
/// volume a case variant of our own name is cleared rather than left as a second spelling.
pub fn install_lunar(jars: &LunarJars, weave_dir: &Path) -> Result<Installed, String> {
    let mods_dir = weave_dir.join("mods");
    fs::create_dir_all(&mods_dir)
        .map_err(|e| format!("Cannot create {}: {e}", mods_dir.display()))?;

    let agent_path = weave_dir.join(&jars.agent_name);
    install_jar(&jars.agent_src, &agent_path, &jars.agent_sha256)?;

    let (stale, mut conflicts) = mod_peers(&mods_dir, &jars.mod_name)?;
    let mod_path = mods_dir.join(&jars.mod_name);
    let staged = stage_verified(&jars.mod_src, &mod_path, &jars.mod_sha256)?;
    for path in stale {
        match fs::remove_file(&path) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            // Still loadable beside ours, so the user has to see it.
            Err(_) => conflicts.push(path),
        }
    }
    if let Some(staged) = staged {
        commit(staged)?;
    }
    conflicts.sort();
    Ok(Installed {
        agent_path,
        conflicts,
    })
}

/// Splits the `Cobblify-Lunar-*` entries beside our destination into old releases to delete
/// and everything else to report. Our own entry and directories are skipped.
fn mod_peers(mods_dir: &Path, ours: &str) -> Result<(Vec<PathBuf>, Vec<PathBuf>), String> {
    let entries =
        fs::read_dir(mods_dir).map_err(|e| format!("Cannot read {}: {e}", mods_dir.display()))?;
    let mut stale = Vec::new();
    let mut conflicts = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|e| format!("Cannot read {}: {e}", mods_dir.display()))?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if !is_our_jar_name(&name, MOD_JAR_PREFIX) || is_destination_name(&name, ours) {
            continue;
        }
        let path = entry.path();
        let kind = fs::symlink_metadata(&path)
            .map_err(|e| format!("Cannot read {}: {e}", path.display()))?
            .file_type();
        if kind.is_dir() {
            continue;
        }
        // A symlink may point anywhere, so it is reported, never deleted.
        if kind.is_file() && is_release_name(&name) {
            stale.push(path);
        } else {
            conflicts.push(path);
        }
    }
    Ok((stale, conflicts))
}

/// Removes what `install_lunar` put on disk, and nothing else (R3).
///
/// Only two name shapes are deleted - the Weave loader agent in `<weave>` and
/// Cobblify's mod jar in `<weave>/mods` - because Weave is a general-purpose
/// loader: a user may well have other mods in that directory, and an
/// uninstaller that emptied it would be taking files it never wrote. The two
/// directories are then removed if and only if they are empty, so a machine
/// that only ever had `~/.weave` because of Cobblify is left clean while
/// anybody else's setup is left intact.
///
/// A missing directory is success: there is nothing of ours to remove.
pub fn uninstall_lunar(weave_dir: &Path) -> Result<(), String> {
    if !weave_dir.exists() {
        return Ok(());
    }
    remove_our_jars(weave_dir, AGENT_JAR_PREFIX)?;

    let mods_dir = weave_dir.join("mods");
    if mods_dir.exists() {
        remove_our_jars(&mods_dir, MOD_JAR_PREFIX)?;
        // Both of these fail, harmlessly, on a directory that still holds
        // someone else's files - which is exactly the wanted behaviour.
        let _ = fs::remove_dir(&mods_dir);
    }
    let _ = fs::remove_dir(weave_dir);
    Ok(())
}

fn remove_our_jars(dir: &Path, prefix: &str) -> Result<(), String> {
    let entries = fs::read_dir(dir).map_err(|e| format!("Cannot read {}: {e}", dir.display()))?;
    for entry in entries {
        let entry = entry.map_err(|e| format!("Cannot read {}: {e}", dir.display()))?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if is_our_jar_name(&name, prefix) {
            fs::remove_file(entry.path())
                .map_err(|e| format!("Cannot remove {}: {e}", entry.path().display()))?;
        }
    }
    Ok(())
}

/// Caseless for the same reason `is_conflicting_name` is on Windows: NTFS
/// preserves case but ignores it, so a jar Weave loads as ours can be spelled
/// differently on disk than we wrote it. Jar names are ASCII by construction,
/// so ASCII folding is exact.
fn is_our_jar_name(name: &str, prefix: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    lower.starts_with(&prefix.to_ascii_lowercase()) && lower.ends_with(".jar")
}

/// `Cobblify-Lunar-<major>.<minor>.<patch>.jar`, in any case - the only shape a release
/// ships in, and so the only shape deleted as superseded.
fn is_release_name(name: &str) -> bool {
    name.to_ascii_lowercase()
        .strip_prefix(&MOD_JAR_PREFIX.to_ascii_lowercase())
        .and_then(|rest| rest.strip_suffix(".jar"))
        .is_some_and(is_semver)
}

pub(crate) fn is_semver(s: &str) -> bool {
    let parts: Vec<&str> = s.split('.').collect();
    parts.len() == 3
        && parts
            .iter()
            .all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()))
}

/// Whether a directory entry is our destination. Identity is per-platform: NTFS ignores
/// case, so on Windows a case-only spelling of our name is the destination itself. (A
/// per-directory case-sensitive NTFS layout can break that identity - accepted residual,
/// PLAN Phase 3.) Elsewhere only the exact name is; a case variant on a case-folding APFS
/// volume is then deleted as an old release before the commit writes ours. Jar names are
/// ASCII by construction, so ASCII folding is exact.
#[cfg(not(windows))]
fn is_destination_name(name: &str, ours: &str) -> bool {
    name == ours
}

#[cfg(windows)]
fn is_destination_name(name: &str, ours: &str) -> bool {
    name.eq_ignore_ascii_case(ours)
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

    /// R3. The uninstaller runs against a directory that is not ours: Weave
    /// is a general-purpose loader and a user may run other mods through it.
    #[test]
    fn uninstall_lunar_removes_only_our_jars() {
        let weave = tempfile::tempdir().unwrap();
        let mods = weave.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        fs::write(weave.path().join("Weave-Loader-Agent-1.3.3.jar"), b"agent").unwrap();
        fs::write(mods.join("Cobblify-Lunar-0.8.1.jar"), b"mod").unwrap();
        fs::write(mods.join("Other.jar"), b"someone else's mod").unwrap();
        fs::write(weave.path().join("weave.log"), b"log").unwrap();

        uninstall_lunar(weave.path()).unwrap();

        assert!(!weave.path().join("Weave-Loader-Agent-1.3.3.jar").exists());
        assert!(!mods.join("Cobblify-Lunar-0.8.1.jar").exists());
        assert!(mods.join("Other.jar").exists(), "a user's mod must survive");
        assert!(weave.path().join("weave.log").exists(), "so must Weave's own files");
        assert!(mods.exists(), "a mods directory with anything left in it stays");
    }

    /// A `.weave` that held nothing but our two jars is removed entirely -
    /// the point of the uninstall is to leave no trace on a machine that
    /// only ever had Weave because of Cobblify.
    #[test]
    fn uninstall_lunar_removes_an_emptied_weave_directory_and_tolerates_a_missing_one() {
        let base = tempfile::tempdir().unwrap();
        let weave = base.path().join(".weave");
        fs::create_dir_all(weave.join("mods")).unwrap();
        fs::write(weave.join("Weave-Loader-Agent-1.3.3.jar"), b"agent").unwrap();
        fs::write(weave.join("mods/Cobblify-Lunar-0.8.1.jar"), b"mod").unwrap();

        uninstall_lunar(&weave).unwrap();
        assert!(!weave.exists());

        uninstall_lunar(&base.path().join("nothing-here")).unwrap();
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

    /// A case-only spelling of the current name is the destination itself on NTFS.
    #[cfg(windows)]
    #[test]
    fn windows_destination_identity_is_ascii_caseless() {
        const OURS: &str = "Cobblify-Lunar-0.8.1.jar";
        assert!(is_destination_name("COBBLIFY-Lunar-0.8.1.JAR", OURS));
        assert!(!is_destination_name("Cobblify-Lunar-0.7.0.jar", OURS));
    }

    /// Weave loads a jar however its name is cased, so release names are matched caselessly.
    #[test]
    fn release_names_are_caseless_and_strict() {
        assert!(is_release_name("Cobblify-Lunar-0.7.0.jar"));
        assert!(is_release_name("cobblify-lunar-10.20.30.JAR"));
        assert!(!is_release_name("Cobblify-Lunar-0.7.0-dev.jar"));
        assert!(!is_release_name("Cobblify-Lunar-0.7.jar"));
        assert!(!is_release_name("Cobblify-Lunar-.jar"));
        assert!(!is_release_name("Cobblify-Lunar-mine.jar"));
        assert!(!is_release_name("Weave-Loader-Agent-1.3.3.jar"));
    }

    fn entries(dir: &Path) -> Vec<String> {
        let mut names: Vec<String> = fs::read_dir(dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        names.sort();
        names
    }

    /// The auto-update bug: the new release landed beside the old one, and setup blocked on
    /// "Conflicting jars" until the user deleted the old jar by hand.
    #[test]
    fn an_update_deletes_the_releases_it_replaces() {
        let src = tempfile::tempdir().unwrap();
        let weave = tempfile::tempdir().unwrap();
        let res = bundle(src.path(), b"mod", b"agent");
        let mods = weave.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        fs::write(mods.join("Cobblify-Lunar-0.8.0.jar"), b"previous release").unwrap();
        fs::write(mods.join("cobblify-lunar-0.7.0.JAR"), b"an older one").unwrap();
        fs::write(mods.join("SomeOtherMod.jar"), b"unrelated").unwrap();

        let installed = install_lunar(&res, weave.path()).unwrap();

        assert!(installed.conflicts.is_empty(), "{:?}", installed.conflicts);
        assert_eq!(fs::read(mods.join("Cobblify-Lunar-0.8.1.jar")).unwrap(), b"mod");
        assert_eq!(
            entries(&mods),
            vec!["Cobblify-Lunar-0.8.1.jar", "SomeOtherMod.jar"]
        );
    }

    /// Only plain release names are ours to delete. A `-dev` or hand-renamed build may be
    /// someone's own, so it is reported and left where it is.
    #[test]
    fn a_cobblify_jar_that_is_not_a_release_is_reported_not_deleted() {
        let src = tempfile::tempdir().unwrap();
        let weave = tempfile::tempdir().unwrap();
        let res = bundle(src.path(), b"mod", b"agent");
        let mods = weave.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        let dev = mods.join("Cobblify-Lunar-0.8.1-dev.jar");
        let renamed = mods.join("Cobblify-Lunar-mine.jar");
        fs::write(&dev, b"dev build").unwrap();
        fs::write(&renamed, b"friend build").unwrap();
        fs::write(mods.join("Cobblify-Lunar-0.8.0.jar"), b"previous release").unwrap();

        let installed = install_lunar(&res, weave.path()).unwrap();

        assert_eq!(installed.conflicts, vec![dev.clone(), renamed.clone()]);
        assert_eq!(fs::read(&dev).unwrap(), b"dev build");
        assert_eq!(fs::read(&renamed).unwrap(), b"friend build");
        assert!(
            !mods.join("Cobblify-Lunar-0.8.0.jar").exists(),
            "the old release still goes"
        );
        assert_eq!(fs::read(mods.join("Cobblify-Lunar-0.8.1.jar")).unwrap(), b"mod");
    }

    /// A symlink may point anywhere, so it is reported and never deleted - the same rule
    /// `forge::install` applies.
    #[cfg(unix)]
    #[test]
    fn a_symlinked_release_is_reported_not_deleted() {
        let src = tempfile::tempdir().unwrap();
        let weave = tempfile::tempdir().unwrap();
        let elsewhere = tempfile::tempdir().unwrap();
        let res = bundle(src.path(), b"mod", b"agent");
        let mods = weave.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        let target = elsewhere.path().join("Cobblify-Lunar-0.8.0.jar");
        fs::write(&target, b"kept elsewhere").unwrap();
        let link = mods.join("Cobblify-Lunar-0.8.0.jar");
        std::os::unix::fs::symlink(&target, &link).unwrap();

        let installed = install_lunar(&res, weave.path()).unwrap();

        assert_eq!(installed.conflicts, vec![link.clone()]);
        assert!(fs::symlink_metadata(&link).is_ok(), "the link stays");
        assert_eq!(fs::read(&target).unwrap(), b"kept elsewhere");
    }

    /// A bundle that fails its hash check must not cost the user the jar they already have.
    #[test]
    fn a_corrupt_bundle_deletes_nothing() {
        let src = tempfile::tempdir().unwrap();
        let weave = tempfile::tempdir().unwrap();
        let res = bundle(src.path(), b"new mod", b"agent");
        fs::write(&res.mod_src, b"tampered").unwrap();
        let mods = weave.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        let old = mods.join("Cobblify-Lunar-0.8.0.jar");
        fs::write(&old, b"previous release").unwrap();

        let err = install_lunar(&res, weave.path()).unwrap_err();

        assert!(err.contains("is corrupt"), "{err}");
        assert_eq!(fs::read(&old).unwrap(), b"previous release");
    }

    /// On a case-folding volume (APFS by default, NTFS) `cobblify-lunar-0.8.1.jar` IS our
    /// destination under another spelling; on a case-sensitive one it is a second jar Weave
    /// would load. Either way exactly one copy, ours, must be left.
    #[test]
    fn a_case_variant_of_our_name_leaves_exactly_our_jar() {
        let src = tempfile::tempdir().unwrap();
        let weave = tempfile::tempdir().unwrap();
        let res = bundle(src.path(), b"mod", b"agent");
        let mods = weave.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        fs::write(mods.join("cobblify-lunar-0.8.1.jar"), b"previous build").unwrap();

        let installed = install_lunar(&res, weave.path()).unwrap();

        assert!(installed.conflicts.is_empty(), "{:?}", installed.conflicts);
        let jars = entries(&mods);
        assert_eq!(jars.len(), 1, "{jars:?}");
        assert_eq!(fs::read(mods.join(&jars[0])).unwrap(), b"mod");
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
