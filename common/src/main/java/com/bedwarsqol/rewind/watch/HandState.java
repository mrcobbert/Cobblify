package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;

import java.util.Arrays;

/**
 * The recorded player's hand, held item and hotbar for the camera in your view, one world tick at a time (PLAN
 * section 4.1, Rev 3 amendment 1). Pure; one instance per connection, fed every world tick from tick 0 of a restart,
 * so its state at a tick is the same however the tick was reached. It is fed in both views; only {@link #hurtTime}
 * depends on the view.
 *
 * <ul>
 *   <li><b>Selected slot</b> = TICK {@code heldSlot} (the last TICK at or before t).</li>
 *   <li><b>Hotbar and armour stacks:</b> every EQUIP event of t's segment up to t, in order, once each: a held stack
 *       goes to the hotbar slot selected when it was recorded (the client's own view, counts it predicted included),
 *       armour to its slot. Applying each event only once lets a later S2F for that slot replace it; a slot you
 *       switch away from keeps its last stack. The full state ({@link #hotbarItem}, {@link #armourItem}) is there
 *       for a camera made anew by an S01/S07. A window-0 inventory packet ({@link #inventoryPacket}) replaces the
 *       history of the slots it covers, so that full state never restores a stack older than the server's
 *       inventory (code review 2, I2).</li>
 *   <li><b>Swing:</b> vanilla's {@code swingItem} (counter -1 unless a swing is in its first half) and
 *       {@code updateArmSwingProgress} (counter + 1, back to 0 at the swing's end), run at the tick each recorded
 *       swing started ({@link PuppetState#swingStartIn}: one TICK of lookahead). Repeated clicks restart it as in
 *       vanilla.</li>
 *   <li><b>Item in use:</b> the using-item flag's rising tick starts it with the item's full duration, the count
 *       drops by one per tick, the falling tick clears it.</li>
 *   <li><b>hurtTime:</b> the recorded TICK value in your view; 0 in the free camera, which never tilts.</li>
 * </ul>
 */
public final class HandState {

    public static final int HOTBAR = 9;
    /** Vanilla's swing length without haste or mining fatigue. */
    public static final int SWING_END = 6;

    private final SelfTrackEncoder.State state = new SelfTrackEncoder.State();
    private boolean sampled;
    private long lastUs;
    private int segment = -1;
    private int equipCursor = -1;

    private int selected;
    private final byte[][] hotbar = new byte[HOTBAR][];
    private final byte[][] armour = new byte[RewindFormat.EQUIP_HELMET + 1][];
    private int hotbarChanged;
    private int armourChanged;
    /** Per slot: the last window-0 inventory packet's time; older EQUIP events never apply (code review 2, I2). */
    private final long[] hotbarSince = filled(HOTBAR);
    private final long[] armourSince = filled(RewindFormat.EQUIP_HELMET + 1);

    private static final int SET_SLOT = 0x2F;
    private static final int WINDOW_ITEMS = 0x30;
    private static final int PLAYER_WINDOW = 0;
    /** The player inventory container's first hotbar slot (36-44) and its helmet slot (5; 8 is the boots). */
    private static final int HOTBAR_FIRST_SLOT = 36;
    private static final int HELMET_SLOT = 5;

    private static long[] filled(int n) {
        long[] a = new long[n];
        Arrays.fill(a, Long.MIN_VALUE);
        return a;
    }

    private boolean swingStarted;
    private boolean swingInProgress;
    private int swingProgressInt;
    private float swingProgress;
    private float prevSwingProgress;

    private boolean inUse;
    private boolean useStarted;
    private int useTicks;

    /**
     * Takes the track's state for the world tick at {@code timeUs}.
     *
     * @param swingEnd the swing's length ({@link #swingEnd}) from the recorded player's effects
     * @return false if the track has no TICK (nothing changes)
     */
    public boolean sample(SelfTrack track, long timeUs, int swingEnd) {
        hotbarChanged = 0;
        armourChanged = 0;
        swingStarted = false;
        useStarted = false;
        int si = track == null ? -1 : track.segmentIndexAt(timeUs);
        if (si < 0 || !track.stateAt(timeUs, state)) {
            return false;
        }
        SelfTrack.Segment s = track.segment(si);
        long windowStart = sampled && timeUs > lastUs ? lastUs : timeUs - Timeline.TICK_US;
        if (si != segment) {
            segment = si;
            equipCursor = -1;
        }
        selected = Math.max(0, Math.min(HOTBAR - 1, state.heldSlot));
        applyEquip(s, timeUs);
        swing(PuppetState.swingStartIn(s, windowStart, timeUs), Math.max(1, swingEnd));
        use((state.flags & RewindFormat.FLAG_USING_ITEM) != 0);
        sampled = true;
        lastUs = timeUs;
        return true;
    }

    private void applyEquip(SelfTrack.Segment s, long timeUs) {
        int latest = s.equipIndexAt(timeUs);
        for (int i = equipCursor + 1; i <= latest; i++) {
            int slot = s.equipSlot(i);
            long at = s.equipTimeUs(i);
            if (slot == RewindFormat.EQUIP_HELD) {
                int t = s.tickIndexAt(at);
                int held = s.heldSlot(Math.max(0, t));
                if (held >= 0 && held < HOTBAR && at >= hotbarSince[held]) {
                    hotbar[held] = s.equipItem(i);
                    hotbarChanged |= 1 << held;
                }
            } else if (slot >= RewindFormat.EQUIP_BOOTS && slot <= RewindFormat.EQUIP_HELMET
                    && at >= armourSince[slot]) {
                armour[slot] = s.equipItem(i);
                armourChanged |= 1 << slot;
            }
        }
        if (latest > equipCursor) {
            equipCursor = latest;
        }
    }

    /**
     * A window-0 inventory packet the camera just took at {@code timeUs} (code review 2, I2): the server's contents
     * replace the EQUIP history of the slots it covers, an S30 every hotbar and armour slot, an S2F its one slot
     * (container slots 36-44 the hotbar, 5-8 helmet to boots). Their remembered stacks are forgotten, and EQUIP events
     * recorded before {@code timeUs} are never applied to them, so a camera made anew restores only stacks newer than
     * the server's inventory. Other packets and other windows change nothing.
     *
     * @param buf varint packet id + body at {@code [off, off + len)}, as stored
     */
    public void inventoryPacket(byte[] buf, int off, int len, long timeUs) {
        int id = RewindFormat.packetId(buf, off, len);
        if (id != SET_SLOT && id != WINDOW_ITEMS) {
            return;
        }
        int body = off + idLength(buf, off, len);
        if (body >= off + len || buf[body] != PLAYER_WINDOW) {
            return;
        }
        if (id == WINDOW_ITEMS) {
            for (int i = 0; i < HOTBAR; i++) {
                forgetHotbar(i, timeUs);
            }
            for (int s = RewindFormat.EQUIP_BOOTS; s <= RewindFormat.EQUIP_HELMET; s++) {
                forgetArmour(s, timeUs);
            }
            return;
        }
        if (body + 2 >= off + len) {
            return;
        }
        int slot = (short) ((buf[body + 1] & 0xFF) << 8 | buf[body + 2] & 0xFF);
        if (slot >= HOTBAR_FIRST_SLOT && slot < HOTBAR_FIRST_SLOT + HOTBAR) {
            forgetHotbar(slot - HOTBAR_FIRST_SLOT, timeUs);
        } else if (slot >= HELMET_SLOT && slot < HELMET_SLOT + RewindFormat.EQUIP_HELMET) {
            forgetArmour(RewindFormat.EQUIP_HELMET - (slot - HELMET_SLOT), timeUs);
        }
    }

    private void forgetHotbar(int i, long timeUs) {
        hotbar[i] = null;
        hotbarChanged &= ~(1 << i);
        hotbarSince[i] = Math.max(hotbarSince[i], timeUs);
    }

    private void forgetArmour(int equipSlot, long timeUs) {
        armour[equipSlot] = null;
        armourChanged &= ~(1 << equipSlot);
        armourSince[equipSlot] = Math.max(armourSince[equipSlot], timeUs);
    }

    private static int idLength(byte[] buf, int off, int len) {
        int n = 0;
        while (n < Math.min(len, 5)) {
            if ((buf[off + n++] & 0x80) == 0) {
                break;
            }
        }
        return n;
    }

    /** One vanilla tick of the swing: {@code swingItem} when a swing starts, then {@code updateArmSwingProgress}. */
    private void swing(boolean start, int end) {
        if (start) {
            swingStarted = true;
            if (!swingInProgress || swingProgressInt >= end / 2 || swingProgressInt < 0) {
                swingProgressInt = -1;
                swingInProgress = true;
            }
        }
        prevSwingProgress = swingProgress;
        if (swingInProgress) {
            swingProgressInt++;
            if (swingProgressInt >= end) {
                swingProgressInt = 0;
                swingInProgress = false;
            }
        } else {
            swingProgressInt = 0;
        }
        swingProgress = (float) swingProgressInt / end;
    }

    private void use(boolean flag) {
        if (flag && !inUse) {
            inUse = true;
            useStarted = true;
            useTicks = 0;
        } else if (flag) {
            useTicks++;
        } else {
            inUse = false;
            useTicks = 0;
        }
    }

    /** Vanilla's {@code getArmSwingAnimationEnd}: haste shortens, mining fatigue lengthens (amplifier, or -1). */
    public static int swingEnd(int hasteAmplifier, int fatigueAmplifier) {
        if (hasteAmplifier >= 0) {
            return SWING_END - (1 + hasteAmplifier);
        }
        if (fatigueAmplifier >= 0) {
            return SWING_END + (1 + fatigueAmplifier) * 2;
        }
        return SWING_END;
    }

    /** One tick of vanilla's {@code hurtResistantTime} countdown (the camera's own tick does not run it). */
    public static int countDown(int hurtResistantTime) {
        return hurtResistantTime > 0 ? hurtResistantTime - 1 : 0;
    }

    /** The hotbar slot selected, 0-8. */
    public int selectedSlot() {
        return selected;
    }

    /** Hotbar slot {@code slot} got an EQUIP stack at this sample. */
    public boolean hotbarChanged(int slot) {
        return slot >= 0 && slot < HOTBAR && (hotbarChanged & (1 << slot)) != 0;
    }

    /** Hotbar slot {@code slot}'s last EQUIP stack (protocol-47 bytes), or null if none. */
    public byte[] hotbarItem(int slot) {
        return slot >= 0 && slot < HOTBAR ? hotbar[slot] : null;
    }

    /** Armour slot {@code equipSlot} (1 boots - 4 helmet) got an EQUIP stack at this sample. */
    public boolean armourChanged(int equipSlot) {
        return equipSlot >= RewindFormat.EQUIP_BOOTS && equipSlot <= RewindFormat.EQUIP_HELMET
                && (armourChanged & (1 << equipSlot)) != 0;
    }

    /** Armour slot {@code equipSlot}'s last EQUIP stack, or null if none. */
    public byte[] armourItem(int equipSlot) {
        return equipSlot >= RewindFormat.EQUIP_BOOTS && equipSlot <= RewindFormat.EQUIP_HELMET
                ? armour[equipSlot] : null;
    }

    /** A recorded swing started at this sample's tick. */
    public boolean swingStarted() {
        return swingStarted;
    }

    public boolean swingInProgress() {
        return swingInProgress;
    }

    public int swingProgressInt() {
        return swingProgressInt;
    }

    public float swingProgress() {
        return swingProgress;
    }

    public float prevSwingProgress() {
        return prevSwingProgress;
    }

    /** An item is in use (bow pull, eating, blocking). */
    public boolean inUse() {
        return inUse;
    }

    /** The use started at this sample's tick. */
    public boolean useStarted() {
        return useStarted;
    }

    /** Vanilla's {@code itemInUseCount} for an item of {@code maxDuration}: full at the start, one less per tick. */
    public int useCount(int maxDuration) {
        return maxDuration - useTicks;
    }

    /** The camera's {@code hurtTime}: as recorded in your view, 0 in the free camera. */
    public int hurtTime(boolean yourView) {
        return yourView && sampled ? state.hurtTime : 0;
    }
}
