package com.bedwarsqol.rewind.export;

import java.nio.ByteBuffer;

/**
 * When frame 0 of an export may be taken (Rewind Export PLAN Revision 4 and 4.1; reduced from Rev 3's settle rule).
 *
 * <p>The seek to the range start runs faster than real time and loads chunks, so frame 0 is drawn again, time held,
 * until the last {@value #STABLE_DRAWS} draws differ by at most {@value #MAX_MEAN_DIFF} mean on a {@value #GRID_W}x{@value
 * #GRID_H} luma grid; a draw that differs restarts the count. On the vanilla renderer its chunk-queue signal is an extra
 * wait only ({@code queue-wait}): a draw while the queues are busy does not count. It is never proof that a mesh is done
 * (review r3-B2), and the stable picture is not either (review r2-B1). The {@value #CAP_MS} ms cap ends it
 * ({@link State#CAPPED}, counted in the export log as {@code settleCapped}).
 *
 * <p>Mid-range frames have no hold: the session paces every step to real time instead, so a terrain change gets at
 * least the real time it had when watching at 1x (Revision 4.1). One instance per frame 0; times are milliseconds on
 * any monotonic clock. Pure.
 */
public final class SettleRule {

    /** The longest frame 0 is held. */
    public static final long CAP_MS = 2000;
    /** Draws in a row that must agree. */
    public static final int STABLE_DRAWS = 3;
    /** The largest mean luma difference (0-255) between two draws that still counts as equal. */
    public static final double MAX_MEAN_DIFF = 1.0;
    public static final int GRID_W = 64;
    public static final int GRID_H = 36;

    /** Where frame 0 is. */
    public enum State { SETTLING, SETTLED, CAPPED }

    private final long startMs;
    private float[] last;
    private int stable;
    private long lastMs;
    private boolean capped;

    private SettleRule(long startMs) {
        this.startMs = startMs;
        this.lastMs = startMs;
    }

    /** The frame-0 rule, from frame 0's first draw at {@code startMs}. */
    public static SettleRule frameZero(long startMs) {
        return new SettleRule(startMs);
    }

    /** One more draw of frame 0 (no queue signal). */
    public State offer(float[] grid, long nowMs) {
        return offer(grid, nowMs, true);
    }

    /**
     * One more draw of frame 0.
     *
     * @param grid  its luma grid ({@link #grid})
     * @param nowMs when it was drawn
     * @param ready the vanilla renderer's chunk queues read idle (true where there is no such signal)
     */
    public State offer(float[] grid, long nowMs, boolean ready) {
        lastMs = nowMs;
        if (!ready) {
            stable = 0;
        } else if (last == null || stable == 0 || diff(last, grid) > MAX_MEAN_DIFF) {
            stable = 1;
        } else {
            stable++;
        }
        last = grid;
        if (stable >= STABLE_DRAWS) {
            return State.SETTLED;
        }
        if (nowMs - startMs >= CAP_MS) {
            capped = true;
            return State.CAPPED;
        }
        return State.SETTLING;
    }

    /** Wall time from the start to the last draw offered. */
    public long waitedMs() {
        return lastMs - startMs;
    }

    /** The cap ended it. */
    public boolean capped() {
        return capped;
    }

    /**
     * The mean luma (0-255, Rec. 709 weights) of each cell of a {@value #GRID_W}x{@value #GRID_H} grid over a BGRA
     * picture of {@code w x h}, from its position; the buffer's position is left as it was.
     */
    public static float[] grid(ByteBuffer bgra, int w, int h) {
        float[] sum = new float[GRID_W * GRID_H];
        int[] count = new int[GRID_W * GRID_H];
        int base = bgra.position();
        int step = Math.max(1, Math.min(w / (GRID_W * 4), h / (GRID_H * 4))); // sample, at least 4x4 per cell
        for (int y = 0; y < h; y += step) {
            int gy = (int) ((long) y * GRID_H / h);
            int row = base + 4 * y * w;
            for (int x = 0; x < w; x += step) {
                int gx = (int) ((long) x * GRID_W / w);
                int p = row + 4 * x;
                int b = bgra.get(p) & 0xFF, g = bgra.get(p + 1) & 0xFF, r = bgra.get(p + 2) & 0xFF;
                int cell = gy * GRID_W + gx;
                sum[cell] += 0.2126f * r + 0.7152f * g + 0.0722f * b;
                count[cell]++;
            }
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] = count[i] == 0 ? 0 : sum[i] / count[i];
        }
        return sum;
    }

    /** Mean absolute difference of two grids of the same size. */
    public static double diff(float[] a, float[] b) {
        double s = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            s += Math.abs(a[i] - b[i]);
        }
        return n == 0 ? 0 : s / n;
    }
}
