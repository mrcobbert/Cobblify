package com.bedwarsqol.gui.sheet;

import java.util.Locale;

/**
 * The colour picker's arithmetic: HSV to and from RGB, hex text, and the pixels of its gradient fields. The fields
 * are computed here, one colour per device pixel, and drawn through {@link Canvas#pixels}, so every platform shows
 * the same pixels. Hue, saturation and brightness are all 0..1.
 */
public final class ColorPick {

    private ColorPick() {
    }

    /** What a gradient field shows. */
    public static final int FIELD_SV = 0, FIELD_HUE = 1;

    public static int rgb(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        float x = h * 6f;
        int i = (int) x;
        float f = x - i, p = v * (1f - s), q = v * (1f - s * f), t = v * (1f - s * (1f - f));
        float r, g, b;
        switch (i % 6) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default: r = v; g = p; b = q; break;
        }
        return (Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(b * 255f);
    }

    /** {hue, saturation, brightness} of an RGB colour; a grey's hue and black's saturation are 0. */
    public static float[] hsv(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0f;
        if (d > 0f) {
            if (max == r) h = ((g - b) / d + (g < b ? 6f : 0f)) / 6f;
            else if (max == g) h = ((b - r) / d + 2f) / 6f;
            else h = ((r - g) / d + 4f) / 6f;
        }
        return new float[]{h, max == 0f ? 0f : d / max, max};
    }

    /** {@code #RRGGBB}, upper case. */
    public static String hex(int rgb) {
        String s = Integer.toHexString(0x1000000 | (rgb & 0xFFFFFF)).substring(1);
        return "#" + s.toUpperCase(Locale.ROOT);
    }

    /** The colour of hex text ({@code RGB} or {@code RRGGBB}, with or without {@code #}), or -1 when it is not one. */
    public static int parse(String text) {
        String s = text.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 3 && s.length() != 6) return -1;
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            int d = Character.digit(s.charAt(i), 16);
            if (d < 0) return -1;
            v = s.length() == 3 ? (v << 8) | (d * 17) : (v << 4) | d;
        }
        return v;
    }

    /**
     * One gradient field's pixels, {@code pw} by {@code ph}, as opaque RGB rows from the top. The saturation and
     * brightness square runs saturation left to right and brightness bottom to top at hue {@code h}; the hue bar runs the
     * spectrum left to right.
     */
    public static void field(int kind, float h, int[] out, int pw, int ph) {
        float dx = pw > 1 ? 1f / (pw - 1) : 0f, dy = ph > 1 ? 1f / (ph - 1) : 0f;
        if (kind == FIELD_SV) {
            for (int j = 0; j < ph; j++) for (int i = 0; i < pw; i++) out[j * pw + i] = rgb(h, i * dx, 1f - j * dy);
            return;
        }
        for (int i = 0; i < pw; i++) {
            float t = i * dx;
            int c = rgb(t, 1f, 1f);
            for (int j = 0; j < ph; j++) out[j * pw + i] = c;
        }
    }
}
