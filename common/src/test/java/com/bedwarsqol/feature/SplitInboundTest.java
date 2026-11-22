package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The "Teammate Returning" arrival latch. Each test drives scans every {@link #SCAN} ms, like the adapter's
 * every-4-ticks tick: {@code observe} for each teammate seen, then {@code endScan}.
 */
public class SplitInboundTest {

    private static final long T0 = 1_000_000L;
    private static final long SCAN = 200L;
    /** The local player standing at the forge. */
    private static final double HERE = 2.0;

    /** A small driver: a clock, and one scan per call. */
    private static final class Game {
        final SplitInbound in = new SplitInbound();
        long now = T0;
        int fired;

        /** One scan seeing {@code name} at {@code dist}; returns whether it fired. */
        boolean scan(String name, double dist) {
            now += SCAN;
            boolean f = in.observe(name, dist, now);
            in.endScan(now);
            if (f) fired++;
            return f;
        }

        /** One scan seeing nobody. */
        void empty() {
            now += SCAN;
            in.endScan(now);
        }

        /** Scans at a fixed distance for {@code ms}. */
        void stay(String name, double dist, long ms) {
            for (long t = 0; t < ms; t += SCAN) scan(name, dist);
        }

        /** Scans walking from {@code from} to {@code to} at {@code step} blocks per scan, ending exactly at {@code to}. */
        void walk(String name, double from, double to, double step) {
            double d = from;
            double dir = Math.signum(to - from);
            while ((to - d) * dir > 1e-9) {
                d += dir * step;
                if ((to - d) * dir < 0) d = to;
                scan(name, d);
            }
        }

        boolean cue() {
            return in.cue(HERE, now);
        }

        /** At base, then away beyond 36 blocks long enough to be armed, standing at 40. */
        void armByTrip(String name) {
            stay(name, 10, 2_000L);
            stay(name, 40, SplitInbound.AWAY_MS + SCAN);
        }
    }

    @Test
    public void aTeammateWhoStaysInTheBaseNeverCues() {
        Game g = new Game();
        for (int i = 0; i < 60_000 / SCAN; i++) {
            // wanders between 5 and 30 blocks, fast enough to "close" often
            double d = 17.5 + 12.5 * Math.sin(i / 7.0);
            assertFalse("scan " + i, g.scan("Steve", d));
            assertFalse("scan " + i, g.cue());
        }
        assertEquals(0, g.fired);
    }

    @Test
    public void awayForEightSecondsThenClosingInsideEighteenCues() {
        Game g = new Game();
        g.armByTrip("Steve");
        assertFalse(g.cue());
        g.walk("Steve", 40, 19, 1.0);
        assertEquals("not yet inside 18", 0, g.fired);
        assertFalse(g.cue());
        assertTrue("closing inside 18 fires", g.scan("Steve", 18));
        assertTrue(g.cue());
        assertEquals(1, g.fired);
    }

    @Test
    public void awayForLessThanEightSecondsDoesNotArm() {
        Game g = new Game();
        g.stay("Steve", 10, 2_000L);
        // 7 s at 40, then the walk in spends 3 more scans beyond 36: away 7.6 s in all
        g.stay("Steve", 40, SplitInbound.AWAY_MS - 1_000L);
        g.walk("Steve", 40, 5, 1.0);
        assertEquals(0, g.fired);
        assertFalse(g.cue());
        // coming back inside 36 clears the timer: two short trips do not add up
        g.stay("Steve", 40, 5_000L);
        g.stay("Steve", 30, 1_000L);
        g.stay("Steve", 40, 5_000L);
        g.walk("Steve", 40, 5, 1.0);
        assertEquals(0, g.fired);
        assertFalse(g.cue());
    }

    @Test
    public void armedButNotClosingDoesNotCue() {
        Game g = new Game();
        g.armByTrip("Steve");
        g.walk("Steve", 40, 20, 1.0);
        g.stay("Steve", 20, 1_000L);
        // creeps to 17 at 0.1 block per scan: 0.2 per 400 ms, under the 0.5 closing threshold
        g.walk("Steve", 20, 17, 0.1);
        g.stay("Steve", 17, 3_000L);
        assertEquals("inside 18 but not closing", 0, g.fired);
        assertFalse(g.cue());
        // still armed: starting to close now cues
        g.walk("Steve", 17, 15, 1.0);
        assertEquals(1, g.fired);
        assertTrue(g.cue());
    }

    @Test
    public void cueEndsWhenTheyReachTheForge() {
        Game g = new Game();
        g.armByTrip("Steve");
        g.walk("Steve", 40, 18, 1.0);
        assertTrue(g.cue());
        g.walk("Steve", 18, 4, 1.0);
        assertTrue("still on the way", g.cue());
        g.scan("Steve", 3.0);
        assertFalse("at the forge", g.cue());
    }

    @Test
    public void cueEndsWhenTheyTurnAway() {
        Game g = new Game();
        g.armByTrip("Steve");
        g.walk("Steve", 40, 18, 1.0);
        assertTrue(g.cue());
        g.walk("Steve", 18, 12, 1.0);
        g.walk("Steve", 12, 13.9, 0.1);
        assertTrue("1.9 blocks farther than their nearest", g.cue());
        g.scan("Steve", 14.0);
        assertFalse("2 blocks farther than their nearest", g.cue());
        // and it does not come back by itself
        g.walk("Steve", 14, 5, 1.0);
        assertFalse(g.cue());
        assertEquals(1, g.fired);
    }

    @Test
    public void cueEndsAfterSixSeconds() {
        Game g = new Game();
        g.armByTrip("Steve");
        g.walk("Steve", 40, 18, 1.0);
        long fired = g.now;
        assertTrue(g.cue());
        g.stay("Steve", 15, 5_800L);
        assertTrue(g.in.cue(HERE, fired + SplitInbound.CUE_MS - 1));
        assertFalse(g.in.cue(HERE, fired + SplitInbound.CUE_MS));
        g.stay("Steve", 15, 2_000L);
        assertFalse(g.cue());
    }

    @Test
    public void oneCuePerArrival() {
        Game g = new Game();
        g.armByTrip("Steve");
        g.walk("Steve", 40, 18, 1.0);
        g.walk("Steve", 18, 3, 1.0);
        assertFalse(g.cue());
        assertEquals(1, g.fired);
        // staying in the base afterwards never cues again
        for (int i = 0; i < 60_000 / SCAN; i++) {
            g.scan("Steve", 17.5 + 12.5 * Math.sin(i / 7.0));
            assertFalse("scan " + i, g.cue());
        }
        assertEquals(1, g.fired);
        // away for 8 s again re-arms
        g.stay("Steve", 40, SplitInbound.AWAY_MS + SCAN);
        g.walk("Steve", 40, 18, 1.0);
        assertEquals(2, g.fired);
        assertTrue(g.cue());
    }

    @Test
    public void anUnobservedTeammateIsAwayAndCuesOnReappearingNearTheForge() {
        Game g = new Game();
        g.stay("Steve", 10, 2_000L);
        // out on the map for a few seconds, then dies: the entity is gone for the 5 s respawn
        g.stay("Steve", 50, 4_000L);
        for (long t = 0; t < 5_000L; t += SCAN) g.empty();
        // the respawn appears a few blocks from the forge with no older sample: fires at once
        assertTrue(g.scan("Steve", 5.0));
        assertTrue(g.cue());
        // a live cue ends when they go unobserved
        g.empty();
        assertFalse(g.cue());

        // unobserved alone also arms (beyond tracking range, say)
        Game h = new Game();
        h.stay("Alex", 10, 2_000L);
        for (long t = 0; t < SplitInbound.AWAY_MS + SCAN; t += SCAN) h.empty();
        assertTrue(h.scan("Alex", 6.0));
        assertTrue(h.cue());
    }

    @Test
    public void aTeammateWhoDiesInTheBaseCuesOnRespawning() {
        Game g = new Game();
        g.stay("Steve", 10, 2_000L);
        g.in.arm("STEVE"); // the death line
        for (long t = 0; t < 5_000L; t += SCAN) g.empty();
        assertTrue(g.scan("Steve", 5.0));
        assertTrue(g.cue());
    }

    @Test
    public void theFirstObservationNeverArms() {
        Game g = new Game();
        for (long t = 0; t < 30_000L; t += SCAN) g.empty();
        assertFalse("first seen near the forge", g.scan("Steve", 5.0));
        assertFalse(g.cue());

        Game h = new Game();
        for (long t = 0; t < 30_000L; t += SCAN) h.empty();
        h.scan("Steve", 50.0);
        h.walk("Steve", 50, 5, 1.0);
        assertEquals("first seen far, back within 8 s", 0, h.fired);
        assertFalse(h.cue());
    }

    @Test
    public void noCueWhileTheLocalPlayerIsAwayFromTheForge() {
        Game g = new Game();
        g.armByTrip("Steve");
        g.walk("Steve", 40, 18, 1.0);
        assertTrue(g.in.cue(SplitInbound.LOCAL_RANGE, g.now));
        assertFalse(g.in.cue(8.01, g.now));
        assertFalse(g.in.cue(30.0, g.now));
        assertTrue("the latch is kept while gated", g.in.cue(0.0, g.now));
    }

    @Test
    public void twoTeammatesAreTrackedSeparately() {
        SplitInbound in = new SplitInbound();
        long now = T0;
        // Steve stays at base; Alex goes out
        for (long t = 0; t < 2_000L; t += SCAN) {
            now += SCAN;
            in.observe("Steve", 10, now);
            in.observe("Alex", 10, now);
            in.endScan(now);
        }
        for (long t = 0; t < SplitInbound.AWAY_MS + SCAN; t += SCAN) {
            now += SCAN;
            in.observe("Steve", 10, now);
            in.observe("Alex", 40, now);
            in.endScan(now);
        }
        // Steve closing inside 18 does not cue: he was never away
        for (double d = 17; d >= 5; d -= 1) {
            now += SCAN;
            assertFalse(in.observe("Steve", d, now));
            in.observe("Alex", 40, now);
            in.endScan(now);
            assertFalse(in.cue(HERE, now));
        }
        // Alex coming back does
        int fired = 0;
        for (double d = 39; d >= 12; d -= 1) {
            now += SCAN;
            in.observe("Steve", 5, now);
            if (in.observe("Alex", d, now)) fired++;
            in.endScan(now);
        }
        assertEquals(1, fired);
        assertTrue(in.cue(HERE, now));
        // Steve going unobserved or reaching the forge does not end Alex's cue
        now += SCAN;
        in.observe("Alex", 11, now);
        in.endScan(now);
        assertTrue(in.cue(HERE, now));
        now += SCAN;
        in.observe("Steve", 2, now);
        in.observe("Alex", 10, now);
        in.endScan(now);
        assertTrue(in.cue(HERE, now));
    }

    @Test
    public void namesAreCaseInsensitive() {
        SplitInbound in = new SplitInbound();
        long now = T0;
        for (long t = 0; t < 2_000L; t += SCAN) {
            now += SCAN;
            in.observe("Steve", 10, now);
            in.endScan(now);
        }
        for (long t = 0; t < SplitInbound.AWAY_MS + SCAN; t += SCAN) {
            now += SCAN;
            in.observe(t % 400 == 0 ? "STEVE" : "steve", 40, now);
            in.endScan(now);
        }
        int fired = 0;
        for (double d = 39; d >= 12; d -= 1) {
            now += SCAN;
            if (in.observe("sTeVe", d, now)) fired++;
            in.endScan(now);
        }
        assertEquals("one teammate, one cue", 1, fired);
        assertTrue(in.cue(HERE, now));
        now += SCAN;
        in.observe("STEVE", 3, now);
        in.endScan(now);
        assertFalse("the same teammate reached the forge", in.cue(HERE, now));
    }

    @Test
    public void resetForgetsEverything() {
        Game g = new Game();
        g.armByTrip("Steve");
        g.walk("Steve", 40, 18, 1.0);
        assertTrue(g.cue());
        g.in.reset();
        assertFalse("the live cue is gone", g.cue());

        Game h = new Game();
        h.armByTrip("Steve");
        h.in.reset();
        h.walk("Steve", 40, 5, 1.0);
        assertEquals("the arming is gone", 0, h.fired);
        assertFalse(h.cue());
        // and the name is unknown again: an empty scan does not start an away timer for it
        Game k = new Game();
        k.stay("Steve", 10, 2_000L);
        k.in.reset();
        for (long t = 0; t < SplitInbound.AWAY_MS + SCAN; t += SCAN) k.empty();
        assertFalse(k.scan("Steve", 5.0));
        assertFalse(k.cue());
    }
}
