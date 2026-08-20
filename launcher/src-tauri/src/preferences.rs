//! Backend-owned auto-join preference at `~/.cobblify/launcher-preferences.json`.

use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use atomic_write_file::AtomicWriteFile;
use fs2::FileExt;
use serde::{Deserialize, Serialize};

const PREF_FILE: &str = "launcher-preferences.json";
const LOCK_FILE: &str = "launcher-preferences.lock";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PreferenceHealth {
    Valid,
    Missing,
    Invalid,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchPreferencesView {
    pub auto_join_hypixel: bool,
    pub health: PreferenceHealth,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub diagnostic: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum PreferenceSaveReply {
    Saved {
        #[serde(rename = "autoJoinHypixel")]
        auto_join_hypixel: bool,
    },
    Reconciled {
        #[serde(rename = "autoJoinHypixel")]
        auto_join_hypixel: bool,
    },
    NotSaved { diagnostic: String },
    Indeterminate,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct PreferenceDoc {
    auto_join_hypixel: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PreferenceReadError {
    ParentNotDirectory,
    PermissionDenied,
    Io(String),
}

fn cobblify_dir(home: &Path) -> PathBuf {
    home.join(".cobblify")
}

fn pref_path(home: &Path) -> PathBuf {
    cobblify_dir(home).join(PREF_FILE)
}

fn lock_path(home: &Path) -> PathBuf {
    cobblify_dir(home).join(LOCK_FILE)
}

/// Ensure `~/.cobblify` exists and is a directory before opening the lock.
pub fn ensure_cobblify_dir(home: &Path) -> Result<PathBuf, PreferenceReadError> {
    let dir = cobblify_dir(home);
    if dir.exists() {
        if !dir.is_dir() {
            return Err(PreferenceReadError::ParentNotDirectory);
        }
        return Ok(dir);
    }
    fs::create_dir_all(&dir).map_err(map_io)?;
    if !dir.is_dir() {
        return Err(PreferenceReadError::ParentNotDirectory);
    }
    Ok(dir)
}

fn map_io(e: std::io::Error) -> PreferenceReadError {
    match e.kind() {
        std::io::ErrorKind::PermissionDenied => PreferenceReadError::PermissionDenied,
        _ => PreferenceReadError::Io(e.to_string()),
    }
}

pub struct LockedPrefs {
    _lock: File,
    home: PathBuf,
}

impl LockedPrefs {
    pub fn acquire(home: &Path) -> Result<Self, PreferenceReadError> {
        let dir = ensure_cobblify_dir(home)?;
        let lock_file = dir.join(LOCK_FILE);
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(&lock_file)
            .map_err(map_io)?;
        lock.lock_exclusive().map_err(map_io)?;
        Ok(LockedPrefs {
            _lock: lock,
            home: home.to_path_buf(),
        })
    }

    pub(crate) fn read_view(&self) -> LaunchPreferencesView {
        match read_doc(&pref_path(&self.home)) {
            Ok(doc) => LaunchPreferencesView {
                auto_join_hypixel: doc.auto_join_hypixel,
                health: PreferenceHealth::Valid,
                diagnostic: None,
            },
            Err(ReadOutcome::Missing) => LaunchPreferencesView {
                auto_join_hypixel: true,
                health: PreferenceHealth::Missing,
                diagnostic: None,
            },
            Err(ReadOutcome::Invalid(msg)) => LaunchPreferencesView {
                auto_join_hypixel: true,
                health: PreferenceHealth::Invalid,
                diagnostic: Some(msg),
            },
        }
    }

    pub(crate) fn read_strict(&self) -> Result<bool, String> {
        match read_doc(&pref_path(&self.home)) {
            Ok(doc) => Ok(doc.auto_join_hypixel),
            Err(ReadOutcome::Missing) => Ok(true),
            Err(ReadOutcome::Invalid(msg)) => Err(msg),
        }
    }

    fn write_bool(&self, enabled: bool) -> PreferenceSaveReply {
        let path = pref_path(&self.home);
        let doc = PreferenceDoc {
            auto_join_hypixel: enabled,
        };
        match write_doc(&path, &doc) {
            Ok(()) => PreferenceSaveReply::Saved {
                auto_join_hypixel: enabled,
            },
            Err(SaveOutcome::NotSaved(msg)) => PreferenceSaveReply::NotSaved { diagnostic: msg },
            Err(SaveOutcome::Indeterminate) => PreferenceSaveReply::Indeterminate,
            Err(SaveOutcome::Reconciled(value)) => PreferenceSaveReply::Reconciled {
                auto_join_hypixel: value,
            },
        }
    }
}

enum ReadOutcome {
    Missing,
    Invalid(String),
}

fn read_doc(path: &Path) -> Result<PreferenceDoc, ReadOutcome> {
    if path.exists() && !path.is_file() {
        return Err(ReadOutcome::Invalid(
            "preference path is not a file".into(),
        ));
    }
    if !path.is_file() {
        return Err(ReadOutcome::Missing);
    }
    let mut file = File::open(path).map_err(|e| ReadOutcome::Invalid(e.to_string()))?;
    let mut text = String::new();
    file.read_to_string(&mut text)
        .map_err(|e| ReadOutcome::Invalid(e.to_string()))?;
    let value: serde_json::Value =
        serde_json::from_str(&text).map_err(|e| ReadOutcome::Invalid(e.to_string()))?;
    if !value.is_object() {
        return Err(ReadOutcome::Invalid("preference file is not an object".into()));
    }
    let doc: PreferenceDoc =
        serde_json::from_value(value).map_err(|e| ReadOutcome::Invalid(e.to_string()))?;
    Ok(doc)
}

enum SaveOutcome {
    NotSaved(String),
    Indeterminate,
    Reconciled(bool),
}

fn write_doc(path: &Path, doc: &PreferenceDoc) -> Result<(), SaveOutcome> {
    let json = serde_json::to_string(doc).map_err(|e| SaveOutcome::NotSaved(e.to_string()))?;
    let mut writer = match AtomicWriteFile::options().open(path) {
        Ok(w) => w,
        Err(e) => {
            return Err(SaveOutcome::NotSaved(format!("cannot open writer: {e}")));
        }
    };
    if let Err(e) = writer.write_all(json.as_bytes()) {
        let _ = writer.discard();
        return Err(SaveOutcome::NotSaved(format!("write failed: {e}")));
    }
    match writer.commit() {
        Ok(()) => Ok(()),
        Err(_) => {
            // Commit may have succeeded; reread under the same outer lock.
            match read_doc(path) {
                Ok(current) if current == *doc => {
                    Err(SaveOutcome::Reconciled(current.auto_join_hypixel))
                }
                Ok(current) => Err(SaveOutcome::Reconciled(current.auto_join_hypixel)),
                Err(_) => Err(SaveOutcome::Indeterminate),
            }
        }
    }
}

pub fn launch_preferences(home: &Path) -> Result<LaunchPreferencesView, PreferenceReadError> {
    let locked = LockedPrefs::acquire(home)?;
    Ok(locked.read_view())
}

pub fn read_strict_auto_join(home: &Path) -> Result<bool, PreferenceReadError> {
    let locked = LockedPrefs::acquire(home)?;
    locked
        .read_strict()
        .map_err(|msg| PreferenceReadError::Io(msg))
}

pub fn set_auto_join_hypixel(
    home: &Path,
    enabled: bool,
) -> Result<PreferenceSaveReply, PreferenceReadError> {
    let locked = LockedPrefs::acquire(home)?;
    Ok(locked.write_bool(enabled))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_home() -> PathBuf {
        tempfile::tempdir()
            .expect("tempdir")
            .into_path()
    }

    #[test]
    fn missing_defaults_on() {
        let home = temp_home();
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.auto_join_hypixel, true);
        assert_eq!(view.health, PreferenceHealth::Missing);
    }

    #[test]
    fn first_save_persists() {
        let home = temp_home();
        let reply = set_auto_join_hypixel(&home, false).unwrap();
        assert!(matches!(reply, PreferenceSaveReply::Saved { auto_join_hypixel: false }));
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.auto_join_hypixel, false);
        assert_eq!(view.health, PreferenceHealth::Valid);
    }

    #[test]
    fn parent_is_file_rejected() {
        let base = tempfile::tempdir().unwrap();
        let home = base.path().join("home");
        fs::create_dir(&home).unwrap();
        let cobblify = home.join(".cobblify");
        fs::write(&cobblify, "not a dir").unwrap();
        let err = launch_preferences(&home).unwrap_err();
        assert_eq!(err, PreferenceReadError::ParentNotDirectory);
    }

    #[test]
    fn malformed_is_invalid_but_defaults_on_in_view() {
        let home = temp_home();
        let dir = ensure_cobblify_dir(&home).unwrap();
        fs::write(dir.join(PREF_FILE), "{not json").unwrap();
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.health, PreferenceHealth::Invalid);
        assert_eq!(view.auto_join_hypixel, true);
        assert!(view.diagnostic.is_some());
    }

    #[test]
    #[cfg(unix)]
    fn permission_denied_on_unwritable_parent() {
        use std::os::unix::fs::PermissionsExt;
        let base = tempfile::tempdir().unwrap();
        let home = base.path().join("home");
        fs::create_dir(&home).unwrap();
        let cobblify = home.join(".cobblify");
        fs::create_dir(&cobblify).unwrap();
        let mut perms = fs::metadata(&cobblify).unwrap().permissions();
        perms.set_mode(0o444);
        fs::set_permissions(&cobblify, perms).unwrap();
        let err = set_auto_join_hypixel(&home, false).unwrap_err();
        assert!(matches!(err, PreferenceReadError::PermissionDenied | PreferenceReadError::Io(_)));
        let mut perms = fs::metadata(&cobblify).unwrap().permissions();
        perms.set_mode(0o755);
        fs::set_permissions(&cobblify, perms).unwrap();
    }

    #[test]
    fn preference_path_that_is_directory_is_invalid() {
        let home = temp_home();
        let dir = ensure_cobblify_dir(&home).unwrap();
        fs::create_dir(dir.join(PREF_FILE)).unwrap();
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.health, PreferenceHealth::Invalid);
        assert_eq!(view.auto_join_hypixel, true);
    }

    #[test]
    fn wrong_type_auto_join_is_invalid() {
        let home = temp_home();
        let dir = ensure_cobblify_dir(&home).unwrap();
        fs::write(dir.join(PREF_FILE), r#"{"auto_join_hypixel":"yes"}"#).unwrap();
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.health, PreferenceHealth::Invalid);
        assert_eq!(view.auto_join_hypixel, true);
    }

    #[test]
    fn save_reply_serializes_snake_case_tags() {
        let saved = serde_json::to_value(PreferenceSaveReply::Saved {
            auto_join_hypixel: true,
        })
        .unwrap();
        assert_eq!(saved["status"], "saved");
        let not_saved = serde_json::to_value(PreferenceSaveReply::NotSaved {
            diagnostic: "disk".into(),
        })
        .unwrap();
        assert_eq!(not_saved["status"], "not_saved");
    }
}
