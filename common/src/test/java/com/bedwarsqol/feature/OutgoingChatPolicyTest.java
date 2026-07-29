package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pins shared outgoing-chat pacing: global gap, quiet window after manual, hold expiry. */
public class OutgoingChatPolicyTest {

    @Test
    public void manualRespectsGlobalGapOnly() {
        long lastSend = 1000L;
        long lastManual = 0L;
        assertFalse(OutgoingChatPolicy.canSend(OutgoingChatKind.MANUAL, lastSend + 1000L, lastSend, lastManual, false));
        assertTrue(OutgoingChatPolicy.canSend(OutgoingChatKind.MANUAL,
                lastSend + OutgoingChatPolicy.MIN_GAP_MS, lastSend, lastManual, false));
        assertTrue(OutgoingChatPolicy.canSend(OutgoingChatKind.MANUAL, lastSend + 1L, lastSend, lastManual, true));
    }

    @Test
    public void autoWaitsForQuietWindowAfterManual() {
        long lastSend = 0L;
        long lastManual = 10_000L;
        long almost = lastManual + OutgoingChatPolicy.QUIET_AFTER_MANUAL_MS - 1;
        assertTrue(almost - lastSend >= OutgoingChatPolicy.MIN_GAP_MS);
        assertFalse(OutgoingChatPolicy.canSend(OutgoingChatKind.AUTOGG, almost, lastSend, lastManual, false));
        assertFalse(OutgoingChatPolicy.canSend(OutgoingChatKind.SWEAT, almost, lastSend, lastManual, false));
        assertTrue(OutgoingChatPolicy.canSend(OutgoingChatKind.INC, almost, lastSend, lastManual, false));
        long quietDone = lastManual + OutgoingChatPolicy.QUIET_AFTER_MANUAL_MS;
        assertTrue(OutgoingChatPolicy.canSend(OutgoingChatKind.AUTOGG, quietDone, lastSend, lastManual, false));
    }

    @Test
    public void nextEligibleAccountsForQuietOnAutoOnly() {
        long now = 9000L;
        long lastSend = 1000L;
        long lastManual = 8000L;
        long autoReady = OutgoingChatPolicy.nextEligibleMs(OutgoingChatKind.SWEAT, now, lastSend, lastManual);
        assertEquals(lastManual + OutgoingChatPolicy.QUIET_AFTER_MANUAL_MS, autoReady);
        long incReady = OutgoingChatPolicy.nextEligibleMs(OutgoingChatKind.INC, now, lastSend, lastManual);
        assertEquals(now, incReady);
    }

    @Test
    public void manualHoldExpiresAtCap() {
        long enqueued = 1000L;
        assertFalse(OutgoingChatPolicy.manualHoldExpired(enqueued, enqueued + OutgoingChatPolicy.MANUAL_HOLD_MAX_MS - 1));
        assertTrue(OutgoingChatPolicy.manualHoldExpired(enqueued, enqueued + OutgoingChatPolicy.MANUAL_HOLD_MAX_MS));
    }
}
