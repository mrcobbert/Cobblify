package com.bedwarsqol.rewind;

import com.bedwarsqol.rewind.watch.PlaybackGate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A7, the client half: one TICK_HEAD per {@code Minecraft.runTick} head of a recorded segment, none when no segment
 * is recorded or while a recording plays, its count equal to the handled increase since the previous head, and
 * 100k heads allocate nothing.
 */
public class TickHeadCaptureTest {

    private static final int A = 0x23;

    private RecordingCaptureSink sink;
    private HandledRing ring;
    private PacketStats.Feed feed;

    @Before
    public void setUp() {
        sink = new RecordingCaptureSink();
        feed = new PacketStats.Feed();
        ring = new HandledRing(feed);
        ActionCapture.bind(sink, ring, feed);
        ActionCapture.startSegment(1);
    }

    @After
    public void tearDown() {
        ActionCapture.unbind();
        if (PlaybackGate.global().isMuted()) {
            PlaybackGate.global().exit();
        }
    }

    private int[] head(RecordingCaptureSink.Rec r) {
        ExtCodec.TickHead h = new ExtCodec.TickHead().read(r.body, 0, r.body.length);
        return new int[]{h.handled, h.flags};
    }

    @Test
    public void oneHeadPerTickCountingTheHandledIncrease() {
        for (int i = 1; i <= 5; i++) {
            ring.push(1, i == 1 ? 0x01 : A, true, i);
        }
        ActionCapture.onPacketRun(0x01);
        ActionCapture.onPacketRun(A);
        ActionCapture.onPacketRun(A);
        ActionCapture.onRunTickHead();
        ActionCapture.onRunTickHead();
        ActionCapture.onPacketRun(A);
        ActionCapture.onPacketRun(A);
        ActionCapture.onRunTickHead();

        List<RecordingCaptureSink.Rec> heads = sink.ofTag(RewindFormat.TAG_TICK_HEAD);
        assertEquals(3, heads.size());
        assertEquals(3, sink.recs.size());
        assertEquals(3, head(heads.get(0))[0]);
        assertEquals(0, head(heads.get(1))[0]);
        assertEquals(2, head(heads.get(2))[0]);
        for (RecordingCaptureSink.Rec r : heads) {
            assertEquals(0, head(r)[1]);
        }
    }

    @Test
    public void noHeadWhenTheSegmentIsNotRecordedOrWhileMutedOrUnbound() {
        sink.recording = false;
        ActionCapture.onRunTickHead();
        sink.recording = true;
        PlaybackGate.global().enter();
        ActionCapture.onRunTickHead();
        PlaybackGate.global().exit();
        ActionCapture.unbind();
        ActionCapture.onRunTickHead();
        assertEquals(0, sink.appends);
    }

    @Test
    public void packetsHandledWhileTheSegmentWasNotRecordedAreNotCountedLater() {
        ring.push(1, 0x01, true, 1);
        ring.push(1, A, true, 2);
        sink.recording = false;
        ActionCapture.onPacketRun(0x01);
        ActionCapture.onRunTickHead();
        sink.recording = true;
        ActionCapture.onPacketRun(A);
        ActionCapture.onRunTickHead();
        List<RecordingCaptureSink.Rec> heads = sink.ofTag(RewindFormat.TAG_TICK_HEAD);
        assertEquals(1, heads.size());
        assertEquals(1, head(heads.get(0))[0]);
    }

    @Test
    public void aResyncMarksTheNextHeadUnknownAndCountsIt() {
        ring.push(1, 0x01, true, 1);
        ring.push(1, A, true, 2);
        ring.push(1, 0x12, true, 3);
        ActionCapture.onPacketRun(0x01);
        ActionCapture.onPacketRun(0x12); // the S23 was dropped by another mod
        ActionCapture.onRunTickHead();
        ActionCapture.onRunTickHead();
        List<RecordingCaptureSink.Rec> heads = sink.ofTag(RewindFormat.TAG_TICK_HEAD);
        assertEquals(3, head(heads.get(0))[0]);
        assertEquals(RewindFormat.TICK_HEAD_ORDER_UNKNOWN, head(heads.get(0))[1]);
        assertEquals(0, head(heads.get(1))[1]);
        long[] counters = feed.handledCounters();
        assertEquals("resyncs", 1L, counters[0]);
        assertEquals("order-unknown ticks", 1L, counters[2]);
    }

    @Test
    public void withoutAHandledRingEveryHeadSaysTheOrderIsUnknown() {
        ActionCapture.bind(sink, null, feed);
        ActionCapture.startSegment(1);
        ActionCapture.onRunTickHead();
        List<RecordingCaptureSink.Rec> heads = sink.ofTag(RewindFormat.TAG_TICK_HEAD);
        assertEquals(1, heads.size());
        assertEquals(0, head(heads.get(0))[0]);
        assertEquals(RewindFormat.TICK_HEAD_ORDER_UNKNOWN, head(heads.get(0))[1]);
    }

    static volatile Object keepAlive;

    @Test(timeout = 120_000)
    public void aHundredThousandHeadsAllocateNothing() {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long self = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(self);
        for (int i = 0; i < 100_000; i++) {
            keepAlive = new byte[1 + (i & 7)];
        }
        assertTrue("allocation meter works", mx.getThreadAllocatedBytes(self) - before >= 100_000 * 16L);
        sink.keep = false;
        ActionCapture.bind(sink, ring, feed); // a timed test runs on its own thread: make it the client thread
        ActionCapture.startSegment(1);
        int ordinal = 0;
        for (int i = 0; i < 300_000; i++) {
            ring.push(1, A, true, ++ordinal);
            ActionCapture.onPacketRun(A);
            ActionCapture.onRunTickHead();
        }
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            long a = mx.getThreadAllocatedBytes(self);
            for (int i = 0; i < 100_000; i++) {
                ring.push(1, A, true, ++ordinal);
                ActionCapture.onPacketRun(A);
                ActionCapture.onRunTickHead();
            }
            best = Math.min(best, mx.getThreadAllocatedBytes(self) - a);
        }
        assertEquals(600_000, sink.appends);
        assertEquals(RewindFormat.TAG_TICK_HEAD, sink.lastTag);
        assertTrue("100k heads allocate " + best + " B", best < 50_000);
    }
}
