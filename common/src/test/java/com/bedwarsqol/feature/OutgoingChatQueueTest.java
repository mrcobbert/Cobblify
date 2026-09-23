package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Pins priority, interruption, and same-slot replace behavior for the outgoing-chat queue. */
public class OutgoingChatQueueTest {

    private static OutgoingChatRequest req(OutgoingChatKind kind, String text) {
        boolean party = OutgoingChatCore.requiresParty(kind);
        return new OutgoingChatRequest(kind, text, 1, "hypixel.net",
                kind == OutgoingChatKind.SWEAT, party, 0, 0L);
    }

    @Test
    public void priorityOrderIsManualThenIncThenAuto() {
        OutgoingChatQueue q = new OutgoingChatQueue();
        q.enqueue(req(OutgoingChatKind.SWEAT, "/pc sweat"));
        q.enqueue(req(OutgoingChatKind.INC, "/pc INC"));
        q.enqueue(req(OutgoingChatKind.MANUAL, "hello"));
        assertEquals(OutgoingChatKind.MANUAL, q.pollNext().kind);
        assertEquals(OutgoingChatKind.INC, q.pollNext().kind);
        assertNull(q.pollNext());
    }

    @Test
    public void manualInterruptsOptionalAutomation() {
        OutgoingChatQueue q = new OutgoingChatQueue();
        q.enqueue(req(OutgoingChatKind.SWEAT, "/pc sweat"));
        OutgoingChatQueue.Displacement d = q.enqueue(req(OutgoingChatKind.MANUAL, "hi"));
        assertEquals(OutgoingChatKind.SWEAT, d.optional.kind);
        assertNull(q.peekAuto());
        assertEquals("hi", q.peekManual().text);
    }

    @Test
    public void incInterruptsOptionalAutomation() {
        OutgoingChatQueue q = new OutgoingChatQueue();
        q.enqueue(req(OutgoingChatKind.AUTOGG, "gg"));
        OutgoingChatQueue.Displacement d = q.enqueue(req(OutgoingChatKind.INC, "/pc INC"));
        assertEquals(OutgoingChatKind.AUTOGG, d.optional.kind);
        assertNull(q.peekAuto());
        assertEquals(OutgoingChatKind.INC, q.peekNext().kind);
    }

    @Test
    public void latestAutoReplacesPriorAuto() {
        OutgoingChatQueue q = new OutgoingChatQueue();
        q.enqueue(req(OutgoingChatKind.SWEAT, "/pc a"));
        OutgoingChatQueue.Displacement d = q.enqueue(req(OutgoingChatKind.AUTOGG, "gg"));
        assertEquals(OutgoingChatKind.SWEAT, d.priorSameSlot.kind);
        assertEquals(OutgoingChatKind.AUTOGG, q.peekAuto().kind);
    }

    @Test
    public void cancelOptionalPreservesManualAndInc() {
        OutgoingChatQueue q = new OutgoingChatQueue();
        q.enqueue(req(OutgoingChatKind.INC, "/pc INC"));
        q.enqueue(req(OutgoingChatKind.SWEAT, "/pc s"));
        OutgoingChatRequest manual = req(OutgoingChatKind.MANUAL, "typed");
        q.enqueue(manual);
        assertNull(q.peekAuto());
        assertSame(manual, q.peekManual());
        assertEquals(OutgoingChatKind.INC, q.peekInc().kind);
        assertNull(q.cancelOptional());
        assertTrue(q.peekManual() != null && q.peekInc() != null);
    }

    @Test
    public void contextMismatchRespectsPartyScope() {
        OutgoingChatRequest party = new OutgoingChatRequest(
                OutgoingChatKind.SWEAT, "/pc x", 3, "hypixel.net", true, true, 2, 0L);
        OutgoingChatRequest pub = new OutgoingChatRequest(
                OutgoingChatKind.MANUAL, "hi", 3, "hypixel.net", false, false, 2, 0L);
        assertTrue(party.contextMatches(3, "hypixel.net", true, 2));
        assertTrue(!party.contextMatches(3, "hypixel.net", true, 3));
        assertTrue(pub.contextMatches(3, "hypixel.net", true, 99)); // party ignored
        assertTrue(!pub.contextMatches(4, "hypixel.net", true, 2));
    }

    // ---- M1: held typed lines are a FIFO, not a slot -----------------------------------------

    @Test
    public void manualEnqueueNeverDisplacesAnEarlierManual() {
        OutgoingChatQueue q = new OutgoingChatQueue();
        OutgoingChatRequest m1 = req(OutgoingChatKind.MANUAL, "one");
        OutgoingChatRequest m2 = req(OutgoingChatKind.MANUAL, "two");
        q.enqueue(m1);
        OutgoingChatQueue.Displacement d = q.enqueue(m2);
        assertNull(d.priorSameSlot);
        assertNull(d.rejected);
        assertSame(m1, q.peekManual());
        assertEquals(2, q.heldManualCount());
        assertSame(m1, q.pollNext());
        assertSame(m2, q.pollNext());
        assertNull(q.pollNext());
    }

    @Test
    public void manualsGoOutInOrderAheadOfIncAndAuto() {
        OutgoingChatQueue q = new OutgoingChatQueue();
        q.enqueue(req(OutgoingChatKind.MANUAL, "one"));
        q.enqueue(req(OutgoingChatKind.INC, "/pc INC"));
        q.enqueue(req(OutgoingChatKind.MANUAL, "two"));
        q.enqueue(req(OutgoingChatKind.AUTOGG, "gg"));
        assertEquals("one", q.pollNext().text);
        assertEquals("two", q.pollNext().text);
        assertEquals(OutgoingChatKind.INC, q.pollNext().kind);
        assertEquals(OutgoingChatKind.AUTOGG, q.pollNext().kind);
    }

    @Test
    public void cancelManualDropsOnlyTheHeadAndDrainReturnsTheRest() {
        OutgoingChatQueue q = new OutgoingChatQueue();
        OutgoingChatRequest m1 = req(OutgoingChatKind.MANUAL, "one");
        OutgoingChatRequest m2 = req(OutgoingChatKind.MANUAL, "two");
        OutgoingChatRequest m3 = req(OutgoingChatKind.MANUAL, "three");
        q.enqueue(m1); q.enqueue(m2); q.enqueue(m3);
        assertSame(m1, q.cancelKind(OutgoingChatKind.MANUAL));
        assertSame(m2, q.peekManual());
        java.util.List<OutgoingChatRequest> drained = q.drainManuals();
        assertEquals(java.util.Arrays.asList(m2, m3), drained);
        assertNull(q.peekManual());
        assertEquals(0, q.heldManualCount());
        assertTrue(q.isEmpty());
    }

    @Test
    public void theSeventeenthManualIsRejectedNotQueued() {
        OutgoingChatQueue q = new OutgoingChatQueue();
        for (int i = 1; i <= 16; i++) {
            assertNull(q.enqueue(req(OutgoingChatKind.MANUAL, "m" + i)).rejected);
        }
        OutgoingChatRequest extra = req(OutgoingChatKind.MANUAL, "m17");
        OutgoingChatQueue.Displacement d = q.enqueue(extra);
        assertSame(extra, d.rejected);
        assertEquals(16, q.heldManualCount());
        assertEquals("m1", q.peekManual().text);
    }
}
