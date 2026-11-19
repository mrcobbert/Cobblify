package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Your view's hand state from the self track (PLAN section 4.1, Phase 4c), checked against a small reference of
 * vanilla's swing rule ({@code EntityLivingBase.swingItem} + {@code updateArmSwingProgress}): swing timing to the
 * tick including restarts by repeated clicks, item use from the using-item flag, the selected slot from TICK
 * {@code heldSlot}, EQUIP stacks applied once per change (so a later S2F for that slot wins), armour, and
 * {@code hurtTime} by view.
 *
 * <p><b>Format v2</b> (Phase 13, C9): a swing starts on the tick of a recorded OUT C0A, the item-use count is the
 * recorded USE count (counted down between records), and all 41 inventory slots (36 main, 4 armour, the cursor) are
 * the INV records' (they win over EQUIP), also when the state is first taken at a later tick (a seek).
 */
public class HandStateTest {

    private static final long T = 5_000_000L;
    private static final long TICK = Timeline.TICK_US;
    private static final int END = 6;

    private final SelfTrackEncoder enc = new SelfTrackEncoder();
    private final SelfTrack.Builder b = new SelfTrack.Builder();
    private final HandState h = new HandState();
    private SelfTrack tableTrack;
    private TickTable table;

    /**
     * Tick {@code i} of the fixture as playback runs it: the v1 table rebuilt from its TICK times (one every 50 ms
     * from {@code T}), so tick i ends with the TICK written at {@code T + i x 50 ms}.
     */
    private boolean sample(SelfTrack s, int i) {
        if (s != tableTrack) {
            tableTrack = s;
            table = TickTable.forRecording(T - 1_000, s, ExtRecords.EMPTY);
        }
        return h.sample(s, table, i, END);
    }

    /** Vanilla 1.8.9's swing, as {@code EntityLivingBase} does it. */
    static final class VanillaSwing {
        boolean inProgress;
        int progress;
        float swing;
        float prev;

        void swingItem(int end) {
            if (!inProgress || progress >= end / 2 || progress < 0) {
                progress = -1;
                inProgress = true;
            }
        }

        void tick(int end) {
            prev = swing;
            if (inProgress) {
                progress++;
                if (progress >= end) {
                    progress = 0;
                    inProgress = false;
                }
            } else {
                progress = 0;
            }
            swing = (float) progress / end;
        }
    }

    private void tick(int i, int flags, int heldSlot, int swingInt, int hurt) {
        int n = enc.tick(i, 64, 0, 0f, 0f, 0f, flags, heldSlot, swingInt, hurt);
        assertTrue("fixture tick must change something", n > 0);
        b.record(T + i * TICK, RewindFormat.TAG_TICK, enc.buffer(), 0, n);
    }

    private void equip(int i, int slot, int... item) {
        byte[] body = new byte[item.length + 1];
        body[0] = (byte) slot;
        for (int k = 0; k < item.length; k++) {
            body[k + 1] = (byte) item[k];
        }
        b.record(T + i * TICK, RewindFormat.TAG_EQUIP, body, 0, body.length);
    }

    private void swingMatchesReference(int[] stored, int... clickTicks) {
        b.segment(1);
        for (int i = 0; i < stored.length; i++) {
            tick(i, 0, 0, stored[i] - 1, 0);
        }
        SelfTrack s = b.build();
        VanillaSwing ref = new VanillaSwing();
        for (int i = 0; i < stored.length + 3; i++) {
            boolean click = false;
            for (int c : clickTicks) {
                click |= c == i;
            }
            if (click) {
                ref.swingItem(END);
            }
            ref.tick(END);
            assertTrue(sample(s, i));
            String at = "tick " + i;
            assertEquals(at, click, h.swingStarted());
            assertEquals(at, ref.progress, h.swingProgressInt());
            assertEquals(at, ref.inProgress, h.swingInProgress());
            assertEquals(at, ref.swing, h.swingProgress(), 0f);
            assertEquals(at, ref.prev, h.prevSwingProgress(), 0f);
            if (i < stored.length) {
                assertEquals(at + " reads as recorded", stored[i] - 1, h.swingProgressInt());
            }
        }
    }

    @Test
    public void oneSwingStartsAtTheSecondTickAndMatchesVanilla() {
        swingMatchesReference(new int[]{1, 1, 2, 3, 4, 5, 6, 1, 1}, 1);
    }

    @Test
    public void aRepeatedClickMidSwingRestartsAsVanillaDoes() {
        swingMatchesReference(new int[]{1, 2, 3, 4, 1, 2, 3, 4, 5, 6, 1}, 0, 4);
    }

    @Test
    public void anIdleRunNeverSwings() {
        swingMatchesReference(new int[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1});
    }

    @Test
    public void hasteAndMiningFatigueChangeTheSwingLength() {
        assertEquals(6, HandState.swingEnd(-1, -1));
        assertEquals(4, HandState.swingEnd(1, -1));
        assertEquals(8, HandState.swingEnd(-1, 0));
        assertEquals("haste wins", 5, HandState.swingEnd(0, 2));
    }

    @Test
    public void theUsingItemFlagStartsCountsDownAndClearsTheItemInUse() {
        b.segment(1);
        for (int i = 0; i < 40; i++) {
            tick(i, i >= 10 && i < 30 ? RewindFormat.FLAG_USING_ITEM : 0, 0, -1, 0);
        }
        SelfTrack s = b.build();
        int max = 72000; // a bow
        for (int i = 0; i < 40; i++) {
            assertTrue(sample(s, i));
            String at = "tick " + i;
            assertEquals(at, i >= 10 && i < 30, h.inUse());
            assertEquals(at, i == 10, h.useStarted());
            if (h.inUse()) {
                assertEquals(at, max - (i - 10), h.useCount(max));
            }
        }
    }

    @Test
    public void heldSlotEquipStacksAndALaterSetSlot() {
        b.segment(1);
        byte[][] inventory = new byte[HandState.HOTBAR][];
        for (int i = 0; i < 30; i++) {
            tick(i, 0, i < 10 ? 0 : i < 20 ? 3 : 8, -1, 0);
            if (i == 0) {
                equip(0, RewindFormat.EQUIP_HELD, 7, 64);
                equip(0, RewindFormat.EQUIP_HELMET, 9, 1);
            } else if (i == 4) {
                equip(4, RewindFormat.EQUIP_HELD, 7, 63);
            } else if (i == 5) {
                equip(5, RewindFormat.EQUIP_HELD, 7, 62);
            } else if (i == 10) {
                equip(10, RewindFormat.EQUIP_HELD, 3, 1);
            } else if (i == 20) {
                equip(20, RewindFormat.EQUIP_HELD, 5, 16);
                equip(20, RewindFormat.EQUIP_BOOTS, 2, 1);
            }
        }
        SelfTrack s = b.build();
        for (int i = 0; i < 30; i++) {
            assertTrue(sample(s, i));
            for (int slot = 0; slot < HandState.HOTBAR; slot++) {
                if (h.hotbarChanged(slot)) {
                    inventory[slot] = h.hotbarItem(slot);
                }
            }
            String at = "tick " + i;
            assertEquals(at, i < 10 ? 0 : i < 20 ? 3 : 8, h.selectedSlot());
            if (i == 0) {
                assertArrayEquals(new byte[]{7, 64}, inventory[0]);
                assertTrue(h.armourChanged(RewindFormat.EQUIP_HELMET));
                assertArrayEquals(new byte[]{9, 1}, h.armourItem(RewindFormat.EQUIP_HELMET));
                assertNull(h.armourItem(RewindFormat.EQUIP_BOOTS));
            } else if (i == 4) {
                assertArrayEquals(new byte[]{7, 63}, inventory[0]);
                assertFalse(h.armourChanged(RewindFormat.EQUIP_HELMET));
            } else if (i == 5) {
                assertArrayEquals(new byte[]{7, 62}, inventory[0]);
            } else if (i == 7) {
                inventory[0] = new byte[]{7, 60}; // a later S2F for the same slot
                h.inventoryPacket(setSlot(0, 36), 0, 6, T + i * TICK + 1); // which the sink reports (review 2, I2)
            } else if (i > 7 && i < 10) {
                assertArrayEquals("the S2F is not overwritten", new byte[]{7, 60}, inventory[0]);
            } else if (i == 10) {
                assertArrayEquals(new byte[]{3, 1}, inventory[3]);
                assertArrayEquals("switching away keeps the last count", new byte[]{7, 60}, inventory[0]);
            } else if (i == 20) {
                assertArrayEquals(new byte[]{5, 16}, inventory[8]);
                assertArrayEquals(new byte[]{3, 1}, inventory[3]);
                assertTrue(h.armourChanged(RewindFormat.EQUIP_BOOTS));
                assertArrayEquals(new byte[]{2, 1}, h.armourItem(RewindFormat.EQUIP_BOOTS));
            } else {
                for (int slot = 0; slot < HandState.HOTBAR; slot++) {
                    assertFalse(at + " slot " + slot, h.hotbarChanged(slot));
                }
            }
        }
        // The full state, for a camera made anew by an S01/S07: the S2F replaced slot 0's older EQUIP stack.
        assertNull(h.hotbarItem(0));
        assertArrayEquals(new byte[]{3, 1}, h.hotbarItem(3));
        assertArrayEquals(new byte[]{5, 16}, h.hotbarItem(8));
        assertNull(h.hotbarItem(1));
    }

    @Test
    public void hurtTimeFollowsTheTrackInYourViewAndIsZeroInTheFreeCamera() {
        b.segment(1);
        for (int i = 0; i < 15; i++) {
            tick(i, 0, 0, -1, i < 3 ? 0 : Math.max(0, 13 - i));
        }
        SelfTrack s = b.build();
        for (int i = 0; i < 15; i++) {
            assertTrue(sample(s, i));
            int recorded = i < 3 ? 0 : Math.max(0, 13 - i);
            assertEquals("tick " + i, recorded, h.hurtTime(true));
            assertEquals("tick " + i, 0, h.hurtTime(false));
        }
    }

    @Test
    public void hurtResistantTimeCountsDownToZero() {
        int v = 20;
        for (int i = 0; i < 20; i++) {
            v = HandState.countDown(v);
        }
        assertEquals(0, v);
        assertEquals(0, HandState.countDown(0));
        assertEquals(9, HandState.countDown(10));
    }

    // ------------------------------------------------------------------ code review 2, I2

    private void equipAt(long timeUs, int slot, int... item) {
        byte[] body = new byte[item.length + 1];
        body[0] = (byte) slot;
        for (int k = 0; k < item.length; k++) {
            body[k + 1] = (byte) item[k];
        }
        b.record(timeUs, RewindFormat.TAG_EQUIP, body, 0, body.length);
    }

    /** S30 (window items) for {@code window}: id, u8 window, short count, then the slots (not read here). */
    static byte[] windowItems(int window) {
        return new byte[]{0x30, (byte) window, 0, 45};
    }

    /** S2F (set slot) for {@code window} and container slot {@code slot}, with an empty stack. */
    static byte[] setSlot(int window, int slot) {
        return new byte[]{0x2F, (byte) window, (byte) (slot >> 8), (byte) slot, (byte) 0xFF, (byte) 0xFF};
    }

    /** What the camera adapter does for a camera made anew by an S01/S07: every remembered stack is copied. */
    private byte[][] freshCameraCopy(byte[][] serverHotbar) {
        byte[][] cam = serverHotbar.clone();
        for (int i = 0; i < HandState.HOTBAR; i++) {
            if (h.hotbarItem(i) != null) {
                cam[i] = h.hotbarItem(i);
            }
        }
        return cam;
    }

    /**
     * The reviewer's case: a sword held in hotbar slot 1, then empty slot 0 selected; later an S07 makes a new camera
     * and an S30 (window 0) gives it an empty hotbar in the same playback tick. The fresh camera's full copy must not
     * put the old sword back: the server's inventory replaces the older EQUIP history.
     */
    @Test
    public void aFreshCameraAfterAnS30KeepsTheServersHotbarNotTheOldSword() {
        final byte[] empty = {(byte) 0xFF, (byte) 0xFF};
        b.segment(1);
        for (int i = 0; i < 20; i++) {
            tick(i, 0, i < 10 ? 1 : 0, -1, 0);
            if (i == 0) {
                equip(0, RewindFormat.EQUIP_HELD, 7, 1);            // the sword, held in slot 1
            } else if (i == 10) {
                equip(10, RewindFormat.EQUIP_HELD, 0xFF, 0xFF);      // empty slot 0 selected
            }
        }
        b.segment(2);                                                // after the S07
        enc.resetSegment();
        for (int i = 20; i < 30; i++) {
            tick(i, 0, 0, -1, 0);
            if (i == 20) {
                equip(20, RewindFormat.EQUIP_HELD, 0xFF, 0xFF);      // the segment's first EQUIP sample
            }
        }
        SelfTrack s = b.build();
        for (int i = 0; i < 20; i++) {
            assertTrue(sample(s, i));
        }
        assertArrayEquals("remembered before the S30", new byte[]{7, 1}, h.hotbarItem(1));

        // Tick 20: the S07 and the S30 (empty hotbar) are fed before the tick's sample.
        h.inventoryPacket(windowItems(0), 0, 4, T + 20 * TICK - 20_000L);
        assertTrue(sample(s, 20));
        byte[][] cam = freshCameraCopy(new byte[HandState.HOTBAR][]);
        assertEquals(0, h.selectedSlot());
        assertEquals("no ghost sword in slot 1", null, cam[1]);
        assertArrayEquals("the newer EQUIP of the held slot still applies", empty, cam[0]);
        for (int i = 21; i < 30; i++) {
            assertTrue(sample(s, i));
            assertFalse("tick " + i, h.hotbarChanged(1));
            assertNull("tick " + i, h.hotbarItem(1));
        }
    }

    /** An EQUIP recorded before the S30 (same tick window) is older than it and never overwrites it; a later one does. */
    @Test
    public void anEquipOlderThanTheS30IsNotAppliedAndANewerOneIs() {
        b.segment(1);
        for (int i = 0; i < 10; i++) {
            tick(i, 0, 2, -1, 0);
        }
        equipAt(T, RewindFormat.EQUIP_HELD, 7, 1);
        equipAt(T + 4 * TICK + 10_000L, RewindFormat.EQUIP_HELD, 7, 5);     // before the S30
        equipAt(T + 4 * TICK + 10_000L, RewindFormat.EQUIP_HELMET, 9, 1);
        equipAt(T + 7 * TICK, RewindFormat.EQUIP_HELD, 7, 4);               // after it
        SelfTrack s = b.build();
        for (int i = 0; i <= 4; i++) {
            assertTrue(sample(s, i));
        }
        h.inventoryPacket(windowItems(0), 0, 4, T + 4 * TICK + 20_000L);
        assertTrue(sample(s, 5));
        assertFalse("the older EQUIP does not overwrite the S30's slot", h.hotbarChanged(2));
        assertNull(h.hotbarItem(2));
        assertFalse("nor its armour", h.armourChanged(RewindFormat.EQUIP_HELMET));
        assertNull(h.armourItem(RewindFormat.EQUIP_HELMET));
        assertTrue(sample(s, 6));
        assertTrue(sample(s, 7));
        assertTrue("a newer EQUIP applies", h.hotbarChanged(2));
        assertArrayEquals(new byte[]{7, 4}, h.hotbarItem(2));
    }

    /** An S2F replaces one slot's history (hotbar 36-44, armour 5 helmet - 8 boots); other windows change nothing. */
    @Test
    public void anS2fForgetsOnlyItsOwnSlot() {
        b.segment(1);
        for (int i = 0; i < 10; i++) {
            tick(i, 0, i < 3 ? 1 : 3, -1, 0);
        }
        equip(0, RewindFormat.EQUIP_HELD, 7, 1);
        equip(0, RewindFormat.EQUIP_HELMET, 9, 1);
        equip(0, RewindFormat.EQUIP_BOOTS, 2, 1);
        equip(3, RewindFormat.EQUIP_HELD, 5, 16);
        SelfTrack s = b.build();
        for (int i = 0; i < 5; i++) {
            assertTrue(sample(s, i));
        }
        h.inventoryPacket(setSlot(1, 37), 0, 6, T + 5 * TICK);              // a chest's window: ignored
        h.inventoryPacket(setSlot(0, 40), 0, 6, T + 5 * TICK);              // hotbar slot 4: nothing kept there
        assertArrayEquals(new byte[]{7, 1}, h.hotbarItem(1));
        h.inventoryPacket(setSlot(0, 37), 0, 6, T + 5 * TICK);              // hotbar slot 1
        h.inventoryPacket(setSlot(0, 5), 0, 6, T + 5 * TICK);               // the helmet
        assertNull(h.hotbarItem(1));
        assertArrayEquals("other slots keep theirs", new byte[]{5, 16}, h.hotbarItem(3));
        assertNull(h.armourItem(RewindFormat.EQUIP_HELMET));
        assertArrayEquals(new byte[]{2, 1}, h.armourItem(RewindFormat.EQUIP_BOOTS));
        h.inventoryPacket(windowItems(1), 0, 4, T + 5 * TICK);              // another window's S30: ignored
        assertArrayEquals(new byte[]{5, 16}, h.hotbarItem(3));
    }

    @Test
    public void noTrackNoState() {
        b.segment(1);
        assertFalse(sample(b.build(), 0));
        assertEquals(0, h.selectedSlot());
        assertFalse(h.swingInProgress());
        assertFalse(h.inUse());
    }

    // ------------------------------------------------------------------ format v2 (Phase 13, C9)

    private final ExtCodec codec = new ExtCodec();
    private final ExtRecords.Builder xb = new ExtRecords.Builder();
    private int xseg;

    /** Tick i's head, 1 ms before its TICK (so the table is the v2 heads, tick i = [T + i x 50 ms - 1 ms, ...)). */
    private void head(int i) {
        int n = codec.tickHead(0);
        xb.addEncoded(xseg, T + i * TICK - 1_000, RewindFormat.TAG_TICK_HEAD, codec.buffer(), n);
    }

    /** An extension record stamped inside tick i (after its head, before its TICK). */
    private void ext(int i, int tag, int n) {
        xb.addEncoded(xseg, T + i * TICK - 500, tag, codec.buffer(), n);
    }

    private void out(int i, int packetId) {
        ext(i, RewindFormat.TAG_OUT, codec.out(packetId, new byte[]{1, 2}, 0, packetId == 0x0A ? 0 : 2));
    }

    private void use(int i, int count) {
        ext(i, RewindFormat.TAG_USE, codec.use(count));
    }

    private void inv(int i, int slot, byte[] item) {
        ext(i, RewindFormat.TAG_INV, codec.inv(slot, item, 0, item.length));
    }

    /** A protocol-47 slot: item {@code id}, {@code count}, damage 0, no tag. */
    private static byte[] item(int id, int count) {
        return new byte[]{(byte) (id >> 8), (byte) id, (byte) count, 0, 0, 0};
    }

    private static final byte[] EMPTY = {(byte) 0xFF, (byte) 0xFF};

    private ExtTrack xt;
    private TickTable v2table;

    private ExtTrack buildExt(SelfTrack s) {
        ExtRecords r = xb.build();
        xt = ExtTrack.of(r);
        v2table = TickTable.forRecording(T - 1_000, s, r);
        assertEquals(TickTable.Kind.V2, v2table.kind());
        return xt;
    }

    private boolean sampleV2(HandState hs, SelfTrack s, int i) {
        return hs.sample(s, xt, v2table, i, END);
    }

    @Test
    public void v2ASwingStartsOnTheTickOfEachRecordedC0aNotOnTheTickCounters() {
        b.segment(1);
        // The TICK counters tell of one swing starting at tick 12 (they read 1 from tick 13); the C0As say 3, 5, 9.
        int[] stored = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 3, 4, 5, 6, 1};
        for (int i = 0; i < stored.length; i++) {
            tick(i, 0, 0, stored[i] - 1, 0);
            head(i);
        }
        out(3, 0x0A);
        out(5, 0x0A); // mid-swing, first half: vanilla does not restart
        out(7, 0x08); // a block placement, not a swing
        out(9, 0x0A);
        out(9, 0x0A); // two in one tick: one start
        SelfTrack s = b.build();
        buildExt(s);
        VanillaSwing ref = new VanillaSwing();
        for (int i = 0; i < stored.length; i++) {
            boolean click = i == 3 || i == 5 || i == 9;
            if (click) {
                ref.swingItem(END);
            }
            ref.tick(END);
            assertTrue(sampleV2(h, s, i));
            String at = "tick " + i;
            assertTrue(at, h.extSwing());
            assertEquals(at, click, h.swingStarted());
            assertEquals(at, ref.progress, h.swingProgressInt());
            assertEquals(at, ref.inProgress, h.swingInProgress());
            assertEquals(at, ref.swing, h.swingProgress(), 0f);
            assertEquals(at, ref.prev, h.prevSwingProgress(), 0f);
        }
    }

    @Test
    public void v2ItemUseTakesTheRecordedCount() {
        b.segment(1);
        for (int i = 0; i < 40; i++) {
            tick(i, i >= 10 && i < 30 ? RewindFormat.FLAG_USING_ITEM : 0, 0, -1, 0);
            head(i);
        }
        use(10, 72000); // a bow draw starts at the full count
        use(20, 32);    // the count jumps (a new item in use): the recorder writes it, playback takes it
        use(30, 0);     // released
        SelfTrack s = b.build();
        buildExt(s);
        for (int i = 0; i < 40; i++) {
            assertTrue(sampleV2(h, s, i));
            String at = "tick " + i;
            assertTrue(at, h.extUse());
            assertEquals(at, i >= 10 && i < 30, h.inUse());
            if (h.inUse()) {
                assertEquals(at, i < 20 ? 72000 - (i - 10) : 32 - (i - 20), h.useCount(72000));
            }
        }
    }

    @Test
    public void v2AllFortyOneSlotsFollowTheInvRecordsAndWinOverEquip() {
        b.segment(1);
        byte[][] want = new byte[RewindFormat.INV_SLOTS][];
        for (int i = 0; i < 12; i++) {
            tick(i, 0, 0, -1, 0);
            head(i);
        }
        for (int slot = 0; slot < RewindFormat.INV_SLOTS; slot++) {
            inv(0, slot, slot % 5 == 4 ? EMPTY : item(slot + 1, 1)); // a segment's first tick: all 41
        }
        equip(0, RewindFormat.EQUIP_HELD, 7, 64);                    // disagrees with INV slot 0
        equip(0, RewindFormat.EQUIP_HELMET, 9, 1);                   // and with INV slot 39
        inv(4, 0, item(100, 5));
        inv(4, 20, item(101, 1));
        inv(4, 38, item(102, 1));
        inv(4, 40, item(103, 2));
        equip(6, RewindFormat.EQUIP_HELD, 7, 1);                     // INV still wins
        inv(8, 40, EMPTY);
        SelfTrack s = b.build();
        buildExt(s);
        int[][] changes = {{4, 0, 20, 38, 40}, {8, 40}};
        for (int i = 0; i < 12; i++) {
            boolean[] changed = new boolean[RewindFormat.INV_SLOTS];
            if (i == 0) {
                for (int slot = 0; slot < RewindFormat.INV_SLOTS; slot++) {
                    want[slot] = slot % 5 == 4 ? EMPTY : item(slot + 1, 1);
                    changed[slot] = true;
                }
            }
            for (int[] c : changes) {
                if (c[0] == i) {
                    for (int j = 1; j < c.length; j++) {
                        changed[c[j]] = true;
                    }
                }
            }
            if (i == 4) {
                want[0] = item(100, 5);
                want[20] = item(101, 1);
                want[38] = item(102, 1);
                want[40] = item(103, 2);
            } else if (i == 8) {
                want[40] = EMPTY;
            }
            assertTrue(sampleV2(h, s, i));
            String at = "tick " + i;
            assertTrue(at, h.extInventory());
            for (int slot = 0; slot < RewindFormat.INV_SLOTS; slot++) {
                assertArrayEquals(at + " slot " + slot, want[slot], h.invItem(slot));
                assertEquals(at + " slot " + slot, changed[slot], h.invChanged(slot));
            }
            for (int slot = 0; slot < HandState.HOTBAR; slot++) {
                assertArrayEquals(at + " hotbar " + slot, want[slot], h.hotbarItem(slot));
                assertEquals(at + " hotbar " + slot, changed[slot], h.hotbarChanged(slot));
            }
            for (int e = RewindFormat.EQUIP_BOOTS; e <= RewindFormat.EQUIP_HELMET; e++) {
                int slot = RewindFormat.INV_ARMOR_FIRST + e - RewindFormat.EQUIP_BOOTS;
                assertArrayEquals(at + " armour " + e, want[slot], h.armourItem(e));
                assertEquals(at + " armour " + e, changed[slot], h.armourChanged(e));
            }
        }
    }

    /** A state first taken at a later tick (a seek, a new connection) is the INV records' at that tick. */
    @Test
    public void v2InventoryFirstTakenLaterAndAcrossASegmentIsTheKeyedState() {
        b.segment(1);
        for (int i = 0; i < 10; i++) {
            tick(i, 0, 0, -1, 0);
            head(i);
        }
        for (int slot = 0; slot < RewindFormat.INV_SLOTS; slot++) {
            inv(0, slot, item(slot + 1, 1));
        }
        inv(3, 12, item(200, 3));
        inv(6, 40, item(201, 1));
        b.segment(2);
        enc.resetSegment();
        xseg = 1;
        for (int i = 10; i < 20; i++) {
            tick(i, 0, 0, -1, 0);
            head(i);
        }
        for (int slot = 0; slot < RewindFormat.INV_SLOTS; slot++) {
            inv(10, slot, slot == 12 ? item(300, 1) : EMPTY);            // the new segment restates all 41
        }
        SelfTrack s = b.build();
        buildExt(s);
        for (int i = 0; i < 20; i++) {
            assertTrue(sampleV2(h, s, i));
            if (i == 7 || i == 15) {
                HandState fresh = new HandState();
                assertTrue(sampleV2(fresh, s, i));
                for (int slot = 0; slot < RewindFormat.INV_SLOTS; slot++) {
                    String at = "tick " + i + " slot " + slot;
                    assertArrayEquals(at, h.invItem(slot), fresh.invItem(slot));
                    assertTrue(at + ": a first sample hands over every slot", fresh.invChanged(slot));
                }
            }
        }
        assertArrayEquals(item(300, 1), h.invItem(12));
        assertArrayEquals("segment 2 restated the cursor", EMPTY, h.invItem(40));
        HandState at7 = new HandState();
        assertTrue(sampleV2(at7, s, 7));
        assertArrayEquals(item(200, 3), at7.invItem(12));
        assertArrayEquals(item(201, 1), at7.invItem(40));
        assertArrayEquals(item(1, 1), at7.invItem(0));
    }

    /** C10: no v2 record of a kind, no v2 path for it; a v1 recording plays exactly as before. */
    @Test
    public void withoutTheV2RecordsTheV1PathRunsUnchanged() {
        b.segment(1);
        for (int i = 0; i < 30; i++) {
            tick(i, i >= 10 && i < 20 ? RewindFormat.FLAG_USING_ITEM : 0, i < 15 ? 1 : 4, i % 9 == 3 ? 0 : -1, 0);
            head(i);
        }
        equip(0, RewindFormat.EQUIP_HELD, 7, 64);
        equip(16, RewindFormat.EQUIP_HELD, 5, 2);
        SelfTrack s = b.build();
        ExtTrack headsOnly = buildExt(s);
        assertFalse(HandState.usesExt(headsOnly));
        assertFalse(HandState.usesExt(ExtTrack.EMPTY));
        out(0, 0x0A);
        assertTrue("an OUT record makes it a v2 hand", HandState.usesExt(ExtTrack.of(xb.build())));
        HandState v1 = new HandState();
        TickTable v1table = TickTable.forRecording(T - 1_000, s, ExtRecords.EMPTY);
        for (int i = 0; i < 30; i++) {
            assertTrue(h.sample(s, headsOnly, v1table, i, END));
            assertTrue(v1.sample(s, v1table, i, END));
            String at = "tick " + i;
            assertFalse(at, h.extSwing() || h.extUse() || h.extInventory());
            assertEquals(at, v1.swingStarted(), h.swingStarted());
            assertEquals(at, v1.swingProgressInt(), h.swingProgressInt());
            assertEquals(at, v1.inUse(), h.inUse());
            assertEquals(at, v1.useCount(72000), h.useCount(72000));
            assertEquals(at, v1.selectedSlot(), h.selectedSlot());
            for (int slot = 0; slot < HandState.HOTBAR; slot++) {
                assertEquals(at, v1.hotbarChanged(slot), h.hotbarChanged(slot));
                assertArrayEquals(at, v1.hotbarItem(slot), h.hotbarItem(slot));
            }
            assertNull(h.invItem(0));
        }
        assertTrue(Arrays.equals(new byte[]{5, 2}, h.hotbarItem(4)));
    }
}
