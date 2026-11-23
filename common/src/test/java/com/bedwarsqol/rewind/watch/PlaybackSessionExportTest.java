package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.export.ExportPlan;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The production {@link PlaybackSession} driving an export at a fixed step (Rewind Export PLAN Solution 1, Phase 1;
 * review B1, I1, r2-I1, r2-I2; TASK P1, P2, S2), against a fake client whose frame loop re-reads
 * {@code elapsedTicks} before every tick as {@code Minecraft.runGameLoop} does.
 *
 * <p>Times in the comments are on the recording's clock relative to {@link #ORIGIN} (the first tick).
 */
public class PlaybackSessionExportTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final long ORIGIN = 9_000_000L;
    static final long MS = 1_000L;
    static final String ID = "20261122-120000-export";
    static final long EVEN_NS = 16_666_667L;

    // ------------------------------------------------------------------ the fake client

    /** One timed record handed to the sink, and what the session said while it was handed over. */
    static final class Dispatch {
        final int tag;
        final long stamp;
        final long eventUs;
        final long frame;
        final boolean preroll;

        Dispatch(int tag, long stamp, long eventUs, long frame, boolean preroll) {
            this.tag = tag;
            this.stamp = stamp;
            this.eventUs = eventUs;
            this.frame = frame;
            this.preroll = preroll;
        }

        @Override
        public String toString() {
            return tag + "@" + (stamp - ORIGIN) + " event " + (eventUs - ORIGIN) + " frame " + frame
                    + (preroll ? " preroll" : "");
        }
    }

    final class Sink implements PacketSink {
        final List<Dispatch> timed = new ArrayList<Dispatch>();

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            if (packetId == 0x01) {
                ops.world = true;
            }
            return true;
        }

        @Override
        public void boundary(int packetId, byte[] buf, int off, int len) {
        }

        @Override
        public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
            return true;
        }

        @Override
        public void timed(int tag, long timeUs, byte[] buf, int off, int len) {
            timed.add(new Dispatch(tag, timeUs, session.eventTimeUs(), session.exportFrame(),
                    session.exportPreroll()));
        }

        @Override
        public void sample(long tick, long timeUs) {
        }
    }

    final class Ops implements ClientOps {
        boolean world;
        boolean paused;
        int elapsed;
        float partial;
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
        int exportStarts;
        final Sink sink = new Sink();

        @Override public RecordingMeta readMeta(String id) { return null; }
        @Override public boolean connected() { return false; }
        @Override public void disconnect() { }
        @Override public void settleRecorder() { }
        @Override public void showLoading() { }
        @Override public void showViewer() { }

        @Override
        public void startLoad(String id, PlaybackSession.Load load) {
            LoadedRecording r = LoadedRecording.load(cbr, json).withExt(ext);
            if (r.problem() != null) {
                load.fail(PlaybackSession.Failure.UNPLAYABLE, String.valueOf(r.problem()));
            } else {
                load.succeed(r);
            }
        }

        @Override public void resetGui() { }
        @Override public void newConnection() { world = false; }
        @Override public PacketSink sink() { return sink; }
        @Override public boolean worldPresent() { return world; }
        @Override public boolean gamePaused() { return paused; }
        @Override public int elapsedTicks() { return elapsed; }
        @Override public void setElapsedTicks(int ticks) { elapsed = ticks; }
        @Override public void setPartialTicks(float p) { partial = p; }
        @Override public long nanoTime() { return nanos; }
        @Override public void teardown() { world = false; }
        @Override public void clearTitlesAndBossBar() { }
        @Override public void openTitle() { }
        @Override public void openRejoin() { }
        @Override public void openGallery() { }
        @Override public void log(String line) { }
        @Override public void exportStart() { exportStarts++; }
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

    Ops ops;
    PlaybackSession session;
    int worldTicks;
    /** Ticks whose world tick plays a sound (their recorded times), and what eventTimeUs said then. */
    final Set<Long> soundTicks = new HashSet<Long>();
    final List<Dispatch> tickSounds = new ArrayList<Dispatch>();

    @Before
    public void client() {
        ops = new Ops();
        session = new PlaybackSession(ops, new PlaybackGate(3000));
        worldTicks = 0;
        soundTicks.clear();
        tickSounds.clear();
    }

    /**
     * Wall time that passes once, between the next {@code frameStart()} that runs ticks and its first
     * {@code tickHead()} (a slow frame start, before Minecraft runs the requested ticks); 0: none.
     */
    long stallBeforeTicksNs;

    /** One {@code runGameLoop} pass, {@code wallNs} after the last. */
    void frame(long wallNs) {
        ops.nanos += wallNs;
        session.frameStart();
        for (int j = 0; j < ops.elapsed; j++) { // re-read before every pass
            if (stallBeforeTicksNs > 0) {
                ops.nanos += stallBeforeTicksNs;
                stallBeforeTicksNs = 0;
            }
            session.tickHead();
            if (ops.world && !ops.paused) {
                worldTicks++;
                long k = session.ticks().ticksRun() - 1;
                long t = session.timeline().ticks().timeOf(k);
                if (soundTicks.contains(t)) {
                    tickSounds.add(new Dispatch(-1, t, session.eventTimeUs(), session.exportFrame(),
                            session.exportPreroll()));
                }
            }
            session.tickEnd();
        }
        ops.paused = false;
        session.afterPauseWrite();
    }

    /** A v2 recording: S01 at the origin, a time update every 10 ms, TICK_HEADs at {@code ORIGIN + heads[i]}. */
    void recording(long[] headsUs, long endUs, ExtRecords.Builder more) throws IOException {
        TickFeederTest.Rec rec = new TickFeederTest.Rec()
                .segment(1, TickFeederTest.standardHeader0(), TickFeederTest.standardHeader1())
                .at(ORIGIN, RecordingFixture.joinGame(0));
        for (long t = 10 * MS; t < endUs; t += 10 * MS) {
            rec.at(ORIGIN + t, TickFeederTest.p(0x03, (int) (t / MS) & 0x7F));
        }
        File dir = tmp.newFolder();
        rec.load(dir, ORIGIN, ORIGIN + endUs).close();
        ops.cbr = new File(dir, "20261117-120000-feed.cbr");
        ops.json = new File(dir, "20261117-120000-feed.json");
        ExtCodec c = new ExtCodec();
        ExtRecords.Builder b = more == null ? new ExtRecords.Builder() : more;
        for (long h : headsUs) {
            b.addEncoded(0, ORIGIN + h, RewindFormat.TAG_TICK_HEAD, c.buffer(), c.tickHead(0));
        }
        ops.ext = b.build();
    }

    static long[] every50(long toUs, long... extra) {
        List<Long> t = new ArrayList<Long>();
        for (long us = 0; us <= toUs; us += 50 * MS) {
            t.add(us);
        }
        for (long e : extra) {
            t.add(e);
        }
        long[] a = new long[t.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = t.get(i);
        }
        Arrays.sort(a);
        return a;
    }

    static ExtRecords.Builder records(Object... tagAndUs) {
        ExtCodec c = new ExtCodec();
        ExtRecords.Builder b = new ExtRecords.Builder();
        for (int i = 0; i < tagAndUs.length; i += 2) {
            int tag = (Integer) tagAndUs[i];
            long us = ORIGIN + (Long) tagAndUs[i + 1];
            int n = tag == RewindFormat.TAG_TAP ? c.tap(RewindFormat.TAP_LMB)
                    : c.sound("random.click", 1f, 64f, 1f, 1f, 1f);
            b.addEncoded(0, us, tag, c.buffer(), n);
        }
        return b;
    }

    void open() {
        session.open(ID);
        for (int i = 0; i < 100 && session.state() != PlaybackSession.State.WATCHING; i++) {
            frame(0);
        }
        assertEquals(PlaybackSession.State.WATCHING, session.state());
    }

    /** Straight play at 1x until playback time has reached {@code ORIGIN + us}. */
    void watchTo(long us) {
        while (session.renderTimeUs() < ORIGIN + us) {
            frame(EVEN_NS);
        }
    }

    long countAtOrBefore(long absUs) {
        return session.timeline().ticks().tickOf(absUs + 1);
    }

    /** What one drawn frame showed: export frame index, P, ticks run, the partial vanilla got. */
    static final class Shown {
        final long frame;
        final long p;
        final long ticks;
        final float partial;

        Shown(long frame, long p, long ticks, float partial) {
            this.frame = frame;
            this.p = p;
            this.ticks = ticks;
            this.partial = partial;
        }

        @Override
        public String toString() {
            return frame + ":" + (p - ORIGIN) + "," + ticks + "," + partial;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Shown && o.toString().equals(toString());
        }

        @Override
        public int hashCode() {
            return toString().hashCode();
        }
    }

    Shown shown() {
        return new Shown(session.exportFrame(), session.renderTimeUs(), session.ticks().ticksRun(), ops.partial);
    }

    /**
     * Begins {@code plan} and draws until its last frame was captured; every drawn frame is listed (pre-roll frames
     * with index -1), except passes the real-time floor held ({@link PlaybackSession#exportWaiting}). {@code wall} is the wall time before each frame (cycled); {@code atFrame} runs before a frame.
     */
    List<Shown> export(ExportPlan plan, long[] wall, Runnable atFrame5) {
        assertTrue(session.beginExport(plan));
        List<Shown> out = new ArrayList<Shown>();
        int w = 0;
        for (int guard = 0; guard < 100_000; guard++) {
            frame(wall[w++ % wall.length]);
            if (session.exportWaiting()) {
                // the real-time floor (PLAN Revision 4.1) held this pass: nothing stepped, nothing to capture
                assertEquals(-1, session.exportFrame());
                continue;
            }
            Shown s = shown();
            out.add(s);
            if (s.frame < 0) {
                assertTrue("before frame 0 the session says pre-roll", session.exportPreroll());
                continue;
            }
            assertFalse(session.exportPreroll());
            if (s.frame == 5 && atFrame5 != null) {
                atFrame5.run();
            }
            session.advanceExportFrame();
            if (s.frame == plan.frames() - 1) {
                return out;
            }
        }
        throw new AssertionError("the export never ended");
    }

    static List<Shown> captured(List<Shown> all) {
        List<Shown> out = new ArrayList<Shown>();
        for (Shown s : all) {
            if (s.frame >= 0) {
                out.add(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ B1: a start not on a tick

    /**
     * Review B1, written against the production seek: heads every 50 ms (1,000,000 and 1,050,000 among them), SOUND
     * at 1,010,000 and 1,030,000, a TAP at 1,025,000. Exported from 1,025,000 (not on a tick), from 1,000,000 (on a
     * tick) and from 1,030,000 (on a record's stamp), after watching past the range: a record stamped before the start
     * is never dispatched on an export frame (only in the uncaptured pre-roll, whose sounds the tap discards); every
     * one at or after it is dispatched once, on the first frame at or after its stamp.
     */
    @Test
    public void aStartNotOnATickDispatchesEveryRecordFromTheStartOnceAndNoneBeforeIt() throws IOException {
        long[] stamps = {1_010_000L, 1_025_000L, 1_030_000L};
        for (long start : new long[]{1_025_000L, 1_000_000L, 1_030_000L}) {
            client();
            recording(every50(3_000_000L), 3_000_000L, records(RewindFormat.TAG_SOUND, 1_010_000L,
                    RewindFormat.TAG_TAP, 1_025_000L, RewindFormat.TAG_SOUND, 1_030_000L));
            open();
            watchTo(2_000_000L); // the export must rebuild backward
            ops.sink.timed.clear();
            ExportPlan plan = new ExportPlan(ORIGIN + start, ORIGIN + start + 500_000L, 60);
            List<Shown> frames = captured(export(plan, new long[]{EVEN_NS}, null));
            assertEquals(ORIGIN + start, frames.get(0).p);
            assertEquals("frame 0: exactly the ticks at or before the start ran",
                    countAtOrBefore(ORIGIN + start), frames.get(0).ticks);
            assertEquals(1, ops.exportStarts);
            for (long stamp : stamps) {
                List<Dispatch> onFrames = new ArrayList<Dispatch>();
                int all = 0;
                for (Dispatch d : ops.sink.timed) {
                    if (d.stamp == ORIGIN + stamp) {
                        all++;
                        if (!d.preroll) {
                            onFrames.add(d);
                        }
                    }
                }
                assertTrue("start " + start + ", record " + stamp + ": at most once " + ops.sink.timed, all <= 1);
                if (stamp < start) {
                    assertTrue("start " + start + ": " + stamp + " never on an export frame " + onFrames,
                            onFrames.isEmpty());
                } else {
                    long want = 0;
                    while (plan.timeOfFrame(want) < ORIGIN + stamp) {
                        want++;
                    }
                    assertEquals("start " + start + ", record " + stamp + ": once " + ops.sink.timed, 1,
                            onFrames.size());
                    assertEquals("start " + start + ", record " + stamp + ": first frame at or after it", want,
                            onFrames.get(0).frame);
                    assertEquals("its own stamp is its event time", ORIGIN + stamp, onFrames.get(0).eventUs);
                }
            }
        }
    }

    // ------------------------------------------------------------------ I1, r2-I1: the tail

    /**
     * Review I1: range [0, 1,000,000) at 60 fps, SOUND at 990,000, a tick at 995,000 whose world tick plays a sound,
     * a SOUND at 1,000,000. The 60 frames (the last at 983,333) dispatch neither; {@code collectExportTail()} does,
     * with event times 990,000 and 995,000, captures no frame and runs once.
     */
    @Test
    public void theTailCollectsTheSoundsAfterTheLastFrameUpToTheSelectedEnd() throws IOException {
        recording(every50(3_000_000L, 995_000L), 3_000_000L,
                records(RewindFormat.TAG_SOUND, 990_000L, RewindFormat.TAG_SOUND, 1_000_000L));
        soundTicks.add(ORIGIN + 995_000L);
        open();
        ExportPlan plan = new ExportPlan(ORIGIN, ORIGIN + 1_000_000L, 60);
        List<Shown> frames = captured(export(plan, new long[]{EVEN_NS}, null));
        assertEquals(60, frames.size());
        assertEquals(ORIGIN + 983_333L, frames.get(59).p);
        assertTrue("no sound by the 60 frames: " + ops.sink.timed, ops.sink.timed.isEmpty());
        assertTrue("no tick sound by the 60 frames: " + tickSounds, tickSounds.isEmpty());
        assertFalse(session.exportTailCollected());

        session.collectExportTail();
        frame(EVEN_NS);
        assertTrue(session.exportTailCollected());
        assertEquals("the tail is not a frame to capture", -1, session.exportFrame());
        assertFalse(session.exportPreroll());
        assertEquals("the SOUND at 990,000, not the one at 1,000,000: " + ops.sink.timed, 1, ops.sink.timed.size());
        assertEquals(ORIGIN + 990_000L, ops.sink.timed.get(0).eventUs);
        assertEquals("the tick at 995,000 ran in the tail: " + tickSounds, 1, tickSounds.size());
        assertEquals("a tick's sound: that tick's recorded time", ORIGIN + 995_000L, tickSounds.get(0).eventUs);
        long ticks = session.ticks().ticksRun();
        assertEquals("ticks before the end only", countAtOrBefore(ORIGIN + 999_999L), ticks);

        session.collectExportTail(); // idempotent
        frame(EVEN_NS);
        frame(400_000_000L);
        assertEquals(1, ops.sink.timed.size());
        assertEquals(1, tickSounds.size());
        assertEquals(ticks, session.ticks().ticksRun());
        assertEquals(-1, session.exportFrame());
    }

    /** Review r2-I1: [0, 1,010,000) at 60 fps: 60 frames, 48,480 samples, the tail reaches the selected end. */
    @Test
    public void anEndNotOnAFrameKeepsTheSelectedEndForTheSoundAndTheTail() throws IOException {
        recording(every50(3_000_000L), 3_000_000L,
                records(RewindFormat.TAG_SOUND, 1_005_000L, RewindFormat.TAG_SOUND, 1_010_000L));
        open();
        ExportPlan plan = new ExportPlan(ORIGIN, ORIGIN + 1_010_000L, 60);
        assertEquals(60, plan.frames());
        assertEquals(48_480L, plan.audioSamples());
        captured(export(plan, new long[]{EVEN_NS}, null));
        assertTrue(ops.sink.timed.toString(), ops.sink.timed.isEmpty());
        session.collectExportTail();
        frame(EVEN_NS);
        assertEquals("1,005,000 by the tail, not 1,010,000: " + ops.sink.timed, 1, ops.sink.timed.size());
        assertEquals(ORIGIN + 1_005_000L, ops.sink.timed.get(0).stamp);
        assertEquals(ORIGIN + 1_005_000L, ops.sink.timed.get(0).eventUs);
    }

    // ------------------------------------------------------------------ the fixed step

    /**
     * P2: frame 0 is at the start with exactly the ticks at or before it run; the per-frame (P, ticks run, partial)
     * list is the same with an even clock, a clock that jumps 1 ms, 400 ms and 0 ms, and with pause and speed
     * pressed mid-export; timed records reach the frames once each, in order, none before the start.
     */
    @Test
    public void theFramesDoNotDependOnWallTimePauseOrSpeed() throws IOException {
        List<Shown> even = null;
        List<String> evenTimed = null;
        for (int run = 0; run < 3; run++) {
            client();
            recording(every50(1_400_000L, 1_401_000L, 1_401_100L, 1_401_200L, 1_401_300L), 3_000_000L,
                    records(RewindFormat.TAG_SOUND, 1_010_000L, RewindFormat.TAG_TAP, 1_025_000L,
                            RewindFormat.TAG_SOUND, 1_030_000L, RewindFormat.TAG_TAP, 1_031_000L,
                            RewindFormat.TAG_SOUND, 1_200_000L, RewindFormat.TAG_SOUND, 1_499_999L));
            open();
            watchTo(600_000L);
            if (run == 2) {
                session.setPaused(true); // paused when Export is pressed
                frame(EVEN_NS);
            }
            ops.sink.timed.clear();
            ExportPlan plan = new ExportPlan(ORIGIN + 1_025_000L, ORIGIN + 1_525_000L, 60);
            long[] wall = run == 1 ? new long[]{1_000_000L, 400_000_000L, 0L} : new long[]{EVEN_NS};
            Runnable press = run != 2 ? null : new Runnable() {
                @Override
                public void run() {
                    session.setPaused(true);
                    session.faster();
                    session.togglePlay();
                    session.seekTo(0);
                }
            };
            List<Shown> all = export(plan, wall, press);
            List<Shown> frames = captured(all);
            assertEquals(plan.frames(), frames.size());
            assertEquals(ORIGIN + 1_025_000L, frames.get(0).p);
            assertEquals(countAtOrBefore(ORIGIN + 1_025_000L), frames.get(0).ticks);
            for (int i = 0; i < frames.size(); i++) {
                assertEquals(i, frames.get(i).frame);
                assertEquals(plan.timeOfFrame(i), frames.get(i).p);
                assertEquals("frame " + i, countAtOrBefore(plan.timeOfFrame(i)), frames.get(i).ticks);
            }
            assertEquals("speed untouched", ReplayTicks.DEFAULT_SPEED_INDEX, session.ticks().speedIndex());
            assertFalse("not paused while exporting", session.ticks().paused());

            List<String> onFrames = new ArrayList<String>();
            long last = Long.MIN_VALUE;
            for (Dispatch d : ops.sink.timed) {
                if (d.preroll) {
                    assertTrue("pre-roll records are before the start: " + d, d.stamp < plan.startUs());
                    continue;
                }
                assertTrue("none before the start on a frame: " + d, d.stamp >= plan.startUs());
                assertTrue("in order", d.stamp >= last);
                last = d.stamp;
                onFrames.add(d.toString());
            }
            assertEquals("each once: " + onFrames, new HashSet<String>(onFrames).size(), onFrames.size());
            assertEquals(onFrames.toString(), 5, onFrames.size()); // 1,025,000 to 1,499,999
            if (even == null) {
                even = all;
                evenTimed = onFrames;
            } else {
                assertEquals("run " + run + ": the same frames", even, all);
                assertEquals("run " + run + ": the same records on the same frames", evenTimed, onFrames);
            }
        }
    }

    /** A frame not advanced draws the same moment again and runs no tick and no record twice (settling). */
    @Test
    public void aFrameNotAdvancedIsTheSameWhenDrawnAgain() throws IOException {
        recording(every50(3_000_000L), 3_000_000L, records(RewindFormat.TAG_SOUND, 1_100_000L,
                RewindFormat.TAG_SOUND, 1_101_000L));
        open();
        ExportPlan plan = new ExportPlan(ORIGIN + 1_025_000L, ORIGIN + 2_025_000L, 60);
        assertTrue(session.beginExport(plan));
        while (session.exportFrame() != 5) {
            frame(EVEN_NS);
            if (session.exportFrame() >= 0 && session.exportFrame() < 5) {
                session.advanceExportFrame();
            }
        }
        Shown first = shown();
        int ticks = worldTicks;
        int records = ops.sink.timed.size();
        assertEquals("the 1,100,000 records are on frame 5 (1,108,333)", 2, ops.sink.timed.size()
                - countPreroll());
        for (int i = 0; i < 4; i++) {
            frame(i == 1 ? 400_000_000L : EVEN_NS);
            assertEquals("drawn again: identical", first, shown());
        }
        assertEquals("no tick ran twice", ticks, worldTicks);
        assertEquals("no record twice", records, ops.sink.timed.size());
        session.advanceExportFrame();
        assertEquals("the frame drawn now until the next frame starts", 5, session.exportFrame());
        session.advanceExportFrame(); // twice for one frame: once
        frame(EVEN_NS);
        assertEquals(6, session.exportFrame());
        assertEquals(plan.timeOfFrame(6), session.renderTimeUs());
    }

    private int countPreroll() {
        int n = 0;
        for (Dispatch d : ops.sink.timed) {
            if (d.preroll) {
                n++;
            }
        }
        return n;
    }

    /** {@code endExport} leaves the session paused where the export was; wall time afterwards does not jump P. */
    @Test
    public void endExportPausesAndTheClockDoesNotJumpAfterwards() throws IOException {
        recording(every50(3_000_000L), 3_000_000L, null);
        open();
        ExportPlan plan = new ExportPlan(ORIGIN + 1_025_000L, ORIGIN + 1_525_000L, 60);
        export(plan, new long[]{EVEN_NS}, null);
        session.collectExportTail();
        frame(EVEN_NS);
        long p = session.renderTimeUs();
        long ticks = session.ticks().ticksRun();
        session.endExport();
        assertFalse(session.exporting());
        assertTrue("paused after an export", session.ticks().paused());
        frame(5_000_000_000L);
        frame(EVEN_NS);
        assertEquals("no jump after 5 s of wall time", p, session.renderTimeUs());
        assertEquals(ticks, session.ticks().ticksRun());
        assertTrue("the selected end, less a microsecond", p >= plan.endUs() - 1 && p <= plan.endUs());
        assertEquals("commands work again", -1, session.exportFrame());
        session.setPaused(false);
        frame(EVEN_NS);
        assertEquals(p + EVEN_NS / 1000, session.renderTimeUs());
    }

    /**
     * Code review 1 I3 (TASK P7): an export ended before frame 0 (cancelled or failed in its pre-roll, or before its
     * seek landed) returns paused inside the selected range, by the picture and by the viewer's playhead, and stays
     * there.
     */
    @Test
    public void endingBeforeFrameZeroReturnsPausedInsideTheRange() throws IOException {
        recording(every50(3_000_000L), 3_000_000L, null);
        open();
        ExportPlan plan = new ExportPlan(ORIGIN + 1_000_000L, ORIGIN + 2_000_000L, 60);
        for (String when : new String[]{"pre-roll", "seek"}) {
            watchTo(2_500_000L);
            assertTrue(session.beginExport(plan));
            if (when.equals("pre-roll")) {
                for (int i = 0; i < 10_000 && !(session.state() == PlaybackSession.State.WATCHING
                        && session.exportPreroll() && session.renderTimeUs() >= ORIGIN + 216_666L
                        && session.renderTimeUs() < plan.startUs()); i++) {
                    frame(EVEN_NS);
                }
                assertTrue(when, session.exportPreroll());
                assertTrue(when + ": in the pre-roll", session.renderTimeUs() < plan.startUs());
            }
            session.endExport();
            for (int i = 0; i < 200 && (session.state() != PlaybackSession.State.WATCHING || i < 3); i++) {
                frame(EVEN_NS);
            }
            assertEquals(when, PlaybackSession.State.WATCHING, session.state());
            assertTrue(when + ": paused", session.ticks().paused());
            long p = session.renderTimeUs();
            long head = session.timeline().timeOfTick(session.playheadTick());
            assertTrue(when + ": the picture at " + (p - ORIGIN) + " us, inside the range",
                    p >= plan.startUs() && p < plan.endUs());
            assertTrue(when + ": the playhead at " + (head - ORIGIN) + " us, inside the range",
                    head >= plan.startUs() && head < plan.endUs());
            frame(5_000_000_000L);
            frame(EVEN_NS);
            assertEquals(when + ": it stays", p, session.renderTimeUs());
            session.setPaused(false);
        }
    }

    /**
     * Code review 1 I3 (TASK P7): an export that never began (its helper failed or Esc came while it started) leaves
     * the viewer paused inside the range too: from straight play outside it, and without a move from inside it.
     */
    @Test
    public void anExportThatNeverBeganPausesInsideTheRange() throws IOException {
        recording(every50(3_000_000L), 3_000_000L, null);
        open();
        ExportPlan plan = new ExportPlan(ORIGIN + 1_000_000L, ORIGIN + 2_000_000L, 60);
        watchTo(2_500_000L);
        assertFalse(session.ticks().paused());
        session.pauseInside(plan);
        for (int i = 0; i < 200 && (session.state() != PlaybackSession.State.WATCHING || i < 3); i++) {
            frame(EVEN_NS);
        }
        assertTrue("paused", session.ticks().paused());
        long p = session.renderTimeUs();
        assertTrue("at " + (p - ORIGIN) + " us, inside the range", p >= plan.startUs() && p < plan.endUs());
        long head = session.timeline().timeOfTick(session.playheadTick());
        assertTrue("the playhead inside the range", head >= plan.startUs() && head < plan.endUs());

        session.setPaused(false);
        frame(EVEN_NS);
        frame(EVEN_NS);
        long inside = session.renderTimeUs();
        session.pauseInside(plan);
        frame(EVEN_NS);
        assertTrue(session.ticks().paused());
        assertEquals("inside already: it stays", inside, session.renderTimeUs());
    }

    /** The reviewer's legal v2 freeze: tick heads at 0, 1 s and 5 s only, 6 s long; the range [2 s, 3 s) holds no tick. */
    static final long[] FREEZE_HEADS = {0L, 1_000_000L, 5_000_000L};

    void assertParkedInside(String when, ExportPlan plan) {
        for (int i = 0; i < 10; i++) {
            frame(EVEN_NS);
        }
        for (int i = 0; i < 200 && session.state() != PlaybackSession.State.WATCHING; i++) {
            frame(EVEN_NS);
        }
        assertEquals(when, PlaybackSession.State.WATCHING, session.state());
        assertTrue(when + ": paused", session.ticks().paused());
        long p = session.renderTimeUs();
        assertTrue(when + ": the picture at " + (p - ORIGIN) + " us, inside the range",
                p >= plan.startUs() && p < plan.endUs());
        long head = session.playheadUs();
        assertTrue(when + ": the viewer's playhead at " + (head - ORIGIN) + " us, inside the range",
                head >= plan.startUs() && head < plan.endUs());
        frame(5_000_000_000L);
        frame(EVEN_NS);
        assertEquals(when + ": it stays", p, session.renderTimeUs());
    }

    /**
     * Code review 2 I3 (TASK P7), the reviewer's fixture: no recorded tick lies inside the range, so parking on "the
     * first tick at or after the start" leaves it (that tick is at 5 s). An export cancelled in its pre-roll, and one
     * that never began ({@code pauseInside}), still leave the viewer paused at a time inside the range, by the picture
     * and by the playhead.
     */
    @Test
    public void terminationParksInsideARangeThatHoldsNoTick() throws IOException {
        ExportPlan plan = new ExportPlan(ORIGIN + 2_000_000L, ORIGIN + 3_000_000L, 60);
        recording(FREEZE_HEADS, 6_000_000L, null);
        open();
        assertTrue(session.beginExport(plan));
        for (int i = 0; i < 40; i++) {
            frame(EVEN_NS);
        }
        assertTrue("cancelled in the pre-roll", session.exportPreroll());
        assertTrue(session.renderTimeUs() < plan.startUs());
        session.endExport();
        assertParkedInside("pre-roll", plan);

        client();
        recording(FREEZE_HEADS, 6_000_000L, null);
        open();
        session.pauseInside(plan);
        assertParkedInside("never began", plan);
    }

    /** {@code eventTimeUs()}: the running tick's time inside a tick, a record's stamp inside its dispatch, else P. */
    @Test
    public void eventTimeIsTheTicksTimeTheRecordsStampOrPlaybackTime() throws IOException {
        recording(every50(3_000_000L, 1_051_000L), 3_000_000L, records(RewindFormat.TAG_SOUND, 1_052_000L));
        soundTicks.add(ORIGIN + 1_051_000L);
        open();
        ExportPlan plan = new ExportPlan(ORIGIN + 1_025_000L, ORIGIN + 1_525_000L, 60);
        assertTrue(session.beginExport(plan));
        boolean checked = false;
        for (int i = 0; i < 400 && session.exporting(); i++) {
            frame(EVEN_NS);
            assertEquals("outside a tick and a dispatch: P", session.renderTimeUs(), session.eventTimeUs());
            if (session.exportFrame() >= 0) {
                if (session.exportFrame() == 2) { // 1,058,333: the ticks at 1,050,000 and 1,051,000 ran
                    assertEquals(1, tickSounds.size());
                    assertEquals(ORIGIN + 1_051_000L, tickSounds.get(0).eventUs);
                    Dispatch d = ops.sink.timed.get(ops.sink.timed.size() - 1);
                    assertEquals(ORIGIN + 1_052_000L, d.eventUs);
                    assertEquals(2, d.frame);
                    checked = true;
                    break;
                }
                session.advanceExportFrame();
            }
        }
        assertTrue(checked);
    }

    /** A start 0.3 s into the game pre-rolls from the first tick; an export cannot begin twice or before the game. */
    @Test
    public void aStartNearTheFirstTickPreRollsFromItAndBeginIsGuarded() throws IOException {
        recording(every50(3_000_000L), 3_000_000L, records(RewindFormat.TAG_SOUND, 0L, RewindFormat.TAG_SOUND,
                100_000L));
        open();
        watchTo(1_000_000L);
        assertFalse("before the first tick", session.beginExport(new ExportPlan(ORIGIN - 1, ORIGIN + 500_000L, 60)));
        ops.sink.timed.clear();
        ExportPlan plan = new ExportPlan(ORIGIN + 300_000L, ORIGIN + 800_000L, 60);
        assertTrue(session.beginExport(plan));
        assertFalse("one export at a time", session.beginExport(plan));
        long firstPreroll = Long.MAX_VALUE;
        for (int i = 0; i < 100 && session.exportFrame() < 0; i++) {
            frame(EVEN_NS);
            if (session.exportPreroll() && session.state() == PlaybackSession.State.WATCHING) {
                firstPreroll = Math.min(firstPreroll, session.renderTimeUs());
            }
        }
        assertEquals("the pre-roll starts at the first tick", ORIGIN, firstPreroll);
        assertEquals(0, session.exportFrame());
        assertEquals(ORIGIN + 300_000L, session.renderTimeUs());
        assertEquals("the SOUND at the first tick is dispatched in the pre-roll", 2, ops.sink.timed.size());
        assertTrue(ops.sink.timed.get(0).preroll);
        assertTrue(ops.sink.timed.get(1).preroll);
    }
}
