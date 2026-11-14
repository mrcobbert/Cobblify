package com.bedwarsqol.hud;

/**
 * Edit HUD's pointer and steps in screen pixels. Vanilla hands a screen the mouse in whole GUI pixels (device pixels
 * divided by the GUI scale with integer division), so at GUI scale 3 every drag step would be three screen pixels.
 * The editor reads the event's device pixel instead and keeps positions to a fraction of a GUI pixel.
 */
public final class HudGrid {

    private HudGrid() {
    }

    /** One screen pixel, in GUI px. */
    public static float step(int scale) {
        return 1f / scale;
    }

    /**
     * A mouse event's position in GUI px: {@code eventX/eventY} as LWJGL reports them (device px, origin at the
     * bottom-left) on a display {@code displayHeight} tall. The whole part is vanilla's GUI pixel.
     */
    public static float[] pointer(int eventX, int eventY, int displayHeight, int scale) {
        return new float[]{eventX / (float) scale, (displayHeight - 1 - eventY) / (float) scale};
    }

    /** {@code x} moved by {@code screenPx} screen pixels: relative and unrounded, so an off-grid spot keeps its offset. */
    public static float nudged(float x, int screenPx, int scale) {
        return x + screenPx / (float) scale;
    }
}
