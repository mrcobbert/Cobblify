package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;

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
 *       for a camera made anew by an S01/S07.</li>
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
            if (slot == RewindFormat.EQUIP_HELD) {
                int t = s.tickIndexAt(s.equipTimeUs(i));
                int held = s.heldSlot(Math.max(0, t));
                if (held >= 0 && held < HOTBAR) {
                    hotbar[held] = s.equipItem(i);
                    hotbarChanged |= 1 << held;
                }
            } else if (slot >= RewindFormat.EQUIP_BOOTS && slot <= RewindFormat.EQUIP_HELMET) {
                armour[slot] = s.equipItem(i);
                armourChanged |= 1 << slot;
            }
        }
        if (latest > equipCursor) {
            equipCursor = latest;
        }
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
