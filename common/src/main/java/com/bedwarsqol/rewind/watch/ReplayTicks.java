package com.bedwarsqol.rewind.watch;

/**
 * Playback's tick counter: ticks run since the last restart, speed (G45: 0.25x, 0.5x, 1x, 2x, 4x), pause, the end
 * tick and a running catch-up. Playback time advances only by world ticks: tick k stands for
 * {@code origin + k x 50 ms}, and after {@link #ticksRun()} = n ticks the records of ticks 0..n-1 have been fed.
 *
 * <p>The session calls {@link #beginTick()} at {@code runTick} HEAD (with the feed) and {@link #finishTick()} at
 * RETURN. A catch-up ({@link #seek}) runs ticks whether paused or not; when it reaches its target the user's play
 * state applies again, so a paused one holds there. Reaching the end tick pauses; play at the end restarts at the
 * start ({@link PlayToggle#RESTART}; mockup {@code togglePlay}).
 *
 * <p>Pure; one thread.
 */
public final class ReplayTicks {

    public static final float[] SPEEDS = {0.25f, 0.5f, 1f, 2f, 4f};
    public static final int DEFAULT_SPEED_INDEX = 2;

    /** What {@link #togglePlay()} did. */
    public enum PlayToggle {
        PLAYING,
        PAUSED,
        /** At the end: now playing, and the session must seek to the game start. */
        RESTART
    }

    private long ticksRun;
    private long endTick;
    private int speedIndex = DEFAULT_SPEED_INDEX;
    private boolean paused;
    private boolean catchingUp;
    private long target;

    /** @param endTick the timeline's end tick ({@link Timeline#endTick()}); playing never runs past it */
    public ReplayTicks(long endTick) {
        this.endTick = Math.max(0, endTick);
    }

    /** World ticks run since the last restart. */
    public long ticksRun() {
        return ticksRun;
    }

    public long endTick() {
        return endTick;
    }

    /** Moves the end earlier (a recorded kick, {@link TickFeeder#endedAtTick()}); never later, never before 0. */
    public void endAt(long tick) {
        endTick = Math.max(0, Math.min(endTick, tick));
        if (catchingUp && target > endTick) {
            target = endTick;
        }
    }

    public boolean atEnd() {
        return ticksRun >= endTick;
    }

    public float speed() {
        return SPEEDS[speedIndex];
    }

    public int speedIndex() {
        return speedIndex;
    }

    /** One speed step up; false at 4x. */
    public boolean faster() {
        if (speedIndex >= SPEEDS.length - 1) {
            return false;
        }
        speedIndex++;
        return true;
    }

    /** One speed step down; false at 0.25x. */
    public boolean slower() {
        if (speedIndex <= 0) {
            return false;
        }
        speedIndex--;
        return true;
    }

    /** The user's play state (a catch-up runs regardless). */
    public boolean paused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** Play/pause; at the end (paused there) it plays again and asks for a seek to the start. */
    public PlayToggle togglePlay() {
        if (paused && atEnd()) {
            paused = false;
            return PlayToggle.RESTART;
        }
        paused = !paused;
        return paused ? PlayToggle.PAUSED : PlayToggle.PLAYING;
    }

    /**
     * Starts a seek: a restart sets the ticks run to 0 (the session rebuilds the world and restarts the feed); then
     * the catch-up runs to the plan's target (clamped to the end tick). A target at the ticks run needs no catch-up.
     */
    public void seek(SeekPlanner.Plan plan) {
        if (plan.restart) {
            ticksRun = 0;
        }
        target = Math.min(Math.max(plan.targetTick, ticksRun), endTick);
        catchingUp = target > ticksRun;
    }

    public boolean catchingUp() {
        return catchingUp;
    }

    /** The running catch-up's target (meaningless when not catching up). */
    public long target() {
        return target;
    }

    /** Where the playhead stands: the seek target while a catch-up runs, else the ticks run. */
    public long playheadTick() {
        return catchingUp ? target : ticksRun;
    }

    /** The nearer of the catch-up target and the end tick; the end tick when not catching up. */
    public long nextStop() {
        return catchingUp ? Math.min(target, endTick) : endTick;
    }

    public long ticksToNextStop() {
        return Math.max(0, nextStop() - ticksRun);
    }

    /**
     * World ticks this frame may run while watching: 0 when paused (and not catching up), else vanilla's count capped
     * at the next stop. A catch-up's per-frame budget is the session's (PLAN section 3).
     */
    public int frameTicks(int vanillaElapsed) {
        if (paused && !catchingUp) {
            return 0;
        }
        return (int) Math.max(0, Math.min(vanillaElapsed, ticksToNextStop()));
    }

    /** {@code runTick} HEAD: the tick about to run (feed it), counted as run. */
    public long beginTick() {
        return ticksRun++;
    }

    /**
     * {@code runTick} RETURN: true when a stop was reached (the session ends vanilla's tick loop for this frame). A
     * reached target ends the catch-up; the reached end tick pauses.
     */
    public boolean finishTick() {
        boolean stop = false;
        if (catchingUp && ticksRun >= target) {
            catchingUp = false;
            stop = true;
        }
        if (ticksRun >= endTick) {
            paused = true;
            catchingUp = false;
            stop = true;
        }
        return stop;
    }

    /** Sounds are muted for the whole catch-up. */
    public boolean soundsMuted() {
        return catchingUp;
    }

    /** Particles are muted during a catch-up except in its last {@link SeekPlanner#PARTICLE_TAIL_TICKS} ticks. */
    public boolean particlesMuted() {
        if (!catchingUp) {
            return false;
        }
        // During tick k (after beginTick) ticksRun is k + 1: the tick being run is ticksRun - 1.
        return target - (ticksRun - 1) > SeekPlanner.PARTICLE_TAIL_TICKS;
    }
}
