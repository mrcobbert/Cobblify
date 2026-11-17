package com.bedwarsqol.rewind;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The pure capture logic of PLAN "Capture": per frame, boundary first (S01, or an S07 that changes
 * dimension, rolls to the new segment before anything is appended, so it is the first NET record of its
 * own segment), the S47 freeze in stream order, the skip list and the location tap, the copy, and the
 * failure latch that only {@link CaptureSession#rearm()} clears. Frames are plain byte arrays here; the
 * Netty side is {@code RewindCaptureTest} (Ornithe).
 */
public class CaptureSessionTest {

    private RecordChunk.Producer producer;
    private FakeClock clock;
    private List<Object[]> locations;
    private CaptureSession session;
    private Drain drain;

    @Before
    public void setUp() {
        build(4096, 2, 4);
    }

    private void build(int capacity, int initial, int max) {
        producer = new RecordChunk.Producer(capacity, initial, max, 0);
        clock = new FakeClock();
        locations = new ArrayList<Object[]>();
        session = new CaptureSession(producer, clock, new CaptureSession.LocationSink() {
            @Override
            public void onLocation(int segment, byte[] payload) {
                locations.add(new Object[]{segment, payload});
            }
        });
        drain = new Drain(producer);
    }

    private void feed(byte[]... frames) {
        ArrayFrame f = new ArrayFrame();
        for (byte[] b : frames) {
            session.onFrame(f.set(b));
        }
    }

    // ------------------------------------------------------------------ boundaries

    @Test
    public void s01IsTheFirstRecordOfItsOwnSegmentNeverThePrevious() {
        byte[] a = s01(7, 0);
        byte[] x = other(0x20, 30);
        byte[] b = s01(8, 0); // S01 is a boundary even with the same dimension
        byte[] y = other(0x21, 12);
        feed(a, x, b, y);
        drain.drain();
        assertEquals(2, session.segment());
        assertRecords(drain.segment(1), a, x);
        assertRecords(drain.segment(2), b, y);
        assertEquals(Arrays.asList(1, 2), drain.segments());
    }

    @Test
    public void dimensionChangingS07OpensASegmentAndSameDimensionS07StaysPut() {
        byte[] join = s01(1, 0);
        byte[] x = other(0x15, 9);
        byte[] same = s07(0);
        byte[] y = other(0x16, 9);
        byte[] nether = s07(-1);
        byte[] z = other(0x17, 9);
        byte[] sameNether = s07(-1);
        feed(join, x, same, y, nether, z, sameNether);
        drain.drain();
        assertEquals(2, session.segment());
        assertRecords(drain.segment(1), join, x, same, y);
        assertRecords(drain.segment(2), nether, z, sameNether);
    }

    @Test
    public void skippedSegmentStillLetsTheNextBoundaryOpenItsOwnSegment() {
        byte[] join = s01(1, 0);
        byte[] x = other(0x20, 10);
        byte[] y = other(0x21, 10);
        byte[] nether = s07(-1);
        byte[] z = other(0x22, 10);
        feed(join, x);
        session.skipSegment(1); // the client thread dropped segment 1 (lobby)
        feed(y, nether, z);
        drain.drain();
        assertRecords(drain.segment(1), join, x);
        assertRecords(drain.segment(2), nether, z); // the skip applied to one segment only
    }

    @Test
    public void staleSkipForAnEarlierSegmentIsIgnored() {
        feed(s01(1, 0));
        byte[] nether = s07(-1);
        byte[] z = other(0x22, 10);
        feed(nether);
        session.skipSegment(1); // arrives after Netty already moved on
        feed(z);
        drain.drain();
        assertRecords(drain.segment(2), nether, z);
    }

    @Test
    public void framesBeforeTheFirstBoundaryAreNotCopied() {
        byte[] early = other(0x30, 8);
        byte[] join = s01(1, 0);
        feed(early, join);
        drain.drain();
        assertEquals(Arrays.asList(1), drain.segments());
        assertRecords(drain.segment(1), join);
    }

    // ------------------------------------------------------------------ S47 freeze

    @Test
    public void s47IsFrozenAtTheBoundaryInStreamOrder() {
        byte[] h0 = s47("H0", "F0");
        byte[] h1 = s47("H1", "F1");
        byte[] nether = s07(-1);
        feed(s01(1, 0), h0, nether, h1);
        drain.drain();
        assertArrayEquals(h0, drain.carry(2));
        assertRecords(drain.segment(2), nether, h1);
        assertEquals(0, drain.carry(1).length);
    }

    @Test
    public void s47FreezeHoldsWhileTheSegmentIsSkipped() {
        byte[] join = s01(1, 0);
        byte[] h0 = s47("H0", "F0");
        byte[] h1 = s47("H1", "F1");
        byte[] nether = s07(-1);
        feed(join);
        session.skipSegment(1);
        feed(h0, nether, h1);
        drain.drain();
        assertRecords(drain.segment(1), join);
        assertArrayEquals(h0, drain.carry(2));
        assertRecords(drain.segment(2), nether, h1);
    }

    @Test
    public void s47FreezeHoldsWhileLatchedAndCopyRestartsAtTheBoundaryAfterRearm() {
        byte[] h0 = s47("H0", "F0");
        byte[] h1 = s47("H1", "F1");
        byte[] nether = s07(-1);
        byte[] lost = other(0x20, 10);
        feed(s01(1, 0));
        session.fail(new IllegalStateException("test"));
        session.rearm();
        feed(lost, h0, nether, h1); // rearm waits for the boundary: lost and h0 are not copied
        drain.drain();
        assertFalse(session.failed());
        assertArrayEquals(h0, drain.carry(2));
        assertRecords(drain.segment(2), nether, h1);
        assertRecords(drain.segment(1), s01(1, 0));
    }

    @Test
    public void largerS47GrowsTheCarryAndAShorterOneIsStoredExactly() {
        build(16384, 2, 4);
        byte[] big = s47(repeat('x', 9000), "f");
        byte[] small = s47("s", "f");
        byte[] n1 = s07(-1);
        byte[] n2 = s07(0);
        feed(s01(1, 0), big, n1, small, n2);
        drain.drain();
        assertArrayEquals(big, drain.carry(2));
        assertArrayEquals(small, drain.carry(3));
    }

    // ------------------------------------------------------------------ skip list and location

    @Test
    public void keepAliveCompressionAndCustomPayloadAreSkippedAndLocationIsPostedWithItsSegment() {
        byte[] join = s01(1, 0);
        byte[] keepAlive = frame(0x00, varInt(1234));
        byte[] compression = frame(0x46, varInt(256));
        byte[] brand = s3f("MC|Brand", "fake".getBytes(StandardCharsets.UTF_8));
        byte[] payload = {1, 1, 4, 'm', 'i', 'n', 'i', 0, 0, 0, 0};
        byte[] location = s3f("hyevent:location", payload);
        byte[] x = other(0x02, 20);
        feed(join, keepAlive, compression, brand, location, x);
        drain.drain();
        assertRecords(drain.segment(1), join, x);
        assertEquals(1, locations.size());
        assertEquals(1, locations.get(0)[0]);
        assertArrayEquals(payload, (byte[]) locations.get(0)[1]);
        assertFalse(session.restrictChannelSeen());
        assertEquals(1, session.skippedCount(0x00));
        assertEquals(keepAlive.length, session.skippedBytes(0x00));
        assertEquals(2, session.skippedCount(0x3F));
        assertEquals(1, session.skippedCount(0x46));
    }

    @Test
    public void locationIsPostedEvenBeforeTheFirstBoundaryAndWhileLatched() {
        byte[] payload = {1, 1, 1, 'a', 0, 0, 0, 0};
        feed(s3f("hyevent:location", payload));
        feed(s01(1, 0));
        session.fail(new IllegalStateException("test"));
        feed(s07(-1), s3f("hyevent:location", payload));
        assertEquals(2, locations.size());
        assertEquals(0, locations.get(0)[0]);
        assertEquals(2, locations.get(1)[0]);
    }

    @Test
    public void restrictStyleChannelIsFlagged() {
        feed(s01(1, 0), s3f("Replay|Restrict", new byte[0]));
        assertTrue(session.restrictChannelSeen());
        assertTrue(locations.isEmpty());
    }

    @Test
    public void lookalikeLocationChannelIsNotPosted() {
        feed(s01(1, 0), s3f("hyevent:locatioN", new byte[]{1}), s3f("hyevent:location2", new byte[]{1}),
                s3f("hyevent:locatio", new byte[]{1}));
        assertTrue(locations.isEmpty());
        assertFalse(session.restrictChannelSeen());
    }

    // ------------------------------------------------------------------ failure latch

    @Test
    public void exceptionLatchesAndSameGameBoundariesNeverRestartCopying() {
        byte[] join = s01(1, 0);
        byte[] x = other(0x20, 10);
        feed(join);
        clock.throwOnce = true;
        feed(x); // the clock throws inside the copy step
        assertTrue(session.failed());
        assertEquals(CaptureSession.FAIL_EXCEPTION, session.failureReason());
        assertNotNull(session.failureCause());
        assertEquals(1, session.failureCount());

        feed(other(0x21, 10), s07(-1), other(0x22, 10), s01(2, 0), other(0x23, 10), s07(-1));
        assertEquals(4, session.segment()); // boundaries keep counting
        assertTrue(session.failed()); // ... but never clear the latch, even with free chunks
        drain.drain();
        assertEquals(Arrays.asList(1), drain.segments());
        assertRecords(drain.segment(1), join);

        session.rearm();
        byte[] notYet = other(0x24, 10);
        byte[] back = s07(0);
        byte[] y = other(0x25, 10);
        feed(notYet, back, y);
        assertFalse(session.failed());
        assertEquals(1, session.failureCount());
        drain.drain();
        assertEquals(Arrays.asList(1, 5), drain.segments());
        assertRecords(drain.segment(5), back, y);
    }

    @Test
    public void throwingCopyAbortsTheReservationAndKeepsTheCommittedPrefix() {
        byte[] join = s01(1, 0);
        byte[] x = other(0x20, 10);
        feed(join, x);
        ArrayFrame bad = new ArrayFrame() {
            @Override
            public void copyTo(int index, byte[] dst, int dstOff, int len) {
                dst[dstOff] = 0x7F;
                throw new IllegalStateException("copy");
            }
        };
        session.onFrame(bad.set(other(0x21, 50)));
        assertTrue(session.failed());
        drain.drain();
        assertRecords(drain.segment(1), join, x);
    }

    @Test
    public void throwingLocationSinkLatchesWithoutThrowing() {
        session = new CaptureSession(producer, clock, new CaptureSession.LocationSink() {
            @Override
            public void onLocation(int segment, byte[] payload) {
                throw new IllegalStateException("sink");
            }
        });
        feed(s01(1, 0), s3f("hyevent:location", new byte[]{1, 1, 1, 'a', 0, 0, 0, 0}));
        assertTrue(session.failed());
    }

    @Test
    public void noFreeChunkLatchesAndRearmWaitsForABoundaryWithAFreeChunk() {
        build(64, 1, 1);
        byte[] join = s01(1, 0);
        feed(join);
        for (int i = 0; i < 4; i++) {
            feed(other(0x20 + i, 20)); // the second does not fit: the roll finds no free chunk
        }
        assertTrue(session.failed());
        assertEquals(CaptureSession.FAIL_NO_CHUNK, session.failureReason());
        feed(s07(-1));
        assertTrue(session.failed());

        session.rearm();
        feed(s07(0)); // no chunk is free yet: still off, the rearm stays pending
        assertTrue(session.failed());
        assertEquals(1, session.failureCount());

        drain.drain(); // the writer frees the chunk
        byte[] back = s07(-1);
        byte[] y = other(0x30, 5);
        feed(back, y);
        assertFalse(session.failed());
        drain.drain();
        assertRecords(drain.segment(4), back, y);
    }

    @Test
    public void rearmBeforeTheFailureDoesNotClearIt() {
        feed(s01(1, 0));
        session.rearm();
        session.fail(new IllegalStateException("test"));
        feed(s07(-1), other(0x20, 5));
        assertTrue(session.failed());
        drain.drain();
        assertEquals(Arrays.asList(1), drain.segments());
    }

    @Test
    public void oversizeFrameLatches() {
        build(64, 2, 2);
        feed(s01(1, 0), other(0x20, 60));
        assertTrue(session.failed());
        assertEquals(CaptureSession.FAIL_OVERSIZE, session.failureReason());
    }

    // ------------------------------------------------------------------ times, close, local, install

    @Test
    public void recordTimesComeFromTheClock() {
        clock.now = 1_000;
        feed(s01(1, 0));
        clock.now = 1_500;
        feed(other(0x20, 4));
        clock.now = 9_000;
        feed(s07(-1));
        drain.drain();
        assertEquals(1_000, drain.segmentRecords(1).get(0).timeUs);
        assertEquals(1_500, drain.segmentRecords(1).get(1).timeUs);
        assertEquals(9_000, drain.segmentRecords(2).get(0).timeUs);
    }

    @Test
    public void closeHandsOffTheLiveChunkAndStopsEverything() {
        byte[] join = s01(1, 0);
        feed(join);
        session.close();
        assertNull(producer.liveChunk());
        RecordChunk c = producer.peekFull();
        assertNotNull(c);
        assertEquals(1, c.segment());
        feed(other(0x20, 5), s07(-1));
        assertEquals(1, session.segment());
        drain.drain();
        assertRecords(drain.segment(1), join);
        assertEquals(Arrays.asList(1), drain.segments());
    }

    @Test
    public void localWantsBoundaryAndS47AlwaysAndOtherPacketsOnlyWhileCopying() {
        assertTrue(session.localWants(0x01));
        assertTrue(session.localWants(0x07));
        assertTrue(session.localWants(0x47));
        assertFalse(session.localWants(0x20)); // no segment yet
        feed(s01(1, 0));
        assertTrue(session.localWants(0x20));
        assertFalse(session.localWants(0x00));
        assertFalse(session.localWants(0x46));
        assertFalse(session.localWants(0x3F));
        session.skipSegment(1);
        assertFalse(session.localWants(0x20));
        assertTrue(session.localWants(0x47));
        session.onLocalCustomPayload("Replay|Restrict");
        assertTrue(session.restrictChannelSeen());
    }

    @Test
    public void installOnlyWhenOnAndLocalOrExactHypixel() {
        assertTrue(CaptureSession.shouldInstall(true, true, false, "mc.hypixel.net"));
        assertTrue(CaptureSession.shouldInstall(true, true, false, "hypixel.net:25565"));
        assertTrue(CaptureSession.shouldInstall(true, true, true, null));
        assertFalse(CaptureSession.shouldInstall(false, true, false, "mc.hypixel.net"));
        assertFalse(CaptureSession.shouldInstall(true, false, false, "mc.hypixel.net"));
        assertFalse(CaptureSession.shouldInstall(false, true, true, null));
        assertFalse(CaptureSession.shouldInstall(true, true, false, "hypixel.net.example.org"));
        assertFalse(CaptureSession.shouldInstall(true, true, false, "play.example.org"));
        assertFalse(CaptureSession.shouldInstall(true, true, false, null));
    }

    @Test
    public void onFrameNeverThrowsOnMalformedFrames() {
        feed(new byte[0], new byte[]{(byte) 0x80}, new byte[]{0x01, 0, 0}, new byte[]{0x07, 0},
                new byte[]{0x3F, 0x10, 'h'}, new byte[]{0x3F});
        assertFalse(session.failed());
        assertEquals(0, session.segment());
    }

    // ================================================================== helpers (also used by RewindCaptureTest)

    static void assertRecords(List<byte[]> actual, byte[]... expected) {
        assertEquals("record count", expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals("record " + i, expected[i], actual.get(i));
        }
    }

    static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    static byte[] varInt(int v) {
        byte[] b = new byte[5];
        return Arrays.copyOf(b, RewindFormat.putVarInt(b, 0, v));
    }

    static byte[] string(String s) {
        byte[] u = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(varInt(u.length), 0, varInt(u.length).length);
        out.write(u, 0, u.length);
        return out.toByteArray();
    }

    static byte[] frame(int id, byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] idb = varInt(id);
        out.write(idb, 0, idb.length);
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    static byte[] be32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    /** S01 as protocol 47 writes it: int entityId, u8 game type, i8 dimension, u8 difficulty, u8 max players, level type, bool. */
    static byte[] s01(int entityId, int dimension) {
        return frame(0x01, be32(entityId), new byte[]{0, (byte) dimension, 2, 100}, string("default"), new byte[]{0});
    }

    /** S07: int dimension, u8 difficulty, u8 game type, level type. */
    static byte[] s07(int dimension) {
        return frame(0x07, be32(dimension), new byte[]{2, 0}, string("default"));
    }

    static byte[] s47(String header, String footer) {
        return frame(0x47, string("{\"text\":\"" + header + "\"}"), string("{\"text\":\"" + footer + "\"}"));
    }

    static byte[] s3f(String channel, byte[] payload) {
        return frame(0x3F, string(channel), payload);
    }

    /** A packet {@code id} of {@code total} bytes (id included) with a recognisable body. */
    static byte[] other(int id, int total) {
        byte[] b = new byte[total];
        RewindFormat.putVarInt(b, 0, id);
        for (int i = 1; i < total; i++) {
            b[i] = (byte) (id * 31 + i);
        }
        return b;
    }

    static class ArrayFrame implements CaptureSession.Frame {
        byte[] b;

        ArrayFrame set(byte[] bytes) {
            b = bytes;
            return this;
        }

        @Override
        public int length() {
            return b.length;
        }

        @Override
        public int byteAt(int index) {
            return b[index] & 0xFF;
        }

        @Override
        public void copyTo(int index, byte[] dst, int dstOff, int len) {
            System.arraycopy(b, index, dst, dstOff, len);
        }
    }

    static final class FakeClock implements CaptureSession.Clock {
        long now;
        boolean throwOnce;

        @Override
        public long nowUs() {
            if (throwOnce) {
                throwOnce = false;
                throw new IllegalStateException("clock");
            }
            return now;
        }
    }

    /** One NET record read back from the chunks. */
    static final class Rec {
        final int segment;
        final long timeUs;
        final byte[] bytes;

        Rec(int segment, long timeUs, byte[] bytes) {
            this.segment = segment;
            this.timeUs = timeUs;
            this.bytes = bytes;
        }
    }

    /**
     * A test stand-in for the writer: drains handed-off chunks (and releases them) and reads the live chunk
     * up to its committed length, remembering how far it got, then parses {@code [varint dt][varint len][bytes]}.
     * Single-threaded use only (same thread as the producer).
     */
    static final class Drain {
        private final RecordChunk.Producer p;
        final List<Rec> recs = new ArrayList<Rec>();
        private final Map<Integer, byte[]> carry = new TreeMap<Integer, byte[]>();
        private int liveSeq = -1;
        private int liveOff;
        private long liveTime;

        Drain(RecordChunk.Producer p) {
            this.p = p;
        }

        Drain drain() {
            RecordChunk c;
            while ((c = p.pollFull()) != null) {
                boolean resumed = c.seq() == liveSeq;
                read(c, resumed ? liveOff : 0, resumed ? liveTime : c.startTimeUs());
                if (resumed) {
                    liveSeq = -1;
                }
                p.release(c);
            }
            c = p.liveChunk();
            if (c != null) {
                boolean resumed = c.seq() == liveSeq;
                liveTime = read(c, resumed ? liveOff : 0, resumed ? liveTime : c.startTimeUs());
                liveSeq = c.seq();
                liveOff = c.committedLength();
            }
            return this;
        }

        private long read(RecordChunk c, int from, long t) {
            if (from == 0 && c.firstOfSegment()) {
                carry.put(c.segment(), Arrays.copyOf(c.carry(), c.carryLength()));
            }
            byte[] d = c.data();
            int pos = from;
            int end = c.committedLength();
            while (pos < end) {
                long[] dt = readVarInt(d, pos);
                pos = (int) dt[1];
                long[] len = readVarInt(d, pos);
                pos = (int) len[1];
                t += dt[0];
                recs.add(new Rec(c.segment(), t, Arrays.copyOfRange(d, pos, pos + (int) len[0])));
                pos += (int) len[0];
            }
            return t;
        }

        private static long[] readVarInt(byte[] d, int pos) {
            long v = 0;
            for (int shift = 0; ; shift += 7) {
                int x = d[pos++];
                v |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) {
                    return new long[]{v, pos};
                }
                if (shift > 28) {
                    fail("bad varint");
                }
            }
        }

        List<Integer> segments() {
            List<Integer> out = new ArrayList<Integer>();
            for (Rec r : recs) {
                if (!out.contains(r.segment)) {
                    out.add(r.segment);
                }
            }
            return out;
        }

        List<Rec> segmentRecords(int segment) {
            List<Rec> out = new ArrayList<Rec>();
            for (Rec r : recs) {
                if (r.segment == segment) {
                    out.add(r);
                }
            }
            return out;
        }

        List<byte[]> segment(int segment) {
            List<byte[]> out = new ArrayList<byte[]>();
            for (Rec r : segmentRecords(segment)) {
                out.add(r.bytes);
            }
            return out;
        }

        /** The carry area of the segment's first chunk (frozen S47); empty array if none or not seen. */
        byte[] carry(int segment) {
            byte[] c = carry.get(segment);
            return c == null ? new byte[0] : c;
        }
    }
}
