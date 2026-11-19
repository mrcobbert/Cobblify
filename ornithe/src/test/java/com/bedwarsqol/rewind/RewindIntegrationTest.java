package com.bedwarsqol.rewind;

import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.DiagLog;
import com.bedwarsqol.feature.SessionChatLine;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

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
import static org.junit.Assert.assertSame;
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
            this(dir, new RecordingStore(dir));
        }

        Harness(File dir, RecordingStore store) {
            this.dir = dir;
            this.writer = new RewindWriter(store, "0.0.0-test", clock, new Random(7));
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

        /** The last connect's capture session, or null. */
        CaptureSession session;

        CaptureSession connect(boolean rewindOn, boolean record) {
            CaptureSession s = rewind.onServerConnect(ch, null, false, "mc.hypixel.net", rewindOn, record);
            session = s;
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
        // The failure is segment 2's, an undecided candidate (code review 2, I2): the game fails once its location
        // shows segment 2 is this game's, and keeps it.
        assertTrue(h.rewind.rules().gameOpen());
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        assertFalse("the game closed as failed", h.rewind.rules().gameOpen());
        h.step();
        RewindWriterTest.FileContents fc = RewindWriterTest.read(RewindWriterTest.onlyCbr(h.dir));
        assertTrue(fc.complete);
        assertTrue(fc.endMeta.failed);
        assertEquals(new TreeSet<Integer>(Arrays.asList(1, 2)), segments(fc));

        // The next game on the same connection is recorded again, from its own boundary.
        h.rewind.onGameLine(SessionChatLine.Kind.LOSS); // the failed game provably ended
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

    // ------------------------------------------------------------------ code review 1, I2: failure before connect

    /** Netty runs ahead of a delayed connect task: S01, initial world data, then {@code failure}. */
    private void failureBeforeTheConnectTaskIsHandledAndALaterGameIsRecorded(String name, boolean exhaust)
            throws Exception {
        Harness h = new Harness(new File(tmp.getRoot(), name));
        CaptureSession session = h.rewind.onServerConnect(h.ch, null, false, "mc.hypixel.net", true, true);
        assertNotNull(session);
        assertEquals("the connect task is still queued", 1, h.client.size());
        h.advance(1_000);
        h.net(s01(701, 0));
        if (exhaust) {
            for (int i = 0; i < 13 && !session.failed(); i++) {
                h.net(other(0x21, 1 << 20)); // 1 MiB of initial world data each, nobody drains yet
            }
        } else {
            h.net(other(0x21, 300), other(0x22, 40));
            session.fail(new IllegalStateException("fake capture failure"));
        }
        assertTrue(session.failed());
        assertEquals(1, session.failureCount());
        h.net(other(0x23, 50));

        // Only now does the client thread run the connect task, then the S01's WORLD_LOAD.
        h.runClient();
        h.rewind.onWorldLoad(new Rewind.SnapshotSource() {
            @Override
            public CarryOverEncoder.Snapshot capture() {
                return snapshot(0);
            }
        });
        h.location("fakeGame7A", "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.tick(true, false);
        h.advance(300_000);
        h.tick(false, true);
        assertFalse("the rules saw the failure: the failed run never opens", h.rewind.rules().gameOpen());
        h.step();
        assertTrue("nothing recorded for the failed run", h.ids().isEmpty());

        // The failed run provably ends at a lobby; the next game on this connection is recorded.
        h.advance(1_000_000L);
        h.boundary(s01(702, 0), 0);
        h.location("fakeLobby7", "MAIN", "fakeLobby7", null, null);
        h.game(5, 71);
        h.step();
        h.advance(1_000_000L);
        h.boundary(s01(703, 0), 0);
        h.location("fakeGame7B", "BEDWARS", null, "BEDWARS_EIGHT_TWO", "FakeOther");
        h.game(20, 72);
        h.advance(300_000);
        h.tick(false, true);
        assertTrue(h.rewind.rules().gameOpen());
        h.game(20, 73);
        h.ch.close();
        h.tick(false, false);
        h.step();

        File cbr = RewindWriterTest.onlyCbr(h.dir);
        RewindWriterTest.FileContents fc = RewindWriterTest.read(cbr);
        assertTrue(fc.complete);
        assertFalse(fc.endMeta.failed);
        assertEquals(Collections.singleton(3), segments(fc));
        assertArrayEquals(s01(703, 0), netOf(fc, 3).get(0));
        assertSameRecords(h.netSent.get(3), netOf(fc, 3));
        h.ch.checkException();
        assertEquals(h.fired, h.rx.messages.size());
    }

    @Test
    public void aCaptureExceptionBeforeTheConnectTaskReachesTheRules() throws Exception {
        failureBeforeTheConnectTaskIsHandledAndALaterGameIsRecorded("exception", false);
    }

    @Test
    public void chunkExhaustionFromInitialWorldDataBeforeTheConnectTaskReachesTheRules() throws Exception {
        failureBeforeTheConnectTaskIsHandledAndALaterGameIsRecorded("exhaustion", true);
    }

    // ------------------------------------------------------------------ code review 1, I4: no log I/O on the client

    /** Stands in for DiagLog's file: records which thread wrote each line. */
    static final class ThreadRecordingWriter extends PrintWriter {
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<Thread>());

        ThreadRecordingWriter() {
            super(new StringWriter());
        }

        @Override
        public void println(String s) {
            lines.add(s);
            threads.add(Thread.currentThread());
            super.println(s);
        }
    }

    @Test
    public void rewindNeverWritesTheDiagLogOnTheClientThread() throws Exception {
        Field outField = DiagLog.class.getDeclaredField("out");
        Field writtenField = DiagLog.class.getDeclaredField("written");
        outField.setAccessible(true);
        writtenField.setAccessible(true);
        Object oldOut;
        long oldWritten;
        ThreadRecordingWriter log = new ThreadRecordingWriter();
        synchronized (DiagLog.class) {
            oldOut = outField.get(null);
            oldWritten = writtenField.getLong(null);
            outField.set(null, log); // initialised as in production: DiagLog writes, it is not a no-op
            writtenField.setLong(null, 0L);
        }
        ExecutorService writerThread = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "test-writer-thread");
            }
        });
        try {
            final Harness h = new Harness(new File(tmp.getRoot(), "rewind"));
            Runnable step = new Runnable() {
                @Override
                public void run() {
                    h.writer.step();
                }
            };
            Thread client = Thread.currentThread();
            CaptureSession session = h.connect(true, true);                       // connect
            writerThread.submit(step).get();
            h.advance(1_000);
            h.boundary(s01(901, 0), 0);
            h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
            h.game(10, 91);
            h.advance(300_000);
            h.tick(false, true);                                                 // OPEN
            assertTrue(h.rewind.rules().gameOpen());
            writerThread.submit(step).get();
            h.game(10, 92);
            session.fail(new IllegalStateException("fake capture failure"));     // failure + failed CLOSE
            h.tick(false, true);
            assertFalse(h.rewind.rules().gameOpen());
            writerThread.submit(step).get();
            h.rewind.onGameLine(SessionChatLine.Kind.WIN);                       // the failed game ended: rearm
            h.advance(1_000_000L);
            h.boundary(s01(902, 0), -1);
            h.location("fakeGameServer9B", "BEDWARS", null, "BEDWARS_EIGHT_TWO", "FakeOther");
            h.game(10, 93);
            h.advance(300_000);
            h.tick(false, true);                                                 // OPEN
            assertTrue(h.rewind.rules().gameOpen());
            h.game(10, 94);
            h.ch.close();
            h.tick(false, false);                                                // disconnect CLOSE
            writerThread.submit(step).get();

            List<String> rewindLines = new ArrayList<String>();
            synchronized (log.lines) {
                for (int i = 0; i < log.lines.size(); i++) {
                    String line = log.lines.get(i);
                    if (line.contains("rewind:")) {
                        rewindLines.add(line);
                        assertNotEquals("written on the client thread: " + line, client, log.threads.get(i));
                    }
                }
            }
            String all = rewindLines.toString();
            assertTrue(all, all.contains("rewind: connect"));
            assertTrue(all, all.contains("rewind: open"));
            assertTrue(all, all.contains("rewind: capture failure"));
            assertTrue(all, all.contains("rewind: close endedBy=failed"));
            assertTrue(all, all.contains("rewind: close endedBy=disconnect"));
            assertEquals(2, h.ids().size());
        } finally {
            writerThread.shutdownNow();
            synchronized (DiagLog.class) {
                outField.set(null, oldOut);
                writtenField.setLong(null, oldWritten);
            }
        }
    }

    // ------------------------------------------------------------------ code review 1, I6: recovery keeps the start

    /**
     * A non-Bed-Wars visit that opens at 60 s (start = visit start). Clean: the connection ends at T. Crash: the
     * process dies at T after the last 2 s flush, the {@code .json} is lost, and a new writer recovers the file.
     */
    private RecordingMeta sixtySecondVisit(String name, boolean crash) throws Exception {
        Harness h = new Harness(new File(tmp.getRoot(), name));
        assertNotNull(h.connect(true, true));
        h.advance(1_000);
        h.boundary(s01(801, 0), 0);
        h.location("fakeSkyServer3", "SKYWARS", null, "solo_normal", "FakeSky");
        for (int i = 0; i < 75; i++) {
            h.advance(1_000_000L);
            h.net(other(0x15 + i % 4, 30 + i));
            h.self(i);
            h.tick(false, false);
            if (i == 70) {
                h.rewind.onMarkerKey();
            }
            if (i % 10 == 9) {
                h.step();
            }
        }
        assertTrue("open at 60 s", h.rewind.rules().gameOpen());
        h.advance(500_000);
        h.self(1000);                                                            // the last record, at T
        if (!crash) {
            h.ch.close();
            h.tick(false, false);                                                // CLOSE at T
            h.step();
            return RewindWriterTest.jsonOf(RewindWriterTest.onlyCbr(h.dir));
        }
        h.step();
        h.advance(RewindWriter.SYNC_INTERVAL_US);
        h.step();                                                                // the 2 s flush reaches disk
        File cbr = RewindWriterTest.onlyCbr(h.dir);
        File after = new File(tmp.getRoot(), name + "-after-crash");
        assertTrue(after.mkdirs());
        File copy = new File(after, cbr.getName());
        Files.copy(cbr.toPath(), copy.toPath());                                 // no .json survives
        RewindWriter fresh = new RewindWriter(new RecordingStore(after), "0.0.0-test", h.clock, new Random(8));
        fresh.recoverAndPrune();
        assertEquals(1, fresh.recovered());
        return RewindWriterTest.jsonOf(copy);
    }

    @Test
    public void recoveryWithoutTheJsonKeepsTheVisitStartDateAndLength() throws Exception {
        RecordingMeta clean = sixtySecondVisit("clean", false);
        RecordingMeta recovered = sixtySecondVisit("crash", true);
        assertTrue(recovered.recovered);
        assertEquals("start = visit start", 50_000_000L + 1_000, clean.gameStartUs);
        assertEquals(clean.gameStartUs, recovered.gameStartUs);
        assertEquals(clean.startedAtMs, recovered.startedAtMs);
        assertEquals(clean.endUs, recovered.endUs);
        assertEquals(clean.lengthUs, recovered.lengthUs);
        assertEquals(clean.mode, recovered.mode);
        assertEquals(clean.map, recovered.map);
        assertEquals(clean.markers.size(), recovered.markers.size());
        assertEquals(clean.markers.get(0).tUs, recovered.markers.get(0).tUs);
        assertEquals(clean.keyMarked, recovered.keyMarked);
    }

    // ------------------------------------------------------------------ code review 1, I3: growing TRACK bodies

    /** Every TRACK record of a producer's live chunk, as the writer's walker reads it. */
    static List<RewindWriterTest.TrackRec> walkLive(RecordChunk.Producer p) {
        final List<RewindWriterTest.TrackRec> out = new ArrayList<RewindWriterTest.TrackRec>();
        RecordChunk c = p.liveChunk();
        RecordingReader.walkTrack(c.data(), 0, c.committedLength(), c.startTimeUs(), new RecordingReader.TrackVisitor() {
            @Override
            public void track(long timeUs, int tag, byte[] buf, int off, int len) {
                out.add(new RewindWriterTest.TrackRec(0, timeUs, tag, Arrays.copyOfRange(buf, off, off + len)));
            }
        });
        return out;
    }

    static String text(int bytes) {
        StringBuilder sb = new StringBuilder(bytes);
        for (int i = 0; i < bytes; i++) {
            sb.append((char) ('a' + i % 26));
        }
        return sb.toString();
    }

    @Test
    public void infoValuesThatGrowTheEncoderBufferRoundTripThroughTheTrackWalker() {
        for (int len : new int[]{251, 300, 5000}) {
            SelfTrackSampler s = new SelfTrackSampler();
            RecordChunk.Producer p = new RecordChunk.Producer(RewindFormat.CHUNK_CAPACITY, 1, 1, 0L);
            assertTrue(p.startSegment(1, null, 0, 0));
            String value = text(len);
            assertTrue("info " + len, s.info(p, 10L, RewindFormat.INFO_MAP, value));
            assertTrue(s.marker(p, 20L, RewindFormat.MARKER_KEY));
            List<RewindWriterTest.TrackRec> recs = walkLive(p);
            assertEquals("records for " + len, 2, recs.size());
            RewindWriterTest.TrackRec info = recs.get(0);
            assertEquals(RewindFormat.TAG_INFO, info.tag);
            RewindFormat.Cursor c = new RewindFormat.Cursor(info.body, 0, info.body.length);
            assertEquals(RewindFormat.INFO_MAP, c.u8());
            assertEquals(value, c.string(RewindFormat.MAX_STRING_BYTES));
            assertEquals(RewindFormat.TAG_MARKER, recs.get(1).tag);
        }
    }

    /**
     * A protocol-47 slot as vanilla's {@code writeItemStackToBuffer} lays it out (i16 id, u8 count, i16 damage,
     * named root compound): a sword with a long display name and 40 long lore lines, about 5.6 KB of NBT. Built by
     * hand so no Minecraft registry (and its log4j file config) is started by the test.
     */
    static byte[] largeItemSlot() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(out);
        try {
            d.writeShort(276);                    // diamond sword
            d.writeByte(1);
            d.writeShort(0);
            d.writeByte(10);                      // root compound, empty name
            d.writeShort(0);
            d.writeByte(10);                      // "display" compound
            d.writeUTF("display");
            d.writeByte(8);
            d.writeUTF("Name");
            d.writeUTF("Fake " + text(300));
            d.writeByte(9);                       // "Lore": list of strings
            d.writeUTF("Lore");
            d.writeByte(8);
            d.writeInt(40);
            for (int i = 0; i < 40; i++) {
                d.writeUTF("fake lore line " + i + " " + text(120));
            }
            d.writeByte(0);                       // end of display
            d.writeByte(0);                       // end of root
            d.flush();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }

    @Test
    public void aLargeEquipNbtRoundTripsThroughTheTrackWalker() {
        byte[] item = largeItemSlot();
        assertTrue(item.length > 5000);
        byte[] padded = new byte[item.length + 7];
        System.arraycopy(item, 0, padded, 3, item.length);
        SelfTrackSampler s = new SelfTrackSampler();
        RecordChunk.Producer p = new RecordChunk.Producer(RewindFormat.CHUNK_CAPACITY, 1, 1, 0L);
        assertTrue(p.startSegment(1, null, 0, 0));
        assertTrue(s.equipBytes(p, 10L, RewindFormat.EQUIP_HELD, padded, 3, item.length));
        assertTrue(s.info(p, 20L, RewindFormat.INFO_MODE, text(400)));
        byte[] empty = {(byte) 0xFF, (byte) 0xFF};
        assertTrue(s.equipBytes(p, 30L, RewindFormat.EQUIP_HELMET, empty, 0, empty.length));
        List<RewindWriterTest.TrackRec> recs = walkLive(p);
        assertEquals(3, recs.size());
        assertEquals(RewindFormat.TAG_EQUIP, recs.get(0).tag);
        byte[] body = recs.get(0).body;
        assertEquals(RewindFormat.EQUIP_HELD, body[0]);
        assertArrayEquals(item, Arrays.copyOfRange(body, 1, body.length));
        assertEquals(RewindFormat.TAG_INFO, recs.get(1).tag);
        assertEquals(RewindFormat.TAG_EQUIP, recs.get(2).tag);
        assertArrayEquals(new byte[]{RewindFormat.EQUIP_HELMET, (byte) 0xFF, (byte) 0xFF}, recs.get(2).body);
    }

    @Test
    public void aLongMapNameAtOpenIsRecordedAndTheGameIsNotFailed() throws Exception {
        Harness h = new Harness(new File(tmp.getRoot(), "rewind"));
        assertNotNull(h.connect(true, true));
        h.advance(1_000);
        h.boundary(s01(601, 0), 0);
        String map = "Fake" + text(296);
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", map);
        h.game(10, 61);
        h.advance(300_000);
        h.tick(false, true);
        assertTrue(h.rewind.rules().gameOpen());
        h.game(10, 62);
        h.ch.close();
        h.tick(false, false);
        h.step();
        RewindWriterTest.FileContents fc = RewindWriterTest.read(RewindWriterTest.onlyCbr(h.dir));
        assertTrue(fc.complete);
        assertFalse(fc.endMeta.failed);
        assertEquals(map, fc.endMeta.map);
        int infos = 0;
        for (RewindWriterTest.TrackRec t : fc.track) {
            if (t.tag == RewindFormat.TAG_INFO) {
                infos++;
            }
        }
        assertEquals(2, infos);
    }

    // ------------------------------------------------------------------ code review 2: shared helpers

    /** The client thread applies the boundary the capture handler already saw (WORLD_LOAD), after its queued tasks. */
    static void worldLoad(Harness h, final int snapshotDimension) {
        h.runClient();
        h.rewind.onWorldLoad(new Rewind.SnapshotSource() {
            @Override
            public CarryOverEncoder.Snapshot capture() {
                return snapshot(snapshotDimension);
            }
        });
    }

    static List<String> metaModes(File dir) throws IOException {
        List<String> out = new ArrayList<String>();
        for (String id : RewindWriterTest.ids(dir)) {
            out.add(RewindWriterTest.jsonOf(new File(dir, id + ".cbr")).mode);
        }
        return out;
    }

    static RecordingMeta metaOfSegments(File dir, Set<Integer> wanted) throws IOException {
        for (String id : RewindWriterTest.ids(dir)) {
            File cbr = new File(dir, id + ".cbr");
            if (segments(RewindWriterTest.read(cbr)).equals(wanted)) {
                return RewindWriterTest.jsonOf(cbr);
            }
        }
        throw new AssertionError("no recording with segments " + wanted + " in " + RewindWriterTest.names(dir));
    }

    static int count(RewindWriterTest.FileContents fc, int tag) {
        int n = 0;
        for (RewindWriterTest.TrackRec t : fc.track) {
            if (t.tag == tag) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ code review 2, I1: the client runs behind Netty

    /**
     * WIN, then Netty reads a same-server S07 one second before the 15 s result close, but the client thread's tick at
     * +15 s runs before that S07's WORLD_LOAD task. The segment is the game's and must be in its file.
     */
    @Test
    public void aRespawnTheClientHasNotLoadedWhenTheResultCloseIsDueStaysInTheGame() throws Exception {
        Harness h = openGame(new File(tmp.getRoot(), "rewind"));
        h.game(20, 81);
        h.rewind.onGameLine(SessionChatLine.Kind.WIN);
        h.advance(RewindGameRules.RESULT_CLOSE_US - 1_000_000L);
        h.net(s07(-1));                                                         // Netty: segment 2 starts
        h.net(other(0x30, 70), other(0x31, 80));
        h.advance(1_000_000L);
        h.tick(false, true);                                                    // due, before WORLD_LOAD
        h.step();
        worldLoad(h, 0);
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.tick(false, false);
        h.step();
        assertFalse(h.rewind.rules().gameOpen());

        RewindWriterTest.FileContents fc = RewindWriterTest.read(RewindWriterTest.onlyCbr(h.dir));
        assertTrue(fc.complete);
        assertEquals(new TreeSet<Integer>(Arrays.asList(1, 2)), segments(fc));
        assertSameRecords(h.netSent.get(2), netOf(fc, 2));
        assertEquals("result", fc.endMeta.endedBy);
        assertEquals(RecordingMeta.RESULT_WIN, fc.endMeta.result);
    }

    /** As above, but the connection ends before the client ever loads the respawn: it is still the game's. */
    @Test
    public void aRespawnTheClientNeverLoadedBeforeTheDisconnectStaysInTheGame() throws Exception {
        Harness h = openGame(new File(tmp.getRoot(), "rewind"));
        h.game(20, 82);
        h.net(s07(-1));
        h.net(other(0x30, 70), other(0x31, 80));
        h.ch.close();
        h.tick(false, false);                                                   // disconnect, WORLD_LOAD never ran
        h.step();
        RewindWriterTest.FileContents fc = RewindWriterTest.read(RewindWriterTest.onlyCbr(h.dir));
        assertTrue(fc.complete);
        assertEquals(new TreeSet<Integer>(Arrays.asList(1, 2)), segments(fc));
        assertSameRecords(h.netSent.get(2), netOf(fc, 2));
        assertEquals("disconnect", fc.endMeta.endedBy);
    }

    // ------------------------------------------------------------------ code review 2, I2: failure ownership by segment

    /**
     * After attach, Netty reads S01 and the session latches a failure in segment 1; the client ticks ({@code
     * tickFirst}) before or after that S01's WORLD_LOAD. The failed run must never open, the latch must survive the
     * same-server S07, and only the game's result rearms capture; the next game is then recorded.
     */
    private void earlyFailureOwnsItsSegment(String name, boolean beforeAttach, boolean tickFirst) throws Exception {
        Harness h = new Harness(new File(tmp.getRoot(), name));
        CaptureSession session = h.rewind.onServerConnect(h.ch, null, false, "mc.hypixel.net", true, true);
        assertNotNull(session);
        if (!beforeAttach) {
            h.runClient();                                                      // the connect task ran
        }
        h.advance(1_000);
        h.net(s01(1101, 0), other(0x21, 300), other(0x22, 40));
        session.fail(new IllegalStateException("fake capture failure"));
        h.net(other(0x23, 50));
        h.runClient();
        if (tickFirst) {
            h.tick(false, false);                                               // before the S01's WORLD_LOAD
        }
        worldLoad(h, 0);
        if (!tickFirst) {
            h.tick(false, false);
        }
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.tick(true, false);
        h.advance(300_000);
        h.tick(false, true);
        assertFalse("the failed run never opens", h.rewind.rules().gameOpen());
        h.game(10, 111);
        // Same server, new dimension: still the failed game. The latch must hold.
        h.advance(1_000_000L);
        h.boundary(s07(-1), 0);
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.game(10, 112);
        h.advance(6_000_000L);
        h.tick(false, true);
        h.step();
        assertTrue("capture stays latched inside the failed game", session.failed());
        assertFalse(h.rewind.rules().gameOpen());
        assertTrue("nothing recorded for the failed game", h.ids().isEmpty());

        // Its result ends it: rearm; Play Again to another server is recorded.
        h.rewind.onGameLine(SessionChatLine.Kind.WIN);
        h.advance(2_000_000L);
        h.boundary(s01(1102, 0), -1);
        assertFalse("copying restarted at the boundary after the rearm", session.failed());
        h.location("fakeGameServer11B", "BEDWARS", null, "BEDWARS_EIGHT_TWO", "FakeOther");
        h.game(10, 113);
        h.advance(300_000);
        h.tick(false, true);
        assertTrue(h.rewind.rules().gameOpen());
        h.game(10, 114);
        h.ch.close();
        h.tick(false, false);
        h.step();
        RewindWriterTest.FileContents fc = RewindWriterTest.read(RewindWriterTest.onlyCbr(h.dir));
        assertTrue(fc.complete);
        assertFalse(fc.endMeta.failed);
        assertEquals(Collections.singleton(3), segments(fc));
        assertSameRecords(h.netSent.get(3), netOf(fc, 3));
        h.ch.checkException();
        assertEquals(h.fired, h.rx.messages.size());
    }

    /** The reviewer's sequence: attached, failure in segment 1, a tick before its WORLD_LOAD. */
    @Test
    public void aFailureAfterAttachWithATickBeforeTheWorldLoadIsOwnedByItsGame() throws Exception {
        earlyFailureOwnsItsSegment("attached-tick-first", false, true);
    }

    @Test
    public void aFailureAfterAttachWithTheWorldLoadBeforeATickIsOwnedByItsGame() throws Exception {
        earlyFailureOwnsItsSegment("attached-load-first", false, false);
    }

    @Test
    public void aFailureBeforeAttachWithATickBeforeTheWorldLoadIsOwnedByItsGame() throws Exception {
        earlyFailureOwnsItsSegment("unattached-tick-first", true, true);
    }

    @Test
    public void aFailureBeforeAttachWithTheWorldLoadBeforeATickIsOwnedByItsGame() throws Exception {
        earlyFailureOwnsItsSegment("unattached-load-first", true, false);
    }

    /**
     * A game is open in segment 1; Netty reads a dimension-changing S07 (segment 2) and latches a failure there before
     * the client loads it. With the same server, segment 2's prefix is the game's: the file keeps it and is failed.
     * With another server, the open game is healthy and closes normally; the new run owns the failure.
     */
    private void failureInASegmentTheClientHasNotReached(String name, boolean sameServer, boolean tickFirst)
            throws Exception {
        Harness h = openGame(new File(tmp.getRoot(), name));
        CaptureSession session = RewindCapture.get(h.ch).session();
        h.game(20, 121);
        h.advance(1_000_000L);
        h.net(sameServer ? s07(-1) : s01(1201, 0), other(0x24, 90), other(0x25, 60));
        session.fail(new IllegalStateException("fake capture failure"));
        h.net(other(0x26, 70));
        h.runClient();
        if (tickFirst) {
            h.tick(false, true);
        }
        worldLoad(h, 0);
        h.tick(false, sameServer);
        if (sameServer) {
            h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        } else {
            h.location("fakeGameServer12B", "BEDWARS", null, "BEDWARS_EIGHT_TWO", "FakeOther");
        }
        h.tick(false, false);
        h.step();
        assertFalse(h.rewind.rules().gameOpen());
        assertTrue("the latch holds until the owner provably ends", session.failed());
        File cbr = RewindWriterTest.onlyCbr(h.dir);
        RewindWriterTest.FileContents fc = RewindWriterTest.read(cbr);
        assertTrue(fc.complete);
        if (sameServer) {
            assertTrue(fc.endMeta.failed);
            assertEquals(new TreeSet<Integer>(Arrays.asList(1, 2)), segments(fc));
            assertSameRecords(h.netSent.get(2).subList(0, 3), netOf(fc, 2));   // the prefix before the latch
        } else {
            assertFalse("the open game did not fail", fc.endMeta.failed);
            assertEquals("server", fc.endMeta.endedBy);
            assertEquals(Collections.singleton(1), segments(fc));
            // The failed run's game starts and ends: the result rearms, nothing more is written.
            h.advance(300_000);
            h.tick(false, true);
            assertFalse(h.rewind.rules().gameOpen());
            h.rewind.onGameLine(SessionChatLine.Kind.LOSS);
            h.boundary(s01(1202, 0), 0);
            assertFalse(session.failed());
            h.step();
            assertEquals(1, h.ids().size());
        }
    }

    @Test
    public void aFailureInAnUnloadedSameServerSegmentTickFirstKeepsItsPrefixInTheFailedGame() throws Exception {
        failureInASegmentTheClientHasNotReached("same-tick", true, true);
    }

    @Test
    public void aFailureInAnUnloadedSameServerSegmentLoadFirstKeepsItsPrefixInTheFailedGame() throws Exception {
        failureInASegmentTheClientHasNotReached("same-load", true, false);
    }

    @Test
    public void aFailureInAnUnloadedOtherServerSegmentTickFirstLeavesTheOpenGameHealthy() throws Exception {
        failureInASegmentTheClientHasNotReached("other-tick", false, true);
    }

    @Test
    public void aFailureInAnUnloadedOtherServerSegmentLoadFirstLeavesTheOpenGameHealthy() throws Exception {
        failureInASegmentTheClientHasNotReached("other-load", false, false);
    }

    // ------------------------------------------------------------------ code review 2, I3: writer failures per game

    /** FILE_LOCKS, with {@code force()} failing on the first file this store creates (game A) only. */
    static RecordingStore.Locker forceFailsForTheFirstFile(final int[] forceFailures) {
        return new RecordingStore.Locker() {
            int created;

            @Override
            public RecordingStore.Handle open(File cbr, boolean create) throws IOException {
                final RecordingStore.Handle real = RecordingStore.FILE_LOCKS.open(cbr, create);
                final boolean failing = create && created++ == 0;
                if (real == null || !failing) {
                    return real;
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
                        real.write(position, src, off, len);
                    }

                    @Override
                    public void truncate(long size) throws IOException {
                        real.truncate(size);
                    }

                    @Override
                    public void force() throws IOException {
                        forceFailures[0]++;
                        throw new IOException("fsync failed (test)");
                    }

                    @Override
                    public void close() throws IOException {
                        real.close();
                    }
                };
            }
        };
    }

    /** The reviewer's sequence: A's force() fails during its CLOSE while B is already open on the client. */
    @Test
    public void aLateWriterFailureOfAClosedGameLeavesTheNextGameHealthy() throws Exception {
        File dir = new File(tmp.getRoot(), "rewind");
        int[] forceFailures = {0};
        Harness h = new Harness(dir, new RecordingStore(dir, forceFailsForTheFirstFile(forceFailures)));
        assertNotNull(h.connect(true, true));
        h.advance(1_000);
        h.boundary(s01(1301, 0), 0);
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.game(10, 131);
        h.advance(300_000);
        h.tick(false, true);
        assertTrue(h.rewind.rules().gameOpen());
        h.step();                                                                // A is on disk
        h.game(10, 132);
        // B: another server; the client closes A and opens B before the writer runs either command.
        h.advance(1_000_000L);
        h.boundary(s01(1302, 0), -1);
        h.location("fakeGameServer13B", "BEDWARS", null, "BEDWARS_EIGHT_TWO", "FakeOther");
        h.game(10, 133);
        h.advance(300_000);
        h.tick(false, true);
        assertTrue("B open before the writer ran", h.rewind.rules().gameOpen());
        h.step();                                                                // CLOSE A fails at force(), OPEN B
        assertEquals(1, forceFailures[0]);
        assertEquals(1, h.writer.gameFailures());
        h.tick(false, true);                                                     // the client polls the failure
        assertTrue("A's failure never closes B", h.rewind.rules().gameOpen());
        h.game(10, 134);
        h.ch.close();
        h.tick(false, false);
        h.step();
        assertEquals(2, h.ids().size());
        RecordingMeta b = metaOfSegments(dir, Collections.singleton(2));
        assertTrue(b.complete);
        assertFalse("B is healthy", b.failed);
        assertEquals("disconnect", b.endedBy);
        RecordingMeta a = metaOfSegments(dir, Collections.singleton(1));
        assertTrue("A's file is marked failed", a.failed);
    }

    /** Neighbour: A's late failure arrives while B is still a pending run (before its active edge). */
    @Test
    public void aLateWriterFailureOfAClosedGameLeavesAPendingRunAlone() throws Exception {
        File dir = new File(tmp.getRoot(), "rewind");
        int[] forceFailures = {0};
        Harness h = new Harness(dir, new RecordingStore(dir, forceFailsForTheFirstFile(forceFailures)));
        assertNotNull(h.connect(true, true));
        h.advance(1_000);
        h.boundary(s01(1401, 0), 0);
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.game(10, 141);
        h.advance(300_000);
        h.tick(false, true);
        h.step();
        h.advance(1_000_000L);
        h.boundary(s01(1402, 0), -1);
        h.location("fakeGameServer14B", "BEDWARS", null, "BEDWARS_EIGHT_TWO", "FakeOther");
        h.tick(true, false);                                                     // B pending (queue)
        h.step();                                                                // CLOSE A fails
        assertEquals(1, h.writer.gameFailures());
        h.game(10, 142);                                                         // ticks poll the failure
        h.advance(300_000);
        h.tick(false, true);
        assertTrue("B still opens", h.rewind.rules().gameOpen());
        h.game(10, 143);
        h.ch.close();
        h.tick(false, false);
        h.step();
        RecordingMeta b = metaOfSegments(dir, Collections.singleton(2));
        assertFalse(b.failed);
        assertTrue(b.complete);
    }

    // ------------------------------------------------------------------ code review 2, I4: metadata after OPEN

    /** The reviewer's sequence: sidebar fallback OPEN at 11 s, the same segment's location with mode and map at 21 s. */
    @Test
    public void aLocationAfterTheSidebarOpenReachesTheJsonEndAndTrack() throws Exception {
        Harness h = new Harness(new File(tmp.getRoot(), "rewind"));
        assertNotNull(h.connect(true, true));
        h.advance(1_000);
        h.boundary(s01(1501, 0), 0);
        for (int i = 0; i < 10; i++) {
            h.advance(1_000_000L);
            h.tick(true, false);
        }
        h.advance(1_000_000L);
        h.tick(false, true);                                                     // 11 s: opened by the sidebar
        assertTrue(h.rewind.rules().gameOpen());
        h.step();
        h.game(10, 151);
        h.advance(10_000_000L);
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");      // 21 s
        h.step();
        RecordingMeta live = RewindWriterTest.jsonOf(RewindWriterTest.onlyCbr(h.dir));
        assertEquals("the .json is updated while recording", "FakeMap", live.map);
        h.game(10, 152);
        h.ch.close();
        h.tick(false, false);
        h.step();
        File cbr = RewindWriterTest.onlyCbr(h.dir);
        RewindWriterTest.FileContents fc = RewindWriterTest.read(cbr);
        assertTrue(fc.complete);
        for (RecordingMeta m : Arrays.asList(RewindWriterTest.jsonOf(cbr), fc.endMeta)) {
            assertEquals("BEDWARS_EIGHT_ONE", m.mode);
            assertEquals("FakeMap", m.map);
        }
        assertEquals("mode and map INFO records after the open", 2, count(fc, RewindFormat.TAG_INFO));
    }

    /** A same-server location that names another map later in the game: the latest map wins everywhere. */
    @Test
    public void aMapChangeMidGameIsTheMapSaved() throws Exception {
        Harness h = openGame(new File(tmp.getRoot(), "rewind"));
        h.game(10, 161);
        h.advance(1_000_000L);
        h.boundary(s07(-1), 0);
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMapTwo");
        h.game(10, 162);
        h.ch.close();
        h.tick(false, false);
        h.step();
        File cbr = RewindWriterTest.onlyCbr(h.dir);
        RewindWriterTest.FileContents fc = RewindWriterTest.read(cbr);
        for (RecordingMeta m : Arrays.asList(RewindWriterTest.jsonOf(cbr), fc.endMeta)) {
            assertEquals("FakeMapTwo", m.map);
            assertEquals("BEDWARS_EIGHT_ONE", m.mode);
        }
        // Recovery without the .json reads the same map from TRACK: the last INFO wins.
        File after = new File(tmp.getRoot(), "after");
        assertTrue(after.mkdirs());
        byte[] bytes = Files.readAllBytes(cbr.toPath());
        int cut = (int) fc.blocks.get(fc.blocks.size() - 2).fileOffset;         // drop INDEX + END: a crash
        assertEquals(RewindFormat.BLOCK_INDEX, fc.blocks.get(fc.blocks.size() - 2).type);
        Files.write(new File(after, cbr.getName()).toPath(), Arrays.copyOf(bytes, cut));
        RewindWriter fresh = new RewindWriter(new RecordingStore(after), "0.0.0-test", h.clock, new Random(9));
        fresh.recoverAndPrune();
        assertEquals(1, fresh.recovered());
        assertEquals("FakeMapTwo", RewindWriterTest.jsonOf(new File(after, cbr.getName())).map);
    }

    /** No location for the second game: its mode and map stay absent, never the first game's. */
    @Test
    public void noLocationLeavesModeAndMapAbsentNotTheLastServers() throws Exception {
        Harness h = openGame(new File(tmp.getRoot(), "rewind"));
        h.game(10, 171);
        h.rewind.onGameLine(SessionChatLine.Kind.WIN);
        h.advance(RewindGameRules.RESULT_CLOSE_US);
        h.tick(false, false);
        h.step();
        h.advance(1_000_000L);
        h.boundary(s01(1701, 0), -1);                                           // another game, no location
        for (int i = 0; i < 11; i++) {
            h.advance(1_000_000L);
            h.tick(true, false);
        }
        h.advance(1_000_000L);
        h.tick(false, true);
        assertTrue(h.rewind.rules().gameOpen());
        h.game(10, 172);
        h.ch.close();
        h.tick(false, false);
        h.step();
        RecordingMeta second = metaOfSegments(h.dir, Collections.singleton(2));
        assertNull(second.mode);
        assertNull(second.map);
        assertEquals(0, count(RewindWriterTest.read(new File(h.dir, second.id + ".cbr")), RewindFormat.TAG_INFO));
    }

    // ------------------------------------------------------------------ code review 3

    /**
     * I1, the reviewer's sequence: WIN at 100 s; Netty reads a same-server S07 (segment 2) at 114 s; one tick at
     * 115 s sees the capture progress; the client stalls until the WORLD_LOAD at 130 s; then the same-server location.
     * Segment 2 is the game's and its records are in the result-closed file.
     */
    @Test
    public void aStalledClientKeepsTheRespawnInTheResultClosedFile() throws Exception {
        Harness h = openGame(new File(tmp.getRoot(), "rewind"));
        h.game(20, 83);
        h.rewind.onGameLine(SessionChatLine.Kind.WIN);
        h.advance(RewindGameRules.RESULT_CLOSE_US - 1_000_000L);
        h.net(s07(-1));                                                         // Netty: segment 2 starts
        h.net(other(0x30, 70), other(0x31, 80));
        h.advance(1_000_000L);
        h.tick(false, true);                                                    // due; sees the capture progress
        h.step();
        h.advance(15_000_000L);                                                 // the client stalls
        h.net(other(0x32, 90));
        worldLoad(h, 0);
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.tick(false, false);
        h.step();
        assertFalse(h.rewind.rules().gameOpen());

        RewindWriterTest.FileContents fc = RewindWriterTest.read(RewindWriterTest.onlyCbr(h.dir));
        assertTrue(fc.complete);
        assertEquals(new TreeSet<Integer>(Arrays.asList(1, 2)), segments(fc));
        assertSameRecords(h.netSent.get(2), netOf(fc, 2));
        assertEquals("result", fc.endMeta.endedBy);
        assertEquals(RecordingMeta.RESULT_WIN, fc.endMeta.result);
    }

    /** I1 wiring: a game line (no tick first) is settled against the capture handler before the rules see it. */
    @Test
    public void aGameLineBeforeAnyTickStillWaitsForTheCapturedRespawn() throws Exception {
        Harness h = openGame(new File(tmp.getRoot(), "rewind"));
        h.game(20, 84);
        h.rewind.onGameLine(SessionChatLine.Kind.WIN);
        h.advance(RewindGameRules.RESULT_CLOSE_US - 1_000_000L);
        h.net(s07(-1), other(0x30, 70));                                        // Netty: segment 2 starts
        h.advance(2_000_000L);                                                  // the close is due
        h.rewind.onGameLine(SessionChatLine.Kind.FINAL_KILL);                   // the first client event since
        h.step();
        worldLoad(h, 0);
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.tick(false, false);
        h.step();
        RewindWriterTest.FileContents fc = RewindWriterTest.read(RewindWriterTest.onlyCbr(h.dir));
        assertEquals(new TreeSet<Integer>(Arrays.asList(1, 2)), segments(fc));
        assertSameRecords(h.netSent.get(2), netOf(fc, 2));
        assertEquals("result", fc.endMeta.endedBy);
    }

    /**
     * I2, the reviewer's sequence through the wiring: forty 1 MiB incompressible frames in segment 1 while the client
     * thread runs nothing, the writer stepping after each. Pending memory never passes the cap and nothing reaches the
     * disk; when the client resumes, that run is dropped and the next game on the connection is recorded.
     */
    @Test
    public void aStalledClientCannotGrowPendingPastTheCapAndTheNextGameIsRecorded() throws Exception {
        Harness h = new Harness(new File(tmp.getRoot(), "rewind"));
        assertNotNull(h.connect(true, true));
        h.advance(1_000);
        h.net(s01(1401, 0));
        Random r = new Random(1);
        byte[] big = new byte[1 << 20];
        for (int i = 0; i < 40; i++) {
            r.nextBytes(big);
            big[0] = 0x21;
            h.advance(10_000);
            RewindCaptureTest.fire(h.ch, big);
            h.fired++;
            h.step();
            assertTrue("pending " + h.writer.pendingBytes() + " after frame " + i,
                    h.writer.pendingBytes() <= RewindGameRules.PENDING_MAX_BYTES);
        }
        assertTrue("nothing on disk", h.ids().isEmpty());
        assertFalse("capture never failed", RewindCapture.get(h.ch).session().failed());

        worldLoad(h, 0);                                                        // the client resumes
        h.location(GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.tick(true, false);
        h.advance(300_000);
        h.tick(false, true);
        assertFalse("the run that passed the cap never opens", h.rewind.rules().gameOpen());
        h.step();
        assertTrue(h.ids().isEmpty());

        h.advance(1_000_000L);
        h.boundary(s01(1402, 0), 0);
        h.location("fakeGameServer14B", "BEDWARS", null, "BEDWARS_EIGHT_TWO", "FakeOther");
        h.game(10, 141);
        h.tick(true, false);
        h.advance(300_000);
        h.tick(false, true);
        assertTrue(h.rewind.rules().gameOpen());
        h.game(10, 142);
        h.ch.close();
        h.tick(false, false);
        h.step();
        RewindWriterTest.FileContents fc = RewindWriterTest.read(RewindWriterTest.onlyCbr(h.dir));
        assertTrue(fc.complete);
        assertEquals(Collections.singleton(2), segments(fc));
        assertSameRecords(h.netSent.get(2), netOf(fc, 2));
        assertEquals(0, h.writer.pendingBytes());
        h.ch.checkException();
        assertEquals(h.fired, h.rx.messages.size());
    }

    static int keyRecords(RewindWriterTest.FileContents fc) {
        int n = 0;
        for (RewindWriterTest.TrackRec t : fc.track) {
            if (t.tag == RewindFormat.TAG_MARKER && (t.body[0] & 0xFF) == RewindFormat.MARKER_KEY) {
                n++;
            }
        }
        return n;
    }

    /**
     * I3, the reviewer's sequence: game A open; WORLD_LOAD of segment 2 on game server B; Marker Key before B's
     * location. The KEY record is in segment 2, so A is closed unmarked and B is key-marked.
     */
    @Test
    public void aMarkerKeyDuringAnUndecidedSwitchMarksTheGameThatOwnsItsSegment() throws Exception {
        Harness h = openGame(new File(tmp.getRoot(), "rewind"));
        h.game(10, 95);
        h.step();
        h.advance(1_000_000L);
        h.boundary(s01(1501, 0), 0);                                            // segment 2, location not yet
        h.rewind.onMarkerKey();
        assertNotEquals("accepted: it flashes", 0, h.rewind.flashColor());
        h.step();
        h.location("fakeGameServer15B", "BEDWARS", null, "BEDWARS_EIGHT_TWO", "FakeOther");
        h.game(10, 96);
        h.tick(true, false);
        h.advance(300_000);
        h.tick(false, true);
        assertTrue(h.rewind.rules().gameOpen());
        h.game(10, 97);
        h.ch.close();
        h.tick(false, false);
        h.step();

        RecordingMeta a = metaOfSegments(h.dir, Collections.singleton(1));
        RecordingMeta b = metaOfSegments(h.dir, Collections.singleton(2));
        RewindWriterTest.FileContents fa = RewindWriterTest.read(new File(h.dir, a.id + ".cbr"));
        RewindWriterTest.FileContents fb = RewindWriterTest.read(new File(h.dir, b.id + ".cbr"));
        assertEquals("A holds no KEY record", 0, keyRecords(fa));
        assertFalse("A's .json", a.keyMarked);
        assertFalse("A's END", fa.endMeta.keyMarked);
        assertEquals("B holds the KEY record", 1, keyRecords(fb));
        assertTrue(b.keyMarked);
        assertTrue(fb.endMeta.keyMarked);
    }

    /** O1, the reviewer's sequence: a Replay|Restrict-style message in game A and again in game B, one connection. */
    @Test
    public void aRestrictChannelSeenInEachGameIsInEachGamesStats() throws Exception {
        Harness h = openGame(new File(tmp.getRoot(), "rewind"));
        h.net(s3f("Replay|Restrict", new byte[]{1}));
        h.game(10, 98);
        h.rewind.onGameLine(SessionChatLine.Kind.WIN);
        h.advance(RewindGameRules.RESULT_CLOSE_US);
        h.tick(false, false);
        h.step();
        assertEquals(1, h.ids().size());

        h.advance(1_000_000L);
        h.boundary(s01(1601, 0), 0);
        h.location("fakeGameServer16B", "BEDWARS", null, "BEDWARS_EIGHT_TWO", "FakeOther");
        h.tick(true, false);
        h.advance(300_000);
        h.tick(false, true);
        assertTrue(h.rewind.rules().gameOpen());
        h.net(s3f("Replay|Restrict", new byte[]{2}));
        h.game(10, 99);
        h.ch.close();
        h.tick(false, false);
        h.step();

        List<String> ids = h.ids();
        assertEquals(2, ids.size());
        for (String id : ids) {
            String stats = new String(Files.readAllBytes(new File(h.dir, id + ".stats.json").toPath()),
                    StandardCharsets.UTF_8);
            assertTrue(id + ": " + stats, stats.contains("\"restrictChannelSeen\":true"));
        }
    }

    // ------------------------------------------------------------------ code review 2, I4: Record Games off

    /** A {@code hyevent:location} as vanilla's decoder hands it to {@code packet_handler}. */
    private static void decodedLocation(Harness h, String server, String type, String lobby, String mode) {
        h.ch.pipeline().fireChannelRead(new net.minecraft.network.play.server.S3FPacketCustomPayload(
                "hyevent:location", new net.minecraft.network.PacketBuffer(io.netty.buffer.Unpooled.wrappedBuffer(
                        locationPayload(server, type, lobby, mode, null)))));
        h.fired++;
        h.runClient();
    }

    /**
     * Rewind on, Record Games off: nothing is recorded (no capture handler, no writer, no file, every packet passes),
     * but the rules still learn whether the run is a lobby, so Watch Last Game can show there (A4).
     */
    @Test
    public void recordGamesOffStillTellsTheRulesWhenTheRunIsALobby() {
        File dir = new File(tmp.getRoot(), "record-off");
        Harness h = new Harness(dir);
        assertNull(h.connect(true, false));
        assertNull(RewindCapture.get(h.ch));
        h.boundary(s01(601, 0), 0);
        assertFalse("a new world", h.rewind.rules().entryState().lobby);
        decodedLocation(h, "fakeLobby2", "MAIN", null, null);
        h.tick(false, false);
        assertTrue("the lobby is known", h.rewind.rules().entryState().lobby);
        assertFalse(h.rewind.rules().active());

        h.boundary(s07(1), 1);
        decodedLocation(h, GAME, "BEDWARS", null, "BEDWARS_EIGHT_ONE");
        h.game(10, 61);
        h.tick(false, true);
        assertFalse("a game run is not a lobby", h.rewind.rules().entryState().lobby);
        assertFalse("and nothing records it", h.rewind.rules().gameOpen());

        h.boundary(s07(0), 0);
        decodedLocation(h, "fakeLobby4", null, "fakeLobby4", null);
        assertTrue(h.rewind.rules().entryState().lobby);
        assertEquals("no writer is ever made", 0, h.writersMade);
        assertFalse(dir.exists());
        assertEquals("every packet passes", h.fired, h.rx.messages.size());
        h.ch.close();
        h.tick(false, false);
        assertFalse("disconnected", h.rewind.rules().entryState().lobby);
    }

    /** Record Games turned off mid-connection: the capture handler goes, the next lobby is still known. */
    @Test
    public void recordGamesTurnedOffMidConnectionStillFollowsTheNextLobby() throws Exception {
        Harness h = openGame(tmp.newFolder("off-mid"));
        h.rewind.tick(false, false, false);               // Record Games switched off
        h.step();
        h.ch.runPendingTasks();
        assertNull("the capture handler is removed", RewindCapture.get(h.ch));
        assertFalse(h.rewind.rules().active());
        h.boundary(s07(1), 1);
        decodedLocation(h, "fakeLobby2", "MAIN", null, null);
        assertTrue(h.rewind.rules().entryState().lobby);
    }

    /** Rewind off: nothing at all, not even the lobby. */
    @Test
    public void rewindOffFollowsNothing() {
        Harness h = new Harness(new File(tmp.getRoot(), "rewind-off"));
        assertNull(h.connect(false, false));
        h.boundary(s01(602, 0), 0);
        decodedLocation(h, "fakeLobby2", "MAIN", null, null);
        assertFalse(h.rewind.rules().entryState().lobby);
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

    // ================================================================== Phase 6: format v2 capture, wired

    @BeforeClass
    public static void bootstrapMinecraft() {
        InventoryCaptureTest.bootstrap();
    }

    @After
    public void releaseCapture() {
        V2.reset();
    }

    /**
     * One client tick of every format v2 capture hook, driven through the production wiring (the sink {@link Rewind}
     * installs at connect) with real vanilla objects and fake values only. Each {@link #round} touches all 17
     * extension tags: TICK_HEAD (runTick head), OUT, SOUND and BLOCK (own actions), HUD and TAP (the painters'
     * hand-offs), VIEW, FOV, INPUT, MOUSE and CHAT_INPUT (frame), DIG, INV, USE, CONT, SETTINGS and IDENTITY (end of
     * tick).
     */
    static final class V2 {
        static final int DRAINED_ID = 0x15;
        final InventoryPlayer inv = InventoryCaptureTest.fullInventory();
        final InventoryCaptureTest.Own own = new InventoryCaptureTest.Own();
        final InventoryBasic box = new InventoryBasic("Fake Chest", false, 27);
        final ContainerChest chest = new ContainerChest(inv, box, null);
        final GameSettings gs = SnapshotCaptureTest.gameSettings();
        final ClientSettings cfg = SnapshotCaptureTest.clientSettings();
        final SettingsSnapshot snap = new SettingsSnapshot();
        final GameProfile profile = SnapshotCaptureTest.profile();
        final DigCaptureTest.MapWorld world = new DigCaptureTest.MapWorld(true);
        final List<Packet<?>> nine;
        final ISound step = LocalSoundCaptureTest.sound(new ResourceLocation("dig.stone"), false,
                ISound.AttenuationType.LINEAR, 1.5f, 64f, -2.5f);
        final ViewCapture.Frame frame = new ViewCapture.Frame();
        long frameNs = 1_000_000_000L;

        V2() {
            try {
                nine = OutboundCaptureTest.nineStored();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            chest.windowId = 3;
        }

        /** Everything capture-related back to unbound (static state shared by every test in this JVM). */
        static void reset() {
            Rewind.unbindCapture();
            ActionCapture.unbind();
            StateCapture.install(null);
            ViewCapture.install(null);
            HudCapture.shared().setSink(null);
            HudCapture.shared().setTapReceiver(null);
        }

        /** {@code n} rounds, 50 ms apart. */
        void rounds(Harness h, int from, int n) {
            for (int i = from; i < from + n; i++) {
                round(h, i);
            }
        }

        void round(final Harness h, final int i) {
            h.advance(50_000);
            h.rewind.captureTickHead();
            // a server packet captured, then handled by the client before the next tick head
            h.net(other(DRAINED_ID, 12));
            ActionCapture.onPacketRun(DRAINED_ID);
            h.advance(1_000);
            ActionCapture.onSend(nine.get(i % nine.size()));
            ActionCapture.enterLocal();
            ActionCapture.onSound(step);
            world.change(new BlockPos(2, 64 + i, 0), Blocks.stone.getDefaultState());
            ActionCapture.exitLocal();
            HudCapture.shared().frame();
            HudCapture.shared().offerTimer(RewindFormat.HUD_DIAMOND_TIMER, 30 - i);
            HudCapture.shared().mousePress(i & 1);
            h.advance(16_000);
            frameNs += 20_000_000L;
            h.rewind.captureFrame(new Rewind.FrameCapture() {
                @Override
                public void frame(float partialTicks, long nanoTime) {
                    ViewCapture.Frame f = frame;
                    f.nanoTime = nanoTime;
                    f.perspective = i % 3;
                    f.flags = (i & 1) == 0 ? 0 : RewindFormat.VIEW_F3;
                    f.screenKind = RewindFormat.SCREEN_CHAT;
                    f.keys = i & 0x7F;
                    f.fovRead = true;
                    f.fov = 70f + i;
                    f.screenOpen = true;
                    f.mouseDx = 4 * i;
                    f.mouseDy = -2 * i;
                    f.chatOpen = true;
                    f.chatText = "fake typing " + i;
                    f.chatCursor = i;
                    ViewCapture.frame(f);
                }
            }, 0.5f, frameNs);
            h.advance(16_000);
            h.rewind.captureTick(new Rewind.TickCapture() {
                @Override
                public void endOfTick() {
                    ActionCapture.dig(true, new BlockPos(5, 63, 5), 1, 0.1f * (i % 10) + 0.01f);
                    inv.mainInventory[2].stackSize = 1 + i % 7;
                    box.setInventorySlotContents(i % 27, new ItemStack(Items.iron_ingot, 1 + i % 5));
                    snap.read(gs, 480, 270, 1920, 1080, cfg);
                    StateCapture.tick(i == 1 ? 72000 : 0, inv, chest, own, snap, profile);
                }
            });
        }
    }

    /** The format v2 decoder of each extension tag; throws on a malformed body. */
    static void decodeExt(RewindWriterTest.TrackRec t) {
        byte[] b = t.body;
        switch (t.tag) {
            case RewindFormat.TAG_TICK_HEAD: new ExtCodec.TickHead().read(b, 0, b.length); break;
            case RewindFormat.TAG_OUT: new ExtCodec.Out().read(b, 0, b.length); break;
            case RewindFormat.TAG_DIG: new ExtCodec.Dig().read(b, 0, b.length); break;
            case RewindFormat.TAG_BLOCK: new ExtCodec.Block().read(b, 0, b.length); break;
            case RewindFormat.TAG_SOUND: new ExtCodec.Sound().read(b, 0, b.length); break;
            case RewindFormat.TAG_VIEW: new ExtCodec.View().read(b, 0, b.length); break;
            case RewindFormat.TAG_MOUSE: new ExtCodec.Mouse().read(b, 0, b.length); break;
            case RewindFormat.TAG_CHAT_INPUT: new ExtCodec.ChatInput().read(b, 0, b.length); break;
            case RewindFormat.TAG_FOV: new ExtCodec.Fov().read(b, 0, b.length); break;
            case RewindFormat.TAG_USE: new ExtCodec.Use().read(b, 0, b.length); break;
            case RewindFormat.TAG_INV: new ExtCodec.Inv().read(b, 0, b.length); break;
            case RewindFormat.TAG_SETTINGS: new ExtCodec.Settings().read(b, 0, b.length); break;
            case RewindFormat.TAG_IDENTITY: new ExtCodec.Identity().read(b, 0, b.length); break;
            case RewindFormat.TAG_INPUT: new ExtCodec.Input().read(b, 0, b.length); break;
            case RewindFormat.TAG_HUD: new ExtCodec.Hud().read(b, 0, b.length); break;
            case RewindFormat.TAG_CONT: new ExtCodec.Cont().read(b, 0, b.length); break;
            case RewindFormat.TAG_TAP: new ExtCodec.Tap().read(b, 0, b.length); break;
            default: throw new AssertionError("not an extension tag " + t.tag);
        }
    }

    static List<RewindWriterTest.TrackRec> ext(RewindWriterTest.FileContents fc, int tag) {
        List<RewindWriterTest.TrackRec> out = new ArrayList<RewindWriterTest.TrackRec>();
        for (RewindWriterTest.TrackRec t : fc.track) {
            if (t.tag == tag) {
                out.add(t);
            }
        }
        return out;
    }

    static int extTotal(RewindWriterTest.FileContents fc) {
        int n = 0;
        for (RewindWriterTest.TrackRec t : fc.track) {
            if (RewindFormat.isExtensionTag(t.tag)) {
                n++;
            }
        }
        return n;
    }

    /** The connection ends; the writer closes the file. */
    static File finish(Harness h) {
        h.ch.close();
        h.advance(1_000);
        h.tick(false, false);
        h.step();
        h.advance(RewindWriter.SYNC_INTERVAL_US);
        h.step();
        return RewindWriterTest.onlyCbr(h.dir);
    }

    @Test
    public void aV2GameThroughTheRealWriterHoldsEveryExtensionTagAndPassesRewindCheck() throws Exception {
        Harness h = openGame(new File(tmp.getRoot(), "rewind"));
        assertTrue("capture is on for the recorded segment", h.rewind.capturing());
        V2 v = new V2();
        v.rounds(h, 0, 6);
        h.rewind.onMarkerKey(); // RewindCheck's pass needs a KEY marker
        v.rounds(h, 6, 6);
        File cbr = finish(h);

        h.ch.checkException();
        assertEquals("every message reached packet_handler", h.fired, h.rx.messages.size());
        RewindWriterTest.FileContents fc = RewindWriterTest.read(cbr);
        assertTrue(fc.complete);
        assertFalse(fc.tornTail);
        RewindCheck.Summary s = RewindCheck.summarize(cbr);
        assertEquals(s.line(), 0, s.exitCode());
        assertEquals(RewindFormat.FORMAT_VERSION, s.formatVersion);
        assertEquals(0, s.extUnknown);
        for (int tag : RewindFormat.EXT_TAGS) {
            List<RewindWriterTest.TrackRec> recs = ext(fc, tag);
            assertTrue(RewindFormat.extName(tag) + " records in the file", recs.size() > 0);
            assertEquals(RewindFormat.extName(tag) + " counted by RewindCheck", recs.size(), s.extCount(tag));
            for (RewindWriterTest.TrackRec t : recs) {
                assertEquals("in the game's segment", 1, t.segment);
                decodeExt(t);
            }
        }

        // values round-trip as captured
        assertEquals("one TICK_HEAD per runTick head", 12, ext(fc, RewindFormat.TAG_TICK_HEAD).size());
        ExtCodec.TickHead head = new ExtCodec.TickHead();
        byte[] last = ext(fc, RewindFormat.TAG_TICK_HEAD).get(11).body;
        head.read(last, 0, last.length);
        assertEquals("the previous round's drained packet", 1, head.handled);
        assertEquals("a clean count", 0, head.flags);
        List<RewindWriterTest.TrackRec> outs = ext(fc, RewindFormat.TAG_OUT);
        assertEquals(12, outs.size());
        ExtCodec.Out out = new ExtCodec.Out();
        for (int i = 0; i < outs.size(); i++) {
            Packet<?> p = v.nine.get(i % v.nine.size());
            out.read(outs.get(i).body, 0, outs.get(i).body.length);
            assertEquals(OutboundCaptureTest.serverboundId(p), out.packetId);
            assertArrayEquals("OUT " + i + " holds vanilla's bytes", OutboundCaptureTest.data(p),
                    Arrays.copyOfRange(out.buf, out.dataOff, out.dataOff + out.dataLen));
        }
        assertEquals("IDENTITY once per segment", 1, ext(fc, RewindFormat.TAG_IDENTITY).size());
        assertTrue("INV: all 41 slots on the first tick", ext(fc, RewindFormat.TAG_INV).size() >= 41);
        ExtCodec.Sound snd = new ExtCodec.Sound();
        byte[] sb = ext(fc, RewindFormat.TAG_SOUND).get(0).body;
        snd.read(sb, 0, sb.length);
        assertEquals("minecraft:dig.stone", snd.name);
        ExtCodec.Tap tap = new ExtCodec.Tap();
        byte[] tb = ext(fc, RewindFormat.TAG_TAP).get(0).body;
        tap.read(tb, 0, tb.length);
        assertEquals(RewindFormat.TAP_LMB, tap.button);
        ExtCodec.Use use = new ExtCodec.Use();
        byte[] ub = ext(fc, RewindFormat.TAG_USE).get(0).body;
        use.read(ub, 0, ub.length);
        assertEquals("a bow draw is stored in full", 72000, use.count);

        // stats: numbers only, every new record type counted, the timings filled
        File stats = new File(h.dir, cbr.getName().replace(".cbr", ".stats.json"));
        String json = new String(Files.readAllBytes(stats.toPath()), StandardCharsets.UTF_8);
        com.google.gson.JsonObject o = new com.google.gson.JsonParser().parse(json).getAsJsonObject();
        assertEquals(RewindFormat.FORMAT_VERSION, o.get("format").getAsInt());
        Set<Integer> counted = new TreeSet<Integer>();
        for (com.google.gson.JsonElement e : o.getAsJsonArray("extRecords")) {
            com.google.gson.JsonObject r = e.getAsJsonObject();
            assertTrue(r.get("count").getAsLong() > 0);
            assertTrue(r.get("bytes").getAsLong() > 0);
            counted.add(r.get("tag").getAsInt());
        }
        for (int tag : RewindFormat.EXT_TAGS) {
            assertTrue(RewindFormat.extName(tag) + " in the stats", counted.contains(tag));
        }
        for (String k : new String[]{"clientTickNsMax", "clientFrameNsMax", "outNsMax"}) {
            assertTrue(k + " measured: " + o.get(k), o.get(k).getAsLong() > 0);
        }
        for (String secret : new String[]{"Steve_Test", "fake typing", "dig.stone", "Fake Chest"}) {
            assertFalse("no player text in the stats: " + secret, json.contains(secret));
        }
    }

    /** A sound whose name lookup throws: a capture hook failure inside {@code SoundHandler.playSound}. */
    private static ISound throwingSound() {
        final ISound base = LocalSoundCaptureTest.sound(new ResourceLocation("dig.stone"), false,
                ISound.AttenuationType.LINEAR, 0, 64, 0);
        return (ISound) java.lang.reflect.Proxy.newProxyInstance(ISound.class.getClassLoader(),
                new Class<?>[]{ISound.class}, new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) throws Throwable {
                        if (m.getName().equals("getSoundLocation")) {
                            throw new IllegalStateException("fake capture bug");
                        }
                        return m.invoke(base, args);
                    }
                });
    }

    /**
     * A14: {@code hook} throws inside capture. It returns normally, capture stops at once, the next settle fails the
     * game (the prefix stays readable), nothing more is written for it, and the connection is untouched.
     */
    private void assertHookFailureFailsOnlyRecording(String name, Runnable hook) throws Exception {
        assertHookFailureFailsOnlyRecording(name, name, hook);
    }

    /** As above; {@code where} is the place the diagnostic line names (code review 1, O6). */
    private void assertHookFailureFailsOnlyRecording(String name, String where, Runnable hook) throws Exception {
        V2.reset();
        Harness h = openGame(new File(tmp.getRoot(), "fail-" + name));
        current = h.rewind;
        V2 v = new V2();
        v.rounds(h, 0, 3);
        assertTrue(h.rewind.capturing());
        h.advance(1_000);
        long failUs = h.clock.nowUs;
        hook.run(); // must not throw
        assertFalse(name + ": capture stops at once", h.rewind.capturing());
        assertEquals(name + ": the failure is reported where it happened", where, h.rewind.captureWhere());
        v.rounds(h, 3, 2); // written nowhere
        h.tick(false, true);
        assertFalse(name + ": the failure closed the game", h.rewind.rules().gameOpen());
        assertTrue(name + ": the connection is untouched", h.ch.isOpen());
        File cbr = finish(h);
        h.ch.checkException();
        assertEquals(name + ": every message passed", h.fired, h.rx.messages.size());
        RewindWriterTest.FileContents fc = RewindWriterTest.read(cbr);
        assertTrue(name + ": the prefix is a complete file", fc.complete);
        assertTrue(name + ": failed", RewindWriterTest.jsonOf(cbr).failed);
        assertTrue(name + ": records before the failure", extTotal(fc) > 0);
        for (RewindWriterTest.TrackRec t : fc.track) {
            if (RewindFormat.isExtensionTag(t.tag)) {
                assertTrue(name + ": " + RewindFormat.extName(t.tag) + " written after the failure",
                        t.timeUs <= failUs);
            }
        }
        V2.reset();
    }

    @Test
    public void aThrowInsideTheSoundHookFailsTheGameAndThePlayCallGoesOn() throws Exception {
        final DigCaptureTest.MapWorld world = new DigCaptureTest.MapWorld(true);
        assertHookFailureFailsOnlyRecording("sound", "action", new Runnable() {
            @Override
            public void run() {
                ActionCapture.enterLocal();
                ActionCapture.onSound(throwingSound());
                assertTrue("the block change inside the same click still happens",
                        world.change(new BlockPos(9, 70, 9), Blocks.dirt.getDefaultState()));
                ActionCapture.exitLocal();
                assertSame(Blocks.dirt.getDefaultState(), world.getBlockState(new BlockPos(9, 70, 9)));
            }
        });
    }

    @Test
    public void aThrowInsideTheEndOfTickCaptureFailsTheGame() throws Exception {
        assertHookFailureFailsOnlyRecording("tick", new Runnable() {
            @Override
            public void run() {
                instanceUnderTest().captureTick(new Rewind.TickCapture() {
                    @Override
                    public void endOfTick() {
                        throw new IllegalStateException("fake tick capture bug");
                    }
                });
            }
        });
    }

    @Test
    public void aThrowInsideTheInventoryCaptureFailsTheGame() throws Exception {
        assertHookFailureFailsOnlyRecording("state", new Runnable() {
            @Override
            public void run() {
                final InventoryPlayer broken = new InventoryPlayer(null);
                broken.mainInventory = null;
                instanceUnderTest().captureTick(new Rewind.TickCapture() {
                    @Override
                    public void endOfTick() {
                        StateCapture.tick(0, broken, null, null, null, null);
                    }
                });
            }
        });
    }

    @Test
    public void aThrowInsideTheFrameCaptureFailsTheGame() throws Exception {
        assertHookFailureFailsOnlyRecording("frame", new Runnable() {
            @Override
            public void run() {
                instanceUnderTest().captureFrame(new Rewind.FrameCapture() {
                    @Override
                    public void frame(float partialTicks, long nanoTime) {
                        throw new IllegalStateException("fake frame capture bug");
                    }
                }, 0.5f, System.nanoTime());
            }
        });
    }

    @Test
    public void aThrowInsideAHudHandOffFailsTheGameAndThePainterGoesOn() throws Exception {
        assertHookFailureFailsOnlyRecording("hud", new Runnable() {
            @Override
            public void run() {
                HudCapture.shared().tickHead();
                HudCapture.shared().frame();
                HudCapture.shared().offerLines(RewindFormat.HUD_SESSION_STATS, Arrays.asList("x"),
                        new com.bedwarsqol.hud.HudModel.LineView<String>() {
                            @Override
                            public String text(String line) {
                                throw new IllegalStateException("fake painter value bug");
                            }

                            @Override
                            public int kind(String line) {
                                return com.bedwarsqol.hud.HudModel.LINE_ROW;
                            }
                        });
            }
        });
    }

    /**
     * Code review 1, O2: another mod's cancellable HEAD injector on {@code clickMouse} (or a throw) skips Cobblify's
     * RETURN hook, leaving a local scope open. The end-of-tick capture closes it, so the next frame's packet drain
     * (server block changes and positioned sounds) is not stored as the player's own.
     */
    @Test
    public void aLocalScopeLeftOpenBySkippedReturnClosesAtTheEndOfTheTick() throws Exception {
        V2.reset();
        Harness h = openGame(new File(tmp.getRoot(), "scope"));
        V2 v = new V2();
        v.rounds(h, 0, 3);
        h.advance(1_000);
        ActionCapture.enterLocal(); // clickMouse HEAD ran; its RETURN never does
        assertTrue(ActionCapture.scope().local());
        h.rewind.captureTick(new Rewind.TickCapture() {
            @Override
            public void endOfTick() {
            }
        });
        assertFalse("the end of the tick closes every local scope", ActionCapture.scope().local());
        h.advance(5_000);
        long drainUs = h.clock.nowUs;
        // the next frame's scheduled-task drain: a server sound and a server block change
        ActionCapture.onSound(v.step);
        v.world.change(new BlockPos(40, 70, 40), Blocks.gold_block.getDefaultState());
        assertSame(Blocks.gold_block.getDefaultState(), v.world.getBlockState(new BlockPos(40, 70, 40)));
        File cbr = finish(h);
        RewindWriterTest.FileContents fc = RewindWriterTest.read(cbr);
        assertTrue(fc.complete);
        assertTrue("own records before", ext(fc, RewindFormat.TAG_SOUND).size() > 0);
        for (RewindWriterTest.TrackRec t : fc.track) {
            if (t.tag == RewindFormat.TAG_SOUND || t.tag == RewindFormat.TAG_BLOCK) {
                assertTrue(RewindFormat.extName(t.tag) + " from the drain stored as own", t.timeUs < drainUs);
            }
        }
    }

    /**
     * Code review 1, O3: releasing capture (a disconnect, Record Games off) drops what the state and view capture
     * kept: the last open window (and through it the player and the world), the per-slot stacks and typed text.
     */
    @Test
    public void releasingCaptureDropsTheWindowStacksAndTypedText() throws Exception {
        V2.reset();
        Harness h = openGame(new File(tmp.getRoot(), "release"));
        V2 v = new V2();
        v.rounds(h, 0, 3); // a chest open, chat typed
        assertTrue("state capture holds the open window and stacks", StateCapture.holdsReferences());
        assertTrue("view capture holds the typed text", ViewCapture.holdsReferences());
        h.rewind.tick(false, false, false); // Record Games off: capture released
        assertFalse(StateCapture.installed());
        assertFalse("no window, stack or tag kept after release", StateCapture.holdsReferences());
        assertFalse("no renderer or typed text kept after release", ViewCapture.holdsReferences());
    }

    /** The harness of the running failure test (the hook runs inside it). */
    private Rewind instanceUnderTest() {
        return current;
    }

    private Rewind current;

    @Test
    public void recordGamesOffCapturesNothingAndTurningItOffUnbindsCapture() throws Exception {
        // off from the start: nothing is bound, nothing written
        V2.reset();
        Harness off = new Harness(new File(tmp.getRoot(), "off"));
        assertNull(off.connect(true, false));
        off.boundary(s01(701, 0), 0);
        assertFalse(off.rewind.capturing());
        assertFalse("no action capture", ActionCapture.bound());
        assertFalse("no state capture", StateCapture.installed());
        assertFalse("no view capture", ViewCapture.installed());
        assertFalse("no HUD capture", HudCapture.shared().active());
        new V2().rounds(off, 0, 3);
        off.step();
        assertEquals(0, off.writersMade);
        assertTrue(off.ids().isEmpty());

        // turned off mid-game: capture is unbound with the CLOSE, nothing follows
        Harness h = openGame(new File(tmp.getRoot(), "mid"));
        V2 v = new V2();
        v.rounds(h, 0, 3);
        assertTrue(h.rewind.capturing());
        assertTrue(ActionCapture.bound());
        h.advance(1_000);
        long offUs = h.clock.nowUs;
        h.rewind.tick(false, false, false);
        assertFalse(h.rewind.capturing());
        assertFalse(ActionCapture.bound());
        assertFalse(StateCapture.installed());
        assertFalse(ViewCapture.installed());
        assertFalse(HudCapture.shared().active());
        v.rounds(h, 3, 3);
        h.step();
        h.advance(RewindWriter.SYNC_INTERVAL_US);
        h.step();
        RewindWriterTest.FileContents fc = RewindWriterTest.read(RewindWriterTest.onlyCbr(h.dir));
        assertTrue(fc.complete);
        assertTrue(extTotal(fc) > 0);
        for (RewindWriterTest.TrackRec t : fc.track) {
            if (RewindFormat.isExtensionTag(t.tag)) {
                assertTrue("written after Record Games went off", t.timeUs <= offUs);
            }
        }
    }
}
