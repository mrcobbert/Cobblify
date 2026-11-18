package com.bedwarsqol.rewind;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

import static com.bedwarsqol.rewind.RecordingFormatTest.BASE_EPOCH_MS;
import static com.bedwarsqol.rewind.RecordingFormatTest.FileBuilder;
import static com.bedwarsqol.rewind.RecordingFormatTest.ORIGIN_US;
import static com.bedwarsqol.rewind.RecordingFormatTest.committed;
import static com.bedwarsqol.rewind.RecordingFormatTest.handOff;
import static com.bedwarsqol.rewind.RecordingFormatTest.joinGame;
import static com.bedwarsqol.rewind.RecordingFormatTest.packet;
import static com.bedwarsqol.rewind.RecordingFormatTest.read;
import static com.bedwarsqol.rewind.RecordingFormatTest.readAll;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Format v2 (PLAN section A "Format v2", A1, A2): the file says version 2, every v1 record is written exactly as v1
 * wrote it, extension records ({@code tag >= 0x20}: {@code varint dtUs | u8 tag | varint len | body}) round-trip byte
 * for byte, the reader takes versions 1 and 2 only, skips an extension tag it does not know and still stops at an
 * unknown old-style tag.
 */
public class RecordingFormatV2Test {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** One TRACK record as appended: time on the producer clock, tag, the bytes after the tag, the body proper. */
    static final class Rec {
        final long timeUs;
        final int tag;
        final byte[] appended;
        final byte[] body;

        Rec(long timeUs, int tag, byte[] appended, byte[] body) {
            this.timeUs = timeUs;
            this.tag = tag;
            this.appended = appended;
            this.body = body;
        }
    }

    private static Rec v1(long t, int tag, SelfTrackEncoder enc, int n) {
        byte[] b = Arrays.copyOf(enc.buffer(), n);
        return new Rec(t, tag, b, b);
    }

    private static Rec ext(long t, int tag, ExtCodec c, int n) {
        return new Rec(t, tag, Arrays.copyOf(c.buffer(), n), ExtCodecTest.body(c, n));
    }

    /** The v1 records of one segment, through the real encoder (every v1 TRACK tag). */
    static List<Rec> v1Records(long t0) {
        SelfTrackEncoder enc = new SelfTrackEncoder();
        List<Rec> out = new ArrayList<Rec>();
        long t = t0;
        out.add(v1(t, RewindFormat.TAG_GAME_START, enc, enc.gameStart(1_500_000L)));
        out.add(v1(t += 1_000, RewindFormat.TAG_TICK, enc,
                enc.tick(10.5, 64, -3.25, 90f, 12.5f, 91f, RewindFormat.FLAG_ON_GROUND, 2, -1, 0)));
        out.add(v1(t += 50_000, RewindFormat.TAG_TICK, enc,
                enc.tick(10.75, 64, -3.25, 90f, 12.5f, 91f, RewindFormat.FLAG_ON_GROUND, 2, 1, 0)));
        out.add(v1(t += 4_000, RewindFormat.TAG_CAMERA, enc, enc.camera(91.5f, 11f)));
        byte[] slot = RecordingFormatTest.itemSlotWithNbt();
        out.add(v1(t += 10, RewindFormat.TAG_EQUIP, enc, enc.equip(RewindFormat.EQUIP_HELD, slot, 0, slot.length)));
        out.add(v1(t += 10, RewindFormat.TAG_MARKER, enc, enc.marker(RewindFormat.MARKER_KEY)));
        out.add(v1(t += 10, RewindFormat.TAG_INFO, enc, enc.info(RewindFormat.INFO_MAP, "FakeMap")));
        out.add(v1(t += 50_000, RewindFormat.TAG_TICK, enc,
                enc.tick(11, 64, -3.25, 90f, 12.5f, 91f, RewindFormat.FLAG_ON_GROUND, 3, -1, 0)));
        out.add(v1(t += 10, RewindFormat.TAG_GAME_END, enc, enc.gameEnd(RewindFormat.GAME_END_WIN)));
        return out;
    }

    /** One record of each of the 17 extension tags, at {@code t0} and later, 1 us apart. */
    static List<Rec> extRecords(long t0) {
        ExtCodec c = new ExtCodec();
        List<Rec> out = new ArrayList<Rec>();
        long t = t0;
        out.add(ext(t, RewindFormat.TAG_TICK_HEAD, c, c.tickHead(70_000)));
        byte[] data = {0x01, 0x02, 0x03};
        out.add(ext(++t, RewindFormat.TAG_OUT, c, c.out(0x0A, data, 0, 0)));
        out.add(ext(++t, RewindFormat.TAG_DIG, c, c.dig(RewindFormat.DIG_HITTING, 123_456_789L, 1, 4)));
        out.add(ext(++t, RewindFormat.TAG_BLOCK, c, c.block(123_456_789L, 35 << 4, 0,
                RewindFormat.BLOCK_CAUSE_OWN_BREAK)));
        out.add(ext(++t, RewindFormat.TAG_SOUND, c, c.sound("dig.cloth", 1f, 2f, 3f, 1f, 0.8f)));
        out.add(ext(++t, RewindFormat.TAG_VIEW, c, c.view(1, RewindFormat.VIEW_F3, RewindFormat.SCREEN_INVENTORY)));
        out.add(ext(++t, RewindFormat.TAG_MOUSE, c, c.mouse(-40, 12)));
        out.add(ext(++t, RewindFormat.TAG_CHAT_INPUT, c, c.chatInput("/fake", 5)));
        out.add(ext(++t, RewindFormat.TAG_FOV, c, c.fov(77.5f)));
        out.add(ext(++t, RewindFormat.TAG_USE, c, c.use(72_000)));
        byte[] slot = RecordingFormatTest.itemSlotWithNbt();
        out.add(ext(++t, RewindFormat.TAG_INV, c, c.inv(RewindFormat.INV_CURSOR, slot, 0, slot.length)));
        c.beginSettings();
        c.settingFloat(RewindFormat.SETTING_FOV, 90f);
        c.settingString(RewindFormat.SETTING_GUI_ACCENT, "pale-blue");
        out.add(ext(++t, RewindFormat.TAG_SETTINGS, c, c.endSettings()));
        out.add(ext(++t, RewindFormat.TAG_IDENTITY, c, c.identity(1L, 2L, "FakeName", "FakeSkin", "")));
        out.add(ext(++t, RewindFormat.TAG_INPUT, c, c.input(RewindFormat.INPUT_W | RewindFormat.INPUT_LMB)));
        out.add(ext(++t, RewindFormat.TAG_HUD, c,
                c.beginHud(RewindFormat.HUD_FPS, RewindFormat.HUD_FLAG_ON_SCREEN).putVarInt(144).end()));
        out.add(ext(++t, RewindFormat.TAG_CONT, c, c.cont(2, 53, slot, 0, slot.length)));
        out.add(ext(++t, RewindFormat.TAG_TAP, c, c.tap(RewindFormat.TAP_LMB)));
        return out;
    }

    /** The v1 records with the 17 extension records spread between them (some before, some after each). */
    static List<Rec> interleaved(List<Rec> v1, List<Rec> ext) {
        List<Rec> out = new ArrayList<Rec>();
        int e = 0;
        for (int i = 0; i < v1.size(); i++) {
            Rec r = v1.get(i);
            // two extension records 4 us and 2 us before each v1 record (v1 records are at least 10 us apart)
            for (int k = 0; k < 2 && e < ext.size(); k++, e++) {
                Rec x = ext.get(e);
                out.add(new Rec(r.timeUs - 4 + 2 * k, x.tag, x.appended, x.body));
            }
            out.add(r);
        }
        long last = v1.get(v1.size() - 1).timeUs;
        while (e < ext.size()) {
            Rec x = ext.get(e++);
            out.add(new Rec(last += 3, x.tag, x.appended, x.body));
        }
        return out;
    }

    static RecordChunk trackChunk(List<Rec> recs, boolean skipExtensions) {
        RecordChunk.Producer p = new RecordChunk.Producer(64 * 1024, 2, 6, ORIGIN_US);
        assertTrue(p.startSegment(0, null, 0, 0));
        for (Rec r : recs) {
            if (skipExtensions && RewindFormat.isExtensionTag(r.tag)) {
                continue;
            }
            assertTrue(p.appendTrack(r.timeUs, r.tag, r.appended, 0, r.appended.length));
        }
        return handOff(p);
    }

    static RecordChunk netChunk() {
        RecordChunk.Producer net = new RecordChunk.Producer(16 * 1024, 2, 4, ORIGIN_US);
        assertTrue(net.startSegment(0, null, 0, 0));
        byte[] s01 = joinGame(0);
        assertTrue(net.appendNet(ORIGIN_US + 10, s01, 0, s01.length));
        byte[] chat = packet(0x02, "{\"text\":\"FakeChat\"}");
        assertTrue(net.appendNet(ORIGIN_US + 20, chat, 0, chat.length));
        return handOff(net);
    }

    // ------------------------------------------------------------------ A1

    @Test
    public void aV2FileWithEveryExtensionTagRoundTripsAndStrippingThemLeavesTheV1Bytes() throws IOException {
        List<Rec> v1 = v1Records(ORIGIN_US + 1_000);
        List<Rec> ext = extRecords(0);
        List<Rec> all = interleaved(v1, ext);
        RecordChunk mixed = trackChunk(all, false);
        RecordChunk v1Only = trackChunk(all, true);
        RecordChunk net = netChunk();

        FileBuilder fb = new FileBuilder();
        fb.block(RewindFormat.BLOCK_HEADER, 0, 0, BlockCodec.headerPayload(RewindFormat.SOURCE_CLIENT_SNAPSHOT,
                Collections.singletonList(joinGame(0))), true);
        fb.block(RewindFormat.BLOCK_NET, 0, net.startTimeUs() - ORIGIN_US, committed(net), true);
        fb.block(RewindFormat.BLOCK_TRACK, 0, mixed.startTimeUs() - ORIGIN_US, committed(mixed), true);
        fb.block(RewindFormat.BLOCK_INDEX, 0, 0, BlockCodec.indexPayload(
                Collections.<RecordingReader.IndexEntry>emptyList()), false);
        fb.block(RewindFormat.BLOCK_END, 0, 0, "{\"format\":2,\"complete\":true}".getBytes(StandardCharsets.UTF_8),
                false);
        byte[] file = fb.bytes();

        assertEquals("a new file names format 2", 2, RewindFormat.getU16(file, 8));
        RecordingReader r = read(file);
        assertEquals(2, r.header().formatVersion);
        List<RecordingReader.Block> blocks = readAll(r);
        assertEquals(RecordingReader.Stop.NONE, r.stop());
        assertTrue(r.complete());
        assertFalse(r.tornTail());
        assertEquals(5, blocks.size());

        // Every record, v1 and extension, with its tag, time and body; extension bodies without their length prefix.
        List<RecordingReader.TrackRecord> tr = RecordingReader.trackRecords(blocks.get(2));
        assertEquals(all.size(), tr.size());
        java.util.Set<Integer> extSeen = new java.util.TreeSet<Integer>();
        for (int i = 0; i < all.size(); i++) {
            assertEquals("tag #" + i, all.get(i).tag, tr.get(i).tag);
            assertEquals("time #" + i, all.get(i).timeUs - ORIGIN_US, tr.get(i).timeUs);
            assertArrayEquals("body #" + i, all.get(i).body, tr.get(i).body);
            if (RewindFormat.isExtensionTag(tr.get(i).tag)) {
                extSeen.add(tr.get(i).tag);
            }
        }
        assertEquals("all 17 extension tags present", 17, extSeen.size());

        // Decoded values survive the file (spot checks; ExtCodecTest pins each layout).
        for (RecordingReader.TrackRecord t : tr) {
            if (t.tag == RewindFormat.TAG_TICK_HEAD) {
                assertEquals(70_000, new ExtCodec.TickHead().read(t.body, 0, t.body.length).handled);
            } else if (t.tag == RewindFormat.TAG_USE) {
                assertEquals(72_000, new ExtCodec.Use().read(t.body, 0, t.body.length).count);
            } else if (t.tag == RewindFormat.TAG_IDENTITY) {
                assertEquals("FakeName", new ExtCodec.Identity().read(t.body, 0, t.body.length).name);
            }
        }

        // Stripping the extension records and re-timing what is left gives exactly the v1 encoding.
        RecordChunk.Producer strip = new RecordChunk.Producer(64 * 1024, 2, 6, ORIGIN_US);
        assertTrue(strip.startSegment(0, null, 0, 0));
        for (RecordingReader.TrackRecord t : tr) {
            if (!RewindFormat.isExtensionTag(t.tag)) {
                assertTrue(strip.appendTrack(t.timeUs + ORIGIN_US, t.tag, t.body, 0, t.body.length));
            }
        }
        RecordChunk stripped = handOff(strip);
        assertEquals(v1Only.startTimeUs(), stripped.startTimeUs());
        assertArrayEquals(committed(v1Only), committed(stripped));
        // ... and the records read from the stripped bytes are the v1 records.
        final List<Integer> strippedTags = new ArrayList<Integer>();
        byte[] sb = committed(stripped);
        RecordingReader.walkTrack(sb, 0, sb.length, stripped.startTimeUs(), new RecordingReader.TrackVisitor() {
            @Override
            public void track(long timeUs, int tag, byte[] buf, int off, int len) {
                strippedTags.add(tag);
            }
        });
        assertEquals(v1.size(), strippedTags.size());
        for (int i = 0; i < v1.size(); i++) {
            assertEquals(v1.get(i).tag, (int) strippedTags.get(i));
        }

        // Byte for byte: re-encoding each read block reproduces the file.
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

    // ------------------------------------------------------------------ A2: versions

    static byte[] withVersion(byte[] file, int version) {
        byte[] f = file.clone();
        RewindFormat.putShort(f, 8, version);
        CRC32 c = new CRC32();
        c.update(f, 0, 24);
        RewindFormat.putInt(f, 24, (int) c.getValue());
        return f;
    }

    @Test
    public void versionsOneAndTwoLoadAndThreeIsUnsupported() throws IOException {
        byte[] file = RecordingFormatTest.smallFile().bytes();
        for (int v : new int[]{1, 2}) {
            RecordingReader r = read(withVersion(file, v));
            assertEquals(v, r.header().formatVersion);
            assertFalse("v" + v, r.unsupportedVersion());
            assertEquals("v" + v, 3, readAll(r).size());
            assertTrue("v" + v, r.complete());
            assertTrue(RewindFormat.supportedVersion(v));
        }
        for (int v : new int[]{0, 3, 4, 0xFFFF}) {
            RecordingReader r = read(withVersion(file, v));
            assertTrue("v" + v, r.unsupportedVersion());
            assertEquals(RecordingReader.Stop.UNSUPPORTED_VERSION, r.stop());
            assertEquals(null, r.next());
            assertFalse(r.tornTail());
            assertFalse(RewindFormat.supportedVersion(v));
        }
        assertEquals(2, RewindFormat.FORMAT_VERSION);
        assertEquals(1, RewindFormat.FORMAT_VERSION_1);

        // The store recovers and prunes versions 1 and 2 only; anything else is left alone.
        File dir = tmp.newFolder("rewind");
        RecordingStore store = new RecordingStore(dir);
        String[] ids = {"20260101-120000-0001", "20260101-120000-0002", "20260101-120000-0003"};
        int[] versions = {1, 2, 3};
        for (int i = 0; i < 3; i++) {
            Files.write(new File(dir, ids[i] + ".cbr").toPath(), withVersion(file, versions[i]));
            assertEquals(versions[i], store.cbrVersion(ids[i]));
        }
        assertTrue(store.supportedCbr(ids[0]));
        assertTrue(store.supportedCbr(ids[1]));
        assertFalse(store.supportedCbr(ids[2]));
    }

    // ------------------------------------------------------------------ A2: unknown tags

    private static byte[] track(byte[]... records) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] r : records) {
            o.write(r, 0, r.length);
        }
        return o.toByteArray();
    }

    private static byte[] tickRecord() {
        SelfTrackEncoder enc = new SelfTrackEncoder();
        int n = enc.tick(1, 2, 3, 4f, 5f, 6f, 0, 0, -1, 0);
        byte[] r = new byte[2 + n];
        r[0] = 7; // dtUs
        r[1] = RewindFormat.TAG_TICK;
        System.arraycopy(enc.buffer(), 0, r, 2, n);
        return r;
    }

    private static byte[] cameraRecord() {
        byte[] r = new byte[2 + 8];
        r[0] = 3;
        r[1] = RewindFormat.TAG_CAMERA;
        return r;
    }

    @Test
    public void anUnknownExtensionTagWithALengthIsSkipped() throws IOException {
        for (int tag : new int[]{0x31, 0x7F, 0x80, 0xFF}) {
            byte[] unknown = {5, (byte) tag, 3, 'a', 'b', 'c'};
            byte[] payload = track(tickRecord(), unknown, cameraRecord());
            final List<Integer> tags = new ArrayList<Integer>();
            final List<String> bodies = new ArrayList<String>();
            long last = RecordingReader.walkTrack(payload, 0, payload.length, 1_000, new RecordingReader.TrackVisitor() {
                @Override
                public void track(long timeUs, int t, byte[] buf, int off, int len) {
                    tags.add(t);
                    bodies.add(new String(buf, off, len, StandardCharsets.ISO_8859_1));
                }
            });
            assertEquals(Arrays.asList(RewindFormat.TAG_TICK, tag, RewindFormat.TAG_CAMERA), tags);
            assertEquals("abc", bodies.get(1));
            assertEquals(1_000 + 7 + 5 + 3, last);
            assertEquals("length prefix + body", 4, RecordingReader.trackBodyLength(tag, unknown, 2, unknown.length));

            FileBuilder fb = new FileBuilder();
            fb.block(RewindFormat.BLOCK_TRACK, 0, 0, payload, false);
            RecordingReader r = read(fb.bytes());
            assertEquals(1, readAll(r).size());
            assertEquals(RecordingReader.Stop.NONE, r.stop());
            assertFalse(r.tornTail());
        }
        // An empty extension body is a record too.
        byte[] empty = track(new byte[]{0, 0x40, 0}, cameraRecord());
        assertEquals(2, RecordingReader.trackRecords(block(empty)).size());
    }

    private static RecordingReader.Block block(byte[] trackPayload) throws IOException {
        FileBuilder fb = new FileBuilder();
        fb.block(RewindFormat.BLOCK_TRACK, 0, 0, trackPayload, false);
        return readAll(read(fb.bytes())).get(0);
    }

    @Test
    public void anUnknownOldStyleTagStillStopsTheBlock() throws IOException {
        for (int tag : new int[]{0x00, 0x08, 0x10, 0x1F}) {
            byte[] bad = track(tickRecord(), new byte[]{0, (byte) tag, 3, 'a', 'b', 'c'});
            FileBuilder fb = new FileBuilder();
            byte[] ok = track(tickRecord(), cameraRecord());
            fb.block(RewindFormat.BLOCK_TRACK, 0, 0, ok, false);
            int at = fb.out.size();
            fb.block(RewindFormat.BLOCK_TRACK, 0, 0, bad, false);
            RecordingReader r = read(fb.bytes());
            assertEquals("tag " + tag, 1, readAll(r).size());
            assertEquals(RecordingReader.Stop.MALFORMED, r.stop());
            assertTrue(r.tornTail());
            assertEquals(at, r.validLength());
            try {
                RecordingReader.trackBodyLength(tag, bad, 0, bad.length);
                fail("tag " + tag + " sized");
            } catch (RewindFormat.FormatException expected) {
                // tags 0x08-0x1F stay invalid
            }
        }
    }

    // ------------------------------------------------------------------ A2: truncation

    @Test
    public void truncationAtEveryByteOfAnExtensionRecordKeepsThePrefix() throws IOException {
        ExtCodec c = new ExtCodec();
        int n = c.sound("random.fakeSound", 1f, 2f, 3f, 0.5f, 1.25f);
        byte[] sound = new byte[2 + n];
        sound[0] = 9;
        sound[1] = (byte) RewindFormat.TAG_SOUND;
        System.arraycopy(c.buffer(), 0, sound, 2, n);
        byte[] head = tickRecord();
        byte[] payload = track(head, sound, cameraRecord());
        int start = head.length;
        int end = start + sound.length;

        // Inside one payload: a cut record is a format error, never an index error; a cut at its start is whole.
        for (int cut = start; cut < end; cut++) {
            if (cut == start) {
                assertEquals(1, RecordingReader.trackRecords(block(Arrays.copyOf(payload, cut))).size());
                continue;
            }
            try {
                RecordingReader.walkTrack(payload, 0, cut, 0, new RecordingReader.TrackVisitor() {
                    @Override
                    public void track(long timeUs, int tag, byte[] buf, int off, int len) {
                    }
                });
                fail("cut " + cut + " accepted");
            } catch (RewindFormat.FormatException expected) {
                // the block holding it is malformed
            }
            FileBuilder fb = new FileBuilder();
            fb.block(RewindFormat.BLOCK_TRACK, 0, 0, track(tickRecord(), cameraRecord()), false);
            int at = fb.out.size();
            fb.block(RewindFormat.BLOCK_TRACK, 0, 0, Arrays.copyOf(payload, cut), false);
            RecordingReader r = read(fb.bytes());
            assertEquals("cut " + cut, 1, readAll(r).size());
            assertEquals(RecordingReader.Stop.MALFORMED, r.stop());
            assertEquals(at, r.validLength());
        }

        // A length that runs past the block is a format error too.
        byte[] long1 = track(head, new byte[]{0, 0x24, 100, 1, 2});
        try {
            RecordingReader.trackBodyLength(0x24, long1, head.length + 2, long1.length);
            fail("over-long extension sized");
        } catch (RewindFormat.FormatException expected) {
            // never an index error
        }
        FileBuilder over = new FileBuilder();
        over.block(RewindFormat.BLOCK_TRACK, 0, 0, long1, false);
        RecordingReader ro = read(over.bytes());
        assertEquals(0, readAll(ro).size());
        assertEquals(RecordingReader.Stop.MALFORMED, ro.stop());
        assertEquals(RewindFormat.FILE_HEADER_LENGTH, ro.validLength());

        // Torn files: the extension-bearing TRACK block cut at every byte keeps every whole block before it.
        FileBuilder fb = new FileBuilder();
        fb.block(RewindFormat.BLOCK_HEADER, 0, 0, BlockCodec.headerPayload(RewindFormat.SOURCE_CLIENT_SNAPSHOT,
                Collections.singletonList(joinGame(0))), false);
        int trackStart = fb.out.size();
        fb.block(RewindFormat.BLOCK_TRACK, 0, 0, payload, false);
        int trackEnd = fb.out.size();
        fb.block(RewindFormat.BLOCK_END, 0, 0, "{}".getBytes(StandardCharsets.UTF_8), false);
        byte[] file = fb.bytes();
        for (int cut = trackStart; cut < file.length; cut++) {
            RecordingReader r = read(Arrays.copyOf(file, cut));
            int whole = cut >= trackEnd ? 2 : 1;
            try {
                assertEquals("cut " + cut, whole, readAll(r).size());
            } catch (RuntimeException e) {
                throw new AssertionError("reader threw at cut " + cut, e);
            }
            assertEquals("cut " + cut, whole == 2 ? trackEnd : trackStart, r.validLength());
            assertFalse(r.complete());
            assertEquals("cut " + cut, cut != r.validLength(), r.tornTail());
        }
        assertEquals(3, readAll(read(file)).size());
    }
}
