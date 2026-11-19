package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The hand's lag behind a turn (TASK B5; PLAN Phase 7): {@link ArmSway} against vanilla's
 * {@code EntityPlayerSP.updateEntityActionState} ({@code renderArm = (float) (renderArm + (rotation - renderArm) x
 * 0.5D)}, previous values kept), one tick at a time.
 */
public class ArmSwayTest {

    private static float vanilla(float arm, float rotation) {
        return (float) ((double) arm + (double) (rotation - arm) * 0.5D);
    }

    @Test
    public void aTurnIsFollowedHalfWayEachTick() {
        ArmSway a = new ArmSway();
        a.snap(0f, 0f);
        float yaw = 0f;
        float pitch = 0f;
        for (int t = 0; t < 4; t++) {
            a.step(90f, -30f);
            float py = yaw;
            float pp = pitch;
            yaw = vanilla(yaw, 90f);
            pitch = vanilla(pitch, -30f);
            assertEquals(yaw, a.yaw(), 0f);
            assertEquals(pitch, a.pitch(), 0f);
            assertEquals(py, a.prevYaw(), 0f);
            assertEquals(pp, a.prevPitch(), 0f);
        }
        assertEquals(84.375f, a.yaw(), 1e-4f);
    }

    @Test
    public void holdingStillSettlesOnTheRotation() {
        ArmSway a = new ArmSway();
        a.snap(10f, 5f);
        a.step(10f, 5f);
        assertEquals(10f, a.yaw(), 0f);
        assertEquals(10f, a.prevYaw(), 0f);
        assertEquals(5f, a.pitch(), 0f);
    }

    @Test
    public void snapRemovesTheLagAtOnceForUnwrappedYaw() {
        ArmSway a = new ArmSway();
        a.snap(0f, 0f);
        a.step(40f, 0f);
        a.snap(725f, -12f); // raw, unwrapped yaw as the recording holds it
        assertEquals(725f, a.yaw(), 0f);
        assertEquals(725f, a.prevYaw(), 0f);
        assertEquals(-12f, a.pitch(), 0f);
        assertEquals(-12f, a.prevPitch(), 0f);
    }
}
