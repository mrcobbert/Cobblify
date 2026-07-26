package com.bedwarsqol.gui;

import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.SeraphTag;
import com.bedwarsqol.stats.UrchinTag;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Pure formatting helpers for the Players page detail table (no Minecraft/GL dependencies, so they are
 * unit-testable). Covers the full stat ceiling — all six raw counters plus the three derived ratios —
 * per mode, with an honest dash presentation for a mode with no games.
 */
public final class PlayersFormat {

    private PlayersFormat() {}

    /** Column headers for the per-mode stat table, in the same order as {@link #modeCells}. */
    public static final String[] COLS = {"FKDR", "WLR", "KD", "FK", "FD", "W", "L", "K", "D"};

    private static final String DASH = "-";

    public static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** The nine display cells for one mode row (FKDR, WLR, KD, FK, FD, W, L, K, D). A mode with no games
     *  renders all dashes so a specific mode is never shown Overall's numbers. */
    public static String[] modeCells(BedwarsStats.ModeStats m) {
        if (m == null || !m.hasGames()) {
            String[] d = new String[COLS.length];
            Arrays.fill(d, DASH);
            return d;
        }
        return new String[]{
                fmt2(m.fkdr), fmt2(m.wlr), fmt2(m.kd),
                String.valueOf(m.finalKills), String.valueOf(m.finalDeaths),
                String.valueOf(m.wins), String.valueOf(m.losses),
                String.valueOf(m.kills), String.valueOf(m.deaths)
        };
    }

    /** Strip Minecraft § color/format codes (used where a plain, uncolored string is wanted; the custom
     *  font itself parses § codes, so most Players-page text passes them through instead). */
    public static String stripSection(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { i++; continue; }
            b.append(c);
        }
        return b.toString();
    }

    /** ARGB for a Minecraft §-color code string (standard 16-color table); used to honor
     *  {@link com.bedwarsqol.stats.UrchinTag#color()}'s authoritative per-type palette. */
    public static int sectionColorToRgb(String code) {
        char c = code != null && code.length() >= 2 && code.charAt(0) == '§' ? code.charAt(1) : 'f';
        switch (c) {
            case '0': return 0xFF000000; case '1': return 0xFF0000AA; case '2': return 0xFF00AA00;
            case '3': return 0xFF00AAAA; case '4': return 0xFFAA0000; case '5': return 0xFFAA00AA;
            case '6': return 0xFFFFAA00; case '7': return 0xFFAAAAAA; case '8': return 0xFF555555;
            case '9': return 0xFF5555FF; case 'a': return 0xFF55FF55; case 'b': return 0xFF55FFFF;
            case 'c': return 0xFFFF5555; case 'd': return 0xFFFF55FF; case 'e': return 0xFFFFFF55;
            default:  return 0xFFFFFFFF;
        }
    }

    /** Compact count: {@code <1000} verbatim, else 1-decimal {@code k}/{@code M} (trailing {@code .0}
     *  trimmed). Used for the raw per-mode counters so a wide count never overruns its cell. */
    public static String abbrev(long v) {
        if (v < 1000L) return Long.toString(v);
        if (v < 1_000_000L) return trim1(v / 1000.0) + "k";
        return trim1(v / 1_000_000.0) + "M";
    }

    private static String trim1(double d) {
        String s = String.format(Locale.ROOT, "%.1f", d);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** A one-glyph severity chip for a list row: its ARGB color and optional 2-char code (may be null). */
    public static final class Chip {
        public final int argb;
        public final String code;
        public Chip(int argb, String code) { this.argb = argb; this.code = code; }
    }

    /**
     * The single most-severe safety signal across both providers for a list row, or null when there is
     * none. Any danger signal (all Urchin tags, and non-safelist Seraph tags) outranks a Seraph safelist
     * (a positive "known legit" marker); within a class the higher {@code severity()} wins, ties favor
     * Seraph. Callers pass already gating-filtered lists.
     */
    public static Chip chipFor(List<UrchinTag> urchin, long nowMs, List<SeraphTag> seraph) {
        UrchinTag u = UrchinTag.priority(urchin, nowMs);
        SeraphTag s = SeraphTag.priority(seraph);
        int uRank = u == null ? Integer.MIN_VALUE : 100 + u.severity();               // Urchin = danger band
        int sRank;
        if (s == null) sRank = Integer.MIN_VALUE;
        else if ("safelist".equals(s.kind)) sRank = s.severity();                     // positive, low band
        else sRank = 100 + s.severity();                                              // danger band
        if (uRank == Integer.MIN_VALUE && sRank == Integer.MIN_VALUE) return null;
        if (sRank >= uRank) return new Chip(sectionColorToRgb(s.color()), s.displayIcon());
        return new Chip(sectionColorToRgb(u.color()), u.displayIcon());
    }
}
