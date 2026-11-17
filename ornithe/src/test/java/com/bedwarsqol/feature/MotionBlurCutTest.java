package com.bedwarsqol.feature;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The glue between hook C and the world pass: {@link MotionBlur#onPositionCorrected()}, the one call
 * {@code NetHandlerPlayClientMixin} makes after a server position/look correction, makes the next frame
 * {@link MotionBlur#decide decided} by the real code reset and draw unblurred (REVIEW B2: a 0.5 block move plus 90 deg
 * of yaw in one 240 FPS frame). Without the call the same frame smears. And a camera at rest never enters the GL
 * helper: the frame body the world pass hook runs leaves {@code worldPassRan} false and {@code glEntries} unchanged.
 */
public class MotionBlurCutTest {

    private static final long FRAME_NANOS = 1_000_000_000L / 240;

    private long nanos;

    @Before
    public void fresh() {
        MotionBlur.invalidate();
        nanos = 5_000_000_000L;
    }

    private MotionBlurFrame.Sample rest() {
        MotionBlurFrame.Sample s = new MotionBlurFrame.Sample();
        s.yaw = 10;
        s.pitch = 5;
        s.x = 100.5;
        s.y = 64;
        s.z = -20.5;
        s.world = 1;
        s.viewEntity = 2;
        s.perspective = 0;
        s.width = 1920;
        s.height = 1080;
        s.fov = 70;
        s.strength = MotionBlurRules.DEFAULT_STRENGTH;
        return s;
    }

    private boolean next(MotionBlurFrame.Sample s) {
        nanos += FRAME_NANOS;
        s.nanos = nanos;
        return MotionBlur.decide(s);
    }

    /** A first frame (reset) and 24 more at rest. */
    private MotionBlurFrame.Sample seeded() {
        MotionBlurFrame.Sample s = rest();
        assertFalse(next(s));
        assertEquals(MotionBlurFrame.RESET_FIRST_FRAME, MotionBlur.resetReason());
        for (int i = 0; i < 24; i++) {
            assertFalse(next(s));
            assertNull(MotionBlur.resetReason());
        }
        return s;
    }

    /** The B2 frame: 0.5 block along x and 90 deg of yaw since the last frame. */
    private static void b2(MotionBlurFrame.Sample s) {
        s.x += 0.5;
        s.yaw += 90;
    }

    @Test
    public void positionCorrectionResetsTheNextFrame() {
        MotionBlurFrame.Sample s = seeded();
        int serial = MotionBlur.cutSerial();
        long frame = MotionBlur.frameId();

        MotionBlur.onPositionCorrected();
        assertEquals(serial + 1, MotionBlur.cutSerial());
        b2(s);
        assertFalse(next(s));

        assertEquals(frame + 1, MotionBlur.frameId());
        assertEquals(MotionBlurFrame.RESET_POSITION_CORRECTION, MotionBlur.resetReason());
        assertEquals(serial + 1, MotionBlur.recordedCutSerial());
        assertEquals(0.0, MotionBlur.smearPx(), 0.0);
        assertEquals(0, MotionBlur.taps());
        assertFalse(MotionBlur.active());
        assertFalse(MotionBlur.worldPassRan());
        assertEquals(100.0, MotionBlur.yaw(), 0.0);
        assertEquals(5.0, MotionBlur.pitch(), 0.0);

        // Re-seeded on the corrected pose: the same pose next frame is still and not a reset.
        assertFalse(next(s));
        assertNull(MotionBlur.resetReason());
        assertEquals(0.0, MotionBlur.smearPx(), 0.0);
    }

    @Test
    public void withoutTheCallTheSameFrameSmears() {
        MotionBlurFrame.Sample s = seeded();
        int serial = MotionBlur.cutSerial();
        b2(s);
        assertTrue(next(s));
        assertNull(MotionBlur.resetReason());
        assertEquals(serial, MotionBlur.recordedCutSerial());
        assertTrue(MotionBlur.smearPx() >= MotionBlurRules.ON_PX);
        assertTrue(MotionBlur.active());
        assertTrue(MotionBlur.taps() >= MotionBlurRules.MIN_TAPS);
    }

    @Test
    public void stillCameraNeverEntersGl() {
        long entries = MotionBlur.glEntries();
        MotionBlurFrame.Sample s = rest();
        for (int i = 0; i < 240; i++) {
            nanos += FRAME_NANOS;
            s.nanos = nanos;
            MotionBlur.render(s);
            assertFalse(MotionBlur.active());
            assertFalse(MotionBlur.worldPassRan());
            assertEquals(0.0, MotionBlur.smearPx(), 0.0);
            assertEquals(entries, MotionBlur.glEntries());
        }
        assertNull(MotionBlur.failed());
    }

    @Test
    public void offOrSuppressedGateKeepsGlUntouchedAndResetsOnReturn() {
        long entries = MotionBlur.glEntries();
        MotionBlurFrame.Sample s = seeded();
        assertFalse("module off", MotionBlur.gate(2, false, true, false));
        assertTrue(MotionBlur.gate(2, true, true, false));
        assertFalse(next(s));
        assertEquals("switching the module on resets", MotionBlurFrame.RESET_FIRST_FRAME, MotionBlur.resetReason());

        MotionBlur.suppress(true);
        assertFalse("suppressed", MotionBlur.gate(2, true, true, false));
        MotionBlur.suppress(false);
        assertTrue(MotionBlur.gate(2, true, true, false));
        assertFalse(next(s));
        assertEquals("the frame after suppress clears resets", MotionBlurFrame.RESET_FIRST_FRAME,
                MotionBlur.resetReason());

        assertFalse("anaglyph passes", MotionBlur.gate(0, true, true, true));
        assertFalse("no world", MotionBlur.gate(2, true, false, false));
        assertEquals(entries, MotionBlur.glEntries());
    }
}
