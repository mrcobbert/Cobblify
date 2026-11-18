package com.bedwarsqol.rewind.watch;

/**
 * Seeks are a number of ticks, not a packet filter. A target at or after the ticks run catches up in place; an
 * earlier one is a RESTART (rebuild the world, ticks run = 0, {@link TickFeeder#restart()}) followed by a catch-up
 * from tick 0. Catch-up length = target - ticks run, or the target itself after a restart. Every restart starts at
 * the file's first segment, so one origin and one tick grid serve every seek.
 *
 * <p>During a catch-up sounds are muted throughout and particles except in the last {@link #PARTICLE_TAIL_TICKS}
 * ticks ({@link ReplayTicks#soundsMuted()}, {@link ReplayTicks#particlesMuted()}); behind the loading card the world
 * is not drawn except in the last {@link #DRAW_TAIL_TICKS}. No record is dropped or reordered for a catch-up.
 *
 * <p>Pure.
 */
public final class SeekPlanner {

    /** Particles show again in a catch-up's last this many ticks. */
    public static final int PARTICLE_TAIL_TICKS = 60;
    /** Behind the loading card the world is drawn in a catch-up's last this many ticks (its chunk meshes build). */
    public static final int DRAW_TAIL_TICKS = 40;

    /** One seek. */
    public static final class Plan {
        /** Rebuild and feed from tick 0. */
        public final boolean restart;
        /** The tick the playhead lands on: {@link ReplayTicks#ticksRun()} after the catch-up. */
        public final long targetTick;
        /** World ticks the catch-up runs. */
        public final long catchUpTicks;

        Plan(boolean restart, long targetTick, long catchUpTicks) {
            this.restart = restart;
            this.targetTick = targetTick;
            this.catchUpTicks = catchUpTicks;
        }
    }

    private SeekPlanner() {
    }

    /** The plan from {@code ticksRun} to {@code targetTick} (already clamped by the caller, see {@link #clamp}). */
    public static Plan plan(long ticksRun, long targetTick) {
        if (targetTick >= ticksRun) {
            return new Plan(false, targetTick, targetTick - ticksRun);
        }
        long target = Math.max(0, targetTick);
        return new Plan(true, target, target);
    }

    /** Opening a recording: restart, headers, catch up to the game start. */
    public static Plan opening(Timeline t) {
        long target = Math.max(0, t.startTick());
        return new Plan(true, target, target);
    }

    /** {@code tick} kept inside the timeline: {@code [startTick, endTick]}. */
    public static long clamp(Timeline t, long tick) {
        return Math.max(t.startTick(), Math.min(t.endTick(), tick));
    }

    /** The clamped tick for a time on the recording's clock ({@link Timeline#tickOf}: the first tick at or after it). */
    public static long tickForTime(Timeline t, long timeUs) {
        return clamp(t, t.tickOf(timeUs));
    }

    /**
     * The clamped tick {@code deltaUs} from {@code fromTick} (G45: 5 s and 30 s steps are whole ticks). Pass
     * {@link ReplayTicks#playheadTick()}, so a second press during a catch-up steps from the first one's target.
     */
    public static long relative(Timeline t, long fromTick, long deltaUs) {
        return clamp(t, fromTick + deltaUs / Timeline.TICK_US);
    }
}
