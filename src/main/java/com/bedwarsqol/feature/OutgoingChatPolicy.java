package com.bedwarsqol.feature;

/**
 * Pure pacing rules for the shared outgoing-chat gatekeeper. Tuned conservatively for Hypixel: one
 * global gap between managed sends, plus a quiet window after player-written chat before optional
 * automation may resume. The continuation lines of one already-started report keep SweatReport's
 * original ~0.5s cadence (see {@link #SWEAT_LINE_GAP_MS}).
 *
 * <p>Slash commands may bypass the gap via {@code bypassPacing} but still update send clocks so
 * automation waits afterward.
 */
public final class OutgoingChatPolicy {

    /** Minimum gap between any two managed sends (manual, INC, or automation). */
    public static final long MIN_GAP_MS = 2500L;

    /**
     * Gap between the lines of one report that is already underway — the cadence SweatReport used
     * before it was routed through here. Only the report's first line pays {@link #MIN_GAP_MS};
     * once Hypixel has accepted it, the rest follow at the old speed.
     */
    public static final long SWEAT_LINE_GAP_MS = 500L;

    /**
     * Extra quiet time after a player-written message before optional AUTO traffic may send.
     * INC (user-triggered) only needs {@link #MIN_GAP_MS}.
     */
    public static final long QUIET_AFTER_MANUAL_MS = 2000L;

    /**
     * How long a held manual message may wait for pacing before we surface a local notice instead
     * of sending into a changed context. Never silently drop held text.
     */
    public static final long MANUAL_HOLD_MAX_MS = 12_000L;

    private OutgoingChatPolicy() {}

    /**
     * Whether {@code kind} may send at {@code nowMs}. When {@code bypassPacing} is true (typed slash
     * commands), the gap/quiet checks are skipped.
     */
    public static boolean canSend(OutgoingChatKind kind, long nowMs, long lastSendMs, long lastManualMs,
                                  boolean bypassPacing) {
        return canSend(kind, nowMs, lastSendMs, lastManualMs, bypassPacing, MIN_GAP_MS);
    }

    /** As {@link #canSend}, with an explicit gap (see {@link #SWEAT_LINE_GAP_MS}). */
    public static boolean canSend(OutgoingChatKind kind, long nowMs, long lastSendMs, long lastManualMs,
                                  boolean bypassPacing, long minGapMs) {
        if (kind == null) return false;
        if (bypassPacing) return true;
        if (nowMs - lastSendMs < minGapMs) return false;
        if (kind.isOptionalAutomation() && nowMs - lastManualMs < QUIET_AFTER_MANUAL_MS) return false;
        return true;
    }

    /** Earliest time {@code kind} becomes eligible (may be {@code nowMs} if already eligible). */
    public static long nextEligibleMs(OutgoingChatKind kind, long nowMs, long lastSendMs, long lastManualMs) {
        long gapReady = lastSendMs + MIN_GAP_MS;
        long ready = Math.max(nowMs, gapReady);
        if (kind != null && kind.isOptionalAutomation()) {
            ready = Math.max(ready, lastManualMs + QUIET_AFTER_MANUAL_MS);
        }
        return ready;
    }

    public static boolean manualHoldExpired(long enqueuedAtMs, long nowMs) {
        return nowMs - enqueuedAtMs >= MANUAL_HOLD_MAX_MS;
    }
}
