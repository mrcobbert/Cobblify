package com.bedwarsqol.stats;

import java.util.Locale;

/**
 * A Bedwars team format. {@link #UNKNOWN} covers the lobby, dream modes, 4v4, and any
 * layout we can't confidently map — callers fall back to overall stats for it.
 */
public enum BedwarsMode {
    SOLO("solo", "Solo"),
    DOUBLES("doubles", "Doubles"),
    THREES("threes", "3v3v3v3"),
    FOURS("fours", "4v4v4v4"),
    UNKNOWN("overall", "Overall");

    private final String jsonKey;
    private final String label;

    BedwarsMode(String jsonKey, String label) {
        this.jsonKey = jsonKey;
        this.label = label;
    }

    public String jsonKey() {
        return jsonKey;
    }

    public String label() {
        return label;
    }

    /**
     * Map the team count and players-per-team observed on the scoreboard (at game start,
     * when teams are full) to a standard mode: 8×1 Solo, 8×2 Doubles, 4×3 Threes, 4×4
     * Fours. Anything else (2×4 4v4, dream variants) is {@link #UNKNOWN}.
     */
    public static BedwarsMode fromTeams(int teamCount, int maxTeamSize) {
        if (teamCount == 8) {
            return maxTeamSize <= 1 ? SOLO : DOUBLES;
        }
        if (teamCount == 4) {
            if (maxTeamSize == 3) return THREES;
            if (maxTeamSize == 4) return FOURS;
        }
        return UNKNOWN;
    }

    /**
     * A forced display mode from a stored/typed token: {@code all}/{@code overall} &rarr; {@link #UNKNOWN}
     * (overall), plus {@code solo}/{@code doubles}/{@code threes}/{@code fours}. Returns {@code null} for
     * {@code "auto"} (and anything unrecognised), signalling the caller to fall back to live detection.
     */
    public static BedwarsMode fromToken(String token) {
        if (token == null) return null;
        switch (token.trim().toLowerCase(Locale.US)) {
            case "all": case "overall": return UNKNOWN;
            case "solo": return SOLO;
            case "doubles": return DOUBLES;
            case "threes": return THREES;
            case "fours": return FOURS;
            default: return null;
        }
    }
}
