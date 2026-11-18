package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Motion Blur rules: off by default at Strength 5, the strength table whose middle is PolyBlur's default world
 * strength 3 at its 240 FPS reference, taps from the smear length, the yaw wrap and the off switch.
 */
public class MotionBlurRulesTest {

    private static final double EPS = 1e-12;

    @Test
    public void defaultsOffMiddleFive() {
        assertFalse(MotionBlurRules.DEFAULT_ENABLED);
        assertEquals(1, MotionBlurRules.MIN_STRENGTH);
        assertEquals(9, MotionBlurRules.MAX_STRENGTH);
        assertEquals(5, MotionBlurRules.DEFAULT_STRENGTH);
        assertEquals("the default is the middle of the slider",
                (MotionBlurRules.MIN_STRENGTH + MotionBlurRules.MAX_STRENGTH) / 2, MotionBlurRules.DEFAULT_STRENGTH);
        assertEquals(1, MotionBlurRules.clampStrength(0));
        assertEquals(1, MotionBlurRules.clampStrength(-40));
        assertEquals(9, MotionBlurRules.clampStrength(12));
        assertEquals(5, MotionBlurRules.clampStrength(5));
    }

    @Test
    public void middleMatchesPolyBlurDefaults() {
        assertEquals(3.0, MotionBlurRules.polyBlurStrength(5), EPS);
        assertEquals(0.024, MotionBlurRules.maxSmear(5), EPS);
        assertEquals(1.0 / 240.0, MotionBlurRules.exposureSeconds(5), EPS);
        assertEquals(7, MotionBlurRules.maxTaps(5));
        // The ends of the table.
        assertEquals(0.9, MotionBlurRules.polyBlurStrength(1), EPS);
        assertEquals(10.0, MotionBlurRules.polyBlurStrength(9), EPS);
        assertEquals(0.080, MotionBlurRules.maxSmear(9), EPS);
        assertEquals(4.0 / 240.0, MotionBlurRules.exposureSeconds(9), EPS);
    }

    @Test
    public void strengthTableMatchesThePlan() {
        double[] pw = {0.9, 1.2, 1.6, 2.2, 3, 4.1, 5.5, 7.4, 10};
        double[] smear = {.007, .010, .013, .018, .024, .032, .044, .059, .080};
        double[] exposureMs = {4.17, 4.17, 4.17, 4.17, 4.17, 6.05, 8.59, 12.0, 16.7};
        int[] taps = {5, 5, 5, 6, 7, 8, 10, 12, 16};
        for (int s = 1; s <= 9; s++) {
            assertEquals("Pw " + s, pw[s - 1], MotionBlurRules.polyBlurStrength(s), 0.05);
            assertEquals("max smear " + s, smear[s - 1], MotionBlurRules.maxSmear(s), 0.0005);
            assertEquals("exposure " + s, exposureMs[s - 1], MotionBlurRules.exposureSeconds(s) * 1000, 0.05);
            assertEquals("max taps " + s, taps[s - 1], MotionBlurRules.maxTaps(s));
        }
    }

    @Test
    public void strengthMonotonic() {
        for (int s = 2; s <= 9; s++) {
            assertTrue("Pw " + s, MotionBlurRules.polyBlurStrength(s) > MotionBlurRules.polyBlurStrength(s - 1));
            assertTrue("max smear " + s, MotionBlurRules.maxSmear(s) > MotionBlurRules.maxSmear(s - 1));
            assertTrue("exposure " + s, MotionBlurRules.exposureSeconds(s) >= MotionBlurRules.exposureSeconds(s - 1));
            assertTrue("max taps " + s, MotionBlurRules.maxTaps(s) >= MotionBlurRules.maxTaps(s - 1));
        }
        // Out-of-range strengths read as the nearest end.
        assertEquals(MotionBlurRules.maxSmear(1), MotionBlurRules.maxSmear(0), 0);
        assertEquals(MotionBlurRules.maxSmear(9), MotionBlurRules.maxSmear(15), 0);
    }

    @Test
    public void tapsFollowSmear() {
        int max5 = MotionBlurRules.maxTaps(5);
        assertEquals("never fewer than two", 2, MotionBlurRules.taps(0.5, max5));
        assertEquals(2, MotionBlurRules.taps(1.0, max5));
        assertEquals(2, MotionBlurRules.taps(12.0, max5));
        assertEquals("one tap per 6 px, rounded up", 3, MotionBlurRules.taps(12.5, max5));
        assertEquals(4, MotionBlurRules.taps(24.0, max5));
        assertEquals(7, MotionBlurRules.taps(42.0, max5));
        assertEquals("capped at the strength's max", 7, MotionBlurRules.taps(46.08, max5));
        assertEquals(7, MotionBlurRules.taps(1000.0, max5));
        assertEquals(16, MotionBlurRules.taps(204.8, MotionBlurRules.maxTaps(9)));
        for (double px = 1.0; px < 300; px += 0.25) {
            assertTrue(MotionBlurRules.taps(px + 0.25, max5) >= MotionBlurRules.taps(px, max5));
        }
    }

    @Test
    public void yawWrapsAcross180() {
        assertEquals(2.0, MotionBlurRules.wrapDegrees(-179.0 - 179.0), EPS);
        assertEquals(-2.0, MotionBlurRules.wrapDegrees(179.0 - -179.0), EPS);
        assertEquals(20.0, MotionBlurRules.wrapDegrees(10.0 - 350.0), EPS);
        assertEquals(-20.0, MotionBlurRules.wrapDegrees(350.0 - 10.0), EPS);
        assertEquals(5.0, MotionBlurRules.wrapDegrees(725.0 - 720.0), EPS);
        assertEquals(1.0, MotionBlurRules.wrapDegrees(-719.0 - -360.0), EPS);
        assertEquals(0.0, MotionBlurRules.wrapDegrees(0.0), EPS);
        assertEquals(180.0, Math.abs(MotionBlurRules.wrapDegrees(180.0)), EPS);
    }

    @Test
    public void offNeverRuns() {
        for (int bits = 0; bits < 16; bits++) {
            assertFalse(Integer.toBinaryString(bits), MotionBlurRules.shouldRun(false, (bits & 1) != 0,
                    (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0));
        }
        assertTrue(MotionBlurRules.shouldRun(true, false, true, false, false));
        assertFalse("no world", MotionBlurRules.shouldRun(true, false, false, false, false));
        assertFalse("anaglyph", MotionBlurRules.shouldRun(true, false, true, true, false));
        assertFalse("failed", MotionBlurRules.shouldRun(true, false, true, false, true));
    }

    @Test
    public void suppressedNeverRuns() {
        for (int bits = 0; bits < 16; bits++) {
            assertFalse(Integer.toBinaryString(bits), MotionBlurRules.shouldRun((bits & 1) != 0, true,
                    (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0));
        }
    }

    @Test
    public void smearIsAngleOverFovAndStopTailFollowsThePlan() {
        // 300 deg/s at s = 5 for 1/240 s is 1.25 deg; on a 70 deg tall, 16:9 view that is 1.25 / (70 * 16/9) of the width.
        double aspect = 1920.0 / 1080.0;
        assertEquals(1.25 / (70 * aspect), MotionBlurRules.smearX(300, MotionBlurRules.exposureSeconds(5), 70, aspect), EPS);
        assertEquals(1.25 / 70, MotionBlurRules.smearY(300, MotionBlurRules.exposureSeconds(5), 70), EPS);
        assertEquals(46.08, MotionBlurRules.maxSmearPx(5, 1920, 1080), 1e-9);
        assertEquals(204.8, MotionBlurRules.maxSmearPx(9, 2560, 1440), 1e-9);
        assertEquals(0.008 * Math.log(460.8) / Math.log(2), MotionBlurRules.stopTailSeconds(46.08), 1e-12);
        assertTrue(MotionBlurRules.stopTailSeconds(MotionBlurRules.maxSmearPx(9, 2560, 1440)) <= 0.090);
        assertEquals(1.0, MotionBlurRules.ON_PX, 0);
        assertEquals(0.1, MotionBlurRules.OFF_PX, 0);
    }

    /** Code review round 1 I1: a diagonal turn must smear along the scene's motion, not its mirror image. */
    @Test
    public void diagonalTurnSmearsAlongSceneMotionNotItsReflection() {
        double exposure = MotionBlurRules.exposureSeconds(5);
        double aspect = 1920.0 / 1080.0;
        // Yaw and pitch both increasing: vanilla moves scene detail left (u falls) and up (v rises).
        double u = MotionBlurRules.textureAxisU(MotionBlurRules.smearX(200, exposure, 70, aspect));
        double v = MotionBlurRules.textureAxisV(MotionBlurRules.smearY(200, exposure, 70), 0);
        assertTrue("u and v have opposite signs for same-sign yaw and pitch rates", u * v < 0);
        assertTrue(u < 0);
        assertTrue(v > 0);
        // Opposite-sign rates smear along the other diagonal.
        double u2 = MotionBlurRules.textureAxisU(MotionBlurRules.smearX(200, exposure, 70, aspect));
        double v2 = MotionBlurRules.textureAxisV(MotionBlurRules.smearY(-200, exposure, 70), 0);
        assertTrue(u2 * v2 > 0);
        // Pure turns keep their axis and length.
        assertEquals(0.0, MotionBlurRules.textureAxisV(MotionBlurRules.smearY(0, exposure, 70), 0), 0);
        assertEquals(Math.abs(MotionBlurRules.smearX(300, exposure, 70, aspect)),
                Math.abs(MotionBlurRules.textureAxisU(MotionBlurRules.smearX(300, exposure, 70, aspect))), 0);
    }

    /** Code review round 2 I1: the front third-person camera mirrors the vertical scene motion only. */
    @Test
    public void frontThirdPersonFlipsOnlyTheVerticalComponent() {
        double exposure = MotionBlurRules.exposureSeconds(5);
        double aspect = 1920.0 / 1080.0;
        double sx = MotionBlurRules.smearX(200, exposure, 70, aspect);
        double sy = MotionBlurRules.smearY(200, exposure, 70);
        double u = MotionBlurRules.textureAxisU(sx);
        // First person (0) and back third person (1): (-x, +y).
        for (int perspective = 0; perspective <= 1; perspective++) {
            double v = MotionBlurRules.textureAxisV(sy, perspective);
            assertTrue(u < 0);
            assertTrue(v > 0);
        }
        // Front third person (2): yaw and pitch both increasing move distant detail left and down, (-x, -y).
        double front = MotionBlurRules.textureAxisV(sy, MotionBlurRules.PERSPECTIVE_FRONT);
        assertTrue(front < 0);
        assertTrue("same-sign components in front view", u * front > 0);
        assertEquals(Math.abs(sy), Math.abs(front), 0);
    }
}
