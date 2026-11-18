package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The NET cursor returns every stored NET record byte for byte, in time order, segment by segment. */
public class NetCursorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private LoadedRecording loadRealShaped(RecordingFixture f) throws Exception {
        File dir = tmp.newFolder();
        String id = "20261117-120000-abcd";
        File cbr = f.write(dir, id);
        File json = RecordingFixture.writeMeta(dir, id, RecordingFixture.realShapedMeta());
        return LoadedRecording.load(cbr, json);
    }

    @Test
    public void everyNetRecordComesBackByteForByteInTimeOrderSegmentBySegment() throws Exception {
        RecordingFixture f = RecordingFixture.realShaped();
        LoadedRecording r = loadRealShaped(f);
        assertNull(r.problem());
        NetCursor c = r.newCursor();
        List<RecordingFixture.Net> want = f.netWritten;
        int i = 0;
        long lastTime = Long.MIN_VALUE;
        int lastSegment = Integer.MIN_VALUE;
        int boundaries = 0;
        while (!c.atEnd()) {
            RecordingFixture.Net w = want.get(i);
            assertEquals("time of record " + i, w.timeUs, c.timeUs());
            assertEquals("segment of record " + i, w.segment, c.segment());
            byte[] got = Arrays.copyOfRange(c.buffer(), c.offset(), c.offset() + c.length());
            assertArrayEquals("bytes of record " + i, w.bytes, got);
            assertEquals(w.bytes[0], c.packetId());
            assertEquals(i, c.position());
            assertTrue("time order", c.timeUs() >= lastTime);
            if (c.segment() != lastSegment) {
                assertTrue("segments in order", c.segment() > lastSegment);
                assertTrue(c.firstOfSegment());
                int id = c.packetId();
                assertTrue("boundary packet first", id == RewindFormat.PACKET_JOIN_GAME
                        || id == RewindFormat.PACKET_RESPAWN);
                boundaries++;
            } else {
                assertFalse(c.firstOfSegment());
            }
            lastTime = c.timeUs();
            lastSegment = c.segment();
            c.advance();
            i++;
        }
        assertEquals(want.size(), i);
        assertEquals(3, boundaries);
        assertEquals(want.size(), r.netRecordCount());
        c.advance(); // advancing at the end stays at the end
        assertTrue(c.atEnd());
    }

    @Test
    public void resetReturnsToTheFirstRecord() throws Exception {
        RecordingFixture f = RecordingFixture.realShaped();
        LoadedRecording r = loadRealShaped(f);
        NetCursor c = r.newCursor();
        for (int i = 0; i < 150; i++) {
            c.advance();
        }
        assertEquals(150, c.position());
        c.reset();
        assertEquals(0, c.position());
        RecordingFixture.Net first = f.netWritten.get(0);
        assertEquals(first.timeUs, c.timeUs());
        assertEquals(first.segment, c.segment());
        assertTrue(c.firstOfSegment());
        assertArrayEquals(first.bytes, Arrays.copyOfRange(c.buffer(), c.offset(), c.offset() + c.length()));
        // A full second pass after the reset sees the same records.
        List<Long> times = new ArrayList<Long>();
        while (!c.atEnd()) {
            times.add(c.timeUs());
            c.advance();
        }
        assertEquals(f.netWritten.size(), times.size());
        assertEquals(f.netWritten.get(f.netWritten.size() - 1).timeUs, (long) times.get(times.size() - 1));
    }

    @Test
    public void twoCursorsOnOneRecordingAreIndependent() throws Exception {
        LoadedRecording r = loadRealShaped(RecordingFixture.realShaped());
        NetCursor a = r.newCursor();
        NetCursor b = r.newCursor();
        for (int i = 0; i < 250; i++) {
            a.advance();
        }
        int n = 0;
        while (!b.atEnd()) {
            b.advance();
            n++;
        }
        assertEquals(r.netRecordCount(), n);
        assertEquals(250, a.position());
        long t = a.timeUs();
        a.advance();
        assertTrue(a.timeUs() >= t);
    }
}
