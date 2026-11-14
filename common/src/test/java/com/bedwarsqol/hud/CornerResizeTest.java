package com.bedwarsqol.hud;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void theProbeAppliesTheScreenClampWithTheGrownPadding() {
        // code review R1-I1: a panel whose padding grows with its scale (4 su per 1.0), grown by its top-left corner
        // towards the screen edge, is pushed back on screen by the renderer's clamp with the new padding; the wall
        // check must see that rectangle. Content (104,90,100,30) at scale 1, screen 480x270, wall (214,50,40,100).
        final CornerResize r = new CornerResize(CornerResize.TOP_LEFT, 104, 90, 100, 30, 1f);
        HudWalls.ScaleProbe probe = new HudWalls.ScaleProbe() {
            @Override
            public HudWalls.Rect at(float scale) {
                return r.visualRectAt(scale, 100 * scale, 30 * scale, 4 * scale, 480, 270);
            }
        };
        rect(0, 52, 216, 76, probe.at(2f)); // content x 4 clamped to the new pad 8: right edge 216, into the wall
        List<HudWalls.Rect> wall = Collections.singletonList(new HudWalls.Rect(214, 50, 40, 100));
        float s = HudWalls.largestFreeScale(1f, 2f, probe, wall);
        assertEquals("clamped right edge 108 s meets the wall at 214", 214f / 108f, s, 1e-3f);
        HudWalls.Rect at = probe.at(s);
        assertTrue(HudWalls.free(at.x, at.y, at.w, at.h, wall));
        // away from the screen edge the clamp changes nothing
        rect(61, 81, 82, 42, new CornerResize(CornerResize.TOP_LEFT, 102, 102, 40, 20, 1f)
                .visualRectAt(2f, 80, 40, 1, 480, 270));
    }

    // ----- cross-cycle A-I1: the box is placed from its measured size -----

    @Test
    public void aMeasuredSizeKeepsTheOppositeCornerPinned() {
        // A module laid out at its text scale measures in whole device pixels: at raw scale 0.75 in Minecraft's font at
        // GUI 2 it still measures 56 x 67, not 42 x 50.25. Placing it from base x raw scale moved it instead.
        float[][] sizes = {{56f, 67f}, {84f, 100.5f}, {28f, 33.5f}};
        for (float[] m : sizes) {
            CornerResize tl = new CornerResize(CornerResize.TOP_LEFT, 100, 100, 56, 67, 1f);
            assertEquals(156f, tl.leftFor(m[0]) + m[0], EPS);
            assertEquals(167f, tl.topFor(m[1]) + m[1], EPS);
            CornerResize tr = new CornerResize(CornerResize.TOP_RIGHT, 100, 100, 56, 67, 1f);
            assertEquals(100f, tr.leftFor(m[0]), EPS);
            assertEquals(167f, tr.topFor(m[1]) + m[1], EPS);
            CornerResize bl = new CornerResize(CornerResize.BOTTOM_LEFT, 100, 100, 56, 67, 1f);
            assertEquals(156f, bl.leftFor(m[0]) + m[0], EPS);
            assertEquals(100f, bl.topFor(m[1]), EPS);
            CornerResize br = new CornerResize(CornerResize.BOTTOM_RIGHT, 100, 100, 56, 67, 1f);
            assertEquals(100f, br.leftFor(m[0]), EPS);
            assertEquals(100f, br.topFor(m[1]), EPS);
        }
        // with the size base x scale, the measured placement is today's
        CornerResize tl = new CornerResize(CornerResize.TOP_LEFT, 100, 100, 56, 67, 1f);
        assertEquals(tl.left(1.5f), tl.leftFor(84f), EPS);
        assertEquals(tl.top(1.5f), tl.topFor(100.5f), EPS);
    }

    @Test
    public void theProbePlacesTheMeasuredBoxAtThePinnedCorner() {
        // top-left corner, raw scale 0.5, but the box still measures 56 x 67: the probe's rectangle keeps the
        // bottom-right corner (156, 167) where the screen will draw it, not 28 px to the right
        CornerResize r = new CornerResize(CornerResize.TOP_LEFT, 100, 100, 56, 67, 1f);
        rect(99, 99, 58, 69, r.visualRectAt(0.5f, 56, 67, 1));
        rect(99, 99, 58, 69, r.visualRectAt(0.5f, 56, 67, 1, 480, 270));
    }
}
