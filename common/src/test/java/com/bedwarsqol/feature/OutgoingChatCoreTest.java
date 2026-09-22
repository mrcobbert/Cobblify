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
        assertFalse(core.consumeSweatRetry(0L));
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
        assertFalse(core.consumeSweatRetry(0L));
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
        assertTrue(core.consumeSweatRetry(0L));
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
    public void multiLineSweatDeliversEveryLineInOrder() {
        core.submitSweat(java.util.Arrays.asList("/pc one", "/pc two", "/pc three"), ctx, 0L);
        long now = 0L;
        java.util.List<String> sent = new java.util.ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            now += OutgoingChatPolicy.MIN_GAP_MS;
            assertEquals(OutgoingChatCore.SweatFlight.IN_FLIGHT, core.sweatFlight());
            OutgoingChatCore.Decision d = core.tick(ctx, now, false, allOn);
            assertNotNull(d.send);
            sent.add(d.send.text);
            core.acknowledgeDelivered(d.send, now);
        }
        assertEquals(java.util.Arrays.asList("/pc one", "/pc two", "/pc three"), sent);
        assertEquals(OutgoingChatCore.SweatFlight.DONE, core.sweatFlight());
        assertNull(core.queue().peekAuto());
    }

    @Test
    public void reportLinesAfterTheFirstUseTheOldHalfSecondCadence() {
        // Prime a prior send so the first report line actually has a gap to wait out.
        core.acknowledgeDelivered(core.newRequest(OutgoingChatKind.INC, "/pc INC", ctx, 0L), 0L);
        core.submitSweat(java.util.Arrays.asList("/pc one", "/pc two"), ctx, 0L);

        // The first line still pays the full gap.
        assertNull(core.tick(ctx, OutgoingChatPolicy.MIN_GAP_MS - 1, false, allOn).send);
        OutgoingChatCore.Decision first = core.tick(ctx, OutgoingChatPolicy.MIN_GAP_MS, false, allOn);
        assertEquals("/pc one", first.send.text);
        core.acknowledgeDelivered(first.send, OutgoingChatPolicy.MIN_GAP_MS);

        // The rest follow at SWEAT_LINE_GAP_MS, not MIN_GAP_MS.
        long tooSoon = OutgoingChatPolicy.MIN_GAP_MS + OutgoingChatPolicy.SWEAT_LINE_GAP_MS - 1;
        assertNull(core.tick(ctx, tooSoon, false, allOn).send);
        OutgoingChatCore.Decision second = core.tick(ctx, tooSoon + 1, false, allOn);
        assertEquals("/pc two", second.send.text);
    }

    @Test
    public void interruptMidBatchResumesRemainderWithoutRepeating() {
        core.submitSweat(java.util.Arrays.asList("/pc one", "/pc two", "/pc three"), ctx, 0L);
        OutgoingChatCore.Decision first = core.tick(ctx, 2500L, false, allOn);
        assertEquals("/pc one", first.send.text);
        core.acknowledgeDelivered(first.send, 2500L);

        core.onUserIntent(2600L); // typed chat cancels the queued "/pc two"
        assertEquals(OutgoingChatCore.SweatFlight.RETRYABLE, core.sweatFlight());

        assertTrue(core.consumeSweatRetry(2700L));
        assertEquals(OutgoingChatCore.SweatFlight.IN_FLIGHT, core.sweatFlight());
        OutgoingChatCore.Decision resumed = core.tick(ctx, 9000L, false, allOn);
        assertEquals("/pc two", resumed.send.text); // resumed, not restarted
        core.acknowledgeDelivered(resumed.send, 9000L);
        OutgoingChatCore.Decision last = core.tick(ctx, 12000L, false, allOn);
        assertEquals("/pc three", last.send.text);
        core.acknowledgeDelivered(last.send, 12000L);
        assertEquals(OutgoingChatCore.SweatFlight.DONE, core.sweatFlight());
    }

    @Test
    public void interruptBeforeAnyLineSentRebuildsFromScratch() {
        core.submitSweat(java.util.Arrays.asList("/pc one", "/pc two"), ctx, 0L);
        core.onUserIntent(10L);
        assertEquals(OutgoingChatCore.SweatFlight.RETRYABLE, core.sweatFlight());
        assertTrue(core.consumeSweatRetry(20L));
        // Nothing reached the wire, so the stale batch is dropped and SweatReport may rebuild.
        assertEquals(OutgoingChatCore.SweatFlight.IDLE, core.sweatFlight());
        assertNull(core.queue().peekAuto());
        core.submitSweat(java.util.Arrays.asList("/pc fresh"), ctx, 30L);
        assertEquals("/pc fresh", core.queue().peekAuto().text);
    }

    @Test
    public void partyEndMidBatchDropsTheRemainder() {
        core.submitSweat(java.util.Arrays.asList("/pc one", "/pc two"), ctx, 0L);
        OutgoingChatCore.Decision first = core.tick(ctx, 2500L, false, allOn);
        core.acknowledgeDelivered(first.send, 2500L);
        core.onPartyEpochAdvanced();
        assertEquals(OutgoingChatCore.SweatFlight.DONE, core.sweatFlight());
        assertFalse(core.consumeSweatRetry(2600L));
        assertNull(core.queue().peekAuto());
    }

    @Test
    public void knownPartylessDropsSweatBatchTail() {
        core.submitSweat(java.util.Arrays.asList("/pc one", "/pc two", "/pc three"), ctx, 0L);
        OutgoingChatCore.Decision first = core.tick(ctx, 2500L, false, allOn);
        assertEquals("/pc one", first.send.text); // one line escapes, then Hypixel tells us
        core.acknowledgeDelivered(first.send, 2500L);

        OutgoingChatCore.LiveContext partyless =
                new OutgoingChatCore.LiveContext(1, "hypixel.net", true, 0, false);
        assertNull(core.tick(partyless, 5000L, false, allOn).send);
        assertNull(core.queue().peekAuto());
        assertEquals(OutgoingChatCore.SweatFlight.DONE, core.sweatFlight());
        assertFalse(core.consumeSweatRetry(5100L));
    }

    @Test
    public void knownPartylessDropsIncButSparesPublicAndAutoGg() {
        OutgoingChatCore.LiveContext partyless =
                new OutgoingChatCore.LiveContext(1, "hypixel.net", true, 0, false);
        assertTrue(core.submitInc("/pc INC", partyless, 0L, 0L));
        assertNull(core.tick(partyless, 5000L, false, allOn).send);
        assertNull(core.queue().peekInc());
        assertFalse(core.isIncInFlight());
        assertTrue(core.canSubmitInc(5000L, 2000L)); // cooldown not burnt

        // Non-party traffic is untouched by the gate.
        core.submit(OutgoingChatKind.AUTOGG, "gg", partyless, 5000L);
        assertNotNull(core.tick(partyless, 8000L, false, allOn).send);
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

    // ---- M1: typed chat is never dropped, and only Hypixel is paced -------------------------

    private static OutgoingChatCore.LiveContext onHypixel(boolean on) {
        return new OutgoingChatCore.LiveContext(1, "hypixel.net", true, 0, true, on);
    }

    /** Keeps the gap closed for a tick: a dummy manual line "just" went out. */
    private void closeGap(long nowMs) {
        core.acknowledgeDelivered(core.newRequest(OutgoingChatKind.MANUAL, "x", ctx, nowMs), nowMs);
    }

    @Test
    public void typedChatIsNeverDroppedInsideTheGap() {
        OutgoingChatCore.InterceptResult a = core.interceptPlayerSend("a", ctx, 1000L);
        assertTrue(a.handled);
        assertEquals("a", a.decision.send.text);
        assertTrue(a.decision.notices.isEmpty());
        core.acknowledgeDelivered(a.decision.send, 1000L);

        OutgoingChatCore.InterceptResult b = core.interceptPlayerSend("b", ctx, 1500L);
        assertTrue(b.handled);
        assertNull(b.decision.send);
        assertTrue(b.decision.notices.isEmpty());
        OutgoingChatCore.InterceptResult c = core.interceptPlayerSend("c", ctx, 2000L);
        assertTrue(c.handled);
        assertNull(c.decision.send);
        assertTrue(c.decision.notices.isEmpty());
        assertEquals(2, core.queue().heldManualCount());

        OutgoingChatCore.Decision early = core.tick(ctx, 3000L, false, allOn);
        assertNull(early.send);
        assertTrue(early.notices.isEmpty());
        OutgoingChatCore.Decision second = core.tick(ctx, 3500L, false, allOn);
        assertEquals("b", second.send.text);
        core.acknowledgeDelivered(second.send, 3500L);
        assertNull(core.tick(ctx, 5000L, false, allOn).send);
        OutgoingChatCore.Decision third = core.tick(ctx, 6000L, false, allOn);
        assertEquals("c", third.send.text);
        core.acknowledgeDelivered(third.send, 6000L);
        assertTrue(core.queue().isEmpty());
    }

    /** Review B1: a line typed after the gap re-opens must not overtake a line still held. */
    @Test
    public void typedLineArrivingWithTheGapOpenGoesBehindTheHeldOnes() {
        OutgoingChatCore.InterceptResult a = core.interceptPlayerSend("a", ctx, 1000L);
        core.acknowledgeDelivered(a.decision.send, 1000L);
        OutgoingChatCore.InterceptResult b = core.interceptPlayerSend("b", ctx, 1500L);
        assertNull(b.decision.send);
        // Gap open again, no tick has run yet: the oldest held line goes, the new one waits.
        OutgoingChatCore.InterceptResult c = core.interceptPlayerSend("c", ctx, 3500L);
        assertTrue(c.handled);
        assertEquals("b", c.decision.send.text);
        assertTrue(c.decision.notices.isEmpty());
        core.acknowledgeDelivered(c.decision.send, 3500L);
        assertEquals(1, core.queue().heldManualCount());
        OutgoingChatCore.Decision last = core.tick(ctx, 6000L, false, allOn);
        assertEquals("c", last.send.text);
        core.acknowledgeDelivered(last.send, 6000L);
        assertTrue(core.queue().isEmpty());
    }

    /** Code review G, I1: the intercept's fast path must validate the head like a tick does. */
    @Test
    public void typedLineWithGapOpenNoticesAStaleHeadInsteadOfSendingIt() {
        OutgoingChatCore.InterceptResult a = core.interceptPlayerSend("a", ctx, 1000L);
        core.acknowledgeDelivered(a.decision.send, 1000L);
        core.interceptPlayerSend("b", ctx, 1500L); // held under session 1
        OutgoingChatCore.LiveContext session2 = new OutgoingChatCore.LiveContext(2, "hypixel.net", true, 0);
        OutgoingChatCore.InterceptResult c = core.interceptPlayerSend("c", session2, 3500L);
        assertTrue(c.handled);
        assertNull(c.decision.send);
        assertEquals(1, c.decision.notices.size());
        assertEquals("b", c.decision.notices.get(0).text);
        assertEquals("context changed", c.decision.noticeWhy);
        assertEquals(1, core.queue().heldManualCount()); // c waits its turn
        OutgoingChatCore.Decision next = core.tick(session2, 3600L, false, allOn);
        assertEquals("c", next.send.text);
        core.acknowledgeDelivered(next.send, 3600L);
        assertTrue(core.queue().isEmpty());
    }

    @Test
    public void offHypixelTypedChatIsNotIntercepted() {
        OutgoingChatCore.LiveContext off = onHypixel(false);
        OutgoingChatCore.InterceptResult r = core.interceptPlayerSend("hello", off, 1000L);
        assertFalse(r.handled);
        assertNull(r.decision.send);
        assertTrue(r.decision.notices.isEmpty());
        assertTrue(core.queue().isEmpty());
        assertEquals(1000L, core.lastManualMs()); // the quiet window still opens
        OutgoingChatCore.InterceptResult slash = core.interceptPlayerSend("/pc hi", off, 1100L);
        assertFalse(slash.handled);
        assertTrue(core.queue().isEmpty());
        // And the same lines ON Hypixel are handled, as before.
        assertTrue(core.interceptPlayerSend("hello", onHypixel(true), 5000L).handled);
    }

    @Test
    public void heldManualsAreNoticedOnWorldChange() {
        OutgoingChatCore.InterceptResult a = core.interceptPlayerSend("a", ctx, 1000L);
        core.acknowledgeDelivered(a.decision.send, 1000L);
        core.interceptPlayerSend("b", ctx, 1500L);
        core.interceptPlayerSend("c", ctx, 2000L);
        OutgoingChatCore.Decision inv = core.invalidateAll();
        assertEquals(2, inv.notices.size());
        assertEquals("b", inv.notices.get(0).text);
        assertEquals("c", inv.notices.get(1).text);
        assertEquals("world change", inv.noticeWhy);
        assertEquals("b", inv.notice.text);
        assertTrue(core.queue().isEmpty());
    }

    @Test
    public void heldManualTimesOutFromWhenItReachesTheHead() {
        closeGap(1000L);
        core.interceptPlayerSend("b", ctx, 1500L);
        core.interceptPlayerSend("c", ctx, 2000L);
        long max = OutgoingChatPolicy.MANUAL_HOLD_MAX_MS;

        closeGap(2500L); // b reaches the head on this first tick
        OutgoingChatCore.Decision first = core.tick(ctx, 2500L, false, allOn);
        assertNull(first.send);
        assertTrue(first.notices.isEmpty());

        closeGap(2500L + max - 1);
        OutgoingChatCore.Decision notYet = core.tick(ctx, 2500L + max - 1, false, allOn);
        assertTrue(notYet.notices.isEmpty());

        closeGap(2500L + max);
        OutgoingChatCore.Decision timedOut = core.tick(ctx, 2500L + max, false, allOn);
        assertEquals(1, timedOut.notices.size());
        assertEquals("b", timedOut.notices.get(0).text);
        assertEquals("timed out", timedOut.noticeWhy);

        // c's clock starts when c becomes the head, not when it was typed.
        long cHead = 2500L + max + 100L;
        closeGap(cHead);
        assertTrue(core.tick(ctx, cHead, false, allOn).notices.isEmpty());
        closeGap(cHead + max - 1);
        assertTrue(core.tick(ctx, cHead + max - 1, false, allOn).notices.isEmpty());
        closeGap(cHead + max);
        OutgoingChatCore.Decision cOut = core.tick(ctx, cHead + max, false, allOn);
        assertEquals("c", cOut.notices.get(0).text);
        assertEquals("timed out", cOut.noticeWhy);
        assertTrue(core.queue().isEmpty());
    }

    @Test
    public void sixteenHeldLinesIsTheCapAndTheSeventeenthIsNoticed() {
        closeGap(1000L);
        for (int i = 1; i <= 16; i++) {
            OutgoingChatCore.InterceptResult r = core.interceptPlayerSend("m" + i, ctx, 1000L + i);
            assertTrue(r.handled);
            assertNull(r.decision.send);
            assertTrue("line " + i + " held silently", r.decision.notices.isEmpty());
        }
        assertEquals(16, core.queue().heldManualCount());
        OutgoingChatCore.InterceptResult r17 = core.interceptPlayerSend("m17", ctx, 1017L);
        assertTrue(r17.handled);
        assertNull(r17.decision.send);
        assertEquals(1, r17.decision.notices.size());
        assertEquals("m17", r17.decision.notices.get(0).text);
        assertEquals("too many held", r17.decision.noticeWhy);
        assertEquals(16, core.queue().heldManualCount());
    }

    // ---- M3: a report must never swallow the AutoGG it displaced ----------------------------

    @Test
    public void sweatDisplacingAutoGgSendsBoth() {
        core.submit(OutgoingChatKind.AUTOGG, "gg", ctx, 0L);
        core.submitSweat(java.util.Arrays.asList("/pc s1", "/pc s2"), ctx, 10L);
        assertEquals(OutgoingChatKind.SWEAT, core.queue().peekAuto().kind);

        OutgoingChatCore.Decision first = core.tick(ctx, 100L, false, allOn);
        assertEquals("/pc s1", first.send.text);
        core.acknowledgeDelivered(first.send, 100L);
        OutgoingChatCore.Decision second = core.tick(ctx, 700L, false, allOn);
        assertEquals("/pc s2", second.send.text);
        core.acknowledgeDelivered(second.send, 700L);
        assertEquals(OutgoingChatCore.SweatFlight.DONE, core.sweatFlight());

        // The gg waits out the normal gap after the report's last line, then goes.
        assertNull(core.tick(ctx, 800L, false, allOn).send);
        OutgoingChatCore.Decision gg = core.tick(ctx, 3300L, false, allOn);
        assertNotNull("the displaced AutoGG must still be sent", gg.send);
        assertEquals("gg", gg.send.text);
        assertEquals(OutgoingChatKind.AUTOGG, gg.send.kind);
        core.acknowledgeDelivered(gg.send, 3300L);
        assertTrue(core.queue().isEmpty());
        assertEquals(OutgoingChatCore.SweatFlight.DONE, core.sweatFlight());
    }

    @Test
    public void cancelAutoGgAlsoDropsADeferredOne() {
        core.submit(OutgoingChatKind.AUTOGG, "gg", ctx, 0L);
        core.submitSweat(java.util.Arrays.asList("/pc s1", "/pc s2"), ctx, 10L);
        core.cancelKind(OutgoingChatKind.AUTOGG); // AutoGG re-armed: that gg is void

        OutgoingChatCore.Decision first = core.tick(ctx, 100L, false, allOn);
        assertEquals("/pc s1", first.send.text);
        core.acknowledgeDelivered(first.send, 100L);
        OutgoingChatCore.Decision second = core.tick(ctx, 700L, false, allOn);
        assertEquals("/pc s2", second.send.text);
        core.acknowledgeDelivered(second.send, 700L);

        assertNull(core.tick(ctx, 3300L, false, allOn).send);
        assertNull(core.tick(ctx, 10000L, false, allOn).send);
        assertTrue(core.queue().isEmpty());
    }

    @Test
    public void newGameDropsADeferredGg() {
        core.submit(OutgoingChatKind.AUTOGG, "gg", ctx, 0L);
        core.submitSweat(java.util.Arrays.asList("/pc s1", "/pc s2"), ctx, 10L);
        core.resetSweatForNewGame();
        assertTrue(core.queue().isEmpty());

        assertNull(core.tick(ctx, 3300L, false, allOn).send);
        assertNull(core.tick(ctx, 10000L, false, allOn).send);
        assertTrue(core.queue().isEmpty());
    }

    @Test
    public void autoGgReplacingAutoGgIsNotDeferred() {
        core.submit(OutgoingChatKind.AUTOGG, "gg", ctx, 0L);
        core.submit(OutgoingChatKind.AUTOGG, "gg", ctx, 10L);

        OutgoingChatCore.Decision only = core.tick(ctx, 2600L, false, allOn);
        assertEquals("gg", only.send.text);
        core.acknowledgeDelivered(only.send, 2600L);
        assertTrue(core.queue().isEmpty());
        // One gg was queued twice, not two ggs: nothing is owed afterwards.
        assertNull(core.tick(ctx, 6000L, false, allOn).send);
        assertNull(core.tick(ctx, 20000L, false, allOn).send);
    }

    /** Review I1: an INC interrupting a half-sent report must not cost the gg either. */
    @Test
    public void interruptedPartlySentReportStillSendsTheGg() {
        core.submit(OutgoingChatKind.AUTOGG, "gg", ctx, 0L);
        core.submitSweat(java.util.Arrays.asList("/pc s1", "/pc s2"), ctx, 10L);

        OutgoingChatCore.Decision first = core.tick(ctx, 100L, false, allOn);
        assertEquals("/pc s1", first.send.text);
        core.acknowledgeDelivered(first.send, 100L);

        // INC displaces the queued second line: the report goes RETRYABLE and frees the AUTO slot.
        assertTrue(core.submitInc("/pc INC", ctx, 200L, 0L));
        assertEquals(OutgoingChatCore.SweatFlight.RETRYABLE, core.sweatFlight());
        assertNotNull("the gg returns to the AUTO slot the report vacated", core.queue().peekAuto());
        assertEquals(OutgoingChatKind.AUTOGG, core.queue().peekAuto().kind);

        // Resuming line two displaces the gg again: deferred once more, not dropped.
        assertTrue(core.consumeSweatRetry(300L));
        assertEquals(OutgoingChatKind.SWEAT, core.queue().peekAuto().kind);

        assertNull(core.tick(ctx, 500L, false, allOn).send);
        OutgoingChatCore.Decision inc = core.tick(ctx, 2600L, false, allOn);
        assertEquals("/pc INC", inc.send.text);
        core.acknowledgeDelivered(inc.send, 2600L);
        OutgoingChatCore.Decision second = core.tick(ctx, 3100L, false, allOn);
        assertEquals("/pc s2", second.send.text);
        core.acknowledgeDelivered(second.send, 3100L);
        assertEquals(OutgoingChatCore.SweatFlight.DONE, core.sweatFlight());

        OutgoingChatCore.Decision gg = core.tick(ctx, 5600L, false, allOn);
        assertNotNull("the displaced AutoGG must survive the interruption", gg.send);
        assertEquals("gg", gg.send.text);
        assertEquals(OutgoingChatKind.AUTOGG, gg.send.kind);
        core.acknowledgeDelivered(gg.send, 5600L);
        assertTrue(core.queue().isEmpty());
        assertNull(core.tick(ctx, 20000L, false, allOn).send); // exactly once
    }

    @Test
    public void aNewerAutoGgSupersedesADeferredOne() {
        core.submit(OutgoingChatKind.AUTOGG, "gg", ctx, 0L);
        core.submitSweat(java.util.Arrays.asList("/pc s1", "/pc s2"), ctx, 10L); // gg deferred
        core.submit(OutgoingChatKind.AUTOGG, "gg", ctx, 20L); // replaces the report: sweat RETRYABLE
        assertEquals(OutgoingChatCore.SweatFlight.RETRYABLE, core.sweatFlight());
        int ggs = 0;
        long now = 100L;
        for (int i = 0; i < 12; i++, now += OutgoingChatPolicy.MIN_GAP_MS) {
            if (core.sweatFlight() == OutgoingChatCore.SweatFlight.RETRYABLE) {
                core.consumeSweatRetry(now);
                if (core.sweatFlight() == OutgoingChatCore.SweatFlight.IDLE) {
                    core.submitSweat(java.util.Arrays.asList("/pc s1", "/pc s2"), ctx, now);
                }
            }
            OutgoingChatCore.Decision d = core.tick(ctx, now, false, allOn);
            if (d.send == null) continue;
            if ("gg".equals(d.send.text)) ggs++;
            core.acknowledgeDelivered(d.send, now);
        }
        assertEquals("exactly one gg for the game", 1, ggs);
        assertTrue(core.queue().isEmpty());
    }
}
