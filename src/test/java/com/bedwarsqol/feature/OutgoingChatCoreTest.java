package com.bedwarsqol.feature;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Coordinator-level policy: party scope, command bypass, sweat flight acks, Inc cooldown,
 * priority/pacing, and cancellation.
 */
public class OutgoingChatCoreTest {

    private OutgoingChatCore core;
    private OutgoingChatCore.LiveContext ctx;

    private final OutgoingChatCore.FeatureGate allOn = new OutgoingChatCore.FeatureGate() {
        public boolean enabled(OutgoingChatKind kind) { return true; }
    };

    private final OutgoingChatCore.FeatureGate sweatOff = new OutgoingChatCore.FeatureGate() {
        public boolean enabled(OutgoingChatKind kind) {
            return kind != OutgoingChatKind.SWEAT;
        }
    };

    @Before
    public void setUp() {
        core = new OutgoingChatCore();
        ctx = new OutgoingChatCore.LiveContext(1, "hypixel.net", true, 0);
    }

    @Test
    public void partyEpochOnlyInvalidatesPartyTargetedMessages() {
        // Enqueue manual first, then sweat — MANUAL enqueue would otherwise clear AUTO.
        OutgoingChatRequest pub = core.newRequest(OutgoingChatKind.MANUAL, "hello public", ctx, 100L);
        core.queue().enqueue(pub);
        core.submitSweat("/pc sweat", ctx, 0L);
        assertNotNull(core.queue().peekAuto());
        assertNotNull(core.queue().peekManual());

        OutgoingChatCore.LiveContext newParty = new OutgoingChatCore.LiveContext(1, "hypixel.net", true, 1);
        assertFalse(core.queue().peekAuto().contextMatches(
                newParty.sessionId, newParty.serverKey, newParty.activeGame, newParty.partyEpoch));
        assertTrue(pub.contextMatches(
                newParty.sessionId, newParty.serverKey, newParty.activeGame, newParty.partyEpoch));

        // Prime a recent send so the tick purges sweat but cannot flush the held manual yet.
        core.acknowledgeDelivered(core.newRequest(OutgoingChatKind.MANUAL, "x", ctx, 0L), 4000L);
        core.tick(newParty, 4100L, false, allOn);
        // Party membership ended → Sweat is finalized (not retryable under a new party).
        assertEquals(OutgoingChatCore.SweatFlight.DONE, core.sweatFlight());
        assertFalse(core.consumeSweatRetry());
        assertNull(core.queue().peekAuto());
        assertNotNull(core.queue().peekManual());

        // AutoGG is not party-scoped and remains valid across the same party bump.
        core.submit(OutgoingChatKind.AUTOGG, "gg", ctx, 4200L);
        assertTrue(core.queue().peekAuto().contextMatches(
                newParty.sessionId, newParty.serverKey, newParty.activeGame, newParty.partyEpoch));
    }

    @Test
    public void partyEpochAdvanceFinalizesRetryableSweat() {
        core.submitSweat("/pc sweat", ctx, 0L);
        core.onUserIntent(10L);
        assertEquals(OutgoingChatCore.SweatFlight.RETRYABLE, core.sweatFlight());
        core.onPartyEpochAdvanced();
        assertEquals(OutgoingChatCore.SweatFlight.DONE, core.sweatFlight());
        assertFalse(core.consumeSweatRetry());
    }

    @Test
    public void classifyStaleDistinguishesPartyFromOtherContext() {
        OutgoingChatRequest sweat = core.newRequest(OutgoingChatKind.SWEAT, "/pc s", ctx, 0L);
        assertEquals(OutgoingChatCore.SweatCancelReason.PARTY_CHANGED,
                OutgoingChatCore.classifyStale(sweat,
                        new OutgoingChatCore.LiveContext(1, "hypixel.net", true, 1)));
        assertEquals(OutgoingChatCore.SweatCancelReason.CONTEXT_STALE,
                OutgoingChatCore.classifyStale(sweat,
                        new OutgoingChatCore.LiveContext(2, "hypixel.net", true, 0)));
        assertEquals(OutgoingChatCore.SweatCancelReason.GAME_STALE,
                OutgoingChatCore.classifyStale(sweat,
                        new OutgoingChatCore.LiveContext(1, "hypixel.net", false, 0)));
        assertTrue(OutgoingChatCore.isRetryable(OutgoingChatCore.SweatCancelReason.INTERRUPTED));
        assertTrue(OutgoingChatCore.isRetryable(OutgoingChatCore.SweatCancelReason.REPLACED));
        assertFalse(OutgoingChatCore.isRetryable(OutgoingChatCore.SweatCancelReason.PARTY_CHANGED));
        assertFalse(OutgoingChatCore.isRetryable(OutgoingChatCore.SweatCancelReason.CONTEXT_STALE));
    }

    @Test
    public void slashCommandsBypassPacingAndUpdateClocks() {
        core.acknowledgeDelivered(
                core.newRequest(OutgoingChatKind.MANUAL, "prior", ctx, 0L), 1000L);
        // Only 100ms later — normal manual would hold; slash bypasses.
        OutgoingChatCore.InterceptResult r = core.interceptPlayerSend("/lobby", ctx, 1100L);
        assertTrue(r.handled);
        assertNotNull(r.decision.send);
        assertTrue(r.decision.bypassPacing);
        assertTrue(OutgoingChatCore.isSlashCommand("/lobby"));
        assertFalse(OutgoingChatCore.isSlashCommand("hello"));
        core.acknowledgeDelivered(r.decision.send, 1100L);
        // Automation must wait for gap + quiet after the command.
        core.submit(OutgoingChatKind.AUTOGG, "gg", ctx, 1100L);
        assertNull(core.tick(ctx, 2000L, false, allOn).send);
        long ready = 1100L + Math.max(OutgoingChatPolicy.MIN_GAP_MS, OutgoingChatPolicy.QUIET_AFTER_MANUAL_MS);
        OutgoingChatCore.Decision readyTick = core.tick(ctx, ready, false, allOn);
        assertNotNull(readyTick.send);
        assertEquals(OutgoingChatKind.AUTOGG, readyTick.send.kind);
    }

    @Test
    public void sweatRetryableAfterInterruptThenDeliveredOnce() {
        core.submitSweat("/pc sweat", ctx, 0L);
        assertEquals(OutgoingChatCore.SweatFlight.IN_FLIGHT, core.sweatFlight());
        core.onUserIntent(10L); // cancels optional → retryable
        assertEquals(OutgoingChatCore.SweatFlight.RETRYABLE, core.sweatFlight());
        assertTrue(core.consumeSweatRetry());
        assertEquals(OutgoingChatCore.SweatFlight.IDLE, core.sweatFlight());
        core.submitSweat("/pc sweat", ctx, 20L);
        assertEquals(OutgoingChatCore.SweatFlight.IN_FLIGHT, core.sweatFlight());
        OutgoingChatCore.Decision send = core.tick(ctx, 5000L, false, allOn);
        assertNotNull(send.send);
        core.acknowledgeDelivered(send.send, 5000L);
        assertEquals(OutgoingChatCore.SweatFlight.DONE, core.sweatFlight());
        // No duplicate submit while DONE
        core.submitSweat("/pc again", ctx, 6000L);
        assertNull(core.queue().peekAuto());
        assertEquals(OutgoingChatCore.SweatFlight.DONE, core.sweatFlight());
    }

    @Test
    public void sweatFinalWhenToggleOff() {
        core.submitSweat("/pc sweat", ctx, 0L);
        OutgoingChatCore.Decision d = core.tick(ctx, 5000L, false, sweatOff);
        assertNull(d.send);
        assertEquals(OutgoingChatCore.SweatFlight.DONE, core.sweatFlight());
    }

    @Test
    public void worldInvalidateNoticesHeldManual() {
        // Force hold by priming last send
        core.acknowledgeDelivered(core.newRequest(OutgoingChatKind.MANUAL, "x", ctx, 0L), 1000L);
        OutgoingChatCore.InterceptResult hold = core.interceptPlayerSend("held text", ctx, 1100L);
        assertTrue(hold.handled);
        assertNotNull(core.queue().peekManual());
        OutgoingChatCore.Decision inv = core.invalidateAll();
        assertNotNull(inv.notice);
        assertEquals("world change", inv.noticeWhy);
        assertEquals("held text", inv.notice.text);
        assertTrue(core.queue().isEmpty());
    }

    @Test
    public void incCooldownOnlyBurnsOnDelivery() {
        assertTrue(core.canSubmitInc(0L, 2000L));
        assertTrue(core.submitInc("/pc INC", ctx, 0L, 2000L));
        assertTrue(core.isIncInFlight());
        assertFalse(core.canSubmitInc(100L, 2000L)); // in flight
        // Cancel without delivery
        core.cancelKind(OutgoingChatKind.INC);
        assertFalse(core.isIncInFlight());
        assertTrue(core.canSubmitInc(200L, 2000L)); // cooldown not burned
        assertTrue(core.submitInc("/pc INC", ctx, 200L, 2000L));
        OutgoingChatCore.Decision send = core.tick(ctx, 5000L, false, allOn);
        assertNotNull(send.send);
        core.acknowledgeDelivered(send.send, 5000L);
        assertFalse(core.canSubmitInc(5500L, 2000L));
        assertTrue(core.canSubmitInc(7000L, 2000L));
    }

    @Test
    public void priorityManualBeatsIncBeatsAuto() {
        core.submitSweat("/pc s", ctx, 0L);
        core.submitInc("/pc INC", ctx, 0L, 0L);
        // hold manual by priming gap
        core.acknowledgeDelivered(core.newRequest(OutgoingChatKind.MANUAL, "p", ctx, 0L), 100L);
        core.interceptPlayerSend("typed", ctx, 200L);
        // sweat interrupted by inc then manual
        assertNull(core.queue().peekAuto());
        assertEquals(OutgoingChatKind.MANUAL, core.queue().peekNext().kind);
        OutgoingChatCore.Decision d = core.tick(ctx, 5000L, false, allOn);
        assertEquals(OutgoingChatKind.MANUAL, d.send.kind);
        core.acknowledgeDelivered(d.send, 5000L);
        OutgoingChatCore.Decision d2 = core.tick(ctx, 5000L + OutgoingChatPolicy.MIN_GAP_MS, false, allOn);
        assertEquals(OutgoingChatKind.INC, d2.send.kind);
    }

    @Test
    public void chatOpenPausesAutoOnly() {
        core.submit(OutgoingChatKind.AUTOGG, "gg", ctx, 0L);
        assertNull(core.tick(ctx, 5000L, true, allOn).send);
        OutgoingChatCore.Decision open = core.tick(ctx, 5000L, false, allOn);
        assertNotNull(open.send);
    }

    @Test
    public void requiresPartyFlags() {
        assertTrue(OutgoingChatCore.requiresParty(OutgoingChatKind.INC));
        assertTrue(OutgoingChatCore.requiresParty(OutgoingChatKind.SWEAT));
        assertFalse(OutgoingChatCore.requiresParty(OutgoingChatKind.MANUAL));
        assertFalse(OutgoingChatCore.requiresParty(OutgoingChatKind.AUTOGG));
    }
}
