package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * {@link TickClock}'s fixed step for exports (Rewind Export PLAN Solution 1, Phase 1; TASK P2): {@code step} sets
 * playback time exactly, {@code dueAll} is {@code due} without vanilla's 10-tick cap and never pulls playback time
 * back, {@code seatTimed} puts the timed cursor on the first record at or after a time, and {@code advance} is
 * unchanged.
 */
public class ExportClockTest {

    private static final long O = 5_000_000L;
    private static final long MS = 1_000L;
    private static final long NS_PER_MS = 1_000_000L;

    /** Heads every 50 ms from O to O + 100 ms, a freeze, then 25 catch-up heads 100 us apart from O + 600 ms. */
    private static TickTable withFreeze() {
        List<Long> t = new ArrayList<Long>();
        for (long ms = 0; ms <= 100; ms += 50) {
            t.add(O + ms * MS);
        }
        for (int i = 0; i < 25; i++) {
            t.add(O + 600 * MS + i * 100L);
        }
        long[] a = new long[t.size()];
        int[] zero = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            a[i] = t.get(i);
        }
        return TickTable.v2(O, a, zero, zero, zero, a.length);
    }

    private static TickTable every50(int n) {
        long[] t = new long[n];
        int[] zero = new int[n];
        for (int i = 0; i < n; i++) {
            t[i] = O + i * 50 * MS;
        }
        return TickTable.v2(O, t, zero, zero, zero, n);
    }

    @Test
    public void stepSetsPlaybackTimeExactly() {
        TickTable table = every50(100);
        TickClock clock = new TickClock(table);
        clock.set(O, 0L);
        clock.advance(333_333L, 0.25f, false); // leaves a sub-microsecond remainder behind
        for (long us : new long[]{O + 1, O + 16_666, O + 1_025_000, O + 7, O + 4_999_999}) {
            clock.step(us);
            assertEquals(us, clock.playbackUs());
        }
        // a step leaves no remainder for a later advance
        clock.step(O + 100 * MS);
        clock.advance(1_000L, 1f, false);
        assertEquals(O + 100 * MS, clock.playbackUs());
    }

    @Test
    public void dueAllRunsEveryTickReachedTwentyFiveInOneCallAndNeverPullsBack() {
        TickTable table = withFreeze();
        TickClock clock = new TickClock(table);
        long afterHundred = table.tickOf(O + 100 * MS) + 1; // ticks at 0, 50, 100 ran
        clock.step(O + 610 * MS);
        assertEquals("the freeze's 25 catch-up ticks at once", 25, clock.dueAll(afterHundred, 1_000));
        assertEquals("playback time stays", O + 610 * MS, clock.playbackUs());
        assertEquals("never past the stop", 7, clock.dueAll(afterHundred, 7));
        assertEquals(O + 610 * MS, clock.playbackUs());
        assertEquals("at the stop: none", 0, clock.dueAll(afterHundred, 0));
        assertEquals(0, clock.dueAll(afterHundred, -3));

        // every tick with T[k] <= P, and the next one not
        for (long p = O; p < O + 900 * MS; p += 7_919) {
            clock.step(p);
            int due = clock.dueAll(0, 10_000);
            assertEquals("P " + p, table.tickOf(p + 1), due);
            assertEquals(p, clock.playbackUs());
        }

        // inside the freeze nothing is due
        clock.step(O + 400 * MS);
        assertEquals(0, clock.dueAll(afterHundred, 1_000));
    }

    @Test
    public void partialAfterAStepIsPlaybackTimeOverTheRecordedInterval() {
        TickTable table = withFreeze();
        TickClock clock = new TickClock(table);
        for (long p = O; p < O + 800 * MS; p += 3_331) {
            clock.step(p);
            int due = clock.dueAll(0, 10_000);
            long j = due - 1;
            double want = (p - table.timeOf(j)) / (double) (table.timeOf(j + 1) - table.timeOf(j));
            assertEquals("P " + p, Math.max(0, Math.min(1, want)), clock.partial(j), 1e-6);
        }
    }

    @Test
    public void advanceStillCapsAtTenAndPullsBackAsBefore() {
        TickTable table = every50(200);
        TickClock clock = new TickClock(table);
        clock.set(table.timeOf(1), 0L);
        clock.advance(900 * NS_PER_MS, 1f, false);
        assertEquals(O + 950 * MS, clock.playbackUs());
        assertEquals(TickClock.MAX_TICKS_PER_FRAME, clock.due(1, 1_000));
        assertEquals(table.timeOf(11), clock.playbackUs());
        clock.advance(916 * NS_PER_MS, 1f, false);
        assertEquals(table.timeOf(11) + 16 * MS, clock.playbackUs());
        clock.advance(926 * NS_PER_MS, 1f, true);
        assertEquals("paused", table.timeOf(11) + 16 * MS, clock.playbackUs());
    }

    @Test
    public void seatTimedPutsTheCursorOnTheFirstRecordAtOrAfterTheTime() {
        TickTable table = every50(100);
        ExtCodec c = new ExtCodec();
        ExtRecords.Builder b = new ExtRecords.Builder();
        for (long ms : new long[]{1_010, 1_025, 1_030}) {
            b.addEncoded(0, O + ms * MS, RewindFormat.TAG_TAP, c.buffer(), c.tap(RewindFormat.TAP_LMB));
        }
        ExtRecords ext = b.build();
        final List<Long> got = new ArrayList<Long>();
        PacketSink sink = new PacketSink() {
            @Override public boolean apply(int packetId, byte[] buf, int off, int len) { return true; }
            @Override public void boundary(int packetId, byte[] buf, int off, int len) { }
            @Override public boolean sessionHook(int packetId, byte[] buf, int off, int len) { return true; }
            @Override public void sample(long tick, long timeUs) { }
            @Override public void timed(int tag, long timeUs, byte[] buf, int off, int len) {
                got.add((timeUs - O) / MS);
            }
        };
        TickClock clock = new TickClock(table);
        clock.step(O + 1_050 * MS);
        clock.seatTimed(ext, O + 1_025 * MS); // the cursor is not where P is
        assertEquals(2, clock.dispatchTimed(ext, sink));
        assertEquals("[1025, 1030]", got.toString());

        got.clear();
        clock.step(O + 1_050 * MS);
        clock.seatTimed(ext, O + 1_000 * MS); // seated back: dispatched again from there
        clock.dispatchTimed(ext, sink);
        assertEquals("[1010, 1025, 1030]", got.toString());
        assertEquals(0, clock.dispatchTimed(ext, sink));
    }
}
