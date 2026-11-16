package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.config.SettingsManager;
import net.minecraft.client.settings.KeyBinding;

/**
 * Fullbright (Ornithe build): the "Toggle Fullbright" key flips {@link ClientSettings#fullbright}, and the
 * lightmap is raised toward white by a level that fades with {@link FullbrightRules#step}. The level is
 * kept here between frames so a toggle mid-fade reverses from where it is. Render thread only.
 */
public final class Fullbright {

    private static float level;
    private static long lastNanos;

    private Fullbright() {
    }

    /** Run at the end of every client tick: a press of the key flips the module and saves the config. */
    public static void onClientTick(KeyBinding key) {
        ClientSettings c = BedwarsQol.config;
        if (c == null || key == null || !key.isPressed()) return;
        c.fullbright = !c.fullbright;
        SettingsManager.save(c);
    }

    /**
     * Moves the level toward the module's state by the time since the last call. Returns whether the
     * lightmap must be rebuilt this frame: the level moved, or it is still part way through a fade.
     */
    public static boolean advance() {
        ClientSettings c = BedwarsQol.config;
        boolean on = c != null && c.fullbright;
        long now = System.nanoTime();
        float dtMs = lastNanos == 0L ? 0f : (now - lastNanos) / 1_000_000f;
        lastNanos = now;
        float before = level;
        level = FullbrightRules.step(before, on, dtMs);
        return level != before || (level > 0f && level < 1f);
    }

    /** The current fade level, 0 (vanilla) to 1 (full white lightmap). */
    public static float level() {
        return level;
    }
}
