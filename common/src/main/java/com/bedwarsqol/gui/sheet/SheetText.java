package com.bedwarsqol.gui.sheet;

/** Where WebKit puts text vertically. Pure, so the rule is tested once and shared by both trees' canvases. */
public final class SheetText {

    private SheetText() {
    }

    /** Ascent in device px at {@code sizePx}, rounded as WebKit rounds font metrics. */
    public static int ascent(FontMetrics m, float sizePx) {
        return Math.round(m.ascent * sizePx / m.upm);
    }

    public static int descent(FontMetrics m, float sizePx) {
        return Math.round(m.descent * sizePx / m.upm);
    }

    /** Baseline y in device px for a line box at {@code topPx}, {@code heightPx} tall (CSS half-leading). */
    public static float baseline(FontMetrics m, float sizePx, float topPx, float heightPx) {
        int a = ascent(m, sizePx), d = descent(m, sizePx);
        return Math.round(topPx + (heightPx - (a + d)) / 2f + a);
    }
}
