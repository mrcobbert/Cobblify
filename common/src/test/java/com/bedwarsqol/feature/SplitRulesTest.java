package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SplitRulesTest {

    private static final long T0 = 1_000_000L;

    @Test
    public void teammateDeathIsActiveForFiveSeconds() {
        SplitRules r = new SplitRules();
        assertFalse("nothing before any death", r.active(T0));
        assertTrue(r.onDeath('c', 'c', false, T0));
        assertTrue(r.active(T0));
        assertTrue(r.active(T0 + 4_999L));
        assertFalse(r.active(T0 + 5_000L));
        assertFalse(r.active(T0 + 60_000L));
        assertFalse("not before the death it records", r.active(T0 - 1L));
        assertEquals(5_000L, SplitRules.WINDOW_MS);
    }

    @Test
    public void enemyOrUnknownColourNeverCounts() {
        SplitRules r = new SplitRules();
        assertFalse(r.onDeath('9', 'c', false, T0));
        assertFalse(r.onDeath((char) 0, 'c', false, T0));
        assertFalse(r.onDeath('c', (char) 0, false, T0));
        assertFalse(r.onDeath((char) 0, (char) 0, false, T0));
        assertFalse(r.active(T0));
        // colour codes compare case-insensitively
        assertTrue(r.onDeath('A', 'a', false, T0));
        assertTrue(r.active(T0));
    }

    @Test
    public void ownDeathNeverCounts() {
        SplitRules r = new SplitRules();
        assertFalse(r.onDeath('c', 'c', true, T0));
        assertFalse(r.active(T0));
    }

    @Test
    public void aLaterDeathRestartsTheWindow() {
        SplitRules r = new SplitRules();
        assertTrue(r.onDeath('e', 'e', false, T0));
        assertTrue(r.onDeath('e', 'e', false, T0 + 3_000L));
        assertTrue(r.active(T0 + 7_999L));
        assertFalse(r.active(T0 + 8_000L));
        // an enemy death in between does not move it
        assertFalse(r.onDeath('c', 'e', false, T0 + 4_000L));
        assertFalse(r.active(T0 + 8_000L));
    }

    @Test
    public void forgeLocksOnFirstInRangeResourceAndStays() {
        SplitRules r = new SplitRules();
        assertFalse(r.hasForge());
        // 3-D distance: 10, 10, 10 is ~17.3 blocks away, out of range even though each axis is within 16
        assertFalse(r.offerResource(true, 0, 64, 0, 10, 74, 10));
        assertFalse(r.hasForge());
        assertFalse(r.offerResource(true, 0, 64, 0, 16.01, 64, 0));
        assertFalse(r.hasForge());
        assertEquals(16.0, SplitRules.LEARN_RANGE, 0);
        // exactly 16 blocks is in range
        assertTrue(r.offerResource(true, 0, 64, 0, 0, 64, 16));
        assertTrue(r.hasForge());
        assertEquals(0, r.forgeX(), 0);
        assertEquals(64, r.forgeY(), 0);
        assertEquals(16, r.forgeZ(), 0);
        // a later offer elsewhere does not move it
        assertFalse(r.offerResource(true, 100, 64, 100, 101, 64, 100));
        assertEquals(0, r.forgeX(), 0);
        assertEquals(64, r.forgeY(), 0);
        assertEquals(16, r.forgeZ(), 0);
    }

    @Test
    public void forgeIsNotLearnedWhileThePlayerCannotLearn() {
        SplitRules r = new SplitRules();
        assertFalse(r.offerResource(false, 0, 64, 0, 1, 64, 1));
        assertFalse(r.hasForge());
        assertTrue(r.offerResource(true, 0, 64, 0, 2.5, 63.5, -1));
        assertTrue(r.hasForge());
        assertEquals(2.5, r.forgeX(), 0);
        assertEquals(63.5, r.forgeY(), 0);
        assertEquals(-1, r.forgeZ(), 0);
    }

    @Test
    public void resetForgetsDeathAndForge() {
        SplitRules r = new SplitRules();
        r.onDeath('c', 'c', false, T0);
        r.offerResource(true, 0, 64, 0, 1, 64, 1);
        assertTrue(r.active(T0 + 1));
        assertTrue(r.hasForge());
        r.reset();
        assertFalse(r.active(T0 + 1));
        assertFalse(r.hasForge());
        // learns again after a reset
        assertTrue(r.offerResource(true, 0, 64, 0, 5, 64, 5));
        assertEquals(5, r.forgeX(), 0);
    }

    @Test
    public void secondsLeftCountsDownFromFive() {
        SplitRules r = new SplitRules();
        assertEquals("0 before any death", 0, r.secondsLeft(T0));
        r.onDeath('c', 'c', false, T0);
        assertEquals(5, r.secondsLeft(T0));
        assertEquals(5, r.secondsLeft(T0 + 1L));
        assertEquals(4, r.secondsLeft(T0 + 1_000L));
        assertEquals(1, r.secondsLeft(T0 + 4_999L));
        assertEquals(0, r.secondsLeft(T0 + 5_000L));
        assertEquals(0, r.secondsLeft(T0 + 60_000L));
    }

    @Test
    public void nearForgeIsWithinThirtyTwoBlocks() {
        SplitRules r = new SplitRules();
        assertEquals(32.0, SplitRules.NEAR_RANGE, 0);
        assertFalse("no forge", r.nearForge(0, 64, 0));
        r.offerResource(true, 0, 64, 0, 0, 64, 0);
        assertTrue("inside", r.nearForge(10, 70, -10));
        assertTrue("boundary inclusive at exactly 32", r.nearForge(0, 64, 32));
        assertFalse("outside", r.nearForge(0, 64, 32.01));
        // 3-D: 20, 20, 20 is ~34.6 blocks away though each axis is within 32
        assertFalse(r.nearForge(20, 84, 20));
    }

    @Test
    public void hudCueRunsFromOwnRespawnWhileATeammateIsStillDead() {
        SplitRules r = new SplitRules();
        assertEquals(5_000L, SplitRules.RESPAWN_MS);
        assertEquals(5_000L, SplitRules.HUD_AFTER_RESPAWN_MS);
        r.onOwnDeath(T0);
        assertTrue(r.onDeath('c', 'c', false, T0 + 3_000L));
        assertFalse("still dead", r.hudCue(T0 + 4_999L));
        assertTrue("respawned", r.hudCue(T0 + 5_000L));
        assertTrue(r.hudCue(T0 + 7_999L));
        assertFalse("teammate window over", r.hudCue(T0 + 8_000L));
    }

    @Test
    public void noHudCueWithoutAnOwnDeath() {
        SplitRules r = new SplitRules();
        assertTrue(r.onDeath('c', 'c', false, T0));
        for (long t = T0; t < T0 + SplitRules.WINDOW_MS + 1_000L; t += 250L) {
            assertFalse("at +" + (t - T0), r.hudCue(t));
        }
    }

    @Test
    public void noHudCueWhenTheTeammateDiedFirst() {
        SplitRules r = new SplitRules();
        assertTrue(r.onDeath('c', 'c', false, T0));
        r.onOwnDeath(T0 + 2_000L);
        for (long t = T0; t < T0 + 15_000L; t += 250L) {
            assertFalse("at +" + (t - T0), r.hudCue(t));
        }
    }

    @Test
    public void hudCueEndsFiveSecondsAfterRespawn() {
        SplitRules r = new SplitRules();
        r.onOwnDeath(T0);
        assertTrue(r.onDeath('c', 'c', false, T0 + 3_000L));
        assertTrue(r.onDeath('c', 'c', false, T0 + 9_500L));
        assertTrue(r.active(T0 + 10_000L));
        assertTrue(r.hudCue(T0 + 9_999L));
        assertFalse(r.hudCue(T0 + 10_000L));
    }

    @Test
    public void resetForgetsTheOwnDeath() {
        SplitRules r = new SplitRules();
        r.onOwnDeath(T0);
        r.onDeath('c', 'c', false, T0 + 3_000L);
        assertTrue(r.hudCue(T0 + 5_000L));
        r.reset();
        assertFalse(r.hudCue(T0 + 5_000L));
        // a teammate death after the reset, with no own death recorded since, gives no cue
        r.onDeath('c', 'c', false, T0 + 5_000L);
        assertTrue(r.active(T0 + 6_000L));
        assertFalse(r.hudCue(T0 + 6_000L));
    }

    @Test
    public void leadingColourReadsTheFirstTeamCode() {
        assertEquals('c', SplitRules.leadingColour("\u00a7r\u00a7cSteve \u00a7r\u00a77was killed by \u00a7r\u00a79Alex\u00a7r\u00a77."));
        assertEquals('c', SplitRules.leadingColour("\u00a7r\u00a7CSteve \u00a7r\u00a77was killed by Alex."));
        assertEquals('9', SplitRules.leadingColour("\u00a7l\u00a79Steve fell into the void."));
        assertEquals(0, SplitRules.leadingColour("Steve fell into the void."));
        assertEquals(0, SplitRules.leadingColour("\u00a7r\u00a7lSteve"));
        assertEquals(0, SplitRules.leadingColour("trailing \u00a7"));
        assertEquals(0, SplitRules.leadingColour(""));
        assertEquals(0, SplitRules.leadingColour(null));
    }
}
