package com.bedwarsqol.hud;

/**
 * Keystrokes' LMB and RMB labels as pixel art, for Minecraft's font. The font only comes in whole-pixel sizes, and at
 * GUI scale 2 its capitals are 7 px or 14 px tall, either side of the modern font's 8. These are Minecraft's own L, M,
 * R and B, drawn 8 px tall in its bold (2 px uprights, 1 px bars, a 1 px gap between letters), so the labels keep
 * the game's look at the modern font's size.
 */
public final class MouseKeyLabel {

    /** Capital height in pixels. */
    public static final int HEIGHT = 8;

    private static final String[] L = {
        "##....",
        "##....",
        "##....",
        "##....",
        "##....",
        "##....",
        "##....",
        "######",
    };
    private static final String[] M = {
        "##...##",
        "###.###",
        "##.#.##",
        "##...##",
        "##...##",
        "##...##",
        "##...##",
        "##...##",
    };
    private static final String[] R = {
        "#####.",
        "##..##",
        "##..##",
        "#####.",
        "##..##",
        "##..##",
        "##..##",
        "##..##",
    };
    private static final String[] B = {
        "#####.",
        "##..##",
        "##..##",
        "#####.",
        "##..##",
        "##..##",
        "##..##",
        "#####.",
    };

    /** Receives one run of set pixels: {@code w} pixels from (x, y), in pixels from the label's top left. */
    public interface Run {
        void fill(int x, int y, int w);
    }

    private MouseKeyLabel() {
    }

    /** Whether {@code label} has pixel art: "LMB" or "RMB". */
    public static boolean has(String label) {
        return "LMB".equals(label) || "RMB".equals(label);
    }

    /** Width in pixels. */
    public static int width(String label) {
        String[][] g = glyphs(label);
        int w = g.length - 1;
        for (String[] rows : g) w += rows[0].length();
        return w;
    }

    /**
     * Pixels per art pixel for a label meant to be {@code capHeight} device px tall: the nearest whole number,
     * never below one.
     */
    public static int pixel(float capHeight) {
        return Math.max(1, Math.round(capHeight / HEIGHT));
    }

    /** Every run of set pixels, row by row. */
    public static void runs(String label, Run out) {
        int x0 = 0;
        for (String[] rows : glyphs(label)) {
            for (int y = 0; y < HEIGHT; y++) {
                String row = rows[y];
                int x = 0;
                while (x < row.length()) {
                    if (row.charAt(x) != '#') {
                        x++;
                        continue;
                    }
                    int end = x;
                    while (end < row.length() && row.charAt(end) == '#') end++;
                    out.fill(x0 + x, y, end - x);
                    x = end;
                }
            }
            x0 += rows[0].length() + 1;
        }
    }

    private static String[][] glyphs(String label) {
        if ("LMB".equals(label)) return new String[][]{L, M, B};
        if ("RMB".equals(label)) return new String[][]{R, M, B};
        throw new IllegalArgumentException(label);
    }
}
