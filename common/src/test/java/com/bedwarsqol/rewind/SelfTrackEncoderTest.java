package com.bedwarsqol.rewind;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** The self track writes only what changed: the first TICK of a segment is full, an unchanged tick writes nothing. */
public class SelfTrackEncoderTest {

    private static final int FLAGS = RewindFormat.FLAG_ON_GROUND | RewindFormat.FLAG_SPRINTING;

    private static int base(SelfTrackEncoder e) {
        return e.tick(1.5, 70.0, -2.25, 45f, -10f, 50f, FLAGS, 3, -1, 0);
    }

    @Test
    public void firstTickIsFullAndAnUnchangedTickWritesNothing() {
        SelfTrackEncoder e = new SelfTrackEncoder();
        int n = base(e);
        assertEquals(1 + 24 + 8 + 4 + 1 + 1 + 1 + 1, n);
        assertEquals(RewindFormat.TICK_ALL, e.buffer()[0] & 0xFF);
        assertEquals(0, base(e));
        assertEquals(0, base(e));
    }

    @Test
    public void onlyChangedFieldsAreWrittenAndDecodeOverThePreviousState() {
        SelfTrackEncoder e = new SelfTrackEncoder();
        SelfTrackEncoder.State s = new SelfTrackEncoder.State();
        int n = base(e);
        assertEquals(RewindFormat.TICK_ALL, SelfTrackEncoder.applyTick(e.buffer(), 0, n, s));
        assertEquals(1.5, s.x, 0);
        assertEquals(70.0, s.y, 0);
        assertEquals(-2.25, s.z, 0);
        assertEquals(45f, s.yaw, 0);
        assertEquals(-10f, s.pitch, 0);
        assertEquals(50f, s.headYaw, 0);
        assertEquals(FLAGS, s.flags);
        assertEquals(3, s.heldSlot);
        assertEquals(-1, s.swingProgressInt);
        assertEquals(0, s.hurtTime);

        n = e.tick(1.75, 70.0, -2.25, 45f, -10f, 50f, FLAGS, 3, -1, 0);
        assertEquals(1 + 24, n);
        assertEquals(RewindFormat.TICK_POS, e.buffer()[0] & 0xFF);
        SelfTrackEncoder.applyTick(e.buffer(), 0, n, s);
        assertEquals(1.75, s.x, 0);
        assertEquals(45f, s.yaw, 0);

        n = e.tick(1.75, 70.0, -2.25, 46f, -10f, 50f, FLAGS | RewindFormat.FLAG_SNEAKING, 3, 2, 9);
        assertEquals(RewindFormat.TICK_ROT | RewindFormat.TICK_FLAGS | RewindFormat.TICK_SWING
                | RewindFormat.TICK_HURT, e.buffer()[0] & 0xFF);
        assertEquals(1 + 8 + 1 + 1 + 1, n);
        SelfTrackEncoder.applyTick(e.buffer(), 0, n, s);
        assertEquals(46f, s.yaw, 0);
        assertEquals(-10f, s.pitch, 0);
        assertEquals(FLAGS | RewindFormat.FLAG_SNEAKING, s.flags);
        assertEquals(2, s.swingProgressInt);
        assertEquals(9, s.hurtTime);
        assertEquals(1.75, s.x, 0);

        n = e.tick(1.75, 70.0, -2.25, 46f, -10f, 51f, FLAGS | RewindFormat.FLAG_SNEAKING, 8, 2, 9);
        assertEquals(RewindFormat.TICK_HEAD_YAW | RewindFormat.TICK_HELD_SLOT, e.buffer()[0] & 0xFF);
        SelfTrackEncoder.applyTick(e.buffer(), 0, n, s);
        assertEquals(51f, s.headYaw, 0);
        assertEquals(8, s.heldSlot);
    }

    @Test
    public void swingIsStoredPlusOneSoNotSwingingIsZero() {
        SelfTrackEncoder e = new SelfTrackEncoder();
        int n = base(e);
        // mask, pos 24, rot 8, head 4, flags 1, slot 1, then swing+1
        assertEquals(0, e.buffer()[1 + 24 + 8 + 4 + 1 + 1] & 0xFF);
        assertEquals(n, 1 + 24 + 8 + 4 + 1 + 1 + 1 + 1);
    }

    @Test
    public void aNewSegmentStartsWithAFullTickAndFreshCameraAndEquipment() {
        SelfTrackEncoder e = new SelfTrackEncoder();
        base(e);
        assertEquals(8, e.camera(10f, 20f));
        byte[] item = {0x00, 0x01, 1, 0, 0, 0};
        assertEquals(1 + item.length, e.equip(RewindFormat.EQUIP_HELD, item, 0, item.length));
        e.resetSegment();
        assertEquals(1 + 24 + 8 + 4 + 1 + 1 + 1 + 1, base(e));
        assertEquals(RewindFormat.TICK_ALL, e.buffer()[0] & 0xFF);
        assertEquals(8, e.camera(10f, 20f));
        assertEquals(1 + item.length, e.equip(RewindFormat.EQUIP_HELD, item, 0, item.length));
    }

    @Test
    public void cameraAndEquipmentAreWrittenOnlyOnChange() {
        SelfTrackEncoder e = new SelfTrackEncoder();
        assertEquals(8, e.camera(10f, 20f));
        assertEquals(10f, RewindFormat.getFloat(e.buffer(), 0), 0);
        assertEquals(20f, RewindFormat.getFloat(e.buffer(), 4), 0);
        assertEquals(0, e.camera(10f, 20f));
        assertEquals(8, e.camera(10f, 20.5f));
        assertEquals(8, e.camera(Float.NaN, 20.5f));
        assertEquals(0, e.camera(Float.NaN, 20.5f));

        byte[] sword = {0x01, 0x14, 1, 0, 0, 0};
        byte[] empty = {(byte) 0xFF, (byte) 0xFF};
        assertEquals(7, e.equip(RewindFormat.EQUIP_HELD, sword, 0, sword.length));
        assertEquals(RewindFormat.EQUIP_HELD, e.buffer()[0]);
        assertArrayEquals(sword, Arrays.copyOfRange(e.buffer(), 1, 7));
        assertEquals(0, e.equip(RewindFormat.EQUIP_HELD, sword, 0, sword.length));
        // Each slot diffs on its own.
        assertEquals(7, e.equip(4, sword, 0, sword.length));
        assertEquals(3, e.equip(RewindFormat.EQUIP_HELD, empty, 0, empty.length));
        assertEquals(0, e.equip(RewindFormat.EQUIP_HELD, empty, 0, empty.length));
        assertEquals(0, e.equip(4, sword, 0, sword.length));
    }

    @Test
    public void markerInfoAndGameEndBodies() {
        SelfTrackEncoder e = new SelfTrackEncoder();
        assertEquals(1, e.marker(RewindFormat.MARKER_BED_LOST));
        assertEquals(RewindFormat.MARKER_BED_LOST, e.buffer()[0]);
        assertEquals(1, e.gameEnd(RewindFormat.GAME_END_LOSS));
        assertEquals(RewindFormat.GAME_END_LOSS, e.buffer()[0]);
        String map = "Fake Map é";
        byte[] utf8 = map.getBytes(StandardCharsets.UTF_8);
        int n = e.info(RewindFormat.INFO_MAP, map);
        assertEquals(1 + 1 + utf8.length, n);
        assertEquals(RewindFormat.INFO_MAP, e.buffer()[0]);
        RewindFormat.Cursor c = new RewindFormat.Cursor(e.buffer(), 1, n);
        assertEquals(map, c.string(RewindFormat.MAX_STRING_BYTES));
        // Every body the encoder writes is one the reader can measure.
        assertEquals(n, RecordingReader.trackBodyLength(RewindFormat.TAG_INFO, e.buffer(), 0, n));
        int t = base(e);
        assertEquals(t, RecordingReader.trackBodyLength(RewindFormat.TAG_TICK, e.buffer(), 0, t));
    }
}
