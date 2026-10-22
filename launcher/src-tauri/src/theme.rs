//! The game's own accent and font, so the launcher looks like the client you play (0.18
//! decision 8). Read-only: the mod owns `cobblify.json`.
//!
//! - Lunar: `~/.cobblify/cobblify.json` (lunar SettingsManager.settingsFile).
//! - Forge: `<instance game dir>/config/cobblify.json` (Forge's config dir).
//!
//! Tokens are passed through as written, lowercased; the frontend maps them to colours with a
//! table checked against `GuiTheme.Accent`. Anything missing or malformed reads as "no
//! preference" and the launcher uses its default.

use serde::Serialize;
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct GameTheme {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub accent: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub font: Option<String>,
}

/// Where the config of `kind` ("lunar" or "forge") lives, if it can be known.
pub fn config_path(home: &Path, kind: &str, forge_game_dir: Option<&str>) -> Option<PathBuf> {
    match kind {
        "lunar" => Some(home.join(".cobblify").join("cobblify.json")),
        "forge" => forge_game_dir.map(|dir| Path::new(dir).join("config").join("cobblify.json")),
        _ => None,
    }
}

fn token(value: Option<&serde_json::Value>) -> Option<String> {
    let t = value?.as_str()?.trim().to_ascii_lowercase();
    let ok = !t.is_empty()
        && t.len() <= 32
        && t.bytes().all(|b| b.is_ascii_lowercase() || b == b'-');
    ok.then_some(t)
}

pub fn read(path: &Path) -> GameTheme {
    let Ok(raw) = fs::read(path) else {
        return GameTheme::default();
    };
    let Ok(doc) = serde_json::from_slice::<serde_json::Value>(&raw) else {
        return GameTheme::default();
    };
    let font = token(doc.get("guiFont")).filter(|f| f == "minecraft" || f == "modern");
    GameTheme {
        accent: token(doc.get("guiAccent")),
        font,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_file(contents: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "cobblify-theme-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("cobblify.json");
        fs::write(&path, contents).unwrap();
        path
    }

    #[test]
    fn a_missing_file_has_no_preference() {
        assert_eq!(read(Path::new("/nonexistent/cobblify.json")), GameTheme::default());
    }

    #[test]
    fn an_invalid_file_has_no_preference() {
        assert_eq!(read(&temp_file("{not json")), GameTheme::default());
        assert_eq!(read(&temp_file("[1,2]")), GameTheme::default());
    }

    #[test]
    fn reads_accent_and_font() {
        let t = read(&temp_file(r#"{"guiAccent":" Pale-Blue ","guiFont":"minecraft","other":1}"#));
        assert_eq!(t.accent.as_deref(), Some("pale-blue"));
        assert_eq!(t.font.as_deref(), Some("minecraft"));
    }

    #[test]
    fn rejects_odd_values() {
        let t = read(&temp_file(r#"{"guiAccent":"<script>","guiFont":"comic"}"#));
        assert_eq!(t, GameTheme::default());
        let t = read(&temp_file(r#"{"guiAccent":42}"#));
        assert_eq!(t.accent, None);
    }

    #[test]
    fn unknown_tokens_pass_through_for_the_frontend_to_default() {
        assert_eq!(read(&temp_file(r#"{"guiAccent":"ultraviolet"}"#)).accent.as_deref(), Some("ultraviolet"));
    }

    #[test]
    fn config_paths() {
        let home = Path::new("/h");
        assert_eq!(config_path(home, "lunar", None), Some(PathBuf::from("/h/.cobblify/cobblify.json")));
        assert_eq!(config_path(home, "forge", Some("/i/.minecraft")), Some(PathBuf::from("/i/.minecraft/config/cobblify.json")));
        assert_eq!(config_path(home, "forge", None), None);
        assert_eq!(config_path(home, "other", None), None);
    }
}
