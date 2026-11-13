package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Zoom and No Hurt Cam (Ornithe build): the defaults, when zoom applies, the FOV it produces, and
 * that releasing the key gives the unzoomed FOV back on the very next frame (no latching).
 */
public class CameraRulesTest {

    private static final float EPS = 1e-4f;

    @Test
    public void defaultsAreZoomOnWithCAndNoHurtCamOff() {
        assertTrue(CameraRules.DEFAULT_ZOOM_ENABLED);
        assertFalse(CameraRules.DEFAULT_NO_HURT_CAM);
        assertEquals(46, CameraRules.DEFAULT_ZOOM_KEY); // LWJGL Keyboard.KEY_C, OptiFine's zoom key
        assertEquals("Zoom", CameraRules.ZOOM_KEY_NAME); // options.txt key_Zoom
    }

    @Test
    public void zoomsOnlyWhileEnabledHeldAndNoScreenIsOpen() {
        assertTrue(CameraRules.zooming(true, true, false));
        assertFalse(CameraRules.zooming(false, true, false)); // Zoom off: the key does nothing
        assertFalse(CameraRules.zooming(true, false, false)); // not held
        assertFalse(CameraRules.zooming(true, true, true));   // typing in chat or a menu open
    }

    @Test
    public void zoomDividesTheWorldFovByTheFactor() {
        assertEquals(4f, CameraRules.ZOOM_FACTOR, EPS);
        assertEquals(17.5f, CameraRules.fov(70f, true, true), EPS);
        assertEquals(27.5f, CameraRules.fov(110f, true, true), EPS);
    }

    @Test
    public void handFovAndUnzoomedFovAreUnchanged() {
        assertEquals(70f, CameraRules.fov(70f, false, true), EPS); // the hand pass (no FOV setting)
        assertEquals(70f, CameraRules.fov(70f, true, false), EPS);
    }

    @Test
    public void releasingTheKeyRestoresTheFovAtOnce() {
        float base = 90f;
        float held = CameraRules.fov(base, true, CameraRules.zooming(true, true, false));
        float released = CameraRules.fov(base, true, CameraRules.zooming(true, false, false));
        assertTrue(held < base);
        assertEquals(base, released, EPS);
    }

    @Test
    public void hurtCamIsCanceledOnlyWithNoHurtCamOn() {
        assertTrue(CameraRules.cancelHurtCam(true));
        assertFalse(CameraRules.cancelHurtCam(false));
    }
}
