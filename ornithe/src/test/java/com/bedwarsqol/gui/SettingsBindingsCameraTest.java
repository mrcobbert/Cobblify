package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.CameraRules;
import com.bedwarsqol.gui.sheet.SheetSchema;
import com.google.gson.Gson;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Ornithe sheet lists Zoom and No Hurt Cam (Forge and Lunar do not; see SheetPlatformOnlyTest), backed by
 * {@link ClientSettings}: Zoom on and No Hurt Cam off by default, also for a config saved before they existed.
 */
public class SettingsBindingsCameraTest {

    private String savedDir;
    private ClientSettings savedConfig;

    @Before
    public void isolate() throws Exception {
        savedDir = System.getProperty("cobblify.test.configDir");
        savedConfig = BedwarsQol.config;
        File dir = Files.createTempDirectory("cobblify-camera-test").toFile();
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
    public void ornitheListsZoomAndNoHurtCam() {
        SettingsBindings values = new SettingsBindings();
        assertTrue(SheetSchema.shows(values, "zoom"));
        assertTrue(SheetSchema.shows(values, "noHurtCam"));
        // opting in does not reach other modules or rows
        assertFalse(values.hasPlatformOnly("noEsc"));
        assertFalse(values.hasPlatformOnly("incAlert"));
    }

    @Test
    public void defaultsAreZoomOnAndNoHurtCamOff() {
        ClientSettings fresh = new ClientSettings();
        assertTrue(fresh.zoomEnabled);
        assertFalse(fresh.noHurtCam);
        SettingsBindings values = new SettingsBindings();
        assertTrue(values.on("zoom"));
        assertFalse(values.on("noHurtCam"));
    }

    @Test
    public void aConfigFromBeforeTheseOptionsGetsTheDefaults() {
        ClientSettings old = new Gson().fromJson("{\"suppressEscMenu\":true}", ClientSettings.class);
        assertTrue(old.suppressEscMenu);
        assertTrue(old.zoomEnabled);
        assertFalse(old.noHurtCam);
    }

    @Test
    public void togglesWriteTheSettings() {
        SettingsBindings values = new SettingsBindings();
        values.setOn("zoom", false);
        values.setOn("noHurtCam", true);
        assertFalse(BedwarsQol.config.zoomEnabled);
        assertTrue(BedwarsQol.config.noHurtCam);
        assertFalse(values.on("zoom"));
        assertTrue(values.on("noHurtCam"));
    }

    @Test
    public void zoomKeyIsCNamedZoom() {
        assertEquals(Keyboard.KEY_C, CameraRules.DEFAULT_ZOOM_KEY);
        assertEquals("Zoom", CameraRules.ZOOM_KEY_NAME); // options.txt key_Zoom (CONTRACT)
    }
}
