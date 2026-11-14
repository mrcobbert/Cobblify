package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.config.SettingsManager;
import com.bedwarsqol.gui.sheet.SheetSchema;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.HudModules;
import com.google.gson.Gson;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The scoreboard, tab list and chat Background switches on the Ornithe sheet: modules whose toggle is the item's
 * Background, read and written through hud-look's one Background mechanism ({@link SheetSchema#hudItem},
 * {@link HudModules#background}, {@link ClientSettings#hudBackground}); On by default, persisted.
 */
public class SettingsBindingsOverlayBackgroundTest {

    /** Sheet module, HUD item. */
    private static final String[][] SWITCHES = {
            {"scoreboardBg", BedwarsHudRenderer.SCOREBOARD_HUD},
            {"tabListBg", BedwarsHudRenderer.TAB_LIST_HUD},
            {"chatBg", BedwarsHudRenderer.CHAT_HUD},
    };

    private String savedDir;
    private ClientSettings savedConfig;

    @Before
    public void isolate() throws Exception {
        savedDir = System.getProperty("cobblify.test.configDir");
        savedConfig = BedwarsQol.config;
        File dir = Files.createTempDirectory("cobblify-overlay-background-test").toFile();
        System.setProperty("cobblify.test.configDir", dir.getAbsolutePath());
        BedwarsQol.config = new ClientSettings();
    }

    @After
    public void restore() {
        if (savedDir == null) System.clearProperty("cobblify.test.configDir");
        else System.setProperty("cobblify.test.configDir", savedDir);
        BedwarsQol.config = savedConfig;
    }

    @Test
    public void onByDefaultAlsoForAConfigSavedBeforeTheSetting() {
        SettingsBindings values = new SettingsBindings();
        for (String[] s : SWITCHES) {
            assertTrue(s[0], SheetSchema.shows(values, s[0]));
            assertTrue(s[0], values.on(s[0]));
            assertTrue(s[1], HudModules.get(BedwarsQol.config, s[1]).background);
        }
        ClientSettings old = new Gson().fromJson("{\"scoreboardSize\":1,\"styledTabListSize\":0,\"chatCopy\":true}",
                ClientSettings.class);
        old.sanitize();
        BedwarsQol.config = old;
        for (String[] s : SWITCHES) assertTrue(s[0], values.on(s[0]));
    }

    @Test
    public void theModuleToggleWritesTheItemsBackgroundAndSaves() {
        SettingsBindings values = new SettingsBindings();
        for (String[] s : SWITCHES) {
            values.setOn(s[0], false);
            assertFalse(s[1], BedwarsQol.config.hudBackground(s[1]));
            assertFalse(s[1], HudModules.background(BedwarsQol.config, s[1]));
            assertFalse(s[1], HudModules.get(BedwarsQol.config, s[1]).background);
            assertFalse(s[0], values.on(s[0]));
            assertFalse("saved", SettingsManager.load().hudBackground(s[1]));
            values.setOn(s[0], true);
            assertTrue(s[1], HudModules.background(BedwarsQol.config, s[1]));
            assertTrue("saved", SettingsManager.load().hudBackground(s[1]));
        }
        values.setOn("chatBg", false);
        assertTrue("only that item", values.on("scoreboardBg"));
        assertTrue(values.on("tabListBg"));
    }

    @Test
    public void editHudsFlagAndTheSheetAreTheSameSwitch() {
        SettingsBindings values = new SettingsBindings();
        ClientSettings c = BedwarsQol.config;
        for (String[] s : SWITCHES) {
            HudModules.setBackground(c, s[1], false); // what Edit HUD's menu toggles
            assertFalse(s[0], values.on(s[0]));
            HudModules.set(c, s[1], HudModules.get(c, s[1]).withBackground(true));
            assertTrue(s[0], values.on(s[0]));
            HudModules.set(c, s[1], HudModules.get(c, s[1]).withBackground(false));
            assertFalse(s[1], c.hudBackground(s[1]));
            assertEquals("the rest of the state is kept", HudModules.get(c, s[1]).withBackground(true),
                    HudModules.defaults(c, s[1]).withBackground(true));
        }
    }

    @Test
    public void survivesAGsonRoundTrip() {
        ClientSettings c = BedwarsQol.config;
        HudModules.setBackground(c, BedwarsHudRenderer.SCOREBOARD_HUD, false);
        HudModules.setBackground(c, BedwarsHudRenderer.CHAT_HUD, false);
        Gson gson = new Gson();
        ClientSettings back = gson.fromJson(gson.toJson(c), ClientSettings.class);
        back.sanitize();
        assertFalse(HudModules.background(back, BedwarsHudRenderer.SCOREBOARD_HUD));
        assertFalse(HudModules.background(back, BedwarsHudRenderer.CHAT_HUD));
        assertTrue(HudModules.background(back, BedwarsHudRenderer.TAB_LIST_HUD));
    }

    @Test
    public void editHudsOpenSettingsLandsOnTheSwitch() {
        for (String[] s : SWITCHES) assertEquals(s[1], s[0], SettingsBindings.moduleForHud(s[1]));
    }

    @Test
    public void theRendererDrawsWhatTheSwitchSays() {
        ClientSettings c = BedwarsQol.config;
        for (String[] s : SWITCHES) {
            assertTrue(s[1], BedwarsHudRenderer.overlayBackground(c, s[1]));
            HudModules.setBackground(c, s[1], false);
            assertFalse(s[1], BedwarsHudRenderer.overlayBackground(c, s[1]));
        }
        assertTrue("no config yet: On", BedwarsHudRenderer.overlayBackground(null, BedwarsHudRenderer.CHAT_HUD));
    }
}
