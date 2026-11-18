package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Free camera (G43, Q1, Q2): speed levels, WASD by yaw, Space/Shift up/down, drag look with vanilla's curve. */
public class FreeCameraMathTest {

    private static final double EPS = 1e-9;

    @Test
    public void speedLevelsRunFromAboutThreeAndAHalfToSeventyFiveBlocksPerSecond() {
        assertEquals(4, FreeCameraMath.DEFAULT_LEVEL);
        assertEquals(10.0, FreeCameraMath.blocksPerSecond(4), EPS);
        assertEquals(10.0 / (1.4 * 1.4 * 1.4), FreeCameraMath.blocksPerSecond(1), EPS);
        assertEquals(10.0 * Math.pow(1.4, 6), FreeCameraMath.blocksPerSecond(10), EPS);
        assertEquals(3.64, FreeCameraMath.blocksPerSecond(1), 0.01);
        assertEquals(75.3, FreeCameraMath.blocksPerSecond(10), 0.1);
        for (int l = 1; l < 10; l++) {
            assertEquals(1.4, FreeCameraMath.blocksPerSecond(l + 1) / FreeCameraMath.blocksPerSecond(l), EPS);
        }
        assertEquals("out of range levels clamp", FreeCameraMath.blocksPerSecond(1),
                FreeCameraMath.blocksPerSecond(0), EPS);
        assertEquals(FreeCameraMath.blocksPerSecond(10), FreeCameraMath.blocksPerSecond(11), EPS);
    }

    @Test
    public void scrollStepsOneLevelPerNotchAndClamps() {
        assertEquals(5, FreeCameraMath.scrolled(4, 120));
        assertEquals(3, FreeCameraMath.scrolled(4, -120));
        assertEquals(5, FreeCameraMath.scrolled(4, 1));
        assertEquals(4, FreeCameraMath.scrolled(4, 0));
        assertEquals(10, FreeCameraMath.scrolled(10, 120));
        assertEquals(1, FreeCameraMath.scrolled(1, -120));
    }

    @Test
    public void axisFromTwoKeys() {
        assertEquals(1, FreeCameraMath.axis(true, false));
        assertEquals(-1, FreeCameraMath.axis(false, true));
        assertEquals(0, FreeCameraMath.axis(true, true));
        assertEquals(0, FreeCameraMath.axis(false, false));
    }

    @Test
    public void wasdMovesHorizontallyByYaw() {
        FreeCameraMath.Camera c = new FreeCameraMath.Camera();
        c.pitch = 60f; // pitch never tilts the horizontal move
        FreeCameraMath.move(c, 1, 0, 0, 4, 1.0);
        assertEquals("yaw 0 faces +z", 10.0, c.z, EPS);
        assertEquals(0.0, c.x, EPS);
        assertEquals(0.0, c.y, EPS);

        c = new FreeCameraMath.Camera();
        c.yaw = 90f;
        FreeCameraMath.move(c, 1, 0, 0, 4, 0.5);
        assertEquals("yaw 90 faces -x", -5.0, c.x, 1e-6);
        assertEquals(0.0, c.z, 1e-6);

        c = new FreeCameraMath.Camera();
        FreeCameraMath.move(c, 0, 1, 0, 4, 1.0);
        assertEquals("A (strafe +1) at yaw 0 goes +x", 10.0, c.x, 1e-6);

        c = new FreeCameraMath.Camera();
        FreeCameraMath.move(c, -1, -1, 0, 4, 1.0);
        assertEquals("a diagonal is not faster", 10.0, Math.hypot(c.x, c.z), 1e-6);
        assertEquals(-Math.sqrt(50), c.z, 1e-6);
        assertEquals(-Math.sqrt(50), c.x, 1e-6);
    }

    @Test
    public void spaceAndShiftFlyUpAndDown() {
        FreeCameraMath.Camera c = new FreeCameraMath.Camera();
        FreeCameraMath.move(c, 0, 0, FreeCameraMath.axis(true, false), 4, 0.25);
        assertEquals(2.5, c.y, EPS);
        FreeCameraMath.move(c, 0, 0, FreeCameraMath.axis(false, true), 1, 1.0);
        assertEquals(2.5 - FreeCameraMath.blocksPerSecond(1), c.y, EPS);
        FreeCameraMath.move(c, 1, 0, 1, 4, 1.0);
        assertEquals("vertical keeps its own speed beside a horizontal move", 12.5 - FreeCameraMath.blocksPerSecond(1),
                c.y, EPS);
        assertEquals(10.0, c.z, EPS);
    }

    @Test
    public void noKeysOrNoTimeDoesNotMove() {
        FreeCameraMath.Camera c = new FreeCameraMath.Camera();
        c.x = 1;
        c.y = 2;
        c.z = 3;
        FreeCameraMath.move(c, 0, 0, 0, 10, 1.0);
        FreeCameraMath.move(c, 1, 1, 1, 10, 0.0);
        assertEquals(1, c.x, EPS);
        assertEquals(2, c.y, EPS);
        assertEquals(3, c.z, EPS);
    }

    @Test
    public void dragLookUsesVanillasSensitivityCurve() {
        FreeCameraMath.Camera c = new FreeCameraMath.Camera();
        // Sensitivity 0.5: f = 0.5, f^3 x 8 = 1, so a pixel turns 0.15 degrees.
        FreeCameraMath.look(c, 100, 0, 0.5f, false);
        assertEquals(15f, c.yaw, 1e-4f);
        FreeCameraMath.look(c, 0, 100, 0.5f, false);
        assertEquals("mouse up looks up", -15f, c.pitch, 1e-4f);
        FreeCameraMath.look(c, 0, 100, 0.5f, true);
        assertEquals("inverted", 0f, c.pitch, 1e-4f);

        FreeCameraMath.Camera d = new FreeCameraMath.Camera();
        float s = 1f; // f = 0.8
        double f = s * 0.6 + 0.2;
        FreeCameraMath.look(d, 10, 0, s, false);
        assertEquals((float) (10 * f * f * f * 8 * 0.15), d.yaw, 1e-4f);
    }

    @Test
    public void pitchIsClampedYawIsNot() {
        FreeCameraMath.Camera c = new FreeCameraMath.Camera();
        FreeCameraMath.look(c, 3000, -3000, 0.5f, false);
        assertEquals(90f, c.pitch, 0f);
        assertEquals(450f, c.yaw, 1e-3f);
        FreeCameraMath.look(c, 0, 3000, 0.5f, false);
        assertEquals(-90f, c.pitch, 0f);
    }

    @Test
    public void theSpeedBarShowsOneSecondThenFades() {
        assertEquals(1f, FreeCameraMath.speedBarAlpha(0), 0f);
        assertEquals(1f, FreeCameraMath.speedBarAlpha(999), 0f);
        assertEquals(0.5f, FreeCameraMath.speedBarAlpha(1100), 1e-6f);
        assertEquals(0f, FreeCameraMath.speedBarAlpha(1200), 0f);
        assertEquals(0f, FreeCameraMath.speedBarAlpha(Long.MAX_VALUE), 0f);
        assertEquals(0f, FreeCameraMath.speedBarAlpha(-1), 0f);
    }
}
