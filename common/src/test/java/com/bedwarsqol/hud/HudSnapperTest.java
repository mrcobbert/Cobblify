package com.bedwarsqol.hud;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class HudSnapperTest {

    private static final float EPS = 1e-4f;
    private static final List<HudSnapper.Target> NONE = Collections.emptyList();

    @Test
    public void farFromEverythingStaysPut() {
        HudSnapper.Snap s = HudSnapper.snap(100, 40, true, 480, NONE, 3);
        assertFalse(s.snapped);
        assertEquals(100f, s.position, EPS);
    }

    @Test
    public void snapsToTheScreenEdgesAndCentre() {
        HudSnapper.Snap left = HudSnapper.snap(2, 40, true, 480, NONE, 3);
        assertTrue(left.snapped);
        assertEquals(0f, left.position, EPS);
        assertNull(left.target);

        HudSnapper.Snap right = HudSnapper.snap(438, 40, true, 480, NONE, 3);
        assertEquals(440f, right.position, EPS);
        assertEquals(480f, right.line, EPS);

        HudSnapper.Snap centre = HudSnapper.snap(221, 40, true, 480, NONE, 3); // centre at 241
        assertEquals(220f, centre.position, EPS);
        assertEquals(240f, centre.line, EPS);
    }

    @Test
    public void snapsEdgeToEdgeAndCentreToCentreWithAnotherBox() {
        HudSnapper.Target other = new HudSnapper.Target("timer", 300, 50, 60, 20);
        List<HudSnapper.Target> targets = Collections.singletonList(other);

        HudSnapper.Snap abut = HudSnapper.snap(362, 40, true, 480, targets, 3); // left near other's right (360)
        assertTrue(abut.snapped);
        assertEquals(360f, abut.position, EPS);
        assertSame(other, abut.target);

        assertFalse(HudSnapper.snap(80, 10, false, 270, targets, 3).snapped); // nearest line is 10 away
        HudSnapper.Snap centredClose = HudSnapper.snap(56, 10, false, 270, targets, 3); // centre 61 vs 60
        assertEquals(55f, centredClose.position, EPS);
        assertEquals(60f, centredClose.line, EPS);
    }

    @Test
    public void edgesNeverSnapToCentres() {
        HudSnapper.Target other = new HudSnapper.Target("timer", 300, 50, 60, 20); // centre y = 60
        HudSnapper.Snap s = HudSnapper.snap(59, 30, false, 270, Collections.singletonList(other), 3);
        assertFalse(s.snapped); // top at 59 is 1 px from the centre line, but edges only match edges
        HudSnapper.Snap screen = HudSnapper.snap(239, 40, true, 480, NONE, 3); // left edge 1 px from the screen centre
        assertFalse(screen.snapped);
    }

    @Test
    public void theClosestMatchWins() {
        HudSnapper.Target a = new HudSnapper.Target("a", 102, 0, 10, 10);
        HudSnapper.Target b = new HudSnapper.Target("b", 101, 0, 10, 10);
        HudSnapper.Snap s = HudSnapper.snap(100, 50, true, 480, Arrays.asList(a, b), 3);
        assertSame(b, s.target);
        assertEquals(101f, s.position, EPS);
    }

    @Test
    public void exactlyAtTheThresholdStillSnaps() {
        assertTrue(HudSnapper.snap(3, 40, true, 480, NONE, 3).snapped);
        assertFalse(HudSnapper.snap(3.5f, 40, true, 480, NONE, 3).snapped);
    }

    // ----- edit-hud: a gentler snap, 4 screen pixels at every GUI scale -----

    @Test
    public void thresholdIsFourScreenPixels() {
        assertEquals(4f, HudSnapper.SNAP_DEVICE_PX, 0f);
        assertEquals(2f, HudSnapper.threshold(2), 0f);
        assertEquals(4f / 3f, HudSnapper.threshold(3), EPS);
    }

    @Test
    public void snapsWithinFourScreenPixelsAndNotAtFive() {
        HudSnapper.Target other = new HudSnapper.Target("timer", 300, 50, 60, 20);
        List<HudSnapper.Target> targets = Collections.singletonList(other);
        for (int scale : new int[]{2, 3}) {
            float t = HudSnapper.threshold(scale);
            float three = 3f / scale, five = 5f / scale;
            // the left screen edge
            HudSnapper.Snap edge = HudSnapper.snap(three, 40, true, 480, NONE, t);
            assertTrue(edge.snapped);
            assertEquals(0f, edge.position, 0f);
            assertFalse(HudSnapper.snap(five, 40, true, 480, NONE, t).snapped);
            // another box's right edge (360)
            HudSnapper.Snap abut = HudSnapper.snap(360f + three, 40, true, 480, targets, t);
            assertTrue(abut.snapped);
            assertEquals(360f, abut.position, 0f);
            assertFalse(HudSnapper.snap(360f + five, 40, true, 480, targets, t).snapped);
            // the screen centre (240): a 40-wide box centred there starts at 220
            HudSnapper.Snap centre = HudSnapper.snap(220f + three, 40, true, 480, NONE, t);
            assertTrue(centre.snapped);
            assertEquals(220f, centre.position, 0f);
            assertFalse(HudSnapper.snap(220f + five, 40, true, 480, NONE, t).snapped);
        }
    }
}
