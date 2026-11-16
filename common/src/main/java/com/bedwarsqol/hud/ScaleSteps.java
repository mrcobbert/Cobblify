package com.bedwarsqol.hud;

/**
 * The sizes where Minecraft's pixel font is crispest: whole device pixels per font pixel, so {@code k / sf} GUI px per
 * font pixel for a GUI scale factor {@code sf} and {@code k >= 1}. HUD modules draw at any size (sharp between steps);
 * Edit HUD lets these steps catch a resize nearby.
 */
public final class ScaleSteps {

    private ScaleSteps() {
    }

    /** The step nearest {@code scale} when it is within {@code reach} of it, else {@code scale}. */
    public static float snap(float scale, int sf, float reach) {
        float step = Math.max(1, Math.round(scale * sf)) / (float) sf;
        return Math.abs(scale - step) <= reach ? step : scale;
    }

    /**
     * For a size a wall stopped: the step at or below {@code scale} when {@code scale} is at most {@code reach} past it,
     * else {@code scale}. It never rounds up, so the result still fits.
     */
    public static float restOnStep(float scale, int sf, float reach) {
        float step = Math.max(1, (int) Math.floor(scale * sf)) / (float) sf;
        return step <= scale && scale - step <= reach ? step : scale;
    }

    /** The largest step at or below {@code scale}, never below {@code 1 / sf}: for a size that must fit a space. */
    public static float atMost(float scale, int sf) {
        return Math.max(1, (int) Math.floor(scale * sf + 1e-3f)) / (float) sf;
    }
}
