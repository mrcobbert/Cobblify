package com.bedwarsqol.gui;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps at most one live resolve attempt per key, and refuses a key that just failed for a while.
 * Built for the Players hero card, whose draw calls {@code face(name)} every frame: without a gate,
 * every frame starts another lookup thread.
 *
 * <p>Ticketed on purpose. {@link #tryStart} hands out a fresh ticket and the attempt owns it;
 * {@link #succeed} and {@link #fail} act only when that ticket is still the key's current attempt.
 * An attempt whose callback never fires stops being live once {@code inflightTtlMs} has passed, so
 * the next caller can start a new one — and when the abandoned attempt finally reports in, its
 * ticket is stale and the gate ignores it, so a late reply can neither settle nor cancel the
 * attempt that replaced it. Keys are used verbatim (the caller lower-cases names).
 *
 * <p>Platform-neutral and clock-free: every method takes the caller's {@code nowMs}, which is what
 * makes the whole lifecycle testable.
 */
public final class ResolveGate {

    /** How long an unsettled attempt counts as live before a new one may start. */
    private final long inflightTtlMs;
    /** How long a failed key is left alone before another attempt is allowed. */
    private final long failureTtlMs;

    private final Map<String, Attempt> attempts = new HashMap<String, Attempt>();
    private final Map<String, Long> failures = new HashMap<String, Long>();
    private long nextTicket;

    public ResolveGate(long inflightTtlMs, long failureTtlMs) {
        this.inflightTtlMs = inflightTtlMs;
        this.failureTtlMs = failureTtlMs;
    }

    /**
     * Claim the key: a fresh ticket (never negative, never repeated) when the caller may resolve
     * now, or -1 while another attempt is live or the key's failure window is still open.
     */
    public synchronized long tryStart(String key, long nowMs) {
        Attempt live = attempts.get(key);
        if (live != null && nowMs - live.startedAt < inflightTtlMs) return -1L;
        Long failedAt = failures.get(key);
        if (failedAt != null && nowMs - failedAt < failureTtlMs) return -1L;
        failures.remove(key);
        long ticket = nextTicket++;
        attempts.put(key, new Attempt(ticket, nowMs));
        return ticket;
    }

    /** Settle the attempt: the key is free again (callers serve later frames from their cache). */
    public synchronized void succeed(String key, long ticket) {
        Attempt live = attempts.get(key);
        if (live == null || live.ticket != ticket) return; // superseded or already settled
        attempts.remove(key);
    }

    /** Settle the attempt as a failure and start the key's failure window at {@code nowMs}. */
    public synchronized void fail(String key, long ticket, long nowMs) {
        Attempt live = attempts.get(key);
        if (live == null || live.ticket != ticket) return; // superseded: it opens no window
        attempts.remove(key);
        failures.put(key, nowMs);
    }

    /** Whether an attempt is running for {@code key} and has not yet settled or timed out. */
    public synchronized boolean isLive(String key, long nowMs) {
        Attempt live = attempts.get(key);
        return live != null && nowMs - live.startedAt < inflightTtlMs;
    }

    private static final class Attempt {
        final long ticket;
        final long startedAt;

        Attempt(long ticket, long startedAt) {
            this.ticket = ticket;
            this.startedAt = startedAt;
        }
    }
}
