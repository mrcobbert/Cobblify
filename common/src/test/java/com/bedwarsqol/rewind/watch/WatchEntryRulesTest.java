package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindGameRules;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The pause menu's "Watch Last Game" row (TASK A4, A16; PLAN section 6): Rewind on, on Hypixel, a newest kept game
 * that is still being written or has not failed the quick check, and (no game open and a lobby run) or (a game open
 * with a WIN/LOSS).
 */
public class WatchEntryRulesTest {

    private static final WatchEntryRules.Newest[] ALL = WatchEntryRules.Newest.values();

    private static boolean show(boolean on, boolean hypixel, WatchEntryRules.Newest n, boolean open, boolean result,
                                boolean lobby) {
        return WatchEntryRules.show(on, hypixel, n, open, result, lobby);
    }

    @Test
    public void lobbyWithAKeptGame() {
        assertTrue(show(true, true, WatchEntryRules.Newest.PASSED, false, false, true));
    }

    @Test
    public void gameOpenWithAResult() {
        // the game that just ended is still being written: Watch Last Game opens it once the recorder settles
        assertTrue(show(true, true, WatchEntryRules.Newest.WRITING, true, true, false));
        assertTrue(show(true, true, WatchEntryRules.Newest.PASSED, true, true, false));
    }

    @Test
    public void neverMidGameOrInAWaitingRoom() {
        // mid-game: open, no result
        assertFalse(show(true, true, WatchEntryRules.Newest.WRITING, true, false, false));
        // waiting room / undecided run: neither open nor a lobby
        assertFalse(show(true, true, WatchEntryRules.Newest.PASSED, false, false, false));
    }

    @Test
    public void everyGateIsRequired() {
        for (WatchEntryRules.Newest n : ALL) {
            assertFalse("module off " + n, show(false, true, n, false, false, true));
            assertFalse("off Hypixel " + n, show(true, false, n, false, false, true));
        }
        assertFalse("no kept game", show(true, true, WatchEntryRules.Newest.NONE, false, false, true));
        assertFalse("no kept game, result", show(true, true, WatchEntryRules.Newest.NONE, true, true, false));
    }

    @Test
    public void quickCheckRows() {
        // passed or not yet checked: shown (a load that fails then lands in the gallery, round 1 I3)
        assertTrue(show(true, true, WatchEntryRules.Newest.PASSED, false, false, true));
        assertTrue(show(true, true, WatchEntryRules.Newest.UNCHECKED, false, false, true));
        assertTrue(show(true, true, WatchEntryRules.Newest.WRITING, false, false, true));
        // failed: hidden, in a lobby and after a result (the gallery still lists it)
        assertFalse(show(true, true, WatchEntryRules.Newest.FAILED, false, false, true));
        assertFalse(show(true, true, WatchEntryRules.Newest.FAILED, true, true, false));
    }

    @Test
    public void fullTruthTable() {
        int shown = 0;
        for (int bits = 0; bits < 32; bits++) {
            boolean on = (bits & 1) != 0, hyp = (bits & 2) != 0, open = (bits & 4) != 0, res = (bits & 8) != 0;
            boolean lobby = (bits & 16) != 0;
            for (WatchEntryRules.Newest n : ALL) {
                boolean available = n != WatchEntryRules.Newest.NONE && n != WatchEntryRules.Newest.FAILED;
                boolean expect = on && hyp && available && ((!open && lobby) || (open && res));
                assertEquals(bits + " " + n, expect, show(on, hyp, n, open, res, lobby));
                if (expect) shown++;
            }
        }
        // on, hypixel, 3 available states, and (open+res with lobby either way) or (!open, lobby, res either way)
        assertEquals(3 * 4, shown);
    }

    @Test
    public void recorderStateFromRewindGameRules() {
        // an inactive recorder (Record Games off) reports all false: no row, even with old recordings
        RewindGameRules rules = new RewindGameRules();
        RewindGameRules.EntryState s = rules.entryState();
        assertFalse(WatchEntryRules.show(true, true, WatchEntryRules.Newest.PASSED, s));
    }
}
