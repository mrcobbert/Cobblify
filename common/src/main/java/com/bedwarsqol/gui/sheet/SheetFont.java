package com.bedwarsqol.gui.sheet;

/**
 * A sheet type role: Inter at a size in sheet units and a weight (400, 500 or 600). SHEET-SPEC §3. In Minecraft's font
 * every role is the font's one size, as in the game itself; descriptions recede as vanilla's do, grey and without a
 * shadow. The name in the header is pixel art ({@link SheetWordmark}), not a role, and the version and search beside
 * it stay in the modern font, so the header looks the same in both.
 */
public final class SheetFont {

    public final float size;
    public final int weight;
    /** Minecraft's font: whether the text has the font's drop shadow. */
    public final boolean shadow;
    /** Drawn in the modern font even with Minecraft's: the header's version and search, the same in both fonts. */
    public final boolean modern;

    public SheetFont(float size, int weight) {
        this(size, weight, true);
    }

    public SheetFont(float size, int weight, boolean shadow) {
        this(size, weight, shadow, false);
    }

    public SheetFont(float size, int weight, boolean shadow, boolean modern) {
        this.size = size;
        this.weight = weight;
        this.shadow = shadow;
        this.modern = modern;
    }

    public static final SheetFont VERSION = new SheetFont(6.5f, 500, true, true);
    public static final SheetFont NAME = new SheetFont(10f, 600);
    public static final SheetFont DESC = new SheetFont(8f, 400, false);
    public static final SheetFont CATEGORY = new SheetFont(8.5f, 500);
    public static final SheetFont SEARCH = new SheetFont(8.5f, 400, true, true);
    public static final SheetFont WELL_LABEL = new SheetFont(8.5f, 500);
    public static final SheetFont ROW_LABEL = new SheetFont(9f, 500);
    public static final SheetFont ROW_DESC = new SheetFont(8f, 400, false);
    public static final SheetFont HINT = new SheetFont(7.5f, 400, false);
    public static final SheetFont BUTTON = new SheetFont(8.5f, 500);
    public static final SheetFont BUTTON_ACCENT = new SheetFont(8.5f, 600);
    public static final SheetFont HERO = new SheetFont(9f, 600);
    public static final SheetFont FIELD = new SheetFont(8.5f, 500);
    public static final SheetFont TOAST = new SheetFont(9f, 500);
    public static final SheetFont PATH = new SheetFont(7.5f, 400);
    public static final SheetFont MORE = new SheetFont(8f, 400);
    public static final SheetFont RESULTS = new SheetFont(8f, 400);

    /** The same size in another weight (a pressed accent face drops SemiBold to Medium). */
    public SheetFont withWeight(int w) {
        return w == weight ? this : new SheetFont(size, w, shadow, modern);
    }
}
