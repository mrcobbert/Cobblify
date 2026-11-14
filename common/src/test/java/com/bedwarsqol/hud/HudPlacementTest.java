package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HudPlacementTest {

    private static final float EPS = 1e-4f;

    @Test
    public void topLeftAnchorIsAPlainOffset() {
        assertEquals(5f, HudPlacement.absoluteX(5, 0, 40, 480), EPS);
        assertEquals(7f, HudPlacement.absoluteY(7, 0, 20, 270), EPS);
    }

    @Test
    public void bottomRightAnchorKeepsTheFarEdgeFixed() {
        // stored -10 from the right edge: the box's right edge sits 10 px in, whatever its width
        assertEquals(480f - 10f - 60f, HudPlacement.absoluteX(-10, 8, 60, 480), EPS);
        assertEquals(480f - 10f - 90f, HudPlacement.absoluteX(-10, 8, 90, 480), EPS);
        assertEquals(270f - 20f - 74f, HudPlacement.absoluteY(-20, 8, 74, 270), EPS);
    }

    @Test
    public void storedIsTheInverseOfAbsolute() {
        for (int anchor = 0; anchor <= 8; anchor++) {
            float x = HudPlacement.absoluteX(-12, anchor, 50, 640);
            float y = HudPlacement.absoluteY(33, anchor, 30, 360);
            assertEquals(-12, HudPlacement.storedX(x, anchor, 50, 640));
            assertEquals(33, HudPlacement.storedY(y, anchor, 30, 360));
        }
    }

    @Test
    public void exactStoredIsTheUnroundedInverseOfAbsolute() {
        float[][] screens = {{427f, 240f}, {640f, 360f}, {427.5f, 240.5f}, {960f, 540f}};
        float[][] boxes = {{206f, 74f}, {40.5f, 21.25f}, {51f, 1f}};
        float[] positions = {0f, 110.5f, 10.4f, 133.333f, 2f};
        for (float[] scr : screens) {
            for (float[] box : boxes) {
                for (float p : positions) {
                    for (int anchor = 0; anchor <= 8; anchor++) {
                        float sx = HudPlacement.exactStoredX(p, anchor, box[0], scr[0]);
                        float sy = HudPlacement.exactStoredY(p, anchor, box[1], scr[1]);
                        String at = "anchor " + anchor + " p " + p + " box " + box[0] + " screen " + scr[0];
                        assertEquals(at, p, HudPlacement.absoluteX(sx, anchor, box[0], scr[0]), EPS);
                        assertEquals(at, p, HudPlacement.absoluteY(sy, anchor, box[1], scr[1]), EPS);
                        assertEquals(at, HudPlacement.storedX(p, anchor, box[0], scr[0]), Math.round(sx));
                        assertEquals(at, HudPlacement.storedY(p, anchor, box[1], scr[1]), Math.round(sy));
                    }
                }
            }
        }
    }

    @Test
    public void anchorFollowsTheScreenThirds() {
        assertEquals(0, HudPlacement.anchorFor(5, 5, 40, 20, 480, 270));
        assertEquals(2, HudPlacement.anchorFor(430, 5, 45, 20, 480, 270));
        assertEquals(6, HudPlacement.anchorFor(5, 240, 40, 25, 480, 270));
        assertEquals(8, HudPlacement.anchorFor(400, 200, 60, 60, 480, 270));
        assertEquals(1, HudPlacement.anchorFor(200, 5, 40, 20, 480, 270));
        assertEquals(3, HudPlacement.anchorFor(5, 120, 40, 20, 480, 270));
        assertEquals(5, HudPlacement.anchorFor(430, 120, 45, 20, 480, 270));
        assertEquals(7, HudPlacement.anchorFor(200, 240, 40, 25, 480, 270));
        assertEquals(4, HudPlacement.anchorFor(200, 120, 40, 20, 480, 270));
    }

    @Test
    public void outOfRangeAnchorsAreTreatedAsTheNearestValidOne() {
        assertEquals(HudPlacement.absoluteX(5, 0, 40, 480), HudPlacement.absoluteX(5, -3, 40, 480), EPS);
        assertEquals(HudPlacement.absoluteX(5, 8, 40, 480), HudPlacement.absoluteX(5, 12, 40, 480), EPS);
    }

    @Test
    public void clampKeepsTheBoxAndItsPaddingOnScreen() {
        assertEquals(100f, HudPlacement.clamp(100, 50, 4, 480), EPS);
        assertEquals(4f, HudPlacement.clamp(-30, 50, 4, 480), EPS);
        assertEquals(480f - 50f - 4f, HudPlacement.clamp(470, 50, 4, 480), EPS);
    }

    @Test
    public void aBoxBiggerThanTheScreenKeepsItsLeadingEdgeVisible() {
        assertEquals(0f, HudPlacement.clamp(200, 600, 0, 480), EPS);
        assertEquals(0f, HudPlacement.clamp(-200, 600, 0, 480), EPS);
    }
}
