package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Particles: off by default, Always Crit and Hide Own Potion on, Hide Block Break off, Multiplier 2x; the extra
 * hit particles, the crit/sharpness multiplier and the two hide rules, each keeping vanilla while off.
 */
public class ParticleRulesTest {

    @Test
    public void defaults() {
        assertFalse(ParticleRules.DEFAULT_ENABLED);
        assertTrue(ParticleRules.DEFAULT_ALWAYS_CRIT);
        assertTrue(ParticleRules.DEFAULT_HIDE_OWN_POTION);
        assertFalse(ParticleRules.DEFAULT_HIDE_BLOCK_BREAK);
        assertEquals(2, ParticleRules.DEFAULT_MULTIPLIER);
        assertEquals(1, ParticleRules.MIN_MULTIPLIER);
        assertEquals(3, ParticleRules.MAX_MULTIPLIER);
    }

    @Test
    public void aRowIsEffectiveOnlyWhileTheModuleIsOn() {
        assertTrue(ParticleRules.effective(true, true));
        assertFalse(ParticleRules.effective(true, false));
        assertFalse(ParticleRules.effective(false, true));
        assertFalse(ParticleRules.effective(false, false));
    }

    @Test
    public void multiplierIsClampedToOneThroughThree() {
        assertEquals(1, ParticleRules.clampMultiplier(1));
        assertEquals(2, ParticleRules.clampMultiplier(2));
        assertEquals(3, ParticleRules.clampMultiplier(3));
        assertEquals(1, ParticleRules.clampMultiplier(0));
        assertEquals(1, ParticleRules.clampMultiplier(-5));
        assertEquals(3, ParticleRules.clampMultiplier(4));
        assertEquals(3, ParticleRules.clampMultiplier(Integer.MAX_VALUE));
        assertEquals(1, ParticleRules.clampMultiplier(Integer.MIN_VALUE));
    }

    // ---- particle ids, as in EnumParticleTypes ----

    @Test
    public void particleIdsMatchVanilla() {
        assertEquals(9, ParticleRules.CRIT_ID);
        assertEquals(10, ParticleRules.CRIT_MAGIC_ID);
    }

    // ---- 124 + 125 Always Crit ----

    @Test
    public void alwaysCritAddsOnlyWhatVanillaDidNotShow() {
        int both = ParticleRules.EXTRA_CRIT | ParticleRules.EXTRA_MAGIC;
        assertEquals(both, ParticleRules.extraHitParticles(false, false, true));
        assertEquals(ParticleRules.EXTRA_MAGIC, ParticleRules.extraHitParticles(true, false, true));
        assertEquals(ParticleRules.EXTRA_CRIT, ParticleRules.extraHitParticles(false, true, true));
        assertEquals(0, ParticleRules.extraHitParticles(true, true, true));
    }

    @Test
    public void alwaysCritOffAddsNothing() {
        assertEquals(0, ParticleRules.extraHitParticles(false, false, false));
        assertEquals(0, ParticleRules.extraHitParticles(true, false, false));
        assertEquals(0, ParticleRules.extraHitParticles(false, true, false));
        assertEquals(0, ParticleRules.extraHitParticles(true, true, false));
    }

    @Test
    public void extraFlagsAreDistinctBits() {
        assertEquals(0, ParticleRules.EXTRA_CRIT & ParticleRules.EXTRA_MAGIC);
        assertTrue(ParticleRules.EXTRA_CRIT != 0 && ParticleRules.EXTRA_MAGIC != 0);
    }

    // ---- 131 Multiplier ----

    @Test
    public void multiplierAppliesToCritAndSharpness() {
        for (int mult = 1; mult <= 3; mult++) {
            assertEquals(mult, ParticleRules.copies(ParticleRules.CRIT_ID, mult, true));
            assertEquals(mult, ParticleRules.copies(ParticleRules.CRIT_MAGIC_ID, mult, true));
        }
    }

    @Test
    public void multiplierLeavesEveryOtherParticleAtOne() {
        for (int id = -1; id <= 50; id++) {
            if (id == ParticleRules.CRIT_ID || id == ParticleRules.CRIT_MAGIC_ID) continue;
            assertEquals("id " + id, 1, ParticleRules.copies(id, 3, true));
        }
    }

    @Test
    public void multiplierIsHeldToOneThroughThree() {
        assertEquals(1, ParticleRules.copies(ParticleRules.CRIT_ID, 0, true));
        assertEquals(1, ParticleRules.copies(ParticleRules.CRIT_ID, -4, true));
        assertEquals(3, ParticleRules.copies(ParticleRules.CRIT_MAGIC_ID, 10, true));
    }

    @Test
    public void multiplierOffIsOneCopy() {
        for (int mult = 1; mult <= 3; mult++) {
            assertEquals(1, ParticleRules.copies(ParticleRules.CRIT_ID, mult, false));
            assertEquals(1, ParticleRules.copies(ParticleRules.CRIT_MAGIC_ID, mult, false));
        }
    }

    // ---- 120 Hide Own Potion ----

    @Test
    public void ownPotionHiddenOnlyForYouInFirstPerson() {
        assertTrue(ParticleRules.hideOwnPotion(true, true, true));
        assertFalse(ParticleRules.hideOwnPotion(true, false, true));
        assertFalse(ParticleRules.hideOwnPotion(false, true, true));
        assertFalse(ParticleRules.hideOwnPotion(false, false, true));
    }

    @Test
    public void ownPotionOffHidesNothing() {
        assertFalse(ParticleRules.hideOwnPotion(true, true, false));
        assertFalse(ParticleRules.hideOwnPotion(true, false, false));
        assertFalse(ParticleRules.hideOwnPotion(false, true, false));
        assertFalse(ParticleRules.hideOwnPotion(false, false, false));
    }

    // ---- 132 Hide Block Break ----

    @Test
    public void blockBreakHiddenOnlyWhileOn() {
        assertTrue(ParticleRules.hideBlockBreak(true));
        assertFalse(ParticleRules.hideBlockBreak(false));
    }

    @Test
    public void moduleOffMakesEveryRowVanilla() {
        boolean on = ParticleRules.effective(false, true);
        assertEquals(0, ParticleRules.extraHitParticles(false, false, on));
        assertEquals(1, ParticleRules.copies(ParticleRules.CRIT_ID, 3, on));
        assertFalse(ParticleRules.hideOwnPotion(true, true, on));
        assertFalse(ParticleRules.hideBlockBreak(on));
    }
}
