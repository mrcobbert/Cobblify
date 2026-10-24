package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pins the Clutch Practice rules: cooldown, block whitelist, void reset, blast and flight. */
public class ClutchRulesTest {

    @Test
    public void cooldownIsHalfASecond() {
        assertTrue(ClutchRules.cooldownReady(100, -1));
        assertFalse(ClutchRules.cooldownReady(105, 100));
        assertFalse(ClutchRules.cooldownReady(109, 100));
        assertTrue(ClutchRules.cooldownReady(110, 100));
        // World time going backwards (/time set) never locks throws out.
        assertTrue(ClutchRules.cooldownReady(50, 100));
    }

    @Test
    public void breaksOnlyCheapDefenceBlocks() {
        assertTrue(ClutchRules.breaks("minecraft:wool"));
        assertTrue(ClutchRules.breaks("minecraft:planks"));
        assertTrue(ClutchRules.breaks("minecraft:stained_hardened_clay"));
        assertFalse(ClutchRules.breaks("minecraft:end_stone"));
        assertFalse(ClutchRules.breaks("minecraft:obsidian"));
        assertFalse(ClutchRules.breaks("minecraft:glass"));
        assertFalse(ClutchRules.breaks("minecraft:stained_glass"));
        assertFalse(ClutchRules.breaks("minecraft:bed"));
        assertFalse(ClutchRules.breaks(null));
    }

    @Test
    public void voidResetNeedsSafeGroundAndARealFall() {
        assertFalse(ClutchRules.shouldVoidReset(-10, false, 0));
        assertFalse(ClutchRules.shouldVoidReset(60, true, 70));
        assertFalse(ClutchRules.shouldVoidReset(30.5, true, 70));
        assertTrue(ClutchRules.shouldVoidReset(29.9, true, 70));
        assertTrue(ClutchRules.shouldVoidReset(-0.1, true, 20));
    }

    @Test
    public void knockbackFallsOffAndLiftsAtTheCentre() {
        assertNull(ClutchRules.knockback(4.0, 0, 0));
        double[] under = ClutchRules.knockback(0, 0, 0);
        assertEquals(0, under[0], 1e-9);
        assertEquals(ClutchRules.KNOCKBACK + ClutchRules.LIFT, under[1], 1e-9);
        double[] side = ClutchRules.knockback(2.0, 0, 0);
        assertEquals(ClutchRules.KNOCKBACK * 0.5, side[0], 1e-9);
        assertEquals(ClutchRules.LIFT * 0.5, side[1], 1e-9);
        assertEquals(ClutchRules.MAX_DAMAGE, ClutchRules.damage(0), 1e-6);
        assertEquals(0, ClutchRules.damage(5), 1e-6);
    }

    @Test
    public void steadyAccelerationHoldsSpeed() {
        double speed = 2.5;
        double[] a = ClutchRules.steadyAcceleration(0, 1, 0, speed);
        double v = speed;
        for (int i = 0; i < 200; i++) {
            v = (v + a[1]) * ClutchRules.FIREBALL_DRAG;
        }
        assertEquals(speed, v, 1e-9);
        assertEquals(0, a[0], 0);
    }
}
