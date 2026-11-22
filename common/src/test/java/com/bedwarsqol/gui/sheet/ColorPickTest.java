package com.bedwarsqol.gui.sheet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ColorPickTest {

    @Test
    public void hsvAndRgbRoundTripEveryNamedCorner() {
        int[] colours = {0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFFFFFF, 0x000000, 0x808080,
                0xC0DCFF, 0xF0770F, 0x123456};
        for (int rgb : colours) {
            float[] hsv = ColorPick.hsv(rgb);
            assertEquals(Integer.toHexString(rgb), rgb, ColorPick.rgb(hsv[0], hsv[1], hsv[2]));
        }
        assertEquals("hue wraps", 0xFF0000, ColorPick.rgb(1f, 1f, 1f));
    }

    @Test
    public void hexTextReadsWithOrWithoutTheHashInThreeOrSixDigits() {
        assertEquals("#C0DCFF", ColorPick.hex(0xC0DCFF));
        assertEquals("#00000A", ColorPick.hex(0x00000A));
        assertEquals(0xC0DCFF, ColorPick.parse("#c0dcff"));
        assertEquals(0xC0DCFF, ColorPick.parse(" C0DCFF "));
        assertEquals(0xFF8800, ColorPick.parse("#f80"));
        assertEquals(-1, ColorPick.parse("#c0dcf"));
        assertEquals(-1, ColorPick.parse("#c0dcfg"));
        assertEquals(-1, ColorPick.parse(""));
        assertEquals(-1, ColorPick.parse("#80c0dcff"));
    }

    @Test
    public void theSquareRunsSaturationAcrossAndBrightnessUp() {
        int[] px = new int[5 * 3];
        ColorPick.field(ColorPick.FIELD_SV, 0f, px, 5, 3);
        assertEquals("top left is white", 0xFFFFFF, px[0]);
        assertEquals("top right is the hue", 0xFF0000, px[4]);
        assertEquals("the bottom row is black", 0x000000, px[10]);
        assertEquals(0x000000, px[14]);
        assertEquals("half way down the right edge", 0x800000, px[9]);
    }

    @Test
    public void theHueBarRunsTheSpectrumInEveryRow() {
        int[] px = new int[7 * 2];
        ColorPick.field(ColorPick.FIELD_HUE, 0.5f, px, 7, 2);
        assertArrayEquals(new int[]{0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFF0000},
                java.util.Arrays.copyOf(px, 7));
        assertEquals(px[3], px[10]);
    }
}
