package com.bedwarsqol.gui;

/**
 * Hover highlight for one control: eases toward 1 while hovered and back toward 0 after, so a hover
 * colour blends in and out instead of snapping. Call {@link #update} once per frame.
 */
public final class HoverFade {

    private static final long NEVER = Long.MIN_VALUE;

    private final long inMs;
    private final long outMs;
    private float progress;
    private long lastMs = NEVER;

    public HoverFade(long inMs, long outMs) {
        this.inMs = Math.max(1L, inMs);
        this.outMs = Math.max(1L, outMs);
    }

    /** Advances to {@code nowMs} and returns the eased highlight level in [0, 1]. */
    public float update(boolean hovered, long nowMs) {
        if (lastMs != NEVER) {
            long dt = Math.max(0L, nowMs - lastMs);
            progress = hovered
                    ? Math.min(1f, progress + dt / (float) inMs)
                    : Math.max(0f, progress - dt / (float) outMs);
        }
        lastMs = nowMs;
        return progress * progress * (3f - 2f * progress); // smoothstep
    }
}
