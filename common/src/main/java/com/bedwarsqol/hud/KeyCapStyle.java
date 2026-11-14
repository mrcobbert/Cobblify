package com.bedwarsqol.hud;

import com.bedwarsqol.gui.render.GuiTheme;

/**
 * Keystrokes cap colours for a press level from 0 (idle) to 1 (held): idle caps are Lunar's see-through black with the
 * Background on and absent with it off; a pressed cap is the solid accent with its letter white, or near-black on an
 * accent too light for white. Labels have a shadow only on bare caps that are not pressed.
 */
public final class KeyCapStyle {

    private static final int IDLE_FILL = HudBackground.FILL;
    private static final int TEXT_IDLE = 0xFFFFFFFF;
    private static final int TEXT_ON_LIGHT = 0xFF121212;

    private KeyCapStyle() {
    }

    public static int fill(float level, boolean background, int accent) {
        return lerp(background ? IDLE_FILL : 0x00000000, 0xFF000000 | accent, level);
    }

    public static int text(float level, int accent) {
        return lerp(TEXT_IDLE, onAccent(accent), level);
    }

    /** A pressed cap's text colour: white, unless white falls under 2:1 on the accent (pale, yellow, white). */
    public static int onAccent(int accent) {
        return GuiTheme.contrast(accent & 0xFFFFFF, 0xFFFFFF) < 2.0 ? TEXT_ON_LIGHT : TEXT_IDLE;
    }

    public static boolean shadow(float level, boolean background) {
        return !background && level < 0.5f;
    }

    /** ARGB blend, each channel rounded (as GuiRender.lerpColor). */
    static int lerp(int c0, int c1, float t) {
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        int a0 = c0 >>> 24 & 0xFF, r0 = c0 >> 16 & 0xFF, g0 = c0 >> 8 & 0xFF, b0 = c0 & 0xFF;
        int a1 = c1 >>> 24 & 0xFF, r1 = c1 >> 16 & 0xFF, g1 = c1 >> 8 & 0xFF, b1 = c1 & 0xFF;
        int a = Math.round(a0 + (a1 - a0) * t), r = Math.round(r0 + (r1 - r0) * t);
        int g = Math.round(g0 + (g1 - g0) * t), b = Math.round(b0 + (b1 - b0) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
