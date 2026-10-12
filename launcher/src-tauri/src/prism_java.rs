//! Java 8 for an instance Cobblify MADE (spec 1.5; existing instances keep Prism's own Java).
//!
//! The order (PLAN D):
//!   1. Prism downloads Java itself when `AutomaticJavaSwitch` AND `AutomaticJavaDownload`
//!      are on (`Application.cpp:741-754`: each defaults to on exactly when the global
//!      `JavaPath` is absent or empty) -> nothing to do.
//!   2. An installed Java 8 -> pin its java. macOS: `/usr/libexec/java_home -F -v 1.8 -a <arch>`
//!      (`-F`: without it java_home silently falls back to a non-8 JVM); on Apple Silicon
//!      x86_64 is also tried when Rosetta is present. Windows: the homes Java 8 installers
//!      record in the registry and the vendors' default Program Files folders, each proven by
//!      its `release` file (`java8_home`).
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
//! Prism's global settings are never written. The java pinned is `bin/java`, on Windows
//! `bin\javaw.exe` (what Prism itself runs there, `JAVA_BIN`).

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
/// The java Prism runs, inside a Java home: `bin/java`; Windows `bin/javaw.exe` (no console).
pub const JAVA_BIN: &str = if cfg!(windows) { "bin/javaw.exe" } else { "bin/java" };

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
    /// Prism meta's `runtimeOS` on this platform.
    pub fn runtime_os(self) -> &'static str {
        match (cfg!(windows), self) {
            (true, Arch::Arm64) => "windows-arm64",
            (true, Arch::X64) => "windows-x64",
            (false, Arch::Arm64) => "mac-os-arm64",
            (false, Arch::X64) => "mac-os-x64",
        }
    }
}

/// The machine facts and network the Java step needs, injected for tests.
pub trait JavaEnv {
    fn arch(&self) -> Arch;
    /// x86_64 Java can run on this arm64 machine (Rosetta 2; Windows 11's x64 emulation).
    fn rosetta(&self) -> bool;
    /// An installed Java 8 home for `arch`, or `None` (macOS `java_home -F -v 1.8 -a <arch>`;
    /// Windows `host_java_home`).
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
    prism::java_dir(data).join(JRE_NAME).join(JAVA_BIN)
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
            let java = home.join(JAVA_BIN);
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
    Err(if cfg!(windows) {
        "Prism has no Java 8 for this PC.".to_string()
    } else {
        "Prism has no Java 8 for this Mac.".to_string()
    })
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
        if !root.join(JAVA_BIN).is_file() {
            return Err("The Java 8 download has no java.".to_string());
        }
        rename_settling(&root, &dest)
            .map_err(|e| format!("Cannot put Java 8 in Prism's Java folder: {e}"))
    })();
    remove_settling(&work);
    result?;
    Ok(dest.join(JAVA_BIN))
}

/// How long a just-written runtime may stay locked. On Windows a folder can't be renamed or
/// removed while any file in it is open, and antivirus opens new executables and DLLs to scan
/// them right after they are written.
const SETTLE: std::time::Duration = std::time::Duration::from_secs(15);
const SETTLE_STEP: std::time::Duration = std::time::Duration::from_millis(250);

/// `rename_no_replace`, retried while the folder is still held open (never over an existing
/// target: that answer is final).
fn rename_settling(from: &Path, to: &Path) -> std::io::Result<()> {
    let deadline = std::time::Instant::now() + SETTLE;
    loop {
        match prism::rename_no_replace(from, to) {
            Err(e) if e.kind() != std::io::ErrorKind::AlreadyExists
                && from.exists()
                && std::time::Instant::now() < deadline =>
            {
                std::thread::sleep(SETTLE_STEP)
            }
            other => return other,
        }
    }
}

/// Removes a staging folder, retried the same way; best effort.
fn remove_settling(dir: &Path) {
    let deadline = std::time::Instant::now() + SETTLE;
    while fs::remove_dir_all(dir).is_err() && dir.exists() && std::time::Instant::now() < deadline {
        std::thread::sleep(SETTLE_STEP);
    }
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
/// targets, because the folder is renamed into place afterwards. (Windows runtimes are flat:
/// `bin\javaw.exe` is already there.)
fn link_home(root: &Path) -> Result<(), String> {
    if root.join(JAVA_BIN).exists() {
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
    if cfg!(windows) {
        // Windows 11 on Arm runs x64 programs.
        cfg!(target_arch = "aarch64")
    } else {
        Path::new("/Library/Apple/usr/libexec/oah/libRosettaRuntime").exists()
    }
}

/// The vendors' default install parents under Program Files (one Java home per child).
#[cfg_attr(not(windows), allow(dead_code))]
const VENDOR_DIRS: &[&str] = &[
    "Java",
    "Eclipse Adoptium",
    "Eclipse Foundation",
    "AdoptOpenJDK",
    "Zulu",
    "Amazon Corretto",
    "BellSoft",
    "Microsoft",
];

/// The folders one level under each vendor parent in `program_files`, sorted.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn vendor_homes(program_files: &[PathBuf]) -> Vec<PathBuf> {
    let mut out = Vec::new();
    for root in program_files {
        for vendor in VENDOR_DIRS {
            let mut homes: Vec<PathBuf> = fs::read_dir(root.join(vendor))
                .into_iter()
                .flatten()
                .flatten()
                .map(|e| e.path())
                .filter(|p| p.is_dir())
                .collect();
            homes.sort();
            out.extend(homes);
        }
    }
    out
}

/// The first `candidates` home that is surely a Java 8 for `arch`: its `release` file says
/// `JAVA_VERSION="1.8..."` and an `OS_ARCH` of that architecture, and `JAVA_BIN` exists.
/// Nothing is run.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn java8_home(candidates: &[PathBuf], arch: Arch) -> Option<PathBuf> {
    let arch_names: &[&str] = match arch {
        Arch::X64 => &["amd64", "x86_64", "x64"],
        Arch::Arm64 => &["aarch64", "arm64"],
    };
    candidates.iter().find(|home| {
        let Ok(text) = fs::read_to_string(home.join("release")) else {
            return false;
        };
        let value = |key: &str| {
            text.lines()
                .find_map(|l| l.trim().strip_prefix(key)?.strip_prefix('='))
                .map(|v| v.trim().trim_matches('"').to_string())
        };
        let java8 = value("JAVA_VERSION").is_some_and(|v| v.starts_with("1.8"));
        let right_arch = value("OS_ARCH").is_some_and(|v| arch_names.iter().any(|a| v.eq_ignore_ascii_case(a)));
        java8 && right_arch && home.join(JAVA_BIN).is_file()
    })
    .cloned()
}

/// Every Java home the registry records (64-bit view): Oracle's JavaSoft keys, the Adoptium
/// family's MSI keys, Azul's Zulu key. Read-only.
#[cfg(windows)]
fn registry_java_homes() -> Vec<PathBuf> {
    use crate::win_reg::{string, subkeys, HKEY_LOCAL_MACHINE as HKLM};
    let mut out = Vec::new();
    for kind in ["Java Runtime Environment", "Java Development Kit"] {
        let base = format!(r"SOFTWARE\JavaSoft\{kind}");
        for v in subkeys(HKLM, &base) {
            if v.starts_with("1.8") {
                out.extend(string(HKLM, &format!(r"{base}\{v}"), "JavaHome").map(PathBuf::from));
            }
        }
    }
    for vendor in ["Eclipse Adoptium", "Eclipse Foundation", "AdoptOpenJDK"] {
        for kind in ["JRE", "JDK"] {
            let base = format!(r"SOFTWARE\{vendor}\{kind}");
            for v in subkeys(HKLM, &base) {
                if v.starts_with("8.") || v.starts_with("1.8") {
                    out.extend(string(HKLM, &format!(r"{base}\{v}\hotspot\MSI"), "Path").map(PathBuf::from));
                }
            }
        }
    }
    out.extend(string(HKLM, r"SOFTWARE\Azul Systems\Zulu\zulu-8", "InstallationPath").map(PathBuf::from));
    out
}

/// macOS: `/usr/libexec/java_home -F -v 1.8 -a <arch>`. Windows: the registry's Java homes,
/// then the vendors' Program Files folders, the first proven Java 8 (`java8_home`).
pub fn host_java_home(arch: Arch) -> Option<PathBuf> {
    #[cfg(windows)]
    {
        let mut candidates = registry_java_homes();
        let program_files: Vec<PathBuf> = ["ProgramW6432", "ProgramFiles"]
            .iter()
            .filter_map(|k| std::env::var_os(k).map(PathBuf::from))
            .collect();
        candidates.extend(vendor_homes(&program_files));
        return java8_home(&candidates, arch);
    }
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
    #[cfg(not(any(target_os = "macos", windows)))]
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
                {"name": "jre-legacy", "runtimeOS": Arch::X64.runtime_os(), "downloadType": "manifest",
                 "url": "https://fixture.invalid/manifest.json",
                 "checksum": {"type": "sha1", "hash": "0000"}},
                {"name": "jre-legacy", "runtimeOS": Arch::Arm64.runtime_os(), "downloadType": "archive",
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
        fs::write(jdk.path().join(JAVA_BIN), b"").unwrap();
        env.homes.insert("arm64", jdk.path().to_path_buf());

        assert_eq!(plan(&env, d), Plan::PrismDownloads);
        assert!(env.asked.lock().unwrap().is_empty(), "java_home not consulted");

        auto_off(d);
        assert_eq!(plan(&env, d), Plan::Pin(jdk.path().join(JAVA_BIN)));

        // No arm64 Java 8: x86_64 only with Rosetta.
        env.homes.clear();
        env.homes.insert("x86_64", jdk.path().to_path_buf());
        env.asked.lock().unwrap().clear();
        assert_eq!(plan(&env, d), Plan::Download);
        assert_eq!(*env.asked.lock().unwrap(), vec![Arch::Arm64]);
        env.rosetta = true;
        assert_eq!(plan(&env, d), Plan::Pin(jdk.path().join(JAVA_BIN)));

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

    /// Mojang's flat runtimes (Windows x64/x86, Linux): no links, `JAVA_BIN` already at the
    /// root. Every file is checked; the pinned java is `JAVA_BIN` (Windows `bin\javaw.exe`).
    #[test]
    fn a_flat_manifest_runtime_installs_with_its_java_where_prism_runs_it() {
        let data = tempfile::tempdir().unwrap();
        let mut env = Stub::new(Arch::X64);
        let java_bytes = b"MZ fixture javaw".to_vec();
        let dll_bytes = b"MZ fixture dll".to_vec();
        let file = |url: &str, bytes: &[u8], exec: bool| serde_json::json!({
            "type": "file", "executable": exec,
            "downloads": {"raw": {"url": url, "sha1": sha1_hex(bytes), "size": bytes.len()}}});
        let manifest = serde_json::json!({"files": {
            "bin": {"type": "directory"},
            JAVA_BIN: file("https://fixture.invalid/javaw", &java_bytes, true),
            "bin/java.dll": file("https://fixture.invalid/dll", &dll_bytes, false),
            "lib": {"type": "directory"}
        }});
        let manifest = serde_json::to_vec(&manifest).unwrap();
        let java8 = serde_json::to_vec(&serde_json::json!({"runtimes": [
            {"name": "jre-legacy", "runtimeOS": Arch::X64.runtime_os(), "downloadType": "manifest",
             "url": "https://fixture.invalid/manifest.json",
             "checksum": {"type": "sha1", "hash": sha1_hex(&manifest)}}]})).unwrap();
        let index = serde_json::to_vec(&serde_json::json!({"versions": [
            {"version": "java8", "sha256": sha256_hex(&java8)}]})).unwrap();
        env.net.insert(INDEX_URL.into(), index);
        env.net.insert(JAVA8_URL.into(), java8);
        env.net.insert("https://fixture.invalid/manifest.json".into(), manifest);
        env.net.insert("https://fixture.invalid/javaw".into(), java_bytes.clone());
        env.net.insert("https://fixture.invalid/dll".into(), dll_bytes.clone());

        let java = install_jre_legacy(&env, data.path()).unwrap();
        assert_eq!(java, data.path().join("java/jre-legacy").join(JAVA_BIN));
        assert_eq!(java, jre_legacy_java(data.path()), "what the next plan reuses");
        assert_eq!(fs::read(&java).unwrap(), java_bytes);
        assert_eq!(fs::read(data.path().join("java/jre-legacy/bin/java.dll")).unwrap(), dll_bytes);
        assert!(data.path().join("java/jre-legacy/lib").is_dir());

        // A wrong size or a wrong hash refuses the whole runtime and leaves nothing.
        for bad in [b"MZ fixture javaw!".to_vec(), b"MZ fixture javaX".to_vec()] {
            let data = tempfile::tempdir().unwrap();
            env.net.insert("https://fixture.invalid/javaw".into(), bad);
            assert!(install_jre_legacy(&env, data.path()).is_err());
            let left: Vec<_> = fs::read_dir(data.path().join("java")).unwrap().flatten().collect();
            assert!(left.is_empty(), "{left:?}");
        }
    }

    /// Windows' installed-Java lookup, proven by each home's `release` file (plan-1 B2: a
    /// Temurin 8 in its default folder counts with no registry entry).
    #[test]
    fn an_installed_java_8_is_proven_by_its_release_file() {
        let pf = tempfile::tempdir().unwrap();
        let home = |rel: &str, release: Option<&str>, java: bool| {
            let h = pf.path().join(rel);
            fs::create_dir_all(h.join("bin")).unwrap();
            if let Some(r) = release {
                fs::write(h.join("release"), r).unwrap();
            }
            if java {
                fs::write(h.join(JAVA_BIN), b"").unwrap();
            }
            h
        };
        let java11 = home("Eclipse Adoptium/jdk-11.0.20-hotspot", Some("JAVA_VERSION=\"11.0.20\"\nOS_ARCH=\"x86_64\"\n"), true);
        let x86 = home("Java/jre1.8.0_381 (x86)", Some("JAVA_VERSION=\"1.8.0_381\"\nOS_ARCH=\"x86\"\n"), true);
        let no_java = home("Zulu/zulu-8-broken", Some("JAVA_VERSION=\"1.8.0_402\"\nOS_ARCH=\"amd64\"\n"), false);
        let no_release = home("Java/jre-unknown", None, true);
        let temurin = home("Eclipse Adoptium/jre-8.0.402.6-hotspot", Some("IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"1.8.0_402\"\nOS_ARCH=\"x86_64\"\n"), true);
        let oracle = home("Java/jre1.8.0_381", Some("JAVA_VERSION=\"1.8.0_381\"\nOS_NAME=\"Windows\"\nOS_ARCH=\"amd64\"\n"), true);

        let found = vendor_homes(&[pf.path().to_path_buf()]);
        for h in [&java11, &x86, &no_java, &no_release, &temurin, &oracle] {
            assert!(found.contains(h), "{} found by the folder scan", h.display());
        }
        assert_eq!(java8_home(&found, Arch::X64), Some(oracle.clone()), "the first proven one, in scan order");
        assert_eq!(java8_home(&[java11.clone(), temurin.clone()], Arch::X64), Some(temurin.clone()));
        assert_eq!(java8_home(&[java11.clone(), x86.clone(), no_java.clone(), no_release.clone()], Arch::X64), None);
        assert_eq!(java8_home(&[oracle.clone()], Arch::X64), Some(oracle.clone()));
        assert_eq!(java8_home(&[oracle], Arch::Arm64), None, "an x64 Java is not an arm64 one");
        assert!(vendor_homes(&[pf.path().join("nowhere")]).is_empty());
    }

    /// Subagent review I2: a runtime folder held open for a moment (antivirus scanning a new
    /// DLL) is still put in place once it is released; an existing target is final at once.
    #[test]
    fn a_briefly_locked_runtime_is_renamed_once_released() {
        let dir = tempfile::tempdir().unwrap();
        let from = dir.path().join("staged");
        fs::create_dir_all(from.join("bin")).unwrap();
        fs::write(from.join("bin/x.dll"), b"dll").unwrap();
        let to = dir.path().join("jre-legacy");
        let held = fs::File::open(from.join("bin/x.dll")).unwrap();
        let release = std::thread::spawn(move || {
            std::thread::sleep(std::time::Duration::from_millis(700));
            drop(held);
        });
        rename_settling(&from, &to).unwrap();
        release.join().unwrap();
        assert!(to.join("bin/x.dll").is_file() && !from.exists());

        let again = dir.path().join("again");
        fs::create_dir_all(&again).unwrap();
        let started = std::time::Instant::now();
        let err = rename_settling(&again, &to).unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::AlreadyExists);
        assert!(started.elapsed() < std::time::Duration::from_secs(2), "no waiting on a taken name");
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
