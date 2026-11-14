package com.bedwarsqol.hud;

/**
 * The scoreboard, chat and tab list as HUD items (Ornithe): their default size and the migration of the
 * old Scoreboard Size and Tab List Size rows (Small, Medium, Large) into an item scale.
 */
public final class OverlayItems {

    /** Scale of an overlay item that was never resized or was reset: vanilla's own size. */
    public static final float DEFAULT_SCALE = 1f;

    private OverlayItems() {
    }

    /**
     * The scale a saved Scoreboard Size or Tab List Size drew at: 0 = Small (0.7), 1 = Medium (0.85),
     * 2 = Large (1.0). Out-of-range values clamp to 0..2 first, as the old sanitize did.
     */
    public static float legacySizeScale(int size) {
        int s = Math.max(0, Math.min(2, size));
        switch (s) {
            case 0: return 0.7f;
            case 1: return 0.85f;
            default: return 1.0f;
        }
    }
}
