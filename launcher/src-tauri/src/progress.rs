//! Stepped launch progress read from Weave's log. Purely cosmetic and
//! fail-soft: every advance is tied to a real milestone line, and the IO layer
//! (in `main.rs`) swallows any error into the "fired" baseline rather than
//! surfacing it. This module holds the pure, unit-tested parsing core.

use serde::Serialize;

/// Furthest launch milestone visible in the current session's weave log.
/// Ordered low→high; reaching one implies every earlier one is reached too.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug)]
pub enum Milestone {
    /// Deep link fired; no milestone line seen yet (or the log is not fresh).
    Fired,
    /// `Attached Weave` - the agent is in.
    Attached,
    /// `Discovered N mod files` - Weave enumerated the mods.
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

/// `Discovered <N> mod files` for any run of digits (the regex
/// `Discovered \d+ mod files`).
fn is_discovered_mods(line: &str) -> bool {
    let Some((_, rest)) = line.split_once("Discovered ") else {
        return false;
    };
    let digits = rest.chars().take_while(|c| c.is_ascii_digit()).count();
    digits > 0 && rest[digits..].starts_with(" mod files")
}

/// The bar state the UI renders. `stage` is a stable key the frontend maps to
/// its own copy; `percent` is the bar width.
#[derive(Clone, Serialize)]
pub struct Progress {
    pub stage: &'static str,
    pub percent: u8,
}

/// Map a milestone (plus whether the log has gone quiet after mixins began) to
/// the bar state. `settled` is only ever true once mixins have started.
pub fn progress_for(milestone: Milestone, settled: bool) -> Progress {
    match milestone {
        Milestone::Fired => Progress { stage: "fired", percent: 10 },
        Milestone::Attached => Progress { stage: "attached", percent: 35 },
        Milestone::Discovered => Progress { stage: "discovered", percent: 60 },
        Milestone::Mixing if settled => Progress { stage: "settled", percent: 100 },
        Milestone::Mixing => Progress { stage: "mixing", percent: 85 },
    }
}

pub fn progress_for_forge(milestone: Milestone, settled: bool) -> Progress {
    let (stage, percent) = match milestone {
        Milestone::Fired => ("forge_fired", 10),
        Milestone::Attached => ("forge_attached", 35),
        Milestone::Discovered => ("forge_discovered", 60),
        Milestone::Mixing if settled => ("forge_settled", 100),
        Milestone::Mixing => ("forge_mixing", 85),
    };
    Progress { stage, percent }
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
}
