package com.bedwarsqol.rewind;

import com.bedwarsqol.feature.SessionChatLine;
import com.bedwarsqol.rewind.watch.PlaybackGate;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static com.bedwarsqol.rewind.CaptureSessionTest.s01;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * R1's recorder while a recording plays (PLAN section 5 layer 3 and section 3 step 2; Phase 5a), on R1's own
 * harness ({@link RewindIntegrationTest.Harness}) with the production gate ({@link PlaybackGate#global()}):
 * <ul>
 *   <li>with the gate on, a connection, world loads, ticks, self-track and camera frames, game lines and a Marker Key
 *       press reach no writer and no file;</li>
 *   <li>a game that was open when the gate went on gets nothing more while it is on;</li>
 *   <li>recorder settle (planner amendment PA1): R1 notices a closed connection only on its next CLIENT_TICK, which
 *       the gate silences, so the session calls {@link Rewind#closeDisconnected()} first: the file is closed; without
 *       it the file stays open until the gate is off again.</li>
 * </ul>
 * With the gate off the recorder is R1's: {@link RewindIntegrationTest} stays green unchanged.
 */
public class RewindIgnoresPlaybackTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final PlaybackGate gate = PlaybackGate.global();

    @Before
    public void gateOff() {
        if (gate.isMuted()) {
            gate.exit();
        }
    }

    @After
    public void leaveTheGateOff() {
        if (gate.isMuted()) {
            gate.exit();
        }
    }

    @Test
    public void withTheGateOnNothingReachesAWriterOrAFile() throws Exception {
        gate.enter();
        RewindIntegrationTest.Harness h = new RewindIntegrationTest.Harness(new File(tmp.getRoot(), "rewind"));
        assertNull("no capture session while muted", h.connect(true, true));
        h.advance(1_000);
        h.boundary(s01(101, 0), 0);
        h.location("fakeGameServer7", "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
        h.game(30, 1);
        h.tick(true, false);
        h.advance(300_000);
        h.tick(false, true);
        h.self(12.5);
        h.rewind.camera(System.nanoTime(), 10f, 20f);
        h.rewind.onGameLine(SessionChatLine.Kind.FINAL_KILL);
        h.rewind.onMarkerKey();
        h.runClient();
        h.step();
        h.advance(RewindWriter.SYNC_INTERVAL_US);
        h.step();

        assertEquals("no writer was made", 0, h.writersMade);
        assertTrue("no file", h.ids().isEmpty());
        assertFalse("no game opened", h.rewind.rules().gameOpen());
        assertEquals("no flash for the Marker Key", 0, h.rewind.flashColor());
        File[] files = h.dir.listFiles();
        assertTrue("nothing in the folder", files == null || files.length == 0);
    }

    @Test
    public void aGameOpenWhenTheGateGoesOnGetsNothingMoreWhileItIsOn() throws Exception {
        RewindIntegrationTest.Harness h = RewindIntegrationTest.openGame(new File(tmp.getRoot(), "rewind"));
        h.step();
        gate.enter();
        long gateOnUs = h.clock.nowUs;
        h.advance(10_000);
        h.self(12.5);
        h.rewind.camera(System.nanoTime(), 10f, 20f);
        h.rewind.onGameLine(SessionChatLine.Kind.FINAL_KILL);
        h.rewind.onGameLine(SessionChatLine.Kind.BED_BREAK);
        h.rewind.onMarkerKey();
        assertEquals("no flash for the Marker Key", 0, h.rewind.flashColor());
        h.rewind.onWorldLoad(null);
        h.advance(RewindGameRules.RESULT_CLOSE_US);
        h.tick(false, false);
        h.step();
        assertTrue("still open: nothing closed it", h.rewind.rules().gameOpen());

        // Gate off again, then the connection ends: R1 closes the game as before.
        gate.exit();
        h.ch.close();
        h.advance(1_000);
        h.tick(false, false);
        h.step();
        h.advance(RewindWriter.SYNC_INTERVAL_US);
        h.step();
        File cbr = RewindWriterTest.onlyCbr(h.dir);
        RewindWriterTest.FileContents fc = RewindWriterTest.read(cbr);
        assertTrue(fc.complete);
        RecordingMeta m = RewindWriterTest.jsonOf(cbr);
        assertTrue(m.complete);
        assertEquals("no marker from the muted span", 0, m.markers.size());
        assertFalse(m.keyMarked);
        assertEquals("the muted world load opened no segment", 1, m.segments);
        for (RewindWriterTest.TrackRec t : fc.track) {
            boolean closing = t.tag == RewindFormat.TAG_GAME_END;
            assertTrue("TRACK record tag " + t.tag + " written during the muted span",
                    closing || t.timeUs <= gateOnUs);
        }
    }

    @Test
    public void settlingTheDisconnectBeforeTheGateClosesTheFile() throws Exception {
        RewindIntegrationTest.Harness h = RewindIntegrationTest.openGame(new File(tmp.getRoot(), "rewind"));
        h.game(20, 2);
        h.step();
        h.ch.close();
        h.rewind.closeDisconnected();
        gate.enter();
        h.advance(1_000);
        h.tick(false, false); // silenced: the settle already did the work
        h.step();
        h.advance(RewindWriter.SYNC_INTERVAL_US);
        h.step();
        assertFalse(h.rewind.rules().gameOpen());
        File cbr = RewindWriterTest.onlyCbr(h.dir);
        assertTrue("CLOSED by the settle, with the gate on", RewindWriterTest.jsonOf(cbr).complete);
        assertTrue(RewindWriterTest.read(cbr).complete);
    }

    @Test
    public void withoutTheSettleTheFileStaysOpenWhileTheGateIsOn() throws Exception {
        RewindIntegrationTest.Harness h = RewindIntegrationTest.openGame(new File(tmp.getRoot(), "rewind"));
        h.game(20, 2);
        h.step();
        h.ch.close();
        gate.enter();
        h.advance(1_000);
        h.tick(false, false);
        h.step();
        h.advance(RewindWriter.SYNC_INTERVAL_US);
        h.step();
        File cbr = RewindWriterTest.onlyCbr(h.dir);
        assertTrue("the recorder never saw the disconnect", h.rewind.rules().gameOpen());
        assertFalse("still being written", RewindWriterTest.jsonOf(cbr).complete);

        // After playback the first tick closes it, as R1 always did.
        gate.exit();
        h.tick(false, false);
        h.step();
        h.advance(RewindWriter.SYNC_INTERVAL_US);
        h.step();
        assertTrue(RewindWriterTest.jsonOf(cbr).complete);
    }

    @Test
    public void settlingWithNoConnectionOrTwiceIsHarmless() throws Exception {
        RewindIntegrationTest.Harness idle = new RewindIntegrationTest.Harness(new File(tmp.getRoot(), "idle"));
        idle.rewind.closeDisconnected();
        assertEquals(0, idle.writersMade);

        RewindIntegrationTest.Harness h = RewindIntegrationTest.openGame(new File(tmp.getRoot(), "rewind"));
        h.ch.close();
        h.rewind.closeDisconnected();
        h.rewind.closeDisconnected();
        h.step();
        h.advance(RewindWriter.SYNC_INTERVAL_US);
        h.step();
        assertEquals(1, h.ids().size());
        assertTrue(RewindWriterTest.jsonOf(RewindWriterTest.onlyCbr(h.dir)).complete);
    }
}
