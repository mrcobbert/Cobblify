//! Backend-owned launcher preferences at `~/.cobblify/launcher-preferences.json`.

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
    pub use_external_overlay: bool,
    pub health: PreferenceHealth,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub diagnostic: Option<String>,
}

/// Which release channel the updater asks for. `Dev` is the opt-in "Test dev builds"
/// checkbox; the Worker then answers with the newer of stable and dev. Anything the
/// preference file holds that is not exactly `"dev"` reads as `Stable`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UpdateChannel {
    Stable,
    Dev,
}

impl UpdateChannel {
    pub fn parse(value: &str) -> Self {
        if value == "dev" {
            UpdateChannel::Dev
        } else {
            UpdateChannel::Stable
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            UpdateChannel::Stable => "stable",
            UpdateChannel::Dev => "dev",
        }
    }
}

impl Default for UpdateChannel {
    fn default() -> Self {
        UpdateChannel::Stable
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdatePreferencesView {
    pub auto_update_enabled: bool,
    pub auto_update_prompted: bool,
    pub update_channel: UpdateChannel,
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
        #[serde(rename = "useExternalOverlay")]
        use_external_overlay: bool,
    },
    Reconciled {
        #[serde(rename = "autoJoinHypixel")]
        auto_join_hypixel: bool,
        #[serde(rename = "useExternalOverlay")]
        use_external_overlay: bool,
    },
    NotSaved { diagnostic: String },
    Indeterminate,
}

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum UpdatePreferenceSaveReply {
    Saved {
        #[serde(rename = "autoUpdateEnabled")]
        auto_update_enabled: bool,
        #[serde(rename = "autoUpdatePrompted")]
        auto_update_prompted: bool,
        #[serde(rename = "updateChannel")]
        update_channel: UpdateChannel,
    },
    Reconciled {
        #[serde(rename = "autoUpdateEnabled")]
        auto_update_enabled: bool,
        #[serde(rename = "autoUpdatePrompted")]
        auto_update_prompted: bool,
        #[serde(rename = "updateChannel")]
        update_channel: UpdateChannel,
    },
    NotSaved { diagnostic: String },
    Indeterminate,
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct PreferenceDoc {
    auto_join_hypixel: bool,
    #[serde(default = "default_true")]
    use_external_overlay: bool,
    #[serde(default)]
    auto_update_enabled: bool,
    #[serde(default)]
    auto_update_prompted: bool,
    /// Stored as the channel name so a hand-edited or future value never invalidates the file.
    #[serde(default)]
    update_channel: String,
}

impl PreferenceDoc {
    fn channel(&self) -> UpdateChannel {
        UpdateChannel::parse(&self.update_channel)
    }
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
                use_external_overlay: doc.use_external_overlay,
                health: PreferenceHealth::Valid,
                diagnostic: None,
            },
            Err(ReadOutcome::Missing) => LaunchPreferencesView {
                auto_join_hypixel: true,
                use_external_overlay: true,
                health: PreferenceHealth::Missing,
                diagnostic: None,
            },
            Err(ReadOutcome::Invalid(msg)) => LaunchPreferencesView {
                auto_join_hypixel: true,
                use_external_overlay: true,
                health: PreferenceHealth::Invalid,
                diagnostic: Some(msg),
            },
        }
    }

    pub(crate) fn read_update_view(&self) -> UpdatePreferencesView {
        match read_doc(&pref_path(&self.home)) {
            Ok(doc) => UpdatePreferencesView {
                auto_update_enabled: doc.auto_update_enabled,
                auto_update_prompted: doc.auto_update_prompted,
                update_channel: doc.channel(),
                health: PreferenceHealth::Valid,
                diagnostic: None,
            },
            Err(ReadOutcome::Missing) => UpdatePreferencesView {
                auto_update_enabled: false,
                auto_update_prompted: false,
                update_channel: UpdateChannel::Stable,
                health: PreferenceHealth::Missing,
                diagnostic: None,
            },
            Err(ReadOutcome::Invalid(msg)) => UpdatePreferencesView {
                auto_update_enabled: false,
                auto_update_prompted: false,
                update_channel: UpdateChannel::Stable,
                health: PreferenceHealth::Invalid,
                diagnostic: Some(msg),
            },
        }
    }

    pub(crate) fn read_strict(&self) -> Result<(bool, bool), String> {
        match read_doc(&pref_path(&self.home)) {
            Ok(doc) => Ok((doc.auto_join_hypixel, doc.use_external_overlay)),
            Err(ReadOutcome::Missing) => Ok((true, true)),
            Err(ReadOutcome::Invalid(msg)) => Err(msg),
        }
    }

    /// Save one field, preserving the sibling. The sibling comes from a
    /// lenient per-field read so a valid value survives even when the other
    /// field (or the document's health) is invalid; only a file that is not
    /// a JSON object at all falls back to both defaults.
    fn write_key(&self, key: SetterKey, enabled: bool) -> PreferenceSaveReply {
        let path = pref_path(&self.home);
        let mut doc = read_fields_lenient(&path);
        match key {
            SetterKey::AutoJoin => doc.auto_join_hypixel = enabled,
            SetterKey::Overlay => doc.use_external_overlay = enabled,
        }
        match write_doc(&path, &doc) {
            Ok(()) => PreferenceSaveReply::Saved {
                auto_join_hypixel: doc.auto_join_hypixel,
                use_external_overlay: doc.use_external_overlay,
            },
            Err(SaveOutcome::NotSaved(msg)) => PreferenceSaveReply::NotSaved { diagnostic: msg },
            Err(SaveOutcome::Indeterminate) => PreferenceSaveReply::Indeterminate,
            Err(SaveOutcome::Reconciled(current)) => PreferenceSaveReply::Reconciled {
                auto_join_hypixel: current.auto_join_hypixel,
                use_external_overlay: current.use_external_overlay,
            },
        }
    }

    fn write_auto_update(&self, enabled: bool) -> UpdatePreferenceSaveReply {
        let path = pref_path(&self.home);
        let mut doc = read_fields_lenient(&path);
        doc.auto_update_enabled = enabled;
        doc.auto_update_prompted = true;
        Self::update_reply(write_doc(&path, &doc), &doc)
    }

    fn write_update_channel(&self, channel: UpdateChannel) -> UpdatePreferenceSaveReply {
        let path = pref_path(&self.home);
        let mut doc = read_fields_lenient(&path);
        doc.update_channel = channel.as_str().to_string();
        Self::update_reply(write_doc(&path, &doc), &doc)
    }

    fn update_reply(outcome: Result<(), SaveOutcome>, doc: &PreferenceDoc) -> UpdatePreferenceSaveReply {
        match outcome {
            Ok(()) => UpdatePreferenceSaveReply::Saved {
                auto_update_enabled: doc.auto_update_enabled,
                auto_update_prompted: doc.auto_update_prompted,
                update_channel: doc.channel(),
            },
            Err(SaveOutcome::NotSaved(msg)) => UpdatePreferenceSaveReply::NotSaved { diagnostic: msg },
            Err(SaveOutcome::Indeterminate) => UpdatePreferenceSaveReply::Indeterminate,
            Err(SaveOutcome::Reconciled(current)) => UpdatePreferenceSaveReply::Reconciled {
                auto_update_enabled: current.auto_update_enabled,
                auto_update_prompted: current.auto_update_prompted,
                update_channel: current.channel(),
            },
        }
    }
}

#[derive(Debug, Clone, Copy)]
enum SetterKey {
    AutoJoin,
    Overlay,
}

/// Per-field salvage for the save path: each known key is read
/// independently; a bool survives, anything else takes its default.
fn read_fields_lenient(path: &Path) -> PreferenceDoc {
    let defaults = || PreferenceDoc {
        auto_join_hypixel: true,
        use_external_overlay: true,
        auto_update_enabled: false,
        auto_update_prompted: false,
        update_channel: String::new(),
    };
    let Ok(mut file) = File::open(path) else {
        return defaults();
    };
    let mut text = String::new();
    if file.read_to_string(&mut text).is_err() {
        return defaults();
    }
    let Ok(serde_json::Value::Object(map)) = serde_json::from_str(&text) else {
        return defaults();
    };
    let field = |name: &str, fallback| map.get(name).and_then(|v| v.as_bool()).unwrap_or(fallback);
    PreferenceDoc {
        auto_join_hypixel: field("auto_join_hypixel", true),
        use_external_overlay: field("use_external_overlay", true),
        auto_update_enabled: field("auto_update_enabled", false),
        auto_update_prompted: field("auto_update_prompted", false),
        update_channel: map
            .get("update_channel")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
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
    Reconciled(PreferenceDoc),
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
                Ok(current) => Err(SaveOutcome::Reconciled(current)),
                Err(_) => Err(SaveOutcome::Indeterminate),
            }
        }
    }
}

pub fn launch_preferences(home: &Path) -> Result<LaunchPreferencesView, PreferenceReadError> {
    let locked = LockedPrefs::acquire(home)?;
    Ok(locked.read_view())
}

pub fn update_preferences(home: &Path) -> Result<UpdatePreferencesView, PreferenceReadError> {
    let locked = LockedPrefs::acquire(home)?;
    Ok(locked.read_update_view())
}

pub fn set_auto_join_hypixel(
    home: &Path,
    enabled: bool,
) -> Result<PreferenceSaveReply, PreferenceReadError> {
    let locked = LockedPrefs::acquire(home)?;
    Ok(locked.write_key(SetterKey::AutoJoin, enabled))
}

pub fn set_use_external_overlay(
    home: &Path,
    enabled: bool,
) -> Result<PreferenceSaveReply, PreferenceReadError> {
    let locked = LockedPrefs::acquire(home)?;
    Ok(locked.write_key(SetterKey::Overlay, enabled))
}

pub fn set_auto_update(
    home: &Path,
    enabled: bool,
) -> Result<UpdatePreferenceSaveReply, PreferenceReadError> {
    let locked = LockedPrefs::acquire(home)?;
    Ok(locked.write_auto_update(enabled))
}

pub fn set_update_channel(
    home: &Path,
    channel: UpdateChannel,
) -> Result<UpdatePreferenceSaveReply, PreferenceReadError> {
    let locked = LockedPrefs::acquire(home)?;
    Ok(locked.write_update_channel(channel))
}

/// The channel the updater should ask for. Any problem reading preferences means stable:
/// a broken file must never make a launcher fetch dev builds.
pub fn update_channel(home: &Path) -> UpdateChannel {
    match update_preferences(home) {
        Ok(view) if view.health == PreferenceHealth::Valid => view.update_channel,
        _ => UpdateChannel::Stable,
    }
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
        assert!(matches!(
            reply,
            PreferenceSaveReply::Saved {
                auto_join_hypixel: false,
                use_external_overlay: true,
            }
        ));
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.auto_join_hypixel, false);
        assert_eq!(view.use_external_overlay, true);
        assert_eq!(view.health, PreferenceHealth::Valid);
    }

    #[test]
    fn overlay_save_persists_and_preserves_auto_join() {
        let home = temp_home();
        set_auto_join_hypixel(&home, false).unwrap();
        let reply = set_use_external_overlay(&home, false).unwrap();
        assert!(matches!(
            reply,
            PreferenceSaveReply::Saved {
                auto_join_hypixel: false,
                use_external_overlay: false,
            }
        ));
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.auto_join_hypixel, false);
        assert_eq!(view.use_external_overlay, false);
    }

    #[test]
    fn auto_join_save_preserves_overlay_sibling() {
        let home = temp_home();
        set_use_external_overlay(&home, false).unwrap();
        set_auto_join_hypixel(&home, true).unwrap();
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.auto_join_hypixel, true);
        assert_eq!(view.use_external_overlay, false);
    }

    #[test]
    fn old_one_key_file_reads_overlay_default_on() {
        let home = temp_home();
        let dir = ensure_cobblify_dir(&home).unwrap();
        fs::write(dir.join(PREF_FILE), r#"{"auto_join_hypixel":false}"#).unwrap();
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.health, PreferenceHealth::Valid);
        assert_eq!(view.auto_join_hypixel, false);
        assert_eq!(view.use_external_overlay, true);
        let locked = LockedPrefs::acquire(&home).unwrap();
        assert_eq!(locked.read_strict().unwrap(), (false, true));
    }

    #[test]
    fn old_launch_preferences_prompt_for_updates_without_enabling_them() {
        let home = temp_home();
        let dir = ensure_cobblify_dir(&home).unwrap();
        fs::write(
            dir.join(PREF_FILE),
            r#"{"auto_join_hypixel":false,"use_external_overlay":true}"#,
        )
        .unwrap();
        let view = update_preferences(&home).unwrap();
        assert!(!view.auto_update_enabled);
        assert!(!view.auto_update_prompted);
        assert_eq!(view.health, PreferenceHealth::Valid);
    }

    #[test]
    fn update_consent_preserves_both_launch_preferences() {
        let home = temp_home();
        set_auto_join_hypixel(&home, false).unwrap();
        set_use_external_overlay(&home, false).unwrap();
        let reply = set_auto_update(&home, true).unwrap();
        assert!(matches!(
            reply,
            UpdatePreferenceSaveReply::Saved {
                auto_update_enabled: true,
                auto_update_prompted: true,
                ..
            }
        ));
        let launch = launch_preferences(&home).unwrap();
        assert!(!launch.auto_join_hypixel);
        assert!(!launch.use_external_overlay);
    }

    #[test]
    fn update_channel_defaults_to_stable_and_round_trips() {
        let home = temp_home();
        assert_eq!(update_channel(&home), UpdateChannel::Stable);
        assert_eq!(update_preferences(&home).unwrap().update_channel, UpdateChannel::Stable);

        let reply = set_update_channel(&home, UpdateChannel::Dev).unwrap();
        assert!(matches!(
            reply,
            UpdatePreferenceSaveReply::Saved {
                update_channel: UpdateChannel::Dev,
                ..
            }
        ));
        assert_eq!(update_channel(&home), UpdateChannel::Dev);
        assert_eq!(update_preferences(&home).unwrap().update_channel, UpdateChannel::Dev);

        set_update_channel(&home, UpdateChannel::Stable).unwrap();
        assert_eq!(update_channel(&home), UpdateChannel::Stable);
    }

    #[test]
    fn update_channel_leaves_every_other_preference_alone() {
        let home = temp_home();
        set_auto_join_hypixel(&home, false).unwrap();
        set_use_external_overlay(&home, false).unwrap();
        set_auto_update(&home, true).unwrap();
        set_update_channel(&home, UpdateChannel::Dev).unwrap();
        let launch = launch_preferences(&home).unwrap();
        assert!(!launch.auto_join_hypixel);
        assert!(!launch.use_external_overlay);
        let update = update_preferences(&home).unwrap();
        assert!(update.auto_update_enabled);
        assert!(update.auto_update_prompted);
        assert_eq!(update.update_channel, UpdateChannel::Dev);
        // And the reverse: an auto-update change keeps the channel.
        set_auto_update(&home, false).unwrap();
        assert_eq!(update_channel(&home), UpdateChannel::Dev);
    }

    #[test]
    fn unknown_or_broken_channel_values_read_as_stable() {
        let home = temp_home();
        let dir = ensure_cobblify_dir(&home).unwrap();
        fs::write(
            dir.join(PREF_FILE),
            r#"{"auto_join_hypixel":true,"update_channel":"nightly"}"#,
        )
        .unwrap();
        let view = update_preferences(&home).unwrap();
        assert_eq!(view.health, PreferenceHealth::Valid);
        assert_eq!(view.update_channel, UpdateChannel::Stable);
        assert_eq!(update_channel(&home), UpdateChannel::Stable);

        // A file that does not parse never yields dev, even if the text says so.
        fs::write(dir.join(PREF_FILE), r#"{"update_channel":"dev""#).unwrap();
        assert_eq!(update_channel(&home), UpdateChannel::Stable);

        // Salvage on save keeps a stored dev channel when another field is junk.
        fs::write(
            dir.join(PREF_FILE),
            r#"{"auto_join_hypixel":"yes","update_channel":"dev"}"#,
        )
        .unwrap();
        set_auto_update(&home, true).unwrap();
        assert_eq!(update_channel(&home), UpdateChannel::Dev);
    }

    #[test]
    fn later_records_the_prompt_without_enabling_updates() {
        let home = temp_home();
        set_auto_update(&home, false).unwrap();
        let view = update_preferences(&home).unwrap();
        assert!(!view.auto_update_enabled);
        assert!(view.auto_update_prompted);
    }

    #[test]
    fn repairing_overlay_salvages_valid_auto_join_from_invalid_doc() {
        let home = temp_home();
        let dir = ensure_cobblify_dir(&home).unwrap();
        fs::write(
            dir.join(PREF_FILE),
            r#"{"auto_join_hypixel":false,"use_external_overlay":"yes"}"#,
        )
        .unwrap();
        let reply = set_use_external_overlay(&home, true).unwrap();
        assert!(matches!(
            reply,
            PreferenceSaveReply::Saved {
                auto_join_hypixel: false,
                use_external_overlay: true,
            }
        ));
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.health, PreferenceHealth::Valid);
        assert_eq!(view.auto_join_hypixel, false);
    }

    #[test]
    fn repairing_auto_join_salvages_valid_overlay_from_invalid_doc() {
        let home = temp_home();
        let dir = ensure_cobblify_dir(&home).unwrap();
        fs::write(
            dir.join(PREF_FILE),
            r#"{"auto_join_hypixel":"yes","use_external_overlay":false}"#,
        )
        .unwrap();
        let reply = set_auto_join_hypixel(&home, true).unwrap();
        assert!(matches!(
            reply,
            PreferenceSaveReply::Saved {
                auto_join_hypixel: true,
                use_external_overlay: false,
            }
        ));
    }

    #[test]
    fn unparseable_file_resets_both_to_defaults_on_save() {
        let home = temp_home();
        let dir = ensure_cobblify_dir(&home).unwrap();
        fs::write(dir.join(PREF_FILE), "{not json").unwrap();
        let reply = set_auto_join_hypixel(&home, false).unwrap();
        assert!(matches!(
            reply,
            PreferenceSaveReply::Saved {
                auto_join_hypixel: false,
                use_external_overlay: true,
            }
        ));
    }

    #[test]
    fn wrong_type_overlay_is_invalid_but_defaults_on_in_view() {
        let home = temp_home();
        let dir = ensure_cobblify_dir(&home).unwrap();
        fs::write(
            dir.join(PREF_FILE),
            r#"{"auto_join_hypixel":true,"use_external_overlay":"yes"}"#,
        )
        .unwrap();
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.health, PreferenceHealth::Invalid);
        assert_eq!(view.use_external_overlay, true);
        let locked = LockedPrefs::acquire(&home).unwrap();
        assert!(locked.read_strict().is_err());
    }

    #[test]
    fn round_trip_of_both_keys() {
        let home = temp_home();
        set_auto_join_hypixel(&home, false).unwrap();
        set_use_external_overlay(&home, false).unwrap();
        set_auto_join_hypixel(&home, true).unwrap();
        set_use_external_overlay(&home, true).unwrap();
        let locked = LockedPrefs::acquire(&home).unwrap();
        assert_eq!(locked.read_strict().unwrap(), (true, true));
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
            use_external_overlay: false,
        })
        .unwrap();
        assert_eq!(saved["status"], "saved");
        assert_eq!(saved["useExternalOverlay"], false);
        let not_saved = serde_json::to_value(PreferenceSaveReply::NotSaved {
            diagnostic: "disk".into(),
        })
        .unwrap();
        assert_eq!(not_saved["status"], "not_saved");
    }
}
