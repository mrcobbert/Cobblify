package com.bedwarsqol.feature;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Motion Blur (Ornithe build): the world smears along the direction the camera turns. Two hooks drive it.
 * <ul>
 *   <li><b>W</b>, {@code EntityRendererMotionBlurMixin}, runs in the final world pass just before the hand is drawn,
 *       so the held item, the HUD and every screen stay sharp. It calls {@link #gate}, fills a
 *       {@link MotionBlurFrame.Sample} and calls {@link #render}.</li>
 *   <li><b>C</b>, {@code NetHandlerPlayClientMixin}, calls {@link #onPositionCorrected} after every server
 *       position/look correction, so the next frame is a camera cut and draws unblurred.</li>
 * </ul>
 * {@link #decide} is plain Java (no Minecraft or GL type), so a unit test drives it. The GL work is in
 * {@link MotionBlurGl}, entered only on frames whose decision is "pass": with the module off, suppressed, failed or
 * the camera still, no GL call is made.
 *
 * <p>The probe record ({@link #record}) describes the last decided frame. Render (client) thread only, like the
 * hooks.
 */
public final class MotionBlur {

    private static final Logger LOGGER = LogManager.getLogger("Cobblify");

    private static final MotionBlurFrame FRAME = new MotionBlurFrame();
    private static final MotionBlurFrame.Sample SAMPLE = new MotionBlurFrame.Sample();

    /** Bumped by hook C on every server position/look correction. */
    private static int cutSerial;
    /** Rewind: while set, hook W returns before any GL call. */
    private static boolean suppressed;
    /** Whether the last {@link #gate} let the hook go on; a false-to-true edge makes the next frame a first frame. */
    private static boolean wasRunning;
    /** Why the effect turned itself off for the session, or null. */
    private static String failed;

    // The probe record: the last decided frame.
    private static long frameId;
    private static double yaw, pitch, smearPx;
    private static int taps, recordedCutSerial;
    private static boolean active, worldPassRan;
    private static String resetReason;
    /** Every entry into {@link MotionBlurGl} that can touch GL. */
    private static long glEntries;

    private MotionBlur() {
    }

    /** Hook C: the server corrected the player's position or look. Reads and sends nothing; touches no GL. */
    public static void onPositionCorrected() {
        cutSerial++;
    }

    /** Rewind: suppresses the effect while set; the first frame after it clears resets. */
    public static void suppress(boolean on) {
        if (suppressed && !on) FRAME.invalidate();
        suppressed = on;
    }

    public static boolean suppressed() {
        return suppressed;
    }

    /** Makes the next decided frame a first frame (reset). */
    public static void invalidate() {
        FRAME.invalidate();
    }

    /**
     * Hook W, step 1: whether the hook goes on this frame, which is only the final world pass ({@code pass == 2})
     * while {@link MotionBlurRules#shouldRun} holds. Plain Java. Coming back after any frame that did not go on (the
     * module was switched on, Rewind ended, a world loaded) invalidates the frame state, so that frame resets.
     */
    public static boolean gate(int pass, boolean enabled, boolean worldLoaded, boolean anaglyph) {
        boolean run = pass == 2 && MotionBlurRules.shouldRun(enabled, suppressed, worldLoaded, anaglyph, failed != null);
        if (!run) {
            wasRunning = false;
            worldPassRan = false;
            return false;
        }
        if (!wasRunning) {
            FRAME.invalidate();
            wasRunning = true;
        }
        return true;
    }

    /** The sample hook W fills each frame (render thread only; reused). */
    public static MotionBlurFrame.Sample sample() {
        return SAMPLE;
    }

    /**
     * Hook W, step 3: advances the frame rules with {@code s} and records the result; returns whether the pass runs.
     * {@code s.cutSerial} is overwritten with the current position-correction count. No Minecraft or GL dependency.
     */
    public static boolean decide(MotionBlurFrame.Sample s) {
        s.cutSerial = cutSerial;
        boolean pass = FRAME.update(s);
        frameId++;
        yaw = s.yaw;
        pitch = s.pitch;
        smearPx = FRAME.smearPx();
        taps = FRAME.taps();
        active = FRAME.active();
        resetReason = FRAME.resetReason();
        recordedCutSerial = s.cutSerial;
        worldPassRan = false;
        return pass;
    }

    /**
     * Hook W, steps 3-5: {@link #decide}; on "no pass" returns with no GL call, else runs the world pass on the bound
     * framebuffer.
     */
    public static void render(MotionBlurFrame.Sample s) {
        if (!decide(s)) return;
        worldPassRan = MotionBlurGl.run((float) FRAME.smearX(), (float) FRAME.smearY(), FRAME.taps(), s.width, s.height);
    }

    /** Frees the texture and program; the module was switched off. No GL call when nothing is held. */
    public static void release() {
        MotionBlurGl.release();
    }

    /** Called by {@link MotionBlurGl} on each entry that can touch GL. */
    static void countGlEntry() {
        glEntries++;
    }

    /** A setup or pass failure: logged once, recorded, and the effect stays off for the session. */
    static void fail(String reason) {
        if (failed != null) return;
        failed = reason;
        LOGGER.warn("Motion Blur turned off for this session: {}", reason);
    }

    public static long frameId() {
        return frameId;
    }

    /** The sampled camera yaw of the last decided frame, degrees. */
    public static double yaw() {
        return yaw;
    }

    /** The sampled camera pitch of the last decided frame, degrees. */
    public static double pitch() {
        return pitch;
    }

    public static double smearPx() {
        return smearPx;
    }

    public static int taps() {
        return taps;
    }

    public static boolean active() {
        return active;
    }

    /** Whether the GL pass drew on the last frame. */
    public static boolean worldPassRan() {
        return worldPassRan;
    }

    public static String resetReason() {
        return resetReason;
    }

    /** The position-correction count now (bumped by hook C). */
    public static int cutSerial() {
        return cutSerial;
    }

    /** The position-correction count the last decided frame saw. */
    public static int recordedCutSerial() {
        return recordedCutSerial;
    }

    public static long glEntries() {
        return glEntries;
    }

    /** Why the effect is off for the session, or null. */
    public static String failed() {
        return failed;
    }

    /** The probe record of the last decided frame, by name. Read on the client thread. */
    public static Map<String, Object> record() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("frameId", frameId);
        m.put("yaw", yaw);
        m.put("pitch", pitch);
        m.put("smearPx", smearPx);
        m.put("taps", taps);
        m.put("active", active);
        m.put("worldPassRan", worldPassRan);
        m.put("resetReason", resetReason);
        m.put("cutSerial", recordedCutSerial);
        m.put("cutSerialNow", cutSerial);
        m.put("glEntries", glEntries);
        m.put("failed", failed);
        m.put("suppressed", suppressed);
        return m;
    }
}
