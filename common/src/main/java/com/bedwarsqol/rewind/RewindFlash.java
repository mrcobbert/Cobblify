package com.bedwarsqol.rewind;

/**
 * The Marker Key flash: one full-screen white rectangle whose alpha starts at {@link #PEAK_ALPHA}
 * and eases out to zero over {@link #DURATION_MS}. Nothing else is ever drawn while recording.
 * Pure: the HUD draws {@link #colorAt} with {@code drawRect} when it is non-zero.
 */
public final class RewindFlash {

    public static final int PEAK_ALPHA = 0x40;
    public static final long DURATION_MS = 250L;

    private long startMs;
    private boolean started;

    /** Alpha (0..{@link #PEAK_ALPHA}) {@code elapsedMs} after the press; 0 outside the flash. */
    public static int alpha(long elapsedMs) {
        if (elapsedMs < 0 || elapsedMs >= DURATION_MS) return 0;
        double remaining = 1.0 - elapsedMs / (double) DURATION_MS;
        // Ease-out: the fade moves fast first and settles slowly, (1 - t)^2. Rounded up so the
        // flash stays visible (alpha >= 1) for its whole duration.
        return (int) Math.ceil(PEAK_ALPHA * remaining * remaining);
    }

    /** ARGB white with {@link #alpha}, or 0 when nothing should be drawn. */
    public static int color(long elapsedMs) {
        int a = alpha(elapsedMs);
        return a == 0 ? 0 : (a << 24) | 0xFFFFFF;
    }

    /** Starts (or restarts) the flash at {@code nowMs}. */
    public void start(long nowMs) {
        startMs = nowMs;
        started = true;
    }

    public boolean isActive(long nowMs) {
        return colorAt(nowMs) != 0;
    }

    /** The colour to draw at {@code nowMs}, 0 when idle. */
    public int colorAt(long nowMs) {
        return started ? color(nowMs - startMs) : 0;
    }
}
