package com.bedwarsqol.rewind;

import com.bedwarsqol.rewind.watch.PlaybackGate;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.BlockPos;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayDeque;
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
 * A7 and C2 (capture side): the real capture handler on an {@link EmbeddedChannel}, and a fake client thread that
 * runs the queued packet "handlers" only when the test drains it, as {@code Minecraft.runGameLoop} drains its
 * scheduled tasks before the tick loop. Each TICK_HEAD must count exactly the stored packets whose handler had run by
 * then, whatever their arrival stamps say.
 */
public class HandledOrderTest {

    private static final int S23 = 0x23;
    private static final int S12 = 0x12;

    /** {@code packet_handler} stand-in: queues the id of every frame whose handler vanilla runs on the client thread. */
    static final class ClientQueue extends ChannelInboundHandlerAdapter {
        final ArrayDeque<Integer> scheduled = new ArrayDeque<Integer>();
        int delivered;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            delivered++;
            ByteBuf b = (ByteBuf) msg;
            int id = b.getByte(b.readerIndex()) & 0x7F; // every id here is one varint byte
            if (HandledRing.clientHandled(id)) {
                scheduled.add(id);
            }
        }
    }

    static final class Clock implements CaptureSession.Clock {
        long nowUs;

        @Override
        public long nowUs() {
            return nowUs;
        }
    }

    private final Clock clock = new Clock();
    private RecordingCaptureSink sink;
    private PacketStats.Feed feed;
    private HandledRing ring;
    private CaptureSession session;
    private RecordChunk.Producer producer;
    private ClientQueue client;
    private EmbeddedChannel ch;
    private DigCaptureTest.MapWorld world;
    private int boundaries;

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    @Before
    public void setUp() {
        build(HandledRing.CAPACITY);
    }

    private void build(int ringCapacity) {
        sink = new RecordingCaptureSink();
        feed = new PacketStats.Feed();
        ring = new HandledRing(ringCapacity, feed);
        producer = new RecordChunk.Producer(64 * 1024, 2, 8, 0);
        session = new CaptureSession(producer, clock, null, ring);
        client = new ClientQueue();
        ch = RewindCaptureTest.remotePipeline(client);
        assertTrue(RewindCapture.install(ch, false, session) != null);
        ActionCapture.bind(sink, ring, feed);
        world = new DigCaptureTest.MapWorld(true);
        boundaries = 0;
    }

    @After
    public void tearDown() {
        ActionCapture.unbind();
        if (PlaybackGate.global().isMuted()) {
            PlaybackGate.global().exit();
        }
    }

    private void at(double ms) {
        clock.nowUs = Math.round(ms * 1000);
        sink.nowUs = clock.nowUs;
    }

    private void capture(byte[]... frames) {
        RewindCaptureTest.fire(ch, frames);
        ch.checkException();
    }

    /** Runs every queued handler; {@code drop} = the queued positions another mod swallows. */
    private void drain(int... drop) {
        int i = 0;
        Integer id;
        while ((id = client.scheduled.poll()) != null) {
            boolean dropped = false;
            for (int d : drop) {
                dropped |= d == i;
            }
            i++;
            if (dropped) {
                continue;
            }
            ActionCapture.onPacketRun(id); // PacketThreadUtil$1.run HEAD
            if (id == 0x01 || id == 0x07) {
                ActionCapture.startSegment(++boundaries); // WORLD_LOAD, inside the boundary's handler
            }
        }
    }

    private int[] tickHead() {
        int before = sink.ofTag(RewindFormat.TAG_TICK_HEAD).size();
        ActionCapture.onRunTickHead();
        List<RecordingCaptureSink.Rec> heads = sink.ofTag(RewindFormat.TAG_TICK_HEAD);
        assertEquals("one head per tick", before + 1, heads.size());
        RecordingCaptureSink.Rec r = heads.get(heads.size() - 1);
        ExtCodec.TickHead h = new ExtCodec.TickHead().read(r.body, 0, r.body.length);
        return new int[]{h.handled, h.flags};
    }

    private static void assertHead(int handled, int flags, int[] head) {
        assertEquals("handled", handled, head[0]);
        assertEquals("flags", flags, head[1]);
    }

    private void join() {
        at(0);
        capture(s01(1, 0));
        drain();
        at(50);
        assertHead(1, 0, tickHead());
    }

    @Test
    public void aPacketCapturedBeforeAHeadButHandledAfterItIsCountedAtTheNextHead() {
        join();
        at(99.5);
        capture(other(S23, 15)); // arrives, waits in the queue
        at(100);
        assertHead(0, 0, tickHead()); // not handled yet
        at(101);
        ActionCapture.enterLocal();
        world.change(new BlockPos(2, 64, 2), Blocks.wool.getDefaultState()); // own placement in tick 100
        ActionCapture.exitLocal();
        drain(); // the next frame's drain runs the S23
        at(150);
        assertHead(1, 0, tickHead());

        List<RecordingCaptureSink.Rec> blocks = sink.ofTag(RewindFormat.TAG_BLOCK);
        assertEquals(1, blocks.size());
        assertEquals(101_000L, blocks.get(0).timeUs);
        CaptureSessionTest.Drain d = new CaptureSessionTest.Drain(producer).drain();
        assertEquals(2, d.recs.size());
        assertEquals("the S23's stamp is before the head that did not count it", 99_500L, d.recs.get(1).timeUs);
    }

    @Test
    public void aPacketCapturedAndHandledBeforeAHeadIsCounted() {
        join();
        at(120);
        capture(other(S23, 15));
        drain();
        at(150);
        assertHead(1, 0, tickHead());
    }

    @Test
    public void unstoredAndNettyThreadFramesBetweenStoredOnesKeepTheCountRight() {
        join();
        at(60);
        capture(other(S23, 12), s3f("MC|Brand", new byte[]{3, 'f', 'a', 'k'}), frame(0x00, varInt(9)),
                s47("H", "F"), other(S12, 9));
        drain();
        at(100);
        assertHead(2, 0, tickHead());
        assertEquals(0, ring.resyncs());
        assertEquals(0, ring.size());
    }

    @Test
    public void aPacketDroppedBetweenCaptureAndExecutionCostsOneResync() {
        join();
        at(60);
        capture(other(S23, 12), other(S12, 12), other(S23, 13));
        drain(1); // another mod swallows the S12
        at(100);
        assertHead(3, RewindFormat.TICK_HEAD_ORDER_UNKNOWN, tickHead());
        at(110);
        capture(other(S23, 14));
        drain();
        at(150);
        assertHead(1, 0, tickHead());
        long[] c = feed.handledCounters();
        assertEquals("resyncs", 1L, c[0]);
        assertEquals("order-unknown ticks", 1L, c[2]);
    }

    @Test
    public void aFullRingMarksTheSpanUnknownWithoutAnExceptionAndCaptureGoesOn() {
        build(4);
        join();
        at(60);
        for (int i = 0; i < 10; i++) {
            capture(other(S23, 12 + i));
        }
        assertEquals("every message delivered", 11, client.delivered);
        assertFalse(session.failed());
        assertEquals(6, ring.overflows());
        assertEquals(6L, feed.handledCounters()[1]);
        drain();
        at(100);
        assertHead(4, RewindFormat.TICK_HEAD_ORDER_UNKNOWN, tickHead());
        at(110);
        capture(other(S23, 30));
        drain();
        at(150);
        assertHead(7, RewindFormat.TICK_HEAD_ORDER_UNKNOWN, tickHead()); // the count restarts from the absolute ordinal
        at(160);
        capture(other(S23, 31));
        drain();
        at(200);
        assertHead(1, 0, tickHead());
        CaptureSessionTest.Drain d = new CaptureSessionTest.Drain(producer).drain();
        assertEquals("every frame stored", 13, d.recs.size());
    }

    @Test
    public void aSegmentBoundaryRestartsTheOrdinal() {
        join();
        at(60);
        capture(other(S23, 12), other(S23, 13));
        drain();
        at(100);
        assertHead(2, 0, tickHead());
        at(120);
        capture(s07(-1), other(S12, 9), other(S23, 10));
        drain();
        at(150);
        assertHead(3, 0, tickHead());
        assertEquals(2, ring.segment());
        assertEquals(3, ring.handled());
    }

    @Test
    public void everyMessageStillPassesOnceWithTheRingOn() {
        join();
        List<byte[]> frames = new ArrayList<byte[]>();
        for (int i = 0; i < 50; i++) {
            frames.add(other(0x02 + i % 0x30, 5 + i));
        }
        capture(frames.toArray(new byte[0][]));
        assertEquals(51, client.delivered);
        assertTrue(ch.outboundMessages().isEmpty());
    }
}
