package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.SessionHoldKey;
import com.bedwarsqol.gui.sheet.SheetSchema;
import com.google.gson.Gson;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Rewind on the Ornithe sheet: the last Hypixel module with exactly Record Games, Marker Key, Clean View Key and Open
 * Folder (Forge and Lunar do not list it; see SheetPlatformOnlyTest). Rewind and Record Games are on and both keys
 * unbound by default, also for a config saved before them; each key row stores its own binding and leaves the
 * Session Stats key alone.
 */
public class SettingsBindingsRewindTest {

    private String savedDir;
    private ClientSettings savedConfig;

    @Before
    public void isolate() throws Exception {
        savedDir = System.getProperty("cobblify.test.configDir");
        savedConfig = BedwarsQol.config;
        File dir = Files.createTempDirectory("cobblify-rewind-test").toFile();
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
    public void listedOnOrnitheAsTheLastHypixelModuleWithItsFourRowsInOrder() {
        SettingsBindings values = new SettingsBindings();
        List<SheetSchema.Module> hypixel = SheetSchema.category("hypixel").modules;
        List<String> shown = new ArrayList<String>();
        for (SheetSchema.Module m : hypixel) if (SheetSchema.shows(values, m.id)) shown.add(m.id);
        assertEquals("rewind", shown.get(shown.size() - 1));
        assertTrue(SheetSchema.shows(values, "rewind"));

        SheetSchema.Module m = SheetSchema.module("rewind");
        assertEquals("Rewind", m.name);
        String[][] rows = {
                {"rewindRecord", "Record Games", "TOGGLE"},
                {"rewindMarkerKey", "Marker Key", "KEY"},
                {"rewindCleanKey", "Clean View Key", "KEY"},
                {"rewindOpenFolder", "Open Folder", "ACTION"}};
        assertEquals(rows.length, m.rows.size());
        for (int i = 0; i < rows.length; i++) {
            SheetSchema.Row r = m.rows.get(i);
            assertEquals(rows[i][0], r.id);
            assertEquals(rows[i][1], r.name);
            assertEquals(SheetSchema.Kind.valueOf(rows[i][2]), r.kind);
            assertTrue(r.id, SheetSchema.shows(values, r.id));
        }
        assertEquals("Open", SheetSchema.row("rewindOpenFolder").label());
    }

    @Test
    public void onWithKeysUnboundByDefaultAlsoForAConfigSavedBeforeThem() {
        ClientSettings empty = new Gson().fromJson("{}", ClientSettings.class);
        ClientSettings old = new Gson().fromJson("{\"autoGg\":false,\"sessionStatsKeyCode\":50}", ClientSettings.class);
        old.sanitize();
        for (ClientSettings c : new ClientSettings[]{new ClientSettings(), empty, old}) {
            assertTrue(c.rewind);
            assertTrue(c.rewindRecordGames);
            assertEquals(0, c.rewindMarkerKey);
            assertEquals(0, c.rewindCleanViewKey);
        }
        assertEquals("the old file's own key survives", 50, old.sessionStatsKeyCode);
        SettingsBindings values = new SettingsBindings();
        assertTrue(values.on("rewind"));
        assertTrue(values.on("rewindRecord"));
        String none = SessionHoldKey.displayName(0, null);
        assertEquals(none, values.keyLabel("rewindMarkerKey"));
        assertEquals(none, values.keyLabel("rewindCleanKey"));
    }

    @Test
    public void switchesReadAndWriteTheirSettings() {
        SettingsBindings values = new SettingsBindings();
        values.setOn("rewind", false);
        assertFalse(BedwarsQol.config.rewind);
        assertFalse(values.on("rewind"));
        assertTrue("Record Games is its own setting", BedwarsQol.config.rewindRecordGames);
        values.setOn("rewindRecord", false);
        assertFalse(BedwarsQol.config.rewindRecordGames);
        assertFalse(values.on("rewindRecord"));
        values.setOn("rewind", true);
        values.setOn("rewindRecord", true);
        assertTrue(BedwarsQol.config.rewind);
        assertTrue(BedwarsQol.config.rewindRecordGames);
    }

    @Test
    public void eachKeyRowStoresItsOwnBindingAndLeavesTheSessionKeyAlone() {
        SettingsBindings values = new SettingsBindings();
        values.setKey("sessionKey", Keyboard.KEY_H);
        values.setKey("rewindMarkerKey", Keyboard.KEY_M);
        values.setKey("rewindCleanKey", SessionHoldKey.bindingForMouse(3));
        ClientSettings c = BedwarsQol.config;
        assertEquals(Keyboard.KEY_H, c.sessionStatsKeyCode);
        assertEquals(Keyboard.KEY_M, c.rewindMarkerKey);
        assertEquals(SessionHoldKey.bindingForMouse(3), c.rewindCleanViewKey);

        assertEquals(SessionHoldKey.displayName(Keyboard.KEY_H, Keyboard.getKeyName(Keyboard.KEY_H)), values.keyLabel("sessionKey"));
        assertEquals(SessionHoldKey.displayName(Keyboard.KEY_M, Keyboard.getKeyName(Keyboard.KEY_M)), values.keyLabel("rewindMarkerKey"));
        assertEquals(SessionHoldKey.displayName(SessionHoldKey.bindingForMouse(3), null), values.keyLabel("rewindCleanKey"));

        values.setKey("rewindMarkerKey", 0);
        assertEquals(0, c.rewindMarkerKey);
        assertEquals("clearing the Marker Key leaves the Session key", Keyboard.KEY_H, c.sessionStatsKeyCode);
        assertEquals(SessionHoldKey.bindingForMouse(3), c.rewindCleanViewKey);
        values.setKey("sessionKey", 0);
        assertEquals(0, c.sessionStatsKeyCode);
        assertEquals("clearing the Session key leaves Clean View", SessionHoldKey.bindingForMouse(3), c.rewindCleanViewKey);

        ClientSettings back = new Gson().fromJson(new Gson().toJson(c), ClientSettings.class);
        back.sanitize();
        assertEquals(0, back.rewindMarkerKey);
        assertEquals(SessionHoldKey.bindingForMouse(3), back.rewindCleanViewKey);
        assertEquals(0, back.sessionStatsKeyCode);
    }

    @Test
    public void anUnreadableStoredKeyResetsToUnbound() {
        ClientSettings bad = new Gson().fromJson("{\"rewindMarkerKey\":9999,\"rewindCleanViewKey\":-9999}", ClientSettings.class);
        bad.sanitize();
        assertEquals(0, bad.rewindMarkerKey);
        assertEquals(0, bad.rewindCleanViewKey);
    }
}
