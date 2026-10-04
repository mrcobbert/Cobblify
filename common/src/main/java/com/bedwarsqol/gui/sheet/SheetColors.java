package com.bedwarsqol.gui.sheet;

import com.bedwarsqol.gui.render.GuiTheme;

/** The settings sheet's colour tokens (SHEET-SPEC §2) and the OKLab accent shades. All opaque ARGB. */
public final class SheetColors {

    private SheetColors() {
    }

    public static final int BLACK = 0xFF000000;
    public static final int BAND = 0xFF121212;
    public static final int GROUND = 0xFF1A1A1A;
    public static final int WELL = 0xFF141414;
    public static final int WELL_TOP = 0xFF0A0A0A;
    public static final int FACE = 0xFF3B3B3B;
    public static final int FACE_HOVER = 0xFF454545;
    public static final int EDGE = 0xFF646464;
    public static final int LOWER = 0xFF4A4A4A;
    public static final int DEPTH = 0xFF060606;
    public static final int KNOB = 0xFF989898;
    public static final int KNOB_HOVER = 0xFFA4A4A4;
    public static final int KNOB_EDGE = 0xFFC4C4C4;
    public static final int KNOB_LOWER = 0xFFA6A6A6;
    public static final int KNOB_DEPTH = 0xFF1C1C1C;
    public static final int PIT = 0xFF0D0D0D;
    public static final int RIM = 0xFF2E2E2E;
    public static final int GROOVE = 0xFF2C2C2C;
    public static final int TEXT_HI = 0xFFEDEDED;
    public static final int TEXT_MID = 0xFFB0B0B0;
    public static final int TEXT_LO = 0xFF7A7A7A;
    public static final int TEXT_ON = 0xFF121212;
    public static final int SH = 0xFF1B1B1B;
    public static final int SH2 = 0xFF2B2B2B;
    public static final int THUMB = 0xFF4A4A4A;
    /**
     * The sheet's left edge, bevelled like a raised face lit from the left: inside the black border, a lit line
     * (the ground plus 0.15 OKLab lightness, the step a button's edge has over its face), then a softer step.
     */
    public static final int EDGE_LIT = 0xFF3F3F3F;
    public static final int EDGE_STEP = 0xFF282828;
    /** A recess lit from the top left (the search field): its top and left walls in shadow, its bottom and right lit. */
    public static final int RECESS_SHADE = 0xFF080808;
    public static final int RECESS_LIT = 0xFF222222;

    /** One colour's raised and pressed shades, computed in OKLab exactly as the prototype's {@code shades()}. */
    public static final class Shades {
        public final int base, hl, ln, dp, sh, sh2;
        /** Text and marks drawn on the accent: dark on pale and vivid accents, light on deep ones. */
        public final int on;

        private Shades(int base) {
            this.base = 0xFF000000 | base;
            this.hl = Oklab.shade(base, 0.10, 0.90);
            this.ln = Oklab.shade(base, 0.04, 1.00);
            this.dp = Oklab.shade(base, -0.32, 0.80);
            this.sh = pressSh(base);
            this.sh2 = pressSh2(base);
            this.on = GuiTheme.darkTextOn(base) ? TEXT_ON : TEXT_HI;
        }

        public static Shades of(int rgb) {
            return new Shades(rgb);
        }
    }

    /** Vanilla Minecraft's GUI text shadow for text of colour {@code argb}: the same colour at a quarter brightness. */
    public static int textShadow(int argb) {
        return (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2);
    }

    /** Text in Minecraft's font takes vanilla's colours: white for {@link #TEXT_HI}, its grey (§7) for {@link #TEXT_MID}. */
    public static int minecraftText(int argb) {
        if (argb == TEXT_HI) return 0xFFFFFFFF;
        if (argb == TEXT_MID) return 0xFFAAAAAA;
        return argb;
    }

    /** First line of the rim's shadow on a pressed face of colour {@code rgb}. */
    public static int pressSh(int rgb) {
        return Oklab.shade(rgb, -0.20, 0.85);
    }

    /** Second line, and the left edge, of the rim's shadow on a pressed face. */
    public static int pressSh2(int rgb) {
        return Oklab.shade(rgb, -0.11, 0.92);
    }

    /** CSS {@code filter: brightness(k)} on an opaque colour (sRGB, clamped). */
    public static int brightness(int argb, float k) {
        int r = Math.min(255, Math.round(((argb >> 16) & 255) * k));
        int g = Math.min(255, Math.round(((argb >> 8) & 255) * k));
        int b = Math.min(255, Math.round((argb & 255) * k));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /**
     * A disabled control's colour: {@code filter: saturate(.15)} and {@code opacity: .4} as one layer over the flat
     * {@code backdrop} under it, in sRGB as browsers composite. Applying it to every layer of the control (keeping
     * each layer's own alpha) gives the same pixels as compositing the group, because both steps are linear.
     */
    public static int disabled(int rgb, int backdrop) {
        float s = 0.15f;
        float r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        float sr = (0.213f + 0.787f * s) * r + (0.715f - 0.715f * s) * g + (0.072f - 0.072f * s) * b;
        float sg = (0.213f - 0.213f * s) * r + (0.715f + 0.285f * s) * g + (0.072f - 0.072f * s) * b;
        float sb = (0.213f - 0.213f * s) * r + (0.715f - 0.715f * s) * g + (0.072f + 0.928f * s) * b;
        float br = (backdrop >> 16) & 255, bg = (backdrop >> 8) & 255, bb = backdrop & 255;
        int or = clamp(Math.round(br * 0.6f + clamp(Math.round(sr)) * 0.4f));
        int og = clamp(Math.round(bg * 0.6f + clamp(Math.round(sg)) * 0.4f));
        int ob = clamp(Math.round(bb * 0.6f + clamp(Math.round(sb)) * 0.4f));
        return (or << 16) | (og << 8) | ob;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }
}
