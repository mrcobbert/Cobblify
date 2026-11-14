package com.bedwarsqol.hud;

/**
 * Lunar's Keystrokes geometry, as the approved sandbox draws it: 18 px caps with 1 px gaps, WASD, the mouse row under
 * them and a 10 px space bar at the bottom; 56 x 67 module units (multiply by the draw scale).
 */
public final class KeystrokesLayout {

    public static final int CAP = 18;
    public static final int GAP = 1;
    public static final int WIDTH = 3 * CAP + 2 * GAP;
    public static final int SPACE_H = 10;
    public static final int HEIGHT = 3 * (CAP + GAP) + SPACE_H;

    private static final float HALF = (WIDTH - GAP) / 2f;

    /** {x, y, w, h} of each cap, in the order W, A, S, D, Space, LMB, RMB. */
    public static final float[][] CAPS = {
            {CAP + GAP, 0, CAP, CAP},
            {0, CAP + GAP, CAP, CAP},
            {CAP + GAP, CAP + GAP, CAP, CAP},
            {2 * (CAP + GAP), CAP + GAP, CAP, CAP},
            {0, 3 * (CAP + GAP), WIDTH, SPACE_H},
            {0, 2 * (CAP + GAP), HALF, CAP},
            {HALF + GAP, 2 * (CAP + GAP), HALF, CAP}};
    public static final String[] LABELS = {"W", "A", "S", "D", "", "LMB", "RMB"};

    /** The space bar's line {x, y, w, h}: 36% of the width, centred, halfway down the bar; its shadow is 1 px right and down. */
    public static final float[] SPACE_LINE = {
            (WIDTH - Math.round(WIDTH * 0.36f)) / 2f, 3 * (CAP + GAP) + SPACE_H / 2, Math.round(WIDTH * 0.36f), 1};
    public static final int SPACE_LINE_SHADOW = 0xFF3F3F3F;

    private KeystrokesLayout() {
    }

    /** Left of a label centred in {@code cap}; {@code trailing} is the empty column the font leaves after it. */
    public static float labelX(float[] cap, float advance, float trailing) {
        return cap[0] + (cap[2] - (advance - trailing)) / 2f;
    }

    /** Top of a label in {@code cap}: Minecraft's 7 px capitals centred, rounded up. */
    public static float labelY(float[] cap) {
        return cap[1] + (float) Math.floor((cap[3] - 7) / 2f);
    }
}
