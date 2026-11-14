package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class TabListLayoutTest {

    private static final float EPS = 1e-4f;

    @Test
    public void columnsAndRowsSplitAtTwentyRows() {
        int[][] cases = {{1, 1, 1}, {20, 1, 20}, {21, 2, 11}, {40, 2, 20}, {41, 3, 14}, {80, 4, 20}, {0, 1, 0}};
        for (int[] c : cases) {
            assertEquals(c[0] + " players: columns", c[1], TabListLayout.columns(c[0]));
            assertEquals(c[0] + " players: rows", c[2], TabListLayout.rows(c[0], c[1]));
        }
    }

    @Test
    public void listWidthIsTheGridOrTheWidestHeaderLine() {
        assertEquals(3 * 60 + 2 * 5, TabListLayout.listWidth(3, 60, 100));
        assertEquals(250, TabListLayout.listWidth(3, 60, 250));
        assertEquals(60, TabListLayout.listWidth(1, 60, 0));
    }

    @Test
    public void vanillaRectMatchesRenderPlayerlist() {
        assertArrayEquals(new float[]{139, 9, 202, 73}, TabListLayout.vanillaRect(480, 200, 0, 8, 0), EPS);
        // two header lines and one footer line: header rows, a 1 px gap, the list, a 1 px gap, the footer
        assertArrayEquals(new float[]{139, 9, 202, 102}, TabListLayout.vanillaRect(480, 200, 2, 8, 1), EPS);
        // odd list width: integer width / 2 - l1 / 2 - 1 to width / 2 + l1 / 2 + 1
        assertArrayEquals(new float[]{139, 9, 202, 73}, TabListLayout.vanillaRect(480, 201, 0, 8, 0), EPS);
        // top border 1 + header 9 + gap 1 + 20 rows + gap 1 + footer 18
        assertArrayEquals(new float[]{213 - 50 - 1, 9, 102, 1 + 9 + 1 + 9 * 20 + 1 + 18},
                TabListLayout.vanillaRect(427, 100, 1, 20, 2), EPS);
    }

    @Test
    public void effectiveScaleKeepsTodaysFitClamp() {
        assertEquals("no clamp below the fit", 0.7f, TabListLayout.effectiveScale(0.7f, 300, 480), EPS);
        assertEquals("clamps above the fit", (480 - 4f) / 600f, TabListLayout.effectiveScale(1.5f, 600, 480), EPS);
        assertEquals("nothing measured", 1.25f, TabListLayout.effectiveScale(1.25f, 0, 480), EPS);
    }

    @Test
    public void statsKeepTheirOnScreenSize() {
        assertEquals(1.0f, TabListLayout.statsScale(0.7f), EPS);
        assertEquals(0.75f, TabListLayout.statsScale(1.0f), EPS);
        assertEquals(0.5f, TabListLayout.statsScale(1.5f), EPS);
    }

    @Test
    public void autoBoxDrawsWhereTheOldSizeSettingDrew() {
        int[][] screens = {{480, 270}, {427, 241}, {960, 507}};
        float[] naturals = {150, 300, 900};
        for (int[] scr : screens) {
            for (float natural : naturals) {
                float[] rect = TabListLayout.vanillaRect(scr[0], (int) natural, 1, 12, 1);
                for (int k = 0; k <= 2; k++) {
                    // bedwarsqol$pushTabScale before the items: Size scale, fit clamp, scale about the top centre
                    float s = OverlayItems.legacySizeScale(k);
                    float fit = (scr[0] - 4f) / natural;
                    if (fit < s) s = fit;
                    float ax = scr[0] / 2f;
                    float[] old = {ax + s * (rect[0] - ax), s * rect[1], rect[2] * s, rect[3] * s};
                    float es = TabListLayout.effectiveScale(OverlayItems.legacySizeScale(k), natural, scr[0]);
                    assertArrayEquals(scr[0] + " natural " + natural + " size " + k, old,
                            TabListLayout.autoBox(rect, scr[0], es), EPS);
                }
            }
        }
    }

    @Test
    public void backgroundOnUsesTheLunarFills() {
        assertEquals(0x80000000, TabListLayout.listFill(true));
        assertEquals(0x20000000, TabListLayout.rowFill(true));
        assertEquals(0, TabListLayout.listFill(false));
        assertEquals(0, TabListLayout.rowFill(false));
    }
}
