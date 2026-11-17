package com.bedwarsqol.rewind;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Disk format v1: every block type round-trips byte for byte with its timestamps, and a torn, flipped
 * or oversized tail is detected and dropped without throwing, keeping the complete prefix.
 */
public class RecordingFormatTest {

    static final long ORIGIN_US = 5_000_000L;
    static final long BASE_EPOCH_MS = 1_700_000_000_000L;

    /** Builds a recording in memory through {@link BlockCodec}; records where each block starts. */
    static final class FileBuilder {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final BlockCodec codec = new BlockCodec();
        final List<Integer> blockStarts = new ArrayList<Integer>();

        FileBuilder() {
            byte[] h = BlockCodec.fileHeader(BASE_EPOCH_MS);
            out.write(h, 0, h.length);
        }

        FileBuilder block(int type, int segment, long firstTimeUs, byte[] raw, boolean deflate) {
            blockStarts.add(out.size());
            int n = codec.encode(type, segment, firstTimeUs, raw, 0, raw.length, deflate);
            out.write(codec.out(), 0, n);
            return this;
        }

        FileBuilder raw(byte[] b) {
            out.write(b, 0, b.length);
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    /** A chunk handed off by a producer, as the writer would take it from the full ring. */
    static RecordChunk handOff(RecordChunk.Producer p) {
        p.handOff();
        RecordChunk c = p.pollFull();
        assertNotNull(c);
        return c;
    }

    static byte[] committed(RecordChunk c) {
        return Arrays.copyOf(c.data(), c.committedLength());
    }

    static byte[] packet(int id, String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + body.length];
        out[0] = (byte) id;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    static byte[] joinGame(int dimension) {
        // S01: int entityId, u8 gameType, i8 dimension, ...
        return new byte[]{0x01, 0, 0, 0, 42, 0, (byte) dimension, 1, 20, 7, 'd', 'e', 'f', 'a', 'u', 'l', 't', 0};
    }

    static byte[] respawn(int dimension) {
        // S07: int dimension, u8 difficulty, u8 gameType, string worldType
        return new byte[]{0x07, 0, 0, 0, (byte) dimension, 1, 0, 7, 'd', 'e', 'f', 'a', 'u', 'l', 't'};
    }

    static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static RecordingReader read(byte[] file) throws IOException {
        return new RecordingReader(new ByteArrayInputStream(file), file.length);
    }

    static List<RecordingReader.Block> readAll(RecordingReader r) throws IOException {
        List<RecordingReader.Block> out = new ArrayList<RecordingReader.Block>();
        RecordingReader.Block b;
        while ((b = r.next()) != null) {
            out.add(b);
        }
        return out;
    }

    /** NBT compound with a nested list of compounds, an int array and a string: exercises the reader's NBT walk. */
    static byte[] itemSlotWithNbt() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x01); o.write(0x13);           // i16 id 275
        o.write(1);                             // count
        o.write(0); o.write(5);                 // damage 5
        o.write(10); o.write(0); o.write(0);    // compound, empty name
        o.write(9); o.write(0); o.write(4); o.write('e'); o.write('n'); o.write('c'); o.write('h');
        o.write(10); o.write(0); o.write(0); o.write(0); o.write(1);   // list of 1 compound
        o.write(2); o.write(0); o.write(2); o.write('i'); o.write('d'); o.write(0); o.write(16); // short id=16
        o.write(0);                             // end of list element compound
        o.write(11); o.write(0); o.write(1); o.write('a'); o.write(0); o.write(0); o.write(0); o.write(2);
        o.write(0); o.write(0); o.write(0); o.write(1); o.write(0); o.write(0); o.write(0); o.write(2);
        o.write(8); o.write(0); o.write(1); o.write('n'); o.write(0); o.write(4);
        o.write('F'); o.write('a'); o.write('k'); o.write('e');
        o.write(0);                             // end of root compound
        return o.toByteArray();
    }

    @Test
    public void everyBlockTypeRoundTripsByteForByteWithTimestamps() throws IOException {
        // NET segment 0 through a real producer: S01 first, then chat and a large packet.
        RecordChunk.Producer net = new RecordChunk.Producer(64 * 1024, 2, 6, ORIGIN_US);
        byte[] carry0 = packet(0x47, "{\"text\":\"Header0\"}");
        assertTrue(net.startSegment(0, carry0, 0, carry0.length));
        byte[][] packets0 = {joinGame(0), packet(0x02, "{\"text\":\"FakePlayerOne: hi\"}"), random(5000, 1)};
        packets0[2][0] = 0x26;
        long[] times0 = {ORIGIN_US + 250, ORIGIN_US + 1750, ORIGIN_US + 71750};
        for (int i = 0; i < packets0.length; i++) {
            assertTrue(net.appendNet(times0[i], packets0[i], 0, packets0[i].length));
        }
        RecordChunk netChunk0 = handOff(net);
        assertTrue(netChunk0.firstOfSegment());
        assertEquals(0, netChunk0.segment());
        byte[] carryRead = Arrays.copyOf(netChunk0.carry(), netChunk0.carryLength());
        assertArrayEquals(carry0, carryRead);

        // TRACK segment 0 through a real producer and encoder.
        RecordChunk.Producer track = new RecordChunk.Producer(64 * 1024, 2, 6, ORIGIN_US);
        assertTrue(track.startSegment(0, null, 0, 0));
        SelfTrackEncoder enc = new SelfTrackEncoder();
        List<int[]> tags = new ArrayList<int[]>();
        List<byte[]> bodies = new ArrayList<byte[]>();
        List<Long> trackTimes = new ArrayList<Long>();
        long t = ORIGIN_US + 300;
        int n = enc.tick(10.5, 64, -3.25, 90f, 12.5f, 91f, RewindFormat.FLAG_ON_GROUND, 2, -1, 0);
        appendTrack(track, enc, t, RewindFormat.TAG_TICK, n, tags, bodies, trackTimes);
        n = enc.tick(10.75, 64, -3.25, 90f, 12.5f, 91f, RewindFormat.FLAG_ON_GROUND, 2, -1, 0);
        appendTrack(track, enc, t += 50_000, RewindFormat.TAG_TICK, n, tags, bodies, trackTimes);
        n = enc.camera(91.5f, 11f);
        appendTrack(track, enc, t += 4_000, RewindFormat.TAG_CAMERA, n, tags, bodies, trackTimes);
        byte[] slot = itemSlotWithNbt();
        n = enc.equip(RewindFormat.EQUIP_HELD, slot, 0, slot.length);
        appendTrack(track, enc, t += 10, RewindFormat.TAG_EQUIP, n, tags, bodies, trackTimes);
        byte[] empty = {(byte) 0xFF, (byte) 0xFF};
        n = enc.equip(4, empty, 0, empty.length);
        appendTrack(track, enc, t += 10, RewindFormat.TAG_EQUIP, n, tags, bodies, trackTimes);
        n = enc.marker(RewindFormat.MARKER_KEY);
        appendTrack(track, enc, t += 10, RewindFormat.TAG_MARKER, n, tags, bodies, trackTimes);
        n = enc.info(RewindFormat.INFO_MAP, "FakeMap");
        appendTrack(track, enc, t += 10, RewindFormat.TAG_INFO, n, tags, bodies, trackTimes);
        n = enc.gameStart(60_000_000L);
        appendTrack(track, enc, t += 10, RewindFormat.TAG_GAME_START, n, tags, bodies, trackTimes);
        n = enc.gameEnd(RewindFormat.GAME_END_WIN);
        appendTrack(track, enc, t += 10, RewindFormat.TAG_GAME_END, n, tags, bodies, trackTimes);
        RecordChunk trackChunk0 = handOff(track);

        // NET segment 1: the dimension-changing S07 opens it.
        byte[] carry1 = packet(0x47, "{\"text\":\"Header1\"}");
        assertTrue(net.startSegment(1, carry1, 0, carry1.length));
        byte[][] packets1 = {respawn(-1), packet(0x03, "time")};
        long[] times1 = {ORIGIN_US + 900_000, ORIGIN_US + 900_001};
        for (int i = 0; i < packets1.length; i++) {
            assertTrue(net.appendNet(times1[i], packets1[i], 0, packets1[i].length));
        }
        RecordChunk netChunk1 = handOff(net);
        assertEquals(1, netChunk1.segment());

        StringBuilder tab = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            tab.append("FakePlayer").append(i).append(",SURVIVAL,ping=40;");
        }
        List<byte[]> snapshot0 = Arrays.asList(joinGame(0), packet(0x38, tab.toString()), packet(0x3B, "obj"));
        byte[] header0 = BlockCodec.headerPayload(RewindFormat.SOURCE_CLIENT_SNAPSHOT, snapshot0);
        byte[] header1 = BlockCodec.headerPayload(RewindFormat.SOURCE_CAPTURE_CARRY,
                java.util.Collections.singletonList(carryRead));
        byte[] header1b = BlockCodec.headerPayload(RewindFormat.SOURCE_CAPTURE_CARRY,
                java.util.Collections.singletonList(carry1));
        String endJson = "{\"format\":1,\"complete\":true}";

        FileBuilder fb = new FileBuilder();
        fb.block(RewindFormat.BLOCK_HEADER, 0, 0, header0, true);
        fb.block(RewindFormat.BLOCK_HEADER, 0, 0, header1, false);
        fb.block(RewindFormat.BLOCK_NET, 0, netChunk0.startTimeUs() - ORIGIN_US, committed(netChunk0), true);
        fb.block(RewindFormat.BLOCK_TRACK, 0, trackChunk0.startTimeUs() - ORIGIN_US, committed(trackChunk0), false);
        fb.block(RewindFormat.BLOCK_HEADER, 1, 900_000, header1b, true);
        fb.block(RewindFormat.BLOCK_NET, 1, netChunk1.startTimeUs() - ORIGIN_US, committed(netChunk1), false);
        List<RecordingReader.IndexEntry> index = new ArrayList<RecordingReader.IndexEntry>();
        for (int i = 0; i < fb.blockStarts.size(); i++) {
            index.add(new RecordingReader.IndexEntry(i == 3 ? 2 : 1, i < 4 ? 0 : 1, 1L << 40 | i,
                    fb.blockStarts.get(i)));
        }
        byte[] indexPayload = BlockCodec.indexPayload(index);
        fb.block(RewindFormat.BLOCK_INDEX, 0, 999_999, indexPayload, false);
        fb.block(RewindFormat.BLOCK_END, 0, 999_999, endJson.getBytes(StandardCharsets.UTF_8), true);
        byte[] file = fb.bytes();

        RecordingReader r = read(file);
        RecordingReader.FileHeader h = r.header();
        assertNotNull(h);
        assertEquals(1, h.formatVersion);
        assertEquals(0, h.flags);
        assertEquals(47, h.protocol);
        assertEquals(BASE_EPOCH_MS, h.baseEpochMs);
        List<RecordingReader.Block> blocks = readAll(r);
        assertEquals(8, blocks.size());
        assertFalse(r.tornTail());
        assertTrue(r.complete());
        assertEquals(file.length, r.validLength());
        assertEquals(RecordingReader.Stop.NONE, r.stop());
        for (int i = 0; i < blocks.size(); i++) {
            assertEquals((long) fb.blockStarts.get(i), blocks.get(i).fileOffset);
        }

        RecordingReader.HeaderPayload hp0 = RecordingReader.headerPayload(blocks.get(0));
        assertEquals(RewindFormat.SOURCE_CLIENT_SNAPSHOT, hp0.source);
        assertEquals(3, hp0.packets.size());
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(snapshot0.get(i), hp0.packets.get(i));
        }
        RecordingReader.HeaderPayload hp1 = RecordingReader.headerPayload(blocks.get(1));
        assertEquals(RewindFormat.SOURCE_CAPTURE_CARRY, hp1.source);
        assertArrayEquals(carry0, hp1.packets.get(0));
        assertEquals(RewindFormat.CODEC_DEFLATE, blocks.get(0).codec);
        assertEquals(RewindFormat.CODEC_STORED, blocks.get(1).codec);

        List<RecordingReader.NetRecord> net0 = RecordingReader.netRecords(blocks.get(2));
        assertEquals(3, net0.size());
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(packets0[i], net0.get(i).bytes);
            assertEquals(times0[i] - ORIGIN_US, net0.get(i).timeUs);
        }
        assertEquals(0x01, net0.get(0).packetId());

        List<RecordingReader.TrackRecord> tr = RecordingReader.trackRecords(blocks.get(3));
        assertEquals(tags.size(), tr.size());
        for (int i = 0; i < tr.size(); i++) {
            assertEquals(tags.get(i)[0], tr.get(i).tag);
            assertArrayEquals(bodies.get(i), tr.get(i).body);
            assertEquals(trackTimes.get(i) - ORIGIN_US, tr.get(i).timeUs);
        }
        assertEquals(RewindFormat.TICK_ALL, tr.get(0).body[0] & 0xFF);
        assertEquals(RewindFormat.TICK_POS, tr.get(1).body[0] & 0xFF);

        assertArrayEquals(carry1, RecordingReader.headerPayload(blocks.get(4)).packets.get(0));
        assertEquals(900_000, blocks.get(4).firstTimeUs);
        List<RecordingReader.NetRecord> net1 = RecordingReader.netRecords(blocks.get(5));
        assertEquals(2, net1.size());
        assertEquals(1, blocks.get(5).segment);
        for (int i = 0; i < 2; i++) {
            assertArrayEquals(packets1[i], net1.get(i).bytes);
            assertEquals(times1[i] - ORIGIN_US, net1.get(i).timeUs);
        }

        List<RecordingReader.IndexEntry> back = RecordingReader.index(blocks.get(6));
        assertEquals(index.size(), back.size());
        for (int i = 0; i < index.size(); i++) {
            assertEquals(index.get(i).type, back.get(i).type);
            assertEquals(index.get(i).segment, back.get(i).segment);
            assertEquals(index.get(i).timeUs, back.get(i).timeUs);
            assertEquals(index.get(i).fileOffset, back.get(i).fileOffset);
        }
        assertEquals(endJson, RecordingReader.endJson(blocks.get(7)));
        assertEquals(999_999, blocks.get(7).firstTimeUs);

        // Byte for byte: re-encoding each read block with its codec reproduces the file exactly.
        BlockCodec again = new BlockCodec();
        ByteArrayOutputStream re = new ByteArrayOutputStream();
        re.write(BlockCodec.fileHeader(BASE_EPOCH_MS), 0, RewindFormat.FILE_HEADER_LENGTH);
        for (RecordingReader.Block b : blocks) {
            int len = again.encode(b.type, b.segment, b.firstTimeUs, b.payload, 0, b.payload.length,
                    b.codec == RewindFormat.CODEC_DEFLATE);
            re.write(again.out(), 0, len);
        }
        assertArrayEquals(file, re.toByteArray());
    }

    private static void appendTrack(RecordChunk.Producer p, SelfTrackEncoder enc, long t, int tag, int n,
                                    List<int[]> tags, List<byte[]> bodies, List<Long> times) {
        assertTrue(p.appendTrack(t, tag, enc.buffer(), 0, n));
        tags.add(new int[]{tag});
        bodies.add(Arrays.copyOf(enc.buffer(), n));
        times.add(t);
    }

    /** Two segments, a deflated NET block and END last, so the last two blocks cover both codecs. */
    static FileBuilder smallFile() {
        RecordChunk.Producer net = new RecordChunk.Producer(16 * 1024, 2, 4, ORIGIN_US);
        net.startSegment(0, null, 0, 0);
        byte[] s01 = joinGame(0);
        net.appendNet(ORIGIN_US + 10, s01, 0, s01.length);
        for (int i = 0; i < 40; i++) {
            byte[] p = packet(0x03, "tick tick tick " + i);
            net.appendNet(ORIGIN_US + 100 + i * 50_000L, p, 0, p.length);
        }
        RecordChunk c = handOff(net);
        FileBuilder fb = new FileBuilder();
        fb.block(RewindFormat.BLOCK_HEADER, 0, 0,
                BlockCodec.headerPayload(RewindFormat.SOURCE_CLIENT_SNAPSHOT,
                        java.util.Collections.singletonList(joinGame(0))), false);
        fb.block(RewindFormat.BLOCK_NET, 0, c.startTimeUs() - ORIGIN_US, committed(c), true);
        fb.block(RewindFormat.BLOCK_END, 0, 2_000_000, "{\"complete\":true}".getBytes(StandardCharsets.UTF_8),
                false);
        return fb;
    }

    @Test
    public void truncationAtEveryByteOfTheLastTwoBlocksKeepsThePrefix() throws IOException {
        FileBuilder fb = smallFile();
        byte[] file = fb.bytes();
        int nBlocks = fb.blockStarts.size();
        assertEquals(RewindFormat.CODEC_DEFLATE, file[fb.blockStarts.get(nBlocks - 2) + 5]);
        for (int cut = fb.blockStarts.get(nBlocks - 2); cut < file.length; cut++) {
            byte[] torn = Arrays.copyOf(file, cut);
            int whole = 0;
            int validEnd = RewindFormat.FILE_HEADER_LENGTH;
            for (int i = 0; i < nBlocks; i++) {
                int end = i + 1 < nBlocks ? fb.blockStarts.get(i + 1) : file.length;
                if (end <= cut) {
                    whole = i + 1;
                    validEnd = end;
                }
            }
            try {
                RecordingReader r = read(torn);
                List<RecordingReader.Block> blocks = readAll(r);
                assertEquals("cut " + cut, whole, blocks.size());
                assertEquals("cut " + cut, validEnd, r.validLength());
                assertEquals("cut " + cut, cut != validEnd, r.tornTail());
                assertFalse("cut " + cut, r.complete());
            } catch (RuntimeException e) {
                throw new AssertionError("reader threw at cut " + cut, e);
            }
        }
    }

    @Test
    public void truncatedFileHeaderIsATornEmptyPrefix() throws IOException {
        byte[] file = smallFile().bytes();
        for (int cut = 0; cut < RewindFormat.FILE_HEADER_LENGTH; cut++) {
            RecordingReader r = read(Arrays.copyOf(file, cut));
            assertNull(r.header());
            assertNull(r.next());
            assertEquals(0, r.validLength());
            assertEquals(cut > 0, r.tornTail());
            assertFalse(r.complete());
        }
    }

    @Test
    public void aFlippedByteStopsAtTheDamagedBlock() throws IOException {
        FileBuilder fb = smallFile();
        byte[] file = fb.bytes();
        int start = fb.blockStarts.get(1);
        int end = fb.blockStarts.get(2);
        for (int i = start; i < end; i++) {
            byte[] bad = file.clone();
            bad[i] ^= 0x10;
            RecordingReader r = read(bad);
            List<RecordingReader.Block> blocks = readAll(r);
            assertEquals("flip " + i, 1, blocks.size());
            assertEquals("flip " + i, start, r.validLength());
            assertTrue("flip " + i, r.tornTail());
            assertFalse("flip " + i, r.complete());
            if (i >= start + RewindFormat.BLOCK_HEADER_LENGTH || (i >= start + 4 && i < start + 20)) {
                assertEquals("flip " + i, RecordingReader.Stop.CRC, r.stop());
            }
        }
        byte[] badHeader = file.clone();
        badHeader[20] ^= 1;
        RecordingReader r = read(badHeader);
        assertNull(r.header());
        assertEquals(RecordingReader.Stop.BAD_FILE_HEADER, r.stop());
        assertTrue(r.tornTail());
    }

    @Test
    public void anOverCapLengthIsRejectedWithoutReadingIt() throws IOException {
        FileBuilder fb = smallFile();
        byte[] valid = fb.bytes();
        for (int field : new int[]{20, 24}) {
            byte[] hdr = new byte[RewindFormat.BLOCK_HEADER_LENGTH];
            RewindFormat.putInt(hdr, 0, RewindFormat.BLOCK_MAGIC);
            hdr[4] = RewindFormat.BLOCK_NET;
            hdr[5] = RewindFormat.CODEC_STORED;
            RewindFormat.putInt(hdr, 20, 16);
            RewindFormat.putInt(hdr, 24, 16);
            RewindFormat.putInt(hdr, field, RewindFormat.MAX_STORED_LENGTH + 1);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(valid, 0, fb.blockStarts.get(2)); // header + first two blocks, END dropped
            o.write(hdr, 0, hdr.length);
            o.write(new byte[64], 0, 64);
            RecordingReader r = read(o.toByteArray());
            assertEquals(2, readAll(r).size());
            assertEquals(RecordingReader.Stop.OVERSIZE, r.stop());
            assertTrue(r.tornTail());
            assertEquals((long) fb.blockStarts.get(2), r.validLength());
        }
        BlockCodec codec = new BlockCodec();
        try {
            codec.encode(RewindFormat.BLOCK_NET, 0, 0, new byte[RewindFormat.MAX_RAW_LENGTH + 1], 0,
                    RewindFormat.MAX_RAW_LENGTH + 1, false);
            fail("encoder accepted an over-cap block");
        } catch (IllegalArgumentException expected) {
            // the writer can never produce what the reader rejects
        }
    }

    @Test
    public void incompressiblePayloadIsStoredAndDeflateIsUsedWhenItHelps() throws IOException {
        byte[] noise = random(4096, 7);
        byte[] text = new byte[4096];
        Arrays.fill(text, (byte) 'a');
        FileBuilder fb = new FileBuilder();
        fb.block(RewindFormat.BLOCK_END, 0, 0, noise, true);
        byte[] f1 = fb.bytes();
        assertEquals(RewindFormat.CODEC_STORED, f1[fb.blockStarts.get(0) + 5]);
        FileBuilder fb2 = new FileBuilder();
        fb2.block(RewindFormat.BLOCK_END, 0, 0, text, true);
        byte[] f2 = fb2.bytes();
        assertEquals(RewindFormat.CODEC_DEFLATE, f2[fb2.blockStarts.get(0) + 5]);
        assertTrue(f2.length < 1000);
        assertArrayEquals(noise, readAll(read(f1)).get(0).payload);
        assertArrayEquals(text, readAll(read(f2)).get(0).payload);
    }

    @Test
    public void anUnknownFormatVersionIsFlaggedAndNotRead() throws IOException {
        byte[] file = smallFile().bytes();
        file[9] = 2; // formatVersion = 2
        BlockCodec codec = new BlockCodec();
        RewindFormat.putInt(file, 24, (int) codec.crc(file, 0, 24));
        RecordingReader r = read(file);
        assertNotNull(r.header());
        assertEquals(2, r.header().formatVersion);
        assertTrue(r.unsupportedVersion());
        assertNull(r.next());
        assertEquals(RecordingReader.Stop.UNSUPPORTED_VERSION, r.stop());
        assertFalse(r.tornTail());
    }

    @Test
    public void bytesAfterEndAreATornTail() throws IOException {
        FileBuilder fb = smallFile();
        int endStart = fb.out.size();
        fb.block(RewindFormat.BLOCK_TRACK, 0, 0, new byte[0], false);
        RecordingReader r = read(fb.bytes());
        assertEquals(3, readAll(r).size());
        assertTrue(r.complete());
        assertTrue(r.tornTail());
        assertEquals(RecordingReader.Stop.AFTER_END, r.stop());
        assertEquals(endStart, r.validLength());
    }

    @Test
    public void aMalformedRecordInsideAValidBlockStopsThere() throws IOException {
        byte[] badNet = {0, 50, 0x01, 0x02}; // dt 0, len 50, only 2 bytes follow
        byte[] badTrack = {0, 0x7F};         // unknown tag
        byte[] badHeader = {5, 0};           // unknown source
        for (Object[] c : new Object[][]{{RewindFormat.BLOCK_NET, badNet}, {RewindFormat.BLOCK_TRACK, badTrack},
                {RewindFormat.BLOCK_HEADER, badHeader}}) {
            FileBuilder fb = new FileBuilder();
            byte[] ok = BlockCodec.headerPayload(RewindFormat.SOURCE_CLIENT_SNAPSHOT,
                    java.util.Collections.singletonList(joinGame(0)));
            fb.block(RewindFormat.BLOCK_HEADER, 0, 0, ok, false);
            int at = fb.out.size();
            fb.block((Integer) c[0], 0, 0, (byte[]) c[1], false);
            RecordingReader r = read(fb.bytes());
            assertEquals(1, readAll(r).size());
            assertEquals(RecordingReader.Stop.MALFORMED, r.stop());
            assertTrue(r.tornTail());
            assertEquals(at, r.validLength());
        }
    }

    @Test
    public void varintsRoundTripAtTheirEdges() {
        byte[] b = new byte[10];
        long[] longs = {0, 1, 127, 128, 16383, 16384, Integer.MAX_VALUE, 0xFFFFFFFFL, Long.MAX_VALUE, -1L};
        for (long v : longs) {
            int end = RewindFormat.putVarLong(b, 0, v);
            RewindFormat.Cursor c = new RewindFormat.Cursor(b, 0, end);
            assertEquals(v, c.varLong());
            assertFalse(c.hasMore());
        }
        int[] ints = {0, 1, 127, 128, 300, 2_097_152, Integer.MAX_VALUE, -1};
        for (int v : ints) {
            int end = RewindFormat.putVarInt(b, 0, v);
            assertEquals(end, RewindFormat.varIntSize(v));
            RewindFormat.Cursor c = new RewindFormat.Cursor(b, 0, end);
            assertEquals(v, c.varInt());
        }
        try {
            new RewindFormat.Cursor(new byte[]{(byte) 0x80}, 0, 1).varInt();
            fail("short varint read");
        } catch (RewindFormat.FormatException expected) {
            // a torn varint is a format error, never an index exception
        }
    }
}
