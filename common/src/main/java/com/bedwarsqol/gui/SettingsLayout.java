package com.bedwarsqol.gui;

/**
 * Pure geometry helper for the two-column masonry grid of module cards. Given the intrinsic pixel
 * height of each card (already measured by the caller, including any expanded subsettings), it hands
 * back where each card lands: which column, its x/y in content-local coordinates, and the column
 * width. It knows nothing about Minecraft, scrolling, or where the panel sits on screen — the caller
 * offsets every placement by {@code contentX} horizontally and {@code (viewportTop - scroll)}
 * vertically. Keeping it Minecraft-free makes the layout math unit-testable in isolation.
 *
 * <p><b>Why fixed-parity columns instead of shortest-column packing.</b> Card {@code i} is assigned
 * to {@code column = i % 2} — a stable function of its position in the ordered list, never of the
 * current heights. A true masonry packer that always drops the next card into the currently-shorter
 * column would give a tighter fit, but it couples every card's column to every earlier card's height:
 * expanding one card's subsettings could then ripple sideways and reshuffle which column later cards
 * live in, making cards jump columns as the user toggles expanders. With fixed parity, expanding card
 * {@code i} changes only that card's height, which only slides the same-parity cards <em>below</em> it
 * in its own column further down. The ordered list and each card's parity are untouched, so no card
 * ever changes column and the opposite column is byte-for-byte unaffected. Predictable motion beats a
 * marginally tighter pack here. See {@code SettingsLayoutTest} for the invariance guarantee.
 */
final class SettingsLayout {

    private SettingsLayout() {
    }

    /**
     * Where a single card lands. {@code column} is 0 (left) or 1 (right); {@code x}/{@code y} are
     * 0-based relative to the content origin; {@code width} is the shared column width.
     */
    static final class Placement {
        final int column;
        final int x;
        final int y;
        final int width;

        Placement(int column, int x, int y, int width) {
            this.column = column;
            this.x = x;
            this.y = y;
            this.width = width;
        }
    }

    /**
     * The full layout: one {@link Placement} per input card in input order, plus the total content
     * height (the taller of the two columns, trailing card gap stripped) the caller uses to size the
     * scrollable region.
     */
    static final class Result {
        final Placement[] placements;
        final int totalHeight;

        Result(Placement[] placements, int totalHeight) {
            this.placements = placements;
            this.totalHeight = totalHeight;
        }
    }

    /**
     * Lay out {@code heights.length} cards into two fixed-parity columns.
     *
     * @param heights        intrinsic pixel height of each card, in display order
     * @param availableWidth total pixel width available for both columns and the gap between them
     * @param columnGap      horizontal gap between the two columns
     * @param cardGap        vertical gap between stacked cards in the same column
     * @return placements (one per card, in input order) and the total content height
     */
    static Result masonry(int[] heights, int availableWidth, int columnGap, int cardGap) {
        // Split the available width into two equal columns with the gap carved out of the middle.
        int columnWidth = (availableWidth - columnGap) / 2;
        int[] columnX = {0, columnWidth + columnGap};

        // Independent running y cursor per column; each starts at the content top.
        int[] cursor = {0, 0};
        int[] count = {0, 0};

        Placement[] placements = new Placement[heights.length];
        for (int i = 0; i < heights.length; i++) {
            int col = i % 2;
            placements[i] = new Placement(col, columnX[col], cursor[col], columnWidth);
            cursor[col] += heights[i] + cardGap;
            count[col]++;
        }

        // Strip the trailing card gap each column left dangling past its last card; an empty column
        // contributes no height at all.
        int used0 = count[0] == 0 ? 0 : cursor[0] - cardGap;
        int used1 = count[1] == 0 ? 0 : cursor[1] - cardGap;
        int totalHeight = Math.max(used0, used1);

        return new Result(placements, totalHeight);
    }
}
