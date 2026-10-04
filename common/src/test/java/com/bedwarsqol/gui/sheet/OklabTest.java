package com.bedwarsqol.gui.sheet;

import static org.junit.Assert.assertEquals;

import com.bedwarsqol.gui.render.GuiTheme;
import org.junit.Test;

/** The original ten accents shade as the approved prototype's {@code shades()} did (values from its app.js, by node). */
public class OklabTest {

    private static final String[][] EXPECTED = {
            // name, hl, ln, dp, sh, sh2
            {"Red", "ff7371", "f35659", "680000", "940d1f", "b82a33"},
            {"Orange", "ff9c53", "fe8428", "771f00", "a14100", "c45900"},
            {"Yellow", "ffe875", "ffd34b", "876500", "ae8800", "cea41d"},
            {"Lime", "c0ff7f", "a8f059", "467900", "669f1a", "7dbd30"},
            {"Green", "70d779", "4ec65d", "005300", "007720", "209436"},
            {"Aqua", "79effe", "50dded", "006975", "008e9b", "1fabb9"},
            {"Blue", "71b0fc", "569df0", "00336c", "1a5495", "2f6fb7"},
            {"Purple", "c5aeff", "b398ff", "4a3281", "6b53ab", "856cce"},
            {"Pink", "ffa3e2", "ff8bd1", "7c295f", "a64a83", "c7619f"},
            {"White", "fafafa", "f3f3f3", "828282", "a6a6a6", "c2c2c2"},
    };

    private static String hex(int argb) {
        return String.format("%06x", argb & 0xFFFFFF);
    }

    @Test
    public void originalAccentsShadeLikeThePrototype() {
        for (String[] e : EXPECTED) {
            GuiTheme.Accent a = GuiTheme.fromToken(e[0].toLowerCase());
            assertEquals(e[0], a.label());
            SheetColors.Shades s = SheetColors.Shades.of(a.base());
            assertEquals(e[0] + " hl", e[1], hex(s.hl));
            assertEquals(e[0] + " ln", e[2], hex(s.ln));
            assertEquals(e[0] + " dp", e[3], hex(s.dp));
            assertEquals(e[0] + " sh", e[4], hex(s.sh));
            assertEquals(e[0] + " sh2", e[5], hex(s.sh2));
        }
    }

    @Test
    public void overlaySwatchShadowsComeFromTheirOwnColour() {
        assertEquals("ab1e27", hex(SheetColors.pressSh(0xFF5555)));
        assertEquals("d1373b", hex(SheetColors.pressSh2(0xFF5555)));
        assertEquals("265dad", hex(SheetColors.pressSh(0x5599FF)));
        assertEquals("3b77d2", hex(SheetColors.pressSh2(0x5599FF)));
    }

    @Test
    public void disabledIsTheGreyedOutControlOverItsBackdrop() {
        // sampled from the visuals-off reference render
        assertEquals("242424", hex(SheetColors.disabled(0x3B3B3B, 0x141414)));
        assertEquals("49423c", hex(SheetColors.disabled(0xF0770F, 0x141414)));
        assertEquals("4d564f", hex(SheetColors.disabled(0x4CD964, 0x141414)));
    }
}
