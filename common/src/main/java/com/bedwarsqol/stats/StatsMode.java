package com.bedwarsqol.stats;

import java.util.Locale;

/**
 * THE vocabulary of the {@code /bw mode} setting ({@code ClientSettings.chatStatsMode}): what a user
 * may type, what is stored, and what each stored token means. Every layer that touches the setting
 * — the command's parser and reply, the settings sanitiser, the display-mode resolver, help and tab
 * completion — reads it from here, so a synonym or a label can never drift between them.
 *
 * <p>Stored tokens are {@code auto} (follow the live per-game detection) or one of the five forced
 * modes {@code overall|solo|doubles|threes|fours}. {@code auto} is deliberately not a
 * {@link BedwarsMode}: {@link #forced} returns {@code null} for it so a caller falls through to
 * {@code BedwarsModeDetector.current()}, while {@code overall} maps to {@link BedwarsMode#UNKNOWN},
 * the enum's "use the overall block" value.
 */
public final class StatsMode {

    /** The stored token that means "follow the detected game mode". */
    public static final String AUTO = "auto";

    /** The words offered by help and tab completion, in display order. */
    public static final String[] OPTIONS = {"auto", "all", "solo", "2s", "3s", "4s"};

    private StatsMode() {
    }

    /**
     * Canonical stored token for a user-typed mode word, or {@code null} when unrecognised. Accepts
     * the option words plus their common synonyms; case- and whitespace-insensitive.
     */
    public static String parse(String typed) {
        if (typed == null) return null;
        switch (typed.trim().toLowerCase(Locale.US)) {
            case "auto": case "detect": case "default": return AUTO;
            case "all": case "overall": return BedwarsMode.UNKNOWN.jsonKey();
            case "solo": case "solos": case "1s": case "1v1": return BedwarsMode.SOLO.jsonKey();
            case "doubles": case "double": case "2s": case "2v2v2v2": return BedwarsMode.DOUBLES.jsonKey();
            case "threes": case "three": case "3s": case "3v3v3v3": return BedwarsMode.THREES.jsonKey();
            case "fours": case "four": case "4s": case "4v4v4v4": return BedwarsMode.FOURS.jsonKey();
            default: return null;
        }
    }

    /**
     * Coerce a stored token to a canonical one, defaulting anything unrecognised (a hand-edited
     * file, an older build's value, {@code null}) to {@link #AUTO}. Only the stored spellings are
     * accepted here — this is the sanitiser for the settings file, not the typed-word parser.
     */
    public static String normalize(String stored) {
        if (stored == null) return AUTO;
        String s = stored.trim().toLowerCase(Locale.US);
        for (BedwarsMode m : BedwarsMode.values()) {
            if (m.jsonKey().equals(s)) return s;
        }
        return AUTO;
    }

    /**
     * The mode a canonical stored token forces, or {@code null} for {@link #AUTO} (and anything
     * unrecognised), signalling the caller to use the live detection instead.
     */
    public static BedwarsMode forced(String stored) {
        if (stored == null) return null;
        String s = stored.trim().toLowerCase(Locale.US);
        for (BedwarsMode m : BedwarsMode.values()) {
            if (m.jsonKey().equals(s)) return m;
        }
        return null;
    }

    /** True when the stored token pins a mode rather than following detection. */
    public static boolean isForced(String stored) {
        return forced(stored) != null;
    }

    /** Human description of a stored token for the command reply: {@code Auto (per-game)}, {@code Solo}, … */
    public static String describe(String stored) {
        BedwarsMode m = forced(stored);
        return m == null ? "Auto (per-game)" : m.label();
    }
}
