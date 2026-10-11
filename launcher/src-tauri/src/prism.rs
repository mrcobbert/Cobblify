//! Prism Launcher facts, read-only except for the one instance Cobblify makes.
//!
//! * Installed: `Prism Launcher.app` in `/Applications` or `~/Applications`, else - only when
//!   neither holds it - wherever Spotlight knows Prism's bundle id (an app left in Downloads,
//!   say), with `lunar_detect`'s rules for which registrations count.
//!   Windows: `prismlauncher.exe` in the folder the installer recorded, its default per-user
//!   folder, an all-users copy, or Scoop; else the Prism Cobblify remembered, the portable Prism
//!   owning the saved instance, or - the stand-in for Spotlight - a running `prismlauncher.exe`
//!   (`find_exe_among`).
//! * Data: `~/Library/Application Support/PrismLauncher`; Windows: Prism's own rule
//!   (`windows_data_dir`). Instances under its `InstanceDir` (`prismlauncher.cfg`, default
//!   `instances`), Java downloads under `JavaDir` (`java`).
//! * Signed in: `accounts.json` (formatVersion 3) has an ACTIVE, non-Offline account whose
//!   `entitlement.ownsMinecraft` is true - what `--launch` needs to raise no account dialog.
//! * Made instance: "Cobblify 1.8.9", written in the exact shape Prism 11 saves, staged under
//!   `<instances>/.cobblify-staging/<random>/` and renamed into place once, never over an
//!   existing folder (Prism watches only the instances folder itself, so the instance must
//!   appear whole, in one rename).
//!
//! PRIVACY: Prism keeps live Microsoft tokens in plain text in `accounts.json`. It is read
//! ONLY through the narrow deserializer below, which keeps `formatVersion` and, per account,
//! whether its `type` is Offline, `active`, and `entitlement.ownsMinecraft`. Every other key
//! (tokens, profile, names, ids) is skipped by serde without being kept. Nothing here may
//! ever print the file or any part of it.
//!
//! Prism's global settings (`prismlauncher.cfg`) are read, never written.

use std::collections::HashMap;
use std::ffi::OsString;
use std::fmt;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use serde::de::{Deserializer, Visitor};
use serde::Deserialize;

#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub const BUNDLE_NAME: &str = "Prism Launcher.app";
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub const BUNDLE_EXE: &str = "Contents/MacOS/prismlauncher";
pub const GLOBAL_CFG: &str = "prismlauncher.cfg";
pub const INSTANCE_CFG: &str = "instance.cfg";
pub const PACK_FILE: &str = "mmc-pack.json";
/// The name (and first-choice folder name) of the instance Cobblify makes.
pub const MADE_NAME: &str = "Cobblify 1.8.9";
/// Two levels below the instances folder, so Prism never discovers anything staged there.
/// (`.tmp` is Prism's own and is wiped at its startup.)
pub const STAGING_DIR: &str = ".cobblify-staging";

// ── where things are ────────────────────────────────────────────────────────────

/// Every place a Prism bundle is looked for, in order.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn app_candidates(home: &Path) -> Vec<PathBuf> {
    vec![
        Path::new("/Applications").join(BUNDLE_NAME),
        home.join("Applications").join(BUNDLE_NAME),
    ]
}

/// The first candidate whose executable exists.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn find_app_in(candidates: &[PathBuf]) -> Option<PathBuf> {
    candidates
        .iter()
        .find(|app| app.join(BUNDLE_EXE).is_file())
        .cloned()
}

/// Prism's bundle identifier (`CFBundleIdentifier`).
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub const BUNDLE_ID: &str = "org.prismlauncher.PrismLauncher";

/// The installed Prism. macOS: the app bundle.
#[cfg(target_os = "macos")]
pub fn find_app(home: &Path) -> Option<PathBuf> {
    use crate::lunar_detect::{spotlight_lookup_id, LOOKUP_TIMEOUT, MDFIND};
    find_app_with(home, &|| spotlight_lookup_id(Path::new(MDFIND), BUNDLE_ID, LOOKUP_TIMEOUT))
}

/// The installed bundle: a standard folder's first, else the first usable registration the
/// bundle-id `lookup` names (run only when neither standard folder has one) whose executable
/// exists.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub fn find_app_with(home: &Path, lookup: &dyn Fn() -> Vec<PathBuf>) -> Option<PathBuf> {
    find_app_among(&app_candidates(home), lookup)
}

/// `find_app_with` over given standard `candidates` (tests keep the real `/Applications` out).
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
fn find_app_among(candidates: &[PathBuf], lookup: &dyn Fn() -> Vec<PathBuf>) -> Option<PathBuf> {
    find_app_in(candidates).or_else(|| {
        lookup()
            .into_iter()
            .find(|app| crate::lunar_detect::usable_registration(app) && app.join(BUNDLE_EXE).is_file())
    })
}

/// Prism's executable in a Windows install folder.
#[cfg_attr(not(windows), allow(dead_code))]
pub const WIN_EXE: &str = "prismlauncher.exe";
/// The install folder's name (the installer's `Launcher_CommonName`).
#[cfg_attr(not(windows), allow(dead_code))]
const WIN_DIR: &str = "PrismLauncher";
/// Where Cobblify remembers a Prism found outside the standard places (Windows).
#[cfg_attr(not(windows), allow(dead_code))]
const REMEMBERED: &str = ".cobblify/prism-location.json";

/// The standard places a Windows Prism lives, in order: the folder the installer recorded
/// (`HKCU\Software\PrismLauncher\InstallDir`, passed in), the installer's default per-user
/// folder, all-users copies, then Scoop's current version (the user's Scoop, then the global
/// one). `var` reads an environment variable; unusable (empty or relative) values are skipped.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn windows_candidates(var: &dyn Fn(&str) -> Option<OsString>, install_dir: Option<PathBuf>) -> Vec<PathBuf> {
    let path = |k: &str| {
        var(k)
            .filter(|v| !v.is_empty())
            .map(PathBuf::from)
            .filter(|p| p.is_absolute())
    };
    let mut dirs: Vec<PathBuf> = install_dir.filter(|p| p.is_absolute()).into_iter().collect();
    dirs.extend(path("LOCALAPPDATA").map(|l| l.join("Programs").join(WIN_DIR)));
    for k in ["ProgramFiles", "ProgramFiles(x86)"] {
        dirs.extend(path(k).map(|p| p.join(WIN_DIR)));
    }
    let scoop_user = path("SCOOP").or_else(|| path("USERPROFILE").map(|h| h.join("scoop")));
    let scoop_global = path("SCOOP_GLOBAL").or_else(|| path("ProgramData").map(|d| d.join("scoop")));
    for scoop in [scoop_user, scoop_global].into_iter().flatten() {
        dirs.push(scoop.join("apps").join("prismlauncher").join("current"));
    }
    dirs.into_iter().map(|d| d.join(WIN_EXE)).collect()
}

/// The portable Prism that owns a saved instance folder, by Prism's portable layouts: the
/// instances folder sits in the data folder, which is the exe's own folder (`portable.txt`)
/// or its `UserData` subfolder. None when no exe is there (an installed Prism, or a custom
/// `InstanceDir`).
#[cfg_attr(not(windows), allow(dead_code))]
pub fn owner_of_instance(instance_root: &Path) -> Option<PathBuf> {
    let data = instance_root.parent()?.parent()?;
    let beside = data.join(WIN_EXE);
    if beside.is_file() && data.join("portable.txt").is_file() {
        return Some(beside);
    }
    let is_user_data = data
        .file_name()
        .is_some_and(|n| n.to_string_lossy().eq_ignore_ascii_case("UserData"));
    data.parent()
        .filter(|_| is_user_data)
        .map(|dir| dir.join(WIN_EXE))
        .filter(|exe| exe.is_file())
}

/// The Windows lookup with every source injected: the first standard candidate that exists,
/// else the remembered exe, else the saved instance's portable owner, else a running Prism
/// (`running` is asked only then). The flag says the find should be remembered: it came from
/// the owner or the running process and is not already the remembered one.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn find_exe_among(
    standard: &[PathBuf],
    remembered: Option<PathBuf>,
    owner: Option<PathBuf>,
    running: &dyn Fn() -> Option<PathBuf>,
) -> Option<(PathBuf, bool)> {
    if let Some(exe) = standard.iter().find(|e| e.is_file()) {
        return Some((exe.clone(), false));
    }
    let remembered = remembered.filter(|e| e.is_file());
    if let Some(exe) = remembered.clone() {
        return Some((exe, false));
    }
    owner
        .filter(|e| e.is_file())
        .or_else(|| running().filter(|e| e.is_file()))
        .map(|exe| {
            let new = remembered.as_ref() != Some(&exe);
            (exe, new)
        })
}

/// The exe Cobblify remembered, if the record is readable.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn remembered_exe(home: &Path) -> Option<PathBuf> {
    let v: serde_json::Value = serde_json::from_slice(&fs::read(home.join(REMEMBERED)).ok()?).ok()?;
    v.get("exe")
        .and_then(serde_json::Value::as_str)
        .filter(|s| !s.is_empty())
        .map(PathBuf::from)
}

/// Records `exe` as the Prism to find next time (Cobblify's own state, never Prism's).
#[cfg_attr(not(windows), allow(dead_code))]
pub fn remember_exe(home: &Path, exe: &Path) -> Result<(), String> {
    let file = home.join(REMEMBERED);
    let dir = file.parent().ok_or("No folder for the Prism record.")?;
    fs::create_dir_all(dir).map_err(|e| format!("Cannot create {}: {e}", dir.display()))?;
    let body = serde_json::json!({ "exe": exe.to_string_lossy() }).to_string();
    write_atomic(&file, body.as_bytes())
}

/// The installed Prism. Windows: its `prismlauncher.exe` (see `find_exe_among`).
#[cfg(windows)]
pub fn find_app(home: &Path) -> Option<PathBuf> {
    let install = crate::win_reg::string(
        crate::win_reg::HKEY_CURRENT_USER,
        r"Software\PrismLauncher",
        "InstallDir",
    )
    .map(PathBuf::from);
    let standard = windows_candidates(&|k| std::env::var_os(k), install);
    let owner = crate::forge::saved_instance_root(home).and_then(|r| owner_of_instance(&r));
    let (exe, remember) = find_exe_among(&standard, remembered_exe(home), owner, &crate::proc::running_prism_exe)?;
    if remember {
        let _ = remember_exe(home, &exe);
    }
    Some(exe)
}

/// Prism's data folder on Windows, by Prism's own rule (`Application.cpp`): the
/// `PRISMLAUNCHER_DATA_DIR` environment value, else the exe's `UserData` folder if it exists,
/// else the exe's own folder when `portable.txt` is beside it, else `%APPDATA%\PrismLauncher`.
#[cfg_attr(not(windows), allow(dead_code))]
pub fn windows_data_dir(exe: Option<&Path>, appdata: Option<&Path>, data_env: Option<OsString>) -> Option<PathBuf> {
    if let Some(dir) = data_env.filter(|d| !d.is_empty()) {
        return Some(PathBuf::from(dir));
    }
    if let Some(dir) = exe.and_then(Path::parent) {
        let user_data = dir.join("UserData");
        if user_data.is_dir() {
            return Some(user_data);
        }
        if dir.join("portable.txt").is_file() {
            return Some(dir.to_path_buf());
        }
    }
    appdata.map(data_dir)
}

/// Prism's data directory under the per-application data root.
pub fn data_dir(app_support: &Path) -> PathBuf {
    app_support.join("PrismLauncher")
}

/// `prismlauncher.cfg` as key/value pairs (empty when missing or unreadable).
pub fn global_cfg(data: &Path) -> HashMap<String, String> {
    read_ini(&data.join(GLOBAL_CFG)).unwrap_or_default()
}

/// A directory setting of `prismlauncher.cfg`, relative to the data dir unless absolute.
fn cfg_dir(data: &Path, cfg: &HashMap<String, String>, key: &str, default: &str) -> PathBuf {
    let value = cfg
        .get(key)
        .map(|v| v.trim())
        .filter(|v| !v.is_empty())
        .unwrap_or(default);
    let path = Path::new(value);
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        data.join(path)
    }
}

/// Where Prism keeps its instances (`InstanceDir`, default `instances`).
pub fn instances_dir(data: &Path) -> PathBuf {
    cfg_dir(data, &global_cfg(data), "InstanceDir", "instances")
}

/// Where Prism keeps the Java runtimes it downloads (`JavaDir`, default `java`).
pub fn java_dir(data: &Path) -> PathBuf {
    cfg_dir(data, &global_cfg(data), "JavaDir", "java")
}

// ── INI (Qt QSettings IniFormat, as Prism writes it) ───────────────────────────────

/// The top-level and `[General]` keys of a Prism cfg file; other sections are skipped.
/// `None` when the file cannot be read.
pub fn read_ini(path: &Path) -> Option<HashMap<String, String>> {
    let bytes = fs::read(path).ok()?;
    Some(parse_ini(&String::from_utf8_lossy(&bytes)))
}

pub fn parse_ini(text: &str) -> HashMap<String, String> {
    let mut out = HashMap::new();
    let mut in_general = true;
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with(';') || line.starts_with('#') {
            continue;
        }
        if line.starts_with('[') && line.ends_with(']') {
            in_general = line.eq_ignore_ascii_case("[General]");
            continue;
        }
        if !in_general {
            continue;
        }
        if let Some((k, v)) = line.split_once('=') {
            out.insert(k.trim().to_string(), unquote(v.trim()));
        }
    }
    out
}

/// A QSettings value: `"..."` with `\"` / `\\` escapes, or a bare value.
fn unquote(v: &str) -> String {
    let Some(inner) = v.strip_prefix('"') else {
        return v.replace("\\\\", "\\");
    };
    let mut out = String::new();
    let mut chars = inner.chars();
    while let Some(c) = chars.next() {
        match c {
            '"' => break,
            '\\' => match chars.next() {
                Some('n') => out.push('\n'),
                Some('t') => out.push('\t'),
                Some(other) => out.push(other),
                None => break,
            },
            c => out.push(c),
        }
    }
    out
}

/// How QSettings writes a value: bare unless it holds a character it must quote.
pub fn ini_value(v: &str) -> String {
    let needs_quotes = v.chars().any(|c| matches!(c, ';' | ',' | '=' | '"' | '\\') || c.is_control())
        || v.starts_with(' ')
        || v.ends_with(' ');
    if !needs_quotes {
        return v.to_string();
    }
    let mut out = String::from("\"");
    for c in v.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\t' => out.push_str("\\t"),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// Sets `keys` in the `[General]` section of an existing cfg file, keeping every other
/// line as it is, and writes it atomically (temp file in the same folder, then rename).
pub fn set_ini_keys(path: &Path, keys: &[(&str, &str)]) -> Result<(), String> {
    let text = fs::read_to_string(path).map_err(|e| format!("Cannot read {}: {e}", path.display()))?;
    let mut lines: Vec<String> = text.lines().map(str::to_string).collect();
    // The [General] section's line range; a file without sections is all "General".
    let has_sections = lines.iter().any(|l| {
        let t = l.trim();
        t.starts_with('[') && t.ends_with(']')
    });
    let (start, mut end) = if has_sections {
        match lines
            .iter()
            .position(|l| l.trim().eq_ignore_ascii_case("[General]"))
        {
            Some(header) => {
                let end = lines[header + 1..]
                    .iter()
                    .position(|l| l.trim().starts_with('['))
                    .map(|i| header + 1 + i)
                    .unwrap_or(lines.len());
                (header + 1, end)
            }
            None => {
                lines.insert(0, "[General]".to_string());
                (1, 1)
            }
        }
    } else {
        (0, lines.len())
    };
    for (key, value) in keys {
        let line = format!("{key}={}", ini_value(value));
        let matches = |l: &String| l.split_once('=').is_some_and(|(k, _)| k.trim() == *key);
        match lines[start..end].iter().position(matches) {
            Some(i) => {
                lines[start + i] = line;
                // Drop any later duplicate of the key in the section.
                let mut j = start + i + 1;
                while j < end {
                    if matches(&lines[j]) {
                        lines.remove(j);
                        end -= 1;
                    } else {
                        j += 1;
                    }
                }
            }
            None => {
                // After the section's last non-empty line.
                let mut at = end;
                while at > start && lines[at - 1].trim().is_empty() {
                    at -= 1;
                }
                lines.insert(at, line);
                end += 1;
            }
        }
    }
    let mut out = lines.join("\n");
    out.push('\n');
    write_atomic(path, out.as_bytes())
}

/// Temp file beside `path`, then rename over it.
pub fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let dir = path.parent().ok_or("A file has no folder.")?;
    let name = path.file_name().unwrap_or_default().to_string_lossy();
    let tmp = dir.join(format!(".{name}.cobblify-{}", unique()));
    fs::write(&tmp, bytes).map_err(|e| format!("Cannot write {}: {e}", tmp.display()))?;
    fs::rename(&tmp, path).map_err(|e| {
        let _ = fs::remove_file(&tmp);
        format!("Cannot write {}: {e}", path.display())
    })
}

/// A short unique token for temp and staging names (no randomness crate needed).
pub fn unique() -> String {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0);
    let n = COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("{:x}{:x}{n:x}", std::process::id(), nanos)
}

/// Renames `from` to `to` only if nothing exists at `to`: never replaces a folder (or
/// anything else) that raced into place. macOS: `renamex_np(RENAME_EXCL)`; Windows:
/// `MoveFileExW` without `MOVEFILE_REPLACE_EXISTING`.
pub fn rename_no_replace(from: &Path, to: &Path) -> io::Result<()> {
    #[cfg(target_os = "macos")]
    {
        use std::ffi::CString;
        use std::os::unix::ffi::OsStrExt;
        extern "C" {
            fn renamex_np(
                from: *const std::ffi::c_char,
                to: *const std::ffi::c_char,
                flags: std::ffi::c_uint,
            ) -> std::ffi::c_int;
        }
        const RENAME_EXCL: std::ffi::c_uint = 0x0000_0004;
        let a = CString::new(from.as_os_str().as_bytes())
            .map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))?;
        let b = CString::new(to.as_os_str().as_bytes())
            .map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))?;
        // SAFETY: two valid NUL-terminated paths; the call reads them only.
        let rc = unsafe { renamex_np(a.as_ptr(), b.as_ptr(), RENAME_EXCL) };
        if rc == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }
    // Windows: MoveFileExW without MOVEFILE_REPLACE_EXISTING never replaces a file or a folder
    // (`fs::rename` would replace a file).
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        let wide = |p: &Path| -> Vec<u16> { p.as_os_str().encode_wide().chain(std::iter::once(0)).collect() };
        let (a, b) = (wide(from), wide(to));
        // SAFETY: two valid NUL-terminated paths; the call reads them only.
        let ok = unsafe { windows_sys::Win32::Storage::FileSystem::MoveFileExW(a.as_ptr(), b.as_ptr(), 0) };
        if ok != 0 {
            return Ok(());
        }
        let err = io::Error::last_os_error();
        if fs::symlink_metadata(to).is_ok() {
            return Err(io::Error::from(io::ErrorKind::AlreadyExists));
        }
        Err(err)
    }
    #[cfg(not(any(target_os = "macos", windows)))]
    {
        if fs::symlink_metadata(to).is_ok() {
            return Err(io::Error::from(io::ErrorKind::AlreadyExists));
        }
        fs::rename(from, to)
    }
}

// ── signed in ───────────────────────────────────────────────────────────────────

pub fn accounts_json(data: &Path) -> PathBuf {
    data.join("accounts.json")
}

/// Missing, unreadable or malformed reads as not signed in.
pub fn signed_in(data: &Path) -> bool {
    match fs::read(accounts_json(data)) {
        Ok(bytes) => signed_in_from(&bytes),
        Err(_) => false,
    }
}

/// formatVersion 3 (any other version Prism itself discards), and an account that is
/// active, not Offline, and owns Minecraft. A valid account that is not the active one
/// makes Prism ask "Which account?" at every launch, so it does not count.
pub(crate) fn signed_in_from(bytes: &[u8]) -> bool {
    match serde_json::from_slice::<AccountsFile>(bytes) {
        Ok(file) => {
            file.format_version == Some(3)
                && file
                    .accounts
                    .iter()
                    .any(|a| a.active && a.kind.0 == AccountKind::Online && a.owns())
        }
        Err(_) => false,
    }
}

/// The only shape `accounts.json` is ever read into.
#[derive(Deserialize)]
struct AccountsFile {
    #[serde(rename = "formatVersion", default)]
    format_version: Option<u64>,
    #[serde(default)]
    accounts: Vec<Account>,
}

#[derive(Deserialize)]
struct Account {
    #[serde(rename = "type", default)]
    kind: TypeOf,
    #[serde(default)]
    active: bool,
    #[serde(default)]
    entitlement: Option<Entitlement>,
}

impl Account {
    fn owns(&self) -> bool {
        self.entitlement.as_ref().is_some_and(|e| e.owns_minecraft)
    }
}

#[derive(Deserialize)]
struct Entitlement {
    #[serde(rename = "ownsMinecraft", default)]
    owns_minecraft: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
enum AccountKind {
    /// No type, or a non-string one.
    #[default]
    Missing,
    Offline,
    Online,
}

/// Whether `type` is "Offline"; the string itself is never kept.
#[derive(Default)]
struct TypeOf(AccountKind);

impl<'de> Deserialize<'de> for TypeOf {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        struct V;
        impl<'de> Visitor<'de> for V {
            type Value = TypeOf;
            fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
                f.write_str("an account type")
            }
            fn visit_str<E: serde::de::Error>(self, v: &str) -> Result<Self::Value, E> {
                Ok(TypeOf(if v.is_empty() {
                    AccountKind::Missing
                } else if v == "Offline" {
                    AccountKind::Offline
                } else {
                    AccountKind::Online
                }))
            }
            fn visit_unit<E: serde::de::Error>(self) -> Result<Self::Value, E> {
                Ok(TypeOf(AccountKind::Missing))
            }
        }
        d.deserialize_any(V)
    }
}

// ── the made instance ───────────────────────────────────────────────────────────

/// `instance.cfg` of the made instance, exactly as research-prism §2 gives it. Prism adds
/// `uuid` itself when it loads the instance.
pub fn made_instance_cfg() -> String {
    [
        "[General]",
        "ConfigVersion=1.3",
        "InstanceType=OneSix",
        &format!("name={}", ini_value(MADE_NAME)),
        "iconKey=default",
        "",
    ]
    .join("\n")
}

/// Prism meta's values for 1.8.9: Forge 11.15.1.2318 (recommended), which requires
/// Minecraft 1.8.9, which suggests LWJGL 2.9.4-nightly-20150209.
pub const LWJGL_VERSION: &str = "2.9.4-nightly-20150209";
pub const MINECRAFT_VERSION: &str = "1.8.9";
pub const FORGE_VERSION: &str = "11.15.1.2318";

/// `mmc-pack.json` of the made instance in the shape Prism saves (`PackProfile.cpp`):
/// keys sorted, 4-space indentation (Qt's indented JSON), dependencies before dependees.
pub fn made_mmc_pack() -> String {
    let pack = serde_json::json!({
        "components": [
            {
                "cachedName": "LWJGL 2",
                "cachedVersion": LWJGL_VERSION,
                "cachedVolatile": true,
                "dependencyOnly": true,
                "uid": "org.lwjgl",
                "version": LWJGL_VERSION
            },
            {
                "cachedName": "Minecraft",
                "cachedRequires": [ { "suggests": LWJGL_VERSION, "uid": "org.lwjgl" } ],
                "cachedVersion": MINECRAFT_VERSION,
                "important": true,
                "uid": "net.minecraft",
                "version": MINECRAFT_VERSION
            },
            {
                "cachedName": "Forge",
                "cachedRequires": [ { "equals": MINECRAFT_VERSION, "uid": "net.minecraft" } ],
                "cachedVersion": FORGE_VERSION,
                "uid": "net.minecraftforge",
                "version": FORGE_VERSION
            }
        ],
        "formatVersion": 1
    });
    let mut out = Vec::new();
    let formatter = serde_json::ser::PrettyFormatter::with_indent(b"    ");
    let mut ser = serde_json::Serializer::with_formatter(&mut out, formatter);
    serde::Serialize::serialize(&sort_keys(pack), &mut ser).expect("serializing a literal");
    let mut text = String::from_utf8(out).expect("serde_json writes UTF-8");
    text.push('\n');
    text
}

/// serde_json keeps insertion order here (`preserve_order`); Prism writes keys sorted.
fn sort_keys(v: serde_json::Value) -> serde_json::Value {
    match v {
        serde_json::Value::Object(map) => {
            let mut entries: Vec<_> = map.into_iter().collect();
            entries.sort_by(|a, b| a.0.cmp(&b.0));
            serde_json::Value::Object(entries.into_iter().map(|(k, v)| (k, sort_keys(v))).collect())
        }
        serde_json::Value::Array(items) => {
            serde_json::Value::Array(items.into_iter().map(sort_keys).collect())
        }
        other => other,
    }
}

/// Makes "Cobblify 1.8.9" in `instances`: both files are written into a staging folder,
/// which is then renamed ONCE into `Cobblify 1.8.9`, `Cobblify 1.8.9(1)`, ... - never over
/// anything that exists. Returns the new instance folder. On failure nothing is left in
/// the instances folder (the staging folder is removed).
pub fn make_instance(instances: &Path) -> Result<PathBuf, String> {
    fs::create_dir_all(instances)
        .map_err(|e| format!("Cannot create {}: {e}", instances.display()))?;
    let staging_root = instances.join(STAGING_DIR);
    let staged = staging_root.join(unique());
    let result = (|| -> Result<PathBuf, String> {
        fs::create_dir_all(&staged)
            .map_err(|e| format!("Cannot create {}: {e}", staged.display()))?;
        fs::write(staged.join(INSTANCE_CFG), made_instance_cfg())
            .map_err(|e| format!("Cannot write the instance: {e}"))?;
        fs::write(staged.join(PACK_FILE), made_mmc_pack())
            .map_err(|e| format!("Cannot write the instance: {e}"))?;
        // Prism's own naming: the name with path separators replaced, then (1), (2), ...
        let base = MADE_NAME.replace(['/', '\\'], "-");
        for n in 0..1000u32 {
            let name = if n == 0 {
                base.clone()
            } else {
                format!("{base}({n})")
            };
            let target = instances.join(&name);
            match rename_no_replace(&staged, &target) {
                Ok(()) => return Ok(target),
                Err(e) if e.kind() == io::ErrorKind::AlreadyExists => continue,
                // Anything else in the way also means "taken": try the next name.
                Err(_) if fs::symlink_metadata(&target).is_ok() => continue,
                Err(e) => return Err(format!("Cannot add the instance to Prism: {e}")),
            }
        }
        Err("Prism already has too many instances with this name.".to_string())
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&staged);
    }
    // Only removed when empty: another run's staging is never touched.
    let _ = fs::remove_dir(&staging_root);
    result
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub(crate) const SIGNED_IN: &str = r#"{"formatVersion":3,"accounts":[
        {"type":"Offline","active":false,"profile":{"id":"x","name":"y"}},
        {"type":"MSA","active":true,"msa":{"token":"SECRET-TOKEN"},"profile":{"id":"a","name":"Player"},
         "entitlement":{"ownsMinecraft":true,"canPlayMinecraft":true}}]}"#;

    pub(crate) fn write_accounts(data: &Path, json: &str) {
        fs::create_dir_all(data).unwrap();
        fs::write(accounts_json(data), json).unwrap();
    }

    #[test]
    fn signed_in_needs_an_active_owning_online_account_in_format_3() {
        assert!(signed_in_from(SIGNED_IN.as_bytes()));
        // Offline only.
        assert!(!signed_in_from(
            br#"{"formatVersion":3,"accounts":[{"type":"Offline","active":true,"entitlement":{"ownsMinecraft":true}}]}"#
        ));
        // No entitlement (never checked) - not counted.
        assert!(!signed_in_from(
            br#"{"formatVersion":3,"accounts":[{"type":"MSA","active":true,"profile":{"id":"a"}}]}"#
        ));
        // Entitlement says no.
        assert!(!signed_in_from(
            br#"{"formatVersion":3,"accounts":[{"type":"MSA","active":true,"entitlement":{"ownsMinecraft":false}}]}"#
        ));
        // No active account.
        assert!(!signed_in_from(
            br#"{"formatVersion":3,"accounts":[{"type":"MSA","entitlement":{"ownsMinecraft":true}}]}"#
        ));
        // The active one is Offline; the owning one is not active.
        assert!(!signed_in_from(
            br#"{"formatVersion":3,"accounts":[{"type":"Offline","active":true},{"type":"MSA","entitlement":{"ownsMinecraft":true}}]}"#
        ));
        // Wrong format version (Prism discards the file).
        assert!(!signed_in_from(
            br#"{"formatVersion":2,"accounts":[{"type":"MSA","active":true,"entitlement":{"ownsMinecraft":true}}]}"#
        ));
        // No type.
        assert!(!signed_in_from(
            br#"{"formatVersion":3,"accounts":[{"active":true,"entitlement":{"ownsMinecraft":true}}]}"#
        ));
        // Malformed, empty, wrong shapes.
        for bad in [&b"{"[..], b"", b"[]", b"null", br#"{"formatVersion":3,"accounts":{}}"#,
                    br#"{"formatVersion":3,"accounts":[{"type":"MSA","active":"yes"}]}"#] {
            assert!(!signed_in_from(bad), "{}", String::from_utf8_lossy(bad));
        }
    }

    #[test]
    fn signed_in_reads_the_data_dir_file() {
        let data = tempfile::tempdir().unwrap();
        assert!(!signed_in(data.path()), "missing file");
        write_accounts(data.path(), SIGNED_IN);
        assert!(signed_in(data.path()));
    }

    #[test]
    fn instance_and_java_dirs_come_from_prismlauncher_cfg() {
        let data = tempfile::tempdir().unwrap();
        assert_eq!(instances_dir(data.path()), data.path().join("instances"));
        assert_eq!(java_dir(data.path()), data.path().join("java"));
        // An absolute path as Prism writes one on this platform (forward slashes on Windows too).
        let abs = if cfg!(windows) { "C:/opt/prism-java" } else { "/opt/prism-java" };
        fs::write(
            data.path().join(GLOBAL_CFG),
            format!("[General]\nConfigVersion=1.3\nInstanceDir=My Instances\nJavaDir={abs}\n"),
        )
        .unwrap();
        assert_eq!(instances_dir(data.path()), data.path().join("My Instances"));
        assert_eq!(java_dir(data.path()), PathBuf::from(abs));
        // The old MultiMC format has no sections.
        fs::write(data.path().join(GLOBAL_CFG), "InstanceDir=\"inst, old\"\n").unwrap();
        assert_eq!(instances_dir(data.path()), data.path().join("inst, old"));
        // Keys in other sections are not General's.
        fs::write(data.path().join(GLOBAL_CFG), "[General]\n[Other]\nInstanceDir=x\n").unwrap();
        assert_eq!(instances_dir(data.path()), data.path().join("instances"));
    }

    #[test]
    fn ini_values_round_trip_through_quoting() {
        for v in ["plain", "/Users/a b/Library/Application Support/x/bin/java", "a,b", "x=y",
                  "q\"uote", "back\\slash", " lead", ""] {
            let line = format!("k={}", ini_value(v));
            assert_eq!(parse_ini(&line).get("k").map(String::as_str), Some(v), "{line}");
        }
        assert_eq!(ini_value("/Users/a/Library/Application Support/j"), "/Users/a/Library/Application Support/j");
    }

    #[test]
    fn set_ini_keys_replaces_or_appends_in_general_and_keeps_the_rest() {
        let dir = tempfile::tempdir().unwrap();
        let cfg = dir.path().join("instance.cfg");
        fs::write(
            &cfg,
            "[General]\nConfigVersion=1.3\nJavaPath=/old\nname=Cobblify 1.8.9\nuuid=abc\n\n[Other]\nJavaPath=keep\n",
        )
        .unwrap();
        set_ini_keys(&cfg, &[("OverrideJavaLocation", "true"), ("JavaPath", "/new java/bin/java")])
            .unwrap();
        assert_eq!(
            fs::read_to_string(&cfg).unwrap(),
            "[General]\nConfigVersion=1.3\nJavaPath=/new java/bin/java\nname=Cobblify 1.8.9\nuuid=abc\nOverrideJavaLocation=true\n\n[Other]\nJavaPath=keep\n"
        );
        let leftovers: Vec<_> = fs::read_dir(dir.path()).unwrap().collect();
        assert_eq!(leftovers.len(), 1, "no temp file left");
    }

    /// Golden files: byte-for-byte the shape Prism 11 saves (research-prism §2).
    #[test]
    fn the_made_instance_files_match_the_golden_files() {
        assert_eq!(made_instance_cfg(), include_str!("golden/prism-instance.cfg"));
        assert_eq!(made_mmc_pack(), include_str!("golden/prism-mmc-pack.json"));
    }

    #[test]
    fn make_instance_renames_once_and_never_overwrites() {
        let instances = tempfile::tempdir().unwrap();
        // Something already named like the instance (a user's own) is never replaced.
        let theirs = instances.path().join(MADE_NAME);
        fs::create_dir_all(&theirs).unwrap();
        fs::write(theirs.join("instance.cfg"), "theirs").unwrap();

        let made = make_instance(instances.path()).unwrap();
        assert_eq!(made, instances.path().join("Cobblify 1.8.9(1)"));
        assert_eq!(fs::read_to_string(theirs.join("instance.cfg")).unwrap(), "theirs");
        assert_eq!(fs::read_to_string(made.join(INSTANCE_CFG)).unwrap(), made_instance_cfg());
        assert_eq!(fs::read_to_string(made.join(PACK_FILE)).unwrap(), made_mmc_pack());
        assert!(!instances.path().join(STAGING_DIR).exists(), "staging removed");

        let again = make_instance(instances.path()).unwrap();
        assert_eq!(again, instances.path().join("Cobblify 1.8.9(2)"));
    }

    // ── Windows discovery (pure; every source injected, so it runs on any platform) ─────

    fn touch(p: &Path) {
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(p, b"").unwrap();
    }

    fn vars(pairs: &[(&str, &Path)]) -> impl Fn(&str) -> Option<OsString> {
        let map: HashMap<String, OsString> =
            pairs.iter().map(|(k, v)| (k.to_string(), v.as_os_str().to_os_string())).collect();
        move |k: &str| map.get(k).cloned()
    }

    #[test]
    fn windows_candidates_come_in_the_planned_order_and_skip_unusable_values() {
        let r = tempfile::tempdir().unwrap();
        let root = r.path();
        let var = vars(&[
            ("LOCALAPPDATA", &root.join("Local")),
            ("ProgramFiles", &root.join("PF")),
            ("ProgramFiles(x86)", &root.join("PF86")),
            ("USERPROFILE", &root.join("home")),
            ("ProgramData", &root.join("PD")),
        ]);
        let got = windows_candidates(&var, Some(root.join("Custom")));
        let want: Vec<PathBuf> = [
            root.join("Custom"),
            root.join("Local/Programs/PrismLauncher"),
            root.join("PF/PrismLauncher"),
            root.join("PF86/PrismLauncher"),
            root.join("home/scoop/apps/prismlauncher/current"),
            root.join("PD/scoop/apps/prismlauncher/current"),
        ]
        .into_iter()
        .map(|d| d.join(WIN_EXE))
        .collect();
        assert_eq!(got, want);

        // SCOOP / SCOOP_GLOBAL win over the defaults; relative and empty values are skipped.
        let var = vars(&[
            ("SCOOP", &root.join("S")),
            ("SCOOP_GLOBAL", &root.join("SG")),
            ("USERPROFILE", &root.join("home")),
            ("LOCALAPPDATA", Path::new("relative")),
            ("ProgramFiles", Path::new("")),
        ]);
        assert_eq!(
            windows_candidates(&var, Some(PathBuf::from("relative/install"))),
            vec![
                root.join("S/apps/prismlauncher/current").join(WIN_EXE),
                root.join("SG/apps/prismlauncher/current").join(WIN_EXE),
            ]
        );
    }

    #[test]
    fn the_first_existing_standard_place_wins_and_nothing_else_is_asked() {
        let r = tempfile::tempdir().unwrap();
        let a = r.path().join("a").join(WIN_EXE);
        let b = r.path().join("b").join(WIN_EXE);
        touch(&b);
        let found = find_exe_among(&[a.clone(), b.clone()], None, None, &|| panic!("no process scan"));
        assert_eq!(found, Some((b.clone(), false)));
        touch(&a);
        assert_eq!(find_exe_among(&[a.clone(), b], None, None, &|| None), Some((a, false)));
    }

    /// Review plan-2 B1: a portable Prism seen running is remembered, and is found again once
    /// it is closed; a remembered exe that is gone is not.
    #[test]
    fn a_running_portable_prism_is_remembered_and_found_again_when_closed() {
        let r = tempfile::tempdir().unwrap();
        let home = r.path().join("home");
        let portable = r.path().join("Games/Prism").join(WIN_EXE);
        touch(&portable);
        let standard = vec![r.path().join("Local/Programs/PrismLauncher").join(WIN_EXE)];

        let running = { let p = portable.clone(); move || Some(p.clone()) };
        let (exe, remember) = find_exe_among(&standard, remembered_exe(&home), None, &running).unwrap();
        assert_eq!((exe.clone(), remember), (portable.clone(), true));
        remember_exe(&home, &exe).unwrap();
        assert_eq!(remembered_exe(&home), Some(portable.clone()));

        // Closed now: the record finds it, and asks nothing else.
        let found = find_exe_among(&standard, remembered_exe(&home), None, &|| panic!("no process scan"));
        assert_eq!(found, Some((portable.clone(), false)));

        // Gone: nothing (the stale record is not trusted).
        fs::remove_file(&portable).unwrap();
        assert_eq!(find_exe_among(&standard, remembered_exe(&home), None, &|| None), None);
        // A malformed record reads as none.
        fs::write(home.join(REMEMBERED), b"{").unwrap();
        assert_eq!(remembered_exe(&home), None);
    }

    /// Both portable layouts own their instances: the exe beside `portable.txt` with the data
    /// in its folder, and the exe above a `UserData` data folder. An installed Prism (data in
    /// %APPDATA%) owns nothing this way.
    #[test]
    fn a_saved_instance_leads_back_to_its_portable_prism_in_both_layouts() {
        let r = tempfile::tempdir().unwrap();
        let flat = r.path().join("Flat");
        touch(&flat.join(WIN_EXE));
        touch(&flat.join("portable.txt"));
        assert_eq!(owner_of_instance(&flat.join("instances/Cobblify 1.8.9")), Some(flat.join(WIN_EXE)));
        fs::remove_file(flat.join("portable.txt")).unwrap();
        assert_eq!(owner_of_instance(&flat.join("instances/Cobblify 1.8.9")), None, "no portable.txt");

        let nested = r.path().join("Nested");
        touch(&nested.join(WIN_EXE));
        let inst = nested.join("UserData/instances/Cobblify 1.8.9");
        fs::create_dir_all(&inst).unwrap();
        assert_eq!(owner_of_instance(&inst), Some(nested.join(WIN_EXE)));

        let installed = r.path().join("Roaming/PrismLauncher/instances/X");
        fs::create_dir_all(&installed).unwrap();
        assert_eq!(owner_of_instance(&installed), None);

        // Found through the owner, with the standard places empty, and remembered.
        let found = find_exe_among(&[], None, owner_of_instance(&inst), &|| None);
        assert_eq!(found, Some((nested.join(WIN_EXE), true)));
    }

    /// Prism's `Application.cpp` rule, in its order.
    #[test]
    fn the_windows_data_folder_follows_prisms_own_rule() {
        let r = tempfile::tempdir().unwrap();
        let appdata = r.path().join("Roaming");
        let exe_dir = r.path().join("Prism");
        let exe = exe_dir.join(WIN_EXE);
        touch(&exe);
        let installed = windows_data_dir(Some(&exe), Some(&appdata), None);
        assert_eq!(installed, Some(appdata.join("PrismLauncher")));
        assert_eq!(windows_data_dir(None, Some(&appdata), None), Some(appdata.join("PrismLauncher")));
        assert_eq!(windows_data_dir(None, None, None), None);

        touch(&exe_dir.join("portable.txt"));
        assert_eq!(windows_data_dir(Some(&exe), Some(&appdata), None), Some(exe_dir.clone()));
        fs::create_dir_all(exe_dir.join("UserData")).unwrap();
        assert_eq!(
            windows_data_dir(Some(&exe), Some(&appdata), None),
            Some(exe_dir.join("UserData")),
            "UserData wins over portable.txt"
        );
        let env_dir = r.path().join("Elsewhere");
        assert_eq!(
            windows_data_dir(Some(&exe), Some(&appdata), Some(env_dir.clone().into_os_string())),
            Some(env_dir),
            "PRISMLAUNCHER_DATA_DIR wins over everything"
        );
        assert_eq!(
            windows_data_dir(None, Some(&appdata), Some(OsString::new())),
            Some(appdata.join("PrismLauncher")),
            "an empty value is unset"
        );
    }

    #[test]
    fn rename_no_replace_refuses_an_existing_file_target() {
        let dir = tempfile::tempdir().unwrap();
        let a = dir.path().join("a.jar");
        let b = dir.path().join("b.jar");
        fs::write(&a, b"new").unwrap();
        fs::write(&b, b"theirs").unwrap();
        let err = rename_no_replace(&a, &b).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::AlreadyExists);
        assert_eq!(fs::read(&b).unwrap(), b"theirs", "never replaced");
        assert_eq!(fs::read(&a).unwrap(), b"new");
        fs::remove_file(&b).unwrap();
        rename_no_replace(&a, &b).unwrap();
        assert_eq!(fs::read(&b).unwrap(), b"new");
    }

    #[test]
    fn rename_no_replace_refuses_an_existing_target() {
        let dir = tempfile::tempdir().unwrap();
        let a = dir.path().join("a");
        let b = dir.path().join("b");
        fs::create_dir_all(&a).unwrap();
        fs::create_dir_all(&b).unwrap(); // empty: plain rename(2) would replace it
        let err = rename_no_replace(&a, &b).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::AlreadyExists);
        assert!(a.exists());
        fs::remove_dir(&b).unwrap();
        rename_no_replace(&a, &b).unwrap();
        assert!(b.exists() && !a.exists());
    }

    #[test]
    fn the_app_is_found_in_either_applications_folder() {
        let root = tempfile::tempdir().unwrap();
        let system = root.path().join("sys").join(BUNDLE_NAME);
        let user = root.path().join("user").join(BUNDLE_NAME);
        let candidates = vec![system.clone(), user.clone()];
        assert_eq!(find_app_in(&candidates), None);
        fs::create_dir_all(user.join("Contents/MacOS")).unwrap();
        fs::write(user.join(BUNDLE_EXE), b"").unwrap();
        assert_eq!(find_app_in(&candidates), Some(user));
        fs::create_dir_all(system.join("Contents/MacOS")).unwrap();
        fs::write(system.join(BUNDLE_EXE), b"").unwrap();
        assert_eq!(find_app_in(&candidates), Some(system));
    }

    fn make_bundle(app: &Path) {
        fs::create_dir_all(app.join("Contents/MacOS")).unwrap();
        fs::write(app.join(BUNDLE_EXE), b"").unwrap();
    }

    #[test]
    fn a_bundle_outside_the_applications_folders_is_found_by_its_bundle_id() {
        let root = tempfile::tempdir().unwrap();
        let home = root.path().join("home");
        let standard = vec![root.path().join("sys").join(BUNDLE_NAME), home.join("Applications").join(BUNDLE_NAME)];
        let downloads = home.join("Downloads").join(BUNDLE_NAME);
        let trashed = home.join(".Trash").join(BUNDLE_NAME);
        let empty = home.join("Games").join(BUNDLE_NAME);
        make_bundle(&downloads);
        make_bundle(&trashed);
        fs::create_dir_all(&empty).unwrap();
        let lookup = || vec![trashed.clone(), empty.clone(), downloads.clone()];
        assert_eq!(find_app_among(&standard, &lookup), Some(downloads.clone()));
        assert_eq!(find_app_among(&standard, &Vec::new), None);

        make_bundle(&standard[1]);
        let found = find_app_among(&standard, &|| panic!("no lookup when a standard folder has Prism"));
        assert_eq!(found, Some(standard[1].clone()));
    }
}
