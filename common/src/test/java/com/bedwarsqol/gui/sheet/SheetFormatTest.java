package com.bedwarsqol.gui.sheet;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SheetFormatTest {

    @Test
    public void valuesPrintLikeToFixed() {
        SheetSchema.Row x = SheetSchema.row("handX");
        assertEquals("-0.30", SheetFormat.number(x, -0.3));
        assertEquals("0.00", SheetFormat.number(x, -0.0));
        assertEquals("0.00", SheetFormat.number(x, -0.001));
        assertEquals("5s", SheetFormat.number(SheetSchema.row("stackWindow"), 5));
    }

    @Test
    public void slidersSnapToTheirStepAndRange() {
        SheetSchema.Row scale = SheetSchema.row("handScale");
        assertEquals(1.25, SheetFormat.snap(scale, 1.2501), 0);
        assertEquals(2.0, SheetFormat.snap(scale, 9), 0);
        assertEquals(0.5, SheetFormat.atFraction(scale, -1), 0);
        SheetSchema.Row window = SheetSchema.row("stackWindow");
        assertEquals(16.0, SheetFormat.atFraction(window, 0.52), 0);
    }
}
