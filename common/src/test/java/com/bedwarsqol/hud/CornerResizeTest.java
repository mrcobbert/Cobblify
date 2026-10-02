package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CornerResizeTest {

    private static final float EPS = 1e-4f;
    private static final float SW = 1000f;
    private static final float SH = 1000f;

    @Test
    public void bottomRightGrowsAwayFromAFixedTopLeft() {
        CornerResize r = new CornerResize(CornerResize.BOTTOM_RIGHT, 100, 100, 40, 20, 1f);
        float s = r.scaleFor(180, 140, SW, SH, 0, false); // corner at 2x is (180, 140)
        assertEquals(2f, s, EPS);
        assertEquals(100f, r.left(s), EPS);
        assertEquals(100f, r.top(s), EPS);
    }

    @Test
    public void topLeftGrowsAwayFromAFixedBottomRight() {
        CornerResize r = new CornerResize(CornerResize.TOP_LEFT, 100, 100, 40, 20, 1f);
        float s = r.scaleFor(60, 80, SW, SH, 0, false); // corner at 2x is (60, 80)
        assertEquals(2f, s, EPS);
        assertEquals(60f, r.left(s), EPS);
        assertEquals(80f, r.top(s), EPS);
        assertEquals(140f, r.left(s) + 40f * s, EPS); // right edge stays put
    }

    @Test
    public void topRightAndBottomLeftPinTheirOppositeCorners() {
        CornerResize tr = new CornerResize(CornerResize.TOP_RIGHT, 100, 100, 40, 20, 1f);
        float s = tr.scaleFor(120, 110, SW, SH, 0, false); // corner at 0.5x is (120, 110)
        assertEquals(0.5f, s, EPS);
        assertEquals(100f, tr.left(s), EPS);
        assertEquals(110f, tr.top(s), EPS);

        CornerResize bl = new CornerResize(CornerResize.BOTTOM_LEFT, 100, 100, 40, 20, 1f);
        s = bl.scaleFor(60, 140, SW, SH, 0, false);
        assertEquals(2f, s, EPS);
        assertEquals(60f, bl.left(s), EPS);
        assertEquals(100f, bl.top(s), EPS);
    }

    @Test
    public void verticalDragAloneStillScales() {
        CornerResize r = new CornerResize(CornerResize.BOTTOM_RIGHT, 0, 0, 20, 20, 1f);
        // straight down from the corner: (20*20 + 40*20) / (20*20 + 20*20) = 1.5
        assertEquals(1.5f, r.scaleFor(20, 40, SW, SH, 0, false), EPS);
    }

    @Test
    public void startsFromTheCurrentScale() {
        CornerResize r = new CornerResize(CornerResize.BOTTOM_RIGHT, 0, 0, 80, 40, 2f); // base 40x20
        assertEquals(3f, r.scaleFor(120, 60, SW, SH, 0, false), EPS);
    }

    @Test
    public void nearOneSnapsToExactlyOne() {
        CornerResize r = new CornerResize(CornerResize.BOTTOM_RIGHT, 0, 0, 100, 100, 1f);
        assertEquals(1f, r.scaleFor(104, 104, SW, SH, 0, true), EPS);
        assertEquals(1.04f, r.scaleFor(104, 104, SW, SH, 0, false), EPS);
        assertEquals(1.1f, r.scaleFor(110, 110, SW, SH, 0, true), EPS);
    }

    @Test
    public void neverBelowTheMinimum() {
        CornerResize r = new CornerResize(CornerResize.BOTTOM_RIGHT, 100, 100, 40, 20, 1f);
        assertEquals(CornerResize.MIN_SCALE, r.scaleFor(0, 0, SW, SH, 0, false), EPS);
    }

    @Test
    public void neverGrowsPastTheScreenEdgeOrItsPadding() {
        CornerResize r = new CornerResize(CornerResize.BOTTOM_RIGHT, 900, 100, 40, 20, 1f);
        // only 100 px (minus 4 padding) of room to the right
        assertEquals(96f / 40f, r.scaleFor(5000, 5000, SW, SH, 4, false), EPS);
        CornerResize up = new CornerResize(CornerResize.TOP_LEFT, 100, 10, 40, 20, 1f);
        // pinned bottom at 30, so only 30 px of room above
        assertEquals(30f / 20f, up.scaleFor(-5000, -5000, SW, SH, 0, false), EPS);
    }

    @Test
    public void cornerAtFindsTheNearestCornerInReach() {
        assertEquals(CornerResize.BOTTOM_RIGHT, CornerResize.cornerAt(141, 121, 100, 100, 40, 20, 3));
        assertEquals(CornerResize.TOP_LEFT, CornerResize.cornerAt(99, 98, 100, 100, 40, 20, 3));
        assertEquals(CornerResize.TOP_RIGHT, CornerResize.cornerAt(140, 100, 100, 100, 40, 20, 3));
        assertEquals(CornerResize.BOTTOM_LEFT, CornerResize.cornerAt(100, 120, 100, 100, 40, 20, 3));
        assertEquals(-1, CornerResize.cornerAt(120, 110, 100, 100, 40, 20, 3));
    }

    @Test
    public void cornerAtPrefersTheCloserCornerOnATinyBox() {
        assertEquals(CornerResize.BOTTOM_RIGHT, CornerResize.cornerAt(104, 104, 100, 100, 4, 4, 5));
        assertEquals(CornerResize.TOP_LEFT, CornerResize.cornerAt(100, 100, 100, 100, 4, 4, 5));
    }
}
