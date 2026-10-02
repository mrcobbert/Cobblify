package com.bedwarsqol.feature;

/**
 * Press highlight for one Keystrokes key: 1 while held, then fading linearly to 0 over the fade time
 * after release. {@link #tap} covers a click that starts and ends between two frames, so it still
 * flashes instead of never being drawn.
 */
public final class KeyFade {

    private static final long NEVER = Long.MIN_VALUE;

    private final long fadeMs;
    private boolean down;
    private long releasedAt = NEVER;

    public KeyFade(long fadeMs) {
        this.fadeMs = Math.max(1L, fadeMs);
    }

    /** A press seen as an event; draws as a release at {@code nowMs} unless the key is still held. */
    public void tap(long nowMs) {
        releasedAt = nowMs;
    }

    /** Highlight level in [0, 1] for this frame, given whether the key is held now. */
    public float level(boolean isDown, long nowMs) {
        if (isDown) {
            down = true;
            return 1f;
        }
        if (down) {
            down = false;
            releasedAt = nowMs;
        }
        if (releasedAt == NEVER) return 0f;
        long since = nowMs - releasedAt;
        if (since <= 0L) return 1f;
        if (since >= fadeMs) return 0f;
        return 1f - since / (float) fadeMs;
    }
}
