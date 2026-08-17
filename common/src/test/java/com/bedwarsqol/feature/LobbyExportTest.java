package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Pins launcher queue/game titles against Hypixel sidebar and detector labels. */
public class LobbyExportTest {

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
}
