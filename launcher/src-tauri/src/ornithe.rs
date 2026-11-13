//! The Prism instance Cobblify makes for its Ornithe build: Minecraft 1.8.9 on Ornithe's
//! Fabric Loader, Java 25, a pinned set of performance mods, and the Cobblify jar.
//!
//! * Profile: the Ornithe installer's 1.8.9 Gen2 Fabric output (ornithe-installer-rs
//!   `src/actions/prism_pack.rs` @ 7c84dcc) minus Flap, embedded here - no runtime call to
//!   Ornithe's meta. Components: the `org.lwjgl` 2.9.4+legacyfabric.15 placeholder, a local
//!   `net.minecraft` patch (`profile/ornithe-net.minecraft.json`, generated from Ornithe's gen2
//!   1.8.9 manifest, sha1 370e5c4d…) saying `compatibleJavaMajors: [25]`,
//!   `compatibleJavaName: java-runtime-epsilon`, `+traits: [FirstThreadOnMacOS]` and the two
//!   `-Dfabric.*` JVM args, the calamus gen2 intermediary patch, Fabric Loader 0.20.1 (from
//!   Prism's meta) and the nine library upgrades of Ornithe's `/v3/versions/gen2/libraries/1.8.9`.
//! * `instance.cfg`: Cobblify's own shape (`prism::made_instance_cfg`), named [`NAME`]; Java 25
//!   is pinned later (`prism_java::pin_strict`).
//! * Marker: [`MARKER`] in the instance folder says Cobblify made it and records every file
//!   Cobblify placed in `mods/` (name + sha512), so an update replaces or removes only those,
//!   never a mod the player added. A re-run finds the instance by it instead of making `(1)`.
//! * Mods: [`PINS`] - URL, sha512, size, platform. Downloaded into [`DOWNLOADS`] (checked by
//!   size and sha512), then placed with `install::stage_verified`/`commit`. Argentum comes only
//!   from its GitHub release: a missing asset is a failure, never a mirror.
//! * Target: the `ornithe` key of `~/.cobblify/launcher-targets.json`; every other key (the
//!   Forge target included) is kept as it is.

use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256, Sha512};

use crate::install::{commit, stage_verified};
use crate::prism;
pub use crate::resources::OrnitheJar;

/// The made instance's name and first-choice folder name.
pub const NAME: &str = "Cobblify 1.8.9 Ornithe";
/// In the instance folder: Cobblify made it, and what Cobblify placed in its `mods/`.
pub const MARKER: &str = ".cobblify-ornithe.json";
/// In the instance folder: verified downloads waiting to be placed.
pub const DOWNLOADS: &str = ".cobblify-downloads";
pub const MINECRAFT: &str = "1.8.9";
pub const LWJGL: &str = "2.9.4+legacyfabric.15";
pub const LOADER: &str = "0.20.1";
pub const INTERMEDIARY: &str = "net.ornithemc:calamus-intermediary-gen2:1.8.9";
pub const ORNITHE_MAVEN: &str = "https://maven.ornithemc.net/releases";
/// Bumped whenever the embedded profile changes, so a saved target asks for a new setup.
const PROFILE_REVISION: &str = "ornithe-gen2-1.8.9-loader-0.20.1-r1";
const MINECRAFT_PATCH: &str = include_str!("profile/ornithe-net.minecraft.json");

/// Ornithe's gen2 library upgrades for 1.8.9 (`name`, maven), in its order.
pub const LIBRARY_UPGRADES: [(&str, &str); 9] = [
    ("org.slf4j:slf4j-api:2.0.17", "https://libraries.minecraft.net/"),
    ("org.apache.logging.log4j:log4j-slf4j2-impl:2.19.0", "https://libraries.minecraft.net/"),
    ("org.apache.logging.log4j:log4j-api:2.19.0", "https://libraries.minecraft.net/"),
    ("org.apache.logging.log4j:log4j-core:2.19.0", "https://libraries.minecraft.net/"),
    ("it.unimi.dsi:fastutil:8.5.9", "https://libraries.minecraft.net/"),
    ("com.google.code.gson:gson:2.10", "https://libraries.minecraft.net/"),
    ("net.ornithemc:log4j-patch:1.0.0", "https://maven.ornithemc.net/releases/"),
    ("net.sf.jopt-simple:jopt-simple:5.0.4", "https://libraries.minecraft.net/"),
    ("com.mojang:brigadier:1.3.11", "https://libraries.minecraft.net/"),
];

// ── the profile ─────────────────────────────────────────────────────────────────

pub fn instance_cfg() -> String {
    [
        "[General]",
        "ConfigVersion=1.3",
        "InstanceType=OneSix",
        &format!("name={}", prism::ini_value(NAME)),
        "iconKey=default",
        "",
    ]
    .join("\n")
}

/// `group:artifact:version` -> (uid `group.artifact`, artifact, version).
fn library_parts(name: &str) -> (String, &str, &str) {
    let mut parts = name.splitn(3, ':');
    let group = parts.next().unwrap_or("");
    let artifact = parts.next().unwrap_or("");
    let version = parts.next().unwrap_or("");
    (format!("{group}.{artifact}"), artifact, version)
}

/// Prism's JSON shape: keys sorted, 4-space indentation, trailing newline.
fn prism_json(v: serde_json::Value) -> String {
    let mut out = Vec::new();
    let formatter = serde_json::ser::PrettyFormatter::with_indent(b"    ");
    let mut ser = serde_json::Serializer::with_formatter(&mut out, formatter);
    serde::Serialize::serialize(&prism::sort_keys(v), &mut ser).expect("serializing a literal");
    let mut text = String::from_utf8(out).expect("serde_json writes UTF-8");
    text.push('\n');
    text
}

pub fn mmc_pack() -> String {
    let mut components = vec![
        serde_json::json!({
            "cachedName": "LWJGL 2", "cachedVersion": LWJGL, "cachedVolatile": true,
            "dependencyOnly": true, "uid": "org.lwjgl", "version": LWJGL
        }),
        serde_json::json!({
            "cachedName": "Minecraft",
            "cachedRequires": [ { "suggests": LWJGL, "uid": "org.lwjgl" } ],
            "cachedVersion": MINECRAFT, "important": true, "uid": "net.minecraft",
            "version": MINECRAFT
        }),
        serde_json::json!({
            "cachedName": "Intermediary Mappings",
            "cachedRequires": [ { "equals": MINECRAFT, "uid": "net.minecraft" } ],
            "cachedVersion": MINECRAFT, "dependencyOnly": true,
            "uid": "net.fabricmc.intermediary", "version": MINECRAFT
        }),
        serde_json::json!({
            "cachedName": "Fabric Loader",
            "cachedRequires": [ { "uid": "net.fabricmc.intermediary" } ],
            "cachedVersion": LOADER, "uid": "net.fabricmc.fabric-loader", "version": LOADER
        }),
    ];
    for (name, _) in LIBRARY_UPGRADES {
        let (uid, artifact, version) = library_parts(name);
        components.push(serde_json::json!({
            "cachedName": artifact, "cachedVersion": version, "uid": uid
        }));
    }
    prism_json(serde_json::json!({ "components": components, "formatVersion": 1 }))
}

/// Every file of a new instance (relative path, bytes), marker included.
pub fn instance_files() -> Vec<(String, Vec<u8>)> {
    let mut files = vec![
        (prism::INSTANCE_CFG.to_string(), instance_cfg().into_bytes()),
        (prism::PACK_FILE.to_string(), mmc_pack().into_bytes()),
        ("patches/net.minecraft.json".to_string(), MINECRAFT_PATCH.as_bytes().to_vec()),
        (
            "patches/net.fabricmc.intermediary.json".to_string(),
            prism_json(serde_json::json!({
                "formatVersion": 1,
                "libraries": [ { "name": INTERMEDIARY, "url": ORNITHE_MAVEN } ],
                "name": "Intermediary Mappings",
                "requires": [ { "equals": MINECRAFT, "uid": "net.minecraft" } ],
                "type": "release", "uid": "net.fabricmc.intermediary", "version": MINECRAFT
            }))
            .into_bytes(),
        ),
        (
            "patches/org.lwjgl.json".to_string(),
            prism_json(serde_json::json!({
                "formatVersion": 1, "name": "LWJGL 2", "type": "release",
                "uid": "org.lwjgl", "version": LWJGL
            }))
            .into_bytes(),
        ),
    ];
    for (name, url) in LIBRARY_UPGRADES {
        let (uid, artifact, version) = library_parts(name);
        files.push((
            format!("patches/{uid}.json"),
            prism_json(serde_json::json!({
                "formatVersion": 1,
                "libraries": [ { "name": name, "url": url } ],
                "name": artifact, "type": "release", "uid": uid, "version": version
            }))
            .into_bytes(),
        ));
    }
    files.push((MARKER.to_string(), marker_json(&[])));
    files
}

/// Makes the instance in `instances`, staged and renamed in once (`NAME`, `NAME(1)`, ...).
pub fn make_instance(instances: &Path) -> Result<PathBuf, String> {
    prism::make_instance_from(instances, NAME, &instance_files())
}

/// The game folder of a made instance (Prism's rule).
pub fn game_dir(root: &Path) -> PathBuf {
    crate::forge::prism_game_dir(root)
}

/// A Cobblify-made Ornithe instance: our marker, `instance.cfg`, and a pack with Minecraft
/// 1.8.9 and Fabric Loader (no Forge).
pub fn is_ornithe_instance(root: &Path) -> bool {
    if read_marker(root).is_none() || !root.join(prism::INSTANCE_CFG).is_file() {
        return false;
    }
    let Some(pack) = fs::read(root.join(prism::PACK_FILE))
        .ok()
        .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
    else {
        return false;
    };
    let comps = pack
        .get("components")
        .and_then(|c| c.as_array())
        .cloned()
        .unwrap_or_default();
    let has = |uid: &str, version: Option<&str>| {
        comps.iter().any(|c| {
            c.get("uid").and_then(|u| u.as_str()) == Some(uid)
                && version.is_none_or(|v| c.get("version").and_then(|x| x.as_str()) == Some(v))
        })
    };
    has("net.minecraft", Some(MINECRAFT))
        && has("net.fabricmc.fabric-loader", None)
        && !has("net.minecraftforge", None)
}

/// The Cobblify-made Ornithe instance in `instances`, if any (first by name).
pub fn find_made(instances: &Path) -> Option<PathBuf> {
    let mut dirs: Vec<PathBuf> = fs::read_dir(instances)
        .ok()?
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_dir())
        .collect();
    dirs.sort();
    dirs.into_iter().find(|d| is_ornithe_instance(d))
}

// ── the marker and its record ───────────────────────────────────────────────────

/// One file Cobblify placed in the instance's `mods/`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Managed {
    pub name: String,
    pub sha512: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct Marker {
    #[serde(rename = "formatVersion")]
    format_version: u32,
    made: String,
    #[serde(default)]
    managed: Vec<Managed>,
}

fn marker_json(managed: &[Managed]) -> Vec<u8> {
    let mut bytes = serde_json::to_vec_pretty(&Marker {
        format_version: 1,
        made: "ornithe".to_string(),
        managed: managed.to_vec(),
    })
    .expect("serializing the marker");
    bytes.push(b'\n');
    bytes
}

fn read_marker(root: &Path) -> Option<Marker> {
    let bytes = fs::read(root.join(MARKER)).ok()?;
    serde_json::from_slice::<Marker>(&bytes)
        .ok()
        .filter(|m| m.made == "ornithe")
}

/// The files Cobblify placed, per the marker (empty when there is none).
pub fn managed(root: &Path) -> Vec<Managed> {
    read_marker(root).map(|m| m.managed).unwrap_or_default()
}

fn write_managed(root: &Path, managed: &[Managed]) -> Result<(), String> {
    prism::write_atomic(&root.join(MARKER), &marker_json(managed))
}

// ── the pinned mods ─────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Os {
    Mac,
    Windows,
}

impl Os {
    pub fn host() -> Os {
        if cfg!(windows) {
            Os::Windows
        } else {
            Os::Mac
        }
    }
}

/// Which platforms a pin is for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Only {
    All,
    Mac,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Pin {
    /// "Pylon 0.2.8", for messages.
    pub name: &'static str,
    /// The file name in `mods/`.
    pub file: &'static str,
    pub url: &'static str,
    pub sha512: &'static str,
    pub size: u64,
    pub only: Only,
    /// Where it comes from, for messages ("its GitHub release").
    pub from: &'static str,
}

/// Every mod the instance gets besides Cobblify. Hashes verified on the downloaded bytes
/// (launcher research §3; OSL 0.23.1 = the version `ornithe/build.gradle.kts` compiles
/// against, Modrinth version 840EtTTj, checked 2026-11-12).
pub const PINS: [Pin; 5] = [
    Pin {
        name: "Pylon 0.2.8",
        file: "pylon-0.2.8.jar",
        url: "https://cdn.modrinth.com/data/wx17t6U9/versions/zz9ReJww/pylon-0.2.8.jar",
        sha512: "d993bbbc1bcbc42698ec4d3685a849b4e247298435513249b96f51fb2e3756db7df24809305643dd1e4dcb623b81c26d68cb0a404f23cb5f71341e6f10a83cfc",
        size: 15_312_680,
        only: Only::All,
        from: "Modrinth",
    },
    Pin {
        name: "Argentum 1.0.3-beta.8",
        file: "argentum-1.0.3-beta.8.jar",
        url: "https://github.com/Polyfrost/Argentum/releases/download/v1.0.3-beta.8/argentum-1.0.3-beta.8.jar",
        sha512: "991de0675a595a7dcf1629e3eaace35d05bd825f1bce2c0554013c6d10f13fa2b33f538ae13f95a508328a46c92612041fd14c2933e94ef9979f6e7377986b67",
        size: 1_900_696,
        only: Only::All,
        from: "its GitHub release",
    },
    Pin {
        name: "Sarcio 1.2.0-beta.1",
        file: "Sarcio-1.2.0-beta.1.jar",
        url: "https://github.com/Polyfrost/Sarcio/releases/download/v1.2.0-beta.1/Sarcio-1.2.0-beta.1.jar",
        sha512: "92b6830f4609745836880446c8e71ec223fb9f34f2a1acc6d5379d70050b01fc3b1f7f1e5b02d5df50d495d2bcc4a15e15a26373f48caab209d961cd3afe8a00",
        size: 3_197_918,
        only: Only::All,
        from: "its GitHub release",
    },
    Pin {
        name: "Ornithe Standard Libraries 0.23.1",
        file: "ornithe-standard-libraries-0.23.1.jar",
        url: "https://cdn.modrinth.com/data/EHGtwpJv/versions/840EtTTj/ornithe-standard-libraries-0.23.1.jar",
        sha512: "85210284ba82c90cefe06f0325cc3cac0497faf341cc5494843e4e1e6b272c5346e909b68021555e188fdbb61f7a4316e4e133388b0947b363c06029d7811bea",
        size: 5_569_488,
        only: Only::All,
        from: "Modrinth",
    },
    Pin {
        name: "Slipstream 0.1.0",
        file: "slipstream-1.8.9-0.1.0.jar",
        url: "https://cdn.modrinth.com/data/XDGYMuZ6/versions/7yvc7d4F/slipstream-1.8.9-0.1.0.jar",
        sha512: "270d7c4cad3e05b63ec07cd766d17f9146981dc721b751d16e2b33bace22de240694a5247e4cc054b742d1d5bdab492459d7fe4855ff756edc028678d7b05200",
        size: 194_995,
        only: Only::Mac,
        from: "Modrinth",
    },
];

/// The pins for `os`.
pub fn pins_for(table: &[Pin], os: Os) -> Vec<&Pin> {
    table
        .iter()
        .filter(|p| p.only == Only::All || (p.only == Only::Mac && os == Os::Mac))
        .collect()
}

/// A fingerprint of everything Cobblify puts in the instance besides its own jar (the
/// profile revision, Java 25 and the pins for `os`). A saved target with another one needs a
/// new setup: a pin change is never downloaded silently.
pub fn pins_id(table: &[Pin], os: Os) -> String {
    let mut h = Sha256::new();
    h.update(PROFILE_REVISION.as_bytes());
    h.update(crate::prism_java::JAVA25.name.as_bytes());
    for p in pins_for(table, os) {
        h.update(b"\n");
        h.update(p.file.as_bytes());
        h.update(b" ");
        h.update(p.sha512.as_bytes());
    }
    hex(&h.finalize())[..16].to_string()
}

pub(crate) fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn sha512_file(path: &Path) -> Option<String> {
    fs::read(path).ok().map(|b| hex(&Sha512::digest(&b)))
}

fn is_pin_file(path: &Path, pin: &Pin) -> bool {
    fs::metadata(path).is_ok_and(|m| m.len() == pin.size)
        && sha512_file(path).is_some_and(|h| h.eq_ignore_ascii_case(pin.sha512))
}

/// The pins not yet in `mods/` or in the download folder with the right bytes.
pub fn to_download<'a>(root: &Path, game_dir: &Path, pins: &[&'a Pin]) -> Vec<&'a Pin> {
    pins.iter()
        .copied()
        .filter(|p| {
            !is_pin_file(&game_dir.join("mods").join(p.file), p)
                && !is_pin_file(&root.join(DOWNLOADS).join(p.file), p)
        })
        .collect()
}

/// Downloads every pin not already verified into `<root>/DOWNLOADS`, each checked by size
/// and sha512 before it is kept. The first failure stops, naming the mod; nothing
/// unverified is left behind. HTTPS only.
pub fn download(
    root: &Path,
    pins: &[&Pin],
    fetch: &dyn Fn(&str) -> Result<Vec<u8>, String>,
) -> Result<(), String> {
    let dir = root.join(DOWNLOADS);
    fs::create_dir_all(&dir).map_err(|e| format!("Cannot create {}: {e}", dir.display()))?;
    for pin in pins {
        let dest = dir.join(pin.file);
        if is_pin_file(&dest, pin) {
            continue;
        }
        if !pin.url.starts_with("https://") {
            return Err(format!("Refusing to download {} without HTTPS.", pin.name));
        }
        let bytes = fetch(pin.url).map_err(|e| {
            format!("Cobblify couldn't download {} from {} ({e}).", pin.name, pin.from)
        })?;
        if bytes.len() as u64 != pin.size
            || !hex(&Sha512::digest(&bytes)).eq_ignore_ascii_case(pin.sha512)
        {
            return Err(format!(
                "The download of {} from {} does not match its pinned hash.",
                pin.name, pin.from
            ));
        }
        prism::write_atomic(&dest, &bytes)?;
    }
    Ok(())
}

/// Whether `name` looks like a Cobblify jar (any build, any case).
fn is_cobblify_name(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    (lower.starts_with("cobblify") || lower.starts_with("bedwarsqol")) && lower.ends_with(".jar")
}

/// Check 5's scan: nothing in `mods/` that Cobblify would have to overwrite without having
/// placed it, and no Cobblify jar it did not place (Fabric refuses two `bedwarsqol` mods).
/// Reads only.
pub fn check_mods(root: &Path, game_dir: &Path, pins: &[&Pin], jar: &OrnitheJar) -> Result<(), String> {
    let mods = game_dir.join("mods");
    let record = managed(root);
    let recorded = |name: &str| record.iter().any(|m| m.name == name);
    for pin in pins {
        let path = mods.join(pin.file);
        if fs::symlink_metadata(&path).is_ok() && !recorded(pin.file) && !is_pin_file(&path, pin) {
            return Err(format!(
                "The instance's mods folder has a {} Cobblify did not put there.",
                pin.file
            ));
        }
    }
    let Ok(entries) = fs::read_dir(&mods) else {
        return Ok(());
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        if is_cobblify_name(&name) && name != jar.name && !recorded(&name) {
            return Err(format!(
                "The instance's mods folder has a Cobblify file Cobblify may not replace: {name}."
            ));
        }
    }
    Ok(())
}

/// Check 6: the downloaded pins and the Cobblify jar into `mods/`, then the record. Order:
/// scan, stage every file (a failure costs nothing), remove the recorded files no longer
/// wanted, commit, write the record, clear the download folder.
pub fn place(root: &Path, game_dir: &Path, pins: &[&Pin], jar: &OrnitheJar) -> Result<(), String> {
    check_mods(root, game_dir, pins, jar)?;
    let mods = game_dir.join("mods");
    fs::create_dir_all(&mods).map_err(|e| format!("Cannot create {}: {e}", mods.display()))?;
    let downloads = root.join(DOWNLOADS);

    let mut wanted: Vec<Managed> = Vec::new();
    let mut staged = Vec::new();
    for pin in pins {
        let dest = mods.join(pin.file);
        wanted.push(Managed { name: pin.file.to_string(), sha512: pin.sha512.to_ascii_lowercase() });
        if is_pin_file(&dest, pin) {
            continue;
        }
        let src = downloads.join(pin.file);
        if !is_pin_file(&src, pin) {
            return Err(format!("{} was not downloaded. Try again.", pin.name));
        }
        let sha256 = crate::resources::sha256_file(&src)?;
        if let Some(s) = stage_verified(&src, &dest, &sha256)? {
            staged.push(s);
        }
    }
    let jar_sha512 = sha512_file(&jar.src).ok_or("Cannot read Cobblify's own jar.")?;
    wanted.push(Managed { name: jar.name.clone(), sha512: jar_sha512 });
    if let Some(s) = stage_verified(&jar.src, &mods.join(&jar.name), &jar.sha256)? {
        staged.push(s);
    }

    for old in managed(root) {
        if wanted.iter().any(|w| w.name == old.name) {
            continue;
        }
        let path = mods.join(&old.name);
        // Only the very file Cobblify placed; a file the player changed is left alone.
        if sha512_file(&path).is_some_and(|h| h.eq_ignore_ascii_case(&old.sha512)) {
            fs::remove_file(&path)
                .map_err(|e| format!("Cannot remove the old {}: {e}", old.name))?;
        }
    }
    for s in staged {
        commit(s)?;
    }
    write_managed(root, &wanted)?;
    let _ = fs::remove_dir_all(&downloads);
    Ok(())
}

// ── the saved target ────────────────────────────────────────────────────────────

/// The `ornithe` key of `launcher-targets.json`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Target {
    /// The instance folder.
    pub instance: String,
    /// `pins_id` when it was set up.
    pub pins: String,
    /// Made and not launched yet: Prism downloads Minecraft and the loader on the first launch.
    #[serde(default)]
    pub first_launch_pending: bool,
}

const TARGET_KEY: &str = "ornithe";

fn targets_path(home: &Path) -> PathBuf {
    home.join(".cobblify").join("launcher-targets.json")
}

fn read_targets(home: &Path) -> serde_json::Map<String, serde_json::Value> {
    fs::read(targets_path(home))
        .ok()
        .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default()
}

pub fn load_target(home: &Path) -> Option<Target> {
    read_targets(home)
        .get(TARGET_KEY)
        .and_then(|v| serde_json::from_value(v.clone()).ok())
}

/// Saves the Ornithe target, keeping every other key of the file.
pub fn save_target(home: &Path, target: &Target) -> Result<(), String> {
    let mut all = read_targets(home);
    all.insert(
        TARGET_KEY.to_string(),
        serde_json::to_value(target).map_err(|e| format!("Cannot record the Prism instance: {e}"))?,
    );
    let path = targets_path(home);
    let dir = path.parent().unwrap_or(home);
    fs::create_dir_all(dir).map_err(|e| format!("Cannot create {}: {e}", dir.display()))?;
    let json = serde_json::to_vec_pretty(&serde_json::Value::Object(all))
        .map_err(|e| format!("Cannot record the Prism instance: {e}"))?;
    prism::write_atomic(&path, &json)
}

/// The first launch has started: "Downloading Minecraft" is over for good.
pub fn clear_first_launch_pending(home: &Path) -> Result<(), String> {
    match load_target(home) {
        Some(mut t) if t.first_launch_pending => {
            t.first_launch_pending = false;
            save_target(home, &t)
        }
        _ => Ok(()),
    }
}

/// The saved target's instance folder, while it is still a Cobblify-made Ornithe instance.
pub fn revalidate(target: &Target) -> Option<PathBuf> {
    let root = PathBuf::from(&target.instance);
    is_ornithe_instance(&root).then_some(root)
}

pub fn saved_root(home: &Path) -> Option<PathBuf> {
    load_target(home).as_ref().and_then(revalidate)
}

/// The saved instance's game folder, for the readers of its logs and config.
pub fn saved_game_dir(home: &Path) -> Option<PathBuf> {
    saved_root(home).map(|r| game_dir(&r))
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use serde_json::Value;
    use std::fs;
    use std::path::{Path, PathBuf};

    pub(crate) fn sha512_hex(bytes: &[u8]) -> String {
        hex(&Sha512::digest(bytes))
    }

    /// A pin served by the fake network: its bytes and a matching table entry.
    pub(crate) fn fake_pin(file: &str, bytes: &[u8], only: Only) -> Pin {
        let leak = |s: String| -> &'static str { Box::leak(s.into_boxed_str()) };
        Pin {
            name: leak(format!("Fake {file}")),
            file: leak(file.to_string()),
            url: leak(format!("https://fixture.invalid/{file}")),
            sha512: leak(sha512_hex(bytes)),
            size: bytes.len() as u64,
            only,
            from: "the fixture",
        }
    }

    pub(crate) fn fake_jar(dir: &Path, name: &str, bytes: &[u8]) -> OrnitheJar {
        let src = dir.join(name);
        fs::write(&src, bytes).unwrap();
        OrnitheJar {
            src,
            name: name.to_string(),
            sha256: crate::prism_java::sha256_hex(bytes),
        }
    }

    fn json(path: &Path) -> Value {
        serde_json::from_slice(&fs::read(path).unwrap()).unwrap()
    }

    fn net(pins: &[(&Pin, &[u8])]) -> HashMap<String, Vec<u8>> {
        pins.iter().map(|(p, b)| (p.url.to_string(), b.to_vec())).collect()
    }

    fn fetcher(net: HashMap<String, Vec<u8>>, log: &std::sync::Mutex<Vec<String>>) -> impl Fn(&str) -> Result<Vec<u8>, String> + '_ {
        move |url: &str| {
            log.lock().unwrap().push(url.to_string());
            net.get(url).cloned().ok_or_else(|| format!("404 for {url}"))
        }
    }

    use std::collections::HashMap;

    // ── the instance ────────────────────────────────────────────────────────────

    /// The installer's 1.8.9 Gen2 Fabric layout minus Flap, Java 25 only, Cobblify's
    /// instance.cfg shape and marker; staged and renamed in once.
    #[test]
    fn the_made_instance_has_ornithes_components_and_patches() {
        let prism = tempfile::tempdir().unwrap();
        let instances = prism.path().join("instances");
        let root = make_instance(&instances).unwrap();
        assert_eq!(root, instances.join(NAME));
        assert!(!instances.join(crate::prism::STAGING_DIR).exists(), "no staging left");

        let cfg = crate::prism::read_ini(&root.join("instance.cfg")).unwrap();
        assert_eq!(cfg.get("name").map(String::as_str), Some(NAME));
        assert_eq!(cfg.get("InstanceType").map(String::as_str), Some("OneSix"));

        let pack = json(&root.join("mmc-pack.json"));
        let comps = pack["components"].as_array().unwrap();
        let uids: Vec<&str> = comps.iter().map(|c| c["uid"].as_str().unwrap()).collect();
        assert_eq!(
            uids,
            vec![
                "org.lwjgl",
                "net.minecraft",
                "net.fabricmc.intermediary",
                "net.fabricmc.fabric-loader",
                "org.slf4j.slf4j-api",
                "org.apache.logging.log4j.log4j-slf4j2-impl",
                "org.apache.logging.log4j.log4j-api",
                "org.apache.logging.log4j.log4j-core",
                "it.unimi.dsi.fastutil",
                "com.google.code.gson.gson",
                "net.ornithemc.log4j-patch",
                "net.sf.jopt-simple.jopt-simple",
                "com.mojang.brigadier",
            ]
        );
        assert!(!uids.iter().any(|u| u.contains("flap") || u.contains("minecraftforge")));
        assert_eq!(comps[0]["version"], "2.9.4+legacyfabric.15");
        assert_eq!(comps[1]["version"], "1.8.9");
        assert_eq!(comps[3]["version"], "0.20.1");
        assert_eq!(comps[3]["cachedRequires"][0]["uid"], "net.fabricmc.intermediary");
        assert!(comps[4].get("version").is_none(), "library upgrades carry no version key");
        assert_eq!(comps[4]["cachedVersion"], "2.0.17");

        let mc = json(&root.join("patches/net.minecraft.json"));
        assert_eq!(mc["compatibleJavaMajors"], serde_json::json!([25]));
        assert_eq!(mc["compatibleJavaName"], "java-runtime-epsilon");
        assert_eq!(mc["+traits"], serde_json::json!(["FirstThreadOnMacOS"]));
        assert_eq!(
            mc["+jvmArgs"],
            serde_json::json!(["-Dfabric.fixPackageAccess=true", "-Dfabric.gameVersion=1.8.9"])
        );
        assert_eq!(mc["mainJar"]["downloads"]["artifact"]["sha1"], "3870888a6c3d349d3771a3e9d16c9bf5e076b908");
        assert_eq!(mc["assetIndex"]["sha1"], "f6ad102bcaa53b1a58358f16e376d548d44933ec");
        let libs: Vec<&str> = mc["libraries"].as_array().unwrap().iter().map(|l| l["name"].as_str().unwrap()).collect();
        assert_eq!(libs.len(), 33);
        assert!(libs.contains(&"org.lwjgl.lwjgl:lwjgl:2.9.4+legacyfabric.15"));
        assert!(!libs.iter().any(|l| l.contains("org.ow2.asm")));

        let inter = json(&root.join("patches/net.fabricmc.intermediary.json"));
        assert_eq!(inter["libraries"][0]["name"], "net.ornithemc:calamus-intermediary-gen2:1.8.9");
        assert_eq!(inter["libraries"][0]["url"], "https://maven.ornithemc.net/releases");
        let lwjgl = json(&root.join("patches/org.lwjgl.json"));
        assert_eq!(lwjgl["version"], "2.9.4+legacyfabric.15");
        assert!(lwjgl.get("libraries").is_none(), "an empty placeholder");
        let patch = json(&root.join("patches/net.ornithemc.log4j-patch.json"));
        assert_eq!(patch["libraries"][0]["name"], "net.ornithemc:log4j-patch:1.0.0");
        assert_eq!(patch["libraries"][0]["url"], "https://maven.ornithemc.net/releases/");
        let gson = json(&root.join("patches/com.google.code.gson.gson.json"));
        assert_eq!(gson["libraries"][0]["name"], "com.google.code.gson:gson:2.10");
        assert_eq!(gson["libraries"][0]["url"], "https://libraries.minecraft.net/");
        assert_eq!(fs::read_dir(root.join("patches")).unwrap().count(), 12);

        assert!(root.join(MARKER).is_file());
        assert!(is_ornithe_instance(&root));
        assert!(managed(&root).is_empty());
    }

    /// Prism's own naming: a taken name gets (1). Only a Cobblify-made Ornithe instance is
    /// found again; a Forge instance or a lookalike without the marker is not.
    #[test]
    fn instances_get_unique_names_and_only_the_marked_one_is_found() {
        let prism = tempfile::tempdir().unwrap();
        let instances = prism.path().join("instances");
        assert_eq!(find_made(&instances), None);
        let forge = instances.join(NAME);
        crate::forge::tests::write_instance(
            &forge,
            br#"{"components":[{"uid":"net.minecraft","version":"1.8.9"},{"uid":"net.minecraftforge","version":"11.15.1.2318"}]}"#,
            0,
        );
        assert!(!is_ornithe_instance(&forge));
        let root = make_instance(&instances).unwrap();
        assert_eq!(root, instances.join(format!("{NAME}(1)")));
        assert_eq!(find_made(&instances), Some(root.clone()));

        let copy = instances.join("Copy");
        fs::create_dir_all(&copy).unwrap();
        for f in ["instance.cfg", "mmc-pack.json"] {
            fs::copy(root.join(f), copy.join(f)).unwrap();
        }
        assert!(!is_ornithe_instance(&copy), "no marker, not ours");
        fs::remove_file(root.join(MARKER)).unwrap();
        assert!(!is_ornithe_instance(&root));
        assert_eq!(find_made(&instances), None);
    }

    // ── the pins ────────────────────────────────────────────────────────────────

    #[test]
    fn the_pinned_mods_are_complete_and_slipstream_is_macos_only() {
        let files: Vec<&str> = PINS.iter().map(|p| p.file).collect();
        assert_eq!(
            files,
            vec![
                "pylon-0.2.8.jar",
                "argentum-1.0.3-beta.8.jar",
                "Sarcio-1.2.0-beta.1.jar",
                "ornithe-standard-libraries-0.23.1.jar",
                "slipstream-1.8.9-0.1.0.jar",
            ]
        );
        for p in PINS.iter() {
            assert!(p.url.starts_with("https://") && p.url.ends_with(p.file), "{}", p.url);
            assert_eq!(p.sha512.len(), 128, "{}", p.file);
            assert!(p.sha512.bytes().all(|b| b.is_ascii_hexdigit()));
            assert!(p.size > 0);
        }
        let argentum = PINS.iter().find(|p| p.file.starts_with("argentum")).unwrap();
        assert!(argentum
            .url
            .starts_with("https://github.com/Polyfrost/Argentum/releases/download/v1.0.3-beta.8/"));
        let osl = PINS.iter().find(|p| p.file.starts_with("ornithe-standard")).unwrap();
        assert_eq!(osl.sha512, "85210284ba82c90cefe06f0325cc3cac0497faf341cc5494843e4e1e6b272c5346e909b68021555e188fdbb61f7a4316e4e133388b0947b363c06029d7811bea");

        let mac: Vec<&str> = pins_for(&PINS, Os::Mac).iter().map(|p| p.file).collect();
        let win: Vec<&str> = pins_for(&PINS, Os::Windows).iter().map(|p| p.file).collect();
        assert_eq!(mac.len(), 5);
        assert!(mac.contains(&"slipstream-1.8.9-0.1.0.jar"));
        assert_eq!(win.len(), 4);
        assert!(!win.iter().any(|f| f.starts_with("slipstream")));
        assert_ne!(pins_id(&PINS, Os::Mac), pins_id(&PINS, Os::Windows));
        assert_eq!(pins_id(&PINS, Os::Mac), pins_id(&PINS, Os::Mac));
        let mut changed = PINS.to_vec();
        changed[0].sha512 = "00";
        assert_ne!(pins_id(&changed, Os::Windows), pins_id(&PINS, Os::Windows));
    }

    // ── downloads and placing ───────────────────────────────────────────────────

    /// Each download is checked by size and sha512 before it is kept; a bad one leaves
    /// nothing and names the mod. A file already verified is not fetched again.
    #[test]
    fn downloads_are_verified_and_kept_only_when_they_match() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("inst");
        fs::create_dir_all(&root).unwrap();
        let a = fake_pin("a-1.jar", b"aaa", Only::All);
        let b = fake_pin("b-1.jar", b"bbb", Only::All);
        let log = std::sync::Mutex::new(Vec::new());
        let served = net(&[(&a, b"aaa"), (&b, b"bbX")]);
        let err = download(&root, &[&a, &b], &fetcher(served, &log)).unwrap_err();
        assert!(err.contains("Fake b-1.jar"), "{err}");
        assert!(root.join(DOWNLOADS).join("a-1.jar").is_file());
        assert!(!root.join(DOWNLOADS).join("b-1.jar").exists());
        let leftovers: Vec<_> = fs::read_dir(root.join(DOWNLOADS)).unwrap().flatten().map(|e| e.file_name()).collect();
        assert_eq!(leftovers.len(), 1, "{leftovers:?}");

        let log = std::sync::Mutex::new(Vec::new());
        let served = net(&[(&a, b"aaa"), (&b, b"bbb")]);
        download(&root, &[&a, &b], &fetcher(served, &log)).unwrap();
        assert_eq!(*log.lock().unwrap(), vec![b.url.to_string()], "a is not fetched again");
        assert!(to_download(&root, &root.join("minecraft"), &[&a, &b]).is_empty());
    }

    /// Argentum comes only from its GitHub release: a missing asset is a clear failure and
    /// nothing else is tried.
    #[test]
    fn a_missing_argentum_asset_is_a_clear_failure_with_no_mirror() {
        let dir = tempfile::tempdir().unwrap();
        let argentum = PINS.iter().find(|p| p.file.starts_with("argentum")).unwrap();
        let log = std::sync::Mutex::new(Vec::new());
        let err = download(dir.path(), &[argentum], &fetcher(HashMap::new(), &log)).unwrap_err();
        assert!(err.contains("Argentum 1.0.3-beta.8"), "{err}");
        assert!(err.contains("GitHub release"), "{err}");
        assert_eq!(*log.lock().unwrap(), vec![argentum.url.to_string()], "no mirror");
    }

    /// The record lists every file Cobblify placed (name + sha512). An update replaces and
    /// removes only recorded files; a player's own mod stays.
    #[test]
    fn placing_records_cobblifys_files_and_an_update_replaces_only_them() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("inst");
        let game = root.join("minecraft");
        fs::create_dir_all(&root).unwrap();
        let a1 = fake_pin("a-1.jar", b"a1", Only::All);
        let b = fake_pin("b-1.jar", b"b", Only::All);
        let jar1 = fake_jar(dir.path(), "Cobblify-1.8.9-ornithe-0.19.0.jar", b"cob1");
        let log = std::sync::Mutex::new(Vec::new());
        download(&root, &[&a1, &b], &fetcher(net(&[(&a1, b"a1"), (&b, b"b")]), &log)).unwrap();
        place(&root, &game, &[&a1, &b], &jar1).unwrap();
        let mods = game.join("mods");
        assert_eq!(fs::read(mods.join("a-1.jar")).unwrap(), b"a1");
        assert_eq!(fs::read(mods.join("b-1.jar")).unwrap(), b"b");
        assert_eq!(fs::read(mods.join(&jar1.name)).unwrap(), b"cob1");
        assert!(!root.join(DOWNLOADS).exists(), "the download folder is cleared");
        let mut rec = managed(&root);
        rec.sort_by(|x, y| x.name.cmp(&y.name));
        assert_eq!(
            rec,
            vec![
                Managed { name: jar1.name.clone(), sha512: sha512_hex(b"cob1") },
                Managed { name: "a-1.jar".into(), sha512: sha512_hex(b"a1") },
                Managed { name: "b-1.jar".into(), sha512: sha512_hex(b"b") },
            ]
        );

        fs::write(mods.join("my-own-mod.jar"), b"mine").unwrap();
        let a2 = fake_pin("a-2.jar", b"a2", Only::All);
        let jar2 = fake_jar(dir.path(), "Cobblify-1.8.9-ornithe-0.19.1.jar", b"cob2");
        assert!(check_mods(&root, &game, &[&a2, &b], &jar2).is_ok());
        let log = std::sync::Mutex::new(Vec::new());
        let need = to_download(&root, &game, &[&a2, &b]);
        download(&root, &need, &fetcher(net(&[(&a2, b"a2")]), &log)).unwrap();
        assert_eq!(*log.lock().unwrap(), vec![a2.url.to_string()], "b is already in place");
        place(&root, &game, &[&a2, &b], &jar2).unwrap();
        let mut names: Vec<String> = fs::read_dir(&mods).unwrap().flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect();
        names.sort();
        assert_eq!(names, vec![jar2.name.clone(), "a-2.jar".into(), "b-1.jar".into(), "my-own-mod.jar".into()]);
        assert_eq!(fs::read(mods.join("my-own-mod.jar")).unwrap(), b"mine");
        assert!(managed(&root).iter().all(|m| m.name != "my-own-mod.jar"));
    }

    /// A file at a pinned name that Cobblify did not put there, or a stray Cobblify jar,
    /// blocks before anything is downloaded or written.
    #[test]
    fn a_foreign_file_at_a_pinned_name_or_a_stray_cobblify_jar_blocks() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("inst");
        let game = root.join("minecraft");
        let mods = game.join("mods");
        fs::create_dir_all(&mods).unwrap();
        let a = fake_pin("a-1.jar", b"a1", Only::All);
        let jar = fake_jar(dir.path(), "Cobblify-1.8.9-ornithe-0.19.0.jar", b"cob");
        assert!(check_mods(&root, &game, &[&a], &jar).is_ok());

        fs::write(mods.join("a-1.jar"), b"not the pin").unwrap();
        let err = check_mods(&root, &game, &[&a], &jar).unwrap_err();
        assert!(err.contains("a-1.jar"), "{err}");
        // The same bytes as the pin are fine (adopted, not replaced).
        fs::write(mods.join("a-1.jar"), b"a1").unwrap();
        assert!(check_mods(&root, &game, &[&a], &jar).is_ok());

        fs::write(mods.join("Cobblify-1.8.9-ornithe-0.18.0.jar"), b"old").unwrap();
        let err = check_mods(&root, &game, &[&a], &jar).unwrap_err();
        assert!(err.contains("Cobblify-1.8.9-ornithe-0.18.0.jar"), "{err}");
        let err = place(&root, &game, &[&a], &jar).unwrap_err();
        assert!(err.contains("Cobblify-1.8.9-ornithe-0.18.0.jar"), "{err}");
        assert!(!mods.join(&jar.name).exists(), "nothing placed on a block");
    }

    // ── the saved target ────────────────────────────────────────────────────────

    /// `launcher-targets.json` keeps its `forge` key byte-for-byte in meaning; the Ornithe
    /// target is a key of its own and is valid only while its instance carries the marker.
    #[test]
    fn the_ornithe_target_is_its_own_key_and_revalidates_by_marker() {
        let home = tempfile::tempdir().unwrap();
        let prism = home.path().join("prism");
        let root = make_instance(&prism.join("instances")).unwrap();
        let dir = home.path().join(".cobblify");
        fs::create_dir_all(&dir).unwrap();
        let forge = serde_json::json!({"game_dir": "/x/minecraft", "validated_by": "marker", "marker": "mmc-pack.json"});
        fs::write(dir.join("launcher-targets.json"), serde_json::to_vec(&serde_json::json!({"forge": forge})).unwrap()).unwrap();
        assert_eq!(load_target(home.path()), None);

        let target = Target { instance: root.display().to_string(), pins: "p1".into(), first_launch_pending: true };
        save_target(home.path(), &target).unwrap();
        assert_eq!(load_target(home.path()), Some(target.clone()));
        let file = json(&dir.join("launcher-targets.json"));
        assert_eq!(file["forge"], forge, "the Forge target is kept");
        assert_eq!(saved_root(home.path()), Some(root.clone()));
        assert_eq!(saved_game_dir(home.path()), Some(root.join("minecraft")));

        clear_first_launch_pending(home.path()).unwrap();
        assert!(!load_target(home.path()).unwrap().first_launch_pending);
        fs::remove_file(root.join(MARKER)).unwrap();
        assert_eq!(saved_root(home.path()), None, "no marker, not a valid target");
        let _ = PathBuf::new();
    }
}
