package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * When a recorded window the player closed stops being drawn (PLAN "Recorded screens", review B3; TASK B10).
 *
 * <p>v1 ({@link WindowCloseRule.Heuristic}, labelled heuristic): no close made by the player was stored, and vanilla
 * turns the view only while no screen is open, so the window closes at the first tick whose head is at or after a
 * CAMERA sample more than 100 ms after the open, or at the first swing start in or after the open's tick; never at a
 * CAMERA sample 40 ms after the open (the open's own frame noise). v2 ({@link WindowCloseRule.RecordedView}): the
 * recorded VIEW screen kind leaving "container" closes it, and none of the heuristic closes fires.
 *
 * <p>Fixture: ticks on a 50 ms grid of heads (a v2 table); each tick's TICK is stamped 10 ms into its interval.
 */
public class WindowCloseRuleTest {

    private static final long T0 = 1_000_000L;
    private static final long TICK = 50_000L;
    private static final int TICKS = 40;

    private final SelfTrackEncoder enc = new SelfTrackEncoder();
    private final SelfTrack.Builder b = new SelfTrack.Builder().segment(1);
    private final int[] swing = new int[TICKS];
    private final ExtRecords.Builder ext = new ExtRecords.Builder();
    private final ExtCodec codec = new ExtCodec();

    private static long head(long k) {
        return T0 + k * TICK;
    }

    private static TickTable table() {
        long[] heads = new long[TICKS];
        for (int i = 0; i < TICKS; i++) {
            heads[i] = head(i);
        }
        return TickTable.v2(T0, heads, new int[TICKS], new int[TICKS], new int[TICKS], TICKS);
    }

    /** A swing whose first counted tick ("reads 1") is in tick {@code k + 1}'s interval: it starts in tick k. */
    private void swingStartsIn(int k) {
        swing[k + 1] = 1;
        if (k + 2 < TICKS) {
            swing[k + 2] = 2;
        }
    }

    private final java.util.List<long[]> cameras = new java.util.ArrayList<long[]>();

    private void camera(long timeUs) {
        cameras.add(new long[]{timeUs});
    }

    /** The self track: one TICK per tick (stamped head + 10 ms) and the CAMERA samples, in time order. */
    private SelfTrack track() {
        int c = 0;
        for (int i = 0; i < TICKS; i++) {
            long t = head(i) + 10_000L;
            while (c < cameras.size() && cameras.get(c)[0] <= t) {
                cameraRecord(cameras.get(c)[0], c);
                c++;
            }
            int n = enc.tick(i, 64, 0, 0f, 0f, 0f, 0, 0, swing[i], 0);
            b.record(t, RewindFormat.TAG_TICK, enc.buffer(), 0, n);
        }
        while (c < cameras.size()) {
            cameraRecord(cameras.get(c)[0], c);
            c++;
        }
        return b.build();
    }

    private void cameraRecord(long t, int i) {
        byte[] body = new byte[8];
        RewindFormat.putFloat(body, 0, 10f + i);
        RewindFormat.putFloat(body, 4, 0f);
        b.record(t, RewindFormat.TAG_CAMERA, body, 0, 8);
    }

    private void view(long timeUs, int screenKind) {
        int n = codec.view(0, 0, screenKind);
        ext.addEncoded(0, timeUs, RewindFormat.TAG_VIEW, codec.buffer(), n);
    }

    /** The first tick in [from, to] at which the source closes a window opened before tick openTick, or -1. */
    private static long firstClose(WindowCloseRule.Source s, long openTick, long openUs, long to) {
        for (long k = openTick; k <= to; k++) {
            if (s.closedBy(openTick, openUs, k)) {
                return k;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ v1 heuristic

    @Test
    public void aCameraSample40msAfterTheOpenDoesNotCloseIt() {
        long openUs = head(10) - 5_000L; // fed before tick 10
        camera(openUs + 40_000L);
        WindowCloseRule.Source s = new WindowCloseRule.Heuristic(track(), table());
        assertEquals(-1, firstClose(s, 10, openUs, TICKS - 2));
    }

    @Test
    public void aCameraSampleMoreThan100msAfterTheOpenClosesItAtTheFirstHeadAtOrAfterIt() {
        long openUs = head(10) - 5_000L;
        camera(openUs + 40_000L);
        camera(head(14) + 20_000L); // 245 ms after the open, inside tick 14's interval
        WindowCloseRule.Source s = new WindowCloseRule.Heuristic(track(), table());
        assertEquals("never before the turn: tick 15 is the first head at or after it", 15,
                firstClose(s, 10, openUs, TICKS - 2));
    }

    @Test
    public void exactly100msAfterTheOpenIsNotMoreThan100ms() {
        long openUs = head(10);
        camera(openUs + WindowCloseRule.CAMERA_GRACE_US);
        WindowCloseRule.Source s = new WindowCloseRule.Heuristic(track(), table());
        assertEquals(-1, firstClose(s, 10, openUs, TICKS - 2));
    }

    @Test
    public void aCameraSampleBeforeTheOpenDoesNotCloseIt() {
        long openUs = head(20) - 5_000L;
        camera(head(5));
        WindowCloseRule.Source s = new WindowCloseRule.Heuristic(track(), table());
        assertEquals(-1, firstClose(s, 20, openUs, TICKS - 2));
    }

    @Test
    public void aSwingStartAfterTheOpenClosesItInItsTick() {
        swingStartsIn(13);
        long openUs = head(10) - 5_000L;
        WindowCloseRule.Source s = new WindowCloseRule.Heuristic(track(), table());
        assertEquals(13, firstClose(s, 10, openUs, TICKS - 3));
    }

    @Test
    public void theRightClickSwingThatOpenedAChestIsBeforeTheOpenAndDoesNotCloseIt() {
        swingStartsIn(9); // right-click on the chest swings the arm; the server's S2D is fed before tick 10
        long openUs = head(10) - 5_000L;
        WindowCloseRule.Source s = new WindowCloseRule.Heuristic(track(), table());
        assertEquals(-1, firstClose(s, 10, openUs, TICKS - 3));
    }

    @Test
    public void theAnswerDependsOnTheTickOnlySoASeekGetsTheSameAnswers() {
        swingStartsIn(30);
        long openUs = head(10) - 5_000L;
        camera(openUs + 40_000L);
        camera(head(22) + 1_000L);
        WindowCloseRule.Source s = new WindowCloseRule.Heuristic(track(), table());
        boolean[] straight = new boolean[TICKS - 3];
        for (int k = 10; k < straight.length; k++) {
            straight[k] = s.closedBy(10, openUs, k);
        }
        for (int k = straight.length - 1; k >= 10; k--) { // backwards, then a jump
            assertEquals("tick " + k, straight[k], s.closedBy(10, openUs, k));
        }
        assertEquals(straight[25], s.closedBy(10, openUs, 25));
        assertFalse(straight[22]);
        assertTrue(straight[23]);
    }

    // ------------------------------------------------------------------ v2: the recorded screen kind

    @Test
    public void onARecordingWithViewRecordsNoHeuristicCloseFires() {
        swingStartsIn(13);
        long openUs = head(10) - 5_000L;
        camera(head(12));
        view(head(10) + 5_000L, RewindFormat.SCREEN_CHEST);
        SelfTrack track = track();
        ExtRecords e = ext.build();
        WindowCloseRule.Source s = WindowCloseRule.forRecording(track, e, table());
        assertTrue(s instanceof WindowCloseRule.RecordedView);
        assertEquals(-1, firstClose(s, 10, openUs, TICKS - 3));
    }

    @Test
    public void theViewScreenKindLeavingContainerClosesIt() {
        long openUs = head(10) - 5_000L;
        view(head(9), RewindFormat.SCREEN_NONE); // before the open's tick: not this window's close
        view(head(10) + 5_000L, RewindFormat.SCREEN_CHEST);
        view(head(12) + 5_000L, RewindFormat.SCREEN_CHEST); // F3 pressed with the window open
        view(head(17) + 30_000L, RewindFormat.SCREEN_NONE); // E or Esc
        WindowCloseRule.Source s = WindowCloseRule.forRecording(track(), ext.build(), table());
        assertEquals(18, firstClose(s, 10, openUs, TICKS - 3));
    }

    @Test
    public void anOtherContainerKindKeepsItAndTheInventoryKindClosesIt() {
        long openUs = head(10) - 5_000L;
        view(head(10) + 5_000L, RewindFormat.SCREEN_OTHER_CONTAINER);
        view(head(20) + 5_000L, RewindFormat.SCREEN_INVENTORY);
        WindowCloseRule.Source s = WindowCloseRule.forRecording(track(), ext.build(), table());
        assertEquals(21, firstClose(s, 10, openUs, TICKS - 3));
        assertFalse("seek back: the same answer", s.closedBy(10, openUs, 20));
        assertTrue(s.closedBy(10, openUs, 30));
        assertEquals("a later window opened before tick 25 starts from its own head", -1,
                firstClose(s, 25, head(25) - 1_000L, TICKS - 3));
    }

    @Test
    public void aRecordingWithoutViewRecordsUsesTheHeuristic() {
        WindowCloseRule.Source s = WindowCloseRule.forRecording(track(), ExtRecords.EMPTY, table());
        assertTrue(s instanceof WindowCloseRule.Heuristic);
        assertTrue(WindowCloseRule.forRecording(null, null, null) == WindowCloseRule.NEVER);
    }
}
