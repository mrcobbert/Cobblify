package com.bedwarsqol.rewind;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The SPSC rings, the chunk producer's rollover order and the {@code (chunkSeq, offset)} watermark: a
 * reader on another thread that syncs to a watermark cutoff only ever sees whole records, and every
 * chunk older than the cutoff is already in the full ring.
 */
public class ChunkRingTest {

    @Test
    public void ringIsFifoBoundedAndPeekDoesNotRemove() {
        ChunkRing ring = new ChunkRing(3);
        assertEquals(4, ring.capacity());
        assertTrue(ring.isEmpty());
        assertNull(ring.peek());
        assertNull(ring.poll());
        RecordChunk[] c = new RecordChunk[5];
        for (int i = 0; i < 5; i++) {
            c[i] = new RecordChunk(16);
        }
        for (int i = 0; i < 4; i++) {
            assertTrue(ring.offer(c[i]));
        }
        assertFalse(ring.offer(c[4]));
        assertEquals(4, ring.size());
        assertSame(c[0], ring.peek());
        assertSame(c[0], ring.peek());
        for (int i = 0; i < 4; i++) {
            assertSame(c[i], ring.poll());
        }
        assertTrue(ring.isEmpty());
        assertTrue(ring.offer(c[4]));
        assertSame(c[4], ring.poll());
    }

    @Test(timeout = 60_000)
    public void ringHandsChunksAcrossThreadsInOrder() throws Exception {
        final ChunkRing full = new ChunkRing(8);
        final ChunkRing free = new ChunkRing(8);
        for (int i = 0; i < 8; i++) {
            free.offer(new RecordChunk(8));
        }
        final int n = 200_000;
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread consumer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int expected = 0;
                    while (expected < n) {
                        RecordChunk c = full.poll();
                        if (c == null) {
                            Thread.yield();
                            continue;
                        }
                        int v = c.data()[0] & 0xFF | (c.data()[1] & 0xFF) << 8 | (c.data()[2] & 0xFF) << 16;
                        assertEquals(expected & 0xFFFFFF, v);
                        expected++;
                        assertTrue(free.offer(c));
                    }
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        });
        consumer.start();
        for (int i = 0; i < n; i++) {
            RecordChunk c;
            while ((c = free.poll()) == null) {
                if (error.get() != null) {
                    throw new AssertionError(error.get());
                }
                Thread.yield();
            }
            c.data()[0] = (byte) i;
            c.data()[1] = (byte) (i >> 8);
            c.data()[2] = (byte) (i >> 16);
            assertTrue(full.offer(c));
        }
        consumer.join(30_000);
        assertFalse(consumer.isAlive());
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @Test
    public void rolloverHandsOffBeforePublishingTheNextChunkAndItsWatermark() {
        RecordChunk.Producer p = new RecordChunk.Producer(64, 2, 3, 1000);
        assertEquals(0L, p.watermark());
        assertNull(p.liveChunk());
        byte[] carry = {0x47, 1, 2};
        assertTrue(p.startSegment(0, carry, 0, carry.length));
        RecordChunk first = p.liveChunk();
        assertNotNull(first);
        assertEquals(1, first.seq());
        assertTrue(first.firstOfSegment());
        assertEquals(3, first.carryLength());
        assertEquals(RecordChunk.watermark(1, 0), p.watermark());

        byte[] rec = new byte[20];
        rec[0] = 0x02;
        assertTrue(p.appendNet(1010, rec, 0, rec.length));
        int afterOne = first.committedLength();
        assertEquals(1 + 1 + 20, afterOne);
        assertEquals(RecordChunk.watermark(1, afterOne), p.watermark());
        assertTrue(p.appendNet(1020, rec, 0, rec.length));
        // The third record does not fit: chunk 1 goes to the full ring, chunk 2 becomes current.
        assertTrue(p.appendNet(1030, rec, 0, rec.length));
        assertSame(first, p.peekFull());
        RecordChunk second = p.liveChunk();
        assertEquals(2, second.seq());
        assertEquals(0, second.segment());
        assertFalse(second.firstOfSegment());
        assertEquals(0, second.carryLength());
        assertEquals(RecordChunk.watermark(2, 22), p.watermark());
        assertEquals(1020, second.startTimeUs());
        assertSame(first, p.chunkForSeq(1));
        assertSame(second, p.chunkForSeq(2));
        assertNull(p.chunkForSeq(3));

        // Boundary roll: segment 1 opens in a fresh chunk with the carry; chunk 2 is handed off.
        assertTrue(p.startSegment(1, carry, 0, 2));
        RecordChunk third = p.liveChunk();
        assertEquals(3, third.seq());
        assertEquals(1, third.segment());
        assertTrue(third.firstOfSegment());
        assertEquals(2, third.carryLength());
        assertEquals(1, p.segment());
        assertEquals(3, p.allocatedChunks());

        // No free chunk and at the cap: the roll fails, the old chunk is still handed off.
        assertFalse(p.startSegment(2, null, 0, 0));
        assertNull(p.liveChunk());
        assertFalse(p.appendNet(1040, rec, 0, rec.length));
        assertSame(first, p.pollFull());
        assertEquals(2, p.pollFull().seq());
        assertSame(third, p.pollFull());
        assertNull(p.pollFull());
        p.release(first);
        assertTrue(p.startSegment(2, null, 0, 0));
        assertSame(first, p.liveChunk());
        assertEquals(4, first.seq());
        assertEquals(0, first.committedLength());
        assertEquals(RecordChunk.watermark(4, 0), p.watermark());
    }

    @Test
    public void reservationsCommitOnlyWhenToldAndAbortedBytesAreNeverHandedOff() {
        RecordChunk.Producer p = new RecordChunk.Producer(128, 1, 1, 0);
        assertTrue(p.startSegment(0, null, 0, 0));
        int at = p.reserveNet(5, 4);
        assertTrue(at > 0);
        RecordChunk c = p.liveChunk();
        System.arraycopy(new byte[]{0x02, 9, 9, 9}, 0, c.data(), at, 4);
        assertEquals(0, c.committedLength());
        assertEquals(RecordChunk.watermark(1, 0), p.watermark());
        p.commit();
        int committed = c.committedLength();
        assertEquals(at + 4, committed);
        assertEquals(RecordChunk.watermark(1, committed), p.watermark());

        int at2 = p.reserveNet(10, 4);
        assertTrue(at2 >= committed);
        p.abort();
        assertEquals(committed, c.committedLength());
        // A packet larger than an empty chunk can hold is refused outright.
        assertEquals(-1, p.reserveNet(11, 200));
        p.handOff();
        assertSame(c, p.pollFull());
        assertEquals(committed, c.committedLength());
        byte[] tail = new byte[committed];
        assertEquals(committed, c.tail(0, committed, tail, 0));
        assertEquals(0x02, tail[at]);
    }

    @Test
    public void largestPacketFitsAnEmptyDefaultChunk() {
        RecordChunk.Producer p = new RecordChunk.Producer(RewindFormat.CHUNK_CAPACITY, 1, 1, 0);
        assertTrue(p.startSegment(0, null, 0, 0));
        assertTrue(p.reserveNet(Long.MAX_VALUE / 2, RewindFormat.MAX_PACKET_LENGTH) > 0);
        p.commit();
    }

    /**
     * A writer thread syncs to the watermark exactly as PLAN "Sync to a cutoff" describes while the
     * producer rolls chunks underneath it: drain through seq < s never waits, chunk s is always found
     * live or at the ring head, and every record read is whole, in order, with its timestamp.
     */
    @Test(timeout = 60_000)
    public void aWatermarkReadOnAnotherThreadSeesOnlyWholeRecords() throws Exception {
        final long origin = 1_000_000L;
        final RecordChunk.Producer p = new RecordChunk.Producer(300, 2, 4, origin);
        assertTrue(p.startSegment(0, null, 0, 0));
        final int n = 150_000;
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        final int[] seen = new int[1];
        final int[] drainedWhileRunning = new int[1];
        Thread writer = new Thread(new Runnable() {
            int consumedSeq = 1;
            int consumedOff = 0;
            long timeAtConsumed = origin;
            int next = 0;

            void consume(RecordChunk c, int from, int to) {
                if (from == 0) {
                    assertEquals(timeAtConsumed, c.startTimeUs());
                }
                RewindFormat.Cursor cur = new RewindFormat.Cursor(c.data(), from, to);
                while (cur.hasMore()) {
                    long dt = cur.uvarInt();
                    int len = cur.varInt();
                    int at = cur.pos();
                    cur.skip(len);
                    timeAtConsumed += dt;
                    assertEquals(expectedLength(next), len);
                    assertEquals(origin + 3L * (next + 1), timeAtConsumed);
                    for (int j = 0; j < len; j++) {
                        assertEquals((byte) (next + j), c.data()[at + j]);
                    }
                    next++;
                }
                assertEquals(to, cur.pos());
            }

            void sync() {
                long wm = p.watermark();
                int s = RecordChunk.watermarkSeq(wm);
                int o = RecordChunk.watermarkOffset(wm);
                RecordChunk head;
                while ((head = p.peekFull()) != null && head.seq() < s) {
                    assertEquals(consumedSeq, head.seq());
                    consume(head, consumedOff, head.committedLength());
                    assertSame(head, p.pollFull());
                    // Read the seq before release: once released the producer may reuse the chunk.
                    consumedSeq = head.seq() + 1;
                    consumedOff = 0;
                    p.release(head);
                    drainedWhileRunning[0]++;
                }
                assertEquals("every chunk before the cutoff was already in the ring", s, consumedSeq);
                RecordChunk c = p.chunkForSeq(s);
                assertNotNull("chunk s is live or at the ring head", c);
                if (o > consumedOff) {
                    consume(c, consumedOff, o);
                    consumedOff = o;
                }
            }

            @Override
            public void run() {
                try {
                    while (!done.get()) {
                        sync();
                    }
                    // Final drain after the producer handed off its last chunk.
                    RecordChunk head;
                    while ((head = p.pollFull()) != null) {
                        consume(head, head.seq() == consumedSeq ? consumedOff : 0, head.committedLength());
                        consumedSeq = head.seq() + 1;
                        consumedOff = 0;
                    }
                    seen[0] = next;
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        });
        writer.start();
        byte[] buf = new byte[64];
        for (int i = 0; i < n && error.get() == null; i++) {
            int len = expectedLength(i);
            for (int j = 0; j < len; j++) {
                buf[j] = (byte) (i + j);
            }
            while (!p.appendNet(origin + 3L * (i + 1), buf, 0, len)) {
                // Writer lagging and all 4 chunks in flight: the real producer latches failure here;
                // this test instead retries the same record (the next append takes a freed chunk).
                assertNull(p.liveChunk());
                if (error.get() != null) {
                    throw new AssertionError(error.get());
                }
                Thread.yield();
            }
        }
        p.handOff();
        done.set(true);
        writer.join(60_000);
        assertFalse(writer.isAlive());
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        assertEquals(n, seen[0]);
        // The 4-chunk cap forces the writer to drain thousands of rollovers while the producer runs.
        assertEquals(4, p.allocatedChunks());
        assertTrue(drainedWhileRunning[0] > 1000);
    }

    static int expectedLength(int i) {
        return 1 + (i * 7) % 60;
    }
}
