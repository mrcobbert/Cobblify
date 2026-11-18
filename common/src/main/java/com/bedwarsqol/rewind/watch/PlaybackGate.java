package com.bedwarsqol.rewind.watch;

/**
 * Rewind's isolation gate (PLAN section 5): while a recording plays, Cobblify's own features must not react.
 *
 * <p><b>Muted.</b> Every Ornithe {@code ModEvents} post goes through one helper that skips posting while
 * {@link #muted()}, every direct mixin hook into a feature returns early, and R1's recorder ignores everything. The
 * gate lives at the Ornithe posting sites, not in the shared {@code EventDispatcher}, so Lunar and Forge never see
 * it (nothing there enters it).
 *
 * <p><b>Epoch and tickets.</b> The epoch grows by one at every entry and every exit. Work that leaves the thread it
 * started on carries a {@link Ticket} taken where the work is submitted; its effect re-checks {@link #isCurrent}, which
 * is true only while unmuted and in the same epoch. So work submitted before playback has no effect during playback
 * or after it, whenever it finishes.
 *
 * <p>Thread safety: entry and exit are serialized on the gate's lock; the flag and the epoch are volatile, so a
 * change made on one thread is seen by every other. {@link #isCurrent} reads both under the lock, so it never sees
 * an unmuted flag with an epoch from before the matching exit.
 *
 * <p>Production code uses {@link #global()}; tests make their own instances.
 */
public final class PlaybackGate {

    /** The epoch a piece of work was submitted in. Immutable. */
    public static final class Ticket {
        private final long epoch;

        Ticket(long epoch) {
            this.epoch = epoch;
        }

        public long epoch() {
            return epoch;
        }

        @Override
        public String toString() {
            return "Ticket(" + epoch + ")";
        }
    }

    private static final PlaybackGate GLOBAL = new PlaybackGate();

    /** The gate the client uses. */
    public static PlaybackGate global() {
        return GLOBAL;
    }

    /** {@code global().isMuted()}: true while a recording plays. Cheap (one volatile read), any thread. */
    public static boolean muted() {
        return GLOBAL.muted;
    }

    /** {@code global().currentTicket()}. */
    public static Ticket ticket() {
        return GLOBAL.currentTicket();
    }

    private final Object lock = new Object();
    private volatile boolean muted;
    private volatile long epoch;

    public PlaybackGate() {
    }

    public boolean isMuted() {
        return muted;
    }

    public long epoch() {
        return epoch;
    }

    /** A ticket for work submitted now. Taken while muted it is never current (and stale after exit). */
    public Ticket currentTicket() {
        return new Ticket(epoch);
    }

    /** True when the gate is unmuted and still in {@code ticket}'s epoch; false for null. */
    public boolean isCurrent(Ticket ticket) {
        if (ticket == null) {
            return false;
        }
        synchronized (lock) {
            return !muted && ticket.epoch == epoch;
        }
    }

    /** Playback starts: mute, then advance the epoch. */
    public void enter() {
        synchronized (lock) {
            muted = true;
            epoch++;
        }
    }

    /** Playback ended: unmute and advance the epoch, so tickets from before and during playback are stale. */
    public void exit() {
        synchronized (lock) {
            epoch++;
            muted = false;
        }
    }
}
