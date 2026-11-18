package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Loads every real recording in the folder named by {@code COBBLIFY_REWIND_SAMPLES} (skipped when it is unset) and
 * checks the loader's invariants on real data. Recordings are player data: the output is one counts-only line per
 * file, numbered by position, never the file id, a name, chat or a map.
 */
public class RealRecordingDryRunTest {

    static File[] samples() {
        String dir = System.getenv("COBBLIFY_REWIND_SAMPLES");
        Assume.assumeTrue("COBBLIFY_REWIND_SAMPLES unset", dir != null && !dir.trim().isEmpty());
        File[] files = new File(dir.trim()).listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.endsWith(".cbr");
            }
        });
        Assume.assumeTrue("no .cbr files", files != null && files.length > 0);
        Arrays.sort(files);
        return files;
    }

    static File jsonFor(File cbr) {
        String name = cbr.getName();
        return new File(cbr.getParentFile(), name.substring(0, name.length() - 4) + ".json");
    }

    @Test
    public void everyRealRecordingLoadsAndKeepsTheLoaderInvariants() {
        File[] files = samples();
        for (int n = 0; n < files.length; n++) {
            String label = "file #" + (n + 1);
            long t0 = System.nanoTime();
            LoadedRecording r = LoadedRecording.load(files[n], jsonFor(files[n]));
            long loadMs = (System.nanoTime() - t0) / 1_000_000L;
            assertNull(label + " problem", r.problem());
            assertNull(label + " quick check", LoadedRecording.quickCheck(files[n], jsonFor(files[n])));

            for (LoadedRecording.Segment s : r.segments()) {
                if (s.netRecordCount() > 0) {
                    int id = s.firstPacketId();
                    assertTrue(label + " segment starts with S01/S07",
                            id == RewindFormat.PACKET_JOIN_GAME || id == RewindFormat.PACKET_RESPAWN);
                }
            }

            NetCursor c = r.newCursor();
            long records = 0;
            long backInSegment = 0;
            long backAcrossSegments = 0;
            long last = Long.MIN_VALUE;
            int seg = Integer.MIN_VALUE;
            while (!c.atEnd()) {
                if (c.segment() == seg) {
                    if (c.timeUs() < last) {
                        backInSegment++;
                    }
                } else {
                    if (c.timeUs() < last) {
                        backAcrossSegments++;
                    }
                    int id = c.packetId();
                    assertTrue(label + " boundary first", c.firstOfSegment()
                            && (id == RewindFormat.PACKET_JOIN_GAME || id == RewindFormat.PACKET_RESPAWN));
                    seg = c.segment();
                }
                last = c.timeUs();
                records++;
                c.advance();
            }
            assertEquals(label + " NET times go back inside a segment", 0, backInSegment);
            assertEquals(label + " cursor count", r.netRecordCount(), records);

            SelfTrack track = r.selfTrack();
            for (int i = 0; i < track.segmentCount(); i++) {
                SelfTrack.Segment s = track.segment(i);
                if (s.tickCount() > 0) {
                    assertEquals(label + " first TICK is full", RewindFormat.TICK_ALL, s.tickMask(0));
                }
            }
            assertTrue(label + " has TICKs", track.tickCount() > 0);

            Timeline t = r.timeline();
            assertNotNull(label + " timeline", t);
            assertTrue(label + " start inside the data", t.startInsideData());
            assertTrue(label + " end after start", t.endUs() >= t.gameStartUs());
            for (Timeline.Marker m : t.markers()) {
                assertTrue(label + " marker inside the game", m.timeUs >= t.gameStartUs() && m.timeUs <= t.endUs());
            }
            assertEquals(label + " every recorded marker lies inside the game", r.trackMarkerCount(),
                    t.markers().size());

            System.out.println("rewind dry run " + label + ": segments=" + r.segments().size()
                    + " skipped=" + r.skippedSegments() + " torn=" + r.tornTail()
                    + " netBlocks=" + r.netBlockCount() + " netRecords=" + records
                    + " storedNetKB=" + r.retainedNetBytes() / 1024 + " rawNetKB=" + r.rawNetBytes() / 1024
                    + " backAcrossSegments=" + backAcrossSegments
                    + " ticks=" + track.tickCount() + " camera=" + track.cameraCount()
                    + " equip=" + track.equipCount() + " markers=" + t.markers().size()
                    + " startTick=" + t.startTick() + " endTick=" + t.endTick()
                    + " lastNetTick=" + t.tickOf(t.lastNetUs()) + " loadMs=" + loadMs);
            r.close();
        }
    }
}
