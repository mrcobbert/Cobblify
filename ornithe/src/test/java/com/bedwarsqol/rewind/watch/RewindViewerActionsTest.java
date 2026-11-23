package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.gui.sheet.SheetFont;
import com.bedwarsqol.gui.sheet.TextMetrics;
import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.export.ExportJob;
import com.bedwarsqol.rewind.export.ExportPanelModel;
import com.bedwarsqol.rewind.export.ExportRange;
import com.bedwarsqol.rewind.export.ExportSettings;
import com.bedwarsqol.rewind.export.RenderRoute;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 6b: every action of the viewer (its keys, {@link ViewerKeys}; its bar, {@link ViewerLayout.Pointer}) and of
 * the Esc menu ({@link EscMenuModel.Key}) reaches the session (or the camera controls) as the plan says, against a real
 * {@link PlaybackSession} driven to WATCHING by {@link PlaybackSessionTest}'s fake client on a recording with three
 * markers. Each enum is covered value by value: a new action without a mapping fails here.
 *
 * <p>Rewind Export Phase 7: the viewer's export mode (TASK U1-U6) against the same session, with the export itself
 * replaced by a recorder ({@link FakeExporter}): the range around the marker last jumped to, the gallery's request,
 * the panel's key states from the export's progress and outcome, and the Esc order (a running export is cancelled
 * first, then export mode is left, then the Esc menu opens).
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
    private FakeExporter exporter;
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

    /** The export the viewer starts (RewindExport in the game); here it only records. */
    static final class FakeExporter implements RewindViewerScreen.Controls.Exporter {
        ExportSettings stored = ExportSettings.of(720, 30, "standard", "hevc", false);
        String refuse;
        boolean active;
        float progress;
        ExportJob.State outcome;
        long startUs, endUs;
        ExportPanelModel.Choice choice;
        ExportPanelModel panel;
        final List<String> calls = new ArrayList<String>();

        @Override
        public ExportSettings stored() {
            return stored;
        }

        @Override
        public String start(long startUs, long endUs, ExportPanelModel.Choice choice, ExportPanelModel panel) {
            calls.add("start");
            this.startUs = startUs;
            this.endUs = endUs;
            this.choice = choice;
            this.panel = panel;
            if (refuse != null) return refuse;
            active = true;
            progress = 0f;
            outcome = null;
            return null;
        }

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public float progress() {
            return progress;
        }

        @Override
        public ExportJob.State outcome() {
            return outcome;
        }

        @Override
        public void cancel() {
            calls.add("cancel");
        }

        @Override
        public void openFolder() {
            calls.add("openFolder");
        }

        @Override
        public void sweep() {
            calls.add("sweep");
        }

        /** The running export ended with {@code s}. */
        void end(ExportJob.State s) {
            active = false;
            outcome = s;
        }
    }

    /** Minecraft's font at one device px per su, for the panel's layout. */
    static final TextMetrics MC = new TextMetrics() {
        @Override public float unit() { return 1f; }
        @Override public float width(String s, SheetFont font) { return s.length() * 6f; }
        @Override public float ascent(SheetFont font) { return 7.5f; }
        @Override public float descent(SheetFont font) { return 1.5f; }
        @Override public float normalLineHeight(SheetFont font) { return 9f; }
        @Override public boolean minecraftFont() { return true; }
        @Override public float pixel(SheetFont font) { return 1f; }
    };

    private static File writeRecording(File dir) throws Exception {
        return writeRecording(dir, 0);
    }

    /** The recording, its game start {@code startShiftUs} after its tick (negative: before). */
    private static File writeRecording(File dir, long startShiftUs) throws Exception {
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
        long start = ORIGIN + START_TICK * TICK + startShiftUs, end = ORIGIN + END_TICK * TICK;
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
        exporter = new FakeExporter();
        RewindViewerScreen.Controls.setExporter(exporter);
        controls = new RewindViewerScreen.Controls(session);
        assertEquals("a new session starts in your view", "select " + ViewerCameraControls.YOU, cameras.calls.get(0));
        cameras.calls.clear();
    }

    @org.junit.After
    public void unwire() {
        ViewerCameraControls.set(null);
        RewindViewerScreen.Controls.setExporter(null);
        RewindViewerScreen.requestExport(null);
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


    // ------------------------------------------------------------------ export mode (Rewind Export Phase 7)

    /** Microseconds from the game start of tick {@code k}. */
    private long fromStart(long k) {
        return session.timeline().timeOfTick(k) - session.timeline().gameStartUs();
    }

    /** A probe that opens H.264 at 1080p60 and 720p30 only. */
    private static ExportPanelModel.Matrix matrix() {
        return new ExportPanelModel.Matrix(Arrays.asList(new int[]{1920, 1080, 60}, new int[]{1280, 720, 30}), null);
    }

    /** The panel's key pressed and released (as a click on it). */
    private void pressKey(ExportPanelModel p) {
        ExportPanelModel.Layout l = p.layout(960, 510, MC);
        float[] k = l.key();
        p.mouseDown(l, k[0] + 2, k[1] + 2);
        controls.panelIntent(p.mouseUp(l, k[0] + 2, k[1] + 2));
    }

    @Test
    public void exportModeOpensAroundTheMarkerLastJumpedToAndEscLeavesIt() {
        session.setPaused(true); // the playhead stays where a step puts it
        assertFalse(controls.exportMode());
        assertNull(controls.panel());

        // the marker last jumped to holds after the playhead moved on (U3)
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.MARKER, 2, 0));
        settle();
        seekTo(800);
        assertEquals(-1, controls.highlightedMarker());
        controls.openExport();
        assertTrue(controls.exportMode());
        long marker = fromStart(700);
        ExportRange r = controls.range();
        assertEquals("10 s before the marker", marker - 10_000_000L, r.startUs());
        assertEquals("5 s after it", marker + 5_000_000L, r.endUs());
        assertNotNull(controls.panel());
        assertEquals("the panel starts from the stored settings: Standard", 0,
                controls.panel().chosen(ExportPanelModel.Row.QUALITY));
        assertEquals("and Chat off", 1, controls.panel().chosen(ExportPanelModel.Row.CHAT));
        assertTrue("the bar is pinned in export mode", controls.barPinned());

        // seeking and the camera keys still work; the range stays
        key(ViewerKeys.KEY_RIGHT, false);
        assertEquals(900, session.playheadTick());
        settle();
        key(ViewerKeys.KEY_2, false);
        assertTrue(controls.free());
        key(ViewerKeys.KEY_1, false);
        assertEquals(r.startUs(), controls.range().startUs());
        assertEquals(r.endUs(), controls.range().endUs());

        // Esc: the camera menu first, then export mode, then the Esc menu
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA_MENU, -1, 0));
        key(ViewerKeys.KEY_ESCAPE, false);
        assertFalse(controls.menuOpen());
        assertTrue(controls.exportMode());
        key(ViewerKeys.KEY_ESCAPE, false);
        assertFalse("Esc leaves export mode", controls.exportMode());
        assertNull(controls.panel());
        assertNull(controls.range());
        assertFalse(controls.barPinned());
        assertEquals(0, escOpened);
        key(ViewerKeys.KEY_ESCAPE, false);
        assertEquals("then the Esc menu", 1, escOpened);

        // no marker jumped to in this viewing: around the playhead
        RewindViewerScreen.Controls fresh = new RewindViewerScreen.Controls(session);
        fresh.openExport();
        assertEquals(fromStart(900) - 10_000_000L, fresh.range().startUs());
        assertEquals(fromStart(900) + 5_000_000L, fresh.range().endUs());
        assertFalse("nothing started", exporter.calls.contains("start"));
    }

    @Test
    public void escCancelsARunningExportBeforeItLeavesExportModeAndTheKeyFollowsTheExport() {
        session.setPaused(true); // the playhead stays where a step puts it
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.MARKER, 2, 0));
        settle();
        controls.openExport();
        assertEquals("export mode sweeps the folder (F2)", Arrays.asList("sweep"), exporter.calls);
        exporter.calls.clear();
        ExportPanelModel p = controls.panel();
        p.probe(matrix());
        p.route(RenderRoute.Route.OWN, 1280, 720);
        assertEquals(ExportPanelModel.KeyState.EXPORT, p.keyState());

        // the Export key: the range on the recording's clock and the panel's choice
        pressKey(p);
        assertEquals(Arrays.asList("start"), exporter.calls);
        long start = session.timeline().gameStartUs();
        assertEquals(start + controls.range().startUs(), exporter.startUs);
        assertEquals(start + controls.range().endUs(), exporter.endUs);
        assertEquals(1280, exporter.choice.width());
        assertEquals(30, exporter.choice.fps());
        assertEquals("the overlay fills this panel's key", p, exporter.panel);
        exporter.progress = 0.25f;
        controls.syncExport();
        assertEquals(ExportPanelModel.KeyState.FILLING, p.keyState());
        assertEquals(0.25f, p.fill(), 0f);

        // while it runs no key does anything but Esc (U5)
        long tick = session.playheadTick();
        int cameraCalls = cameras.calls.size();
        boolean clean = session.view().clean();
        for (int code : new int[]{ViewerKeys.KEY_RIGHT, ViewerKeys.KEY_PERIOD, ViewerKeys.KEY_2, ViewerKeys.KEY_F1,
                ViewerKeys.KEY_SPACE, ViewerKeys.KEY_RBRACKET}) {
            key(code, false);
        }
        settle();
        assertEquals(tick, session.playheadTick());
        assertEquals(cameraCalls, cameras.calls.size());
        assertEquals(clean, session.view().clean());
        assertEquals(ReplayTicks.DEFAULT_SPEED_INDEX, speedIndex());
        assertTrue(controls.exportBusy());
        controls.act(new ViewerLayout.Action(ViewerLayout.Action.Kind.CAMERA_MENU, -1, 0));
        assertFalse("bar actions do nothing while it runs", controls.menuOpen());

        // Esc order: cancel the export (export mode stays), then leave export mode, then the Esc menu
        key(ViewerKeys.KEY_ESCAPE, false);
        assertEquals(Arrays.asList("start", "cancel"), exporter.calls);
        assertTrue(controls.exportMode());
        assertEquals(0, escOpened);
        exporter.end(ExportJob.State.CANCELLED);
        controls.syncExport();
        assertFalse(controls.exportBusy());
        assertEquals("cancelled: back to Export", ExportPanelModel.KeyState.EXPORT, p.keyState());
        assertTrue(controls.exportMode());

        // done: Open Folder opens the file's folder
        pressKey(p);
        exporter.progress = 1f;
        controls.syncExport();
        exporter.end(ExportJob.State.DONE);
        controls.syncExport();
        assertEquals(ExportPanelModel.KeyState.OPEN_FOLDER, p.keyState());
        pressKey(p);
        assertEquals(Arrays.asList("start", "cancel", "start", "openFolder"), exporter.calls);

        // a changed row makes the key read Export again (ExportPanelModel); failed: Failed in red, and the key starts
        // another export (U6)
        p.pick(ExportPanelModel.Row.QUALITY, 2);
        assertEquals(ExportPanelModel.KeyState.EXPORT, p.keyState());
        pressKey(p);
        exporter.end(ExportJob.State.FAILED);
        controls.syncExport();
        assertEquals(ExportPanelModel.KeyState.FAILED, p.keyState());
        exporter.refuse = "busy";
        pressKey(p);
        assertEquals(Arrays.asList("start", "cancel", "start", "openFolder", "start", "start"), exporter.calls);
        assertEquals("a refused start reads Failed", ExportPanelModel.KeyState.FAILED, p.keyState());
        assertFalse(controls.exportBusy());

        key(ViewerKeys.KEY_ESCAPE, false);
        assertFalse(controls.exportMode());
        assertEquals(0, escOpened);
        key(ViewerKeys.KEY_ESCAPE, false);
        assertEquals(1, escOpened);
    }

    @Test
    public void aRangeFromTheGameStartBeginsAtTheFirstRecordedTick() throws Exception {
        // a game whose start lies 25 ms before its first tick (the export cannot start before that tick)
        File cbr = writeRecording(h.tmp.newFolder("offtick"), -25_000L);
        session.requestExit(PlaybackSession.Exit.TITLE);
        h.loop.frame(1);
        assertEquals(PlaybackSession.State.IDLE, session.state());
        h.ops.loadCbr = cbr;
        h.ops.loadJson = new File(cbr.getParentFile(), PlaybackSessionTest.ID + ".json");
        session.open(PlaybackSessionTest.ID);
        h.loop.until(PlaybackSession.State.WATCHING, 1);
        session.setPaused(true);
        long first = session.timeline().timeOfTick(session.timeline().startTick());
        assertEquals("the fixture's game start is off its tick", first - 25_000L, session.timeline().gameStartUs());

        RewindViewerScreen.Controls c = new RewindViewerScreen.Controls(session);
        c.openExport();
        exporter.calls.clear();
        assertEquals(0, c.range().startUs());
        ExportPanelModel p = c.panel();
        p.probe(matrix());
        ExportPanelModel.Layout l = p.layout(960, 510, MC);
        float[] k = l.key();
        p.mouseDown(l, k[0] + 2, k[1] + 2);
        c.panelIntent(p.mouseUp(l, k[0] + 2, k[1] + 2));
        assertEquals(Arrays.asList("start"), exporter.calls);
        assertEquals("never before the first tick (beginExport refuses that)", first, exporter.startUs);
        assertEquals(session.timeline().gameStartUs() + c.range().endUs(), exporter.endUs);
    }

    /** Code review 1 I1 (TASK F2): opening export mode, from the viewer or the gallery, sweeps the export folder. */
    @Test
    public void openingExportModeSweepsTheExportFolder() {
        assertFalse(exporter.calls.contains("sweep"));
        controls.openExport();
        assertEquals(Arrays.asList("sweep"), exporter.calls);
        controls.leaveExport();
        exporter.calls.clear();
        RewindViewerScreen.requestExport(PlaybackSessionTest.ID);
        RewindViewerScreen.Controls c = new RewindViewerScreen.Controls(session);
        assertTrue(c.exportMode());
        assertEquals(Arrays.asList("sweep"), exporter.calls);
    }

    @Test
    public void theGalleryRequestOpensExportModePausedAtTheFirstMarker() {
        // another game's request does nothing and is dropped
        RewindViewerScreen.requestExport("someone-else");
        RewindViewerScreen.Controls other = new RewindViewerScreen.Controls(session);
        assertFalse(other.exportMode());

        RewindViewerScreen.requestExport(PlaybackSessionTest.ID);
        RewindViewerScreen.Controls c = new RewindViewerScreen.Controls(session);
        assertTrue("the gallery's Export opens export mode (U2)", c.exportMode());
        ExportRange r = c.range();
        long first = fromStart(MARKER_TICKS[0]);
        assertEquals(ExportRange.forGallery(session.timeline().lengthUs(), first).startUs(), r.startUs());
        assertEquals(ExportRange.forGallery(session.timeline().lengthUs(), first).endUs(), r.endUs());
        settle();
        assertEquals("the playhead at the first marker", MARKER_TICKS[0], session.playheadTick());
        assertTrue("paused", paused());
        assertNotNull(c.panel());

        RewindViewerScreen.Controls again = new RewindViewerScreen.Controls(session);
        assertFalse("the request is used once", again.exportMode());
    }

    // ------------------------------------------------------------------ the Esc menu

    @Test
    public void everyEscKeyReachesTheSession() {
        Set<EscMenuModel.Key> checked = EnumSet.noneOf(EscMenuModel.Key.class);
        int[] views = {0};
        int[] galleries = {0};
        int[] exports = {0};
        Runnable viewer = () -> views[0]++;
        Runnable gallery = () -> galleries[0]++;
        Runnable export = () -> exports[0]++;

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

        // Export (TASK U1): the viewer in export mode, still paused as the menu left it; the session keeps watching
        model = RewindEscScreen.opened(session);
        RewindEscScreen.apply(EscMenuModel.Key.EXPORT, session, model, viewer, gallery, export);
        assertEquals(1, exports[0]);
        assertEquals("Export opens no plain viewer", 2, views[0]);
        assertEquals(1, galleries[0]);
        assertTrue("export mode starts paused", paused());
        assertEquals(PlaybackSession.State.WATCHING, session.state());
        checked.add(EscMenuModel.Key.EXPORT);

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
        assertEquals(1, exports[0]);
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
