package com.bedwarsqol.hud;

/**
 * The sizes Minecraft's pixel font can be drawn at while staying crisp: whole device pixels per font pixel, so
 * {@code k / sf} GUI px per font pixel for a GUI scale factor {@code sf} and {@code k >= 1}. HUD modules lay out at
 * these steps, so Edit HUD resizes them in the same steps (HUD tweaks scaling report, cause a).
 */
public final class ScaleSteps {

    private ScaleSteps() {
    }

    /** The step nearest {@code scale}, never below one device pixel per font pixel ({@code 1 / sf}). */
    public static float nearest(float scale, int sf) {
        return Math.max(1, Math.round(scale * sf)) / (float) sf;
    }

    /** The largest step at or below {@code scale}, never below {@code 1 / sf}: for a size that must fit a space. */
    public static float atMost(float scale, int sf) {
        return Math.max(1, (int) Math.floor(scale * sf + 1e-3f)) / (float) sf;
    }
}
