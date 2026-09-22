package com.bedwarsqol.stats;

/**
 * The single rule for reading a mode label off Hypixel's pregame sidebar ({@code Mode: <X>}) or out
 * of the lobby export. A label counts only when the value <i>is</i> that label — a trailing
 * decoration the scoreboard paints on (a space, a seasonal glyph) is ignored, but another word is
 * not — so dream variants ("Rush Doubles", "Ultimate 4v4v4v4") and formats with no stats block of
 * their own ("4v4", "Castle") stay {@link BedwarsMode#UNKNOWN} and fall back to overall stats.
 *
 * <p>Platform-neutral: both the Forge and Lunar {@code BedwarsModeDetector} copies and
 * {@code LobbyExport} call in here, so the rule exists once.
 */
public final class SidebarModeLabel {

    private SidebarModeLabel() {}

    /** Exact label, case-insensitive, optionally followed only by whitespace/symbol decoration. */
    public static boolean isLabelWithDecoration(String value, String label) {
        if (value == null || label == null) return false;
        if (value.length() < label.length()
                || !value.regionMatches(true, 0, label, 0, label.length())) return false;
        for (int i = label.length(); i < value.length();) {
            int codePoint = value.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) return false;
            i += Character.charCount(codePoint);
        }
        return true;
    }

    /**
     * The mode a sidebar value names: Solo/Solos, Doubles, 3v3v3v3, 4v4v4v4. Anything else —
     * including null, blank, 4v4, Castle and every dream variant — is {@link BedwarsMode#UNKNOWN}.
     */
    public static BedwarsMode toMode(String value) {
        if (value == null) return BedwarsMode.UNKNOWN;
        String v = value.trim();
        if (v.isEmpty()) return BedwarsMode.UNKNOWN;
        if (isLabelWithDecoration(v, "solo") || isLabelWithDecoration(v, "solos")) return BedwarsMode.SOLO;
        if (isLabelWithDecoration(v, "doubles")) return BedwarsMode.DOUBLES;
        if (isLabelWithDecoration(v, "3v3v3v3")) return BedwarsMode.THREES;
        if (isLabelWithDecoration(v, "4v4v4v4")) return BedwarsMode.FOURS;
        return BedwarsMode.UNKNOWN;
    }
}
