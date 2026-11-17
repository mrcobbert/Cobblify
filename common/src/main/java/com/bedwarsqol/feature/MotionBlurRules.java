package com.bedwarsqol.feature;

/**
 * Motion Blur, a Visuals module of the Ornithe build: the world smears along the direction the camera turns. Off by
 * default, with one Strength slider 1..9 whose middle, 5, matches PolyBlur's default world strength 3.
 *
 * <p>Strength {@code s} maps to a PolyBlur-equivalent world strength {@code Pw = 3 * (10/3)^((s - 5) / 4)} (0.9 at 1,
 * 3 at 5, 10 at 9). From it: the longest smear, {@code 0.008 * Pw} of the screen; the exposure, the time whose turn
 * the smear shows, {@code (1/240 s) * (1 + max(0, Pw - 3) * 3/7)}; and the most taps, {@code floor(4 + 1.2 * Pw)}.
 * The per-frame state lives in {@link MotionBlurFrame}.
 */
public final class MotionBlurRules {

    public static final boolean DEFAULT_ENABLED = false;
    public static final int MIN_STRENGTH = 1;
    public static final int MAX_STRENGTH = 9;
    public static final int DEFAULT_STRENGTH = 5;

    /** PolyBlur's reference frame rate: at Strength 5 the exposure is one frame of it. */
    public static final double REFERENCE_FPS = 240.0;
    /** The pass turns on when the smear reaches this many screen pixels. */
    public static final double ON_PX = 1.0;
    /** The pass turns off when the smear falls under this many screen pixels; the smoothed speed is then zeroed. */
    public static final double OFF_PX = 0.1;
    /** The shortest frame time used, in seconds (0.05 ms). */
    public static final double MIN_DT_SECONDS = 0.00005;
    /** The smoothed turn speed keeps {@code 2^(-dt / HALF_LIFE_SECONDS)} of its old value each frame. */
    public static final double HALF_LIFE_SECONDS = 0.008;
    /** A frame more than this long after the last one (100 ms) resets. */
    public static final long MAX_GAP_NANOS = 100000000L;
    /** A camera move of more than this many blocks in one frame ... */
    public static final double TELEPORT_BLOCKS = 1.0;
    /** ... and faster than this many blocks a second resets (a backstop for cuts other than a position correction). */
    public static final double TELEPORT_BLOCKS_PER_SECOND = 100.0;
    /** The fewest taps a pass takes. */
    public static final int MIN_TAPS = 2;
    /** One tap for every this many pixels of smear. */
    public static final double PX_PER_TAP = 6.0;

    private MotionBlurRules() {
    }

    /** A saved Strength held to 1..9. */
    public static int clampStrength(int strength) {
        return strength < MIN_STRENGTH ? MIN_STRENGTH : Math.min(MAX_STRENGTH, strength);
    }

    /** The PolyBlur-equivalent world strength {@code Pw} of Strength {@code s} (held to 1..9): 0.9 .. 3 .. 10. */
    public static double polyBlurStrength(int s) {
        return 3.0 * Math.pow(10.0 / 3.0, (clampStrength(s) - DEFAULT_STRENGTH) / 4.0);
    }

    /** The longest smear, as a fraction of the screen: {@code 0.008 * Pw} (0.024 at Strength 5). */
    public static double maxSmear(int s) {
        return 0.008 * polyBlurStrength(s);
    }

    /** The exposure in seconds: one 240 FPS frame up to Strength 5, longer above it (4/240 s at 9). */
    public static double exposureSeconds(int s) {
        return (1.0 / REFERENCE_FPS) * (1.0 + Math.max(0.0, polyBlurStrength(s) - 3.0) * 3.0 / 7.0);
    }

    /** The most taps a pass takes: {@code floor(4 + 1.2 * Pw)} (7 at Strength 5, 16 at 9). */
    public static int maxTaps(int s) {
        return (int) Math.floor(4.0 + 1.2 * polyBlurStrength(s));
    }

    /** The taps for a smear of {@code smearPx} pixels: one per 6 px, rounded up, held to 2..{@code maxTaps}. */
    public static int taps(double smearPx, int maxTaps) {
        int t = (int) Math.ceil(smearPx / PX_PER_TAP);
        return Math.max(MIN_TAPS, Math.min(Math.max(MIN_TAPS, maxTaps), t));
    }

    /** An angle change in degrees wrapped into -180..180, so a turn across +-180 is the short way round. */
    public static double wrapDegrees(double delta) {
        double d = delta % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    /** The share of its old value the smoothed speed keeps after {@code dtSeconds}: {@code 2^(-dt / 8 ms)}. */
    public static double keep(double dtSeconds) {
        return Math.pow(2.0, -dtSeconds / HALF_LIFE_SECONDS);
    }

    /**
     * Smear axis in texture coordinates (u right, v up) for the shader, from the turn-signed smear fractions.
     * Vanilla's camera rotates the scene opposite to a yaw increase (scene detail moves left) and moves it up for a
     * pitch increase, so the scene-motion axis is {@code (-smearX, +smearY)}. The blur is centred, so only the
     * relative sign of the two components matters; it decides which diagonal a combined turn smears along.
     */
    public static double textureAxisU(double smearX) {
        return -smearX;
    }

    /** Vanilla's {@code thirdPersonView} value for the front (mirror) camera. */
    public static final int PERSPECTIVE_FRONT = 2;

    /**
     * See {@link #textureAxisU}. In front third-person vanilla adds a half turn about the vertical axis, so a pitch
     * increase moves distant scene detail down instead of up: the vertical component flips, the horizontal one does
     * not.
     */
    public static double textureAxisV(double smearY, int perspective) {
        return perspective == PERSPECTIVE_FRONT ? -smearY : smearY;
    }

    /** The horizontal smear, a fraction of the screen width, of a yaw speed: the angle turned over the horizontal FOV. */
    public static double smearX(double yawDegPerSecond, double exposureSeconds, double fovDegrees, double aspect) {
        return yawDegPerSecond * exposureSeconds / (fovDegrees * aspect);
    }

    /** The vertical smear, a fraction of the screen height, of a pitch speed: the angle turned over the FOV. */
    public static double smearY(double pitchDegPerSecond, double exposureSeconds, double fovDegrees) {
        return pitchDegPerSecond * exposureSeconds / fovDegrees;
    }

    /** The on-screen length in pixels of a smear of {@code (fx, fy)} screen fractions. */
    public static double smearPx(double fx, double fy, int width, int height) {
        double x = fx * width;
        double y = fy * height;
        return Math.sqrt(x * x + y * y);
    }

    /** The longest smear in pixels on a {@code width} x {@code height} screen. */
    public static double maxSmearPx(int s, int width, int height) {
        return maxSmear(s) * Math.max(width, height);
    }

    /** How long after the camera stops the pass ends at most, from a smear of {@code smearPx}: {@code 8 ms * log2(px / 0.1)}. */
    public static double stopTailSeconds(double smearPx) {
        return smearPx <= OFF_PX ? 0.0 : HALF_LIFE_SECONDS * Math.log(smearPx / OFF_PX) / Math.log(2.0);
    }

    /**
     * Whether the world pass hook does anything this frame. False while the module is off, while suppressed (Rewind),
     * with no world loaded, in an anaglyph pass and after a setup failure; the hook then returns before any GL call.
     */
    public static boolean shouldRun(boolean enabled, boolean suppressed, boolean worldLoaded, boolean anaglyph,
                                    boolean failed) {
        return enabled && !suppressed && worldLoaded && !anaglyph && !failed;
    }
}
