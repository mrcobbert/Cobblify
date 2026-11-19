package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The recorded player's stand-in (PLAN section 4, Phase 4b; parity "Self state binding"): each world tick k maps
 * tick k's end state (the last TICK stamped before the next tick's time) to entity fields, starts a swing exactly once
 * per recorded swing (at the tick vanilla's {@code swingItem} ran: one tick of lookahead), applies each EQUIP change
 * once in the tick it was stamped in, and is hidden in your view and while the recorded invisible flag is set. Ticks
 * are the v1 table rebuilt from the fixture's TICK times ({@link #sample}).
 */
public class PuppetStateTest {

    private static final long T = 1_000_000L;
    private static final long TICK = Timeline.TICK_US;

    private final SelfTrackEncoder enc = new SelfTrackEncoder();
    private final SelfTrack.Builder b = new SelfTrack.Builder();
    private final PuppetState p = new PuppetState();
    private SelfTrack tableTrack;
    private TickTable table;

    /** Tick {@code k} of the v1 table built from the TICK times of {@code s} (origin 1 ms before T). */
    private boolean sample(SelfTrack s, long k) {
        if (s != tableTrack) {
            tableTrack = s;
            table = TickTable.forRecording(T - 1_000, s, ExtRecords.EMPTY);
        }
        return p.sample(s, table, k);
    }

    private void tick(long t, double x, int flags, int swingInt) {
        int n = enc.tick(x, 64, 0, 10f, 5f, 20f, flags, 0, swingInt, 0);
        assertTrue("fixture tick must change something", n > 0);
        b.record(t, RewindFormat.TAG_TICK, enc.buffer(), 0, n);
    }

    private void equip(long t, int slot, int... item) {
        byte[] body = new byte[item.length + 1];
        body[0] = (byte) slot;
        for (int i = 0; i < item.length; i++) {
            body[i + 1] = (byte) item[i];
        }
        b.record(t, RewindFormat.TAG_EQUIP, body, 0, body.length);
    }

    /** Samples every tick from 0 on and returns the ticks at which a swing started. */
    private List<Integer> swingTicks(SelfTrack s, int count) {
        List<Integer> out = new ArrayList<Integer>();
        for (int k = 0; k < count; k++) {
            assertTrue(sample(s, k));
            if (p.swingStarted()) {
                out.add(k);
            }
        }
        return out;
    }

    @Test
    public void aTicksPoseAndFlagsBecomeEntityFields() {
        b.segment(1);
        int flags = RewindFormat.FLAG_ON_GROUND | RewindFormat.FLAG_SNEAKING | RewindFormat.FLAG_USING_ITEM
                | RewindFormat.FLAG_BURNING;
        int n = enc.tick(3.5, 70.25, -8.75, 91f, -12f, 95f, flags, 4, -1, 0);
        b.record(T, RewindFormat.TAG_TICK, enc.buffer(), 0, n);
        n = enc.tick(4.5, 70.25, -8.75, 92f, -12f, 96f, RewindFormat.FLAG_SPRINTING, 4, -1, 0);
        b.record(T + TICK, RewindFormat.TAG_TICK, enc.buffer(), 0, n);
        SelfTrack s = b.build();

        assertTrue(sample(s, 0));
        assertEquals(3.5, p.x(), 0);
        assertEquals(70.25, p.y(), 0);
        assertEquals(-8.75, p.z(), 0);
        assertEquals(91f, p.yaw(), 0);
        assertEquals(-12f, p.pitch(), 0);
        assertEquals(95f, p.headYaw(), 0);
        assertTrue(p.onGround());
        assertTrue(p.sneaking());
        assertFalse(p.sprinting());
        assertTrue(p.usingItem());
        assertTrue(p.burning());
        assertFalse(p.invisible());

        assertTrue(sample(s, 1));
        assertEquals(4.5, p.x(), 0);
        assertEquals(96f, p.headYaw(), 0);
        assertFalse(p.onGround());
        assertFalse(p.sneaking());
        assertTrue(p.sprinting());
        assertFalse(p.usingItem());
        assertFalse(p.burning());

        // A long gap holds the last TICK.
        assertTrue(sample(s, 40));
        assertEquals(4.5, p.x(), 0);
    }

    @Test
    public void noTrackNoSample() {
        b.segment(1);
        assertFalse(sample(b.build(), 0));
        assertFalse(p.swingStarted());
    }

    @Test
    public void aSwingStartsOnceAtTheTickSwingItemRan() {
        b.segment(1);
        // Recorded swingProgressInt per client tick: idle 0, the click tick reads 0 too, then 1..5, then 0.
        int[] swing = {0, 0, 1, 2, 3, 4, 5, 0, 0};
        for (int i = 0; i < swing.length; i++) {
            tick(T + i * TICK, i, RewindFormat.FLAG_ON_GROUND, swing[i]);
        }
        SelfTrack s = b.build();
        List<Integer> expected = new ArrayList<Integer>();
        expected.add(1);
        assertEquals(expected, swingTicks(s, swing.length + 3));
    }

    @Test
    public void aRepeatedClickRestartsTheSwing() {
        b.segment(1);
        int[] swing = {0, 1, 2, 3, 0, 1, 2, 3, 4, 5, 0};
        for (int i = 0; i < swing.length; i++) {
            tick(T + i * TICK, i, 0, swing[i]);
        }
        SelfTrack s = b.build();
        List<Integer> expected = new ArrayList<Integer>();
        expected.add(0);
        expected.add(4);
        assertEquals(expected, swingTicks(s, swing.length + 2));
    }

    @Test
    public void anIdlePlayerNeverSwings() {
        b.segment(1);
        for (int i = 0; i < 20; i++) {
            tick(T + i * TICK, i, 0, 0);
        }
        assertTrue(swingTicks(b.build(), 25).isEmpty());
    }

    @Test
    public void aSwingAfterAnUnchangedGapStartsOneTickBeforeItsFirstRecord() {
        b.segment(1);
        tick(T, 0, 0, 0);
        // Standing still for 2 s writes nothing; the click tick reads like idle and is not written either.
        tick(T + 41 * TICK, 0, 0, 1);
        tick(T + 42 * TICK, 0, 0, 2);
        SelfTrack s = b.build();
        List<Integer> expected = new ArrayList<Integer>();
        expected.add(40);
        assertEquals(expected, swingTicks(s, 45));
    }

    @Test
    public void eachEquipChangeAppliesOnce() {
        b.segment(1);
        tick(T, 0, 0, 0);
        equip(T, RewindFormat.EQUIP_HELD, 1, 2, 3);
        equip(T, RewindFormat.EQUIP_HELMET, 9, 9);
        equip(T + 120_000, RewindFormat.EQUIP_HELD, 4, 5); // stamped in tick 2's interval [99, 149) ms
        equip(T + 120_000, RewindFormat.EQUIP_HELD, 4, 6); // two changes in one tick: the later one
        SelfTrack s = b.build();

        assertTrue(sample(s, 0));
        assertTrue(p.equipChanged(RewindFormat.EQUIP_HELD));
        assertTrue(p.equipChanged(RewindFormat.EQUIP_HELMET));
        assertFalse(p.equipChanged(RewindFormat.EQUIP_BOOTS));
        assertArrayEquals(new byte[]{1, 2, 3}, p.equipItem(RewindFormat.EQUIP_HELD));
        assertArrayEquals(new byte[]{9, 9}, p.equipItem(RewindFormat.EQUIP_HELMET));
        assertNull(p.equipItem(RewindFormat.EQUIP_BOOTS));

        assertTrue(sample(s, 1));
        for (int slot = 0; slot < PuppetState.SLOTS; slot++) {
            assertFalse("no change in tick 1", p.equipChanged(slot));
        }
        assertTrue(sample(s, 2));
        assertTrue("the change is in the tick it was stamped in", p.equipChanged(RewindFormat.EQUIP_HELD));
        assertFalse(p.equipChanged(RewindFormat.EQUIP_HELMET));
        assertArrayEquals(new byte[]{4, 6}, p.equipItem(RewindFormat.EQUIP_HELD));
        assertTrue(sample(s, 3));
        assertFalse(p.equipChanged(RewindFormat.EQUIP_HELD));

        // After a restart (a fresh puppet) every recorded slot applies again, at its state for that tick.
        p.reset();
        assertTrue(sample(s, 4));
        assertTrue(p.equipChanged(RewindFormat.EQUIP_HELD));
        assertTrue(p.equipChanged(RewindFormat.EQUIP_HELMET));
        assertArrayEquals(new byte[]{4, 6}, p.equipItem(RewindFormat.EQUIP_HELD));
    }

    @Test
    public void aNewSegmentAppliesItsOwnEquipment() {
        b.segment(1);
        tick(T, 0, 0, 0);
        equip(T, RewindFormat.EQUIP_HELD, 1);
        b.segment(2);
        enc.resetSegment();
        tick(T + 10 * TICK, 0, 0, 0);
        equip(T + 10 * TICK, RewindFormat.EQUIP_HELD, 2);
        SelfTrack s = b.build();
        assertTrue(sample(s, 9));
        assertArrayEquals(new byte[]{1}, p.equipItem(RewindFormat.EQUIP_HELD));
        assertTrue(sample(s, 10));
        assertTrue(p.equipChanged(RewindFormat.EQUIP_HELD));
        assertArrayEquals(new byte[]{2}, p.equipItem(RewindFormat.EQUIP_HELD));
    }

    @Test
    public void invisibleHidesAndYourViewHides() {
        b.segment(1);
        tick(T, 0, 0, 0);
        tick(T + TICK, 1, RewindFormat.FLAG_INVISIBLE, 0);
        SelfTrack s = b.build();
        assertTrue(p.hidden(true)); // nothing sampled yet
        assertTrue(p.hidden(false));
        assertTrue(sample(s, 0));
        assertFalse(p.hidden(false));
        assertTrue(p.hidden(true));
        assertTrue(sample(s, 1));
        assertTrue(p.invisible());
        assertTrue(p.hidden(false));
        assertTrue(p.hidden(true));
    }
}
