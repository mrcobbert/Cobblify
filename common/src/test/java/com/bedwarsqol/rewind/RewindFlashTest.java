package com.bedwarsqol.rewind;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** One white full-screen flash: peak alpha 0x40, 250 ms ease-out, nothing otherwise. */
public class RewindFlashTest {

    @Test
    public void peaksAtTheStartAndEndsAt250ms() {
        assertEquals(0x40, RewindFlash.alpha(0));
        assertEquals(0, RewindFlash.alpha(250));
        assertEquals(0, RewindFlash.alpha(251));
        assertEquals(0, RewindFlash.alpha(10_000));
        assertEquals(0, RewindFlash.alpha(-1));
        assertTrue(RewindFlash.alpha(249) > 0);
    }

    @Test
    public void fadesMonotonicallyWithAnEaseOut() {
        int prev = RewindFlash.alpha(0);
        for (long t = 1; t <= 250; t++) {
            int a = RewindFlash.alpha(t);
            assertTrue("t=" + t, a <= prev);
            prev = a;
        }
        // Ease-out: most of the change happens early, so at half time it is well below half the peak.
        assertTrue(RewindFlash.alpha(125) < 0x40 / 2);
        assertEquals(0x10, RewindFlash.alpha(125));
    }

    @Test
    public void colourIsWhiteWithTheAlpha() {
        assertEquals(0x40FFFFFF, RewindFlash.color(0));
        assertEquals(0, RewindFlash.color(250));
        int mid = RewindFlash.color(100);
        assertEquals(0xFFFFFF, mid & 0xFFFFFF);
        assertEquals(RewindFlash.alpha(100), mid >>> 24);
    }

    @Test
    public void aFlashInstanceDrawsOnlyAfterStartAndUntilItEnds() {
        RewindFlash f = new RewindFlash();
        assertFalse(f.isActive(1000));
        assertEquals(0, f.colorAt(1000));
        f.start(1000);
        assertTrue(f.isActive(1000));
        assertEquals(0x40FFFFFF, f.colorAt(1000));
        assertTrue(f.isActive(1249));
        assertFalse(f.isActive(1250));
        assertEquals(0, f.colorAt(1250));
        f.start(2000);                                                   // a second press restarts it
        assertEquals(0x40FFFFFF, f.colorAt(2000));
        assertEquals(0, f.colorAt(1999));                                // clock before start draws nothing
    }
}
