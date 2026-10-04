package com.bedwarsqol.gui.sheet;

/**
 * Edit HUD's Reset All button, drawn as the sheet's Edit HUD button is: the accent hero, centred under the crosshair.
 * Coordinates are sheet units from the screen's top-left, and the rect is {x, y, w, h}.
 */
public final class EditHudChrome {

    public static final String RESET_ALL = "Reset All";

    /** The hero's height, and the room either side of its label. */
    static final float BUTTON_H = 20f;
    static final float BUTTON_PAD = 12f;
    /** The button's top, below the screen's centre: clear of the crosshair, and a spot no module or vanilla bar uses. */
    static final float DROP = 20f;

    private EditHudChrome() {
    }

    /** Reset All, centred under the crosshair, on whole device px. */
    public static float[] resetAll(TextMetrics tm, float screenW, float screenH, float unit) {
        float w = tm.width(RESET_ALL, SheetFont.HERO) + 2f * BUTTON_PAD;
        return new float[]{snap((screenW - w) / 2f, unit), snap(screenH / 2f + DROP, unit), w, BUTTON_H};
    }

    public static boolean hit(float[] r, float x, float y) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    public static void paintResetAll(Canvas c, float[] r, SheetColors.Shades accent, boolean hover, boolean pressed) {
        SheetPainter.hero(c, r[0], r[1], r[2], r[3], RESET_ALL, SheetFont.HERO, accent, hover, pressed);
    }

    /** A sheet-unit coordinate on a whole device px. */
    private static float snap(float v, float unit) {
        return Math.round(v * unit) / unit;
    }
}
