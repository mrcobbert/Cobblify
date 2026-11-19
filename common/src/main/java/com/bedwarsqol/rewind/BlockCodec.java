package com.bedwarsqol.rewind;

import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Block framing for the {@code .cbr} format (see {@link RewindFormat}). One instance reuses its
 * Deflater (level 6, raw), Inflater (raw), CRC32 and output buffer, so encoding a block allocates only
 * when the output buffer must grow. Not thread-safe; the writer owns one, a reader owns another.
 * Call {@link #close} to free the native zlib state.
 */
public final class BlockCodec implements AutoCloseable {

    private final Deflater deflater = new Deflater(6, true);
    private final Inflater inflater = new Inflater(true);
    private final CRC32 crc = new CRC32();
    private byte[] out = new byte[RewindFormat.BLOCK_HEADER_LENGTH + 4096];

    /** The buffer the last {@link #encode} wrote into; valid until the next call. */
    public byte[] out() {
        return out;
    }

    /**
     * Encodes one block (32-byte header + payload) into {@link #out()} and returns its length. With
     * {@code tryDeflate}, the payload is raw-deflated and stored compressed only if that is smaller;
     * otherwise it is stored as is.
     *
     * @throws IllegalArgumentException for an unknown type or a payload over {@link RewindFormat#MAX_RAW_LENGTH}
     */
    public int encode(int type, int segment, long firstTimeUs, byte[] raw, int off, int len, boolean tryDeflate) {
        if (type < RewindFormat.BLOCK_NET || type > RewindFormat.BLOCK_END) {
            throw new IllegalArgumentException("type " + type);
        }
        if (len < 0 || len > RewindFormat.MAX_RAW_LENGTH) {
            throw new IllegalArgumentException("payload " + len);
        }
        int h = RewindFormat.BLOCK_HEADER_LENGTH;
        int cap = h + len;
        if (out.length < cap) {
            out = new byte[Math.max(cap, Math.min(RewindFormat.MAX_RAW_LENGTH + h, out.length * 2))];
        }
        int codec = RewindFormat.CODEC_STORED;
        int stored = len;
        if (tryDeflate && len > 0) {
            int n = deflate(raw, off, len, h, cap);
            if (n >= 0) {
                codec = RewindFormat.CODEC_DEFLATE;
                stored = n;
            }
        }
        if (codec == RewindFormat.CODEC_STORED && len > 0) {
            System.arraycopy(raw, off, out, h, len);
        }
        RewindFormat.putInt(out, 0, RewindFormat.BLOCK_MAGIC);
        out[4] = (byte) type;
        out[5] = (byte) codec;
        out[6] = 0;
        out[7] = 0;
        RewindFormat.putInt(out, 8, segment);
        RewindFormat.putLong(out, 12, firstTimeUs);
        RewindFormat.putInt(out, 20, len);
        RewindFormat.putInt(out, 24, stored);
        crc.reset();
        crc.update(out, 0, 28);
        crc.update(out, h, stored);
        RewindFormat.putInt(out, 28, (int) crc.getValue());
        return h + stored;
    }

    /** Deflates into out[at, limit); returns the compressed length, or -1 if it would not be smaller. */
    private int deflate(byte[] raw, int off, int len, int at, int limit) {
        deflater.reset();
        deflater.setInput(raw, off, len);
        deflater.finish();
        int pos = at;
        // Stop one byte short of the raw length: deflate must save at least one byte to be worth it.
        int stop = limit - 1;
        while (!deflater.finished() && pos < stop) {
            int n = deflater.deflate(out, pos, stop - pos);
            if (n == 0 && !deflater.finished()) {
                return -1; // no progress with room left: never spin, store instead
            }
            pos += n;
        }
        return deflater.finished() ? pos - at : -1;
    }

    /** CRC32 of {@code b[off, off+len)}. */
    public long crc(byte[] b, int off, int len) {
        crc.reset();
        crc.update(b, off, len);
        return crc.getValue();
    }

    /** CRC32 of a block: header bytes 0-27 followed by the stored payload. */
    public long blockCrc(byte[] header, byte[] payload, int off, int len) {
        crc.reset();
        crc.update(header, 0, 28);
        crc.update(payload, off, len);
        return crc.getValue();
    }

    /**
     * Inflates a raw-deflate payload that must expand to exactly {@code rawLen} bytes and end there.
     * Returns null on any mismatch or corrupt stream.
     */
    public byte[] inflate(byte[] stored, int off, int storedLen, int rawLen) {
        if (rawLen < 0 || rawLen > RewindFormat.MAX_RAW_LENGTH) {
            return null;
        }
        byte[] raw = new byte[rawLen];
        inflater.reset();
        inflater.setInput(stored, off, storedLen);
        try {
            int pos = 0;
            while (pos < rawLen) {
                int n = inflater.inflate(raw, pos, rawLen - pos);
                if (n == 0) {
                    return null; // ended early, truncated, or no progress: never spin
                }
                pos += n;
            }
            if (!inflater.finished()) {
                // Either more output than rawLen, or the end marker is still pending: probe one byte.
                byte[] probe = new byte[1];
                int n = inflater.inflate(probe, 0, 1);
                if (n != 0 || !inflater.finished()) {
                    return null;
                }
            }
            if (inflater.getRemaining() != 0) {
                return null; // trailing bytes after the deflate stream
            }
            return raw;
        } catch (DataFormatException e) {
            return null;
        }
    }

    // ---------------- file header and payload builders ----------------

    /** The 28-byte file header for the current format ({@link RewindFormat#FORMAT_VERSION}), protocol 47. */
    public static byte[] fileHeader(long baseEpochMs) {
        byte[] h = new byte[RewindFormat.FILE_HEADER_LENGTH];
        System.arraycopy(RewindFormat.fileMagic(), 0, h, 0, 8);
        RewindFormat.putShort(h, 8, RewindFormat.FORMAT_VERSION);
        RewindFormat.putShort(h, 10, 0);
        RewindFormat.putInt(h, 12, RewindFormat.PROTOCOL);
        RewindFormat.putLong(h, 16, baseEpochMs);
        CRC32 c = new CRC32();
        c.update(h, 0, 24);
        RewindFormat.putInt(h, 24, (int) c.getValue());
        return h;
    }

    /** HEADER payload: u8 source, varint count, then per packet varint len + bytes. */
    public static byte[] headerPayload(int source, List<byte[]> packets) {
        if (source != RewindFormat.SOURCE_CLIENT_SNAPSHOT && source != RewindFormat.SOURCE_CAPTURE_CARRY) {
            throw new IllegalArgumentException("source " + source);
        }
        int size = 1 + RewindFormat.varIntSize(packets.size());
        for (byte[] p : packets) {
            size += RewindFormat.varIntSize(p.length) + p.length;
        }
        byte[] b = new byte[size];
        b[0] = (byte) source;
        int pos = RewindFormat.putVarInt(b, 1, packets.size());
        for (byte[] p : packets) {
            pos = RewindFormat.putVarInt(b, pos, p.length);
            System.arraycopy(p, 0, b, pos, p.length);
            pos += p.length;
        }
        return b;
    }

    /** INDEX payload: varint n, then n × (u8 type, varint segment, varint64 timeUs, varint64 fileOffset). */
    public static byte[] indexPayload(List<RecordingReader.IndexEntry> entries) {
        byte[] b = new byte[5 + entries.size() * (1 + 5 + 10 + 10)];
        int pos = RewindFormat.putVarInt(b, 0, entries.size());
        for (RecordingReader.IndexEntry e : entries) {
            b[pos++] = (byte) e.type;
            pos = RewindFormat.putVarInt(b, pos, e.segment);
            pos = RewindFormat.putVarLong(b, pos, e.timeUs);
            pos = RewindFormat.putVarLong(b, pos, e.fileOffset);
        }
        byte[] exact = new byte[pos];
        System.arraycopy(b, 0, exact, 0, pos);
        return exact;
    }

    @Override
    public void close() {
        deflater.end();
        inflater.end();
    }
}
