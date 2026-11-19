package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Your view's bob and nausea (TASK B5, B8; PLAN Phase 7): {@link ViewBob} over scripted recorded tracks against the
 * values vanilla's own recurrences give ({@code EntityPlayer.onLivingUpdate}, {@code Entity.moveEntity},
 * {@code EntityPlayerSP.onLivingUpdate}; constants from {@code javap} on the mapped 1.8.9 jar). The expected values
 * are written out from those formulas here, one scenario at a time: walk, sprint, jump, sneak, stand.
 */
public class ViewBobTest {

    private static final int GROUND = RewindFormat.FLAG_ON_GROUND;
    private static final int SNEAK = RewindFormat.FLAG_SNEAKING;
    private static final int RIDING = RewindFormat.FLAG_RIDING;
    private static final float EPS = 1e-6f;

    /** Ground friction vanilla multiplies the motion by after a move that started on plain ground. */
    private static final double GROUND_F = (double) (0.6f * 0.91f);

    /** cameraYaw after one tick from {@code yaw} toward {@code f}. */
    private static float yawStep(float yaw, float f) {
        return yaw + (f - yaw) * 0.4f;
    }

    private static float pitchStep(float pitch, float f1) {
        return pitch + (f1 - pitch) * 0.8f;
    }

    @Test
    public void walkingOnTheGroundBobsTowardTheCappedSpeedAndWalksSixTenthsOfTheDistance() {
        ViewBob b = new ViewBob();
        double step = 0.21585; // vanilla walking, blocks per tick
        b.step(0, 64, 0, GROUND, true); // first tick: no motion yet
        assertEquals(0f, b.cameraYaw(), 0f);
        float yaw = 0f;
        float dist = 0f;
        for (int t = 1; t <= 5; t++) {
            b.step(step * t, 64, 0, GROUND, true);
            float f = (float) Math.min(step * GROUND_F, 0.1); // 0.1178 -> capped at 0.1
            assertEquals(0.1f, f, 0f);
            float prev = yaw;
            yaw = yawStep(yaw, f);
            float prevDist = dist;
            dist = (float) (dist + step * 0.6);
            assertEquals("tick " + t, yaw, b.cameraYaw(), EPS);
            assertEquals(prev, b.prevCameraYaw(), EPS);
            assertEquals(dist, b.distanceWalked(), 1e-5f);
            assertEquals(prevDist, b.prevDistanceWalked(), 1e-5f);
            assertEquals("on the ground: no pitch", 0f, b.cameraPitch(), 0f);
        }
        assertEquals(0.1f * (1 - (float) Math.pow(0.6, 5)), b.cameraYaw(), 1e-5f);
    }

    @Test
    public void aSlowWalkBobsLessThanTheCap() {
        ViewBob b = new ViewBob();
        b.step(0, 64, 0, GROUND, true);
        b.step(0.03, 64, 0.04, GROUND, true); // 0.05 per tick
        float f = (float) Math.sqrt(Math.pow(0.03 * GROUND_F, 2) + Math.pow(0.04 * GROUND_F, 2));
        assertTrue(f < 0.1f);
        assertEquals(yawStep(0, f), b.cameraYaw(), EPS);
        assertEquals((float) (0.05 * 0.6), b.distanceWalked(), 1e-6f);
    }

    @Test
    public void sprintingWalksFurtherPerTickWithTheSameCappedBob() {
        ViewBob b = new ViewBob();
        double step = 0.2806; // vanilla sprinting
        b.step(0, 64, 0, GROUND, true);
        b.step(0, 64, step, GROUND, true);
        b.step(0, 64, 2 * step, GROUND, true);
        assertEquals(yawStep(yawStep(0, 0.1f), 0.1f), b.cameraYaw(), EPS);
        assertEquals((float) ((float) (step * 0.6) + step * 0.6), b.distanceWalked(), 1e-5f);
    }

    @Test
    public void aJumpTiltsThePitchWhileInTheAirAndStopsTheYawBob() {
        ViewBob b = new ViewBob();
        b.step(0, 64, 0, GROUND, true);
        b.step(0.2, 64, 0, GROUND, true);
        float yaw = b.cameraYaw();
        // jump tick: vanilla moved by motionY 0.42, ends in the air; motion read after gravity and drag
        b.step(0.4, 64.42, 0, 0, true);
        float f1 = (float) (Math.atan(-((0.42 - 0.08) * 0.9800000190734863) * 0.20000000298023224) * 15.0);
        assertEquals(pitchStep(0, f1), b.cameraPitch(), EPS);
        assertEquals("in the air the yaw bob eases to 0", yawStep(yaw, 0f), b.cameraYaw(), EPS);
        assertTrue("looking up while rising means a negative pitch", b.cameraPitch() < 0);
        // distance still counts in the air (the tick started on the ground, not sneaking)
        assertEquals((float) ((float) (0.2 * 0.6) + 0.2 * 0.6), b.distanceWalked(), 1e-5f);
        // falling: positive pitch
        float pitch = b.cameraPitch();
        b.step(0.5, 64.2, 0, 0, true);
        float f1b = (float) (Math.atan(-((-0.22 - 0.08) * 0.9800000190734863) * 0.20000000298023224) * 15.0);
        assertEquals(pitchStep(pitch, f1b), b.cameraPitch(), 1e-5f);
        // landing: on the ground the pitch eases back to 0
        pitch = b.cameraPitch();
        b.step(0.6, 64, 0, GROUND, true);
        assertEquals(pitchStep(pitch, 0f), b.cameraPitch(), 1e-5f);
    }

    @Test
    public void sneakingOnTheGroundBobsSlightlyButWalksNoDistance() {
        ViewBob b = new ViewBob();
        double step = 0.0647; // vanilla sneaking
        b.step(0, 64, 0, GROUND | SNEAK, true);
        b.step(step, 64, 0, GROUND | SNEAK, true);
        b.step(2 * step, 64, 0, GROUND | SNEAK, true);
        float f = (float) (step * GROUND_F);
        assertEquals(yawStep(yawStep(0, f), f), b.cameraYaw(), EPS);
        assertEquals("moveEntity skips the walked distance while sneaking on the ground", 0f, b.distanceWalked(), 0f);
    }

    @Test
    public void standingStillEasesTheBobBackToZero() {
        ViewBob b = new ViewBob();
        b.step(0, 64, 0, GROUND, true);
        b.step(0.25, 64, 0, GROUND, true);
        float yaw = b.cameraYaw();
        float dist = b.distanceWalked();
        for (int t = 0; t < 3; t++) {
            b.step(0.25, 64, 0, GROUND, true);
            yaw = yawStep(yaw, 0f);
            assertEquals(yaw, b.cameraYaw(), EPS);
            assertEquals("no motion, no distance", dist, b.distanceWalked(), 0f);
            assertEquals(dist, b.prevDistanceWalked(), 0f);
        }
    }

    @Test
    public void deadOrRidingOrTeleportedGiveNoBob() {
        ViewBob dead = new ViewBob();
        dead.step(0, 64, 0, GROUND, false);
        dead.step(0.2, 64, 0, GROUND, false);
        assertEquals(0f, dead.cameraYaw(), 0f);
        dead.step(0.4, 64.4, 0, 0, false);
        assertEquals("dead: no pitch either", 0f, dead.cameraPitch(), 0f);

        ViewBob riding = new ViewBob();
        riding.step(0, 64, 0, RIDING, true);
        riding.step(0.3, 64, 0, RIDING, true);
        assertEquals("riding walks no distance", 0f, riding.distanceWalked(), 0f);

        ViewBob tp = new ViewBob();
        tp.step(0, 64, 0, GROUND, true);
        tp.step(100, 64, 0, GROUND, true);
        assertEquals("a teleport is not a move", 0f, tp.cameraYaw(), 0f);
        assertEquals(0f, tp.distanceWalked(), 0f);
    }

    @Test
    public void resetForgetsThePreviousPosition() {
        ViewBob b = new ViewBob();
        b.step(0, 64, 0, GROUND, true);
        b.step(0.2, 64, 0, GROUND, true);
        b.nausea(200);
        b.reset();
        assertEquals(0f, b.cameraYaw(), 0f);
        assertEquals(0f, b.distanceWalked(), 0f);
        assertEquals(0f, b.timeInPortal(), 0f);
        b.step(5, 64, 0, GROUND, true); // first after the reset: no motion
        assertEquals(0f, b.cameraYaw(), 0f);
        assertEquals(0f, b.distanceWalked(), 0f);
    }

    // ------------------------------------------------------------------ nausea (B8)

    @Test
    public void nauseaRisesWhileConfusionLastsAndFadesAfter() {
        ViewBob b = new ViewBob();
        float t = 0f;
        for (int i = 0; i < 10; i++) {
            b.nausea(300);
            float prev = t;
            t = Math.min(1f, t + 0.006666667f);
            assertEquals(t, b.timeInPortal(), EPS);
            assertEquals(prev, b.prevTimeInPortal(), EPS);
        }
        for (int i = 0; i < 200; i++) {
            b.nausea(300);
        }
        assertEquals("capped at 1", 1f, b.timeInPortal(), 0f);
        b.nausea(60); // vanilla needs more than 60 ticks left
        assertEquals(0.95f, b.timeInPortal(), EPS);
        b.nausea(-1);
        assertEquals(0.9f, b.timeInPortal(), EPS);
        for (int i = 0; i < 30; i++) {
            b.nausea(-1);
        }
        assertEquals("floor 0", 0f, b.timeInPortal(), 0f);
    }
}
