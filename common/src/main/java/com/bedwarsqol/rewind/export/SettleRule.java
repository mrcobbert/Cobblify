package com.bedwarsqol.rewind.export;

import java.nio.ByteBuffer;

/**
 * When a held export frame may be taken (Rewind Export PLAN Solution 2 "Settle rule", reviews I6 and r2-B1).
 *
 * <p>After a tick that applied a terrain packet of a held class, and before frame 0, the same frame is drawn again
 * until it settles. {@link #bestEffort}: at least {@code holdMs} of wall time has passed since that tick <b>and</b> the
 * last {@value #STABLE_DRAWS} draws differ by at most {@value #MAX_MEAN_DIFF} mean on a {@value #GRID_W}x{@value
 * #GRID_H} luma grid; a draw that differs restarts the count. This is best effort only: a mesh slower than the hold is
 * taken stale. {@link #readySignal}: the renderer says its terrain is ready, then {@value #STABLE_DRAWS} stable draws;
 * no hold. Either way the {@value #CAP_MS} ms cap ends it ({@link State#CAPPED}, counted in the export log).
 *
 * <p>The hold per packet class is measured, not guessed (PLAN Phase 2 check K10); see the HANDOFF Phase 2 block for the
 * numbers. One instance per held frame; times are milliseconds on any monotonic clock. Pure.
 */
public final class SettleRule {

    /** The longest a frame is held. */
    public static final long CAP_MS = 2000;
    /** Draws in a row that must agree. */
    public static final int STABLE_DRAWS = 3;
    /** The largest mean luma difference (0-255) between two draws that still counts as equal. */
    public static final double MAX_MEAN_DIFF = 1.0;
    public static final int GRID_W = 64;
    public static final int GRID_H = 36;

    /*
     * Holds per packet class: 2 x the largest mesh latency L measured, at least 50 ms (PLAN Phase 2 check K10,
     * 2026-11-22). Mac (Apple silicon), cobdev --prod client (Argentum 1.0.3-beta.8, Sarcio, Pylon, Slipstream), smoke
     * world fixture, window path, playhead stepped onto each tick and held 2.2 s, two clean runs: chunk data
     * (0x21/0x26) n=14 each, max 220 / 219 ms, median 32 / 36.5 ms; block changes (0x22/0x23) n=6 each, max 17 / 124
     * ms, median 16 / 16 ms. Best effort on this machine only: the block maximum moved 7x between two runs, and a slower
     * machine or a bigger backlog can exceed the hold without the cap firing (review r2-B1).
     */
    /** Hold after a tick with chunk data (S21 0x21, S26 0x26). */
    public static final long HOLD_CHUNK_MS = 440;
    /** Hold after a tick with block changes (S22 0x22, S23 0x23). */
    public static final long HOLD_BLOCK_MS = 248;

    /** Where a held frame is. */
    public enum State { SETTLING, SETTLED, CAPPED }

    private final boolean signal;
    private final long holdMs;
    private final long startMs;
    private float[] last;
    private int stable;
    private long lastMs;
    private boolean capped;

    private SettleRule(boolean signal, long holdMs, long startMs) {
        this.signal = signal;
        this.holdMs = Math.max(0, holdMs);
        this.startMs = startMs;
        this.lastMs = startMs;
    }

    /** The best-effort rule: {@code holdMs} after {@code startMs} (the terrain tick's wall time), then stability. */
    public static SettleRule bestEffort(long holdMs, long startMs) {
        return new SettleRule(false, holdMs, startMs);
    }

    /** The ready-signal rule: no hold; stable draws count only while the renderer says ready. */
    public static SettleRule readySignal(long startMs) {
        return new SettleRule(true, 0, startMs);
    }

    /** One more draw of the held frame (best effort: the renderer's readiness is not known). */
    public State offer(float[] grid, long nowMs) {
        return offer(grid, nowMs, true);
    }

    /**
     * One more draw of the held frame.
     *
     * @param grid  its luma grid ({@link #grid})
     * @param nowMs when it was drawn
     * @param ready the renderer's readiness signal (ignored by the best-effort rule)
     */
    public State offer(float[] grid, long nowMs, boolean ready) {
        lastMs = nowMs;
        if (signal && !ready) {
            stable = 0;
            last = grid;
        } else if (last == null || stable == 0 || diff(last, grid) > MAX_MEAN_DIFF) {
            stable = 1;
            last = grid;
        } else {
            stable++;
            last = grid;
        }
        if (stable >= STABLE_DRAWS && nowMs - startMs >= holdMs) {
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
