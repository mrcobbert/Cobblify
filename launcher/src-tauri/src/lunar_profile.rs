//! Which Lunar profile is selected, and switching it to 1.8.9 (macOS).
//!
//! Lunar starts the profile in `settings.gameProfile` (`launcher.json`, a profile id); its
//! profiles are rows of `~/.lunarclient/db/profiles.db` (SQLite, WAL). Lunar reads that key
//! when it starts and keeps its selection in memory after that, so the key is only written
//! while Lunar is fully quit (`lunar_config::select_profile_with`), and a write never makes a
//! running Lunar change version.
//!
//! The database is only ever READ, through `/usr/bin/sqlite3 -readonly` (no new dependency),
//! for at most `LOOKUP_TIMEOUT`, and any failure reads as "unknown": Cobblify then launches as
//! before and the version guard keeps another version harmless. When the WAL file holds
//! nothing, every row is in the main file and it is opened `immutable` (no locks, no side
//! files); otherwise a plain read-only connection reads the WAL too.
//!
//! Only a plain Lunar 1.8 profile is ever selected: Lunar type, 1.8.9, no loaders, not Badlion
//! or a modpack, and no JVM arguments of its own (those replace Lunar's global ones, which
//! would drop the guard). Exactly one such profile, or nothing is written.

use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Duration;

const SQLITE: &str = "/usr/bin/sqlite3";
const LOOKUP_TIMEOUT: Duration = Duration::from_secs(2);

/// The plain Lunar 1.8.9 profiles.
const PLAIN_1_8_9: &str = "SELECT id FROM profiles WHERE type='lunar' AND major_game_version='1.8' \
     AND game_version='1.8.9' AND loaders='[]' AND is_badlion=0 AND provider IS NULL \
     AND jvm_arguments IS NULL;";

pub fn profiles_db(home: &Path) -> PathBuf {
    home.join(".lunarclient/db/profiles.db")
}

/// The one plain Lunar 1.8.9 profile's id; None when there is none, several, or the database
/// cannot be read.
pub fn plain_1_8_9(home: &Path) -> Option<String> {
    if !cfg!(target_os = "macos") {
        return None;
    }
    one_id(&query(&profiles_db(home), PLAIN_1_8_9)?)
}

/// Whether profile `id` is a 1.8.9 one, of any kind (Some(false): another version). None when
/// it is not a saved profile or the database cannot be read.
pub fn is_1_8_9(home: &Path, id: &str) -> Option<bool> {
    if !cfg!(target_os = "macos") || !is_id(id) {
        return None;
    }
    let sql = format!("SELECT game_version FROM profiles WHERE id='{id}';");
    let out = query(&profiles_db(home), &sql)?;
    let mut rows = out.lines().filter(|l| !l.is_empty());
    let version = rows.next()?;
    rows.next().is_none().then(|| version == "1.8.9")
}

/// Exactly one id-shaped row, else None.
fn one_id(out: &str) -> Option<String> {
    let mut rows = out.lines().filter(|l| !l.is_empty());
    let id = rows.next()?;
    (rows.next().is_none() && is_id(id)).then(|| id.to_string())
}

/// A profile id as Lunar makes them (a UUID; older ones `<category>-<slug>`): only these
/// characters ever reach SQL or launcher.json.
pub fn is_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_' || b == b'.')
}

/// One read-only query; its stdout, or None on any failure.
fn query(db: &Path, sql: &str) -> Option<String> {
    if !db.is_file() {
        return None;
    }
    let wal = PathBuf::from(format!("{}-wal", db.display()));
    let wal_has_rows = std::fs::metadata(&wal).is_ok_and(|m| m.len() > 0);
    let target = if wal_has_rows {
        db.to_string_lossy().into_owned()
    } else {
        format!("file:{}?immutable=1", uri_path(db))
    };
    let mut cmd = Command::new(SQLITE);
    cmd.args(["-readonly", "-bail", "-batch", "-noheader", "-list"])
        .arg(target)
        .arg(sql);
    let out = crate::lunar_detect::run_bounded(cmd, LOOKUP_TIMEOUT)?;
    String::from_utf8(out).ok()
}

/// A path for an SQLite `file:` URI: everything but unreserved characters and `/` escaped.
fn uri_path(path: &Path) -> String {
    let mut out = String::new();
    for b in path.to_string_lossy().bytes() {
        if b.is_ascii_alphanumeric() || b"-._~/".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

#[cfg(all(test, target_os = "macos"))]
mod tests {
    use super::*;

    const SCHEMA: &str = "PRAGMA journal_mode=WAL; CREATE TABLE profiles (id TEXT PRIMARY KEY, \
        type TEXT NOT NULL, major_game_version TEXT NOT NULL, game_version TEXT NOT NULL, \
        loaders TEXT NOT NULL DEFAULT '[]', is_badlion INTEGER NOT NULL DEFAULT 0, provider TEXT, \
        jvm_arguments TEXT);";

    fn db(home: &Path, rows: &str) {
        let path = profiles_db(home);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        let st = Command::new(SQLITE)
            .arg(&path)
            .arg(format!("{SCHEMA} {rows}"))
            .output()
            .unwrap();
        assert!(st.status.success(), "{}", String::from_utf8_lossy(&st.stderr));
    }

    const ID: &str = "3f2c1a4e-9b7d-4c1e-8f00-123456789abc";

    #[test]
    fn the_one_plain_profile_is_found_and_read_without_writing() {
        let home = tempfile::tempdir().unwrap();
        db(
            home.path(),
            &format!(
                "INSERT INTO profiles(id,type,major_game_version,game_version) VALUES \
                 ('{ID}','lunar','1.8','1.8.9'),('other','lunar','26.3','26.3');"
            ),
        );
        let before = std::fs::read(profiles_db(home.path())).unwrap();
        assert_eq!(plain_1_8_9(home.path()), Some(ID.to_string()));
        assert_eq!(is_1_8_9(home.path(), ID), Some(true));
        assert_eq!(is_1_8_9(home.path(), "other"), Some(false));
        assert_eq!(is_1_8_9(home.path(), "missing"), None);
        assert_eq!(is_1_8_9(home.path(), "x'; DROP TABLE profiles; --"), None);
        assert_eq!(std::fs::read(profiles_db(home.path())).unwrap(), before);
    }

    #[test]
    fn the_db_is_read_with_or_without_its_wal_files() {
        let home = tempfile::tempdir().unwrap();
        db(
            home.path(),
            &format!("INSERT INTO profiles(id,type,major_game_version,game_version) VALUES ('{ID}','lunar','1.8','1.8.9');"),
        );
        let db = profiles_db(home.path());
        let _ = std::fs::remove_file(format!("{}-wal", db.display()));
        let _ = std::fs::remove_file(format!("{}-shm", db.display()));
        assert_eq!(plain_1_8_9(home.path()), Some(ID.to_string()));
        let left: Vec<_> = std::fs::read_dir(db.parent().unwrap()).unwrap().map(|e| e.unwrap().file_name()).collect();
        assert_eq!(left, vec![std::ffi::OsString::from("profiles.db")], "no side files made");
    }

    #[test]
    fn several_or_unusual_1_8_9_profiles_select_nothing() {
        let home = tempfile::tempdir().unwrap();
        db(
            home.path(),
            "INSERT INTO profiles(id,type,major_game_version,game_version,jvm_arguments) VALUES \
             ('a','lunar','1.8','1.8.9',''); \
             INSERT INTO profiles(id,type,major_game_version,game_version,loaders) VALUES \
             ('b','lunar','1.8','1.8.9','[\"forge\"]'); \
             INSERT INTO profiles(id,type,major_game_version,game_version) VALUES ('c','vanilla','1.8','1.8.9');",
        );
        assert_eq!(plain_1_8_9(home.path()), None, "an override, a loader, not Lunar's type");
        let two = tempfile::tempdir().unwrap();
        db(
            two.path(),
            "INSERT INTO profiles(id,type,major_game_version,game_version) VALUES \
             ('a','lunar','1.8','1.8.9'),('b','lunar','1.8','1.8.9');",
        );
        assert_eq!(plain_1_8_9(two.path()), None, "two is ambiguous");
        assert_eq!(plain_1_8_9(tempfile::tempdir().unwrap().path()), None, "no database");
    }

    #[test]
    fn a_path_with_a_space_or_a_question_mark_is_escaped() {
        assert_eq!(uri_path(Path::new("/Users/a b/x?y#.db")), "/Users/a%20b/x%3Fy%23.db");
    }
}
