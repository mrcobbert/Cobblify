package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.hud.HudSource;
import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.export.ExportPlan;
import net.minecraft.client.Minecraft;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Frame 0 of an export shows the HUD state straight playback shows at that time (Rewind Export PLAN Solution 1
 * "Pre-roll", Phase 1; review r2-I2; TASK P2): the production {@link PlaybackSession}, the real {@link HudPlayback}
 * and {@code RecordedHudSource}, a fake client; every drawn frame is followed by the draw-time reads
 * ({@link HudPlayback#debugState}, which reads each Keystrokes cap's level as the painter does).
 *
 * <p>The recording (times after its first tick): Keystrokes on; W held from 1,900,000 and released at 2,000,000 (a
 * release fades only once a draw sees it); a TAP LMB at 2,010,000 (a flash from its own stamp); a SOUND at 2,015,000.
 * The export starts at 2,025,000. PLAN Phase 1 states these one second earlier (start 1,025,000); with the one-second
 * pre-roll a playhead at 0.5 s would then be after the pre-roll's start, so no export from it could go forward.
 * Shifting the fixture by a second keeps the three starts the plan asks for: backward, forward, other history.
 */
public class ExportStartStateTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final long ORIGIN = 9_000_000L;
    static final long MS = 1_000L;
    static final long START = 2_025_000L;
    static final int FPS = 60;
    static final long EVEN_NS = 16_666_667L;
    static final int LMB = 5, W = 0, RMB = 6;

    private ClientSettings savedConfig;

    @Before
    public void config() {
        savedConfig = BedwarsQol.config;
        BedwarsQol.config = new ClientSettings();
    }

    @After
    public void restoreConfig() {
        BedwarsQol.config = savedConfig;
    }

    // ------------------------------------------------------------------ the client

    final class Sink implements PacketSink {
        /** SOUND records as an export's sound tap would log them: not before frame 0 (the pre-roll discards). */
        final List<Long> logged = new ArrayList<Long>();
        final List<Long> discarded = new ArrayList<Long>();

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            if (packetId == 0x01) {
                ops.world = true;
            }
            return true;
        }

        @Override public void boundary(int packetId, byte[] buf, int off, int len) { }
        @Override public boolean sessionHook(int packetId, byte[] buf, int off, int len) { return true; }
        @Override public void sample(long tick, long timeUs) { }

        @Override
        public void timed(int tag, long timeUs, byte[] buf, int off, int len) {
            hud.timed(tag, timeUs, buf, off, len);
            if (tag == RewindFormat.TAG_SOUND && session.exporting()) {
                (session.exportPreroll() ? discarded : logged).add(session.eventTimeUs() - ORIGIN);
            }
        }
    }

    final class Ops implements ClientOps {
        boolean world;
        boolean paused;
        int elapsed;
        float timerSpeed = 1f;
        int thirdPerson;
        boolean hideGui;
        boolean showDebug;
        boolean smoothCamera;
        int difficulty;
        int counter;
        long nanos = 1_000_000_000L;
        File cbr;
        File json;
        ExtRecords ext;
        final Sink sink = new Sink();

        @Override public RecordingMeta readMeta(String id) { return null; }
        @Override public boolean connected() { return false; }
        @Override public void disconnect() { }
        @Override public void settleRecorder() { }
        @Override public void showLoading() { }
        @Override public void showViewer() { }

        @Override
        public void startLoad(String id, PlaybackSession.Load load) {
            load.succeed(LoadedRecording.load(cbr, json).withExt(ext));
        }

        @Override public void resetGui() { }

        @Override
        public void newConnection() {
            world = false;
            hud.restart();
        }

        @Override public void exportStart() { hud.exportStart(); }
        @Override public PacketSink sink() { return sink; }
        @Override public boolean worldPresent() { return world; }
        @Override public boolean gamePaused() { return paused; }
        @Override public int elapsedTicks() { return elapsed; }
        @Override public void setElapsedTicks(int ticks) { elapsed = ticks; }
        @Override public void setPartialTicks(float p) { }
        @Override public long nanoTime() { return nanos; }

        @Override
        public void teardown() {
            world = false;
            hud.exit();
        }

        @Override public void clearTitlesAndBossBar() { }
        @Override public void openTitle() { }
        @Override public void openRejoin() { }
        @Override public void openGallery() { }
        @Override public void log(String line) { }
        @Override public float timerSpeed() { return timerSpeed; }
        @Override public void setTimerSpeed(float speed) { timerSpeed = speed; }
        @Override public int thirdPersonView() { return thirdPerson; }
        @Override public void setThirdPersonView(int view) { thirdPerson = view; }
        @Override public boolean hideGui() { return hideGui; }
        @Override public void setHideGui(boolean hide) { hideGui = hide; }
        @Override public boolean showDebugInfo() { return showDebug; }
        @Override public void setShowDebugInfo(boolean show) { showDebug = show; }
        @Override public boolean smoothCamera() { return smoothCamera; }
        @Override public void setSmoothCamera(boolean smooth) { smoothCamera = smooth; }
        @Override public int difficulty() { return difficulty; }
        @Override public void setDifficulty(int id) { difficulty = id; }
        @Override public void setGamePaused(boolean p) { paused = p; }
        @Override public Object copyChat() { return "chat"; }
        @Override public void restoreChat(Object saved) { }
        @Override public int updateCounter() { return counter; }
        @Override public void setUpdateCounter(int c) { counter = c; }
    }

    static final HudPlayback.Painters NO_PAINTERS = new HudPlayback.Painters() {
        @Override public void text(Minecraft mc, ClientSettings settings, HudSource source) { }
        @Override public void post(Minecraft mc, ClientSettings settings, HudSource source) { }
    };

    HudPlayback hud;
    Ops ops;
    PlaybackSession session;

    void client() throws IOException {
        hud = new HudPlayback(NO_PAINTERS);
        ops = new Ops();
        session = new PlaybackSession(ops, new PlaybackGate(3000));
        recording();
    }

    /** The fixture above; heads every 50 ms for 4.5 s. */
    void recording() throws IOException {
        TickFeederTest.Rec rec = new TickFeederTest.Rec()
                .segment(1, TickFeederTest.standardHeader0(), TickFeederTest.standardHeader1())
                .at(ORIGIN, RecordingFixture.joinGame(0));
        for (long t = 10 * MS; t < 4_500 * MS; t += 10 * MS) {
            rec.at(ORIGIN + t, TickFeederTest.p(0x03, (int) (t / MS) & 0x7F));
        }
        File dir = tmp.newFolder();
        rec.load(dir, ORIGIN, ORIGIN + 4_500 * MS).close();
        ops.cbr = new File(dir, "20261117-120000-feed.cbr");
        ops.json = new File(dir, "20261117-120000-feed.json");

        ExtCodec c = new ExtCodec();
        ExtRecords.Builder b = new ExtRecords.Builder();
        for (long t = 0; t <= 4_500 * MS; t += 50 * MS) {
            b.addEncoded(0, ORIGIN + t, RewindFormat.TAG_TICK_HEAD, c.buffer(), c.tickHead(0));
        }
        c.beginSettings();
        c.settingInt(RewindFormat.settingHudKey(RewindFormat.HUD_KEYSTROKES, RewindFormat.HUD_FIELD_ENABLED), 1);
        b.addEncoded(0, ORIGIN, RewindFormat.TAG_SETTINGS, c.buffer(), c.endSettings());
        b.addEncoded(0, ORIGIN, RewindFormat.TAG_HUD, c.buffer(),
                c.beginHud(RewindFormat.HUD_KEYSTROKES, RewindFormat.HUD_FLAG_ON_SCREEN).end());
        b.addEncoded(0, ORIGIN, RewindFormat.TAG_INPUT, c.buffer(), c.input(0));
        b.addEncoded(0, ORIGIN + 280 * MS, RewindFormat.TAG_TAP, c.buffer(), c.tap(RewindFormat.TAP_LMB));
        b.addEncoded(0, ORIGIN + 1_900 * MS, RewindFormat.TAG_INPUT, c.buffer(), c.input(RewindFormat.INPUT_W));
        b.addEncoded(0, ORIGIN + 2_000 * MS, RewindFormat.TAG_INPUT, c.buffer(), c.input(0));
        b.addEncoded(0, ORIGIN + 2_010 * MS, RewindFormat.TAG_TAP, c.buffer(), c.tap(RewindFormat.TAP_LMB));
        b.addEncoded(0, ORIGIN + 2_015 * MS, RewindFormat.TAG_SOUND, c.buffer(),
                c.sound("random.click", 1f, 64f, 1f, 1f, 1f));
        ops.ext = b.build();
    }

    /** One {@code runGameLoop} pass {@code wallNs} after the last, then the frame's draw-time reads. */
    String frame(long wallNs) {
        ops.nanos += wallNs;
        session.frameStart();
        for (int j = 0; j < ops.elapsed; j++) {
            session.tickHead();
            session.tickEnd();
        }
        ops.paused = false;
        session.afterPauseWrite();
        return draw();
    }

    String draw() {
        LoadedRecording r = session.recording();
        return hud.debugState(session.view(), r.extTrack(), ViewPart.segmentNow(session), session.renderTimeUs());
    }

    void open() {
        session.open("fake");
        for (int i = 0; i < 100 && session.state() != PlaybackSession.State.WATCHING; i++) {
            frame(0);
        }
        assertEquals(PlaybackSession.State.WATCHING, session.state());
    }

    void watchTo(long us) {
        while (session.renderTimeUs() < ORIGIN + us) {
            frame(EVEN_NS);
        }
    }

    static long grid(long start, long i) {
        return start + Math.floorDiv(i * 1_000_000L, FPS);
    }

    /**
     * Straight play from the first tick at 60 fps, its frames on the export's grid through {@code start} (so a frame
     * lands on it), drawn and read every frame: the reads at {@code start} and the next 10 frame times.
     */
    float refLmb, refW, refRmb;

    List<String> reference(long start) throws IOException {
        client();
        open();
        draw();
        long i = -1;
        while (grid(ORIGIN + start, i - 1) > ORIGIN) {
            i--;
        }
        List<String> out = new ArrayList<String>();
        for (; i <= 10; i++) {
            long to = grid(ORIGIN + start, i);
            String d = frame((to - session.renderTimeUs()) * 1000L);
            assertEquals(to, session.renderTimeUs());
            if (i >= 0) {
                out.add(d);
            }
            if (i == 0) {
                refLmb = level(LMB);
                refW = level(W);
                refRmb = level(RMB);
            }
        }
        return out;
    }

    /** Exports from {@code start}; the draw-time reads of frames 0-10. */
    List<String> export(long start) {
        ops.sink.logged.clear();
        ops.sink.discarded.clear();
        ExportPlan plan = new ExportPlan(ORIGIN + start, ORIGIN + start + 1_000_000L, FPS);
        assertTrue(session.beginExport(plan));
        List<String> out = new ArrayList<String>();
        for (int guard = 0; guard < 10_000 && out.size() < 11; guard++) {
            String d = frame(EVEN_NS);
            long f = session.exportFrame();
            if (f >= 0) {
                assertEquals(plan.timeOfFrame(f), session.renderTimeUs());
                out.add(d);
                session.advanceExportFrame();
            }
        }
        assertEquals(11, out.size());
        return out;
    }

    /** The level the Keystrokes painter reads for a cap now (a repeated read at the same time changes nothing). */
    float level(int cap) {
        return hud.source().keyFade(cap).level(hud.source().keyStates(null)[cap], hud.source().now());
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void frameZeroShowsTheHudStateOfStraightPlayWhicheverWayTheExportStarted() throws IOException {
        List<String> ref = reference(START);
        assertEquals("the LMB flash at the start: 15 ms into 120", 0.875f, refLmb, 1e-6f);
        assertTrue("the W fade part-way at the start: " + refW, refW > 0f && refW < 1f);
        assertEquals(0f, refRmb, 0f);

        // (i) watched ahead to 4 s: the export rebuilds backward
        client();
        open();
        watchTo(4_000_000L);
        assertEquals("backward", ref, export(START));
        checkSound();

        // (ii) from a playhead at 0.5 s: the export goes forward
        client();
        open();
        watchTo(500_000L);
        long before = session.ticks().ticksRun();
        assertEquals("forward", ref, export(START));
        assertTrue(before < session.ticks().ticksRun());
        checkSound();

        // (iii) another history first: watched past the click, back to 0.5 s, and a stray RMB flash left in the
        // HUD that straight play never had (the forward start must not inherit it)
        client();
        open();
        watchTo(2_200_000L);
        session.seekTo(session.timeline().tickOf(ORIGIN + 500_000L));
        for (int i = 0; i < 10; i++) {
            frame(0);
        }
        ExtCodec c = new ExtCodec();
        byte[] rmb = ExtRecords.body(c.buffer(), c.tap(RewindFormat.TAP_RMB));
        hud.timed(RewindFormat.TAG_TAP, ORIGIN + 2_030_000L, rmb, 0, rmb.length);
        assertEquals("other history", ref, export(START));
        checkSound();
    }

    /** The SOUND at 2,015,000 was dispatched in the pre-roll and discarded; nothing before the start was logged. */
    private void checkSound() {
        assertEquals("dispatched in the pre-roll: " + ops.sink.discarded, 1, ops.sink.discarded.size());
        assertEquals(2_015_000L, (long) ops.sink.discarded.get(0));
        for (long t : ops.sink.logged) {
            assertTrue("nothing before the start is logged: " + t, t >= START);
        }
    }

    /** A start 0.3 s into the game pre-rolls from the first tick and still matches straight play (a click at 0.28 s). */
    @Test
    public void aStartNearTheFirstTickMatchesStraightPlay() throws IOException {
        List<String> ref = reference(300_000L);
        assertEquals("the LMB flash at 0.3 s", 1f - 20f / 120f, refLmb, 1e-6f);
        client();
        open();
        watchTo(1_500_000L);
        assertEquals(ref, export(300_000L));
    }
}
