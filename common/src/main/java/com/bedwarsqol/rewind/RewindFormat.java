package com.bedwarsqol.rewind;

import java.nio.charset.StandardCharsets;

/**
 * Constants and primitive codecs of the Rewind disk format v1 ({@code .cbr}). This is R2's contract:
 * change nothing here without bumping {@link #FORMAT_VERSION}.
 *
 * <p>Encoding: big-endian; protocol varints (LEB128, at most 5 bytes for a varint, 10 for a varint64);
 * strings are a varint byte length then UTF-8.
 *
 * <p><b>File header</b> (28 B): magic {@code 43 42 52 57 4E 44 31 0A}, u16 formatVersion=1, u16 flags=0,
 * u32 protocol=47, i64 baseEpochMs (wall clock at timeUs 0), u32 CRC32 of bytes 0-23.
 *
 * <p><b>Block</b> (32 B header + payload): "RWB1" | u8 type | u8 codec (0 stored, 1 raw deflate) |
 * u16 reserved | u32 segment | i64 firstTimeUs | u32 rawLen | u32 storedLen (at most 8 MiB) | u32 CRC32
 * over header bytes 0-27 and the stored payload. NET and TRACK blocks never straddle segments.
 *
 * <p><b>Record times.</b> In NET and TRACK payloads every record starts with an unsigned 32-bit varint
 * {@code dtUs} measured from the previous record; the first record of a block is measured from the
 * block's {@code firstTimeUs}. So record 0 is at {@code firstTimeUs + dt0} and record i at
 * {@code t(i-1) + dt_i}. A writer that starts a block mid-chunk sets {@code firstTimeUs} to the time
 * of the record just before it, which lets it copy chunk bytes unchanged.
 *
 * <p><b>Segment invariant.</b> The first NET record of every segment is that segment's own S01, or its
 * dimension-changing S07.
 */
public final class RewindFormat {

    private RewindFormat() {
    }

    // ---- file header ----
    private static final byte[] FILE_MAGIC = {0x43, 0x42, 0x52, 0x57, 0x4E, 0x44, 0x31, 0x0A};
    public static final int FORMAT_VERSION = 1;
    public static final int PROTOCOL = 47;
    public static final int FILE_HEADER_LENGTH = 28;

    // ---- blocks ----
    /** "RWB1" as a big-endian u32. */
    public static final int BLOCK_MAGIC = 0x52574231;
    public static final int BLOCK_HEADER_LENGTH = 32;
    /** Largest stored payload a block may carry; the reader rejects more without reading it. */
    public static final int MAX_STORED_LENGTH = 8 << 20;
    /** Largest inflated payload; the writer never exceeds a chunk (2 MiB + 16), the reader caps allocation. */
    public static final int MAX_RAW_LENGTH = 8 << 20;

    public static final int BLOCK_NET = 1;
    public static final int BLOCK_TRACK = 2;
    public static final int BLOCK_HEADER = 3;
    public static final int BLOCK_INDEX = 4;
    public static final int BLOCK_END = 5;

    public static final int CODEC_STORED = 0;
    public static final int CODEC_DEFLATE = 1;

    /** HEADER source 0: the client-thread snapshot, starting with the synthetic S01; holds no S47. */
    public static final int SOURCE_CLIENT_SNAPSHOT = 0;
    /** HEADER source 1: the capture handler's S47 frozen at the boundary, in stream order. */
    public static final int SOURCE_CAPTURE_CARRY = 1;

    // ---- chunks ----
    /** Largest packet vanilla 1.8.9 accepts (2,097,152 B). */
    public static final int MAX_PACKET_LENGTH = 2_097_152;
    /** A chunk holds the largest packet with its record prefix: 2 MiB + 16 B. */
    public static final int CHUNK_CAPACITY = MAX_PACKET_LENGTH + 16;
    /** Largest record prefix: varint dtUs (5) + varint len (5), or varint dtUs (5) + u8 tag (1). */
    public static final int MAX_RECORD_PREFIX = 10;
    /** Largest dtUs a record can carry (unsigned 32-bit varint); longer gaps are clamped. */
    public static final long MAX_DT_US = 0xFFFFFFFFL;

    // ---- TRACK tags ----
    public static final int TAG_TICK = 0x01;
    public static final int TAG_CAMERA = 0x02;
    public static final int TAG_EQUIP = 0x03;
    public static final int TAG_MARKER = 0x04;
    public static final int TAG_INFO = 0x05;
    /**
     * Body: varint64 {@code backUs}. The record is written when the game is opened; the logical game start
     * (visit start, or the Bed Wars active edge) is the record's time minus {@code backUs} (code review 1, I6).
     */
    public static final int TAG_GAME_START = 0x06;
    public static final int TAG_GAME_END = 0x07;

    /** TICK mask bits, in body order. */
    public static final int TICK_POS = 1;        // x, y, z f64
    public static final int TICK_ROT = 1 << 1;   // yaw, pitch f32
    public static final int TICK_HEAD_YAW = 1 << 2; // f32
    public static final int TICK_FLAGS = 1 << 3; // u8
    public static final int TICK_HELD_SLOT = 1 << 4; // u8
    public static final int TICK_SWING = 1 << 5; // u8 swingProgressInt + 1
    public static final int TICK_HURT = 1 << 6;  // u8 hurtTime
    public static final int TICK_ALL = 0x7F;

    /** TICK flags byte bits. */
    public static final int FLAG_ON_GROUND = 1;
    public static final int FLAG_SNEAKING = 1 << 1;
    public static final int FLAG_SPRINTING = 1 << 2;
    public static final int FLAG_USING_ITEM = 1 << 3;
    public static final int FLAG_INVISIBLE = 1 << 4;
    public static final int FLAG_BURNING = 1 << 5;
    public static final int FLAG_RIDING = 1 << 6;

    /** EQUIP slots: 0 held, 1-4 boots to helmet. */
    public static final int EQUIP_HELD = 0;
    public static final int EQUIP_BOOTS = 1;
    public static final int EQUIP_HELMET = 4;

    public static final int MARKER_KEY = 1;
    public static final int MARKER_DEATH = 2;
    public static final int MARKER_FINAL_KILL = 3;
    public static final int MARKER_BED_BROKEN = 4;
    public static final int MARKER_BED_LOST = 5;

    public static final int INFO_MODE = 1;
    public static final int INFO_MAP = 2;
    public static final int INFO_MODE_LABEL = 3;

    public static final int GAME_END_UNKNOWN = 0;
    public static final int GAME_END_WIN = 1;
    public static final int GAME_END_LOSS = 2;

    /** Longest string (in UTF-8 bytes) any TRACK INFO body may hold. */
    public static final int MAX_STRING_BYTES = 32767 * 3;

    // ---- protocol 47 play-state clientbound ids ----
    public static final int PACKET_KEEP_ALIVE = 0x00;
    public static final int PACKET_JOIN_GAME = 0x01;
    public static final int PACKET_RESPAWN = 0x07;
    public static final int PACKET_CUSTOM_PAYLOAD = 0x3F;
    public static final int PACKET_SET_COMPRESSION = 0x46;
    public static final int PACKET_PLAYER_LIST_HEADER_FOOTER = 0x47;

    public static byte[] fileMagic() {
        return FILE_MAGIC.clone();
    }

    /** True for the packets a NET block never stores: S00 keep-alive, S3F custom payload, S46 compression. */
    public static boolean isSkipped(int packetId) {
        return packetId == PACKET_KEEP_ALIVE || packetId == PACKET_CUSTOM_PAYLOAD || packetId == PACKET_SET_COMPRESSION;
    }

    /** {@code .json} marker kind for a TRACK MARKER code, or null for an unknown code. */
    public static String markerName(int kind) {
        switch (kind) {
            case MARKER_KEY:
                return "KEY";
            case MARKER_DEATH:
                return "DEATH";
            case MARKER_FINAL_KILL:
                return "FINAL_KILL";
            case MARKER_BED_BROKEN:
                return "BED_BROKEN";
            case MARKER_BED_LOST:
                return "BED_LOST";
            default:
                return null;
        }
    }

    /** Leading varint packet id of a stored packet, or -1 if the bytes do not start with one. */
    public static int packetId(byte[] b, int off, int len) {
        int value = 0;
        int end = off + Math.min(len, 5);
        for (int i = off, shift = 0; i < end; i++, shift += 7) {
            int x = b[i];
            value |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                return value;
            }
        }
        return -1;
    }

    // ---- writing primitives (return the offset after the written bytes) ----

    public static int varIntSize(int v) {
        if ((v & 0xFFFFFF80) == 0) {
            return 1;
        }
        if ((v & 0xFFFFC000) == 0) {
            return 2;
        }
        if ((v & 0xFFE00000) == 0) {
            return 3;
        }
        if ((v & 0xF0000000) == 0) {
            return 4;
        }
        return 5;
    }

    public static int putVarInt(byte[] b, int off, int v) {
        while ((v & 0xFFFFFF80) != 0) {
            b[off++] = (byte) (v & 0x7F | 0x80);
            v >>>= 7;
        }
        b[off++] = (byte) v;
        return off;
    }

    public static int putVarLong(byte[] b, int off, long v) {
        while ((v & 0xFFFFFFFFFFFFFF80L) != 0) {
            b[off++] = (byte) (v & 0x7F | 0x80);
            v >>>= 7;
        }
        b[off++] = (byte) v;
        return off;
    }

    public static int putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
        return off + 2;
    }

    public static int putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
        return off + 4;
    }

    public static int putLong(byte[] b, int off, long v) {
        putInt(b, off, (int) (v >>> 32));
        return putInt(b, off + 4, (int) v);
    }

    public static int putFloat(byte[] b, int off, float v) {
        return putInt(b, off, Float.floatToRawIntBits(v));
    }

    public static int putDouble(byte[] b, int off, double v) {
        return putLong(b, off, Double.doubleToRawLongBits(v));
    }

    // ---- reading primitives (caller checks bounds) ----

    public static int getU16(byte[] b, int off) {
        return (b[off] & 0xFF) << 8 | b[off + 1] & 0xFF;
    }

    public static int getInt(byte[] b, int off) {
        return (b[off] & 0xFF) << 24 | (b[off + 1] & 0xFF) << 16 | (b[off + 2] & 0xFF) << 8 | b[off + 3] & 0xFF;
    }

    public static long getLong(byte[] b, int off) {
        return (long) getInt(b, off) << 32 | getInt(b, off + 4) & 0xFFFFFFFFL;
    }

    public static float getFloat(byte[] b, int off) {
        return Float.intBitsToFloat(getInt(b, off));
    }

    public static double getDouble(byte[] b, int off) {
        return Double.longBitsToDouble(getLong(b, off));
    }

    /** A payload that breaks the format: never an index exception, always this. */
    public static final class FormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public FormatException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Bounds-checked reader over {@code [pos, end)} of a byte array. Reusable through {@link #reset} so
     * a hot caller allocates nothing; every overrun throws {@link FormatException}.
     */
    public static final class Cursor {
        private byte[] buf;
        private int pos;
        private int end;

        public Cursor(byte[] buf, int off, int end) {
            reset(buf, off, end);
        }

        public Cursor reset(byte[] buf, int off, int end) {
            if (off < 0 || end > buf.length || off > end) {
                throw new FormatException("bad range");
            }
            this.buf = buf;
            this.pos = off;
            this.end = end;
            return this;
        }

        public int pos() {
            return pos;
        }

        public int end() {
            return end;
        }

        public boolean hasMore() {
            return pos < end;
        }

        public int remaining() {
            return end - pos;
        }

        private void need(int n) {
            if (n < 0 || n > end - pos) {
                throw new FormatException("short");
            }
        }

        public void skip(int n) {
            need(n);
            pos += n;
        }

        public int u8() {
            need(1);
            return buf[pos++] & 0xFF;
        }

        public int i8() {
            need(1);
            return buf[pos++];
        }

        public int u16() {
            need(2);
            int v = getU16(buf, pos);
            pos += 2;
            return v;
        }

        public short i16() {
            return (short) u16();
        }

        public int i32() {
            need(4);
            int v = getInt(buf, pos);
            pos += 4;
            return v;
        }

        public long i64() {
            need(8);
            long v = getLong(buf, pos);
            pos += 8;
            return v;
        }

        public float f32() {
            return Float.intBitsToFloat(i32());
        }

        public double f64() {
            return Double.longBitsToDouble(i64());
        }

        /** Protocol varint (up to 5 bytes, signed int as the protocol defines it). */
        public int varInt() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int x = u8();
                value |= (x & 0x7F) << shift;
                if ((x & 0x80) == 0) {
                    return value;
                }
            }
            throw new FormatException("varint too long");
        }

        /** A varint read as an unsigned 32-bit value (record {@code dtUs}). */
        public long uvarInt() {
            return varInt() & 0xFFFFFFFFL;
        }

        /** A varint that must be a length in {@code [0, max]}. */
        public int length(int max) {
            int v = varInt();
            if (v < 0 || v > max) {
                throw new FormatException("bad length");
            }
            return v;
        }

        public long varLong() {
            long value = 0;
            for (int shift = 0; shift < 70; shift += 7) {
                int x = u8();
                value |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) {
                    return value;
                }
            }
            throw new FormatException("varlong too long");
        }

        public byte[] bytes(int n) {
            need(n);
            byte[] out = new byte[n];
            System.arraycopy(buf, pos, out, 0, n);
            pos += n;
            return out;
        }

        public String string(int maxBytes) {
            int n = length(maxBytes);
            need(n);
            String s = new String(buf, pos, n, StandardCharsets.UTF_8);
            pos += n;
            return s;
        }
    }
}
