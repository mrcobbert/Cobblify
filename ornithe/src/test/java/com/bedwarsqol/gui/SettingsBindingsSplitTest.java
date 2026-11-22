package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.gui.sheet.SheetSchema;
import com.google.gson.Gson;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Ornithe sheet lists Split and its Crosshair Cue row, both on by default (also for a config saved before they
 * existed), and each row writes its setting.
 */
public class SettingsBindingsSplitTest {

    private String savedDir;
    private ClientSettings savedConfig;

    @Before
    public void isolate() throws Exception {
        savedDir = System.getProperty("cobblify.test.configDir");
        savedConfig = BedwarsQol.config;
        File dir = Files.createTempDirectory("cobblify-split-test").toFile();
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
    public void ornitheListsSplit() {
        assertTrue(new SettingsBindings().has("split"));
        assertTrue(SheetSchema.shows(new SettingsBindings(), "split"));
        assertTrue(new SettingsBindings().has("splitHud"));
        assertTrue(SheetSchema.shows(new SettingsBindings(), "splitHud"));
    }

    @Test
    public void onByDefault() {
        assertTrue(new ClientSettings().splitEnabled);
        assertTrue(new SettingsBindings().on("split"));
        ClientSettings old = new Gson().fromJson("{\"suppressEscMenu\":true}", ClientSettings.class);
        assertTrue(old.splitEnabled);
    }

    @Test
    public void crosshairCueOnByDefault() {
        assertTrue(new ClientSettings().splitHudCue);
        assertTrue(new SettingsBindings().on("splitHud"));
        ClientSettings old = new Gson().fromJson("{\"suppressEscMenu\":true,\"splitEnabled\":true}", ClientSettings.class);
        assertTrue(old.splitHudCue);
    }

    @Test
    public void theCrosshairCueRowWritesTheSetting() {
        SettingsBindings values = new SettingsBindings();
        values.setOn("splitHud", false);
        assertFalse(BedwarsQol.config.splitHudCue);
        assertFalse(values.on("splitHud"));
        assertTrue("the module switch is separate", BedwarsQol.config.splitEnabled);
        values.setOn("splitHud", true);
        assertTrue(BedwarsQol.config.splitHudCue);
        assertTrue(values.on("splitHud"));
    }

    @Test
    public void theRowWritesTheSetting() {
        SettingsBindings values = new SettingsBindings();
        values.setOn("split", false);
        assertFalse(BedwarsQol.config.splitEnabled);
        assertFalse(values.on("split"));
        values.setOn("split", true);
        assertTrue(BedwarsQol.config.splitEnabled);
        assertTrue(values.on("split"));
    }
}
