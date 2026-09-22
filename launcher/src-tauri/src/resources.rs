//! Verification of the jars bundled into the app against the injected manifest.
//!
//! `tools/package-owner-bundle.sh` and `launcher/tools/package-windows-bundle.sh` write
//! `manifest.json` next to the jars when they inject the token-baked build. A development
//! build has no manifest and no jars; that is reported as a normal error state, never a panic.
//!
//! Verification is two layers, and the split is load-bearing (PLAN 2.2):
//!
//!   * GLOBAL, fail-closed, aborts everything: unreadable or unparseable manifest, an
//!     unknown key, a declared filename that is not a safe leaf, or a `.jar` in the
//!     directory that no manifest field declares.
//!   * PER TARGET, isolated: each target's own file-exists + SHA-256 check produces its
//!     own `Result`, so a corrupt Forge jar cannot suppress a healthy Lunar setup, or
//!     the other way round.
//!
//! Declaration and verification are DIFFERENT steps. A name is *declared* - and so
//! allow-listed by `reject_unexpected_jars` - when it is paired with a non-empty digest
//! and passes the leaf checks. Whether its bytes match that digest is a per-target
//! question. Conflating the two would turn a hash-failed Forge jar into an "unexpected"
//! jar and abort globally, destroying the isolation this module exists to provide.

use std::fs;
use std::path::{Component, Path, PathBuf};

use serde::Deserialize;
use sha2::{Digest, Sha256};

/// The wire shape, exactly as the packaging scripts write it. `forge_jar`/`forge_sha256`
/// are optional so a bundle built before Forge support still verifies; `deny_unknown_fields`
/// still rejects anything else.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct RawManifest {
    mod_jar: String,
    agent_jar: String,
    mod_version: String,
    mod_sha256: String,
    agent_sha256: String,
    /// Serde maps BOTH an absent key and an explicit `null` to `None`, and that is
    /// deliberate here: the two are equivalent, and policing the difference would buy
    /// nothing. What matters is the pair invariant enforced in `fold_forge`.
    #[serde(default)]
    forge_jar: Option<String>,
    #[serde(default)]
    forge_sha256: Option<String>,
}

/// A declared Forge resource: a safe leaf name paired with a non-empty digest. Its
/// existence says nothing about whether the bytes match.
#[derive(Debug, Clone)]
pub struct ForgeRes {
    pub jar: String,
    pub sha256: String,
}

#[derive(Debug)]
pub struct Manifest {
    pub mod_jar: String,
    pub agent_jar: String,
    pub mod_version: String,
    pub mod_sha256: String,
    pub agent_sha256: String,
    /// `None` = this bundle predates Forge support, or declares no Forge jar.
    pub forge: Option<ForgeRes>,
}

/// Lunar's two jars, self-contained so `install` needs nothing else.
#[derive(Debug, Clone)]
pub struct LunarJars {
    pub mod_src: PathBuf,
    pub mod_name: String,
    pub mod_sha256: String,
    pub agent_src: PathBuf,
    pub agent_name: String,
    pub agent_sha256: String,
}

#[derive(Debug, Clone)]
pub struct ForgeJar {
    pub src: PathBuf,
    pub name: String,
    pub sha256: String,
}

#[derive(Debug)]
pub struct Resources {
    pub manifest: Manifest,
    /// Lunar's own resource result. An `Err` here never blocks Forge.
    pub lunar: Result<LunarJars, String>,
    /// `None` = nothing declared. `Some(Err(..))` = declared but the bytes are wrong;
    /// Lunar is unaffected either way.
    pub forge: Option<Result<ForgeJar, String>>,
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

/// Windows keeps a reserved device basename reserved even when it is followed by one or
/// more extensions: `NUL.txt` and `NUL.tar.gz` both address the device, per Microsoft's
/// file-naming documentation. So the comparison is against the text before the FIRST
/// period, not the stem. Enforced on every build host, because a macOS-packaged bundle
/// must not be able to carry a name that becomes a device on a friend's Windows machine.
const RESERVED_DEVICES: [&str; 22] = [
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
    "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8",
    "LPT9",
];

/// A manifest filename must be ONE safe relative file name. This is a security boundary,
/// not packaging polish: `verify` joins these strings onto the resource directory and
/// opens the result.
///
/// Three independent checks, because none alone is sufficient:
///   1. structural - exactly one `Normal` component, so `a/b.jar`, `..\x.jar`, `/x.jar`,
///      `\\srv\s\x.jar` and the drive-relative `C:payload.jar` are all rejected (a bare
///      `..` or `.` is not `Normal` at all);
///   2. grammar - the same ASCII set `app-inject-lib.sh` already enforces when WRITING
///      the manifest, which admits no separator, no colon and no NTFS stream syntax;
///   3. device - see `RESERVED_DEVICES`.
fn safe_leaf(name: &str) -> Result<(), String> {
    let bad = |why: &str| Err(format!("manifest names {name}, which {why}."));

    let mut components = Path::new(name).components();
    match (components.next(), components.next()) {
        (Some(Component::Normal(_)), None) => {}
        _ => return bad("is not a single plain file name"),
    }

    let stem_ok = name.len() > 4
        && name.ends_with(".jar")
        && name[..name.len() - 4]
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'+' | b'-'))
        && !name[..name.len() - 4].is_empty();
    if !stem_ok {
        return bad("is not an ASCII .jar file name");
    }

    let head = name.split('.').next().unwrap_or("");
    if RESERVED_DEVICES
        .iter()
        .any(|d| head.eq_ignore_ascii_case(d))
    {
        return bad("is a reserved Windows device name");
    }
    Ok(())
}

/// The Forge pair is all-or-nothing. Half a record must never be usable: a name without a
/// digest would enter the allow list and never be hashed.
fn fold_forge(
    jar: Option<String>,
    sha256: Option<String>,
) -> Result<Option<ForgeRes>, String> {
    match (jar, sha256) {
        (None, None) => Ok(None),
        (Some(jar), Some(sha256)) => {
            if jar.trim().is_empty() || sha256.trim().is_empty() {
                return Err(
                    "manifest declares an empty forge_jar or forge_sha256.".to_string()
                );
            }
            Ok(Some(ForgeRes { jar, sha256 }))
        }
        (Some(_), None) => {
            Err("manifest declares forge_jar without forge_sha256.".to_string())
        }
        (None, Some(_)) => {
            Err("manifest declares forge_sha256 without forge_jar.".to_string())
        }
    }
}

/// Fails closed on anything GLOBAL: a missing manifest, an unknown key, an unsafe name,
/// a half-declared Forge pair, or an undeclared jar sitting in the directory. Per-target
/// byte failures are reported inside `Resources`, not here.
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
    let raw: RawManifest = serde_json::from_slice(&raw)
        .map_err(|e| format!("{} is not a valid manifest: {e}", manifest_path.display()))?;

    safe_leaf(&raw.mod_jar)?;
    safe_leaf(&raw.agent_jar)?;
    let forge = fold_forge(raw.forge_jar, raw.forge_sha256)?;
    if let Some(f) = &forge {
        safe_leaf(&f.jar)?;
    }

    let manifest = Manifest {
        mod_jar: raw.mod_jar,
        agent_jar: raw.agent_jar,
        mod_version: raw.mod_version,
        mod_sha256: raw.mod_sha256,
        agent_sha256: raw.agent_sha256,
        forge,
    };

    // Global: every jar present must be one this manifest DECLARES. Runs before the
    // per-target hashing, so a declared-but-corrupt jar is never mistaken for a stranger.
    reject_unexpected_jars(dir, &manifest)?;

    let lunar = verify_lunar(dir, &manifest);
    let forge = manifest.forge.as_ref().map(|f| verify_forge(dir, f));

    Ok(Resources {
        manifest,
        lunar,
        forge,
    })
}

fn verify_lunar(dir: &Path, m: &Manifest) -> Result<LunarJars, String> {
    let mod_src = dir.join(&m.mod_jar);
    let agent_src = dir.join(&m.agent_jar);
    check_hash(&mod_src, &m.mod_sha256)?;
    check_hash(&agent_src, &m.agent_sha256)?;
    Ok(LunarJars {
        mod_src,
        mod_name: m.mod_jar.clone(),
        mod_sha256: m.mod_sha256.clone(),
        agent_src,
        agent_name: m.agent_jar.clone(),
        agent_sha256: m.agent_sha256.clone(),
    })
}

fn verify_forge(dir: &Path, f: &ForgeRes) -> Result<ForgeJar, String> {
    let src = dir.join(&f.jar);
    check_hash(&src, &f.sha256)?;
    Ok(ForgeJar {
        src,
        name: f.jar.clone(),
        sha256: f.sha256.clone(),
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
    let entries = fs::read_dir(dir).map_err(|e| format!("Cannot read {}: {e}", dir.display()))?;
    for entry in entries {
        let entry = entry.map_err(|e| format!("Cannot read {}: {e}", dir.display()))?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if !name.ends_with(".jar") {
            continue;
        }
        let declared = name == manifest.mod_jar
            || name == manifest.agent_jar
            || manifest.forge.as_ref().is_some_and(|f| f.jar == name);
        if !declared {
            return Err(format!(
                "Unexpected jar {name} in the bundled resources. Refusing to install."
            ));
        }
    }
    Ok(())
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    const MOD: &str = "Cobblify-Lunar-0.8.1.jar";
    const AGENT: &str = "Weave-Loader-Agent-1.3.3.jar";
    const FORGE: &str = "Cobblify-1.8.9-forge-0.8.1.jar";

    fn sha(bytes: &[u8]) -> String {
        hex(&Sha256::digest(bytes))
    }

    /// A pre-Forge, five-key bundle.
    fn write_lunar_only(dir: &Path, mod_bytes: &[u8], agent_bytes: &[u8]) {
        fs::write(dir.join(MOD), mod_bytes).unwrap();
        fs::write(dir.join(AGENT), agent_bytes).unwrap();
        let manifest = format!(
            r#"{{"mod_jar":"{MOD}","agent_jar":"{AGENT}","mod_version":"0.8.1","mod_sha256":"{}","agent_sha256":"{}"}}"#,
            sha(mod_bytes),
            sha(agent_bytes)
        );
        fs::write(dir.join("manifest.json"), manifest).unwrap();
    }

    /// The seven-key bundle this change introduces. `pub(crate)` so `main.rs`'s
    /// setup tests can build a real, verifiable bundle instead of a hand-made
    /// `Resources`.
    pub(crate) fn write_bundle(dir: &Path, mod_bytes: &[u8], agent_bytes: &[u8], forge_bytes: &[u8]) {
        fs::write(dir.join(MOD), mod_bytes).unwrap();
        fs::write(dir.join(AGENT), agent_bytes).unwrap();
        fs::write(dir.join(FORGE), forge_bytes).unwrap();
        let manifest = format!(
            r#"{{"mod_jar":"{MOD}","agent_jar":"{AGENT}","mod_version":"0.8.1","mod_sha256":"{}","agent_sha256":"{}","forge_jar":"{FORGE}","forge_sha256":"{}"}}"#,
            sha(mod_bytes),
            sha(agent_bytes),
            sha(forge_bytes)
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
        write_bundle(dir.path(), b"mod", b"agent", b"forge");
        let res = verify(dir.path()).unwrap();
        assert_eq!(res.manifest.mod_version, "0.8.1");
        assert!(res.lunar.is_ok());
        assert!(res.forge.as_ref().unwrap().is_ok());
    }

    /// Acceptance criterion 6: a bundle built before Forge support still runs, with Forge
    /// simply unavailable rather than an error.
    #[test]
    fn a_five_key_bundle_still_verifies_with_forge_absent() {
        let dir = tempfile::tempdir().unwrap();
        write_lunar_only(dir.path(), b"mod", b"agent");
        let res = verify(dir.path()).unwrap();
        assert!(res.lunar.is_ok());
        assert!(res.forge.is_none(), "no forge jar was declared");
        assert!(res.manifest.forge.is_none());
    }

    #[test]
    fn hash_mismatch_fails_the_target_not_the_bundle() {
        let dir = tempfile::tempdir().unwrap();
        write_bundle(dir.path(), b"mod", b"agent", b"forge");
        fs::write(dir.path().join(MOD), b"tampered").unwrap();
        let res = verify_detailed(dir.path()).unwrap();
        assert!(res.lunar.is_err());
        assert!(res.lunar.unwrap_err().contains("does not match the manifest"));
    }

    /// PLAN 2.2 / round-1 B1: the two targets are isolated in BOTH directions.
    #[test]
    fn a_corrupt_forge_jar_leaves_lunar_installable() {
        let dir = tempfile::tempdir().unwrap();
        write_bundle(dir.path(), b"mod", b"agent", b"forge");
        fs::write(dir.path().join(FORGE), b"tampered").unwrap();
        let res = verify_detailed(dir.path()).unwrap();
        assert!(res.lunar.is_ok(), "a bad forge jar must not suppress lunar");
        assert!(res.forge.unwrap().is_err());
    }

    #[test]
    fn a_corrupt_lunar_jar_leaves_forge_installable() {
        let dir = tempfile::tempdir().unwrap();
        write_bundle(dir.path(), b"mod", b"agent", b"forge");
        fs::write(dir.path().join(AGENT), b"tampered").unwrap();
        let res = verify_detailed(dir.path()).unwrap();
        assert!(res.lunar.is_err());
        assert!(
            res.forge.unwrap().is_ok(),
            "a bad lunar agent must not suppress forge"
        );
    }

    /// Round-4 B2, the invariant that took four review rounds to state correctly: a
    /// DECLARED jar whose bytes are wrong is a per-target failure, never an "unexpected"
    /// jar. Getting this wrong would abort globally and destroy the isolation above.
    #[test]
    fn a_declared_but_corrupt_forge_jar_is_not_an_unexpected_jar() {
        let dir = tempfile::tempdir().unwrap();
        write_bundle(dir.path(), b"mod", b"agent", b"forge");
        fs::write(dir.path().join(FORGE), b"tampered").unwrap();
        // The whole bundle still verifies; only the forge target carries the error.
        let res = verify_detailed(dir.path()).expect("must not abort globally");
        assert!(res.forge.unwrap().is_err());
    }

    #[test]
    fn extra_jar_fails_closed() {
        let dir = tempfile::tempdir().unwrap();
        write_bundle(dir.path(), b"mod", b"agent", b"forge");
        fs::write(dir.path().join("Something-Else.jar"), b"x").unwrap();
        let err = verify_detailed(dir.path()).unwrap_err();
        assert!(err.contains("Unexpected jar"), "{err}");
    }

    /// The forge jar must stop being "unexpected" once it is declared - and must still be
    /// unexpected when it is not.
    #[test]
    fn an_undeclared_forge_jar_is_still_rejected() {
        let dir = tempfile::tempdir().unwrap();
        write_lunar_only(dir.path(), b"mod", b"agent");
        fs::write(dir.path().join(FORGE), b"forge").unwrap();
        let err = verify_detailed(dir.path()).unwrap_err();
        assert!(err.contains("Unexpected jar"), "{err}");
    }

    #[test]
    fn missing_jar_fails_its_own_target() {
        let dir = tempfile::tempdir().unwrap();
        write_bundle(dir.path(), b"mod", b"agent", b"forge");
        fs::remove_file(dir.path().join(AGENT)).unwrap();
        let res = verify_detailed(dir.path()).unwrap();
        assert!(res.lunar.unwrap_err().contains("Cannot read"));
        assert!(res.forge.unwrap().is_ok());
    }

    // ── the Forge pair invariant (round-1 B2, round-3 I1) ──────────────────────

    #[test]
    fn forge_pair_is_all_or_nothing() {
        assert!(fold_forge(None, None).unwrap().is_none());
        assert!(fold_forge(Some("a.jar".into()), Some("ff".into()))
            .unwrap()
            .is_some());
        assert!(fold_forge(Some("a.jar".into()), None).is_err());
        assert!(fold_forge(None, Some("ff".into())).is_err());
        assert!(fold_forge(Some(String::new()), Some("ff".into())).is_err());
        assert!(fold_forge(Some("a.jar".into()), Some(String::new())).is_err());
    }

    /// Round-3 I1: serde cannot tell an absent key from an explicit `null`, and this is
    /// the behaviour that follows - both mean "no Forge jar". A one-sided `null` is still
    /// a hard error, which is the property that actually matters.
    #[test]
    fn explicit_null_forge_keys_read_as_absent() {
        let dir = tempfile::tempdir().unwrap();
        write_lunar_only(dir.path(), b"mod", b"agent");
        let manifest = format!(
            r#"{{"mod_jar":"{MOD}","agent_jar":"{AGENT}","mod_version":"0.8.1","mod_sha256":"{}","agent_sha256":"{}","forge_jar":null,"forge_sha256":null}}"#,
            sha(b"mod"),
            sha(b"agent")
        );
        fs::write(dir.path().join("manifest.json"), manifest).unwrap();
        let res = verify_detailed(dir.path()).unwrap();
        assert!(res.forge.is_none());
    }

    #[test]
    fn a_one_sided_null_forge_pair_fails_closed() {
        let dir = tempfile::tempdir().unwrap();
        write_lunar_only(dir.path(), b"mod", b"agent");
        let manifest = format!(
            r#"{{"mod_jar":"{MOD}","agent_jar":"{AGENT}","mod_version":"0.8.1","mod_sha256":"{}","agent_sha256":"{}","forge_jar":null,"forge_sha256":"abc"}}"#,
            sha(b"mod"),
            sha(b"agent")
        );
        fs::write(dir.path().join("manifest.json"), manifest).unwrap();
        let err = verify_detailed(dir.path()).unwrap_err();
        assert!(err.contains("without forge_jar"), "{err}");
    }

    #[test]
    fn an_unknown_manifest_key_still_fails_closed() {
        let dir = tempfile::tempdir().unwrap();
        write_lunar_only(dir.path(), b"mod", b"agent");
        let manifest = format!(
            r#"{{"mod_jar":"{MOD}","agent_jar":"{AGENT}","mod_version":"0.8.1","mod_sha256":"{}","agent_sha256":"{}","surprise":"x"}}"#,
            sha(b"mod"),
            sha(b"agent")
        );
        fs::write(dir.path().join("manifest.json"), manifest).unwrap();
        let err = verify_detailed(dir.path()).unwrap_err();
        assert!(err.contains("not a valid manifest"), "{err}");
    }

    // ── safe leaf names (round-2 I3, round-4 B1) ───────────────────────────────

    /// These strings are joined onto the resource directory and opened, so this is a
    /// security boundary. Every rejected form here defeats one specific escape.
    #[test]
    fn safe_leaf_rejects_every_path_escape() {
        for bad in [
            "a/b.jar",
            "a\\b.jar",
            "../x.jar",
            "..\\x.jar",
            "/x.jar",
            "\\\\srv\\share\\x.jar",
            // Drive-relative: no separator and no "..", but Windows resolves it against
            // the drive's current directory. This is why the grammar check exists.
            "C:payload.jar",
            "",
            ".",
            "..",
            "x.txt",
            ".jar",
            "spa ce.jar",
            "quo\"te.jar",
        ] {
            assert!(safe_leaf(bad).is_err(), "must reject {bad:?}");
        }
    }

    /// Round-4 B1: a reserved device stays reserved behind ANY number of extensions, so
    /// the comparison is against the text before the FIRST period. Stripping only the
    /// last one would let `NUL.payload.jar` through.
    #[test]
    fn safe_leaf_rejects_windows_devices_including_compound_extensions() {
        for bad in [
            "NUL.jar",
            "nul.jar",
            "con.jar",
            "COM1.jar",
            "Lpt9.jar",
            "aux.jar",
            "PRN.jar",
            "NUL.payload.jar",
            "com1.extra.jar",
        ] {
            assert!(safe_leaf(bad).is_err(), "must reject {bad:?}");
        }
    }

    #[test]
    fn safe_leaf_accepts_the_names_we_actually_ship() {
        // Interior periods are normal for our own names - the device check must split at
        // the first period without rejecting these.
        for good in [
            "Cobblify-1.8.9-forge-0.9.0.jar",
            "Cobblify-Lunar-0.9.0.jar",
            "Weave-Loader-Agent-1.3.3.jar",
            "conform.jar", // starts with "con" but is not "CON"
            "nuls.jar",
        ] {
            assert!(safe_leaf(good).is_ok(), "must accept {good:?}");
        }
    }

    #[test]
    fn an_unsafe_manifest_name_fails_closed_before_any_read() {
        let dir = tempfile::tempdir().unwrap();
        write_lunar_only(dir.path(), b"mod", b"agent");
        let manifest = format!(
            r#"{{"mod_jar":"../escape.jar","agent_jar":"{AGENT}","mod_version":"0.8.1","mod_sha256":"{}","agent_sha256":"{}"}}"#,
            sha(b"mod"),
            sha(b"agent")
        );
        fs::write(dir.path().join("manifest.json"), manifest).unwrap();
        let err = verify_detailed(dir.path()).unwrap_err();
        assert!(err.contains("single plain file name"), "{err}");
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
            assert!(
                !shown.contains('/'),
                "a release build must not leak a path: {shown}"
            );
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
