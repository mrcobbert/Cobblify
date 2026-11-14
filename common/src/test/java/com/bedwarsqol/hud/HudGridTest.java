package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Edit HUD reads the pointer in device pixels and moves by screen pixels, whatever the GUI scale. */
public class HudGridTest {

    private static final float EPS = 1e-4f;
    private static final int[] SCALES = {1, 2, 3, 4, 9};

    @Test
    public void stepIsOneScreenPixel() {
        for (int s : SCALES) assertEquals(1f / s, HudGrid.step(s), 0f);
    }

    @Test
    public void pointerFlipsYAndMapsTheTopRowToZero() {
        for (int s : SCALES) {
            int dh = 720;
            float[] topLeft = HudGrid.pointer(0, dh - 1, dh, s);
            assertEquals(0f, topLeft[0], 0f);
            assertEquals(0f, topLeft[1], 0f);
            float[] bottomRight = HudGrid.pointer(1279, 0, dh, s);
            assertEquals(1279f / s, bottomRight[0], EPS);
            assertEquals((dh - 1f) / s, bottomRight[1], EPS);
        }
    }

    @Test
    public void wholePartIsVanillasGuiPixel() {
        // GuiScreen.handleMouseInput: x * width / displayWidth, height - y * height / displayHeight - 1 (int maths)
        int dw = 1440, dh = 900;
        for (int s : new int[]{1, 2, 3, 4}) {
            int w = dw / s, h = dh / s;
            for (int ex = 0; ex < dw; ex += 7) {
                for (int ey = 0; ey < dh; ey += 11) {
                    float[] p = HudGrid.pointer(ex, ey, dh, s);
                    assertEquals("x at scale " + s, ex * w / dw, (int) Math.floor(p[0]));
                    assertEquals("y at scale " + s, h - ey * h / dh - 1, (int) Math.floor(p[1]));
                }
            }
        }
    }

    @Test
    public void consecutiveDevicePixelsAreOneStepApart() {
        for (int s : SCALES) {
            for (int e = 100; e < 110; e++) {
                assertEquals(1f / s, HudGrid.pointer(e + 1, 50, 720, s)[0] - HudGrid.pointer(e, 50, 720, s)[0], EPS);
                assertEquals(1f / s, HudGrid.pointer(50, e, 720, s)[1] - HudGrid.pointer(50, e + 1, 720, s)[1], EPS);
            }
        }
    }

    @Test
    public void nudgeKeepsAnOffGridOffset() {
        // review I1: a centred 206-wide box on a 427-wide screen sits at 110.5; one press at GUI 3 is one screen pixel
        assertEquals(110.8333f, HudGrid.nudged(110.5f, 1, 3), EPS);
        assertEquals(110.1667f, HudGrid.nudged(110.5f, -1, 3), EPS);
        assertEquals(10.75f, HudGrid.nudged(10.25f, 1, 2), EPS);
        assertEquals(10.25f + 10f / 3f, HudGrid.nudged(10.25f, 10, 3), EPS); // Shift: ten screen pixels
        assertEquals(5f, HudGrid.nudged(10f, -10, 2), EPS);
    }
}
