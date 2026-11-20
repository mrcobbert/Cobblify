package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 6b: every action of the viewer (its keys, {@link ViewerKeys}; its bar, {@link ViewerLayout.Pointer}) and of
 * the Esc menu ({@link EscMenuModel.Key}) reaches the session (or the camera controls) as the plan says, against a real
 * {@link PlaybackSession} driven to WATCHING by {@link PlaybackSessionTest}'s fake client on a recording with three
 * markers. Each enum is covered value by value: a new action without a mapping fails here.
 */
public class RewindViewerActionsTest {

    private static final long ORIGIN = PlaybackSessionTest.ORIGIN;
    private static final long TICK = Timeline.TICK_US;
    private static final int START_TICK = 20;
    private static final int END_TICK = 1000;
    /** Two markers inside the game's first 3 s (their landings coincide at the game start) and one late. */
    private static final int[] MARKER_TICKS = {30, 35, 700};
    private static final int[] MARKER_KINDS = {RewindFormat.MARKER_DEATH, RewindFormat.MARKER_BED_BROKEN,
            RewindFormat.MARKER_FINAL_KILL};
    private static final int KEY_A = 30;

    private PlaybackSessionTest h;
    private PlaybackSession session;
    private FakeCameras cameras;
    private RewindViewerScreen.Controls controls;
    private int escOpened;
    private final Runnable openEsc = () -> escOpened++;

    /** The camera controls the wiring phase connects; here they only record. */
    static final class FakeCameras implements ViewerCameraControls {
        int camera = YOU;
        final List<String> calls = new ArrayList<String>();

        @Override
        public void select(int c) {
            calls.add("select " + c);
            camera = c;
        }

        @Override
        public int camera() {
            return camera;
        }

        @Override
        public void move(int forward, int strafe, int vertical, double seconds) {
            calls.add("move " + forward + " " + strafe + " " + vertical);
        }

        @Override
        public void look(double dx, double dy) {
            calls.add("look " + dx + " " + dy);
        }

        @Override
        public void scroll(int wheel) {
            calls.add("scroll " + wheel);
        }

        @Override
        public int flyLevel() {
            return FreeCameraMath.DEFAULT_LEVEL;
        }
    }

    private static File writeRecording(File dir) throws Exception {
        String id = PlaybackSessionTest.ID;
        RecordingFixture f = new RecordingFixture();
        f.header(1, RewindFormat.SOURCE_CLIENT_SNAPSHOT, ORIGIN, TickFeederTest.standardHeader0());
        f.header(1, RewindFormat.SOURCE_CAPTURE_CARRY, ORIGIN, TickFeederTest.standardHeader1());
        long base = ORIGIN;
        for (int i = 0; i < END_TICK; i += 37) {
            int to = Math.min(END_TICK, i + 37);
            long[] t = new long[to - i];
            byte[][] p = new byte[to - i][];
            for (int j = i; j < to; j++) {
                t[j - i] = ORIGIN + j * TICK;
                p[j - i] = j == 0 ? RecordingFixture.joinGame(0) : TickFeederTest.p(0x03, j & 0x7F);
            }
            f.net(1, base, t, p);
            base = t[t.length - 1];
        }
        RecordingFixture.Track[] marks = new RecordingFixture.Track[MARKER_TICKS.length];
        for (int i = 0; i < marks.length; i++) {
            marks[i] = RecordingFixture.marker(ORIGIN + MARKER_TICKS[i] * TICK, MARKER_KINDS[i]);
        }
        f.track(1, ORIGIN + MARKER_TICKS[0] * TICK, marks);
        long start = ORIGIN + START_TICK * TICK, end = ORIGIN + END_TICK * TICK;
        RecordingMeta meta = RecordingFixture.meta(id, start, end);
        f.end(meta, end);
        f.write(dir, id);
        RecordingFixture.writeMeta(dir, id, meta);
        return new File(dir, id + ".cbr");
    }

    @Before
    public void watching() throws Exception {
        h = new PlaybackSessionTest();
        h.tmp.create();
        h.writeRecording();
        h.client();
        File cbr = writeRecording(h.tmp.newFolder("markers"));
        h.ops.loadCbr = cbr;
        h.ops.loadJson = new File(cbr.getParentFile(), PlaybackSessionTest.ID + ".json");
        session = h.session;
        session.open(PlaybackSessionTest.ID);
        h.loop.until(PlaybackSession.State.WATCHING, 1);
        assertEquals(3, session.timeline().markers().size());
        assertEquals(START_TICK, session.playheadTick());

        cameras = new FakeCameras();
        ViewerCameraControls.set(cameras);
        controls = new RewindViewerScreen.Controls(session);
        assertEquals("a new session starts in your view", "select " + ViewerCameraControls.YOU, cameras.calls.get(0));
        cameras.calls.clear();
    }

    @org.junit.After
    public void unwire() {
        ViewerCameraControls.set(null);
    }

    /** Lets a pending seek land (applied at the next frame start, then caught up). */
    private void settle() {
        h.loop.frame(1);
        h.loop.until(PlaybackSession.State.WATCHING, 1);
    }

    private void seekTo(long tick) {
        session.seekTo(tick);
        settle();
        assertEquals(tick, session.playheadTick());
    }

    private void key(int keyCode, boolean shift) {
        controls.key(keyCode, shift, openEsc);
    }

    private boolean paused() {
        return session.ticks().paused();
    }

    private int speedIndex() {
        return session.ticks().speedIndex();
    }

    /** One key code for each viewer action ({@link ViewerKeys#action}), with or without Shift. */
    private static int codeOf(ViewerKeys.Action a) {
        for (int code = 0; code < 256; code++) {
            if (ViewerKeys.action(code, false, false) == a || ViewerKeys.action(code, true, false) == a) return code;
        }
        throw new AssertionError("no key for " + a);
    }

    // ------------------------------------------------------------------ keys

    @Test
    public void everyViewerKeyActionReachesTheSessionOrTheCamera() {
        Set<ViewerKeys.Action> checked = EnumSet.noneOf(ViewerKeys.Action.class);

        // NONE: nothing changes
        long p = session.playheadTick();
        boolean wasPaused = paused();
        key(KEY_A, false);
        assertEquals(p, session.playheadTick());
        assertEquals(wasPaused, paused());
        assertEquals(0, escOpened);
        assertTrue(cameras.calls.isEmpty());
        checked.add(ViewerKeys.Action.NONE);

        // TOGGLE_PLAY: Space and K in your view; in the free camera only K (Space flies up there, Q1)
        key(ViewerKeys.KEY_SPACE, false);
        assertTrue("Space pauses", paused());
        key(ViewerKeys.KEY_K, false);
        assertFalse("K plays", paused());
        cameras.camera = ViewerCameraControls.FREE;
        key(ViewerKeys.KEY_SPACE, false);
        assertFalse("Space does not pause in the free camera", paused());
        key(ViewerKeys.KEY_K, false);
        assertTrue("K pauses in the free camera", paused());
        cameras.camera = ViewerCameraControls.YOU;
        checked.add(ViewerKeys.Action.TOGGLE_PLAY);

        // seeks: 5 s and 30 s from the playhead, whole ticks
        seekTo(400);
        key(ViewerKeys.KEY_RIGHT, false);
        assertEquals(500, session.playheadTick());
        settle();
        checked.add(ViewerKeys.Action.FORWARD_SHORT);
        key(ViewerKeys.KEY_LEFT, false);
        assertEquals(400, session.playheadTick());
        settle();
        checked.add(ViewerKeys.Action.BACK_SHORT);
        key(ViewerKeys.KEY_RIGHT, true);
        assertEquals(1000, session.playheadTick());
        settle();
        checked.add(ViewerKeys.Action.FORWARD_LONG);
        key(ViewerKeys.KEY_LEFT, true);
        assertEquals(400, session.playheadTick());
        settle();
        checked.add(ViewerKeys.Action.BACK_LONG);

        // markers: next visits each in order (the first two land on the same tick), previous steps back
        seekTo(START_TICK);
        assertEquals(-1, controls.highlightedMarker());
        key(ViewerKeys.KEY_PERIOD, false);
        assertEquals(START_TICK, session.playheadTick());
        assertEquals(0, controls.highlightedMarker());
        settle();
        key(ViewerKeys.KEY_PERIOD, false);
        assertEquals(START_TICK, session.playheadTick());
        assertEquals("the second marker is visited though its landing is the same", 1,
                controls.highlightedMarker());
        settle();
        key(ViewerKeys.KEY_PERIOD, false);
        assertEquals(700 - 60, session.playheadTick());
        assertEquals(2, controls.highlightedMarker());
        settle();
        checked.add(ViewerKeys.Action.NEXT_MARKER);
        key(ViewerKeys.KEY_COMMA, false);
        assertEquals(START_TICK, session.playheadTick());
        assertEquals(1, controls.highlightedMarker());
        settle();
        checked.add(ViewerKeys.Action.PREVIOUS_MARKER);
        key(ViewerKeys.KEY_RIGHT, false);
        assertEquals("any other seek clears the highlight", -1, controls.highlightedMarker());
        settle();

        // speed
        assertEquals(ReplayTicks.DEFAULT_SPEED_INDEX, speedIndex());
        key(ViewerKeys.KEY_RBRACKET, false);
        assertEquals(ReplayTicks.DEFAULT_SPEED_INDEX + 1, speedIndex());
        checked.add(ViewerKeys.Action.FASTER);
        key(ViewerKeys.KEY_LBRACKET, false);
        key(ViewerKeys.KEY_LBRACKET, false);
        assertEquals(ReplayTicks.DEFAULT_SPEED_INDEX - 1, speedIndex());
        checked.add(ViewerKeys.Action.SLOWER);

        // cameras
        key(ViewerKeys.KEY_2, false);
        assertEquals("select " + ViewerCameraControls.FREE, cameras.calls.get(cameras.calls.size() - 1));
        assertTrue(controls.free());
        checked.add(ViewerKeys.Action.VIEW_FREE);
        key(ViewerKeys.KEY_1, false);
        assertEquals("select " + ViewerCameraControls.YOU, cameras.calls.get(cameras.calls.size() - 1));
        assertFalse(controls.free());
        checked.add(ViewerKeys.Action.VIEW_YOU);

        // Esc: closes the camera menu first, else opens the Esc menu
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA_MENU, -1, 0));
        assertTrue(controls.menuOpen());
        key(ViewerKeys.KEY_ESCAPE, false);
        assertFalse(controls.menuOpen());
        assertEquals(0, escOpened);
        key(ViewerKeys.KEY_ESCAPE, false);
        assertEquals(1, escOpened);
        checked.add(ViewerKeys.Action.ESC_MENU);

        // the free camera holds the cursor (mouse look, as the game); Esc lets it go before it opens the Esc menu,
        // a click on the world takes it back, every other view leaves it free
        assertFalse("your view: the cursor is free", controls.grabWanted());
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA_MENU, -1, 0));
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA, ViewerCameraControls.FREE, 0));
        assertTrue("entering the free camera takes the cursor", controls.grabWanted());
        assertTrue(controls.grabWanted());
        key(ViewerKeys.KEY_K, false);
        assertTrue("a viewer key keeps it", controls.grabWanted());
        key(ViewerKeys.KEY_K, false);
        key(ViewerKeys.KEY_ESCAPE, false);
        assertFalse("Esc lets it go", controls.grabWanted());
        assertEquals("and opens no Esc menu", 1, escOpened);
        assertTrue("still the free camera", controls.free());
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA_MENU, -1, 0));
        assertTrue("the bar is usable with the cursor free", controls.menuOpen());
        assertFalse(controls.grabWanted());
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA_MENU, -1, 0));
        controls.grabCursor();
        assertTrue("a click on the world takes it back", controls.grabWanted());
        key(ViewerKeys.KEY_ESCAPE, false);
        key(ViewerKeys.KEY_ESCAPE, false);
        assertEquals("a second Esc opens the Esc menu", 2, escOpened);
        controls.grabCursor();
        key(ViewerKeys.KEY_1, false);
        assertFalse("your view frees the cursor", controls.grabWanted());
        controls.grabCursor();
        assertFalse("a click takes no cursor outside the free camera", controls.grabWanted());
        key(ViewerKeys.KEY_2, false);
        assertTrue("key 2 takes it again", controls.grabWanted());
        key(ViewerKeys.KEY_1, false);
        assertFalse(controls.grabWanted());

        // Tab: held per frame (the player-list binding), never a session command
        p = session.playheadTick();
        wasPaused = paused();
        int calls = cameras.calls.size();
        key(ViewerKeys.KEY_TAB, false);
        assertEquals(p, session.playheadTick());
        assertEquals(wasPaused, paused());
        assertEquals(calls, cameras.calls.size());
        assertEquals(2, escOpened);
        assertTrue(RewindViewerScreen.Controls.tabListHeld(ViewerKeys.KEY_TAB, true));
        assertFalse(RewindViewerScreen.Controls.tabListHeld(ViewerKeys.KEY_TAB, false));
        checked.add(ViewerKeys.Action.TAB_LIST);

        // F5: your view's perspective (first person, behind, front); F1 and the Clean View Key: the clean view; key 1
        // ends both overrides (ViewState). The session writes them to the game settings each frame.
        ViewState v = session.view();
        assertEquals(0, v.perspective());
        key(ViewerKeys.KEY_F5, false);
        assertEquals("F5: behind", 1, v.perspective());
        key(ViewerKeys.KEY_F5, false);
        assertEquals("F5: front", 2, v.perspective());
        checked.add(ViewerKeys.Action.CYCLE_PERSPECTIVE);
        key(ViewerKeys.KEY_F1, false);
        assertTrue("F1: the clean view", v.clean());
        assertTrue(v.hideGui());
        controls.key(47, false, 47, openEsc); // the Clean View Key the user bound (V)
        assertFalse("the Clean View Key toggles it back", v.clean());
        checked.add(ViewerKeys.Action.CLEAN_VIEW);
        p = session.playheadTick();
        calls = cameras.calls.size();
        key(ViewerKeys.KEY_3, false);
        key(ViewerKeys.KEY_3, true);
        assertEquals("3 and Shift+3 ask the playback parts (Phase 14), nothing else", p, session.playheadTick());
        assertEquals(calls, cameras.calls.size());
        checked.add(ViewerKeys.Action.NEXT_PLAYER);
        checked.add(ViewerKeys.Action.PREVIOUS_PLAYER);

        assertEquals("every viewer key action is checked", EnumSet.allOf(ViewerKeys.Action.class), checked);
        for (ViewerKeys.Action a : ViewerKeys.Action.values()) {
            if (a != ViewerKeys.Action.NONE) codeOf(a);
        }
    }

    // ------------------------------------------------------------------ the bar

    @Test
    public void everyBarActionReachesTheSessionOrTheCamera() {
        Set<ViewerLayout.Action.Kind> checked = EnumSet.noneOf(ViewerLayout.Action.Kind.class);

        long p = session.playheadTick();
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.NONE, -1, 0));
        assertEquals(p, session.playheadTick());
        assertTrue(cameras.calls.isEmpty());
        checked.add(ViewerLayout.Action.Kind.NONE);

        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.PLAY_PAUSE, -1, 0));
        assertTrue(paused());
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.PLAY_PAUSE, -1, 0));
        assertFalse(paused());
        checked.add(ViewerLayout.Action.Kind.PLAY_PAUSE);

        // speed key: click up, wrapping from 4x to 0.25x; right-click down
        for (int i = speedIndex(); i < ReplayTicks.SPEEDS.length - 1; i++) {
            controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.SPEED_UP, -1, 0));
        }
        assertEquals(ReplayTicks.SPEEDS.length - 1, speedIndex());
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.SPEED_UP, -1, 0));
        assertEquals("4x wraps to 0.25x", 0, speedIndex());
        checked.add(ViewerLayout.Action.Kind.SPEED_UP);
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.SPEED_UP, -1, 0));
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.SPEED_DOWN, -1, 0));
        assertEquals(0, speedIndex());
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.SPEED_DOWN, -1, 0));
        assertEquals("no step below 0.25x", 0, speedIndex());
        checked.add(ViewerLayout.Action.Kind.SPEED_DOWN);

        // the camera key and its menu
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA_MENU, -1, 0));
        assertTrue(controls.menuOpen());
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CLOSE_MENU, -1, 0));
        assertFalse(controls.menuOpen());
        checked.add(ViewerLayout.Action.Kind.CLOSE_MENU);
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA_MENU, -1, 0));
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA_MENU, -1, 0));
        assertFalse("the camera key closes its open menu", controls.menuOpen());
        checked.add(ViewerLayout.Action.Kind.CAMERA_MENU);
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA_MENU, -1, 0));
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA, ViewerCameraControls.FREE, 0));
        assertEquals("select " + ViewerCameraControls.FREE, cameras.calls.get(cameras.calls.size() - 1));
        assertFalse("choosing closes the menu", controls.menuOpen());
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA, ViewerCameraControls.YOU, 0));
        assertEquals("select " + ViewerCameraControls.YOU, cameras.calls.get(cameras.calls.size() - 1));
        // a player's row (Phase 14) goes to the ridden view, never to your view or the free camera
        int before = cameras.calls.size();
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA_MENU, -1, 0));
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA, ViewerLayout.FIRST_PLAYER, 0));
        assertEquals(before, cameras.calls.size());
        assertFalse(controls.menuOpen());
        checked.add(ViewerLayout.Action.Kind.CAMERA);

        // a marker click jumps 3 s before it and highlights it
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.MARKER, 2, 0));
        assertEquals(700 - 60, session.playheadTick());
        assertEquals(2, controls.highlightedMarker());
        settle();
        checked.add(ViewerLayout.Action.Kind.MARKER);

        // a timeline click or drag release seeks to its time from the game start; the highlight clears
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.SEEK, -1, 10_000_000L));
        assertEquals(START_TICK + 200, session.playheadTick());
        assertEquals(-1, controls.highlightedMarker());
        assertEquals(10_000_000L, controls.playheadUs());
        settle();
        checked.add(ViewerLayout.Action.Kind.SEEK);

        assertEquals("every bar action is checked", EnumSet.allOf(ViewerLayout.Action.Kind.class), checked);
    }

    @Test
    public void scrollChangesFlightSpeedOnlyInTheFreeCamera() {
        controls.scroll(120, 1000L);
        assertTrue("no flight speed in your view", cameras.calls.isEmpty());
        assertEquals(0f, controls.speedBarAlpha(1000L), 0f);
        cameras.camera = ViewerCameraControls.FREE;
        controls.scroll(-120, 2000L);
        assertEquals("scroll -120", cameras.calls.get(0));
        assertEquals(1f, controls.speedBarAlpha(2500L), 0f);
        assertEquals(0f, controls.speedBarAlpha(2000L + FreeCameraMath.SPEED_BAR_MS
                + FreeCameraMath.SPEED_BAR_FADE_MS), 0f);
    }

    // ------------------------------------------------------------------ the Esc menu

    @Test
    public void everyEscKeyReachesTheSession() {
        Set<EscMenuModel.Key> checked = EnumSet.noneOf(EscMenuModel.Key.class);
        int[] views = {0};
        int[] galleries = {0};
        Runnable viewer = () -> views[0]++;
        Runnable gallery = () -> galleries[0]++;

        // opening pauses; Resume restores the play state from before
        EscMenuModel model = RewindEscScreen.opened(session);
        assertTrue(paused());
        RewindEscScreen.apply(EscMenuModel.Key.RESUME, session, model, viewer, gallery);
        assertFalse("playing before, playing after", paused());
        assertEquals(1, views[0]);
        session.setPaused(true);
        model = RewindEscScreen.opened(session);
        RewindEscScreen.apply(EscMenuModel.Key.RESUME, session, model, viewer, gallery);
        assertTrue("paused before, paused after", paused());
        assertEquals(2, views[0]);
        checked.add(EscMenuModel.Key.RESUME);

        RewindEscScreen.apply(EscMenuModel.Key.GALLERY, session, model, viewer, gallery);
        assertEquals(1, galleries[0]);
        assertEquals("the gallery keeps the session", PlaybackSession.State.WATCHING, session.state());
        checked.add(EscMenuModel.Key.GALLERY);

        RewindEscScreen.apply(EscMenuModel.Key.REJOIN, session, model, viewer, gallery);
        h.loop.frame(1);
        assertEquals(PlaybackSession.State.IDLE, session.state());
        assertEquals(PlaybackSession.Exit.REJOIN, session.lastExit());
        assertTrue(h.ops.log.contains("openRejoin muted=false"));
        checked.add(EscMenuModel.Key.REJOIN);

        session.open(PlaybackSessionTest.ID);
        h.loop.until(PlaybackSession.State.WATCHING, 1);
        model = RewindEscScreen.opened(session);
        RewindEscScreen.apply(EscMenuModel.Key.QUIT, session, model, viewer, gallery);
        h.loop.frame(1);
        assertEquals(PlaybackSession.State.IDLE, session.state());
        assertEquals(PlaybackSession.Exit.TITLE, session.lastExit());
        checked.add(EscMenuModel.Key.QUIT);

        assertEquals("every Esc key is checked", EnumSet.allOf(EscMenuModel.Key.class), checked);
        assertEquals(2, views[0]);
    }

    @Test
    public void theScreensRegisterThroughTheSessionsScreenFactory() {
        RewindPlayback.Screens s = RewindViewerScreen.screens();
        assertNotNull(s);
        assertTrue(s.loading(session) instanceof RewindLoadingScreen);
        assertTrue(s.viewer(session) instanceof RewindViewerScreen);
        assertTrue(RewindPlayback.isRewindScreen(s.loading(session)));
        assertTrue(RewindPlayback.isRewindScreen(new RewindEscScreen(session, s.viewer(session),
                RewindEscScreen.opened(session))));
    }
}
