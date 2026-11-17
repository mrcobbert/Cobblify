package com.bedwarsqol.rewind;

import com.bedwarsqol.rewind.RewindWriterTest.FileContents;
import com.bedwarsqol.rewind.RewindWriterTest.Fixture;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.CRC32;

import static com.bedwarsqol.rewind.RewindWriterTest.ids;
import static com.bedwarsqol.rewind.RewindWriterTest.packet;
import static com.bedwarsqol.rewind.RewindWriterTest.read;
import static com.bedwarsqol.rewind.RewindWriterTest.s01;
import static com.bedwarsqol.rewind.RewindWriterTest.snapshot;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Recovery at start (PLAN "Recovery at start", I3) and pruning over recovered files (A5): crashed
 * games are finalized with INDEX + END and a {@code complete:true, recovered:true} {@code .json}, then
 * pruned like any closed game; a locked file is neither recovered nor pruned, a prefix with no NET
 * record is deleted, and an unknown format version is left alone.
 */
public class RecordingRecoveryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long START_US = 1_000_000L;

    private enum Json { MISSING, INCOMPLETE }

    /** What a crashed game holds, to compare with the recovered file. */
    private static final class Crashed {
        final String id;
        final long startedAtMs;
        final List<byte[]> net = new ArrayList<byte[]>();
        final byte[] bytes;

        Crashed(String id, long startedAtMs, byte[] bytes) {
            this.id = id;
            this.startedAtMs = startedAtMs;
            this.bytes = bytes;
        }
    }

    private static byte[] fileHeader(long baseEpochMs, int version) {
        byte[] h = BlockCodec.fileHeader(baseEpochMs);
        if (version != RewindFormat.FORMAT_VERSION) {
            RewindFormat.putShort(h, 8, version);
            CRC32 c = new CRC32();
            c.update(h, 0, 24);
            RewindFormat.putInt(h, 24, (int) c.getValue());
        }
        return h;
    }

    private static void block(ByteArrayOutputStream out, BlockCodec codec, int type, int segment, long t, byte[] raw) {
        int n = codec.encode(type, segment, t, raw, 0, raw.length, true);
        out.write(codec.out(), 0, n);
    }

    /**
     * A crashed game: valid blocks (HEADER, NET when {@code withNet}, TRACK with GAME_START and ticks,
     * an optional KEY marker), then a torn half block; {@code .json} missing or {@code complete:false}.
     */
    private static Crashed writeCrashed(File dir, String id, long baseEpochMs, boolean key, Json json,
                                        boolean withNet) throws IOException {
        dir.mkdirs();
        BlockCodec codec = new BlockCodec();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] h = fileHeader(baseEpochMs, RewindFormat.FORMAT_VERSION);
        out.write(h, 0, h.length);
        long startedAtMs = baseEpochMs + START_US / 1000;
        Crashed c;
        block(out, codec, RewindFormat.BLOCK_HEADER, 0, START_US,
                BlockCodec.headerPayload(RewindFormat.SOURCE_CLIENT_SNAPSHOT, snapshot(0)));
        List<byte[]> net = new ArrayList<byte[]>();
        if (withNet) {
            net.add(s01(5, 0));
            for (int i = 0; i < 8; i++) {
                net.add(packet(0x14 + i, 12 + i, i));
            }
            byte[] payload = new byte[4096];
            int p = 0;
            for (byte[] pk : net) {
                p = RewindFormat.putVarInt(payload, p, 1_000);
                p = RewindFormat.putVarInt(payload, p, pk.length);
                System.arraycopy(pk, 0, payload, p, pk.length);
                p += pk.length;
            }
            block(out, codec, RewindFormat.BLOCK_NET, 0, START_US - 1_000, Arrays.copyOf(payload, p));
        }
        SelfTrackEncoder enc = new SelfTrackEncoder();
        byte[] track = new byte[4096];
        int p = RewindFormat.putVarInt(track, 0, 0);
        track[p++] = RewindFormat.TAG_GAME_START;
        for (int i = 0; i < 3; i++) {
            int n = enc.tick(i, 70, 0, 1f, 2f, 3f, 0, 0, -1, 0);
            p = RewindFormat.putVarInt(track, p, 50_000);
            track[p++] = RewindFormat.TAG_TICK;
            System.arraycopy(enc.buffer(), 0, track, p, n);
            p += n;
        }
        if (key) {
            p = RewindFormat.putVarInt(track, p, 10);
            track[p++] = RewindFormat.TAG_MARKER;
            track[p++] = RewindFormat.MARKER_KEY;
        }
        block(out, codec, RewindFormat.BLOCK_TRACK, 0, START_US, Arrays.copyOf(track, p));
        // The torn tail: half of a further NET block.
        byte[] more = new byte[]{(byte) 0x90, 0x4E, 3, 0x30, 1, 2};
        int n = codec.encode(RewindFormat.BLOCK_NET, 0, START_US + 200_000, more, 0, more.length, false);
        out.write(codec.out(), 0, n / 2);
        codec.close();
        byte[] bytes = out.toByteArray();
        Files.write(new File(dir, id + ".cbr").toPath(), bytes);
        if (json == Json.INCOMPLETE) {
            RecordingMeta m = new RecordingMeta();
            m.id = id;
            m.complete = false;
            m.startedAtMs = startedAtMs;
            m.gameStartUs = START_US;
            m.mode = "BEDWARS_EIGHT_TWO";
            m.server = RecordingMeta.SERVER_HYPIXEL;
            Files.write(new File(dir, id + ".json").toPath(), m.toUtf8());
        }
        c = new Crashed(id, startedAtMs, bytes);
        c.net.addAll(net);
        return c;
    }

    private static String crashedId(int i) {
        return String.format("202009%02d-120000-%04x", i + 1, 0xc000 + i);
    }

    private static long crashedBase(int i) {
        return 1_600_000_000_000L + i * 86_400_000L;
    }

    /** FILE_LOCKS, except that {@code lockedId}'s file is held by "another instance". */
    private static RecordingStore.Locker lockedElsewhere(final String lockedId) {
        return new RecordingStore.Locker() {
            @Override
            public RecordingStore.Handle open(File cbr, boolean create) throws IOException {
                if (cbr.getName().equals(lockedId + ".cbr")) {
                    return null;
                }
                return RecordingStore.FILE_LOCKS.open(cbr, create);
            }
        };
    }

    private static void assertRecovered(File dir, Crashed c, boolean key) throws IOException {
        File cbr = new File(dir, c.id + ".cbr");
        FileContents fc = read(cbr);
        assertTrue(c.id + " complete", fc.complete);
        assertFalse(c.id + " torn tail removed", fc.tornTail);
        int b = fc.blocks.size();
        assertEquals(RewindFormat.BLOCK_INDEX, fc.blocks.get(b - 2).type);
        assertEquals(RewindFormat.BLOCK_END, fc.blocks.get(b - 1).type);
        assertEquals("index covers the data blocks", b - 2, fc.index.size());
        assertEquals(c.net.size(), fc.net.size());
        for (int i = 0; i < c.net.size(); i++) {
            assertArrayEquals(c.net.get(i), fc.net.get(i).bytes);
        }
        RecordingMeta json = RewindWriterTest.jsonOf(cbr);
        for (RecordingMeta m : Arrays.asList(json, fc.endMeta)) {
            assertTrue(m.complete);
            assertTrue(m.recovered);
            assertEquals("recovered", m.endedBy);
            assertEquals(c.id, m.id);
            assertEquals(c.startedAtMs, m.startedAtMs);
            assertEquals(START_US, m.gameStartUs);
            assertEquals(key, m.keyMarked);
            assertEquals(key ? 1 : 0, m.markers.size());
            assertEquals(START_US + 150_000L + (key ? 10 : 0), m.endUs);
            assertEquals(m.endUs - m.gameStartUs, m.lengthUs);
        }
        assertTrue(new File(dir, c.id + ".stats.json").isFile());
        RewindCheck.Summary s = RewindCheck.summarize(cbr);
        assertTrue(s.complete);
        assertFalse(s.tornTail);
    }

    @Test
    public void elevenCrashedGamesAreRecoveredThenTenCleanGamesPruneThemToTheKeyMarkedOne() throws IOException {
        File dir = new File(tmp.getRoot(), "rewind");
        List<Crashed> crashed = new ArrayList<Crashed>();
        int keyIndex = 3;
        for (int i = 0; i < 11; i++) {
            crashed.add(writeCrashed(dir, crashedId(i), crashedBase(i), i == keyIndex,
                    i % 2 == 0 ? Json.MISSING : Json.INCOMPLETE, true));
        }
        // Held by another running instance: the oldest, so pruning would otherwise take it first.
        String lockedId = "20200101-120000-1111";
        Crashed locked = writeCrashed(dir, lockedId, 1_577_000_000_000L, false, Json.INCOMPLETE, true);
        byte[] lockedJson = Files.readAllBytes(new File(dir, lockedId + ".json").toPath());
        // No NET record: nothing to replay, deleted.
        String noNetId = "20200102-120000-2222";
        writeCrashed(dir, noNetId, 1_577_100_000_000L, false, Json.INCOMPLETE, false);
        // A future format version: left alone.
        String futureId = "20200103-120000-3333";
        byte[] future = new byte[64];
        System.arraycopy(fileHeader(1_577_200_000_000L, 2), 0, future, 0, RewindFormat.FILE_HEADER_LENGTH);
        Files.write(new File(dir, futureId + ".cbr").toPath(), future);

        Fixture f = new Fixture(dir, lockedElsewhere(lockedId), 1_000_000L);
        f.writer.recoverAndPrune();

        assertEquals(11, f.writer.recovered());
        assertEquals(1, f.writer.recoveryDeleted());
        for (int i = 0; i < 11; i++) {
            assertRecovered(dir, crashed.get(i), i == keyIndex);
        }
        assertEquals("10 unmarked recovered games are exactly the keep count", 0, f.writer.pruned());
        assertArrayEquals("locked file untouched", locked.bytes,
                Files.readAllBytes(new File(dir, lockedId + ".cbr").toPath()));
        assertArrayEquals(lockedJson, Files.readAllBytes(new File(dir, lockedId + ".json").toPath()));
        assertFalse(new File(dir, lockedId + ".stats.json").exists());
        for (String name : PruneRules.fileNames(noNetId)) {
            assertFalse("no-NET prefix deleted: " + name, new File(dir, name).exists());
        }
        assertArrayEquals("future version untouched", future,
                Files.readAllBytes(new File(dir, futureId + ".cbr").toPath()));
        assertFalse(new File(dir, futureId + ".json").exists());

        // Ten clean games, newer than every crashed one.
        Set<String> before = new HashSet<String>(ids(dir));
        for (int g = 0; g < 10; g++) {
            f.connect(true, 512, 16);
            f.advance(1_000);
            f.netBoundary(0, null, s01(9, 0));
            f.clientBoundary(0, snapshot(0));
            f.tick(g);
            f.step();
            f.advance(3_000_000L);
            f.submit(f.rules.onDisconnect(f.clock.nowUs, false));
            f.step();
        }
        Set<String> clean = new TreeSet<String>(ids(dir));
        clean.removeAll(before);
        assertEquals("ten clean games written", 10, clean.size());
        for (String id : clean) {
            RecordingMeta m = RewindWriterTest.jsonOf(new File(dir, id + ".cbr"));
            assertTrue(m.complete);
            assertFalse(m.recovered);
        }
        Set<String> expected = new TreeSet<String>(clean);
        expected.add(crashed.get(keyIndex).id);
        expected.add(lockedId);
        expected.add(futureId);
        assertEquals(expected, new TreeSet<String>(ids(dir)));
        assertEquals(10, f.writer.pruned());
        assertArrayEquals("locked file still untouched", locked.bytes,
                Files.readAllBytes(new File(dir, lockedId + ".cbr").toPath()));
    }

    @Test
    public void startUpPrunesRecoveredGamesBeyondTheKeepCount() throws IOException {
        File dir = new File(tmp.getRoot(), "rewind");
        for (int i = 0; i < 12; i++) {
            writeCrashed(dir, crashedId(i), crashedBase(i), false, Json.INCOMPLETE, true);
        }
        Fixture f = new Fixture(dir, null, 1_000_000L);
        f.writer.recoverAndPrune();
        assertEquals(12, f.writer.recovered());
        assertEquals(2, f.writer.pruned());
        List<String> left = ids(dir);
        assertEquals(10, left.size());
        assertFalse("oldest pruned", left.contains(crashedId(0)));
        assertFalse(left.contains(crashedId(1)));
        assertTrue(left.contains(crashedId(11)));
    }
}
