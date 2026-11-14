package com.bedwarsqol.hud;

/**
 * Lunar's two-line potion rows, as the approved sandbox draws them: an 18 px icon, then the effect's name with its
 * level in white and its time left (m:ss) in gray under it, one row every 20 px. An effect with under 10 s left blinks,
 * half a second hidden, half shown. Geometry is in module units (multiply by the draw scale).
 */
public final class PotionRows {

    public static final int ROW = 20;
    public static final int ICON = 18;
    /** Gap between the icon and the text. */
    public static final int GAP = 3;
    /** Offset of the name's line top, and of the time's, from the row top. */
    public static final int NAME_DY = 1;
    public static final int TIME_DY = 10;
    /** Ticks left under which an effect blinks (10 s). */
    public static final int BLINK_TICKS = 200;
    public static final int BLINK_HALF_MS = 500;
    public static final int NAME_COLOR = 0xFFFFFFFF;
    public static final int TIME_COLOR = 0xFFAAAAAA;

    private static final String[] ROMAN = {"", "", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private PotionRows() {
    }

    /**
     * {@code name} with its level: nothing for level 1, a Roman numeral for 2 to 10, digits above. The amplifier is a
     * byte on the wire, so a negative one counts from 256.
     */
    public static String label(String name, int amplifier) {
        int level = (amplifier & 0xFF) + 1;
        if (level == 1) return name;
        return name + " " + (level < ROMAN.length ? ROMAN[level] : Integer.toString(level));
    }

    /** Time left as vanilla writes it ({@code m:ss}), or {@code **:**} for an endless effect. */
    public static String time(int ticks, boolean max) {
        if (max) return "**:**";
        int seconds = ticks / 20;
        int minutes = seconds / 60;
        seconds %= 60;
        return seconds < 10 ? minutes + ":0" + seconds : minutes + ":" + seconds;
    }

    /** Whether a row is in the hidden half of its blink at {@code nowMs}. */
    public static boolean blinkHidden(int ticks, boolean max, long nowMs) {
        return !max && ticks < BLINK_TICKS && (nowMs / BLINK_HALF_MS) % 2 == 1;
    }

    /**
     * Width of the rows: icon, gap and the longer of name and time. {@code trailing} is the empty column a font
     * leaves after its last glyph (1 in Minecraft's font), which the box leaves out.
     */
    public static float width(float nameAdvance, float timeAdvance, float trailing) {
        return ICON + GAP + Math.max(nameAdvance, timeAdvance) - trailing;
    }

    /** Height of {@code rows} rows: the last row ends at its time's baseline shadow, 2 px short of a full row. */
    public static int height(int rows) {
        return rows == 0 ? 0 : rows * ROW - 2;
    }
}
