package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;

import java.util.Arrays;

/**
 * The recorded tick table (PLAN "Tick table", review I3; TASK B12): T[k], the recorded head time of client tick k,
 * on the recording's clock (µs). Every tick number in playback refers to it ({@link Timeline#tickOf},
 * {@link Timeline#timeOfTick}, {@link TickFeeder}, {@link TickClock}, the self state binding).
 *
 * <ul>
 *   <li><b>v2</b> ({@link Kind#V2}): the TICK_HEAD times as they are, one entry per record, nothing inserted and
 *       nothing dropped (heads before the origin excepted). A freeze is a long gap with no tick in it; the catch-up
 *       ticks after it are separate entries a few hundred µs apart. Each entry carries H[k], the handled count at that
 *       head summed over its segment, and whether the order is known there (the head's
 *       {@link RewindFormat#TICK_HEAD_ORDER_UNKNOWN} flag clear).</li>
 *   <li><b>v1</b> ({@link Kind#V1}), reconstructed: each TICK record time minus {@value #V1_TICK_RUN_US} µs (a TICK
 *       is written at the end of its tick; 1 ms is the estimate of a tick's run). A gap over
 *       {@value #V1_FILL_OVER_US} µs is filled with {@code round(gap / 50 ms)} evenly spaced ticks, because v1 wrote
 *       nothing for a tick in which nothing changed. A real freeze in a v1 game is therefore replayed as ticks (stated
 *       limitation). No handled counts.</li>
 *   <li>Before the first and after the last entry the table continues at 50 ms; tick 0 is the first of those times
 *       at or after the origin, so tick numbers start at the recording's start as R2's did. A recording with no tick
 *       times is R2's grid {@code origin + k x 50 ms} ({@link Kind#GRID}). Continuation ticks have no handled count
 *       (the feed uses the arrival rule there).</li>
 * </ul>
 *
 * Immutable; reading allocates nothing.
 */
public final class TickTable {

    /** The continuation step, and R2's grid. */
    public static final long TICK_US = 50_000L;
    /** v1: a TICK record is written this long after its tick's head (estimate). */
    public static final long V1_TICK_RUN_US = 1_000L;
    /** v1: a gap between two tick times longer than this is filled with 50 ms ticks. */
    public static final long V1_FILL_OVER_US = 75_000L;

    public enum Kind { GRID, V1, V2 }

    private final Kind kind;
    /** Entries, non-decreasing; at least one (the origin for a grid). */
    private final long[] t;
    private final int n;
    /** The tick number of entry 0. */
    private final long m;
    /** v2: H at each entry (summed over the entry's segment), its segment and whether the order is known. */
    private final long[] handled;
    private final int[] segment;
    private final boolean[] known;

    private TickTable(Kind kind, long originUs, long[] t, int n, long[] handled, int[] segment, boolean[] known) {
        this.kind = kind;
        this.t = t;
        this.n = n;
        this.m = Math.floorDiv(t[0] - originUs, TICK_US);
        this.handled = handled;
        this.segment = segment;
        this.known = known;
    }

    /** R2's grid: tick k is {@code origin + k x 50 ms}. */
    public static TickTable grid(long originUs) {
        return new TickTable(Kind.GRID, originUs, new long[]{originUs}, 1, null, null, null);
    }

    /**
     * v1: rebuilt from the TICK record times (ascending; times that do not increase, and those that would put a tick
     * before the origin, are skipped). No times: the grid.
     */
    public static TickTable v1(long originUs, long[] tickRecordTimes, int count) {
        long[] src = new long[Math.max(4, count)];
        int k = 0;
        for (int i = 0; i < count; i++) {
            long s = tickRecordTimes[i] - V1_TICK_RUN_US;
            if (s < originUs || k > 0 && s <= src[k - 1]) {
                continue;
            }
            if (k > 0 && s - src[k - 1] > V1_FILL_OVER_US) {
                long prev = src[k - 1];
                long gap = s - prev;
                long parts = Math.round(gap / (double) TICK_US);
                for (long j = 1; j < parts; j++) {
                    src = ensure(src, k + 1);
                    src[k++] = prev + gap * j / parts;
                }
            }
            src = ensure(src, k + 1);
            src[k++] = s;
        }
        if (k == 0) {
            return grid(originUs);
        }
        return new TickTable(Kind.V1, originUs, Arrays.copyOf(src, k), k, null, null, null);
    }

    /**
     * v2: one entry per head (ascending times), with its handled increment ({@code ExtCodec.TickHead.handled}), its
     * flags and its segment (a position in {@link LoadedRecording#segments()}). Heads before the origin are dropped
     * (their increments still count toward their segment's sum). No heads: the grid.
     */
    public static TickTable v2(long originUs, long[] headTimes, int[] handled, int[] flags, int[] segments,
                               int count) {
        long[] t = new long[count];
        long[] h = new long[count];
        int[] seg = new int[count];
        boolean[] ok = new boolean[count];
        int k = 0;
        long sum = 0;
        int current = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            if (segments[i] != current) {
                current = segments[i];
                sum = 0;
            }
            sum += Math.max(0, handled[i]);
            if (headTimes[i] < originUs || k > 0 && headTimes[i] < t[k - 1]) {
                continue;
            }
            t[k] = headTimes[i];
            h[k] = sum;
            seg[k] = current;
            ok[k] = (flags[i] & RewindFormat.TICK_HEAD_ORDER_UNKNOWN) == 0;
            k++;
        }
        if (k == 0) {
            return grid(originUs);
        }
        return new TickTable(Kind.V2, originUs, Arrays.copyOf(t, k), k, Arrays.copyOf(h, k), Arrays.copyOf(seg, k),
                Arrays.copyOf(ok, k));
    }

    /**
     * The table of a loaded recording: v2 from its TICK_HEAD records when it has any, else v1 from its TICK records,
     * else the grid. A malformed head is skipped.
     */
    public static TickTable forRecording(long originUs, SelfTrack track, ExtRecords ext) {
        if (ext != null && ext.has(RewindFormat.TAG_TICK_HEAD)) {
            int count = 0;
            for (int i = 0; i < ext.size(); i++) {
                if (ext.tag(i) == RewindFormat.TAG_TICK_HEAD) {
                    count++;
                }
            }
            long[] t = new long[count];
            int[] h = new int[count];
            int[] f = new int[count];
            int[] s = new int[count];
            ExtCodec.TickHead head = new ExtCodec.TickHead();
            int k = 0;
            for (int i = 0; i < ext.size(); i++) {
                if (ext.tag(i) != RewindFormat.TAG_TICK_HEAD) {
                    continue;
                }
                try {
                    head.read(ext.buffer(), ext.offset(i), ext.length(i));
                } catch (RewindFormat.FormatException e) {
                    continue;
                }
                t[k] = ext.timeUs(i);
                h[k] = head.handled;
                f[k] = head.flags;
                s[k] = ext.segmentIndex(i);
                k++;
            }
            return v2(originUs, t, h, f, s, k);
        }
        int count = track == null ? 0 : track.tickCount();
        long[] times = new long[count];
        int k = 0;
        for (int g = 0; track != null && g < track.segmentCount(); g++) {
            SelfTrack.Segment seg = track.segment(g);
            for (int i = 0; i < seg.tickCount(); i++) {
                times[k++] = seg.tickTimeUs(i);
            }
        }
        Arrays.sort(times, 0, k);
        return v1(originUs, times, k);
    }

    public Kind kind() {
        return kind;
    }

    /** Recorded entries (heads for v2, rebuilt ticks for v1); 0 for the grid. */
    public int entries() {
        return kind == Kind.GRID ? 0 : n;
    }

    /** T[k]: the head time of tick k (any k; outside the entries the table continues at 50 ms). */
    public long timeOf(long k) {
        long i = k - m;
        if (i < 0) {
            return t[0] + i * TICK_US;
        }
        if (i >= n) {
            return t[n - 1] + (i - (n - 1)) * TICK_US;
        }
        return t[(int) i];
    }

    /** The first tick whose time is at or after {@code tUs}. */
    public long tickOf(long tUs) {
        if (tUs <= t[0]) {
            return m - Math.floorDiv(t[0] - tUs, TICK_US);
        }
        if (tUs > t[n - 1]) {
            return m + (n - 1) + (-Math.floorDiv(t[n - 1] - tUs, TICK_US));
        }
        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (t[mid] >= tUs) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return m + lo;
    }

    private int entry(long k) {
        long i = k - m;
        return i < 0 || i >= n ? -1 : (int) i;
    }

    /** Tick k is a v2 head whose handled count holds (not a continuation tick, not flagged). */
    public boolean orderKnown(long k) {
        int i = entry(k);
        return known != null && i >= 0 && known[i];
    }

    /** H[k]: how many of the segment's client-handled NET records were handled before tick k (v2 heads; else 0). */
    public long handled(long k) {
        int i = entry(k);
        return handled == null || i < 0 ? 0 : handled[i];
    }

    /** The segment (a position in {@link LoadedRecording#segments()}) of tick k's head; -1 when it is not a head. */
    public int segment(long k) {
        int i = entry(k);
        return segment == null || i < 0 ? -1 : segment[i];
    }

    /** {@code (p - T[j]) / (T[j+1] - T[j])}, clamped to 0-1 (1 when the interval is empty). */
    public float partial(long j, long pUs) {
        long a = timeOf(j);
        long b = timeOf(j + 1);
        if (b <= a) {
            return 1f;
        }
        double f = (pUs - a) / (double) (b - a);
        return f <= 0 ? 0f : f >= 1 ? 1f : (float) f;
    }

    private static long[] ensure(long[] a, int need) {
        return need <= a.length ? a : Arrays.copyOf(a, Math.max(need, a.length * 2));
    }
}
