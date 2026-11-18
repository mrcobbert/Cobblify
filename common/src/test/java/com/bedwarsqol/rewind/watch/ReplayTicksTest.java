package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Playback's tick counter: speed steps, pause, stops, the end tick and play-at-the-end. */
public class ReplayTicksTest {

    /** Runs {@code n} world ticks the way the session does: HEAD then RETURN. */
    private static void run(ReplayTicks t, int n) {
        for (int i = 0; i < n; i++) {
            t.beginTick();
            t.finishTick();
        }
    }

    @Test
    public void speedStepsAreExactlyQuarterToFour() {
        assertArrayEquals(new float[]{0.25f, 0.5f, 1f, 2f, 4f}, ReplayTicks.SPEEDS, 0f);
        ReplayTicks t = new ReplayTicks(1000);
        assertEquals(1f, t.speed(), 0f);
        assertTrue(t.faster());
        assertEquals(2f, t.speed(), 0f);
        assertTrue(t.faster());
        assertEquals(4f, t.speed(), 0f);
        assertFalse("4x is the top step", t.faster());
        assertEquals(4f, t.speed(), 0f);
        for (int i = 0; i < 4; i++) {
            assertTrue(t.slower());
        }
        assertEquals(0.25f, t.speed(), 0f);
        assertFalse("0.25x is the bottom step", t.slower());
        assertEquals(0, t.speedIndex());
    }

    @Test
    public void pauseHoldsTheTickCount() {
        ReplayTicks t = new ReplayTicks(1000);
        run(t, t.frameTicks(3));
        assertEquals(3, t.ticksRun());
        t.setPaused(true);
        for (int f = 0; f < 10; f++) {
            assertEquals(0, t.frameTicks(10));
        }
        assertEquals(3, t.ticksRun());
        t.setPaused(false);
        assertEquals(4, t.frameTicks(4));
    }

    @Test
    public void nextStopIsTheNearerOfTargetAndEnd() {
        ReplayTicks t = new ReplayTicks(100);
        assertEquals(100, t.nextStop());
        run(t, 10);
        t.seek(SeekPlanner.plan(t.ticksRun(), 50));
        assertTrue(t.catchingUp());
        assertEquals(50, t.nextStop());
        assertEquals(40, t.ticksToNextStop());
        assertEquals(50, t.playheadTick());
        t.seek(SeekPlanner.plan(t.ticksRun(), 150));
        assertEquals("the end is nearer", 100, t.nextStop());
        assertEquals(90, t.ticksToNextStop());
    }

    @Test
    public void framesNeverRunPastTheNextStop() {
        ReplayTicks t = new ReplayTicks(100);
        run(t, 98);
        assertEquals(2, t.frameTicks(5));
        run(t, 2);
        assertEquals(0, t.frameTicks(5));
    }

    @Test
    public void theEndTickPauses() {
        ReplayTicks t = new ReplayTicks(20);
        run(t, 19);
        assertFalse(t.paused());
        t.beginTick();
        assertTrue("the end tick is a stop", t.finishTick());
        assertTrue(t.paused());
        assertTrue(t.atEnd());
        assertEquals(20, t.ticksRun());
        assertEquals(0, t.frameTicks(10));
    }

    @Test
    public void playAtTheEndRestarts() {
        ReplayTicks t = new ReplayTicks(20);
        run(t, 20);
        assertTrue(t.paused());
        assertEquals(ReplayTicks.PlayToggle.RESTART, t.togglePlay());
        assertFalse(t.paused());
    }

    @Test
    public void playToggleFlipsElsewhere() {
        ReplayTicks t = new ReplayTicks(20);
        run(t, 5);
        assertEquals(ReplayTicks.PlayToggle.PAUSED, t.togglePlay());
        assertTrue(t.paused());
        assertEquals(ReplayTicks.PlayToggle.PLAYING, t.togglePlay());
        assertFalse(t.paused());
    }

    @Test
    public void aCatchUpRunsWhilePausedAndHoldsAtItsTarget() {
        ReplayTicks t = new ReplayTicks(1000);
        run(t, 10);
        t.setPaused(true);
        t.seek(SeekPlanner.plan(t.ticksRun(), 13));
        assertEquals(3, t.frameTicks(10));
        t.beginTick();
        assertFalse(t.finishTick());
        t.beginTick();
        assertFalse(t.finishTick());
        t.beginTick();
        assertTrue("the target is a stop", t.finishTick());
        assertFalse(t.catchingUp());
        assertTrue("the user's play state is kept", t.paused());
        assertEquals(13, t.ticksRun());
        assertEquals(0, t.frameTicks(10));
    }

    @Test
    public void aBackwardSeekRestartsAtTickZero() {
        ReplayTicks t = new ReplayTicks(1000);
        run(t, 300);
        SeekPlanner.Plan p = SeekPlanner.plan(t.ticksRun(), 120);
        t.seek(p);
        assertEquals(0, t.ticksRun());
        assertEquals(120, t.ticksToNextStop());
        assertEquals(120, t.playheadTick());
    }

    @Test
    public void soundsMutedForTheWholeCatchUpParticlesExceptTheLastSixtyTicks() {
        ReplayTicks t = new ReplayTicks(1000);
        assertFalse(t.soundsMuted());
        assertFalse(t.particlesMuted());
        t.seek(SeekPlanner.plan(0, 100));
        int particleTicks = 0;
        while (t.catchingUp()) {
            t.beginTick();
            assertTrue(t.soundsMuted());
            if (!t.particlesMuted()) {
                particleTicks++;
            }
            t.finishTick();
        }
        assertEquals(SeekPlanner.PARTICLE_TAIL_TICKS, particleTicks);
        assertFalse(t.soundsMuted());
        assertFalse(t.particlesMuted());
    }

    @Test
    public void aRecordedKickShortensTheEnd() {
        ReplayTicks t = new ReplayTicks(1000);
        t.endAt(40);
        assertEquals(40, t.endTick());
        t.endAt(500);
        assertEquals("never lengthens", 40, t.endTick());
        run(t, t.frameTicks(100));
        assertEquals(40, t.ticksRun());
        assertTrue(t.paused());
    }
}
