package com.bedwarsqol.config;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Settings v3 moves HUD modules still at their old default spots to the new default layout, and
 * leaves anything the user moved alone.
 */
public class HudLayoutMigrationTest {

    private static final Gson GSON = new Gson();

    private static ClientSettings load(String json) {
        ClientSettings s = GSON.fromJson(json, ClientSettings.class);
        s.sanitize();
        return s;
    }

    @Test
    public void oldDefaultsMoveToTheNewLayout() {
        ClientSettings s = load("{\"settingsVersion\":2,"
                + "\"inventoryHudX\":5,\"inventoryHudY\":5,\"inventoryHudAnchor\":6,"
                + "\"emeraldTimerHudX\":-5,\"emeraldTimerHudY\":27,\"emeraldTimerHudAnchor\":2,"
                + "\"sessionStatsHudX\":-5,\"sessionStatsHudY\":60,\"sessionStatsHudAnchor\":2}");
        ClientSettings fresh = new ClientSettings();
        assertEquals(fresh.inventoryHudX, s.inventoryHudX);
        assertEquals(fresh.inventoryHudY, s.inventoryHudY);
        assertEquals(fresh.inventoryHudAnchor, s.inventoryHudAnchor);
        assertEquals(fresh.emeraldTimerHudX, s.emeraldTimerHudX);
        assertEquals(fresh.emeraldTimerHudY, s.emeraldTimerHudY);
        assertEquals(fresh.sessionStatsHudY, s.sessionStatsHudY);
        assertEquals(ClientSettings.CURRENT_SETTINGS_VERSION, s.settingsVersion);
    }

    @Test
    public void aPreV1SessionPositionStillReachesTheNewDefault() {
        ClientSettings s = load("{\"sessionStatsHudX\":5,\"sessionStatsHudY\":60,\"sessionStatsHudAnchor\":2}");
        assertEquals(-5, s.sessionStatsHudX);
        assertEquals(new ClientSettings().sessionStatsHudY, s.sessionStatsHudY);
    }

    @Test
    public void movedModulesStayWhereTheUserPutThem() {
        ClientSettings s = load("{\"settingsVersion\":2,"
                + "\"inventoryHudX\":40,\"inventoryHudY\":-12,\"inventoryHudAnchor\":6,"
                + "\"emeraldTimerHudX\":-5,\"emeraldTimerHudY\":80,\"emeraldTimerHudAnchor\":2,"
                + "\"sessionStatsHudX\":5,\"sessionStatsHudY\":60,\"sessionStatsHudAnchor\":0}");
        assertEquals(40, s.inventoryHudX);
        assertEquals(-12, s.inventoryHudY);
        assertEquals(6, s.inventoryHudAnchor);
        assertEquals(80, s.emeraldTimerHudY);
        assertEquals(60, s.sessionStatsHudY);
        assertEquals(0, s.sessionStatsHudAnchor);
    }

    @Test
    public void runsOnlyOnce() {
        ClientSettings s = load("{\"settingsVersion\":3,\"emeraldTimerHudX\":-5,\"emeraldTimerHudY\":27,\"emeraldTimerHudAnchor\":2}");
        assertEquals(-5, s.emeraldTimerHudX);
        assertEquals(27, s.emeraldTimerHudY);
    }

    @Test
    public void aFreshInstallKeepsTheNewDefaults() {
        ClientSettings fresh = new ClientSettings();
        ClientSettings s = new ClientSettings();
        s.sanitize();
        assertEquals(fresh.inventoryHudAnchor, s.inventoryHudAnchor);
        assertEquals(fresh.emeraldTimerHudX, s.emeraldTimerHudX);
        assertEquals(fresh.sessionStatsHudY, s.sessionStatsHudY);
    }
}
