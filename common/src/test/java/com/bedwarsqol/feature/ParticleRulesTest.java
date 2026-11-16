package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Particles: off by default, Always Crit and Hide Own Potion on, Hide Block Break off, Multiplier 2x. */
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
}
