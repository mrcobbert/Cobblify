package com.bedwarsqol.rewind.watch;

/**
 * The viewer bar's opacity (G36, mockup {@code update()}): active while the mouse moved in the last 2 s, the cursor
 * is over the bar or a drag is in progress; it fades in over 120 ms and out over 200 ms. Each {@link #update}
 * integrates the step since the previous one exactly: active up to the moment 2 s after the last move, idle after.
 *
 * <p>Pure; times in milliseconds on any monotonic clock; one thread.
 */
public final class BarFade {

    public static final long IDLE_MS = 2000L;
    public static final long FADE_IN_MS = 120L;
    public static final long FADE_OUT_MS = 200L;

    private boolean moved;
    private long lastMoveMs;
    private boolean started;
    private long lastUpdateMs;
    private float alpha;

    public void mouseMoved(long nowMs) {
        moved = true;
        lastMoveMs = nowMs;
    }

    /** Advances to {@code nowMs} and returns the opacity, 0 (hidden) to 1. */
    public float update(long nowMs, boolean overBar, boolean dragging) {
        if (!started) {
            started = true;
            lastUpdateMs = nowMs;
        }
        long dt = Math.max(0, nowMs - lastUpdateMs);
        lastUpdateMs = nowMs;
        long activeMs;
        if (overBar || dragging) {
            activeMs = dt;
        } else if (!moved) {
            activeMs = 0;
        } else {
            long until = lastMoveMs + IDLE_MS;
            activeMs = Math.max(0, Math.min(dt, until - (nowMs - dt)));
        }
        long idleMs = dt - activeMs;
        float a = alpha + (float) activeMs / FADE_IN_MS;
        a = Math.min(1f, a);
        a -= (float) idleMs / FADE_OUT_MS;
        alpha = Math.max(0f, a);
        return alpha;
    }

    public float alpha() {
        return alpha;
    }

    /** True while any of the bar shows (clicks reach it). */
    public boolean visible() {
        return alpha > 0f;
    }
}
