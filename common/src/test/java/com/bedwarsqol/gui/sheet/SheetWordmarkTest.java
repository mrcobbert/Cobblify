package com.bedwarsqol.gui.sheet;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SheetWordmarkTest {

    @Test
    public void eachArtPixelIsTheUnitRoundedToWholeDevicePixels() {
        assertEquals(1, SheetWordmark.pixel(0.7f));
        assertEquals(1, SheetWordmark.pixel(1.4f));
        assertEquals(2, SheetWordmark.pixel(1.7f));
        assertEquals(2, SheetWordmark.pixel(2f));
        assertEquals(3, SheetWordmark.pixel(2.55f));
        assertEquals(63f, SheetWordmark.width(2f), 0f);
        assertEquals(10f, SheetWordmark.height(2f), 0f);
        assertEquals(63f * 2f / 1.7f, SheetWordmark.width(1.7f), 1e-4f);
    }

    @Test
    public void everyEdgeLandsOnTheDeviceGridAtFractionalUnits() {
        for (float unit : new float[]{1.4f, 1.7f, 2.55f, 3.4f}) {
            SheetFixtures.Recorder c = new SheetFixtures.Recorder(new SheetFixtures.MinecraftMetrics(unit));
            SheetWordmark.paint(c, 11f, 17f, SheetColors.TEXT_HI);
            int p = SheetWordmark.pixel(unit);
            for (float[] r : c.fills) {
                for (float v : new float[]{r[0] * unit, r[1] * unit, (r[0] + r[2]) * unit, (r[1] + r[3]) * unit}) {
                    assertEquals("unit " + unit, Math.round(v), v, 1e-3f);
                }
                assertEquals("unit " + unit + " rows are one art pixel", p, r[3] * unit, 1e-3f);
            }
        }
    }

    @Test
    public void capitalsCentreOnTheGivenLineOverMinecraftsShadow() {
        SheetFixtures.Recorder c = new SheetFixtures.Recorder();
        SheetWordmark.paint(c, 11f, 17f, SheetColors.TEXT_HI);
        float top = Float.MAX_VALUE, bottom = 0f;
        int faces = 0;
        boolean shadowFirst = true;
        for (int i = 0; i < c.fills.size(); i++) {
            float[] r = c.fills.get(i);
            int colour = c.colours.get(i);
            if (colour == SheetColors.TEXT_HI) {
                faces++;
                top = Math.min(top, r[1]);
                bottom = Math.max(bottom, r[1] + r[3]);
            } else {
                assertEquals(SheetColors.textShadow(SheetColors.TEXT_HI), colour);
                if (faces > 0) shadowFirst = false;
            }
        }
        assertTrue(faces > 0);
        assertTrue("the shadow goes under the face", shadowFirst);
        assertEquals(12f, top, 0f);
        assertEquals(22f, bottom, 0f);
    }
}
