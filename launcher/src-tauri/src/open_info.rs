//! What kind of open this is: a first install, the first open after an update, or neither.
//!
//! The 0.18 home plays the island's 1.4 s build on every open whose first home is ready, and What's New once
//! after every update (dev and next builds included).
//!
//! The record is `~/.cobblify/launcher-last-seen`: the highest launcher version ever opened.
//! A 0.17 launcher never wrote it, so on the first 0.18 open the updater's own
//! `pending-version` marker tells an update from a fresh install. That marker is deleted by
//! `report_completed_install`, so [`capture`] must run before it.

use semver::Version;
use serde::Serialize;
use std::fs;
use std::io::ErrorKind;
use std::path::{Path, PathBuf};

const LAST_SEEN_FILE: &str = "launcher-last-seen";

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct OpenInfo {
    pub version: String,
    /// The first open of a newer version than any opened before.
    pub updated: bool,
    /// No launcher of this generation has opened here before, and this is not an update.
    pub fresh: bool,
}

enum LastSeen {
    Absent,
    Unparseable,
    Unreadable,
    Seen(Version),
}

fn last_seen_path(home: &Path) -> PathBuf {
    home.join(".cobblify").join(LAST_SEEN_FILE)
}

fn read_last_seen(path: &Path) -> LastSeen {
    match fs::read_to_string(path) {
        Ok(text) => match Version::parse(text.trim()) {
            Ok(v) => LastSeen::Seen(v),
            Err(_) => LastSeen::Unparseable,
        },
        Err(e) if e.kind() == ErrorKind::NotFound => LastSeen::Absent,
        Err(_) => LastSeen::Unreadable,
    }
}

/// Temp file then rename, so an interrupted write never leaves a truncated record.
fn write_last_seen(path: &Path, version: &Version) -> std::io::Result<()> {
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir)?;
    }
    let tmp = path.with_file_name(format!(".{LAST_SEEN_FILE}.tmp"));
    fs::write(&tmp, format!("{version}\n"))?;
    fs::rename(&tmp, path).inspect_err(|_| {
        let _ = fs::remove_file(&tmp);
    })
}

/// Reads and updates the record for the running version. `pending_version` is the content of
/// the updater's `pending-version` marker, if any, read before anything deletes it.
pub fn capture(home: &Path, running: &str, pending_version: Option<&str>) -> OpenInfo {
    let quiet = OpenInfo {
        version: running.to_string(),
        updated: false,
        fresh: false,
    };
    let Ok(current) = Version::parse(running) else {
        return quiet;
    };
    let path = last_seen_path(home);
    let (updated, fresh, store) = match read_last_seen(&path) {
        // Unreadable: show the island still and leave the record alone.
        LastSeen::Unreadable => return quiet,
        LastSeen::Absent => {
            let updated = pending_version.map(str::trim) == Some(running);
            (updated, !updated, current.clone())
        }
        // A record we cannot read as a version is never a first install, and never a reason
        // to replay; it is replaced.
        LastSeen::Unparseable => (false, false, current.clone()),
        LastSeen::Seen(seen) => {
            let updated = current > seen;
            // Keep the highest: opening an older build must not make the newer one replay.
            (updated, false, if updated { current.clone() } else { seen })
        }
    };
    // An update is only "the first open" if the record of it sticks: when it cannot be written, every later
    // open would replay too, so this one stays quiet instead.
    if write_last_seen(&path, &store).is_err() {
        return quiet;
    }
    OpenInfo {
        version: running.to_string(),
        updated,
        fresh,
    }
}

/// Startup: the `pending-version` marker as it stands before `report_completed_install`.
pub fn pending_version(home: &Path) -> Option<String> {
    fs::read_to_string(home.join(".cobblify/updates/pending-version")).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_home() -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "cobblify-open-info-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn seen(home: &Path) -> String {
        fs::read_to_string(last_seen_path(home)).unwrap().trim().to_string()
    }

    fn set_seen(home: &Path, text: &str) {
        fs::create_dir_all(home.join(".cobblify")).unwrap();
        fs::write(last_seen_path(home), text).unwrap();
    }

    #[test]
    fn a_first_install_is_fresh_and_still() {
        let home = temp_home();
        let info = capture(&home, "0.18.0-next.1", None);
        assert_eq!((info.updated, info.fresh), (false, true));
        assert_eq!(seen(&home), "0.18.0-next.1");
    }

    #[test]
    fn reopening_the_same_version_is_quiet() {
        let home = temp_home();
        capture(&home, "0.18.0", None);
        let info = capture(&home, "0.18.0", None);
        assert_eq!((info.updated, info.fresh), (false, false));
    }

    #[test]
    fn a_newer_next_build_is_an_update() {
        let home = temp_home();
        capture(&home, "0.18.0-next.1", None);
        let info = capture(&home, "0.18.0-next.2", None);
        assert_eq!((info.updated, info.fresh), (true, false));
        assert_eq!(seen(&home), "0.18.0-next.2");
        // Only once.
        assert!(!capture(&home, "0.18.0-next.2", None).updated);
    }

    #[test]
    fn dev_to_dev_and_dev_to_release_are_updates() {
        let home = temp_home();
        set_seen(&home, "0.18.0-dev.3");
        assert!(capture(&home, "0.18.0-dev.4", None).updated);
        assert!(capture(&home, "0.18.0", None).updated);
    }

    #[test]
    fn opening_an_older_build_is_quiet_and_keeps_the_newer_record() {
        let home = temp_home();
        set_seen(&home, "0.18.0-next.2");
        let info = capture(&home, "0.18.0-next.1", None);
        assert_eq!((info.updated, info.fresh), (false, false));
        assert_eq!(seen(&home), "0.18.0-next.2");
        // Going back to next.2 is not an update either.
        assert!(!capture(&home, "0.18.0-next.2", None).updated);
    }

    #[test]
    fn the_first_0_18_open_after_a_0_17_update_is_an_update() {
        let home = temp_home();
        let info = capture(&home, "0.18.0", Some("0.18.0\n"));
        assert_eq!((info.updated, info.fresh), (true, false));
    }

    #[test]
    fn a_stale_pending_marker_is_not_an_update() {
        let home = temp_home();
        let info = capture(&home, "0.18.0", Some("0.17.1"));
        assert_eq!((info.updated, info.fresh), (false, true));
    }

    #[test]
    fn an_unparseable_record_is_neither_fresh_nor_updated_and_is_replaced() {
        let home = temp_home();
        set_seen(&home, "not a version");
        let info = capture(&home, "0.18.0", Some("0.18.0"));
        assert_eq!((info.updated, info.fresh), (false, false));
        assert_eq!(seen(&home), "0.18.0");
    }

    #[cfg(unix)]
    #[test]
    fn an_unreadable_record_is_quiet_and_left_alone() {
        use std::os::unix::fs::PermissionsExt;
        let home = temp_home();
        set_seen(&home, "0.17.0");
        fs::set_permissions(last_seen_path(&home), fs::Permissions::from_mode(0o000)).unwrap();
        if fs::read_to_string(last_seen_path(&home)).is_ok() {
            return; // running as root: permissions do not apply
        }
        let info = capture(&home, "0.18.0", None);
        assert_eq!((info.updated, info.fresh), (false, false));
        fs::set_permissions(last_seen_path(&home), fs::Permissions::from_mode(0o644)).unwrap();
        assert_eq!(seen(&home), "0.17.0");
    }

    #[cfg(unix)]
    #[test]
    fn an_update_whose_record_cannot_be_written_is_quiet_every_time() {
        use std::os::unix::fs::PermissionsExt;
        let home = temp_home();
        set_seen(&home, "0.18.0-next.1");
        let dir = home.join(".cobblify");
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o500)).unwrap();
        if fs::write(dir.join("probe"), "x").is_ok() {
            fs::set_permissions(&dir, fs::Permissions::from_mode(0o755)).unwrap();
            return; // running as root: permissions do not apply
        }
        for _ in 0..2 {
            let info = capture(&home, "0.18.0-next.2", None);
            assert_eq!((info.updated, info.fresh), (false, false));
        }
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o755)).unwrap();
        assert_eq!(seen(&home), "0.18.0-next.1");
    }

    #[test]
    fn pending_version_reads_the_marker() {
        let home = temp_home();
        assert_eq!(pending_version(&home), None);
        fs::create_dir_all(home.join(".cobblify/updates")).unwrap();
        fs::write(home.join(".cobblify/updates/pending-version"), "0.18.0").unwrap();
        assert_eq!(pending_version(&home).as_deref(), Some("0.18.0"));
    }
}
