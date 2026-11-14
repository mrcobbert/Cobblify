package com.bedwarsqol.feature;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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
    public void breaksAnythingButBlastProofBlocks() {
        assertTrue(ClutchRules.breaks("minecraft:wool"));
        assertTrue(ClutchRules.breaks("minecraft:planks"));
        assertTrue(ClutchRules.breaks("minecraft:stained_hardened_clay"));
        assertTrue(ClutchRules.breaks("minecraft:dirt"));
        assertTrue(ClutchRules.breaks("minecraft:leaves"));
        assertTrue(ClutchRules.breaks("minecraft:stone"));
        assertFalse(ClutchRules.breaks("minecraft:end_stone"));
        assertFalse(ClutchRules.breaks("minecraft:obsidian"));
        assertFalse(ClutchRules.breaks("minecraft:bedrock"));
        assertFalse(ClutchRules.breaks("minecraft:glass"));
        assertFalse(ClutchRules.breaks("minecraft:stained_glass"));
        assertFalse(ClutchRules.breaks("minecraft:bed"));
        assertFalse(ClutchRules.breaks(null));
    }

    // ---- block ray-cast (vanilla 1.8.9 resistances: Block.getExplosionResistance = resistance / 5) ----

    private static final float LEAVES = 0.2f, DIRT = 0.5f, WOOL = 0.8f, PLANKS = 3f, CLAY = 4.2f, STONE = 6f,
            END_STONE = 9f;

    /** A world of air holding the blocks put into it. */
    private static final class Grid implements ClutchRules.Resistance {
        private final Map<String, Float> blocks = new HashMap<String, Float>();

        Grid put(int x, int y, int z, float resistance) {
            blocks.put(x + "," + y + "," + z, resistance);
            return this;
        }

        /** Fills the inclusive box with one block type. */
        Grid box(int x0, int y0, int z0, int x1, int y1, int z1, float resistance) {
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) put(x, y, z, resistance);
            return this;
        }

        @Override
        public float at(int x, int y, int z) {
            Float r = blocks.get(x + "," + y + "," + z);
            return r == null ? -1f : r;
        }
    }

    private static Set<String> blast(Grid grid, double x, double y, double z, long seed) {
        Set<String> out = new HashSet<String>();
        for (int[] p : ClutchRules.blastBlocks(x, y, z, ClutchRules.EXPLOSION_POWER, new Random(seed), grid)) {
            out.add(p[0] + "," + p[1] + "," + p[2]);
        }
        return out;
    }

    /** Hypixel fireball jump: the blast sits 0.62 above the floor (recording, throw 6). */
    @Test
    public void fireballJumpAlwaysBreaksTheBlockUnderYou() {
        for (float floor : new float[]{WOOL, DIRT, LEAVES}) {
            Grid grid = new Grid().box(-4, 77, -4, 4, 79, 4, floor);
            for (long seed = 0; seed < 40; seed++) {
                Set<String> hit = blast(grid, 0.5, 80.62, 0.5, seed);
                assertTrue("floor " + floor + " seed " + seed, hit.contains("0,79,0"));
                // A single fireball digs a shallow crater, never three layers down.
                for (String p : hit) assertFalse(p, p.split(",")[1].equals("77"));
            }
        }
    }

    @Test
    public void woolWallNextToTheBlastLosesItsMiddle() {
        Grid grid = new Grid().box(-2, 79, 2, 2, 83, 2, WOOL);
        for (long seed = 0; seed < 40; seed++) {
            assertTrue("seed " + seed, blast(grid, 0.5, 81.5, 1.0, seed).contains("0,81,2"));
        }
        // Out of reach: the strongest ray (2.6) is spent after 12 steps, 3.6 blocks.
        for (long seed = 0; seed < 40; seed++) assertTrue(blast(grid, 0.5, 81.5, -2.0, seed).isEmpty());
    }

    @Test
    public void glassShieldsTheWallBehindIt() {
        Grid open = new Grid().box(-3, 78, 3, 3, 84, 3, WOOL);
        Grid shielded = new Grid().box(-3, 78, 3, 3, 84, 3, WOOL).box(-4, 77, 2, 4, 85, 2, ClutchRules.SHIELD_RESISTANCE);
        int openHits = 0;
        for (long seed = 0; seed < 40; seed++) {
            openHits += blast(open, 0.5, 81.5, 1.2, seed).size();
            for (String p : blast(shielded, 0.5, 81.5, 1.2, seed)) assertFalse("wool behind glass broke: " + p, p.endsWith(",3"));
        }
        assertTrue(openHits > 0);
    }

    @Test
    public void endStoneNeverBreaksAndWoodIsHarderThanWool() {
        Grid endStone = new Grid().box(-3, 77, -3, 3, 79, 3, END_STONE);
        for (long seed = 0; seed < 40; seed++) assertTrue(blast(endStone, 0.5, 80.02, 0.5, seed).isEmpty());

        int wool = 0, planks = 0, clay = 0, stone = 0;
        for (long seed = 0; seed < 40; seed++) {
            wool += blast(new Grid().box(-3, 77, -3, 3, 79, 3, WOOL), 0.5, 80.62, 0.5, seed).size();
            planks += blast(new Grid().box(-3, 77, -3, 3, 79, 3, PLANKS), 0.5, 80.62, 0.5, seed).size();
            clay += blast(new Grid().box(-3, 77, -3, 3, 79, 3, CLAY), 0.5, 80.62, 0.5, seed).size();
            stone += blast(new Grid().box(-3, 77, -3, 3, 79, 3, STONE), 0.5, 80.62, 0.5, seed).size();
        }
        assertTrue(wool > planks && planks > clay && clay >= stone);
        assertTrue("wood breaks sometimes", planks > 0);
    }

    @Test
    public void airIsNeverListed() {
        for (long seed = 0; seed < 5; seed++) assertTrue(blast(new Grid(), 0.5, 80.5, 0.5, seed).isEmpty());
    }

    @Test
    public void glassShieldsTheBlast() {
        assertTrue(ClutchRules.shieldsBlast("minecraft:glass"));
        assertTrue(ClutchRules.shieldsBlast("minecraft:stained_glass"));
        assertTrue(ClutchRules.shieldsBlast("minecraft:stained_glass_pane"));
        assertFalse(ClutchRules.shieldsBlast("minecraft:wool"));
        assertFalse(ClutchRules.shieldsBlast(null));
        // One ray step into a shield drains more than the strongest ray (power x 1.3) carries.
        assertTrue((ClutchRules.SHIELD_RESISTANCE + 0.3f) * 0.3f > ClutchRules.EXPLOSION_POWER * 1.3f);
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
