package com.bedwarsqol.config;

import com.bedwarsqol.hud.HudPlacement;
import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Settings v3 for the Forge-only HUD modules: Potion, Armor and Keystrokes. */
public class ForgeHudLayoutMigrationTest {

    private static final Gson GSON = new Gson();
    private static final float EPS = 1e-4f;

    private static ClientSettings load(String json) {
        ClientSettings s = GSON.fromJson(json, ClientSettings.class);
        s.sanitize();
        return s;
    }

    @Test
    public void oldDefaultsMoveIntoTheDefaultLayout() {
        ClientSettings s = load("{\"settingsVersion\":2,"
                + "\"potionHudX\":5,\"potionHudY\":5,\"potionHudAnchor\":0,"
                + "\"armorHudX\":5,\"armorHudY\":34,\"armorHudAnchor\":0,"
                + "\"keystrokesHudX\":-10,\"keystrokesHudY\":-20,\"keystrokesHudAnchor\":8}");
        assertEquals(HudPlacement.AUTO, s.potionHudAnchor);
        assertEquals(HudPlacement.AUTO, s.armorHudAnchor);
        assertEquals(HudPlacement.AUTO, s.keystrokesHudAnchor);
    }

    @Test
    public void movedModulesStay() {
        ClientSettings s = load("{\"settingsVersion\":2,"
                + "\"potionHudX\":5,\"potionHudY\":90,\"potionHudAnchor\":0,"
                + "\"armorHudX\":60,\"armorHudY\":34,\"armorHudAnchor\":0,"
                + "\"keystrokesHudX\":-10,\"keystrokesHudY\":-20,\"keystrokesHudAnchor\":6}");
        assertEquals(0, s.potionHudAnchor);
        assertEquals(90, s.potionHudY);
        assertEquals(60, s.armorHudX);
        assertEquals(6, s.keystrokesHudAnchor);
    }

    @Test
    public void hudSizeRescalesTheForgeModulesToo() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        s.setHudSize(2);
        assertEquals(1.5f, s.potionHudScale, EPS);
        assertEquals(1.5f, s.armorHudScale, EPS);
        assertEquals(1.5f, s.keystrokesHudScale, EPS);
    }
}
