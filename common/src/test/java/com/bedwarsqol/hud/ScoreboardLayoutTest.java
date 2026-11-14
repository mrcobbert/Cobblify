package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ScoreboardLayoutTest {

    private static final float EPS = 1e-4f;

    @Test
    public void shownCountFollowsVanillasSkip() {
        assertEquals(10, ScoreboardLayout.shownCount(10, 10));
        assertEquals(15, ScoreboardLayout.shownCount(20, 20));
        assertEquals(15, ScoreboardLayout.shownCount(17, 15));
        // vanilla skips (sorted - 15) entries of the filtered list
        assertEquals(13, ScoreboardLayout.shownCount(22, 20));
        assertEquals(0, ScoreboardLayout.shownCount(0, 0));
        assertEquals(0, ScoreboardLayout.shownCount(40, 16));
    }

    @Test
    public void withoutScoreDropsTheRedNumber() {
        assertEquals("Red: §a✔", ScoreboardLayout.withoutScore("Red: §a✔: §c3"));
        assertEquals("§fKills: §a12", ScoreboardLayout.withoutScore("§fKills: §a12: §c-1"));
        assertEquals("plain line", ScoreboardLayout.withoutScore("plain line"));
        assertEquals("a: §c", ScoreboardLayout.withoutScore("a: §c"));
        assertEquals("x: §c12a", ScoreboardLayout.withoutScore("x: §c12a"));
        assertEquals("x: §c-", ScoreboardLayout.withoutScore("x: §c-"));
        assertEquals("", ScoreboardLayout.withoutScore(""));
        assertEquals(null, ScoreboardLayout.withoutScore(null));
    }

    @Test
    public void vanillaRectMatchesRenderScoreboard() {
        assertArrayEquals(new float[]{423, 107, 56, 37},
                ScoreboardLayout.vanillaRect(480, 270, 40, new int[]{30, 52, 18}), EPS);
        // a title wider than every line sets the width
        assertArrayEquals(new float[]{480 - 70 - 5, 107, 74, 37},
                ScoreboardLayout.vanillaRect(480, 270, 70, new int[]{30, 52, 18}), EPS);
        assertNull("no lines: vanilla draws nothing", ScoreboardLayout.vanillaRect(480, 270, 40, new int[0]));
        // odd screen height: integer sh / 2 + n * 9 / 3
        float[] odd = ScoreboardLayout.vanillaRect(480, 271, 40, new int[]{30, 52, 18});
        assertEquals(135 + 9 - 27 - 10, odd[1], EPS);
        float[] five = ScoreboardLayout.vanillaRect(427, 241, 20, new int[]{10, 10, 10, 10, 10});
        assertEquals(120 + 15 - 45 - 10, five[1], EPS);
        assertEquals(55, five[3], EPS);
    }

    @Test
    public void autoBoxDrawsWhereTheOldSizeSettingDrew() {
        int[][] screens = {{480, 270}, {427, 241}, {960, 507}};
        for (int[] scr : screens) {
            float[] rect = ScoreboardLayout.vanillaRect(scr[0], scr[1], 40, new int[]{30, 52, 18, 61});
            for (int k = 0; k <= 2; k++) {
                float s = OverlayItems.legacySizeScale(k);
                float ax = scr[0], ay = scr[1] / 2f;
                // OverlayScale before the items: translate(ax, ay); scale(s); translate(-ax, -ay)
                float[] old = {ax + s * (rect[0] - ax), ay + s * (rect[1] - ay), rect[2] * s, rect[3] * s};
                assertArrayEquals("screen " + scr[0] + "x" + scr[1] + " size " + k, old,
                        ScoreboardLayout.autoBox(rect, scr[0], scr[1], s), EPS);
            }
        }
    }

    @Test
    public void backgroundOnFillsAndDropsTheShadow() {
        assertEquals(0x50000000, ScoreboardLayout.lineFill(true));
        assertEquals(0x50000000, ScoreboardLayout.titleFill(true));
        assertEquals(0, ScoreboardLayout.lineFill(false));
        assertEquals(0, ScoreboardLayout.titleFill(false));
        assertFalse(ScoreboardLayout.textShadow(true));
        assertTrue(ScoreboardLayout.textShadow(false));
    }
}
