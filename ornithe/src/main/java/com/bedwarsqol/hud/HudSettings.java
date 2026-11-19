package com.bedwarsqol.hud;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;

/**
 * The settings the HUD painters, the HUD font ({@code BedwarsQolFont}) and the overlay placement read: the player's
 * ({@link BedwarsQol#config}), unless a scope is open on the calling thread ({@link #with}), when they are that
 * scope's copy. Rewind draws the recorded HUD inside a scope holding the recorded settings, so font choice, glyph
 * widths, scale snapping, accent, backgrounds and placement all follow the recording; outside it nothing changes.
 *
 * <p>The only reader of {@code BedwarsQol.config} under {@code com.bedwarsqol.hud} and in {@code BedwarsQolFont}.
 */
public final class HudSettings {

    private static Thread scopeThread;
    private static ClientSettings scoped;

    private HudSettings() {
    }

    /** The settings in effect for the HUD on this thread: the open scope's, else the player's (may be null). */
    public static ClientSettings active() {
        if (scopeThread == Thread.currentThread()) return scoped;
        return BedwarsQol.config;
    }

    /** Runs {@code body} with {@code settings} as {@link #active} on this thread; the previous state is back after, also when it throws. */
    public static void with(ClientSettings settings, Runnable body) {
        Thread prevThread = scopeThread;
        ClientSettings prev = scoped;
        scoped = settings;
        scopeThread = Thread.currentThread();
        try {
            body.run();
        } finally {
            scoped = prev;
            scopeThread = prevThread;
        }
    }
}
