package com.bedwarsqol.feature;

/**
 * Fullbright, a Visuals module of the Ornithe build: the world drawn at full brightness. Off by default.
 *
 * <p>A retained level 0..1 fades toward 1 (on) or 0 (off) at a fixed rate, so a toggle mid-fade reverses
 * from where the level is. Each lightmap texel is raised toward white by that level.
 */
public final class FullbrightRules {

    public static final boolean DEFAULT_ENABLED = false;
    /** The vanilla key binding's name; options.txt stores it as {@code key_Toggle Fullbright}. Unbound by default. */
    public static final String TOGGLE_KEY_NAME = "Toggle Fullbright";
    /** A full fade, 0 to 1 or back, takes this long. */
    public static final float FADE_MS = 300f;

    private FullbrightRules() {
    }

    /** The level after {@code dtMs}: moves toward 1 when on, 0 when off, at 1 per {@link #FADE_MS}; clamped to 0..1. */
    public static float step(float currentLevel, boolean on, float dtMs) {
        float level = clamp(currentLevel);
        if (!(dtMs > 0f)) return level;
        float delta = dtMs / FADE_MS;
        return on ? Math.min(1f, level + delta) : Math.max(0f, level - delta);
    }

    /** One lightmap texel raised toward white by {@code level}: 0 returns it unchanged, 1 is white; alpha kept. */
    public static int lift(int argb, float level) {
        float l = clamp(level);
        if (l == 0f) return argb;
        int r = liftChannel((argb >> 16) & 0xFF, l);
        int g = liftChannel((argb >> 8) & 0xFF, l);
        int b = liftChannel(argb & 0xFF, l);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int liftChannel(int c, float level) {
        return Math.min(255, c + (int) ((255 - c) * level + 0.5f));
    }

    private static float clamp(float v) {
        if (!(v > 0f)) return 0f;
        return v > 1f ? 1f : v;
    }
}
