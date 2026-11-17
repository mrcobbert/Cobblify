package com.bedwarsqol.feature;

/**
 * Motion Blur's per-frame state: whether the world pass runs this frame and how long its smear is. Pure Java, fed one
 * {@link Sample} per rendered frame by the platform's world pass hook.
 *
 * <p>Each frame:
 * <ol>
 *   <li>dt is the real time since the last frame, at least {@link MotionBlurRules#MIN_DT_SECONDS};</li>
 *   <li>the turn speed is the wrapped yaw change and the pitch change over dt;</li>
 *   <li>it is smoothed by time: the old value keeps {@link MotionBlurRules#keep 2^(-dt / 8 ms)};</li>
 *   <li>the smear is the smoothed speed times the exposure over the FOV (the horizontal FOV is FOV x aspect), and the
 *       smoothed speed is clamped after each step to the speed that gives the longest smear;</li>
 *   <li>the pass turns on at a smear of {@link MotionBlurRules#ON_PX 1 px} and off under
 *       {@link MotionBlurRules#OFF_PX 0.1 px}, which zeroes the smoothed speed; in between the state is kept, so a
 *       slow drift never toggles it;</li>
 *   <li>while on, the pass takes {@link MotionBlurRules#taps} taps.</li>
 * </ol>
 * A reset (a camera cut) zeroes the speed, turns the pass off and re-seeds the angles, so that frame is unblurred.
 * Same smear for the same turn at any frame rate, because every step is a function of elapsed time.
 */
public final class MotionBlurFrame {

    /** The first frame, or the first after {@link #invalidate} (the module was just enabled, or Rewind ended). */
    public static final String RESET_FIRST_FRAME = "first-frame";
    /** The server corrected the player's position or look: {@link Sample#cutSerial} changed. */
    public static final String RESET_POSITION_CORRECTION = "position-correction";
    public static final String RESET_WORLD = "world-change";
    /** The render view entity changed (respawn, spectate). */
    public static final String RESET_VIEW_ENTITY = "view-entity-change";
    public static final String RESET_PERSPECTIVE = "perspective-change";
    /** The display size changed, or is not usable. */
    public static final String RESET_SIZE = "size-change";
    /** More than 100 ms since the last frame. */
    public static final String RESET_GAP = "frame-gap";
    /** The camera moved more than 1 block in one frame, faster than 100 blocks a second. */
    public static final String RESET_TELEPORT = "teleport";

    /**
     * One rendered frame's camera, filled by the platform. Identities are tokens that change when the thing changes
     * (for example {@code System.identityHashCode}); they are compared, never interpreted.
     */
    public static final class Sample {
        /** {@code System.nanoTime()} of this frame. */
        public long nanos;
        /** The camera's interpolated yaw and pitch, in degrees; yaw may be unbounded (it is wrapped per frame). */
        public double yaw, pitch;
        /** The camera's interpolated position, in blocks. */
        public double x, y, z;
        public int world, viewEntity, perspective;
        /** The display size in pixels. */
        public int width, height;
        /** The vertical field of view in degrees. */
        public double fov;
        /** Bumped by every server position correction. */
        public int cutSerial;
        /** The Strength setting; held to 1..9 here. */
        public int strength = MotionBlurRules.DEFAULT_STRENGTH;
    }

    private boolean seeded;
    private long lastNanos;
    private double lastYaw, lastPitch, lastX, lastY, lastZ;
    private int lastWorld, lastViewEntity, lastPerspective, lastWidth, lastHeight, lastCutSerial;

    private double speedYaw, speedPitch;
    private boolean active;
    private double smearX, smearY, smearPx;
    private int taps;
    private String resetReason;

    /** Makes the next {@link #update} a first frame (reset). Touches nothing else. */
    public void invalidate() {
        seeded = false;
    }

    /** Advances one frame; returns {@link #pass()}. */
    public boolean update(Sample s) {
        String reason = resetReason(s);
        double dtSeconds = Math.max(MotionBlurRules.MIN_DT_SECONDS, (s.nanos - lastNanos) / 1e9);
        if (reason == null) {
            double dist = Math.sqrt(sq(s.x - lastX) + sq(s.y - lastY) + sq(s.z - lastZ));
            if (dist > MotionBlurRules.TELEPORT_BLOCKS && dist / dtSeconds > MotionBlurRules.TELEPORT_BLOCKS_PER_SECOND) {
                reason = RESET_TELEPORT;
            }
        }
        resetReason = reason;
        if (reason != null) {
            speedYaw = 0;
            speedPitch = 0;
            active = false;
            smearX = 0;
            smearY = 0;
            smearPx = 0;
            taps = 0;
            seed(s);
            return false;
        }

        int strength = MotionBlurRules.clampStrength(s.strength);
        double rawYaw = MotionBlurRules.wrapDegrees(s.yaw - lastYaw) / dtSeconds;
        double rawPitch = (s.pitch - lastPitch) / dtSeconds;
        double keep = MotionBlurRules.keep(dtSeconds);
        speedYaw = keep * speedYaw + (1 - keep) * rawYaw;
        speedPitch = keep * speedPitch + (1 - keep) * rawPitch;

        double exposure = MotionBlurRules.exposureSeconds(strength);
        double aspect = (double) s.width / s.height;
        double fx = MotionBlurRules.smearX(speedYaw, exposure, s.fov, aspect);
        double fy = MotionBlurRules.smearY(speedPitch, exposure, s.fov);
        double length = Math.sqrt(fx * fx + fy * fy);
        double max = MotionBlurRules.maxSmear(strength);
        if (length > max) {
            // Clamp the smoothed speed itself, so the decay after a flick starts at most from the cap.
            double scale = max / length;
            speedYaw *= scale;
            speedPitch *= scale;
            fx = MotionBlurRules.smearX(speedYaw, exposure, s.fov, aspect);
            fy = MotionBlurRules.smearY(speedPitch, exposure, s.fov);
        }
        double px = MotionBlurRules.smearPx(fx, fy, s.width, s.height);

        if (!active && px >= MotionBlurRules.ON_PX) {
            active = true;
        } else if (active && px < MotionBlurRules.OFF_PX) {
            active = false;
            speedYaw = 0;
            speedPitch = 0;
            fx = 0;
            fy = 0;
            px = 0;
        }
        smearX = fx;
        smearY = fy;
        smearPx = px;
        taps = active ? MotionBlurRules.taps(px, MotionBlurRules.maxTaps(strength)) : 0;
        seed(s);
        return active;
    }

    private String resetReason(Sample s) {
        if (!seeded) return RESET_FIRST_FRAME;
        if (s.cutSerial != lastCutSerial) return RESET_POSITION_CORRECTION;
        if (s.world != lastWorld) return RESET_WORLD;
        if (s.viewEntity != lastViewEntity) return RESET_VIEW_ENTITY;
        if (s.perspective != lastPerspective) return RESET_PERSPECTIVE;
        if (s.width != lastWidth || s.height != lastHeight || s.width <= 0 || s.height <= 0 || !(s.fov > 0)) {
            return RESET_SIZE;
        }
        if (s.nanos - lastNanos > MotionBlurRules.MAX_GAP_NANOS) return RESET_GAP;
        return null;
    }

    private void seed(Sample s) {
        seeded = true;
        lastNanos = s.nanos;
        lastYaw = s.yaw;
        lastPitch = s.pitch;
        lastX = s.x;
        lastY = s.y;
        lastZ = s.z;
        lastWorld = s.world;
        lastViewEntity = s.viewEntity;
        lastPerspective = s.perspective;
        lastWidth = s.width;
        lastHeight = s.height;
        lastCutSerial = s.cutSerial;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Whether the world pass runs this frame: the {@code active} flag after the last {@link #update}. */
    public boolean pass() {
        return active;
    }

    public boolean active() {
        return active;
    }

    /** The smoothed yaw speed, degrees a second (exactly 0 after a reset or once the pass turns off). */
    public double speedYaw() {
        return speedYaw;
    }

    /** The smoothed pitch speed, degrees a second. */
    public double speedPitch() {
        return speedPitch;
    }

    /** The smear along x, a signed fraction of the screen width (positive while yaw increases). */
    public double smearX() {
        return smearX;
    }

    /** The smear along y, a signed fraction of the screen height (positive while pitch increases). */
    public double smearY() {
        return smearY;
    }

    /** The smear's on-screen length in pixels. */
    public double smearPx() {
        return smearPx;
    }

    /** The taps the pass takes; 0 when it does not run. */
    public int taps() {
        return taps;
    }

    /** Why the last frame reset (one of the {@code RESET_*} ids), or null if it did not. */
    public String resetReason() {
        return resetReason;
    }
}
