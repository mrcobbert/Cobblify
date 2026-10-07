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
        #[serde(rename = "useExternalOverlay")]
        use_external_overlay: bool,
    },
    Reconciled {
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

/// 0.17's auto-join key, kept only for 0.17. The 0.17 launcher shares this file and its
/// parser REQUIRES the key (a file without it makes 0.17 reject every launch with
/// `preference_error`), so 0.18 reads it leniently - missing or not a bool reads as `true`
/// and never makes the file invalid for 0.18 - and every 0.18 save writes it back: the
/// stored bool unchanged, or `true` where there was none. 0.18 never acts on it.
fn lenient_auto_join<'de, D: serde::Deserializer<'de>>(d: D) -> Result<bool, D::Error> {
    let value = serde_json::Value::deserialize(d)?;
    Ok(value.as_bool().unwrap_or(true))
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct PreferenceDoc {
    #[serde(default = "default_true", deserialize_with = "lenient_auto_join")]
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
    /// Updates download by default until the player has chosen (0.18 decision 14). The stored
    /// field keeps its old meaning, so a 0.17 launcher reading the same file is unaffected; a
    /// player who chose (prompted) gets exactly what they chose.
    fn effective_auto_update(&self) -> bool {
        self.auto_update_enabled || !self.auto_update_prompted
    }
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
                use_external_overlay: doc.use_external_overlay,
                health: PreferenceHealth::Valid,
                diagnostic: None,
            },
            Err(ReadOutcome::Missing) => LaunchPreferencesView {
                use_external_overlay: true,
                health: PreferenceHealth::Missing,
                diagnostic: None,
            },
            Err(ReadOutcome::Invalid(msg)) => LaunchPreferencesView {
                use_external_overlay: true,
                health: PreferenceHealth::Invalid,
                diagnostic: Some(msg),
            },
        }
    }

    pub(crate) fn read_update_view(&self) -> UpdatePreferencesView {
        match read_doc(&pref_path(&self.home)) {
            Ok(doc) => UpdatePreferencesView {
                auto_update_enabled: doc.effective_auto_update(),
                auto_update_prompted: doc.auto_update_prompted,
                update_channel: doc.channel(),
                health: PreferenceHealth::Valid,
                diagnostic: None,
            },
            // Updates download by default until the player chooses (0.18 decision 14).
            Err(ReadOutcome::Missing) => UpdatePreferencesView {
                auto_update_enabled: true,
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

    /// The stored External Overlay choice, strictly: an invalid file is an error.
    pub(crate) fn read_strict(&self) -> Result<bool, String> {
        match read_doc(&pref_path(&self.home)) {
            Ok(doc) => Ok(doc.use_external_overlay),
            Err(ReadOutcome::Missing) => Ok(true),
            Err(ReadOutcome::Invalid(msg)) => Err(msg),
        }
    }

    /// Save the External Overlay choice, preserving every other key. The
    /// others come from a lenient per-field read so a valid value survives
    /// even when another field (or the document's health) is invalid; only a
    /// file that is not a JSON object at all falls back to the defaults.
    fn write_overlay(&self, enabled: bool) -> PreferenceSaveReply {
        let path = pref_path(&self.home);
        let mut doc = read_fields_lenient(&path);
        doc.use_external_overlay = enabled;
        match write_doc(&path, &doc) {
            Ok(()) => PreferenceSaveReply::Saved {
                use_external_overlay: doc.use_external_overlay,
            },
            Err(SaveOutcome::NotSaved(msg)) => PreferenceSaveReply::NotSaved { diagnostic: msg },
            Err(SaveOutcome::Indeterminate) => PreferenceSaveReply::Indeterminate,
            Err(SaveOutcome::Reconciled(current)) => PreferenceSaveReply::Reconciled {
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
                auto_update_enabled: doc.effective_auto_update(),
                auto_update_prompted: doc.auto_update_prompted,
                update_channel: doc.channel(),
            },
            Err(SaveOutcome::NotSaved(msg)) => UpdatePreferenceSaveReply::NotSaved { diagnostic: msg },
            Err(SaveOutcome::Indeterminate) => UpdatePreferenceSaveReply::Indeterminate,
            Err(SaveOutcome::Reconciled(current)) => UpdatePreferenceSaveReply::Reconciled {
                auto_update_enabled: current.effective_auto_update(),
                auto_update_prompted: current.auto_update_prompted,
                update_channel: current.channel(),
            },
        }
    }
}

/// Per-field salvage for the save path: each known key is read
/// independently; a bool survives, anything else takes its default. This is
/// what carries 0.17's `auto_join_hypixel` through every 0.18 save.
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

pub fn set_use_external_overlay(
    home: &Path,
    enabled: bool,
) -> Result<PreferenceSaveReply, PreferenceReadError> {
    let locked = LockedPrefs::acquire(home)?;
    Ok(locked.write_overlay(enabled))
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

    fn write_raw(home: &Path, text: &str) {
        let dir = ensure_cobblify_dir(home).unwrap();
        fs::write(dir.join(PREF_FILE), text).unwrap();
    }

    fn raw(home: &Path) -> serde_json::Value {
        serde_json::from_str(&fs::read_to_string(pref_path(home)).unwrap()).unwrap()
    }

    /// The 0.17 launcher's own parser, as shipped: `auto_join_hypixel` is a REQUIRED bool.
    #[derive(Deserialize)]
    #[allow(dead_code)]
    struct Doc017 {
        auto_join_hypixel: bool,
        #[serde(default = "default_true")]
        use_external_overlay: bool,
    }

    fn parses_for_0_17(home: &Path) -> Option<bool> {
        let text = fs::read_to_string(pref_path(home)).ok()?;
        serde_json::from_str::<Doc017>(&text).ok().map(|d| d.auto_join_hypixel)
    }

    #[test]
    fn missing_defaults_on() {
        let home = temp_home();
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.use_external_overlay, true);
        assert_eq!(view.health, PreferenceHealth::Missing);
    }

    /// 0.18 never acts on the auto-join key, but the 0.17 launcher shares the file and needs
    /// it: a stored value survives every kind of 0.18 save unchanged.
    #[test]
    fn a_0_18_save_preserves_the_0_17_auto_join_key() {
        let home = temp_home();
        write_raw(&home, r#"{"auto_join_hypixel":false,"use_external_overlay":true}"#);
        set_use_external_overlay(&home, false).unwrap();
        assert_eq!(raw(&home)["auto_join_hypixel"], false);
        set_auto_update(&home, true).unwrap();
        assert_eq!(raw(&home)["auto_join_hypixel"], false);
        set_update_channel(&home, UpdateChannel::Dev).unwrap();
        assert_eq!(raw(&home)["auto_join_hypixel"], false);
        assert_eq!(parses_for_0_17(&home), Some(false), "0.17 still reads the file");
        assert_eq!(raw(&home)["use_external_overlay"], false);
    }

    /// A file without the key (written by hand, or by nothing yet) gets it as `true` on the
    /// first 0.18 save, so 0.17 can read the file again. 0.18 itself reads such a file as
    /// valid.
    #[test]
    fn a_missing_auto_join_key_is_written_as_true() {
        let home = temp_home();
        write_raw(&home, r#"{"use_external_overlay":false}"#);
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.health, PreferenceHealth::Valid, "0.18 reads it leniently");
        assert_eq!(view.use_external_overlay, false);
        assert_eq!(parses_for_0_17(&home), None, "0.17 cannot read it yet");
        set_auto_update(&home, false).unwrap();
        assert_eq!(raw(&home)["auto_join_hypixel"], true);
        assert_eq!(parses_for_0_17(&home), Some(true));

        let fresh = temp_home();
        set_use_external_overlay(&fresh, true).unwrap();
        assert_eq!(raw(&fresh)["auto_join_hypixel"], true, "a brand-new file carries it too");
    }

    /// A wrong-typed auto-join value does not make the file invalid for 0.18 (it is never
    /// used); a save repairs it to `true` for 0.17.
    #[test]
    fn a_wrong_type_auto_join_key_is_lenient_and_repaired_on_save() {
        let home = temp_home();
        write_raw(&home, r#"{"auto_join_hypixel":"yes","use_external_overlay":false}"#);
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.health, PreferenceHealth::Valid);
        assert_eq!(view.use_external_overlay, false);
        let locked = LockedPrefs::acquire(&home).unwrap();
        assert_eq!(locked.read_strict().unwrap(), false);
        drop(locked);
        set_use_external_overlay(&home, false).unwrap();
        assert_eq!(raw(&home)["auto_join_hypixel"], true);
    }

    #[test]
    fn first_save_persists() {
        let home = temp_home();
        let reply = set_use_external_overlay(&home, false).unwrap();
        assert!(matches!(
            reply,
            PreferenceSaveReply::Saved {
                use_external_overlay: false,
            }
        ));
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.use_external_overlay, false);
        assert_eq!(view.health, PreferenceHealth::Valid);
    }

    #[test]
    fn old_one_key_file_reads_overlay_default_on() {
        let home = temp_home();
        write_raw(&home, r#"{"auto_join_hypixel":false}"#);
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.health, PreferenceHealth::Valid);
        assert_eq!(view.use_external_overlay, true);
        let locked = LockedPrefs::acquire(&home).unwrap();
        assert_eq!(locked.read_strict().unwrap(), true);
    }

    #[test]
    fn old_launch_preferences_default_auto_update_on_until_chosen() {
        let home = temp_home();
        write_raw(&home, r#"{"auto_join_hypixel":false,"use_external_overlay":true}"#);
        let view = update_preferences(&home).unwrap();
        assert!(view.auto_update_enabled);
        assert!(!view.auto_update_prompted);
        assert_eq!(view.health, PreferenceHealth::Valid);
        // The file itself is not rewritten by reading it.
        let text = fs::read_to_string(pref_path(&home)).unwrap();
        assert!(!text.contains("auto_update"));
    }

    #[test]
    fn a_missing_file_defaults_auto_update_on() {
        let home = temp_home();
        let view = update_preferences(&home).unwrap();
        assert!(view.auto_update_enabled);
        assert!(!view.auto_update_prompted);
    }

    #[test]
    fn choosing_off_is_respected_and_choosing_on_stays_on() {
        let home = temp_home();
        let off = set_auto_update(&home, false).unwrap();
        assert!(matches!(off, UpdatePreferenceSaveReply::Saved { auto_update_enabled: false, .. }));
        assert!(!update_preferences(&home).unwrap().auto_update_enabled);
        set_auto_update(&home, true).unwrap();
        assert!(update_preferences(&home).unwrap().auto_update_enabled);
    }

    #[test]
    fn switching_channel_before_choosing_keeps_the_default_on() {
        let home = temp_home();
        let reply = set_update_channel(&home, UpdateChannel::Dev).unwrap();
        assert!(matches!(reply, UpdatePreferenceSaveReply::Saved { auto_update_enabled: true, .. }));
    }

    #[test]
    fn update_consent_preserves_the_launch_preferences() {
        let home = temp_home();
        write_raw(&home, r#"{"auto_join_hypixel":false}"#);
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
        assert!(!launch.use_external_overlay);
        assert_eq!(raw(&home)["auto_join_hypixel"], false);
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
        write_raw(&home, r#"{"auto_join_hypixel":false}"#);
        set_use_external_overlay(&home, false).unwrap();
        set_auto_update(&home, true).unwrap();
        set_update_channel(&home, UpdateChannel::Dev).unwrap();
        let launch = launch_preferences(&home).unwrap();
        assert!(!launch.use_external_overlay);
        assert_eq!(raw(&home)["auto_join_hypixel"], false);
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
        write_raw(&home, r#"{"auto_join_hypixel":true,"update_channel":"nightly"}"#);
        let view = update_preferences(&home).unwrap();
        assert_eq!(view.health, PreferenceHealth::Valid);
        assert_eq!(view.update_channel, UpdateChannel::Stable);
        assert_eq!(update_channel(&home), UpdateChannel::Stable);

        // A file that does not parse never yields dev, even if the text says so.
        write_raw(&home, r#"{"update_channel":"dev""#);
        assert_eq!(update_channel(&home), UpdateChannel::Stable);

        // Salvage on save keeps a stored dev channel when another field is junk.
        write_raw(&home, r#"{"use_external_overlay":"yes","update_channel":"dev"}"#);
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
        write_raw(&home, r#"{"auto_join_hypixel":false,"use_external_overlay":"yes"}"#);
        let reply = set_use_external_overlay(&home, true).unwrap();
        assert!(matches!(
            reply,
            PreferenceSaveReply::Saved {
                use_external_overlay: true,
            }
        ));
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.health, PreferenceHealth::Valid);
        assert_eq!(raw(&home)["auto_join_hypixel"], false);
    }

    #[test]
    fn unparseable_file_resets_to_defaults_on_save() {
        let home = temp_home();
        write_raw(&home, "{not json");
        let reply = set_use_external_overlay(&home, false).unwrap();
        assert!(matches!(
            reply,
            PreferenceSaveReply::Saved {
                use_external_overlay: false,
            }
        ));
        assert_eq!(raw(&home)["auto_join_hypixel"], true);
    }

    #[test]
    fn wrong_type_overlay_is_invalid_but_defaults_on_in_view() {
        let home = temp_home();
        write_raw(&home, r#"{"auto_join_hypixel":true,"use_external_overlay":"yes"}"#);
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.health, PreferenceHealth::Invalid);
        assert_eq!(view.use_external_overlay, true);
        let locked = LockedPrefs::acquire(&home).unwrap();
        assert!(locked.read_strict().is_err());
    }

    #[test]
    fn round_trip_of_the_overlay_key() {
        let home = temp_home();
        set_use_external_overlay(&home, false).unwrap();
        {
            let locked = LockedPrefs::acquire(&home).unwrap();
            assert_eq!(locked.read_strict().unwrap(), false);
        }
        set_use_external_overlay(&home, true).unwrap();
        let locked = LockedPrefs::acquire(&home).unwrap();
        assert_eq!(locked.read_strict().unwrap(), true);
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
        write_raw(&home, "{not json");
        let view = launch_preferences(&home).unwrap();
        assert_eq!(view.health, PreferenceHealth::Invalid);
        assert_eq!(view.use_external_overlay, true);
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
        let err = set_use_external_overlay(&home, false).unwrap_err();
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
        assert_eq!(view.use_external_overlay, true);
    }

    #[test]
    fn save_reply_serializes_snake_case_tags() {
        let saved = serde_json::to_value(PreferenceSaveReply::Saved {
            use_external_overlay: false,
        })
        .unwrap();
        assert_eq!(saved["status"], "saved");
        assert_eq!(saved["useExternalOverlay"], false);
        assert!(saved.get("autoJoinHypixel").is_none(), "no auto-join on the wire");
        let not_saved = serde_json::to_value(PreferenceSaveReply::NotSaved {
            diagnostic: "disk".into(),
        })
        .unwrap();
        assert_eq!(not_saved["status"], "not_saved");
    }
}
