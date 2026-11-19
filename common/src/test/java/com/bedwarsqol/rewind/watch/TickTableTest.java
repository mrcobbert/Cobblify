package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The recorded tick table (PLAN "Tick table", review I3; TASK B12): v2 takes the TICK_HEAD times as they are, v1 is
 * rebuilt from its on-change TICK records, both continue at 50 ms outside the recorded span, and a recording with no
 * tick times keeps R2's grid.
 */
public class TickTableTest {

    private static final long MS = 1_000L;
    private static final long O = 7_000_000L;

    /** Heads at {@code O + ms[i]} milliseconds, one segment, handled increment 0, no flags. */
    static TickTable heads(long... ms) {
        long[] t = new long[ms.length];
        for (int i = 0; i < ms.length; i++) {
            t[i] = O + ms[i] * MS;
        }
        return TickTable.v2(O, t, new int[ms.length], new int[ms.length], new int[ms.length], ms.length);
    }

    private static long[] times(TickTable t, long from, long to) {
        long[] out = new long[(int) (to - from)];
        for (long k = from; k < to; k++) {
            out[(int) (k - from)] = (t.timeOf(k) - O) / MS;
        }
        return out;
    }

    @Test
    public void v2HeadsAreTheTableExactlyAFreezeIsAGapNothingInsertedNothingDropped() {
        TickTable t = heads(100, 600, 601, 602);
        assertSame(TickTable.Kind.V2, t.kind());
        assertEquals(4, t.entries());
        // 100 ms after the origin: two 50 ms continuation ticks before it (0 and 50), then the four heads, then 50 ms.
        long first = t.tickOf(O + 100 * MS);
        assertEquals(2, first);
        assertEquals(O, t.timeOf(0));
        assertEquals(O + 50 * MS, t.timeOf(1));
        assertEquals(O + 100 * MS, t.timeOf(first));
        assertEquals(O + 600 * MS, t.timeOf(first + 1));
        assertEquals(O + 601 * MS, t.timeOf(first + 2));
        assertEquals(O + 602 * MS, t.timeOf(first + 3));
        assertEquals("continues at 50 ms after the last head", O + 652 * MS, t.timeOf(first + 4));
        assertEquals("nothing inside the freeze", first + 1, t.tickOf(O + 101 * MS));
        assertEquals(first + 1, t.tickOf(O + 599 * MS));
    }

    @Test
    public void v1RebuildsTicksFromTickRecordTimesMinusOneMsAndSubdividesGaps() {
        // TICKs at 51, 101, 151 (consecutive), then 351 (a 200 ms gap: nothing changed for three ticks), then 431
        // (80 ms: over 75, two intervals), then 491 (60 ms: kept as one interval).
        long[] tick = {51, 101, 151, 351, 431, 491};
        long[] us = new long[tick.length];
        for (int i = 0; i < tick.length; i++) {
            us[i] = O + tick[i] * MS;
        }
        TickTable t = TickTable.v1(O, us, us.length);
        assertSame(TickTable.Kind.V1, t.kind());
        long k = t.tickOf(O + 50 * MS);
        assertEquals("one tick before the first record time", 1, k);
        assertEquals(O + 50 * MS, t.timeOf(k));
        assertTrue(Arrays.equals(new long[]{0, 50, 100, 150, 200, 250, 300, 350, 390, 430, 490, 540},
                times(t, 0, 12)));
        assertFalse("v1 has no handled counts", t.orderKnown(k));
    }

    @Test
    public void beforeTheFirstSourceTimeTheTableStepsBackFiftyMsToCoverTheOrigin() {
        TickTable t = heads(130);
        // 130 ms -> 80 -> 30: tick 0 is the first continuation time at or after the origin.
        assertEquals(O + 30 * MS, t.timeOf(0));
        assertEquals(O - 20 * MS, t.timeOf(-1));
        assertEquals(0, t.tickOf(O));
        assertEquals(0, t.tickOf(O + 30 * MS));
        assertEquals(1, t.tickOf(O + 31 * MS));
        assertEquals(2, t.tickOf(O + 130 * MS));
        assertEquals(3, t.tickOf(O + 131 * MS));
        assertEquals(3, t.tickOf(O + 180 * MS));
        assertEquals(103, t.tickOf(O + 5_180 * MS));
    }

    @Test
    public void noTickTimesKeepsTheFiftyMsGridFromTheOrigin() {
        TickTable g = TickTable.forRecording(O, new SelfTrack.Builder().segment(1).build(), ExtRecords.EMPTY);
        assertSame(TickTable.Kind.GRID, g.kind());
        for (long k = -3; k < 50; k++) {
            assertEquals(O + k * 50 * MS, g.timeOf(k));
            assertEquals(k, g.tickOf(O + k * 50 * MS));
            assertEquals(k + 1, g.tickOf(O + k * 50 * MS + 1));
        }
    }

    @Test
    public void forRecordingPrefersTickHeadsThenTickRecords() {
        SelfTrackEncoder enc = new SelfTrackEncoder();
        SelfTrack.Builder b = new SelfTrack.Builder().segment(1);
        for (int i = 0; i < 3; i++) {
            int n = enc.tick(i, 64, 0, 0f, 0f, 0f, 0, 0, -1, 0);
            b.record(O + 51 * MS + i * 50 * MS, RewindFormat.TAG_TICK, enc.buffer(), 0, n);
        }
        SelfTrack track = b.build();
        TickTable v1 = TickTable.forRecording(O, track, ExtRecords.EMPTY);
        assertSame(TickTable.Kind.V1, v1.kind());
        assertEquals(O + 50 * MS, v1.timeOf(v1.tickOf(O + 50 * MS)));

        ExtCodec codec = new ExtCodec();
        ExtRecords.Builder x = new ExtRecords.Builder();
        int n = codec.tickHead(0);
        x.addEncoded(0, O + 70 * MS, RewindFormat.TAG_TICK_HEAD, codec.buffer(), n);
        n = codec.tickHead(3);
        x.addEncoded(0, O + 120 * MS, RewindFormat.TAG_TICK_HEAD, codec.buffer(), n);
        TickTable v2 = TickTable.forRecording(O, track, x.build());
        assertSame(TickTable.Kind.V2, v2.kind());
        assertEquals(2, v2.entries());
        assertEquals(O + 70 * MS, v2.timeOf(v2.tickOf(O + 70 * MS)));
        assertEquals(3, v2.handled(v2.tickOf(O + 120 * MS)));
    }

    @Test
    public void handledCountsAddUpPerSegmentAndAFlaggedHeadIsOrderUnknown() {
        long[] t = {O + 100 * MS, O + 150 * MS, O + 200 * MS, O + 250 * MS, O + 300 * MS};
        int[] inc = {6, 1, 4, 2, 3};
        int[] flags = {0, 0, RewindFormat.TICK_HEAD_ORDER_UNKNOWN, 0, 0};
        int[] seg = {0, 0, 0, 1, 1};
        TickTable table = TickTable.v2(O, t, inc, flags, seg, 5);
        long k = table.tickOf(O + 100 * MS);
        assertEquals(6, table.handled(k));
        assertEquals(7, table.handled(k + 1));
        assertEquals("a flagged head still counts", 11, table.handled(k + 2));
        assertEquals("a new segment starts from 0", 2, table.handled(k + 3));
        assertEquals(5, table.handled(k + 4));
        assertTrue(table.orderKnown(k));
        assertTrue(table.orderKnown(k + 1));
        assertFalse(table.orderKnown(k + 2));
        assertTrue(table.orderKnown(k + 3));
        assertFalse("continuation ticks have no count", table.orderKnown(k - 1));
        assertFalse(table.orderKnown(k + 5));
        assertEquals(0, table.segment(k));
        assertEquals(1, table.segment(k + 3));
    }

    @Test
    public void partialIsPlaybackTimeOverTheRecordedInterval() {
        TickTable t = heads(100, 170);
        long j = t.tickOf(O + 100 * MS);
        assertEquals(0.5f, t.partial(j, O + 135 * MS), 1e-6f);
        assertEquals(0f, t.partial(j, O + 100 * MS), 0f);
        assertEquals("clamped", 1f, t.partial(j, O + 400 * MS), 0f);
        assertEquals("clamped", 0f, t.partial(j, O + 10 * MS), 0f);
    }

    @Test
    public void onlyTheNettyThreadHandlersCarryNoOrdinal() {
        for (int id = 0; id <= 0x49; id++) {
            boolean netty = id == 0x00 || id == 0x40 || id == 0x46 || id == 0x47 || id == 0x48;
            assertEquals("id " + id, !netty, TickTable.clientHandled(id));
        }
    }
}
