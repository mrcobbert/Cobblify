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

/** The Ornithe sheet lists Toggle Sprint, on by default (also for a config saved before it existed), not toggled. */
public class SettingsBindingsSprintTest {

    private String savedDir;
    private ClientSettings savedConfig;

    @Before
    public void isolate() throws Exception {
        savedDir = System.getProperty("cobblify.test.configDir");
        savedConfig = BedwarsQol.config;
        File dir = Files.createTempDirectory("cobblify-sprint-test").toFile();
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
    public void ornitheListsToggleSprint() {
        assertTrue(SheetSchema.shows(new SettingsBindings(), "toggleSprint"));
    }

    @Test
    public void onByDefaultAndNotToggled() {
        ClientSettings fresh = new ClientSettings();
        assertTrue(fresh.toggleSprintEnabled);
        assertFalse(fresh.toggleSprintOn);
        assertTrue(new SettingsBindings().on("toggleSprint"));
        ClientSettings old = new Gson().fromJson("{\"suppressEscMenu\":true}", ClientSettings.class);
        assertTrue(old.toggleSprintEnabled);
        assertFalse(old.toggleSprintOn);
    }

    @Test
    public void theRowWritesTheSetting() {
        SettingsBindings values = new SettingsBindings();
        values.setOn("toggleSprint", false);
        assertFalse(BedwarsQol.config.toggleSprintEnabled);
        assertFalse(values.on("toggleSprint"));
    }
}
