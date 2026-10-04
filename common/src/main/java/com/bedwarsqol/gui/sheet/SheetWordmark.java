package com.bedwarsqol.gui.sheet;

/**
 * The header's name as pixel art, the same in both fonts. It is built the way Mojang's Minecraft Ten is built, the
 * face Ore UI sets its title bars in: one letter height (Ten is unicase), 3 su stems, counters cut as 1 su slits and
 * the notches on B's right side. Ten's capitals and Inter's are both about 10 su tall, so the name keeps the Modern
 * font's size.
 *
 * <p>Pixel art can't be scaled by fractions: each art pixel is the unit rounded to whole device pixels, and the
 * mark sits on the device grid, so it stays sharp at every GUI Size (about 18% larger than the sheet at 1.7 px per
 * su, and smaller at 1.4).
 */
public final class SheetWordmark {

    private static final String[] ROWS = {
        "######..#######..######...######...###.....###..######..###.###",
        "######..#######..#######..#######..###.....###..######..###.###",
        "###.....###.###..###.###..###.###..###.....###..###.....###.###",
        "###.....###.###..###.###..###.###..###.....###..###.....###.###",
        "###.....###.###..######...######...###.....###..#####...#######",
        "###.....###.###..#######..#######..###.....###..#####...#######",
        "###.....###.###..###.###..###.###..###.....###..###.......###..",
        "###.....###.###..###.###..###.###..###.....###..###.......###..",
        "######..#######..#######..#######..######..###..###.......###..",
        "######..#######..#######..#######..######..###..###.......###..",
    };

    /** Size in art pixels. */
    public static final int WIDTH = ROWS[0].length(), HEIGHT = ROWS.length;

    private SheetWordmark() {
    }

    /** Device pixels per art pixel: the unit rounded, never below one. */
    public static int pixel(float unit) {
        return Math.max(1, Math.round(unit));
    }

    /** Width in su at {@code unit} device px per su, without the shadow. */
    public static float width(float unit) {
        return WIDTH * pixel(unit) / unit;
    }

    /** Height in su at {@code unit} device px per su, without the shadow. */
    public static float height(float unit) {
        return HEIGHT * pixel(unit) / unit;
    }

    /**
     * Paints the mark with its left edge at {@code x} and its capitals centred on {@code cy} (su), both snapped to
     * the device grid, in {@code argb} over Minecraft's text shadow: the same pixels one art pixel down and right, at
     * a quarter of the brightness.
     */
    public static void paint(Canvas c, float x, float cy, int argb) {
        float unit = c.unit();
        int p = pixel(unit);
        int left = Math.round(x * unit);
        int top = Math.round(cy * unit - HEIGHT * p / 2f);
        runs(c, unit, p, left + p, top + p, SheetColors.textShadow(argb));
        runs(c, unit, p, left, top, argb);
    }

    /** Each row's runs of set pixels as one fill, from device px (left, top). */
    private static void runs(Canvas c, float unit, int p, int left, int top, int argb) {
        for (int y = 0; y < HEIGHT; y++) {
            String row = ROWS[y];
            int x = 0;
            while (x < WIDTH) {
                if (row.charAt(x) != '#') {
                    x++;
                    continue;
                }
                int end = x;
                while (end < WIDTH && row.charAt(end) == '#') end++;
                c.fill((left + x * p) / unit, (top + y * p) / unit, (end - x) * p / unit, p / unit, argb);
                x = end;
            }
        }
    }
}
