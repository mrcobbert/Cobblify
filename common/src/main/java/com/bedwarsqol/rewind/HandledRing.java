package com.bedwarsqol.rewind;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The handled-order ring (PLAN section A "Handled order", A7): which stored NET records the client had actually
 * handled at each tick head. A NET stamp is the Netty arrival time; the handler runs later, in whichever frame's
 * scheduled-task drain reaches it, and never inside a tick. So:
 * <ul>
 *   <li><b>Capture thread</b> ({@link #push}, from the capture handler): one entry per frame whose handler vanilla runs
 *       on the client thread ({@link #clientHandled}: every play id except S00 keep-alive, S40 disconnect, S46
 *       compression, S47 tab header/footer and S48 resource pack, which vanilla handles on the Netty thread). Frames
 *       that are not stored (S3F, a dropped or failed segment) are pushed too, flagged unstored, so the queue stays
 *       aligned with the client's.</li>
 *   <li><b>Client thread</b> ({@link #onHandled}, from {@code PacketThreadUtil$1.run} HEAD): pops the entry of the
 *       packet about to run. If the head entry's id is not the running packet's id, a packet was dropped between
 *       capture and execution (another mod): the entries up to the first with the running id are skipped (one
 *       resync). A packet with no entry at all (the ring overflowed, or a mod ran a packet the handler never saw)
 *       consumes nothing (also a resync). The search never passes an entry that follows an overflow gap.</li>
 *   <li><b>Client thread</b> ({@link #takeHead}, at {@code Minecraft.runTick} HEAD): the increase of the highest
 *       handled ordinal since the previous head of the same segment, and {@link RewindFormat#TICK_HEAD_ORDER_UNKNOWN}
 *       when a resync, an overflow gap or an impossible count happened since the previous head. The count restarts
 *       from the popped entry's ordinal, which is absolute, so the head after an unknown one is exact again.</li>
 * </ul>
 *
 * <p><b>Ordinal.</b> 1-based index of the frame among the segment's <i>stored</i> NET records whose handler runs on
 * the client thread ({@link #clientHandled}); stored S47, S40 and S48 records have none. A segment's boundary packet
 * is ordinal 1 of its own segment. A TICK_HEAD's count is therefore "how many more of this segment's stored,
 * client-handled NET records had their handler run", and playback rebuilds ordinals by counting the segment's NET
 * records whose id is {@link #clientHandled}.
 *
 * <p><b>Entry</b> (one {@code long}): bits 0-31 ordinal, 32-39 packet id, 40 stored, 41 first entry after an overflow
 * gap, 48-63 segment (low 16 bits).
 *
 * <p><b>Cost.</b> Single producer, single consumer; preallocated ({@link #CAPACITY} entries, 512 KB); no allocation,
 * no lock, no I/O on either side. A full ring never blocks or throws: the entry is dropped and counted
 * ({@code handledOverflows}).
 */
public final class HandledRing {

    /** Default capacity: a backlog of 65,536 packets waiting for the client thread. */
    public static final int CAPACITY = 1 << 16;
    /** How far {@link #onHandled} looks ahead for the running packet's id. */
    public static final int SCAN_LIMIT = 1024;

    private static final int PACKET_DISCONNECT = 0x40;
    private static final int PACKET_RESOURCE_PACK = 0x48;

    private static final long ORDINAL_MASK = 0xFFFFFFFFL;
    private static final int ID_SHIFT = 32;
    private static final long STORED = 1L << 40;
    private static final long GAP = 1L << 41;
    private static final int SEGMENT_SHIFT = 48;
    private static final int SEGMENT_MASK = 0xFFFF;

    private final AtomicLongArray entries;
    private final int mask;
    private final int capacity;
    private final PacketStats.Feed feed;
    /** Written by the producer only. */
    private final AtomicLong head = new AtomicLong();
    /** Written by the consumer only. */
    private final AtomicLong tail = new AtomicLong();

    // ---- producer (capture thread) ----
    private long headLocal;
    private long tailSeen;
    private boolean gapPending;
    private volatile long overflows;

    // ---- consumer (client thread) ----
    private long tailLocal;
    private int segment = -1;
    private int handled;
    private boolean unknown;
    private int reportedSegment = -1;
    private int reported;
    private int headIncrease;
    private int headFlags;
    private volatile long resyncs;

    public HandledRing(PacketStats.Feed feed) {
        this(CAPACITY, feed);
    }

    /** {@code capacity} must be a power of two. {@code feed} may be null (tests). */
    public HandledRing(int capacity, PacketStats.Feed feed) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("capacity " + capacity);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.entries = new AtomicLongArray(capacity);
        this.feed = feed;
    }

    /** True when vanilla runs this clientbound play packet's handler on the client thread (PacketThreadUtil). */
    public static boolean clientHandled(int packetId) {
        return packetId != RewindFormat.PACKET_KEEP_ALIVE
                && packetId != PACKET_DISCONNECT
                && packetId != RewindFormat.PACKET_SET_COMPRESSION
                && packetId != RewindFormat.PACKET_PLAYER_LIST_HEADER_FOOTER
                && packetId != PACKET_RESOURCE_PACK;
    }

    // ================================================================== capture thread

    /**
     * One client-handled frame was captured. Returns false (and counts an overflow) when the ring is full; the next
     * entry that fits carries the gap mark. Never throws, never allocates.
     */
    public boolean push(int segment, int packetId, boolean stored, int ordinal) {
        long h = headLocal;
        if (h - tailSeen >= capacity) {
            tailSeen = tail.get();
            if (h - tailSeen >= capacity) {
                gapPending = true;
                overflows = overflows + 1;
                if (feed != null) {
                    feed.handledOverflow();
                }
                return false;
            }
        }
        long e = ((long) (segment & SEGMENT_MASK) << SEGMENT_SHIFT)
                | ((long) (packetId & 0xFF) << ID_SHIFT)
                | (ordinal & ORDINAL_MASK)
                | (stored ? STORED : 0L)
                | (gapPending ? GAP : 0L);
        gapPending = false;
        entries.lazySet((int) (h & mask), e);
        headLocal = h + 1;
        head.lazySet(h + 1);
        return true;
    }

    /** Entries pushed that could not fit (capture thread writes, any thread reads). */
    public long overflows() {
        return overflows;
    }

    // ================================================================== client thread

    /** The packet with clientbound play id {@code packetId} is about to run its handler on the client thread. */
    public void onHandled(int packetId) {
        long t = tailLocal;
        long h = head.get();
        if (t == h) {
            miss();
            return;
        }
        long e = entries.get((int) (t & mask));
        if (id(e) != packetId) {
            if ((e & GAP) != 0) {
                miss(); // a packet lost in the overflow: never search past the gap
                return;
            }
            long limit = Math.min(h, t + SCAN_LIMIT);
            long found = -1;
            for (long k = t + 1; k < limit; k++) {
                long x = entries.get((int) (k & mask));
                if ((x & GAP) != 0) {
                    if (id(x) == packetId) {
                        found = k;
                    }
                    break;
                }
                if (id(x) == packetId) {
                    found = k;
                    break;
                }
            }
            if (found < 0) {
                miss();
                return;
            }
            countResync();
            t = found;
            e = entries.get((int) (t & mask));
        }
        if ((e & GAP) != 0) {
            unknown = true;
        }
        tailLocal = t + 1;
        tail.lazySet(t + 1);
        int seg = (int) (e >>> SEGMENT_SHIFT) & SEGMENT_MASK;
        if (seg != segment) {
            segment = seg;
            handled = 0;
        }
        if ((e & STORED) != 0) {
            handled = (int) (e & ORDINAL_MASK);
        }
    }

    private void miss() {
        countResync();
    }

    private void countResync() {
        unknown = true;
        resyncs = resyncs + 1;
        if (feed != null) {
            feed.handledResync();
        }
    }

    /**
     * A tick head of TRACK segment {@code trackSegment}: computes {@link #headIncrease()} and {@link #headFlags()}.
     * Call once per tick head, recorded or not, so a later recorded head counts only its own span.
     */
    public void takeHead(int trackSegment) {
        int seg = trackSegment & SEGMENT_MASK;
        if (reportedSegment != seg) {
            reportedSegment = seg;
            reported = 0;
        }
        int inc = 0;
        if (segment == seg) {
            inc = handled - reported;
            if (inc < 0) {
                inc = 0;
                unknown = true;
            }
            reported = handled;
        } else {
            unknown = true; // nothing of this segment handled: its boundary packet's entry is missing
        }
        headIncrease = inc;
        headFlags = unknown ? RewindFormat.TICK_HEAD_ORDER_UNKNOWN : 0;
        unknown = false;
    }

    /** The count for the last {@link #takeHead}. */
    public int headIncrease() {
        return headIncrease;
    }

    /** The {@code TICK_HEAD_*} flags for the last {@link #takeHead}. */
    public int headFlags() {
        return headFlags;
    }

    /** Segment (low 16 bits) of the last popped entry, or -1 before the first. */
    public int segment() {
        return segment;
    }

    /** Highest handled ordinal of {@link #segment()}. */
    public int handled() {
        return handled;
    }

    /** Resyncs so far (client thread writes, any thread reads). */
    public long resyncs() {
        return resyncs;
    }

    /** Packet id of the oldest entry not yet popped, or -1 (consumer side; tests and drains). */
    public int peekPacketId() {
        long t = tailLocal;
        if (t == head.get()) {
            return -1;
        }
        return id(entries.get((int) (t & mask)));
    }

    /** Entries waiting (consumer side). */
    public int size() {
        return (int) (head.get() - tailLocal);
    }

    private static int id(long e) {
        return (int) (e >>> ID_SHIFT) & 0xFF;
    }
}
