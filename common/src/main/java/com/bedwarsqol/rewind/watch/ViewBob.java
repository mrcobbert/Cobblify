package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;

/**
 * Your view's bob and nausea warp, one recorded client tick at a time (PLAN "Camera state", TASK B5, B8). Vanilla
 * draws both from fields of the view entity that the playback camera never updates itself; this computes them from the
 * recorded state with vanilla's recurrences ({@code javap} on the mapped 1.8.9 jar):
 *
 * <ul>
 *   <li><b>Bob</b> ({@code EntityPlayer.onLivingUpdate}): {@code f = min(sqrt(motionX^2 + motionZ^2), 0.1)},
 *       {@code f1 = atan(-motionY x 0.2) x 15}; {@code f = 0} unless on the ground and alive, {@code f1 = 0} on the
 *       ground or dead; {@code cameraYaw += (f - cameraYaw) x 0.4}, {@code cameraPitch += (f1 - cameraPitch) x 0.8}.
 *       The motion is the one vanilla reads after its move: the recorded position delta times the friction of the
 *       ground the tick started on ({@code 0.6 x 0.91}, or {@code 0.91} in the air), and vertically
 *       {@code (dy - 0.08) x 0.98}.</li>
 *   <li><b>Walked distance</b> ({@code Entity.moveEntity}): {@code += sqrt(dx^2 + dz^2) x 0.6}, except while sneaking
 *       on the ground the tick started on, or riding.</li>
 *   <li><b>Nausea</b> ({@code EntityPlayerSP.onLivingUpdate}): with confusion of duration over 60 ticks
 *       {@code timeInPortal += 0.006666667} (cap 1), otherwise {@code -= 0.05} (floor 0).</li>
 * </ul>
 * Every value keeps its previous tick's value for the frame's partial tick. The first tick after {@link #reset} and a
 * jump over {@link #TELEPORT_BLOCKS} (a teleport, not a move) count as no motion. Not recorded: the ground's
 * slipperiness (ice, slime) and flying, so the bob phase is approximate (TASK "Parity definition"). Pure.
 */
public final class ViewBob {

    /** {@code 0.6F x 0.91F}: plain ground's friction, as vanilla multiplies it. */
    static final double GROUND_FRICTION = 0.6f * 0.91f;
    static final double AIR_FRICTION = 0.91f;
    static final double GRAVITY = 0.08;
    static final double DRAG = 0.9800000190734863;
    /** A step longer than this (blocks) is a teleport: no motion, no walked distance. */
    static final double TELEPORT_BLOCKS = 8;
    /** Confusion warps only while more than this many ticks remain. */
    static final int NAUSEA_MIN_DURATION = 60;

    private boolean hasPrevious;
    private double px;
    private double py;
    private double pz;
    private boolean prevGround;
    private float cameraYaw;
    private float prevCameraYaw;
    private float cameraPitch;
    private float prevCameraPitch;
    private float distanceWalked;
    private float prevDistanceWalked;
    private float timeInPortal;
    private float prevTimeInPortal;

    /** Everything 0 and no previous position (a new camera, a restart). */
    public void reset() {
        hasPrevious = false;
        px = py = pz = 0;
        prevGround = false;
        cameraYaw = prevCameraYaw = 0f;
        cameraPitch = prevCameraPitch = 0f;
        distanceWalked = prevDistanceWalked = 0f;
        timeInPortal = prevTimeInPortal = 0f;
    }

    /**
     * One client tick that ended at {@code (x, y, z)} with the recorded {@code RewindFormat.FLAG_*} flags.
     *
     * @param alive the recorded health is above 0
     */
    public void step(double x, double y, double z, int flags, boolean alive) {
        double dx = 0;
        double dy = 0;
        double dz = 0;
        boolean startedOnGround = prevGround;
        if (hasPrevious) {
            dx = x - px;
            dy = y - py;
            dz = z - pz;
            if (dx * dx + dy * dy + dz * dz > TELEPORT_BLOCKS * TELEPORT_BLOCKS) {
                dx = dy = dz = 0;
            }
        }
        boolean moved = hasPrevious && (dx != 0 || dy != 0 || dz != 0);
        boolean onGround = flag(flags, RewindFormat.FLAG_ON_GROUND);
        prevCameraYaw = cameraYaw;
        prevCameraPitch = cameraPitch;
        prevDistanceWalked = distanceWalked;

        // the motion vanilla reads after its move: friction of the ground the tick started on, gravity and drag
        double friction = startedOnGround ? GROUND_FRICTION : AIR_FRICTION;
        double mx = dx * friction;
        double mz = dz * friction;
        double my = moved ? (dy - GRAVITY) * DRAG : 0;
        float f = (float) Math.sqrt(mx * mx + mz * mz);
        float f1 = (float) (Math.atan(-my * 0.20000000298023224) * 15.0);
        if (f > 0.1f) {
            f = 0.1f;
        }
        if (!onGround || !alive) {
            f = 0f;
        }
        if (onGround || !alive) {
            f1 = 0f;
        }
        cameraYaw += (f - cameraYaw) * 0.4f;
        cameraPitch += (f1 - cameraPitch) * 0.8f;

        boolean sneakingOnGround = startedOnGround && flag(flags, RewindFormat.FLAG_SNEAKING);
        if (moved && !sneakingOnGround && !flag(flags, RewindFormat.FLAG_RIDING)) {
            distanceWalked = (float) (distanceWalked + (double) (float) Math.sqrt(dx * dx + dz * dz) * 0.6);
        }
        hasPrevious = true;
        px = x;
        py = y;
        pz = z;
        prevGround = onGround;
    }

    /** One client tick of the nausea warp; {@code confusionDuration} is the effect's remaining ticks, or -1. */
    public void nausea(int confusionDuration) {
        prevTimeInPortal = timeInPortal;
        if (confusionDuration > NAUSEA_MIN_DURATION) {
            timeInPortal += 0.006666667f;
            if (timeInPortal > 1f) {
                timeInPortal = 1f;
            }
        } else {
            if (timeInPortal > 0f) {
                timeInPortal -= 0.05f;
            }
            if (timeInPortal < 0f) {
                timeInPortal = 0f;
            }
        }
    }

    public float cameraYaw() {
        return cameraYaw;
    }

    public float prevCameraYaw() {
        return prevCameraYaw;
    }

    public float cameraPitch() {
        return cameraPitch;
    }

    public float prevCameraPitch() {
        return prevCameraPitch;
    }

    public float distanceWalked() {
        return distanceWalked;
    }

    public float prevDistanceWalked() {
        return prevDistanceWalked;
    }

    public float timeInPortal() {
        return timeInPortal;
    }

    public float prevTimeInPortal() {
        return prevTimeInPortal;
    }

    private static boolean flag(int flags, int f) {
        return (flags & f) != 0;
    }
}
