package com.bedwarsqol.feature;

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

        public LiveContext(int sessionId, String serverKey, boolean activeGame, int partyEpoch) {
            this.sessionId = sessionId;
            this.serverKey = serverKey == null ? "" : serverKey;
            this.activeGame = activeGame;
            this.partyEpoch = partyEpoch;
        }
    }

    /** Result of deciding what the runtime should do for one tick / intercept. */
    public static final class Decision {
        public final OutgoingChatRequest send;
        public final OutgoingChatRequest notice;
        public final String noticeWhy;
        /** When true, {@link #send} bypasses pacing (slash commands). */
        public final boolean bypassPacing;

        public Decision(OutgoingChatRequest send, boolean bypassPacing,
                        OutgoingChatRequest notice, String noticeWhy) {
            this.send = send;
            this.bypassPacing = bypassPacing;
            this.notice = notice;
            this.noticeWhy = noticeWhy;
        }

        public static Decision send(OutgoingChatRequest req, boolean bypass) {
            return new Decision(req, bypass, null, null);
        }

        public static Decision notice(OutgoingChatRequest req, String why) {
            return new Decision(null, false, req, why);
        }

        public static Decision none() {
            return new Decision(null, false, null, null);
        }
    }

    private final OutgoingChatQueue queue = new OutgoingChatQueue();

    private long lastSendMs = Long.MIN_VALUE / 4;
    private long lastManualMs = Long.MIN_VALUE / 4;

    private SweatFlight sweatFlight = SweatFlight.IDLE;
    private boolean incInFlight;
    private long lastIncDeliveredMs = Long.MIN_VALUE / 4;

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
        incInFlight = false;
        lastIncDeliveredMs = Long.MIN_VALUE / 4;
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
     * Decide how to handle a player-originated send. Slash commands always bypass pacing.
     * Returns a send decision and/or a notice for a displaced prior manual.
     */
    public InterceptResult interceptPlayerSend(String message, LiveContext ctx, long nowMs) {
        if (message == null || message.isEmpty()) return new InterceptResult(false, Decision.none());
        onUserIntent(nowMs);

        if (isSlashCommand(message)) {
            OutgoingChatRequest req = newRequest(OutgoingChatKind.MANUAL, message, ctx, nowMs);
            return new InterceptResult(true, Decision.send(req, true));
        }

        if (OutgoingChatPolicy.canSend(OutgoingChatKind.MANUAL, nowMs, lastSendMs, lastManualMs, false)) {
            return new InterceptResult(true,
                    Decision.send(newRequest(OutgoingChatKind.MANUAL, message, ctx, nowMs), false));
        }

        Decision holdSide = holdManual(message, ctx, nowMs);
        return new InterceptResult(true, holdSide);
    }

    private Decision holdManual(String message, LiveContext ctx, long nowMs) {
        OutgoingChatRequest request = newRequest(OutgoingChatKind.MANUAL, message, ctx, nowMs);
        OutgoingChatQueue.Displacement d = queue.enqueue(request);
        cancelSweat(d.optional, SweatCancelReason.INTERRUPTED);
        if (d.priorSameSlot != null && d.priorSameSlot.kind == OutgoingChatKind.MANUAL) {
            OutgoingChatRequest older = d.priorSameSlot;
            if (OutgoingChatPolicy.canSend(OutgoingChatKind.MANUAL, nowMs, lastSendMs, lastManualMs, false)
                    && older.contextMatches(ctx.sessionId, ctx.serverKey, ctx.activeGame, ctx.partyEpoch)) {
                return Decision.send(older, false);
            }
            return Decision.notice(older, "superseded");
        }
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
        OutgoingChatQueue.Displacement d = queue.enqueue(request);
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
        if (sweatFlight == SweatFlight.IN_FLIGHT || sweatFlight == SweatFlight.DONE) return;
        submit(OutgoingChatKind.SWEAT, text, ctx, nowMs);
    }

    /** Consume RETRYABLE → IDLE so SweatReport may resubmit once. */
    public boolean consumeSweatRetry() {
        if (sweatFlight != SweatFlight.RETRYABLE) return false;
        sweatFlight = SweatFlight.IDLE;
        return true;
    }

    public void markSweatDone() {
        sweatFlight = SweatFlight.DONE;
    }

    public void resetSweatForNewGame() {
        queue.cancelKind(OutgoingChatKind.SWEAT);
        sweatFlight = SweatFlight.IDLE;
    }

    // ---- cancel / world -----------------------------------------------------

    public Decision cancelKind(OutgoingChatKind kind) {
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
        OutgoingChatRequest held = queue.peekManual();
        OutgoingChatRequest inc = queue.peekInc();
        OutgoingChatRequest auto = queue.peekAuto();
        queue.clear();
        if (inc != null) incInFlight = false;
        if (auto != null && auto.kind == OutgoingChatKind.SWEAT) {
            sweatFlight = SweatFlight.DONE;
        } else if (sweatFlight == SweatFlight.IN_FLIGHT || sweatFlight == SweatFlight.RETRYABLE) {
            sweatFlight = SweatFlight.DONE;
        }
        if (held != null) return Decision.notice(held, "world change");
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

        if (next.kind == OutgoingChatKind.MANUAL
                && OutgoingChatPolicy.manualHoldExpired(next.enqueuedAtMs, nowMs)) {
            OutgoingChatRequest dropped = queue.pollNext();
            return Decision.notice(dropped, "timed out");
        }

        if (!OutgoingChatPolicy.canSend(next.kind, nowMs, lastSendMs, lastManualMs, false)) {
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
     * Party mismatch is distinct so Sweat is finalized (never RETRYABLE).
     */
    static SweatCancelReason classifyStale(OutgoingChatRequest request, LiveContext ctx) {
        if (request == null || ctx == null) return SweatCancelReason.CONTEXT_STALE;
        if (request.sessionId != ctx.sessionId) return SweatCancelReason.CONTEXT_STALE;
        String live = ctx.serverKey == null ? "" : ctx.serverKey;
        if (!request.serverKey.equals(live)) return SweatCancelReason.CONTEXT_STALE;
        if (request.requireActiveGame && !ctx.activeGame) return SweatCancelReason.GAME_STALE;
        if (request.requireParty && request.partyEpoch != ctx.partyEpoch) {
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
            sweatFlight = SweatFlight.DONE;
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
        sweatFlight = isRetryable(reason) ? SweatFlight.RETRYABLE : SweatFlight.DONE;
    }

    /** Only safe interruptions keep Sweat retryable; party/context ends are final. */
    static boolean isRetryable(SweatCancelReason reason) {
        return reason == SweatCancelReason.INTERRUPTED || reason == SweatCancelReason.REPLACED;
    }

    public interface FeatureGate {
        boolean enabled(OutgoingChatKind kind);
    }
}
