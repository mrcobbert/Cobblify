package com.bedwarsqol.hud;

/**
 * The sidebar scoreboard as a HUD item: vanilla's geometry and the Cobblify look. Mirrors vanilla 1.8.9
 * {@code GuiIngame.renderScoreboard} (mapped bytecode): the filter and skip at 12-67, the width loop
 * ({@code getStringWidth} ordinal 0 at 77 for the title, ordinal 1 at 177 for each {@code "name: §cN"}),
 * {@code j1 = sh / 2 + n * 9 / 3} at 205-216, {@code l1 = sw - width - 3} at 221-231, each line's fill
 * {@code (l1 - 2, k, sw - 1, k + 9)} with {@code 0x50000000} at 372, and the title row
 * {@code (l1 - 2, k - 10, sw - 1, k - 1)} with {@code 0x60000000} at 462 plus the 1 px gap
 * {@code 0x50000000} at 480.
 */
public final class ScoreboardLayout {

    /** Background On: the see-through fill behind the lines, the gap and the title row. */
    public static final int FILL = 0x50000000;

    private static final int FONT_HEIGHT = 9;
    private static final int MAX_LINES = 15;
    private static final String SCORE_MARK = ": §c";

    private ScoreboardLayout() {
    }

    /**
     * Lines vanilla draws: every filtered score when there are at most 15, otherwise the filtered list
     * after skipping {@code sorted - 15} entries (vanilla counts the skip on the unfiltered collection).
     */
    public static int shownCount(int sorted, int filtered) {
        if (filtered <= MAX_LINES) return Math.max(0, filtered);
        return Math.max(0, filtered - (sorted - MAX_LINES));
    }

    /**
     * A width-loop line without its red score: {@code "Red: §a✔: §c3"} becomes {@code "Red: §a✔"}. Only a
     * trailing {@code ": §c"} followed by an integer is removed; anything else is returned unchanged.
     */
    public static String withoutScore(String line) {
        if (line == null) return null;
        int at = line.lastIndexOf(SCORE_MARK);
        if (at < 0) return line;
        int from = at + SCORE_MARK.length();
        int i = from;
        if (i < line.length() && line.charAt(i) == '-') i++;
        if (i >= line.length()) return line;
        for (; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') return line;
        }
        return line.substring(0, at);
    }

    /**
     * Where vanilla draws the sidebar at scale 1, {@code {x, y, w, h}}: the title row through the last
     * line's fill. {@code lineWidths} are the width-loop widths of the shown lines (score already trimmed);
     * null when there is no line (vanilla then draws nothing, not even the title).
     */
    public static float[] vanillaRect(int sw, int sh, int titleWidth, int[] lineWidths) {
        int n = lineWidths.length;
        if (n == 0) return null;
        int width = titleWidth;
        for (int w : lineWidths) width = Math.max(width, w);
        int bottom = sh / 2 + n * FONT_HEIGHT / 3;
        int left = sw - width - 3 - 2;
        int top = bottom - n * FONT_HEIGHT - FONT_HEIGHT - 1;
        return new float[]{left, top, (sw - 1) - left, bottom - top};
    }

    /** The never-moved box: the vanilla rect scaled about vanilla's right-middle (sw, sh / 2), as the old Size row did. */
    public static float[] autoBox(float[] rect, float sw, float sh, float s) {
        return OverlayBox.scaledAbout(rect, sw, sh / 2f, s);
    }

    /** Fill behind each line and the gap under the title: Lunar's see-through black, or none. */
    public static int lineFill(boolean background) {
        return background ? FILL : 0;
    }

    /** Fill behind the title row: the same as the lines (vanilla's is darker), or none. */
    public static int titleFill(boolean background) {
        return background ? FILL : 0;
    }

    /** Text drops a shadow only without the fill, so it stays readable over the world. */
    public static boolean textShadow(boolean background) {
        return !background;
    }
}
