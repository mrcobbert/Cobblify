package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RecordingMeta;

import java.util.Locale;

/**
 * Labels for a recording in the gallery and on the loading card (PLAN section 6 "Labels"). Format v1 never writes a
 * mode label (format gap 4), so they come from the mode id table: Solo, Doubles, Threes, Fours and 4v4 for the five
 * Bed Wars queues, "Bed Wars" for its other modes, the server type in title case for other games ("Duels"),
 * "Singleplayer" for a single-player world. Title = mode, plus " · map" when a map is known.
 */
public final class ModeLabels {

    /** Hypixel's replay viewer: never a game in the gallery or behind Watch Last Game (coordinator amendment 2). */
    public static final String SERVER_TYPE_REPLAY = "REPLAY";

    private static final String[][] BEDWARS = {
            {"BEDWARS_EIGHT_ONE", "Solo"},
            {"BEDWARS_EIGHT_TWO", "Doubles"},
            {"BEDWARS_FOUR_THREE", "Threes"},
            {"BEDWARS_FOUR_FOUR", "Fours"},
            {"BEDWARS_TWO_FOUR", "4v4"},
    };

    private ModeLabels() {
    }

    /** The mode label; never null. */
    public static String mode(RecordingMeta m) {
        if (m == null) return "Game";
        String mode = m.mode == null ? "" : m.mode.trim().toUpperCase(Locale.ROOT);
        if (mode.equals(RecordingMeta.MODE_SINGLEPLAYER) || RecordingMeta.SERVER_SINGLEPLAYER.equals(m.server)) {
            return "Singleplayer";
        }
        String type = m.serverType == null ? "" : m.serverType.trim();
        if (type.equalsIgnoreCase("BEDWARS") || mode.startsWith("BEDWARS_")) {
            for (String[] row : BEDWARS) {
                if (row[0].equals(mode)) return row[1];
            }
            return "Bed Wars";
        }
        if (!type.isEmpty()) return titleCase(type);
        return RecordingMeta.SERVER_HYPIXEL.equals(m.server) ? "Hypixel" : "Game";
    }

    /** Mode, then " · map" when the map is known. */
    public static String title(RecordingMeta m) {
        String mode = mode(m);
        String map = m == null || m.map == null ? "" : m.map.trim();
        return map.isEmpty() ? mode : mode + " · " + map;
    }

    /** "WIN" or "LOSS", else null (blank: unknown, and every non-Bed Wars game, Q6). */
    public static String result(RecordingMeta m) {
        if (m == null || m.result == null) return null;
        if (m.result.equals(RecordingMeta.RESULT_WIN)) return RecordingMeta.RESULT_WIN;
        if (m.result.equals(RecordingMeta.RESULT_LOSS)) return RecordingMeta.RESULT_LOSS;
        return null;
    }

    /** Minecraft's green (§a) for a win, its red (§c) for a loss. */
    public static int resultColour(String result) {
        return RecordingMeta.RESULT_WIN.equals(result) ? 0xFF55FF55 : 0xFFFF5555;
    }

    /** Whether the recording is a visit to Hypixel's replay viewer (serverType REPLAY). */
    public static boolean isReplay(RecordingMeta m) {
        return m != null && m.serverType != null && m.serverType.trim().equalsIgnoreCase(SERVER_TYPE_REPLAY);
    }

    /** "BUILD_BATTLE" -> "Build Battle". */
    static String titleCase(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean start = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' || c == ' ') {
                if (b.length() > 0 && b.charAt(b.length() - 1) != ' ') b.append(' ');
                start = true;
            } else {
                b.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
                start = false;
            }
        }
        return b.toString().trim();
    }
}
