package com.bedwarsqol.rewind.export.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * The sounds of one export's range, in start order (Rewind Export PLAN Solution 3; TASK S2).
 *
 * <p>Only sounds starting in {@code [startUs, endUs)} are kept: nothing from the seek or the pre-roll before the range,
 * nothing at or after its end. A distance-delayed sound starts {@code delay x 50 ms} after the moment it was played.
 * Sounds are mixed span by span, so {@link #takeBefore} hands each one out once.
 *
 * <p>Not thread-safe; the client thread logs and takes.
 */
public final class SoundLog {

    /** One client tick, the unit of {@code SoundHandler.playDelayedSound}'s delay. */
    public static final long TICK_US = 50_000L;

    private final long startUs;
    private final long endUs;
    /** Not yet taken, sorted by start time; equal times keep the order they were logged in. */
    private final List<SoundEvent> pending = new ArrayList<>();
    private int dropped;

    /**
     * @param startUs the range start (frame 0's time)
     * @param endUs   the selected range end (the sound track's end)
     */
    public SoundLog(long startUs, long endUs) {
        this.startUs = startUs;
        this.endUs = endUs;
    }

    /** Logs a sound that starts at its own time. Returns false when it lies outside the range (dropped). */
    public boolean add(SoundEvent e) {
        return add(e, 0);
    }

    /**
     * Logs a sound played with a delay of {@code delayTicks} client ticks: it starts at
     * {@code e.timeUs() + delayTicks x 50 ms}. Returns false when that start lies outside the range (dropped).
     */
    public boolean add(SoundEvent e, int delayTicks) {
        long t = e.timeUs() + Math.max(0, delayTicks) * TICK_US;
        if (t < startUs || t >= endUs) {
            dropped++;
            return false;
        }
        SoundEvent s = e.at(t);
        int i = pending.size();
        while (i > 0 && pending.get(i - 1).timeUs() > t) {
            i--;
        }
        pending.add(i, s);
        return true;
    }

    /** The sounds not yet taken, in start order (a copy). */
    public List<SoundEvent> events() {
        return new ArrayList<>(pending);
    }

    /** Removes and returns, in start order, every sound not yet taken that starts before {@code us}. */
    public List<SoundEvent> takeBefore(long us) {
        int n = 0;
        while (n < pending.size() && pending.get(n).timeUs() < us) {
            n++;
        }
        List<SoundEvent> head = pending.subList(0, n);
        List<SoundEvent> out = new ArrayList<>(head);
        head.clear();
        return out;
    }

    /** Sounds left out because they started outside the range. */
    public int dropped() {
        return dropped;
    }
}
