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
}
