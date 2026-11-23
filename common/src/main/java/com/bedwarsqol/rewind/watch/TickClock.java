package com.bedwarsqol.rewind.watch;

/**
 * Playback time and the ticks it makes due (PLAN "Tick scheduler", review B4, round 2 I2; TASK B12, C3). Replaces
 * vanilla's timer for deciding how many world ticks a frame runs while a recording is WATCHED.
 *
 * <ul>
 *   <li>Playback time P advances each frame by the wall time since the last frame ({@code ClientOps.nanoTime()})
 *       times the speed; it stands still while paused, and a seek or catch-up sets it ({@link #set}).</li>
 *   <li>{@link #due}: the ticks of the table with {@code T[k] <= P} not yet run, at most
 *       {@value #MAX_TICKS_PER_FRAME} (vanilla's own cap) and never past the next stop. When the cap holds ticks back,
 *       P waits at the next tick's time, as vanilla's timer drops the time it cannot tick.</li>
 *   <li>{@link #partial}: {@code (P - T[j]) / (T[j+1] - T[j])}, j the last tick that will have run: what vanilla's
 *       {@code renderPartialTicks} must be so entities are drawn where they were at P.</li>
 *   <li>Time-scoped extension records ({@link ExtRecords.Scope#TIME}): {@link #dispatchTimed} hands every one stamped
 *       at or before P, not dispatched yet, to {@link PacketSink#timed}, in stamp order; {@link #skipTimed} moves the
 *       cursor to P without dispatching what is stamped before P (seek, catch-up, restart). A record stamped exactly
 *       at P stays: a seek lands at the next tick's time, and that tick (with its records) runs after the seek
 *       (review code-2 O10).</li>
 *   <li>An export (Rewind Export PLAN Solution 1) does not use wall time: {@link #step} sets P to each frame's time,
 *       {@link #dueAll} runs every tick reached without the 10-tick cap, {@link #seatTimed} puts the cursor at the
 *       export's start.</li>
 * </ul>
 *
 * So a tick runs in the first frame whose playback time has reached its recorded time: at 1x its error is at most
 * one frame, and a recorded freeze replays as a freeze followed by its catch-up ticks in one frame. Pure; one thread.
 */
public final class TickClock {

    /** Vanilla's {@code Timer.updateTimer} caps a frame at 10 ticks. */
    public static final int MAX_TICKS_PER_FRAME = 10;

    private final TickTable table;
    private long playUs;
    private long lastNanos;
    /** Playback nanoseconds not yet a whole microsecond (no drift at speeds like 0.25x). */
    private long remNanos;
    private int timedNext;
    /** The stamp of the record {@link #dispatchTimed} is handing over ({@link #dispatching()}). */
    private long dispatchingUs;
    private boolean dispatching;

    public TickClock(TickTable table) {
        this.table = table;
    }

    /** P, on the recording's clock (µs). */
    public long playbackUs() {
        return playUs;
    }

    /** A seek or catch-up landed: P is {@code us}, and wall time counts from {@code nowNanos}. */
    public void set(long us, long nowNanos) {
        playUs = us;
        lastNanos = nowNanos;
        remNanos = 0;
    }

    /** One frame on: P += wall time since the last frame x speed (nothing while paused; the wall time is spent). */
    public void advance(long nowNanos, float speed, boolean paused) {
        long dt = nowNanos - lastNanos;
        lastNanos = nowNanos;
        if (paused || dt <= 0) {
            return;
        }
        long ns = (long) (dt * (double) speed) + remNanos;
        playUs += ns / 1000;
        remNanos = ns % 1000;
    }

    /**
     * The ticks due this frame: {@code k >= ticksRun} with {@code T[k] <= P}, at most {@link #MAX_TICKS_PER_FRAME}
     * and {@code ticksToStop}. When either cap holds a due tick back, P is set to that tick's time.
     */
    public int due(long ticksRun, long ticksToStop) {
        long cap = Math.min(MAX_TICKS_PER_FRAME, Math.max(0, ticksToStop));
        int due = 0;
        while (due < cap && table.timeOf(ticksRun + due) <= playUs) {
            due++;
        }
        long next = table.timeOf(ticksRun + due);
        if (due == cap && next < playUs) {
            playUs = next;
        }
        return due;
    }

    /**
     * An export's fixed step (Rewind Export PLAN Solution 1): P is {@code us}, exactly. Wall time and speed play no
     * part; the caller sets the clock again ({@link #set}) when the export ends.
     */
    public void step(long us) {
        playUs = us;
        remNanos = 0;
    }

    /**
     * {@link #due} without vanilla's 10-tick cap, for an export: every tick {@code k >= ticksRun} with
     * {@code T[k] <= P}, at most {@code ticksToStop}; P is never pulled back (a recorded freeze's catch-up ticks all
     * run in the frame that reaches them).
     */
    public int dueAll(long ticksRun, long ticksToStop) {
        long cap = Math.min(Integer.MAX_VALUE, Math.max(0, ticksToStop));
        int due = 0;
        while (due < cap && table.timeOf(ticksRun + due) <= playUs) {
            due++;
        }
        return due;
    }

    /**
     * The dispatch cursor to the first time-scoped record stamped at or after {@code us}, wherever P is (an export's
     * start, review B1: a seek lands on a tick and {@link #skipTimed} passes what lies before it).
     */
    public void seatTimed(ExtRecords ext, long us) {
        timedNext = ext == null ? 0 : ext.firstTimedAfter(us - 1);
    }

    /** The partial tick to draw once tick {@code lastRun} has run. */
    public float partial(long lastRun) {
        return table.partial(lastRun, playUs);
    }

    /** The dispatch cursor to P: nothing stamped before P will be dispatched; what is stamped at P will. */
    public void skipTimed(ExtRecords ext) {
        timedNext = ext == null ? 0 : ext.firstTimedAfter(playUs - 1);
    }

    /** Dispatches every time-scoped record stamped at or before P not dispatched yet; returns how many. */
    public int dispatchTimed(ExtRecords ext, PacketSink sink) {
        if (ext == null || sink == null) {
            return 0;
        }
        int n = 0;
        while (timedNext < ext.timedCount()) {
            int i = ext.timed(timedNext);
            if (ext.timeUs(i) > playUs) {
                break;
            }
            timedNext++;
            dispatching = true;
            dispatchingUs = ext.timeUs(i);
            try {
                sink.timed(ext.tag(i), ext.timeUs(i), ext.buffer(), ext.offset(i), ext.length(i));
            } finally {
                dispatching = false;
            }
            n++;
        }
        return n;
    }

    /** True while {@link #dispatchTimed} hands a record to the sink. */
    public boolean dispatching() {
        return dispatching;
    }

    /** The stamp of the record being handed over (meaningful while {@link #dispatching()}). */
    public long dispatchingUs() {
        return dispatchingUs;
    }
}
