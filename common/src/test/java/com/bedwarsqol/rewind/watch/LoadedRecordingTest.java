package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Loading keeps only the stored NET bytes, closes the file, decodes headers and the self track at once, and names the
 * reason a recording cannot be played.
 */
public class LoadedRecordingTest {

    private static final String ID = "20261117-120000-abcd";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File dir() throws Exception {
        return tmp.newFolder();
    }

    @Test
    public void realShapedFileLoadsWithItsSegmentsHeadersAndTrack() throws Exception {
        File dir = dir();
        RecordingFixture f = RecordingFixture.realShaped();
        File cbr = f.write(dir, ID);
        File json = RecordingFixture.writeMeta(dir, ID, RecordingFixture.realShapedMeta());
        LoadedRecording r = LoadedRecording.load(cbr, json);
        assertNull(r.problem());
        assertFalse(r.tornTail());
        assertEquals(3, r.segments().size());
        assertEquals(0, r.skippedSegments());
        assertEquals(RecordingFixture.SEG_JOIN, r.startSegment().number());
        assertEquals(RecordingFixture.SEG_JOIN, r.segments().get(0).number());
        assertEquals(RecordingFixture.SEG_NETHER, r.segments().get(1).number());
        assertEquals(RecordingFixture.SEG_GAME, r.segments().get(2).number());
        LoadedRecording.Segment s0 = r.segments().get(0);
        assertEquals(4, s0.header(RewindFormat.SOURCE_CLIENT_SNAPSHOT).size());
        assertEquals(RewindFormat.PACKET_JOIN_GAME, s0.header(RewindFormat.SOURCE_CLIENT_SNAPSHOT).get(0)[0]);
        assertEquals(1, s0.header(RewindFormat.SOURCE_CAPTURE_CARRY).size());
        assertEquals(RewindFormat.PACKET_PLAYER_LIST_HEADER_FOOTER,
                s0.header(RewindFormat.SOURCE_CAPTURE_CARRY).get(0)[0]);
        assertEquals(RewindFormat.PACKET_JOIN_GAME, s0.firstPacketId());
        assertEquals(RewindFormat.PACKET_RESPAWN, r.segments().get(1).firstPacketId());
        assertEquals(RewindFormat.PACKET_RESPAWN, r.segments().get(2).firstPacketId());
        assertEquals(2, s0.netRecordCount());
        assertEquals(300, r.segments().get(2).netRecordCount());
        assertEquals(3, r.segments().get(2).netBlockCount());
        assertEquals(RecordingFixture.T0 + 10, s0.firstNetTimeUs());
        assertEquals(RecordingFixture.T0 + 90, s0.lastNetTimeUs());
        assertNotNull(r.meta());
        assertEquals("BEDWARS_EIGHT_ONE", r.meta().mode);
        assertEquals(3, r.selfTrack().tickCount());
        assertEquals(1, r.selfTrack().cameraCount());
        assertEquals(1, r.selfTrack().equipCount());
        assertEquals(2, r.timeline().markers().size());
        // TRACK blocks were written out of time order: the self track is still in time order.
        SelfTrack.Segment game = r.selfTrack().segment(2);
        assertEquals(RecordingFixture.SEG_GAME, game.number());
        assertEquals(3, game.tickCount());
        assertEquals(RewindFormat.TICK_ALL, game.tickMask(0));
        assertTrue(game.tickTimeUs(0) < game.tickTimeUs(1) && game.tickTimeUs(1) < game.tickTimeUs(2));
        SelfTrack.Pose p = new SelfTrack.Pose();
        // The v1 tick table puts ticks 1 ms before each TICK: half way through the second tick's interval.
        TickTable t = r.timeline().ticks();
        assertEquals(TickTable.Kind.V1, t.kind());
        long at = RecordingFixture.TRACK_US + 74_000;
        long j = t.tickOf(at + 1) - 1;
        assertTrue(r.selfTrack().pose(t, j, t.partial(j, at), at, p));
        assertEquals(0.125, p.x, 1e-9);
        assertEquals(5f, p.yaw, 1e-6);
        r.close();
    }

    @Test
    public void onlyStoredNetBytesAreRetained() throws Exception {
        File dir = dir();
        RecordingFixture f = RecordingFixture.realShaped();
        File cbr = f.write(dir, ID);
        LoadedRecording r = LoadedRecording.load(cbr, null);
        assertNull(r.problem());
        assertEquals(f.storedNetBytes, r.retainedNetBytes());
        assertEquals(f.rawNetBytes, r.rawNetBytes());
        assertTrue("the fixture compresses", f.storedNetBytes < f.rawNetBytes);
        r.close();
    }

    @Test
    public void theFileIsClosedAfterLoadAndCanBeDeletedWhileInUse() throws Exception {
        File dir = dir();
        RecordingFixture f = RecordingFixture.realShaped();
        File cbr = f.write(dir, ID);
        File json = RecordingFixture.writeMeta(dir, ID, RecordingFixture.realShapedMeta());
        LoadedRecording.load(cbr, json).close(); // warm up class loading before counting descriptors
        int before = openDescriptors();
        LoadedRecording r = LoadedRecording.load(cbr, json);
        int after = openDescriptors();
        if (before >= 0) {
            assertEquals("descriptors open after load", before, after);
        }
        assertTrue(cbr.delete());
        assertTrue(json.delete());
        NetCursor c = r.newCursor();
        int n = 0;
        while (!c.atEnd()) {
            n++;
            c.advance();
        }
        assertEquals(f.netWritten.size(), n);
        c.reset();
        assertEquals(f.netWritten.get(0).timeUs, c.timeUs());
        r.close();
    }

    /** Open file descriptors of this process where the OS lists them (macOS, Linux), else -1. */
    private static int openDescriptors() {
        for (String p : new String[]{"/dev/fd", "/proc/self/fd"}) {
            String[] list = new File(p).list();
            if (list != null) {
                return list.length;
            }
        }
        return -1;
    }

    @Test
    public void tornTailPlaysItsCompletePrefix() throws Exception {
        File dir = dir();
        RecordingFixture f = RecordingFixture.realShaped();
        byte[] full = f.bytes();
        // Cut inside the END block: everything up to the INDEX is the complete prefix.
        byte[] torn = Arrays.copyOf(full, full.length - 20);
        File cbr = new File(dir, ID + ".cbr");
        java.nio.file.Files.write(cbr.toPath(), torn);
        LoadedRecording r = LoadedRecording.load(cbr, null);
        assertNull(r.problem());
        assertTrue(r.tornTail());
        assertEquals(f.netWritten.size(), r.netRecordCount());
        assertNull(LoadedRecording.quickCheck(cbr, null));

        // Cut inside the last NET block: the records before it still play.
        RecordingFixture g = new RecordingFixture();
        g.header(1, RewindFormat.SOURCE_CLIENT_SNAPSHOT, 100, RecordingFixture.joinGame(0));
        g.net(1, 100, new long[]{110, 200}, new byte[][]{RecordingFixture.joinGame(0),
                RecordingFixture.packet(0x02, 30, 1)});
        g.track(1, 150, RecordingFixture.gameStart(150, 0));
        int prefix = g.bytes().length;
        g.net(1, 200, new long[]{300, 400}, new byte[][]{RecordingFixture.packet(0x02, 30, 2),
                RecordingFixture.packet(0x02, 30, 3)});
        byte[] cut = Arrays.copyOf(g.bytes(), prefix + 40);
        File cbr2 = new File(dir, "20261117-120001-abcd.cbr");
        java.nio.file.Files.write(cbr2.toPath(), cut);
        LoadedRecording r2 = LoadedRecording.load(cbr2, null);
        assertNull(r2.problem());
        assertTrue(r2.tornTail());
        assertEquals(2, r2.netRecordCount());
    }

    @Test
    public void unknownVersionNoNetNoHeaderAndStartOutsideTheDataEachGiveTheirReason() throws Exception {
        File dir = dir();

        // Unknown format version: patch the header's version and its CRC (3: no build knows it yet).
        byte[] good = RecordingFixture.realShaped().bytes();
        byte[] v2 = good.clone();
        RewindFormat.putShort(v2, 8, 3);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(v2, 0, 24);
        RewindFormat.putInt(v2, 24, (int) crc.getValue());
        File fv2 = new File(dir, "v2.cbr");
        java.nio.file.Files.write(fv2.toPath(), v2);
        assertEquals(LoadedRecording.Problem.UNSUPPORTED_VERSION, LoadedRecording.load(fv2, null).problem());
        assertEquals(LoadedRecording.Problem.UNSUPPORTED_VERSION, LoadedRecording.quickCheck(fv2, null));

        // Not a recording at all.
        File junk = new File(dir, "junk.cbr");
        java.nio.file.Files.write(junk.toPath(), new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30});
        assertEquals(LoadedRecording.Problem.UNREADABLE, LoadedRecording.load(junk, null).problem());
        assertEquals(LoadedRecording.Problem.UNREADABLE, LoadedRecording.quickCheck(junk, null));
        assertEquals(LoadedRecording.Problem.UNREADABLE,
                LoadedRecording.load(new File(dir, "missing.cbr"), null).problem());

        // Headers but no NET record.
        RecordingFixture noNet = new RecordingFixture();
        noNet.header(1, RewindFormat.SOURCE_CLIENT_SNAPSHOT, 100, RecordingFixture.joinGame(0));
        noNet.track(1, 150, RecordingFixture.gameStart(150, 0));
        File fNoNet = noNet.write(dir, "nonet");
        assertEquals(LoadedRecording.Problem.NO_NET, LoadedRecording.load(fNoNet, null).problem());
        assertEquals(LoadedRecording.Problem.NO_NET, LoadedRecording.quickCheck(fNoNet, null));

        // NET records but no source-0 header in any segment.
        RecordingFixture noHeader = new RecordingFixture();
        noHeader.header(1, RewindFormat.SOURCE_CAPTURE_CARRY, 100, RecordingFixture.packet(0x47, 10, 1));
        noHeader.net(1, 100, new long[]{110, 200}, new byte[][]{RecordingFixture.joinGame(0),
                RecordingFixture.packet(0x02, 30, 1)});
        File fNoHeader = noHeader.write(dir, "noheader");
        assertEquals(LoadedRecording.Problem.NO_HEADER, LoadedRecording.load(fNoHeader, null).problem());
        assertEquals(LoadedRecording.Problem.NO_HEADER, LoadedRecording.quickCheck(fNoHeader, null));

        // The game starts after the last NET record.
        RecordingFixture late = new RecordingFixture();
        late.header(1, RewindFormat.SOURCE_CLIENT_SNAPSHOT, 100, RecordingFixture.joinGame(0));
        late.net(1, 100, new long[]{110, 200}, new byte[][]{RecordingFixture.joinGame(0),
                RecordingFixture.packet(0x02, 30, 1)});
        late.track(1, 150, RecordingFixture.gameStart(5_000_000, 0));
        File fLate = late.write(dir, "late");
        assertEquals(LoadedRecording.Problem.START_OUTSIDE_DATA, LoadedRecording.load(fLate, null).problem());
        assertEquals(LoadedRecording.Problem.START_OUTSIDE_DATA, LoadedRecording.quickCheck(fLate, null));

        // The game starts before the start segment's first NET record.
        RecordingFixture early = new RecordingFixture();
        early.header(1, RewindFormat.SOURCE_CLIENT_SNAPSHOT, 1_000, RecordingFixture.joinGame(0));
        early.net(1, 1_000, new long[]{1_010, 2_000}, new byte[][]{RecordingFixture.joinGame(0),
                RecordingFixture.packet(0x02, 30, 1)});
        early.track(1, 1_500, RecordingFixture.gameStart(1_500, 1_000));
        File fEarly = early.write(dir, "early");
        assertEquals(LoadedRecording.Problem.START_OUTSIDE_DATA, LoadedRecording.load(fEarly, null).problem());

        // And the good file passes the quick check.
        File fGood = new File(dir, ID + ".cbr");
        java.nio.file.Files.write(fGood.toPath(), good);
        assertNull(LoadedRecording.quickCheck(fGood, null));
    }

    @Test
    public void metaComesFromTheJsonElseFromTheEndBlock() throws Exception {
        File dir = dir();
        File cbr = RecordingFixture.realShaped().write(dir, ID);
        RecordingMeta disk = RecordingFixture.realShapedMeta();
        disk.map = "FromJson";
        File json = RecordingFixture.writeMeta(dir, ID, disk);
        assertEquals("FromJson", LoadedRecording.load(cbr, json).meta().map);
        LoadedRecording fromEnd = LoadedRecording.load(cbr, new File(dir, "absent.json"));
        assertNotNull(fromEnd.meta());
        assertNull(fromEnd.meta().map);
        assertEquals(RecordingFixture.END_US, fromEnd.meta().endUs);
    }

    // ---------------- Phase 10: format v2 extension records ----------------

    private static void assertBody(String what, byte[] want, ExtRecords r, int i) {
        assertEquals(what + " length", want.length, r.length(i));
        for (int k = 0; k < want.length; k++) {
            assertEquals(what + " byte " + k, want[k], r.buffer()[r.offset(i) + k]);
        }
    }

    @Test
    public void aV2RecordingWrittenByTheWriterLoadsEveryExtensionRecordInOrder() throws Exception {
        File dir = new File(dir(), "rewind");
        RecordingFixture.WriterRun w = new RecordingFixture.WriterRun(dir, 10_000_000L);
        w.connect(512);
        w.advance(1_000);
        w.boundary(0, RecordingFixture.joinGame(0), 0);
        int seed = 0;
        // Segment 0: one round before the game opens (pending, flushed on OPEN), two after; one round with every
        // record at one stamp. NET packets and writer steps between them split everything over many blocks.
        for (int round = 0; round < 3; round++) {
            if (round == 1) {
                w.advance(500_000);
                w.open();
            }
            for (RecordingFixture.Track t : RecordingFixture.everyExtTag(0, 0, seed++)) {
                w.advance(round == 2 ? 0 : 3_000);
                w.track(t.tag, t.body);
                if (round != 2) {
                    w.net(RecordingFixture.packet(0x20 + seed % 8, 40 + seed, seed));
                }
            }
            w.step();
        }
        // Segment 1 (S07): two more rounds.
        w.advance(3_000);
        w.boundary(1, RecordingFixture.respawn(-1), -1);
        for (int round = 0; round < 2; round++) {
            for (RecordingFixture.Track t : RecordingFixture.everyExtTag(0, 0, seed++)) {
                w.advance(2_000);
                w.track(t.tag, t.body);
                w.net(RecordingFixture.packet(0x30 + seed % 3, 30 + seed, seed));
            }
            w.step();
        }
        File cbr = w.close();

        LoadedRecording r = LoadedRecording.load(cbr, null);
        assertNull(r.problem());
        assertFalse(r.tornTail());
        assertEquals(2, r.formatVersion());
        assertEquals(0, r.extUnknown());
        assertEquals(0, r.extMalformed());
        assertEquals(2, r.segments().size());

        // Expected: every extension record appended, in append order (times never go back, so a stable time sort is
        // append order), with its segment as a position in segments().
        java.util.List<RecordingFixture.WriterRun.Sent> want = new java.util.ArrayList<RecordingFixture.WriterRun.Sent>();
        for (RecordingFixture.WriterRun.Sent s : w.trackSent) {
            if (RewindFormat.isExtensionTag(s.tag)) {
                want.add(s);
            }
        }
        assertEquals(5 * RewindFormat.EXT_TAGS.length, want.size());
        ExtRecords ext = r.ext();
        assertEquals("every extension record reached", want.size(), ext.size());
        for (int i = 0; i < want.size(); i++) {
            RecordingFixture.WriterRun.Sent s = want.get(i);
            String what = "record #" + i + " " + RewindFormat.extName(s.tag);
            assertEquals(what, s.tag, ext.tag(i));
            assertEquals(what, s.timeUs, ext.timeUs(i));
            assertEquals(what, s.segment, r.segments().get(ext.segmentIndex(i)).number());
            assertBody(what, RecordingFixture.extBody(new RecordingFixture.Track(s.timeUs, s.tag, s.appended)),
                    ext, i);
        }

        // The per-tag track holds the same records per segment, in the same order.
        ExtTrack x = r.extTrack();
        assertSame(ext, x.records());
        for (int tag : RewindFormat.EXT_TAGS) {
            String name = RewindFormat.extName(tag);
            assertTrue(name, r.has(tag));
            assertTrue(name, x.has(tag));
            for (int seg = 0; seg < 2; seg++) {
                int j = 0;
                for (int i = 0; i < want.size(); i++) {
                    if (ext.tag(i) == tag && ext.segmentIndex(i) == seg) {
                        assertEquals(name + " seg " + seg + " #" + j, i, x.record(tag, seg, j++));
                    }
                }
                assertEquals(name + " seg " + seg, seg == 0 ? 3 : 2, j);
                assertEquals(name + " seg " + seg, j, x.count(tag, seg));
            }
        }
        // TICK_HEAD records make the v2 tick table.
        assertEquals(TickTable.Kind.V2, r.timeline().ticks().kind());
        assertEquals(5, r.timeline().ticks().entries());
        r.close();
    }

    @Test
    public void extensionRecordsAreInTimeOrderWhateverTheOrderOfTheirTrackBlocks() throws Exception {
        java.util.List<RecordingFixture.Track> a = RecordingFixture.everyExtTag(1_000_000L, 1_000, 1);
        java.util.List<RecordingFixture.Track> b = RecordingFixture.everyExtTag(2_000_000L, 1_000, 2);
        RecordingFixture f = new RecordingFixture(2);
        f.header(1, RewindFormat.SOURCE_CLIENT_SNAPSHOT, 100, RecordingFixture.joinGame(0));
        f.net(1, 100, new long[]{110, 1_500_000L}, new byte[][]{RecordingFixture.joinGame(0),
                RecordingFixture.packet(0x02, 30, 1)});
        f.track(1, 2_000_000L, b.toArray(new RecordingFixture.Track[0])); // the later block first
        java.util.List<RecordingFixture.Track> first = new java.util.ArrayList<RecordingFixture.Track>();
        first.add(RecordingFixture.gameStart(150, 0));
        first.addAll(a);
        f.track(1, 150, first.toArray(new RecordingFixture.Track[0]));
        f.net(1, 1_500_000L, new long[]{3_000_000L}, new byte[][]{RecordingFixture.packet(0x02, 30, 2)});
        LoadedRecording r = LoadedRecording.load(f.write(dir(), "ooo"), null);
        assertNull(r.problem());
        assertEquals(2, r.formatVersion());
        ExtRecords ext = r.ext();
        assertEquals(a.size() + b.size(), ext.size());
        for (int i = 0; i < ext.size(); i++) {
            RecordingFixture.Track t = i < a.size() ? a.get(i) : b.get(i - a.size());
            assertEquals("#" + i, t.tag, ext.tag(i));
            assertEquals("#" + i, t.timeUs, ext.timeUs(i));
            assertEquals("#" + i, 0, ext.segmentIndex(i));
            assertBody("#" + i, RecordingFixture.extBody(t), ext, i);
        }
        for (int tag : RewindFormat.EXT_TAGS) {
            assertEquals(2, r.extTrack().count(tag, 0));
            assertTrue(r.extTrack().timeUs(tag, 0, 0) < 2_000_000L);
            assertTrue(r.extTrack().timeUs(tag, 0, 1) >= 2_000_000L);
        }
    }

    @Test
    public void unknownAndMalformedExtensionRecordsAreSkippedAndCountedAndTheRestLoads() throws Exception {
        java.util.List<RecordingFixture.Track> good = RecordingFixture.everyExtTag(1_000_000L, 1_000, 3);
        java.util.List<RecordingFixture.Track> all = new java.util.ArrayList<RecordingFixture.Track>();
        all.add(RecordingFixture.gameStart(150, 0));
        for (int i = 0; i < good.size(); i++) {
            all.add(good.get(i));
            long t = good.get(i).timeUs + 1;
            switch (i) {
                case 2:
                    all.add(RecordingFixture.extRaw(t, 0x3F, new byte[]{1, 2, 3})); // a tag no build knows yet
                    break;
                case 5:
                    all.add(RecordingFixture.extRaw(t, RewindFormat.TAG_INV, new byte[]{99, (byte) 0xFF, (byte) 0xFF}));
                    break;
                case 8:
                    all.add(RecordingFixture.extRaw(t, RewindFormat.TAG_HUD, new byte[]{(byte) 200, 1, 5}));
                    break;
                case 11:
                    all.add(RecordingFixture.extRaw(t, RewindFormat.TAG_SETTINGS, new byte[]{1, 1, 9, 0}));
                    break;
                default:
                    break;
            }
        }
        RecordingFixture f = new RecordingFixture(2);
        f.header(1, RewindFormat.SOURCE_CLIENT_SNAPSHOT, 100, RecordingFixture.joinGame(0));
        f.net(1, 100, new long[]{110, 3_000_000L}, new byte[][]{RecordingFixture.joinGame(0),
                RecordingFixture.packet(0x02, 30, 1)});
        f.track(1, 150, all.toArray(new RecordingFixture.Track[0]));
        LoadedRecording r = LoadedRecording.load(f.write(dir(), "bad"), null);
        assertNull(r.problem());
        assertFalse(r.tornTail());
        assertEquals(1, r.extUnknown());
        assertEquals(3, r.extMalformed());
        assertFalse(r.has(0x3F));
        ExtRecords ext = r.ext();
        assertEquals(good.size(), ext.size());
        for (int i = 0; i < good.size(); i++) {
            assertEquals("#" + i, good.get(i).tag, ext.tag(i));
            assertEquals("#" + i, good.get(i).timeUs, ext.timeUs(i));
            assertBody("#" + i, RecordingFixture.extBody(good.get(i)), ext, i);
        }
        assertEquals("the skipped INV is not a slot state", 1, r.extTrack().count(RewindFormat.TAG_INV, 0));
        assertEquals(1, r.extTrack().count(RewindFormat.TAG_HUD, 0));
        assertEquals(1, r.extTrack().count(RewindFormat.TAG_SETTINGS, 0));
    }

    @Test
    public void aTornV2FileKeepsTheExtensionRecordsOfItsCompletePrefix() throws Exception {
        java.util.List<RecordingFixture.Track> a = RecordingFixture.everyExtTag(1_000_000L, 1_000, 4);
        java.util.List<RecordingFixture.Track> b = RecordingFixture.everyExtTag(2_000_000L, 1_000, 5);
        RecordingFixture f = new RecordingFixture(2);
        f.header(1, RewindFormat.SOURCE_CLIENT_SNAPSHOT, 100, RecordingFixture.joinGame(0));
        f.net(1, 100, new long[]{110, 3_000_000L}, new byte[][]{RecordingFixture.joinGame(0),
                RecordingFixture.packet(0x02, 30, 1)});
        java.util.List<RecordingFixture.Track> first = new java.util.ArrayList<RecordingFixture.Track>();
        first.add(RecordingFixture.gameStart(150, 0));
        first.addAll(a);
        f.track(1, 150, first.toArray(new RecordingFixture.Track[0]));
        int prefix = f.bytes().length;
        f.track(1, 2_000_000L, b.toArray(new RecordingFixture.Track[0]));
        byte[] cut = Arrays.copyOf(f.bytes(), prefix + (f.bytes().length - prefix) / 2);
        File cbr = new File(dir(), "torn.cbr");
        java.nio.file.Files.write(cbr.toPath(), cut);
        LoadedRecording r = LoadedRecording.load(cbr, null);
        assertNull(r.problem());
        assertTrue(r.tornTail());
        assertEquals(2, r.formatVersion());
        assertEquals(0, r.extMalformed());
        assertEquals("the cut block's records are gone, the prefix's all load", a.size(), r.ext().size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).timeUs, r.ext().timeUs(i));
            assertBody("#" + i, RecordingFixture.extBody(a.get(i)), r.ext(), i);
        }
        assertEquals(TickTable.Kind.V2, r.timeline().ticks().kind());
    }

    /** Hashes everything the feeder hands over, including any extension record (there must be none for v1). */
    private static final class AllSink implements PacketSink {
        long h = 17;
        int local;
        int timed;

        private void mix(long v) {
            h = h * 1_000_003L ^ v;
        }

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            mix(packetId);
            mix(len);
            return true;
        }

        @Override
        public void boundary(int packetId, byte[] buf, int off, int len) {
            mix(-packetId);
        }

        @Override
        public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
            mix(-1000 - packetId);
            return true;
        }

        @Override
        public void local(int tag, long timeUs, byte[] buf, int off, int len) {
            local++;
            mix(-2000 - tag);
        }

        @Override
        public void timed(int tag, long timeUs, byte[] buf, int off, int len) {
            timed++;
        }

        @Override
        public void sample(long tick, long timeUs) {
            mix(tick);
            mix(timeUs);
        }
    }

    private static long feedAll(LoadedRecording r, AllSink sink) {
        TickFeeder feeder = new TickFeeder(r);
        feeder.feedHeaders(sink);
        for (long k = 0; k <= r.timeline().endTick(); k++) {
            feeder.feedForTick(k, sink);
        }
        sink.timed += r.ext().timedCount(); // what the clock would dispatch
        return sink.h;
    }

    @Test
    public void aV1RecordingTakesNoV2Path() throws Exception {
        File dir = dir();
        ExtCodec c = new ExtCodec();
        long t = RecordingFixture.TRACK_US + 41_000_000L;
        // A v1 header with extension-tagged records in its TRACK (no shipped writer makes this; it must be ignored).
        File plain = RecordingFixture.realShaped(1).write(dir, "v1plain");
        File odd = RecordingFixture.realShaped(1,
                RecordingFixture.ext(t, RewindFormat.TAG_TICK_HEAD, c, c.tickHead(3)),
                RecordingFixture.ext(t + 10, RewindFormat.TAG_VIEW, c, c.view(1, 0, 0)),
                RecordingFixture.ext(t + 20, RewindFormat.TAG_DIG, c, c.dig(0, 5L, 1, 2)),
                RecordingFixture.extRaw(t + 30, 0x3F, new byte[]{1})).write(dir, "v1odd");
        for (File f : new File[]{plain, odd}) {
            LoadedRecording r = LoadedRecording.load(f, null);
            String what = f.getName();
            assertNull(what, r.problem());
            assertEquals(what, 1, r.formatVersion());
            assertSame(what, ExtRecords.EMPTY, r.ext());
            assertSame(what, ExtTrack.EMPTY, r.extTrack());
            assertEquals(what, 0, r.extUnknown());
            assertEquals(what, 0, r.extMalformed());
            for (int tag = 0; tag < 256; tag++) {
                assertFalse(what + " tag " + tag, r.has(tag));
            }
            assertEquals(what, TickTable.Kind.V1, r.timeline().ticks().kind());
        }
        AllSink a = new AllSink();
        AllSink b = new AllSink();
        assertEquals("the v1 file feeds the same trace with or without stray records",
                feedAll(LoadedRecording.load(plain, null), a), feedAll(LoadedRecording.load(odd, null), b));
        assertEquals(0, a.local + a.timed + b.local + b.timed);
    }

    @Test
    public void inflationIsOnDemandAndStillCorrectAfterManyBlockSwitches() throws Exception {
        // Many small NET blocks: walking two cursors in lock step forces the two-block cache to switch blocks.
        RecordingFixture f = new RecordingFixture();
        f.header(1, RewindFormat.SOURCE_CLIENT_SNAPSHOT, 100, RecordingFixture.joinGame(0));
        long t = 100;
        f.net(1, t, new long[]{t + 1}, new byte[][]{RecordingFixture.joinGame(0)});
        t += 1;
        for (int b = 0; b < 20; b++) {
            long[] times = new long[5];
            byte[][] packets = new byte[5][];
            for (int i = 0; i < 5; i++) {
                times[i] = t + 1000 * (i + 1);
                packets[i] = RecordingFixture.packet(0x02, 50 + b, b * 5 + i);
            }
            f.net(1, t, times, packets);
            t = times[4];
        }
        f.track(1, 100, RecordingFixture.gameStart(101, 0));
        File cbr = f.write(dir(), "many");
        LoadedRecording r = LoadedRecording.load(cbr, null);
        assertNull(r.problem());
        assertEquals(21, r.netBlockCount());
        NetCursor a = r.newCursor();
        NetCursor b = r.newCursor();
        NetCursor c = r.newCursor();
        for (int i = 0; i < 40; i++) {
            c.advance();
        }
        int i = 0;
        while (!a.atEnd()) {
            RecordingFixture.Net w = f.netWritten.get(i);
            assertEquals(w.timeUs, a.timeUs());
            assertEquals(w.timeUs, b.timeUs());
            assertEquals(w.bytes[w.bytes.length - 1], a.buffer()[a.offset() + a.length() - 1]);
            assertEquals(w.bytes[w.bytes.length - 1], b.buffer()[b.offset() + b.length() - 1]);
            a.advance();
            b.advance();
            if (!c.atEnd()) {
                c.advance();
            }
            i++;
        }
        assertEquals(101, i);
    }
}
