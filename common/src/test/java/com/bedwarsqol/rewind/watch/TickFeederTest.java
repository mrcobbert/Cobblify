package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The tick-locked feed: which records come before which tick, headers, boundary hooks, failures and the kick. */
public class TickFeederTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---------------- shared helpers (also used by SeekPlannerTest and TickFeedEquivalenceTest) ----------------

    /** Builds a small v1 recording segment by segment: headers, then NET records split into blocks of 37. */
    static final class Rec {
        static final class Seg {
            final int number;
            final byte[][] header0;
            final byte[][] header1;
            final List<Long> times = new ArrayList<Long>();
            final List<byte[]> packets = new ArrayList<byte[]>();

            Seg(int number, byte[][] header0, byte[][] header1) {
                this.number = number;
                this.header0 = header0;
                this.header1 = header1;
            }
        }

        final List<Seg> segs = new ArrayList<Seg>();
        /** Every NET record in feed order. */
        final List<byte[]> all = new ArrayList<byte[]>();
        final List<Long> allTimes = new ArrayList<Long>();

        /** A segment whose first record (its boundary) must follow through {@link #at}. */
        Rec segment(int number, byte[][] header0, byte[][] header1) {
            segs.add(new Seg(number, header0, header1));
            return this;
        }

        Rec at(long timeUs, byte[] packet) {
            Seg s = segs.get(segs.size() - 1);
            s.times.add(timeUs);
            s.packets.add(packet);
            all.add(packet);
            allTimes.add(timeUs);
            return this;
        }

        LoadedRecording load(File dir, long gameStartUs, long endUs) throws IOException {
            RecordingFixture f = new RecordingFixture();
            for (Seg s : segs) {
                long first = s.times.get(0);
                if (s.header0 != null) {
                    f.header(s.number, RewindFormat.SOURCE_CLIENT_SNAPSHOT, first, s.header0);
                }
                if (s.header1 != null) {
                    f.header(s.number, RewindFormat.SOURCE_CAPTURE_CARRY, first, s.header1);
                }
                long base = first;
                for (int i = 0; i < s.times.size(); i += 37) {
                    int to = Math.min(s.times.size(), i + 37);
                    long[] t = new long[to - i];
                    byte[][] p = new byte[to - i][];
                    for (int j = i; j < to; j++) {
                        t[j - i] = s.times.get(j);
                        p[j - i] = s.packets.get(j);
                    }
                    f.net(s.number, base, t, p);
                    base = t[t.length - 1];
                }
            }
            String id = "20261117-120000-feed";
            f.end(RecordingFixture.meta(id, gameStartUs, endUs), endUs);
            File cbr = f.write(dir, id);
            File json = RecordingFixture.writeMeta(dir, id, RecordingFixture.meta(id, gameStartUs, endUs));
            LoadedRecording r = LoadedRecording.load(cbr, json);
            assertNull("fixture loads", r.problem());
            return r;
        }
    }

    static byte[] p(int id, int... body) {
        byte[] b = new byte[1 + body.length];
        b[0] = (byte) id;
        for (int i = 0; i < body.length; i++) {
            b[i + 1] = (byte) body[i];
        }
        return b;
    }

    static byte[][] standardHeader0() {
        return new byte[][]{RecordingFixture.joinGame(0), p(0x09, 0), p(0x38, 1, 2, 3)};
    }

    static byte[][] standardHeader1() {
        return new byte[][]{p(0x47, 4, 5)};
    }

    /** One call the feeder made, with what it reported about the record being fed. */
    static final class Event {
        static final int APPLY = 0;
        static final int BOUNDARY = 1;
        static final int HOOK = 2;
        static final int SAMPLE = 3;
        final int kind;
        final long tick;
        final int packetId;
        final long recordIndex;
        final long timeUs;
        final byte[] bytes;

        Event(int kind, long tick, int packetId, long recordIndex, long timeUs, byte[] bytes) {
            this.kind = kind;
            this.tick = tick;
            this.packetId = packetId;
            this.recordIndex = recordIndex;
            this.timeUs = timeUs;
            this.bytes = bytes;
        }
    }

    /** Logs every call; fails (returns false or throws) on chosen packet ids. */
    static class LogSink implements PacketSink {
        final TickFeeder feeder;
        final List<Event> events = new ArrayList<Event>();
        long tick = -1;
        int failId = -1;
        int throwId = -1;

        LogSink(TickFeeder feeder) {
            this.feeder = feeder;
        }

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            events.add(new Event(Event.APPLY, tick, packetId, feeder.recordIndex(), feeder.recordTimeUs(),
                    Arrays.copyOfRange(buf, off, off + len)));
            if (packetId == throwId) {
                throw new IllegalStateException("handler failed");
            }
            return packetId != failId;
        }

        @Override
        public void boundary(int packetId, byte[] buf, int off, int len) {
            events.add(new Event(Event.BOUNDARY, tick, packetId, feeder.recordIndex(), feeder.recordTimeUs(),
                    Arrays.copyOfRange(buf, off, off + len)));
        }

        @Override
        public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
            events.add(new Event(Event.HOOK, tick, packetId, feeder.recordIndex(), feeder.recordTimeUs(), null));
            return true;
        }

        @Override
        public void sample(long t, long timeUs) {
            events.add(new Event(Event.SAMPLE, t, -1, -1, timeUs, null));
        }

        TickFeeder.State feed(long k) {
            tick = k;
            return feeder.feedForTick(k, this);
        }

        List<Event> of(int kind) {
            List<Event> out = new ArrayList<Event>();
            for (Event e : events) {
                if (e.kind == kind) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    private LoadedRecording realShaped() throws IOException {
        File dir = tmp.newFolder();
        String id = "20261117-120000-abcd";
        File cbr = RecordingFixture.realShaped().write(dir, id);
        File json = RecordingFixture.writeMeta(dir, id, RecordingFixture.realShapedMeta());
        LoadedRecording r = LoadedRecording.load(cbr, json);
        assertNull(r.problem());
        return r;
    }

    // ---------------- tests ----------------

    @Test
    public void beforeTickKExactlyTheRecordsAtOrBeforeItsTimeAreFedOnceInOrder() throws Exception {
        LoadedRecording r = realShaped();
        Timeline tl = r.timeline();
        TickFeeder feeder = new TickFeeder(r);
        LogSink sink = new LogSink(feeder);
        long lastTick = tl.tickOf(tl.lastNetUs());
        for (long k = 0; k <= lastTick; k++) {
            assertEquals(TickFeeder.State.RUNNING, sink.feed(k));
            assertEquals(k + 1, feeder.nextTick());
        }
        // Every NET record, in cursor order, each once; the record's tick is the first whose time reaches it.
        long expectIndex = 0;
        NetCursor c = r.newCursor();
        for (Event e : sink.events) {
            if (e.kind != Event.APPLY || e.recordIndex == TickFeeder.HEADER) {
                continue;
            }
            assertFalse("no record left", c.atEnd());
            assertEquals("order", expectIndex, e.recordIndex);
            assertEquals(c.timeUs(), e.timeUs);
            assertArrayEquals(Arrays.copyOfRange(c.buffer(), c.offset(), c.offset() + c.length()), e.bytes);
            assertTrue("time <= tick time", e.timeUs <= tl.timeOfTick(e.tick));
            if (e.tick > 0) {
                assertTrue("not fed a tick early", e.timeUs > tl.timeOfTick(e.tick - 1));
            }
            expectIndex++;
            c.advance();
        }
        assertTrue("every record was fed", c.atEnd());
        assertEquals(r.netRecordCount(), expectIndex);
        assertEquals(r.netRecordCount(), feeder.recordsPassed());
        feeder.restart();
        assertEquals(0, feeder.recordsPassed());
    }

    @Test
    public void oneSampleAfterEachTicksRecordsAtTheTicksTime() throws Exception {
        LoadedRecording r = realShaped();
        Timeline tl = r.timeline();
        TickFeeder feeder = new TickFeeder(r);
        LogSink sink = new LogSink(feeder);
        for (long k = 0; k < 50; k++) {
            sink.feed(k);
        }
        List<Event> samples = sink.of(Event.SAMPLE);
        assertEquals(50, samples.size());
        long k = 0;
        for (int i = 0; i < sink.events.size(); i++) {
            Event e = sink.events.get(i);
            if (e.kind == Event.SAMPLE) {
                assertEquals(k, e.tick);
                assertEquals(tl.timeOfTick(k), e.timeUs);
                k++;
            } else {
                assertEquals("records of tick k come before its sample", k, e.tick);
            }
        }
    }

    @Test
    public void headersComeBeforeTickZeroSourceZeroThenOneAndLaterSegmentsHeadersNever() throws Exception {
        LoadedRecording r = realShaped();
        TickFeeder feeder = new TickFeeder(r);
        LogSink sink = new LogSink(feeder);
        Timeline tl = r.timeline();
        for (long k = 0; k <= tl.tickOf(tl.lastNetUs()); k++) {
            sink.feed(k);
        }
        List<Event> applied = sink.of(Event.APPLY);
        // Start segment: header 0 = S01, S09 (dropped while the amendment rows drop), S38, S3E; header 1 = S47.
        int[] headerIds = {0x01, 0x38, 0x3E, 0x47};
        for (int i = 0; i < headerIds.length; i++) {
            assertEquals("header packet " + i, headerIds[i], applied.get(i).packetId);
            assertEquals(TickFeeder.HEADER, applied.get(i).recordIndex);
            assertEquals(0, applied.get(i).tick);
        }
        assertEquals("then the first NET record", 0, applied.get(headerIds.length).recordIndex);
        int headers = 0;
        int s01 = 0;
        for (Event e : applied) {
            if (e.recordIndex == TickFeeder.HEADER) {
                headers++;
            }
            if (e.packetId == RewindFormat.PACKET_JOIN_GAME) {
                s01++;
                assertEquals("never the nether segment's header S01", 0, e.bytes[6]);
            }
        }
        assertEquals("later segments' headers never", headerIds.length, headers);
        assertEquals("header S01 + the first segment's boundary", 2, s01);
    }

    @Test
    public void feedHeadersCanRunBeforeTheFirstTickAndOnlyOnce() throws Exception {
        LoadedRecording r = realShaped();
        TickFeeder feeder = new TickFeeder(r);
        LogSink sink = new LogSink(feeder);
        feeder.feedHeaders(sink);
        int afterHeaders = sink.of(Event.APPLY).size();
        assertEquals(4, afterHeaders);
        feeder.feedHeaders(sink);
        sink.feed(0);
        int headers = 0;
        for (Event e : sink.of(Event.APPLY)) {
            if (e.recordIndex == TickFeeder.HEADER) {
                headers++;
            }
        }
        assertEquals(4, headers);
    }

    @Test
    public void theBoundaryHookFollowsEachS01AndS07() throws Exception {
        LoadedRecording r = realShaped();
        TickFeeder feeder = new TickFeeder(r);
        LogSink sink = new LogSink(feeder);
        Timeline tl = r.timeline();
        for (long k = 0; k <= tl.tickOf(tl.lastNetUs()); k++) {
            sink.feed(k);
        }
        int boundaries = 0;
        for (int i = 0; i < sink.events.size(); i++) {
            Event e = sink.events.get(i);
            boolean isBoundaryPacket = e.kind == Event.APPLY
                    && (e.packetId == RewindFormat.PACKET_JOIN_GAME || e.packetId == RewindFormat.PACKET_RESPAWN);
            if (isBoundaryPacket) {
                Event next = sink.events.get(i + 1);
                assertEquals("hook right after the packet", Event.BOUNDARY, next.kind);
                assertEquals(e.packetId, next.packetId);
                assertEquals(e.recordIndex, next.recordIndex);
                boundaries++;
            }
            if (e.kind == Event.BOUNDARY) {
                Event prev = sink.events.get(i - 1);
                assertEquals(Event.APPLY, prev.kind);
            }
        }
        // Header S01, the first segment's S01, the nether S07 and the game S07.
        assertEquals(4, boundaries);
        assertEquals(4, sink.of(Event.BOUNDARY).size());
    }

    @Test
    public void droppedPacketsNeverReachTheSink() throws Exception {
        long o = 1_000_000L;
        Rec rec = new Rec().segment(1, standardHeader0(), standardHeader1())
                .at(o, RecordingFixture.joinGame(0))
                .at(o + 10, p(0x08, 1))       // position/look: acts on the client player
                .at(o + 20, p(0x2D, 2))       // open window
                .at(o + 30, p(0x2B, 4, 0, 0, 0, 0)) // credits screen
                .at(o + 40, p(0x2B, 1, 0, 0, 0, 0)) // rain: passes
                .at(o + 50, p(0x02, 7));
        LoadedRecording r = rec.load(tmp.newFolder(), o, o + 1_000_000L);
        TickFeeder feeder = new TickFeeder(r);
        LogSink sink = new LogSink(feeder);
        sink.feed(0);
        sink.feed(1);
        List<Integer> ids = new ArrayList<Integer>();
        for (Event e : sink.of(Event.APPLY)) {
            if (e.recordIndex != TickFeeder.HEADER) {
                ids.add(e.packetId);
            }
        }
        assertEquals(Arrays.asList(0x01, 0x2B, 0x02), ids);
        assertEquals("three NET records and the header's S09", 4, feeder.dropped());
    }

    @Test
    public void aFailingOrThrowingSinkIsSkippedAndCounted() throws Exception {
        long o = 2_000_000L;
        Rec rec = new Rec().segment(1, standardHeader0(), standardHeader1()).at(o, RecordingFixture.joinGame(0));
        for (int i = 1; i <= 30; i++) {
            rec.at(o + i * 10_000L, p(i % 2 == 0 ? 0x15 : 0x16, i));
            rec.at(o + i * 10_000L, p(0x02, i));
        }
        LoadedRecording r = rec.load(tmp.newFolder(), o, o + 1_000_000L);
        TickFeeder feeder = new TickFeeder(r);
        LogSink sink = new LogSink(feeder);
        sink.throwId = 0x15;
        sink.failId = 0x16;
        for (long k = 0; k <= 7; k++) {
            assertEquals(TickFeeder.State.RUNNING, sink.feed(k));
        }
        assertEquals(30, feeder.failures());
        int chat = 0;
        for (Event e : sink.of(Event.APPLY)) {
            if (e.packetId == 0x02) {
                chat++;
            }
        }
        assertEquals("records after a failure are still fed", 30, chat);
        assertEquals(1 + 30 + 3, feeder.applied()); // S01 + chat + header S01, S38, S47
    }

    @Test
    public void theFiftyFirstFailureEndsWithFailed() throws Exception {
        long o = 3_000_000L;
        Rec rec = new Rec().segment(1, standardHeader0(), standardHeader1()).at(o, RecordingFixture.joinGame(0));
        for (int i = 1; i <= 60; i++) {
            rec.at(o + i * 500L, p(0x15, i)); // all 60 before tick 1
        }
        rec.at(o + 200_000L, p(0x02, 1));
        LoadedRecording r = rec.load(tmp.newFolder(), o, o + 1_000_000L);
        TickFeeder feeder = new TickFeeder(r);
        LogSink sink = new LogSink(feeder) {
            @Override
            public boolean apply(int packetId, byte[] buf, int off, int len) {
                boolean ok = super.apply(packetId, buf, off, len);
                if (packetId == 0x15) {
                    int nth = buf[off + 1];
                    assertEquals("state before failure " + nth, TickFeeder.State.RUNNING, feeder.state());
                }
                return ok;
            }
        };
        sink.failId = 0x15;
        TickFeeder.State s = sink.feed(0);
        assertEquals(TickFeeder.State.RUNNING, s);
        s = sink.feed(1);
        assertEquals(TickFeeder.State.FAILED, s);
        assertEquals(TickFeeder.MAX_FAILURES + 1, feeder.failures());
        int failing = 0;
        for (Event e : sink.of(Event.APPLY)) {
            if (e.packetId == 0x15) {
                failing++;
            }
        }
        assertEquals("nothing after the 51st failure", 51, failing);
        int before = sink.events.size();
        for (long k = 2; k < 10; k++) {
            assertEquals(TickFeeder.State.FAILED, sink.feed(k));
        }
        assertEquals("a failed feed feeds nothing", before, sink.events.size());
        feeder.restart();
        assertEquals("FAILED holds across a restart", TickFeeder.State.FAILED, feeder.state());
    }

    @Test
    public void aRestartReplayingTheSameFailuresDoesNotCountThemTwice() throws Exception {
        long o = 4_000_000L;
        Rec rec = new Rec().segment(1, standardHeader0(), standardHeader1()).at(o, RecordingFixture.joinGame(0));
        for (int i = 1; i <= 30; i++) {
            rec.at(o + i * 20_000L, p(0x15, i));
        }
        LoadedRecording r = rec.load(tmp.newFolder(), o, o + 2_000_000L);
        TickFeeder feeder = new TickFeeder(r);
        LogSink sink = new LogSink(feeder);
        sink.failId = 0x15;
        for (long k = 0; k <= 6; k++) {
            sink.feed(k);
        }
        int first = feeder.failures();
        assertTrue(first > 0 && first < 30);
        for (int pass = 0; pass < 3; pass++) {
            feeder.restart();
            assertEquals(0, feeder.nextTick());
            for (long k = 0; k <= 6; k++) {
                sink.feed(k);
            }
            assertEquals("same records, same count", first, feeder.failures());
        }
        for (long k = 7; k <= 13; k++) {
            sink.feed(k);
        }
        assertEquals("new records still count", 30, feeder.failures());
    }

    @Test
    public void aRecordedKickEndsTheFeed() throws Exception {
        long o = 5_000_000L;
        Rec rec = new Rec().segment(1, standardHeader0(), standardHeader1())
                .at(o, RecordingFixture.joinGame(0))
                .at(o + 120_000L, p(0x02, 1))
                .at(o + 130_000L, p(0x40, 2, '{', '}'))
                .at(o + 130_000L, p(0x02, 2))
                .at(o + 400_000L, p(0x02, 3));
        LoadedRecording r = rec.load(tmp.newFolder(), o, o + 1_000_000L);
        TickFeeder feeder = new TickFeeder(r);
        LogSink sink = new LogSink(feeder);
        for (long k = 0; k <= 2; k++) {
            assertEquals(TickFeeder.State.RUNNING, sink.feed(k));
        }
        assertEquals(-1, feeder.endedAtTick());
        assertEquals(TickFeeder.State.ENDED, sink.feed(3));
        assertEquals(3, feeder.endedAtTick());
        for (long k = 4; k <= 12; k++) {
            assertEquals(TickFeeder.State.ENDED, sink.feed(k));
        }
        for (Event e : sink.of(Event.APPLY)) {
            assertTrue("the kick and what follows are not fed", e.packetId != 0x40
                    && !(e.packetId == 0x02 && e.bytes[1] > 1));
        }
        feeder.restart();
        assertEquals(TickFeeder.State.RUNNING, feeder.state());
        assertEquals(-1, feeder.endedAtTick());
    }

    @Test
    public void ticksMustBeFedInSequence() throws Exception {
        LoadedRecording r = realShaped();
        TickFeeder feeder = new TickFeeder(r);
        LogSink sink = new LogSink(feeder);
        sink.feed(0);
        try {
            sink.feed(2);
            fail("skipping a tick must fail");
        } catch (IllegalStateException expected) {
            // expected
        }
        try {
            sink.feed(0);
            fail("feeding a tick twice must fail");
        } catch (IllegalStateException expected) {
            // expected
        }
    }
}
