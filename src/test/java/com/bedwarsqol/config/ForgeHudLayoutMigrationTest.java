package com.bedwarsqol.config;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Settings v3 for the Forge-only HUD modules: Potion, Armor and Keystrokes. */
public class ForgeHudLayoutMigrationTest {

    private static final Gson GSON = new Gson();

    private static ClientSettings load(String json) {
        ClientSettings s = GSON.fromJson(json, ClientSettings.class);
        s.sanitize();
        return s;
    }

    @Test
    public void armorAndPotionStopOverlapping() {
        ClientSettings s = load("{\"settingsVersion\":2,"
                + "\"potionHudX\":5,\"potionHudY\":5,\"potionHudAnchor\":0,"
                + "\"armorHudX\":5,\"armorHudY\":34,\"armorHudAnchor\":0}");
        assertEquals(5, s.armorHudY);
        assertEquals(25, s.potionHudY);
    }

    @Test
    public void keystrokesMovesIntoTheCorner() {
        ClientSettings s = load("{\"settingsVersion\":2,"
                + "\"keystrokesHudX\":-10,\"keystrokesHudY\":-20,\"keystrokesHudAnchor\":8}");
        assertEquals(-5, s.keystrokesHudX);
        assertEquals(-5, s.keystrokesHudY);
    }

    @Test
    public void movedModulesStay() {
        ClientSettings s = load("{\"settingsVersion\":2,"
                + "\"potionHudX\":5,\"potionHudY\":90,\"potionHudAnchor\":0,"
                + "\"armorHudX\":60,\"armorHudY\":34,\"armorHudAnchor\":0,"
                + "\"keystrokesHudX\":-10,\"keystrokesHudY\":-20,\"keystrokesHudAnchor\":6}");
        assertEquals(90, s.potionHudY);
        assertEquals(60, s.armorHudX);
        assertEquals(34, s.armorHudY);
        assertEquals(-10, s.keystrokesHudX);
        assertEquals(-20, s.keystrokesHudY);
    }
}
