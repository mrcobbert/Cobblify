package com.bedwarsqol.feature;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Motion Blur's per-frame state machine: the smear is the same at any frame rate (A4), a still camera never runs the
 * pass and a turn's pass ends within the plan's tail (the TASK's still-camera constraint, with hysteresis so a slow
 * drift never flickers), and every camera cut resets to an unblurred frame (A5).
 */
public class MotionBlurFrameTest {

    private static final double FOV = 70;
    private static final int[][] SIZES = {{1920, 1080}, {2560, 1440}};

    /** One run of frames over a fresh {@link MotionBlurFrame}, at the default view unless changed. */
    private static final class Run {
        final MotionBlurFrame frame = new MotionBlurFrame();
        final MotionBlurFrame.Sample s = new MotionBlurFrame.Sample();

        Run(int strength, int width, int height) {
            s.strength = strength;
            s.width = width;
            s.height = height;
            s.fov = FOV;
            s.world = 11;
            s.viewEntity = 22;
            s.perspective = 0;
            s.cutSerial = 0;
        }

        Run() {
            this(5, 1920, 1080);
        }

        /** One frame at {@code seconds}; returns whether the pass runs. */
        boolean at(double seconds, double yaw, double pitch) {
            s.nanos = nanos(seconds);
            s.yaw = yaw;
            s.pitch = pitch;
            return frame.update(s);
        }
    }

    private static long nanos(double seconds) {
        return Math.round(seconds * 1e9);
    }

    /** Frame {@code n}'s time at {@code fps}, on the same nanosecond grid {@link Run#at} uses. */
    private static double time(int n, int fps) {
        return nanos((double) n / fps) / 1e9;
    }

    /** The constant yaw speed whose smear is {@code px} on screen (16:9, FOV 70). */
    private static double speedForPx(double px, int strength, int width, int height) {
        double aspect = (double) width / height;
        return px / (MotionBlurRules.smearX(1.0, MotionBlurRules.exposureSeconds(strength), FOV, aspect) * width);
    }

    private static boolean speedIsZero(MotionBlurFrame f) {
        return f.speedYaw() == 0.0 && f.speedPitch() == 0.0;
    }

    // ------------------------------------------------------------------ A4: frame-rate independence

    @Test
    public void smearSameAt60_240_1000_2000Fps() {
        int[] rates = {60, 240, 1000, 2000};
        double aspect = 1920.0 / 1080.0;
        double exposure = MotionBlurRules.exposureSeconds(5);
        for (double omega : new double[]{300, 1500}) {
            for (double at : new double[]{0.050, 0.100}) {
                double ideal = Math.min(omega * (1 - Math.pow(2, -at / 0.008)) * exposure / (FOV * aspect),
                        MotionBlurRules.maxSmear(5));
                if (omega == 1500) assertEquals("1,500 deg/s is capped", MotionBlurRules.maxSmear(5), ideal, 0);
                else assertTrue("300 deg/s is under the cap", ideal < MotionBlurRules.maxSmear(5));
                for (int fps : rates) {
                    Run r = new Run();
                    int last = (int) Math.round(at * fps);
                    assertEquals("frame boundary", at, (double) last / fps, 1e-12);
                    for (int n = 0; n <= last; n++) {
                        double t = time(n, fps);
                        r.at(t, omega * t, 0);
                    }
                    String what = omega + " deg/s at " + fps + " FPS, " + at * 1000 + " ms";
                    assertTrue(what + " passes", r.frame.pass());
                    assertEquals(what, ideal, r.frame.smearX(), ideal * 1e-6);
                    assertEquals(what + " in px", ideal * 1920, r.frame.smearPx(), ideal * 1920 * 1e-6);
                    assertEquals(what, 0.0, r.frame.smearY(), 0);
                }
            }
        }
    }

    @Test
    public void jitteryMouseAt2000FpsSteady() {
        int fps = 2000;
        double omega = 300;
        Run r = new Run();
        double yaw = 0;
        double min = Double.MAX_VALUE, max = 0, sum = 0;
        int count = 0;
        for (int n = 0; n <= fps / 2; n++) {
            double t = time(n, fps);
            if (n > 0 && n % 2 == 0) yaw += 2 * omega / fps;
            r.at(t, yaw, 0);
            if (t >= 0.3) {
                double px = r.frame.smearPx();
                min = Math.min(min, px);
                max = Math.max(max, px);
                sum += px;
                count++;
                assertTrue(r.frame.pass());
            }
        }
        double mean = sum / count;
        assertTrue("ripple " + (max - min) / mean, (max - min) / mean < 0.10);
        double steady = omega * MotionBlurRules.exposureSeconds(5) / (FOV * 1920.0 / 1080.0) * 1920;
        assertEquals("averages the true speed", steady, mean, steady * 0.01);
    }

    // ------------------------------------------------------------------ still camera

    @Test
    public void stillCameraNeverRuns() {
        for (int fps : new int[]{60, 240, 1000, 2000}) {
            Run fresh = new Run();
            for (int n = 0; n <= fps; n++) {
                assertFalse("fresh, " + fps + " FPS, frame " + n, fresh.at(time(n, fps), 37.5, 12.0));
                assertFalse(fresh.frame.active());
            }

            Run afterReset = new Run();
            int turn = fps / 5;
            for (int n = 0; n <= turn; n++) afterReset.at(time(n, fps), 300 * time(n, fps), 0);
            assertTrue("turning before the reset", afterReset.frame.pass());
            double yaw = 300 * time(turn, fps);
            afterReset.s.world = 99;
            assertFalse(afterReset.at(time(turn + 1, fps), yaw, 0));
            assertEquals(MotionBlurFrame.RESET_WORLD, afterReset.frame.resetReason());
            for (int n = turn + 2; n <= turn + 1 + fps; n++) {
                assertFalse("after a reset, " + fps + " FPS, frame " + n, afterReset.at(time(n, fps), yaw, 0));
                assertTrue(speedIsZero(afterReset.frame));
            }
        }
    }

    @Test
    public void stopsWithinTailAfterTurn() {
        int[] rates = {60, 240, 1000};
        for (int s : new int[]{5, 9}) {
            for (int[] size : SIZES) {
                double bound = MotionBlurRules.stopTailSeconds(MotionBlurRules.maxSmearPx(s, size[0], size[1]));
                assertTrue("tail bound " + bound, bound <= 0.090);
                // A 300 deg/s turn for 500 ms, and a 10,000 deg/s flick for 50 ms (capped from its first frame).
                for (double[] turn : new double[][]{{300, 0.5}, {10000, 0.05}}) {
                    double omega = turn[0];
                    double stop = turn[1];
                    double refPx = Double.NaN;
                    for (int fps : rates) {
                        String what = omega + " deg/s, s=" + s + ", " + size[1] + "p, " + fps + " FPS";
                        Run r = new Run(s, size[0], size[1]);
                        int stopFrame = (int) Math.round(stop * fps);
                        assertEquals(what + " stop on a frame", stop, (double) stopFrame / fps, 1e-12);
                        double pxAtStop = Double.NaN;
                        double lastPass = Double.NaN;
                        int end = stopFrame + (int) Math.round(0.3 * fps);
                        for (int n = 0; n <= end; n++) {
                            double t = time(n, fps);
                            boolean pass = r.at(t, omega * Math.min(t, stop), 0);
                            if (n == stopFrame) {
                                assertTrue(what + " passes at the stop", pass);
                                pxAtStop = r.frame.smearPx();
                            }
                            if (pass) lastPass = t;
                        }
                        if (Double.isNaN(refPx)) refPx = pxAtStop;
                        assertEquals(what + " same smear at the stop at every rate", refPx, pxAtStop, refPx * 1e-6);
                        if (omega == 10000) {
                            assertEquals(what + " flick is capped", MotionBlurRules.maxSmearPx(s, size[0], size[1]),
                                    pxAtStop, pxAtStop * 1e-9);
                        }
                        // The pass ends when the decaying smear falls under 0.1 px: the same time at every rate.
                        double ideal = stop + 0.008 * Math.log(refPx / MotionBlurRules.OFF_PX) / Math.log(2);
                        double interval = 1.0 / fps;
                        assertTrue(what + " last pass " + lastPass + " <= " + ideal, lastPass <= ideal + 1e-9);
                        assertTrue(what + " last pass " + lastPass + " within a frame of " + ideal,
                                lastPass > ideal - interval - 1e-9);
                        assertTrue(what + " within the tail bound", lastPass - stop <= bound + 1e-9);
                        assertTrue(what + " within 90 ms", lastPass - stop <= 0.090);
                        // Every frame after it: no pass, the smoothed speed exactly 0.
                        int first = (int) Math.floor(lastPass * fps + 1e-6) + 1;
                        Run again = new Run(s, size[0], size[1]);
                        for (int n = 0; n <= end; n++) {
                            double t = time(n, fps);
                            boolean pass = again.at(t, omega * Math.min(t, stop), 0);
                            if (n >= first) {
                                assertFalse(what + " frame " + n, pass);
                                assertFalse(again.frame.active());
                                assertTrue(what + " speed zeroed, frame " + n, speedIsZero(again.frame));
                            }
                        }
                    }
                }
            }
        }
    }

    /** Count of active-flag changes over a constant turn of {@code px} smear for {@code seconds}. */
    private static int changesAtConstant(double px, int fps, double seconds) {
        Run r = new Run();
        double omega = speedForPx(px, 5, 1920, 1080);
        boolean was = false;
        int changes = 0;
        for (int n = 0; n <= Math.round(seconds * fps); n++) {
            double t = time(n, fps);
            boolean pass = r.at(t, omega * t, 0);
            assertEquals("pass is the active flag", r.frame.active(), pass);
            if (r.frame.active() != was) changes++;
            was = r.frame.active();
        }
        return changes;
    }

    @Test
    public void slowDriftNeverFlickers() {
        for (int fps : new int[]{60, 240, 1000, 2000}) {
            for (double px : new double[]{0.05, 0.5, 0.99, 1.0, 2.0}) {
                int changes = changesAtConstant(px, fps, 2.0);
                assertTrue(px + " px at " + fps + " FPS: " + changes + " changes", changes <= 1);
                if (px < 1.0) assertEquals(px + " px never starts", 0, changes);
                if (px == 2.0) assertEquals("2 px starts once and stays on", 1, changes);
            }

            // Up from rest to 2 px over 1 s, down to 0 over 1 s, then still for 0.5 s.
            Run ramp = new Run();
            double top = speedForPx(2.0, 5, 1920, 1080);
            double yaw = 0;
            boolean was = false;
            int changes = 0;
            for (int n = 0; n <= Math.round(2.5 * fps); n++) {
                double t = time(n, fps);
                if (n > 0) {
                    double mid = (time(n - 1, fps) + t) / 2;
                    double omega = mid < 1 ? top * mid : mid < 2 ? top * (2 - mid) : 0;
                    yaw += omega * (t - time(n - 1, fps));
                }
                ramp.at(t, yaw, 0);
                if (ramp.frame.active() != was) changes++;
                was = ramp.frame.active();
            }
            assertEquals("ramp at " + fps + " FPS", 2, changes);
            assertFalse(ramp.frame.active());
        }

        // Jittery input at 2,000 FPS near 1 px: deltas alternate 0 and twice the mean.
        int fps = 2000;
        for (double px : new double[]{0.95, 0.99, 1.0, 1.01, 1.05}) {
            Run r = new Run();
            double omega = speedForPx(px, 5, 1920, 1080);
            double yaw = 0;
            boolean was = false;
            int changes = 0;
            for (int n = 0; n <= 2 * fps; n++) {
                if (n > 0 && n % 2 == 0) yaw += 2 * omega / fps;
                r.at(time(n, fps), yaw, 0);
                if (r.frame.active() != was) changes++;
                was = r.frame.active();
            }
            assertTrue("jitter at " + px + " px: " + changes + " changes", changes <= 1);
        }
    }

    @Test
    public void underOnePixelNeverStarts() {
        for (int fps : new int[]{60, 240, 1000, 2000}) {
            Run r = new Run();
            double omega = speedForPx(0.99, 5, 1920, 1080);
            for (int n = 0; n <= 2 * fps; n++) {
                double t = time(n, fps);
                assertFalse(fps + " FPS frame " + n, r.at(t, omega * t, 0));
                assertTrue(r.frame.smearPx() < MotionBlurRules.ON_PX);
            }
            assertEquals("it still tracks the drift", 0.99, r.frame.smearPx(), 1e-6);
        }
    }

    // ------------------------------------------------------------------ A5: resets

    private static final int FPS = 240;
    private static final double OMEGA = 300;

    /** A run turning at 300 deg/s at 240 FPS for 200 ms, its pass on; frame {@link #TURN} is the last one. */
    private static Run turning() {
        Run r = new Run();
        for (int n = 0; n <= TURN; n++) r.at(time(n, FPS), OMEGA * time(n, FPS), 0);
        assertTrue("turning", r.frame.pass());
        assertTrue(r.frame.active());
        assertTrue(r.frame.smearPx() > MotionBlurRules.ON_PX);
        return r;
    }

    private static final int TURN = 48;

    /** The next frame of {@link #turning}, still turning: it would pass unless something resets it. */
    private static boolean nextTurningFrame(Run r, int plus) {
        double t = time(TURN + plus, FPS);
        return r.at(t, OMEGA * t, 0);
    }

    private static void assertReset(Run r, boolean pass, String reason) {
        assertFalse(reason + " draws unblurred", pass);
        assertEquals(reason, r.frame.resetReason());
        assertFalse(reason, r.frame.active());
        assertEquals(reason + " smear", 0.0, r.frame.smearPx(), 0);
        assertTrue(reason + " velocity zeroed", speedIsZero(r.frame));
    }

    @Test
    public void positionCorrectionResets() {
        // The reviewer's B2 case: at rest, then a 0.5 block move plus 90 deg of yaw in one 240 FPS frame.
        for (boolean bumped : new boolean[]{true, false}) {
            Run r = new Run();
            for (int n = 0; n <= 24; n++) r.at(time(n, FPS), 10, 5);
            assertFalse(r.frame.pass());
            r.s.x += 0.5;
            if (bumped) r.s.cutSerial++;
            boolean pass = r.at(time(25, FPS), 100, 5);
            if (bumped) {
                assertReset(r, pass, MotionBlurFrame.RESET_POSITION_CORRECTION);
                assertFalse("re-seeded: the same pose next frame is still", r.at(time(26, FPS), 100, 5));
                assertNull(r.frame.resetReason());
            } else {
                assertTrue("without the cut signal the same frame smears", pass);
                assertTrue(r.frame.smearPx() > 0);
                assertNull(r.frame.resetReason());
            }
        }
    }

    @Test
    public void yawOnlyCorrectionResets() {
        Run r = turning();
        r.s.cutSerial++;
        double t = time(TURN + 1, FPS);
        assertReset(r, r.at(t, OMEGA * time(TURN, FPS) + 90, 0), MotionBlurFrame.RESET_POSITION_CORRECTION);
    }

    @Test
    public void firstFrameResetsAndDrawsUnblurred() {
        Run r = new Run();
        assertReset(r, r.at(0.5, 123, 4), MotionBlurFrame.RESET_FIRST_FRAME);
        // Module just enabled: invalidate() makes the next frame a first frame.
        Run on = turning();
        on.frame.invalidate();
        assertReset(on, nextTurningFrame(on, 1), MotionBlurFrame.RESET_FIRST_FRAME);
    }

    @Test
    public void worldChangeResetsAndDrawsUnblurred() {
        Run r = turning();
        r.s.world = 12;
        assertReset(r, nextTurningFrame(r, 1), MotionBlurFrame.RESET_WORLD);
    }

    @Test
    public void viewEntityChangeResetsAndDrawsUnblurred() {
        Run r = turning();
        r.s.viewEntity = 23;
        assertReset(r, nextTurningFrame(r, 1), MotionBlurFrame.RESET_VIEW_ENTITY);
    }

    @Test
    public void perspectiveChangeResetsAndDrawsUnblurred() {
        Run r = turning();
        r.s.perspective = 1;
        assertReset(r, nextTurningFrame(r, 1), MotionBlurFrame.RESET_PERSPECTIVE);
    }

    @Test
    public void teleportBackstopResetsAndDrawsUnblurred() {
        Run r = turning();
        r.s.z += 5;
        assertReset(r, nextTurningFrame(r, 1), MotionBlurFrame.RESET_TELEPORT);
        // 2 blocks in one 60 FPS frame is 120 blocks/s: also a cut.
        Run slow = new Run();
        for (int n = 0; n <= 12; n++) slow.at(time(n, 60), OMEGA * time(n, 60), 0);
        assertTrue(slow.frame.pass());
        slow.s.x -= 2;
        assertReset(slow, slow.at(time(13, 60), OMEGA * time(13, 60), 0), MotionBlurFrame.RESET_TELEPORT);
    }

    @Test
    public void fallingDoesNotReset() {
        // Terminal fall speed, 3.92 blocks a tick: over 1 block a frame at 20 and 60 FPS, but under 100 blocks/s.
        for (int fps : new int[]{20, 60, 240}) {
            Run r = new Run();
            for (int n = 0; n <= fps / 2; n++) {
                double t = time(n, fps);
                r.s.y = 200 - 78.4 * t;
                boolean pass = r.at(t, OMEGA * t, 0);
                if (n > 0) {
                    assertNull(fps + " FPS frame " + n, r.frame.resetReason());
                    assertTrue(fps + " FPS frame " + n, pass);
                }
            }
        }
    }

    @Test
    public void gapOver100msResetsAndDrawsUnblurred() {
        Run r = turning();
        double t = time(TURN, FPS) + 0.101;
        assertReset(r, r.at(t, OMEGA * t, 0), MotionBlurFrame.RESET_GAP);
        Run exact = turning();
        double t2 = time(TURN, FPS) + 0.100;
        exact.at(t2, OMEGA * t2, 0);
        assertNull("a gap of exactly 100 ms is not over it", exact.frame.resetReason());
    }

    @Test
    public void sizeChangeResetsAndDrawsUnblurred() {
        Run r = turning();
        r.s.width = 1280;
        assertReset(r, nextTurningFrame(r, 1), MotionBlurFrame.RESET_SIZE);
        Run h = turning();
        h.s.height = 720;
        assertReset(h, nextTurningFrame(h, 1), MotionBlurFrame.RESET_SIZE);
    }

    @Test
    public void normalFrameKeepsState() {
        Run r = turning();
        double before = r.frame.smearPx();
        assertTrue(nextTurningFrame(r, 1));
        assertNull(r.frame.resetReason());
        assertTrue(r.frame.active());
        assertEquals(before, r.frame.smearPx(), before * 0.01);
        assertEquals(MotionBlurRules.taps(r.frame.smearPx(), MotionBlurRules.maxTaps(5)), r.frame.taps());
        // The same cut serial, world, entity, perspective and size, frame after frame: no reset.
        for (int i = 2; i < 20; i++) {
            nextTurningFrame(r, i);
            assertNull(r.frame.resetReason());
            assertTrue(r.frame.pass());
        }
    }

    @Test
    public void yawWrapAcross180IsASmallTurn() {
        Run r = new Run();
        List<Double> yaws = new ArrayList<Double>();
        for (int n = 0; n <= 48; n++) {
            double yaw = 170 + OMEGA * time(n, FPS);
            yaw = yaw > 180 ? yaw - 360 : yaw;
            yaws.add(yaw);
            r.at(time(n, FPS), yaw, 0);
        }
        assertTrue("crossed 180", yaws.get(48) < 0);
        double px = OMEGA * MotionBlurRules.exposureSeconds(5) / (FOV * 1920.0 / 1080.0) * 1920;
        assertEquals(px, r.frame.smearPx(), px * 0.01);
        assertTrue("turning right stays positive", r.frame.smearX() > 0);
    }
}
