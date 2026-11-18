package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;

import java.util.List;

/**
 * The tick-locked feed: the only place a recording's packets enter the client.
 *
 * <p>Tick k stands for {@code origin + k x 50 ms} ({@link Timeline#timeOfTick}). Before world tick k runs,
 * {@link #feedForTick feedForTick(k, sink)} hands over every NET record with a time at or before that, in stored
 * order, each through {@link PacketPolicy}, then the puppet's sample for that time ({@link PacketSink#sample}). Ticks
 * are fed in sequence from 0; speed, pause and catch-up only change how many ticks a frame runs, never which records
 * come before which tick. No record is dropped or reordered for a catch-up.
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
    private final NetCursor cursor;

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
        long limit = timeline.timeOfTick(k);
        while (state == State.RUNNING && !cursor.atEnd() && cursor.timeUs() <= limit) {
            long position = cursor.position();
            currentIndex = position;
            currentTimeUs = cursor.timeUs();
            feedOne(cursor.buffer(), cursor.offset(), cursor.length(), position > highWater, k, false, sink);
            if (position > highWater) {
                highWater = position;
            }
            cursor.advance();
        }
        currentIndex = HEADER;
        currentTimeUs = limit;
        if (state != State.FAILED) {
            sink.sample(k, limit);
        }
        return state;
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

    private static boolean hook(PacketSink sink, int id, byte[] buf, int off, int len) {
        try {
            return sink.sessionHook(id, buf, off, len);
        } catch (RuntimeException e) {
            return false;
        }
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
