package com.bedwarsqol.rewind;

import com.bedwarsqol.stats.EligibilitySnapshot;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The pure half of the Rewind capture handler (PLAN "Capture"): what to do with each clientbound play
 * frame of one connection. The Netty handler ({@code RewindCapture}, Ornithe) only wraps the frame bytes
 * in a {@link Frame}, calls {@link #onFrame} and forwards the message untouched.
 *
 * <p><b>Per frame, in this order.</b>
 * <ol>
 *   <li>Read the varint packet id.</li>
 *   <li><b>Boundary first.</b> S01 (dimension at body byte 5), or S07 (dimension at body bytes 0-3) whose
 *       dimension differs from the current one, rolls to segment k+1 before anything is appended: the
 *       segment-k chunk is handed off and a fresh chunk opens k+1 with the frozen S47 in its carry area.
 *       The boundary packet is therefore the first NET record of its own segment. The lobby skip applies
 *       to one segment only, and the failure latch is left alone. Detection and counting run even while
 *       copying is off, so the count stays aligned with the client thread's WORLD_LOAD count.</li>
 *   <li><b>S47 carry.</b> Every S47 is copied into the preallocated {@code lastS47} buffer (grown only for a
 *       larger one), even while copying is off; the value frozen at a boundary is the latest S47 before the
 *       boundary packet in stream order.</li>
 *   <li><b>Skip list.</b> S00, S46 and S3F are never stored. For S3F the channel name is compared in place:
 *       a {@code hyevent:location} payload is copied and posted with the current segment index, and a
 *       name containing "restrict" (ASCII, any case, e.g. {@code Replay|Restrict}) is counted, each time, so
 *       every game's stats window sees its own (code review 3, O1).</li>
 *   <li><b>Copy</b> if the segment is copied: clock, reserve {@code [varint dtUs][varint len]}, copy, commit
 *       (which publishes the watermark).</li>
 *   <li><b>Handled order</b> (Rewind parity, A7): with a {@link HandledRing}, every frame whose handler vanilla runs
 *       on the client thread ({@link HandledRing#clientHandled}) pushes one entry: segment, id, whether it was stored
 *       and its ordinal among the segment's stored client-handled records. Unstored frames (S3F, a dropped or failed
 *       segment) are pushed too. A full ring is counted there and never fails the session.</li>
 * </ol>
 *
 * <p><b>Failure latch.</b> Any Throwable, no free chunk, or a frame larger than a chunk latches
 * {@link #failed()}, keeps the reason for the client thread and stops copying. A boundary never clears it;
 * only {@link #rearm()} does, and copying then restarts at the next boundary that gets a chunk (the rearm
 * stays pending through boundaries that find none). {@link #failureCount()} counts latches, so the client
 * thread can tell a new failure from the old one while a rearm is pending. Each latch records the segment it
 * happened in ({@link #failureSegment()}) and each restart the segment copying resumed in
 * ({@link #resumedSegment()}, counted by {@link #resumeCount()}), so the rules attach a failure to the game that
 * owns that segment, not to whatever the client thread is tracking when it looks (code review 2, I2).
 *
 * <p><b>Threads.</b> {@link #onFrame}, {@link #localWants}, {@link #onLocalCustomPayload}, {@link #onLocalUnstored},
 * {@link #fail} and {@link #close} run on the connection's Netty thread only. {@link #rearm} and {@link #skipSegment} are
 * for the client thread. The rest are reads, safe from any thread. Steady state allocates nothing: the
 * only allocations are a larger {@code lastS47} or chunk carry, a new chunk while fewer than the cap
 * exist, and the copied payload of a {@code hyevent:location} frame (one per server switch). No I/O, no
 * logging.
 */
public final class CaptureSession {

    /** One frame's bytes ({@code [varint id][body]}); indexes are relative to the frame start. */
    public interface Frame {
        int length();

        /** Unsigned byte at {@code index}. */
        int byteAt(int index);

        /** Copies {@code [index, index + len)} into {@code dst}; must not allocate on the remote path. */
        void copyTo(int index, byte[] dst, int dstOff, int len);
    }

    /** Producer clock in µs, the same one the producer's origin was taken from. */
    public interface Clock {
        long nowUs();
    }

    /** Receives each {@code hyevent:location} payload on the Netty thread; posts it to the client thread. */
    public interface LocationSink {
        void onLocation(int segment, byte[] payload);
    }

    public static final Clock NANO_CLOCK = new Clock() {
        @Override
        public long nowUs() {
            return System.nanoTime() / 1000L;
        }
    };

    public static final int FAIL_NONE = 0;
    /** A Throwable on the Netty thread; {@link #failureCause()} holds it. */
    public static final int FAIL_EXCEPTION = 1;
    /** No free chunk at the cap (the writer is behind). */
    public static final int FAIL_NO_CHUNK = 2;
    /** A frame that can never fit a chunk. */
    public static final int FAIL_OVERSIZE = 3;

    /** Initial size of the {@code lastS47} buffer. */
    public static final int S47_INITIAL_CAPACITY = 4096;

    private static final byte[] LOCATION_CHANNEL = "hyevent:location".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RESTRICT = "restrict".getBytes(StandardCharsets.US_ASCII);
    /** 1.8.9 reads custom-payload channel names with a limit of 20. */
    private static final int MAX_CHANNEL_BYTES = 20 * 4;

    private final RecordChunk.Producer producer;
    private final Clock clock;
    private final LocationSink sink;

    // ---- Netty-thread state (the volatile ones are also read elsewhere) ----
    private volatile int segment;
    private int dimension;
    private boolean haveDimension;
    private byte[] lastS47 = new byte[S47_INITIAL_CAPACITY];
    private int lastS47Length;
    private boolean closed;
    private int rearmSeen;
    /** Length of the varint read by {@link #readVarInt}; Netty-thread scratch. */
    private int varIntLength;
    /** Handled-order ring, or null (no handled order recorded). */
    private final HandledRing handled;
    /** Stored client-handled records of the current segment = the last stored one's ordinal. */
    private int storedHandled;
    /** The current frame's client-handled id (-1: none) and whether it was stored; pushed after the frame. */
    private int pendingId = -1;
    private boolean pendingStored;

    private volatile boolean failed;
    private volatile int failureReason;
    private volatile Throwable failureCause;
    private volatile int failureCount;
    /** Segment of the latest latch; written before {@link #failureCount} is incremented. */
    private volatile int failureSegment;
    /** Segment copying resumed in after the latest rearm; written before {@link #resumeCount} is incremented. */
    private volatile int resumedSegment;
    private volatile int resumeCount;
    /** Replay|Restrict-style names seen; single writer (Netty), read by the client thread per stats window. */
    private volatile int restrictCount;
    /** count, bytes for S00, S3F, S46; single writer (Netty), ordered stores. */
    private final AtomicLongArray skipped = new AtomicLongArray(6);

    // ---- client-thread inputs ----
    private final AtomicInteger rearmRequests = new AtomicInteger();
    private volatile int skipSegment = -1;

    public CaptureSession(RecordChunk.Producer producer, Clock clock, LocationSink sink) {
        this(producer, clock, sink, null);
    }

    public CaptureSession(RecordChunk.Producer producer, Clock clock, LocationSink sink, HandledRing handled) {
        if (producer == null || clock == null) {
            throw new NullPointerException();
        }
        this.producer = producer;
        this.clock = clock;
        this.sink = sink;
        this.handled = handled;
    }

    /**
     * A10: whether a connection gets a capture handler at all. Rewind and Record Games on, and the
     * connection is single-player or an exact Hypixel host.
     */
    public static boolean shouldInstall(boolean rewindOn, boolean recordGames, boolean local, String serverAddress) {
        return rewindOn && recordGames && (local || EligibilitySnapshot.isExactHypixelHost(serverAddress));
    }

    // ================================================================== Netty thread

    /** Steps 1-6 for one frame. Never throws: any failure latches. */
    public void onFrame(Frame f) {
        if (closed) {
            return;
        }
        pendingId = -1;
        pendingStored = false;
        try {
            frame(f);
        } catch (Throwable t) {
            fail(t);
        }
        if (pendingId >= 0) {
            pushHandled(pendingId, pendingStored);
        }
    }

    /** Step 6; also for a frame the local path never encodes. Never throws. */
    private void pushHandled(int id, boolean stored) {
        HandledRing r = handled;
        if (r == null) {
            return;
        }
        try {
            r.push(segment, id, stored, storedHandled);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void frame(Frame f) {
        int len = f.length();
        // 1. id
        int id = readVarInt(f, 0, len);
        if (id < 0) {
            return;
        }
        int p = varIntLength;
        if (handled != null && HandledRing.clientHandled(id)) {
            pendingId = id;
        }
        // 2. boundary first
        if (id == RewindFormat.PACKET_JOIN_GAME) {
            if (len >= p + 6) {
                boundary((byte) f.byteAt(p + 5));
            }
        } else if (id == RewindFormat.PACKET_RESPAWN) {
            if (len >= p + 4) {
                int dim = f.byteAt(p) << 24 | f.byteAt(p + 1) << 16 | f.byteAt(p + 2) << 8 | f.byteAt(p + 3);
                if (!haveDimension || dim != dimension) {
                    boundary(dim);
                }
            }
        } else if (id == RewindFormat.PACKET_PLAYER_LIST_HEADER_FOOTER) {
            // 3. S47 carry
            if (lastS47.length < len) {
                lastS47 = new byte[Math.max(len, Math.min(lastS47.length * 2, RewindFormat.MAX_PACKET_LENGTH))];
            }
            lastS47Length = 0;
            f.copyTo(0, lastS47, 0, len);
            lastS47Length = len;
        }
        // 4. skip list
        if (id == RewindFormat.PACKET_KEEP_ALIVE || id == RewindFormat.PACKET_SET_COMPRESSION) {
            countSkipped(id, len);
            return;
        }
        if (id == RewindFormat.PACKET_CUSTOM_PAYLOAD) {
            countSkipped(id, len);
            customPayload(f, p, len);
            return;
        }
        // 5. copy
        if (!copying()) {
            return;
        }
        RecordChunk c = producer.current();
        if (len + RewindFormat.MAX_RECORD_PREFIX > c.capacity()) {
            latch(FAIL_OVERSIZE, null);
            return;
        }
        int at = producer.reserveNet(clock.nowUs(), len);
        if (at < 0) {
            latch(FAIL_NO_CHUNK, null);
            return;
        }
        f.copyTo(0, producer.current().data(), at, len);
        producer.commit();
        if (pendingId >= 0) {
            storedHandled++;
            pendingStored = true;
        }
    }

    private void boundary(int dim) {
        dimension = dim;
        haveDimension = true;
        int seg = segment + 1;
        segment = seg;
        storedHandled = 0;
        if (failed) {
            int requests = rearmRequests.get();
            if (requests != rearmSeen && producer.startSegment(seg, lastS47, 0, lastS47Length)) {
                rearmSeen = requests;
                failureReason = FAIL_NONE;
                failureCause = null;
                failed = false;
                resumedSegment = seg;
                resumeCount++;
            }
            // Otherwise the segment-k chunk was handed off at the latch (or by the failed start above).
            return;
        }
        if (!producer.startSegment(seg, lastS47, 0, lastS47Length)) {
            latch(FAIL_NO_CHUNK, null);
        }
    }

    private void customPayload(Frame f, int p, int len) {
        int nameLen = readVarInt(f, p, len);
        if (nameLen < 0 || nameLen > MAX_CHANNEL_BYTES) {
            return;
        }
        int name = p + varIntLength;
        int payload = name + nameLen;
        if (payload > len) {
            return;
        }
        if (containsIgnoreCase(f, name, nameLen, RESTRICT)) {
            restrictCount = restrictCount + 1;
        }
        if (sink != null && equalsBytes(f, name, nameLen, LOCATION_CHANNEL)) {
            byte[] data = new byte[len - payload];
            f.copyTo(payload, data, 0, data.length);
            sink.onLocation(segment, data);
        }
    }

    private boolean copying() {
        return !failed && skipSegment != segment && producer.current() != null;
    }

    /**
     * Local path: whether the handler should encode a packet with this id at all. Boundary packets and S47
     * always (counting and the freeze run while copying is off); skip-list ids never (they are counted
     * here; S3F goes to {@link #onLocalCustomPayload} instead); anything else only while copying.
     */
    public boolean localWants(int packetId) {
        if (closed) {
            return false;
        }
        if (packetId == RewindFormat.PACKET_JOIN_GAME || packetId == RewindFormat.PACKET_RESPAWN
                || packetId == RewindFormat.PACKET_PLAYER_LIST_HEADER_FOOTER) {
            return true;
        }
        if (packetId == RewindFormat.PACKET_KEEP_ALIVE || packetId == RewindFormat.PACKET_SET_COMPRESSION) {
            countSkipped(packetId, 0);
            return false;
        }
        if (packetId == RewindFormat.PACKET_CUSTOM_PAYLOAD) {
            return false;
        }
        return copying();
    }

    /** Local path S3F: flagged by name only; its payload is never read (that would move its reader index). */
    public void onLocalCustomPayload(String channel) {
        if (closed) {
            return;
        }
        countSkipped(RewindFormat.PACKET_CUSTOM_PAYLOAD, 0);
        pushHandled(RewindFormat.PACKET_CUSTOM_PAYLOAD, false);
        if (channel != null) {
            for (int i = 0; i + RESTRICT.length <= channel.length(); i++) {
                if (channel.regionMatches(true, i, "restrict", 0, RESTRICT.length)) {
                    restrictCount = restrictCount + 1;
                    return;
                }
            }
        }
    }

    /** Latches the failure (the handler's catch-all). Never throws. */
    public void fail(Throwable t) {
        latch(FAIL_EXCEPTION, t);
    }

    private void latch(int reason, Throwable t) {
        try {
            if (!failed) {
                failureReason = reason;
                failureCause = t;
                rearmSeen = rearmRequests.get(); // a rearm asked for before this failure does not clear it
                failureSegment = segment;
                failureCount++;
                failed = true;
            }
            producer.handOff(); // drops any open reservation; committed records go to the writer
        } catch (Throwable ignored) {
            failed = true;
        }
    }

    /** Channel inactive or handler removed: hands the live chunk off; the session records nothing more. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            producer.handOff();
        } catch (Throwable ignored) {
            // nothing left to protect
        }
    }

    // ================================================================== client thread

    /** Clears the failure latch from the next boundary that gets a chunk. No effect while not failed. */
    public void rearm() {
        rearmRequests.incrementAndGet();
    }

    /** Stops copying segment {@code segment} (dropped lobby run); it never applies to a later segment. */
    public void skipSegment(int segment) {
        skipSegment = segment;
    }

    // ================================================================== reads (any thread)

    public RecordChunk.Producer producer() {
        return producer;
    }

    /** The handled-order ring this session pushes to, or null. */
    public HandledRing handledRing() {
        return handled;
    }

    /**
     * Local path, Netty thread: a packet {@link #localWants} declined still runs its handler on the client thread, so
     * a client-handled one is pushed unstored. Never throws.
     */
    public void onLocalUnstored(int packetId) {
        if (closed || handled == null || !HandledRing.clientHandled(packetId)) {
            return;
        }
        pushHandled(packetId, false);
    }

    /** Boundaries seen so far; segment k is the stream from the k-th boundary (0 before the first). */
    public int segment() {
        return segment;
    }

    /** True from a failure until copying restarts at a boundary after {@link #rearm()}. */
    public boolean failed() {
        return failed;
    }

    public int failureReason() {
        return failureReason;
    }

    public Throwable failureCause() {
        return failureCause;
    }

    /** Number of failures latched so far (a pending rearm does not count a new one). */
    public int failureCount() {
        return failureCount;
    }

    /** The segment the latest failure latched in (read after {@link #failureCount()}). */
    public int failureSegment() {
        return failureSegment;
    }

    /** Times copying restarted after a rearm. */
    public int resumeCount() {
        return resumeCount;
    }

    /** The segment copying last restarted in (read after {@link #resumeCount()}). */
    public int resumedSegment() {
        return resumedSegment;
    }

    public boolean closed() {
        return closed;
    }

    /** Replay|Restrict-style channel names seen so far (code review 3, O1: counted per message). */
    public int restrictCount() {
        return restrictCount;
    }

    public boolean restrictChannelSeen() {
        return restrictCount > 0;
    }

    /** Frames of a skip-list id (S00, S3F, S46) seen; 0 for any other id. */
    public long skippedCount(int packetId) {
        int i = skipIndex(packetId);
        return i < 0 ? 0 : skipped.get(i);
    }

    /** Bytes of those frames (id included); the local path counts 0 bytes (it never encodes them). */
    public long skippedBytes(int packetId) {
        int i = skipIndex(packetId);
        return i < 0 ? 0 : skipped.get(i + 1);
    }

    // ================================================================== helpers

    private static int skipIndex(int packetId) {
        switch (packetId) {
            case RewindFormat.PACKET_KEEP_ALIVE:
                return 0;
            case RewindFormat.PACKET_CUSTOM_PAYLOAD:
                return 2;
            case RewindFormat.PACKET_SET_COMPRESSION:
                return 4;
            default:
                return -1;
        }
    }

    private void countSkipped(int packetId, int bytes) {
        int i = skipIndex(packetId);
        skipped.lazySet(i, skipped.get(i) + 1);
        skipped.lazySet(i + 1, skipped.get(i + 1) + bytes);
    }

    /** Varint at {@code pos}, or -1 if malformed or past {@code end}; its length goes to {@link #varIntLength}. */
    private int readVarInt(Frame f, int pos, int end) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            if (pos + i >= end) {
                return -1;
            }
            int b = f.byteAt(pos + i);
            value |= (b & 0x7F) << 7 * i;
            if ((b & 0x80) == 0) {
                varIntLength = i + 1;
                return value;
            }
        }
        return -1;
    }

    private static boolean equalsBytes(Frame f, int off, int len, byte[] expected) {
        if (len != expected.length) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (f.byteAt(off + i) != (expected[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /** ASCII case-insensitive search for lower-case {@code needle} in {@code [off, off + len)}. */
    private static boolean containsIgnoreCase(Frame f, int off, int len, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= len; i++) {
            for (int j = 0; j < needle.length; j++) {
                int b = f.byteAt(off + i + j);
                if (b >= 'A' && b <= 'Z') {
                    b += 'a' - 'A';
                }
                if (b != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
