package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Fullbright: off by default, a fixed-speed fade that reverses from where it is, and the lightmap lift. */
public class FullbrightRulesTest {

    private static final float EPS = 1e-4f;

    @Test
    public void offByDefault() {
        assertFalse(FullbrightRules.DEFAULT_ENABLED);
    }

    @Test
    public void toggleKeyName() {
        assertEquals("Toggle Fullbright", FullbrightRules.TOGGLE_KEY_NAME);
    }

    @Test
    public void fadeInTakes300Ms() {
        assertEquals(0.5f, FullbrightRules.step(0f, true, 150f), EPS);
        assertEquals(1f, FullbrightRules.step(0f, true, 300f), EPS);
        assertEquals(1f, FullbrightRules.step(0.5f, true, 150f), EPS);
    }

    @Test
    public void fadeOutTakes300Ms() {
        assertEquals(0.5f, FullbrightRules.step(1f, false, 150f), EPS);
        assertEquals(0f, FullbrightRules.step(1f, false, 300f), EPS);
    }

    @Test
    public void levelIsClamped() {
        assertEquals(1f, FullbrightRules.step(0.9f, true, 10_000f), 0f);
        assertEquals(0f, FullbrightRules.step(0.1f, false, 10_000f), 0f);
        assertEquals(1f, FullbrightRules.step(1f, true, 16f), 0f);
        assertEquals(0f, FullbrightRules.step(0f, false, 16f), 0f);
        assertEquals(1f, FullbrightRules.step(5f, true, 0f), 0f);
        assertEquals(0f, FullbrightRules.step(-5f, false, 0f), 0f);
    }

    @Test
    public void noTimeOrNegativeTimeKeepsTheLevel() {
        assertEquals(0.3f, FullbrightRules.step(0.3f, true, 0f), 0f);
        assertEquals(0.3f, FullbrightRules.step(0.3f, false, -50f), 0f);
        assertEquals(0.3f, FullbrightRules.step(0.3f, true, Float.NaN), 0f);
    }

    @Test
    public void toggleMidFadeReversesFromTheCurrentLevelWithNoJump() {
        float level = 0f;
        for (int i = 0; i < 10; i++) level = FullbrightRules.step(level, true, 15f);
        assertEquals(0.5f, level, EPS);
        float prev = level;
        for (int i = 0; i < 40; i++) {
            float next = FullbrightRules.step(prev, false, 15f);
            assertTrue("never rises after turning off", next <= prev);
            assertTrue("moves at most one frame's worth", prev - next <= 15f / 300f + EPS);
            prev = next;
        }
        assertEquals(0f, prev, 0f);
        float first = FullbrightRules.step(0.5f, false, 15f);
        assertEquals(0.5f - 15f / 300f, first, EPS);
    }

    @Test
    public void liftAtZeroIsIdentity() {
        int[] samples = {0xFF000000, 0xFF102030, 0xFFFFFFFF, 0x80123456, 0x00000000, 0xFF7F7F7F};
        for (int argb : samples) assertEquals(argb, FullbrightRules.lift(argb, 0f));
    }

    @Test
    public void liftAtOneIsWhiteWithAlphaKept() {
        assertEquals(0xFFFFFFFF, FullbrightRules.lift(0xFF000000, 1f));
        assertEquals(0xFFFFFFFF, FullbrightRules.lift(0xFF102030, 1f));
        assertEquals(0x80FFFFFF, FullbrightRules.lift(0x80123456, 1f));
    }

    @Test
    public void liftHalfwayMovesEachChannelHalfwayToWhite() {
        int out = FullbrightRules.lift(0xFF000000, 0.5f);
        assertEquals(0xFF, out >>> 24);
        assertEquals(128, (out >> 16) & 0xFF);
        assertEquals(128, (out >> 8) & 0xFF);
        assertEquals(128, out & 0xFF);
        int mixed = FullbrightRules.lift(0xFF0080FF, 0.5f);
        assertEquals(128, (mixed >> 16) & 0xFF);
        assertEquals(192, (mixed >> 8) & 0xFF);
        assertEquals(255, mixed & 0xFF);
    }

    @Test
    public void liftNeverDarkens() {
        for (int c = 0; c <= 255; c += 17) {
            int argb = 0xFF000000 | (c << 16) | (c << 8) | c;
            for (float l = 0f; l <= 1f; l += 0.1f) {
                int out = FullbrightRules.lift(argb, l);
                assertTrue((out & 0xFF) >= c);
            }
        }
    }
}
