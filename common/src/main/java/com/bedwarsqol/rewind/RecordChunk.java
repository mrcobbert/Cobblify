package com.bedwarsqol.rewind;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A reused append-only {@code byte[]} of NET or TRACK records from one producer thread, read by the
 * writer thread without locks.
 *
 * <p>Record layout in the chunk is exactly the block payload layout: NET {@code [varint dtUs][varint
 * len][bytes]}, TRACK {@code [varint dtUs][u8 tag][body]}. The first record's {@code dtUs} is measured
 * from {@link #startTimeUs()}, so a block cut from any point of the chunk carries the bytes unchanged.
 *
 * <p>Each chunk also has a carry area (the frozen S47 of the capture handler, HEADER source 1) that is
 * filled before the chunk is published and stays untouched until the writer releases it.
 *
 * <p>Producers do not touch chunks directly; {@link Producer} owns the sequence, the watermark and the
 * rollover order.
 */
public final class RecordChunk {

    private final byte[] data;
    private byte[] carry = new byte[0];
    private int carryLength;
    private int seq;
    private int segment;
    private long startTimeUs;
    private boolean firstOfSegment;
    /** Producer-private write position, past the committed bytes while a reservation is open. */
    private int writePos;
    /**
     * End of the last committed record. Written by the producer before the watermark store and before
     * the handoff, so the writer may read it for a handed-off chunk; for the live chunk it reads the
     * watermark instead.
     */
    private int committed;

    public RecordChunk(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity " + capacity);
        }
        this.data = new byte[capacity];
    }

    public byte[] data() {
        return data;
    }

    public int capacity() {
        return data.length;
    }

    /** Strictly increasing per producer, starting at 1. */
    public int seq() {
        return seq;
    }

    public int segment() {
        return segment;
    }

    /** Producer clock (µs) the first record's {@code dtUs} is measured from. */
    public long startTimeUs() {
        return startTimeUs;
    }

    /** True for the chunk a boundary opened; its carry area is that segment's HEADER source 1. */
    public boolean firstOfSegment() {
        return firstOfSegment;
    }

    public byte[] carry() {
        return carry;
    }

    public int carryLength() {
        return carryLength;
    }

    /** Length of the committed records. Final once the chunk is handed off. */
    public int committedLength() {
        return committed;
    }

    /** Writer side: copies committed bytes {@code [from, to)} into {@code dst}; returns the count. */
    public int tail(int from, int to, byte[] dst, int dstOff) {
        if (from < 0 || to > data.length || from > to) {
            throw new IndexOutOfBoundsException(from + ".." + to);
        }
        System.arraycopy(data, from, dst, dstOff, to - from);
        return to - from;
    }

    void reset(int seq, int segment, long startTimeUs, boolean firstOfSegment, byte[] carrySrc, int carryOff,
               int carryLen) {
        this.seq = seq;
        this.segment = segment;
        this.startTimeUs = startTimeUs;
        this.firstOfSegment = firstOfSegment;
        this.writePos = 0;
        this.committed = 0;
        if (carryLen > 0) {
            if (carry.length < carryLen) {
                carry = new byte[carryLen];
            }
            System.arraycopy(carrySrc, carryOff, carry, 0, carryLen);
        }
        this.carryLength = Math.max(0, carryLen);
    }

    // ---- watermark encoding: (chunkSeq << 32) | offset ----

    public static long watermark(int seq, int offset) {
        return (long) seq << 32 | offset & 0xFFFFFFFFL;
    }

    public static int watermarkSeq(long watermark) {
        return (int) (watermark >>> 32);
    }

    public static int watermarkOffset(long watermark) {
        return (int) watermark;
    }

    /**
     * One producer's chunks: the live chunk, the full ring (producer to writer), the free ring (writer
     * back to producer) and the {@code (chunkSeq, offset)} watermark.
     *
     * <p><b>Producer thread</b> (exactly one): {@link #startSegment}, {@link #reserveNet}/{@link
     * #reserveTrack} + {@link #commit}/{@link #abort}, {@link #appendNet}, {@link #appendTrack}, {@link
     * #handOff}. After each committed record it publishes the watermark with one ordered store. No
     * allocation except a new chunk when the free ring is empty and fewer than {@code maxChunks} exist.
     *
     * <p><b>Rollover order</b> (fixed, so a writer that read watermark {@code (s, o)} finds every chunk
     * with seq &lt; s already in the full ring): (1) offer chunk s to the full ring, (2) publish {@code
     * current} = chunk s+1, (3) publish its first watermark {@code (s+1, 0)}.
     *
     * <p><b>Writer thread</b> (exactly one): {@link #watermark}, {@link #liveChunk}, {@link #peekFull},
     * {@link #pollFull}, {@link #chunkForSeq}, {@link #release}. Bytes of chunk s below the watermark
     * offset are immutable until the writer releases the chunk.
     *
     * <p>A failed roll (no free chunk at the cap) leaves no live chunk; the watermark keeps naming the
     * handed-off chunk, so the writer still finds it at the ring head. The next append or segment start
     * tries again.
     */
    public static final class Producer {
        private final int chunkCapacity;
        private final int maxChunks;
        private final ChunkRing full;
        private final ChunkRing free;
        private final AtomicLong watermark = new AtomicLong();
        private final AtomicReference<RecordChunk> live = new AtomicReference<RecordChunk>();

        // producer-thread state
        private RecordChunk cur;
        private int allocated;
        private int nextSeq = 1;
        private int segment;
        private long lastTimeUs;
        private long pendingTimeUs;

        /**
         * @param chunkCapacity bytes per chunk ({@link RewindFormat#CHUNK_CAPACITY} in production)
         * @param initialChunks chunks allocated now and placed in the free ring
         * @param maxChunks     most chunks this producer will ever allocate
         * @param originUs      producer clock (µs) the very first record is measured from
         */
        public Producer(int chunkCapacity, int initialChunks, int maxChunks, long originUs) {
            if (maxChunks < 1 || initialChunks < 0 || initialChunks > maxChunks) {
                throw new IllegalArgumentException("chunks " + initialChunks + "/" + maxChunks);
            }
            this.chunkCapacity = chunkCapacity;
            this.maxChunks = maxChunks;
            this.full = new ChunkRing(maxChunks);
            this.free = new ChunkRing(maxChunks);
            for (int i = 0; i < initialChunks; i++) {
                free.offer(new RecordChunk(chunkCapacity));
            }
            this.allocated = initialChunks;
            this.lastTimeUs = originUs;
            this.pendingTimeUs = originUs;
        }

        // ---------------- producer thread ----------------

        /** The live chunk as the producer sees it; null before the first segment or after a failed roll. */
        public RecordChunk current() {
            return cur;
        }

        public int segment() {
            return segment;
        }

        public int allocatedChunks() {
            return allocated;
        }

        /**
         * Boundary roll: hands off the live chunk (if any) and opens segment {@code segment} in a fresh
         * chunk whose carry area holds {@code carry[off, off+len)} (len 0 = empty carry). Returns false if
         * no chunk is free and the cap is reached; the old chunk is handed off either way.
         */
        public boolean startSegment(int segment, byte[] carry, int carryOff, int carryLen) {
            if (carryLen < 0 || carryLen > 0 && (carry == null || carryOff < 0 || carryOff + carryLen > carry.length)) {
                throw new IllegalArgumentException("carry");
            }
            this.segment = segment;
            return roll(true, carry, carryOff, carryLen);
        }

        /** Hands off the live chunk and leaves none (channel inactive, handler removed). */
        public void handOff() {
            if (cur != null) {
                abort();
                full.offer(cur);
                cur = null;
                live.lazySet(null);
            }
        }

        /**
         * Reserves a NET record of {@code len} bytes and writes its {@code [dtUs][len]} prefix. Returns the
         * offset in {@code current().data()} where the caller copies the packet bytes, then calls {@link
         * #commit}. Returns -1 if the record can never fit a chunk or no chunk is available.
         */
        public int reserveNet(long nowUs, int len) {
            if (len < 0 || len + RewindFormat.MAX_RECORD_PREFIX > chunkCapacity) {
                return -1;
            }
            if (!ensureRoom(len + RewindFormat.MAX_RECORD_PREFIX)) {
                return -1;
            }
            int pos = writeDt(nowUs);
            pos = RewindFormat.putVarInt(cur.data, pos, len);
            cur.writePos = pos + len;
            return pos;
        }

        /** As {@link #reserveNet}, for a TRACK record {@code [dtUs][u8 tag][body of len bytes]}. */
        public int reserveTrack(long nowUs, int tag, int len) {
            if (len < 0 || len + RewindFormat.MAX_RECORD_PREFIX > chunkCapacity) {
                return -1;
            }
            if (!ensureRoom(len + RewindFormat.MAX_RECORD_PREFIX)) {
                return -1;
            }
            int pos = writeDt(nowUs);
            cur.data[pos++] = (byte) tag;
            cur.writePos = pos + len;
            return pos;
        }

        /** Publishes the reserved record: committed length first, then the watermark (one ordered store). */
        public void commit() {
            RecordChunk c = cur;
            if (c == null || c.writePos == c.committed) {
                return;
            }
            c.committed = c.writePos;
            lastTimeUs = pendingTimeUs;
            watermark.lazySet(RecordChunk.watermark(c.seq, c.committed));
        }

        /** Drops an open reservation (the copy failed); nothing past the last commit is ever handed off. */
        public void abort() {
            if (cur != null) {
                cur.writePos = cur.committed;
            }
            pendingTimeUs = lastTimeUs;
        }

        public boolean appendNet(long nowUs, byte[] src, int off, int len) {
            int at = reserveNet(nowUs, len);
            if (at < 0) {
                return false;
            }
            System.arraycopy(src, off, cur.data, at, len);
            commit();
            return true;
        }

        public boolean appendTrack(long nowUs, int tag, byte[] body, int off, int len) {
            int at = reserveTrack(nowUs, tag, len);
            if (at < 0) {
                return false;
            }
            System.arraycopy(body, off, cur.data, at, len);
            commit();
            return true;
        }

        private int writeDt(long nowUs) {
            long dt = nowUs - lastTimeUs;
            if (dt < 0) {
                dt = 0;
            } else if (dt > RewindFormat.MAX_DT_US) {
                dt = RewindFormat.MAX_DT_US;
            }
            // Reconstructed time is always lastTimeUs + dt, so a clamp shifts later records, never splits them.
            pendingTimeUs = lastTimeUs + dt;
            return RewindFormat.putVarInt(cur.data, cur.committed, (int) dt);
        }

        private boolean ensureRoom(int need) {
            if (cur != null) {
                cur.writePos = cur.committed;
                if (chunkCapacity - cur.committed >= need) {
                    return true;
                }
            }
            return roll(false, null, 0, 0);
        }

        private boolean roll(boolean boundary, byte[] carry, int carryOff, int carryLen) {
            // (1) hand off chunk s.
            if (cur != null) {
                abort();
                full.offer(cur);
                cur = null;
            }
            RecordChunk next = free.poll();
            if (next == null && allocated < maxChunks) {
                next = new RecordChunk(chunkCapacity);
                allocated++;
            }
            if (next == null) {
                live.lazySet(null);
                return false;
            }
            next.reset(nextSeq++, segment, lastTimeUs, boundary, carry, carryOff, boundary ? carryLen : 0);
            cur = next;
            // (2) publish current = chunk s+1, (3) then its first watermark.
            live.lazySet(next);
            watermark.lazySet(RecordChunk.watermark(next.seq, 0));
            return true;
        }

        // ---------------- writer thread ----------------

        /** The cutoff: everything committed before this read is in a chunk with seq &lt; s, or in chunk s below o. */
        public long watermark() {
            return watermark.get();
        }

        /** The published live chunk, or null. */
        public RecordChunk liveChunk() {
            return live.get();
        }

        public RecordChunk peekFull() {
            return full.peek();
        }

        public RecordChunk pollFull() {
            return full.poll();
        }

        /** Chunk {@code seq}: the live chunk if it has that seq, else the full ring's head if it does, else null. */
        public RecordChunk chunkForSeq(int seq) {
            RecordChunk c = live.get();
            if (c != null && c.seq == seq) {
                return c;
            }
            c = full.peek();
            if (c != null && c.seq == seq) {
                return c;
            }
            return null;
        }

        /** Returns a fully consumed chunk (already polled from the full ring) for reuse. */
        public void release(RecordChunk c) {
            if (!free.offer(c)) {
                throw new IllegalStateException("free ring full");
            }
        }
    }
}
