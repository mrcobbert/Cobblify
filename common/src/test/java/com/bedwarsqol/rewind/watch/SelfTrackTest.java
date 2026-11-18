package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Your recorded eye position and camera rotation at any time: position one tick behind its latest TICK, held across
 * gaps, CAMERA rotation over TICK rotation, unwrapped yaw, and every segment starting from its own full TICK.
 */
public class SelfTrackTest {

    private static final double EPS = 1e-9;
    private static final int GROUND = RewindFormat.FLAG_ON_GROUND;
    private static final long T = 1_000_000L;

    private final SelfTrackEncoder enc = new SelfTrackEncoder();
    private final SelfTrack.Builder b = new SelfTrack.Builder();

    private void tick(long t, double x, double y, double z, float yaw, float pitch, int flags) {
        tick(t, x, y, z, yaw, pitch, flags, 0);
    }

    private void tick(long t, double x, double y, double z, float yaw, float pitch, int flags, int heldSlot) {
        int n = enc.tick(x, y, z, yaw, pitch, yaw, flags, heldSlot, -1, 0);
        assertTrue("fixture tick must change something", n > 0);
        b.record(t, RewindFormat.TAG_TICK, enc.buffer(), 0, n);
    }

    private void camera(long t, float yaw, float pitch) {
        byte[] body = new byte[8];
        RewindFormat.putFloat(body, 0, yaw);
        RewindFormat.putFloat(body, 4, pitch);
        b.record(t, RewindFormat.TAG_CAMERA, body, 0, 8);
    }

    private SelfTrack.Pose pose(SelfTrack s, long t) {
        SelfTrack.Pose p = new SelfTrack.Pose();
        assertTrue(s.pose(t, p));
        return p;
    }

    @Test
    public void fiftyMsCadenceInterpolatesOneTickBehind() {
        b.segment(1);
        tick(T, 0, 64, 0, 0f, 0f, GROUND);
        tick(T + 50_000, 1, 64, 0, 0f, 0f, GROUND);
        tick(T + 100_000, 2, 64, 0, 0f, 0f, GROUND);
        SelfTrack s = b.build();
        assertEquals(3, s.tickCount());
        assertEquals(0, pose(s, T).x, EPS);
        assertEquals(0, pose(s, T + 50_000).x, EPS);
        assertEquals(0.5, pose(s, T + 75_000).x, EPS);
        assertEquals(1, pose(s, T + 100_000).x, EPS);
        assertEquals(1.5, pose(s, T + 125_000).x, EPS);
        assertEquals(2, pose(s, T + 150_000).x, EPS);
        assertEquals(64, pose(s, T + 75_000).y, EPS);
        assertEquals(64 + SelfTrack.EYE_HEIGHT, pose(s, T + 75_000).eyeY, EPS);
        // Before the first TICK the first pose holds.
        assertEquals(0, pose(s, T - 500_000).x, EPS);
    }

    @Test
    public void aTwoSecondGapHolds() {
        b.segment(1);
        tick(T, 0, 64, 0, 0f, 0f, GROUND);
        tick(T + 50_000, 1, 64, 0, 0f, 0f, GROUND);
        tick(T + 2_050_000, 5, 64, 0, 0f, 0f, GROUND);
        SelfTrack s = b.build();
        assertEquals(1, pose(s, T + 100_000).x, EPS);
        assertEquals(1, pose(s, T + 1_000_000).x, EPS);
        assertEquals(1, pose(s, T + 2_049_999).x, EPS);
        assertEquals(1, pose(s, T + 2_050_000).x, EPS);
        assertEquals(3, pose(s, T + 2_075_000).x, EPS);
        assertEquals(5, pose(s, T + 9_000_000).x, EPS);
    }

    @Test
    public void sneakingLowersTheEyeByEightHundredths() {
        b.segment(1);
        tick(T, 0, 64, 0, 0f, 0f, GROUND);
        tick(T + 50_000, 0, 64, 0, 0f, 0f, GROUND | RewindFormat.FLAG_SNEAKING);
        SelfTrack s = b.build();
        SelfTrack.Pose standing = pose(s, T + 10_000);
        SelfTrack.Pose sneaking = pose(s, T + 60_000);
        assertEquals(1.62, standing.eyeY - standing.y, 1e-6);
        assertEquals(1.54, sneaking.eyeY - sneaking.y, 1e-6);
        assertEquals(0.08, standing.eyeY - sneaking.eyeY, 1e-6);
    }

    @Test
    public void cameraWinsOverTickRotation() {
        b.segment(1);
        tick(T, 0, 64, 0, 0f, 0f, GROUND);
        tick(T + 50_000, 0, 64, 0, 20f, -10f, GROUND);
        camera(T + 60_000, 90f, 30f);
        camera(T + 80_000, 100f, 40f);
        camera(T + 300_000, 120f, 50f);
        tick(T + 400_000, 0, 64, 0, -45f, 5f, GROUND);
        SelfTrack s = b.build();
        // Before the segment's first CAMERA: TICK rotation, interpolated like position.
        SelfTrack.Pose p = pose(s, T + 55_000);
        assertEquals(2f, p.yaw, 1e-4);
        assertEquals(-1f, p.pitch, 1e-4);
        // From the first CAMERA on: CAMERA, interpolated with a next sample within 50 ms.
        assertEquals(90f, pose(s, T + 60_000).yaw, 1e-4);
        assertEquals(95f, pose(s, T + 70_000).yaw, 1e-4);
        assertEquals(35f, pose(s, T + 70_000).pitch, 1e-4);
        // The next sample is 220 ms later: hold.
        assertEquals(100f, pose(s, T + 200_000).yaw, 1e-4);
        // A later TICK rotation never overrides the latest CAMERA.
        assertEquals(120f, pose(s, T + 500_000).yaw, 1e-4);
        assertEquals(50f, pose(s, T + 500_000).pitch, 1e-4);
    }

    @Test
    public void yawFrom359To361InterpolatesThrough360() {
        b.segment(1);
        tick(T, 0, 64, 0, 359f, 0f, GROUND);
        tick(T + 50_000, 0, 64, 0, 361f, 0f, GROUND);
        camera(T + 1_000_000, 359f, 0f);
        camera(T + 1_010_000, 361f, 0f);
        SelfTrack s = b.build();
        assertEquals(360f, pose(s, T + 75_000).yaw, 1e-4);
        assertEquals(360f, pose(s, T + 1_005_000).yaw, 1e-4);
    }

    @Test
    public void aNewSegmentStartsFromItsFullTick() {
        b.segment(1);
        tick(T, 0, 64, 0, 0f, 0f, GROUND);
        tick(T + 50_000, 1, 64, 0, 0f, 0f, GROUND);
        camera(T + 60_000, 90f, 0f);
        b.segment(2);
        enc.resetSegment();
        tick(T + 1_000_000, 100, 70, 100, 30f, 0f, GROUND);
        tick(T + 1_050_000, 101, 70, 100, 30f, 0f, GROUND);
        SelfTrack s = b.build();
        assertEquals(2, s.segmentCount());
        assertEquals(RewindFormat.TICK_ALL, s.segment(1).tickMask(0));
        // No interpolation from the old segment's last position, no rotation from its camera.
        SelfTrack.Pose p = pose(s, T + 1_025_000);
        assertEquals(100, p.x, EPS);
        assertEquals(70, p.y, EPS);
        assertEquals(30f, p.yaw, 1e-4);
        assertEquals(100.5, pose(s, T + 1_075_000).x, EPS);
        // The old segment still answers for its own time.
        assertEquals(1, pose(s, T + 500_000).x, EPS);
        assertEquals(90f, pose(s, T + 500_000).yaw, 1e-4);
    }

    @Test
    public void fieldsAMaskLeavesOutCarryForward() {
        b.segment(1);
        tick(T, 3, 64, -2, 0f, 0f, GROUND, 4);
        tick(T + 50_000, 3, 64, -2, 15f, 0f, GROUND, 4); // rotation only
        SelfTrack s = b.build();
        SelfTrack.Segment seg = s.segment(0);
        assertEquals(RewindFormat.TICK_ROT | RewindFormat.TICK_HEAD_YAW, seg.tickMask(1));
        assertEquals(3, seg.x(1), EPS);
        assertEquals(-2, seg.z(1), EPS);
        assertEquals(4, seg.heldSlot(1));
        assertEquals(GROUND, seg.flags(1));
        assertEquals(15f, seg.yaw(1), 0);
        SelfTrackEncoder.State st = new SelfTrackEncoder.State();
        assertTrue(s.stateAt(T + 60_000, st));
        assertEquals(15f, st.yaw, 0);
        assertEquals(4, st.heldSlot);
        assertEquals(-1, st.swingProgressInt);
    }

    @Test
    public void equipEventsAreKeptPerSegment() {
        b.segment(1);
        tick(T, 0, 64, 0, 0f, 0f, GROUND);
        byte[] sword = {RewindFormat.EQUIP_HELD, 0x01, 0x0B, 1, 0, 0, 0};
        b.record(T + 10_000, RewindFormat.TAG_EQUIP, sword, 0, sword.length);
        byte[] empty = {(byte) RewindFormat.EQUIP_HELMET, (byte) 0xFF, (byte) 0xFF};
        b.record(T + 20_000, RewindFormat.TAG_EQUIP, empty, 0, empty.length);
        SelfTrack s = b.build();
        assertEquals(2, s.equipCount());
        SelfTrack.Segment seg = s.segment(0);
        assertEquals(T + 10_000, seg.equipTimeUs(0));
        assertEquals(RewindFormat.EQUIP_HELD, seg.equipSlot(0));
        assertEquals(6, seg.equipItem(0).length);
        assertEquals(0x0B, seg.equipItem(0)[1]);
        assertEquals(RewindFormat.EQUIP_HELMET, seg.equipSlot(1));
        assertEquals(2, seg.equipItem(1).length);
    }

    @Test
    public void noTickMeansNoPose() {
        b.segment(1);
        camera(T, 10f, 0f);
        SelfTrack s = b.build();
        assertFalse(s.pose(T, new SelfTrack.Pose()));
        assertFalse(s.stateAt(T, new SelfTrackEncoder.State()));
    }
}
