package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link TickClock} on its own (review code-2 I2; TASK B12): the ticks a frame runs are those whose recorded time
 * playback time has reached, at most {@link TickClock#MAX_TICKS_PER_FRAME} and never past the next stop; when a cap
 * holds a due tick back, playback time is pulled back to that tick's time (vanilla's timer drops the time it cannot
 * tick), so the next frame goes on from there instead of running the held-back ticks all at once.
 */
public class TickClockTest {

    private static final long O = 5_000_000L;
    private static final long MS = 1_000L;
    private static final long NS_PER_MS = 1_000_000L;

    /** A v2 table: heads every 50 ms from O for {@code n} ticks. */
    private static TickTable every50(int n) {
        long[] t = new long[n];
        int[] zero = new int[n];
        for (int i = 0; i < n; i++) {
            t[i] = O + i * 50 * MS;
        }
        return TickTable.v2(O, t, zero, zero, zero, n);
    }

    @Test
    public void aLongFrameRunsAtMostTenTicksAndPullsPlaybackTimeBackToTheFirstHeldBackTick() {
        TickTable table = every50(200);
        TickClock clock = new TickClock(table);
        clock.set(table.timeOf(1), 0L); // tick 0 ran, P at tick 1's time
        clock.advance(900 * NS_PER_MS, 1f, false); // a 900 ms frame
        assertEquals(O + 950 * MS, clock.playbackUs());
        int due = clock.due(1, 1_000);
        assertEquals("vanilla's cap", TickClock.MAX_TICKS_PER_FRAME, due);
        assertEquals("P waits at the first tick held back", table.timeOf(1 + due), clock.playbackUs());

        // the next 16 ms frame runs the next due tick only: the dropped time is gone
        clock.advance(916 * NS_PER_MS, 1f, false);
        assertEquals(1, clock.due(1 + due, 1_000));
        assertEquals(table.timeOf(1 + due) + 16 * MS, clock.playbackUs());
    }

    @Test
    public void theNextStopCapsTheTicksAndPullsBackTheSameWay() {
        TickTable table = every50(200);
        TickClock clock = new TickClock(table);
        clock.set(table.timeOf(10), 0L);
        clock.advance(400 * NS_PER_MS, 1f, false);
        assertEquals(3, clock.due(10, 3));
        assertEquals(table.timeOf(13), clock.playbackUs());
        clock.advance(500 * NS_PER_MS, 1f, false);
        assertEquals("at the stop: nothing", 0, clock.due(13, 0));
        assertEquals(table.timeOf(13), clock.playbackUs());
    }

    @Test
    public void ticksNotHeldBackLeavePlaybackTimeAlone() {
        TickTable table = every50(200);
        TickClock clock = new TickClock(table);
        clock.set(table.timeOf(1), 0L);
        clock.advance(120 * NS_PER_MS, 1f, false); // P = 170 ms: ticks 1, 2, 3 (50, 100, 150) are due
        assertEquals(3, clock.due(1, 1_000));
        assertEquals(O + 170 * MS, clock.playbackUs());
    }

    @Test
    public void pausedFramesSpendTheirWallTimeAndOtherSpeedsScaleIt() {
        TickTable table = every50(400);
        TickClock clock = new TickClock(table);
        clock.set(table.timeOf(1), 0L);
        clock.advance(500 * NS_PER_MS, 1f, true);
        assertEquals("paused: P holds", table.timeOf(1), clock.playbackUs());
        clock.advance(510 * NS_PER_MS, 4f, false); // 10 ms of wall time at 4x
        assertEquals(table.timeOf(1) + 40 * MS, clock.playbackUs());
        // 0.25x in frames of 1 ms: no drift from rounding
        for (int i = 1; i <= 1_000; i++) {
            clock.advance(510 * NS_PER_MS + i * NS_PER_MS, 0.25f, false);
        }
        assertEquals(table.timeOf(1) + 40 * MS + 250 * MS, clock.playbackUs());
    }
}
