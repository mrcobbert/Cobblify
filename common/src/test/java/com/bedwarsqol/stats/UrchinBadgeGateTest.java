package com.bedwarsqol.stats;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the pure render gate {@link UrchinTag#badgeAllowed} shared by the tab and nametag badge
 * surfaces (B1). A badge may render only when the current-session eligibility snapshot confirms the
 * exact displayed (name, uuid) pair; a null snapshot, a stale session, or an unconfirmed row all
 * fail closed.
 *
 * <p>The clear-path semantics (B2) - a successful key clear leaving every entry resolved-empty via
 * {@code StatsCache.stripUrchinTags()} without re-invalidating resolution - cannot be exercised in a
 * pure JUnit test because {@code StatsCache} imports Forge/Minecraft ({@code BedwarsQol},
 * {@code net.minecraftforge.fml.common.Loader}) and cannot be instantiated headless. That behavior is
 * covered by the manual/route verification matrix instead.
 */
public class UrchinBadgeGateTest {

    // Runs headless: GameSessionTracker.currentSessionId() defaults to 0 (its static driver only
    // advances from onClientTick, which needs Minecraft). So a live snapshot uses sessionId 0.
    private static final int LIVE = GameSessionTracker.currentSessionId();
    private static final UUID UID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String NAME = "Cheater123";

    private static IdentitySnapshot idWith(int sessionId, String name, UUID uuid) {
        Set<String> rows = new HashSet<String>();
        rows.add(IdentitySnapshot.rowKey(name, uuid));
        return new IdentitySnapshot(rows, 1L, sessionId);
    }

    private static EligibilitySnapshot snap(int sessionId, IdentitySnapshot id) {
        return new EligibilitySnapshot(sessionId, true /*exactHost*/, true /*activeGame*/,
                true /*masterOn*/, id);
    }

    @Test
    public void currentConfirmedPairAllowsBadge() {
        EligibilitySnapshot s = snap(LIVE, idWith(LIVE, NAME, UID));
        assertTrue(UrchinTag.badgeAllowed(s, NAME, UID));
    }

    @Test
    public void nullSnapshotFailsClosed() {
        assertFalse(UrchinTag.badgeAllowed(null, NAME, UID));
    }

    @Test
    public void staleSessionFailsClosed() {
        // Snapshot and its identity agree internally, but on a session id that is no longer live.
        EligibilitySnapshot s = snap(LIVE + 7, idWith(LIVE + 7, NAME, UID));
        assertFalse(UrchinTag.badgeAllowed(s, NAME, UID));
    }

    @Test
    public void unconfirmedRowFailsClosed() {
        // Live session, but the displayed pair is not in the confirmed-row set.
        EligibilitySnapshot s = snap(LIVE, idWith(LIVE, "SomeoneElse", UID));
        assertFalse(UrchinTag.badgeAllowed(s, NAME, UID));
    }
}
