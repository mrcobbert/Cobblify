package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class OverlayBoxTest {

    private static final float EPS = 1e-4f;

    @Test
    public void scaledAboutKeepsTheAnchorPointFixed() {
        float[] rect = {423, 107, 56, 37};
        float[] box = OverlayBox.scaledAbout(rect, 480, 135, 0.5f);
        assertArrayEquals(new float[]{480 + 0.5f * (423 - 480), 135 + 0.5f * (107 - 135), 28, 18.5f}, box, EPS);
        assertArrayEquals(rect, OverlayBox.scaledAbout(rect, 480, 135, 1f), EPS);
    }

    @Test
    public void autoAnchorReturnsTheAutoBox() {
        float[] rect = {10, 20, 30, 40};
        float[] auto = {1, 2, 3, 4};
        assertSame(auto, OverlayBox.placed(rect, 2f, 99, 99, HudPlacement.AUTO, 480, 270, auto));
    }

    @Test
    public void placedMatchesHudPlacementForEveryAnchor() {
        float[] rect = {300, 100, 60, 40};
        float s = 1.5f, sw = 480, sh = 270;
        float w = 90, h = 60;
        for (int anchor = 0; anchor <= 8; anchor++) {
            float[] box = OverlayBox.placed(rect, s, 0, 0, anchor, sw, sh, null);
            assertEquals("x anchor " + anchor, HudPlacement.absoluteX(0, anchor, w, sw), box[0], EPS);
            assertEquals("y anchor " + anchor, HudPlacement.absoluteY(0, anchor, h, sh), box[1], EPS);
            assertEquals(w, box[2], EPS);
            assertEquals(h, box[3], EPS);
            float[] moved = OverlayBox.placed(rect, s, 7, -5, anchor, sw, sh, null);
            float ex = HudPlacement.clamp(HudPlacement.absoluteX(7, anchor, w, sw), w, 0, sw);
            float ey = HudPlacement.clamp(HudPlacement.absoluteY(-5, anchor, h, sh), h, 0, sh);
            assertEquals(ex, moved[0], EPS);
            assertEquals(ey, moved[1], EPS);
        }
    }

    @Test
    public void placedBoxesClampOnScreen() {
        float[] rect = {0, 0, 100, 50};
        float[] off = OverlayBox.placed(rect, 1f, -40, -30, 0, 480, 270, null);
        assertEquals(0f, off[0], EPS);
        assertEquals(0f, off[1], EPS);
        float[] farRight = OverlayBox.placed(rect, 1f, 60, 40, 8, 480, 270, null);
        assertEquals(380f, farRight[0], EPS);
        assertEquals(220f, farRight[1], EPS);
        // a box larger than the screen keeps its leading edge on screen
        float[] huge = OverlayBox.placed(rect, 6f, 20, 20, 8, 480, 270, null);
        assertEquals(0f, huge[0], EPS);
        assertEquals(0f, huge[1], EPS);
        assertEquals(600f, huge[2], EPS);
    }

    @Test
    public void toScreenAndToVanillaAreInversesAndMapCorners() {
        float[] rect = {423, 107, 56, 37};
        float s = 0.75f;
        float[] box = {12, 30, 56 * s, 37 * s};
        assertArrayEquals(new float[]{12, 30}, OverlayBox.toScreen(rect, box, s, 423, 107), EPS);
        assertArrayEquals(new float[]{12 + 56 * s, 30 + 37 * s}, OverlayBox.toScreen(rect, box, s, 479, 144), EPS);
        for (float vx = 400; vx < 500; vx += 7.3f) {
            for (float vy = 90; vy < 160; vy += 5.1f) {
                float[] sc = OverlayBox.toScreen(rect, box, s, vx, vy);
                assertArrayEquals(new float[]{vx, vy}, OverlayBox.toVanilla(rect, box, s, sc[0], sc[1]), 1e-3f);
            }
        }
    }
}
