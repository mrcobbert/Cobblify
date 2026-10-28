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

    /*
     * Hypixel fixtures from the 2026-10-27 recording (/Users/Shared/2026-10-27_17-46-54.jsonl),
     * standing throws only, where the server saw exactly the client's position. Each row: feet minus
     * blast (S27 position) {dx, dy, dz}, then the S12 velocity Hypixel set {vx, vy, vz}.
     */
    private static final double[][] HYPIXEL_BLASTS = {
            {0.0, -0.6200, 0.0, 0.0, 1.645, 0.0},                    // throw 6: straight down, standing
            {0.0, 0.1332, 0.0, 0.0, 1.766625, 0.0},                  // throw 12: down, first jump tick (server pos)
            {-0.1290, -0.0141, 1.2596, -0.107375, 1.028125, 1.048875}, // throw 26
            {-0.0117, -0.2201, 1.4907, -0.009125, 0.797375, 1.163125}, // throw 27
            {-0.0527, -0.5198, 1.7232, -0.032375, 0.5105, 1.233625},   // throw 35
            {-0.1100, -0.2863, 2.8322, -0.043375, 0.31775, 1.035125},  // throw 36
    };

    /** Eye x/y/z, yaw, pitch, then feet x/y/z, for the standing throws above (ground top at y = 80). */
    private static final double[][] HYPIXEL_THROWS = {
            {1400.549394865202, 81.62000000476837, 3.539090824562182, -4.2000003, 90.0, 80.0},
            {1400.6236735842756, 81.62000000476837, 3.389511542505805, -174.1501, 51.74993, 80.0},
            {1400.5301791786856, 81.62000000476837, 3.6252593078722755, -179.55002, 43.199997, 80.0},
            {1400.199057484735, 81.62000000476837, 3.140463545426316, -178.49991, 32.55, 80.0},
            {1400.3013680277147, 81.62000000476837, 3.237653174248381, -177.6, 25.200022, 80.0},
    };
    private static final double[][] HYPIXEL_THROW_S12 = {
            {0.0, 1.645, 0.0},
            {-0.107375, 1.028125, 1.048875},
            {-0.009125, 0.797375, 1.163125},
            {-0.032375, 0.5105, 1.233625},
            {-0.043375, 0.31775, 1.035125},
    };

    @Test
    public void knockbackMatchesHypixelBlasts() {
        for (double[] row : HYPIXEL_BLASTS) {
            double[] v = ClutchRules.knockback(row[0], row[1], row[2]);
            for (int i = 0; i < 3; i++) assertEquals("blast " + java.util.Arrays.toString(row), row[3 + i], v[i], 0.03);
        }
    }

    @Test
    public void knockbackRangeAndFlatDamage() {
        assertNull(ClutchRules.knockback(4.0, 0, 0));
        double[] under = ClutchRules.knockback(0, 0, 0);
        assertEquals(ClutchRules.KNOCKBACK, under[1], 1e-9);
        // Hypixel: every blast in the recording cost exactly 2 health, 0.6 to 2.9 blocks away.
        assertEquals(2.0f, ClutchRules.damage(0.6), 0);
        assertEquals(2.0f, ClutchRules.damage(2.9), 0);
        assertEquals(0, ClutchRules.damage(5), 0);
    }

    @Test
    public void fireballFlightMatchesHypixel() {
        double[] l = ClutchRules.launch(0, 0, 1);
        assertEquals(1.0, l[2], 0);
        assertEquals(0.1, l[5], 0);
        // Fireball S12 speeds Hypixel sent, tick by tick, for an open-air throw.
        double[] hypixel = {1.0449, 1.0876, 1.1282, 1.1668, 1.2034};
        double v = l[2];
        for (double expected : hypixel) {
            v = (v + l[5]) * ClutchRules.FIREBALL_DRAG;
            assertEquals(expected, v, 2e-4);
        }
    }

    /**
     * Replays each standing Hypixel throw end to end with our rules: launch from the eye, fly the
     * vanilla way over flat ground, blast at the fireball's position on the tick its move would
     * reach the ground (vanilla {@code onImpact} order), then knockback on the feet.
     */
    @Test
    public void standingThrowsReplayToHypixelVelocity() {
        for (int k = 0; k < HYPIXEL_THROWS.length; k++) {
            double[] t = HYPIXEL_THROWS[k];
            double yaw = Math.toRadians(t[3]), pitch = Math.toRadians(t[4]);
            double lx = -Math.sin(yaw) * Math.cos(pitch), ly = -Math.sin(pitch), lz = Math.cos(yaw) * Math.cos(pitch);
            double[] l = ClutchRules.launch(lx, ly, lz);
            double px = t[0], py = t[1], pz = t[2], mx = l[0], my = l[1], mz = l[2];
            for (int i = 0; i < 20 && py + my > t[5]; i++) {
                px += mx;
                py += my;
                pz += mz;
                mx = (mx + l[3]) * ClutchRules.FIREBALL_DRAG;
                my = (my + l[4]) * ClutchRules.FIREBALL_DRAG;
                mz = (mz + l[5]) * ClutchRules.FIREBALL_DRAG;
            }
            double[] v = ClutchRules.knockback(t[0] - px, t[5] - py, t[2] - pz);
            for (int i = 0; i < 3; i++) assertEquals("throw " + k, HYPIXEL_THROW_S12[k][i], v[i], 0.04);
        }
    }
}
