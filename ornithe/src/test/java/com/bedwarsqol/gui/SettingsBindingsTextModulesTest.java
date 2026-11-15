package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.sheet.SheetSchema;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.HudModuleState;
import com.bedwarsqol.hud.HudModules;
import com.bedwarsqol.hud.HudPlacement;
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
 * FPS, CPS and Ping: HUD modules of the Ornithe sheet, each with its Background and Brackets rows; off by default with
 * the Background on and Brackets off (also for a config saved before they existed); their rows write the settings,
 * Brackets independent of the Background; Edit HUD knows them; their layout fields round-trip, sanitize and follow HUD
 * Size like every module's.
 */
public class SettingsBindingsTextModulesTest {

    private static final String[] IDS = {BedwarsHudRenderer.FPS_HUD, BedwarsHudRenderer.CPS_HUD, BedwarsHudRenderer.PING_HUD};

    private String savedDir;
    private ClientSettings savedConfig;

    @Before
    public void isolate() throws Exception {
        savedDir = System.getProperty("cobblify.test.configDir");
        savedConfig = BedwarsQol.config;
        File dir = Files.createTempDirectory("cobblify-text-modules-test").toFile();
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
    public void listedOnOrnithe() {
        SettingsBindings values = new SettingsBindings();
        for (String id : new String[]{"fps", "cps", "ping", "fpsBg", "cpsBg", "pingBg", "fpsBrackets", "cpsBrackets",
                "pingBrackets"}) {
            assertTrue(id, SheetSchema.shows(values, id));
        }
    }

    @Test
    public void offByDefaultBackgroundOn() {
        SettingsBindings values = new SettingsBindings();
        ClientSettings old = new Gson().fromJson("{\"suppressEscMenu\":true,\"keystrokesEnabled\":true}", ClientSettings.class);
        old.sanitize();
        for (ClientSettings c : new ClientSettings[]{new ClientSettings(), old}) {
            assertFalse(c.fpsEnabled);
            assertFalse(c.cpsEnabled);
            assertFalse(c.pingEnabled);
            for (String id : IDS) {
                HudModuleState s = HudModules.get(c, id);
                assertEquals(id, HudPlacement.AUTO, s.anchor);
                assertEquals(id, 1.0f, s.scale, 0f);
                assertTrue(id, s.background);
                assertFalse(id, HudModules.brackets(c, id));
            }
        }
        for (String id : new String[]{"fps", "cps", "ping"}) {
            assertFalse(id, values.on(id));
            assertTrue(id, values.on(id + "Bg"));
            assertFalse(id, values.on(id + "Brackets"));
        }
    }

    @Test
    public void rowsWriteTheSettings() {
        SettingsBindings values = new SettingsBindings();
        values.setOn("fps", true);
        values.setOn("cps", true);
        values.setOn("ping", true);
        assertTrue(BedwarsQol.config.fpsEnabled);
        assertTrue(BedwarsQol.config.cpsEnabled);
        assertTrue(BedwarsQol.config.pingEnabled);
        values.setOn("cpsBg", false);
        assertFalse(BedwarsQol.config.hudBackground(BedwarsHudRenderer.CPS_HUD));
        assertTrue(BedwarsQol.config.hudBackground(BedwarsHudRenderer.FPS_HUD));
        values.setOn("ping", false);
        assertFalse(values.on("ping"));
    }

    @Test
    public void bracketsAreTheirOwnSwitch() {
        SettingsBindings values = new SettingsBindings();
        // Background off no longer brings brackets with it
        values.setOn("fpsBg", false);
        assertFalse(HudModules.brackets(BedwarsQol.config, BedwarsHudRenderer.FPS_HUD));
        // and brackets go on with the Background on or off
        values.setOn("cpsBrackets", true);
        assertTrue(BedwarsQol.config.cpsBrackets);
        assertTrue(HudModules.brackets(BedwarsQol.config, BedwarsHudRenderer.CPS_HUD));
        assertTrue(HudModules.background(BedwarsQol.config, BedwarsHudRenderer.CPS_HUD));
        values.setOn("pingBrackets", true);
        values.setOn("pingBg", false);
        assertTrue(values.on("pingBrackets"));
        assertFalse(values.on("pingBg"));
        assertFalse(values.on("fpsBrackets"));
        values.setOn("cpsBrackets", false);
        assertFalse(BedwarsQol.config.cpsBrackets);
        assertFalse(HudModules.brackets(BedwarsQol.config, "potion"));
        // saved and read back
        ClientSettings back = new Gson().fromJson(new Gson().toJson(BedwarsQol.config), ClientSettings.class);
        assertTrue(back.pingBrackets);
        assertFalse(back.cpsBrackets);
    }

    @Test
    public void hudModulesKnowThem() {
        assertEquals("FPS", HudModules.title(BedwarsHudRenderer.FPS_HUD));
        assertEquals("CPS", HudModules.title(BedwarsHudRenderer.CPS_HUD));
        assertEquals("Ping", HudModules.title(BedwarsHudRenderer.PING_HUD));
        int n = HudModules.ids().size();
        assertEquals("appended at the end, in this order", java.util.Arrays.asList(IDS),
                HudModules.ids().subList(n - 3, n));
        ClientSettings c = new ClientSettings();
        for (String id : IDS) {
            HudModuleState s = new HudModuleState(12, -7, 4, 1.25f, true, false);
            HudModules.set(c, id, s);
            assertEquals(id, s, HudModules.get(c, id));
        }
        assertEquals(12, c.fpsHudX);
        assertEquals(-7, c.cpsHudY);
        assertEquals(4, c.pingHudAnchor);
        assertTrue(c.pingEnabled);
        assertFalse(c.hudBackground(BedwarsHudRenderer.PING_HUD));
    }

    @Test
    public void hudSizeRescalesThem() {
        ClientSettings c = new ClientSettings();
        c.cpsHudScale = 2.0f;
        c.setHudSize(2);
        assertEquals(1.5f, c.fpsHudScale, 1e-4f);
        assertEquals(3.0f, c.cpsHudScale, 1e-4f);
        assertEquals(1.5f, c.pingHudScale, 1e-4f);
        c.setHudSize(0);
        assertEquals(0.75f, c.fpsHudScale, 1e-4f);
    }

    @Test
    public void sanitizeClamps() {
        ClientSettings c = new Gson().fromJson("{\"fpsHudAnchor\":99,\"cpsHudAnchor\":-9,\"pingHudScale\":50.0,"
                + "\"fpsHudScale\":0.1}", ClientSettings.class);
        c.sanitize();
        assertEquals(8, c.fpsHudAnchor);
        assertEquals(HudPlacement.AUTO, c.cpsHudAnchor);
        assertEquals(c.defaultTextSizeScale(), c.pingHudScale, 0f);
        assertEquals(c.defaultTextSizeScale(), c.fpsHudScale, 0f);
    }

    @Test
    public void moduleForHudMapsThem() {
        assertEquals("fps", SettingsBindings.moduleForHud(BedwarsHudRenderer.FPS_HUD));
        assertEquals("cps", SettingsBindings.moduleForHud(BedwarsHudRenderer.CPS_HUD));
        assertEquals("ping", SettingsBindings.moduleForHud(BedwarsHudRenderer.PING_HUD));
    }
}
