package com.bedwarsqol.rewind;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.bedwarsqol.rewind.CaptureSessionTest.frame;
import static com.bedwarsqol.rewind.CaptureSessionTest.other;
import static com.bedwarsqol.rewind.CaptureSessionTest.s01;
import static com.bedwarsqol.rewind.CaptureSessionTest.s07;
import static com.bedwarsqol.rewind.CaptureSessionTest.s3f;
import static com.bedwarsqol.rewind.CaptureSessionTest.s47;
import static com.bedwarsqol.rewind.CaptureSessionTest.varInt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The handled-order ring (PLAN section A "Handled order", A7): the capture handler pushes one entry per frame whose
 * handler vanilla runs on the client thread, the client thread pops one per executed packet, and each tick head takes
 * the increase of the highest handled ordinal. A packet another mod drops costs one resync, a full ring an overflow;
 * neither throws, and both mark the next head's order unknown.
 */
public class HandledRingTest {

    private static final int A = 0x23;
    private static final int B = 0x12;
    private static final int C = 0x2F;
    private static final int D = 0x14;
    private static final int E = 0x15;
    private static final int F = 0x16;

    @Test
    public void onlyTheFiveNettyThreadHandlersAreNotClientHandled() {
        for (int id : new int[]{0x00, 0x40, 0x46, 0x47, 0x48}) {
            assertFalse("0x" + Integer.toHexString(id), HandledRing.clientHandled(id));
        }
        for (int id = 0x01; id <= 0x49; id++) {
            if (id != 0x40 && id != 0x46 && id != 0x47 && id != 0x48) {
                assertTrue("0x" + Integer.toHexString(id), HandledRing.clientHandled(id));
            }
        }
    }

    @Test
    public void popsInCaptureOrderAndEachHeadTakesTheIncrease() {
        PacketStats.Feed feed = new PacketStats.Feed();
        HandledRing r = new HandledRing(feed);
        assertTrue(r.push(1, 0x01, true, 1));
        assertTrue(r.push(1, A, true, 2));
        assertTrue(r.push(1, B, false, 2)); // unstored (an S3F): keeps the queue aligned, counts nothing
        assertTrue(r.push(1, C, true, 3));
        assertEquals(4, r.size());
        assertEquals(0x01, r.peekPacketId());

        r.onHandled(0x01);
        r.onHandled(A);
        assertEquals(1, r.segment());
        assertEquals(2, r.handled());
        r.takeHead(1);
        assertEquals(2, r.headIncrease());
        assertEquals(0, r.headFlags());

        r.onHandled(B);
        assertEquals("an unstored entry counts nothing", 2, r.handled());
        r.takeHead(1);
        assertEquals(0, r.headIncrease());

        r.onHandled(C);
        r.takeHead(1);
        assertEquals(1, r.headIncrease());
        assertEquals(0, r.headFlags());
        assertEquals(0, r.size());
        assertEquals(-1, r.peekPacketId());
        assertEquals(0, r.resyncs());
        assertEquals(0L, feed.handledCounters()[0]);
    }

    @Test
    public void aDroppedPacketCostsOneResyncAndTheCountIsRightFromTheNextPacket() {
        PacketStats.Feed feed = new PacketStats.Feed();
        HandledRing r = new HandledRing(feed);
        r.push(1, 0x01, true, 1);
        r.push(1, A, true, 2);
        r.push(1, B, true, 3); // another mod drops this one: its handler never runs
        r.push(1, C, true, 4);
        r.push(1, D, true, 5);
        r.onHandled(0x01);
        r.onHandled(A);
        r.takeHead(1);
        assertEquals(2, r.headIncrease());
        assertEquals(0, r.headFlags());

        r.onHandled(C);
        assertEquals(1, r.resyncs());
        assertEquals(1L, feed.handledCounters()[0]);
        assertEquals(4, r.handled());
        r.takeHead(1);
        assertEquals(2, r.headIncrease());
        assertEquals(RewindFormat.TICK_HEAD_ORDER_UNKNOWN, r.headFlags());

        r.onHandled(D);
        r.takeHead(1);
        assertEquals(1, r.headIncrease());
        assertEquals("clean again at the next head", 0, r.headFlags());
        assertEquals(1, r.resyncs());
    }

    @Test
    public void aPacketTheHandlerNeverSawConsumesNothing() {
        HandledRing r = new HandledRing(null);
        r.onHandled(A); // empty ring: a packet a mod ran on its own
        assertEquals(1, r.resyncs());
        r.push(1, 0x01, true, 1);
        r.push(1, B, true, 2);
        r.onHandled(E); // no entry with this id: nothing is consumed
        assertEquals(2, r.resyncs());
        assertEquals(2, r.size());
        r.onHandled(0x01);
        r.onHandled(B);
        assertEquals(2, r.handled());
        r.takeHead(1);
        assertEquals(2, r.headIncrease());
        assertEquals(RewindFormat.TICK_HEAD_ORDER_UNKNOWN, r.headFlags());
        r.takeHead(1);
        assertEquals(0, r.headFlags());
    }

    @Test
    public void aFullRingCountsAnOverflowNeverThrowsAndMarksTheSpanUnknown() {
        PacketStats.Feed feed = new PacketStats.Feed();
        HandledRing r = new HandledRing(4, feed);
        assertTrue(r.push(1, 0x01, true, 1));
        assertTrue(r.push(1, A, true, 2));
        assertTrue(r.push(1, B, true, 3));
        assertTrue(r.push(1, C, true, 4));
        assertFalse("full", r.push(1, E, true, 5)); // lost
        assertEquals(1, r.overflows());
        assertEquals(1L, feed.handledCounters()[1]);

        r.onHandled(0x01);
        assertTrue(r.push(1, F, true, 6)); // the first entry after the gap
        r.onHandled(A);
        r.onHandled(B);
        r.onHandled(C);
        r.takeHead(1);
        assertEquals(4, r.headIncrease());
        assertEquals("every entry before the gap is exact", 0, r.headFlags());

        r.onHandled(E); // the lost packet runs: no entry, and no scan past the gap
        assertEquals(1, r.size());
        r.onHandled(F);
        assertEquals(0, r.size());
        assertEquals(6, r.handled());
        r.takeHead(1);
        assertEquals(2, r.headIncrease());
        assertEquals(RewindFormat.TICK_HEAD_ORDER_UNKNOWN, r.headFlags());
        r.takeHead(1);
        assertEquals(0, r.headFlags());
    }

    @Test
    public void theDefaultRingHolds65536EntriesAndOverflowsOnTheNext() {
        HandledRing r = new HandledRing(null);
        for (int i = 0; i < HandledRing.CAPACITY; i++) {
            assertTrue("entry " + i, r.push(1, A, true, i + 1));
        }
        assertEquals(65_536, r.size());
        assertFalse(r.push(1, A, true, 65_537));
        assertEquals(1, r.overflows());
        for (int i = 0; i < HandledRing.CAPACITY; i++) {
            r.onHandled(A);
        }
        assertEquals(65_536, r.handled());
        assertEquals(0, r.resyncs());
    }

    @Test
    public void aNewSegmentRestartsTheCount() {
        HandledRing r = new HandledRing(null);
        r.push(1, 0x01, true, 1);
        r.push(1, A, true, 2);
        r.push(2, 0x07, true, 1);
        r.push(2, A, true, 2);
        r.onHandled(0x01);
        r.onHandled(A);
        r.takeHead(1);
        assertEquals(2, r.headIncrease());
        r.onHandled(0x07);
        assertEquals(2, r.segment());
        assertEquals(1, r.handled());
        r.takeHead(2);
        assertEquals(1, r.headIncrease());
        assertEquals(0, r.headFlags());
        r.onHandled(A);
        r.takeHead(2);
        assertEquals(1, r.headIncrease());
    }

    @Test
    public void aHeadForASegmentWithNothingHandledIsZeroAndUnknown() {
        HandledRing r = new HandledRing(null);
        r.push(1, 0x01, true, 1);
        r.onHandled(0x01);
        r.takeHead(2);
        assertEquals(0, r.headIncrease());
        assertEquals(RewindFormat.TICK_HEAD_ORDER_UNKNOWN, r.headFlags());
    }

    // ------------------------------------------------------------------ the capture side

    private static final class Pops {
        final List<String> trace = new ArrayList<String>();
    }

    /** Pops every entry, writing "segment:id:handled" after each. */
    private static Pops popAll(HandledRing r) {
        Pops p = new Pops();
        int id;
        while ((id = r.peekPacketId()) >= 0) {
            r.onHandled(id);
            p.trace.add(r.segment() + ":" + Integer.toHexString(id) + ":" + r.handled());
        }
        return p;
    }

    @Test
    public void theCaptureSessionPushesEveryClientHandledFrameWithItsStoredOrdinal() {
        RecordChunk.Producer producer = new RecordChunk.Producer(4096, 2, 4, 0);
        HandledRing ring = new HandledRing(null);
        CaptureSession s = new CaptureSession(producer, CaptureSession.NANO_CLOCK, null, ring);
        assertTrue(s.handledRing() == ring);
        CaptureSessionTest.ArrayFrame f = new CaptureSessionTest.ArrayFrame();
        byte[][] frames = {
                s01(1, 0),                      // seg 1, ordinal 1
                other(A, 20),                   // ordinal 2
                s47("H", "F"),                  // stored, Netty-thread handler: not pushed, no ordinal
                s3f("MC|Brand", new byte[]{1}), // client-handled, never stored: pushed unstored
                frame(0x00, varInt(5)),         // keep-alive: Netty thread, not pushed
                frame(0x40, varInt(0)),         // disconnect: stored, Netty thread, not pushed
                other(B, 9),                    // ordinal 3
                s07(-1),                        // seg 2, ordinal 1
                other(C, 7)};                   // ordinal 2
        for (byte[] b : frames) {
            s.onFrame(f.set(b));
        }
        assertFalse(s.failed());
        Pops p = popAll(ring);
        List<String> expected = new ArrayList<String>();
        expected.add("1:1:1");
        expected.add("1:" + Integer.toHexString(A) + ":2");
        expected.add("1:3f:2");
        expected.add("1:" + Integer.toHexString(B) + ":3");
        expected.add("2:7:1");
        expected.add("2:" + Integer.toHexString(C) + ":2");
        assertEquals(expected, p.trace);
        assertEquals(0, ring.resyncs());
    }

    @Test
    public void framesOfASkippedOrFailedSegmentArePushedUnstored() {
        RecordChunk.Producer producer = new RecordChunk.Producer(4096, 2, 4, 0);
        HandledRing ring = new HandledRing(null);
        CaptureSession s = new CaptureSession(producer, CaptureSession.NANO_CLOCK, null, ring);
        CaptureSessionTest.ArrayFrame f = new CaptureSessionTest.ArrayFrame();
        s.onFrame(f.set(s01(1, 0)));
        s.onFrame(f.set(other(A, 10)));
        s.skipSegment(1);
        s.onFrame(f.set(other(B, 10)));
        s.fail(new IllegalStateException("test"));
        s.onFrame(f.set(other(C, 10)));
        s.onLocalUnstored(D);
        s.onLocalUnstored(0x00); // keep-alive: not client-handled
        Pops p = popAll(ring);
        List<String> expected = new ArrayList<String>();
        expected.add("1:1:1");
        expected.add("1:" + Integer.toHexString(A) + ":2");
        expected.add("1:" + Integer.toHexString(B) + ":2");
        expected.add("1:" + Integer.toHexString(C) + ":2");
        expected.add("1:" + Integer.toHexString(D) + ":2");
        assertEquals(expected, p.trace);
    }

    @Test
    public void aFullRingNeverFailsTheCaptureSession() {
        RecordChunk.Producer producer = new RecordChunk.Producer(64 * 1024, 2, 4, 0);
        HandledRing ring = new HandledRing(8, null);
        CaptureSession s = new CaptureSession(producer, CaptureSession.NANO_CLOCK, null, ring);
        CaptureSessionTest.ArrayFrame f = new CaptureSessionTest.ArrayFrame();
        s.onFrame(f.set(s01(1, 0)));
        for (int i = 0; i < 100; i++) {
            s.onFrame(f.set(other(A, 10)));
        }
        assertFalse(s.failed());
        assertEquals(8, ring.size());
        assertEquals(93, ring.overflows());
        CaptureSessionTest.Drain d = new CaptureSessionTest.Drain(producer).drain();
        assertEquals("every frame still stored", 101, d.recs.size());
    }
}
