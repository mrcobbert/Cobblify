package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Origin, game start, end, markers and the tick grid of a recording. */
public class TimelineTest {

    private static final long ORIGIN = 10_000_000L;
    private static final long LAST = ORIGIN + 400_000_000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static RecordingMeta meta(long start, long end) {
        RecordingMeta m = new RecordingMeta();
        m.gameStartUs = start;
        m.endUs = end;
        m.lengthUs = end - start;
        return m;
    }

    private static List<Timeline.Marker> none() {
        return Collections.emptyList();
    }

    @Test
    public void bedWarsStartIsTheGameStartRecordTime() {
        long rec = ORIGIN + 30_000_000L;
        Timeline t = Timeline.build(ORIGIN, LAST, rec, 0, none(), meta(rec + 5, LAST));
        assertEquals(ORIGIN, t.originUs());
        assertEquals(rec, t.gameStartUs());
        assertEquals(LAST, t.endUs());
        assertEquals(LAST - rec, t.lengthUs());
        assertTrue(t.startInsideData());
    }

    @Test
    public void duelsStartIsTheRecordTimeMinusBackUs() {
        long rec = ORIGIN + 60_000_123L;
        Timeline t = Timeline.build(ORIGIN, LAST, rec, 60_000_000L, none(), null);
        assertEquals(ORIGIN + 123, t.gameStartUs());
        assertTrue(t.startInsideData());
    }

    @Test
    public void missingGameStartFallsBackToTheJson() {
        Timeline t = Timeline.build(ORIGIN, LAST, Timeline.NONE, 0, none(), meta(ORIGIN + 7_000_000L, LAST - 1));
        assertEquals(ORIGIN + 7_000_000L, t.gameStartUs());
        assertEquals(LAST - 1, t.endUs());
        // With neither, the game starts at the origin and ends at the last record.
        Timeline bare = Timeline.build(ORIGIN, LAST, Timeline.NONE, 0, none(), null);
        assertEquals(ORIGIN, bare.gameStartUs());
        assertEquals(LAST, bare.endUs());
    }

    @Test
    public void endComesFromTheJsonElseTheLastRecord() {
        long rec = ORIGIN + 1_000_000L;
        assertEquals(LAST + 900_000L, Timeline.build(ORIGIN, LAST, rec, 0, none(),
                meta(rec, LAST + 900_000L)).endUs());
        RecordingMeta noEnd = meta(rec, 0);
        assertEquals(LAST, Timeline.build(ORIGIN, LAST, rec, 0, none(), noEnd).endUs());
    }

    @Test
    public void markersOutsideStartAndEndAreDropped() {
        long start = ORIGIN + 30_000_000L;
        long end = ORIGIN + 300_000_000L;
        List<Timeline.Marker> track = Arrays.asList(
                new Timeline.Marker(start - 1, RewindFormat.MARKER_KEY),
                new Timeline.Marker(start, RewindFormat.MARKER_DEATH),
                new Timeline.Marker(start + 50_000_000L, RewindFormat.MARKER_BED_BROKEN),
                new Timeline.Marker(end, RewindFormat.MARKER_FINAL_KILL),
                new Timeline.Marker(end + 1, RewindFormat.MARKER_BED_LOST));
        Timeline t = Timeline.build(ORIGIN, LAST, start, 0, track, meta(start, end));
        assertEquals(3, t.markers().size());
        assertEquals(start, t.markers().get(0).timeUs);
        assertEquals(RewindFormat.MARKER_DEATH, t.markers().get(0).kind);
        assertEquals(RewindFormat.MARKER_BED_BROKEN, t.markers().get(1).kind);
        assertEquals(end, t.markers().get(2).timeUs);
    }

    @Test
    public void trackMarkersWinElseTheJsonMarkersAreUsedInTimeOrder() {
        long start = ORIGIN + 30_000_000L;
        long end = ORIGIN + 300_000_000L;
        RecordingMeta m = meta(start, end);
        m.markers.add(new RecordingMeta.Marker(start + 9_000_000L, "FINAL_KILL"));
        m.markers.add(new RecordingMeta.Marker(start + 2_000_000L, "DEATH"));
        m.markers.add(new RecordingMeta.Marker(start + 3_000_000L, "NOT_A_KIND"));
        m.markers.add(new RecordingMeta.Marker(end + 5, "DEATH"));
        Timeline fromJson = Timeline.build(ORIGIN, LAST, start, 0, none(), m);
        assertEquals(2, fromJson.markers().size());
        assertEquals(RewindFormat.MARKER_DEATH, fromJson.markers().get(0).kind);
        assertEquals(start + 2_000_000L, fromJson.markers().get(0).timeUs);
        assertEquals(RewindFormat.MARKER_FINAL_KILL, fromJson.markers().get(1).kind);

        List<Timeline.Marker> track = Collections.singletonList(
                new Timeline.Marker(start + 4_000_000L, RewindFormat.MARKER_BED_LOST));
        Timeline fromTrack = Timeline.build(ORIGIN, LAST, start, 0, track, m);
        assertEquals(1, fromTrack.markers().size());
        assertEquals(RewindFormat.MARKER_BED_LOST, fromTrack.markers().get(0).kind);
    }

    @Test
    public void tickOfRoundsUp() {
        Timeline t = Timeline.build(ORIGIN, LAST, ORIGIN, 0, none(), null);
        assertEquals(50_000L, Timeline.TICK_US);
        assertEquals(0, t.tickOf(ORIGIN));
        assertEquals(1, t.tickOf(ORIGIN + 1));
        assertEquals(1, t.tickOf(ORIGIN + 49_999));
        assertEquals(1, t.tickOf(ORIGIN + 50_000));
        assertEquals(2, t.tickOf(ORIGIN + 50_001));
        assertEquals(0, t.tickOf(ORIGIN - 1));
        assertEquals(-1, t.tickOf(ORIGIN - 50_000));
        assertEquals(ORIGIN + 150_000L, t.timeOfTick(3));
        assertEquals(t.tickOf(t.gameStartUs()), t.startTick());
        assertEquals(t.tickOf(t.endUs()), t.endTick());
    }

    @Test
    public void startOutsideTheDataIsFlagged() {
        assertFalse(Timeline.build(ORIGIN, LAST, ORIGIN - 1, 0, none(), null).startInsideData());
        assertFalse(Timeline.build(ORIGIN, LAST, LAST + 1, 0, none(), null).startInsideData());
        assertTrue(Timeline.build(ORIGIN, LAST, LAST, 0, none(), null).startInsideData());
    }

    @Test
    public void originIsTheStartSegmentsFirstNetTimeAndMarkersComeFromTrack() throws Exception {
        File dir = tmp.newFolder();
        String id = "20261117-120000-abcd";
        File cbr = RecordingFixture.realShaped().write(dir, id);
        LoadedRecording r = LoadedRecording.load(cbr, null);
        assertNull(r.problem());
        Timeline t = r.timeline();
        assertEquals(RecordingFixture.T0 + 10, t.originUs());
        assertEquals(RecordingFixture.GAME_START_US, t.gameStartUs());
        assertEquals(RecordingFixture.END_US, t.endUs()); // from the END block's metadata
        assertEquals(2, t.markers().size());
        assertEquals(RecordingFixture.MARKER_US[0], t.markers().get(0).timeUs);
        assertEquals(RewindFormat.MARKER_BED_BROKEN, t.markers().get(0).kind);
        assertEquals(RecordingFixture.MARKER_US[1], t.markers().get(1).timeUs);
        assertEquals(RewindFormat.MARKER_DEATH, t.markers().get(1).kind);
    }

    @Test
    public void aFileWhoseFirstSegmentLostItsHeaderStartsAtTheFirstSegmentWithOne() throws Exception {
        RecordingFixture f = new RecordingFixture();
        // Segment 1: carry header only, no client snapshot.
        f.header(1, RewindFormat.SOURCE_CAPTURE_CARRY, 1_000, RecordingFixture.packet(0x47, 10, 1));
        f.net(1, 1_000, new long[]{1_010, 1_020}, new byte[][]{RecordingFixture.joinGame(0),
                RecordingFixture.packet(0x02, 20, 1)});
        f.header(2, RewindFormat.SOURCE_CLIENT_SNAPSHOT, 5_000, RecordingFixture.joinGame(-1));
        f.net(2, 5_000, new long[]{5_010, 9_000}, new byte[][]{RecordingFixture.respawn(-1),
                RecordingFixture.packet(0x02, 20, 2)});
        f.net(3, 9_000, new long[]{9_500, 400_000}, new byte[][]{RecordingFixture.respawn(0),
                RecordingFixture.packet(0x02, 20, 3)});
        f.track(3, 9_500, RecordingFixture.gameStart(10_000, 0));
        File cbr = f.write(tmp.newFolder(), "lost");
        LoadedRecording r = LoadedRecording.load(cbr, null);
        assertNull(r.problem());
        assertEquals(1, r.skippedSegments());
        assertEquals(2, r.startSegment().number());
        assertEquals(2, r.segments().size());
        assertEquals(5_010, r.timeline().originUs());
        assertEquals(4, r.netRecordCount());
        NetCursor c = r.newCursor();
        assertEquals(5_010, c.timeUs());
        assertEquals(RewindFormat.PACKET_RESPAWN, c.packetId());
    }
}
