package com.bedwarsqol.rewind;

import com.bedwarsqol.feature.SessionChatLine;

/**
 * Which Rewind markers exist and which game lines make one (G9, G47). Automatic markers are the
 * player's own only: {@link SessionChatLine} already classifies every line from the local player's
 * point of view, so this is a plain mapping. The Marker Key adds {@link Marker#KEY}, which keeps the
 * game forever (never pruned).
 *
 * <p>A marker is accepted only while a game is open or a game-server run is pending (it is then
 * carried into the game when it opens); {@link RewindGameRules} applies that rule.
 */
public final class MarkerRules {

    /** Marker kinds with their TRACK {@code MARKER} codes (format v1). */
    public enum Marker {
        KEY(1),
        DEATH(2),
        FINAL_KILL(3),
        BED_BROKEN(4),
        BED_LOST(5);

        public final int code;

        Marker(int code) {
            this.code = code;
        }

        /** The marker for a stored code, or {@code null} for an unknown one. */
        public static Marker fromCode(int code) {
            for (Marker m : values()) if (m.code == code) return m;
            return null;
        }
    }

    private MarkerRules() {
    }

    /**
     * The automatic marker for one classified game line, or {@code null}: DEATH and FINAL_DEATH are
     * deaths, FINAL_KILL, BED_BREAK (a bed you broke) and BED_LOST (your own bed). Kills, results,
     * eliminations and the end-of-game summary are not markers.
     */
    public static Marker forLine(SessionChatLine.Kind kind) {
        if (kind == null) return null;
        switch (kind) {
            case DEATH:
            case FINAL_DEATH:
                return Marker.DEATH;
            case FINAL_KILL:
                return Marker.FINAL_KILL;
            case BED_BREAK:
                return Marker.BED_BROKEN;
            case BED_LOST:
                return Marker.BED_LOST;
            default:
                return null;
        }
    }

    /** Only a Marker Key press keeps a game forever. */
    public static boolean keepsForever(Marker marker) {
        return marker == Marker.KEY;
    }

    /** A marker counts only inside an open game or a pending game-server run. */
    public static boolean accepts(boolean gameOpen, boolean gameServerRunPending) {
        return gameOpen || gameServerRunPending;
    }
}
