package com.bedwarsqol.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Times the {@link TeamCall} sounds: each call has its own debounce, so a spam wall can't chain its
 * sound, and a call's later notes wait for their client tick. One per chat handler, client thread only.
 */
public final class TeamCallAlerts {

    /** Minimum gap between two sounds of the same call. */
    static final long DEBOUNCE_MS = 1500L;

    private final long[] lastMs = new long[TeamCall.values().length];
    /** Queued notes, each with the client ticks left before it plays. */
    private final List<Pending> pending = new ArrayList<Pending>();

    private static final class Pending {
        final TeamCall.Note note;
        int ticksLeft;

        Pending(TeamCall.Note note) {
            this.note = note;
            this.ticksLeft = note.tick;
        }
    }

    /**
     * Starts {@code call} at {@code nowMs}: returns the notes to play now and queues the rest for
     * {@link #tick()}. Returns nothing while that call is still inside its debounce window.
     */
    public List<TeamCall.Note> start(TeamCall call, long nowMs) {
        int i = call.ordinal();
        if (nowMs - lastMs[i] <= DEBOUNCE_MS) return Collections.emptyList();
        lastMs[i] = nowMs;
        List<TeamCall.Note> now = new ArrayList<TeamCall.Note>();
        for (TeamCall.Note n : call.notes()) {
            if (n.tick <= 0) now.add(n);
            else pending.add(new Pending(n));
        }
        return now;
    }

    /** Advances one client tick and returns the queued notes due now. */
    public List<TeamCall.Note> tick() {
        if (pending.isEmpty()) return Collections.emptyList();
        List<TeamCall.Note> due = new ArrayList<TeamCall.Note>();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (--p.ticksLeft <= 0) {
                due.add(p.note);
                it.remove();
            }
        }
        return due;
    }
}
