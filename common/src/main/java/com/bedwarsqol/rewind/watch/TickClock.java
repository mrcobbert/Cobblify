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
 *       cursor to P without dispatching (seek, catch-up, restart).</li>
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

    /** The partial tick to draw once tick {@code lastRun} has run. */
    public float partial(long lastRun) {
        return table.partial(lastRun, playUs);
    }

    /** The dispatch cursor to P: nothing stamped at or before P will be dispatched. */
    public void skipTimed(ExtRecords ext) {
        timedNext = ext == null ? 0 : ext.firstTimedAfter(playUs);
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
            sink.timed(ext.tag(i), ext.timeUs(i), ext.buffer(), ext.offset(i), ext.length(i));
            n++;
        }
        return n;
    }
}
