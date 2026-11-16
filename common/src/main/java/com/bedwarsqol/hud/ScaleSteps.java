package com.bedwarsqol.hud;

/**
 * The sizes where Minecraft's pixel font is crispest: whole device pixels per font pixel, so {@code k / sf} GUI px per
 * font pixel for a GUI scale factor {@code sf} and {@code k >= 1}. HUD modules draw at any size (exact coverage
 * between steps, nearly as crisp just off a step); Edit HUD eases a resize towards these steps.
 */
public final class ScaleSteps {

    private ScaleSteps() {
    }

    /**
     * How far a whole step pulls a resize, in device pixels per font pixel: 0.3 of the way to the next step either side,
     * the same share of every gap at any GUI scale.
     */
    public static final float REACH = 0.3f;

    /** Within this many device pixels per font pixel of a step the magnet returns the step itself. */
    static final float EXACT = 0.01f;

    /**
     * {@code scale} eased towards the nearest whole step within {@link #REACH}: the size changes slowly near a step and
     * one to one elsewhere, with no jump anywhere (2u^3 - u^5 matches the pointer's value and speed at the edge of the
     * reach). So a resize lingers on crisp sizes without sticking to them; right at a step it is the step exactly.
     */
    public static float magnet(float scale, int sf) {
        float t = scale * sf;
        int k = Math.max(1, Math.round(t));
        float d = t - k;
        if (Math.abs(d) >= REACH) return scale;
        float u = d / REACH;
        float eased = REACH * u * u * u * (2f - u * u);
        return Math.abs(eased) < EXACT ? k / (float) sf : (k + eased) / sf;
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
