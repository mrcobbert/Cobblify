package com.bedwarsqol.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the open dropdown's one hit rule (F10, review I2). The click used to subtract a 3px pad while
 * the draw added a 2px one, so the boundary pixel highlighted row {@code i+1} and applied row
 * {@code i}; the draw's per-row {@code GuiRender.inside} test is inclusive at both ends, so that same
 * pixel painted two rows. One rule answers hover and click, and it names exactly one row per pixel.
 */
public class DropdownHitTest {

    private static final float LIST_TOP = 100f, PAD_Y = 2f, ITEM_H = 12f;
    private static final int N = 3;

    private static int at(float mouseY) {
        return DropdownHit.indexAt(mouseY, LIST_TOP, PAD_Y, ITEM_H, N);
    }

    @Test
    public void theBoundaryPixelBelongsToTheRowItDraws() {
        assertEquals(114f, DropdownHit.rowTop(LIST_TOP, PAD_Y, ITEM_H, 1), 0f);
        assertEquals(0, at(113f));
        assertEquals(1, at(114f));
        assertEquals(1, at(125f));
        assertEquals(2, at(126f));
    }

    @Test
    public void outsideTheRowsClampsToTheNearestRow() {
        assertEquals(2, at(1000f));
        assertEquals(0, at(0f));
    }

    @Test
    public void noRowsMeansNoHit() {
        assertEquals(-1, DropdownHit.indexAt(114f, LIST_TOP, PAD_Y, ITEM_H, 0));
        assertEquals(-1, DropdownHit.indexAt(114f, LIST_TOP, PAD_Y, ITEM_H, -1));
    }

    @Test
    public void aZeroRowHeightCannotDivide() {
        assertEquals(0, DropdownHit.indexAt(113f, LIST_TOP, PAD_Y, 0f, N));
    }

    @Test
    public void everyPixelOfTheListPicksTheRowItIsDrawnIn() {
        int totalH = (int) (2 * PAD_Y + N * ITEM_H);
        for (int y = (int) LIST_TOP; y < LIST_TOP + totalH; y++) {
            int expected = 0; // above the first row's top, the first row answers (the click clamps)
            for (int i = 0; i < N; i++) {
                if (DropdownHit.rowTop(LIST_TOP, PAD_Y, ITEM_H, i) <= y) expected = i;
            }
            assertEquals("y=" + y, expected, at(y));
        }
    }

    @Test
    public void exactlyOneRowIsNamedPerPixel() {
        int totalH = (int) (2 * PAD_Y + N * ITEM_H);
        for (int y = (int) LIST_TOP; y < LIST_TOP + totalH; y++) {
            int named = 0;
            int hit = at(y);
            for (int i = 0; i < N; i++) {
                if (i == hit) named++;
            }
            assertEquals("y=" + y, 1, named);
        }
    }
}
