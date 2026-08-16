//! Forge support: finding a Minecraft instance, judging whether Cobblify belongs in it,
//! and installing the jar there without ever costing the user a file.
//!
//! The design rests on one fact: the Forge build of Cobblify is a PURE DROP-IN. Mixin is
//! shaded into the jar and its manifest carries `TweakClass` + `ForceLoadAsMod`, which FML
//! auto-registers, so there is no JVM argument, no coremod, no bootstrap and no config to
//! edit. "Support every launcher" therefore collapses to "find the right `mods` folder",
//! which is why this module detects rather than integrates.
//!
//! Three rules earn their complexity, and each one exists because getting it wrong breaks
//! a real client:
//!
//!   * Forge raises `DuplicateModsFoundException` and refuses to boot when two jars declare
//!     mod id `bedwarsqol`. So a stale Cobblify jar is fatal, not untidy - and it must be
//!     hunted in BOTH `mods/` and `mods/1.8.9/`, because FML loads the version-specific
//!     directory too.
//!   * Forge does not care how a filename is cased. Classification is therefore caseless on
//!     every platform - an exact match would miss `cobblify-1.8.9-forge-0.8.0.jar` on APFS
//!     and hand the user a client that will not start.
//!   * The one file exempt from that hunt is our own destination, identified as a
//!     DIRECTORY ENTRY - same folder, same name under the platform's own casing - and not
//!     by resolved target. Comparing canonical targets would exempt a symlink pointing at
//!     the destination, and Forge builds a mod candidate per directory entry, so that
//!     alias would still load as a second jar with the same mod id. Symlinks are reported
//!     and never moved: ambiguity resolves toward telling the user, never toward leaving a
//!     jar that might crash their game, and never toward touching a file that may live
//!     somewhere else entirely.
//!
//! Nothing here is ever installed speculatively. Detection returns candidates; a user
//! choice installs.

use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::install::{commit, stage_verified};
use crate::resources::{sha256_file, ForgeJar};

/// Renaming a jar to this suffix is enough to make Forge ignore it: FML only considers
/// directory entries matching `(.+).(zip|jar)$`. Same directory, so no cross-volume move,
/// and the user recovers the file by renaming it back.
const DISABLED_SUFFIX: &str = ".cobblify-disabled";

/// FML 1.8.9 discovers mods in `<gameDir>/mods` AND `<gameDir>/mods/<mcversion>`.
const VERSIONED_MODS_DIR: &str = "1.8.9";

/// Where the launcher's own memory of the chosen instance lives. Deliberately a separate
/// file from the mod's `~/.cobblify/cobblify.json`.
const TARGETS_FILE: &str = "launcher-targets.json";

// ── compatibility ───────────────────────────────────────────────────────────────

/// Three states, and only two of them are offerable. `Incompatible` means the launcher's
/// OWN metadata positively says this is not a Minecraft 1.8.9 Forge instance; offering it
/// anyway would let a click crash the client, so it is refused even if the id is passed
/// directly to a command.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Compat {
    Confirmed,
    /// No readable version metadata at all - a hand-picked folder, essentially.
    Unknown,
    Incompatible(String),
}

impl Compat {
    fn tag(&self) -> &'static str {
        match self {
            Compat::Confirmed => "confirmed",
            Compat::Unknown => "unknown",
            Compat::Incompatible(_) => "incompatible",
        }
    }
    fn reason(&self) -> String {
        match self {
            Compat::Incompatible(why) => why.clone(),
            _ => String::new(),
        }
    }
    pub fn is_installable(&self) -> bool {
        !matches!(self, Compat::Incompatible(_))
    }
}

/// One offerable (or refusable) Minecraft instance. `id` is opaque and backend-owned; the
/// frontend never sends us a filesystem path.
#[derive(Debug, Clone)]
pub struct Candidate {
    pub id: String,
    pub launcher: String,
    pub name: String,
    pub game_dir: PathBuf,
    pub compat: Compat,
    /// The marker file that proved this is an instance, if detection found one.
    pub marker: Option<String>,
}

/// The wire shape the UI renders.
#[derive(Debug, Clone, Serialize)]
pub struct CandidateView {
    pub id: String,
    pub launcher: String,
    pub name: String,
    pub path: String,
    pub compat: &'static str,
    pub reason: String,
}

impl Candidate {
    pub fn view(&self) -> CandidateView {
        CandidateView {
            id: self.id.clone(),
            launcher: self.launcher.clone(),
            name: self.name.clone(),
            path: self.game_dir.display().to_string(),
            compat: self.compat.tag(),
            reason: self.compat.reason(),
        }
    }
    /// Why this instance was refused, for a message the user can act on.
    pub fn compat_reason(&self) -> String {
        self.compat.reason()
    }
}

// ── detection ───────────────────────────────────────────────────────────────────

/// The environment detection reads, injected so the whole matrix is unit-testable on any
/// platform. `%APPDATA%` matters: `home()` is `%USERPROFILE%` on Windows while `.minecraft`
/// and Prism's data both live under `%APPDATA%`, so building those paths off the home
/// directory would silently find nothing.
#[derive(Debug, Clone)]
pub struct Env {
    pub home: PathBuf,
    pub appdata: Option<PathBuf>,
}

impl Env {
    pub fn from_process() -> Option<Env> {
        #[cfg(windows)]
        {
            let home = std::env::var_os("USERPROFILE")?;
            Some(Env {
                home: PathBuf::from(home),
                appdata: std::env::var_os("APPDATA").map(PathBuf::from),
            })
        }
        #[cfg(not(windows))]
        {
            let home = std::env::var_os("HOME")?;
            Some(Env {
                home: PathBuf::from(home),
                appdata: None,
            })
        }
    }

    /// macOS's per-application data root; on Windows this is `%APPDATA%`.
    fn app_support(&self) -> Option<PathBuf> {
        #[cfg(windows)]
        {
            self.appdata.clone()
        }
        #[cfg(not(windows))]
        {
            Some(self.home.join("Library/Application Support"))
        }
    }
}

fn read_json(path: &Path) -> Option<Value> {
    serde_json::from_slice(&fs::read(path).ok()?).ok()
}

/// Best-effort listing, for DETECTION only: an unreadable launcher directory
/// simply contributes no candidates, which is the right outcome when we are
/// merely offering choices.
fn dir_entries(dir: &Path) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .collect();
    out.sort();
    out
}

/// Strict listing, for the INSTALL transaction. Failing open here would be a bug
/// with teeth: an unreadable `mods/` would look empty, a stale Cobblify jar would
/// go unseen, and we would commit a second jar with the same mod id - which is
/// exactly the state that stops Forge booting. Both the directory open and each
/// individual entry must succeed or the whole install refuses.
fn read_dir_strict(dir: &Path) -> Result<Vec<PathBuf>, String> {
    let mut out = Vec::new();
    for entry in
        fs::read_dir(dir).map_err(|e| format!("Cannot read {}: {e}", dir.display()))?
    {
        let entry =
            entry.map_err(|e| format!("Cannot read an entry in {}: {e}", dir.display()))?;
        out.push(entry.path());
    }
    out.sort();
    Ok(out)
}

fn contains_caseless(haystack: &str, needle: &str) -> bool {
    haystack.to_ascii_lowercase().contains(&needle.to_ascii_lowercase())
}

/// Detection reads DEFAULT locations only. A relocated CurseForge folder, portable or
/// `--dir` Prism, MultiMC, or anything hand-rolled goes through the folder picker instead -
/// which is not a lesser route, it is the launcher-agnostic one. Parsing each launcher's
/// own configuration to chase a moved root was tried in planning and dropped: Prism's
/// instance directory setting is *relative*, so resolving it against the wrong base scans
/// the wrong tree, and the picker already covers every such case with an absolute path the
/// user chose.
pub fn detect(env: &Env) -> Vec<Candidate> {
    // Prism-only: it is the one supported launcher with an instance-id CLI, so every
    // target offered here can provide the same install-and-launch experience as Lunar.
    let mut out = detect_prism(env);

    // Confirmed first, then unknown, then the refused ones - the UI shows this order.
    out.sort_by_key(|c| match c.compat {
        Compat::Confirmed => 0,
        Compat::Unknown => 1,
        Compat::Incompatible(_) => 2,
    });
    for (i, c) in out.iter_mut().enumerate() {
        c.id = format!("d{i}");
    }
    out
}

fn detect_vanilla(env: &Env) -> Option<Candidate> {
    let root = if cfg!(windows) {
        env.appdata.as_ref()?.join(".minecraft")
    } else {
        env.app_support()?.join("minecraft")
    };
    if !root.join("launcher_profiles.json").is_file() {
        return None;
    }
    // Readable and positively lacking Forge 1.8.9 is a REFUSAL, not an "unknown":
    // installing there would drop a 1.8.9 Forge mod into a folder whose profiles are
    // something else entirely.
    let compat = judge_vanilla(&root);
    Some(Candidate {
        id: String::new(),
        launcher: "Minecraft Launcher".to_string(),
        name: ".minecraft".to_string(),
        game_dir: root,
        compat,
        marker: Some("launcher_profiles.json".to_string()),
    })
}

fn detect_curseforge(env: &Env) -> Vec<Candidate> {
    let root = if cfg!(windows) {
        env.home.join("curseforge/minecraft/Instances")
    } else {
        env.home.join("Documents/curseforge/minecraft/Instances")
    };
    dir_entries(&root)
        .into_iter()
        .filter_map(|dir| {
            let marker = dir.join("minecraftinstance.json");
            if !marker.is_file() {
                return None;
            }
            // Only two fields are read; the file also carries account data, which is
            // never touched, logged or surfaced.
            let compat = match read_json(&marker) {
                Some(v) => judge_curseforge(&v),
                None => Compat::Unknown,
            };
            Some(Candidate {
                id: String::new(),
                launcher: "CurseForge".to_string(),
                name: dir_name(&dir),
                game_dir: dir,
                compat,
                marker: Some("minecraftinstance.json".to_string()),
            })
        })
        .collect()
}

fn detect_prism(env: &Env) -> Vec<Candidate> {
    let Some(root) = env.app_support().map(|p| p.join("PrismLauncher/instances")) else {
        return Vec::new();
    };
    dir_entries(&root)
        .into_iter()
        .filter_map(|dir| {
            let marker = dir.join("mmc-pack.json");
            if !marker.is_file() {
                return None;
            }
            let compat = match read_json(&marker) {
                Some(v) => judge_mmc(&v),
                None => Compat::Unknown,
            };
            Some(Candidate {
                id: String::new(),
                launcher: "Prism Launcher".to_string(),
                name: dir_name(&dir),
                game_dir: prism_game_dir(&dir),
                compat,
                marker: Some("mmc-pack.json".to_string()),
            })
        })
        .collect()
}

/// Prism keeps the game files in a subdirectory of the instance, not the instance root
/// itself - `.minecraft` on most layouts, `minecraft` on others. Neither existing yet is
/// normal for a freshly created instance, in which case `.minecraft` is the one Prism will
/// make.
fn prism_game_dir(instance: &Path) -> PathBuf {
    for name in [".minecraft", "minecraft"] {
        let candidate = instance.join(name);
        if candidate.is_dir() {
            return candidate;
        }
    }
    instance.join(".minecraft")
}

#[allow(dead_code)]
fn detect_modrinth(env: &Env) -> Vec<Candidate> {
    let Some(root) = env
        .app_support()
        .map(|p| p.join("com.modrinth.theseus/profiles"))
    else {
        return Vec::new();
    };
    dir_entries(&root)
        .into_iter()
        .filter_map(|dir| {
            let marker = dir.join("profile.json");
            if !marker.is_file() {
                return None;
            }
            let compat = match read_json(&marker) {
                Some(v) => judge_modrinth(&v),
                None => Compat::Unknown,
            };
            Some(Candidate {
                id: String::new(),
                launcher: "Modrinth App".to_string(),
                name: dir_name(&dir),
                game_dir: dir,
                compat,
                marker: Some("profile.json".to_string()),
            })
        })
        .collect()
}

fn dir_name(p: &Path) -> String {
    p.file_name()
        .unwrap_or_default()
        .to_string_lossy()
        .into_owned()
}

/// The shared version/loader verdict for launchers that record both fields.
///
/// `Unknown` means we read NOTHING - only then is the user allowed to vouch for a folder.
/// If the file identified the instance at all, one-sided evidence is still evidence: a
/// CurseForge or Modrinth record naming Minecraft 1.8.9 with no Forge loader is a vanilla
/// instance, and letting a user confirm past that would drop a Forge mod somewhere it can
/// only crash.
fn judge(version: &str, loader: &str) -> Compat {
    if version.is_empty() && loader.is_empty() {
        return Compat::Unknown;
    }
    if !version.is_empty() && version != "1.8.9" {
        return Compat::Incompatible(format!("This instance is Minecraft {version}, not 1.8.9."));
    }
    if loader.is_empty() {
        return Compat::Incompatible(
            "This instance has no mod loader installed - Cobblify needs Forge.".to_string(),
        );
    }
    if !contains_caseless(loader, "forge") {
        return Compat::Incompatible(format!("This instance uses {loader}, not Forge."));
    }
    if version.is_empty() {
        // Forge, but the file never said which Minecraft version.
        return Compat::Unknown;
    }
    Compat::Confirmed
}

/// Prism/MultiMC record the version as a component list rather than two fields.
fn judge_mmc(v: &Value) -> Compat {
    let Some(components) = v.get("components").and_then(Value::as_array) else {
        return Compat::Unknown;
    };
    let mut mc_version = "";
    let mut has_forge = false;
    for c in components {
        let uid = c.get("uid").and_then(Value::as_str).unwrap_or("");
        let version = c.get("version").and_then(Value::as_str).unwrap_or("");
        match uid {
            "net.minecraft" => mc_version = version,
            "net.minecraftforge" => has_forge = true,
            _ => {}
        }
    }
    if mc_version.is_empty() {
        return Compat::Unknown;
    }
    if mc_version != "1.8.9" {
        return Compat::Incompatible(format!(
            "This instance is Minecraft {mc_version}, not 1.8.9."
        ));
    }
    if !has_forge {
        return Compat::Incompatible("This instance does not have Forge installed.".to_string());
    }
    Compat::Confirmed
}

/// Classifies a directory the user picked by hand. The marker files are the same ones
/// detection uses, so a picked Prism or CurseForge instance is judged exactly as a detected
/// one would be; a folder with no marker at all is `Unknown` and needs explicit
/// confirmation before anything is written.
pub fn classify_picked(dir: &Path) -> Result<Candidate, String> {
    if !dir.is_dir() {
        return Err("That is not a folder.".to_string());
    }
    // Tolerate the user picking the `mods` folder itself, which is the obvious mistake.
    let game_dir = if dir_name(dir).eq_ignore_ascii_case("mods") {
        dir.parent().unwrap_or(dir).to_path_buf()
    } else {
        dir.to_path_buf()
    };

    if game_dir.join("minecraftinstance.json").is_file() {
        let marker = game_dir.join("minecraftinstance.json");
        let compat = read_json(&marker)
            .map(|v| judge_curseforge(&v))
            .unwrap_or(Compat::Unknown);
        return Ok(picked(game_dir, compat, Some("minecraftinstance.json")));
    }
    if game_dir.join("mmc-pack.json").is_file() {
        let compat = read_json(&game_dir.join("mmc-pack.json"))
            .map(|v| judge_mmc(&v))
            .unwrap_or(Compat::Unknown);
        // The user picked the instance root; the game files are a level down.
        return Ok(picked(prism_game_dir(&game_dir), compat, Some("mmc-pack.json")));
    }
    // Prism/MultiMC again, from the OTHER direction: the stored target for a Prism
    // instance is its `.minecraft`/`minecraft` game directory, and the marker lives one
    // level up. Without this branch, re-checking a remembered Prism target found no
    // metadata, fell through to `Unknown`, and would have installed into an instance
    // whose own `mmc-pack.json` now said it was something else entirely.
    if let Some(parent) = game_dir.parent() {
        if parent.join("mmc-pack.json").is_file() {
            let compat = read_json(&parent.join("mmc-pack.json"))
                .map(|v| judge_mmc(&v))
                .unwrap_or(Compat::Unknown);
            return Ok(picked(game_dir, compat, Some("mmc-pack.json")));
        }
    }
    if game_dir.join("profile.json").is_file() {
        let compat = read_json(&game_dir.join("profile.json"))
            .map(|v| judge_modrinth(&v))
            .unwrap_or(Compat::Unknown);
        return Ok(picked(game_dir, compat, Some("profile.json")));
    }
    if game_dir.join("launcher_profiles.json").is_file() {
        return Ok(picked(
            game_dir.clone(),
            judge_vanilla(&game_dir),
            Some("launcher_profiles.json"),
        ));
    }
    Ok(picked(game_dir, Compat::Unknown, None))
}

fn judge_curseforge(v: &Value) -> Compat {
    judge(
        v.get("gameVersion").and_then(Value::as_str).unwrap_or(""),
        v.get("baseModLoader")
            .and_then(|m| m.get("name"))
            .and_then(Value::as_str)
            .unwrap_or(""),
    )
}

fn judge_modrinth(v: &Value) -> Compat {
    judge(
        v.get("game_version").and_then(Value::as_str).unwrap_or(""),
        v.get("loader").and_then(Value::as_str).unwrap_or(""),
    )
}

/// The vanilla `mods/` folder is shared by every profile, so the only evidence that Forge
/// 1.8.9 is present is an installed version of that name.
fn judge_vanilla(root: &Path) -> Compat {
    let versions = root.join("versions");
    if !versions.is_dir() {
        return Compat::Unknown;
    }
    let has = dir_entries(&versions).iter().any(|p| {
        let n = dir_name(p);
        contains_caseless(&n, "1.8.9") && contains_caseless(&n, "forge")
    });
    if has {
        Compat::Confirmed
    } else {
        Compat::Incompatible("Forge 1.8.9 is not installed in this Minecraft folder.".to_string())
    }
}

fn picked(game_dir: PathBuf, compat: Compat, marker: Option<&str>) -> Candidate {
    Candidate {
        id: String::new(),
        launcher: "Chosen folder".to_string(),
        name: dir_name(&game_dir),
        game_dir,
        compat,
        marker: marker.map(str::to_string),
    }
}

// ── stale jars ──────────────────────────────────────────────────────────────────

/// What to do with one Cobblify-looking jar found in a load root.
#[derive(Debug, PartialEq, Eq)]
enum Verdict {
    /// A superseded copy of one of our own packaged releases: safe to set aside.
    Quarantine,
    /// Cobblify-ish but not a release we produced - a `-dev` build, a hand-renamed file,
    /// or a path we could not resolve. Reported, never touched.
    Block,
    Ignore,
}

/// Our release names are `<prefix><major>.<minor>.<patch>.jar`. The prefix is derived from
/// the jar we are installing rather than hard-coded, so it cannot drift from packaging.
fn release_prefix(our_name: &str) -> String {
    match our_name.rfind('-') {
        Some(i) => our_name[..=i].to_string(),
        None => our_name.to_string(),
    }
}

fn is_semver(s: &str) -> bool {
    let parts: Vec<&str> = s.split('.').collect();
    parts.len() == 3
        && parts
            .iter()
            .all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit()))
}

/// Classification is CASELESS on every platform. Forge loads a jar however its name is
/// cased, so an exact comparison would miss a lower-cased stale jar on a case-sensitive
/// volume and hand the user a client that refuses to boot.
fn classify(name: &str, our_name: &str) -> Verdict {
    let lower = name.to_ascii_lowercase();
    if !lower.ends_with(".jar") || !lower.starts_with("cobblify") {
        return Verdict::Ignore;
    }
    let prefix = release_prefix(our_name).to_ascii_lowercase();
    if let Some(rest) = lower.strip_prefix(&prefix) {
        let version = &rest[..rest.len() - 4];
        if is_semver(version) {
            return Verdict::Quarantine;
        }
    }
    Verdict::Block
}

/// Every directory FML will scan for mods.
fn load_roots(mods_dir: &Path) -> Vec<PathBuf> {
    vec![mods_dir.to_path_buf(), mods_dir.join(VERSIONED_MODS_DIR)]
}

/// Moves a file, failing rather than replacing anything already at the destination.
///
/// This has to be ONE atomic operation. An earlier version reserved the name with
/// `create_new` and then renamed over its own placeholder, which looks safe but is not:
/// between the two steps another process can replace that path, and the rename would then
/// destroy a file we never owned - the exact data loss the quarantine exists to prevent.
///
/// Windows: `MoveFileExW` with no `MOVEFILE_REPLACE_EXISTING` fails if the destination
/// exists. Unix: `link()` is defined to fail with `EEXIST` rather than clobber, so
/// hard-link-then-unlink is the atomic no-replace move (same directory, so always the same
/// filesystem). Both surface `AlreadyExists`, which the caller uses to pick another name.
#[cfg(windows)]
fn move_no_replace(src: &Path, dst: &Path) -> std::io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::MoveFileExW;

    fn wide(p: &Path) -> Vec<u16> {
        p.as_os_str()
            .encode_wide()
            .chain(std::iter::once(0))
            .collect()
    }
    let (s, d) = (wide(src), wide(dst));
    // Flags 0: no MOVEFILE_REPLACE_EXISTING, so an existing destination is an error.
    if unsafe { MoveFileExW(s.as_ptr(), d.as_ptr(), 0) } == 0 {
        return Err(std::io::Error::last_os_error());
    }
    Ok(())
}

#[cfg(not(windows))]
fn move_no_replace(src: &Path, dst: &Path) -> std::io::Result<()> {
    fs::hard_link(src, dst)?;
    fs::remove_file(src)
}

/// Sets a jar aside without ever destroying it, and without ever destroying anything else
/// either. Nothing is deleted; the user recovers the file by renaming it back.
fn quarantine(path: &Path) -> Result<PathBuf, String> {
    let base = path.as_os_str().to_string_lossy().into_owned();
    for n in 1..100 {
        let candidate = PathBuf::from(if n == 1 {
            format!("{base}{DISABLED_SUFFIX}")
        } else {
            format!("{base}{DISABLED_SUFFIX} ({n})")
        });
        match move_no_replace(path, &candidate) {
            Ok(()) => return Ok(candidate),
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(format!("Cannot move {} aside: {e}", path.display())),
        }
    }
    Err(format!(
        "Too many set-aside copies already exist next to {}.",
        path.display()
    ))
}

/// Whether a directory entry IS our destination - the one file exempt from the hunt.
///
/// This is a directory-entry question, not a canonical-target one. Comparing resolved
/// targets would exempt a SYMLINK that points at the destination, and Forge builds a mod
/// candidate per directory entry, so that alias would still load as a second jar with the
/// same mod id. Symlinks are handled by the caller (always reported, never quarantined);
/// here only the entry's own location and name matter, cased the way the platform's own
/// filesystem cases them.
fn is_destination_entry(path: &Path, dest: &Path) -> bool {
    if path.parent() != dest.parent() {
        return false;
    }
    match (path.file_name(), dest.file_name()) {
        (Some(a), Some(b)) => {
            #[cfg(windows)]
            {
                a.to_string_lossy().eq_ignore_ascii_case(&b.to_string_lossy())
            }
            #[cfg(not(windows))]
            {
                a == b
            }
        }
        _ => false,
    }
}

/// The result of setting up one Forge instance.
#[derive(Debug, Default)]
pub struct Outcome {
    pub path: Option<String>,
    /// Jars we refused to touch. Their presence blocks, because Forge will not boot.
    pub conflicts: Vec<String>,
    /// `"<old path> -> <new name>"`, rendered so a set-aside file is never silent.
    pub quarantined: Vec<String>,
    /// True when we deliberately installed nothing.
    pub blocked: bool,
}

/// A failure that may have already moved files. The moves travel with the error so the UI
/// can still tell the user exactly what was set aside and how to put it back - a bare
/// string would strand them with files renamed and nothing naming them.
#[derive(Debug)]
pub struct ForgeError {
    pub message: String,
    pub quarantined: Vec<String>,
}

impl From<String> for ForgeError {
    fn from(message: String) -> Self {
        ForgeError {
            message,
            quarantined: Vec::new(),
        }
    }
}

/// Installs the Forge jar into `game_dir`, setting aside anything that would make Forge
/// refuse to boot.
///
/// Order is the whole safety argument (PLAN 2.6):
///   1. stage and hash the new jar - a failure here moves nothing;
///   2. set aside a FOREIGN jar occupying our own destination name, so it is preserved
///      rather than overwritten;
///   3. always scan both load roots - including when step 1 found our jar already current,
///      which is the likeliest real upgrade and the case an early return would break;
///   4. commit.
pub fn install(jar: &ForgeJar, game_dir: &Path) -> Result<Outcome, ForgeError> {
    let mods_dir = game_dir.join("mods");
    fs::create_dir_all(&mods_dir)
        .map_err(|e| ForgeError::from(format!("Cannot create {}: {e}", mods_dir.display())))?;
    let dest = mods_dir.join(&jar.name);

    // 1. DISCOVERY FIRST, and it must complete. Nothing is moved and nothing is staged
    //    until we know everything that is in both load roots, because the decision in
    //    step 2 depends on having seen all of it.
    let mut stale = Vec::new();
    let mut conflicts = Vec::new();
    for root in load_roots(&mods_dir) {
        if !root.is_dir() {
            continue;
        }
        for path in read_dir_strict(&root)? {
            let meta = fs::symlink_metadata(&path)
                .map_err(|e| ForgeError::from(format!("Cannot read {}: {e}", path.display())))?;
            if meta.is_dir() {
                continue;
            }
            let name = dir_name(&path);
            if classify(&name, &jar.name) == Verdict::Ignore {
                continue;
            }
            // A symlink is reported, never moved: it may alias a file far outside this
            // folder, and Forge counts it as its own jar entry regardless of where it
            // points. Reporting is the honest answer for something we cannot reason about.
            if meta.file_type().is_symlink() {
                conflicts.push(path.display().to_string());
                continue;
            }
            if is_destination_entry(&path, &dest) {
                continue;
            }
            match classify(&name, &jar.name) {
                Verdict::Quarantine => stale.push(path),
                Verdict::Block => conflicts.push(path.display().to_string()),
                Verdict::Ignore => unreachable!(),
            }
        }
    }

    // 2. If ANYTHING ambiguous is present, install nothing. Adding our jar beside a
    //    Cobblify jar we are not allowed to touch would itself create the two-mod-id
    //    state that stops Forge booting - reporting it while causing it is no good.
    if !conflicts.is_empty() {
        conflicts.sort();
        return Ok(Outcome {
            path: None,
            conflicts,
            quarantined: Vec::new(),
            blocked: true,
        });
    }

    // 3. Stage. `None` means our jar is already byte-identical (Windows) - no commit
    //    needed, which is NOT the same as nothing left to do; the scan above ran anyway.
    let staged = stage_verified(&jar.src, &dest, &jar.sha256)?;

    // Past this point files start moving, so every failure carries the moves with it.
    let mut quarantined: Vec<String> = Vec::new();
    let fail = |message: String, moved: &[String]| ForgeError {
        message,
        quarantined: moved.to_vec(),
    };

    // 4. A jar at our own name whose bytes are not ours belongs to the user - a renamed
    //    local build, or a privately shared artifact. Preserve it.
    if dest.is_file() {
        let ours = sha256_file(&dest)
            .map(|h| h.eq_ignore_ascii_case(&jar.sha256))
            .unwrap_or(false);
        if !ours {
            let moved = quarantine(&dest).map_err(|m| fail(m, &quarantined))?;
            quarantined.push(format!("{} -> {}", dest.display(), dir_name(&moved)));
        }
    }

    // 5. Superseded releases, in both load roots.
    for path in stale {
        let moved = quarantine(&path).map_err(|m| fail(m, &quarantined))?;
        quarantined.push(format!("{} -> {}", path.display(), dir_name(&moved)));
    }

    // 6. Commit.
    if let Some(staged) = staged {
        commit(staged).map_err(|m| fail(m, &quarantined))?;
    }
    quarantined.sort();
    Ok(Outcome {
        path: Some(dest.display().to_string()),
        conflicts: Vec::new(),
        quarantined,
        blocked: false,
    })
}

// ── remembering the choice ──────────────────────────────────────────────────────

/// How the target was validated when it was chosen. A marker-backed target can be
/// re-checked exactly; a user-confirmed folder never had a marker, so re-checking it the
/// same way would reject it on every launch.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ValidatedBy {
    Marker,
    User,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SavedTarget {
    pub game_dir: String,
    pub validated_by: ValidatedBy,
    #[serde(default)]
    pub marker: Option<String>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct SavedTargets {
    #[serde(default)]
    forge: Option<SavedTarget>,
}

fn targets_path(home: &Path) -> PathBuf {
    home.join(".cobblify").join(TARGETS_FILE)
}

pub fn load_target(home: &Path) -> Option<SavedTarget> {
    let raw = fs::read(targets_path(home)).ok()?;
    serde_json::from_slice::<SavedTargets>(&raw).ok()?.forge
}

/// Temp file then rename, so an interrupted write leaves the previous record intact.
pub fn save_target(home: &Path, target: &SavedTarget) -> Result<(), String> {
    let path = targets_path(home);
    let dir = path.parent().unwrap_or(home);
    fs::create_dir_all(dir).map_err(|e| format!("Cannot create {}: {e}", dir.display()))?;
    let saved = SavedTargets {
        forge: Some(target.clone()),
    };
    let json = serde_json::to_string_pretty(&saved)
        .map_err(|e| format!("Cannot record the chosen folder: {e}"))?;
    let tmp = path.with_extension("json.tmp");
    fs::write(&tmp, json).map_err(|e| format!("Cannot write {}: {e}", tmp.display()))?;
    fs::rename(&tmp, &path).map_err(|e| {
        let _ = fs::remove_file(&tmp);
        format!("Cannot record the chosen folder: {e}")
    })
}

/// Re-checks a remembered target. `None` means "ask again" - the folder is gone, its
/// marker no longer validates, or a user-confirmed folder has since acquired metadata
/// saying it is something other than 1.8.9 Forge.
pub fn revalidate(saved: &SavedTarget) -> Option<Candidate> {
    let game_dir = PathBuf::from(&saved.game_dir);
    if !game_dir.is_dir() {
        return None;
    }
    let fresh = classify_picked(&game_dir).ok()?;
    match saved.validated_by {
        ValidatedBy::Marker => {
            // This target was adopted on the strength of its launcher's own metadata, so
            // that evidence has to still be there AND still say the same thing. Anything
            // less - a missing marker, or one that now reads Unknown - means the instance
            // was moved, rebuilt or re-versioned, and the honest answer is to ask again
            // rather than to quietly downgrade it to a folder the user once vouched for.
            let marker = saved.marker.as_ref()?;
            if marker != "mmc-pack.json" {
                return None;
            }
            if fresh.marker.as_ref() != Some(marker) {
                return None;
            }
            (fresh.compat == Compat::Confirmed).then_some(fresh)
        }
        ValidatedBy::User => {
            // The user vouched specifically for an unmarked folder. Metadata appearing
            // later changes the provenance of that choice, even if it happens to be
            // compatible, so ask again instead of silently upgrading trust.
            (fresh.compat == Compat::Unknown && fresh.marker.is_none()).then_some(fresh)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::{Digest, Sha256};

    const OURS: &str = "Cobblify-1.8.9-forge-0.9.0.jar";

    fn sha(bytes: &[u8]) -> String {
        Sha256::digest(bytes)
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect()
    }

    fn forge_jar(dir: &Path, bytes: &[u8]) -> ForgeJar {
        let src = dir.join(OURS);
        fs::write(&src, bytes).unwrap();
        ForgeJar {
            src,
            name: OURS.to_string(),
            sha256: sha(bytes),
        }
    }

    // ── classification ─────────────────────────────────────────────────────────

    #[test]
    fn release_shaped_jars_quarantine_and_everything_else_blocks_or_is_ignored() {
        assert_eq!(classify("Cobblify-1.8.9-forge-0.8.0.jar", OURS), Verdict::Quarantine);
        assert_eq!(classify("Cobblify-1.8.9-forge-0.10.2.jar", OURS), Verdict::Quarantine);
        // A dev build is ours in spirit but not a release we packaged - never touched.
        assert_eq!(classify("Cobblify-1.8.9-forge-0.9.0-dev.jar", OURS), Verdict::Block);
        assert_eq!(classify("Cobblify-renamed.jar", OURS), Verdict::Block);
        assert_eq!(classify("SomeOtherMod.jar", OURS), Verdict::Ignore);
        assert_eq!(classify("OptiFine_1.8.9_HD_U_M5.jar", OURS), Verdict::Ignore);
        assert_eq!(classify("notes.txt", OURS), Verdict::Ignore);
    }

    /// Forge loads a jar however it is cased, so an exact match would miss this one on a
    /// case-sensitive volume and leave the client unable to boot.
    #[test]
    fn classification_is_caseless_on_every_platform() {
        assert_eq!(classify("cobblify-1.8.9-forge-0.8.0.jar", OURS), Verdict::Quarantine);
        assert_eq!(classify("COBBLIFY-1.8.9-FORGE-0.8.0.JAR", OURS), Verdict::Quarantine);
        // Even a case variant of our OWN name is a candidate - only real file identity
        // exempts the destination, and that is decided in `install`, not here.
        assert_eq!(classify("cobblify-1.8.9-forge-0.9.0.jar", OURS), Verdict::Quarantine);
    }

    #[test]
    fn semver_is_strict() {
        assert!(is_semver("0.9.0"));
        assert!(is_semver("10.20.30"));
        assert!(!is_semver("0.9"));
        assert!(!is_semver("0.9.0-dev"));
        assert!(!is_semver("a.b.c"));
        assert!(!is_semver("0..0"));
    }

    // ── the install transaction ────────────────────────────────────────────────

    #[test]
    fn installs_into_a_clean_instance() {
        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");

        let out = install(&jar, game.path()).unwrap();
        assert_eq!(fs::read(game.path().join("mods").join(OURS)).unwrap(), b"forge");
        assert!(out.conflicts.is_empty());
        assert!(out.quarantined.is_empty());
    }

    /// Round-3 B2, and the single most likely real upgrade: our jar is ALREADY current,
    /// and a stale peer sits beside it. An early return here leaves two `bedwarsqol` jars
    /// and a client that refuses to boot.
    #[test]
    fn a_blocker_prevents_every_install_mutation_even_with_stale_peers() {
        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");
        let mods = game.path().join("mods");
        let versioned = mods.join(VERSIONED_MODS_DIR);
        fs::create_dir_all(&versioned).unwrap();
        // Our jar, already installed and byte-identical.
        fs::write(mods.join(OURS), b"forge").unwrap();
        // A stale peer in each load root.
        fs::write(mods.join("Cobblify-1.8.9-forge-0.8.0.jar"), b"old").unwrap();
        fs::write(versioned.join("Cobblify-1.8.9-forge-0.7.1.jar"), b"older").unwrap();
        // And one we must never touch.
        fs::write(mods.join("Cobblify-1.8.9-forge-0.9.0-dev.jar"), b"dev").unwrap();

        let out = install(&jar, game.path()).unwrap();

        assert_eq!(
            fs::read(mods.join(OURS)).unwrap(),
            b"forge",
            "our own current jar must be left alone"
        );
        assert!(out.blocked);
        assert!(out.quarantined.is_empty(), "{:?}", out.quarantined);
        assert!(mods.join("Cobblify-1.8.9-forge-0.8.0.jar").exists());
        assert!(versioned.join("Cobblify-1.8.9-forge-0.7.1.jar").exists());
        assert!(!mods
            .join(format!("Cobblify-1.8.9-forge-0.8.0.jar{DISABLED_SUFFIX}"))
            .exists());
        assert_eq!(out.conflicts.len(), 1, "the dev jar must block");
        assert!(mods.join("Cobblify-1.8.9-forge-0.9.0-dev.jar").exists());
    }

    /// Round-2 B4: a jar sitting at OUR filename that is not ours belongs to the user.
    #[test]
    fn a_foreign_jar_at_our_own_name_is_set_aside_not_overwritten() {
        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");
        let mods = game.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        fs::write(mods.join(OURS), b"the user's own build").unwrap();

        let out = install(&jar, game.path()).unwrap();

        assert_eq!(fs::read(mods.join(OURS)).unwrap(), b"forge");
        assert_eq!(out.quarantined.len(), 1, "{:?}", out.quarantined);
        let saved = mods.join(format!("{OURS}{DISABLED_SUFFIX}"));
        assert_eq!(
            fs::read(&saved).unwrap(),
            b"the user's own build",
            "the user's bytes must survive verbatim"
        );
    }

    #[test]
    fn a_failed_stage_moves_nothing() {
        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let mut jar = forge_jar(src.path(), b"forge");
        jar.sha256 = sha(b"something else"); // the bundle no longer matches its manifest
        let mods = game.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        fs::write(mods.join("Cobblify-1.8.9-forge-0.8.0.jar"), b"old").unwrap();

        let err = install(&jar, game.path()).unwrap_err();
        assert!(err.message.contains("is corrupt"), "{:?}", err);
        assert!(
            mods.join("Cobblify-1.8.9-forge-0.8.0.jar").exists(),
            "a failed stage must not have moved anything"
        );
    }

    /// The reservation must never truncate a set-aside file that is already there.
    #[test]
    fn quarantine_never_clobbers_an_existing_backup() {
        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");
        let mods = game.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        let stale = mods.join("Cobblify-1.8.9-forge-0.8.0.jar");
        fs::write(&stale, b"second old copy").unwrap();
        let existing = mods.join(format!("Cobblify-1.8.9-forge-0.8.0.jar{DISABLED_SUFFIX}"));
        fs::write(&existing, b"first old copy").unwrap();

        install(&jar, game.path()).unwrap();

        assert_eq!(
            fs::read(&existing).unwrap(),
            b"first old copy",
            "the earlier backup must be untouched"
        );
        assert_eq!(
            fs::read(mods.join(format!(
                "Cobblify-1.8.9-forge-0.8.0.jar{DISABLED_SUFFIX} (2)"
            )))
            .unwrap(),
            b"second old copy"
        );
    }

    #[test]
    fn a_quarantined_jar_is_no_longer_loadable_by_forge() {
        // FML only considers entries matching `(.+).(zip|jar)$`.
        let name = format!("Cobblify-1.8.9-forge-0.8.0.jar{DISABLED_SUFFIX}");
        assert!(!name.ends_with(".jar") && !name.ends_with(".zip"));
    }

    // ── compatibility judgements ───────────────────────────────────────────────

    #[test]
    fn judge_confirms_only_1_8_9_forge() {
        assert_eq!(judge("1.8.9", "forge-11.15.1.2318"), Compat::Confirmed);
        assert!(matches!(judge("1.21.4", "fabric"), Compat::Incompatible(_)));
        assert!(matches!(judge("1.8.9", "fabric-0.16"), Compat::Incompatible(_)));
        assert_eq!(judge("", ""), Compat::Unknown);
    }

    #[test]
    fn judge_mmc_reads_the_component_list() {
        let ok = serde_json::json!({"components":[
            {"uid":"net.minecraft","version":"1.8.9"},
            {"uid":"net.minecraftforge","version":"11.15.1.2318"}]});
        assert_eq!(judge_mmc(&ok), Compat::Confirmed);

        let no_forge = serde_json::json!({"components":[
            {"uid":"net.minecraft","version":"1.8.9"}]});
        assert!(matches!(judge_mmc(&no_forge), Compat::Incompatible(_)));

        let wrong_version = serde_json::json!({"components":[
            {"uid":"net.minecraft","version":"1.21.4"},
            {"uid":"net.minecraftforge","version":"x"}]});
        assert!(matches!(judge_mmc(&wrong_version), Compat::Incompatible(_)));

        assert_eq!(judge_mmc(&serde_json::json!({})), Compat::Unknown);
    }

    /// Round-2 B1: a readable vanilla folder with no Forge version installed is a REFUSAL,
    /// not an "unknown". This is the exact state of the planning machine's own
    /// `%APPDATA%\.minecraft`, which holds 1.8.9 and Fabric 1.21.x but no Forge.
    #[test]
    fn a_vanilla_folder_without_forge_is_refused_not_offered() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("launcher_profiles.json"), b"{}").unwrap();
        let versions = root.path().join("versions");
        fs::create_dir_all(versions.join("1.8.9")).unwrap();
        fs::create_dir_all(versions.join("fabric-loader-0.16.12-1.21.4")).unwrap();

        let c = classify_picked(root.path()).unwrap();
        assert!(matches!(c.compat, Compat::Incompatible(_)), "{:?}", c.compat);
        assert!(!c.compat.is_installable());
    }

    #[test]
    fn a_vanilla_folder_with_forge_1_8_9_is_confirmed() {
        let root = tempfile::tempdir().unwrap();
        fs::write(root.path().join("launcher_profiles.json"), b"{}").unwrap();
        fs::create_dir_all(root.path().join("versions/1.8.9-forge1.8.9-11.15.1.2318")).unwrap();

        let c = classify_picked(root.path()).unwrap();
        assert_eq!(c.compat, Compat::Confirmed);
    }

    #[test]
    fn an_unmarked_folder_is_unknown_and_needs_confirmation() {
        let dir = tempfile::tempdir().unwrap();
        let c = classify_picked(dir.path()).unwrap();
        assert_eq!(c.compat, Compat::Unknown);
        assert!(c.marker.is_none());
    }

    #[test]
    fn picking_the_mods_folder_itself_resolves_to_its_instance() {
        let inst = tempfile::tempdir().unwrap();
        fs::write(inst.path().join("minecraftinstance.json"), br#"{"gameVersion":"1.8.9","baseModLoader":{"name":"forge-11.15.1.2318"}}"#).unwrap();
        let mods = inst.path().join("mods");
        fs::create_dir_all(&mods).unwrap();

        let c = classify_picked(&mods).unwrap();
        assert_eq!(c.compat, Compat::Confirmed);
        assert_eq!(c.game_dir, inst.path());
    }

    #[test]
    fn a_picked_prism_instance_resolves_to_its_game_directory() {
        let inst = tempfile::tempdir().unwrap();
        fs::write(
            inst.path().join("mmc-pack.json"),
            br#"{"components":[{"uid":"net.minecraft","version":"1.8.9"},{"uid":"net.minecraftforge","version":"11.15.1.2318"}]}"#,
        )
        .unwrap();
        fs::create_dir_all(inst.path().join("minecraft")).unwrap();

        let c = classify_picked(inst.path()).unwrap();
        assert_eq!(c.compat, Compat::Confirmed);
        assert_eq!(c.game_dir, inst.path().join("minecraft"));
    }

    #[test]
    fn prism_game_dir_prefers_an_existing_layout_and_defaults_to_dot_minecraft() {
        let inst = tempfile::tempdir().unwrap();
        assert_eq!(prism_game_dir(inst.path()), inst.path().join(".minecraft"));

        fs::create_dir_all(inst.path().join("minecraft")).unwrap();
        assert_eq!(prism_game_dir(inst.path()), inst.path().join("minecraft"));

        fs::create_dir_all(inst.path().join(".minecraft")).unwrap();
        assert_eq!(prism_game_dir(inst.path()), inst.path().join(".minecraft"));
    }

    // ── detection ──────────────────────────────────────────────────────────────

    /// `.minecraft` lives under `%APPDATA%` on Windows while `home()` is `%USERPROFILE%`;
    /// building that path off the home directory would silently find nothing.
    #[test]
    fn windows_detection_reads_appdata_not_the_home_directory() {
        let home = tempfile::tempdir().unwrap();
        let appdata = tempfile::tempdir().unwrap();
        let mc = appdata.path().join(".minecraft");
        fs::create_dir_all(mc.join("versions/1.8.9-forge1.8.9")).unwrap();
        fs::write(mc.join("launcher_profiles.json"), b"{}").unwrap();

        let env = Env {
            home: home.path().to_path_buf(),
            appdata: Some(appdata.path().to_path_buf()),
        };
        let found = detect_vanilla(&env);
        if cfg!(windows) {
            let c = found.expect("must find .minecraft under APPDATA");
            assert_eq!(c.compat, Compat::Confirmed);
            assert_eq!(c.game_dir, mc);
        } else {
            assert!(found.is_none(), "the windows layout must not match on macOS");
        }
    }

    #[test]
    fn curseforge_instances_are_detected_and_judged() {
        let home = tempfile::tempdir().unwrap();
        let root = if cfg!(windows) {
            home.path().join("curseforge/minecraft/Instances")
        } else {
            home.path().join("Documents/curseforge/minecraft/Instances")
        };
        for (name, json) in [
            ("Hypixel", r#"{"gameVersion":"1.8.9","baseModLoader":{"name":"forge-11.15.1.2318"}}"#),
            ("DawnCraft", r#"{"gameVersion":"1.20.1","baseModLoader":{"name":"forge-47.2.0"}}"#),
        ] {
            let dir = root.join(name);
            fs::create_dir_all(&dir).unwrap();
            fs::write(dir.join("minecraftinstance.json"), json).unwrap();
        }

        let env = Env {
            home: home.path().to_path_buf(),
            appdata: None,
        };
        let found = detect_curseforge(&env);
        assert_eq!(found.len(), 2);
        let hypixel = found.iter().find(|c| c.name == "Hypixel").unwrap();
        assert_eq!(hypixel.compat, Compat::Confirmed);
        let dawn = found.iter().find(|c| c.name == "DawnCraft").unwrap();
        assert!(matches!(dawn.compat, Compat::Incompatible(_)));
        assert!(!dawn.compat.is_installable());
    }

    #[test]
    fn detection_sorts_confirmed_first_and_assigns_ids() {
        let home = tempfile::tempdir().unwrap();
        let appdata = tempfile::tempdir().unwrap();
        let env = Env {
            home: home.path().to_path_buf(),
            appdata: Some(appdata.path().to_path_buf()),
        };
        let root = env.app_support().unwrap().join("PrismLauncher/instances");
        for (name, json) in [
            ("AAA-wrong", r#"{"components":[{"uid":"net.minecraft","version":"1.20.1"},{"uid":"net.minecraftforge","version":"x"}]}"#),
            ("ZZZ-right", r#"{"components":[{"uid":"net.minecraft","version":"1.8.9"},{"uid":"net.minecraftforge","version":"11.15.1.2318"}]}"#),
        ] {
            let dir = root.join(name);
            fs::create_dir_all(&dir).unwrap();
            fs::write(dir.join("mmc-pack.json"), json).unwrap();
        }
        let found = detect(&env);
        assert_eq!(found[0].name, "ZZZ-right");
        assert_eq!(found[0].compat, Compat::Confirmed);
        assert_eq!(found[0].id, "d0");
        assert!(found.iter().all(|c| !c.id.is_empty()));
    }

    #[test]
    fn nothing_is_detected_on_an_empty_machine() {
        let home = tempfile::tempdir().unwrap();
        let appdata = tempfile::tempdir().unwrap();
        let env = Env {
            home: home.path().to_path_buf(),
            appdata: Some(appdata.path().to_path_buf()),
        };
        assert!(detect(&env).is_empty());
    }

    // ── persistence ────────────────────────────────────────────────────────────

    #[test]
    fn a_saved_target_round_trips() {
        let home = tempfile::tempdir().unwrap();
        let t = SavedTarget {
            game_dir: "C:/games/hypixel".to_string(),
            validated_by: ValidatedBy::Marker,
            marker: Some("minecraftinstance.json".to_string()),
        };
        save_target(home.path(), &t).unwrap();
        let back = load_target(home.path()).unwrap();
        assert_eq!(back.game_dir, t.game_dir);
        assert_eq!(back.validated_by, ValidatedBy::Marker);
        assert_eq!(back.marker.as_deref(), Some("minecraftinstance.json"));
        // No temp file left behind.
        assert!(!home
            .path()
            .join(".cobblify/launcher-targets.json.tmp")
            .exists());
    }

    #[test]
    fn no_saved_target_reads_as_none() {
        let home = tempfile::tempdir().unwrap();
        assert!(load_target(home.path()).is_none());
    }

    #[test]
    fn a_vanished_folder_is_not_revalidated() {
        let t = SavedTarget {
            game_dir: "/no/such/folder/anywhere".to_string(),
            validated_by: ValidatedBy::User,
            marker: None,
        };
        assert!(revalidate(&t).is_none());
    }

    #[test]
    fn a_marker_that_no_longer_validates_is_not_revalidated() {
        let inst = tempfile::tempdir().unwrap();
        let t = SavedTarget {
            game_dir: inst.path().display().to_string(),
            validated_by: ValidatedBy::Marker,
            marker: Some("minecraftinstance.json".to_string()),
        };
        // The folder exists but the marker is gone - the instance was deleted or moved.
        assert!(revalidate(&t).is_none());
    }

    #[test]
    fn a_user_confirmed_folder_survives_having_no_marker() {
        let dir = tempfile::tempdir().unwrap();
        let t = SavedTarget {
            game_dir: dir.path().display().to_string(),
            validated_by: ValidatedBy::User,
            marker: None,
        };
        let c = revalidate(&t).expect("an unmarked folder is exactly what was confirmed");
        assert_eq!(c.compat, Compat::Unknown);
    }

    /// A folder the user vouched for is still refused once it starts saying, in its own
    /// metadata, that it is something else.
    #[test]
    fn a_user_confirmed_folder_that_becomes_contradictory_is_refused() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(
            dir.path().join("minecraftinstance.json"),
            br#"{"gameVersion":"1.21.4","baseModLoader":{"name":"fabric"}}"#,
        )
        .unwrap();
        let t = SavedTarget {
            game_dir: dir.path().display().to_string(),
            validated_by: ValidatedBy::User,
            marker: None,
        };
        assert!(revalidate(&t).is_none());
    }

    #[test]
    fn prism_marker_is_reparsed_from_saved_game_dir() {
        let prism = tempfile::tempdir().unwrap();
        let prism_game = prism.path().join("minecraft");
        fs::create_dir_all(&prism_game).unwrap();
        fs::write(
            prism.path().join("mmc-pack.json"),
            br#"{"components":[{"uid":"net.minecraft","version":"1.8.9"},{"uid":"net.minecraftforge","version":"11.15.1.2318"}]}"#,
        )
        .unwrap();
        let prism_saved = SavedTarget {
            game_dir: prism_game.display().to_string(),
            validated_by: ValidatedBy::Marker,
            marker: Some("mmc-pack.json".to_string()),
        };
        assert_eq!(revalidate(&prism_saved).unwrap().compat, Compat::Confirmed);

    }

    #[test]
    fn user_confirmed_folder_does_not_silently_acquire_marker_provenance() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(
            dir.path().join("profile.json"),
            br#"{"game_version":"1.8.9","loader":"forge"}"#,
        )
        .unwrap();
        let saved = SavedTarget {
            game_dir: dir.path().display().to_string(),
            validated_by: ValidatedBy::User,
            marker: None,
        };
        assert!(revalidate(&saved).is_none());
    }
}
