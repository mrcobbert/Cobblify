package com.bedwarsqol.rewind.watch;

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
        assertTrue(r.selfTrack().pose(RecordingFixture.TRACK_US + 75_000, p));
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
