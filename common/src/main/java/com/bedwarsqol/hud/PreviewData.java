package com.bedwarsqol.hud;

/**
 * Edit HUD's live preview (G34): the editor shows the same data as play, so opening it changes no item's content or
 * size. An item falls back to sample data only when it has nothing live to show (D11): no active potion, no leggings
 * of a known material, a generator timer not known yet, an unknown map, or no player. Inventory (with a player),
 * Keystrokes, Session Stats and every other item are always live.
 */
public final class PreviewData {

    private PreviewData() {
    }

    /** What play has to show right now, gathered from the same sources the HUD draws from. */
    public static final class Facts {
        public final boolean hasPlayer;
        public final int potionCount;
        public final boolean wearsKnownLeggings;
        /** Seconds to the next spawn, negative while unknown (the HUD shows "--"). */
        public final int diamondSeconds;
        public final int emeraldSeconds;
        public final boolean mapKnown;

        public Facts(boolean hasPlayer, int potionCount, boolean wearsKnownLeggings, int diamondSeconds,
                     int emeraldSeconds, boolean mapKnown) {
            this.hasPlayer = hasPlayer;
            this.potionCount = potionCount;
            this.wearsKnownLeggings = wearsKnownLeggings;
            this.diamondSeconds = diamondSeconds;
            this.emeraldSeconds = emeraldSeconds;
            this.mapKnown = mapKnown;
        }
    }

    /** Whether HUD item {@code id} draws sample data in the editor, by its HUD item id. */
    public static boolean sample(String id, Facts f) {
        if (id == null || f == null) return false;
        switch (id) {
            case "potion": return !f.hasPlayer || f.potionCount <= 0;
            case "armor": return !f.hasPlayer || !f.wearsKnownLeggings;
            case "inventory": return !f.hasPlayer;
            case "diamondtimer": return f.diamondSeconds < 0;
            case "emeraldtimer": return f.emeraldSeconds < 0;
            case "heightlimit": return !f.mapKnown;
            default: return false; // keystrokes, sessionstats and any other item are live
        }
    }
}
