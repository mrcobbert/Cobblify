package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;

import java.util.Arrays;

/**
 * The recorded player's hand, held item and hotbar for the camera in your view, one world tick at a time (PLAN
 * section 4.1, Rev 3 amendment 1; parity "Self state binding"). Pure; one instance per connection, fed every world
 * tick from tick 0 of a restart, so its state at a tick is the same however the tick was reached. It is fed in both
 * views; only {@link #hurtTime} depends on the view. World tick k of the recording's {@link TickTable} takes tick k's
 * end state: the samples stamped before T[k+1].
 *
 * <ul>
 *   <li><b>Selected slot</b> = TICK {@code heldSlot} of tick k's end state ({@link SelfTrack#tickState}).</li>
 *   <li><b>Hotbar and armour stacks:</b> every EQUIP event of the segment stamped before T[k+1], in order, once each:
 *       a held stack goes to the hotbar slot selected when it was recorded (the client's own view, counts it predicted
 *       included),
 *       armour to its slot. Applying each event only once lets a later S2F for that slot replace it; a slot you
 *       switch away from keeps its last stack. The full state ({@link #hotbarItem}, {@link #armourItem}) is there
 *       for a camera made anew by an S01/S07. A window-0 inventory packet ({@link #inventoryPacket}) replaces the
 *       history of the slots it covers, so that full state never restores a stack older than the server's
 *       inventory (code review 2, I2).</li>
 *   <li><b>Swing:</b> vanilla's {@code swingItem} (counter -1 unless a swing is in its first half) and
 *       {@code updateArmSwingProgress} (counter + 1, back to 0 at the swing's end), run at the tick each recorded
 *       swing started ({@link SelfTrack#swingStartsAt}: tick k+1 read as lookahead). Repeated clicks restart it as in
 *       vanilla.</li>
 *   <li><b>Item in use:</b> the using-item flag's rising tick starts it with the item's full duration, the count
 *       drops by one per tick, the falling tick clears it.</li>
 *   <li><b>hurtTime:</b> the recorded TICK value in your view; 0 in the free camera, which never tilts.</li>
 * </ul>
 *
 * <p><b>Format v2</b> (Phase 13, C9), taken from the recording's {@link ExtTrack} by tick, each only when the
 * recording holds that record kind (a v1 recording has none and runs exactly the rules above, C10):
 * <ul>
 *   <li><b>Swing</b> ({@link #extSwing}): starts on the tick an OUT C0A (vanilla's {@code swingItem} sends it) is
 *       stamped in, instead of the TICK counters' lookahead.</li>
 *   <li><b>Item use count</b> ({@link #extUse}): the last USE record stamped before T[k+1], counted down one per tick
 *       since the tick it was stamped in (the recorder writes USE only when the count does not follow, so a bow draw
 *       starts at its 72000). Whether an item is in use stays the TICK flag.</li>
 *   <li><b>Inventory</b> ({@link #extInventory}): all 41 slots (0-35 main, 36-39 boots to helmet, 40 the cursor) are
 *       the last INV record of each slot stamped before T[k+1] ({@link ExtTrack#keyedAt}), over every segment (each
 *       segment restates all 41 on its first tick). They win over EQUIP: {@link #hotbarItem} and {@link #armourItem}
 *       answer from INV. A pure function of k, so the state after a seek or a fresh start is the same.</li>
 * </ul>
 */
public final class HandState {

    public static final int HOTBAR = 9;
    /** Vanilla's swing length without haste or mining fatigue. */
    public static final int SWING_END = 6;

    private final SelfTrackEncoder.State state = new SelfTrackEncoder.State();
    private boolean sampled;
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

    /** C0A, the swing packet vanilla's {@code EntityPlayerSP.swingItem} sends. */
    private static final int SWING_PACKET = 0x0A;
    private boolean extSwing;
    private boolean extUse;
    private boolean extInventory;
    private int recordedUse;
    private final int[] invRecord = minusOnes(RewindFormat.INV_SLOTS);
    private final byte[][] inv = new byte[RewindFormat.INV_SLOTS][];
    private long invChanged;
    private final ExtCodec.Out outBody = new ExtCodec.Out();
    private final ExtCodec.Use useBody = new ExtCodec.Use();
    private final ExtCodec.Inv invBody = new ExtCodec.Inv();

    private static int[] minusOnes(int n) {
        int[] a = new int[n];
        Arrays.fill(a, -1);
        return a;
    }

    /**
     * Takes the track's state for world tick {@code k} of {@code table}.
     *
     * @param swingEnd the swing's length ({@link #swingEnd}) from the recorded player's effects
     * @return false if the track has no TICK (nothing changes)
     */
    public boolean sample(SelfTrack track, TickTable table, long k, int swingEnd) {
        return sample(track, ExtTrack.EMPTY, table, k, swingEnd);
    }

    /**
     * Takes world tick {@code k}: the self track as {@link #sample(SelfTrack, TickTable, long, int)}, with the v2
     * inputs of {@code ext} for each record kind it holds (class comment). {@link ExtTrack#EMPTY}: the v1 rules.
     */
    public boolean sample(SelfTrack track, ExtTrack ext, TickTable table, long k, int swingEnd) {
        hotbarChanged = 0;
        armourChanged = 0;
        invChanged = 0L;
        swingStarted = false;
        useStarted = false;
        ExtTrack x = ext == null ? ExtTrack.EMPTY : ext;
        extSwing = x.has(RewindFormat.TAG_OUT);
        extUse = x.has(RewindFormat.TAG_USE);
        extInventory = x.has(RewindFormat.TAG_INV);
        long before = table.timeOf(k + 1) - 1;
        int si = track == null ? -1 : track.segmentIndexAt(before);
        if (si < 0 || !track.tickState(table, k, state)) {
            return false;
        }
        SelfTrack.Segment s = track.segment(si);
        if (si != segment) {
            segment = si;
            equipCursor = -1;
        }
        selected = Math.max(0, Math.min(HOTBAR - 1, state.heldSlot));
        if (extInventory) {
            applyInv(x, before);
        } else {
            applyEquip(s, before);
        }
        swing(extSwing ? swingSent(x, table, k) : track.swingStartsAt(table, k), Math.max(1, swingEnd));
        use((state.flags & RewindFormat.FLAG_USING_ITEM) != 0);
        if (extUse) {
            recordedUse = recordedUse(x, table, k, before);
        }
        sampled = true;
        return true;
    }

    /** Each slot's last INV record before {@code timeUs}; a slot whose record changed is marked and copied. */
    private void applyInv(ExtTrack x, long timeUs) {
        for (int slot = 0; slot < RewindFormat.INV_SLOTS; slot++) {
            int rec = -1;
            for (int seg = 0; seg < x.segmentCount(); seg++) {
                rec = Math.max(rec, x.keyedAt(RewindFormat.TAG_INV, slot, seg, timeUs)); // record order = time order
            }
            if (rec == invRecord[slot]) {
                continue;
            }
            invRecord[slot] = rec;
            invChanged |= 1L << slot;
            if (rec < 0) {
                inv[slot] = null;
            } else {
                x.read(rec, invBody);
                inv[slot] = Arrays.copyOfRange(invBody.buf, invBody.slotOff, invBody.slotOff + invBody.slotLen);
            }
        }
    }

    /** An OUT C0A is stamped in tick {@code k}'s interval (tick 0: or before it). */
    private boolean swingSent(ExtTrack x, TickTable table, long k) {
        for (int seg = 0; seg < x.segmentCount(); seg++) {
            int end = x.endOfTick(RewindFormat.TAG_OUT, seg, table, k);
            for (int j = x.firstInTick(RewindFormat.TAG_OUT, seg, table, k); j < end; j++) {
                if (x.read(x.record(RewindFormat.TAG_OUT, seg, j), outBody).packetId == SWING_PACKET) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Vanilla's {@code itemInUseCount} at the end of tick {@code k}: the last USE record before {@code timeUs},
     * less one per tick since the tick it was stamped in; 0 before any.
     */
    private int recordedUse(ExtTrack x, TickTable table, long k, long timeUs) {
        int rec = -1;
        for (int seg = 0; seg < x.segmentCount(); seg++) {
            rec = Math.max(rec, x.stateAt(RewindFormat.TAG_USE, seg, timeUs));
        }
        if (rec < 0) {
            return 0;
        }
        int count = x.read(rec, useBody).count;
        long stamp = x.records().timeUs(rec);
        long j = table.tickOf(stamp);
        if (table.timeOf(j) > stamp) {
            j--; // the tick whose interval holds the stamp
        }
        long v = count - (k - Math.max(0L, j));
        return (int) Math.max(Integer.MIN_VALUE, v);
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

    /** The recording holds a v2 hand input (OUT, USE or INV records); false for every v1 recording. */
    public static boolean usesExt(ExtTrack ext) {
        return ext != null && (ext.has(RewindFormat.TAG_OUT) || ext.has(RewindFormat.TAG_USE)
                || ext.has(RewindFormat.TAG_INV));
    }

    /** The last sample's swing came from OUT C0A records. */
    public boolean extSwing() {
        return extSwing;
    }

    /** The last sample's item-use count is the recorded USE count. */
    public boolean extUse() {
        return extUse;
    }

    /** The last sample's inventory (all 41 slots) is the INV records'. */
    public boolean extInventory() {
        return extInventory;
    }

    /**
     * INV slot {@code slot} (0-35 main, 36-39 boots to helmet, 40 cursor): its protocol-47 bytes (an empty slot is
     * id -1), or null when no INV record set it yet. Do not modify.
     */
    public byte[] invItem(int slot) {
        return slot >= 0 && slot < RewindFormat.INV_SLOTS ? inv[slot] : null;
    }

    /** INV slot {@code slot}'s record changed at this sample (every slot with a record at a first sample). */
    public boolean invChanged(int slot) {
        return slot >= 0 && slot < RewindFormat.INV_SLOTS && (invChanged & (1L << slot)) != 0;
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

    /** Hotbar slot {@code slot} got an EQUIP stack at this sample (v2 inventory: its INV record changed). */
    public boolean hotbarChanged(int slot) {
        if (extInventory) {
            return slot >= 0 && slot < HOTBAR && invChanged(slot);
        }
        return slot >= 0 && slot < HOTBAR && (hotbarChanged & (1 << slot)) != 0;
    }

    /** Hotbar slot {@code slot}'s last EQUIP stack (protocol-47 bytes), or null if none (v2 inventory: INV's). */
    public byte[] hotbarItem(int slot) {
        if (extInventory) {
            return slot >= 0 && slot < HOTBAR ? inv[slot] : null;
        }
        return slot >= 0 && slot < HOTBAR ? hotbar[slot] : null;
    }

    /** Armour slot {@code equipSlot} (1 boots - 4 helmet) got an EQUIP stack at this sample (v2 inventory: INV). */
    public boolean armourChanged(int equipSlot) {
        boolean valid = equipSlot >= RewindFormat.EQUIP_BOOTS && equipSlot <= RewindFormat.EQUIP_HELMET;
        if (extInventory) {
            return valid && invChanged(armourInvSlot(equipSlot));
        }
        return valid && (armourChanged & (1 << equipSlot)) != 0;
    }

    /** Armour slot {@code equipSlot}'s last EQUIP stack, or null if none (v2 inventory: INV's). */
    public byte[] armourItem(int equipSlot) {
        if (equipSlot < RewindFormat.EQUIP_BOOTS || equipSlot > RewindFormat.EQUIP_HELMET) {
            return null;
        }
        return extInventory ? inv[armourInvSlot(equipSlot)] : armour[equipSlot];
    }

    /** The INV slot of EQUIP armour slot {@code equipSlot} (1 boots - 4 helmet: 36-39). */
    private static int armourInvSlot(int equipSlot) {
        return RewindFormat.INV_ARMOR_FIRST + equipSlot - RewindFormat.EQUIP_BOOTS;
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

    /**
     * Vanilla's {@code itemInUseCount} for an item of {@code maxDuration}: full at the start, one less per tick; with
     * USE records ({@link #extUse}) the recorded count whatever the duration.
     */
    public int useCount(int maxDuration) {
        return extUse ? recordedUse : maxDuration - useTicks;
    }

    /** The camera's {@code hurtTime}: as recorded in your view, 0 in the free camera. */
    public int hurtTime(boolean yourView) {
        return yourView && sampled ? state.hurtTime : 0;
    }
}
