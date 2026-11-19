package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.feature.HypixelLocationCodec.Location;
import com.bedwarsqol.feature.SessionChatLine;
import com.bedwarsqol.rewind.BlockCodec;
import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.PacketStats;
import com.bedwarsqol.rewind.RecordChunk;
import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RecordingReader;
import com.bedwarsqol.rewind.RecordingStore;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.RewindGameRules;
import com.bedwarsqol.rewind.RewindGameRules.Command;
import com.bedwarsqol.rewind.RewindGameRules.CommandType;
import com.bedwarsqol.rewind.RewindWriter;
import com.bedwarsqol.rewind.SelfTrackEncoder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

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
    /** {@link #realShaped(int, Track...)}'s extra TRACK records, written before INDEX + END. */
    private Track[] pendingExtra;

    RecordingFixture() {
        byte[] h = BlockCodec.fileHeader(BASE_EPOCH_MS);
        out.write(h, 0, h.length);
    }

    /** A file whose header names format {@code version} (1: what dev.95/dev.96 wrote; 2: the current writer's). */
    RecordingFixture(int version) {
        byte[] h = BlockCodec.fileHeader(BASE_EPOCH_MS);
        RewindFormat.putShort(h, 8, version);
        CRC32 crc = new CRC32();
        crc.update(h, 0, 24);
        RewindFormat.putInt(h, 24, (int) crc.getValue());
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

    // ---------------- format v2 extension records ----------------

    /** An extension record as {@code ExtCodec} encoded it: {@code c.buffer()[0, n)} = varint length + body. */
    static Track ext(long timeUs, int tag, ExtCodec c, int n) {
        return new Track(timeUs, tag, Arrays.copyOf(c.buffer(), n));
    }

    /** An extension record with a raw body (any bytes; framed with its varint length, as on disk). */
    static Track extRaw(long timeUs, int tag, byte[] body) {
        byte[] b = new byte[5 + body.length];
        int n = RewindFormat.putVarInt(b, 0, body.length);
        System.arraycopy(body, 0, b, n, body.length);
        return new Track(timeUs, tag, Arrays.copyOf(b, n + body.length));
    }

    /** The body of an extension {@link Track} (the bytes after its length prefix). */
    static byte[] extBody(Track t) {
        RewindFormat.Cursor c = new RewindFormat.Cursor(t.body, 0, t.body.length);
        int len = c.varInt();
        return Arrays.copyOfRange(t.body, c.pos(), c.pos() + len);
    }

    /** A protocol-47 item slot: item id, count, damage, no NBT. */
    static byte[] itemSlot(int item, int count, int damage) {
        return new byte[]{(byte) (item >> 8), (byte) item, (byte) count, (byte) (damage >> 8), (byte) damage, 0};
    }

    /**
     * One record of each of the 17 extension tags, every value derived from {@code seed}, the i-th at
     * {@code t0 + i * stepUs} (step 0: all at one time). Keyed ones: INV slot {@code seed % 36}, SETTINGS FOV and the
     * accent, HUD FPS.
     */
    static List<Track> everyExtTag(long t0, long stepUs, int seed) {
        ExtCodec c = new ExtCodec();
        List<Track> out = new ArrayList<Track>();
        long t = t0;
        out.add(ext(t, RewindFormat.TAG_TICK_HEAD, c, c.tickHead(seed % 7)));
        byte[] data = {0x01, (byte) seed, 0x03};
        out.add(ext(t += stepUs, RewindFormat.TAG_OUT, c, c.out(0x0A, data, 0, data.length)));
        out.add(ext(t += stepUs, RewindFormat.TAG_DIG, c, c.dig(RewindFormat.DIG_HITTING, 1000L + seed, 1, seed % 10)));
        out.add(ext(t += stepUs, RewindFormat.TAG_BLOCK, c, c.block(2000L + seed, 35 << 4, 0,
                RewindFormat.BLOCK_CAUSE_OWN_BREAK)));
        out.add(ext(t += stepUs, RewindFormat.TAG_SOUND, c, c.sound("minecraft:dig.cloth", seed, 64f, 3f, 1f, 0.8f)));
        out.add(ext(t += stepUs, RewindFormat.TAG_VIEW, c, c.view(seed % 3, RewindFormat.VIEW_F3,
                RewindFormat.SCREEN_INVENTORY)));
        out.add(ext(t += stepUs, RewindFormat.TAG_MOUSE, c, c.mouse(-40 + seed, 12)));
        out.add(ext(t += stepUs, RewindFormat.TAG_CHAT_INPUT, c, c.chatInput("/fake" + seed, 5)));
        out.add(ext(t += stepUs, RewindFormat.TAG_FOV, c, c.fov(70f + seed)));
        out.add(ext(t += stepUs, RewindFormat.TAG_USE, c, c.use(72_000 - seed)));
        byte[] slot = itemSlot(267, 1 + seed % 60, seed);
        out.add(ext(t += stepUs, RewindFormat.TAG_INV, c, c.inv(seed % 36, slot, 0, slot.length)));
        c.beginSettings();
        c.settingFloat(RewindFormat.SETTING_FOV, 90f + seed);
        c.settingString(RewindFormat.SETTING_GUI_ACCENT, "pale-blue");
        out.add(ext(t += stepUs, RewindFormat.TAG_SETTINGS, c, c.endSettings()));
        out.add(ext(t += stepUs, RewindFormat.TAG_IDENTITY, c, c.identity(1L, 2L + seed, "FakeName", "FakeSkin", "")));
        out.add(ext(t += stepUs, RewindFormat.TAG_INPUT, c, c.input(RewindFormat.INPUT_W | RewindFormat.INPUT_LMB)));
        out.add(ext(t += stepUs, RewindFormat.TAG_HUD, c,
                c.beginHud(RewindFormat.HUD_FPS, RewindFormat.HUD_FLAG_ON_SCREEN).putVarInt(100 + seed).end()));
        out.add(ext(t += stepUs, RewindFormat.TAG_CONT, c, c.cont(2, 53, slot, 0, slot.length)));
        out.add(ext(t += stepUs, RewindFormat.TAG_TAP, c, c.tap(RewindFormat.TAP_LMB)));
        return out;
    }

    /**
     * A recorder run through the real {@link RewindWriter} in step mode on a temp folder (as R1's writer tests drive
     * it): rules, a NET and a TRACK producer, a settable clock. Every TRACK record appended is kept with its segment.
     */
    static final class WriterRun {
        static final class Sent {
            final int segment;
            final long timeUs;
            final int tag;
            final byte[] appended;

            Sent(int segment, long timeUs, int tag, byte[] appended) {
                this.segment = segment;
                this.timeUs = timeUs;
                this.tag = tag;
                this.appended = appended;
            }
        }

        final File dir;
        long nowUs;
        final RewindWriter writer;
        final RewindGameRules rules = new RewindGameRules();
        final PacketStats.Feed feed = new PacketStats.Feed();
        RecordChunk.Producer net;
        RecordChunk.Producer track;
        final SelfTrackEncoder enc = new SelfTrackEncoder();
        final List<Sent> trackSent = new ArrayList<Sent>();
        int netSent;

        WriterRun(File dir, long startUs) {
            this.dir = dir;
            this.nowUs = startUs;
            RewindWriter.Clock clock = new RewindWriter.Clock() {
                @Override
                public long nowUs() {
                    return WriterRun.this.nowUs;
                }

                @Override
                public long wallMs() {
                    return 1_760_000_000_000L + WriterRun.this.nowUs / 1000;
                }
            };
            this.writer = new RewindWriter(new RecordingStore(dir), "0.0.0-test", clock, new Random(42));
        }

        void connect(int chunkCapacity) {
            writer.submitAll(rules.onConnect(nowUs, false, true));
            net = new RecordChunk.Producer(chunkCapacity, 2, 256, nowUs);
            track = new RecordChunk.Producer(chunkCapacity, 2, 256, nowUs);
            writer.attach(net, track, feed);
        }

        void advance(long us) {
            nowUs += us;
        }

        /** Both sides of a boundary: the boundary packet opens the NET segment, then TRACK, header and rules. */
        void boundary(int segment, byte[] boundaryPacket, int dimension) {
            if (!net.startSegment(segment, null, 0, 0)) {
                throw new IllegalStateException("net segment");
            }
            net(boundaryPacket);
            if (!track.startSegment(segment, null, 0, 0)) {
                throw new IllegalStateException("track segment");
            }
            enc.resetSegment();
            List<byte[]> snapshot = new ArrayList<byte[]>();
            snapshot.add(joinGame(dimension));
            snapshot.add(packet(0x38, 30, 900 + dimension));
            writer.header(segment, nowUs, snapshot);
            writer.submitAll(rules.onBoundary(nowUs, segment));
            writer.submitAll(rules.onLocation(nowUs, segment,
                    new Location("fakeServer1A", "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap")));
        }

        void net(byte[] p) {
            if (!net.appendNet(nowUs, p, 0, p.length)) {
                throw new IllegalStateException("net append");
            }
            netSent++;
        }

        void track(int tag, byte[] appended) {
            if (!track.appendTrack(nowUs, tag, appended, 0, appended.length)) {
                throw new IllegalStateException("track append");
            }
            trackSent.add(new Sent(track.segment(), nowUs, tag, appended));
        }

        /** The Bed Wars active edge: the rules open the file, GAME_START is written with backUs 0. */
        void open() {
            List<Command> open = rules.tick(nowUs, false, true);
            if (open.isEmpty() || open.get(0).type != CommandType.OPEN) {
                throw new IllegalStateException("no OPEN: " + open);
            }
            writer.submitAll(open);
            track(RewindFormat.TAG_GAME_START, new byte[]{0});
            writer.step();
        }

        void step() {
            writer.step();
        }

        /** A win, then the close after the result delay; returns the one recording. */
        File close() {
            writer.submitAll(rules.onGameLine(nowUs, SessionChatLine.Kind.WIN));
            int n = enc.gameEnd(RewindFormat.GAME_END_WIN);
            track(RewindFormat.TAG_GAME_END, Arrays.copyOf(enc.buffer(), n));
            writer.step();
            nowUs += RewindGameRules.RESULT_CLOSE_US;
            List<Command> close = rules.tick(nowUs, false, false);
            if (close.isEmpty() || close.get(0).type != CommandType.CLOSE) {
                throw new IllegalStateException("no CLOSE: " + close);
            }
            writer.submitAll(close);
            writer.step();
            File[] files = dir.listFiles();
            File cbr = null;
            for (File f : files == null ? new File[0] : files) {
                if (f.getName().endsWith(".cbr")) {
                    if (cbr != null) {
                        throw new IllegalStateException("two recordings");
                    }
                    cbr = f;
                }
            }
            if (cbr == null) {
                throw new IllegalStateException("no recording");
            }
            return cbr;
        }
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
        return realShaped(RewindFormat.FORMAT_VERSION);
    }

    /**
     * {@link #realShaped()} with a header naming format {@code version}, plus {@code extra} TRACK records (if any) in
     * one more TRACK block of the last segment.
     */
    static RecordingFixture realShaped(int version, Track... extra) {
        RecordingFixture f = new RecordingFixture(version);
        if (extra.length > 0) {
            f.pendingExtra = extra;
        }
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
        if (f.pendingExtra != null) {
            f.track(SEG_GAME, f.pendingExtra[0].timeUs, f.pendingExtra);
            f.pendingExtra = null;
        }
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
