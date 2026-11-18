package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;

/**
 * The recorded player's stand-in, one world tick at a time (PLAN section 4, "Puppet"): what the Ornithe puppet entity
 * takes from the self track at tick time {@code t = origin + k x 50 ms}. Pure; one instance per puppet, reset when the
 * puppet is made anew (every restart).
 *
 * <ul>
 *   <li><b>Pose and flags:</b> the last TICK at or before t (position, body yaw and pitch, head yaw, the
 *       {@code RewindFormat.FLAG_*} flags). The entity moves there during the tick, like any other player.</li>
 *   <li><b>Swing:</b> vanilla's {@code swingItem} sets the counter to -1 and its tick moves it to 0, so the tick a
 *       swing starts reads the same as idle (R1 stores the counter plus one). A recorded counter of 1 therefore means
 *       the swing started on the client tick before it: the previous TICK when it is at most 60 ms earlier, else 50 ms
 *       before. {@link #swingStarted()} is true at the one sample whose window ({@code (previous sample, t]}, or the
 *       50 ms up to t after a reset) holds that start, so each recorded swing, a restart by a repeated click included,
 *       triggers exactly once. This reads one record ahead of t.</li>
 *   <li><b>Equipment:</b> per EQUIP slot (0 held, 1-4 boots to helmet) the latest EQUIP of t's segment at or before t.
 *       {@link #equipChanged} is true only at the sample where that event became the latest, so each change applies
 *       once; after a reset every slot with a recorded item applies again.</li>
 *   <li><b>Hidden</b> in your view (the camera is you) and while the recorded invisible flag is set.</li>
 * </ul>
 */
public final class PuppetState {

    /** EQUIP slots: 0 held, 1-4 boots to helmet. */
    public static final int SLOTS = RewindFormat.EQUIP_HELMET + 1;
    /** Longest gap between two TICKs that are taken as consecutive client ticks. */
    static final long CONSECUTIVE_US = 60_000L;

    private final SelfTrackEncoder.State state = new SelfTrackEncoder.State();
    private boolean sampled;
    private long lastUs;
    private int segment = -1;
    private boolean swingStarted;
    private final int[] equipIndex = new int[SLOTS];
    private final byte[][] equipItem = new byte[SLOTS][];
    private int equipChanged;

    public PuppetState() {
        reset();
    }

    /** Forgets everything: the next sample is the first (a new puppet). */
    public void reset() {
        sampled = false;
        lastUs = 0;
        segment = -1;
        swingStarted = false;
        equipChanged = 0;
        for (int i = 0; i < SLOTS; i++) {
            equipIndex[i] = -1;
            equipItem[i] = null;
        }
        state.flags = 0;
    }

    /**
     * Takes the track's state for the world tick at {@code timeUs}.
     *
     * @return false if the track has no TICK (nothing changes)
     */
    public boolean sample(SelfTrack track, long timeUs) {
        swingStarted = false;
        equipChanged = 0;
        int si = track == null ? -1 : track.segmentIndexAt(timeUs);
        if (si < 0 || !track.stateAt(timeUs, state)) {
            return false;
        }
        SelfTrack.Segment s = track.segment(si);
        long windowStart = sampled && timeUs > lastUs ? lastUs : timeUs - Timeline.TICK_US;
        swingStarted = swingStartIn(s, windowStart, timeUs);
        if (si != segment) {
            segment = si;
            for (int i = 0; i < SLOTS; i++) {
                equipIndex[i] = -1;
            }
        }
        applyEquip(s, timeUs);
        sampled = true;
        lastUs = timeUs;
        return true;
    }

    /** A recorded swing started in {@code (from, to]}. */
    static boolean swingStartIn(SelfTrack.Segment s, long from, long to) {
        int first = s.tickIndexAt(from) + 1;
        int last = s.tickIndexAt(to + CONSECUTIVE_US);
        for (int j = Math.max(0, first - 1); j <= last; j++) {
            if (s.swingProgressInt(j) != 1 || j > 0 && s.swingProgressInt(j - 1) == 1) {
                continue;
            }
            long start = swingStartUs(s, j);
            if (start > from && start <= to) {
                return true;
            }
        }
        return false;
    }

    /** When the swing that TICK {@code j} (counter 1) belongs to started: the client tick before it. */
    static long swingStartUs(SelfTrack.Segment s, int j) {
        long t = s.tickTimeUs(j);
        if (j > 0 && t - s.tickTimeUs(j - 1) <= CONSECUTIVE_US) {
            return s.tickTimeUs(j - 1);
        }
        return t - Timeline.TICK_US;
    }

    private void applyEquip(SelfTrack.Segment s, long timeUs) {
        int found = 0;
        int need = 0;
        int latest = s.equipIndexAt(timeUs);
        for (int i = latest; i >= 0 && found != (1 << SLOTS) - 1; i--) {
            int slot = s.equipSlot(i);
            if (slot < 0 || slot >= SLOTS || (found & (1 << slot)) != 0) {
                continue;
            }
            found |= 1 << slot;
            if (equipIndex[slot] != i) {
                equipIndex[slot] = i;
                equipItem[slot] = s.equipItem(i);
                need |= 1 << slot;
            }
        }
        equipChanged = need;
    }

    public double x() {
        return state.x;
    }

    public double y() {
        return state.y;
    }

    public double z() {
        return state.z;
    }

    /** Body yaw. */
    public float yaw() {
        return state.yaw;
    }

    public float pitch() {
        return state.pitch;
    }

    public float headYaw() {
        return state.headYaw;
    }

    /** {@code RewindFormat.FLAG_*} of the last sample. */
    public int flags() {
        return state.flags;
    }

    public boolean onGround() {
        return (state.flags & RewindFormat.FLAG_ON_GROUND) != 0;
    }

    public boolean sneaking() {
        return (state.flags & RewindFormat.FLAG_SNEAKING) != 0;
    }

    public boolean sprinting() {
        return (state.flags & RewindFormat.FLAG_SPRINTING) != 0;
    }

    public boolean usingItem() {
        return (state.flags & RewindFormat.FLAG_USING_ITEM) != 0;
    }

    public boolean burning() {
        return (state.flags & RewindFormat.FLAG_BURNING) != 0;
    }

    public boolean invisible() {
        return (state.flags & RewindFormat.FLAG_INVISIBLE) != 0;
    }

    /** A recorded swing starts at this sample's tick (true at most once per swing). */
    public boolean swingStarted() {
        return swingStarted;
    }

    /** Slot {@code slot}'s item became a new EQUIP at this sample. */
    public boolean equipChanged(int slot) {
        return slot >= 0 && slot < SLOTS && (equipChanged & (1 << slot)) != 0;
    }

    /** Slot {@code slot}'s protocol-47 item bytes (an empty slot is a stack of id -1), or null if none recorded. */
    public byte[] equipItem(int slot) {
        return slot >= 0 && slot < SLOTS ? equipItem[slot] : null;
    }

    /** Not drawn: your view (the camera is you), the recorded invisible flag, or nothing sampled yet. */
    public boolean hidden(boolean yourView) {
        return yourView || !sampled || invisible();
    }
}
