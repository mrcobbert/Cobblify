package com.bedwarsqol.rewind.watch;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rewind's isolation gate (PLAN section 5): while a recording plays, Cobblify's own features must not react.
 *
 * <p><b>Muted (layer 3).</b> Every Ornithe {@code ModEvents} post goes through one helper that skips posting while
 * {@link #muted()}, every direct mixin hook into a feature returns early, and R1's recorder ignores everything. The
 * gate lives at the Ornithe posting sites, not in the shared {@code EventDispatcher}, so Lunar and Forge never see
 * it (nothing there enters it).
 *
 * <p><b>One lifetime rule for work that crosses a thread (layer 4).</b> The epoch grows by one at every entry and
 * every exit. Feature work that leaves the thread it started on carries a {@link Ticket} taken <i>where the work is
 * submitted</i>, and every effect re-checks it through one of exactly two calls:
 * <ul>
 *   <li>on the client thread, {@link #isCurrent} (through {@code FeatureTasks.schedule}): true only while unmuted, not
 *       entering, and in the ticket's epoch;</li>
 *   <li>on any other thread, {@link #tryBegin}: a {@link Permit}, decided under the gate's lock, or null while muted
 *       or entering, or when the ticket's epoch is old. A null ticket (a cache fill that is valid in any session) is
 *       decided by the muted/entering state alone. The holder ends the permit in {@code finally}.</li>
 * </ul>
 *
 * <p><b>Staged entry</b> (one step per frame, so the loading card keeps drawing): {@link #beginEntry} refuses new
 * permits and new client-thread effects; {@link #tryCompleteEntry} completes once no permit is running, or once the
 * drain bound ({@link #DRAIN_MS}) has passed, after marking the rest aborted and calling {@code disconnect()} on
 * their attached connections; completion mutes and advances the epoch. After entry completes no permit is running
 * and none can start. A holder re-checks {@link Permit#check()} before each step that can start a request, and maps
 * a failure of an aborted permit to {@link PlaybackMutedException} ({@link Permit#failure}).
 *
 * <p>Thread safety: state changes happen under the gate's lock; the flags and the epoch are volatile, so a change
 * made on one thread is seen by every other. Production code uses {@link #global()}; tests make their own instances.
 */
public final class PlaybackGate {

    /** How long entry waits for running permits before it aborts them. */
    public static final long DRAIN_MS = 3000L;

    /** A request or write refused or aborted by the gate. An {@code IOException}, so network callers see it. */
    public static final class PlaybackMutedException extends IOException {
        public PlaybackMutedException(String message) {
            super(message);
        }

        public PlaybackMutedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

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

    /** Admission for one effect off the client thread: a request (open to response read) or a write. */
    public static final class Permit {
        private final PlaybackGate gate;
        private volatile boolean aborted;
        private volatile HttpURLConnection connection;

        Permit(PlaybackGate gate) {
            this.gate = gate;
        }

        /** The connection to {@code disconnect()} if entry aborts this permit; disconnected at once if it already did. */
        public void attach(HttpURLConnection c) {
            connection = c;
            if (aborted) {
                disconnectQuietly(c);
            }
        }

        public boolean aborted() {
            return aborted;
        }

        /** Before each step that can start a request: throws once entry has aborted this permit. */
        public void check() throws PlaybackMutedException {
            if (aborted) {
                throw new PlaybackMutedException("aborted: a Rewind recording started playing");
            }
        }

        /** {@code e}, or a {@link PlaybackMutedException} caused by it when this permit was aborted. */
        public IOException failure(IOException e) {
            if (!aborted || e instanceof PlaybackMutedException) {
                return e;
            }
            return new PlaybackMutedException("aborted: a Rewind recording started playing", e);
        }

        /** Done (in {@code finally}). Idempotent; harmless after an abort. */
        public void end() {
            gate.ended(this);
        }

        private void abort() {
            aborted = true;
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

    /** {@code global().currentTicket()}: capture it where the work is submitted. */
    public static Ticket ticket() {
        return GLOBAL.currentTicket();
    }

    private final Object lock = new Object();
    private final long drainNanos;
    private final long drainMs;
    private final Set<Permit> running = new LinkedHashSet<Permit>();
    private volatile boolean muted;
    private volatile boolean entering;
    private volatile long epoch;
    private long entryStartNanos;

    public PlaybackGate() {
        this(DRAIN_MS);
    }

    /** A gate with its own drain bound (tests). */
    public PlaybackGate(long drainMs) {
        this.drainMs = drainMs;
        this.drainNanos = drainMs * 1_000_000L;
    }

    public long drainMs() {
        return drainMs;
    }

    public boolean isMuted() {
        return muted;
    }

    /** Entry has begun and not completed: new permits and client-thread effects are refused, events still flow. */
    public boolean isEntering() {
        return entering;
    }

    public long epoch() {
        return epoch;
    }

    /** A ticket for work submitted now. Taken while muted it is never current (and stale after exit). */
    public Ticket currentTicket() {
        return new Ticket(epoch);
    }

    /** True when the gate is unmuted, not entering, and still in {@code ticket}'s epoch; false for null. */
    public boolean isCurrent(Ticket ticket) {
        if (ticket == null) {
            return false;
        }
        synchronized (lock) {
            return !muted && !entering && ticket.epoch == epoch;
        }
    }

    /**
     * A permit for one effect off the client thread, or null: while muted or entering, or when {@code ticket} (if
     * not null) is from an older epoch.
     */
    public Permit tryBegin(Ticket ticket) {
        synchronized (lock) {
            if (muted || entering || ticket != null && ticket.epoch != epoch) {
                return null;
            }
            Permit p = new Permit(this);
            running.add(p);
            return p;
        }
    }

    /** {@code tryBegin(null)}, throwing {@link PlaybackMutedException} when refused (network clients). */
    public Permit admit() throws PlaybackMutedException {
        Permit p = tryBegin(null);
        if (p == null) {
            throw new PlaybackMutedException("refused: a Rewind recording is playing");
        }
        return p;
    }

    /** Entry, step 1: refuse new permits and client-thread effects from now on. A no-op while muted or entering. */
    public void beginEntry() {
        synchronized (lock) {
            if (muted || entering) {
                return;
            }
            entering = true;
            entryStartNanos = System.nanoTime();
        }
    }

    /**
     * Entry, a later step (call once per frame): true once entry is complete. It completes when no permit is
     * running, or once the drain bound has passed, after aborting the permits still running and disconnecting their
     * connections; completion mutes and advances the epoch. Without {@link #beginEntry} it returns whether the gate
     * is already muted.
     */
    public boolean tryCompleteEntry() {
        List<Permit> aborted = null;
        synchronized (lock) {
            if (!entering) {
                return muted;
            }
            if (!running.isEmpty()) {
                if (System.nanoTime() - entryStartNanos < drainNanos) {
                    return false;
                }
                aborted = new ArrayList<Permit>(running);
                for (Permit p : aborted) {
                    p.abort();
                }
                running.clear();
            }
            entering = false;
            muted = true;
            epoch++;
            lock.notifyAll();
        }
        if (aborted != null) {
            for (Permit p : aborted) {
                HttpURLConnection c = p.connection;
                if (c != null) {
                    disconnectQuietly(c);
                }
            }
        }
        return true;
    }

    /**
     * Playback starts, blocking: {@link #beginEntry}, then waits until entry completes (at most the drain bound) or
     * {@link #exit} cancels it. A no-op while already muted.
     */
    public void enter() {
        beginEntry();
        boolean interrupted = false;
        while (!tryCompleteEntry()) {
            synchronized (lock) {
                if (!entering) {
                    break; // cancelled by exit()
                }
                if (running.isEmpty()) {
                    continue;
                }
                long leftMs = (drainNanos - (System.nanoTime() - entryStartNanos)) / 1_000_000L;
                try {
                    lock.wait(Math.max(1L, leftMs));
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Playback ended (or entry was cancelled): unmute and advance the epoch, so tickets from before and during
     * playback are stale. Wakes {@link #awaitUnmuted}.
     */
    public void exit() {
        synchronized (lock) {
            entering = false;
            epoch++;
            muted = false;
            lock.notifyAll();
        }
    }

    /** Blocks while the gate is muted or entering (a dispatcher holding its queue until playback ends). */
    public void awaitUnmuted() throws InterruptedException {
        synchronized (lock) {
            while (muted || entering) {
                lock.wait();
            }
        }
    }

    private void ended(Permit p) {
        synchronized (lock) {
            if (running.remove(p)) {
                lock.notifyAll();
            }
        }
    }

    private static void disconnectQuietly(HttpURLConnection c) {
        try {
            c.disconnect();
        } catch (Throwable ignored) {
            // the holder sees its permit aborted at its next check
        }
    }
}
