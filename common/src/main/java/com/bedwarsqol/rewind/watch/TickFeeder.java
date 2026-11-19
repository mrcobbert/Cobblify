package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.HandledRing;
import com.bedwarsqol.rewind.RewindFormat;

import java.util.List;

/**
 * The tick-locked feed: the only place a recording's packets enter the client.
 *
 * <p>Tick k is tick k of the recording's {@link TickTable} (T[k] = {@link Timeline#timeOfTick}). Before world tick k
 * runs, {@link #feedForTick feedForTick(k, sink)} does, in this order (PLAN "Event order"):
 * <ol>
 *   <li><b>NET records</b> in stored order, each through {@link PacketPolicy}, up to the first one not handled yet:
 *       where tick k is a v2 head with a known order, a client-handled record of the head's segment is handled when
 *       its ordinal (1-based, among the segment's client-handled stored records) is at most H[k], a record of an
 *       earlier segment always is and one of a later segment is not; a record vanilla handles on the Netty thread
 *       ({@link HandledRing#clientHandled} false), a v1 tick, a continuation tick or a head marked order-unknown use the
 *       arrival rule (stamp at or before T[k]; R2's rule, with its stated error of one tick).</li>
 *   <li>the <b>local phase</b>: every tick-scoped extension record ({@link ExtRecords.Scope#TICK}) stamped before
 *       T[k+1] not fed yet, in stamp order ({@link PacketSink#local});</li>
 *   <li>the puppet's <b>sample</b> for tick k ({@link PacketSink#sample}).</li>
 * </ol>
 * Ticks are fed in sequence from 0; speed, pause and catch-up only change how many ticks a frame runs, never which
 * records come before which tick, so the whole order is part of the trace the seek contract compares. No record is
 * dropped or reordered for a catch-up. Time-scoped extension records never enter the feed ({@link TickClock}).
 *
 * <p><b>Restarts.</b> {@link #restart()} goes back to before tick 0 at the start segment. The start segment's
 * source-0 header packets, then its source-1 ones, are fed before tick 0 ({@link #feedHeaders}, or the first
 * {@link #feedForTick} when the session has not called it); later segments' headers never are. Every segment's
 * boundary packet is its first NET record ({@link NetCursor}). The boundary hook follows every S01 and S07 that
 * applied.
 *
 * <p><b>Failures.</b> A record the sink fails ({@code false} or a {@link RuntimeException}) is skipped and counted;
 * more than {@link #MAX_FAILURES} in the session is {@link State#FAILED}, which holds across restarts and feeds
 * nothing more. A restart replays the same records, so a record counts only the first time it is fed in the session
 * (records at or before the furthest position reached earlier are not counted again, nor are headers).
 *
 * <p><b>Kick.</b> An S40 ({@link PacketPolicy.Decision#END}) is not fed; the feed is {@link State#ENDED} at that tick
 * and no later record is fed until a restart. Samples continue.
 *
 * <p>One thread (the client thread).
 */
public final class TickFeeder {

    /** More failed records than this in a session is {@link State#FAILED}. */
    public static final int MAX_FAILURES = 50;
    /** {@link #recordIndex()} while a header packet is being fed. */
    public static final long HEADER = -1L;

    public enum State { RUNNING, ENDED, FAILED }

    private final LoadedRecording recording;
    private final Timeline timeline;
    private final TickTable table;
    private final ExtRecords ext;
    private final NetCursor cursor;
    /** The next tick-scoped extension record (a position in {@link ExtRecords#local}). */
    private int localNext;
    /** Client-handled records fed so far in segment {@link #ordinalSegment}. */
    private long ordinal;
    private int ordinalSegment = Integer.MIN_VALUE;

    private long nextTick;
    private boolean headersFed;
    private boolean headersEverFed;
    private State state = State.RUNNING;
    private long endedAtTick = -1;
    private int failures;
    /** The furthest NET record position fed in this session, -1 before any. */
    private long highWater = -1;
    private long applied;
    private long dropped;
    private long currentIndex = HEADER;
    private long currentTimeUs;

    /** @throws IllegalArgumentException for a recording that cannot be played ({@code problem() != null}) */
    public TickFeeder(LoadedRecording recording) {
        if (recording.problem() != null || recording.timeline() == null) {
            throw new IllegalArgumentException("recording cannot be played: " + recording.problem());
        }
        this.recording = recording;
        this.timeline = recording.timeline();
        this.table = timeline.ticks();
        this.ext = recording.ext();
        this.cursor = recording.newCursor();
        this.currentTimeUs = timeline.originUs();
    }

    /** Back to before tick 0: headers pending, cursor at the first record. Failures and FAILED are kept. */
    public void restart() {
        cursor.reset();
        nextTick = 0;
        headersFed = false;
        if (state != State.FAILED) {
            state = State.RUNNING;
        }
        endedAtTick = -1;
        applied = 0;
        dropped = 0;
        currentIndex = HEADER;
        currentTimeUs = timeline.originUs();
        localNext = 0;
        ordinal = 0;
        ordinalSegment = Integer.MIN_VALUE;
    }

    /** Feeds the start segment's headers (source 0, then 1) if they were not fed since the last restart. */
    public void feedHeaders(PacketSink sink) {
        if (headersFed || state == State.FAILED) {
            return;
        }
        headersFed = true;
        boolean count = !headersEverFed;
        headersEverFed = true;
        LoadedRecording.Segment start = recording.startSegment();
        currentIndex = HEADER;
        currentTimeUs = timeline.originUs();
        feedHeader(start.header(RewindFormat.SOURCE_CLIENT_SNAPSHOT), count, sink);
        feedHeader(start.header(RewindFormat.SOURCE_CAPTURE_CARRY), count, sink);
    }

    private void feedHeader(List<byte[]> packets, boolean count, PacketSink sink) {
        for (byte[] p : packets) {
            if (state == State.FAILED) {
                return;
            }
            feedOne(p, 0, p.length, count, -1, true, sink);
        }
    }

    /**
     * Hands over the records of tick {@code k} and its sample. {@code k} must be {@link #nextTick()}.
     *
     * @return the feed's state afterwards
     */
    public State feedForTick(long k, PacketSink sink) {
        if (k != nextTick) {
            throw new IllegalStateException("tick " + k + " fed, expected " + nextTick);
        }
        nextTick = k + 1;
        if (state == State.FAILED) {
            return state;
        }
        feedHeaders(sink);
        long limit = table.timeOf(k);
        while (state == State.RUNNING && !cursor.atEnd() && handledBefore(k, limit)) {
            long position = cursor.position();
            currentIndex = position;
            currentTimeUs = cursor.timeUs();
            int id = cursor.packetId();
            int segment = cursor.segmentIndex();
            feedOne(cursor.buffer(), cursor.offset(), cursor.length(), position > highWater, k, false, sink);
            if (position > highWater) {
                highWater = position;
            }
            if (HandledRing.clientHandled(id)) {
                if (segment != ordinalSegment) {
                    ordinalSegment = segment;
                    ordinal = 0;
                }
                ordinal++;
            }
            cursor.advance();
        }
        currentIndex = HEADER;
        currentTimeUs = limit;
        if (state != State.FAILED) {
            feedLocal(k, sink);
            sink.sample(k, limit);
        }
        return state;
    }

    /** Whether the record under the cursor was handled before tick {@code k} (the class comment's rule). */
    private boolean handledBefore(long k, long limit) {
        if (!table.orderKnown(k) || !HandledRing.clientHandled(cursor.packetId())) {
            return cursor.timeUs() <= limit;
        }
        int segment = cursor.segmentIndex();
        int head = table.segment(k);
        if (segment != head) {
            return segment < head;
        }
        long next = (segment == ordinalSegment ? ordinal : 0) + 1;
        return next <= table.handled(k);
    }

    /** The local phase of tick {@code k}: tick-scoped extension records stamped before T[k+1], in stamp order. */
    private void feedLocal(long k, PacketSink sink) {
        long end = table.timeOf(k + 1);
        while (localNext < ext.localCount()) {
            int i = ext.local(localNext);
            if (ext.timeUs(i) >= end) {
                return;
            }
            localNext++;
            sink.local(ext.tag(i), ext.timeUs(i), ext.buffer(), ext.offset(i), ext.length(i));
        }
    }

    private void feedOne(byte[] buf, int off, int len, boolean count, long tick, boolean header, PacketSink sink) {
        PacketPolicy.Decision d = PacketPolicy.decide(buf, off, len);
        int id = RewindFormat.packetId(buf, off, len);
        boolean ok;
        switch (d) {
            case PASS:
            case BOUNDARY:
                ok = apply(sink, id, buf, off, len);
                if (ok && d == PacketPolicy.Decision.BOUNDARY) {
                    sink.boundary(id, buf, off, len);
                }
                break;
            case SESSION_HOOK:
                ok = hook(sink, id, buf, off, len);
                break;
            case WINDOW:
                ok = window(sink, id, buf, off, len);
                break;
            case END:
                if (header) {
                    dropped++;
                    return;
                }
                state = State.ENDED;
                endedAtTick = tick;
                return;
            case DROP:
            default:
                dropped++;
                return;
        }
        if (ok) {
            applied++;
        } else if (count) {
            failures++;
            if (failures > MAX_FAILURES) {
                state = State.FAILED;
            }
        }
    }

    private static boolean apply(PacketSink sink, int id, byte[] buf, int off, int len) {
        try {
            return sink.apply(id, buf, off, len);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean window(PacketSink sink, int id, byte[] buf, int off, int len) {
        try {
            return sink.window(id, buf, off, len);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean hook(PacketSink sink, int id, byte[] buf, int off, int len) {
        try {
            return sink.sessionHook(id, buf, off, len);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The tick being fed ({@link #nextTick()} - 1): -1 before tick 0, e.g. while the headers are fed. */
    public long currentTick() {
        return nextTick - 1;
    }

    /** The tick {@link #feedForTick} expects next (0 after a restart). */
    public long nextTick() {
        return nextTick;
    }

    public State state() {
        return state;
    }

    /** Failed records counted in this session (each record once). */
    public int failures() {
        return failures;
    }

    /** Records (header and NET) the sink accepted since the last restart. */
    public long applied() {
        return applied;
    }

    /** Records the policy kept from the sink since the last restart. */
    public long dropped() {
        return dropped;
    }

    /** NET records handed through the policy since the last restart (fed, dropped or failed). */
    public long recordsPassed() {
        return cursor.position();
    }

    /** The tick whose records held the recorded kick, or -1. */
    public long endedAtTick() {
        return endedAtTick;
    }

    /** Position of the NET record being handed over ({@link NetCursor#position()}), or {@link #HEADER}. */
    public long recordIndex() {
        return currentIndex;
    }

    /** Time of the record being handed over; the origin for header packets. */
    public long recordTimeUs() {
        return currentTimeUs;
    }
}
