//! Stepped launch progress read from the loader's log (Weave's for Lunar, FML's for Prism),
//! and the launch's two failure verdicts: Lunar on the wrong game version, and Cobblify
//! not loading. The stages are cosmetic and fail-soft: every advance is tied to a real
//! milestone line, and the IO layer (in `main.rs` / `launch_watch.rs`) swallows any error
//! into the "fired" baseline rather than surfacing it. This module holds the pure,
//! unit-tested core: line parsing, and the verdict over one poll's facts with the clock
//! passed in.

use std::time::{Duration, SystemTime};

use serde::Serialize;

/// Furthest launch milestone visible in the current session's weave log.
/// Ordered low→high; reaching one implies every earlier one is reached too.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug)]
pub enum Milestone {
    /// Deep link fired; no milestone line seen yet (or the log is not fresh).
    Fired,
    /// `Attached Weave` - the agent is in.
    Attached,
    /// `Discovered N mod files` with N > 0 - Weave enumerated the mods. `Discovered 0 mod
    /// files` is not progress: Cobblify's jar is not in `~/.weave/mods`.
    Discovered,
    /// First `Mixing ...bedwarsqol...` - Cobblify's mixins are applying.
    Mixing,
}

/// Scan already-read log text for the highest milestone present. Pure: no IO.
/// Line order does not matter - we take the max milestone whose signature
/// appears anywhere. Only the milestone substrings are inspected; no other log
/// content is read out of this function.
pub fn milestone_from_log(log: &str) -> Milestone {
    let mut best = Milestone::Fired;
    for line in log.lines() {
        let here = if is_bedwarsqol_mixin(line) {
            Milestone::Mixing
        } else if is_discovered_mods(line) {
            Milestone::Discovered
        } else if line.contains("Attached Weave") {
            Milestone::Attached
        } else {
            continue;
        };
        if here > best {
            best = here;
        }
    }
    best
}

pub fn milestone_from_forge_log(log: &str) -> Milestone {
    let mut best = Milestone::Fired;
    for line in log.lines() {
        let here = if is_bedwarsqol_mixin(line) {
            Milestone::Mixing
        } else if line.contains("Forge Mod Loader has identified") && line.contains("mods to load") {
            Milestone::Discovered
        } else if line.contains("Forge Mod Loader version") && line.contains("loading") {
            Milestone::Attached
        } else {
            continue;
        };
        best = best.max(here);
    }
    best
}

pub fn forge_loaded(log: &str) -> bool {
    log.contains("Forge Mod Loader has successfully loaded")
}

/// `Mixing <anything>bedwarsqol<anything>` - a mixin-applying line whose target
/// class is one of ours. Order matters: `Mixing` must come before `bedwarsqol`.
fn is_bedwarsqol_mixin(line: &str) -> bool {
    match line.split_once("Mixing ") {
        Some((_, rest)) => rest.contains("bedwarsqol"),
        None => false,
    }
}

/// `Discovered <N> mod files` with N > 0 (the milestone).
fn is_discovered_mods(line: &str) -> bool {
    discovered_count(line).is_some_and(|n| n > 0)
}

/// N from Weave's `Discovered <N> mod files` (`FileManager.getMods`), for any run of digits
/// (the regex `Discovered \d+ mod files`).
fn discovered_count(line: &str) -> Option<u64> {
    let (_, rest) = line.split_once("Discovered ")?;
    let digits = rest.chars().take_while(|c| c.is_ascii_digit()).count();
    if digits == 0 || !rest[digits..].starts_with(" mod files") {
        return None;
    }
    // A count too long for u64 is still a count, and certainly not zero.
    Some(rest[..digits].parse().unwrap_or(u64::MAX))
}

/// Weave's line for the game version it is loading mappings for
/// (`MappingsHandler`: `"Loading merged mappings for " + version.versionName`).
const WEAVE_MAPPINGS: &str = "Loading merged mappings for ";
/// Weave's `GameInfo.version` throws `IllegalStateException("Could not find game version")`
/// for any version outside its enum, before discovery and mappings. 1.8.9 is in the enum,
/// so this version-free message means "not 1.8.9".
const WEAVE_UNKNOWN_VERSION: &str = "Could not find game version";
/// Weave's line once mods are mapped and it hands over to their tweakers (`AgentKt.premain`).
const WEAVE_TWEAKERS: &str = "Calling tweakers";
/// Weave's `fatalError`: `"An error occurred: " + message`, then a dialog and `exit(-1)`.
const WEAVE_FATAL: &str = "An error occurred:";

/// The fresh Weave log says Lunar is not running 1.8.9: a mappings line for another version,
/// or Weave's version-free unknown-version error. Only these lines are inspected.
pub fn weave_wrong_version(log: &str) -> bool {
    log.lines().any(|line| {
        if line.contains(WEAVE_UNKNOWN_VERSION) {
            return true;
        }
        match line.split_once(WEAVE_MAPPINGS) {
            Some((_, rest)) => {
                let version = rest.split_whitespace().next().unwrap_or("");
                !version.is_empty() && version != "1.8.9"
            }
            None => false,
        }
    })
}

/// Weave hit a fatal error (the player also sees Weave's error dialog).
pub fn weave_fatal(log: &str) -> bool {
    log.lines().any(|line| line.contains(WEAVE_FATAL))
}

/// Weave finished discovering mods (`Discovered N mod files`, any N, zero included).
pub fn weave_discovered(log: &str) -> bool {
    log.lines().any(|line| discovered_count(line).is_some())
}

/// A Cobblify mixin line is in the log (`Mixing ...bedwarsqol...`).
pub fn cobblify_mixed(log: &str) -> bool {
    log.lines().any(is_bedwarsqol_mixin)
}

/// The bar state the UI renders. `stage` is a stable key the frontend maps to
/// its own copy; `percent` is the bar width. `loaded` is the proof that Cobblify
/// loaded in this launch's game (a fresh `lobby.json` from that JVM; it latches) -
/// `settled` is only a quiet log and never implies it; the overlay-off quit waits
/// for `loaded`. `outcome`, once present, ends the launch: the frontend leaves
/// the progress view for that outcome's view.
#[derive(Clone, Serialize)]
pub struct Progress {
    pub stage: &'static str,
    pub percent: u8,
    pub loaded: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub outcome: Option<Outcome>,
}

/// Map a milestone (plus whether the log has gone quiet after mixins began) to
/// the bar state. `settled` is only ever true once mixins have started.
pub fn progress_for(milestone: Milestone, settled: bool) -> Progress {
    match milestone {
        Milestone::Fired => stage("fired", 10),
        Milestone::Attached => stage("attached", 35),
        Milestone::Discovered => stage("discovered", 60),
        Milestone::Mixing if settled => stage("settled", 100),
        Milestone::Mixing => stage("mixing", 85),
    }
}

fn stage(stage: &'static str, percent: u8) -> Progress {
    Progress {
        stage,
        percent,
        loaded: false,
        outcome: None,
    }
}

/// A Cobblify-made Prism instance's first launch: Prism is downloading Minecraft and Forge
/// (the phase after "fired", until the game's FML log appears).
pub fn progress_forge_downloading() -> Progress {
    stage("forge_downloading", 22)
}

pub fn progress_for_forge(milestone: Milestone, settled: bool) -> Progress {
    let (stage, percent) = match milestone {
        Milestone::Fired => ("forge_fired", 10),
        Milestone::Attached => ("forge_attached", 35),
        Milestone::Discovered => ("forge_discovered", 60),
        Milestone::Mixing if settled => ("forge_settled", 100),
        Milestone::Mixing => ("forge_mixing", 85),
    };
    Progress {
        stage,
        percent,
        loaded: false,
        outcome: None,
    }
}

// ── the launch verdict ──────────────────────────────────────────────────────────

/// How a launch ended when it did not end in Cobblify loading.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Outcome {
    /// Lunar is set to a game version other than 1.8.9 (from the fresh Weave log only).
    WrongVersion,
    /// Minecraft started (or died) without Cobblify loading.
    NotLoaded,
}

/// (a) Forge finished loading mods; Cobblify has this long to write `lobby.json`.
pub const FORGE_LOADED_GRACE: Duration = Duration::from_secs(30);
/// (b) Weave finished discovering mods and no Cobblify mixin followed within this.
pub const WEAVE_DISCOVERED_GRACE: Duration = Duration::from_secs(60);
/// (d) The game JVM has been alive this long with no fresh loader log.
pub const NO_LOADER_LOG_LIMIT: Duration = Duration::from_secs(120);
/// (f) The ceiling: a fresh loader log unchanged this long, and still no `lobby.json`.
pub const QUIET_LOG_CEILING: Duration = Duration::from_secs(120);

/// This launch's game JVM, as one poll saw it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GameJvm {
    /// Not identified yet, or nothing can be said this poll.
    NotSeen,
    /// Alive, born at `birth` (the OS's process start time).
    Alive { birth: SystemTime },
    /// Seen, and now proven gone with no replacement.
    Exited,
}

/// The fresh loader log (modified at or after the launch): its text and modification time.
#[derive(Clone, Copy, Debug)]
pub struct LoaderLog<'a> {
    pub text: &'a str,
    pub modified: SystemTime,
}

/// One poll's facts.
#[derive(Clone, Copy, Debug)]
pub struct Facts<'a> {
    /// A Prism (Forge) launch; otherwise Lunar (Weave).
    pub forge: bool,
    pub log: Option<LoaderLog<'a>>,
    /// Lunar only: the version guard's fresh line says the game is another version, so it did
    /// not start Weave (`guard::says_other_version`).
    pub other_version: bool,
    pub jvm: GameJvm,
    /// A fresh `~/.cobblify/lobby.json` written by this launch's game JVM: Cobblify is in.
    pub loaded: bool,
}

/// The launch's verdict across polls. Loaded and each outcome latch: once Cobblify is in,
/// no later poll can say it did not load, and an outcome never changes. Every timer starts
/// at the loader line's first sighting, the JVM's birth or the log's last change - never at
/// the click, because a first launch downloads for minutes before any of them.
#[derive(Debug, Default)]
pub struct LoadWatch {
    loaded: bool,
    outcome: Option<Outcome>,
    /// When the FML "successfully loaded" line was first seen.
    forge_loaded_at: Option<SystemTime>,
    /// When Weave's "Discovered N mod files" line was first seen.
    discovered_at: Option<SystemTime>,
}

fn since(earlier: SystemTime, now: SystemTime) -> Duration {
    now.duration_since(earlier).unwrap_or(Duration::ZERO)
}

impl LoadWatch {
    /// Cobblify is known to have loaded in this launch (latched).
    pub fn is_loaded(&self) -> bool {
        self.loaded
    }

    /// Cobblify loaded, or an outcome is decided: nothing more to watch.
    pub fn decided(&self) -> bool {
        self.loaded || self.outcome.is_some()
    }

    /// Folds one poll's facts in at `now` and returns the outcome, if the launch has one.
    pub fn observe(&mut self, facts: &Facts, now: SystemTime) -> Option<Outcome> {
        if self.loaded {
            return None;
        }
        if self.outcome.is_some() {
            return self.outcome;
        }
        if facts.loaded {
            self.loaded = true;
            return None;
        }
        self.outcome = self.decide(facts, now);
        self.outcome
    }

    fn decide(&mut self, facts: &Facts, now: SystemTime) -> Option<Outcome> {
        let text = facts.log.map(|l| l.text);
        if !facts.forge {
            if facts.other_version {
                return Some(Outcome::WrongVersion);
            }
            if let Some(text) = text {
                // Wrong version first: Weave may also log its fatal error, and the JVM dies.
                if weave_wrong_version(text) {
                    return Some(Outcome::WrongVersion);
                }
                // (c) A Weave error.
                if weave_fatal(text) {
                    return Some(Outcome::NotLoaded);
                }
            }
        }
        // (e) Died before Cobblify loaded (a wrong version was ruled out above).
        if facts.jvm == GameJvm::Exited {
            return Some(Outcome::NotLoaded);
        }
        if let Some(text) = text {
            if facts.forge {
                // (a) Forge finished loading mods, so Cobblify was absent or broken, even if
                // other mods loaded.
                if forge_loaded(text) {
                    let at = *self.forge_loaded_at.get_or_insert(now);
                    if since(at, now) >= FORGE_LOADED_GRACE {
                        return Some(Outcome::NotLoaded);
                    }
                }
            } else if weave_discovered(text) {
                // (b) Weave finished discovering mods (any N) and no Cobblify mixin followed.
                // With mods, Weave maps them between its mappings line and "Calling tweakers" -
                // on a first launch that remaps Mojang's whole jar, a minute or more of a quiet
                // log - so the grace starts once it has called the tweakers; (f) covers a hang.
                let mapping = text.contains(WEAVE_MAPPINGS) && !text.contains(WEAVE_TWEAKERS);
                if !mapping {
                    let at = *self.discovered_at.get_or_insert(now);
                    if !cobblify_mixed(text) && since(at, now) >= WEAVE_DISCOVERED_GRACE {
                        return Some(Outcome::NotLoaded);
                    }
                }
            }
        }
        match (facts.log, facts.jvm) {
            // (d) No loader output from a live game: the agent / Forge never started.
            (None, GameJvm::Alive { birth }) if since(birth, now) >= NO_LOADER_LOG_LIMIT => {
                Some(Outcome::NotLoaded)
            }
            // (f) The ceiling: the loader has gone quiet and Cobblify never reported in (a
            // mod that applied its mixins but never ticks).
            (Some(log), _) if since(log.modified, now) >= QUIET_LOG_CEILING => {
                Some(Outcome::NotLoaded)
            }
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Representative lines from a real ~/.weave/logs/latest.log run.
    const SAMPLE: &str = "\
[12:00:00] [main/INFO]: Loading Weave
[12:00:00] [main/INFO]: Attached Weave (v1.0.0)
[12:00:00] [main/INFO]: Discovered 3 mod files
[12:00:15] [main/INFO]: Mixing bedwarsqolClientMixin from mixins.bedwarsqol.json
[12:00:29] [main/INFO]: Mixing GuiPlayerTabOverlayMixin from mixins.bedwarsqol.json
";

    #[test]
    fn empty_log_is_fired() {
        assert_eq!(milestone_from_log(""), Milestone::Fired);
    }

    #[test]
    fn attached_only() {
        assert_eq!(
            milestone_from_log("[main/INFO]: Attached Weave"),
            Milestone::Attached
        );
    }

    #[test]
    fn discovered_matches_any_count() {
        assert_eq!(
            milestone_from_log("Discovered 1 mod files"),
            Milestone::Discovered
        );
        assert_eq!(
            milestone_from_log("Discovered 42 mod files"),
            Milestone::Discovered
        );
    }

    #[test]
    fn discovered_needs_digits_and_suffix() {
        assert_eq!(milestone_from_log("Discovered mod files"), Milestone::Fired);
        assert_eq!(
            milestone_from_log("Discovered 3 things"),
            Milestone::Fired
        );
    }

    #[test]
    fn full_run_reaches_mixing() {
        assert_eq!(milestone_from_log(SAMPLE), Milestone::Mixing);
    }

    #[test]
    fn bedwarsqol_mixin_requires_order_and_substring() {
        // A non-Cobblify mixin does not count.
        assert_eq!(
            milestone_from_log("Mixing SomeOtherMixin from mixins.other.json"),
            Milestone::Fired
        );
        // bedwarsqol before Mixing is not a Cobblify mixin-apply line.
        assert_eq!(
            milestone_from_log("bedwarsqol says: not Mixing anything"),
            Milestone::Fired
        );
    }

    #[test]
    fn takes_max_regardless_of_line_order() {
        let out_of_order = "\
Mixing bedwarsqolClientMixin from mixins.bedwarsqol.json
Discovered 3 mod files
Attached Weave
";
        assert_eq!(milestone_from_log(out_of_order), Milestone::Mixing);
    }

    #[test]
    fn percent_mapping_is_stepped() {
        assert_eq!(progress_for(Milestone::Fired, false).percent, 10);
        assert_eq!(progress_for(Milestone::Attached, false).percent, 35);
        assert_eq!(progress_for(Milestone::Discovered, false).percent, 60);
        assert_eq!(progress_for(Milestone::Mixing, false).percent, 85);
        assert_eq!(progress_for(Milestone::Mixing, false).stage, "mixing");
    }

    #[test]
    fn settled_only_at_mixing() {
        // settled=true only reaches 100 once mixins have begun.
        assert_eq!(progress_for(Milestone::Mixing, true).percent, 100);
        assert_eq!(progress_for(Milestone::Mixing, true).stage, "settled");
        // settled has no effect on earlier stages.
        assert_eq!(progress_for(Milestone::Discovered, true).percent, 60);
    }

    #[test]
    fn real_forge_signatures_advance_and_settle() {
        let log = "Forge Mod Loader version 11.15.1.2318 for Minecraft 1.8.9 loading\n\
Forge Mod Loader has identified 4 mods to load\n\
Mixing GuiIngameMixin from mixins.bedwarsqol.json into net.minecraft.client.gui.GuiIngame\n\
Forge Mod Loader has successfully loaded 4 mods";
        assert_eq!(milestone_from_forge_log(log), Milestone::Mixing);
        assert!(forge_loaded(log));
        assert_eq!(progress_for_forge(Milestone::Mixing, true).stage, "forge_settled");
    }

    #[test]
    fn discovered_zero_is_not_progress() {
        assert_eq!(milestone_from_log("Discovered 0 mod files"), Milestone::Fired);
        assert_eq!(
            milestone_from_log("Attached Weave\nDiscovered 0 mod files"),
            Milestone::Attached
        );
        // ...but it IS Weave having finished discovery, for the didn't-load verdict.
        assert!(weave_discovered("Discovered 0 mod files"));
        assert!(!weave_discovered("Discovered mod files"));
    }

    // ── the launch verdict ─────────────────────────────────────────────────────

    use std::time::{Duration, SystemTime};

    /// Weave 1.3.3's own strings (read from the bundled agent jar's constant pool):
    /// `"Loading merged mappings for " + versionName`, the IllegalStateException message
    /// `"Could not find game version"`, `"An error occurred: " + message`.
    const WEAVE_ON_1_21: &str = "\
[12:00:00] [main/INFO]: Attached Weave
[12:00:00] [main/INFO]: Loading merged mappings for 1.21.11
[12:00:01] [main/INFO]: Discovered 1 mod files
[12:00:01] [main/FATAL]: An error occurred: Mod bedwarsqol was mapped in namespace mcp, which is not available!
";
    const WEAVE_UNKNOWN_VERSION_LOG: &str = "\
[12:00:00] [main/INFO]: Attached Weave
java.lang.IllegalStateException: Could not find game version
\tat net.weavemc.internals.GameInfo.getVersion(GameInfo.kt:60)
";
    const WEAVE_ON_1_8_9: &str = "\
[12:00:00] [main/INFO]: Attached Weave
[12:00:00] [main/INFO]: Loading merged mappings for 1.8.9
[12:00:01] [main/INFO]: Discovered 1 mod files
";

    fn t0() -> SystemTime {
        SystemTime::UNIX_EPOCH + Duration::from_secs(1_800_000_000)
    }

    fn at(secs: u64) -> SystemTime {
        t0() + Duration::from_secs(secs)
    }

    fn lunar<'a>(text: Option<&'a str>, modified: SystemTime, jvm: GameJvm) -> Facts<'a> {
        Facts {
            forge: false,
            log: text.map(|text| LoaderLog { text, modified }),
            other_version: false,
            jvm,
            loaded: false,
        }
    }

    fn forge<'a>(text: Option<&'a str>, modified: SystemTime, jvm: GameJvm) -> Facts<'a> {
        Facts {
            forge: true,
            ..lunar(text, modified, jvm)
        }
    }

    fn alive(birth_secs: u64) -> GameJvm {
        GameJvm::Alive { birth: at(birth_secs) }
    }

    /// With the version guard, Weave writes nothing on another version and the game runs on:
    /// the guard's line alone is the wrong version, with no Weave log and a live game.
    #[test]
    fn the_guards_other_version_line_is_the_wrong_version() {
        let mut w = LoadWatch::default();
        let facts = Facts { other_version: true, ..lunar(None, at(1), alive(0)) };
        assert_eq!(w.observe(&facts, at(1)), Some(Outcome::WrongVersion));
        let mut f = LoadWatch::default();
        let forge_facts = Facts { other_version: true, ..forge(None, at(1), alive(0)) };
        assert_eq!(f.observe(&forge_facts, at(1)), None, "never a Prism verdict");
    }

    #[test]
    fn a_mappings_line_for_another_version_is_the_wrong_version() {
        assert!(weave_wrong_version(WEAVE_ON_1_21));
        assert!(!weave_wrong_version(WEAVE_ON_1_8_9));
        assert!(!weave_wrong_version("Loading merged mappings for "));
        let mut w = LoadWatch::default();
        let outcome = w.observe(&lunar(Some(WEAVE_ON_1_21), at(1), alive(0)), at(1));
        assert_eq!(outcome, Some(Outcome::WrongVersion), "ahead of the fatal error on the same log");
    }

    #[test]
    fn weaves_version_free_error_is_the_wrong_version_even_when_the_game_died() {
        assert!(weave_wrong_version(WEAVE_UNKNOWN_VERSION_LOG));
        let mut w = LoadWatch::default();
        let outcome = w.observe(&lunar(Some(WEAVE_UNKNOWN_VERSION_LOG), at(1), GameJvm::Exited), at(1));
        assert_eq!(outcome, Some(Outcome::WrongVersion));
    }

    #[test]
    fn the_wrong_version_is_never_read_from_a_forge_log() {
        let mut w = LoadWatch::default();
        assert_eq!(w.observe(&forge(Some(WEAVE_ON_1_21), at(1), alive(0)), at(1)), None);
    }

    #[test]
    fn a_fresh_lobby_json_from_the_game_is_loaded_and_latches() {
        let mut w = LoadWatch::default();
        let mut facts = lunar(Some(WEAVE_ON_1_8_9), at(5), alive(0));
        facts.loaded = true;
        assert_eq!(w.observe(&facts, at(5)), None);
        assert!(w.decided());
        // Nothing later can turn a loaded launch into "didn't load": not an exit, not silence.
        let later = lunar(Some("An error occurred: x"), at(5), GameJvm::Exited);
        assert_eq!(w.observe(&later, at(1000)), None);
    }

    /// (a), round 1's case: a fresh FML log that loaded OTHER mods, no Cobblify, the game
    /// alive past the grace.
    #[test]
    fn a_forge_game_that_loaded_other_mods_without_cobblify_did_not_load() {
        let log = "\
Forge Mod Loader version 11.15.1.2318 for Minecraft 1.8.9 loading
Forge Mod Loader has identified 5 mods to load
Forge Mod Loader has successfully loaded 5 mods
";
        let mut w = LoadWatch::default();
        // The line is first seen at 40 s; the grace runs from there, not from the click.
        assert_eq!(w.observe(&forge(Some(log), at(40), alive(10)), at(40)), None);
        assert_eq!(w.observe(&forge(Some(log), at(40), alive(10)), at(69)), None);
        assert_eq!(
            w.observe(&forge(Some(log), at(40), alive(10)), at(70)),
            Some(Outcome::NotLoaded)
        );
        // Latched.
        let mut loaded = forge(Some(log), at(40), alive(10));
        loaded.loaded = true;
        assert_eq!(w.observe(&loaded, at(71)), Some(Outcome::NotLoaded));
    }

    /// (b): Weave discovered its mods (here, none of them Cobblify's) and no Cobblify mixin
    /// followed for 60 s.
    #[test]
    fn weave_discovery_without_a_cobblify_mixin_did_not_load() {
        let log = "Attached Weave\nDiscovered 0 mod files\n";
        let mut w = LoadWatch::default();
        assert_eq!(w.observe(&lunar(Some(log), at(3), alive(0)), at(3)), None);
        assert_eq!(w.observe(&lunar(Some(log), at(3), alive(0)), at(62)), None);
        assert_eq!(
            w.observe(&lunar(Some(log), at(3), alive(0)), at(63)),
            Some(Outcome::NotLoaded)
        );
    }

    /// (b) waits out a first launch's remap: from the mappings line to "Calling tweakers" no
    /// grace runs, and it starts at the tweakers line.
    #[test]
    fn the_discovery_grace_starts_after_weave_has_mapped_the_mods() {
        let mapping = "Attached Weave\nDiscovered 1 mod files\nLoading merged mappings for 1.8.9\n";
        let mut w = LoadWatch::default();
        assert_eq!(w.observe(&lunar(Some(mapping), at(3), alive(0)), at(3)), None);
        assert_eq!(w.observe(&lunar(Some(mapping), at(3), alive(0)), at(100)), None, "remapping");
        let called = "Attached Weave\nDiscovered 1 mod files\nLoading merged mappings for 1.8.9\nCalling tweakers\n";
        assert_eq!(w.observe(&lunar(Some(called), at(101), alive(0)), at(101)), None);
        assert_eq!(w.observe(&lunar(Some(called), at(101), alive(0)), at(160)), None);
        assert_eq!(
            w.observe(&lunar(Some(called), at(101), alive(0)), at(161)),
            Some(Outcome::NotLoaded),
            "60 s after the tweakers with no Cobblify mixin"
        );
    }

    /// (b) does not fire once Cobblify's mixins applied; that case waits for (f).
    #[test]
    fn a_cobblify_mixin_holds_off_the_discovery_grace() {
        let log = "Discovered 1 mod files\nMixing GuiIngameMixin from mixins.bedwarsqol.json\n";
        let mut w = LoadWatch::default();
        assert_eq!(w.observe(&lunar(Some(log), at(100), alive(0)), at(3)), None);
        assert_eq!(w.observe(&lunar(Some(log), at(100), alive(0)), at(200)), None);
    }

    /// (c): Weave's fatal error.
    #[test]
    fn a_weave_error_did_not_load() {
        let log = "Attached Weave\nAn error occurred: Could not find vanilla jar\n";
        let mut w = LoadWatch::default();
        assert_eq!(w.observe(&lunar(Some(log), at(1), alive(0)), at(1)), Some(Outcome::NotLoaded));
    }

    /// (d): a live game JVM with no fresh loader log 120 s after its birth: the agent (or
    /// Forge) is not attached. Timed from the birth, not the click.
    #[test]
    fn a_live_game_with_no_loader_log_did_not_load() {
        let mut w = LoadWatch::default();
        // Before the JVM exists (a first launch downloading) nothing runs.
        assert_eq!(w.observe(&lunar(None, at(0), GameJvm::NotSeen), at(500)), None);
        assert_eq!(w.observe(&lunar(None, at(0), alive(500)), at(619)), None);
        assert_eq!(
            w.observe(&lunar(None, at(0), alive(500)), at(620)),
            Some(Outcome::NotLoaded)
        );
        let mut prism = LoadWatch::default();
        assert_eq!(prism.observe(&forge(None, at(0), alive(0)), at(120)), Some(Outcome::NotLoaded));
    }

    /// (e): the JVM exits before Cobblify loaded.
    #[test]
    fn a_game_that_dies_before_loading_did_not_load() {
        let mut w = LoadWatch::default();
        let log = "Attached Weave\nLoading merged mappings for 1.8.9\n";
        assert_eq!(w.observe(&lunar(Some(log), at(2), GameJvm::Exited), at(3)), Some(Outcome::NotLoaded));
        let mut prism = LoadWatch::default();
        assert_eq!(prism.observe(&forge(None, at(0), GameJvm::Exited), at(3)), Some(Outcome::NotLoaded));
    }

    /// (f), round 2's case: `Discovered 1 mod files` + Cobblify's mixins, but no lobby.json -
    /// a mod that applies its mixins and never ticks. 120 s after the log last changed.
    #[test]
    fn mixins_without_a_tick_hit_the_ceiling() {
        let log = "\
[12:00:00] [main/INFO]: Attached Weave
[12:00:00] [main/INFO]: Loading merged mappings for 1.8.9
[12:00:01] [main/INFO]: Discovered 1 mod files
[12:00:15] [main/INFO]: Mixing bedwarsqolClientMixin from mixins.bedwarsqol.json
";
        let mut w = LoadWatch::default();
        assert_eq!(w.observe(&lunar(Some(log), at(15), alive(0)), at(16)), None);
        assert_eq!(w.observe(&lunar(Some(log), at(15), alive(0)), at(134)), None);
        assert_eq!(
            w.observe(&lunar(Some(log), at(15), alive(0)), at(135)),
            Some(Outcome::NotLoaded)
        );
    }

    /// (f) runs from the log's LAST change: a log still being written holds it off.
    #[test]
    fn the_ceiling_restarts_while_the_log_changes() {
        let log = "Forge Mod Loader version 11.15.1.2318 for Minecraft 1.8.9 loading\n";
        let mut w = LoadWatch::default();
        assert_eq!(w.observe(&forge(Some(log), at(100), alive(0)), at(200)), None);
        assert_eq!(w.observe(&forge(Some(log), at(200), alive(0)), at(319)), None);
        assert_eq!(w.observe(&forge(Some(log), at(200), alive(0)), at(320)), Some(Outcome::NotLoaded));
    }

    #[test]
    fn outcomes_serialize_as_snake_case_and_are_absent_on_plain_progress() {
        assert_eq!(serde_json::to_value(Outcome::WrongVersion).unwrap(), "wrong_version");
        assert_eq!(serde_json::to_value(Outcome::NotLoaded).unwrap(), "not_loaded");
        let plain = serde_json::to_value(progress_for(Milestone::Fired, false)).unwrap();
        assert!(plain.get("outcome").is_none());
        let mut ended = progress_for(Milestone::Mixing, false);
        ended.outcome = Some(Outcome::NotLoaded);
        assert_eq!(serde_json::to_value(ended).unwrap()["outcome"], "not_loaded");
        let dl = progress_forge_downloading();
        assert_eq!((dl.stage, dl.percent), ("forge_downloading", 22));
    }
}
