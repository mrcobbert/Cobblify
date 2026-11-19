package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The recorded player's own track, decoded into arrays per segment: TICK states (fields a mask leaves out carried
 * forward from the previous TICK of the same segment; the first TICK of a segment starts from zeros, and R1 writes it
 * full), CAMERA samples and EQUIP events. Immutable after {@link Builder#build()}; reading allocates nothing.
 *
 * <p><b>Self state binding</b> (PLAN "Self state binding", round 2 B2; TASK C9). TICK and EQUIP samples are written
 * at the end of the tick that produced them, so a sample stamped in {@code [T[k], T[k+1])} of the recording's
 * {@link TickTable} is tick k's end state (v2: by construction; v1: the table is built from those same stamps minus
 * 1 ms). Playback asks by tick, never by time: {@link #tickState} is the state after the last TICK stamped before
 * T[k+1] (carried forward when tick k wrote none), {@link #swingStartsAt} reads tick k+1 (the swing's first tick reads
 * like idle), and the EQUIP lookups of {@link PuppetState} and {@link HandState} take events stamped before T[k+1].
 *
 * <p><b>Pose.</b> {@link #pose} gives your eye position and camera rotation for a drawn frame: position = lerp(tick
 * j-1's end state, tick j's, partial), j the last tick run and partial the share of tick j's recorded interval the
 * frame's playback time has reached ({@link TickClock#partial}), plus the eye height of tick j's flags. That is what
 * vanilla drew (the client player one tick behind its latest tick), on the table's own interval rather than 50 ms;
 * across a segment boundary the new segment's state is used as is. Rotation: the latest CAMERA sample at or before the
 * playback time, of the segment that answers for it, interpolated with the next sample when that one is at most 50 ms
 * later (raw, unwrapped yaw); before that segment's first CAMERA sample, TICK rotation interpolated like position. The
 * segment for a time is the last one whose first TICK is at or before it (the first segment with a TICK when it is
 * earlier).
 */
public final class SelfTrack {

    /** Vanilla {@code EntityPlayer.getEyeHeight()} standing. */
    public static final double EYE_HEIGHT = 1.62;
    /** Standing minus vanilla's 0.08 while sneaking. */
    public static final double EYE_HEIGHT_SNEAKING = 1.54;
    /** Longest gap between two CAMERA samples that is interpolated. */
    public static final long CAMERA_LERP_US = 50_000L;

    /** A pose written by {@link #pose}; reuse one instance per caller. */
    public static final class Pose {
        public double x;
        /** Feet. */
        public double y;
        public double z;
        /** {@link #y} plus the eye height. */
        public double eyeY;
        public float yaw;
        public float pitch;
        /** Flags ({@code RewindFormat.FLAG_*}) of the TICK the pose comes from. */
        public int flags;
    }

    /** One segment's arrays, indexed by record number within the segment, each kind in time order. */
    public static final class Segment {
        private final int number;
        private final int ticks;
        private final long[] tickTime;
        private final int[] tickMask;
        private final double[] x;
        private final double[] y;
        private final double[] z;
        private final float[] yaw;
        private final float[] pitch;
        private final float[] headYaw;
        private final byte[] flags;
        private final byte[] heldSlot;
        private final byte[] swing;
        private final byte[] hurt;
        private final int cameras;
        private final long[] cameraTime;
        private final float[] cameraYaw;
        private final float[] cameraPitch;
        private final int equips;
        private final long[] equipTime;
        private final byte[] equipSlot;
        private final byte[][] equipItem;

        private Segment(SegmentBuilder b) {
            number = b.number;
            ticks = b.ticks;
            tickTime = Arrays.copyOf(b.tickTime, ticks);
            tickMask = Arrays.copyOf(b.tickMask, ticks);
            x = Arrays.copyOf(b.x, ticks);
            y = Arrays.copyOf(b.y, ticks);
            z = Arrays.copyOf(b.z, ticks);
            yaw = Arrays.copyOf(b.yaw, ticks);
            pitch = Arrays.copyOf(b.pitch, ticks);
            headYaw = Arrays.copyOf(b.headYaw, ticks);
            flags = Arrays.copyOf(b.flags, ticks);
            heldSlot = Arrays.copyOf(b.heldSlot, ticks);
            swing = Arrays.copyOf(b.swing, ticks);
            hurt = Arrays.copyOf(b.hurt, ticks);
            cameras = b.cameras;
            cameraTime = Arrays.copyOf(b.cameraTime, cameras);
            cameraYaw = Arrays.copyOf(b.cameraYaw, cameras);
            cameraPitch = Arrays.copyOf(b.cameraPitch, cameras);
            equips = b.equips;
            equipTime = Arrays.copyOf(b.equipTime, equips);
            equipSlot = Arrays.copyOf(b.equipSlot, equips);
            equipItem = Arrays.copyOf(b.equipItem, equips);
        }

        /** The segment number in the file. */
        public int number() {
            return number;
        }

        public int tickCount() {
            return ticks;
        }

        public long tickTimeUs(int i) {
            return tickTime[i];
        }

        /** The mask record i was written with ({@code RewindFormat.TICK_*}). */
        public int tickMask(int i) {
            return tickMask[i];
        }

        public double x(int i) {
            return x[i];
        }

        public double y(int i) {
            return y[i];
        }

        public double z(int i) {
            return z[i];
        }

        public float yaw(int i) {
            return yaw[i];
        }

        public float pitch(int i) {
            return pitch[i];
        }

        public float headYaw(int i) {
            return headYaw[i];
        }

        public int flags(int i) {
            return flags[i] & 0xFF;
        }

        public int heldSlot(int i) {
            return heldSlot[i] & 0xFF;
        }

        /** Vanilla's {@code swingProgressInt} (-1 when not swinging; R1 stores it plus one). */
        public int swingProgressInt(int i) {
            return (swing[i] & 0xFF) - 1;
        }

        public int hurtTime(int i) {
            return hurt[i] & 0xFF;
        }

        public int cameraCount() {
            return cameras;
        }

        public long cameraTimeUs(int i) {
            return cameraTime[i];
        }

        public float cameraYaw(int i) {
            return cameraYaw[i];
        }

        public float cameraPitch(int i) {
            return cameraPitch[i];
        }

        public int equipCount() {
            return equips;
        }

        public long equipTimeUs(int i) {
            return equipTime[i];
        }

        /** {@code RewindFormat.EQUIP_*}: 0 held, 1-4 boots to helmet. */
        public int equipSlot(int i) {
            return equipSlot[i];
        }

        /** The protocol-47 slot bytes as vanilla wrote them. Shared: do not modify. */
        public byte[] equipItem(int i) {
            return equipItem[i];
        }

        /** Index of the last TICK at or before {@code t}, or -1. */
        public int tickIndexAt(long t) {
            return lastAtOrBefore(tickTime, ticks, t);
        }

        /** Index of the last CAMERA sample at or before {@code t}, or -1. */
        public int cameraIndexAt(long t) {
            return lastAtOrBefore(cameraTime, cameras, t);
        }

        /** Index of the last EQUIP event at or before {@code t}, or -1. */
        public int equipIndexAt(long t) {
            return lastAtOrBefore(equipTime, equips, t);
        }

        private void state(int i, SelfTrackEncoder.State out) {
            out.x = x[i];
            out.y = y[i];
            out.z = z[i];
            out.yaw = yaw[i];
            out.pitch = pitch[i];
            out.headYaw = headYaw[i];
            out.flags = flags(i);
            out.heldSlot = heldSlot(i);
            out.swingProgressInt = swingProgressInt(i);
            out.hurtTime = hurtTime(i);
        }
    }

    private final Segment[] segments;
    private final int tickTotal;
    private final int cameraTotal;
    private final int equipTotal;

    private SelfTrack(Segment[] segments) {
        this.segments = segments;
        int t = 0;
        int c = 0;
        int e = 0;
        for (Segment s : segments) {
            t += s.ticks;
            c += s.cameras;
            e += s.equips;
        }
        tickTotal = t;
        cameraTotal = c;
        equipTotal = e;
    }

    public int segmentCount() {
        return segments.length;
    }

    /** Segment by position (playback order), not by its number. */
    public Segment segment(int index) {
        return segments[index];
    }

    public int tickCount() {
        return tickTotal;
    }

    public int cameraCount() {
        return cameraTotal;
    }

    public int equipCount() {
        return equipTotal;
    }

    /** Position of the segment that answers for time {@code t}, or -1 when no segment has a TICK. */
    public int segmentIndexAt(long t) {
        int found = -1;
        for (int i = 0; i < segments.length; i++) {
            Segment s = segments[i];
            if (s.ticks == 0) {
                continue;
            }
            if (found < 0 || s.tickTime[0] <= t) {
                found = i;
            }
            if (s.tickTime[0] > t) {
                break;
            }
        }
        return found;
    }

    /**
     * Writes tick {@code k}'s end state: the last TICK stamped before {@code T[k+1]} (the first TICK of that segment
     * when none is); false if there is no TICK.
     */
    public boolean tickState(TickTable table, long k, SelfTrackEncoder.State out) {
        long before = table.timeOf(k + 1) - 1;
        int si = segmentIndexAt(before);
        if (si < 0) {
            return false;
        }
        Segment s = segments[si];
        s.state(Math.max(0, s.tickIndexAt(before)), out);
        return true;
    }

    /**
     * True when a recorded swing starts in tick {@code k}: vanilla's {@code swingItem} sets the counter to -1 and the
     * tick moves it to 0, so the swing's first tick reads like idle and tick k+1 is the first to read 1. So: a TICK
     * stamped in tick k+1's interval reads 1 and the TICK before it does not. Each tick reads its own interval, so a
     * recorded swing (a restart by a repeated click included) starts exactly once.
     */
    public boolean swingStartsAt(TickTable table, long k) {
        long from = table.timeOf(k + 1);
        long before = table.timeOf(k + 2) - 1;
        int si = segmentIndexAt(before);
        if (si < 0) {
            return false;
        }
        Segment s = segments[si];
        for (int j = s.tickIndexAt(before); j >= 0 && s.tickTime[j] >= from; j--) {
            if (s.swingProgressInt(j) == 1 && !(j > 0 && s.swingProgressInt(j - 1) == 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes your eye position and camera rotation for a frame that draws tick {@code j} at {@code partial} (0-1) of
     * its recorded interval, at playback time {@code cameraUs}; false if there is no TICK.
     */
    public boolean pose(TickTable table, long j, float partial, long cameraUs, Pose out) {
        long beforeJ = table.timeOf(j + 1) - 1;
        int si = segmentIndexAt(beforeJ);
        if (si < 0) {
            return false;
        }
        Segment s = segments[si];
        int i = Math.max(0, s.tickIndexAt(beforeJ));
        long beforePrev = table.timeOf(j) - 1;
        int prev = segmentIndexAt(beforePrev) == si ? Math.max(0, s.tickIndexAt(beforePrev)) : i;
        double a = Math.max(0.0, Math.min(1.0, partial));
        out.x = lerp(s.x[prev], s.x[i], a);
        out.y = lerp(s.y[prev], s.y[i], a);
        out.z = lerp(s.z[prev], s.z[i], a);
        out.flags = s.flags(i);
        out.eyeY = out.y + ((out.flags & RewindFormat.FLAG_SNEAKING) != 0 ? EYE_HEIGHT_SNEAKING : EYE_HEIGHT);

        int ci = segmentIndexAt(cameraUs);
        Segment cs = ci < 0 ? s : segments[ci];
        int c = cs.cameraIndexAt(cameraUs);
        if (c < 0) {
            out.yaw = (float) lerp(s.yaw[prev], s.yaw[i], a);
            out.pitch = (float) lerp(s.pitch[prev], s.pitch[i], a);
        } else if (c + 1 < cs.cameras && cs.cameraTime[c + 1] - cs.cameraTime[c] <= CAMERA_LERP_US
                && cs.cameraTime[c + 1] > cs.cameraTime[c]) {
            double ca = (double) (cameraUs - cs.cameraTime[c]) / (cs.cameraTime[c + 1] - cs.cameraTime[c]);
            out.yaw = (float) lerp(cs.cameraYaw[c], cs.cameraYaw[c + 1], ca);
            out.pitch = (float) lerp(cs.cameraPitch[c], cs.cameraPitch[c + 1], ca);
        } else {
            out.yaw = cs.cameraYaw[c];
            out.pitch = cs.cameraPitch[c];
        }
        return true;
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    /** Last index in {@code times[0, n)} (ascending) with a value at or before {@code t}, or -1. */
    static int lastAtOrBefore(long[] times, int n, long t) {
        int lo = 0;
        int hi = n - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= t) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    // ---------------- building ----------------

    /**
     * Collects TRACK records segment by segment. Call {@link #segment} before a segment's records, then
     * {@link #record} for each in time order. Tags other than TICK, CAMERA and EQUIP are ignored.
     */
    public static final class Builder {
        private final List<SegmentBuilder> segments = new ArrayList<SegmentBuilder>();
        private SegmentBuilder current;
        private final SelfTrackEncoder.State state = new SelfTrackEncoder.State();

        public Builder segment(int number) {
            current = new SegmentBuilder(number);
            segments.add(current);
            reset(state);
            return this;
        }

        /**
         * @throws RewindFormat.FormatException if a TICK, CAMERA or EQUIP body is malformed
         * @throws IllegalStateException        before the first {@link #segment} call
         */
        public Builder record(long timeUs, int tag, byte[] buf, int off, int len) {
            if (current == null) {
                throw new IllegalStateException("no segment");
            }
            switch (tag) {
                case RewindFormat.TAG_TICK: {
                    int mask = SelfTrackEncoder.applyTick(buf, off, len, state);
                    current.addTick(timeUs, mask, state);
                    break;
                }
                case RewindFormat.TAG_CAMERA: {
                    if (len < 8) {
                        throw new RewindFormat.FormatException("camera");
                    }
                    current.addCamera(timeUs, RewindFormat.getFloat(buf, off), RewindFormat.getFloat(buf, off + 4));
                    break;
                }
                case RewindFormat.TAG_EQUIP: {
                    if (len < 1) {
                        throw new RewindFormat.FormatException("equip");
                    }
                    current.addEquip(timeUs, buf[off] & 0xFF, Arrays.copyOfRange(buf, off + 1, off + len));
                    break;
                }
                default:
                    break;
            }
            return this;
        }

        public SelfTrack build() {
            Segment[] out = new Segment[segments.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = new Segment(segments.get(i));
            }
            return new SelfTrack(out);
        }

        private static void reset(SelfTrackEncoder.State s) {
            s.x = 0;
            s.y = 0;
            s.z = 0;
            s.yaw = 0;
            s.pitch = 0;
            s.headYaw = 0;
            s.flags = 0;
            s.heldSlot = 0;
            s.swingProgressInt = -1;
            s.hurtTime = 0;
        }
    }

    private static final class SegmentBuilder {
        final int number;
        int ticks;
        long[] tickTime = new long[64];
        int[] tickMask = new int[64];
        double[] x = new double[64];
        double[] y = new double[64];
        double[] z = new double[64];
        float[] yaw = new float[64];
        float[] pitch = new float[64];
        float[] headYaw = new float[64];
        byte[] flags = new byte[64];
        byte[] heldSlot = new byte[64];
        byte[] swing = new byte[64];
        byte[] hurt = new byte[64];
        int cameras;
        long[] cameraTime = new long[64];
        float[] cameraYaw = new float[64];
        float[] cameraPitch = new float[64];
        int equips;
        long[] equipTime = new long[16];
        byte[] equipSlot = new byte[16];
        byte[][] equipItem = new byte[16][];

        SegmentBuilder(int number) {
            this.number = number;
        }

        void addTick(long t, int mask, SelfTrackEncoder.State s) {
            if (ticks == tickTime.length) {
                int n = ticks * 2;
                tickTime = Arrays.copyOf(tickTime, n);
                tickMask = Arrays.copyOf(tickMask, n);
                x = Arrays.copyOf(x, n);
                y = Arrays.copyOf(y, n);
                z = Arrays.copyOf(z, n);
                yaw = Arrays.copyOf(yaw, n);
                pitch = Arrays.copyOf(pitch, n);
                headYaw = Arrays.copyOf(headYaw, n);
                flags = Arrays.copyOf(flags, n);
                heldSlot = Arrays.copyOf(heldSlot, n);
                swing = Arrays.copyOf(swing, n);
                hurt = Arrays.copyOf(hurt, n);
            }
            int i = ticks++;
            tickTime[i] = t;
            tickMask[i] = mask;
            x[i] = s.x;
            y[i] = s.y;
            z[i] = s.z;
            yaw[i] = s.yaw;
            pitch[i] = s.pitch;
            headYaw[i] = s.headYaw;
            flags[i] = (byte) s.flags;
            heldSlot[i] = (byte) s.heldSlot;
            swing[i] = (byte) (s.swingProgressInt + 1);
            hurt[i] = (byte) s.hurtTime;
        }

        void addCamera(long t, float yawValue, float pitchValue) {
            if (cameras == cameraTime.length) {
                int n = cameras * 2;
                cameraTime = Arrays.copyOf(cameraTime, n);
                cameraYaw = Arrays.copyOf(cameraYaw, n);
                cameraPitch = Arrays.copyOf(cameraPitch, n);
            }
            int i = cameras++;
            cameraTime[i] = t;
            cameraYaw[i] = yawValue;
            cameraPitch[i] = pitchValue;
        }

        void addEquip(long t, int slot, byte[] item) {
            if (equips == equipTime.length) {
                int n = equips * 2;
                equipTime = Arrays.copyOf(equipTime, n);
                equipSlot = Arrays.copyOf(equipSlot, n);
                equipItem = Arrays.copyOf(equipItem, n);
            }
            int i = equips++;
            equipTime[i] = t;
            equipSlot[i] = (byte) slot;
            equipItem[i] = item;
        }
    }
}
