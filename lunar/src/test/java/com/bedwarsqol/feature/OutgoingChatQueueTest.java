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
}
