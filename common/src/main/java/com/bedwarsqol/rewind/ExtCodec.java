package com.bedwarsqol.rewind;

import java.nio.charset.StandardCharsets;

/**
 * Encoder and decoders of the format v2 extension record bodies (PLAN section A "Records"; layouts on the
 * {@code RewindFormat.TAG_*} constants).
 *
 * <p><b>Encoding.</b> One reused buffer, client thread only. Each typed encode method, and the streaming form
 * ({@link #begin} or {@link #beginHud}, the {@code put*} methods, {@link #end}; {@link #beginSettings}, the
 * {@code setting*} methods, {@link #endSettings}), leaves {@code varint len | body} in {@code buffer()[0, n)} and
 * returns {@code n}: exactly the bytes that follow the tag, so the caller appends the record with
 * {@code producer.appendTrack(nowUs, tag, codec.buffer(), 0, n)}. Read {@link #buffer()} only after the encode call
 * returned (a large body grows the buffer into a new array; after that, encoding allocates nothing). Strings are
 * written as UTF-8 straight from the {@link CharSequence}, with no intermediate array; an unpaired surrogate is
 * written as {@code '?'}, as the JDK's encoder writes it.
 *
 * <p><b>Decoding.</b> One reusable object per tag ({@link TickHead}, {@link Out}, ...): {@code read(body, off, len)}
 * takes the body without its length prefix (what {@code RecordingReader.walkTrack} hands a visitor), fills the
 * public fields and returns the object. A cut or malformed body throws {@link RewindFormat.FormatException}, never an
 * index error. Bytes after the fields a decoder knows are ignored, so a later build may append fields to a body.
 */
public final class ExtCodec {

    /** Room before the body for its varint length. */
    private static final int HEAD = 5;
    /** Room before the SETTINGS entries for their varint count. */
    private static final int COUNT_ROOM = 5;
    private static final byte[] EMPTY = new byte[0];

    private byte[] buf;
    private int pos = HEAD;
    private int bodyStart = HEAD;
    private int settingsCount = -1;

    public ExtCodec() {
        this(256);
    }

    public ExtCodec(int initialCapacity) {
        buf = new byte[Math.max(HEAD + COUNT_ROOM + 16, initialCapacity)];
    }

    /** The record written by the last encode call: {@code varint len | body} in {@code [0, n)}. */
    public byte[] buffer() {
        return buf;
    }

    // ------------------------------------------------------------------ streaming

    /** Starts a body; the {@code put*} methods append to it and {@link #end} closes it. */
    public ExtCodec begin() {
        pos = HEAD;
        bodyStart = HEAD;
        settingsCount = -1;
        return this;
    }

    /** Starts a HUD body: u8 module id, u8 flags; the module's values follow through the {@code put*} methods. */
    public ExtCodec beginHud(int module, int flags) {
        return begin().putU8(module).putU8(flags);
    }

    /** Closes the body started by {@link #begin} or {@link #beginHud}; returns {@code n}, the record length. */
    public int end() {
        if (settingsCount >= 0) {
            throw new IllegalStateException("a SETTINGS body ends with endSettings()");
        }
        return finish(bodyStart);
    }

    public ExtCodec putU8(int v) {
        ensure(1);
        buf[pos++] = (byte) v;
        return this;
    }

    public ExtCodec putI8(int v) {
        return putU8(v);
    }

    public ExtCodec putI16(int v) {
        ensure(2);
        pos = RewindFormat.putShort(buf, pos, v);
        return this;
    }

    public ExtCodec putVarInt(int v) {
        ensure(5);
        pos = RewindFormat.putVarInt(buf, pos, v);
        return this;
    }

    public ExtCodec putI64(long v) {
        ensure(8);
        pos = RewindFormat.putLong(buf, pos, v);
        return this;
    }

    public ExtCodec putF32(float v) {
        ensure(4);
        pos = RewindFormat.putFloat(buf, pos, v);
        return this;
    }

    /** Raw bytes, no length. */
    public ExtCodec putBytes(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, buf, pos, len);
        pos += len;
        return this;
    }

    /** A string: varint UTF-8 length, then the UTF-8 bytes of {@code s} (null is written as empty). */
    public ExtCodec putString(CharSequence s) {
        int n = s == null ? 0 : utf8Length(s);
        if (n > RewindFormat.MAX_STRING_BYTES) {
            throw new IllegalArgumentException("string of " + n + " bytes");
        }
        putVarInt(n);
        ensure(n);
        if (n > 0) {
            pos = putUtf8(s, buf, pos);
        }
        return this;
    }

    /** A string already encoded as UTF-8 (a cached sound name): varint length, then the bytes. */
    public ExtCodec putUtf8(byte[] utf8, int off, int len) {
        if (len > RewindFormat.MAX_STRING_BYTES) {
            throw new IllegalArgumentException("string of " + len + " bytes");
        }
        return putVarInt(len).putBytes(utf8, off, len);
    }

    // ------------------------------------------------------------------ typed encoders

    /** TICK_HEAD with no flag set. */
    public int tickHead(int handled) {
        return tickHead(handled, 0);
    }

    /** TICK_HEAD; the flags byte ({@code TICK_HEAD_*}) is written only when {@code flags != 0}. */
    public int tickHead(int handled, int flags) {
        begin().putVarInt(handled);
        if (flags != 0) {
            putU8(flags);
        }
        return end();
    }

    /** OUT: varint {@code packetId} then {@code data[off, off+len)}, the packet's {@code writePacketData} bytes. */
    public int out(int packetId, byte[] data, int off, int len) {
        return begin().putVarInt(packetId).putBytes(data, off, len).end();
    }

    /** DIG: {@code flags} ({@link RewindFormat#DIG_HITTING}), block position as a protocol long, face, crack stage. */
    public int dig(int flags, long pos, int face, int stage) {
        return begin().putU8(flags).putI64(pos).putU8(face).putI8(stage).end();
    }

    /** BLOCK: position, old and new block state ids, cause ({@code BLOCK_CAUSE_*}). */
    public int block(long pos, int oldState, int newState, int cause) {
        return begin().putI64(pos).putVarInt(oldState).putVarInt(newState).putU8(cause).end();
    }

    public int sound(CharSequence name, float x, float y, float z, float volume, float pitch) {
        return begin().putString(name).putF32(x).putF32(y).putF32(z).putF32(volume).putF32(pitch).end();
    }

    /** SOUND with the name already encoded as UTF-8 (the recorder caches it per sound, so a known name allocates nothing). */
    public int sound(byte[] nameUtf8, int off, int len, float x, float y, float z, float volume, float pitch) {
        return begin().putUtf8(nameUtf8, off, len).putF32(x).putF32(y).putF32(z).putF32(volume).putF32(pitch).end();
    }

    /** VIEW: perspective 0-2, {@code VIEW_*} flags, screen kind ({@code SCREEN_*}). */
    public int view(int perspective, int flags, int screenKind) {
        return begin().putU8(perspective).putU8(flags).putU8(screenKind).end();
    }

    /** MOUSE: offset from the screen centre in quarter GUI pixels, each clamped to i16. */
    public int mouse(int dx, int dy) {
        return begin().putI16(clampShort(dx)).putI16(clampShort(dy)).end();
    }

    public int chatInput(CharSequence text, int cursor) {
        return begin().putString(text).putVarInt(cursor).end();
    }

    /** FOV: the final world FOV in degrees. */
    public int fov(float degrees) {
        return begin().putF32(degrees).end();
    }

    /** USE: the full {@code itemInUseCount} (72000 at the start of a bow draw). */
    public int use(int count) {
        return begin().putVarInt(count).end();
    }

    /** INV: {@code slot} 0-40 ({@code INV_*}) and its protocol-47 slot bytes. */
    public int inv(int slot, byte[] slotBytes, int off, int len) {
        if (slot < 0 || slot >= RewindFormat.INV_SLOTS) {
            throw new IllegalArgumentException("inv slot " + slot);
        }
        return begin().putU8(slot).putBytes(slotBytes, off, len).end();
    }

    /** CONT: the open window's id, one of its own slots and that slot's protocol-47 bytes. */
    public int cont(int windowId, int slot, byte[] slotBytes, int off, int len) {
        if (slot < 0) {
            throw new IllegalArgumentException("cont slot " + slot);
        }
        return begin().putU8(windowId).putVarInt(slot).putBytes(slotBytes, off, len).end();
    }

    /** Starts a SETTINGS body; add entries with the {@code setting*} methods, close it with {@link #endSettings}. */
    public ExtCodec beginSettings() {
        begin();
        pos = HEAD + COUNT_ROOM;
        bodyStart = pos;
        settingsCount = 0;
        return this;
    }

    public ExtCodec settingInt(int key, int value) {
        settingHead(key, RewindFormat.SETTING_TYPE_VARINT);
        return putVarInt(value);
    }

    public ExtCodec settingFloat(int key, float value) {
        settingHead(key, RewindFormat.SETTING_TYPE_F32);
        return putF32(value);
    }

    public ExtCodec settingString(int key, CharSequence value) {
        settingHead(key, RewindFormat.SETTING_TYPE_STRING);
        return putString(value);
    }

    private void settingHead(int key, int type) {
        if (settingsCount < 0) {
            throw new IllegalStateException("beginSettings() first");
        }
        if (key < 0 || key > 0xFF) {
            throw new IllegalArgumentException("setting key " + key);
        }
        putU8(key).putU8(type);
        settingsCount++;
    }

    /** Closes a SETTINGS body: writes the entry count in front of the entries; returns {@code n}. */
    public int endSettings() {
        if (settingsCount < 0) {
            throw new IllegalStateException("beginSettings() first");
        }
        int start = HEAD + COUNT_ROOM - RewindFormat.varIntSize(settingsCount);
        RewindFormat.putVarInt(buf, start, settingsCount);
        settingsCount = -1;
        return finish(start);
    }

    /** IDENTITY: own UUID, name and the {@code textures} property (signature empty or null when there is none). */
    public int identity(long uuidMost, long uuidLeast, CharSequence name, CharSequence skinValue,
                        CharSequence skinSignature) {
        return begin().putI64(uuidMost).putI64(uuidLeast).putString(name).putString(skinValue)
                .putString(skinSignature).end();
    }

    /** INPUT: {@code INPUT_*} bits. */
    public int input(int keys) {
        return begin().putU8(keys).end();
    }

    /** TAP: {@link RewindFormat#TAP_LMB} or {@link RewindFormat#TAP_RMB}. */
    public int tap(int button) {
        return begin().putU8(button).end();
    }

    // ------------------------------------------------------------------ internals

    private void ensure(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("length " + n);
        }
        if (n > buf.length - pos) {
            long want = Math.max((long) buf.length * 2, (long) pos + n);
            long cap = (long) HEAD + RewindFormat.MAX_EXT_BODY_LENGTH;
            if ((long) pos + n > cap) {
                throw new IllegalArgumentException("extension body over " + RewindFormat.MAX_EXT_BODY_LENGTH + " B");
            }
            byte[] grown = new byte[(int) Math.min(want, cap)];
            System.arraycopy(buf, 0, grown, 0, pos);
            buf = grown;
        }
    }

    /** Moves the body {@code [start, pos)} to just after its varint length at 0; returns the record length. */
    private int finish(int start) {
        int len = pos - start;
        if (len > RewindFormat.MAX_EXT_BODY_LENGTH) {
            throw new IllegalArgumentException("extension body of " + len + " B");
        }
        int s = RewindFormat.varIntSize(len);
        RewindFormat.putVarInt(buf, 0, len);
        if (start != s) {
            System.arraycopy(buf, start, buf, s, len);
        }
        pos = HEAD;
        bodyStart = HEAD;
        return s + len;
    }

    private static int clampShort(int v) {
        return v < Short.MIN_VALUE ? Short.MIN_VALUE : v > Short.MAX_VALUE ? Short.MAX_VALUE : v;
    }

    /** UTF-8 length of {@code s} as {@link #putUtf8(CharSequence, byte[], int)} writes it. */
    static int utf8Length(CharSequence s) {
        int n = 0;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                n++;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isSurrogate(c)) {
                if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                    n += 4;
                    i++;
                } else {
                    n++; // '?'
                }
            } else {
                n += 3;
            }
        }
        return n;
    }

    private static int putUtf8(CharSequence s, byte[] b, int p) {
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                b[p++] = (byte) c;
            } else if (c < 0x800) {
                b[p++] = (byte) (0xC0 | c >> 6);
                b[p++] = (byte) (0x80 | c & 0x3F);
            } else if (Character.isSurrogate(c)) {
                if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                    int cp = Character.toCodePoint(c, s.charAt(++i));
                    b[p++] = (byte) (0xF0 | cp >> 18);
                    b[p++] = (byte) (0x80 | cp >> 12 & 0x3F);
                    b[p++] = (byte) (0x80 | cp >> 6 & 0x3F);
                    b[p++] = (byte) (0x80 | cp & 0x3F);
                } else {
                    b[p++] = '?';
                }
            } else {
                b[p++] = (byte) (0xE0 | c >> 12);
                b[p++] = (byte) (0x80 | c >> 6 & 0x3F);
                b[p++] = (byte) (0x80 | c & 0x3F);
            }
        }
        return p;
    }

    // ------------------------------------------------------------------ decoders

    /** A reusable decoder of one extension body. */
    public interface Body {
        /** Decodes {@code b[off, off+len)}, a body without its length prefix. */
        Body read(byte[] b, int off, int len);
    }

    /** Shared cursor handling; each decoder owns one cursor, so decoding allocates nothing but strings. */
    private abstract static class Base implements Body {
        final RewindFormat.Cursor c = new RewindFormat.Cursor(EMPTY, 0, 0);

        final RewindFormat.Cursor open(byte[] b, int off, int len) {
            if (b == null || len < 0) {
                throw new RewindFormat.FormatException("bad range");
            }
            return c.reset(b, off, off + len);
        }
    }

    public static final class TickHead extends Base {
        public int handled;
        /** {@code TICK_HEAD_*} flags; 0 when the body has no flags byte. */
        public int flags;

        public boolean orderUnknown() {
            return (flags & RewindFormat.TICK_HEAD_ORDER_UNKNOWN) != 0;
        }

        @Override
        public TickHead read(byte[] b, int off, int len) {
            RewindFormat.Cursor c = open(b, off, len);
            handled = c.varInt();
            flags = c.hasMore() ? c.u8() : 0;
            return this;
        }
    }

    public static final class Out extends Base {
        public int packetId;
        /** The packet bytes after the id are {@code buf[dataOff, dataOff + dataLen)} (the body array, not a copy). */
        public byte[] buf;
        public int dataOff;
        public int dataLen;

        @Override
        public Out read(byte[] b, int off, int len) {
            RewindFormat.Cursor c = open(b, off, len);
            packetId = c.varInt();
            buf = b;
            dataOff = c.pos();
            dataLen = c.remaining();
            return this;
        }
    }

    public static final class Dig extends Base {
        public int flags;
        public long pos;
        public int face;
        /** -1 none, 0-9. */
        public int stage;

        public boolean hitting() {
            return (flags & RewindFormat.DIG_HITTING) != 0;
        }

        @Override
        public Dig read(byte[] b, int off, int len) {
            RewindFormat.Cursor c = open(b, off, len);
            flags = c.u8();
            pos = c.i64();
            face = c.u8();
            stage = c.i8();
            return this;
        }
    }

    public static final class Block extends Base {
        public long pos;
        public int oldState;
        public int newState;
        public int cause;

        @Override
        public Block read(byte[] b, int off, int len) {
            RewindFormat.Cursor c = open(b, off, len);
            pos = c.i64();
            oldState = c.varInt();
            newState = c.varInt();
            cause = c.u8();
            return this;
        }
    }

    public static final class Sound extends Base {
        public String name;
        public float x;
        public float y;
        public float z;
        public float volume;
        public float pitch;

        @Override
        public Sound read(byte[] b, int off, int len) {
            RewindFormat.Cursor c = open(b, off, len);
            name = c.string(RewindFormat.MAX_STRING_BYTES);
            x = c.f32();
            y = c.f32();
            z = c.f32();
            volume = c.f32();
            pitch = c.f32();
            return this;
        }
    }

    public static final class View extends Base {
        public int perspective;
        public int flags;
        public int screenKind;

        @Override
        public View read(byte[] b, int off, int len) {
            RewindFormat.Cursor c = open(b, off, len);
            perspective = c.u8();
            flags = c.u8();
            screenKind = c.u8();
            return this;
        }
    }

    public static final class Mouse extends Base {
        /** Quarter GUI pixels from the screen centre. */
        public int dx;
        public int dy;

        @Override
        public Mouse read(byte[] b, int off, int len) {
            RewindFormat.Cursor c = open(b, off, len);
            dx = c.i16();
            dy = c.i16();
            return this;
        }
    }

    public static final class ChatInput extends Base {
        public String text;
        public int cursor;

        @Override
        public ChatInput read(byte[] b, int off, int len) {
            RewindFormat.Cursor c = open(b, off, len);
            text = c.string(RewindFormat.MAX_STRING_BYTES);
            cursor = c.varInt();
            return this;
        }
    }

    public static final class Fov extends Base {
        public float degrees;

        @Override
        public Fov read(byte[] b, int off, int len) {
            degrees = open(b, off, len).f32();
            return this;
        }
    }

    public static final class Use extends Base {
        public int count;

        @Override
        public Use read(byte[] b, int off, int len) {
            count = open(b, off, len).varInt();
            return this;
        }
    }

    public static final class Inv extends Base {
        /** 0-35 main, 36-39 armour (boots to helmet), 40 cursor. */
        public int slot;
        /** The protocol-47 slot is {@code buf[slotOff, slotOff + slotLen)} (the body array, not a copy). */
        public byte[] buf;
        public int slotOff;
        public int slotLen;

        @Override
        public Inv read(byte[] b, int off, int len) {
            RewindFormat.Cursor c = open(b, off, len);
            slot = c.u8();
            if (slot >= RewindFormat.INV_SLOTS) {
                throw new RewindFormat.FormatException("inv slot");
            }
            buf = b;
            slotOff = c.pos();
            slotLen = RecordingReader.itemSlotLength(b, slotOff, c.end());
            return this;
        }
    }

    public static final class Cont extends Base {
        public int windowId;
        public int slot;
        /** The protocol-47 slot is {@code buf[slotOff, slotOff + slotLen)} (the body array, not a copy). */
        public byte[] buf;
        public int slotOff;
        public int slotLen;

        @Override
        public Cont read(byte[] b, int off, int len) {
            RewindFormat.Cursor c = open(b, off, len);
            windowId = c.u8();
            slot = c.varInt();
            if (slot < 0) {
                throw new RewindFormat.FormatException("cont slot");
            }
            buf = b;
            slotOff = c.pos();
            slotLen = RecordingReader.itemSlotLength(b, slotOff, c.end());
            return this;
        }
    }

    /**
     * SETTINGS entries {@code [0, count)}: {@code keys[i]}, {@code types[i]} and the value in {@code ints},
     * {@code floats} or {@code strings} by type. A key this build does not number is still read (by its type), so
     * the caller can ignore it; an unknown type cannot be skipped and makes the body malformed.
     */
    public static final class Settings extends Base {
        public int count;
        public int[] keys = new int[0];
        public int[] types = new int[0];
        public int[] ints = new int[0];
        public float[] floats = new float[0];
        public String[] strings = new String[0];

        /** Index of {@code key} among the entries, or -1. */
        public int indexOf(int key) {
            for (int i = 0; i < count; i++) {
                if (keys[i] == key) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public Settings read(byte[] b, int off, int len) {
            RewindFormat.Cursor c = open(b, off, len);
            count = 0;
            int n = c.length(c.remaining() / 3); // every entry is at least 3 bytes
            grow(n);
            for (int i = 0; i < n; i++) {
                keys[i] = c.u8();
                types[i] = c.u8();
                ints[i] = 0;
                floats[i] = 0f;
                strings[i] = null;
                switch (types[i]) {
                    case RewindFormat.SETTING_TYPE_VARINT:
                        ints[i] = c.varInt();
                        break;
                    case RewindFormat.SETTING_TYPE_F32:
                        floats[i] = c.f32();
                        break;
                    case RewindFormat.SETTING_TYPE_STRING:
                        strings[i] = c.string(RewindFormat.MAX_STRING_BYTES);
                        break;
                    default:
                        throw new RewindFormat.FormatException("setting type " + types[i]);
                }
            }
            count = n;
            return this;
        }

        private void grow(int n) {
            if (keys.length < n) {
                keys = new int[n];
                types = new int[n];
                ints = new int[n];
                floats = new float[n];
                strings = new String[n];
            }
        }
    }

    public static final class Identity extends Base {
        public long uuidMost;
        public long uuidLeast;
        public String name;
        public String skinValue;
        /** Empty when the profile had none. */
        public String skinSignature;

        @Override
        public Identity read(byte[] b, int off, int len) {
            RewindFormat.Cursor c = open(b, off, len);
            uuidMost = c.i64();
            uuidLeast = c.i64();
            name = c.string(RewindFormat.MAX_STRING_BYTES);
            skinValue = c.string(RewindFormat.MAX_STRING_BYTES);
            skinSignature = c.string(RewindFormat.MAX_STRING_BYTES);
            return this;
        }
    }

    public static final class Input extends Base {
        public int keys;

        @Override
        public Input read(byte[] b, int off, int len) {
            keys = open(b, off, len).u8();
            return this;
        }
    }

    public static final class Tap extends Base {
        public int button;

        @Override
        public Tap read(byte[] b, int off, int len) {
            button = open(b, off, len).u8();
            return this;
        }
    }

    /** HUD: module id, flags, and the module's values in {@code buf[valuesOff, valuesOff + valuesLen)}. */
    public static final class Hud extends Base {
        public int module;
        public int flags;
        public byte[] buf;
        public int valuesOff;
        public int valuesLen;
        private final RewindFormat.Cursor values = new RewindFormat.Cursor(EMPTY, 0, 0);

        public boolean onScreen() {
            return (flags & RewindFormat.HUD_FLAG_ON_SCREEN) != 0;
        }

        /** A cursor over the values (reused: valid until the next call). */
        public RewindFormat.Cursor values() {
            return values.reset(buf == null ? EMPTY : buf, valuesOff, valuesOff + valuesLen);
        }

        @Override
        public Hud read(byte[] b, int off, int len) {
            RewindFormat.Cursor c = open(b, off, len);
            module = c.u8();
            flags = c.u8();
            buf = b;
            valuesOff = c.pos();
            valuesLen = c.remaining();
            return this;
        }
    }

    /** UTF-8 bytes of {@code s} (for a caller that caches a name once, e.g. a sound). */
    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
