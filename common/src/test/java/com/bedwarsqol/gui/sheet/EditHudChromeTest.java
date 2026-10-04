package com.bedwarsqol.gui.sheet;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EditHudChromeTest {

    private final SheetFixtures.Metrics tm = new SheetFixtures.Metrics();
    private final SheetColors.Shades orange = SheetColors.Shades.of(0xF0770F);

    @Test
    public void resetAllIsTheHeroCentredUnderTheCrosshair() {
        float[] r = EditHudChrome.resetAll(tm, 480f, 270f, 2f);
        assertEquals(tm.width("Reset All", SheetFont.HERO) + 24f, r[2], 0f);
        assertEquals(20f, r[3], 0f);
        assertEquals(135f + 20f, r[1], 0f);
        assertEquals(240f, r[0] + r[2] / 2f, 0.5f);
        // on whole device px
        assertEquals(Math.round(r[0] * 2f), r[0] * 2f, 0f);
    }

    @Test
    public void hitCoversTheButtonOnly() {
        float[] r = EditHudChrome.resetAll(tm, 480f, 270f, 2f);
        assertTrue(EditHudChrome.hit(r, r[0], r[1]));
        assertTrue(EditHudChrome.hit(r, r[0] + r[2] - 0.5f, r[1] + 19.5f));
        assertFalse(EditHudChrome.hit(r, r[0] + r[2], r[1] + 10f));
        assertFalse(EditHudChrome.hit(r, r[0] + 1f, r[1] + 20f));
    }

    @Test
    public void raisedAndPressedFacesFillEachPixelOnce() {
        SheetFixtures.Recorder c = new SheetFixtures.Recorder();
        SheetPainter.hero(c, 10f, 20f, 60f, 20f, "Reset All", SheetFont.HERO, orange, false, false);
        assertTiles(c.fills, 10f, 20f, 60f, 20f);
        c = new SheetFixtures.Recorder();
        SheetPainter.hero(c, 10f, 20f, 60f, 20f, "Reset All", SheetFont.HERO, orange, true, true);
        // a press sinks the face onto its 2 su ledge
        assertTiles(c.fills, 10f, 22f, 60f, 18f);
    }

    /** The fills cover the rect exactly, with no two overlapping, so the face fades cleanly under an alpha. */
    private static void assertTiles(List<float[]> fills, float x, float y, float w, float h) {
        float area = 0f;
        for (int i = 0; i < fills.size(); i++) {
            float[] a = fills.get(i);
            assertTrue(a[0] >= x && a[1] >= y && a[0] + a[2] <= x + w && a[1] + a[3] <= y + h);
            area += a[2] * a[3];
            for (int j = i + 1; j < fills.size(); j++) {
                float[] b = fills.get(j);
                boolean overlap = a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
                assertFalse("fills " + i + " and " + j + " overlap", overlap);
            }
        }
        assertEquals(w * h, area, 0f);
    }
}
