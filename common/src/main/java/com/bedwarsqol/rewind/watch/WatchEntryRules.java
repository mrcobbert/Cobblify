package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindGameRules;

/**
 * Whether the pause menu shows "Watch Last Game" (G16, G35; TASK A4, A16): the Rewind module is on, the player is on
 * Hypixel, the newest kept game is available, and either no game is open and the current run is a lobby, or a game
 * is open and has a WIN/LOSS result. Never mid-game, never in a waiting room.
 *
 * <p>Recorder state comes from {@link RewindGameRules#entryState()}. Two of its cases, handled here on purpose:
 * <ul>
 *   <li>With Record Games off the recorder still follows the connection's worlds (code review 2, I4): no game is
 *       ever open, and a lobby run shows the row for the newest kept game, as A4 asks (no Record Games
 *       condition).</li>
 *   <li>Inside Hypixel's replay viewer {@code lobby} is true, so the row shows there like in any lobby: leaving it
 *       loses no game. Replay-viewer recordings themselves never count as the newest game
 *       ({@link RecordingLibrary#newest()}).</li>
 * </ul>
 */
public final class WatchEntryRules {

    /** The newest kept recording, as the library sees it. */
    public enum Newest {
        /** No kept recording (REPLAY visits do not count). */
        NONE,
        /** Still being written (its {@code .json} is not complete): the game that is ending or just ended. */
        WRITING,
        /** Closed, quick check not run yet. Shown: a load that then fails ends in the gallery (round 1 I3). */
        UNCHECKED,
        /** Closed and passed the quick check. */
        PASSED,
        /** Closed and failed the quick check: the row is hidden; the gallery still lists it. */
        FAILED
    }

    private WatchEntryRules() {
    }

    public static boolean show(boolean rewindOn, boolean onHypixel, Newest newest, RewindGameRules.EntryState s) {
        return s != null && show(rewindOn, onHypixel, newest, s.gameOpen, s.resultKnown, s.lobby);
    }

    public static boolean show(boolean rewindOn, boolean onHypixel, Newest newest, boolean gameOpen,
                               boolean resultKnown, boolean lobby) {
        if (!rewindOn || !onHypixel) return false;
        if (newest == null || newest == Newest.NONE || newest == Newest.FAILED) return false;
        return gameOpen ? resultKnown : lobby;
    }
}
