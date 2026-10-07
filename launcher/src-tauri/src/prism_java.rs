//! Java 8 for an instance Cobblify MADE (spec 1.5; existing instances keep Prism's own Java).
//!
//! The order (PLAN D):
//!   1. Prism downloads Java itself when `AutomaticJavaSwitch` AND `AutomaticJavaDownload`
//!      are on (`Application.cpp:741-754`: each defaults to on exactly when the global
//!      `JavaPath` is absent or empty) -> nothing to do.
//!   2. `/usr/libexec/java_home -F -v 1.8 -a <arch>` finds a Java 8 -> pin `<home>/bin/java`
//!      (`-F`: without it java_home silently falls back to a non-8 JVM). On Apple Silicon
//!      x86_64 is also tried when Rosetta is present.
//!   3. Prism's own `<JavaDir>/jre-legacy` already exists (Prism made it for another
//!      instance) -> pin it, as Prism itself would reuse it.
//!   4. Otherwise download Prism meta's `jre-legacy` for the arch into `<JavaDir>/jre-legacy`
//!      and pin it. Integrity chain: `index.json` (TLS) gives `java8.json`'s sha256;
//!      `java8.json` gives the runtime's checksum - an archive's own sha256 (Azul, arm64) or
//!      a Mojang manifest's sha1 whose entries each carry a sha1 (x64). Everything is staged
//!      under `<JavaDir>/.cobblify-jre-<random>/` and renamed into place once, never over an
//!      existing folder; any failure removes the staging folder.
//!
//! "Pin" = `OverrideJavaLocation=true` + `JavaPath=<java>` in the INSTANCE's `instance.cfg`.
//! Prism's global settings are never written.

use std::fs;
use std::path::{Component, Path, PathBuf};

use serde_json::Value;
use sha2::{Digest, Sha256};

use crate::prism;

pub const INDEX_URL: &str = "https://meta.prismlauncher.org/v1/net.minecraft.java/index.json";
pub const JAVA8_URL: &str = "https://meta.prismlauncher.org/v1/net.minecraft.java/java8.json";
/// The runtime name Minecraft 1.8.9 asks for (`compatibleJavaName`), and Prism's folder name.
pub const JRE_NAME: &str = "jre-legacy";
/// The largest single download accepted (the Mojang x64 runtime is ~171 MB in files).
pub const MAX_DOWNLOAD: usize = 512 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Arch {
    Arm64,
    X64,
}

impl Arch {
    /// `java_home -a` names.
    pub fn java_home_name(self) -> &'static str {
        match self {
            Arch::Arm64 => "arm64",
            Arch::X64 => "x86_64",
        }
    }
    /// Prism meta's `runtimeOS`.
    pub fn runtime_os(self) -> &'static str {
        match self {
            Arch::Arm64 => "mac-os-arm64",
            Arch::X64 => "mac-os-x64",
        }
    }
}

/// The machine facts and network the Java step needs, injected for tests.
pub trait JavaEnv {
    fn arch(&self) -> Arch;
    /// Rosetta 2 is installed (x86_64 Java can run on Apple Silicon).
    fn rosetta(&self) -> bool;
    /// `java_home -F -v 1.8 -a <arch>`: a Java 8 home, or `None`.
    fn java_home(&self, arch: Arch) -> Option<PathBuf>;
    /// One HTTPS GET, the whole body.
    fn fetch(&self, url: &str) -> Result<Vec<u8>, String>;
    /// Extracts a zip archive into `dest` (an existing empty folder), keeping unix modes.
    fn unzip(&self, zip: &Path, dest: &Path) -> Result<(), String> {
        ditto_unzip(zip, dest)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Plan {
    /// Prism will download Java 8 itself at the first launch.
    PrismDownloads,
    /// Pin this `java` executable.
    Pin(PathBuf),
    /// Download `jre-legacy`, then pin it.
    Download,
}

/// QVariant's string-to-bool: false for "", "0" and "false" (any case), true otherwise.
fn qt_bool(v: &str) -> bool {
    let v = v.trim();
    !(v.is_empty() || v == "0" || v.eq_ignore_ascii_case("false"))
}

/// Whether Prism's own automatic Java download is on (both switches; the default rule).
pub fn prism_downloads_java(data: &Path) -> bool {
    let cfg = prism::global_cfg(data);
    let default_on = cfg.get("JavaPath").is_none_or(|p| p.trim().is_empty());
    let switch = cfg
        .get("AutomaticJavaSwitch")
        .map(|v| qt_bool(v))
        .unwrap_or(default_on);
    let download = cfg
        .get("AutomaticJavaDownload")
        .map(|v| qt_bool(v))
        .unwrap_or(default_on);
    switch && download
}

/// Prism's `jre-legacy` java, whether or not it exists.
pub fn jre_legacy_java(data: &Path) -> PathBuf {
    prism::java_dir(data).join(JRE_NAME).join("bin/java")
}

/// The order above, steps 1-3 decided; step 4 is `Plan::Download`.
pub fn plan(env: &dyn JavaEnv, data: &Path) -> Plan {
    if prism_downloads_java(data) {
        return Plan::PrismDownloads;
    }
    let mut archs = vec![env.arch()];
    if env.arch() == Arch::Arm64 && env.rosetta() {
        archs.push(Arch::X64);
    }
    for arch in archs {
        if let Some(home) = env.java_home(arch) {
            let java = home.join("bin/java");
            if java.is_file() {
                return Plan::Pin(java);
            }
        }
    }
    let ours = jre_legacy_java(data);
    if ours.is_file() {
        return Plan::Pin(ours);
    }
    Plan::Download
}

/// Pins `java` to one instance (`instance.cfg`), keeping every other key Prism wrote.
pub fn pin(instance_root: &Path, java: &Path) -> Result<(), String> {
    let cfg = instance_root.join(prism::INSTANCE_CFG);
    let current = prism::read_ini(&cfg).ok_or("Cannot read the instance's settings.")?;
    let java = java.to_string_lossy();
    if current.get("OverrideJavaLocation").is_some_and(|v| qt_bool(v))
        && current.get("JavaPath").map(String::as_str) == Some(java.as_ref())
    {
        return Ok(());
    }
    prism::set_ini_keys(&cfg, &[("OverrideJavaLocation", "true"), ("JavaPath", &java)])
}

// ── the download ────────────────────────────────────────────────────────────────

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    hex(&Sha256::digest(bytes))
}

/// Verifies `bytes` against a meta checksum `{type: sha1|sha256, hash}`.
fn verify(bytes: &[u8], kind: &str, hash: &str, what: &str) -> Result<(), String> {
    let actual = match kind {
        "sha256" => sha256_hex(bytes),
        "sha1" => sha1_hex(bytes),
        other => return Err(format!("Unknown checksum type for {what}: {other}.")),
    };
    if actual.eq_ignore_ascii_case(hash.trim()) {
        Ok(())
    } else {
        Err(format!("{what} failed its checksum."))
    }
}

fn fetch(env: &dyn JavaEnv, url: &str) -> Result<Vec<u8>, String> {
    if !url.starts_with("https://") {
        return Err("Refusing a Java download that is not HTTPS.".to_string());
    }
    let body = env.fetch(url)?;
    if body.len() > MAX_DOWNLOAD {
        return Err("A Java download was larger than expected.".to_string());
    }
    Ok(body)
}

/// The runtime entry of java8.json for this machine.
fn pick_runtime(java8: &Value, env: &dyn JavaEnv) -> Result<Value, String> {
    let runtimes = java8
        .get("runtimes")
        .and_then(Value::as_array)
        .ok_or("Prism's Java list is malformed.")?;
    let find = |os: &str| {
        runtimes
            .iter()
            .find(|r| {
                r.get("runtimeOS").and_then(Value::as_str) == Some(os)
                    && r.get("name").and_then(Value::as_str) == Some(JRE_NAME)
            })
            .cloned()
    };
    if let Some(r) = find(env.arch().runtime_os()) {
        return Ok(r);
    }
    if env.arch() == Arch::Arm64 && env.rosetta() {
        if let Some(r) = find(Arch::X64.runtime_os()) {
            return Ok(r);
        }
    }
    Err("Prism has no Java 8 for this Mac.".to_string())
}

/// Downloads and installs `jre-legacy`; returns its `bin/java`. Leaves nothing behind on
/// failure, and never replaces an existing `jre-legacy`.
pub fn install_jre_legacy(env: &dyn JavaEnv, data: &Path) -> Result<PathBuf, String> {
    // 1. index.json -> java8.json's sha256.
    let index: Value = serde_json::from_slice(&fetch(env, INDEX_URL)?)
        .map_err(|_| "Prism's Java index is malformed.".to_string())?;
    let expected = index
        .get("versions")
        .and_then(Value::as_array)
        .and_then(|vs| {
            vs.iter()
                .find(|v| v.get("version").and_then(Value::as_str) == Some("java8"))
        })
        .and_then(|v| v.get("sha256"))
        .and_then(Value::as_str)
        .ok_or("Prism's Java index has no Java 8.")?
        .to_string();
    let java8_bytes = fetch(env, JAVA8_URL)?;
    verify(&java8_bytes, "sha256", &expected, "Prism's Java 8 list")?;
    let java8: Value = serde_json::from_slice(&java8_bytes)
        .map_err(|_| "Prism's Java 8 list is malformed.".to_string())?;
    let runtime = pick_runtime(&java8, env)?;
    let url = runtime
        .get("url")
        .and_then(Value::as_str)
        .ok_or("Prism's Java 8 entry has no URL.")?;
    let sum = runtime.get("checksum").ok_or("Prism's Java 8 entry has no checksum.")?;
    let sum_type = sum.get("type").and_then(Value::as_str).unwrap_or("");
    let sum_hash = sum.get("hash").and_then(Value::as_str).unwrap_or("");
    if sum_hash.is_empty() {
        return Err("Prism's Java 8 entry has no checksum.".to_string());
    }

    let java_dir = prism::java_dir(data);
    let dest = java_dir.join(JRE_NAME);
    if fs::symlink_metadata(&dest).is_ok() {
        return Err("Prism's Java folder already has a jre-legacy that Cobblify can't use.".into());
    }
    fs::create_dir_all(&java_dir)
        .map_err(|e| format!("Cannot create {}: {e}", java_dir.display()))?;
    let work = java_dir.join(format!(".cobblify-jre-{}", prism::unique()));
    let result = (|| -> Result<(), String> {
        fs::create_dir_all(&work).map_err(|e| format!("Cannot create {}: {e}", work.display()))?;
        let root = match runtime.get("downloadType").and_then(Value::as_str) {
            Some("archive") => {
                let archive = fetch(env, url)?;
                verify(&archive, sum_type, sum_hash, "The Java 8 download")?;
                unpack_archive(env, &archive, &work)?
            }
            Some("manifest") => {
                let manifest = fetch(env, url)?;
                verify(&manifest, sum_type, sum_hash, "The Java 8 file list")?;
                let root = work.join("jre");
                install_manifest(env, &manifest, &root)?;
                root
            }
            _ => return Err("Prism's Java 8 entry has an unknown download type.".to_string()),
        };
        link_home(&root)?;
        set_exec_bits(&root)?;
        if !root.join("bin/java").is_file() {
            return Err("The Java 8 download has no java.".to_string());
        }
        prism::rename_no_replace(&root, &dest)
            .map_err(|e| format!("Cannot put Java 8 in Prism's Java folder: {e}"))
    })();
    let _ = fs::remove_dir_all(&work);
    result?;
    Ok(dest.join("bin/java"))
}

/// Writes the archive, extracts it, and strips its single top folder (as Prism's
/// `ArchiveDownloadTask` does). Returns the stripped root, inside `work`.
fn unpack_archive(env: &dyn JavaEnv, archive: &[u8], work: &Path) -> Result<PathBuf, String> {
    let zip = work.join("archive.zip");
    fs::write(&zip, archive).map_err(|e| format!("Cannot save the Java 8 download: {e}"))?;
    let out = work.join("x");
    fs::create_dir_all(&out).map_err(|e| format!("Cannot unpack Java 8: {e}"))?;
    env.unzip(&zip, &out)?;
    let _ = fs::remove_file(&zip);
    let tops: Vec<PathBuf> = fs::read_dir(&out)
        .map_err(|e| format!("Cannot unpack Java 8: {e}"))?
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.file_name().is_some_and(|n| n != "__MACOSX"))
        .collect();
    match tops.as_slice() {
        [single] if single.is_dir() => Ok(single.clone()),
        _ => Err("The Java 8 download is not laid out as expected.".to_string()),
    }
}

/// A manifest path that stays inside the root: relative, no `..`, no empty parts.
fn safe_relative(rel: &str) -> Option<PathBuf> {
    let path = Path::new(rel);
    if rel.is_empty() || path.is_absolute() {
        return None;
    }
    let mut out = PathBuf::new();
    for c in path.components() {
        match c {
            Component::Normal(p) => out.push(p),
            _ => return None,
        }
    }
    Some(out)
}

/// Mojang's runtime manifest: directories, files (raw download, size + sha1 checked), and
/// links. Mirrors Prism's `ManifestDownloadTask`.
fn install_manifest(env: &dyn JavaEnv, manifest: &[u8], root: &Path) -> Result<(), String> {
    let manifest: Value =
        serde_json::from_slice(manifest).map_err(|_| "The Java 8 file list is malformed.")?;
    let files = manifest
        .get("files")
        .and_then(Value::as_object)
        .ok_or("The Java 8 file list is malformed.")?;
    fs::create_dir_all(root).map_err(|e| format!("Cannot create {}: {e}", root.display()))?;
    let mut entries: Vec<(&String, &Value)> = files.iter().collect();
    // Directories first, then files, then links; each in path order.
    let rank = |v: &Value| match v.get("type").and_then(Value::as_str) {
        Some("directory") => 0,
        Some("file") => 1,
        _ => 2,
    };
    entries.sort_by(|a, b| rank(a.1).cmp(&rank(b.1)).then(a.0.cmp(b.0)));
    for (rel, entry) in entries {
        let rel_path = safe_relative(rel).ok_or("The Java 8 file list has an unsafe path.")?;
        let path = root.join(&rel_path);
        match entry.get("type").and_then(Value::as_str) {
            Some("directory") => {
                fs::create_dir_all(&path).map_err(|e| format!("Cannot unpack Java 8: {e}"))?;
            }
            Some("file") => {
                let raw = entry
                    .get("downloads")
                    .and_then(|d| d.get("raw"))
                    .ok_or("The Java 8 file list is malformed.")?;
                let url = raw.get("url").and_then(Value::as_str).unwrap_or("");
                let sha1 = raw.get("sha1").and_then(Value::as_str).unwrap_or("");
                let size = raw.get("size").and_then(Value::as_u64);
                if sha1.is_empty() {
                    return Err("The Java 8 file list is malformed.".to_string());
                }
                let bytes = fetch(env, url)?;
                if size.is_some_and(|s| s != bytes.len() as u64) {
                    return Err("A Java 8 file has the wrong size.".to_string());
                }
                verify(&bytes, "sha1", sha1, "A Java 8 file")?;
                if let Some(parent) = path.parent() {
                    fs::create_dir_all(parent).map_err(|e| format!("Cannot unpack Java 8: {e}"))?;
                }
                fs::write(&path, &bytes).map_err(|e| format!("Cannot unpack Java 8: {e}"))?;
                let exec = entry.get("executable").and_then(Value::as_bool) == Some(true);
                set_mode(&path, if exec { 0o755 } else { 0o644 })?;
            }
            Some("link") => {
                let target = entry.get("target").and_then(Value::as_str).unwrap_or("");
                // A link may climb (`../lib/x`), but never leave the runtime.
                let resolved = rel_path
                    .parent()
                    .unwrap_or(Path::new(""))
                    .join(target);
                if target.is_empty() || Path::new(target).is_absolute() || !stays_inside(&resolved) {
                    return Err("The Java 8 file list has an unsafe link.".to_string());
                }
                if let Some(parent) = path.parent() {
                    fs::create_dir_all(parent).map_err(|e| format!("Cannot unpack Java 8: {e}"))?;
                }
                symlink(Path::new(target), &path)?;
            }
            _ => return Err("The Java 8 file list has an unknown entry.".to_string()),
        }
    }
    Ok(())
}

/// Whether a relative path, with `..` resolved lexically, stays at or below its start.
fn stays_inside(path: &Path) -> bool {
    let mut depth: i64 = 0;
    for c in path.components() {
        match c {
            Component::Normal(_) => depth += 1,
            Component::CurDir => {}
            Component::ParentDir => {
                depth -= 1;
                if depth < 0 {
                    return false;
                }
            }
            _ => return false,
        }
    }
    true
}

/// Prism's macOS `SymlinkTask`: when `<root>/bin/java` is missing, link every entry of the
/// runtime's `Contents/Home` (at the root or one folder down) into the root. Relative
/// targets, because the folder is renamed into place afterwards.
fn link_home(root: &Path) -> Result<(), String> {
    if root.join("bin/java").exists() {
        return Ok(());
    }
    let mut home_rel = None;
    if root.join("Contents/Home/bin/java").is_file() {
        home_rel = Some(PathBuf::from("Contents/Home"));
    } else {
        let mut subs: Vec<PathBuf> = fs::read_dir(root)
            .map_err(|e| format!("Cannot read the Java 8 folder: {e}"))?
            .flatten()
            .map(|e| e.path())
            .collect();
        subs.sort();
        for sub in subs {
            if sub.join("Contents/Home/bin/java").is_file() {
                let name = sub.file_name().unwrap_or_default();
                home_rel = Some(Path::new(name).join("Contents/Home"));
                break;
            }
        }
    }
    let home_rel = home_rel.ok_or("The Java 8 download has no java.")?;
    let mut items: Vec<_> = fs::read_dir(root.join(&home_rel))
        .map_err(|e| format!("Cannot read the Java 8 folder: {e}"))?
        .flatten()
        .map(|e| e.file_name())
        .collect();
    items.sort();
    for name in items {
        let link = root.join(&name);
        if fs::symlink_metadata(&link).is_ok() {
            continue;
        }
        symlink(&home_rel.join(&name), &link)?;
    }
    Ok(())
}

/// Every file in the runtime's `bin` is executable (and `lib/jspawnhelper`, which the JVM
/// spawns), whatever modes the archive carried.
fn set_exec_bits(root: &Path) -> Result<(), String> {
    let bin = root.join("bin");
    let real = fs::canonicalize(&bin).unwrap_or(bin);
    if let Ok(rd) = fs::read_dir(&real) {
        for e in rd.flatten() {
            if e.path().is_file() {
                add_exec(&e.path())?;
            }
        }
    }
    let helper = root.join("lib/jspawnhelper");
    if helper.is_file() {
        add_exec(&helper)?;
    }
    Ok(())
}

#[cfg(unix)]
fn add_exec(path: &Path) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    let mode = fs::metadata(path)
        .map_err(|e| format!("Cannot read {}: {e}", path.display()))?
        .permissions()
        .mode();
    set_mode(path, mode | 0o111)
}

#[cfg(not(unix))]
fn add_exec(_path: &Path) -> Result<(), String> {
    Ok(())
}

#[cfg(unix)]
fn set_mode(path: &Path, mode: u32) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(mode))
        .map_err(|e| format!("Cannot set the mode of {}: {e}", path.display()))
}

#[cfg(not(unix))]
fn set_mode(_path: &Path, _mode: u32) -> Result<(), String> {
    Ok(())
}

#[cfg(unix)]
fn symlink(target: &Path, link: &Path) -> Result<(), String> {
    std::os::unix::fs::symlink(target, link)
        .map_err(|e| format!("Cannot link {}: {e}", link.display()))
}

#[cfg(not(unix))]
fn symlink(_target: &Path, _link: &Path) -> Result<(), String> {
    Err("Java 8 links are not supported on this platform.".to_string())
}

/// macOS's own unzip: keeps unix modes, adds no quarantine attribute.
pub fn ditto_unzip(zip: &Path, dest: &Path) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        let status = std::process::Command::new("/usr/bin/ditto")
            .arg("-x")
            .arg("-k")
            .arg(zip)
            .arg(dest)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .map_err(|e| format!("Cannot unpack Java 8: {e}"))?;
        if status.success() {
            Ok(())
        } else {
            Err("Cannot unpack Java 8.".to_string())
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = (zip, dest);
        Err("Unpacking Java 8 is only supported on macOS.".to_string())
    }
}

// ── the real machine ────────────────────────────────────────────────────────────

/// The host's architecture.
pub fn host_arch() -> Arch {
    if cfg!(target_arch = "aarch64") {
        Arch::Arm64
    } else {
        Arch::X64
    }
}

pub fn host_rosetta() -> bool {
    Path::new("/Library/Apple/usr/libexec/oah/libRosettaRuntime").exists()
}

/// `/usr/libexec/java_home -F -v 1.8 -a <arch>`.
pub fn host_java_home(arch: Arch) -> Option<PathBuf> {
    #[cfg(target_os = "macos")]
    {
        let out = std::process::Command::new("/usr/libexec/java_home")
            .args(["-F", "-v", "1.8", "-a", arch.java_home_name()])
            .stdin(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .output()
            .ok()?;
        if !out.status.success() {
            return None;
        }
        let home = String::from_utf8(out.stdout).ok()?;
        let home = home.trim();
        (!home.is_empty()).then(|| PathBuf::from(home))
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = arch;
        None
    }
}

/// One HTTPS GET with the updater's client settings (bounded connect and per-read waits).
/// Blocking: called from the setup run's own thread.
pub fn host_fetch(url: &str) -> Result<Vec<u8>, String> {
    use std::time::Duration;
    let url = url.to_string();
    tauri::async_runtime::block_on(async move {
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(15))
            .read_timeout(Duration::from_secs(60))
            .build()
            .map_err(|e| format!("Cannot download Java 8: {e}"))?;
        let response = client
            .get(&url)
            .send()
            .await
            .and_then(|r| r.error_for_status())
            .map_err(|e| format!("Cannot download Java 8: {e}"))?;
        if response
            .content_length()
            .is_some_and(|n| n > MAX_DOWNLOAD as u64)
        {
            return Err("A Java download was larger than expected.".to_string());
        }
        let body = response
            .bytes()
            .await
            .map_err(|e| format!("Cannot download Java 8: {e}"))?;
        Ok(body.to_vec())
    })
}

// ── SHA-1 (Mojang's manifest checksums; no crate in the tree provides it) ─────────

pub fn sha1_hex(data: &[u8]) -> String {
    let mut h: [u32; 5] = [0x6745_2301, 0xEFCD_AB89, 0x98BA_DCFE, 0x1032_5476, 0xC3D2_E1F0];
    let bit_len = (data.len() as u64).wrapping_mul(8);
    let mut msg = data.to_vec();
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bit_len.to_be_bytes());
    for chunk in msg.chunks_exact(64) {
        let mut w = [0u32; 80];
        for (i, word) in chunk.chunks_exact(4).enumerate() {
            w[i] = u32::from_be_bytes([word[0], word[1], word[2], word[3]]);
        }
        for i in 16..80 {
            w[i] = (w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16]).rotate_left(1);
        }
        let [mut a, mut b, mut c, mut d, mut e] = h;
        for (i, wi) in w.iter().enumerate() {
            let (f, k) = match i {
                0..=19 => ((b & c) | (!b & d), 0x5A82_7999),
                20..=39 => (b ^ c ^ d, 0x6ED9_EBA1),
                40..=59 => ((b & c) | (b & d) | (c & d), 0x8F1B_BCDC),
                _ => (b ^ c ^ d, 0xCA62_C1D6),
            };
            let t = a
                .rotate_left(5)
                .wrapping_add(f)
                .wrapping_add(e)
                .wrapping_add(k)
                .wrapping_add(*wi);
            e = d;
            d = c;
            c = b.rotate_left(30);
            b = a;
            a = t;
        }
        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
    }
    h.iter().map(|x| format!("{x:08x}")).collect()
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::sync::Mutex;

    /// A machine with stubbed java_home and a local "network" (a URL -> bytes map).
    pub(crate) struct Stub {
        pub arch: Arch,
        pub rosetta: bool,
        pub homes: HashMap<&'static str, PathBuf>,
        pub net: HashMap<String, Vec<u8>>,
        pub fetched: Mutex<Vec<String>>,
        pub asked: Mutex<Vec<Arch>>,
    }

    impl Stub {
        pub(crate) fn new(arch: Arch) -> Self {
            Stub {
                arch,
                rosetta: false,
                homes: HashMap::new(),
                net: HashMap::new(),
                fetched: Mutex::new(Vec::new()),
                asked: Mutex::new(Vec::new()),
            }
        }
    }

    impl JavaEnv for Stub {
        fn arch(&self) -> Arch {
            self.arch
        }
        fn rosetta(&self) -> bool {
            self.rosetta
        }
        fn java_home(&self, arch: Arch) -> Option<PathBuf> {
            self.asked.lock().unwrap().push(arch);
            self.homes.get(arch.java_home_name()).cloned()
        }
        fn fetch(&self, url: &str) -> Result<Vec<u8>, String> {
            self.fetched.lock().unwrap().push(url.to_string());
            self.net
                .get(url)
                .cloned()
                .ok_or_else(|| format!("no fixture for {url}"))
        }
    }

    pub(crate) const ZIP_URL: &str = "https://fixture.invalid/zulu8-jre.zip";

    /// A zipped runtime shaped like Azul's: one top folder holding `Contents/Home/...`.
    #[cfg(target_os = "macos")]
    pub(crate) fn fixture_zip() -> Vec<u8> {
        let dir = tempfile::tempdir().unwrap();
        let top = dir.path().join("zulu8.0-ca-jre8.0-macosx_aarch64");
        let home = top.join("Contents/Home");
        fs::create_dir_all(home.join("bin")).unwrap();
        fs::create_dir_all(home.join("lib")).unwrap();
        fs::write(home.join("bin/java"), b"#!/bin/sh\necho fixture java\n").unwrap();
        set_mode(&home.join("bin/java"), 0o644).unwrap(); // exec bits come from us
        fs::write(home.join("lib/rt.jar"), b"rt").unwrap();
        fs::write(home.join("release"), b"JAVA_VERSION=\"1.8.0_504\"\n").unwrap();
        fs::write(top.join("Contents/Info.plist"), b"<plist/>").unwrap();
        let zip = dir.path().join("fixture.zip");
        let ok = std::process::Command::new("/usr/bin/ditto")
            .args(["-c", "-k", "--keepParent"])
            .arg(&top)
            .arg(&zip)
            .status()
            .unwrap()
            .success();
        assert!(ok, "ditto made the fixture zip");
        fs::read(zip).unwrap()
    }

    /// index.json -> java8.json -> the archive, every hash real; `archive_hash` overrides
    /// the archive's checksum in java8.json (a wrong one must be refused).
    pub(crate) fn serve_archive(net: &mut HashMap<String, Vec<u8>>, zip: Vec<u8>, archive_hash: Option<&str>) {
        let java8 = serde_json::json!({
            "formatVersion": 1, "name": "Java 8", "uid": "net.minecraft.java", "version": "java8",
            "runtimes": [
                {"name": "jre-legacy", "runtimeOS": "mac-os-x64", "downloadType": "manifest",
                 "url": "https://fixture.invalid/manifest.json",
                 "checksum": {"type": "sha1", "hash": "0000"}},
                {"name": "jre-legacy", "runtimeOS": "mac-os-arm64", "downloadType": "archive",
                 "url": ZIP_URL,
                 "checksum": {"type": "sha256",
                              "hash": archive_hash.map(str::to_string).unwrap_or_else(|| sha256_hex(&zip))}}
            ]
        });
        let java8 = serde_json::to_vec(&java8).unwrap();
        let index = serde_json::json!({"formatVersion": 1, "versions": [
            {"version": "java17", "sha256": "ffff"},
            {"version": "java8", "sha256": sha256_hex(&java8)}]});
        net.insert(INDEX_URL.into(), serde_json::to_vec(&index).unwrap());
        net.insert(JAVA8_URL.into(), java8);
        net.insert(ZIP_URL.into(), zip);
    }

    fn instance(dir: &Path) -> PathBuf {
        let root = dir.join("instances/Cobblify 1.8.9");
        fs::create_dir_all(&root).unwrap();
        fs::write(root.join("instance.cfg"), prism::made_instance_cfg()).unwrap();
        root
    }

    fn auto_off(data: &Path) {
        fs::create_dir_all(data).unwrap();
        fs::write(
            data.join("prismlauncher.cfg"),
            "[General]\nConfigVersion=1.3\nAutomaticJavaDownload=false\n",
        )
        .unwrap();
    }

    #[test]
    fn sha1_matches_the_standard_vectors() {
        assert_eq!(sha1_hex(b""), "da39a3ee5e6b4b0d3255bfef95601890afd80709");
        assert_eq!(sha1_hex(b"abc"), "a9993e364706816aba3e25717850c26c9cd0d89d");
        assert_eq!(
            sha1_hex(b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1"
        );
        assert_eq!(sha1_hex(&vec![b'a'; 1_000_000]), "34aa973cd4c4daa4f61eeb2bdbad27316534016f");
    }

    #[test]
    fn prism_auto_download_follows_the_default_rule() {
        let data = tempfile::tempdir().unwrap();
        let d = data.path();
        assert!(prism_downloads_java(d), "no cfg at all: both default on");
        let cfg = |text: &str| fs::write(d.join("prismlauncher.cfg"), text).unwrap();
        cfg("[General]\nJavaPath=\n");
        assert!(prism_downloads_java(d), "empty JavaPath: on");
        cfg("[General]\nJavaPath=/x/bin/java\n");
        assert!(!prism_downloads_java(d), "a global JavaPath turns the defaults off");
        cfg("[General]\nJavaPath=/x/bin/java\nAutomaticJavaSwitch=true\nAutomaticJavaDownload=true\n");
        assert!(prism_downloads_java(d), "explicit on wins");
        cfg("[General]\nAutomaticJavaSwitch=true\nAutomaticJavaDownload=false\n");
        assert!(!prism_downloads_java(d), "switch without download is not a download");
        cfg("[General]\nAutomaticJavaSwitch=false\n");
        assert!(!prism_downloads_java(d), "download needs the switch too");
    }

    /// Step 1 wins over everything: nothing asked, nothing fetched.
    #[test]
    fn the_order_prism_first_then_java_home_then_jre_legacy_then_download() {
        let data = tempfile::tempdir().unwrap();
        let d = data.path();
        let mut env = Stub::new(Arch::Arm64);
        let jdk = tempfile::tempdir().unwrap();
        fs::create_dir_all(jdk.path().join("bin")).unwrap();
        fs::write(jdk.path().join("bin/java"), b"").unwrap();
        env.homes.insert("arm64", jdk.path().to_path_buf());

        assert_eq!(plan(&env, d), Plan::PrismDownloads);
        assert!(env.asked.lock().unwrap().is_empty(), "java_home not consulted");

        auto_off(d);
        assert_eq!(plan(&env, d), Plan::Pin(jdk.path().join("bin/java")));

        // No arm64 Java 8: x86_64 only with Rosetta.
        env.homes.clear();
        env.homes.insert("x86_64", jdk.path().to_path_buf());
        env.asked.lock().unwrap().clear();
        assert_eq!(plan(&env, d), Plan::Download);
        assert_eq!(*env.asked.lock().unwrap(), vec![Arch::Arm64]);
        env.rosetta = true;
        assert_eq!(plan(&env, d), Plan::Pin(jdk.path().join("bin/java")));

        // Prism's own jre-legacy is reused before any download.
        env.homes.clear();
        let ours = jre_legacy_java(d);
        fs::create_dir_all(ours.parent().unwrap()).unwrap();
        fs::write(&ours, b"").unwrap();
        assert_eq!(plan(&env, d), Plan::Pin(ours.clone()));
        fs::remove_file(&ours).unwrap();
        assert_eq!(plan(&env, d), Plan::Download);
        assert!(env.fetched.lock().unwrap().is_empty(), "planning never touches the network");
    }

    #[test]
    fn pin_writes_the_instance_cfg_only() {
        let data = tempfile::tempdir().unwrap();
        auto_off(data.path());
        let global = fs::read(data.path().join("prismlauncher.cfg")).unwrap();
        let root = instance(data.path());
        let java = data.path().join("java/jre-legacy/bin/java");
        pin(&root, &java).unwrap();
        let cfg = prism::read_ini(&root.join("instance.cfg")).unwrap();
        assert_eq!(cfg.get("OverrideJavaLocation").map(String::as_str), Some("true"));
        assert_eq!(cfg.get("JavaPath").map(PathBuf::from), Some(java.clone()));
        assert_eq!(cfg.get("name").map(String::as_str), Some("Cobblify 1.8.9"));
        assert!(cfg.get("AutomaticJava").is_none(), "AutomaticJava stays unset");
        assert_eq!(fs::read(data.path().join("prismlauncher.cfg")).unwrap(), global);
        // Idempotent.
        let before = fs::read(root.join("instance.cfg")).unwrap();
        pin(&root, &java).unwrap();
        assert_eq!(fs::read(root.join("instance.cfg")).unwrap(), before);
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn a_good_checksum_installs_jre_legacy_in_prisms_layout() {
        use std::os::unix::fs::PermissionsExt;
        let data = tempfile::tempdir().unwrap();
        let mut env = Stub::new(Arch::Arm64);
        serve_archive(&mut env.net, fixture_zip(), None);
        let java = install_jre_legacy(&env, data.path()).unwrap();
        let jre = data.path().join("java/jre-legacy");
        assert_eq!(java, jre.join("bin/java"));
        assert!(java.is_file());
        // Prism's SymlinkTask layout: <jre>/bin -> Contents/Home/bin (relative).
        assert_eq!(fs::read_link(jre.join("bin")).unwrap(), PathBuf::from("Contents/Home/bin"));
        assert_eq!(fs::read_link(jre.join("lib")).unwrap(), PathBuf::from("Contents/Home/lib"));
        assert!(jre.join("Contents/Info.plist").is_file());
        let mode = fs::metadata(&java).unwrap().permissions().mode();
        assert_eq!(mode & 0o111, 0o111, "java is executable: {mode:o}");
        let left: Vec<_> = fs::read_dir(data.path().join("java")).unwrap().flatten()
            .map(|e| e.file_name().to_string_lossy().into_owned()).collect();
        assert_eq!(left, vec!["jre-legacy".to_string()], "no staging left");
        assert_eq!(
            *env.fetched.lock().unwrap(),
            vec![INDEX_URL.to_string(), JAVA8_URL.to_string(), ZIP_URL.to_string()]
        );
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn a_bad_archive_checksum_is_refused_and_leaves_nothing() {
        let data = tempfile::tempdir().unwrap();
        let mut env = Stub::new(Arch::Arm64);
        serve_archive(&mut env.net, fixture_zip(), Some(&"0".repeat(64)));
        let err = install_jre_legacy(&env, data.path()).unwrap_err();
        assert!(err.contains("checksum"), "{err}");
        let left: Vec<_> = fs::read_dir(data.path().join("java")).unwrap().flatten().collect();
        assert!(left.is_empty(), "nothing left behind: {left:?}");
    }

    #[test]
    fn a_tampered_java8_list_is_refused_before_any_download() {
        let data = tempfile::tempdir().unwrap();
        let mut env = Stub::new(Arch::Arm64);
        serve_archive(&mut env.net, b"zip".to_vec(), None);
        env.net.get_mut(JAVA8_URL).unwrap().push(b' ');
        let err = install_jre_legacy(&env, data.path()).unwrap_err();
        assert!(err.contains("checksum"), "{err}");
        assert_eq!(env.fetched.lock().unwrap().len(), 2, "the archive is never fetched");
        assert!(!data.path().join("java").exists());
    }

    /// Mojang's manifest route (Intel): per-file sha1, exec bits, links, Prism's symlinks.
    #[cfg(unix)]
    #[test]
    fn the_manifest_route_checks_every_file_and_builds_the_same_layout() {
        use std::os::unix::fs::PermissionsExt;
        let data = tempfile::tempdir().unwrap();
        let mut env = Stub::new(Arch::X64);
        let java_bytes = b"#!/bin/sh\n".to_vec();
        let lib_bytes = b"libjli".to_vec();
        let file = |url: &str, bytes: &[u8], exec: bool| serde_json::json!({
            "type": "file", "executable": exec,
            "downloads": {"raw": {"url": url, "sha1": sha1_hex(bytes), "size": bytes.len()}}});
        let manifest = serde_json::json!({"files": {
            "jre.bundle/Contents/Home": {"type": "directory"},
            "jre.bundle/Contents/Home/bin/java": file("https://fixture.invalid/java", &java_bytes, true),
            "jre.bundle/Contents/MacOS/libjli.dylib": file("https://fixture.invalid/jli", &lib_bytes, false),
            "jre.bundle/Contents/Home/lib/jli": {"type": "link", "target": "../../MacOS/libjli.dylib"}
        }});
        let manifest = serde_json::to_vec(&manifest).unwrap();
        let java8 = serde_json::to_vec(&serde_json::json!({"runtimes": [
            {"name": "jre-legacy", "runtimeOS": "mac-os-x64", "downloadType": "manifest",
             "url": "https://fixture.invalid/manifest.json",
             "checksum": {"type": "sha1", "hash": sha1_hex(&manifest)}}]})).unwrap();
        let index = serde_json::to_vec(&serde_json::json!({"versions": [
            {"version": "java8", "sha256": sha256_hex(&java8)}]})).unwrap();
        env.net.insert(INDEX_URL.into(), index);
        env.net.insert(JAVA8_URL.into(), java8);
        env.net.insert("https://fixture.invalid/manifest.json".into(), manifest);
        env.net.insert("https://fixture.invalid/java".into(), java_bytes.clone());
        env.net.insert("https://fixture.invalid/jli".into(), lib_bytes);

        let java = install_jre_legacy(&env, data.path()).unwrap();
        let jre = data.path().join("java/jre-legacy");
        assert_eq!(fs::read(&java).unwrap(), java_bytes);
        assert_eq!(
            fs::read_link(jre.join("bin")).unwrap(),
            PathBuf::from("jre.bundle/Contents/Home/bin")
        );
        assert_eq!(fs::metadata(&java).unwrap().permissions().mode() & 0o777, 0o755);
        assert_eq!(fs::read(jre.join("lib/jli")).unwrap(), b"libjli");

        // One wrong file hash refuses the whole runtime and leaves nothing.
        let data = tempfile::tempdir().unwrap();
        env.net.insert("https://fixture.invalid/java".into(), b"tampered".to_vec());
        assert!(install_jre_legacy(&env, data.path()).is_err());
        let left: Vec<_> = fs::read_dir(data.path().join("java")).unwrap().flatten().collect();
        assert!(left.is_empty(), "{left:?}");
    }

    #[test]
    fn manifest_paths_and_links_cannot_escape_the_runtime() {
        assert!(safe_relative("a/b").is_some());
        for bad in ["", "/etc/passwd", "../x", "a/../../x", "a/./b"] {
            // "./" is normalised away by Path; only "a/./b" may pass, as "a/b".
            if bad == "a/./b" {
                assert_eq!(safe_relative(bad), Some(PathBuf::from("a/b")));
            } else {
                assert!(safe_relative(bad).is_none(), "{bad}");
            }
        }
        assert!(stays_inside(Path::new("a/b/../../c")));
        assert!(!stays_inside(Path::new("a/../../c")));
    }

    #[test]
    fn an_existing_jre_legacy_is_never_replaced() {
        let data = tempfile::tempdir().unwrap();
        fs::create_dir_all(data.path().join("java/jre-legacy")).unwrap();
        let mut env = Stub::new(Arch::Arm64);
        serve_archive(&mut env.net, b"zip".to_vec(), None);
        assert!(install_jre_legacy(&env, data.path()).is_err());
        assert!(data.path().join("java/jre-legacy").is_dir());
    }

    #[test]
    fn plain_http_is_refused() {
        let env = Stub::new(Arch::Arm64);
        assert!(fetch(&env, "http://meta.prismlauncher.org/x").is_err());
        assert!(env.fetched.lock().unwrap().is_empty());
    }
}
