package com.bedwarsqol.stats;

/**
 * A Bedwars team format. {@link #UNKNOWN} covers the lobby, dream modes, 4v4, and any
 * layout we can't confidently map — callers fall back to overall stats for it.
 */
public enum BedwarsMode {
    SOLO("solo", "Solo", "Solo"),
    DOUBLES("doubles", "Doubles", "2s"),
    THREES("threes", "3v3v3v3", "3s"),
    FOURS("fours", "4v4v4v4", "4s"),
    UNKNOWN("overall", "Overall", "All");

    private final String jsonKey;
    private final String label;
    private final String shortLabel;

    BedwarsMode(String jsonKey, String label, String shortLabel) {
        this.jsonKey = jsonKey;
        this.label = label;
        this.shortLabel = shortLabel;
    }

    public String jsonKey() {
        return jsonKey;
    }

    /** Long name, as the pregame sidebar and the {@code /bw mode} reply spell it. */
    public String label() {
        return label;
    }

    /**
     * Short tag stamped on a stat that comes from this mode's block — {@code Solo}/{@code 2s}/
     * {@code 3s}/{@code 4s}, and {@code All} for the overall block (which is what a forced mode
     * renders when the player has no games in it).
     */
    public String shortLabel() {
        return shortLabel;
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
}
