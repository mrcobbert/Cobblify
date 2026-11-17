package com.bedwarsqol.rewind;

import com.bedwarsqol.feature.HypixelLocationCodec.Location;
import com.bedwarsqol.feature.SessionChatLine;
import com.bedwarsqol.rewind.RewindGameRules.Command;
import com.bedwarsqol.rewind.RewindGameRules.CommandType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The writer end to end in step mode: a pending run that opens, joins a second segment and closes
 * reads back record for record (A4); a lobby run leaves the folder empty (A6); a close prunes (A5);
 * an unwritable folder or a stream failing mid-game fails only that game (A8, writer side).
 */
public class RewindWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ------------------------------------------------------------------ shared fixture

    /** Settable recorder clock (µs) and wall clock (ms). */
    static final class TestClock implements RewindWriter.Clock {
        long nowUs;
        long wallAtZeroMs;

        TestClock(long nowUs, long wallAtZeroMs) {
            this.nowUs = nowUs;
            this.wallAtZeroMs = wallAtZeroMs;
        }

        @Override
        public long nowUs() {
            return nowUs;
        }

        @Override
        public long wallMs() {
            return wallAtZeroMs + nowUs / 1000;
        }
    }

    static final class NetRec {
        final int segment;
        final long timeUs;
        final byte[] bytes;

        NetRec(int segment, long timeUs, byte[] bytes) {
            this.segment = segment;
            this.timeUs = timeUs;
            this.bytes = bytes;
        }

        @Override
        public String toString() {
            return "Net{seg=" + segment + ", t=" + timeUs + ", id=" + RewindFormat.packetId(bytes, 0, bytes.length)
                    + ", len=" + bytes.length + "}";
        }
    }

    static final class TrackRec {
        final int segment;
        final long timeUs;
        final int tag;
        final byte[] body;

        TrackRec(int segment, long timeUs, int tag, byte[] body) {
            this.segment = segment;
            this.timeUs = timeUs;
            this.tag = tag;
            this.body = body;
        }

        @Override
        public String toString() {
            return "Track{seg=" + segment + ", t=" + timeUs + ", tag=" + tag + ", len=" + body.length + "}";
        }
    }

    /**
     * One recorder: the rules, a writer in step mode on a temp folder, a NET and a TRACK producer.
     * Appends record what was committed so the file can be checked against it.
     */
    static final class Fixture {
        final File dir;
        final TestClock clock;
        final RecordingStore store;
        final RewindWriter writer;
        final RewindGameRules rules = new RewindGameRules();
        final PacketStats.Feed feed = new PacketStats.Feed();
        RecordChunk.Producer net;
        RecordChunk.Producer track;
        final SelfTrackEncoder enc = new SelfTrackEncoder();
        final List<NetRec> netSent = new ArrayList<NetRec>();
        final List<TrackRec> trackSent = new ArrayList<TrackRec>();

        Fixture(File dir, RecordingStore.Locker locker, long startUs) {
            this.dir = dir;
            this.clock = new TestClock(startUs, 1_760_000_000_000L);
            this.store = locker == null ? new RecordingStore(dir) : new RecordingStore(dir, locker);
            this.writer = new RewindWriter(store, "0.0.0-test", clock, new Random(42));
        }

        void connect(boolean singlePlayer, int chunkCapacity, int maxChunks) {
            submit(rules.onConnect(clock.nowUs, singlePlayer, true));
            net = new RecordChunk.Producer(chunkCapacity, 2, maxChunks, clock.nowUs);
            track = new RecordChunk.Producer(chunkCapacity, 2, maxChunks, clock.nowUs);
            writer.attach(net, track, feed);
        }

        void submit(List<Command> commands) {
            writer.submitAll(commands);
        }

        void advance(long us) {
            clock.nowUs += us;
        }

        /** Netty side of a boundary: the boundary packet opens the segment as its first record. */
        void netBoundary(int segment, byte[] carry, byte[] boundaryPacket) {
            assertTrue(net.startSegment(segment, carry, 0, carry == null ? 0 : carry.length));
            net(boundaryPacket);
        }

        /** Client side of a boundary: track segment, source-0 header, rules. */
        void clientBoundary(int segment, List<byte[]> snapshot) {
            assertTrue(track.startSegment(segment, null, 0, 0));
            enc.resetSegment();
            writer.header(segment, clock.nowUs, snapshot);
            submit(rules.onBoundary(clock.nowUs, segment));
        }

        void net(byte[] packet) {
            assertTrue("net append", net.appendNet(clock.nowUs, packet, 0, packet.length));
            netSent.add(new NetRec(net.segment(), clock.nowUs, packet));
        }

        void trackRecord(int tag, byte[] body, int len) {
            assertTrue("track append", track.appendTrack(clock.nowUs, tag, body, 0, len));
            trackSent.add(new TrackRec(track.segment(), clock.nowUs, tag, Arrays.copyOf(body, len)));
        }

        void tick(double x) {
            int n = enc.tick(x, 64, -3.5, 90f, 10f, 91f, RewindFormat.FLAG_ON_GROUND, 2, -1, 0);
            if (n > 0) {
                trackRecord(RewindFormat.TAG_TICK, enc.buffer(), n);
            }
        }

        void marker(int kind) {
            trackRecord(RewindFormat.TAG_MARKER, enc.buffer(), enc.marker(kind));
        }

        void gameEnd(int result) {
            trackRecord(RewindFormat.TAG_GAME_END, enc.buffer(), enc.gameEnd(result));
        }

        void step() {
            writer.step();
        }
    }

    // ------------------------------------------------------------------ packets (fake values only)

    static byte[] s01(int entityId, int dimension) {
        byte[] b = new byte[]{0x01, 0, 0, 0, 0, 1, (byte) dimension, 2, 16, 7, 'd', 'e', 'f', 'a', 'u', 'l', 't', 0};
        RewindFormat.putInt(b, 1, entityId);
        return b;
    }

    static byte[] s07(int dimension) {
        byte[] b = new byte[]{0x07, 0, 0, 0, 0, 2, 1, 4, 'f', 'l', 'a', 't'};
        RewindFormat.putInt(b, 1, dimension);
        return b;
    }

    static byte[] s47(String header) {
        byte[] s = header.getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[1 + 1 + s.length + 1 + 2];
        b[0] = 0x47;
        b[1] = (byte) s.length;
        System.arraycopy(s, 0, b, 2, s.length);
        b[2 + s.length] = 2;
        b[3 + s.length] = '{';
        b[4 + s.length] = '}';
        return b;
    }

    /** A packet {@code id} with a deterministic body of {@code len} bytes. */
    static byte[] packet(int id, int len, int seed) {
        byte[] b = new byte[1 + len];
        b[0] = (byte) id;
        Random r = new Random(seed);
        for (int i = 1; i < b.length; i++) {
            b[i] = (byte) r.nextInt(256);
        }
        return b;
    }

    /** A chat packet (S02) carrying {@code text}. */
    static byte[] chat(String text) {
        byte[] s = ("{\"text\":\"" + text + "\"}").getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[1 + 1 + s.length + 1];
        b[0] = 0x02;
        b[1] = (byte) s.length;
        System.arraycopy(s, 0, b, 2, s.length);
        b[b.length - 1] = 0;
        return b;
    }

    static List<byte[]> snapshot(int dimension) {
        List<byte[]> l = new ArrayList<byte[]>();
        l.add(s01(77, dimension));
        l.add(packet(0x38, 30, 900 + dimension));
        l.add(packet(0x3B, 12, 901 + dimension));
        return l;
    }

    // ------------------------------------------------------------------ reading back

    static final class FileContents {
        final List<NetRec> net = new ArrayList<NetRec>();
        final List<TrackRec> track = new ArrayList<TrackRec>();
        final List<RecordingReader.Block> blocks = new ArrayList<RecordingReader.Block>();
        final List<int[]> headers = new ArrayList<int[]>(); // {segment, source}
        final List<RecordingReader.HeaderPayload> headerPayloads = new ArrayList<RecordingReader.HeaderPayload>();
        List<RecordingReader.IndexEntry> index;
        RecordingMeta endMeta;
        boolean complete;
        boolean tornTail;
        int indexBlocks;
        int endBlocks;
        int blocksAfterEnd;
    }

    static FileContents read(File cbr) throws IOException {
        FileContents fc = new FileContents();
        RecordingReader r = RecordingReader.open(cbr);
        try {
            RecordingReader.Block b;
            boolean ended = false;
            while ((b = r.next()) != null) {
                fc.blocks.add(b);
                if (ended) {
                    fc.blocksAfterEnd++;
                }
                switch (b.type) {
                    case RewindFormat.BLOCK_NET:
                        for (RecordingReader.NetRecord n : RecordingReader.netRecords(b)) {
                            fc.net.add(new NetRec(b.segment, n.timeUs, n.bytes));
                        }
                        break;
                    case RewindFormat.BLOCK_TRACK:
                        for (RecordingReader.TrackRecord t : RecordingReader.trackRecords(b)) {
                            fc.track.add(new TrackRec(b.segment, t.timeUs, t.tag, t.body));
                        }
                        break;
                    case RewindFormat.BLOCK_HEADER:
                        RecordingReader.HeaderPayload h = RecordingReader.headerPayload(b);
                        fc.headers.add(new int[]{b.segment, h.source});
                        fc.headerPayloads.add(h);
                        break;
                    case RewindFormat.BLOCK_INDEX:
                        fc.indexBlocks++;
                        fc.index = RecordingReader.index(b);
                        break;
                    case RewindFormat.BLOCK_END:
                        fc.endBlocks++;
                        fc.endMeta = RecordingMeta.fromJson(RecordingReader.endJson(b));
                        ended = true;
                        break;
                    default:
                        break;
                }
            }
            fc.complete = r.complete();
            fc.tornTail = r.tornTail();
        } finally {
            r.close();
        }
        return fc;
    }

    static void assertNetEquals(List<NetRec> expected, List<NetRec> actual) {
        assertEquals("net record count " + actual, expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            NetRec e = expected.get(i);
            NetRec a = actual.get(i);
            assertEquals("net segment #" + i, e.segment, a.segment);
            assertEquals("net time #" + i, e.timeUs, a.timeUs);
            assertArrayEquals("net bytes #" + i, e.bytes, a.bytes);
        }
    }

    static void assertTrackEquals(List<TrackRec> expected, List<TrackRec> actual) {
        assertEquals("track record count " + actual, expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            TrackRec e = expected.get(i);
            TrackRec a = actual.get(i);
            assertEquals("track segment #" + i, e.segment, a.segment);
            assertEquals("track time #" + i, e.timeUs, a.timeUs);
            assertEquals("track tag #" + i, e.tag, a.tag);
            assertArrayEquals("track body #" + i, e.body, a.body);
        }
    }

    /** NET records grouped in file order by segment, as R2 would feed them (blocks are per segment). */
    static List<NetRec> netOfSegment(List<NetRec> all, int segment) {
        List<NetRec> out = new ArrayList<NetRec>();
        for (NetRec n : all) {
            if (n.segment == segment) {
                out.add(n);
            }
        }
        return out;
    }

    static List<TrackRec> trackOfSegment(List<TrackRec> all, int segment) {
        List<TrackRec> out = new ArrayList<TrackRec>();
        for (TrackRec t : all) {
            if (t.segment == segment) {
                out.add(t);
            }
        }
        return out;
    }

    static Set<String> names(File dir) {
        Set<String> out = new TreeSet<String>();
        String[] list = dir.list();
        if (list != null) {
            out.addAll(Arrays.asList(list));
        }
        return out;
    }

    static List<String> ids(File dir) {
        Set<String> out = new TreeSet<String>();
        for (String n : names(dir)) {
            String id = PruneRules.idOf(n);
            if (id != null) {
                out.add(id);
            }
        }
        return new ArrayList<String>(out);
    }

    static File onlyCbr(File dir) {
        List<String> ids = ids(dir);
        assertEquals("recordings " + names(dir), 1, ids.size());
        return new File(dir, ids.get(0) + ".cbr");
    }

    static RecordingMeta jsonOf(File cbr) throws IOException {
        File json = new File(cbr.getParentFile(), cbr.getName().replace(".cbr", ".json"));
        assertTrue("json exists " + json, json.isFile());
        RecordingMeta m = RecordingMeta.fromUtf8(Files.readAllBytes(json.toPath()));
        assertNotNull(m);
        return m;
    }

    /** A closed recording written by hand: header, one NET block, INDEX, END and a complete .json. */
    static void writeClosed(File dir, String id, long startedAtMs, boolean starred, boolean keyMarked)
            throws IOException {
        dir.mkdirs();
        RecordingFormatTest.FileBuilder fb = new RecordingFormatTest.FileBuilder();
        byte[] net = new byte[32];
        int p = RewindFormat.putVarInt(net, 0, 5);
        byte[] s01 = s01(1, 0);
        p = RewindFormat.putVarInt(net, p, s01.length);
        System.arraycopy(s01, 0, net, p, s01.length);
        p += s01.length;
        fb.block(RewindFormat.BLOCK_NET, 0, 0, Arrays.copyOf(net, p), true);
        fb.block(RewindFormat.BLOCK_INDEX, 0, 0, BlockCodec.indexPayload(
                Collections.singletonList(new RecordingReader.IndexEntry(RewindFormat.BLOCK_NET, 0, 0, 28))), false);
        RecordingMeta m = new RecordingMeta();
        m.id = id;
        m.complete = true;
        m.startedAtMs = startedAtMs;
        m.starred = starred;
        m.keyMarked = keyMarked;
        fb.block(RewindFormat.BLOCK_END, 0, 0, m.toUtf8(), false);
        Files.write(new File(dir, id + ".cbr").toPath(), fb.bytes());
        Files.write(new File(dir, id + ".json").toPath(), m.toUtf8());
        Files.write(new File(dir, id + ".stats.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ A4: round trip

    @Test
    public void pendingRunOpensJoinsAndClosesWithEveryRecordHeaderMarkerAndMetadata() throws IOException {
        File dir = new File(tmp.getRoot(), "rewind");
        Fixture f = new Fixture(dir, null, 10_000_000L);
        f.connect(false, 512, 64);

        // Segment 0: S01 then a lobby-free Bed Wars pregame; pending while the run is unclassified.
        byte[] h0 = s47("FakeHeaderZero");
        f.advance(1_000);
        f.netBoundary(0, h0, s01(1234, 0));
        f.clientBoundary(0, snapshot(0));
        for (int i = 0; i < 40; i++) {
            f.advance(7_000);
            f.net(packet(0x20 + i % 8, 10 + i * 3 % 50, i));
            if (i % 4 == 0) {
                f.tick(i);
            }
            if (i % 10 == 9) {
                f.step();
            }
        }
        f.submit(f.rules.onLocation(f.clock.nowUs, 0,
                new Location("fakeServer1A", "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap")));
        f.step();
        assertEquals("nothing on disk while pending", 0, ids(dir).size());
        assertTrue("pending bytes counted", f.writer.pendingBytes() > 0);

        // Game start: the active edge opens the file with segment 0.
        f.advance(500_000);
        List<Command> open = f.rules.tick(f.clock.nowUs, false, true);
        assertEquals(CommandType.OPEN, open.get(0).type);
        f.submit(open);
        trackRecordGameStart(f);
        f.step();
        assertEquals(1, ids(dir).size());
        assertEquals("pending flushed into the file", 0, f.writer.pendingBytes());

        for (int i = 0; i < 30; i++) {
            f.advance(9_000);
            f.net(packet(0x10 + i % 5, 40 + i, 100 + i));
            f.tick(100 + i);
            if (i == 12) {
                f.submit(f.rules.onMarkerKey(f.clock.nowUs));
                f.marker(RewindFormat.MARKER_KEY);
            }
            if (i == 20) {
                f.advance(RewindWriter.SYNC_INTERVAL_US); // a 2 s flush of the live tails
                f.step();
            }
        }
        f.step();

        // Same-server S07 to another dimension: segment 1 joins the open game.
        byte[] h1 = s47("FakeHeaderOne");
        f.advance(3_000);
        f.netBoundary(1, h1, s07(-1));
        f.clientBoundary(1, snapshot(-1));
        f.submit(f.rules.onLocation(f.clock.nowUs, 1,
                new Location("fakeServer1A", "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap")));
        for (int i = 0; i < 25; i++) {
            f.advance(11_000);
            f.net(packet(0x30 + i % 3, 5 + i * 7 % 90, 200 + i));
            f.tick(300 + i);
        }
        f.submit(f.rules.onGameLine(f.clock.nowUs, SessionChatLine.Kind.FINAL_KILL));
        f.marker(RewindFormat.MARKER_FINAL_KILL);
        f.submit(f.rules.onGameLine(f.clock.nowUs, SessionChatLine.Kind.WIN));
        f.gameEnd(RewindFormat.GAME_END_WIN);
        f.step();
        f.advance(RewindGameRules.RESULT_CLOSE_US);
        List<Command> close = f.rules.tick(f.clock.nowUs, false, false);
        assertEquals(CommandType.CLOSE, close.get(0).type);
        f.submit(close);
        f.step();

        File cbr = onlyCbr(dir);
        FileContents fc = read(cbr);
        assertTrue("complete", fc.complete);
        assertFalse("no torn tail", fc.tornTail);
        assertEquals(1, fc.indexBlocks);
        assertEquals(1, fc.endBlocks);
        assertEquals(RewindFormat.BLOCK_END, fc.blocks.get(fc.blocks.size() - 1).type);
        assertEquals(RewindFormat.BLOCK_INDEX, fc.blocks.get(fc.blocks.size() - 2).type);

        // Every packet and sample, byte for byte with its time, per segment in order.
        for (int seg = 0; seg <= 1; seg++) {
            assertNetEquals(netOfSegment(f.netSent, seg), netOfSegment(fc.net, seg));
            assertTrackEquals(trackOfSegment(f.trackSent, seg), trackOfSegment(fc.track, seg));
        }
        assertEquals(RewindFormat.PACKET_JOIN_GAME, netOfSegment(fc.net, 0).get(0).bytes[0]);
        assertEquals(RewindFormat.PACKET_RESPAWN, netOfSegment(fc.net, 1).get(0).bytes[0]);

        // Both HEADER sources for both segments.
        assertHeader(fc, 0, RewindFormat.SOURCE_CLIENT_SNAPSHOT, snapshot(0));
        assertHeader(fc, 0, RewindFormat.SOURCE_CAPTURE_CARRY, Collections.singletonList(h0));
        assertHeader(fc, 1, RewindFormat.SOURCE_CLIENT_SNAPSHOT, snapshot(-1));
        assertHeader(fc, 1, RewindFormat.SOURCE_CAPTURE_CARRY, Collections.singletonList(h1));

        // INDEX points at every data block.
        int dataBlocks = 0;
        for (RecordingReader.Block b : fc.blocks) {
            if (b.type <= RewindFormat.BLOCK_HEADER) {
                dataBlocks++;
            }
        }
        assertEquals(dataBlocks, fc.index.size());
        for (RecordingReader.IndexEntry e : fc.index) {
            boolean found = false;
            for (RecordingReader.Block b : fc.blocks) {
                found |= b.fileOffset == e.fileOffset && b.type == e.type && b.segment == e.segment;
            }
            assertTrue("index entry names a block at " + e.fileOffset, found);
        }

        // Metadata: END and .json agree.
        RecordingMeta json = jsonOf(cbr);
        RecordingMeta end = fc.endMeta;
        assertNotNull(end);
        for (RecordingMeta m : Arrays.asList(json, end)) {
            assertTrue(m.complete);
            assertFalse(m.recovered);
            assertFalse(m.failed);
            assertEquals("result", m.endedBy);
            assertEquals(RecordingMeta.RESULT_WIN, m.result);
            assertEquals("BEDWARS_EIGHT_ONE", m.mode);
            assertEquals("FakeMap", m.map);
            assertEquals("BEDWARS", m.serverType);
            assertEquals(RecordingMeta.SERVER_HYPIXEL, m.server);
            assertEquals("classified", m.classification);
            assertTrue("key marker keeps it", m.keyMarked);
            assertFalse(m.starred);
            assertEquals(2, m.segments);
            assertEquals(cbr.getName().replace(".cbr", ""), m.id);
            assertEquals(m.endUs - m.gameStartUs, m.lengthUs);
            assertTrue(m.lengthUs > 0);
            assertEquals(2, m.markers.size());
            assertEquals("KEY", m.markers.get(0).kind);
            assertEquals("FINAL_KILL", m.markers.get(1).kind);
            assertTrue(m.rawBytes > 0);
            assertTrue(m.storedBytes > 0);
            assertEquals("0.0.0-test", m.cobblifyVersion);
        }
        assertEquals(json.startedAtMs, end.startedAtMs);
        assertEquals(json.gameStartUs, end.gameStartUs);
        assertEquals(f.clock.wallAtZeroMs + json.gameStartUs / 1000, json.startedAtMs);
        assertTrue(new File(dir, json.id + ".stats.json").isFile());

        // The reader check passes on the writer's file.
        RewindCheck.Summary s = RewindCheck.summarize(cbr);
        assertEquals(s.line(), 0, s.exitCode());
        assertEquals(2, s.segments);
        assertEquals(2, s.segmentsStartingAtBoundary);
    }

    // ------------------------------------------------------------------ code review 1, I5

    /**
     * An active game on one server gets a dimension-changing S07, then the connection drops 1 s later,
     * before a location or the 5 s continuation: the S07 segment's committed records are in the closed file.
     */
    @Test
    public void aDisconnectRightAfterARespawnKeepsTheRespawnSegmentInTheClosedFile() throws IOException {
        File dir = new File(tmp.getRoot(), "rewind");
        Fixture f = new Fixture(dir, null, 1_000_000L);
        f.connect(false, 4096, 64);
        Location server = new Location("fakeServer5E", "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        f.advance(1_000);
        f.netBoundary(1, null, s01(55, 0));
        f.clientBoundary(1, snapshot(0));
        f.submit(f.rules.onLocation(f.clock.nowUs, 1, server));
        f.advance(1_000_000L);
        f.submit(f.rules.tick(f.clock.nowUs, false, true));
        assertTrue(f.rules.gameOpen());
        for (int i = 0; i < 50; i++) {
            f.advance(1_900_000L);
            f.net(packet(0x20 + i % 7, 30 + i, i));
            f.tick(i);
            f.submit(f.rules.tick(f.clock.nowUs, false, true));
            if (i % 10 == 9) {
                f.step();
            }
        }
        // ~100 s: S07 to another dimension on the same server, then its packets (all in the live chunk).
        f.advance(1_000_000L);
        f.netBoundary(2, null, s07(-1));
        f.clientBoundary(2, snapshot(-1));
        for (int i = 0; i < 20; i++) {
            f.advance(40_000);
            f.net(packet(0x30 + i % 5, 50 + i, 500 + i));
            f.tick(500 + i);
        }
        f.advance(200_000);
        List<Command> end = f.rules.onDisconnect(f.clock.nowUs, false);
        f.gameEnd(RewindFormat.GAME_END_UNKNOWN);
        f.submit(end);
        f.step();

        File cbr = onlyCbr(dir);
        FileContents fc = read(cbr);
        assertTrue(fc.complete);
        assertEquals(new TreeSet<Integer>(Arrays.asList(1, 2)), segmentsOf(fc));
        List<NetRec> seg2 = netOfSegment(fc.net, 2);
        assertEquals(21, seg2.size());
        assertArrayEquals("the S07 opens segment 2", s07(-1), seg2.get(0).bytes);
        for (int seg = 1; seg <= 2; seg++) {
            assertNetEquals(netOfSegment(f.netSent, seg), netOfSegment(fc.net, seg));
            assertTrackEquals(trackOfSegment(f.trackSent, seg), trackOfSegment(fc.track, seg));
        }
        assertEquals("disconnect", fc.endMeta.endedBy);
        assertEquals(2, fc.endMeta.segments);
        assertEquals(0, f.writer.lateBytes());
    }

    static Set<Integer> segmentsOf(FileContents fc) {
        Set<Integer> out = new TreeSet<Integer>();
        for (RecordingReader.Block b : fc.blocks) {
            if (b.type <= RewindFormat.BLOCK_HEADER) {
                out.add(b.segment);
            }
        }
        return out;
    }

    private static void trackRecordGameStart(Fixture f) {
        f.trackRecord(RewindFormat.TAG_GAME_START, new byte[]{0}, 1); // backUs 0: started at the active edge
    }

    private static void assertHeader(FileContents fc, int segment, int source, List<byte[]> packets) {
        int found = 0;
        for (int i = 0; i < fc.headers.size(); i++) {
            int[] h = fc.headers.get(i);
            if (h[0] == segment && h[1] == source) {
                found++;
                List<byte[]> got = fc.headerPayloads.get(i).packets;
                assertEquals(packets.size(), got.size());
                for (int k = 0; k < packets.size(); k++) {
                    assertArrayEquals(packets.get(k), got.get(k));
                }
            }
        }
        assertEquals("one HEADER source " + source + " for segment " + segment, 1, found);
    }

    // ------------------------------------------------------------------ A6: lobby leaves nothing

    @Test
    public void lobbyRunLeavesTheFolderEmpty() {
        File dir = new File(tmp.getRoot(), "rewind");
        Fixture f = new Fixture(dir, null, 5_000_000L);
        f.connect(false, 256, 64);
        f.advance(1_000);
        f.netBoundary(0, null, s01(5, 0));
        f.clientBoundary(0, snapshot(0));
        f.submit(f.rules.onLocation(f.clock.nowUs, 0, new Location("fakeLobby3", "MAIN", "fakeLobby3", null, null)));
        for (int i = 0; i < 60; i++) {
            f.advance(100_000);
            f.net(packet(0x02, 40, i));
            f.tick(i);
            List<Command> c = f.rules.tick(f.clock.nowUs, false, false);
            f.submit(c);
            f.step();
        }
        f.advance(RewindWriter.SYNC_INTERVAL_US);
        f.step();
        f.submit(f.rules.onDisconnect(f.clock.nowUs, false));
        f.step();
        assertEquals("no file for a lobby: " + names(dir), 0, names(dir).size());
        assertEquals(0, f.writer.pendingBytes());
    }

    // ------------------------------------------------------------------ A5: close prunes

    @Test
    public void closeRunsPruningAndKeepsStarredAndKeyMarked() throws IOException {
        File dir = new File(tmp.getRoot(), "rewind");
        List<String> old = new ArrayList<String>();
        for (int i = 0; i < 10; i++) {
            String id = String.format("201901%02d-120000-%04x", i + 1, i);
            writeClosed(dir, id, 1_546_300_000_000L + i * 86_400_000L, false, false);
            old.add(id);
        }
        writeClosed(dir, "20180101-120000-aaaa", 1_514_800_000_000L, true, false);
        writeClosed(dir, "20180102-120000-bbbb", 1_514_900_000_000L, false, true);
        File unrelated = new File(dir, "notes.txt");
        Files.write(unrelated.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        // Links named like the oldest closed recording, pointing outside: never followed, never deleted.
        File outside = tmp.newFolder("outside");
        writeClosed(outside, "20170101-120000-cccc", 1_483_000_000_000L, false, false);
        List<File> links = new ArrayList<File>();
        for (String n : PruneRules.fileNames("20170101-120000-cccc")) {
            File link = new File(dir, n);
            Files.createSymbolicLink(link.toPath(), new File(outside, n).toPath());
            links.add(link);
        }

        Fixture f = new Fixture(dir, null, 7_000_000L);
        f.connect(true, 256, 16);
        f.advance(1_000);
        f.netBoundary(0, null, s01(9, 0));
        f.clientBoundary(0, snapshot(0));
        f.tick(1);
        f.step();
        f.advance(2_000_000L);
        f.submit(f.rules.onDisconnect(f.clock.nowUs, false));
        f.step();

        List<String> left = ids(dir);
        assertFalse("oldest unmarked pruned", left.contains(old.get(0)));
        for (int i = 1; i < 10; i++) {
            assertTrue(left.contains(old.get(i)));
        }
        assertTrue("starred kept", left.contains("20180101-120000-aaaa"));
        assertTrue("key-marked kept", left.contains("20180102-120000-bbbb"));
        assertEquals("9 old + new + starred + key-marked + the linked id", 13, left.size());
        assertTrue(unrelated.isFile());
        for (File link : links) {
            assertTrue("link kept " + link, Files.isSymbolicLink(link.toPath()));
        }
        for (String n : PruneRules.fileNames("20170101-120000-cccc")) {
            assertTrue("link target intact " + n, new File(outside, n).isFile());
        }
        for (String n : PruneRules.fileNames(old.get(0))) {
            assertFalse(new File(dir, n).exists());
        }
        assertEquals(1, f.writer.pruned());
    }

    // ------------------------------------------------------------------ A8: writer failures

    @Test
    public void unwritableFolderFailsTheGameAndThrowsNothing() throws IOException {
        File notADir = tmp.newFile("plain-file");
        File dir = new File(notADir, "rewind");
        Fixture f = new Fixture(dir, null, 3_000_000L);
        f.connect(true, 256, 16);
        f.advance(1_000);
        f.netBoundary(0, null, s01(9, 0));
        f.clientBoundary(0, snapshot(0));
        for (int i = 0; i < 20; i++) {
            f.advance(10_000);
            f.net(packet(0x15, 30, i));
            f.tick(i);
            f.step();
        }
        f.submit(f.rules.onDisconnect(f.clock.nowUs, false));
        f.step();
        assertEquals(1, f.writer.gameFailures());
        assertNotNull(f.writer.lastFailure());
        assertFalse(dir.exists());
        // The writer keeps working for the next game: still nothing thrown, chunks still released.
        f.connect(true, 256, 16);
        f.netBoundary(0, null, s01(9, 0));
        f.clientBoundary(0, snapshot(0));
        for (int i = 0; i < 40; i++) {
            f.advance(10_000);
            f.net(packet(0x15, 30, i)); // asserts the producer never runs out of chunks
            f.step();
        }
        assertEquals("the next game fails on its own", 2, f.writer.gameFailures());
        assertEquals("nothing held for a failed game", 0, f.writer.pendingBytes());
    }

    @Test
    public void streamFailingMidGameKeepsAReadablePrefixAndThrowsNothing() throws IOException {
        File dir = new File(tmp.getRoot(), "rewind");
        final long[] budget = {6_000};
        RecordingStore.Locker failing = new RecordingStore.Locker() {
            @Override
            public RecordingStore.Handle open(File cbr, boolean create) throws IOException {
                final RecordingStore.Handle real = RecordingStore.FILE_LOCKS.open(cbr, create);
                if (real == null) {
                    return null;
                }
                return new RecordingStore.Handle() {
                    @Override
                    public long size() throws IOException {
                        return real.size();
                    }

                    @Override
                    public int read(long position, byte[] dst, int off, int len) throws IOException {
                        return real.read(position, dst, off, len);
                    }

                    @Override
                    public void write(long position, byte[] src, int off, int len) throws IOException {
                        if (len > budget[0]) {
                            // a torn write: part of the block lands, then the disk is full
                            int part = (int) Math.max(0, budget[0]);
                            real.write(position, src, off, part / 2);
                            budget[0] = -1;
                            throw new IOException("No space left on device (test)");
                        }
                        budget[0] -= len;
                        real.write(position, src, off, len);
                    }

                    @Override
                    public void truncate(long size) throws IOException {
                        real.truncate(size);
                    }

                    @Override
                    public void force() throws IOException {
                        if (budget[0] < 0) {
                            throw new IOException("fsync failed (test)");
                        }
                        real.force();
                    }

                    @Override
                    public void close() throws IOException {
                        real.close();
                    }
                };
            }
        };
        Fixture f = new Fixture(dir, failing, 4_000_000L);
        f.connect(true, 1024, 64);
        f.advance(1_000);
        f.netBoundary(0, null, s01(9, 0));
        f.clientBoundary(0, snapshot(0));
        f.step();
        for (int i = 0; i < 400; i++) {
            f.advance(5_000);
            f.net(packet(0x22, 60 + i % 40, i));
            f.tick(i);
            if (i % 5 == 4) {
                f.step();
            }
        }
        f.step();
        assertEquals("the disk error failed the game", 1, f.writer.gameFailures());
        long failedGame;
        while ((failedGame = f.writer.pollFailedGame()) >= 0) {
            f.submit(f.rules.onWriterFailure(f.clock.nowUs, failedGame));
        }
        f.step();

        File cbr = onlyCbr(dir);
        FileContents fc = read(cbr);
        assertFalse("never finalized past the failure", fc.complete);
        assertTrue("prefix holds packets", fc.net.size() > 0);
        assertTrue("prefix is shorter than what was sent", fc.net.size() < f.netSent.size());
        // The prefix is exactly the first records sent, in order, byte for byte.
        assertNetEquals(f.netSent.subList(0, fc.net.size()), fc.net);
        RecordingMeta m = jsonOf(cbr);
        assertTrue(m.failed);
        assertFalse(m.complete);
    }

    // ------------------------------------------------------------------ code review 2, I6: a long idle

    /** A single-player game idle for 75 minutes: every NET and TRACK time in the file is exact. */
    @Test
    public void aSeventyFiveMinuteIdleKeepsEveryRecordTimeExact() throws IOException {
        File dir = new File(tmp.getRoot(), "rewind");
        Fixture f = new Fixture(dir, null, 2_000_000L);
        f.connect(true, 4096, 16);
        f.advance(1_000);
        f.netBoundary(1, null, s01(9, 0));
        f.clientBoundary(1, snapshot(0));
        for (int i = 0; i < 5; i++) {
            f.advance(50_000);
            f.net(packet(0x15, 20, i));
            f.tick(i);
        }
        f.step();
        f.advance(75L * 60 * 1_000_000);                                     // paused, nothing changes
        for (int i = 0; i < 5; i++) {
            f.net(packet(0x16, 20, 10 + i));
            f.tick(100 + i);
            f.advance(50_000);
            f.step();
        }
        f.advance(RewindWriter.SYNC_INTERVAL_US);
        f.submit(f.rules.onDisconnect(f.clock.nowUs, false));
        f.step();
        FileContents fc = read(onlyCbr(dir));
        assertTrue(fc.complete);
        assertNetEquals(f.netSent, fc.net);
        assertTrackEquals(f.trackSent, fc.track);
    }

    // ------------------------------------------------------------------ helpers used by other tests

    static Set<String> set(String... s) {
        return new HashSet<String>(Arrays.asList(s));
    }
}
