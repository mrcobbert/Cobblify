package com.bedwarsqol.rewind;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Streaming reader of a {@code .cbr} recording (format v1, see {@link RewindFormat}). JDK only: the
 * G6 reader check runs it from the plain mod jar.
 *
 * <p>{@link #next()} returns blocks in file order with their payload inflated. It stops, without
 * throwing, at the first short read, bad magic, oversize or past-EOF length, CRC mismatch,
 * inflate/rawLen mismatch, unknown type or codec, malformed record payload, or block after END.
 * Everything before is the complete prefix ({@link #validLength()}); {@link #tornTail()} says whether
 * any bytes after it were dropped. Only a real I/O error of the underlying stream is thrown.
 */
public final class RecordingReader implements Closeable {

    /** Why reading stopped. NONE = clean end of input (or not stopped yet). */
    public enum Stop {
        NONE, SHORT_READ, BAD_FILE_HEADER, UNSUPPORTED_VERSION, BAD_MAGIC, OVERSIZE, CRC, INFLATE, BAD_TYPE,
        MALFORMED, AFTER_END
    }

    public static final class FileHeader {
        public final int formatVersion;
        public final int flags;
        public final int protocol;
        public final long baseEpochMs;

        FileHeader(int formatVersion, int flags, int protocol, long baseEpochMs) {
            this.formatVersion = formatVersion;
            this.flags = flags;
            this.protocol = protocol;
            this.baseEpochMs = baseEpochMs;
        }
    }

    public static final class Block {
        public final int type;
        public final int codec;
        public final int segment;
        public final long firstTimeUs;
        public final int rawLength;
        public final int storedLength;
        /** Offset of the block's first header byte in the file. */
        public final long fileOffset;
        /** The payload as written (inflated if the block was deflated). */
        public final byte[] payload;

        Block(int type, int codec, int segment, long firstTimeUs, int rawLength, int storedLength, long fileOffset,
              byte[] payload) {
            this.type = type;
            this.codec = codec;
            this.segment = segment;
            this.firstTimeUs = firstTimeUs;
            this.rawLength = rawLength;
            this.storedLength = storedLength;
            this.fileOffset = fileOffset;
            this.payload = payload;
        }
    }

    public static final class NetRecord {
        public final long timeUs;
        /** varint packetId + body. */
        public final byte[] bytes;

        NetRecord(long timeUs, byte[] bytes) {
            this.timeUs = timeUs;
            this.bytes = bytes;
        }

        public int packetId() {
            return RewindFormat.packetId(bytes, 0, bytes.length);
        }
    }

    public static final class TrackRecord {
        public final long timeUs;
        public final int tag;
        public final byte[] body;

        TrackRecord(long timeUs, int tag, byte[] body) {
            this.timeUs = timeUs;
            this.tag = tag;
            this.body = body;
        }
    }

    public static final class HeaderPayload {
        public final int source;
        public final List<byte[]> packets;

        HeaderPayload(int source, List<byte[]> packets) {
            this.source = source;
            this.packets = packets;
        }
    }

    public static final class IndexEntry {
        public final int type;
        public final int segment;
        public final long timeUs;
        public final long fileOffset;

        public IndexEntry(int type, int segment, long timeUs, long fileOffset) {
            this.type = type;
            this.segment = segment;
            this.timeUs = timeUs;
            this.fileOffset = fileOffset;
        }
    }

    /** Receives NET records without allocation: {@code buf[off, off+len)} is varint packetId + body. */
    public interface NetVisitor {
        void net(long timeUs, byte[] buf, int off, int len);
    }

    /** Receives TRACK records without allocation. */
    public interface TrackVisitor {
        void track(long timeUs, int tag, byte[] buf, int bodyOff, int bodyLen);
    }

    private final InputStream in;
    private final long length;
    private final BlockCodec codec = new BlockCodec();
    private final byte[] head = new byte[RewindFormat.BLOCK_HEADER_LENGTH];
    private FileHeader header;
    private long pos;
    private long validLength;
    private boolean tornTail;
    private boolean complete;
    private boolean done;
    private Stop stop = Stop.NONE;

    /** Opens a file; its length bounds every block, so a past-EOF length is rejected before allocating. */
    public static RecordingReader open(File file) throws IOException {
        return new RecordingReader(new BufferedInputStream(new FileInputStream(file), 1 << 16), file.length());
    }

    /**
     * @param in     the recording from its first byte
     * @param length total byte count if known (a file), or -1
     */
    public RecordingReader(InputStream in, long length) throws IOException {
        this.in = in;
        this.length = length;
        readFileHeader();
    }

    private void readFileHeader() throws IOException {
        byte[] h = new byte[RewindFormat.FILE_HEADER_LENGTH];
        int n = readFully(h, 0, h.length);
        if (n < h.length) {
            finish(Stop.SHORT_READ, n > 0);
            return;
        }
        byte[] magic = RewindFormat.fileMagic();
        boolean ok = true;
        for (int i = 0; i < magic.length; i++) {
            ok &= h[i] == magic[i];
        }
        ok &= (int) codec.crc(h, 0, 24) == RewindFormat.getInt(h, 24);
        if (!ok) {
            finish(Stop.BAD_FILE_HEADER, true);
            return;
        }
        header = new FileHeader(RewindFormat.getU16(h, 8), RewindFormat.getU16(h, 10), RewindFormat.getInt(h, 12),
                RewindFormat.getLong(h, 16));
        if (header.formatVersion != RewindFormat.FORMAT_VERSION) {
            finish(Stop.UNSUPPORTED_VERSION, false);
            return;
        }
        validLength = pos;
    }

    /** The file header, or null if it is missing, short or damaged. */
    public FileHeader header() {
        return header;
    }

    /** True when the header names a format version this reader does not know; no block is read. */
    public boolean unsupportedVersion() {
        return stop == Stop.UNSUPPORTED_VERSION;
    }

    /** True once a valid END block was read. */
    public boolean complete() {
        return complete;
    }

    /** True if bytes after the complete prefix were dropped (as far as reading has got). */
    public boolean tornTail() {
        return tornTail;
    }

    /** End of the complete prefix: the file header plus every block returned so far. */
    public long validLength() {
        return validLength;
    }

    public Stop stop() {
        return stop;
    }

    /** The next valid block, or null at the end of the complete prefix. */
    public Block next() throws IOException {
        if (done) {
            return null;
        }
        long start = pos;
        int n = readFully(head, 0, head.length);
        if (n == 0) {
            finish(Stop.NONE, false);
            return null;
        }
        if (complete) {
            finish(Stop.AFTER_END, true);
            return null;
        }
        if (n < head.length) {
            finish(Stop.SHORT_READ, true);
            return null;
        }
        if (RewindFormat.getInt(head, 0) != RewindFormat.BLOCK_MAGIC) {
            finish(Stop.BAD_MAGIC, true);
            return null;
        }
        int rawLen = RewindFormat.getInt(head, 20);
        int storedLen = RewindFormat.getInt(head, 24);
        if (rawLen < 0 || rawLen > RewindFormat.MAX_RAW_LENGTH || storedLen < 0
                || storedLen > RewindFormat.MAX_STORED_LENGTH) {
            finish(Stop.OVERSIZE, true);
            return null;
        }
        if (length >= 0 && storedLen > length - pos) {
            finish(Stop.SHORT_READ, true);
            return null;
        }
        byte[] stored = new byte[storedLen];
        if (readFully(stored, 0, storedLen) < storedLen) {
            finish(Stop.SHORT_READ, true);
            return null;
        }
        if ((int) codec.blockCrc(head, stored, 0, storedLen) != RewindFormat.getInt(head, 28)) {
            finish(Stop.CRC, true);
            return null;
        }
        int type = head[4] & 0xFF;
        int blockCodec = head[5] & 0xFF;
        if (type < RewindFormat.BLOCK_NET || type > RewindFormat.BLOCK_END
                || blockCodec > RewindFormat.CODEC_DEFLATE) {
            finish(Stop.BAD_TYPE, true);
            return null;
        }
        byte[] raw;
        if (blockCodec == RewindFormat.CODEC_STORED) {
            if (rawLen != storedLen) {
                finish(Stop.INFLATE, true);
                return null;
            }
            raw = stored;
        } else {
            raw = codec.inflate(stored, 0, storedLen, rawLen);
            if (raw == null) {
                finish(Stop.INFLATE, true);
                return null;
            }
        }
        Block b = new Block(type, blockCodec, RewindFormat.getInt(head, 8), RewindFormat.getLong(head, 12), rawLen,
                storedLen, start, raw);
        if (!wellFormed(b)) {
            finish(Stop.MALFORMED, true);
            return null;
        }
        validLength = pos;
        if (type == RewindFormat.BLOCK_END) {
            complete = true;
        }
        return b;
    }

    private void finish(Stop why, boolean torn) {
        done = true;
        stop = why;
        tornTail = torn;
    }

    private int readFully(byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(b, off + total, len - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        pos += total;
        return total;
    }

    @Override
    public void close() throws IOException {
        codec.close();
        in.close();
    }

    // ---------------- payload parsing ----------------

    private static final NetVisitor NO_NET = new NetVisitor() {
        @Override
        public void net(long timeUs, byte[] buf, int off, int len) {
        }
    };

    private static final TrackVisitor NO_TRACK = new TrackVisitor() {
        @Override
        public void track(long timeUs, int tag, byte[] buf, int bodyOff, int bodyLen) {
        }
    };

    private static boolean wellFormed(Block b) {
        try {
            byte[] p = b.payload;
            switch (b.type) {
                case RewindFormat.BLOCK_NET:
                    walkNet(p, 0, p.length, b.firstTimeUs, NO_NET);
                    return true;
                case RewindFormat.BLOCK_TRACK:
                    walkTrack(p, 0, p.length, b.firstTimeUs, NO_TRACK);
                    return true;
                case RewindFormat.BLOCK_HEADER:
                    headerPayload(b);
                    return true;
                case RewindFormat.BLOCK_INDEX:
                    index(b);
                    return true;
                default:
                    return true; // END: metadata JSON, read by RecordingMeta
            }
        } catch (RewindFormat.FormatException e) {
            return false;
        }
    }

    /**
     * Walks NET records in {@code buf[off, end)} whose first {@code dtUs} is measured from {@code
     * baseTimeUs}. Returns the time of the last record (or {@code baseTimeUs} if none).
     *
     * @throws RewindFormat.FormatException if a record is cut or a length is out of range
     */
    public static long walkNet(byte[] buf, int off, int end, long baseTimeUs, NetVisitor v) {
        RewindFormat.Cursor c = new RewindFormat.Cursor(buf, off, end);
        long t = baseTimeUs;
        while (c.hasMore()) {
            t += c.uvarInt();
            int len = c.length(RewindFormat.MAX_PACKET_LENGTH);
            if (len < 1) {
                throw new RewindFormat.FormatException("empty packet");
            }
            int at = c.pos();
            c.skip(len);
            v.net(t, buf, at, len);
        }
        return t;
    }

    /** As {@link #walkNet}, for TRACK records. */
    public static long walkTrack(byte[] buf, int off, int end, long baseTimeUs, TrackVisitor v) {
        RewindFormat.Cursor c = new RewindFormat.Cursor(buf, off, end);
        long t = baseTimeUs;
        while (c.hasMore()) {
            t += c.uvarInt();
            int tag = c.u8();
            int at = c.pos();
            int len = trackBodyLength(tag, buf, at, end);
            c.skip(len);
            v.track(t, tag, buf, at, len);
        }
        return t;
    }

    /**
     * Length of a TRACK body of {@code tag} starting at {@code buf[off]} and ending by {@code end}. TRACK
     * bodies carry no length prefix, so EQUIP walks the protocol-47 slot and its NBT.
     *
     * @throws RewindFormat.FormatException for an unknown tag or a cut body
     */
    public static int trackBodyLength(int tag, byte[] buf, int off, int end) {
        RewindFormat.Cursor c = new RewindFormat.Cursor(buf, off, end);
        switch (tag) {
            case RewindFormat.TAG_TICK: {
                int mask = c.u8();
                if ((mask & ~RewindFormat.TICK_ALL) != 0) {
                    throw new RewindFormat.FormatException("tick mask");
                }
                c.skip(SelfTrackEncoder.tickFieldsLength(mask));
                break;
            }
            case RewindFormat.TAG_CAMERA:
                c.skip(8);
                break;
            case RewindFormat.TAG_EQUIP: {
                int slot = c.u8();
                if (slot > RewindFormat.EQUIP_HELMET) {
                    throw new RewindFormat.FormatException("equip slot");
                }
                skipItemSlot(c);
                break;
            }
            case RewindFormat.TAG_MARKER:
            case RewindFormat.TAG_GAME_END:
                c.u8();
                break;
            case RewindFormat.TAG_INFO:
                c.u8();
                c.skip(c.length(RewindFormat.MAX_STRING_BYTES));
                break;
            case RewindFormat.TAG_GAME_START:
                c.varLong(); // backUs: the logical start is the record time minus this
                break;
            default:
                throw new RewindFormat.FormatException("tag " + tag);
        }
        return c.pos() - off;
    }

    /** Protocol-47 slot: i16 id (-1 empty); else u8 count, i16 damage, NBT (0 = none, else a named compound). */
    private static void skipItemSlot(RewindFormat.Cursor c) {
        int id = c.i16();
        if (id == -1) {
            return;
        }
        c.u8();
        c.i16();
        int type = c.u8();
        if (type == 0) {
            return;
        }
        if (type != 10) {
            throw new RewindFormat.FormatException("nbt root");
        }
        c.skip(c.u16());
        skipNbtPayload(c, 10, 0);
    }

    private static void skipNbtPayload(RewindFormat.Cursor c, int type, int depth) {
        if (depth > 512) {
            throw new RewindFormat.FormatException("nbt depth");
        }
        switch (type) {
            case 1:
                c.skip(1);
                break;
            case 2:
                c.skip(2);
                break;
            case 3:
            case 5:
                c.skip(4);
                break;
            case 4:
            case 6:
                c.skip(8);
                break;
            case 7:
                c.skip(nbtCount(c, 1));
                break;
            case 8:
                c.skip(c.u16());
                break;
            case 9: {
                int inner = c.u8();
                int n = c.i32();
                if (n < 0 || (n > 0 && (inner == 0 || inner > 11))) {
                    throw new RewindFormat.FormatException("nbt list");
                }
                for (int i = 0; i < n; i++) {
                    skipNbtPayload(c, inner, depth + 1);
                }
                break;
            }
            case 10:
                while (true) {
                    int t = c.u8();
                    if (t == 0) {
                        break;
                    }
                    if (t > 11) {
                        throw new RewindFormat.FormatException("nbt type");
                    }
                    c.skip(c.u16());
                    skipNbtPayload(c, t, depth + 1);
                }
                break;
            case 11:
                c.skip(nbtCount(c, 4));
                break;
            default:
                throw new RewindFormat.FormatException("nbt type");
        }
    }

    private static int nbtCount(RewindFormat.Cursor c, int width) {
        int n = c.i32();
        if (n < 0 || (long) n * width > c.remaining()) {
            throw new RewindFormat.FormatException("nbt array");
        }
        return n * width;
    }

    public static List<NetRecord> netRecords(Block b) {
        requireType(b, RewindFormat.BLOCK_NET);
        final List<NetRecord> out = new ArrayList<NetRecord>();
        walkNet(b.payload, 0, b.payload.length, b.firstTimeUs, new NetVisitor() {
            @Override
            public void net(long timeUs, byte[] buf, int off, int len) {
                out.add(new NetRecord(timeUs, Arrays.copyOfRange(buf, off, off + len)));
            }
        });
        return out;
    }

    public static List<TrackRecord> trackRecords(Block b) {
        requireType(b, RewindFormat.BLOCK_TRACK);
        final List<TrackRecord> out = new ArrayList<TrackRecord>();
        walkTrack(b.payload, 0, b.payload.length, b.firstTimeUs, new TrackVisitor() {
            @Override
            public void track(long timeUs, int tag, byte[] buf, int bodyOff, int bodyLen) {
                out.add(new TrackRecord(timeUs, tag, Arrays.copyOfRange(buf, bodyOff, bodyOff + bodyLen)));
            }
        });
        return out;
    }

    public static HeaderPayload headerPayload(Block b) {
        requireType(b, RewindFormat.BLOCK_HEADER);
        RewindFormat.Cursor c = new RewindFormat.Cursor(b.payload, 0, b.payload.length);
        int source = c.u8();
        if (source != RewindFormat.SOURCE_CLIENT_SNAPSHOT && source != RewindFormat.SOURCE_CAPTURE_CARRY) {
            throw new RewindFormat.FormatException("source " + source);
        }
        int count = c.length(c.remaining());
        List<byte[]> packets = new ArrayList<byte[]>(count);
        for (int i = 0; i < count; i++) {
            packets.add(c.bytes(c.length(RewindFormat.MAX_PACKET_LENGTH)));
        }
        if (c.hasMore()) {
            throw new RewindFormat.FormatException("header trailing bytes");
        }
        return new HeaderPayload(source, Collections.unmodifiableList(packets));
    }

    public static List<IndexEntry> index(Block b) {
        requireType(b, RewindFormat.BLOCK_INDEX);
        RewindFormat.Cursor c = new RewindFormat.Cursor(b.payload, 0, b.payload.length);
        int n = c.length(c.remaining());
        List<IndexEntry> out = new ArrayList<IndexEntry>(n);
        for (int i = 0; i < n; i++) {
            int type = c.u8();
            int segment = c.varInt();
            long timeUs = c.varLong();
            long fileOffset = c.varLong();
            out.add(new IndexEntry(type, segment, timeUs, fileOffset));
        }
        if (c.hasMore()) {
            throw new RewindFormat.FormatException("index trailing bytes");
        }
        return out;
    }

    /** The END block's metadata JSON (parse it with {@code RecordingMeta}, which needs Gson). */
    public static String endJson(Block b) {
        requireType(b, RewindFormat.BLOCK_END);
        return new String(b.payload, StandardCharsets.UTF_8);
    }

    private static void requireType(Block b, int type) {
        if (b.type != type) {
            throw new IllegalArgumentException("block type " + b.type + ", wanted " + type);
        }
    }
}
