package com.bedwarsqol.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the fixed-parity two-column masonry helper. The load-bearing test is
 * {@link #expandingACardNeverDisturbsTheOtherColumn()} — it pins the whole reason the layout uses
 * fixed parity instead of shortest-column packing: growing one card only slides the same-parity
 * cards below it, and never touches the opposite column or same-column cards above it.
 */
public class SettingsLayoutTest {

    // A width that divides cleanly: (200 - 10) / 2 = 95 per column.
    private static final int WIDTH = 200;
    private static final int COLUMN_GAP = 10;
    private static final int COLUMN_WIDTH = (WIDTH - COLUMN_GAP) / 2; // 95
    private static final int COL1_X = COLUMN_WIDTH + COLUMN_GAP;      // 105

    // ---- parity assignment ----------------------------------------------------------------------

    @Test
    public void assignsCardsToColumnsByParityWithSharedGeometry() {
        int[] heights = {30, 30, 30, 30, 30, 30};
        SettingsLayout.Result r = SettingsLayout.masonry(heights, WIDTH, COLUMN_GAP, 6);
        for (int i = 0; i < heights.length; i++) {
            SettingsLayout.Placement p = r.placements[i];
            assertEquals("card " + i + " column", i % 2, p.column);
            assertEquals("card " + i + " x", i % 2 == 0 ? 0 : COL1_X, p.x);
            assertEquals("card " + i + " width", COLUMN_WIDTH, p.width);
        }
    }

    // ---- independent y cursors ------------------------------------------------------------------

    @Test
    public void eachColumnFlowsItsOwnHeightsIndependently() {
        int gap = 5;
        int[] heights = {10, 100, 20, 30}; // col0: {10,20}, col1: {100,30}
        SettingsLayout.Result r = SettingsLayout.masonry(heights, WIDTH, COLUMN_GAP, gap);

        // col0 (cards 0,2)
        assertEquals(0, r.placements[0].y);
        assertEquals(10 + gap, r.placements[2].y);
        // col1 (cards 1,3): tall card pushes the next col1 card far down, unaffected by col0.
        assertEquals(0, r.placements[1].y);
        assertEquals(100 + gap, r.placements[3].y);
    }

    // ---- total height ---------------------------------------------------------------------------

    @Test
    public void totalHeightIsTallerColumnWithNoTrailingGap() {
        int gap = 5;
        int[] heights = {10, 100, 20, 30};
        // col0 used = 10 + 5 + 20 = 35 ; col1 used = 100 + 5 + 30 = 135 ; max = 135, no trailing gap.
        SettingsLayout.Result r = SettingsLayout.masonry(heights, WIDTH, COLUMN_GAP, gap);
        assertEquals(135, r.totalHeight);
    }

    @Test
    public void emptyInputProducesEmptyLayout() {
        SettingsLayout.Result r = SettingsLayout.masonry(new int[0], WIDTH, COLUMN_GAP, 5);
        assertEquals(0, r.placements.length);
        assertEquals(0, r.totalHeight);
    }

    // ---- odd card count -------------------------------------------------------------------------

    @Test
    public void oddCountPlacesTheLastCardInColumnZero() {
        int[] heights = {10, 20, 30, 40, 50}; // 5 cards -> last index 4 is even -> column 0
        SettingsLayout.Result r = SettingsLayout.masonry(heights, WIDTH, COLUMN_GAP, 5);
        assertEquals(0, r.placements[4].column);
        assertEquals(0, r.placements[4].x);
    }

    // ---- gap values -----------------------------------------------------------------------------

    @Test
    public void zeroGapsStackCardsFlush() {
        int[] heights = {10, 40, 20, 60};
        SettingsLayout.Result r = SettingsLayout.masonry(heights, WIDTH, 0, 0);
        // With columnGap 0 the columns split the full width evenly and col1 starts at columnWidth.
        int cw = WIDTH / 2; // 100
        assertEquals(cw, r.placements[0].width);
        assertEquals(0, r.placements[0].x);
        assertEquals(cw, r.placements[1].x);
        // No gaps: second same-column card sits flush against the first.
        assertEquals(10, r.placements[2].y); // col0: 0 then 10
        assertEquals(40, r.placements[3].y); // col1: 0 then 40
        // col0 used = 10+20 = 30 ; col1 used = 40+60 = 100 ; max = 100
        assertEquals(100, r.totalHeight);
    }

    @Test
    public void nonzeroGapsAdvanceCursorByHeightPlusGap() {
        int gap = 7;
        int[] heights = {10, 40, 20, 60};
        SettingsLayout.Result r = SettingsLayout.masonry(heights, WIDTH, COLUMN_GAP, gap);
        assertEquals(10 + gap, r.placements[2].y);
        assertEquals(40 + gap, r.placements[3].y);
    }

    // ---- the invariance guarantee (most important) ----------------------------------------------

    @Test
    public void expandingACardNeverDisturbsTheOtherColumn() {
        int gap = 6;
        int[] base = {30, 40, 50, 60, 70, 80, 90, 100};

        // Grow one even-parity card (column 0) and one odd-parity card (column 1).
        assertExpansionInvariant(base, WIDTH, COLUMN_GAP, gap, 2, 55);  // even i -> column 0
        assertExpansionInvariant(base, WIDTH, COLUMN_GAP, gap, 3, 45);  // odd i  -> column 1
    }

    /**
     * Grows {@code heights[i]} by {@code delta} and asserts the fixed-parity contract:
     * opposite-parity placements are byte-for-byte identical, same-parity placements at index
     * {@code < i} are unchanged, the expanded card keeps its position, and same-parity placements at
     * index {@code > i} shift down by exactly {@code delta}.
     */
    private static void assertExpansionInvariant(int[] base, int width, int columnGap, int cardGap,
                                                 int i, int delta) {
        SettingsLayout.Result before = SettingsLayout.masonry(base, width, columnGap, cardGap);

        int[] grown = base.clone();
        grown[i] += delta;
        SettingsLayout.Result after = SettingsLayout.masonry(grown, width, columnGap, cardGap);

        for (int j = 0; j < base.length; j++) {
            SettingsLayout.Placement b = before.placements[j];
            SettingsLayout.Placement a = after.placements[j];

            // Column, x, and width are height-independent for every card.
            assertEquals("card " + j + " column stable", b.column, a.column);
            assertEquals("card " + j + " x stable", b.x, a.x);
            assertEquals("card " + j + " width stable", b.width, a.width);

            if (j % 2 != i % 2) {
                assertEquals("opposite-parity card " + j + " y unchanged", b.y, a.y);
            } else if (j <= i) {
                // The grown card itself and everything above it in the column are anchored.
                assertEquals("same-parity card " + j + " (<= i) y unchanged", b.y, a.y);
            } else {
                assertEquals("same-parity card " + j + " (> i) y shifts by delta", b.y + delta, a.y);
            }
        }
    }
}
