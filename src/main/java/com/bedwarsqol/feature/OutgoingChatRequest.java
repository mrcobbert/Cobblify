package com.bedwarsqol.feature;

/**
 * One pending outgoing line for {@link OutgoingChatQueue}. Pure data — validity predicates and
 * Minecraft sends live in the runtime coordinator.
 */
public final class OutgoingChatRequest {

    public final OutgoingChatKind kind;
    public final String text;
    public final int sessionId;
    public final String serverKey;
    public final boolean requireActiveGame;
    /** When true, {@link #partyEpoch} must still match (party-targeted {@code /pc} only). */
    public final boolean requireParty;
    public final int partyEpoch;
    public final long enqueuedAtMs;

    public OutgoingChatRequest(OutgoingChatKind kind, String text, int sessionId, String serverKey,
                               boolean requireActiveGame, boolean requireParty, int partyEpoch,
                               long enqueuedAtMs) {
        this.kind = kind;
        this.text = text;
        this.sessionId = sessionId;
        this.serverKey = serverKey == null ? "" : serverKey;
        this.requireActiveGame = requireActiveGame;
        this.requireParty = requireParty;
        this.partyEpoch = partyEpoch;
        this.enqueuedAtMs = enqueuedAtMs;
    }

    /**
     * Whether the captured game/server/(optional) party context still matches the live snapshot.
     * Feature toggles are checked separately by the coordinator. Party epoch is ignored unless
     * {@link #requireParty} is set — public chat and AutoGG must survive party membership changes.
     */
    public boolean contextMatches(int liveSessionId, String liveServerKey, boolean liveActiveGame,
                                  int livePartyEpoch) {
        if (sessionId != liveSessionId) return false;
        String live = liveServerKey == null ? "" : liveServerKey;
        if (!serverKey.equals(live)) return false;
        if (requireActiveGame && !liveActiveGame) return false;
        if (requireParty && partyEpoch != livePartyEpoch) return false;
        return true;
    }
}
