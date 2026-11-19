package com.bedwarsqol.rewind;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.network.play.server.S47PacketPlayerListHeaderFooter;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.bedwarsqol.rewind.CaptureSessionTest.assertRecords;
import static com.bedwarsqol.rewind.CaptureSessionTest.other;
import static com.bedwarsqol.rewind.CaptureSessionTest.s01;
import static com.bedwarsqol.rewind.CaptureSessionTest.s07;
import static com.bedwarsqol.rewind.CaptureSessionTest.s3f;
import static com.bedwarsqol.rewind.CaptureSessionTest.s47;
import static com.bedwarsqol.rewind.CaptureSessionTest.frame;
import static com.bedwarsqol.rewind.CaptureSessionTest.varInt;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The Netty capture handler on an {@link EmbeddedChannel} whose handlers carry vanilla's names
 * ({@code timeout, splitter, decoder, prepender, encoder, packet_handler}; single-player has only
 * {@code packet_handler}). Pins A14 (read-only pass-through), A9 (no allocation per packet on the remote
 * path), A8 (failures never touch the connection; only {@code rearm} restarts copying), A10 (no handler when
 * off or not Hypixel), boundary ownership, the S47 freeze, the S46 reposition and the local Packet path.
 */
public class RewindCaptureTest {

    // ------------------------------------------------------------------ pipelines

    /** The remote play pipeline as vanilla names it, with {@code packetHandler} as {@code packet_handler}. */
    static EmbeddedChannel remotePipeline(ChannelHandler packetHandler) {
        ChannelInboundHandlerAdapter placeholder = new ChannelInboundHandlerAdapter();
        EmbeddedChannel ch = new EmbeddedChannel(placeholder);
        ChannelPipeline p = ch.pipeline();
        p.addFirst("packet_handler", packetHandler);
        p.addFirst("encoder", new ChannelOutboundHandlerAdapter());
        p.addFirst("prepender", new ChannelOutboundHandlerAdapter());
        p.addFirst("decoder", new ChannelInboundHandlerAdapter());
        p.addFirst("splitter", new ChannelInboundHandlerAdapter());
        p.addFirst("timeout", new ChannelInboundHandlerAdapter());
        p.remove(placeholder);
        return ch;
    }

    /** The single-player pipeline: only {@code packet_handler}. */
    static EmbeddedChannel localPipeline(ChannelHandler packetHandler) {
        ChannelInboundHandlerAdapter placeholder = new ChannelInboundHandlerAdapter();
        EmbeddedChannel ch = new EmbeddedChannel(placeholder);
        ch.pipeline().addFirst("packet_handler", packetHandler);
        ch.pipeline().remove(placeholder);
        return ch;
    }

    /** {@code packet_handler} stand-in: remembers what reached it, never forwards or releases. */
    static class Received extends ChannelInboundHandlerAdapter {
        final List<Object> messages = new ArrayList<Object>();
        final List<Integer> readerIndex = new ArrayList<Integer>();
        final List<Integer> refCnt = new ArrayList<Integer>();
        final List<byte[]> content = new ArrayList<byte[]>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            messages.add(msg);
            if (msg instanceof ByteBuf) {
                ByteBuf b = (ByteBuf) msg;
                readerIndex.add(b.readerIndex());
                refCnt.add(b.refCnt());
                content.add(bytes(b));
            }
        }
    }

    /** {@code packet_handler} stand-in for the allocation run: counts and records its thread. */
    static final class Counting extends ChannelInboundHandlerAdapter {
        long count;
        long threadId = -1;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            count++;
            threadId = Thread.currentThread().getId();
        }
    }

    static byte[] bytes(ByteBuf b) {
        byte[] out = new byte[b.readableBytes()];
        b.getBytes(b.readerIndex(), out);
        return out;
    }

    /** Every handler test runs with the handled-order ring on (Rewind parity Phase 2), as the recorder will. */
    static CaptureSession session(RecordChunk.Producer p, CaptureSession.Clock clock, final List<Object[]> locations) {
        return new CaptureSession(p, clock, new CaptureSession.LocationSink() {
            @Override
            public void onLocation(int segment, byte[] payload) {
                if (locations != null) {
                    locations.add(new Object[]{segment, payload});
                }
            }
        }, new HandledRing(null));
    }

    static CaptureSession session(RecordChunk.Producer p) {
        return session(p, CaptureSession.NANO_CLOCK, null);
    }

    static void fire(EmbeddedChannel ch, byte[]... frames) {
        for (byte[] f : frames) {
            ch.pipeline().fireChannelRead(Unpooled.wrappedBuffer(f));
        }
    }

    enum Kind { HEAP, HEAP_OFFSET, DIRECT, DIRECT_SLICE, HEAP_SLICE, READ_ONLY }

    static ByteBuf buffer(Kind kind, byte[] f) {
        switch (kind) {
            case HEAP:
                return Unpooled.wrappedBuffer(f);
            case HEAP_OFFSET: {
                ByteBuf b = Unpooled.buffer(f.length + 3);
                b.writeBytes(new byte[]{9, 9, 9}).writeBytes(f);
                b.readerIndex(3);
                return b;
            }
            case DIRECT:
                return Unpooled.directBuffer(f.length).writeBytes(f);
            case DIRECT_SLICE: {
                ByteBuf big = Unpooled.directBuffer(f.length + 11);
                big.writeBytes(new byte[5]).writeBytes(f).writeBytes(new byte[6]);
                return big.slice(5, f.length);
            }
            case HEAP_SLICE: {
                ByteBuf big = Unpooled.buffer(f.length + 11);
                big.writeBytes(new byte[5]).writeBytes(f).writeBytes(new byte[6]);
                return big.slice(5, f.length);
            }
            case READ_ONLY:
                return Unpooled.wrappedBuffer(ByteBuffer.wrap(f).asReadOnlyBuffer());
            default:
                throw new AssertionError(kind);
        }
    }

    // ------------------------------------------------------------------ A14: read-only pass-through

    @Test
    public void everyMessagePassesOnceInOrderUnchanged() {
        Received rx = new Received();
        EmbeddedChannel ch = remotePipeline(rx);
        // 3 boundaries per kind and no writer: enough small chunks for all of them
        RecordChunk.Producer p = new RecordChunk.Producer(16 * 1024, 2, 64, 0);
        CaptureSession s = session(p);
        assertNotNull(RewindCapture.install(ch, false, s));

        byte[][] frames = {
                s01(1, 0), frame(0x00, varInt(77)), s47("H", "F"), s3f("hyevent:location", new byte[]{1, 1, 1, 'a', 0, 0, 0, 0}),
                s3f("MC|Brand", new byte[]{3, 'f', 'a', 'k'}), frame(0x46, varInt(256)), other(0x26, 3000),
                s07(-1), other(0x15, 17), s07(-1), other(0x0F, 9), s01(2, 0), other(0x21, 1)};
        List<ByteBuf> sent = new ArrayList<ByteBuf>();
        List<Integer> sentIndex = new ArrayList<Integer>();
        int k = 0;
        for (Kind kind : Kind.values()) {
            for (byte[] f : frames) {
                ByteBuf b = buffer(kind, f);
                sent.add(b);
                sentIndex.add(b.readerIndex());
                ch.pipeline().fireChannelRead(b);
                k++;
            }
        }
        ch.checkException();
        assertTrue(ch.isOpen());
        assertTrue(ch.outboundMessages().isEmpty());
        assertEquals(k, rx.messages.size());
        for (int i = 0; i < k; i++) {
            assertSame("message " + i, sent.get(i), rx.messages.get(i));
            assertEquals("readerIndex " + i, sentIndex.get(i), rx.readerIndex.get(i));
            assertEquals("refCnt " + i, 1, (int) rx.refCnt.get(i));
            assertArrayEquals("content " + i, frames[i % frames.length], rx.content.get(i));
            assertEquals(sentIndex.get(i).intValue(), sent.get(i).readerIndex());
            assertEquals(1, sent.get(i).refCnt());
        }
        assertFalse(s.failureCause() + " reason " + s.failureReason(), s.failed());

        // and every kind was recorded byte for byte, in order, with the skip list applied
        List<byte[]> expected = new ArrayList<byte[]>();
        for (int i = 0; i < k; i++) {
            byte[] f = frames[i % frames.length];
            if (!RewindFormat.isSkipped(f[0])) {
                expected.add(f);
            }
        }
        CaptureSessionTest.Drain d = new CaptureSessionTest.Drain(p).drain();
        List<byte[]> actual = new ArrayList<byte[]>();
        for (CaptureSessionTest.Rec r : d.recs) {
            actual.add(r.bytes);
        }
        assertEquals(Kind.values().length * 9, expected.size());
        assertRecords(actual, expected.toArray(new byte[0][]));
    }

    // ------------------------------------------------------------------ A9: no allocation per packet

    @Test(timeout = 300_000)
    public void remotePathAllocatesNothingPerPacketOnEveryBufferKind() throws Exception {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long self = Thread.currentThread().getId();
        // the meter must see a per-packet allocation if there were one: 100k 16-byte arrays
        long before = mx.getThreadAllocatedBytes(self);
        for (int i = 0; i < 100_000; i++) {
            sink = new byte[1 + (i & 7)];
        }
        long seen = mx.getThreadAllocatedBytes(self) - before;
        assertTrue("allocation meter works: " + seen, seen >= 100_000 * 16L);
        StringBuilder report = new StringBuilder();
        for (Kind kind : new Kind[]{Kind.HEAP, Kind.DIRECT, Kind.DIRECT_SLICE, Kind.READ_ONLY}) {
            ByteBuf[] pool = allocationPool(kind);
            final RecordChunk.Producer p = new RecordChunk.Producer(RewindFormat.CHUNK_CAPACITY, 6, 6, 0);
            CaptureSession s = session(p);
            Counting withRx = new Counting();
            Counting withoutRx = new Counting();
            EmbeddedChannel with = remotePipeline(withRx);
            EmbeddedChannel without = remotePipeline(withoutRx);
            RewindCapture.install(with, false, s);
            HandledRing ring = s.handledRing();
            Consumer consumer = new Consumer(p);
            consumer.start();
            try {
                with.pipeline().fireChannelRead(Unpooled.wrappedBuffer(s01(1, 0)));
                runDraining(with, pool, 300_000, ring);
                run(without, pool, 300_000);
                long bestWith = Long.MAX_VALUE;
                long bestWithout = Long.MAX_VALUE;
                for (int round = 0; round < 3; round++) {
                    long a = mx.getThreadAllocatedBytes(self);
                    run(without, pool, 100_000);
                    long b = mx.getThreadAllocatedBytes(self);
                    runDraining(with, pool, 100_000, ring);
                    long c = mx.getThreadAllocatedBytes(self);
                    bestWithout = Math.min(bestWithout, b - a);
                    bestWith = Math.min(bestWith, c - b);
                }
                consumer.stop();
                assertFalse(kind + " session failed: " + s.failureCause() + " reason " + s.failureReason(), s.failed());
                assertEquals(self, withRx.threadId);
                assertNotEquals(self, consumer.threadId);
                assertTrue(kind + " consumer saw chunks", consumer.chunks.get() > 0);
                // the ring was pushed for every client-handled frame and popped in order: no overflow, no resync
                assertEquals(kind + " ring overflows", 0, ring.overflows());
                assertEquals(kind + " ring resyncs", 0, ring.resyncs());
                assertTrue(kind + " ring popped", ring.handled() > 0);
                assertEquals(kind + " every message delivered", 1 + 300_000 + 3 * 100_000, withRx.count);
                double perPacket = (bestWith - bestWithout) / 100_000.0;
                report.append(String.format("%s(array=%s, address=%s): with %d B, without %d B, %.4f B/packet; ",
                        kind, pool[1].hasArray(), pool[1].hasMemoryAddress(), bestWith, bestWithout, perPacket));
                assertTrue(kind + " allocates " + perPacket + " B/packet", perPacket < 0.5);
            } finally {
                consumer.stop();
            }
        }
        System.out.println("RewindCapture A9 allocation: " + report);
    }

    /** Keeps the meter check's arrays from being optimised away. */
    static volatile Object sink;

    /** A7: with nobody popping, the ring is full after 16 entries; every push after that is an overflow. */
    @Test(timeout = 300_000)
    public void aFullHandledRingAllocatesNothingPerPacketAndNeverFails() throws Exception {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long self = Thread.currentThread().getId();
        // one segment that is never copied (a dropped lobby run): every frame is pushed unstored, no chunk is used
        ByteBuf[] pool = new ByteBuf[1000];
        for (int i = 0; i < pool.length; i++) {
            byte[] f = i % 50 == 0 ? frame(0x00, varInt(i)) : other(plainId(i), 4 + (i * 7919) % 1497);
            pool[i] = buffer(Kind.HEAP, f);
        }
        final RecordChunk.Producer p = new RecordChunk.Producer(16 * 1024, 2, 2, 0);
        PacketStats.Feed feed = new PacketStats.Feed();
        HandledRing ring = new HandledRing(16, feed);
        CaptureSession s = new CaptureSession(p, CaptureSession.NANO_CLOCK, null, ring);
        Counting withRx = new Counting();
        Counting withoutRx = new Counting();
        EmbeddedChannel with = remotePipeline(withRx);
        EmbeddedChannel without = remotePipeline(withoutRx);
        RewindCapture.install(with, false, s);
        with.pipeline().fireChannelRead(Unpooled.wrappedBuffer(s01(1, 0)));
        s.skipSegment(1);
        run(with, pool, 300_000);
        run(without, pool, 300_000);
        long bestWith = Long.MAX_VALUE;
        long bestWithout = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            long a = mx.getThreadAllocatedBytes(self);
            run(without, pool, 100_000);
            long b = mx.getThreadAllocatedBytes(self);
            run(with, pool, 100_000);
            long c = mx.getThreadAllocatedBytes(self);
            bestWithout = Math.min(bestWithout, b - a);
            bestWith = Math.min(bestWith, c - b);
        }
        assertFalse("session failed: " + s.failureCause() + " reason " + s.failureReason(), s.failed());
        assertEquals("every message delivered", 1 + 300_000 + 3 * 100_000, withRx.count);
        assertEquals(16, ring.size());
        assertTrue("overflows counted: " + ring.overflows(), ring.overflows() > 500_000);
        assertEquals(ring.overflows(), feed.handledCounters()[1]);
        double perPacket = (bestWith - bestWithout) / 100_000.0;
        System.out.println(String.format("RewindCapture full ring: with %d B, without %d B, %.4f B/packet",
                bestWith, bestWithout, perPacket));
        assertTrue("allocates " + perPacket + " B/packet", perPacket < 0.5);
    }

    /** 1,000 mixed frames: sizes 4..1,500, S47 every 97, keep-alive every 50, a brand S3F, two boundaries. */
    private static ByteBuf[] allocationPool(Kind kind) {
        ByteBuf[] pool = new ByteBuf[1000];
        for (int i = 0; i < pool.length; i++) {
            byte[] f;
            if (i == 0) {
                f = s07(-1);
            } else if (i == 500) {
                f = s07(0);
            } else if (i % 97 == 0) {
                f = s47("H" + i % 10, "F");
            } else if (i % 50 == 0) {
                f = frame(0x00, varInt(i));
            } else if (i == 333) {
                f = s3f("MC|Brand", new byte[]{3, 'f', 'a', 'k'});
            } else {
                f = other(plainId(i), 4 + (i * 7919) % 1497);
            }
            pool[i] = buffer(kind, f);
        }
        return pool;
    }

    /** A play id that is neither a boundary, the S47 nor on the skip list. */
    static int plainId(int i) {
        int id = 0x02 + i % 0x40;
        return id == 0x07 || id == 0x3F ? 0x30 : id;
    }

    private static void run(EmbeddedChannel ch, ByteBuf[] pool, int n) {
        ChannelPipeline p = ch.pipeline();
        for (int i = 0; i < n; i++) {
            p.fireChannelRead(pool[i % pool.length]);
        }
    }

    /**
     * {@link #run}, popping the handled-order ring every 500 frames on this thread as the client thread would (each
     * entry with its own id), so the measured cost is a push and a pop per client-handled frame.
     */
    private static void runDraining(EmbeddedChannel ch, ByteBuf[] pool, int n, HandledRing ring) {
        ChannelPipeline p = ch.pipeline();
        for (int i = 0; i < n; i++) {
            p.fireChannelRead(pool[i % pool.length]);
            if (i % 500 == 499) {
                int id;
                while ((id = ring.peekPacketId()) >= 0) {
                    ring.onHandled(id);
                }
            }
        }
    }

    /** Writer stand-in on its own thread: takes handed-off chunks and frees them. */
    static final class Consumer implements Runnable {
        private final RecordChunk.Producer p;
        private final AtomicBoolean stop = new AtomicBoolean();
        final AtomicInteger chunks = new AtomicInteger();
        volatile long threadId = -1;
        volatile CountDownLatch gate;
        private Thread thread;

        Consumer(RecordChunk.Producer p) {
            this.p = p;
        }

        void start() {
            thread = new Thread(this, "rewind-test-consumer");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() throws InterruptedException {
            stop.set(true);
            CountDownLatch g = gate;
            if (g != null) {
                g.countDown();
            }
            thread.join(10_000);
        }

        @Override
        public void run() {
            threadId = Thread.currentThread().getId();
            while (!stop.get()) {
                CountDownLatch g = gate;
                if (g != null) {
                    try {
                        g.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                RecordChunk c = p.pollFull();
                if (c == null) {
                    Thread.yield();
                    continue;
                }
                chunks.incrementAndGet();
                p.release(c);
            }
        }
    }

    // ------------------------------------------------------------------ A8: failures

    @Test
    public void throwingSessionLeavesTheConnectionAloneAndOnlyRearmRestartsAtTheNextBoundary() {
        Received rx = new Received();
        EmbeddedChannel ch = remotePipeline(rx);
        RecordChunk.Producer p = new RecordChunk.Producer(4096, 2, 4, 0);
        final AtomicInteger calls = new AtomicInteger();
        CaptureSession s = session(p, new CaptureSession.Clock() {
            @Override
            public long nowUs() {
                if (calls.incrementAndGet() == 3) {
                    throw new IllegalStateException("fake failure");
                }
                return calls.get();
            }
        }, null);
        RewindCapture.install(ch, false, s);
        byte[] join = s01(1, 0);
        byte[] x = other(0x20, 10);
        fire(ch, join, x, other(0x21, 10)); // the third copy throws
        assertTrue(s.failed());
        fire(ch, s07(-1), other(0x22, 10), s01(2, 0), s07(-1), other(0x23, 10));
        assertTrue("same-game boundaries never clear the latch", s.failed());
        assertEquals(4, s.segment());
        s.rearm();
        byte[] notYet = other(0x24, 10);
        byte[] back = s07(0);
        byte[] y = other(0x25, 10);
        fire(ch, notYet, back, y);
        assertFalse(s.failed());

        ch.checkException();
        assertTrue(ch.isOpen());
        assertEquals(11, rx.messages.size());
        CaptureSessionTest.Drain d = new CaptureSessionTest.Drain(p).drain();
        assertEquals(Arrays.asList(1, 5), d.segments());
        assertRecords(d.segment(1), join, x);
        assertRecords(d.segment(5), back, y);
    }

    @Test
    public void exhaustedPoolLatchesAndEveryMessageIsStillDelivered() {
        Received rx = new Received();
        EmbeddedChannel ch = remotePipeline(rx);
        RecordChunk.Producer p = new RecordChunk.Producer(256, 1, 1, 0);
        CaptureSession s = session(p);
        RewindCapture.install(ch, false, s);
        fire(ch, s01(1, 0));
        for (int i = 0; i < 40; i++) {
            fire(ch, other(0x20, 30));
        }
        fire(ch, s07(-1), other(0x20, 30));
        ch.checkException();
        assertTrue(ch.isOpen());
        assertEquals(43, rx.messages.size());
        assertTrue(s.failed());
        assertEquals(CaptureSession.FAIL_NO_CHUNK, s.failureReason());
        assertEquals(2, s.segment());
    }

    @Test(timeout = 60_000)
    public void stalledConsumerLatchesAndRearmResumesAtABoundaryOnceChunksAreFree() throws Exception {
        Received rx = new Received();
        EmbeddedChannel ch = remotePipeline(rx);
        final RecordChunk.Producer p = new RecordChunk.Producer(1024, 2, 3, 0);
        CaptureSession s = session(p);
        RewindCapture.install(ch, false, s);
        Consumer consumer = new Consumer(p);
        consumer.gate = new CountDownLatch(1); // stalled
        consumer.start();
        try {
            fire(ch, s01(1, 0));
            for (int i = 0; i < 100; i++) {
                fire(ch, other(0x20, 100));
            }
            assertTrue(s.failed());
            fire(ch, s07(-1));
            assertTrue(s.failed());
            consumer.gate.countDown(); // the writer catches up
            consumer.gate = null;
            long deadline = System.currentTimeMillis() + 10_000;
            while (consumer.chunks.get() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            fire(ch, s07(0));
            assertTrue("no restart without rearm", s.failed());
            s.rearm();
            fire(ch, s07(-1), other(0x21, 5));
            assertFalse(s.failed());
        } finally {
            consumer.stop();
        }
        ch.checkException();
        assertTrue(ch.isOpen());
        assertEquals(105, rx.messages.size());
    }

    // ------------------------------------------------------------------ boundaries and S47 through the channel

    @Test
    public void boundaryPacketsOpenTheirOwnSegmentThroughTheHandler() {
        EmbeddedChannel ch = remotePipeline(new Received());
        RecordChunk.Producer p = new RecordChunk.Producer(4096, 2, 4, 0);
        CaptureSession s = session(p);
        RewindCapture.install(ch, false, s);
        byte[] join = s01(1, 0);
        byte[] x = other(0x20, 10);
        byte[] nether = s07(-1);
        byte[] y = other(0x21, 10);
        byte[] sameNether = s07(-1);
        byte[] z = other(0x22, 10);
        fire(ch, join, x, nether, y, sameNether, z);
        s.skipSegment(2); // lobby: segment 2 dropped
        byte[] skipped = other(0x23, 10);
        byte[] over = s07(0);
        byte[] w = other(0x24, 10);
        fire(ch, skipped, over, w);
        s.fail(new IllegalStateException("fake"));
        byte[] lost = s01(2, 0);
        fire(ch, lost);
        s.rearm();
        byte[] again = s07(-1);
        byte[] v = other(0x25, 10);
        fire(ch, again, v);

        CaptureSessionTest.Drain d = new CaptureSessionTest.Drain(p).drain();
        assertRecords(d.segment(1), join, x);
        assertRecords(d.segment(2), nether, y, sameNether, z);
        assertRecords(d.segment(3), over, w);
        assertTrue(d.segment(4).isEmpty());
        assertRecords(d.segment(5), again, v);
    }

    @Test
    public void s47IsFrozenAtTheBoundaryThroughTheHandler() {
        EmbeddedChannel ch = remotePipeline(new Received());
        RecordChunk.Producer p = new RecordChunk.Producer(4096, 2, 4, 0);
        CaptureSession s = session(p);
        RewindCapture.install(ch, false, s);
        byte[] h0 = s47("H0", "F0");
        byte[] nether = s07(-1);
        byte[] h1 = s47("H1", "F1");
        fire(ch, s01(1, 0), h0, nether, h1);
        CaptureSessionTest.Drain d = new CaptureSessionTest.Drain(p).drain();
        assertArrayEquals(h0, d.carry(2));
        assertRecords(d.segment(2), nether, h1);

        // the same with copying off (segment 2 skipped)
        s.skipSegment(2);
        byte[] h2 = s47("H2", "F2");
        byte[] over = s07(0);
        byte[] h3 = s47("H3", "F3");
        fire(ch, h2, over, h3);
        d.drain();
        assertArrayEquals(h2, d.carry(3));
        assertRecords(d.segment(3), over, h3);
    }

    @Test
    public void locationFrameIsPostedWithItsSegment() {
        EmbeddedChannel ch = remotePipeline(new Received());
        List<Object[]> locations = new ArrayList<Object[]>();
        CaptureSession s = session(new RecordChunk.Producer(4096, 2, 4, 0), CaptureSession.NANO_CLOCK, locations);
        RewindCapture.install(ch, false, s);
        byte[] payload = {1, 1, 4, 'm', 'i', 'n', 'i', 0, 0, 0, 0};
        fire(ch, s01(1, 0), s07(-1), s3f("hyevent:location", payload));
        assertEquals(1, locations.size());
        assertEquals(2, locations.get(0)[0]);
        assertArrayEquals(payload, (byte[]) locations.get(0)[1]);
    }

    // ------------------------------------------------------------------ install, remove, S46

    @Test
    public void offOrNotHypixelInstallsNoHandler() {
        final AtomicInteger made = new AtomicInteger();
        Supplier<CaptureSession> factory = new Supplier<CaptureSession>() {
            @Override
            public CaptureSession get() {
                made.incrementAndGet();
                return session(new RecordChunk.Producer(4096, 1, 2, 0));
            }
        };
        EmbeddedChannel ch = remotePipeline(new Received());
        assertNull(RewindCapture.installIfEligible(ch, false, true, false, "mc.hypixel.net", factory));
        assertNull(RewindCapture.installIfEligible(ch, true, false, false, "mc.hypixel.net", factory));
        assertNull(RewindCapture.installIfEligible(ch, true, true, false, "play.example.org", factory));
        assertNull(RewindCapture.installIfEligible(ch, true, true, false, null, factory));
        assertNull(ch.pipeline().get(RewindCapture.NAME));
        assertEquals(0, made.get());

        // a remote channel with no decoder gets nothing either
        EmbeddedChannel bare = localPipeline(new Received());
        assertNull(RewindCapture.installIfEligible(bare, true, true, false, "mc.hypixel.net", factory));
        assertNull(bare.pipeline().get(RewindCapture.NAME));
        assertEquals(0, made.get());

        assertNotNull(RewindCapture.installIfEligible(ch, true, true, false, "mc.hypixel.net", factory));
        assertEquals(Arrays.asList("timeout", "splitter", RewindCapture.NAME, "decoder", "prepender", "encoder",
                "packet_handler"), names(ch));
        assertNull("never twice", RewindCapture.installIfEligible(ch, true, true, false, "mc.hypixel.net", factory));
        assertEquals(1, made.get());

        EmbeddedChannel local = localPipeline(new Received());
        assertNotNull(RewindCapture.installIfEligible(local, true, true, true, null, factory));
        assertEquals(Arrays.asList(RewindCapture.NAME, "packet_handler"), names(local));
    }

    @Test
    public void remoteInstallLandsAfterAnExistingDecompress() {
        EmbeddedChannel ch = remotePipeline(new Received());
        ch.pipeline().addBefore("decoder", "decompress", new ChannelInboundHandlerAdapter());
        assertNotNull(RewindCapture.install(ch, false, session(new RecordChunk.Producer(4096, 1, 2, 0))));
        assertEquals(Arrays.asList("timeout", "splitter", "decompress", RewindCapture.NAME, "decoder", "prepender",
                "encoder", "packet_handler"), names(ch));
    }

    static List<String> names(EmbeddedChannel ch) {
        List<String> out = new ArrayList<String>();
        for (String n : ch.pipeline().names()) {
            if (!n.contains("#")) { // skip the generated names (EmbeddedChannel's last inbound handler)
                out.add(n);
            }
        }
        return out;
    }

    @Test
    public void s46MovesTheHandlerAfterANewDecompressOnTheSameSession() {
        Received rx = new Received() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                super.channelRead(ctx, msg);
                ByteBuf b = (ByteBuf) msg;
                if (b.getByte(b.readerIndex()) == 0x46 && ctx.pipeline().get("decompress") == null) {
                    // what vanilla's handleSetCompressionLevel does, on the same thread, inside the read
                    ctx.pipeline().addBefore("decoder", "decompress", new FakeDecompress());
                }
            }
        };
        EmbeddedChannel ch = remotePipeline(rx);
        RecordChunk.Producer p = new RecordChunk.Producer(4096, 2, 4, 0);
        CaptureSession s = session(p);
        RewindCapture first = RewindCapture.install(ch, false, s);
        byte[] join = s01(1, 0);
        byte[] x = other(0x20, 10);
        fire(ch, join, x, frame(0x46, varInt(256)));
        assertEquals(Arrays.asList("timeout", "splitter", "decompress", RewindCapture.NAME, "decoder", "prepender",
                "encoder", "packet_handler"), names(ch));
        RewindCapture moved = (RewindCapture) ch.pipeline().get(RewindCapture.NAME);
        assertNotSame(first, moved);
        assertSame(s, moved.session());
        RecordChunk live = p.liveChunk();
        assertNotNull("moving does not hand the chunk off", live);
        assertNull(p.peekFull());

        byte[] y = other(0x21, 12);
        byte[] compressed = new byte[y.length + 1];
        compressed[0] = (byte) 0xC0;
        System.arraycopy(y, 0, compressed, 1, y.length);
        fire(ch, compressed);
        ch.checkException();
        assertFalse(s.failed());
        assertSame(live, p.liveChunk());
        CaptureSessionTest.Drain d = new CaptureSessionTest.Drain(p).drain();
        assertRecords(d.segment(1), join, x, y);
    }

    /** Stand-in for vanilla's decompress: drops a 1-byte marker. */
    static final class FakeDecompress extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf b = (ByteBuf) msg;
            ctx.fireChannelRead(b.slice(b.readerIndex() + 1, b.readableBytes() - 1));
        }
    }

    private static void assertNotSame(Object a, Object b) {
        assertFalse("expected different objects", a == b);
    }

    @Test
    public void removeRunsOnTheEventLoopAndHandsTheChunkOff() {
        EmbeddedChannel ch = remotePipeline(new Received());
        RecordChunk.Producer p = new RecordChunk.Producer(4096, 2, 4, 0);
        CaptureSession s = session(p);
        RewindCapture.install(ch, false, s);
        fire(ch, s01(1, 0), other(0x20, 10));
        RewindCapture.remove(ch);
        assertNotNull("removal is queued on the event loop", ch.pipeline().get(RewindCapture.NAME));
        ch.runPendingTasks();
        assertNull(ch.pipeline().get(RewindCapture.NAME));
        assertNull(p.liveChunk());
        assertNotNull(p.peekFull());
        RewindCapture.remove(ch); // absent: harmless
        ch.runPendingTasks();
        ch.checkException();
    }

    @Test
    public void channelInactiveHandsTheChunkOff() {
        EmbeddedChannel ch = remotePipeline(new Received());
        RecordChunk.Producer p = new RecordChunk.Producer(4096, 2, 4, 0);
        RewindCapture.install(ch, false, session(p));
        fire(ch, s01(1, 0), other(0x20, 10));
        ch.close();
        ch.runPendingTasks();
        ch.checkException();
        assertNull(p.liveChunk());
        assertNotNull(p.peekFull());
    }

    // ------------------------------------------------------------------ local (single-player) path

    @Test
    public void localPathEncodesPacketObjectsAndNeverTouchesS3F() throws Exception {
        Received rx = new Received();
        EmbeddedChannel ch = localPipeline(rx);
        RecordChunk.Producer p = new RecordChunk.Producer(4096, 2, 4, 0);
        CaptureSession s = session(p);
        assertNotNull(RewindCapture.install(ch, true, s));

        S01PacketJoinGame join = new S01PacketJoinGame(42, WorldSettings.GameType.SURVIVAL, false, 0,
                EnumDifficulty.NORMAL, 20, WorldType.DEFAULT, false);
        S03PacketTimeUpdate time = new S03PacketTimeUpdate(1000L, 6000L, true);
        PacketBuffer payload = new PacketBuffer(Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5}));
        S3FPacketCustomPayload custom = new S3FPacketCustomPayload("Replay|Restrict", payload);
        S00PacketKeepAlive keepAlive = new S00PacketKeepAlive(5);
        S47PacketPlayerListHeaderFooter h0 = s47Packet("H0", "F0");
        S07PacketRespawn nether = new S07PacketRespawn(-1, EnumDifficulty.NORMAL, WorldType.DEFAULT,
                WorldSettings.GameType.SURVIVAL);
        S47PacketPlayerListHeaderFooter h1 = s47Packet("H1", "F1");
        S07PacketRespawn sameNether = new S07PacketRespawn(-1, EnumDifficulty.NORMAL, WorldType.DEFAULT,
                WorldSettings.GameType.SURVIVAL);
        Object[] packets = {join, time, custom, keepAlive, h0, nether, h1, sameNether, time};
        for (Object o : packets) {
            ch.pipeline().fireChannelRead(o);
        }
        ch.checkException();
        assertEquals(packets.length, rx.messages.size());
        for (int i = 0; i < packets.length; i++) {
            assertSame(packets[i], rx.messages.get(i));
        }
        assertEquals("S3F payload untouched", 0, payload.readerIndex());
        assertEquals(5, payload.readableBytes());
        assertTrue(s.restrictChannelSeen());
        assertFalse(s.failed());

        CaptureSessionTest.Drain d = new CaptureSessionTest.Drain(p).drain();
        assertEquals(2, s.segment());
        assertRecords(d.segment(1), encode(join), encode(time), encode(h0));
        assertArrayEquals(encode(h0), d.carry(2));
        assertRecords(d.segment(2), encode(nether), encode(h1), encode(sameNether), encode(time));
    }

    /** A7 on the single-player path: every client-handled packet is pushed, stored or not; S00 and S47 never. */
    @Test
    public void localPathPushesEveryClientHandledPacketToTheRing() throws Exception {
        Received rx = new Received();
        EmbeddedChannel ch = localPipeline(rx);
        RecordChunk.Producer p = new RecordChunk.Producer(4096, 2, 4, 0);
        CaptureSession s = session(p);
        assertNotNull(RewindCapture.install(ch, true, s));
        S01PacketJoinGame join = new S01PacketJoinGame(42, WorldSettings.GameType.SURVIVAL, false, 0,
                EnumDifficulty.NORMAL, 20, WorldType.DEFAULT, false);
        S03PacketTimeUpdate time = new S03PacketTimeUpdate(1000L, 6000L, true);
        S3FPacketCustomPayload custom = new S3FPacketCustomPayload("MC|Brand",
                new PacketBuffer(Unpooled.wrappedBuffer(new byte[]{1, 'x'})));
        S07PacketRespawn nether = new S07PacketRespawn(-1, EnumDifficulty.NORMAL, WorldType.DEFAULT,
                WorldSettings.GameType.SURVIVAL);
        Object[] first = {join, time, custom, new S00PacketKeepAlive(5), s47Packet("H", "F"), nether, time};
        for (Object o : first) {
            ch.pipeline().fireChannelRead(o);
        }
        s.skipSegment(2);
        ch.pipeline().fireChannelRead(time); // a dropped segment: pushed unstored
        ch.checkException();
        assertEquals(first.length + 1, rx.messages.size());

        HandledRing ring = s.handledRing();
        List<String> trace = new ArrayList<String>();
        int id;
        while ((id = ring.peekPacketId()) >= 0) {
            ring.onHandled(id);
            trace.add(ring.segment() + ":" + Integer.toHexString(id) + ":" + ring.handled());
        }
        assertEquals(Arrays.asList("1:1:1", "1:3:2", "1:3f:2", "2:7:1", "2:3:2", "2:3:2"), trace);
        assertEquals(0, ring.resyncs());
    }

    static S47PacketPlayerListHeaderFooter s47Packet(String header, String footer) throws Exception {
        byte[] f = s47(header, footer);
        S47PacketPlayerListHeaderFooter pk = new S47PacketPlayerListHeaderFooter();
        pk.readPacketData(new PacketBuffer(Unpooled.wrappedBuffer(f, 1, f.length - 1)));
        return pk;
    }

    /** Vanilla's own encoding of a play packet: varint id, then {@code writePacketData}. */
    static byte[] encode(Packet<?> packet) throws Exception {
        PacketBuffer b = new PacketBuffer(Unpooled.buffer());
        b.writeVarIntToBuffer(EnumConnectionState.PLAY.getPacketId(EnumPacketDirection.CLIENTBOUND, packet));
        packet.writePacketData(b);
        return bytes(b);
    }
}
