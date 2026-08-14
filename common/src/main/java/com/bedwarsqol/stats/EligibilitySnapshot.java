package com.bedwarsqol.stats;

import java.util.Locale;
import java.util.UUID;

/**
 * Immutable per-client-tick snapshot of everything the background stats threads may consult to decide
 * Urchin eligibility. Published once per tick from the {@link GameSessionTracker} driver; dispatch
 * threads read ONLY this (never live world/scoreboard/server state off-thread). Missing or
 * stale-session data fails closed.
 */
public final class EligibilitySnapshot {

    public final int sessionId;
    public final boolean exactHost;   // server host is exactly hypixel.net or *.hypixel.net
    public final boolean activeGame;  // in an active Bedwars game
    public final boolean masterOn;    // Urchin master toggle (per-provider opt-in)
    public final boolean seraphOn;    // Seraph master toggle (per-provider opt-in)
    public final IdentitySnapshot identity;

    private static volatile EligibilitySnapshot CURRENT =
            new EligibilitySnapshot(-1, false, false, false, false, IdentitySnapshot.EMPTY);

    public EligibilitySnapshot(int sessionId, boolean exactHost, boolean activeGame, boolean masterOn,
                               boolean seraphOn,
                               IdentitySnapshot identity) {
        this.sessionId = sessionId;
        this.exactHost = exactHost;
        this.activeGame = activeGame;
        this.masterOn = masterOn;
        this.seraphOn = seraphOn;
        this.identity = identity == null ? IdentitySnapshot.EMPTY : identity;
    }

    public static EligibilitySnapshot current() {
        return CURRENT;
    }

    public static void publish(EligibilitySnapshot snapshot) {
        if (snapshot != null) CURRENT = snapshot;
    }

    /**
     * Provider-neutral context predicate: exact Hypixel host, active game, and a current-session
     * confirmed (name, uuid) row. Every provider's eligibility is this AND its own opt-in bit, so a
     * provider is inert when its toggle is off even if another is on.
     */
    public boolean contextEligible(String name, UUID uuid) {
        return exactHost && activeGame
                && identity != null
                && identity.sessionId == sessionId
                && identity.sessionId == GameSessionTracker.currentSessionId()
                && identity.contains(name, uuid);
    }

    /** Whether a (displayed name, canonical uuid) pair is Urchin-eligible right now. */
    public boolean eligible(String name, UUID uuid) {
        return masterOn && contextEligible(name, uuid);
    }

    /** Whether a (displayed name, canonical uuid) pair is Seraph-eligible right now. */
    public boolean eligibleSeraph(String name, UUID uuid) {
        return seraphOn && contextEligible(name, uuid);
    }

    /**
     * Strict Hypixel host-boundary test: exactly {@code hypixel.net} or a {@code *.hypixel.net}
     * subdomain (suffix boundary, never a substring). A port suffix is tolerated. Shared by the
     * client HypixelContext gate and the Urchin/Seraph eligibility snapshot.
     */
    public static boolean isExactHypixelHost(String serverAddress) {
        if (serverAddress == null) return false;
        String host = serverAddress.trim().toLowerCase(Locale.ROOT);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1); // trailing root dot
        return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
    }
}
