package com.bedwarsqol.stats;

/**
 * Global, thread-safe pacing for stats-backend requests.
 *
 * Enforces a conservative minimum spacing between outgoing requests so we never
 * hammer the Cloudflare Worker backend. A pause hook ({@link #pauseRemainingMs()})
 * is retained so a future back-off path can be wired in, but nothing sets a pause today.
 */
public final class RateLimiter {

    /** Minimum gap between requests. */
    private static final long MIN_SPACING_MS = 1300L;

    private final Object lock = new Object();

    private long lastRequestAt = 0L;
    private long pausedUntilMs = 0L;

    public RateLimiter() {}

    /** Whether a request may be attempted right now (not currently paused). */
    public boolean canRequest() {
        synchronized (lock) {
            return System.currentTimeMillis() >= pausedUntilMs;
        }
    }

    /** Milliseconds remaining on the current pause, or 0 if not paused. */
    public long pauseRemainingMs() {
        synchronized (lock) {
            long diff = pausedUntilMs - System.currentTimeMillis();
            return Math.max(0L, diff);
        }
    }

    /**
     * Blocks the calling thread until it is safe to send the next request.
     * Always returns {@code true} once a slot is available.
     */
    public boolean awaitSlot() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                long now = System.currentTimeMillis();

                if (now < pausedUntilMs) {
                    lock.wait(pausedUntilMs - now);
                    continue;
                }

                long wait = (lastRequestAt + MIN_SPACING_MS) - now;
                if (wait > 0) {
                    lock.wait(wait);
                    continue;
                }

                lastRequestAt = System.currentTimeMillis();
                return true;
            }
        }
    }

    /**
     * Bounded variant of {@link #awaitSlot()}: waits at most {@code maxWaitMs} for a slot, measured
     * against a deadline fixed on entry. Returns {@code true} once a slot is claimed, or {@code false}
     * when the deadline passes first — in which case no slot is claimed and nothing may be sent.
     */
    public boolean awaitSlot(long maxWaitMs) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + Math.max(0L, maxWaitMs);
        synchronized (lock) {
            while (true) {
                long now = System.currentTimeMillis();

                // Expiry is decided before the gate, on every wakeup: a slot that only comes free at
                // or after the deadline must be refused without claiming and without moving
                // lastRequestAt, because the caller may no longer send anything.
                long allowance = deadline - now;
                if (allowance <= 0) return false;

                long wait = now < pausedUntilMs
                        ? pausedUntilMs - now
                        : (lastRequestAt + MIN_SPACING_MS) - now;
                if (wait <= 0) {
                    lastRequestAt = System.currentTimeMillis();
                    return true;
                }

                lock.wait(Math.min(wait, allowance));
            }
        }
    }
}
