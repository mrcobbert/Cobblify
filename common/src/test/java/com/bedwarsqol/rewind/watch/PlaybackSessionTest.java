package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RecordingMeta;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The playback session's state machine against a fake client (PLAN section 3, Phase 4a): entry order, the
 * tick-locked feed inside a fake frame loop that re-reads {@code elapsedTicks} before every tick and writes the pause
 * flag after the loop (as {@code Minecraft.runGameLoop} does), the tick bounds of round 2 I1, catch-up effects, the
 * GUI reset of round 2 B2, wait-for-closed, failure destinations (round 1 I3), restore on every exit path and the
 * screen policy.
 */
public class PlaybackSessionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final long ORIGIN = 9_000_000L;
    static final long TICK = Timeline.TICK_US;
    static final int START_TICK = 20;
    static final int END_TICK = 200;
    static final String ID = "20261117-120000-feed";

    private File cbr;
    private File json;

    @Before
    public void writeRecording() throws IOException {
        cbr = recording(tmp.newFolder("rec"), END_TICK);
        json = new File(cbr.getParentFile(), ID + ".json");
    }

    /**
     * One record per tick: a time update (0x03) at tick i, a chat line (0x02) every 10th tick; S01 at the origin.
     * Game start at tick 20, end at tick 200.
     */
    static File recording(File dir, int endTick) throws IOException {
        TickFeederTest.Rec rec = new TickFeederTest.Rec()
                .segment(1, TickFeederTest.standardHeader0(), TickFeederTest.standardHeader1())
                .at(ORIGIN, RecordingFixture.joinGame(0));
        for (int i = 1; i < endTick; i++) {
            rec.at(ORIGIN + i * TICK, TickFeederTest.p(i % 10 == 0 ? 0x02 : 0x03, i & 0x7F));
        }
        rec.load(dir, ORIGIN + START_TICK * TICK, ORIGIN + endTick * TICK).close();
        return new File(dir, ID + ".cbr");
    }

    // ------------------------------------------------------------------ the fake client

    static final class Line {
        final String text;
        final int created;

        Line(String text, int created) {
            this.text = text;
            this.created = created;
        }
    }

    final class FakeSink implements PacketSink {
        final Set<Integer> failIds = new HashSet<Integer>();
        long throwInSampleAtTick = -1;
        int samples;
        int applied;

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            ops.event("apply " + packetId + (loop.inHead ? " head" : loop.inTickBody ? " body" : " outside"));
            if (exiting()) {
                ops.event("FED-AFTER-EXIT");
            }
            if (failIds.contains(packetId)) {
                return false;
            }
            applied++;
            if (packetId == 0x02) {
                ops.chat.add(new Line("rec", ops.counter));
            }
            if (packetId == 0x01) {
                ops.world = true;
            }
            return true;
        }

        @Override
        public void boundary(int packetId, byte[] buf, int off, int len) {
            ops.event("boundary " + packetId);
        }

        @Override
        public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
            return true;
        }

        @Override
        public void sample(long tick, long timeUs) {
            ops.event("sample " + tick + (loop.inHead ? " head" : " outside"));
            if (exiting()) {
                ops.event("FED-AFTER-EXIT");
            }
            if (tick == throwInSampleAtTick) {
                throw new IllegalStateException("hook threw");
            }
            samples++;
        }

        private boolean exiting() {
            return session.state() == PlaybackSession.State.EXITING || session.state() == PlaybackSession.State.IDLE;
        }
    }

    final class FakeOps implements ClientOps {
        final List<String> log = new ArrayList<String>();
        boolean connected;
        boolean world;
        boolean paused;
        int elapsed;
        float timerSpeed = 1f;
        int thirdPerson = 2;
        int difficulty = 3;
        List<Line> chat = new ArrayList<Line>();
        List<String> sent = new ArrayList<String>();
        int counter = 500;
        String title = "live title";
        boolean bossBar = true;
        Object screen = "pause menu";
        long nanos = 1_000_000_000L;
        long tickCostNs;
        File loadCbr = cbr;
        File loadJson = json;
        int closedAfterPolls = 1;
        boolean neverClosed;
        long fakeMs;
        int polls;
        String throwIn;
        FakeSink sink = new FakeSink();
        final List<Object> sessionScreens = new ArrayList<Object>();

        void event(String e) {
            log.add(e);
        }

        private void maybeThrow(String step) {
            if (step.equals(throwIn)) {
                throw new IllegalStateException(step + " threw");
            }
        }

        @Override
        public RecordingMeta readMeta(String id) {
            return null;
        }

        @Override
        public boolean connected() {
            return connected;
        }

        @Override
        public void disconnect() {
            event("disconnect");
            connected = false;
            world = false;
        }

        @Override
        public void settleRecorder() {
            event("settle muted=" + gate.isMuted());
        }

        @Override
        public void showLoading() {
            Object s = "loading " + sessionScreens.size();
            sessionScreens.add(s);
            session.approve(s);
            if (session.allowScreen(s, false)) {
                screen = s;
            }
            event("showLoading entering=" + gate.isEntering());
        }

        @Override
        public void showViewer() {
            maybeThrow("showViewer");
            Object s = "viewer " + sessionScreens.size();
            sessionScreens.add(s);
            session.approve(s);
            if (session.allowScreen(s, false)) {
                screen = s;
            }
            event("showViewer");
        }

        @Override
        public void startLoad(String id, final PlaybackSession.Load load) {
            event("startLoad muted=" + gate.isMuted());
            try {
                boolean closed = PlaybackSession.awaitClosed(() -> {
                    polls++;
                    event("poll");
                    return !neverClosed && polls >= closedAfterPolls;
                }, () -> fakeMs, ms -> fakeMs += ms, PlaybackSession.CLOSE_WAIT_MS, PlaybackSession.CLOSE_POLL_MS);
                if (!closed) {
                    load.fail(PlaybackSession.Failure.LOAD_TIMEOUT, "timeout");
                    return;
                }
                event("load");
                LoadedRecording r = LoadedRecording.load(loadCbr, loadJson);
                if (r.problem() != null) {
                    load.fail(PlaybackSession.Failure.UNPLAYABLE, String.valueOf(r.problem()));
                } else {
                    load.succeed(r);
                }
            } catch (InterruptedException e) {
                load.fail(PlaybackSession.Failure.LOAD_ERROR, "interrupted");
            }
        }

        @Override
        public void resetGui() {
            maybeThrow("resetGui");
            event("resetGui");
            chat.clear();
            sent.clear();
            counter = 0;
            title = null;
            bossBar = false;
        }

        @Override
        public void newConnection() {
            maybeThrow("newConnection");
            event("newConnection");
            world = false;
        }

        @Override
        public PacketSink sink() {
            return sink;
        }

        @Override
        public boolean worldPresent() {
            return world;
        }

        @Override
        public boolean gamePaused() {
            return paused;
        }

        @Override
        public int elapsedTicks() {
            return elapsed;
        }

        @Override
        public void setElapsedTicks(int ticks) {
            elapsed = ticks;
        }

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void teardown() {
            event("teardown muted=" + gate.isMuted());
            maybeThrow("teardown");
            world = false;
        }

        @Override
        public void clearTitlesAndBossBar() {
            maybeThrow("clearTitlesAndBossBar");
            event("clearTitles");
            title = null;
            bossBar = false;
        }

        @Override
        public void openTitle() {
            event("openTitle muted=" + gate.isMuted());
            screen = "title";
        }

        @Override
        public void openRejoin() {
            event("openRejoin muted=" + gate.isMuted());
            screen = "connecting";
        }

        @Override
        public void openGallery() {
            event("openGallery muted=" + gate.isMuted());
            screen = "gallery (Done -> title)";
        }

        @Override
        public void log(String line) {
        }

        // restore client

        @Override
        public float timerSpeed() {
            return timerSpeed;
        }

        @Override
        public void setTimerSpeed(float speed) {
            timerSpeed = speed;
        }

        @Override
        public int thirdPersonView() {
            return thirdPerson;
        }

        @Override
        public void setThirdPersonView(int view) {
            thirdPerson = view;
        }

        @Override
        public int difficulty() {
            return difficulty;
        }

        @Override
        public void setDifficulty(int id) {
            difficulty = id;
        }

        @Override
        public void setGamePaused(boolean p) {
            paused = p;
        }

        @Override
        public Object copyChat() {
            event("captureChat muted=" + gate.isMuted());
            return new Object[]{new ArrayList<Line>(chat), new ArrayList<String>(sent)};
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restoreChat(Object saved) {
            event("restoreChat");
            Object[] s = (Object[]) saved;
            chat = new ArrayList<Line>((List<Line>) s[0]);
            sent = new ArrayList<String>((List<String>) s[1]);
        }

        @Override
        public int updateCounter() {
            return counter;
        }

        @Override
        public void setUpdateCounter(int c) {
            counter = c;
        }
    }

    /** {@code Minecraft.runGameLoop}'s tick part, as the bytecode does it. */
    final class Loop {
        boolean inHead;
        boolean inTickBody;
        int worldTicks;
        /** Per world tick: {tick index, sounds muted, particles muted}. */
        final List<long[]> effects = new ArrayList<long[]>();
        /** Per frame: world drawing skipped. */
        final List<Boolean> drawSkipped = new ArrayList<Boolean>();
        int ticksLastFrame;

        void frame(int vanillaElapsed) {
            ops.elapsed = vanillaElapsed;
            session.frameStart();
            int before = worldTicks;
            for (int j = 0; j < ops.elapsed; j++) { // re-read before every pass
                inHead = true;
                session.tickHead();
                inHead = false;
                inTickBody = true;
                if (ops.world && !ops.paused) {
                    worldTicks++;
                    ops.counter++; // GuiIngame.updateTick
                    effects.add(new long[]{session.ticks() == null ? -1 : session.ticks().ticksRun() - 1,
                            session.soundsMuted() ? 1 : 0, session.particlesMuted() ? 1 : 0});
                }
                inTickBody = false;
                ops.nanos += ops.tickCostNs;
                session.tickEnd();
            }
            ops.paused = false; // vanilla: no integrated server, so never paused
            session.afterPauseWrite();
            drawSkipped.add(session.skipWorldDraw());
            ticksLastFrame = worldTicks - before;
            ops.nanos += 1_000_000L;
        }

        void frames(int n, int vanillaElapsed) {
            for (int i = 0; i < n; i++) {
                frame(vanillaElapsed);
            }
        }

        void until(PlaybackSession.State s, int vanillaElapsed) {
            for (int i = 0; i < 5000 && session.state() != s; i++) {
                frame(vanillaElapsed);
            }
            assertEquals(s, session.state());
        }
    }

    PlaybackGate gate;
    FakeOps ops;
    PlaybackSession session;
    Loop loop;

    @Before
    public void client() {
        gate = new PlaybackGate(3000);
        ops = new FakeOps();
        session = new PlaybackSession(ops, gate);
        loop = new Loop();
        ops.chat.add(new Line("old", 400)); // 100 ticks = 5 s old at counter 500
        ops.sent.add("/typed");
    }

    private int index(String event) {
        int i = ops.log.indexOf(event);
        assertTrue("missing " + event + " in " + ops.log, i >= 0);
        return i;
    }

    private int indexStarting(String prefix) {
        for (int i = 0; i < ops.log.size(); i++) {
            if (ops.log.get(i).startsWith(prefix)) {
                return i;
            }
        }
        throw new AssertionError("missing " + prefix + "... in " + ops.log);
    }

    private int lastIndexStarting(String prefix) {
        for (int i = ops.log.size() - 1; i >= 0; i--) {
            if (ops.log.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private void watching() {
        session.open(ID);
        loop.until(PlaybackSession.State.WATCHING, 1);
    }

    /** A seek is applied at the next frame start; then frames until it has landed. */
    private void seekAndSettle(long tick) {
        session.seekTo(tick);
        loop.frame(1);
        loop.until(PlaybackSession.State.WATCHING, 1);
    }

    // ------------------------------------------------------------------ order

    @Test
    public void entryDisconnectsSettlesEntersTheGateShowsTheCardThenWaitsResetsConnectsFeedsHeadersAndTicks() {
        ops.connected = true;
        ops.world = true;
        PlaybackGate.Permit running = gate.tryBegin(null); // a lookup in flight holds the gate for a frame
        session.open(ID);
        assertEquals(PlaybackSession.State.ENTERING_GATE, session.state());
        loop.frame(1);
        assertEquals("entry waits for the running permit", PlaybackSession.State.ENTERING_GATE, session.state());
        assertEquals(-1, lastIndexStarting("startLoad"));
        running.end();
        loop.until(PlaybackSession.State.WATCHING, 1);

        int disconnect = index("disconnect");
        int settle = index("settle muted=false");
        int loading = index("showLoading entering=true");
        int capture = index("captureChat muted=true");
        int start = index("startLoad muted=true");
        int poll = indexStarting("poll");
        int load = index("load");
        int reset = index("resetGui");
        int connection = index("newConnection");
        int header = index("apply 1 outside");
        int firstTick = indexStarting("sample 0");
        assertTrue(ops.log.toString(), disconnect < settle && settle < loading && loading < capture
                && capture < start && start < poll && poll < load && load < reset && reset < connection
                && connection < header && header < firstTick);
        assertEquals("headers before the first tick, at tick 0's head", "sample 0 head", ops.log.get(firstTick));
        assertEquals(START_TICK, session.ticks().ticksRun());
        assertTrue(gate.isMuted());

        session.requestExit(PlaybackSession.Exit.TITLE);
        loop.frame(1);
        int teardown = index("teardown muted=true");
        int title = index("openTitle muted=false");
        assertTrue("gate exit only after teardown", teardown < title);
        assertFalse(ops.log.contains("FED-AFTER-EXIT"));
        for (int i = teardown; i < ops.log.size(); i++) {
            assertFalse("nothing is fed after EXITING starts: " + ops.log.get(i),
                    ops.log.get(i).startsWith("apply") || ops.log.get(i).startsWith("sample"));
        }
        assertEquals(PlaybackSession.State.IDLE, session.state());
        assertFalse(gate.isMuted());
    }

    @Test
    public void anExitRequestedDuringAFrameFeedsNoFurtherTick() {
        watching();
        ops.elapsed = 3;
        session.frameStart();
        session.tickHead();
        session.requestExit(PlaybackSession.Exit.TITLE);
        int samples = ops.sink.samples;
        session.tickEnd();
        assertEquals("the loop ends after the tick that was running", 1, ops.elapsed);
        session.tickHead();
        assertEquals(samples, ops.sink.samples);
        loop.frame(3);
        assertEquals(PlaybackSession.State.IDLE, session.state());
        assertFalse(ops.log.contains("FED-AFTER-EXIT"));
    }

    // ------------------------------------------------------------------ the feed

    @Test
    public void feedingHappensOnlyAtTickHeadsNeverWhilePausedAndOneTickWindowPerWorldTick() {
        watching();
        for (int n : new int[]{1, 3, 0, 2, 4}) {
            int samples = ops.sink.samples;
            int ticks = loop.worldTicks;
            loop.frame(n);
            assertEquals("a frame with N world ticks feeds N tick windows",
                    loop.worldTicks - ticks, ops.sink.samples - samples);
            assertEquals(n, loop.ticksLastFrame);
        }
        session.setPaused(true);
        int samples = ops.sink.samples;
        int ticks = loop.worldTicks;
        loop.frames(5, 3);
        assertEquals("paused: nothing fed", samples, ops.sink.samples);
        assertEquals("paused: the world holds", ticks, loop.worldTicks);
        assertTrue("paused: the pause flag holds the world", ops.paused);
        for (String e : ops.log) {
            if (e.startsWith("apply") && !e.endsWith("head")) {
                assertTrue("only headers are fed outside a tick head: " + e, e.endsWith("outside")
                        && ops.log.indexOf(e) < indexStarting("sample 0"));
            }
            if (e.startsWith("sample")) {
                assertTrue(e, e.endsWith("head"));
            }
        }
    }

    @Test
    public void speedSetsTheTimerAndPauseKeepsItAtOne() {
        watching();
        session.faster();
        loop.frame(1);
        assertEquals(2f, ops.timerSpeed, 0f);
        session.slower();
        session.slower();
        session.slower();
        loop.frame(1);
        assertEquals(0.25f, ops.timerSpeed, 0f);
        session.setPaused(true);
        loop.frame(1);
        assertEquals(1f, ops.timerSpeed, 0f);
        assertTrue(ops.paused);
        session.setPaused(false);
        long before = session.ticks().ticksRun();
        loop.frame(2);
        assertFalse(ops.paused);
        assertEquals("unpausing ticks in the same frame", before + 2, session.ticks().ticksRun());
    }

    // ------------------------------------------------------------------ bounds (round 2 I1)

    @Test
    public void aFrameWhereVanillaWantsFourTicksRunsExactlyTwoForATwoTickSeek() {
        watching();
        session.setPaused(true);
        loop.frame(1);
        long at = session.ticks().ticksRun();
        session.setPaused(false);
        session.seekTo(at + 2);
        int ticks = loop.worldTicks;
        loop.frame(4);
        assertEquals(2, loop.worldTicks - ticks);
        assertEquals(at + 2, session.ticks().ticksRun());
        assertEquals(PlaybackSession.State.WATCHING, session.state());
    }

    @Test
    public void theEndTickReachedInTheSecondOfFourVanillaTicksStopsTheLoopThere() {
        watching();
        seekAndSettle(END_TICK - 2);
        assertEquals(END_TICK - 2, session.ticks().ticksRun());
        ops.elapsed = 4;
        session.frameStart();
        ops.elapsed = 4; // a cap at the frame start alone is not enough: the RETURN hook must stop the loop
        int runs = 0;
        for (int j = 0; j < ops.elapsed; j++) {
            session.tickHead();
            runs++;
            session.tickEnd();
        }
        session.afterPauseWrite();
        assertEquals(2, runs);
        assertEquals(END_TICK, session.ticks().ticksRun());
        assertTrue(session.ticks().paused());
        assertTrue(ops.paused);
        int ticks = loop.worldTicks;
        loop.frames(3, 4);
        assertEquals("no tick past the end", ticks, loop.worldTicks);
    }

    @Test
    public void aSeekWhilePausedRunsItsCatchUpTicksThenNoFurtherTick() {
        watching();
        session.setPaused(true);
        loop.frame(2);
        long at = session.ticks().ticksRun();
        session.seekTo(at + 7);
        loop.frame(1);
        assertEquals(at + 7, session.ticks().ticksRun());
        assertTrue(session.ticks().paused());
        assertTrue("held paused after the catch-up", ops.paused);
        loop.frames(4, 3);
        assertEquals("no further tick", at + 7, session.ticks().ticksRun());
    }

    @Test
    public void aCatchUpLongerThanTheBudgetSpansFramesAndResumesAtTheNextTick() {
        ops.tickCostNs = 5_000_000L; // 5 ms per tick: 5 ticks fit the 24 ms budget behind the card
        session.open(ID);
        loop.until(PlaybackSession.State.CATCHING_UP, 1);
        List<Long> perFrame = new ArrayList<Long>();
        long last = session.ticks().ticksRun();
        while (session.state() == PlaybackSession.State.CATCHING_UP) {
            loop.frame(0);
            perFrame.add(session.ticks().ticksRun() - last);
            last = session.ticks().ticksRun();
        }
        assertTrue("spans frames: " + perFrame, perFrame.size() >= 3);
        assertEquals(Long.valueOf(5), perFrame.get(1));
        assertEquals(START_TICK, session.ticks().ticksRun());
        // every tick 0..19 was fed exactly once, in order
        List<String> samples = new ArrayList<String>();
        for (String e : ops.log) {
            if (e.startsWith("sample")) {
                samples.add(e);
            }
        }
        for (int k = 0; k < START_TICK; k++) {
            assertEquals("sample " + k + " head", samples.get(k));
        }

        // in view (a forward seek) the budget is 8 ms: 2 ticks per frame
        session.seekTo(START_TICK + 30);
        loop.frame(0);
        assertEquals(PlaybackSession.State.CATCHING_UP, session.state());
        assertEquals(START_TICK + 2, session.ticks().ticksRun());
    }

    // ------------------------------------------------------------------ catch-up effects

    @Test
    public void soundsAreMutedForTheWholeCatchUpParticlesExceptItsLastSixtyTicksAndTheWorldDrawnInItsLastForty()
            throws IOException {
        File longer = recording(tmp.newFolder("long"), 400);
        ops.loadCbr = longer;
        ops.loadJson = new File(longer.getParentFile(), ID + ".json");
        watching(); // opening: catch up to tick 20
        seekAndSettle(300); // forward, in place
        assertEquals(300, session.ticks().ticksRun());
        ops.tickCostNs = 1_000_000L; // 24 ticks per frame behind the card
        session.seekTo(150); // backward: rebuild behind the card
        loop.drawSkipped.clear();
        loop.effects.clear();
        loop.frame(0);
        assertTrue(session.behindCard() || session.state() == PlaybackSession.State.WATCHING);
        while (session.state() == PlaybackSession.State.CATCHING_UP) {
            loop.frame(0);
        }
        assertEquals(150, session.ticks().ticksRun());
        assertEquals(150, loop.effects.size());
        for (long[] e : loop.effects) {
            long k = e[0];
            assertEquals("sound at tick " + k, 1, e[1]);
            assertEquals("particles at tick " + k, 150 - k > SeekPlanner.PARTICLE_TAIL_TICKS ? 1 : 0, e[2]);
        }
        // world drawing: skipped behind the card until the last 40 ticks, then drawn
        assertTrue(loop.drawSkipped.get(0));
        assertFalse(loop.drawSkipped.get(loop.drawSkipped.size() - 1));
        loop.effects.clear();
        loop.frames(3, 1);
        for (long[] e : loop.effects) {
            assertEquals("watching: sounds play", 0, e[1]);
            assertEquals("watching: particles play", 0, e[2]);
        }
        // a forward seek fast-forwards in view: the world is drawn
        session.seekTo(session.ticks().ticksRun() + 100);
        loop.drawSkipped.clear();
        loop.frame(0);
        assertFalse(session.behindCard());
        assertFalse(loop.drawSkipped.get(0));
    }

    // ------------------------------------------------------------------ GUI reset (round 2 B2)

    @Test
    public void everyRestartResetsTheGuiBeforeTheFirstHeaderAndExitRestoresChatWithItsAge() {
        watching();
        int firstReset = index("resetGui");
        assertTrue(firstReset < index("apply 1 outside"));
        assertNull("titles cleared", ops.title);
        assertFalse("boss bar cleared", ops.bossBar);
        loop.frames(40, 1);
        assertTrue("recorded chat arrived", ops.chat.size() >= 3);
        assertEquals("the update counter runs from the restart", 0 + loop.worldTicks, ops.counter);

        int mark = ops.log.size();
        session.seekTo(START_TICK + 5); // backward
        loop.frame(0);
        List<String> after = ops.log.subList(mark, ops.log.size());
        int reset = after.indexOf("resetGui");
        int header = after.indexOf("apply 1 outside");
        assertTrue("reset before the rebuild's first header: " + after, reset >= 0 && reset < header);
        loop.until(PlaybackSession.State.WATCHING, 1);

        session.requestExit(PlaybackSession.Exit.TITLE);
        loop.frame(1);
        assertEquals(1, ops.chat.size());
        assertEquals("old", ops.chat.get(0).text);
        assertEquals(500, ops.counter);
        assertEquals("a line 5 s old at entry is 5 s old after exit", 100, ops.counter - ops.chat.get(0).created);
        assertEquals("/typed", ops.sent.get(0));
        assertTrue(index("openTitle muted=false") < index("restoreChat"));
    }

    // ------------------------------------------------------------------ wait-for-closed

    @Test
    public void awaitClosedPollsUntilTheFileClosesOrTenSecondsPass() throws InterruptedException {
        final AtomicInteger polls = new AtomicInteger();
        final AtomicLong ms = new AtomicLong();
        assertTrue(PlaybackSession.awaitClosed(() -> polls.incrementAndGet() >= 3, ms::get, ms::addAndGet,
                PlaybackSession.CLOSE_WAIT_MS, PlaybackSession.CLOSE_POLL_MS));
        assertEquals(3, polls.get());
        assertEquals(2 * PlaybackSession.CLOSE_POLL_MS, ms.get());

        polls.set(0);
        ms.set(0);
        assertFalse(PlaybackSession.awaitClosed(() -> {
            polls.incrementAndGet();
            return false;
        }, ms::get, ms::addAndGet, PlaybackSession.CLOSE_WAIT_MS, PlaybackSession.CLOSE_POLL_MS));
        assertTrue(ms.get() >= PlaybackSession.CLOSE_WAIT_MS);
        assertTrue(ms.get() < PlaybackSession.CLOSE_WAIT_MS + PlaybackSession.CLOSE_POLL_MS);
    }

    @Test
    public void aFileThatClosesAfterThreePollsOpensAndOneStillOpenAfterTenSecondsEndsInTheGallery() {
        ops.closedAfterPolls = 3;
        watching();
        assertEquals(3, ops.polls);

        session.requestExit(PlaybackSession.Exit.TITLE);
        loop.frame(1);
        ops.neverClosed = true;
        ops.log.clear();
        session.open(ID);
        loop.until(PlaybackSession.State.IDLE, 1);
        assertTrue(ops.fakeMs >= PlaybackSession.CLOSE_WAIT_MS);
        assertEquals(PlaybackSession.Failure.LOAD_TIMEOUT, session.lastFailure());
        assertEquals("gallery (Done -> title)", ops.screen);
        assertEquals(-1, lastIndexStarting("newConnection"));
    }

    // ------------------------------------------------------------------ failure destinations (round 1 I3)

    enum Entry { PAUSE_MENU, TITLE_GALLERY, SESSION_GALLERY }

    enum Fail { LOAD_TIMEOUT, PROBLEM, PACKETS_FAILED, HOOK_THREW, RESTART_THREW }

    @Test
    public void everyFailureEndsInTheGalleryFromEveryEntryAndTheGallerysDoneLeadsToTheTitle() throws IOException {
        File garbage = new File(tmp.newFolder("bad"), ID + ".cbr");
        FileOutputStream o = new FileOutputStream(garbage);
        o.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        o.close();
        for (Entry entry : Entry.values()) {
            for (Fail fail : Fail.values()) {
                client();
                String what = entry + " / " + fail;
                if (entry == Entry.PAUSE_MENU) {
                    ops.connected = true;
                    ops.world = true;
                } else if (entry == Entry.SESSION_GALLERY) {
                    watching();
                    loop.frames(5, 1);
                }
                switch (fail) {
                    case LOAD_TIMEOUT:
                        ops.neverClosed = true;
                        break;
                    case PROBLEM:
                        ops.loadCbr = garbage;
                        break;
                    case PACKETS_FAILED:
                        ops.sink.failIds.add(0x03);
                        break;
                    case HOOK_THREW:
                        ops.sink.throwInSampleAtTick = 3;
                        break;
                    case RESTART_THREW:
                        ops.throwIn = "newConnection";
                        break;
                    default:
                        break;
                }
                ops.log.clear();
                session.open(ID);
                for (int i = 0; i < 2000 && (session.active() || ops.log.indexOf("openGallery muted=false") < 0); i++) {
                    loop.frame(1);
                }
                assertEquals(what, PlaybackSession.State.IDLE, session.state());
                assertEquals(what, "gallery (Done -> title)", ops.screen);
                assertTrue(what + ": " + ops.log, ops.log.contains("openGallery muted=false"));
                assertFalse(what, ops.log.contains("openTitle muted=false"));
                assertFalse(what + " gate open", gate.isMuted() || gate.isEntering());
                assertNotNull(what, session.lastFailure());
                assertEquals(what, 1f, ops.timerSpeed, 0f);
                assertEquals(what, 2, ops.thirdPerson);
                assertEquals(what, 3, ops.difficulty);
                assertFalse(what, ops.paused);
                assertEquals(what, "old", ops.chat.get(0).text);
                assertEquals(what, 500, ops.counter);
            }
        }
    }

    // ------------------------------------------------------------------ restore and screen policy

    @Test
    public void restoreRunsOnEveryExitPathEvenWhenAStepThrows() {
        PlaybackSession.Exit[] exits = {PlaybackSession.Exit.TITLE, PlaybackSession.Exit.REJOIN,
                PlaybackSession.Exit.GALLERY};
        String[] throwing = {null, "teardown", "clearTitlesAndBossBar", "showViewer", "resetGui"};
        for (PlaybackSession.Exit exit : exits) {
            for (String t : throwing) {
                client();
                String what = exit + " / " + t;
                session.open(ID);
                ops.throwIn = t;
                for (int i = 0; i < 500 && session.active() && session.state() != PlaybackSession.State.WATCHING;
                     i++) {
                    loop.frame(1);
                }
                if (session.active()) {
                    session.faster();
                    ops.thirdPerson = 1;
                    ops.difficulty = 0;
                    session.setPaused(true);
                    loop.frame(1);
                    session.requestExit(exit);
                    loop.frame(0); // no vanilla tick after the exit, so the counter is read as restored
                }
                assertEquals(what, PlaybackSession.State.IDLE, session.state());
                assertFalse(what, gate.isMuted() || gate.isEntering());
                assertEquals(what, 1f, ops.timerSpeed, 0f);
                assertEquals(what, 2, ops.thirdPerson);
                assertEquals(what, 3, ops.difficulty);
                assertFalse(what, ops.paused);
                assertEquals(what, 1, ops.chat.size());
                assertEquals(what, 500, ops.counter);
                assertNull(what, ops.title);
                assertFalse(what, ops.bossBar);
                assertTrue(what, ops.screen.equals("title") || ops.screen.equals("connecting")
                        || ops.screen.equals("gallery (Done -> title)"));
                // commands on an ended session are harmless
                Object screen = ops.screen;
                session.faster();
                session.togglePlay();
                session.seekTo(5);
                session.requestExit(exit);
                loop.frame(1);
                assertEquals(what, PlaybackSession.State.IDLE, session.state());
                assertEquals(what, screen, ops.screen);
            }
        }
    }

    @Test
    public void openingAnotherGameWhileWatchingEndsTheFirstThenStartsTheSecondFromTheSavedState() {
        watching();
        loop.frames(10, 1);
        session.faster();
        session.open(ID);
        loop.frame(1);
        assertTrue(gate.isEntering() || gate.isMuted());
        loop.until(PlaybackSession.State.WATCHING, 1);
        assertEquals(START_TICK, session.ticks().ticksRun());
        assertEquals(1f, session.ticks().speed(), 0f);
        session.requestExit(PlaybackSession.Exit.TITLE);
        loop.frame(1);
        assertEquals("the chat from before the first game", "old", ops.chat.get(0).text);
        assertEquals(1, ops.chat.size());
        assertEquals(500, ops.counter);
        assertEquals(1f, ops.timerSpeed, 0f);
        assertFalse(gate.isMuted());
    }

    @Test
    public void theScreenPolicyPassesOnlySessionScreensWhileActive() {
        Object vanilla = "GuiDownloadTerrain";
        assertTrue("no session: everything passes", session.allowScreen(vanilla, false));
        assertTrue(session.allowScreen(null, false));
        session.open(ID);
        assertFalse(session.allowScreen(vanilla, false));
        assertFalse("null (close or title) is ignored", session.allowScreen(null, false));
        assertTrue("a Rewind screen passes", session.allowScreen("gallery", true));
        Object approved = "esc menu";
        session.approve(approved);
        assertTrue(session.allowScreen(approved, false));
        loop.until(PlaybackSession.State.WATCHING, 1);
        assertTrue(String.valueOf(ops.screen), String.valueOf(ops.screen).startsWith("viewer"));
        assertFalse(session.allowScreen("GuiChest", false));
        assertFalse(session.allowScreen("GuiWinGame", false));
        session.requestExit(PlaybackSession.Exit.TITLE);
        loop.frame(1);
        assertTrue(session.allowScreen(vanilla, false));
    }

    @Test
    public void playAtTheEndRestartsAtTheGameStart() {
        watching();
        seekAndSettle(END_TICK);
        loop.frames(2, 1);
        assertTrue(session.ticks().paused());
        session.togglePlay();
        loop.frame(1);
        loop.until(PlaybackSession.State.WATCHING, 1);
        assertEquals(START_TICK, session.ticks().ticksRun());
        assertFalse(session.ticks().paused());
    }
}
