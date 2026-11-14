package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Lunar's two-line potion rows (the approved sandbox): name with level, vanilla m:ss, blink under 10 s, geometry. */
public class PotionRowsTest {

    @Test
    public void levelOneHasNoNumeral() {
        assertEquals("Speed", PotionRows.label("Speed", 0));
    }

    @Test
    public void levelsTwoToTenAreRoman() {
        assertEquals("Speed II", PotionRows.label("Speed", 1));
        assertEquals("Strength III", PotionRows.label("Strength", 2));
        assertEquals("Haste IV", PotionRows.label("Haste", 3));
        assertEquals("Jump Boost V", PotionRows.label("Jump Boost", 4));
        assertEquals("Haste VI", PotionRows.label("Haste", 5));
        assertEquals("Haste VII", PotionRows.label("Haste", 6));
        assertEquals("Haste VIII", PotionRows.label("Haste", 7));
        assertEquals("Haste IX", PotionRows.label("Haste", 8));
        assertEquals("Haste X", PotionRows.label("Haste", 9));
    }

    @Test
    public void aboveTenIsArabic() {
        assertEquals("Haste 11", PotionRows.label("Haste", 10));
        assertEquals("Haste 128", PotionRows.label("Haste", 127));
    }

    @Test
    public void byteOverflowAmplifier() {
        // the amplifier travels as a byte: -1 is 255, level 256
        assertEquals("Haste 256", PotionRows.label("Haste", -1));
        assertEquals("Haste 129", PotionRows.label("Haste", -128));
    }

    @Test
    public void timeMatchesVanilla() {
        assertEquals("1:24", PotionRows.time(1680, false));
        assertEquals("0:38", PotionRows.time(760, false));
        assertEquals("0:00", PotionRows.time(19, false));
        assertEquals("0:09", PotionRows.time(199, false));
        assertEquals("10:00", PotionRows.time(12000, false));
        assertEquals("60:00", PotionRows.time(72000, false));
        assertEquals("**:**", PotionRows.time(1680, true));
    }

    @Test
    public void blinksOnlyUnderTenSeconds() {
        assertFalse(PotionRows.blinkHidden(199, false, 0L));
        assertTrue(PotionRows.blinkHidden(199, false, 500L));
        assertTrue(PotionRows.blinkHidden(199, false, 999L));
        assertFalse(PotionRows.blinkHidden(199, false, 1000L));
        assertFalse("10 s exactly does not blink", PotionRows.blinkHidden(200, false, 500L));
        assertFalse("an endless effect never blinks", PotionRows.blinkHidden(10, true, 500L));
    }

    @Test
    public void geometryMatchesTheSandbox() {
        // vanilla advances: "Jump Boost V" 66, "0:38" 20, each with Minecraft's 1 px trailing gap
        assertEquals(86f, PotionRows.width(66f, 20f, 1f), 0f);
        assertEquals("the longer of name and time sets it", 86f, PotionRows.width(20f, 66f, 1f), 0f);
        assertEquals(87f, PotionRows.width(66f, 20f, 0f), 0f);
        assertEquals(58, PotionRows.height(3));
        assertEquals(18, PotionRows.height(1));
        assertEquals(0, PotionRows.height(0));
        assertEquals(20, PotionRows.ROW);
        assertEquals(18, PotionRows.ICON);
        assertEquals(3, PotionRows.GAP);
        assertEquals(1, PotionRows.NAME_DY);
        assertEquals(10, PotionRows.TIME_DY);
        assertEquals(0xFFFFFFFF, PotionRows.NAME_COLOR);
        assertEquals(0xFFAAAAAA, PotionRows.TIME_COLOR);
    }
}
