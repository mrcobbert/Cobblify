package com.bedwarsqol.feature;

/**
 * Clicks per second for the Keystrokes HUD: how many presses landed in the last second. Timestamps
 * live in a fixed ring, so a burst never grows memory; 64 is far above any human click rate.
 */
public final class ClickCounter {

    static final long WINDOW_MS = 1000L;
    private static final int CAPACITY = 64;

    private final long[] times = new long[CAPACITY];
    private int next;
    private int filled;

    /** Records one press at {@code nowMs}. */
    public void click(long nowMs) {
        times[next] = nowMs;
        next = (next + 1) % CAPACITY;
        if (filled < CAPACITY) filled++;
    }

    /** Presses in the second ending at {@code nowMs}. */
    public int cps(long nowMs) {
        int n = 0;
        for (int i = 0; i < filled; i++) {
            long age = nowMs - times[i];
            if (age >= 0 && age < WINDOW_MS) n++;
        }
        return n;
    }
}
