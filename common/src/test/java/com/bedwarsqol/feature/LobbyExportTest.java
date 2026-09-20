package com.bedwarsqol.feature;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Pins launcher queue/game titles against Hypixel sidebar and detector labels. */
public class LobbyExportTest {

    @Before
    public void resetTransientState() {
        LobbyExport.resetEligibilityStateForTest();
    }

    @Test
    public void mapsDemoTitles() {
        assertEquals("Solos", LobbyExport.dashboardModeLabel("Solo"));
        assertEquals("Solos", LobbyExport.dashboardModeLabel("solo"));
        assertEquals("Solos", LobbyExport.dashboardModeLabel("Solos"));
        assertEquals("Doubles", LobbyExport.dashboardModeLabel("Doubles"));
        assertEquals("3v3v3v3", LobbyExport.dashboardModeLabel("3v3v3v3"));
        assertEquals("4v4v4v4", LobbyExport.dashboardModeLabel("4v4v4v4"));
        assertEquals("4v4", LobbyExport.dashboardModeLabel("4v4"));
    }

    @Test
    public void stripsDecorativeScoreboardSuffixesFromKnownModes() {
        assertEquals("Doubles", LobbyExport.dashboardModeLabel("Doubles 😈"));
        assertEquals("Doubles", LobbyExport.dashboardModeLabel("doubles ☠"));
        assertEquals("Solos", LobbyExport.dashboardModeLabel("Solo ⚔️"));
        assertEquals("4v4v4v4", LobbyExport.dashboardModeLabel("4v4v4v4 ✦"));
        assertNull(LobbyExport.dashboardModeLabel("Overall 😈"));
    }

    @Test
    public void passesThroughUnmappedSidebarModes() {
        assertEquals("Castle", LobbyExport.dashboardModeLabel("Castle"));
        assertEquals("Rush", LobbyExport.dashboardModeLabel(" Rush "));
        assertEquals("Doubles Rush", LobbyExport.dashboardModeLabel("Doubles Rush"));
    }

    @Test
    public void blanksAreNotAMode() {
        assertNull(LobbyExport.dashboardModeLabel(null));
        assertNull(LobbyExport.dashboardModeLabel(""));
        assertNull(LobbyExport.dashboardModeLabel("  "));
        assertNull(LobbyExport.dashboardModeLabel("Overall"));
    }

    @Test
    public void supportedAllowlistIncludesFourVFourAndDecoratedSolo() {
        assertTrue(LobbyExport.isSupportedDashboardMode("Solo"));
        assertTrue(LobbyExport.isSupportedDashboardMode("Solo ⚔️"));
        assertTrue(LobbyExport.isSupportedDashboardMode("Doubles"));
        assertTrue(LobbyExport.isSupportedDashboardMode("3v3v3v3"));
        assertTrue(LobbyExport.isSupportedDashboardMode("4v4v4v4"));
        assertTrue(LobbyExport.isSupportedDashboardMode("4v4"));
        assertFalse(LobbyExport.isSupportedDashboardMode("Castle"));
        assertFalse(LobbyExport.isSupportedDashboardMode("Rush"));
        assertFalse(LobbyExport.isSupportedDashboardMode("Doubles Rush"));
        assertFalse(LobbyExport.isSupportedDashboardMode("Overall"));
        assertFalse(LobbyExport.isSupportedDashboardMode(null));
    }

    @Test
    public void skywarsShapedInputsAreNotTheBedwarsHub() {
        LobbyExport.EvalResult r = LobbyExport.evaluate(true, false, false, false, null, "Solos");
        assertEquals("LOBBY", r.context);
        assertFalse(r.eligible);
        assertFalse(r.scanFullRoster);
        assertFalse(r.autoFetch);
        assertFalse(r.readQueuePartyFromTab);
    }

    @Test
    public void bedwarsHubIsEligibleWithoutAModeButNeverAutoFetches() {
        LobbyExport.EvalResult r = LobbyExport.evaluate(true, true, false, false, null, null);
        assertEquals("LOBBY", r.context);
        assertTrue(r.eligible);
        assertTrue(r.scanFullRoster);
        // The lobby tab is the whole lobby population; listing it must not enqueue a scrape per row.
        assertFalse(r.autoFetch);
        assertNull(r.modeToRetain);
    }

    @Test
    public void offHypixelIsMenuEvenWithGameFlags() {
        LobbyExport.EvalResult r = LobbyExport.evaluate(false, true, true, true, "Solo", "Solos");
        assertEquals("MENU", r.context);
        assertFalse(r.eligible);
        assertFalse(r.scanFullRoster);
        assertFalse(r.autoFetch);
        assertNull(r.modeToRetain);
    }

    @Test
    public void castleSidebarClearsRetainedSolos() {
        LobbyExport.EvalResult r = LobbyExport.evaluate(true, true, true, false, "Castle", "Solos");
        assertEquals("QUEUE", r.context);
        assertFalse(r.eligible);
        assertFalse(r.autoFetch);
        assertTrue(r.readQueuePartyFromTab);
        assertNull(r.modeToRetain);
    }

    @Test
    public void absentModeLineKeepsRetainedSolos() {
        LobbyExport.EvalResult r = LobbyExport.evaluate(true, true, false, true, null, "Solos");
        assertEquals("GAME", r.context);
        assertTrue(r.eligible);
        assertEquals("Solos", r.modeToRetain);
        assertTrue(r.scanFullRoster);
        assertTrue(r.autoFetch);
    }

    @Test
    public void absentModeLineWithoutRetainIsIneligible() {
        LobbyExport.EvalResult r = LobbyExport.evaluate(true, true, false, true, null, null);
        assertEquals("GAME", r.context);
        assertFalse(r.eligible);
        assertNull(r.modeToRetain);
        assertFalse(r.autoFetch);
    }

    @Test
    public void ineligibleQueueCopiesTabPartyWithoutFetching() {
        LobbyExport.EvalResult r = LobbyExport.evaluate(true, true, true, false, "Castle", "Solos");
        RecordingSink sink = new RecordingSink();
        LobbyExport.Lobby lobby = new LobbyExport.Lobby();
        lobby.inHypixel = true;
        List<LobbyExport.TabRow> tab = Arrays.asList(
                new LobbyExport.TabRow("Alice"), new LobbyExport.TabRow("Bob"));
        LobbyExport.applySnapshot(lobby, r, Arrays.asList("StaleParty"), tab,
                Arrays.asList("ShouldNotAppear"), null, sink);
        assertFalse(lobby.dashboardEligible);
        assertEquals("QUEUE", lobby.context);
        assertNull(lobby.mode);
        assertEquals(2, lobby.yourParty.size());
        assertEquals("Alice", lobby.yourParty.get(0).name);
        assertEquals("Bob", lobby.yourParty.get(1).name);
        assertTrue(lobby.players.isEmpty());
        assertTrue(sink.names.isEmpty());
    }

    @Test
    public void eligibleLobbyListsTabWithoutFetchingAndIgnoresQueueTypers() {
        LobbyExport.EvalResult r = LobbyExport.evaluate(true, true, false, false, null, null);
        RecordingSink sink = new RecordingSink();
        LobbyExport.Lobby lobby = new LobbyExport.Lobby();
        List<LobbyExport.TabRow> tab = Arrays.asList(new LobbyExport.TabRow("Steve"));
        LobbyExport.applySnapshot(lobby, r, Arrays.asList("PartyPal"), tab,
                Arrays.asList("Typer"), null, sink);
        assertTrue(lobby.dashboardEligible);
        assertEquals(1, lobby.yourParty.size());
        assertEquals("PartyPal", lobby.yourParty.get(0).name);
        assertEquals(1, lobby.players.size());
        assertEquals("Steve", lobby.players.get(0).name);
        assertTrue(sink.names.isEmpty());
    }

    @Test
    public void supportedQueueStillAutoFetchesItsPartyTab() {
        LobbyExport.EvalResult r = LobbyExport.evaluate(true, true, true, false, "Solo", null);
        assertEquals("QUEUE", r.context);
        assertTrue(r.eligible);
        assertTrue(r.readQueuePartyFromTab);
        assertTrue(r.autoFetch);
    }

    @Test
    public void bedwarsHubAfterAQueueIsEligibleImmediately() {
        LobbyExport.evaluate(true, true, true, false, "Solo", null, 1000L);
        LobbyExport.EvalResult hub = LobbyExport.evaluate(true, true, false, false, null, "Solos", 1100L);
        assertEquals("LOBBY", hub.context);
        assertTrue(hub.eligible);
        assertFalse(hub.autoFetch);
        assertEquals("Solos", hub.modeToRetain);
    }

    @Test
    public void soloRetainSurvivesModeLineGapIntoTheGame() {
        LobbyExport.EvalResult queue = LobbyExport.evaluate(true, true, true, false, "Solo", null, 1000L);
        assertEquals("Solos", queue.modeToRetain);
        LobbyExport.EvalResult gap = LobbyExport.evaluate(true, true, false, false, null, "Solos", 1100L);
        assertTrue(gap.eligible);
        assertEquals("Solos", gap.modeToRetain);
        LobbyExport.EvalResult game = LobbyExport.evaluate(true, true, false, true, null, "Solos", 1200L);
        assertTrue(game.eligible);
        assertEquals("GAME", game.context);
        assertEquals("Solos", game.modeToRetain);
    }

    @Test
    public void queueTabPartySurvivesIntoUnsupportedGame() {
        LobbyExport.EvalResult queue = LobbyExport.evaluate(true, true, true, false, "Castle", null, 1000L);
        LobbyExport.applySnapshot(new LobbyExport.Lobby(), queue, Collections.<String>emptyList(),
                Arrays.asList(new LobbyExport.TabRow("Alice"), new LobbyExport.TabRow("Bob")),
                null, null, new RecordingSink());
        LobbyExport.EvalResult game = LobbyExport.evaluate(true, true, false, true, null, null, 1500L);
        assertFalse(game.eligible);
        RecordingSink sink = new RecordingSink();
        LobbyExport.Lobby lobby = new LobbyExport.Lobby();
        LobbyExport.applySnapshot(lobby, game, Collections.<String>emptyList(),
                Collections.<LobbyExport.TabRow>emptyList(), null, null, sink);
        assertEquals(2, lobby.yourParty.size());
        assertEquals("Alice", lobby.yourParty.get(0).name);
        assertEquals("Bob", lobby.yourParty.get(1).name);
        assertTrue(lobby.players.isEmpty());
        assertTrue(sink.names.isEmpty());
    }

    @Test
    public void queueTabPartySurvivesIntoUnsupportedLobby() {
        LobbyExport.EvalResult queue = LobbyExport.evaluate(true, true, true, false, "Castle", null, 1000L);
        LobbyExport.applySnapshot(new LobbyExport.Lobby(), queue, Collections.<String>emptyList(),
                Arrays.asList(new LobbyExport.TabRow("Alice"), new LobbyExport.TabRow("Bob")),
                null, null, new RecordingSink());
        LobbyExport.EvalResult skywars = LobbyExport.evaluate(true, false, false, false, null, null, 4000L);
        assertEquals("LOBBY", skywars.context);
        assertFalse(skywars.eligible);
        RecordingSink sink = new RecordingSink();
        LobbyExport.Lobby lobby = new LobbyExport.Lobby();
        LobbyExport.applySnapshot(lobby, skywars, Collections.<String>emptyList(),
                Collections.<LobbyExport.TabRow>emptyList(), null, null, sink);
        assertEquals(2, lobby.yourParty.size());
        assertEquals("Alice", lobby.yourParty.get(0).name);
        assertEquals("Bob", lobby.yourParty.get(1).name);
        assertTrue(lobby.players.isEmpty());
        assertTrue(sink.names.isEmpty());
    }

    @Test
    public void doublesRushQueueBlocksTeamShapeFallback() {
        LobbyExport.evaluate(true, true, true, false, "Doubles Rush", null, 1000L);
        assertTrue(LobbyExport.sawUnsupportedMode());
        LobbyExport.EvalResult game = LobbyExport.evaluate(true, true, false, true, null, null, 1500L);
        assertFalse(game.eligible);
        assertTrue(LobbyExport.sawUnsupportedMode());
        LobbyExport.EvalResult hubGap = LobbyExport.evaluate(true, true, false, false, null, null, 1600L);
        assertTrue(hubGap.eligible);
        assertTrue(LobbyExport.sawUnsupportedMode());
        LobbyExport.evaluate(true, true, false, false, null, null, 4000L);
        assertFalse(LobbyExport.sawUnsupportedMode());
    }

    /** A6: the roster's presence rides each game row onto the player and into the JSON. */
    @Test
    public void gameRowsCarryPresenceIntoPlayersAndTeams() {
        LobbyExport.EvalResult game = LobbyExport.evaluate(true, true, false, true, "Solo", null, 1000L);
        assertTrue(game.scanFullRoster);
        LobbyExport.Lobby lobby = new LobbyExport.Lobby();
        LobbyExport.applySnapshot(lobby, game, Collections.<String>emptyList(),
                Arrays.asList(new LobbyExport.TabRow("Alice", "Red"),
                        new LobbyExport.TabRow("Bob", "Blue", GameRoster.ELIMINATED),
                        new LobbyExport.TabRow("Carol", "Blue", null)),
                null, null, new RecordingSink());
        assertEquals(3, lobby.players.size());
        assertEquals(GameRoster.ACTIVE, lobby.players.get(0).presence);
        assertEquals(GameRoster.ELIMINATED, lobby.players.get(1).presence);
        assertEquals(GameRoster.ACTIVE, lobby.players.get(2).presence); // null row presence → ACTIVE
        assertEquals(2, lobby.teams.size());
        assertEquals("Blue", lobby.teams.get(1).name);
        assertEquals(GameRoster.ELIMINATED, lobby.teams.get(1).players.get(0).presence);
        assertSame(lobby.players.get(1), lobby.teams.get(1).players.get(0));
    }

    @Test
    public void playerJsonCarriesPresence() {
        LobbyExport.Player p = new LobbyExport.Player("Alice");
        com.google.gson.JsonObject tree = new com.google.gson.Gson().toJsonTree(p).getAsJsonObject();
        assertEquals("ACTIVE", tree.get("presence").getAsString());
        p.presence = GameRoster.MISSING;
        tree = new com.google.gson.Gson().toJsonTree(p).getAsJsonObject();
        assertEquals("MISSING", tree.get("presence").getAsString());
    }

    private static final class RecordingSink implements LobbyExport.FetchSink {
        final List<String> names = new ArrayList<String>();

        public void fetch(LobbyExport.TabRow row) {
            names.add(row.name);
        }
    }
}
