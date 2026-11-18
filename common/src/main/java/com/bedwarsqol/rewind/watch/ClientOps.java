package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RecordingMeta;

/**
 * The Minecraft calls a {@link PlaybackSession} makes (PLAN section 3). Ornithe's {@code RewindPlayback} implements
 * it; tests use a fake with a fake tick loop. Every method is called on the client thread, except that the work
 * {@link #startLoad} starts runs on a thread of its own.
 */
public interface ClientOps extends PlaybackRestore.Client {

    /** The recording's {@code .json} (small), or null. Used for the loading card's label. */
    RecordingMeta readMeta(String id);

    /** A real world or connection is up (the pause-menu entry). */
    boolean connected();

    /**
     * What vanilla's Disconnect key does, without its screen: remember the server for Rejoin, send the quitting
     * packet, {@code loadWorld(null)}, and clear chat when Keep Chat History is off (as the title screen would).
     */
    void disconnect();

    /** {@code Rewind.settleDisconnect()}: R1 closes the game that just ended before the gate silences it (PA1). */
    void settleRecorder();

    /** The loading card (G49), opened by the session. */
    void showLoading();

    /** The viewer, opened by the session. */
    void showViewer();

    /**
     * Off the client thread: wait until {@code id} is closed ({@link PlaybackSession#awaitClosed}, at most
     * {@link PlaybackSession#CLOSE_WAIT_MS}), then load it, and complete {@code load} either way. Must not block.
     */
    void startLoad(String id, PlaybackSession.Load load);

    /**
     * GUI state that lives outside the world, before every restart (round 2 B2): chat cleared, title, subtitle and
     * action bar cleared with their timers at 0 (default title times), update counter 0, boss bar cleared.
     */
    void resetGui();

    /**
     * A fresh socketless playback connection; a previous one is torn down first ({@code loadWorld(null)}, channel
     * closed). After it, {@link #sink()} feeds the new connection.
     */
    void newConnection();

    /** Where the feed hands packets: the current connection's handler. */
    PacketSink sink();

    /** {@code theWorld != null}. */
    boolean worldPresent();

    /** {@code Minecraft.isGamePaused} as vanilla's tick reads it. */
    boolean gamePaused();

    /** {@code timer.elapsedTicks}: vanilla's frame loop re-reads it before every {@code runTick}. */
    int elapsedTicks();

    void setElapsedTicks(int ticks);

    /** {@code System.nanoTime()} (a fake clock in tests). */
    long nanoTime();

    /** {@code loadWorld(null)} if a world is up, and the playback channel closed. No Rewind camera remains. */
    void teardown();

    /** Titles, subtitle, action bar and the boss bar from playback never outlive it. */
    void clearTitlesAndBossBar();

    /** Exit to the title screen. */
    void openTitle();

    /** Exit by reconnecting to the server that was left (else the Hypixel entry; {@link RejoinTarget}). */
    void openRejoin();

    /** Exit to the gallery after a failure; its Done leads to the title screen (the session is over). */
    void openGallery();

    /** A counts-only diagnostics line (DiagLog in the client). Never recording content. */
    void log(String line);
}
