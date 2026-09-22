package com.bedwarsqol.feature;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;

/**
 * Pure outgoing-chat coordinator state: queue, pacing clocks, sweat/inc flight acknowledgements,
 * and context validity. No Minecraft types — unit-tested directly; {@link OutgoingChat} is the
 * thin runtime wrapper.
 */
public final class OutgoingChatCore {

    public enum SweatFlight {
        /** No report in flight; SweatReport may submit. */
        IDLE,
        /** Queued or about to send. */
        IN_FLIGHT,
        /**
         * Cancelled by a safe interruption (typed chat / INC / replaced by AutoGG) while the
         * original party context was still valid. May resubmit once in the same game.
         */
        RETRYABLE,
        /** Delivered or finally abandoned for this game (including party membership end). */
        DONE
    }

    /**
     * Why a pending Sweat line was cancelled. Only {@link #INTERRUPTED} may leave the flight
     * {@link SweatFlight#RETRYABLE}; party/session/server/game/feature/world ends are final.
     */
    public enum SweatCancelReason {
        /** User intent or higher-priority traffic displaced optional automation. */
        INTERRUPTED,
        /** Replaced in the AUTO slot by another optional line (e.g. AutoGG). */
        REPLACED,
        /** Party epoch changed (leave / kick / disband) — must not /pc under a new party. */
        PARTY_CHANGED,
        /** Session or server identity no longer matches. */
        CONTEXT_STALE,
        /** Active Bedwars game requirement failed. */
        GAME_STALE,
        /** Feature toggle disabled. */
        FEATURE_DISABLED,
        /** Explicit cancel or world invalidate. */
        FINAL
    }

    /** Live snapshot used for context checks (provided by the runtime each tick). */
    public static final class LiveContext {
        public final int sessionId;
        public final String serverKey;
        public final boolean activeGame;
        public final int partyEpoch;
        /** False only once Hypixel has told us we are partyless; unknown counts as in a party. */
        public final boolean inParty;
        /**
         * Whether the client is on Hypixel. The pacer exists for Hypixel's chat limits; anywhere
         * else (singleplayer, other servers) typed chat is not intercepted at all.
         */
        public final boolean onHypixel;

        public LiveContext(int sessionId, String serverKey, boolean activeGame, int partyEpoch) {
            this(sessionId, serverKey, activeGame, partyEpoch, true, true);
        }

        public LiveContext(int sessionId, String serverKey, boolean activeGame, int partyEpoch,
                           boolean inParty) {
            this(sessionId, serverKey, activeGame, partyEpoch, inParty, true);
        }

        public LiveContext(int sessionId, String serverKey, boolean activeGame, int partyEpoch,
                           boolean inParty, boolean onHypixel) {
            this.sessionId = sessionId;
            this.serverKey = serverKey == null ? "" : serverKey;
            this.activeGame = activeGame;
            this.partyEpoch = partyEpoch;
            this.inParty = inParty;
            this.onHypixel = onHypixel;
        }
    }

    /** Result of deciding what the runtime should do for one tick / intercept. */
    public static final class Decision {
        public final OutgoingChatRequest send;
        /** Held lines that will not be sent and must be reported to the player; never null. */
        public final List<OutgoingChatRequest> notices;
        /** The first of {@link #notices}, or null — kept for callers that report one at a time. */
        public final OutgoingChatRequest notice;
        public final String noticeWhy;
        /** When true, {@link #send} bypasses pacing (slash commands). */
        public final boolean bypassPacing;

        public Decision(OutgoingChatRequest send, boolean bypassPacing,
                        OutgoingChatRequest notice, String noticeWhy) {
            this(send, bypassPacing,
                    notice == null ? Collections.<OutgoingChatRequest>emptyList()
                            : Collections.singletonList(notice), noticeWhy);
        }

        public Decision(OutgoingChatRequest send, boolean bypassPacing,
                        List<OutgoingChatRequest> notices, String noticeWhy) {
            this.send = send;
            this.bypassPacing = bypassPacing;
            this.notices = notices == null ? Collections.<OutgoingChatRequest>emptyList()
                    : Collections.unmodifiableList(notices);
            this.notice = this.notices.isEmpty() ? null : this.notices.get(0);
            this.noticeWhy = noticeWhy;
        }

        public static Decision send(OutgoingChatRequest req, boolean bypass) {
            return new Decision(req, bypass, (OutgoingChatRequest) null, null);
        }

        public static Decision notice(OutgoingChatRequest req, String why) {
            return new Decision(null, false, req, why);
        }

        public static Decision notices(List<OutgoingChatRequest> reqs, String why) {
            return new Decision(null, false, reqs, why);
        }

        public static Decision none() {
            return new Decision(null, false, (OutgoingChatRequest) null, null);
        }
    }

    private final OutgoingChatQueue queue = new OutgoingChatQueue();

    private long lastSendMs = Long.MIN_VALUE / 4;
    private long lastManualMs = Long.MIN_VALUE / 4;

    private SweatFlight sweatFlight = SweatFlight.IDLE;
    /** Undelivered tail of the current sweat batch; the head lives in the queue. */
    private final ArrayDeque<String> sweatRemaining = new ArrayDeque<String>();
    /** At least one line of this batch reached the wire — a retry must resume, never rebuild. */
    private boolean sweatPartlySent;
    /** Most recent sweat request, reused as the context template for follow-up lines. */
    private OutgoingChatRequest sweatHead;
    /**
     * An AutoGG a report line pushed out of the AUTO slot. It waits here until the report leaves
     * that slot, so a late report costs the gg its turn in the queue but never the send itself.
     */
    private OutgoingChatRequest deferredAutoGg;
    private boolean incInFlight;
    private long lastIncDeliveredMs = Long.MIN_VALUE / 4;
    /** The held typed line currently at the head of the FIFO, and when it got there (hold timeout base). */
    private OutgoingChatRequest manualHead;
    private long manualHeadSinceMs;

    public OutgoingChatQueue queue() { return queue; }
    public long lastSendMs() { return lastSendMs; }
    public long lastManualMs() { return lastManualMs; }
    public SweatFlight sweatFlight() { return sweatFlight; }
    public boolean isIncInFlight() { return incInFlight; }
    public long lastIncDeliveredMs() { return lastIncDeliveredMs; }

    public void reset() {
        queue.clear();
        lastSendMs = Long.MIN_VALUE / 4;
        lastManualMs = Long.MIN_VALUE / 4;
        sweatFlight = SweatFlight.IDLE;
        clearSweatBatch();
        incInFlight = false;
        lastIncDeliveredMs = Long.MIN_VALUE / 4;
        manualHead = null;
        manualHeadSinceMs = 0L;
        deferredAutoGg = null;
    }

    public static boolean isSlashCommand(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == '/';
    }

    /** Party epoch applies only to party-chat targets ({@code /pc} INC and SweatReport). */
    public static boolean requiresParty(OutgoingChatKind kind) {
        return kind == OutgoingChatKind.INC || kind == OutgoingChatKind.SWEAT;
    }

    public OutgoingChatRequest newRequest(OutgoingChatKind kind, String text, LiveContext ctx, long nowMs) {
        return new OutgoingChatRequest(
                kind, text, ctx.sessionId, ctx.serverKey,
                kind == OutgoingChatKind.SWEAT, // require active game
                requiresParty(kind),
                ctx.partyEpoch, nowMs);
    }

    // ---- user intent / intercept --------------------------------------------

    /**
     * Player submitted from chat. Cancels optional automation and opens the quiet window.
     * Does not enqueue or send.
     */
    public void onUserIntent(long nowMs) {
        OutgoingChatRequest dropped = queue.cancelOptional();
        cancelSweat(dropped, SweatCancelReason.INTERRUPTED);
        lastManualMs = nowMs;
    }

    /**
     * Party membership ended. Any in-flight or retryable Sweat for the prior party is finalized
     * so it cannot be resent under a new epoch.
     */
    public void onPartyEpochAdvanced() {
        OutgoingChatRequest auto = queue.peekAuto();
        if (auto != null && auto.kind == OutgoingChatKind.SWEAT) {
            queue.cancelOptional();
            cancelSweat(auto, SweatCancelReason.PARTY_CHANGED);
        } else if (sweatFlight == SweatFlight.IN_FLIGHT || sweatFlight == SweatFlight.RETRYABLE) {
            sweatFlight = SweatFlight.DONE;
            clearSweatBatch();
        }
    }

    /** Outcome of intercepting a player-originated {@code sendChatMessage}. */
    public static final class InterceptResult {
        /** When true, the original {@code sendChatMessage} must be cancelled. */
        public final boolean handled;
        public final Decision decision;

        public InterceptResult(boolean handled, Decision decision) {
            this.handled = handled;
            this.decision = decision == null ? Decision.none() : decision;
        }
    }

    /**
     * Decide how to handle a player-originated send. Off Hypixel nothing is intercepted: the line
     * is not handled and vanilla sends it. On Hypixel slash commands always bypass pacing; a typed
     * line goes out now when the gap is open and nothing older is held, otherwise it joins the back
     * of the held FIFO (and, if the gap is open, the oldest held line goes out in its place, so
     * wire order is always typing order). A typed line is never dropped: the only refusal is the
     * FIFO cap, and that comes back as a notice.
     */
    public InterceptResult interceptPlayerSend(String message, LiveContext ctx, long nowMs) {
        if (message == null || message.isEmpty()) return new InterceptResult(false, Decision.none());
        onUserIntent(nowMs);
        if (ctx == null || !ctx.onHypixel) return new InterceptResult(false, Decision.none());

        if (isSlashCommand(message)) {
            OutgoingChatRequest req = newRequest(OutgoingChatKind.MANUAL, message, ctx, nowMs);
            return new InterceptResult(true, Decision.send(req, true));
        }

        boolean gapOpen = OutgoingChatPolicy.canSend(OutgoingChatKind.MANUAL, nowMs, lastSendMs, lastManualMs, false);
        if (queue.heldManualCount() == 0 && gapOpen) {
            return new InterceptResult(true,
                    Decision.send(newRequest(OutgoingChatKind.MANUAL, message, ctx, nowMs), false));
        }

        Decision held = holdManual(message, ctx, nowMs);
        if (held.notice != null) return new InterceptResult(true, held);
        if (gapOpen) {
            // Older lines are waiting: the oldest goes now, the new one keeps its place in line.
            // Through the same tick as the runtime flush, so a head whose context went stale or
            // whose hold expired is noticed, never sent (code review G, I1).
            return new InterceptResult(true, tick(ctx, nowMs, false, ALL_ENABLED));
        }
        return new InterceptResult(true, held);
    }

    /** Feature toggles are the runtime's to apply on its next flush; the intercept sends MANUAL only. */
    private static final FeatureGate ALL_ENABLED = new FeatureGate() {
        public boolean enabled(OutgoingChatKind kind) { return true; }
    };

    /** Append a typed line to the held FIFO. Only the cap can refuse it, and that is reported. */
    private Decision holdManual(String message, LiveContext ctx, long nowMs) {
        OutgoingChatRequest request = newRequest(OutgoingChatKind.MANUAL, message, ctx, nowMs);
        OutgoingChatQueue.Displacement d = queue.enqueue(request);
        cancelSweat(d.optional, SweatCancelReason.INTERRUPTED);
        if (d.rejected != null) return Decision.notice(d.rejected, "too many held");
        return Decision.none(); // held silently as next priority
    }

    // ---- feature submit -----------------------------------------------------

    public boolean canSubmitInc(long nowMs, long cooldownMs) {
        if (incInFlight) return false;
        return nowMs - lastIncDeliveredMs >= cooldownMs;
    }

    public void submit(OutgoingChatKind kind, String text, LiveContext ctx, long nowMs) {
        if (text == null || text.isEmpty() || kind == null) return;
        OutgoingChatRequest request = newRequest(kind, text, ctx, nowMs);
        // A newer gg supersedes one still waiting behind a report: only one "gg" per game.
        if (kind == OutgoingChatKind.AUTOGG) deferredAutoGg = null;
        OutgoingChatQueue.Displacement d = kind == OutgoingChatKind.SWEAT
                ? enqueueSweat(request)
                : queue.enqueue(request);
        // MANUAL/INC enqueue clears AUTO as an interrupt.
        if (d.optional != null) {
            if (d.optional.kind == OutgoingChatKind.SWEAT) {
                cancelSweat(d.optional, SweatCancelReason.INTERRUPTED);
            } else if (d.optional.kind == OutgoingChatKind.INC) {
                incInFlight = false;
            }
        }
        if (d.priorSameSlot != null) {
            if (d.priorSameSlot.kind == OutgoingChatKind.SWEAT) {
                cancelSweat(d.priorSameSlot, SweatCancelReason.REPLACED);
            } else if (d.priorSameSlot.kind == OutgoingChatKind.INC) {
                // Replaced queued INC — do not burn cooldown.
                incInFlight = (kind == OutgoingChatKind.INC);
            }
        }
        if (kind == OutgoingChatKind.SWEAT) {
            sweatFlight = SweatFlight.IN_FLIGHT;
            sweatHead = request;
        } else if (kind == OutgoingChatKind.INC) {
            incInFlight = true;
        }
    }

    public boolean submitInc(String text, LiveContext ctx, long nowMs, long cooldownMs) {
        if (!canSubmitInc(nowMs, cooldownMs)) return false;
        submit(OutgoingChatKind.INC, text, ctx, nowMs);
        return true;
    }

    public void submitSweat(String text, LiveContext ctx, long nowMs) {
        submitSweat(Collections.singletonList(text), ctx, nowMs);
    }

    /**
     * Submit a whole report. Only the first line enters the queue; each following line is enqueued
     * once its predecessor is acknowledged, so the batch is paced like any other managed traffic.
     */
    public void submitSweat(List<String> lines, LiveContext ctx, long nowMs) {
        if (lines == null || lines.isEmpty()) return;
        if (sweatFlight == SweatFlight.IN_FLIGHT || sweatFlight == SweatFlight.DONE) return;
        clearSweatBatch();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line != null && !line.isEmpty()) sweatRemaining.addLast(line);
        }
        submit(OutgoingChatKind.SWEAT, lines.get(0), ctx, nowMs);
    }

    /**
     * Consume RETRYABLE. A batch that never reached the wire goes IDLE so SweatReport rebuilds it
     * with fresher stats; a partly-sent batch resumes its remaining lines instead, so no team is
     * ever reported twice.
     */
    public boolean consumeSweatRetry(long nowMs) {
        if (sweatFlight != SweatFlight.RETRYABLE) return false;
        if (sweatPartlySent) {
            if (!enqueueNextSweat(nowMs)) sweatFlight = SweatFlight.DONE;
            return true;
        }
        clearSweatBatch();
        sweatFlight = SweatFlight.IDLE;
        return true;
    }

    public void markSweatDone() {
        sweatFlight = SweatFlight.DONE;
        clearSweatBatch();
        releaseDeferredAutoGg();
    }

    public void resetSweatForNewGame() {
        queue.cancelKind(OutgoingChatKind.SWEAT);
        sweatFlight = SweatFlight.IDLE;
        clearSweatBatch();
        // A new game means last game's gg is void, so it is dropped here rather than released.
        deferredAutoGg = null;
    }

    /** Move the next batch line into the queue under the head's context. False when none remain. */
    private boolean enqueueNextSweat(long nowMs) {
        if (sweatRemaining.isEmpty() || sweatHead == null) return false;
        OutgoingChatRequest next = new OutgoingChatRequest(
                OutgoingChatKind.SWEAT, sweatRemaining.pollFirst(),
                sweatHead.sessionId, sweatHead.serverKey, true, true, sweatHead.partyEpoch, nowMs);
        enqueueSweat(next);
        sweatHead = next;
        sweatFlight = SweatFlight.IN_FLIGHT;
        return true;
    }

    /**
     * The one way a report line enters the queue. A report takes the AUTO slot from a queued
     * AutoGG; remembering it here is what keeps that gg from being dropped silently.
     */
    private OutgoingChatQueue.Displacement enqueueSweat(OutgoingChatRequest request) {
        OutgoingChatQueue.Displacement d = queue.enqueue(request);
        if (d.priorSameSlot != null && d.priorSameSlot.kind == OutgoingChatKind.AUTOGG) {
            deferredAutoGg = d.priorSameSlot;
        }
        return d;
    }

    /**
     * Put a report-displaced AutoGG back once the AUTO slot is free. It re-enters as an ordinary
     * AUTO line, so pacing, the quiet window and the staleness checks all still apply to it.
     */
    private void releaseDeferredAutoGg() {
        if (deferredAutoGg == null || queue.peekAuto() != null) return;
        OutgoingChatRequest gg = deferredAutoGg;
        deferredAutoGg = null;
        queue.enqueue(gg);
    }

    private void clearSweatBatch() {
        sweatRemaining.clear();
        sweatPartlySent = false;
        sweatHead = null;
    }

    // ---- cancel / world -----------------------------------------------------

    public Decision cancelKind(OutgoingChatKind kind) {
        // Cancelling AutoGG must reach a deferred gg as well, queued or not.
        if (kind == OutgoingChatKind.AUTOGG) deferredAutoGg = null;
        OutgoingChatRequest dropped = queue.cancelKind(kind);
        if (dropped == null) return Decision.none();
        if (dropped.kind == OutgoingChatKind.SWEAT) {
            cancelSweat(dropped, SweatCancelReason.FINAL);
        } else if (dropped.kind == OutgoingChatKind.INC) {
            incInFlight = false;
        } else if (dropped.kind == OutgoingChatKind.MANUAL) {
            return Decision.notice(dropped, "cancelled");
        }
        return Decision.none();
    }

    public Decision invalidateAll() {
        List<OutgoingChatRequest> held = queue.drainManuals();
        OutgoingChatRequest inc = queue.peekInc();
        OutgoingChatRequest auto = queue.peekAuto();
        queue.clear();
        manualHead = null;
        deferredAutoGg = null;
        if (inc != null) incInFlight = false;
        if (auto != null && auto.kind == OutgoingChatKind.SWEAT) {
            sweatFlight = SweatFlight.DONE;
            clearSweatBatch();
        } else if (sweatFlight == SweatFlight.IN_FLIGHT || sweatFlight == SweatFlight.RETRYABLE) {
            sweatFlight = SweatFlight.DONE;
            clearSweatBatch();
        }
        if (!held.isEmpty()) return Decision.notices(held, "world change");
        return Decision.none();
    }

    // ---- tick ---------------------------------------------------------------

    /**
     * Advance the coordinator. {@code featureOn} reports whether each kind's toggle is enabled.
     * {@code chatOpen} pauses optional automation only.
     */
    public Decision tick(LiveContext ctx, long nowMs, boolean chatOpen, FeatureGate featureOn) {
        Decision purged = purgeStale(ctx, featureOn);
        if (purged.notice != null) return purged;
        if (queue.isEmpty()) return Decision.none();

        OutgoingChatRequest next = queue.peekNext();
        if (next == null) return Decision.none();

        if (next.kind.isOptionalAutomation() && chatOpen) return Decision.none();

        if (!featureOn.enabled(next.kind)) {
            OutgoingChatRequest dropped = queue.cancelKind(next.kind);
            applyCancel(dropped, SweatCancelReason.FEATURE_DISABLED);
            if (dropped != null && dropped.kind == OutgoingChatKind.MANUAL) {
                return Decision.notice(dropped, "unavailable");
            }
            return Decision.none();
        }

        SweatCancelReason stale = classifyStale(next, ctx);
        if (stale != null) {
            OutgoingChatRequest dropped = queue.pollNext();
            applyCancel(dropped, stale);
            if (dropped != null && dropped.kind == OutgoingChatKind.MANUAL) {
                return Decision.notice(dropped, "context changed");
            }
            return Decision.none();
        }

        if (next.kind == OutgoingChatKind.MANUAL) {
            if (next != manualHead) {
                // The hold timeout counts from when a line reaches the head of the FIFO: a line
                // behind others has not been waiting on pacing yet, it has been waiting on them.
                manualHead = next;
                manualHeadSinceMs = nowMs;
            }
            if (OutgoingChatPolicy.manualHoldExpired(manualHeadSinceMs, nowMs)) {
                OutgoingChatRequest dropped = queue.pollNext();
                manualHead = null;
                return Decision.notice(dropped, "timed out");
            }
        }

        // A report already underway keeps its lines at the old ~0.5s cadence; everything else
        // (including the report's own first line) pays the full gap.
        long gap = next.kind == OutgoingChatKind.SWEAT && sweatPartlySent
                ? OutgoingChatPolicy.SWEAT_LINE_GAP_MS
                : OutgoingChatPolicy.MIN_GAP_MS;
        if (!OutgoingChatPolicy.canSend(next.kind, nowMs, lastSendMs, lastManualMs, false, gap)) {
            return Decision.none();
        }

        OutgoingChatRequest send = queue.pollNext();
        return Decision.send(send, false);
    }

    private Decision purgeStale(LiveContext ctx, FeatureGate featureOn) {
        OutgoingChatRequest auto = queue.peekAuto();
        if (auto != null) {
            SweatCancelReason reason = null;
            if (!featureOn.enabled(auto.kind)) {
                reason = SweatCancelReason.FEATURE_DISABLED;
            } else {
                reason = classifyStale(auto, ctx);
            }
            if (reason != null) {
                OutgoingChatRequest dropped = queue.cancelOptional();
                applyCancel(dropped, reason);
            }
        }
        OutgoingChatRequest manual = queue.peekManual();
        if (manual != null && classifyStale(manual, ctx) != null) {
            OutgoingChatRequest dropped = queue.cancelKind(OutgoingChatKind.MANUAL);
            return Decision.notice(dropped, "context changed");
        }
        OutgoingChatRequest inc = queue.peekInc();
        if (inc != null && (!featureOn.enabled(OutgoingChatKind.INC)
                || classifyStale(inc, ctx) != null)) {
            queue.cancelKind(OutgoingChatKind.INC);
            incInFlight = false;
        }
        return Decision.none();
    }

    /**
     * Returns a non-null cancel reason when {@code request} no longer matches {@code ctx}.
     * Party mismatch — a changed epoch, or knowing we are partyless — is distinct so Sweat is
     * finalized (never RETRYABLE). Public chat and AutoGG are unaffected.
     */
    static SweatCancelReason classifyStale(OutgoingChatRequest request, LiveContext ctx) {
        if (request == null || ctx == null) return SweatCancelReason.CONTEXT_STALE;
        if (request.sessionId != ctx.sessionId) return SweatCancelReason.CONTEXT_STALE;
        String live = ctx.serverKey == null ? "" : ctx.serverKey;
        if (!request.serverKey.equals(live)) return SweatCancelReason.CONTEXT_STALE;
        if (request.requireActiveGame && !ctx.activeGame) return SweatCancelReason.GAME_STALE;
        if (request.requireParty && (request.partyEpoch != ctx.partyEpoch || !ctx.inParty)) {
            return SweatCancelReason.PARTY_CHANGED;
        }
        return null;
    }

    /** Record that {@code request} was actually sent on the wire. */
    public void acknowledgeDelivered(OutgoingChatRequest request, long nowMs) {
        if (request == null) return;
        lastSendMs = nowMs;
        if (request.kind == OutgoingChatKind.MANUAL) {
            lastManualMs = nowMs;
        }
        if (request.kind == OutgoingChatKind.SWEAT) {
            sweatPartlySent = true;
            if (!enqueueNextSweat(nowMs)) {
                sweatFlight = SweatFlight.DONE;
                clearSweatBatch();
                releaseDeferredAutoGg();
            }
        }
        if (request.kind == OutgoingChatKind.INC) {
            incInFlight = false;
            lastIncDeliveredMs = nowMs;
        }
    }

    private void applyCancel(OutgoingChatRequest dropped, SweatCancelReason reason) {
        if (dropped == null) return;
        if (dropped.kind == OutgoingChatKind.SWEAT) {
            cancelSweat(dropped, reason);
        } else if (dropped.kind == OutgoingChatKind.INC) {
            incInFlight = false;
        }
    }

    private void cancelSweat(OutgoingChatRequest dropped, SweatCancelReason reason) {
        if (dropped == null || dropped.kind != OutgoingChatKind.SWEAT) return;
        if (isRetryable(reason)) {
            sweatFlight = SweatFlight.RETRYABLE;
            // Put the cancelled line back only when earlier lines are already out; otherwise the
            // whole report is rebuilt on retry and keeping the old text would duplicate it.
            if (sweatPartlySent) sweatRemaining.addFirst(dropped.text);
            else clearSweatBatch();
        } else {
            sweatFlight = SweatFlight.DONE;
            clearSweatBatch();
        }
        // Either way the report has left the AUTO slot, so the gg it displaced may have it back.
        releaseDeferredAutoGg();
    }

    /** Only safe interruptions keep Sweat retryable; party/context ends are final. */
    static boolean isRetryable(SweatCancelReason reason) {
        return reason == SweatCancelReason.INTERRUPTED || reason == SweatCancelReason.REPLACED;
    }

    public interface FeatureGate {
        boolean enabled(OutgoingChatKind kind);
    }
}
