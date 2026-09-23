package com.bedwarsqol.stats;

import java.util.function.Supplier;

/**
 * Turns the per-tick sidebar observations into the one mode the game is in, latched for the game.
 * Pure state, no Minecraft types, so the transition rule is unit-testable; the platform detectors
 * own the scoreboard reading and feed it in here.
 *
 * <p>Two sources, in priority order. The pregame waiting lobby prints a {@code Mode: <X>} line,
 * which names the format exactly; once the game starts that line is gone, so a client that joined
 * mid-game falls back to counting nametag colours.
 *
 * <p>The subtle part is a mode we deliberately do not map - a dream variant like {@code Rush
 * Doubles}, or {@code 4v4}, which has no stats block of its own. Mapping the label to
 * {@link BedwarsMode#UNKNOWN} is not enough by itself: the active game has no label at all, so the
 * colour fallback cannot tell "never saw a label" from "saw one we do not map", and it happily
 * re-derives DOUBLES from Rush Doubles' eight two-player teams. Remembering that an unsupported
 * label was seen is what makes the overall-stats fallback actually hold for the whole game.
 */
public final class ModeLatch {

    private volatile BedwarsMode latched = BedwarsMode.UNKNOWN;
    /** Set once this game's sidebar named a format we do not map; cleared by {@link #reset()}. */
    private volatile boolean sawUnsupportedLabel;

    /** The mode latched for this game, {@link BedwarsMode#UNKNOWN} until one is known. */
    public BedwarsMode current() {
        return latched;
    }

    /** Forget everything: each Hypixel world is a fresh game. */
    public void reset() {
        latched = BedwarsMode.UNKNOWN;
        sawUnsupportedLabel = false;
    }

    /**
     * Fold one observation into the latch.
     *
     * @param modeLabel     the sidebar's {@code Mode:} value, or null/blank when it has no such line
     * @param inActiveGame  whether an active game is running (the pregame lobby is not one)
     * @param colourFallback counts nametag colours; called only when it can still decide something,
     *                       so a client that never needs it never pays for the tab-list walk
     */
    public void observe(String modeLabel, boolean inActiveGame, Supplier<BedwarsMode> colourFallback) {
        if (modeLabel != null && !modeLabel.trim().isEmpty()) {
            BedwarsMode named = SidebarModeLabel.toMode(modeLabel);
            latched = named;
            // An unmappable label is a dream mode, and saying so is what stops the colour fallback
            // from quietly re-deriving an ordinary mode for it once the label disappears.
            sawUnsupportedLabel = named == BedwarsMode.UNKNOWN;
            return;
        }
        if (latched != BedwarsMode.UNKNOWN || sawUnsupportedLabel) return;
        if (!inActiveGame || colourFallback == null) return;
        BedwarsMode byColour = colourFallback.get();
        if (byColour != BedwarsMode.UNKNOWN) latched = byColour;
    }
}
