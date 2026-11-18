package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.BlockCodec;
import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RecordingReader;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes format-v1 recordings for the watch tests with R1's {@link BlockCodec} and {@link RewindFormat}, block by
 * block, so a test controls segments, block splits and the file order of NET and TRACK blocks. Packet bodies are
 * synthetic: only the leading packet id matters to the loader.
 */
final class RecordingFixture {

    static final long BASE_EPOCH_MS = 1_700_000_000_000L;

    /** One stored NET record as the cursor must return it. */
    static final class Net {
        final int segment;
        final long timeUs;
        final byte[] bytes;

        Net(int segment, long timeUs, byte[] bytes) {
            this.segment = segment;
            this.timeUs = timeUs;
            this.bytes = bytes;
        }
    }

    /** One TRACK record for {@link #track}. */
    static final class Track {
        final long timeUs;
        final int tag;
        final byte[] body;

        Track(long timeUs, int tag, byte[] body) {
            this.timeUs = timeUs;
            this.tag = tag;
            this.body = body;
        }
    }

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final BlockCodec codec = new BlockCodec();
    private final List<RecordingReader.IndexEntry> index = new ArrayList<RecordingReader.IndexEntry>();
    /** Every NET record written, in file order. */
    final List<Net> netWritten = new ArrayList<Net>();
    long storedNetBytes;
    long rawNetBytes;
    long lastTimeUs;

    RecordingFixture() {
        byte[] h = BlockCodec.fileHeader(BASE_EPOCH_MS);
        out.write(h, 0, h.length);
    }

    private void block(int type, int segment, long firstTimeUs, byte[] raw, boolean deflate) {
        index.add(new RecordingReader.IndexEntry(type, segment, firstTimeUs, out.size()));
        int n = codec.encode(type, segment, firstTimeUs, raw, 0, raw.length, deflate);
        if (type == RewindFormat.BLOCK_NET) {
            storedNetBytes += n - RewindFormat.BLOCK_HEADER_LENGTH;
            rawNetBytes += raw.length;
        }
        out.write(codec.out(), 0, n);
    }

    RecordingFixture header(int segment, int source, long timeUs, byte[]... packets) {
        block(RewindFormat.BLOCK_HEADER, segment, timeUs, BlockCodec.headerPayload(source, Arrays.asList(packets)),
                true);
        return this;
    }

    /** One NET block: record i at {@code times[i]}, the first measured from {@code firstTimeUs}. */
    RecordingFixture net(int segment, long firstTimeUs, long[] times, byte[][] packets) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        byte[] tmp = new byte[10];
        long prev = firstTimeUs;
        for (int i = 0; i < times.length; i++) {
            int n = RewindFormat.putVarInt(tmp, 0, (int) (times[i] - prev));
            n = RewindFormat.putVarInt(tmp, n, packets[i].length);
            p.write(tmp, 0, n);
            p.write(packets[i], 0, packets[i].length);
            prev = times[i];
            netWritten.add(new Net(segment, times[i], packets[i]));
            lastTimeUs = Math.max(lastTimeUs, times[i]);
        }
        block(RewindFormat.BLOCK_NET, segment, firstTimeUs, p.toByteArray(), true);
        return this;
    }

    RecordingFixture track(int segment, long firstTimeUs, Track... records) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        byte[] tmp = new byte[10];
        long prev = firstTimeUs;
        for (Track r : records) {
            int n = RewindFormat.putVarInt(tmp, 0, (int) (r.timeUs - prev));
            p.write(tmp, 0, n);
            p.write(r.tag);
            p.write(r.body, 0, r.body.length);
            prev = r.timeUs;
            lastTimeUs = Math.max(lastTimeUs, r.timeUs);
        }
        block(RewindFormat.BLOCK_TRACK, segment, firstTimeUs, p.toByteArray(), true);
        return this;
    }

    /** INDEX + END with {@code meta}'s JSON, as R1 closes a file. */
    RecordingFixture end(RecordingMeta meta, long endUs) {
        block(RewindFormat.BLOCK_INDEX, 0, endUs, BlockCodec.indexPayload(index), true);
        block(RewindFormat.BLOCK_END, 0, endUs, meta.toUtf8(), false);
        return this;
    }

    RecordingFixture raw(byte[] b) {
        out.write(b, 0, b.length);
        return this;
    }

    byte[] bytes() {
        return out.toByteArray();
    }

    File write(File dir, String id) throws IOException {
        File f = new File(dir, id + ".cbr");
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(bytes());
        } finally {
            o.close();
        }
        return f;
    }

    static File writeMeta(File dir, String id, RecordingMeta meta) throws IOException {
        File f = new File(dir, id + ".json");
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(meta.toUtf8());
        } finally {
            o.close();
        }
        return f;
    }

    // ---------------- packets and track bodies ----------------

    /** A packet: id byte then {@code n} filler bytes derived from {@code seed} (compressible). */
    static byte[] packet(int id, int n, int seed) {
        byte[] b = new byte[1 + n];
        b[0] = (byte) id;
        for (int i = 1; i < b.length; i++) {
            b[i] = (byte) ((seed + i / 7) & 0x3F);
        }
        return b;
    }

    static byte[] joinGame(int dimension) {
        // S01: int entityId, u8 gameType, i8 dimension, u8 difficulty, u8 maxPlayers, string levelType, bool
        return new byte[]{0x01, 0, 0, 0, 42, 0, (byte) dimension, 1, 20, 7, 'd', 'e', 'f', 'a', 'u', 'l', 't', 0};
    }

    static byte[] respawn(int dimension) {
        // S07: int dimension, u8 difficulty, u8 gameType, string levelType
        return new byte[]{0x07, 0, 0, 0, (byte) dimension, 1, 0, 7, 'd', 'e', 'f', 'a', 'u', 'l', 't'};
    }

    static Track tick(long timeUs, SelfTrackEncoder enc, double x, double y, double z, float yaw, float pitch,
                      int flags) {
        int n = enc.tick(x, y, z, yaw, pitch, yaw, flags, 0, -1, 0);
        if (n == 0) {
            throw new IllegalStateException("unchanged tick writes nothing");
        }
        return new Track(timeUs, RewindFormat.TAG_TICK, Arrays.copyOf(enc.buffer(), n));
    }

    static Track camera(long timeUs, float yaw, float pitch) {
        byte[] b = new byte[8];
        RewindFormat.putFloat(b, 0, yaw);
        RewindFormat.putFloat(b, 4, pitch);
        return new Track(timeUs, RewindFormat.TAG_CAMERA, b);
    }

    /** EQUIP with an item stack of id {@code item} and {@code count} (no NBT), or empty for item -1. */
    static Track equip(long timeUs, int slot, int item, int count) {
        byte[] b = item < 0 ? new byte[]{(byte) slot, (byte) 0xFF, (byte) 0xFF}
                : new byte[]{(byte) slot, (byte) (item >> 8), (byte) item, (byte) count, 0, 0, 0};
        return new Track(timeUs, RewindFormat.TAG_EQUIP, b);
    }

    static Track marker(long timeUs, int kind) {
        return new Track(timeUs, RewindFormat.TAG_MARKER, new byte[]{(byte) kind});
    }

    static Track gameStart(long timeUs, long backUs) {
        byte[] b = new byte[10];
        int n = RewindFormat.putVarLong(b, 0, backUs);
        return new Track(timeUs, RewindFormat.TAG_GAME_START, Arrays.copyOf(b, n));
    }

    static Track info(long timeUs, int key, String value) {
        byte[] s = value.getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[2 + s.length];
        b[0] = (byte) key;
        b[1] = (byte) s.length;
        System.arraycopy(s, 0, b, 2, s.length);
        return new Track(timeUs, RewindFormat.TAG_INFO, b);
    }

    static RecordingMeta meta(String id, long gameStartUs, long endUs) {
        RecordingMeta m = new RecordingMeta();
        m.id = id;
        m.complete = true;
        m.gameStartUs = gameStartUs;
        m.endUs = endUs;
        m.lengthUs = endUs - gameStartUs;
        m.mode = "BEDWARS_EIGHT_ONE";
        m.serverType = "BEDWARS";
        m.server = RecordingMeta.SERVER_HYPIXEL;
        return m;
    }

    // ---------------- the real-shaped recording ----------------

    static final int SEG_JOIN = 4;
    static final int SEG_NETHER = 5;
    static final int SEG_GAME = 6;
    static final long T0 = 9_000_000L;
    /** The Bed Wars active edge: GAME_START is written there with backUs 0. */
    static final long GAME_START_US = T0 + 30_000_000L;
    static final long END_US = T0 + 90_000_000L;
    static final long TRACK_US = T0 + 30_000;
    /** BED_BROKEN in the earlier TRACK block, DEATH in the later one (written first in the file). */
    static final long[] MARKER_US = {GAME_START_US + 10_000_000L, TRACK_US + 40_100_000L};

    /**
     * Shaped like the real files: an S01 segment, an S07 -1 segment and an S07 0 segment that holds everything else,
     * all boundaries within the first few ms; the last segment's NET records are split over three blocks mid-segment,
     * and its TRACK blocks sit between NET blocks out of time order. INDEX + END close it.
     */
    static RecordingFixture realShaped() {
        RecordingFixture f = new RecordingFixture();
        byte[] s47 = packet(0x47, 20, 1);
        f.header(SEG_JOIN, RewindFormat.SOURCE_CLIENT_SNAPSHOT, T0, joinGame(0), packet(0x09, 1, 0),
                packet(0x38, 80, 2), packet(0x3E, 30, 3));
        f.header(SEG_JOIN, RewindFormat.SOURCE_CAPTURE_CARRY, T0, s47);
        f.net(SEG_JOIN, T0, new long[]{T0 + 10, T0 + 90}, new byte[][]{joinGame(0), packet(0x38, 40, 4)});

        f.header(SEG_NETHER, RewindFormat.SOURCE_CLIENT_SNAPSHOT, T0 + 300, joinGame(-1), packet(0x09, 1, 0));
        f.header(SEG_NETHER, RewindFormat.SOURCE_CAPTURE_CARRY, T0 + 300, s47);
        f.net(SEG_NETHER, T0 + 300, new long[]{T0 + 310}, new byte[][]{respawn(-1)});

        f.header(SEG_GAME, RewindFormat.SOURCE_CLIENT_SNAPSHOT, T0 + 700, joinGame(0), packet(0x09, 1, 0));
        f.header(SEG_GAME, RewindFormat.SOURCE_CAPTURE_CARRY, T0 + 700, s47);

        // Last segment: NET over three blocks, the second starting mid-stream (firstTimeUs = previous record).
        int n = 300;
        long[] times = new long[n];
        byte[][] packets = new byte[n][];
        times[0] = T0 + 710;
        packets[0] = respawn(0);
        for (int i = 1; i < n; i++) {
            times[i] = times[i - 1] + (i % 5 == 0 ? 0 : 280_000 + i * 13);
            int id = new int[]{0x21, 0x15, 0x02, 0x0C, 0x3E, 0x14}[i % 6];
            packets[i] = packet(id, 10 + (i * 37) % 400, i);
        }
        SelfTrackEncoder enc = new SelfTrackEncoder();
        long tr = TRACK_US; // TRACK starts after the last boundary
        f.net(SEG_GAME, T0 + 700, Arrays.copyOfRange(times, 0, 100), Arrays.copyOfRange(packets, 0, 100));
        // A TRACK block from later in time than the NET block written after it.
        f.track(SEG_GAME, tr + 40_000_000L,
                tick(tr + 40_050_000L, enc, 1, 64, 1, 10f, 0f, RewindFormat.FLAG_ON_GROUND),
                marker(MARKER_US[1], RewindFormat.MARKER_DEATH));
        f.net(SEG_GAME, times[99], Arrays.copyOfRange(times, 100, 200), Arrays.copyOfRange(packets, 100, 200));
        SelfTrackEncoder enc2 = new SelfTrackEncoder();
        f.track(SEG_GAME, tr,
                tick(tr, enc2, 0, 64, 0, 0f, 0f, RewindFormat.FLAG_ON_GROUND),
                tick(tr + 50_000, enc2, 0.25, 64, 0, 0f, 0f, RewindFormat.FLAG_ON_GROUND),
                camera(tr + 60_000, 5f, 1f),
                equip(tr + 70_000, RewindFormat.EQUIP_HELD, 267, 1),
                info(tr + 80_000, RewindFormat.INFO_MAP, "Fixture"),
                gameStart(GAME_START_US, 0),
                marker(MARKER_US[0], RewindFormat.MARKER_BED_BROKEN));
        f.net(SEG_GAME, times[199], Arrays.copyOfRange(times, 200, n), Arrays.copyOfRange(packets, 200, n));
        f.end(realShapedMeta(), END_US);
        return f;
    }

    static RecordingMeta realShapedMeta() {
        RecordingMeta m = meta("20261117-120000-abcd", GAME_START_US, END_US);
        m.markers.add(new RecordingMeta.Marker(MARKER_US[0], "BED_BROKEN"));
        m.markers.add(new RecordingMeta.Marker(MARKER_US[1], "DEATH"));
        m.segments = 3;
        return m;
    }

}
