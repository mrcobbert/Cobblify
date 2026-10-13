package com.bedwarsqol.gui.render;

import com.bedwarsqol.gui.sheet.SheetColors;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AccentPaletteTest {

    @Test
    public void twelveHuesInThreeTonesThenThreeNeutrals() {
        GuiTheme.Accent[] all = GuiTheme.Accent.values();
        assertEquals(12 * 3 + 3, all.length);
        Set<String> tokens = new HashSet<String>(), labels = new HashSet<String>();
        for (GuiTheme.Accent a : all) {
            assertTrue("token " + a.token(), tokens.add(a.token()));
            assertTrue("label " + a.label(), labels.add(a.label()));
            assertEquals(a, GuiTheme.fromToken(a.token()));
        }
        assertEquals("pale-red", all[0].token());
        assertEquals("red", all[12].token());
        assertEquals("deep-red", all[24].token());
        assertEquals("white", all[36].token());
    }

    @Test
    public void theOriginalTenKeepTheirTokensAndColours() {
        String[] tokens = {"red", "orange", "yellow", "lime", "green", "aqua", "blue", "purple", "pink", "white"};
        int[] colours = {0xE5484D, 0xF0770F, 0xF5C63A, 0x9BE34A, 0x3FB950, 0x3FD0E0, 0x4A90E2, 0xA78BFA, 0xF27EC4, 0xE6E6E6};
        for (int i = 0; i < tokens.length; i++) {
            GuiTheme.Accent a = GuiTheme.fromToken(tokens[i]);
            assertEquals(tokens[i], a.token());
            assertEquals(tokens[i], colours[i], a.base() & 0xFFFFFF);
        }
        assertEquals("pale-blue", GuiTheme.normalizeToken("not-a-colour"));
    }

    @Test
    public void everyAccentKeepsItsTextReadableAndShowsOnTheSheet() {
        for (GuiTheme.Accent a : GuiTheme.Accent.values()) {
            int base = a.base() & 0xFFFFFF;
            int on = SheetColors.Shades.of(base).on & 0xFFFFFF;
            double text = GuiTheme.contrast(base, on);
            assertTrue(a.label() + " text " + text, text >= 4.5);
            double sheet = GuiTheme.contrast(base, SheetColors.GROUND & 0xFFFFFF);
            assertTrue(a.label() + " on the sheet " + sheet, sheet >= 3.0);
        }
        assertEquals(SheetColors.TEXT_ON, SheetColors.Shades.of(GuiTheme.fromToken("pale-blue").base()).on);
        assertEquals(SheetColors.TEXT_HI, SheetColors.Shades.of(GuiTheme.fromToken("deep-blue").base()).on);
    }
}
