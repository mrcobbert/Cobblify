package com.bedwarsqol.hud;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

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

    // ----- the rectangle a resize really applies, for the wall cap (review I2) -----

    private static HudWalls.Rect rectAt(CornerResize r, float scale, float baseW, float baseH, float pad) {
        return r.visualRectAt(scale, baseW * scale, baseH * scale, pad);
    }

    private static void rect(float x, float y, float w, float h, HudWalls.Rect got) {
        assertEquals(x, got.x, 1e-4f);
        assertEquals(y, got.y, 1e-4f);
        assertEquals(w, got.w, 1e-4f);
        assertEquals(h, got.h, 1e-4f);
    }

    private static HudWalls.ScaleProbe probe(final CornerResize r, final float baseW, final float baseH, final float pad) {
        return new HudWalls.ScaleProbe() {
            @Override
            public HudWalls.Rect at(float scale) {
                return rectAt(r, scale, baseW, baseH, pad);
            }
        };
    }

    @Test
    public void topLeftResizeKeepsTheBottomRightCornerInTheProbe() {
        CornerResize r = new CornerResize(CornerResize.TOP_LEFT, 102, 102, 40, 20, 1f);
        rect(61, 81, 82, 42, rectAt(r, 2f, 40, 20, 1));
        HudWalls.Rect at2 = rectAt(r, 2f, 40, 20, 1);
        assertEquals("bottom-right content corner stays", 142f + 1f, at2.x + at2.w, 1e-4f);
        assertEquals(122f + 1f, at2.y + at2.h, 1e-4f);
        List<HudWalls.Rect> wall = Collections.singletonList(new HudWalls.Rect(60, 70, 30, 80));
        assertEquals(1.275f, HudWalls.largestFreeScale(1f, 2f, probe(r, 40, 20, 1), wall), 1e-3f);
    }

    @Test
    public void everyCornerStopsAtAWallOnItsSide() {
        // top-right corner grows right and up, with its bottom-left corner (102, 122) fixed; wall to the right at 200
        CornerResize tr = new CornerResize(CornerResize.TOP_RIGHT, 102, 102, 40, 20, 1f);
        rect(101, 81, 82, 42, rectAt(tr, 2f, 40, 20, 1));
        List<HudWalls.Rect> right = Collections.singletonList(new HudWalls.Rect(200, 0, 30, 270));
        // right edge 102 + 40s + 1 reaches 200 at s = 2.425
        assertEquals(2.425f, HudWalls.largestFreeScale(1f, 4f, probe(tr, 40, 20, 1), right), 1e-3f);

        // bottom-left corner grows left and down, top-right (142, 102) fixed; wall to the left ending at x 60
        CornerResize bl = new CornerResize(CornerResize.BOTTOM_LEFT, 102, 102, 40, 20, 1f);
        rect(61, 101, 82, 42, rectAt(bl, 2f, 40, 20, 1));
        List<HudWalls.Rect> left = Collections.singletonList(new HudWalls.Rect(0, 0, 60, 270));
        // left edge 142 - 40s - 1 reaches 60 at s = 2.025
        assertEquals(2.025f, HudWalls.largestFreeScale(1f, 4f, probe(bl, 40, 20, 1), left), 1e-3f);

        // bottom-right corner grows right and down, top-left (102, 102) fixed; wall below starting at y 180
        CornerResize br = new CornerResize(CornerResize.BOTTOM_RIGHT, 102, 102, 40, 20, 1f);
        rect(101, 101, 82, 42, rectAt(br, 2f, 40, 20, 1));
        List<HudWalls.Rect> below = Collections.singletonList(new HudWalls.Rect(0, 180, 480, 40));
        // bottom edge 102 + 20s + 1 reaches 180 at s = 3.85
        assertEquals(3.85f, HudWalls.largestFreeScale(1f, 5f, probe(br, 40, 20, 1), below), 1e-3f);
    }
}
