//! Forge support: finding a Minecraft instance, judging whether Cobblify belongs in it,
//! and installing the jar there, deleting only the older releases it replaces.
//!
//! The design rests on one fact: the Forge build of Cobblify is a PURE DROP-IN. Mixin is
//! shaded into the jar and its manifest carries `TweakClass` + `ForceLoadAsMod`, which FML
//! auto-registers, so there is no JVM argument, no coremod, no bootstrap and no config to
//! edit. Forge support is Prism-only: detect instances under Prism Launcher's default
//! directory, install into the instance game folder, and launch via Prism's instance-id CLI.
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
//!     and never deleted: ambiguity resolves toward telling the user, never toward leaving a
//!     jar that might crash their game, and never toward touching a file that may live
//!     somewhere else entirely.
//!
//! Nothing here is ever installed speculatively. Detection returns candidates; a user
//! choice installs.

use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::install::{commit, release_version, stage_verified};
use crate::resources::ForgeJar;

/// Launchers before 0.16.3 renamed a superseded jar to `<name>.cobblify-disabled` (then
/// `... (2)`, `... (3)` when taken) instead of deleting it. FML ignores those, since it only
/// considers `(.+).(zip|jar)$`; `install` clears the ones that were our releases.
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
    /// Prism's `mmc-pack.json` exists but could not be read or parsed.
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
        matches!(self, Compat::Confirmed)
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

/// The wire shape the UI renders. Only confirmed instances are published.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct CandidateView {
    pub id: String,
    pub name: String,
}

impl Candidate {
    pub fn view(&self) -> CandidateView {
        CandidateView {
            id: self.id.clone(),
            name: self.name.clone(),
        }
    }
}

/// Standard Prism install locations used for launch and setup detection.
pub fn prism_exe() -> PathBuf {
    #[cfg(windows)]
    {
        PathBuf::from(std::env::var_os("LOCALAPPDATA").unwrap_or_default())
            .join("Programs/PrismLauncher/prismlauncher.exe")
    }
    #[cfg(target_os = "macos")]
    {
        PathBuf::from("/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher")
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        PathBuf::from("prismlauncher")
    }
}

pub fn prism_installed() -> bool {
    prism_exe().is_file()
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
    for entry in fs::read_dir(dir).map_err(|e| format!("Cannot read {}: {e}", dir.display()))? {
        let entry = entry.map_err(|e| format!("Cannot read an entry in {}: {e}", dir.display()))?;
        out.push(entry.path());
    }
    out.sort();
    Ok(out)
}

/// Detection reads Prism Launcher's default instances directory only. Portable or
/// relocated Prism installs are out of scope: Cobblify supports the standard layout so
/// every offered target can be installed and launched the same way as Lunar.
pub fn detect(env: &Env) -> Vec<Candidate> {
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
/// make - on the instance's first launch, which may well come AFTER the user points
/// Cobblify at it. Detection offers such an instance, so the adopt paths
/// (`classify_picked`, `revalidate`) accept that not-yet-created directory too: the
/// instance root's `mmc-pack.json` is what proves the target, and `install` creates
/// `<gameDir>/mods` regardless.
fn prism_game_dir(instance: &Path) -> PathBuf {
    for name in [".minecraft", "minecraft"] {
        let candidate = instance.join(name);
        if candidate.is_dir() {
            return candidate;
        }
    }
    instance.join(".minecraft")
}

fn dir_name(p: &Path) -> String {
    p.file_name()
        .unwrap_or_default()
        .to_string_lossy()
        .into_owned()
}

/// Prism records the version as a component list rather than two fields.
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

fn prism_instance_from_game_dir(game_dir: &Path) -> Result<PathBuf, String> {
    if game_dir.join("mmc-pack.json").is_file() {
        return Ok(game_dir.to_path_buf());
    }
    let Some(parent) = game_dir.parent() else {
        return Err("That is not a Prism instance.".to_string());
    };
    if parent.join("mmc-pack.json").is_file() {
        return Ok(parent.to_path_buf());
    }
    Err("That is not a Prism instance.".to_string())
}

fn prism_candidate(instance_root: &Path, compat: Compat) -> Candidate {
    Candidate {
        id: String::new(),
        launcher: "Prism Launcher".to_string(),
        name: dir_name(instance_root),
        game_dir: prism_game_dir(instance_root),
        compat,
        marker: Some("mmc-pack.json".to_string()),
    }
}

/// The one path that is allowed not to exist yet: the game directory Prism itself will
/// create inside an instance whose marker we can already read. Narrow on purpose - the
/// name alone proves nothing, so the parent must carry `mmc-pack.json`.
fn is_unborn_prism_game_dir(dir: &Path) -> bool {
    if !matches!(dir_name(dir).as_str(), ".minecraft" | "minecraft") {
        return false;
    }
    dir.parent()
        .is_some_and(|inst| inst.join("mmc-pack.json").is_file())
}

/// Re-reads a remembered Prism instance from its stored game directory.
pub fn classify_picked(dir: &Path) -> Result<Candidate, String> {
    if !dir.is_dir() && !is_unborn_prism_game_dir(dir) {
        return Err("That is not a folder.".to_string());
    }
    let game_dir = if dir_name(dir).eq_ignore_ascii_case("mods") {
        dir.parent().unwrap_or(dir).to_path_buf()
    } else {
        dir.to_path_buf()
    };
    let instance_root = prism_instance_from_game_dir(&game_dir)?;
    let compat = match read_json(&instance_root.join("mmc-pack.json")) {
        Some(v) => judge_mmc(&v),
        None => Compat::Unknown,
    };
    Ok(prism_candidate(&instance_root, compat))
}

// ── stale jars ──────────────────────────────────────────────────────────────────

/// What to do with one Cobblify-looking jar found in a load root.
#[derive(Debug, PartialEq, Eq)]
enum Verdict {
    /// A copy of one of our own packaged releases no newer than ours: deleted.
    Stale,
    /// Cobblify-ish but not a release we may replace - a newer release, a `-dev` build, a
    /// hand-renamed file, or a path we could not resolve. Reported, never touched.
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

/// Classification is CASELESS on every platform. Forge loads a jar however its name is
/// cased, so an exact comparison would miss a lower-cased stale jar on a case-sensitive
/// volume and hand the user a client that refuses to boot.
fn classify(name: &str, our_name: &str) -> Verdict {
    let lower = name.to_ascii_lowercase();
    if !lower.ends_with(".jar") || !lower.starts_with("cobblify") {
        return Verdict::Ignore;
    }
    // Same version and a different name is a second spelling of our own jar. A newer
    // release is not ours to delete: an older launcher would be downgrading.
    let prefix = release_prefix(our_name);
    match (release_version(name, &prefix), release_version(our_name, &prefix)) {
        (Some(theirs), Some(ours)) if theirs <= ours => Verdict::Stale,
        _ => Verdict::Block,
    }
}

/// Every directory FML will scan for mods.
fn load_roots(mods_dir: &Path) -> Vec<PathBuf> {
    vec![mods_dir.to_path_buf(), mods_dir.join(VERSIONED_MODS_DIR)]
}

/// A copy of one of our releases that an older launcher set aside: `<release>.cobblify-disabled`
/// or `<release>.cobblify-disabled (<n>)`, in any case.
fn is_set_aside_release(name: &str, our_name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    let Some(i) = lower.rfind(DISABLED_SUFFIX) else {
        return false;
    };
    let tail = &lower[i + DISABLED_SUFFIX.len()..];
    let numbered = tail
        .strip_prefix(" (")
        .and_then(|t| t.strip_suffix(')'))
        .is_some_and(|n| !n.is_empty() && n.bytes().all(|b| b.is_ascii_digit()));
    (tail.is_empty() || numbered) && classify(&lower[..i], our_name) == Verdict::Stale
}

/// Whether a directory entry IS our destination - the one file exempt from the hunt.
///
/// This is a directory-entry question, not a canonical-target one. Comparing resolved
/// targets would exempt a SYMLINK that points at the destination, and Forge builds a mod
/// candidate per directory entry, so that alias would still load as a second jar with the
/// same mod id. Symlinks are handled by the caller (always reported, never deleted);
/// here only the entry's own location and name matter, cased the way the platform's own
/// filesystem cases them.
///
/// Which is exactly why the name comparison cannot be byte-exact everywhere. On a
/// case-FOLDING volume - APFS by default, and every Windows volume - `cobblify-....jar`
/// and `Cobblify-....jar` in one directory are ONE entry under two spellings. Treating the
/// user's lower-cased file as a separate jar set the same file aside twice and failed the
/// whole install. So a caseless name match ALSO exempts the entry, but only when the two
/// paths are proven to be the same file: same device, same inode. Two distinct hard links
/// share an inode but not a caseless name, so they stay on the stale path - a second
/// directory entry is a second mod candidate however it is stored.
fn is_destination_entry(path: &Path, dest: &Path, dest_entry_present: bool) -> bool {
    if path.parent() != dest.parent() {
        return false;
    }
    match (path.file_name(), dest.file_name()) {
        (Some(a), Some(b)) => {
            if a == b {
                return true;
            }
            #[cfg(windows)]
            {
                a.to_string_lossy()
                    .eq_ignore_ascii_case(&b.to_string_lossy())
            }
            #[cfg(unix)]
            {
                // Same inode is NOT the same directory entry. On a case-folding volume a
                // case variant is the destination's own entry under the name the filesystem
                // stored, and the listing therefore holds no entry spelled exactly like the
                // destination. On a case-sensitive volume the destination has its own entry
                // and a case variant beside it is a SECOND, independently loadable file -
                // even when the two are hard links - so it must stay on the stale path.
                !dest_entry_present
                    && a.to_string_lossy()
                        .eq_ignore_ascii_case(&b.to_string_lossy())
                    && is_same_file(path, dest)
            }
            #[cfg(not(any(windows, unix)))]
            {
                false
            }
        }
        _ => false,
    }
}

/// Same device and same inode - the two names address one file. A destination that does
/// not exist yet, or either path being unreadable, answers "no", which keeps the caller on
/// the stale path it would have taken before.
#[cfg(unix)]
fn is_same_file(a: &Path, b: &Path) -> bool {
    use std::os::unix::fs::MetadataExt;
    match (fs::metadata(a), fs::metadata(b)) {
        (Ok(x), Ok(y)) => x.dev() == y.dev() && x.ino() == y.ino(),
        _ => false,
    }
}

/// The result of setting up one Forge instance.
#[derive(Debug, Default)]
pub struct Outcome {
    pub path: Option<String>,
    /// Jars we refused to touch. Their presence blocks, because Forge will not boot.
    pub conflicts: Vec<String>,
    /// True when we deliberately installed nothing.
    pub blocked: bool,
}

/// Installs the Forge jar into `game_dir`, deleting the releases it supersedes so Forge
/// does not refuse to boot on two `bedwarsqol` jars.
///
/// Order is the whole safety argument (PLAN 2.6):
///   1. scan both load roots, and finish the scan before touching anything - including when
///      our jar is already current, which is the likeliest real upgrade and the case an early
///      return would break;
///   2. install nothing if any Cobblify jar we may not touch is present;
///   3. stage and hash the new jar - a failure here deletes nothing;
///   4. delete the superseded releases, and stop if one cannot be deleted - committing beside
///      it would itself be the two-jar state;
///   5. commit, replacing whatever build sits at our own name;
///   6. clear the copies older launchers set aside.
pub fn install(jar: &ForgeJar, game_dir: &Path) -> Result<Outcome, String> {
    let mods_dir = game_dir.join("mods");
    fs::create_dir_all(&mods_dir)
        .map_err(|e| format!("Cannot create {}: {e}", mods_dir.display()))?;
    let dest = mods_dir.join(&jar.name);

    // 1. DISCOVERY FIRST, and it must complete. Nothing is deleted and nothing is staged
    //    until we know everything that is in both load roots, because the decision in
    //    step 2 depends on having seen all of it.
    let mut stale = Vec::new();
    let mut set_aside = Vec::new();
    let mut conflicts = Vec::new();
    for root in load_roots(&mods_dir) {
        if !root.is_dir() {
            continue;
        }
        let entries = read_dir_strict(&root)?;
        // Whether this directory lists the destination under its exact name; the case-alias
        // exemption below is only sound when it does not.
        let dest_entry_present = entries.iter().any(|entry| entry == &dest);
        for path in entries {
            let meta = fs::symlink_metadata(&path)
                .map_err(|e| format!("Cannot read {}: {e}", path.display()))?;
            if meta.is_dir() {
                continue;
            }
            let name = dir_name(&path);
            if is_set_aside_release(&name, &jar.name) {
                if meta.is_file() {
                    set_aside.push(path);
                }
                continue;
            }
            if classify(&name, &jar.name) == Verdict::Ignore {
                continue;
            }
            // A symlink is reported, never deleted: it may alias a file far outside this
            // folder, and Forge counts it as its own jar entry regardless of where it
            // points. Reporting is the honest answer for something we cannot reason about.
            if meta.file_type().is_symlink() {
                conflicts.push(path.display().to_string());
                continue;
            }
            if is_destination_entry(&path, &dest, dest_entry_present) {
                continue;
            }
            match classify(&name, &jar.name) {
                Verdict::Stale => stale.push(path),
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
            blocked: true,
        });
    }

    // 3. Stage. `None` means our jar is already byte-identical (Windows) - no commit
    //    needed, which is NOT the same as nothing left to do; the scan above ran anyway.
    let staged = stage_verified(&jar.src, &dest, &jar.sha256)?;

    // 4. Superseded releases, in both load roots. Windows refuses while the game has one
    //    open; the staged jar is then dropped uncommitted.
    for path in stale {
        match fs::remove_file(&path) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => {
                return Err(format!(
                    "Cannot delete the old jar {}: {e}. If Minecraft is open, close it and try again.",
                    path.display()
                ))
            }
        }
    }

    // 5. Commit. The rename replaces a different build at our own name - most often the
    //    dev build of this same version.
    if let Some(staged) = staged {
        commit(staged)?;
    }

    // 6. FML never loads a set-aside copy, so clearing them is tidying only and a failure
    //    here never fails the install.
    for path in set_aside {
        let _ = fs::remove_file(path);
    }
    Ok(Outcome {
        path: Some(dest.display().to_string()),
        conflicts: Vec::new(),
        blocked: false,
    })
}

// ── remembering the choice ──────────────────────────────────────────────────────

/// How the target was validated when it was chosen. Only Prism marker-backed targets are
/// remembered today; older user-confirmed records are ignored on revalidation.
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
        .map_err(|e| format!("Cannot record the chosen Prism instance: {e}"))?;
    let tmp = path.with_extension("json.tmp");
    fs::write(&tmp, json).map_err(|e| format!("Cannot write {}: {e}", tmp.display()))?;
    fs::rename(&tmp, &path).map_err(|e| {
        let _ = fs::remove_file(&tmp);
        format!("Cannot record the chosen folder: {e}")
    })
}

/// Re-checks a remembered Prism target. `None` means "ask again" - the instance is gone,
/// its marker no longer validates, or the saved record is from a removed setup path.
pub fn revalidate(saved: &SavedTarget) -> Option<Candidate> {
    if saved.validated_by != ValidatedBy::Marker {
        return None;
    }
    if saved.marker.as_deref() != Some("mmc-pack.json") {
        return None;
    }
    let game_dir = PathBuf::from(&saved.game_dir);
    // The instance must still be there; its game directory may be one Prism has not made
    // yet, exactly as when the target was chosen.
    if !game_dir.is_dir() && !is_unborn_prism_game_dir(&game_dir) {
        return None;
    }
    let fresh = classify_picked(&game_dir).ok()?;
    if fresh.marker.as_deref() != Some("mmc-pack.json") {
        return None;
    }
    (fresh.compat == Compat::Confirmed).then_some(fresh)
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::{Digest, Sha256};

    const OURS: &str = "Cobblify-1.8.9-forge-0.9.0.jar";

    /// A Prism `mmc-pack.json` that `judge_mmc` confirms.
    const CONFIRMED_PACK: &[u8] = br#"{"components":[{"uid":"net.minecraft","version":"1.8.9"},{"uid":"net.minecraftforge","version":"11.15.1.2318"}]}"#;

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
    fn release_shaped_jars_are_stale_and_everything_else_blocks_or_is_ignored() {
        assert_eq!(
            classify("Cobblify-1.8.9-forge-0.8.0.jar", OURS),
            Verdict::Stale
        );
        // A NEWER release is not ours to delete: an older launcher would be downgrading.
        // Versions compare as numbers, so 0.10.2 is newer than 0.9.0.
        assert_eq!(
            classify("Cobblify-1.8.9-forge-0.10.2.jar", OURS),
            Verdict::Block
        );
        assert_eq!(
            classify("Cobblify-1.8.9-forge-0.9.0.jar", "Cobblify-1.8.9-forge-0.10.2.jar"),
            Verdict::Stale
        );
        // A dev build is ours in spirit but not a release we packaged - never touched.
        assert_eq!(
            classify("Cobblify-1.8.9-forge-0.9.0-dev.jar", OURS),
            Verdict::Block
        );
        assert_eq!(classify("Cobblify-renamed.jar", OURS), Verdict::Block);
        assert_eq!(classify("SomeOtherMod.jar", OURS), Verdict::Ignore);
        assert_eq!(
            classify("OptiFine_1.8.9_HD_U_M5.jar", OURS),
            Verdict::Ignore
        );
        assert_eq!(classify("notes.txt", OURS), Verdict::Ignore);
    }

    /// Forge loads a jar however it is cased, so an exact match would miss this one on a
    /// case-sensitive volume and leave the client unable to boot.
    #[test]
    fn classification_is_caseless_on_every_platform() {
        assert_eq!(
            classify("cobblify-1.8.9-forge-0.8.0.jar", OURS),
            Verdict::Stale
        );
        assert_eq!(
            classify("COBBLIFY-1.8.9-FORGE-0.8.0.JAR", OURS),
            Verdict::Stale
        );
        // Even a case variant of our OWN name is a candidate - only real file identity
        // exempts the destination, and that is decided in `install`, not here.
        assert_eq!(
            classify("cobblify-1.8.9-forge-0.9.0.jar", OURS),
            Verdict::Stale
        );
    }

    /// L10: `release_prefix` returns the WHOLE name when it carries no `-`, so the
    /// remainder after stripping the prefix can be shorter than `".jar"` - and slicing
    /// four bytes off it panicked, taking the whole install down.
    #[test]
    fn a_name_that_is_exactly_the_release_prefix_blocks_instead_of_panicking() {
        assert_eq!(classify("cobblify.jar", "Cobblify.jar"), Verdict::Block);
        assert_eq!(classify("Cobblify.jar", "Cobblify.jar"), Verdict::Block);
        // The remainder is short but non-empty, and still not a version.
        assert_eq!(classify("cobblify.jar.jar", "Cobblify.jar"), Verdict::Block);
    }

    // ── the install transaction ────────────────────────────────────────────────

    #[test]
    fn installs_into_a_clean_instance() {
        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");

        let out = install(&jar, game.path()).unwrap();
        assert_eq!(
            fs::read(game.path().join("mods").join(OURS)).unwrap(),
            b"forge"
        );
        assert!(out.conflicts.is_empty());
    }

    /// The auto-update case: the new release lands under a new name, and every older
    /// release in either load root is deleted rather than set aside.
    #[test]
    fn an_update_deletes_superseded_releases_in_both_load_roots() {
        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");
        let mods = game.path().join("mods");
        let versioned = mods.join(VERSIONED_MODS_DIR);
        fs::create_dir_all(&versioned).unwrap();
        fs::write(mods.join("Cobblify-1.8.9-forge-0.8.0.jar"), b"old").unwrap();
        fs::write(versioned.join("cobblify-1.8.9-forge-0.7.1.JAR"), b"older").unwrap();
        fs::write(mods.join("SomeOtherMod.jar"), b"unrelated").unwrap();

        let out = install(&jar, game.path()).unwrap();

        assert!(!out.blocked, "{out:?}");
        assert_eq!(fs::read(mods.join(OURS)).unwrap(), b"forge");
        assert_eq!(
            entries_named(&mods, |_| true),
            vec![VERSIONED_MODS_DIR.to_string(), OURS.to_string(), "SomeOtherMod.jar".to_string()]
        );
        assert!(entries_named(&versioned, |_| true).is_empty());
    }

    /// Older launchers renamed superseded jars to `<name>.cobblify-disabled` instead of
    /// deleting them. Those copies of our releases are cleared too; anything else carrying
    /// the suffix is not ours to judge and stays.
    #[test]
    fn copies_older_launchers_set_aside_are_deleted() {
        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");
        let mods = game.path().join("mods");
        let versioned = mods.join(VERSIONED_MODS_DIR);
        fs::create_dir_all(&versioned).unwrap();
        for name in [
            format!("Cobblify-1.8.9-forge-0.8.0.jar{DISABLED_SUFFIX}"),
            format!("Cobblify-1.8.9-forge-0.8.0.jar{DISABLED_SUFFIX} (2)"),
            format!("COBBLIFY-1.8.9-FORGE-0.7.0.JAR{DISABLED_SUFFIX}"),
            format!("{OURS}{DISABLED_SUFFIX}"),
        ] {
            fs::write(mods.join(name), b"set aside").unwrap();
        }
        fs::write(
            versioned.join(format!("Cobblify-1.8.9-forge-0.7.1.jar{DISABLED_SUFFIX}")),
            b"set aside",
        )
        .unwrap();
        let kept = [
            format!("Cobblify-1.8.9-forge-0.9.0-dev.jar{DISABLED_SUFFIX}"),
            format!("SomeOtherMod.jar{DISABLED_SUFFIX}"),
            format!("Cobblify-1.8.9-forge-0.8.0.jar{DISABLED_SUFFIX}.bak"),
            format!("Cobblify-1.8.9-forge-0.8.0.jar{DISABLED_SUFFIX} (x)"),
        ];
        for name in &kept {
            fs::write(mods.join(name), b"not ours").unwrap();
        }

        let out = install(&jar, game.path()).unwrap();

        assert!(!out.blocked, "{out:?}");
        let mut expected: Vec<String> = kept.to_vec();
        expected.push(OURS.to_string());
        expected.push(VERSIONED_MODS_DIR.to_string());
        expected.sort();
        assert_eq!(entries_named(&mods, |_| true), expected);
        assert!(entries_named(&versioned, |_| true).is_empty());
    }

    #[test]
    fn set_aside_names_are_recognised_strictly() {
        let disabled = |n: &str| format!("Cobblify-1.8.9-forge-0.8.0.jar{n}");
        assert!(is_set_aside_release(&disabled(DISABLED_SUFFIX), OURS));
        assert!(is_set_aside_release(&disabled(".COBBLIFY-DISABLED (12)"), OURS));
        assert!(!is_set_aside_release(&disabled(""), OURS), "a live jar is not a set-aside copy");
        assert!(!is_set_aside_release(&disabled(".cobblify-disabled ()"), OURS));
        assert!(!is_set_aside_release(&disabled(".cobblify-disabled (2"), OURS));
        assert!(!is_set_aside_release(
            &format!("Cobblify-1.8.9-forge-0.9.0-dev.jar{DISABLED_SUFFIX}"),
            OURS
        ));
    }

    /// Deleting an old release can fail (Windows refuses while the game holds it open). The
    /// new jar must then not be committed: two loadable copies would stop Forge booting.
    #[cfg(unix)]
    #[test]
    fn an_old_release_that_cannot_be_deleted_fails_before_the_commit() {
        use std::os::unix::fs::PermissionsExt;

        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");
        let mods = game.path().join("mods");
        let versioned = mods.join(VERSIONED_MODS_DIR);
        fs::create_dir_all(&versioned).unwrap();
        let old = versioned.join("Cobblify-1.8.9-forge-0.8.0.jar");
        fs::write(&old, b"old").unwrap();
        fs::set_permissions(&versioned, fs::Permissions::from_mode(0o555)).unwrap();

        let result = install(&jar, game.path());
        fs::set_permissions(&versioned, fs::Permissions::from_mode(0o755)).unwrap();

        let err = result.unwrap_err();
        assert!(err.contains("Cannot delete"), "{err}");
        assert!(old.exists());
        assert_eq!(
            entries_named(&mods, |_| true),
            vec![VERSIONED_MODS_DIR.to_string()],
            "neither our jar nor its temp file may be left"
        );
    }

    /// Round-3 B2, and the single most likely real upgrade: our jar is ALREADY current (on
    /// Windows the `Ok(None)` skip path) and a stale peer sits beside it. An early return
    /// there leaves two `bedwarsqol` jars and a client that refuses to boot.
    #[test]
    fn a_current_jar_still_clears_an_older_release() {
        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");
        let mods = game.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        fs::write(mods.join(OURS), b"forge").unwrap();
        fs::write(mods.join("Cobblify-1.8.9-forge-0.8.0.jar"), b"old").unwrap();

        let out = install(&jar, game.path()).unwrap();

        assert!(!out.blocked, "{out:?}");
        assert_eq!(entries_named(&mods, |_| true), vec![OURS.to_string()]);
        assert_eq!(fs::read(mods.join(OURS)).unwrap(), b"forge");
    }

    /// An older copy of the launcher reports a newer release instead of deleting it, and
    /// installs nothing beside it.
    #[test]
    fn a_newer_release_blocks_instead_of_being_deleted() {
        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");
        let mods = game.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        let newer = mods.join("Cobblify-1.8.9-forge-0.10.0.jar");
        fs::write(&newer, b"newer").unwrap();

        let out = install(&jar, game.path()).unwrap();

        assert!(out.blocked, "{out:?}");
        assert_eq!(out.conflicts, vec![newer.display().to_string()]);
        assert_eq!(entries_named(&mods, |_| true), vec![dir_name(&newer)]);
    }

    /// The real Windows case for a failed delete: the game holds the old jar open without
    /// delete sharing. The new jar must not be committed beside it.
    #[cfg(windows)]
    #[test]
    fn a_locked_old_release_fails_before_the_commit() {
        use std::os::windows::fs::OpenOptionsExt;

        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");
        let mods = game.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        let old = mods.join("Cobblify-1.8.9-forge-0.8.0.jar");
        fs::write(&old, b"old").unwrap();
        // FILE_SHARE_READ only: readers allowed, deletion refused.
        let lock = fs::OpenOptions::new()
            .read(true)
            .share_mode(1)
            .open(&old)
            .unwrap();

        let result = install(&jar, game.path());
        drop(lock);

        let err = result.unwrap_err();
        assert!(err.contains("Cannot delete"), "{err}");
        assert_eq!(entries_named(&mods, |_| true), vec![dir_name(&old)]);
    }

    /// Nothing installs past a blocker, and nothing is deleted either - not even stale peers.
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
        // A copy an older launcher set aside is not cleared while blocked either.
        let set_aside = mods.join(format!("Cobblify-1.8.9-forge-0.6.0.jar{DISABLED_SUFFIX}"));
        fs::write(&set_aside, b"set aside").unwrap();

        let out = install(&jar, game.path()).unwrap();

        assert_eq!(
            fs::read(mods.join(OURS)).unwrap(),
            b"forge",
            "our own current jar must be left alone"
        );
        assert!(out.blocked);
        assert!(mods.join("Cobblify-1.8.9-forge-0.8.0.jar").exists());
        assert!(versioned.join("Cobblify-1.8.9-forge-0.7.1.jar").exists());
        assert!(set_aside.exists());
        assert_eq!(out.conflicts.len(), 1, "the dev jar must block");
        assert!(mods.join("Cobblify-1.8.9-forge-0.9.0-dev.jar").exists());
    }

    /// A different build under our own name - most often the dev build of the same version
    /// the user tested before the release - is replaced in place, as Lunar's install does.
    #[test]
    fn a_different_build_at_our_own_name_is_replaced() {
        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");
        let mods = game.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        fs::write(mods.join(OURS), b"the dev build").unwrap();

        let out = install(&jar, game.path()).unwrap();

        assert!(!out.blocked, "{out:?}");
        assert_eq!(fs::read(mods.join(OURS)).unwrap(), b"forge");
        assert_eq!(entries_named(&mods, |_| true), vec![OURS.to_string()]);
    }

    /// Which branch of `is_destination_entry` a run exercises depends on the volume, and
    /// both are legitimate; the probe only names which one ran.
    fn volume_folds_case(dir: &Path) -> bool {
        let probe = dir.join("PROBE-CASE");
        fs::write(&probe, b"").unwrap();
        let folds = dir.join("probe-case").exists();
        fs::remove_file(&probe).unwrap();
        folds
    }

    fn entries_named(dir: &Path, p: impl Fn(&str) -> bool) -> Vec<String> {
        let mut out: Vec<String> = read_dir_strict(dir)
            .unwrap()
            .iter()
            .map(|e| dir_name(e))
            .filter(|n| p(n))
            .collect();
        out.sort();
        out
    }

    /// L11: on a case-folding volume `mods/cobblify-...0.9.0.jar` IS our destination, so
    /// handling it as a stale peer and then again as the destination touched the same file
    /// twice and failed the install outright. The user had to run setup again for it to
    /// succeed.
    ///
    /// On a case-sensitive volume the lowercase file is an independent entry that the
    /// caseless classifier deletes - a different path to the same outcome, which is why the
    /// assertions below hold on both.
    #[test]
    fn case_variant_of_our_name_installs_in_one_attempt() {
        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");
        let mods = game.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        fs::write(mods.join(OURS.to_ascii_lowercase()), b"the dev build").unwrap();
        println!(
            "case_variant_of_our_name_installs_in_one_attempt: volume folds case = {}",
            volume_folds_case(&mods)
        );

        let out = install(&jar, game.path()).unwrap();

        assert!(!out.blocked, "{out:?}");
        assert_eq!(
            fs::read(mods.join(OURS)).unwrap(),
            b"forge",
            "our jar must be installed on the first attempt"
        );
        let all = entries_named(&mods, |_| true);
        assert_eq!(all.len(), 1, "exactly one jar, and nothing set aside: {all:?}");
    }

    /// The inode exemption must not swallow a DISTINCT directory entry that happens to
    /// share an inode: Forge builds a mod candidate per entry, so a hard link named as an
    /// older release still loads a second `bedwarsqol`.
    /// Code review round 2, I2. Same inode is not the same directory entry. The exemption
    /// may only fire when the listing holds no entry spelled exactly like the destination:
    /// that is what tells a case-folding volume's single entry apart from a case-sensitive
    /// volume's two independently loadable ones.
    #[cfg(unix)]
    #[test]
    fn a_case_variant_is_the_destination_only_when_the_listing_has_no_exact_entry() {
        let dir = tempfile::tempdir().unwrap();
        let mods = dir.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        let dest = mods.join("Cobblify-1.8.9-forge-0.9.0.jar");
        fs::write(&dest, b"ours").unwrap();
        let variant = mods.join("cobblify-1.8.9-forge-0.9.0.jar");

        if is_same_file(&variant, &dest) {
            // Case-folding volume: ONE entry, reached by either spelling.
            assert!(
                is_destination_entry(&variant, &dest, false),
                "the folded volume's only entry is the destination"
            );
            assert!(
                !is_destination_entry(&variant, &dest, true),
                "a listing that already names the destination means this is a second entry"
            );
        } else {
            // Case-sensitive volume: two entries, hard-linked to one file.
            fs::hard_link(&dest, &variant).unwrap();
            assert!(is_same_file(&variant, &dest));
            assert!(
                !is_destination_entry(&variant, &dest, true),
                "a second loadable entry is never the destination, hard link or not"
            );
        }
    }

    #[test]
    fn distinct_hard_link_of_an_older_release_is_still_deleted() {
        let src = tempfile::tempdir().unwrap();
        let game = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");
        let mods = game.path().join("mods");
        fs::create_dir_all(&mods).unwrap();
        let current = mods.join(OURS);
        fs::write(&current, b"forge").unwrap();
        let older = mods.join("Cobblify-1.8.9-forge-0.8.0.jar");
        fs::hard_link(&current, &older).unwrap();

        let out = install(&jar, game.path()).unwrap();

        assert!(!out.blocked, "{out:?}");
        assert!(!older.exists(), "the older-named link must be deleted");
        assert_eq!(
            fs::read(&current).unwrap(),
            b"forge",
            "deleting the link must not touch our jar's bytes"
        );
        assert_eq!(entries_named(&mods, |_| true), vec![OURS.to_string()]);
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
        assert!(err.contains("is corrupt"), "{err}");
        assert!(
            mods.join("Cobblify-1.8.9-forge-0.8.0.jar").exists(),
            "a failed stage must not have deleted anything"
        );
    }

    // ── compatibility judgements ───────────────────────────────────────────────

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

    #[test]
    fn a_prism_game_directory_is_reparsed_from_its_saved_target() {
        let inst = tempfile::tempdir().unwrap();
        fs::create_dir_all(inst.path().join("minecraft")).unwrap();
        fs::write(
            inst.path().join("mmc-pack.json"),
            br#"{"components":[{"uid":"net.minecraft","version":"1.8.9"},{"uid":"net.minecraftforge","version":"11.15.1.2318"}]}"#,
        )
        .unwrap();

        let c = classify_picked(&inst.path().join("minecraft")).unwrap();
        assert_eq!(c.compat, Compat::Confirmed);
        assert_eq!(c.game_dir, inst.path().join("minecraft"));
        assert_eq!(c.launcher, "Prism Launcher");
    }

    #[test]
    fn unreadable_prism_metadata_is_unknown_and_not_installable() {
        let inst = tempfile::tempdir().unwrap();
        fs::create_dir_all(inst.path().join(".minecraft")).unwrap();
        fs::write(inst.path().join("mmc-pack.json"), b"{").unwrap();

        let c = classify_picked(&inst.path().join(".minecraft")).unwrap();
        assert_eq!(c.compat, Compat::Unknown);
        assert!(!c.compat.is_installable());
    }

    #[test]
    fn non_prism_directories_are_rejected() {
        let dir = tempfile::tempdir().unwrap();
        assert!(classify_picked(dir.path()).is_err());
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

    /// L5: Prism creates `.minecraft` on the instance's FIRST launch, so a brand-new
    /// instance is offerable (detection judges the `mmc-pack.json`) but had no game
    /// directory yet - and every adopt path refused it, leaving the user with an instance
    /// the launcher offered and then would not install into.
    #[test]
    fn a_fresh_prism_instance_without_a_game_dir_is_adoptable() {
        let home = tempfile::tempdir().unwrap();
        let appdata = tempfile::tempdir().unwrap();
        let env = Env {
            home: home.path().to_path_buf(),
            appdata: Some(appdata.path().to_path_buf()),
        };
        let inst = env
            .app_support()
            .unwrap()
            .join("PrismLauncher/instances/Fresh");
        fs::create_dir_all(&inst).unwrap();
        fs::write(inst.join("mmc-pack.json"), CONFIRMED_PACK).unwrap();
        let game_dir = inst.join(".minecraft");

        let found = detect(&env);
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].compat, Compat::Confirmed);
        assert_eq!(found[0].game_dir, game_dir);
        assert!(!game_dir.exists(), "Prism has not made it yet");

        let picked = classify_picked(&game_dir).unwrap();
        assert_eq!(picked.compat, Compat::Confirmed);
        assert_eq!(picked.game_dir, game_dir);

        let saved = SavedTarget {
            game_dir: game_dir.display().to_string(),
            validated_by: ValidatedBy::Marker,
            marker: Some("mmc-pack.json".to_string()),
        };
        assert!(
            revalidate(&saved).is_some(),
            "a remembered fresh instance must survive a restart"
        );

        let src = tempfile::tempdir().unwrap();
        let jar = forge_jar(src.path(), b"forge");
        let out = install(&jar, &game_dir).unwrap();
        assert!(!out.blocked, "{out:?}");
        assert_eq!(fs::read(game_dir.join("mods").join(OURS)).unwrap(), b"forge");
    }

    /// The exemption is narrow: only the directory Prism itself will create, and only
    /// inside something the marker proves is an instance.
    #[test]
    fn a_missing_folder_is_adoptable_only_as_a_prism_game_dir() {
        let plain = tempfile::tempdir().unwrap();
        assert!(
            classify_picked(&plain.path().join(".minecraft")).is_err(),
            "no mmc-pack.json beside it: the name alone proves nothing"
        );

        let inst = tempfile::tempdir().unwrap();
        fs::write(inst.path().join("mmc-pack.json"), CONFIRMED_PACK).unwrap();
        let saves = inst.path().join("saves");
        assert!(
            classify_picked(&saves).is_err(),
            "a missing folder Prism would not create is still not a folder"
        );
        let saved = SavedTarget {
            game_dir: saves.display().to_string(),
            validated_by: ValidatedBy::Marker,
            marker: Some("mmc-pack.json".to_string()),
        };
        assert!(revalidate(&saved).is_none());
    }

    // ── detection ──────────────────────────────────────────────────────────────

    #[test]
    fn candidate_view_is_id_and_name_only() {
        let c = Candidate {
            id: "d0".to_string(),
            launcher: "Prism Launcher".to_string(),
            name: "Hypixel".to_string(),
            game_dir: PathBuf::from("/tmp/hypixel/minecraft"),
            compat: Compat::Confirmed,
            marker: Some("mmc-pack.json".to_string()),
        };
        assert_eq!(
            c.view(),
            CandidateView {
                id: "d0".to_string(),
                name: "Hypixel".to_string(),
            }
        );
    }

    #[test]
    fn detection_exposes_only_prism_candidates() {
        let home = tempfile::tempdir().unwrap();
        let appdata = tempfile::tempdir().unwrap();
        let env = Env {
            home: home.path().to_path_buf(),
            appdata: Some(appdata.path().to_path_buf()),
        };
        let root = env.app_support().unwrap().join("PrismLauncher/instances");
        for (name, json) in [
            ("Unreadable", b"{".as_slice()),
            (
                "Hypixel",
                br#"{"components":[{"uid":"net.minecraft","version":"1.8.9"},{"uid":"net.minecraftforge","version":"11.15.1.2318"}]}"#.as_slice(),
            ),
        ] {
            let dir = root.join(name);
            fs::create_dir_all(&dir).unwrap();
            fs::write(dir.join("mmc-pack.json"), json).unwrap();
        }
        let found = detect(&env);
        assert_eq!(found.len(), 2);
        assert!(found.iter().all(|c| c.launcher == "Prism Launcher"));
        let hypixel = found.iter().find(|c| c.name == "Hypixel").unwrap();
        assert_eq!(hypixel.compat, Compat::Confirmed);
        let unreadable = found.iter().find(|c| c.name == "Unreadable").unwrap();
        assert_eq!(unreadable.compat, Compat::Unknown);
        assert!(!unreadable.compat.is_installable());
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
            (
                "AAA-wrong",
                r#"{"components":[{"uid":"net.minecraft","version":"1.20.1"},{"uid":"net.minecraftforge","version":"x"}]}"#,
            ),
            (
                "ZZZ-right",
                r#"{"components":[{"uid":"net.minecraft","version":"1.8.9"},{"uid":"net.minecraftforge","version":"11.15.1.2318"}]}"#,
            ),
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
            marker: Some("mmc-pack.json".to_string()),
        };
        save_target(home.path(), &t).unwrap();
        let back = load_target(home.path()).unwrap();
        assert_eq!(back.game_dir, t.game_dir);
        assert_eq!(back.validated_by, ValidatedBy::Marker);
        assert_eq!(back.marker.as_deref(), Some("mmc-pack.json"));
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
    fn legacy_user_confirmed_targets_are_not_revalidated() {
        let dir = tempfile::tempdir().unwrap();
        let t = SavedTarget {
            game_dir: dir.path().display().to_string(),
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
            marker: Some("mmc-pack.json".to_string()),
        };
        // The folder exists but the marker is gone - the instance was deleted or moved.
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
}
