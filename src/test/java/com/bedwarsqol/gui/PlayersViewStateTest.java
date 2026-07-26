package com.bedwarsqol.gui;

import org.junit.Before;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the transient-lookup contract (CR-02): any change to the player-search query clears the remote
 * lookup so a looked-up player's detail cannot outlive the query that opened it, while a live-row
 * selection is untouched.
 */
public class PlayersViewStateTest {

    @Before
    public void reset() {
        PlayersViewState.playerQuery = "";
        PlayersViewState.lookupName = null;
        PlayersViewState.listScroll = 0;
        PlayersViewState.selectedUuid = null;
    }

    @Test
    public void changingQueryClearsLookupAndResetsScroll() {
        PlayersViewState.lookupName = "Ghost";
        PlayersViewState.listScroll = 120f;
        assertTrue(PlayersViewState.setQuery("gh"));
        assertEquals("gh", PlayersViewState.playerQuery);
        assertNull(PlayersViewState.lookupName);
        assertEquals(0f, PlayersViewState.listScroll, 0f);
    }

    @Test
    public void clearingQueryAlsoClearsLookup() {
        PlayersViewState.playerQuery = "gh";
        PlayersViewState.lookupName = "Ghost";
        assertTrue(PlayersViewState.setQuery(""));
        assertNull(PlayersViewState.lookupName);
    }

    @Test
    public void unchangedQueryReportsNoChangeAndKeepsLookup() {
        PlayersViewState.playerQuery = "gh";
        PlayersViewState.lookupName = "Ghost";
        assertFalse(PlayersViewState.setQuery("gh"));
        assertEquals("Ghost", PlayersViewState.lookupName); // no spurious clear on a no-op keystroke
    }

    @Test
    public void selectedLiveRowSurvivesQueryChange() {
        UUID u = UUID.fromString("11111111-1111-4111-8111-111111111111");
        PlayersViewState.selectedUuid = u;
        PlayersViewState.setQuery("abc");
        assertEquals(u, PlayersViewState.selectedUuid); // only the name-keyed lookup is transient
    }
}
