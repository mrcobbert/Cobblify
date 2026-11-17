package com.bedwarsqol.rewind;

import com.bedwarsqol.feature.SessionChatLine;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.bedwarsqol.rewind.CaptureSessionTest.other;
import static com.bedwarsqol.rewind.CaptureSessionTest.s01;
import static com.bedwarsqol.rewind.CaptureSessionTest.s07;
import static com.bedwarsqol.rewind.CaptureSessionTest.s3f;
import static com.bedwarsqol.rewind.CaptureSessionTest.s47;
import static com.bedwarsqol.rewind.CaptureSessionTest.varInt;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The whole recorder on one connection: the capture handler on an {@link EmbeddedChannel} with vanilla's handler
 * names, the client-thread wiring ({@link Rewind}) driven by a manual task queue that stands in for Minecraft's
 * scheduled tasks, and the real writer in step mode on a temp folder. Scripted with fake names only.
 *
 * <ul>
 *   <li>A Bed Wars game with a same-server S07 and a lobby after it: one file, two segments, each starting with its
 *       own S01/S07, the markers and the metadata (A4, A6).</li>
 *   <li>A dropped predecessor (round 2 I2): the lobby segment is absent and the game's first segment starts with
 *       its S07, its source-0 S01 naming the lobby's dimension.</li>
 *   <li>A delayed client thread (round 2 B1): source 1 holds the S47 from before the boundary, source 0 none.</li>
 *   <li>Record Games off (round 1 I1): the CLOSE is written before the handler removal runs; nothing follows END.</li>
 *   <li>Failures (A8): an unwritable folder or a throwing snapshot never touch the connection.</li>
 *   <li>Off (A10): no handler, no writer, no file.</li>
 * </ul>
 */
public class RewindIntegrationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ------------------------------------------------------------------ harness

    static final class Harness {
        final RewindWriterTest.TestClock clock = new RewindWriterTest.TestClock(50_000_000L, 1_760_000_000_000L);
        final File dir;
        final RewindWriter writer;
        final ArrayDeque<Runnable> client = new ArrayDeque<Runnable>();
        final Rewind rewind;
        final RewindCaptureTest.Received rx = new RewindCaptureTest.Received();
        final EmbeddedChannel ch = RewindCaptureTest.remotePipeline(rx);
        int writersMade;
        int fired;
        /** NET records the file should hold, by the segment the capture handler gave them. */
        final Map<Integer, List<byte[]>> netSent = new TreeMap<Integer, List<byte[]>>();
        int netSegment;

        Harness(File dir) {
            this.dir = dir;
            this.writer = new RewindWriter(new RecordingStore(dir), "0.0.0-test", clock, new Random(7));
            this.rewind = new Rewind(clock, new java.util.concurrent.Executor() {
                @Override
                public void execute(Runnable task) {
                    client.add(task);
                }
            }, new java.util.function.Supplier<RewindWriter>() {
                @Override
                public RewindWriter get() {
                    writersMade++;
                    return writer;
                }
            });
        }

        CaptureSession connect(boolean rewindOn, boolean record) {
            CaptureSession s = rewind.onServerConnect(ch, null, false, "mc.hypixel.net", rewindOn, record);
            runClient();
            return s;
        }

        void runClient() {
            Runnable t;
            while ((t = client.poll()) != null) {
                t.run();
            }
        }

        void advance(long us) {
            clock.nowUs += us;
        }

        /** Frames through the channel (Netty thread); remembers what a copied segment should hold. */
        void net(byte[]... frames) {
            for (byte[] f : frames) {
                int id = RewindFormat.packetId(f, 0, f.length);
                if (id == RewindFormat.PACKET_JOIN_GAME || id == RewindFormat.PACKET_RESPAWN && isNewDimension(f)) {
                    netSegment++;
                }
                if (!RewindFormat.isSkipped(id)) {
                    List<byte[]> l = netSent.get(netSegment);
                    if (l == null) {
                        netSent.put(netSegment, l = new ArrayList<byte[]>());
                    }
                    l.add(f);
                }
                if (id == RewindFormat.PACKET_JOIN_GAME) {
                    dimension = f[1 + 4 + 1];
                } else if (id == RewindFormat.PACKET_RESPAWN) {
                    dimension = RewindFormat.getInt(f, 1);
                }
                RewindCaptureTest.fire(ch, f);
                fired++;
            }
        }

        int dimension = Integer.MIN_VALUE;

        private boolean isNewDimension(byte[] s07) {
            return RewindFormat.getInt(s07, 1) != dimension;
        }

        /** Netty sees the boundary packet, then the client thread applies it (WORLD_LOAD). */
        void boundary(byte[] packet, final int snapshotDimension) {
            net(packet);
            runClient();
            rewind.onWorldLoad(new Rewind.SnapshotSource() {
                @Override
                public CarryOverEncoder.Snapshot capture() {
                    return snapshot(snapshotDimension);
                }
            });
        }

        void location(String server, String type, String lobby, String mode, String map) {
            net(s3f("hyevent:location", locationPayload(server, type, lobby, mode, map)));
            runClient();
        }

        void game(int packets, int seed) {
            for (int i = 0; i < packets; i++) {
                advance(20_000);
                net(other(0x10 + (seed + i) % 16, 8 + (seed * 7 + i * 13) % 300));
                if (i % 3 == 0) {
                    self(seed * 100 + i);
                }
                if (i % 5 == 4) {
                    tick(false, false);
                }
            }
        }

        void self(final double x) {
            rewind.selfTrack(new Rewind.TrackWork() {
                @Override
                public boolean sample(SelfTrackSampler s, RecordChunk.Producer track, long nowUs) {
                    return s.tick(track, nowUs, x, 64.0, -2.5, 90f, 5f, 91f, RewindFormat.FLAG_ON_GROUND, 1, -1, 0);
                }
            });
        }

        void tick(boolean queue, boolean active) {
            rewind.tick(true, queue, active);
        }

        void step() {
            writer.step();
        }

        List<String> ids() {
            return RewindWriterTest.ids(dir);
        }
    }

    static byte[] locationPayload(String server, String type, String lobby, String mode, String map) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);                                   // success
        write(out, varInt(1));                          // version
        write(out, CaptureSessionTest.string(server));
        for (String opt : new String[]{type, lobby, mode, map}) {
            if (opt == null) {
                out.write(0);
            } else {
                out.write(1);
                write(out, CaptureSessionTest.string(opt));
            }
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }

    /** Fake client values as of a boundary, in the world of {@code dimension}. */
    static CarryOverEncoder.Snapshot snapshot(int dimension) {
        CarryOverEncoder.Snapshot s = new CarryOverEncoder.Snapshot();
        s.entityId = 900 + dimension;
        s.gameType = 0;
        s.dimension = dimension;
        s.difficulty = 2;
        s.maxPlayers = 16;
        s.levelType = "default";
        s.tab.add(new CarryOverEncoder.TabEntry(1L, 2L, "FakeTabPlayer", null, 0, 33, null));
        s.objectives.add(new CarryOverEncoder.Objective("fakeObj", "FAKE", "integer"));
        s.displaySlots[1] = "fakeObj";
        s.walkSpeed = 0.1f;
        s.flySpeed = 0.05f;
        s.heldSlot = 2;
        return s;
    }

    static void assertNoS47(List<byte[]> packets) {
        for (byte[] p : packets) {
            assertNotEquals(RewindFormat.PACKET_PLAYER_LIST_HEADER_FOOTER, RewindFormat.packetId(p, 0, p.length));
        }
    }

    static List<byte[]> netOf(RewindWriterTest.FileContents fc, int segment) {
        List<byte[]> out = new ArrayList<byte[]>();
        for (RewindWriterTest.NetRec n : fc.net) {
            if (n.segment == segment) {
                out.add(n.bytes);
            }
        }
        return out;
    }

    static RecordingReader.HeaderPayload header(RewindWriterTest.FileContents fc, int segment, int source) {
        RecordingReader.HeaderPayload found = null;
        for (int i = 0; i < fc.headers.size(); i++) {
            int[] h = fc.headers.get(i);
            if (h[0] == segment && h[1] == source) {
                assertNull("one HEADER source " + source + " for segment " + segment, found);
                found = fc.headerPayloads.get(i);
            }
        }
        return found;
    }

    static Set<Integer> segments(RewindWriterTest.FileContents fc) {
        Set<Integer> out = new TreeSet<Integer>();
        for (RecordingReader.Block b : fc.blocks) {
            if (b.type <= RewindFormat.BLOCK_HEADER) {
                out.add(b.segment);
            }
        }
        return out;
    }

    static void assertSameRecords(List<byte[]> want, List<byte[]> got) {
        assertEquals("record count", want.size(), got.size());
        for (int i = 0; i < want.size(); i++) {
            assertArrayEquals("record " + i, want.get(i), got.get(i));
        }
    }

    private static final String GAME = "fakeGameServer1A";

    /** Connect, S01, a Bed Wars location, the pregame, then the active edge: the game is open on segment 1. */
    static Harness openGame(File dir) {
        Harness h = new Harness(dir);
        assertNotNull(h.connect(true, true));
        h.advance(1_000);
        h.boundary(s01(101, 0), 0);
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.game(30, 1);
        h.tick(true, false);
        h.step();
        assertTrue("nothing on disk before the game starts", h.ids().isEmpty());
        h.advance(300_000);
        h.tick(false, true);
        assertTrue("the active edge opened the game", h.rewind.rules().gameOpen());
        h.step();
        assertEquals(1, h.ids().size());
        return h;
    }

    // ------------------------------------------------------------------ A4, A6: one game, two segments

    @Test
    public void bedWarsGameWithASameServerRespawnAndALobbyAfterItIsOneFileOfTwoSegments() throws Exception {
        Harness h = openGame(new File(tmp.getRoot(), "rewind"));
        h.game(40, 2);
        h.rewind.onGameLine(SessionChatLine.Kind.FINAL_KILL);
        h.rewind.onGameLine(SessionChatLine.Kind.KILL); // not a marker
        h.rewind.onMarkerKey();
        assertNotEquals("the accepted key marker flashes", 0, h.rewind.flashColor());
        h.game(10, 3);
        h.step();

        // Same server, another dimension: segment 2 joins the game.
        h.advance(4_000);
        h.boundary(s07(-1), 0);
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.game(40, 4);
        h.rewind.onGameLine(SessionChatLine.Kind.BED_BREAK);
        h.game(5, 5);

        // A lobby: the game closes; the lobby run is dropped and never reaches the file.
        h.advance(3_000);
        h.boundary(s01(102, 0), -1);
        h.location("fakeLobby2", "MAIN", "fakeLobby2", null, null);
        h.game(20, 6);
        h.advance(RewindGameRules.LOBBY_VERDICT_WAIT_US);
        h.tick(false, false);
        h.step();
        h.advance(RewindWriter.SYNC_INTERVAL_US);
        h.step();

        h.ch.checkException();
        assertTrue(h.ch.isOpen());
        assertEquals("every message reached packet_handler", h.fired, h.rx.messages.size());

        File cbr = RewindWriterTest.onlyCbr(h.dir);
        RewindWriterTest.FileContents fc = RewindWriterTest.read(cbr);
        assertTrue(fc.complete);
        assertFalse(fc.tornTail);
        assertEquals(0, fc.blocksAfterEnd);
        assertEquals(new TreeSet<Integer>(Arrays.asList(1, 2)), segments(fc));
        assertArrayEquals("segment 1 starts with its S01", s01(101, 0), netOf(fc, 1).get(0));
        assertArrayEquals("segment 2 starts with its S07", s07(-1), netOf(fc, 2).get(0));
        assertSameRecords(h.netSent.get(1), netOf(fc, 1));
        assertSameRecords(h.netSent.get(2), netOf(fc, 2));
        for (int seg = 1; seg <= 2; seg++) {
            RecordingReader.HeaderPayload s0 = header(fc, seg, RewindFormat.SOURCE_CLIENT_SNAPSHOT);
            assertNotNull("source 0 of segment " + seg, s0);
            assertEquals(RewindFormat.PACKET_JOIN_GAME, RewindFormat.packetId(s0.packets.get(0), 0, 4));
            assertNoS47(s0.packets);
            assertNotNull("source 1 of segment " + seg, header(fc, seg, RewindFormat.SOURCE_CAPTURE_CARRY));
        }
        int gameStarts = 0;
        for (RewindWriterTest.TrackRec t : fc.track) {
            if (t.tag == RewindFormat.TAG_GAME_START) {
                gameStarts++;
            }
        }
        assertEquals(1, gameStarts);

        for (RecordingMeta m : Arrays.asList(RewindWriterTest.jsonOf(cbr), fc.endMeta)) {
            assertTrue(m.complete);
            assertFalse(m.failed);
            assertEquals("lobby", m.endedBy);
            assertEquals("BEDWARS_EIGHT_ONE", m.mode);
            assertEquals("FakeMap", m.map);
            assertEquals(RecordingMeta.SERVER_HYPIXEL, m.server);
            assertTrue(m.keyMarked);
            assertEquals(2, m.segments);
            List<String> kinds = new ArrayList<String>();
            for (RecordingMeta.Marker k : m.markers) {
                kinds.add(k.kind);
            }
            assertEquals(Arrays.asList("FINAL_KILL", "KEY", "BED_BROKEN"), kinds);
        }
        RewindCheck.Summary s = RewindCheck.summarize(cbr);
        assertEquals(s.line(), 0, s.exitCode());
        assertEquals(2, s.segmentsStartingAtBoundary);
        String text = new String(Files.readAllBytes(cbr.toPath()), StandardCharsets.ISO_8859_1)
                + new String(Files.readAllBytes(new File(h.dir, cbr.getName().replace(".cbr", ".stats.json")).toPath()),
                StandardCharsets.UTF_8);
        assertFalse("no server name in the metadata or stats", text.contains("fakeGameServer1A"));
        assertFalse(text.contains("mc.hypixel.net"));
    }

    // ------------------------------------------------------------------ round 2 I2: dropped predecessor

    @Test
    public void aDroppedLobbySegmentLeavesTheGameStartingWithItsOwnS07() throws Exception {
        Harness h = new Harness(new File(tmp.getRoot(), "rewind"));
        assertNotNull(h.connect(true, true));
        h.advance(1_000);
        h.boundary(s01(201, 0), 0);
        h.location("fakeLobby9", "MAIN", "fakeLobby9", null, null);
        h.game(20, 11);
        // Queue straight from the lobby: S07 to the game's dimension, then the game server's location.
        h.advance(2_000);
        h.boundary(s07(-1), 0);
        h.location("fakeGame9", "BEDWARS", null, "BEDWARS_TWO_FOUR", "FakeIsland");
        h.game(30, 12);
        h.advance(500_000);
        h.tick(false, true);
        assertTrue(h.rewind.rules().gameOpen());
        h.game(30, 13);
        h.rewind.onMarkerKey();
        h.game(5, 14);
        h.rewind.onGameLine(SessionChatLine.Kind.WIN);
        h.game(5, 15);
        h.advance(RewindGameRules.RESULT_CLOSE_US);
        h.tick(false, false);
        assertFalse(h.rewind.rules().gameOpen());
        h.step();

        File cbr = RewindWriterTest.onlyCbr(h.dir);
        RewindWriterTest.FileContents fc = RewindWriterTest.read(cbr);
        assertTrue(fc.complete);
        assertEquals("the lobby segment is absent", Collections.singleton(2), segments(fc));
        byte[] first = netOf(fc, 2).get(0);
        assertArrayEquals("the game's first NET record is its own S07", s07(-1), first);
        assertEquals(-1, RewindFormat.getInt(first, 1));
        RecordingReader.HeaderPayload s0 = header(fc, 2, RewindFormat.SOURCE_CLIENT_SNAPSHOT);
        byte[] synthetic = s0.packets.get(0);
        assertEquals(RewindFormat.PACKET_JOIN_GAME, synthetic[0]);
        assertEquals("source-0 S01 names the lobby's dimension", 0, synthetic[1 + 4 + 1]);
        assertSameRecords(h.netSent.get(2), netOf(fc, 2));
        assertEquals(RecordingMeta.RESULT_WIN, fc.endMeta.result);
        RewindCheck.Summary s = RewindCheck.summarize(cbr);
        assertEquals(s.line(), 0, s.exitCode());
    }

    // ------------------------------------------------------------------ round 2 B1: delayed client thread

    @Test
    public void s47BeforeTheBoundaryIsSourceOneEvenWhenTheClientThreadRunsLate() throws Exception {
        Harness h = openGame(new File(tmp.getRoot(), "rewind"));
        byte[] h0 = s47("FakeHeaderZero", "FakeFooterZero");
        byte[] h1 = s47("FakeHeaderOne", "FakeFooterOne");
        byte[] respawn = s07(-1);
        // Netty runs ahead: S47(H0), the boundary, S47(H1), and only then the client thread's WORLD_LOAD.
        h.net(h0, respawn, h1);
        final CarryOverEncoder.Snapshot fake = snapshot(0);
        h.runClient();
        h.rewind.onWorldLoad(new Rewind.SnapshotSource() {
            @Override
            public CarryOverEncoder.Snapshot capture() {
                return fake;
            }
        });
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.rewind.onGameLine(SessionChatLine.Kind.LOSS);
        h.advance(RewindGameRules.RESULT_CLOSE_US);
        h.tick(false, false);
        h.step();

        RewindWriterTest.FileContents fc = RewindWriterTest.read(RewindWriterTest.onlyCbr(h.dir));
        assertTrue(fc.complete);
        RecordingReader.HeaderPayload carry = header(fc, 2, RewindFormat.SOURCE_CAPTURE_CARRY);
        assertSameRecords(Collections.singletonList(h0), carry.packets);
        RecordingReader.HeaderPayload s0 = header(fc, 2, RewindFormat.SOURCE_CLIENT_SNAPSHOT);
        assertNoS47(s0.packets);
        assertSameRecords(CarryOverSnapshot.packets(fake), s0.packets);
        assertSameRecords(Arrays.asList(respawn, h1), netOf(fc, 2));
        assertEquals(RecordingMeta.RESULT_LOSS, fc.endMeta.result);
    }

    // ------------------------------------------------------------------ round 1 I1: Record Games off

    @Test
    public void recordGamesOffClosesTheFileBeforeTheHandlerRemovalRuns() throws Exception {
        Harness h = openGame(new File(tmp.getRoot(), "rewind"));
        h.game(25, 21);
        h.rewind.onMarkerKey();
        h.game(5, 22);

        h.rewind.tick(false, false, true); // Record Games switched off
        assertFalse(h.rewind.rules().active());
        assertNotNull("removal waits for the event loop", RewindCapture.get(h.ch));
        h.step(); // the writer runs the CLOSE first
        File cbr = RewindWriterTest.onlyCbr(h.dir);
        byte[] closed = Files.readAllBytes(cbr.toPath());
        RewindWriterTest.FileContents before = RewindWriterTest.read(cbr);
        assertTrue(before.complete);
        assertEquals("settings", before.endMeta.endedBy);

        h.net(other(0x20, 40), other(0x21, 50)); // still copied by the live handler, after the cutoff
        h.ch.runPendingTasks(); // now the removal runs and hands off the live chunk
        assertNull(RewindCapture.get(h.ch));
        h.step();
        h.advance(RewindWriter.SYNC_INTERVAL_US);
        h.step();
        h.net(other(0x22, 60));

        assertArrayEquals("nothing appended after END", closed, Files.readAllBytes(cbr.toPath()));
        RewindWriterTest.FileContents after = RewindWriterTest.read(cbr);
        assertTrue(after.complete);
        assertEquals(0, after.blocksAfterEnd);
        assertEquals(1, h.ids().size());
        h.ch.checkException();
        assertEquals(h.fired, h.rx.messages.size());

        // Off from now on: a new connection with Record Games off gets no handler.
        EmbeddedChannel next = RewindCaptureTest.remotePipeline(new RewindCaptureTest.Received());
        assertNull(h.rewind.onServerConnect(next, null, false, "mc.hypixel.net", true, false));
        h.runClient();
        assertNull(RewindCapture.get(next));
    }

    // ------------------------------------------------------------------ A8: failures leave the connection alone

    @Test
    public void unwritableFolderFailsTheGameAndLeavesTheConnectionUntouched() throws Exception {
        File blocker = tmp.newFile("notAFolder");
        Harness h = new Harness(new File(blocker, "rewind"));
        assertNotNull(h.connect(true, true));
        h.advance(1_000);
        h.boundary(s01(301, 0), 0);
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.game(20, 31);
        h.advance(300_000);
        h.tick(false, true);
        h.step(); // OPEN cannot create the file
        assertEquals(1, h.writer.gameFailures());
        h.game(10, 32); // the client tick sees the writer failure: the game closes as failed
        assertFalse(h.rewind.rules().gameOpen());
        h.rewind.onMarkerKey();
        h.boundary(s07(-1), 0);
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.game(10, 33);
        h.step();

        h.ch.checkException();
        assertTrue(h.ch.isOpen());
        assertEquals(h.fired, h.rx.messages.size());
        for (int i = 0; i < h.rx.content.size(); i++) {
            assertEquals("reader index untouched", 0, (int) h.rx.readerIndex.get(i));
        }
        assertFalse(new File(blocker, "rewind").exists());
    }

    @Test
    public void aThrowingSnapshotFailsOnlyThatGameAndTheBoundaryCountStaysAligned() throws Exception {
        Harness h = openGame(new File(tmp.getRoot(), "rewind"));
        h.game(10, 41);
        h.rewind.onMarkerKey();
        h.net(s07(-1));
        h.runClient();
        h.rewind.onWorldLoad(new Rewind.SnapshotSource() {
            @Override
            public CarryOverEncoder.Snapshot capture() {
                throw new LinkageError("fake client state failure");
            }
        });
        h.game(10, 42);
        assertFalse("the game closed as failed", h.rewind.rules().gameOpen());
        h.step();
        RewindWriterTest.FileContents fc = RewindWriterTest.read(RewindWriterTest.onlyCbr(h.dir));
        assertTrue(fc.complete);
        assertTrue(fc.endMeta.failed);

        // The next game on the same connection is recorded again, from its own boundary.
        h.rewind.onGameLine(SessionChatLine.Kind.LOSS); // the failed game provably ended: rearm
        h.boundary(s01(402, 0), -1);
        h.location("fakeGameServer2B", "BEDWARS", null, "BEDWARS_EIGHT_TWO", "FakeOther");
        h.game(10, 43);
        h.advance(300_000);
        h.tick(false, true);
        assertTrue(h.rewind.rules().gameOpen());
        h.game(10, 44);
        h.ch.close();
        h.tick(false, false); // the disconnect closes it
        h.step();
        assertEquals(2, h.ids().size());
        h.ch.checkException();
        assertEquals(h.fired, h.rx.messages.size());
    }

    // ------------------------------------------------------------------ A10: off

    @Test
    public void rewindOrRecordGamesOffMeansNoHandlerNoWriterNoFile() {
        for (boolean[] flags : new boolean[][]{{false, true}, {true, false}, {false, false}}) {
            File dir = new File(tmp.getRoot(), "off-" + flags[0] + "-" + flags[1]);
            Harness h = new Harness(dir);
            assertNull(h.connect(flags[0], flags[1]));
            assertNull(RewindCapture.get(h.ch));
            h.boundary(s01(501, 0), 0);
            h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
            h.game(10, 51);
            h.tick(false, true);
            h.rewind.onMarkerKey();
            assertEquals("no flash while off", 0, h.rewind.flashColor());
            assertEquals("no writer is ever made", 0, h.writersMade);
            assertFalse(dir.exists());
            assertEquals(h.fired, h.rx.messages.size());
        }
    }
}
