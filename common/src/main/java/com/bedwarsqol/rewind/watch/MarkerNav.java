package com.bedwarsqol.rewind.watch;

import java.util.List;

/**
 * Marker navigation (round 1 I2, round 2 I3). A jump to marker i lands at {@code landing(i) = max(game start,
 * m_i - 3 s)} (Q3), so you see what led up to it.
 *
 * <p>State: the <b>cursor</b>, the index of the marker last jumped to, or none. It holds only while the playhead is in
 * that marker's lead-in; playing past the marker clears it, and the session calls {@link #clear()} on any other seek.
 * {@code base(p)} is the last marker the playhead has reached ({@code m_i <= time of p}). Effective = the cursor if
 * held, else base. Next = effective + 1; previous = cursor - 1 when held, else base. Each jump sets the cursor. So
 * every marker is visited in order even when landings coincide (markers in the first 3 s, or 30 ms apart), and the
 * cursor's marker is the one drawn highlighted.
 *
 * <p>Positions are playhead ticks ({@link ReplayTicks#playheadTick()}): tick p stands for {@code origin + p x 50 ms}.
 * Marker i is reached at tick {@code markerTick(i) = tickOf(m_i)}; the cursor holds while
 * {@code landingTick(cursor) <= p <= markerTick(cursor)} (the marker's own tick is still the lead-in: its records are
 * fed only when that tick runs), so a landing on the grid tick of a marker within 50 ms of the game start still holds.
 *
 * <p>Pure; one thread.
 */
public final class MarkerNav {

    /** How far before a marker a jump lands. */
    public static final long LEAD_US = 3_000_000L;

    private final Timeline timeline;
    private final long[] markerUs;
    private int cursor = -1;

    public MarkerNav(Timeline timeline) {
        this.timeline = timeline;
        List<Timeline.Marker> ms = timeline.markers();
        markerUs = new long[ms.size()];
        for (int i = 0; i < markerUs.length; i++) {
            markerUs[i] = ms.get(i).timeUs;
        }
    }

    public int count() {
        return markerUs.length;
    }

    public long markerUs(int i) {
        return markerUs[i];
    }

    public long landingUs(int i) {
        return Math.max(timeline.gameStartUs(), markerUs[i] - LEAD_US);
    }

    /** The tick a jump to marker i lands on (the first tick at or after its landing). */
    public long landingTick(int i) {
        return timeline.tickOf(landingUs(i));
    }

    /** The tick at which marker i counts as reached. */
    public long markerTick(int i) {
        return timeline.tickOf(markerUs[i]);
    }

    /** The highlighted marker: the cursor if it still holds at playhead {@code p}, else -1 (and it is cleared). */
    public int cursor(long p) {
        if (cursor >= 0 && (p < landingTick(cursor) || p > markerTick(cursor))) {
            cursor = -1;
        }
        return cursor;
    }

    /** The last marker reached at playhead {@code p}, or -1 before the first. */
    public int base(long p) {
        int found = -1;
        for (int i = 0; i < markerUs.length; i++) {
            if (markerTick(i) <= p) {
                found = i;
            } else {
                break;
            }
        }
        return found;
    }

    /** The marker to jump to for "next" at playhead {@code p}, made the cursor; -1 when there is none. */
    public int next(long p) {
        int held = cursor(p);
        int effective = held >= 0 ? held : base(p);
        int i = effective + 1;
        return i < markerUs.length ? jump(i) : -1;
    }

    /** The marker to jump to for "previous" at playhead {@code p}, made the cursor; -1 when there is none. */
    public int previous(long p) {
        int held = cursor(p);
        int i = held >= 0 ? held - 1 : base(p);
        return i >= 0 ? jump(i) : -1;
    }

    /** A jump to marker {@code i} (a click on its icon): it becomes the cursor. Returns i, or -1 if out of range. */
    public int jump(int i) {
        if (i < 0 || i >= markerUs.length) {
            return -1;
        }
        cursor = i;
        return i;
    }

    /** Any seek that is not a marker jump clears the cursor. */
    public void clear() {
        cursor = -1;
    }
}
