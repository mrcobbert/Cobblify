package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;
import org.junit.Test;

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
 */
public class HandStateTest {

    private static final long T = 5_000_000L;
    private static final long TICK = Timeline.TICK_US;
    private static final int END = 6;

    private final SelfTrackEncoder enc = new SelfTrackEncoder();
    private final SelfTrack.Builder b = new SelfTrack.Builder();
    private final HandState h = new HandState();

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
            assertTrue(h.sample(s, T + i * TICK, END));
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
            assertTrue(h.sample(s, T + i * TICK, END));
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
            assertTrue(h.sample(s, T + i * TICK, END));
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
        // The full state, for a camera made anew by an S01/S07.
        assertArrayEquals(new byte[]{7, 62}, h.hotbarItem(0));
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
            assertTrue(h.sample(s, T + i * TICK, END));
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

    @Test
    public void noTrackNoState() {
        b.segment(1);
        assertFalse(h.sample(b.build(), T, END));
        assertEquals(0, h.selectedSlot());
        assertFalse(h.swingInProgress());
        assertFalse(h.inUse());
    }
}
