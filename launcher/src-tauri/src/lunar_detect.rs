//! Lunar Client facts, read-only: installed, opened once, signed in.
//!
//! "Installed" is the app bundle, not `~/.lunarclient`: a Lunar that was installed but
//! never opened has no data directory yet, and an uninstalled Lunar can leave its data
//! behind. "Opened once" is Lunar's own `settings/launcher.json`, which its first run
//! creates. "Signed in" is `settings/game/accounts.json`.
//!
//! PRIVACY: `accounts.json` holds live Microsoft tokens. It is read ONLY through the
//! counting deserializer below, which keeps two facts - how many accounts there are and
//! whether an active account id is set - and never allocates, stores, returns or logs a
//! single value from the file (account ids, names and tokens are all skipped as
//! `IgnoredAny`). Nothing here may ever print the file or any part of it.
//!
//! Where: `/Applications`, then `~/Applications`, then - only when neither holds Lunar -
//! the bundle id `com.moonsworth.client` through Spotlight's LaunchServices metadata
//! (`/usr/bin/mdfind`, bounded by `LOOKUP_TIMEOUT`; any failure reads as nothing found).
//! A registration can be stale or not an install (the Trash, a mounted installer image or
//! another disk, a backup, an App Translocation copy), so only an existing `.app` bundle
//! outside those places counts. The running check derives its executable from whichever
//! bundle was found. The lookup is injectable (`find_app_with`, `launcher_exes_with`).

use std::ffi::OsStr;
use std::fmt;
use std::io::Read;
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use serde::de::{Deserializer, IgnoredAny, MapAccess, Visitor};
use serde::Deserialize;

/// The Electron main executable inside a Lunar bundle (helpers have their own binaries,
/// see `proc.rs`).
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
const BUNDLE_EXE: &str = "Contents/MacOS/Lunar Client";
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
const BUNDLE_NAME: &str = "Lunar Client.app";

/// Every place a Lunar app bundle is looked for, in order: the system Applications folder,
/// then the user's own.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn app_candidates(home: &Path) -> Vec<PathBuf> {
    vec![
        Path::new("/Applications").join(BUNDLE_NAME),
        home.join("Applications").join(BUNDLE_NAME),
    ]
}

/// The first candidate that is a bundle directory.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn find_app_in(candidates: &[PathBuf]) -> Option<PathBuf> {
    candidates.iter().find(|p| p.is_dir()).cloned()
}

/// Lunar's bundle identifier (`CFBundleIdentifier`).
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub const BUNDLE_ID: &str = "com.moonsworth.client";
/// The longest the bundle-id lookup may take; past it, nothing was found.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub(crate) const LOOKUP_TIMEOUT: Duration = Duration::from_secs(2);
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub(crate) const MDFIND: &str = "/usr/bin/mdfind";

/// The installed Lunar, if any. macOS: the app bundle.
#[cfg(target_os = "macos")]
pub fn find_app(home: &Path) -> Option<PathBuf> {
    find_app_with(home, &spotlight_lookup)
}

/// The installed bundle: a standard folder's first, else the first usable bundle the
/// bundle-id `lookup` names (run only when neither standard folder has one).
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn find_app_with(home: &Path, lookup: &dyn Fn() -> Vec<PathBuf>) -> Option<PathBuf> {
    find_app_among(&app_candidates(home), lookup)
}

/// Candidate locations are injectable so tests never inspect an installed app on the build host.
fn find_app_among(candidates: &[PathBuf], lookup: &dyn Fn() -> Vec<PathBuf>) -> Option<PathBuf> {
    find_app_in(candidates).or_else(|| registered_app(&lookup()))
}

/// The first registration that is a real install (see `usable_registration`) and exists as
/// a bundle directory.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
fn registered_app(paths: &[PathBuf]) -> Option<PathBuf> {
    paths
        .iter()
        .find(|p| usable_registration(p) && p.is_dir())
        .cloned()
}

/// Folder names whose contents are never an install: the Trash, Time Machine and local
/// snapshots, macOS's own bookkeeping, and Gatekeeper's App Translocation copies.
const UNUSABLE_DIRS: &[&str] = &[
    ".Trash",
    ".Trashes",
    "Backups.backupdb",
    ".MobileBackups",
    ".timemachine",
    ".DocumentRevisions-V100",
    ".Spotlight-V100",
    "AppTranslocation",
];

/// Whether a bundle-id registration may be taken as the install, by its path alone:
/// absolute, a `.app`, not under `/Volumes` (a mounted installer image, another disk or a
/// backup volume - it can vanish, and it is not where the player installed Lunar), and not
/// inside any `UNUSABLE_DIRS` folder.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn usable_registration(path: &Path) -> bool {
    if !path.is_absolute() || path.starts_with("/Volumes") {
        return false;
    }
    let is_app = path
        .extension()
        .and_then(OsStr::to_str)
        .is_some_and(|e| e.eq_ignore_ascii_case("app"));
    is_app
        && !path.components().any(|c| match c {
            Component::Normal(name) => UNUSABLE_DIRS.iter().any(|d| OsStr::new(d) == name),
            _ => false,
        })
}

/// Production lookup: Spotlight's `kMDItemCFBundleIdentifier` for Lunar's bundle id.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
fn spotlight_lookup() -> Vec<PathBuf> {
    if !cfg!(target_os = "macos") {
        return Vec::new();
    }
    spotlight_lookup_with(Path::new(MDFIND), LOOKUP_TIMEOUT)
}

/// Runs `program -0 "kMDItemCFBundleIdentifier == '<id>'"` for at most `timeout` and reads
/// its NUL-separated paths. A missing program, a non-zero exit or the timeout (the child is
/// killed) all answer nothing found. Never blocks past the timeout.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn spotlight_lookup_with(program: &Path, timeout: Duration) -> Vec<PathBuf> {
    spotlight_lookup_id(program, BUNDLE_ID, timeout)
}

/// `spotlight_lookup_with` for any bundle id (Prism's too).
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn spotlight_lookup_id(program: &Path, bundle_id: &str, timeout: Duration) -> Vec<PathBuf> {
    let mut cmd = Command::new(program);
    cmd.arg("-0")
        .arg(format!("kMDItemCFBundleIdentifier == '{bundle_id}'"));
    run_bounded(cmd, timeout)
        .map(|out| {
            out.split(|b| *b == 0)
                .filter(|p| !p.is_empty())
                .map(|p| PathBuf::from(String::from_utf8_lossy(p).into_owned()))
                .collect()
        })
        .unwrap_or_default()
}

/// A command's stdout when it exits successfully within `timeout`; None otherwise. The
/// output is read on its own thread so a full pipe cannot stall the child, and that thread
/// is waited for only up to the deadline.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub(crate) fn run_bounded(mut cmd: Command, timeout: Duration) -> Option<Vec<u8>> {
    let deadline = Instant::now() + timeout;
    let mut child = cmd
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()?;
    let Some(mut stdout) = child.stdout.take() else {
        let _ = child.kill();
        let _ = child.wait();
        return None;
    };
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let mut buf = Vec::new();
        let _ = stdout.read_to_end(&mut buf);
        let _ = tx.send(buf);
    });
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                if !status.success() {
                    return None;
                }
                let left = deadline.saturating_duration_since(Instant::now());
                return rx.recv_timeout(left.max(Duration::from_millis(50))).ok();
            }
            Ok(None) if Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(15));
            }
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                return None;
            }
        }
    }
}

/// Windows keeps its own (unchanged) notion of installed: the launcher exe under
/// `%LOCALAPPDATA%\Programs`, in either observed layout.
#[cfg(windows)]
pub fn find_app(_home: &Path) -> Option<PathBuf> {
    let local = std::env::var_os("LOCALAPPDATA")?;
    crate::launch::lunar_exe_in(&PathBuf::from(local).join("Programs"))
}

/// Dev builds elsewhere have no app bundle; the data directory is the best signal.
#[cfg(not(any(windows, target_os = "macos")))]
pub fn find_app(home: &Path) -> Option<PathBuf> {
    let dir = home.join(".lunarclient");
    dir.exists().then_some(dir)
}

/// The launcher executable of a bundle.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn bundle_exe(app: &Path) -> PathBuf {
    app.join(BUNDLE_EXE)
}

/// Every executable path a running Lunar launcher may have: the exe of each candidate
/// bundle location, whether or not it exists right now (a match against a missing path
/// simply never fires), plus - when neither standard folder holds Lunar - the exe of the
/// bundle found by its bundle id. Derived from the bundle locations, never only
/// `/Applications`.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn launcher_exes(home: Option<&Path>) -> Vec<PathBuf> {
    launcher_exes_with(home, &spotlight_lookup)
}

/// `launcher_exes` with the bundle-id lookup injected.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn launcher_exes_with(home: Option<&Path>, lookup: &dyn Fn() -> Vec<PathBuf>) -> Vec<PathBuf> {
    let bundles = match home {
        Some(home) => app_candidates(home),
        None => vec![Path::new("/Applications").join(BUNDLE_NAME)],
    };
    launcher_exes_among(&bundles, lookup)
}

pub(crate) fn launcher_exes_among(bundles: &[PathBuf], lookup: &dyn Fn() -> Vec<PathBuf>) -> Vec<PathBuf> {
    let mut exes: Vec<PathBuf> = bundles.iter().map(|a| bundle_exe(a)).collect();
    if find_app_in(bundles).is_none() {
        if let Some(app) = registered_app(&lookup()) {
            exes.push(bundle_exe(&app));
        }
    }
    exes
}

pub fn launcher_json(home: &Path) -> PathBuf {
    home.join(".lunarclient/settings/launcher.json")
}

/// Lunar has run at least once on this account.
pub fn opened_once(home: &Path) -> bool {
    launcher_json(home).is_file()
}

fn accounts_json(home: &Path) -> PathBuf {
    home.join(".lunarclient/settings/game/accounts.json")
}

/// Signed in = `accounts.json` parses, holds at least one account, and names a non-empty
/// active account. Missing, unreadable or malformed reads as not signed in.
pub fn signed_in(home: &Path) -> bool {
    match std::fs::read(accounts_json(home)) {
        Ok(bytes) => signed_in_from(&bytes),
        Err(_) => false,
    }
}

fn signed_in_from(bytes: &[u8]) -> bool {
    match serde_json::from_slice::<AccountsPresence>(bytes) {
        Ok(p) => p.accounts.0 >= 1 && p.active.0,
        Err(_) => false,
    }
}

/// The only shape `accounts.json` is ever read into. Both fields are presence facts;
/// every other key in the file is skipped by serde without being kept.
#[derive(Deserialize)]
struct AccountsPresence {
    #[serde(rename = "activeAccountLocalId", default)]
    active: NonEmptyString,
    #[serde(default)]
    accounts: EntryCount,
}

/// True when the value is a non-empty string. The string itself is never kept.
#[derive(Default)]
struct NonEmptyString(bool);

impl<'de> Deserialize<'de> for NonEmptyString {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        struct V;
        impl<'de> Visitor<'de> for V {
            type Value = NonEmptyString;
            fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
                f.write_str("a string or null")
            }
            fn visit_str<E: serde::de::Error>(self, v: &str) -> Result<Self::Value, E> {
                Ok(NonEmptyString(!v.trim().is_empty()))
            }
            fn visit_unit<E: serde::de::Error>(self) -> Result<Self::Value, E> {
                Ok(NonEmptyString(false))
            }
            fn visit_none<E: serde::de::Error>(self) -> Result<Self::Value, E> {
                Ok(NonEmptyString(false))
            }
        }
        d.deserialize_any(V)
    }
}

/// How many entries an object has. Keys and values are both skipped (`IgnoredAny`), so no
/// account id or token is ever allocated.
#[derive(Default)]
struct EntryCount(usize);

impl<'de> Deserialize<'de> for EntryCount {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        struct V;
        impl<'de> Visitor<'de> for V {
            type Value = EntryCount;
            fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
                f.write_str("an object or null")
            }
            fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Self::Value, A::Error> {
                let mut n = 0;
                while map.next_entry::<IgnoredAny, IgnoredAny>()?.is_some() {
                    n += 1;
                }
                Ok(EntryCount(n))
            }
            fn visit_unit<E: serde::de::Error>(self) -> Result<Self::Value, E> {
                Ok(EntryCount(0))
            }
            fn visit_none<E: serde::de::Error>(self) -> Result<Self::Value, E> {
                Ok(EntryCount(0))
            }
        }
        d.deserialize_any(V)
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    fn isolated_candidates(home: &Path) -> Vec<PathBuf> {
        vec![home.join("System/Applications").join(BUNDLE_NAME), home.join("Applications").join(BUNDLE_NAME)]
    }

    /// A realistic file, tokens included, so the test proves they are skipped.
    pub(crate) const SIGNED_IN: &str = r#"{
        "activeAccountLocalId": "a1b2",
        "accounts": {
            "a1b2": {
                "localId": "a1b2", "username": "someone", "type": "Xbox",
                "minecraftProfile": {"id": "uuid", "name": "Player"},
                "accessToken": "SECRET-ACCESS", "accessTokenExpiresAt": "2026-10-07",
                "refreshToken": "SECRET-REFRESH"
            }
        }
    }"#;

    pub(crate) fn write_accounts(home: &Path, body: &str) {
        let dir = home.join(".lunarclient/settings/game");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("accounts.json"), body).unwrap();
    }

    #[test]
    fn accounts_detection_missing_malformed_empty_and_valid() {
        let home = tempfile::tempdir().unwrap();
        assert!(!signed_in(home.path()), "missing file: not signed in");

        write_accounts(home.path(), "{\"accounts\": {\"a\":");
        assert!(!signed_in(home.path()), "truncated JSON: not signed in");
        write_accounts(home.path(), "[]");
        assert!(!signed_in(home.path()), "wrong top-level shape: not signed in");
        write_accounts(home.path(), r#"{"accounts": [1,2], "activeAccountLocalId": "x"}"#);
        assert!(!signed_in(home.path()), "accounts not an object: not signed in");
        write_accounts(home.path(), r#"{"accounts": {}, "activeAccountLocalId": 7}"#);
        assert!(!signed_in(home.path()), "non-string active id: not signed in");

        write_accounts(home.path(), r#"{"accounts": {}, "activeAccountLocalId": "x"}"#);
        assert!(!signed_in(home.path()), "no accounts: not signed in");
        write_accounts(home.path(), r#"{"accounts": {"x": {}}, "activeAccountLocalId": ""}"#);
        assert!(!signed_in(home.path()), "empty active id: not signed in");
        write_accounts(home.path(), r#"{"accounts": {"x": {}}, "activeAccountLocalId": null}"#);
        assert!(!signed_in(home.path()), "null active id: not signed in");
        write_accounts(home.path(), r#"{"accounts": {"x": {}}}"#);
        assert!(!signed_in(home.path()), "no active id: not signed in");
        write_accounts(home.path(), "{}");
        assert!(!signed_in(home.path()));

        write_accounts(home.path(), SIGNED_IN);
        assert!(signed_in(home.path()), "one account, active id set: signed in");
        write_accounts(
            home.path(),
            r#"{"activeAccountLocalId": "b", "accounts": {"a": {}, "b": {}}, "other": [1]}"#,
        );
        assert!(signed_in(home.path()), "unknown keys are ignored");
    }

    /// A never-opened Lunar has the app bundle and nothing else: it is installed.
    #[test]
    fn a_never_opened_lunar_counts_as_installed() {
        let root = tempfile::tempdir().unwrap();
        let home = root.path().join("home");
        std::fs::create_dir_all(&home).unwrap();
        let app = home.join("Applications").join(BUNDLE_NAME);
        std::fs::create_dir_all(app.join("Contents/MacOS")).unwrap();
        let candidates = vec![root.path().join("System/Applications").join(BUNDLE_NAME), app.clone()];

        assert_eq!(find_app_in(&candidates), Some(app.clone()));
        assert!(!home.join(".lunarclient").exists(), "never opened");
        assert!(!opened_once(&home));
        assert!(!signed_in(&home));

        // Leftover data without a bundle is not an install.
        let gone = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(gone.path().join(".lunarclient/settings")).unwrap();
        assert_eq!(find_app_in(&[gone.path().join(BUNDLE_NAME)]), None);
    }

    #[test]
    fn opened_once_is_lunars_own_launcher_json() {
        let home = tempfile::tempdir().unwrap();
        assert!(!opened_once(home.path()));
        std::fs::create_dir_all(home.path().join(".lunarclient/settings")).unwrap();
        std::fs::write(launcher_json(home.path()), "{}").unwrap();
        assert!(opened_once(home.path()));
    }

    // ── the LaunchServices fallback (bundle id `com.moonsworth.client`) ─────────────

    fn fake_bundle(dir: &Path) -> PathBuf {
        let app = dir.join(BUNDLE_NAME);
        std::fs::create_dir_all(app.join("Contents/MacOS")).unwrap();
        app
    }

    /// Code review round 2 I2: Lunar in neither Applications folder, registered under its
    /// bundle id elsewhere, is installed - and its own exe is what the running check looks for.
    #[test]
    fn a_bundle_found_only_by_its_bundle_id_is_installed_and_its_exe_is_watched() {
        let root = tempfile::tempdir().unwrap();
        let home = root.path().join("home");
        std::fs::create_dir_all(&home).unwrap();
        let games = fake_bundle(&home.join("Games"));
        let found = games.clone();
        let lookup = move || vec![found.clone()];

        assert_eq!(find_app_in(&isolated_candidates(&home)), None, "not in either Applications folder");
        assert_eq!(find_app_among(&isolated_candidates(&home), &lookup), Some(games.clone()));
        let exes = launcher_exes_among(&isolated_candidates(&home), &lookup);
        assert!(exes.contains(&games.join("Contents/MacOS/Lunar Client")), "{exes:?}");
        // The standard locations stay in the list.
        assert!(exes.contains(&home.join("Applications/Lunar Client.app/Contents/MacOS/Lunar Client")));
    }

    /// A standard install wins and the lookup is never run.
    #[test]
    fn the_standard_folders_come_first_and_skip_the_lookup() {
        let root = tempfile::tempdir().unwrap();
        let home = root.path().join("home");
        let app = fake_bundle(&home.join("Applications"));
        let ran = std::cell::Cell::new(0);
        let lookup = || {
            ran.set(ran.get() + 1);
            vec![fake_bundle(&root.path().join("Elsewhere"))]
        };
        assert_eq!(find_app_among(&isolated_candidates(&home), &lookup), Some(app));
        let exes = launcher_exes_among(&isolated_candidates(&home), &lookup);
        assert!(!exes.iter().any(|e| e.starts_with(root.path().join("Elsewhere"))));
        assert_eq!(ran.get(), 0);
    }

    /// Registrations that are not an install: missing, not a bundle, in the Trash, a mounted
    /// volume (an installer image, another disk, a backup), a Time Machine backup, an App
    /// Translocation copy. The first usable one is picked.
    #[test]
    fn unusable_registrations_are_skipped_and_the_first_usable_bundle_is_picked() {
        let root = tempfile::tempdir().unwrap();
        let home = root.path().join("home");
        std::fs::create_dir_all(&home).unwrap();
        let trash = fake_bundle(&home.join(".Trash"));
        let backup = fake_bundle(&root.path().join("Backups.backupdb/Mac/Latest/Applications"));
        let translocated = fake_bundle(&root.path().join("AppTranslocation/ABCD/d"));
        let not_a_bundle = root.path().join("Lunar Client");
        std::fs::create_dir_all(&not_a_bundle).unwrap();
        let file_app = root.path().join("Plain.app");
        std::fs::write(&file_app, b"").unwrap();
        let good = fake_bundle(&root.path().join("Games"));
        let second = fake_bundle(&root.path().join("More"));
        let paths = vec![
            root.path().join("Gone/Lunar Client.app"),
            PathBuf::from("relative/Lunar Client.app"),
            trash,
            PathBuf::from("/Volumes/Lunar Client/Lunar Client.app"),
            backup,
            translocated,
            not_a_bundle,
            file_app,
            good.clone(),
            second,
        ];
        let lookup = move || paths.clone();
        assert_eq!(find_app_among(&isolated_candidates(&home), &lookup), Some(good));
        assert!(!usable_registration(Path::new("/Volumes/Backup/Applications/Lunar Client.app")));
    }

    /// A failed lookup (nothing found, mdfind missing, failing or hung) is "not installed",
    /// promptly.
    #[cfg(unix)]
    #[test]
    fn a_failed_or_hung_lookup_is_not_installed_and_never_hangs() {
        let root = tempfile::tempdir().unwrap();
        let home = root.path().join("home");
        std::fs::create_dir_all(&home).unwrap();
        assert_eq!(find_app_among(&isolated_candidates(&home), &Vec::new), None);
        assert!(launcher_exes_among(&isolated_candidates(&home), &Vec::new)
            .iter()
            .all(|e| e.starts_with("/Applications") || e.starts_with(&home)));

        let missing = root.path().join("no-such-mdfind");
        assert!(spotlight_lookup_with(&missing, Duration::from_secs(2)).is_empty());

        let failing = root.path().join("failing-mdfind");
        std::fs::write(&failing, "#!/bin/sh\nprintf '/Applications/Lunar Client.app'\nexit 1\n").unwrap();
        make_executable(&failing);
        assert!(spotlight_lookup_with(&failing, Duration::from_secs(2)).is_empty(), "a failing run finds nothing");

        let hung = root.path().join("hung-mdfind");
        std::fs::write(&hung, "#!/bin/sh\nexec sleep 30\n").unwrap();
        make_executable(&hung);
        let started = std::time::Instant::now();
        let lookup = || spotlight_lookup_with(&hung, Duration::from_millis(300));
        assert_eq!(find_app_among(&isolated_candidates(&home), &lookup), None);
        assert!(started.elapsed() < Duration::from_secs(5), "bounded: {:?}", started.elapsed());
    }

    /// mdfind's `-0` output: NUL-separated paths, each one kept whole.
    #[cfg(unix)]
    #[test]
    fn the_lookup_reads_nul_separated_paths() {
        let root = tempfile::tempdir().unwrap();
        let fake = root.path().join("fake-mdfind");
        std::fs::write(
            &fake,
            "#!/bin/sh\nprintf '/Users/x/Games/Lunar Client.app\\000/Other/Lunar\\nClient.app\\000'\n",
        )
        .unwrap();
        make_executable(&fake);
        assert_eq!(
            spotlight_lookup_with(&fake, Duration::from_secs(5)),
            vec![
                PathBuf::from("/Users/x/Games/Lunar Client.app"),
                PathBuf::from("/Other/Lunar\nClient.app"),
            ]
        );
    }

    #[cfg(unix)]
    fn make_executable(path: &Path) {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755)).unwrap();
    }

    /// The running check derives its exe paths from the bundle locations, the user's own
    /// Applications folder included - not only the hard-coded `/Applications` one.
    #[test]
    fn launcher_exes_cover_every_bundle_location() {
        // No bundle-id registration here (the lookup is injected), so the list is exactly the
        // standard locations' exes.
        let none = Vec::new;
        let exes = launcher_exes_with(Some(Path::new("/Users/jane")), &none);
        assert!(exes.contains(&PathBuf::from(
            "/Applications/Lunar Client.app/Contents/MacOS/Lunar Client"
        )));
        assert!(exes.contains(&PathBuf::from(
            "/Users/jane/Applications/Lunar Client.app/Contents/MacOS/Lunar Client"
        )));
        assert_eq!(
            launcher_exes_with(None, &none),
            vec![PathBuf::from("/Applications/Lunar Client.app/Contents/MacOS/Lunar Client")]
        );
    }
}
