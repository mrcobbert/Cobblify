//! Mojang's vanilla Minecraft 1.8.9 client jar, which Weave needs on every launch with a mod.
//!
//! Weave 1.3.3 builds its mappings against the vanilla jar and reads classes out of it at run
//! time. It looks for it only at `~/Library/Application Support/minecraft/versions/1.8.9/1.8.9.jar`
//! (macOS) and on the game's classpath; Lunar's classpath never has it, so without that file Weave
//! stops the game ("Could not find vanilla jar for version 1.8.9"). The official Minecraft Launcher
//! puts it there the first time 1.8.9 is played in it, which is why most machines have it.
//!
//! When it is missing, Cobblify downloads the same file from Mojang's own server (the URL and
//! checksum from Mojang's 1.8.9 version manifest) and puts it there - the file the Minecraft
//! Launcher would write, byte for byte. The file belongs to that folder, not to Cobblify:
//! an existing one is never replaced, and uninstall never deletes it.

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

/// Mojang's 1.8.9 `downloads.client` (piston-meta .../1.8.9.json).
const URL: &str =
    "https://launcher.mojang.com/v1/objects/3870888a6c3d349d3771a3e9d16c9bf5e076b908/client.jar";
const SHA1: &str = "3870888a6c3d349d3771a3e9d16c9bf5e076b908";
const SIZE: usize = 8_461_484;

/// Where Weave looks first on macOS.
pub fn jar_path(home: &Path) -> PathBuf {
    home.join("Library/Application Support/minecraft/versions/1.8.9/1.8.9.jar")
}

/// Whether Weave will find the jar. Always true off macOS (not handled there yet).
pub fn present(home: &Path) -> bool {
    !cfg!(target_os = "macos") || jar_path(home).exists()
}

/// Downloads the jar into place when it is missing. `Ok(true)` when it was downloaded.
pub fn ensure(home: &Path, fetch: &dyn Fn(&str) -> Result<Vec<u8>, String>) -> Result<bool, String> {
    if present(home) {
        return Ok(false);
    }
    ensure_at(&jar_path(home), fetch, SHA1, SIZE)
}

fn ensure_at(
    dest: &Path,
    fetch: &dyn Fn(&str) -> Result<Vec<u8>, String>,
    sha1: &str,
    size: usize,
) -> Result<bool, String> {
    if dest.exists() {
        return Ok(false);
    }
    let bytes = fetch(URL).map_err(|_| "Cobblify couldn't download Minecraft 1.8.9 from Mojang.".to_string())?;
    if bytes.len() != size || crate::prism_java::sha1_hex(&bytes) != sha1 {
        return Err("The Minecraft 1.8.9 download from Mojang did not verify.".to_string());
    }
    let dir = dest.parent().ok_or("No folder for Minecraft 1.8.9.")?;
    fs::create_dir_all(dir).map_err(|e| format!("Cannot create {}: {e}", dir.display()))?;
    let tmp = dir.join(".1.8.9.jar.cobblify-tmp");
    let written = fs::File::create(&tmp)
        .and_then(|mut f| {
            f.write_all(&bytes)?;
            f.sync_all()
        })
        .map_err(|e| format!("Cannot write {}: {e}", tmp.display()));
    if let Err(e) = written {
        let _ = fs::remove_file(&tmp);
        return Err(e);
    }
    // Never over a jar that appeared meanwhile (the Minecraft Launcher's own, say).
    let placed = crate::prism::rename_no_replace(&tmp, dest);
    let _ = fs::remove_file(&tmp);
    match placed {
        Ok(()) => Ok(true),
        Err(_) if dest.exists() => Ok(false),
        Err(e) => Err(format!("Cannot write {}: {e}", dest.display())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const BYTES: &[u8] = b"not really minecraft";

    fn sha1() -> String {
        crate::prism_java::sha1_hex(BYTES)
    }

    #[test]
    fn a_missing_jar_is_downloaded_verified_and_put_in_place() {
        let dir = tempfile::tempdir().unwrap();
        let dest = jar_path(dir.path());
        let fetch = |url: &str| {
            assert_eq!(url, URL);
            Ok(BYTES.to_vec())
        };
        assert_eq!(ensure_at(&dest, &fetch, &sha1(), BYTES.len()), Ok(true));
        assert_eq!(fs::read(&dest).unwrap(), BYTES);
        let left: Vec<_> = fs::read_dir(dest.parent().unwrap()).unwrap().map(|e| e.unwrap().file_name()).collect();
        assert_eq!(left, vec![std::ffi::OsString::from("1.8.9.jar")], "no temp file left");
    }

    #[test]
    fn an_existing_jar_is_never_fetched_or_replaced() {
        let dir = tempfile::tempdir().unwrap();
        let dest = jar_path(dir.path());
        fs::create_dir_all(dest.parent().unwrap()).unwrap();
        fs::write(&dest, b"the launcher's own").unwrap();
        let fetch = |_: &str| -> Result<Vec<u8>, String> { panic!("no download") };
        assert_eq!(ensure_at(&dest, &fetch, &sha1(), BYTES.len()), Ok(false));
        assert_eq!(fs::read(&dest).unwrap(), b"the launcher's own");
    }

    #[test]
    fn a_failed_or_wrong_download_writes_nothing() {
        let dir = tempfile::tempdir().unwrap();
        let dest = jar_path(dir.path());
        let offline = |_: &str| -> Result<Vec<u8>, String> { Err("offline".into()) };
        assert!(ensure_at(&dest, &offline, &sha1(), BYTES.len()).unwrap_err().contains("couldn't download"));
        let tampered = |_: &str| Ok(b"tampered!!!!!!!!!!!!".to_vec());
        assert!(ensure_at(&dest, &tampered, &sha1(), BYTES.len()).unwrap_err().contains("did not verify"));
        let short = |_: &str| Ok(BYTES[..4].to_vec());
        assert!(ensure_at(&dest, &short, &sha1(), BYTES.len()).is_err());
        assert!(!dest.exists());
    }

    #[test]
    fn the_pinned_file_is_mojangs_1_8_9_client() {
        // Mojang's manifest values; a typo here would make every download fail to verify.
        assert_eq!(SHA1.len(), 40);
        assert!(URL.contains(SHA1) && URL.ends_with("/client.jar"));
        assert_eq!(SIZE, 8_461_484);
    }
}
