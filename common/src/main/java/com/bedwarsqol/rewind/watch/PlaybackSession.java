package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.export.ExportPlan;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * One Rewind playback session on the client thread (PLAN section 3), against {@link ClientOps}.
 *
 * <p><b>States.</b> IDLE -> DISCONNECTING -> ENTERING_GATE -> LOADING_FILE -> (REBUILDING) -> CATCHING_UP -> WATCHING;
 * a backward seek goes REBUILDING -> CATCHING_UP, a forward one CATCHING_UP in place; any exit goes EXITING -> IDLE.
 * <ol>
 *   <li>{@link #open}: disconnect if connected (vanilla's Disconnect calls), settle R1's recorder (PA1), begin gate
 *       entry, show the loading card. Called while a session runs, it ends that one first (another game).</li>
 *   <li>Each frame until gate entry completes ({@link PlaybackGate#tryCompleteEntry}, the 3 s drain), so the card
 *       keeps drawing; then the restore state is captured ({@link PlaybackRestore}) and the file is loaded off the
 *       client thread after it has closed ({@link #awaitClosed}, at most {@link #CLOSE_WAIT_MS}).</li>
 *   <li>Restart (also every backward seek): GUI reset, F5 view 0, HUD shown, F3 and smooth camera off (B3), a fresh
 *       socketless connection, the start segment's headers, then a catch-up to the target with the game's own
 *       ticks.</li>
 * </ol>
 *
 * <p><b>Frames and ticks</b> (round 2 I1; parity review B4). Vanilla's {@code runGameLoop} runs {@code runTick} while
 * its counter is below {@code timer.elapsedTicks}, re-reading it before every pass, and writes {@code isGamePaused} after
 * the loop. {@link #frameStart} (right after {@code Timer.updateTimer}) decides this frame's ticks: while WATCHING the
 * recorded clock does ({@link TickClock}: playback time advances by wall time x speed, the ticks of the recorded
 * {@link TickTable} it has reached are due, at most 10 and never past the end; {@code renderPartialTicks} is the share
 * of the recorded interval reached; the timer speed stays 1), and the time-scoped extension records it has reached are
 * dispatched ({@link PacketSink#timed}); during a catch-up {@code elapsedTicks} is 1, raised by {@link #tickEnd} one
 * tick at a time while the frame's budget lasts (24 ms behind the card, 8 ms in view). It also writes the view's
 * perspective, hidden HUD and F3 from {@link ViewState}, and forces the pause flag for this frame's ticks.
 * {@link #tickHead} feeds the tick's records ({@link TickFeeder#feedForTick}) only when the world will tick, so every
 * world tick has exactly its records before it. {@link #tickEnd} at a stop sets {@code elapsedTicks} to the passes run,
 * which ends vanilla's loop after this tick: no tick runs past a seek target or the end, paused or not.
 * {@link #afterPauseWrite} forces the pause flag again after vanilla wrote it. A seek or catch-up sets playback time to
 * the time of the tick it lands on and plays no time-scoped record it passed.
 *
 * <p><b>Export</b> ({@link #beginExport}, Rewind Export PLAN Solution 1): while an export runs, each frame's playback
 * time is the export's next frame time, not wall time x speed; pause, speed and seeks from the viewer do nothing.
 *
 * <p><b>Deferred work.</b> Seeks and exits asked for by screens (whose input may arrive inside a tick) are applied at
 * the next {@link #frameStart}, outside {@code runTick}, so a world is never rebuilt in the middle of a tick. A hook
 * that throws, more than {@link TickFeeder#MAX_FAILURES} failed packets, an unreadable file or one not closed within
 * {@link #CLOSE_WAIT_MS} end the session in the gallery, whichever entry opened it (round 1 I3).
 *
 * <p><b>Exit</b>, on every path: teardown, titles and boss bar cleared, settings restored, gate exit (unmute, epoch +
 * 1), the destination, then chat and the update counter restored (round 2 B2). Every step runs even if an earlier one
 * throws. Nothing is fed once EXITING starts.
 *
 * <p><b>Screen policy.</b> While a session is active, only Rewind screens or screens the session approved
 * ({@link #approve}) may open; recorded screens (download terrain, chests, credits) and closing to nothing are ignored.
 *
 * <p>Pure apart from {@link ClientOps}; the client thread only, except {@link Load}, which a loader thread completes.
 */
public final class PlaybackSession {

    /** How long playback waits for R1 to close the file it is opening. */
    public static final long CLOSE_WAIT_MS = 10_000L;
    /** How often it looks. */
    public static final long CLOSE_POLL_MS = 100L;
    /** A frame's catch-up budget behind the loading card. */
    public static final long CARD_BUDGET_NS = 24_000_000L;
    /** A frame's catch-up budget in view (a forward seek). */
    public static final long VIEW_BUDGET_NS = 8_000_000L;

    public enum State { IDLE, DISCONNECTING, ENTERING_GATE, LOADING_FILE, CATCHING_UP, WATCHING, REBUILDING, EXITING }

    /** Where a session ends. */
    public enum Exit {
        /** Quit to Title. */
        TITLE,
        /** Rejoin Hypixel: reconnect to the server that was left. */
        REJOIN,
        /** The gallery (every failure); its Done leads to the title screen. */
        GALLERY,
        /** Another recording: this session ends and the next one opens. */
        NEXT_GAME
    }

    /** Why a session failed (counts-only logs and tests). */
    public enum Failure {
        /** R1 had not closed the file within {@link #CLOSE_WAIT_MS}. */
        LOAD_TIMEOUT,
        /** {@link LoadedRecording#problem()} is not null. */
        UNPLAYABLE,
        /** The loader threw. */
        LOAD_ERROR,
        /** More than {@link TickFeeder#MAX_FAILURES} packets failed. */
        PACKETS_FAILED,
        /** A session step or hook threw. */
        HOOK_THREW
    }

    /** {@code Thread.sleep} (a fake clock in tests). */
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    /**
     * The result of one background load, completed once by the loader thread and read by the session on the client
     * thread. A load the session no longer wants ({@link #cancelled()}) closes the recording it is handed.
     */
    public static final class Load {
        private boolean done;
        private boolean cancelled;
        private LoadedRecording recording;
        private Failure failure;
        private String detail;

        public synchronized void succeed(LoadedRecording r) {
            if (done || cancelled) {
                if (r != null) {
                    r.close();
                }
                done = true;
                return;
            }
            recording = r;
            done = true;
        }

        public synchronized void fail(Failure f, String why) {
            if (done) {
                return;
            }
            failure = f == null ? Failure.LOAD_ERROR : f;
            detail = why;
            done = true;
        }

        /** The session gave up on this load; the loader may stop early. */
        public synchronized boolean cancelled() {
            return cancelled;
        }

        synchronized void cancel() {
            cancelled = true;
            if (recording != null) {
                recording.close();
                recording = null;
            }
        }

        synchronized boolean isDone() {
            return done;
        }

        synchronized LoadedRecording take() {
            LoadedRecording r = recording;
            recording = null;
            return r;
        }

        synchronized Failure failure() {
            return failure;
        }

        synchronized String detail() {
            return detail;
        }
    }

    /**
     * Waits until {@code closed} is true, looking every {@code pollMs}, for at most {@code timeoutMs} (one last look at
     * the bound). Runs on the loader thread.
     *
     * @return true once closed, false after the bound
     */
    public static boolean awaitClosed(BooleanSupplier closed, LongSupplier nowMs, Sleeper sleeper, long timeoutMs,
                                      long pollMs) throws InterruptedException {
        long start = nowMs.getAsLong();
        while (true) {
            if (closed.getAsBoolean()) {
                return true;
            }
            long waited = nowMs.getAsLong() - start;
            if (waited >= timeoutMs) {
                return false;
            }
            sleeper.sleep(Math.max(1L, Math.min(pollMs, timeoutMs - waited)));
        }
    }

    private final ClientOps ops;
    private final PlaybackGate gate;
    private final PlaybackRestore.Errors errors;
    private final ViewState view = new ViewState();

    private State state = State.IDLE;
    private String id;
    private RecordingMeta meta;
    private Load load;
    private LoadedRecording recording;
    private Timeline timeline;
    private TickFeeder feeder;
    private ReplayTicks ticks;
    private TickClock clock;
    private PlaybackRestore restore;
    private boolean gateBegun;
    private boolean connectionOpen;
    private boolean behindCard;
    private boolean loadingShown;
    private Exit pendingExit;
    private String nextId;
    private boolean seekPending;
    private long seekTarget;
    private Object approved;
    private Failure lastFailure;
    private Exit lastExit;

    private int loops;
    private boolean worldTicked;
    private long deadline;

    private long openNanos;
    private long gateNanos;
    private long gateEntryMs;
    private boolean opened;
    private long catchUpNanos;
    private long catchUpFrom;
    private boolean catchUpRestart;
    /** A seek while watching whose target is the tick already reached: no tick runs, playback time stays. */
    private boolean seekInPlace;
    /** The recorded time of the tick running now (between {@link #tickHead} and {@link #tickEnd}). */
    private long tickTimeUs;

    /** Where a running export is ({@link #beginExport}). */
    private enum ExportStage {
        /** The seek to the pre-roll's start has not landed yet. */
        SEEKING,
        /** Uncaptured frames from the pre-roll's start up to the range start. */
        PREROLL,
        /** The frames to capture. */
        FRAMES,
        /** The last frame was captured; playback time holds until the tail is asked for. */
        LAST_TAKEN,
        /** The tail step ran. */
        TAIL_DONE
    }

    private ExportPlan export;
    private ExportStage exportStage;
    private long exportPreUs;
    private long exportIndex;
    /** The frame {@link #exportIndex} was captured ({@link #advanceExportFrame}); the next frame moves on. */
    private boolean exportTaken;
    private boolean tailAsked;
    /** The next pre-roll frame on the export's grid ({@link #gridTime}; negative). */
    private long prerollGrid;
    /** The export's steps keep to real time (Rewind Export PLAN Revision 4.1); off only for raw timing runs. */
    private boolean exportPaced;
    /** Wall time ({@link ClientOps#nanoTime}) the last step was really applied, or the seek landed; MIN: not yet. */
    private long exportAppliedNs;
    /** This pass held the next step back (the real-time floor). */
    private boolean exportWaiting;

    public PlaybackSession(ClientOps ops, PlaybackGate gate) {
        this.ops = ops;
        this.gate = gate;
        this.errors = new PlaybackRestore.Errors() {
            @Override
            public void failed(String step, Throwable t) {
                log("rewind-watch: step failed: " + step + " " + t.getClass().getSimpleName());
            }
        };
    }

    // ================================================================== entry

    /**
     * Opens recording {@code recordingId} (G16 Watch Last Game, the gallery's Watch). Disconnects first when
     * connected. While a session is active it ends that one at the next frame and then opens this one.
     */
    public void open(String recordingId) {
        if (recordingId == null) {
            return;
        }
        if (state != State.IDLE) {
            if (pendingExit == null) {
                pendingExit = Exit.NEXT_GAME;
                nextId = recordingId;
            }
            return;
        }
        begin(recordingId);
    }

    private void begin(String recordingId) {
        clearSession();
        lastFailure = null;
        lastExit = null;
        view.reset();
        id = recordingId;
        state = State.DISCONNECTING;
        openNanos = ops.nanoTime();
        try {
            meta = ops.readMeta(recordingId);
            if (ops.connected()) {
                ops.disconnect();
            }
            ops.settleRecorder();
            gateNanos = ops.nanoTime();
            gate.beginEntry();
            gateBegun = true;
            state = State.ENTERING_GATE;
            behindCard = true;
            showLoading();
        } catch (Throwable t) {
            failNow(Failure.HOOK_THREW, t);
        }
    }

    // ================================================================== frame and tick hooks

    /** {@code runGameLoop}, right after {@code Timer.updateTimer}. */
    public void frameStart() {
        loops = 0;
        worldTicked = false;
        if (state == State.IDLE) {
            return;
        }
        try {
            if (pendingExit != null) {
                Exit e = pendingExit;
                pendingExit = null;
                exitNow(e);
                return;
            }
            if (state == State.ENTERING_GATE) {
                stepGate();
            } else if (state == State.LOADING_FILE) {
                pollLoad();
            }
            if (state == State.CATCHING_UP || state == State.WATCHING) {
                applySeek();
            }
            if (state == State.CATCHING_UP || state == State.WATCHING) {
                control();
            }
        } catch (Throwable t) {
            failNow(Failure.HOOK_THREW, t);
        }
    }

    private void stepGate() {
        if (!gate.tryCompleteEntry()) {
            return;
        }
        gateEntryMs = (ops.nanoTime() - gateNanos) / 1_000_000L;
        restore = PlaybackRestore.capture(ops);
        state = State.LOADING_FILE;
        load = new Load();
        ops.startLoad(id, load);
        pollLoad();
    }

    private void pollLoad() {
        if (load == null || !load.isDone()) {
            return;
        }
        Load l = load;
        load = null;
        if (l.failure() != null) {
            log("rewind-watch: load failed " + l.failure());
            failNow(l.failure(), null);
            return;
        }
        recording = l.take();
        if (recording == null || recording.problem() != null || recording.timeline() == null) {
            failNow(Failure.UNPLAYABLE, null);
            return;
        }
        timeline = recording.timeline();
        feeder = new TickFeeder(recording);
        ticks = new ReplayTicks(timeline.endTick());
        clock = new TickClock(timeline.ticks());
        restart(SeekPlanner.opening(timeline));
    }

    private void applySeek() {
        if (!seekPending) {
            return;
        }
        seekPending = false;
        SeekPlanner.Plan plan = SeekPlanner.plan(ticks.ticksRun(), seekTarget);
        if (plan.restart) {
            restart(plan);
        } else {
            // review code-2 O10: a target already reached (a click inside the current interval, a short seekBy, a
            // marker there) runs no tick, so playback time and the timed cursor stay where straight play has them
            seekInPlace = state == State.WATCHING && plan.catchUpTicks == 0;
            startCatchUp(false);
            ticks.seek(plan);
            afterSeek();
            seekInPlace = false;
        }
    }

    /**
     * GUI reset, F5 view 0, HUD shown, F3 and smooth camera off (B3), a fresh connection, the headers, then the
     * catch-up (PLAN section 3 step 5).
     */
    private void restart(SeekPlanner.Plan plan) {
        state = State.REBUILDING;
        behindCard = true;
        startCatchUp(true);
        ops.resetGui();
        ops.setThirdPersonView(0);
        ops.setHideGui(false);
        ops.setShowDebugInfo(false);
        ops.setSmoothCamera(false);
        connectionOpen = true;
        ops.newConnection();
        feeder.restart();
        ticks.seek(plan);
        feeder.feedHeaders(ops.sink());
        if (feeder.state() == TickFeeder.State.FAILED) {
            failNow(Failure.PACKETS_FAILED, null);
            return;
        }
        if (!loadingShown) {
            showLoading();
        }
        afterSeek();
    }

    private void afterSeek() {
        if (ticks.catchingUp()) {
            state = State.CATCHING_UP;
        } else {
            finishCatchUp();
        }
    }

    private void startCatchUp(boolean isRestart) {
        catchUpNanos = ops.nanoTime();
        catchUpFrom = isRestart ? 0 : ticks.ticksRun();
        catchUpRestart = isRestart;
    }

    private void finishCatchUp() {
        state = State.WATCHING;
        if (export != null) {
            exportAppliedNs = ops.nanoTime(); // the seek landed: the first step waits from here (Revision 4.1)
        }
        if (!seekInPlace) {
            clock.set(timeline.timeOfTick(ticks.ticksRun()), ops.nanoTime());
            clock.skipTimed(recording.ext());
        }
        long ms = (ops.nanoTime() - catchUpNanos) / 1_000_000L;
        long ran = ticks.ticksRun() - catchUpFrom;
        if (!opened) {
            opened = true;
            log("rewind-watch: openMs=" + (ops.nanoTime() - openNanos) / 1_000_000L + " gateEntryMs=" + gateEntryMs
                    + " catchUpTicks=" + ran + " catchUpMs=" + ms + " applied=" + feeder.applied()
                    + " failures=" + feeder.failures());
        } else {
            log("rewind-watch: seek restart=" + catchUpRestart + " catchUpTicks=" + ran + " catchUpMs=" + ms);
        }
        if (behindCard) {
            behindCard = false;
            loadingShown = false;
            ops.showViewer();
        }
    }

    private void control() {
        if (state == State.CATCHING_UP) {
            ops.setTimerSpeed(1f);
            ops.setElapsedTicks((int) Math.min(1L, ticks.ticksToNextStop()));
            ops.setGamePaused(false);
            ops.setPartialTicks(1f);
            deadline = ops.nanoTime() + (behindCard ? CARD_BUDGET_NS : VIEW_BUDGET_NS);
        } else if (export != null) {
            exportControl();
        } else {
            boolean paused = ticks.paused();
            clock.advance(ops.nanoTime(), ticks.speed(), paused);
            int due = paused ? 0 : clock.due(ticks.ticksRun(), ticks.ticksToNextStop());
            ops.setTimerSpeed(1f);
            ops.setElapsedTicks(due);
            ops.setGamePaused(paused);
            ops.setPartialTicks(clock.partial(ticks.ticksRun() + due - 1));
            clock.dispatchTimed(recording.ext(), ops.sink());
        }
        ops.setThirdPersonView(view.perspective());
        ops.setHideGui(view.hideGui());
        ops.setShowDebugInfo(view.showDebug());
    }

    /** {@code runTick} HEAD: feeds the tick's records when the world will tick. */
    public void tickHead() {
        loops++;
        worldTicked = false;
        if ((state != State.CATCHING_UP && state != State.WATCHING) || pendingExit != null) {
            return;
        }
        if (!ops.worldPresent() || ops.gamePaused()) {
            return;
        }
        if (!ticks.catchingUp() && (ticks.paused() || ticks.ticksToNextStop() == 0)) {
            ops.setGamePaused(true); // a world tick without its records would break the tick grid
            return;
        }
        try {
            long k = ticks.beginTick();
            tickTimeUs = timeline.ticks().timeOf(k);
            worldTicked = true;
            TickFeeder.State s = feeder.feedForTick(k, ops.sink());
            if (s == TickFeeder.State.FAILED) {
                fail(Failure.PACKETS_FAILED, null);
            } else if (s == TickFeeder.State.ENDED) {
                ticks.endAt(feeder.endedAtTick() + 1);
            }
        } catch (Throwable t) {
            fail(Failure.HOOK_THREW, t);
        }
    }

    /** {@code runTick} RETURN: ends vanilla's loop at a stop, or allows one more catch-up tick. */
    public void tickEnd() {
        if (state == State.IDLE) {
            return;
        }
        if (pendingExit != null) {
            ops.setElapsedTicks(Math.min(ops.elapsedTicks(), loops));
            return;
        }
        if (!worldTicked) {
            return;
        }
        worldTicked = false;
        if (export != null) {
            // this tick's records were fed and Minecraft has run it: the next paced step counts from now (code
            // review 1 B1), not from the frame start that asked for it
            exportAppliedNs = ops.nanoTime();
        }
        try {
            boolean stop = ticks.finishTick();
            if (stop) {
                ops.setElapsedTicks(loops);
                if (state == State.CATCHING_UP && !ticks.catchingUp()) {
                    finishCatchUp();
                }
            } else if (state == State.CATCHING_UP) {
                ops.setElapsedTicks(ops.nanoTime() < deadline ? loops + 1 : loops);
            } else if (ticks.paused()) {
                ops.setElapsedTicks(loops); // paused during this frame
            }
        } catch (Throwable t) {
            fail(Failure.HOOK_THREW, t);
            ops.setElapsedTicks(Math.min(ops.elapsedTicks(), loops));
        }
    }

    /** {@code runGameLoop}, after vanilla wrote {@code isGamePaused}. */
    public void afterPauseWrite() {
        if (pendingExit != null) {
            return;
        }
        if (state == State.WATCHING && ticks.paused()) {
            ops.setGamePaused(true);
        } else if (state == State.CATCHING_UP) {
            ops.setGamePaused(false);
        }
    }

    /** {@code renderWorld} HEAD: skip drawing behind the opaque card, except a catch-up's last 40 ticks. */
    public boolean skipWorldDraw() {
        return state == State.CATCHING_UP && behindCard
                && ticks.target() - ticks.ticksRun() > SeekPlanner.DRAW_TAIL_TICKS;
    }

    /** Sounds: muted while the world is rebuilt and for the whole catch-up. */
    public boolean soundsMuted() {
        return state == State.REBUILDING || state == State.CATCHING_UP && ticks.soundsMuted();
    }

    /** Particles: muted while rebuilt and during a catch-up except its last 60 ticks. */
    public boolean particlesMuted() {
        return state == State.REBUILDING || state == State.CATCHING_UP && ticks.particlesMuted();
    }

    // ================================================================== screens

    /** The next screen the session itself opens (call right before {@code displayGuiScreen}). */
    public void approve(Object screen) {
        approved = screen;
    }

    /**
     * {@code displayGuiScreen} HEAD. Inactive: everything. Active: a Rewind screen ({@code rewindScreen}) or the screen
     * last {@link #approve approved}; never null (closing to nothing, or the title screen vanilla opens for null).
     */
    public boolean allowScreen(Object screen, boolean rewindScreen) {
        if (state == State.IDLE || state == State.EXITING) {
            return true;
        }
        if (screen == null) {
            return false;
        }
        return rewindScreen || screen == approved;
    }

    private void showLoading() {
        loadingShown = true;
        ops.showLoading();
    }

    // ================================================================== commands (from screens)

    private boolean playing() {
        return (state == State.CATCHING_UP || state == State.WATCHING) && ticks != null && pendingExit == null;
    }

    /** Playing and no export runs: the viewer's play, speed and seek commands apply. */
    private boolean steerable() {
        return playing() && export == null;
    }

    /** Space / K: play or pause; at the end it plays from the game start. */
    public void togglePlay() {
        if (!steerable()) {
            return;
        }
        if (ticks.togglePlay() == ReplayTicks.PlayToggle.RESTART) {
            seekTo(timeline.startTick());
        }
    }

    public void setPaused(boolean paused) {
        if (steerable()) {
            ticks.setPaused(paused);
        }
    }

    public void faster() {
        if (steerable()) {
            ticks.faster();
        }
    }

    public void slower() {
        if (steerable()) {
            ticks.slower();
        }
    }

    /** Seeks to {@code tick} (clamped to the timeline) at the next frame. */
    public void seekTo(long tick) {
        if (!steerable()) {
            return;
        }
        seekTarget = SeekPlanner.clamp(timeline, tick);
        seekPending = true;
    }

    /** Seeks {@code deltaUs} from the playhead (or from a seek not applied yet), in whole ticks. */
    public void seekBy(long deltaUs) {
        if (!steerable()) {
            return;
        }
        seekTo(SeekPlanner.relative(timeline, playheadTick(), deltaUs));
    }

    /** Ends the session at the next frame ({@link Exit#NEXT_GAME} goes through {@link #open}). */
    public void requestExit(Exit exit) {
        if (state == State.IDLE || exit == null || exit == Exit.NEXT_GAME || pendingExit != null) {
            return;
        }
        pendingExit = exit;
    }

    // ================================================================== failure and exit

    private void fail(Failure f, Throwable t) {
        if (pendingExit == null || pendingExit == Exit.NEXT_GAME) {
            lastFailure = f;
            pendingExit = Exit.GALLERY;
            nextId = null;
            log("rewind-watch: failed " + f + (t == null ? "" : " " + t.getClass().getSimpleName()));
        }
    }

    private void failNow(Failure f, Throwable t) {
        lastFailure = f;
        log("rewind-watch: failed " + f + (t == null ? "" : " " + t.getClass().getSimpleName()));
        exitNow(Exit.GALLERY);
    }

    private void exitNow(Exit exit) {
        if (state == State.IDLE || state == State.EXITING) {
            return;
        }
        state = State.EXITING;
        lastExit = exit;
        pendingExit = null;
        seekPending = false;
        if (load != null) {
            load.cancel();
            load = null;
        }
        if (connectionOpen) {
            connectionOpen = false;
            try {
                ops.teardown();
            } catch (Throwable t) {
                errors.failed("teardown", t);
            }
        }
        try {
            ops.clearTitlesAndBossBar();
        } catch (Throwable t) {
            errors.failed("titles", t);
        }
        PlaybackRestore r = restore;
        if (r != null) {
            r.restoreSettings(ops, errors);
        }
        if (gateBegun) {
            gateBegun = false;
            try {
                gate.exit();
            } catch (Throwable t) {
                errors.failed("gate", t);
            }
        }
        if (recording != null) {
            try {
                recording.close();
            } catch (Throwable ignored) {
                // memory only
            }
        }
        String next = exit == Exit.NEXT_GAME ? nextId : null;
        clearSession();
        try {
            switch (exit) {
                case TITLE:
                    ops.openTitle();
                    break;
                case REJOIN:
                    ops.openRejoin();
                    break;
                case GALLERY:
                    ops.openGallery();
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            errors.failed("destination", t);
        }
        if (r != null) {
            r.restoreChat(ops, errors);
        }
        log("rewind-watch: exit " + exit + (lastFailure == null ? "" : " after " + lastFailure));
        if (next != null) {
            begin(next);
        }
    }

    /** Back to IDLE with nothing held (lastFailure and lastExit stay for the caller). */
    private void clearSession() {
        state = State.IDLE;
        id = null;
        meta = null;
        load = null;
        recording = null;
        timeline = null;
        feeder = null;
        ticks = null;
        clock = null;
        restore = null;
        gateBegun = false;
        connectionOpen = false;
        behindCard = false;
        loadingShown = false;
        pendingExit = null;
        nextId = null;
        seekPending = false;
        approved = null;
        opened = false;
        loops = 0;
        worldTicked = false;
        export = null;
        exportStage = null;
        exportWaiting = false;
    }

    private void log(String line) {
        try {
            ops.log(line);
        } catch (Throwable ignored) {
            // logging never matters more than playback
        }
    }

    // ================================================================== export (Rewind Export PLAN Solution 1)

    /**
     * Starts exporting {@code plan} at a fixed step; the viewer's play, speed and seek commands do nothing until
     * {@link #endExport}. The start is the same whatever was watched before (review B1, r2-I2): at the next frame
     * the session seeks to {@code n0} = the count of ticks recorded at or before the pre-roll's start
     * ({@code max(first tick, start - }{@link ExportPlan#PREROLL_US}{@code )}), rebuilding backward or catching up
     * forward; once it has landed, {@link ClientOps#exportStart} (every part forgets what was watched), playback
     * time is stepped to the pre-roll's start and the timed cursor seated at the first record stamped at or after it;
     * then uncaptured pre-roll frames on the export's frame grid run ticks and dispatch every timed record up to the
     * start ({@link #exportPreroll()}), and frame 0 is at the start exactly. Each frame steps playback time to
     * {@code plan.timeOfFrame(i)}, runs every tick recorded at or before it ({@link TickClock#dueAll}), writes the
     * partial tick and dispatches the timed records reached; the index moves only on {@link #advanceExportFrame}.
     *
     * <p><b>Real-time floor</b> (PLAN Revision 4, as amended by 4.1 r3-B1): a step that moves playback time from t to
     * t' (the first step after the seek, every pre-roll step, every frame) is not applied before {@code t' - t} of wall
     * time ({@link ClientOps#nanoTime}) has passed since the previous step was really applied: its frame start, or the
     * end of the last of its ticks when it ran any (Minecraft runs them after the frame start; code review 1 B1); the
     * first step: one frame interval after the seek landed. Until then the pass is drawn again at t, with nothing run and nothing to
     * capture ({@link #exportWaiting()}). So a terrain packet applied at wall w is followed by the frame for recording
     * time T + d no earlier than w + d, stall or not; a slow machine is never held back. The tail is not paced: it
     * captures nothing.
     *
     * @return false (nothing changes) unless a world plays, no export runs and the start is not before the first tick
     */
    public boolean beginExport(ExportPlan plan) {
        return beginExport(plan, true);
    }

    /**
     * {@link #beginExport(ExportPlan)}, with the real-time floor off when {@code paced} is false (raw render-speed runs
     * of the cobdev spike only; the product always paces).
     */
    public boolean beginExport(ExportPlan plan, boolean paced) {
        if (plan == null || !playing() || export != null || timeline == null || clock == null) {
            return false;
        }
        long first = timeline.timeOfTick(timeline.startTick());
        if (plan.startUs() < first) {
            return false;
        }
        export = plan;
        exportStage = ExportStage.SEEKING;
        exportIndex = 0;
        exportTaken = false;
        tailAsked = false;
        exportPaced = paced;
        exportAppliedNs = Long.MIN_VALUE;
        exportWaiting = false;
        exportPreUs = Math.max(first, plan.startUs() - ExportPlan.PREROLL_US);
        ticks.setPaused(false);
        seekTarget = SeekPlanner.clamp(timeline, timeline.ticks().tickOf(exportPreUs + 1));
        seekPending = true;
        log("rewind-export: begin frames=" + plan.frames() + " fps=" + plan.fps() + " prerollMs="
                + (plan.startUs() - exportPreUs) / 1000L + " restart=" + (seekTarget < ticks.ticksRun()));
        return true;
    }

    /** The frame of a running export's fixed step (WATCHING): see {@link #beginExport}. */
    private void exportControl() {
        ExportPlan plan = export;
        long now = ops.nanoTime();
        exportWaiting = false;
        if (exportPaced) {
            long next = nextStepUs(plan);
            if (next != Long.MIN_VALUE) {
                long delta = exportStage == ExportStage.SEEKING ? (1_000_000L + plan.fps() - 1) / plan.fps()
                        : Math.max(0, next - clock.playbackUs());
                if (exportAppliedNs == Long.MIN_VALUE) {
                    exportAppliedNs = now; // no landing seen (the seek finished before beginExport's frame)
                }
                if (now - exportAppliedNs < delta * 1000L) {
                    exportWaiting = true;
                    holdStep();
                    return;
                }
            }
        }
        long t = Long.MIN_VALUE;
        boolean stepped = false;
        if (exportStage == ExportStage.SEEKING) {
            ops.exportStart();
            clock.step(exportPreUs);
            clock.seatTimed(recording.ext(), exportPreUs);
            stepped = true;
            if (exportPreUs < plan.startUs()) {
                exportStage = ExportStage.PREROLL;
                prerollGrid = firstGridAfter(plan, exportPreUs);
                t = exportPreUs;
            } else {
                exportStage = ExportStage.FRAMES;
            }
        } else if (exportStage == ExportStage.PREROLL) {
            t = gridTime(plan, prerollGrid++);
            stepped = true;
            if (t >= plan.startUs()) {
                // Records stamped after the last pre-roll frame but before the start still belong to the pre-roll
                // (their sounds are discarded); they reach the parts before frame 0 is drawn, with their own
                // stamps, as straight play hands them to the first frame at or after them.
                clock.step(plan.startUs() - 1);
                clock.dispatchTimed(recording.ext(), ops.sink());
                exportStage = ExportStage.FRAMES;
                t = Long.MIN_VALUE;
            }
        } else if (exportStage == ExportStage.FRAMES && exportTaken) {
            exportTaken = false;
            if (exportIndex + 1 < plan.frames()) {
                exportIndex++;
                stepped = true;
            } else {
                exportStage = ExportStage.LAST_TAKEN;
            }
        }
        if (exportStage == ExportStage.FRAMES && t == Long.MIN_VALUE) {
            t = plan.timeOfFrame(exportIndex);
        } else if (exportStage == ExportStage.LAST_TAKEN && tailAsked) {
            // up to the selected end, not including it: records and ticks at the end belong to no frame
            t = Math.max(clock.playbackUs(), plan.endUs() - 1);
            exportStage = ExportStage.TAIL_DONE;
        } else if (t == Long.MIN_VALUE) {
            t = clock.playbackUs(); // after the last frame: hold
        }
        if (stepped) {
            exportAppliedNs = now;
        }
        clock.step(t);
        int due = clock.dueAll(ticks.ticksRun(), ticks.ticksToNextStop());
        ops.setTimerSpeed(1f);
        ops.setElapsedTicks(due);
        ops.setGamePaused(false);
        ops.setPartialTicks(clock.partial(ticks.ticksRun() + due - 1));
        clock.dispatchTimed(recording.ext(), ops.sink());
    }

    /**
     * The recording time the next step of this pass would move playback time to, or MIN when this pass applies no
     * paced step (frame i drawn again before it was captured, the last frame captured, the tail).
     */
    private long nextStepUs(ExportPlan plan) {
        switch (exportStage) {
            case SEEKING:
                return exportPreUs;
            case PREROLL:
                return Math.min(gridTime(plan, prerollGrid), plan.startUs());
            case FRAMES:
                return exportTaken && exportIndex + 1 < plan.frames() ? plan.timeOfFrame(exportIndex + 1)
                        : Long.MIN_VALUE;
            default:
                return Long.MIN_VALUE;
        }
    }

    /** A pass the real-time floor held: drawn again where playback time stands, nothing run, nothing dispatched. */
    private void holdStep() {
        ops.setTimerSpeed(1f);
        ops.setElapsedTicks(0);
        ops.setGamePaused(false);
        ops.setPartialTicks(clock.partial(ticks.ticksRun() - 1));
    }

    /** The export's frame grid, also before frame 0: {@code start + floor(i x 1e6 / fps)}, any i. */
    private static long gridTime(ExportPlan plan, long i) {
        return plan.startUs() + Math.floorDiv(i * 1_000_000L, plan.fps());
    }

    /** The first grid index whose time is after {@code us} (a time at most a pre-roll before the start). */
    private static long firstGridAfter(ExportPlan plan, long us) {
        long i = Math.floorDiv((us - plan.startUs()) * plan.fps(), 1_000_000L) - 1;
        while (gridTime(plan, i) <= us) {
            i++;
        }
        return i;
    }

    /**
     * This pass's next step was held back by the real-time floor ({@link #beginExport}): the picture is drawn again
     * where playback time stands and is not captured.
     */
    public boolean exportWaiting() {
        return export != null && exportWaiting;
    }

    /** An export is running ({@link #beginExport} until {@link #endExport} or the session's end). */
    public boolean exporting() {
        return export != null;
    }

    /** The running export's plan, or null. */
    public ExportPlan exportPlan() {
        return export;
    }

    /**
     * The index of the export frame this game-loop pass draws (0 to {@code frames - 1}; decided at its
     * {@link #frameStart}, so it stays the same until the next one), or -1: no export, before frame 0 (the seek and
     * the pre-roll), after the last frame was taken, and the tail. Only these frames are captured.
     */
    public long exportFrame() {
        return export != null && exportStage == ExportStage.FRAMES && !exportWaiting ? exportIndex : -1;
    }

    /**
     * The frame drawn now was captured: the next {@link #frameStart} draws the next one; after the last, playback
     * time holds until {@link #collectExportTail}. Until this is called the same frame is drawn again with nothing run
     * twice (settling). Calling it twice for one frame advances once.
     */
    public void advanceExportFrame() {
        if (export != null && exportStage == ExportStage.FRAMES) {
            exportTaken = true;
        }
    }

    /**
     * After the last frame was captured (review I1): the next frame steps playback time to the selected range end
     * (less a microsecond), running the ticks and dispatching the timed records recorded before it, and is not
     * captured. Read {@link #exportTailCollected()} after that frame's ticks. Calling it again does nothing.
     */
    public void collectExportTail() {
        if (export != null && (exportStage == ExportStage.LAST_TAKEN || exportStage == ExportStage.FRAMES
                && exportTaken && exportIndex + 1 >= export.frames())) {
            tailAsked = true;
        }
    }

    /** The tail step has run (its ticks have run once the frame that took it has). */
    public boolean exportTailCollected() {
        return export != null && exportStage == ExportStage.TAIL_DONE;
    }

    /**
     * Before frame 0 of a running export (the seek and the pre-roll, review r2-I2): what is drawn is not captured and
     * no sound that plays now may reach the export.
     */
    public boolean exportPreroll() {
        return export != null && (exportStage == ExportStage.SEEKING || exportStage == ExportStage.PREROLL);
    }

    /**
     * Ends the export (done, cancelled or failed): the session is paused inside the selected range (TASK P7) and wall
     * time counts from now, so nothing jumps; play, speed and seeks work again. From frame 0 on playback time is
     * already inside the range and stays where it stands; an export ended before frame 0 (its seek or pre-roll, code
     * review 1 I3) seeks to the first tick at or after the range start instead.
     */
    public void endExport() {
        if (export == null) {
            return;
        }
        ExportPlan plan = export;
        ExportStage stage = exportStage;
        export = null;
        exportStage = null;
        exportWaiting = false;
        if (ticks != null) {
            ticks.setPaused(true);
        }
        if (clock != null) {
            clock.set(clock.playbackUs(), ops.nanoTime());
        }
        boolean moved = parkInside(plan, stage != ExportStage.SEEKING && stage != ExportStage.PREROLL);
        log("rewind-export: end" + (moved ? " seek to the range start" : ""));
    }

    /**
     * Playback time stays when {@code mayStay} and it is inside {@code plan}'s range (no seek pending); otherwise the
     * session seeks to the first tick at or after the range start. Call with play paused. @return true when it seeks
     */
    private boolean parkInside(ExportPlan plan, boolean mayStay) {
        boolean inside = mayStay && state == State.WATCHING && !seekPending && clock != null
                && clock.playbackUs() >= plan.startUs() && clock.playbackUs() < plan.endUs();
        if (inside || timeline == null || !playing()) {
            return false;
        }
        seekTo(SeekPlanner.tickForTime(timeline, plan.startUs()));
        return true;
    }

    /**
     * An export that never began ({@link #beginExport} not called: it failed or was cancelled while its helper started,
     * code review 1 I3): the session is paused inside {@code plan}'s range as after {@link #endExport} (TASK P7).
     */
    public void pauseInside(ExportPlan plan) {
        if (plan == null || export != null || !playing()) {
            return;
        }
        ticks.setPaused(true);
        parkInside(plan, true);
    }

    /**
     * When something that happens now happened on the recording's clock (an export's sound log, PLAN Solution 3):
     * the running tick's recorded time inside a tick, a timed record's own stamp while it is dispatched, else
     * playback time ({@link #renderTimeUs()}).
     */
    public long eventTimeUs() {
        if (clock != null && clock.dispatching()) {
            return clock.dispatchingUs();
        }
        if (worldTicked) {
            return tickTimeUs;
        }
        return renderTimeUs();
    }

    // ================================================================== state for screens and hooks

    public State state() {
        return state;
    }

    /** Not IDLE: the hooks, the camera and the screen policy apply. */
    public boolean active() {
        return state != State.IDLE;
    }

    /** CATCHING_UP or WATCHING: a recorded world is being played. */
    public boolean playingWorld() {
        return state == State.CATCHING_UP || state == State.WATCHING;
    }

    public String recordingId() {
        return id;
    }

    /** The recording's {@code .json} read at open (the loading card's label), or null. */
    public RecordingMeta meta() {
        return meta;
    }

    public LoadedRecording recording() {
        return recording;
    }

    public Timeline timeline() {
        return timeline;
    }

    public ReplayTicks ticks() {
        return ticks;
    }

    public TickFeeder feeder() {
        return feeder;
    }

    /** The loading card is up (opening, backward seeks). */
    public boolean behindCard() {
        return behindCard;
    }

    /** The loading key's fill, 0-1: ticks run / ticks needed while catching up behind the card. */
    public float loadingProgress() {
        if (ticks == null || state != State.CATCHING_UP && state != State.WATCHING) {
            return 0f;
        }
        if (state == State.WATCHING) {
            return 1f;
        }
        long need = ticks.target() - catchUpFrom;
        return need <= 0 ? 1f : Math.max(0f, Math.min(1f, (ticks.ticksRun() - catchUpFrom) / (float) need));
    }

    /** Where the playhead stands: a seek's target (pending or running), else the ticks run. */
    public long playheadTick() {
        if (ticks == null) {
            return 0;
        }
        return seekPending ? seekTarget : ticks.playheadTick();
    }

    /**
     * The recording time the picture shows: playback time while WATCHING ({@link TickClock}, frozen while paused), the
     * time of the next tick during a catch-up. The camera rotation of your view is taken at this time.
     */
    public long renderTimeUs() {
        if (timeline == null || ticks == null) {
            return 0;
        }
        if (state != State.WATCHING || clock == null) {
            return timeline.timeOfTick(ticks.ticksRun());
        }
        return clock.playbackUs();
    }

    /** The last tick run, the tick the picture draws (-1 before any). */
    public long renderTick() {
        return ticks == null ? -1 : ticks.ticksRun() - 1;
    }

    /** The partial tick drawn: the share of {@link #renderTick()}'s recorded interval playback time has reached. */
    public float renderPartial() {
        if (ticks == null || timeline == null) {
            return 0f;
        }
        if (state != State.WATCHING || clock == null) {
            return 1f;
        }
        return clock.partial(ticks.ticksRun() - 1);
    }

    /** Your view's eye position and rotation for this frame ({@link SelfTrack#pose}); false without a TICK. */
    public boolean yourPose(SelfTrack.Pose out) {
        if (recording == null || timeline == null || ticks == null) {
            return false;
        }
        return recording.selfTrack().pose(timeline.ticks(), renderTick(), renderPartial(), renderTimeUs(), out);
    }

    /** What the view shows (the viewer's keys and the playback parts read and change it). Reset by {@link #open}. */
    public ViewState view() {
        return view;
    }

    /** The recorded tick clock of the loaded recording, or null. */
    public TickClock clock() {
        return clock;
    }

    public Failure lastFailure() {
        return lastFailure;
    }

    public Exit lastExit() {
        return lastExit;
    }
}
