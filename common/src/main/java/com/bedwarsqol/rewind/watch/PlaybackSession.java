package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RecordingMeta;

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
            startCatchUp(false);
            ticks.seek(plan);
            afterSeek();
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
        clock.set(timeline.timeOfTick(ticks.ticksRun()), ops.nanoTime());
        clock.skipTimed(recording.ext());
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

    /** Space / K: play or pause; at the end it plays from the game start. */
    public void togglePlay() {
        if (!playing()) {
            return;
        }
        if (ticks.togglePlay() == ReplayTicks.PlayToggle.RESTART) {
            seekTo(timeline.startTick());
        }
    }

    public void setPaused(boolean paused) {
        if (playing()) {
            ticks.setPaused(paused);
        }
    }

    public void faster() {
        if (playing()) {
            ticks.faster();
        }
    }

    public void slower() {
        if (playing()) {
            ticks.slower();
        }
    }

    /** Seeks to {@code tick} (clamped to the timeline) at the next frame. */
    public void seekTo(long tick) {
        if (!playing()) {
            return;
        }
        seekTarget = SeekPlanner.clamp(timeline, tick);
        seekPending = true;
    }

    /** Seeks {@code deltaUs} from the playhead (or from a seek not applied yet), in whole ticks. */
    public void seekBy(long deltaUs) {
        if (!playing()) {
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
    }

    private void log(String line) {
        try {
            ops.log(line);
        } catch (Throwable ignored) {
            // logging never matters more than playback
        }
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
