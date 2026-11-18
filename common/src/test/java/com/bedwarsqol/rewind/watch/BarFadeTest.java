package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The viewer bar (G36, mockup): shown on mouse move, over the bar or while dragging; in 120 ms, out 200 ms. */
public class BarFadeTest {

    private static final float EPS = 1e-6f;

    @Test
    public void hiddenUntilTheMouseMoves() {
        BarFade b = new BarFade();
        assertEquals(0f, b.update(1000, false, false), 0f);
        assertFalse(b.visible());
    }

    @Test
    public void fadesInOverOneHundredTwentyMilliseconds() {
        BarFade b = new BarFade();
        b.update(0, false, false);
        b.mouseMoved(0);
        assertEquals(0.5f, b.update(60, false, false), EPS);
        assertTrue(b.visible());
        assertEquals(1f, b.update(120, false, false), EPS);
        assertEquals(1f, b.update(500, false, false), EPS);
        assertEquals(1f, b.alpha(), EPS);
    }

    @Test
    public void staysForTwoSecondsAfterTheLastMoveThenFadesOutOverTwoHundred() {
        BarFade b = new BarFade();
        b.update(0, false, false);
        b.mouseMoved(0);
        b.update(200, false, false);
        b.mouseMoved(1000);
        assertEquals(1f, b.update(2999, false, false), EPS);
        assertEquals(1f, b.update(3000, false, false), EPS); // 2 s still: the fade starts here
        assertEquals(0.5f, b.update(3100, false, false), EPS);
        assertEquals(0f, b.update(3200, false, false), EPS);
        assertFalse(b.visible());
        assertEquals(0f, b.update(9000, false, false), 0f);
    }

    @Test
    public void overTheBarOrDraggingKeepsIt() {
        BarFade b = new BarFade();
        b.update(0, false, false);
        b.mouseMoved(0);
        b.update(120, false, false);
        assertEquals(1f, b.update(5000, true, false), EPS);
        assertEquals(1f, b.update(9000, false, true), EPS);
        assertEquals(0.5f, b.update(9100, false, false), EPS);
    }

    @Test
    public void aMoveDuringTheFadeOutTurnsItBack() {
        BarFade b = new BarFade();
        b.update(0, false, false);
        b.mouseMoved(0);
        b.update(120, false, false);
        b.update(2000, false, false);
        assertEquals(0.5f, b.update(2100, false, false), EPS);
        b.mouseMoved(2100);
        assertEquals(1f, b.update(2160, false, false), EPS);
    }
}
