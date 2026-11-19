package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;

/**
 * Derived footsteps for the recorded player (PLAN Phase 8, B7): vanilla's step rule from {@code Entity.moveEntity}
 * (javap @1442-1905, PLAN "Vanilla facts", Footsteps) run over the recorded per-tick movement, because the puppet that
 * stands in for you moves without collision and so never reaches that rule itself.
 *
 * <p>Per tick, with {@code (dx, dy, dz)} the move since the previous tick:
 * <ul>
 *   <li>no collision (recorded spectator: vanilla returns before the rule), riding, or sneaking on the ground (on the
 *       ground before the move, as vanilla reads it: the previous tick's flag): nothing (the distance does not
 *       grow);</li>
 *   <li>the block is the one at {@code (floor x, floor(y - 0.2), floor z)}; if that is air and the block under it is a
 *       fence, wall or fence gate, that one ({@link #FENCE});</li>
 *   <li>{@code dy} counts only when that block is a ladder; the distance grows by
 *       {@code (float) sqrt(dx^2 + dy^2 + dz^2) x 0.6} in vanilla's float arithmetic;</li>
 *   <li>a step when the distance passes the next step distance (1 at first) and the block is not air; the next step
 *       distance becomes {@code (int) distance + 1}.</li>
 * </ul>
 * Not in vanilla's rule: a move longer than {@link #TELEPORT_BLOCKS} in one tick is a teleport (S08, respawn), which
 * live never went through {@code moveEntity}; it moves the start and adds nothing. Flying is not recorded, so it is
 * not gated (flying high has air below, which plays nothing).
 *
 * <p>A recording with stored SOUND records (format v2) holds the real footsteps, so this derives nothing for it
 * ({@link #storedSounds}; never both). The caller plays the block's step sound ({@code Block.stepSound}, volume
 * x 0.15, snow layer above: snow) where {@link #blockX()}/{@link #blockY()}/{@link #blockZ()} point.
 *
 * <p>One thread (the client thread). Allocates nothing per tick.
 */
public final class StepSounds {

    /** Air ({@code Material.air}): no step. */
    public static final int AIR = 0;
    /** Any other block. */
    public static final int SOLID = 1;
    /** {@code BlockFence}, {@code BlockWall} or {@code BlockFenceGate}: counts when the block above it is air. */
    public static final int FENCE = 2;
    /** {@code Blocks.ladder}: vertical movement counts. */
    public static final int LADDER = 3;
    /** A move longer than this many blocks in one tick is a teleport, not a walk. */
    public static final double TELEPORT_BLOCKS = 8.0;

    /** What the world holds at a block, as the step rule reads it ({@link #AIR}, {@link #SOLID}, ...). */
    public interface Blocks {
        int kind(int x, int y, int z);
    }

    private static final double FEET = 0.20000000298023224D;

    private final SelfTrackEncoder.State state = new SelfTrackEncoder.State();
    private boolean enabled = true;
    private boolean started;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean lastOnGround;
    /** Vanilla's {@code distanceWalkedOnStepModified}. */
    private float distance;
    /** Vanilla's {@code nextStepDistance}. */
    private int nextStep = 1;
    private int blockX;
    private int blockY;
    private int blockZ;

    /** True when the recording stores its own sounds (SOUND records): footsteps come from the file, not this rule. */
    public static boolean storedSounds(ExtRecords ext) {
        return ext != null && ext.has(RewindFormat.TAG_SOUND);
    }

    /** Sets the rule up for a recording (on for one without stored sounds) and starts again ({@link #reset}). */
    public void forRecording(ExtRecords ext) {
        enabled = !storedSounds(ext);
        reset();
    }

    /** Whether this recording's footsteps are derived. */
    public boolean enabled() {
        return enabled;
    }

    /** Like a new player entity (a new world, a fresh connection): no start, distance 0, next step at 1. */
    public void reset() {
        started = false;
        distance = 0f;
        nextStep = 1;
    }

    /**
     * World tick {@code k}: the move from the previous tick's recorded end position to tick k's
     * ({@link SelfTrack#tickState}). {@code noClip}: the recorded game mode is spectator.
     *
     * @return true if a footstep plays this tick
     */
    public boolean tick(SelfTrack track, TickTable table, long k, boolean noClip, Blocks world) {
        if (!enabled || track == null || table == null || !track.tickState(table, k, state)) {
            return false;
        }
        return move(state.x, state.y, state.z, state.flags, noClip, world);
    }

    /**
     * One tick's move to {@code (x, y, z)} with the recorded {@code flags} ({@code RewindFormat.FLAG_*}). The first move
     * after a reset only sets the start.
     *
     * @return true if a footstep plays (its block: {@link #blockX()}, {@link #blockY()}, {@link #blockZ()})
     */
    public boolean move(double x, double y, double z, int flags, boolean noClip, Blocks world) {
        if (!enabled) {
            return false;
        }
        boolean onGround = lastOnGround; // vanilla reads onGround before the move (the previous tick's flag)
        if (!started) {
            started = true;
            setLast(x, y, z, flags);
            return false;
        }
        double dx = x - lastX;
        double dy = y - lastY;
        double dz = z - lastZ;
        setLast(x, y, z, flags);
        if (noClip || dx * dx + dy * dy + dz * dz > TELEPORT_BLOCKS * TELEPORT_BLOCKS) {
            return false;
        }
        boolean sneaking = (flags & RewindFormat.FLAG_SNEAKING) != 0;
        boolean riding = (flags & RewindFormat.FLAG_RIDING) != 0;
        if (onGround && sneaking || riding) {
            return false;
        }
        int bx = floor(x);
        int by = floor(y - FEET);
        int bz = floor(z);
        int kind = world.kind(bx, by, bz);
        if (kind == AIR && world.kind(bx, by - 1, bz) == FENCE) {
            kind = FENCE;
            by--;
        }
        if (kind != LADDER) {
            dy = 0;
        }
        distance = (float) (distance + (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.6D);
        if (distance > (float) nextStep && kind != AIR) {
            nextStep = (int) distance + 1;
            blockX = bx;
            blockY = by;
            blockZ = bz;
            return true;
        }
        return false;
    }

    private void setLast(double x, double y, double z, int flags) {
        lastX = x;
        lastY = y;
        lastZ = z;
        lastOnGround = (flags & RewindFormat.FLAG_ON_GROUND) != 0;
    }

    /** {@code MathHelper.floor_double}. */
    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /** The block of the last footstep. */
    public int blockX() {
        return blockX;
    }

    public int blockY() {
        return blockY;
    }

    public int blockZ() {
        return blockZ;
    }
}
