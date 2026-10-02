package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class KeyFadeTest {

    private static final float EPS = 1e-4f;

    @Test
    public void idleKeyIsDark() {
        assertEquals(0f, new KeyFade(120).level(false, 1000), EPS);
    }

    @Test
    public void heldKeyIsFullyLit() {
        KeyFade f = new KeyFade(120);
        assertEquals(1f, f.level(true, 1000), EPS);
        assertEquals(1f, f.level(true, 5000), EPS);
    }

    @Test
    public void releaseFadesLinearlyThenStaysDark() {
        KeyFade f = new KeyFade(120);
        f.level(true, 1000);
        assertEquals(1f, f.level(false, 1100), EPS); // release seen at 1100
        assertEquals(0.5f, f.level(false, 1160), EPS);
        assertEquals(0f, f.level(false, 1220), EPS);
        assertEquals(0f, f.level(false, 9000), EPS);
    }

    @Test
    public void pressingAgainMidFadeRelights() {
        KeyFade f = new KeyFade(120);
        f.level(true, 1000);
        f.level(false, 1100);
        assertEquals(1f, f.level(true, 1130), EPS);
        assertEquals(1f, f.level(false, 1200), EPS); // a new release restarts the fade
        assertEquals(0.5f, f.level(false, 1260), EPS);
    }

    @Test
    public void aTapBetweenFramesStillFlashes() {
        KeyFade f = new KeyFade(120);
        f.tap(1000);                                  // pressed and released before any frame
        assertEquals(0.75f, f.level(false, 1030), EPS);
        assertEquals(0f, f.level(false, 1120), EPS);
    }

    @Test
    public void aTapWhileHeldDoesNotDim() {
        KeyFade f = new KeyFade(120);
        f.tap(1000);
        assertEquals(1f, f.level(true, 1010), EPS);
        assertEquals(1f, f.level(false, 1050), EPS); // the real release starts the fade
    }
}
