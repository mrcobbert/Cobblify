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
//! LaunchServices (bundle id `com.moonsworth.client`) is NOT consulted: it costs an
//! osascript or Spotlight round trip on every startup and can hold stale registrations.
//! The two standard install folders cover every install the launcher has seen.

use std::fmt;
use std::path::{Path, PathBuf};

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

/// The installed Lunar, if any. macOS: the app bundle.
#[cfg(target_os = "macos")]
pub fn find_app(home: &Path) -> Option<PathBuf> {
    find_app_in(&app_candidates(home))
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
/// simply never fires). Derived from the bundle locations, never only `/Applications`.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn launcher_exes(home: Option<&Path>) -> Vec<PathBuf> {
    match home {
        Some(home) => app_candidates(home).iter().map(|a| bundle_exe(a)).collect(),
        None => vec![bundle_exe(&Path::new("/Applications").join(BUNDLE_NAME))],
    }
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

    /// The running check derives its exe paths from the bundle locations, the user's own
    /// Applications folder included - not only the hard-coded `/Applications` one.
    #[test]
    fn launcher_exes_cover_every_bundle_location() {
        let exes = launcher_exes(Some(Path::new("/Users/jane")));
        assert!(exes.contains(&PathBuf::from(
            "/Applications/Lunar Client.app/Contents/MacOS/Lunar Client"
        )));
        assert!(exes.contains(&PathBuf::from(
            "/Users/jane/Applications/Lunar Client.app/Contents/MacOS/Lunar Client"
        )));
        assert_eq!(
            launcher_exes(None),
            vec![PathBuf::from("/Applications/Lunar Client.app/Contents/MacOS/Lunar Client")]
        );
    }
}
