package com.bedwarsqol.config;

import com.bedwarsqol.hud.HudPlacement;
import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Settings v3: HUD modules still at their old default spot move into the default layout; anything the
 * user moved stays. HUD Size rescales every module and keeps a custom size custom.
 */
public class HudLayoutMigrationTest {

    private static final Gson GSON = new Gson();
    private static final float EPS = 1e-4f;

    private static ClientSettings load(String json) {
        ClientSettings s = GSON.fromJson(json, ClientSettings.class);
        s.sanitize();
        return s;
    }

    @Test
    public void aFreshInstallUsesTheDefaultLayout() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        assertEquals(HudPlacement.AUTO, s.inventoryHudAnchor);
        assertEquals(HudPlacement.AUTO, s.diamondTimerHudAnchor);
        assertEquals(HudPlacement.AUTO, s.emeraldTimerHudAnchor);
        assertEquals(HudPlacement.AUTO, s.sessionStatsHudAnchor);
        assertEquals(HudPlacement.AUTO, s.heightLimitHudAnchor);
    }

    @Test
    public void oldDefaultsMoveIntoTheDefaultLayout() {
        ClientSettings s = load("{\"settingsVersion\":2,"
                + "\"inventoryHudX\":5,\"inventoryHudY\":5,\"inventoryHudAnchor\":6,"
                + "\"diamondTimerHudX\":-5,\"diamondTimerHudY\":5,\"diamondTimerHudAnchor\":2,"
                + "\"emeraldTimerHudX\":-5,\"emeraldTimerHudY\":27,\"emeraldTimerHudAnchor\":2,"
                + "\"sessionStatsHudX\":-5,\"sessionStatsHudY\":60,\"sessionStatsHudAnchor\":2,"
                + "\"heightLimitHudX\":5,\"heightLimitHudY\":5,\"heightLimitHudAnchor\":3}");
        assertEquals(HudPlacement.AUTO, s.inventoryHudAnchor);
        assertEquals(HudPlacement.AUTO, s.diamondTimerHudAnchor);
        assertEquals(HudPlacement.AUTO, s.emeraldTimerHudAnchor);
        assertEquals(HudPlacement.AUTO, s.sessionStatsHudAnchor);
        assertEquals(HudPlacement.AUTO, s.heightLimitHudAnchor);
        assertEquals(ClientSettings.CURRENT_SETTINGS_VERSION, s.settingsVersion);
    }

    @Test
    public void aPreV1SessionPositionStillReachesTheDefaultLayout() {
        ClientSettings s = load("{\"sessionStatsHudX\":5,\"sessionStatsHudY\":60,\"sessionStatsHudAnchor\":2}");
        assertEquals(HudPlacement.AUTO, s.sessionStatsHudAnchor);
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
        assertEquals(2, s.emeraldTimerHudAnchor);
        assertEquals(60, s.sessionStatsHudY);
        assertEquals(0, s.sessionStatsHudAnchor);
    }

    @Test
    public void runsOnlyOnce() {
        ClientSettings s = load("{\"settingsVersion\":3,\"emeraldTimerHudX\":-5,\"emeraldTimerHudY\":27,\"emeraldTimerHudAnchor\":2}");
        assertEquals(2, s.emeraldTimerHudAnchor);
        assertEquals(27, s.emeraldTimerHudY);
    }

    @Test
    public void aSavedDefaultLayoutAnchorSurvivesSanitize() {
        ClientSettings s = load("{\"settingsVersion\":3,\"sessionStatsHudAnchor\":-1,\"inventoryHudAnchor\":-7}");
        assertEquals(HudPlacement.AUTO, s.sessionStatsHudAnchor);
        assertEquals("out of range clamps to the default layout", HudPlacement.AUTO, s.inventoryHudAnchor);
    }

    @Test
    public void hudSizeRescalesEveryModuleAndKeepsCustomSizes() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        s.sessionStatsHudScale = 1.2f; // the player made Session Stats a bit bigger
        s.setHudSize(2);                // Medium -> Large: x1.5
        assertEquals(2, s.defaultTextSize);
        assertEquals(1.5f, s.inventoryHudScale, EPS);
        assertEquals(1.5f, s.diamondTimerHudScale, EPS);
        assertEquals(1.8f, s.sessionStatsHudScale, EPS);
        s.setHudSize(0);                // Large -> Small: x0.5
        assertEquals(0.75f, s.inventoryHudScale, EPS);
        assertEquals(0.9f, s.sessionStatsHudScale, EPS);
        s.setHudSize(1);                // back to Medium
        assertEquals(1.0f, s.inventoryHudScale, EPS);
        assertEquals(1.2f, s.sessionStatsHudScale, EPS);
    }

    @Test
    public void hudSizeKeepsScalesInRange() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        s.heightLimitHudScale = 0.3f;
        s.setHudSize(0);
        assertEquals(0.3f, s.heightLimitHudScale, EPS);
    }
}
