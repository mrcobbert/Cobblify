package com.bedwarsqol.hud;

/**
 * The tab list as a HUD item: vanilla's geometry and the Cobblify look. Mirrors vanilla 1.8.9
 * {@code GuiPlayerTabOverlay.renderPlayerlist} (mapped bytecode): the column split at 201 (at most 20 rows
 * a column), {@code k1 = 10} at 343, the list width {@code l1 = columns * cell + (columns - 1) * 5} at 347
 * widened by the header and footer lines (wrapped at {@code width - 50}, 394 and 479), the header fill
 * {@code (width/2 - l1/2 - 1, k1 - 1, width/2 + l1/2 + 1, k1 + lines * 9)} at 587, the list fill at 716
 * after a 1 px gap, each row's {@code 0x20FFFFFF} cell at 784 and the footer fill at 1306 after another
 * 1 px gap. The three big fills are {@code Integer.MIN_VALUE} ({@code 0x80000000}).
 */
public final class TabListLayout {

    /** Background On: the list, header and footer fill. */
    public static final int LIST_FILL = 0x80000000;
    /** Background On: each player's row. */
    public static final int ROW_FILL = 0x20000000;

    private static final int ROW_HEIGHT = 9;
    private static final int MAX_ROWS = 20;
    private static final int COLUMN_GAP = 5;
    private static final int TOP = 10;
    private static final float STATS_SCALE = 0.75f;

    private TabListLayout() {
    }

    /** Columns vanilla splits {@code players} into (at most 20 rows a column). */
    public static int columns(int players) {
        int columns = 1, rows = players;
        while (rows > MAX_ROWS) {
            columns++;
            rows = (players + columns - 1) / columns;
        }
        return columns;
    }

    /** Rows of a list of {@code players} in {@code columns} columns. */
    public static int rows(int players, int columns) {
        return columns <= 1 ? players : (players + columns - 1) / columns;
    }

    /** The list's width: the grid, or the widest header or footer line when that is wider. */
    public static int listWidth(int columns, int cellWidth, int maxHeaderFooterWidth) {
        return Math.max(columns * cellWidth + (columns - 1) * COLUMN_GAP, maxHeaderFooterWidth);
    }

    /**
     * Where vanilla draws the list at scale 1, {@code {x, y, w, h}}: the header fill (when there is a
     * header) through the footer fill (when there is a footer). {@code width} is the scaled screen width.
     */
    public static float[] vanillaRect(int width, int listWidth, int headerLines, int rows, int footerLines) {
        int left = width / 2 - listWidth / 2 - 1;
        int right = width / 2 + listWidth / 2 + 1;
        int top = TOP - 1;
        int k = TOP;
        if (headerLines > 0) k += headerLines * ROW_HEIGHT + 1;
        int bottom = k + rows * ROW_HEIGHT;
        if (footerLines > 0) bottom += 1 + footerLines * ROW_HEIGHT;
        return new float[]{left, top, right - left, bottom - top};
    }

    /**
     * The scale the list draws at: the item's scale, shrunk to fit a grid wider than the screen (the
     * column clamp Cobblify removes would otherwise let it run off screen). Never larger than the item's.
     */
    public static float effectiveScale(float itemScale, float naturalGridWidth, float screenWidth) {
        if (naturalGridWidth <= 0f) return itemScale;
        return Math.min(itemScale, (screenWidth - 4f) / naturalGridWidth);
    }

    /** Scale of the tab stats inside the scaled list: about the same size on screen at any item scale, never above 1. */
    public static float statsScale(float itemScale) {
        return Math.min(STATS_SCALE / itemScale, 1.0f);
    }

    /** The never-moved box: the vanilla rect scaled about the top centre (sw / 2, 0), as the old Size row did. */
    public static float[] autoBox(float[] rect, float screenWidth, float scale) {
        return OverlayBox.scaledAbout(rect, screenWidth / 2f, 0f, scale);
    }

    /** Fill behind the list, header and footer: Lunar's, or none. */
    public static int listFill(boolean background) {
        return background ? LIST_FILL : 0;
    }

    /** Fill behind each player's row: Lunar's, or none. */
    public static int rowFill(boolean background) {
        return background ? ROW_FILL : 0;
    }
}
