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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The Ornithe sheet lists a Background row first in every HUD module; it reads and writes that item's switch through
 * {@link HudModules}, On by default (also for a config saved before the setting existed), and every write saves.
 */
public class SettingsBindingsBackgroundTest {

    private String savedDir;
    private ClientSettings savedConfig;

    @Before
    public void isolate() throws Exception {
        savedDir = System.getProperty("cobblify.test.configDir");
        savedConfig = BedwarsQol.config;
        File dir = Files.createTempDirectory("cobblify-background-rows-test").toFile();
        System.setProperty("cobblify.test.configDir", dir.getAbsolutePath());
        BedwarsQol.config = new ClientSettings();
    }

    @After
    public void restore() {
        if (savedDir == null) System.clearProperty("cobblify.test.configDir");
        else System.setProperty("cobblify.test.configDir", savedDir);
        BedwarsQol.config = savedConfig;
    }

    /** The Background row of the sheet module behind HUD module {@code hudId}. */
    private static SheetSchema.Row backgroundRowFor(String hudId) {
        String module = SettingsBindings.moduleForHud(hudId);
        assertNotNull(hudId, module);
        SheetSchema.Row r = SheetSchema.module(module).rows.get(0);
        assertNotNull(hudId + " has a Background row first", r.hudItem());
        return r;
    }

    @Test
    public void everyHudModuleListsABackgroundRow() {
        SettingsBindings values = new SettingsBindings();
        for (String id : HudModules.ids()) {
            SheetSchema.Row r = backgroundRowFor(id);
            assertEquals("Background", r.name);
            assertTrue(id, SheetSchema.shows(values, r.id));
            assertTrue(id, values.hasPlatformOnly(r.id));
            // the row switches this module
            values.setOn(r.id, false);
            assertFalse(id, HudModules.background(BedwarsQol.config, id));
            values.setOn(r.id, true);
            assertTrue(id, HudModules.background(BedwarsQol.config, id));
        }
        // opting in to the Background rows does not reach the modules or other rows
        assertFalse(values.hasPlatformOnly("potion"));
        assertFalse(values.hasPlatformOnly("potionInGame"));
    }

    @Test
    public void rowsAreOnByDefaultAlsoForAnOldConfig() {
        SettingsBindings values = new SettingsBindings();
        for (String id : HudModules.ids()) assertTrue(id, values.on(backgroundRowFor(id).id));
        ClientSettings old = new Gson().fromJson("{\"suppressEscMenu\":true,\"potionStatusEnabled\":true}",
                ClientSettings.class);
        old.sanitize();
        BedwarsQol.config = old;
        for (String id : HudModules.ids()) assertTrue(id, values.on(backgroundRowFor(id).id));
    }

    @Test
    public void rowWritesTheItemAndSaves() {
        SettingsBindings values = new SettingsBindings();
        values.setOn("potionBg", false);
        assertFalse(BedwarsQol.config.hudBackground(BedwarsHudRenderer.POTION_HUD));
        assertFalse(values.on("potionBg"));
        assertTrue("only that item", values.on("armorBg"));
        assertFalse("the row saved", SettingsManager.load().hudBackground(BedwarsHudRenderer.POTION_HUD));
        values.setOn("potionBg", true);
        assertTrue(values.on("potionBg"));
        assertTrue(SettingsManager.load().hudBackground(BedwarsHudRenderer.POTION_HUD));
    }

    @Test
    public void genTimersRowSetsBothTimers() {
        SettingsBindings values = new SettingsBindings();
        values.setOn("genTimersBg", false);
        assertFalse(HudModules.background(BedwarsQol.config, BedwarsHudRenderer.DIAMOND_TIMER_HUD));
        assertFalse(HudModules.background(BedwarsQol.config, BedwarsHudRenderer.EMERALD_TIMER_HUD));
        values.setOn("genTimersBg", true);
        assertTrue(HudModules.background(BedwarsQol.config, BedwarsHudRenderer.DIAMOND_TIMER_HUD));
        assertTrue(HudModules.background(BedwarsQol.config, BedwarsHudRenderer.EMERALD_TIMER_HUD));
    }

    @Test
    public void everyBackgroundRowNamesABackgroundCapableItem() {
        int rows = 0, modules = 0;
        for (SheetSchema.Category c : SheetSchema.CATS) {
            for (SheetSchema.Module m : c.modules) {
                // the module-level form (scoreboard, tab list, chat): a leaf module whose toggle is the Background
                if (m.hudItem() != null) {
                    modules++;
                    assertTrue(m.id + " -> " + m.hudItem(), HudModules.backgroundIds().contains(m.hudItem()));
                    assertTrue(m.id + " has no rows", m.rows.isEmpty());
                }
                for (SheetSchema.Row r : m.rows) {
                    if (r.hudItem() == null) continue;
                    rows++;
                    assertTrue(r.id + " -> " + r.hudItem(), HudModules.backgroundIds().contains(r.hudItem()));
                    assertEquals(r.id, SheetSchema.Kind.TOGGLE, r.kind);
                }
            }
        }
        assertTrue(rows >= 7);
        assertEquals(3, modules);
    }
}
