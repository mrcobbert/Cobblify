package com.bedwarsqol.rewind;

import java.nio.charset.StandardCharsets;

/**
 * Encodes the player's own TRACK bodies (the server never echoes them) into one reused buffer, writing
 * only what changed. Client thread only; no allocation in the per-tick and per-frame paths.
 *
 * <p>Each encode method returns the body length in {@link #buffer()}, or 0 when nothing changed (the
 * caller then appends nothing). The caller appends a non-zero body with
 * {@code producer.appendTrack(nowUs, tag, encoder.buffer(), 0, n)}, reading {@link #buffer()} only after
 * the encode call returned (an encode may grow it into a new array).
 *
 * <p>TICK body: u8 mask, then the fields whose bit is set, in bit order (see {@code RewindFormat.TICK_*}).
 * The first TICK after construction or {@link #resetSegment()} carries every field. Rate limiting of
 * CAMERA samples (at most every 4 ms) is the sampler's job, not this class's.
 */
public final class SelfTrackEncoder {

    /** Size of a full TICK body: mask + 24 + 8 + 4 + 1 + 1 + 1 + 1. */
    public static final int FULL_TICK_LENGTH = 1 + 24 + 8 + 4 + 1 + 1 + 1 + 1;
    private static final int EQUIP_SLOTS = RewindFormat.EQUIP_HELMET + 1;

    private byte[] buf = new byte[256];

    private boolean full = true;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private float headYaw;
    private int flags;
    private int heldSlot;
    private int swing;
    private int hurtTime;

    private boolean cameraSet;
    private float cameraYaw;
    private float cameraPitch;

    private final byte[][] equip = new byte[EQUIP_SLOTS][];
    private final int[] equipLength = new int[EQUIP_SLOTS];
    private final boolean[] equipSet = new boolean[EQUIP_SLOTS];

    /** The body written by the last encode call. */
    public byte[] buffer() {
        return buf;
    }

    /** A new segment starts: the next TICK is full and the next CAMERA and EQUIP samples are written. */
    public void resetSegment() {
        full = true;
        cameraSet = false;
        for (int i = 0; i < EQUIP_SLOTS; i++) {
            equipSet[i] = false;
        }
    }

    /**
     * TICK body with the fields that differ from the previous tick (all of them after a reset).
     *
     * @param flags            {@code RewindFormat.FLAG_*} bits
     * @param swingProgressInt vanilla's value (-1 when not swinging); stored as value + 1
     * @return body length, or 0 if nothing changed
     */
    public int tick(double x, double y, double z, float yaw, float pitch, float headYaw, int flags, int heldSlot,
                    int swingProgressInt, int hurtTime) {
        int swing = (swingProgressInt + 1) & 0xFF;
        flags &= 0xFF;
        heldSlot &= 0xFF;
        hurtTime &= 0xFF;
        int mask;
        if (full) {
            mask = RewindFormat.TICK_ALL;
        } else {
            mask = 0;
            if (!same(x, this.x) || !same(y, this.y) || !same(z, this.z)) {
                mask |= RewindFormat.TICK_POS;
            }
            if (!same(yaw, this.yaw) || !same(pitch, this.pitch)) {
                mask |= RewindFormat.TICK_ROT;
            }
            if (!same(headYaw, this.headYaw)) {
                mask |= RewindFormat.TICK_HEAD_YAW;
            }
            if (flags != this.flags) {
                mask |= RewindFormat.TICK_FLAGS;
            }
            if (heldSlot != this.heldSlot) {
                mask |= RewindFormat.TICK_HELD_SLOT;
            }
            if (swing != this.swing) {
                mask |= RewindFormat.TICK_SWING;
            }
            if (hurtTime != this.hurtTime) {
                mask |= RewindFormat.TICK_HURT;
            }
            if (mask == 0) {
                return 0;
            }
        }
        full = false;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.headYaw = headYaw;
        this.flags = flags;
        this.heldSlot = heldSlot;
        this.swing = swing;
        this.hurtTime = hurtTime;

        byte[] b = buf;
        b[0] = (byte) mask;
        int p = 1;
        if ((mask & RewindFormat.TICK_POS) != 0) {
            p = RewindFormat.putDouble(b, p, x);
            p = RewindFormat.putDouble(b, p, y);
            p = RewindFormat.putDouble(b, p, z);
        }
        if ((mask & RewindFormat.TICK_ROT) != 0) {
            p = RewindFormat.putFloat(b, p, yaw);
            p = RewindFormat.putFloat(b, p, pitch);
        }
        if ((mask & RewindFormat.TICK_HEAD_YAW) != 0) {
            p = RewindFormat.putFloat(b, p, headYaw);
        }
        if ((mask & RewindFormat.TICK_FLAGS) != 0) {
            b[p++] = (byte) flags;
        }
        if ((mask & RewindFormat.TICK_HELD_SLOT) != 0) {
            b[p++] = (byte) heldSlot;
        }
        if ((mask & RewindFormat.TICK_SWING) != 0) {
            b[p++] = (byte) swing;
        }
        if ((mask & RewindFormat.TICK_HURT) != 0) {
            b[p++] = (byte) hurtTime;
        }
        return p;
    }

    /** CAMERA body (yaw, pitch as rendered), or 0 if unchanged since the last sample of this segment. */
    public int camera(float yaw, float pitch) {
        if (cameraSet && same(yaw, cameraYaw) && same(pitch, cameraPitch)) {
            return 0;
        }
        cameraSet = true;
        cameraYaw = yaw;
        cameraPitch = pitch;
        RewindFormat.putFloat(buf, 0, yaw);
        RewindFormat.putFloat(buf, 4, pitch);
        return 8;
    }

    /**
     * EQUIP body: u8 slot + the protocol-47 slot bytes (as vanilla's {@code writeItemStackToBuffer}
     * wrote them), or 0 if that slot's bytes did not change. Copies only on change.
     *
     * @param slot 0 held, 1-4 boots to helmet
     */
    public int equip(int slot, byte[] item, int off, int len) {
        if (slot < 0 || slot >= EQUIP_SLOTS) {
            throw new IllegalArgumentException("slot " + slot);
        }
        byte[] last = equip[slot];
        if (equipSet[slot] && equipLength[slot] == len && rangeEquals(last, item, off, len)) {
            return 0;
        }
        if (last == null || last.length < len) {
            last = equip[slot] = new byte[Math.max(len, 16)];
        }
        System.arraycopy(item, off, last, 0, len);
        equipLength[slot] = len;
        equipSet[slot] = true;
        ensure(1 + len);
        buf[0] = (byte) slot;
        System.arraycopy(item, off, buf, 1, len);
        return 1 + len;
    }

    /** MARKER body: u8 kind ({@code RewindFormat.MARKER_*}). */
    public int marker(int kind) {
        buf[0] = (byte) kind;
        return 1;
    }

    /**
     * GAME_START body: varint64 {@code backUs}, how far before the record's own time the game started (the
     * logical start = record time - backUs). A negative value is stored as 0.
     */
    public int gameStart(long backUs) {
        return RewindFormat.putVarLong(buf, 0, Math.max(0L, backUs));
    }

    /** GAME_END body: u8 result ({@code RewindFormat.GAME_END_*}). */
    public int gameEnd(int result) {
        buf[0] = (byte) result;
        return 1;
    }

    /** INFO body: u8 key ({@code RewindFormat.INFO_*}) + string. */
    public int info(int key, String value) {
        byte[] s = value.getBytes(StandardCharsets.UTF_8);
        if (s.length > RewindFormat.MAX_STRING_BYTES) {
            throw new IllegalArgumentException("info too long");
        }
        ensure(1 + 5 + s.length);
        buf[0] = (byte) key;
        int p = RewindFormat.putVarInt(buf, 1, s.length);
        System.arraycopy(s, 0, buf, p, s.length);
        return p + s.length;
    }

    private void ensure(int n) {
        if (buf.length < n) {
            byte[] bigger = new byte[Math.max(n, buf.length * 2)];
            System.arraycopy(buf, 0, bigger, 0, buf.length);
            buf = bigger;
        }
    }

    private static boolean same(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    private static boolean same(float a, float b) {
        return Float.floatToIntBits(a) == Float.floatToIntBits(b);
    }

    private static boolean rangeEquals(byte[] a, byte[] b, int off, int len) {
        for (int i = 0; i < len; i++) {
            if (a[i] != b[off + i]) {
                return false;
            }
        }
        return true;
    }

    // ---------------- decoding ----------------

    /** Length of the TICK fields (after the mask byte) that {@code mask} selects. */
    public static int tickFieldsLength(int mask) {
        int n = 0;
        if ((mask & RewindFormat.TICK_POS) != 0) {
            n += 24;
        }
        if ((mask & RewindFormat.TICK_ROT) != 0) {
            n += 8;
        }
        if ((mask & RewindFormat.TICK_HEAD_YAW) != 0) {
            n += 4;
        }
        n += Integer.bitCount(mask & (RewindFormat.TICK_FLAGS | RewindFormat.TICK_HELD_SLOT
                | RewindFormat.TICK_SWING | RewindFormat.TICK_HURT));
        return n;
    }

    /** The decoded self state; fields a TICK leaves out keep their previous value. */
    public static final class State {
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
        public float headYaw;
        public int flags;
        public int heldSlot;
        /** Vanilla's value: -1 when not swinging. */
        public int swingProgressInt = -1;
        public int hurtTime;
    }

    /**
     * Applies a TICK body {@code b[off, off+len)} over {@code s}. Returns its mask.
     *
     * @throws RewindFormat.FormatException if the body is cut or the mask is unknown
     */
    public static int applyTick(byte[] b, int off, int len, State s) {
        RewindFormat.Cursor c = new RewindFormat.Cursor(b, off, off + len);
        int mask = c.u8();
        if ((mask & ~RewindFormat.TICK_ALL) != 0) {
            throw new RewindFormat.FormatException("tick mask");
        }
        if ((mask & RewindFormat.TICK_POS) != 0) {
            s.x = c.f64();
            s.y = c.f64();
            s.z = c.f64();
        }
        if ((mask & RewindFormat.TICK_ROT) != 0) {
            s.yaw = c.f32();
            s.pitch = c.f32();
        }
        if ((mask & RewindFormat.TICK_HEAD_YAW) != 0) {
            s.headYaw = c.f32();
        }
        if ((mask & RewindFormat.TICK_FLAGS) != 0) {
            s.flags = c.u8();
        }
        if ((mask & RewindFormat.TICK_HELD_SLOT) != 0) {
            s.heldSlot = c.u8();
        }
        if ((mask & RewindFormat.TICK_SWING) != 0) {
            s.swingProgressInt = c.u8() - 1;
        }
        if ((mask & RewindFormat.TICK_HURT) != 0) {
            s.hurtTime = c.u8();
        }
        return mask;
    }
}
