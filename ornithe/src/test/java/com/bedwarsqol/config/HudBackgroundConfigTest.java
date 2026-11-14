package com.bedwarsqol.config;

import com.bedwarsqol.hud.HudModules;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The per-item Background store in {@link ClientSettings#hudBackgrounds}: every item is On unless switched Off, a
 * config saved before the setting existed loads with everything On (Gson keeps the initialiser), and an Off survives a
 * save and load. Only Off entries are written.
 */
public class HudBackgroundConfigTest {

    private static final Gson GSON = new Gson();

    /** A release/0.19.0 config: module fields, the 0.16.3 Background key, no hudBackgrounds. */
    private static final String OLD_JSON = "{\"settingsVersion\":5,\"potionStatusEnabled\":true,\"potionHudX\":12,"
            + "\"potionHudY\":40,\"potionHudAnchor\":0,\"potionHudScale\":1.0,\"armorTypeEnabled\":true,"
            + "\"inventoryHudEnabled\":true,\"genTimersEnabled\":true,\"keystrokesEnabled\":true,"
            + "\"sessionStatsEnabled\":true,\"heightLimitEnabled\":true,\"potionBackgroundEnabled\":false,"
            + "\"guiSize\":1}";

    private String savedDir;
    private File dir;

    @Before
    public void isolate() throws Exception {
        savedDir = System.getProperty("cobblify.test.configDir");
        dir = Files.createTempDirectory("cobblify-hud-background-test").toFile();
        System.setProperty("cobblify.test.configDir", dir.getAbsolutePath());
    }

    @After
    public void restore() {
        if (savedDir == null) System.clearProperty("cobblify.test.configDir");
        else System.setProperty("cobblify.test.configDir", savedDir);
    }

    @Test
    public void freshConfigHasEveryBackgroundOn() {
        ClientSettings c = new ClientSettings();
        assertNotNull(c.hudBackgrounds);
        assertTrue(c.hudBackgrounds.isEmpty());
        for (String id : HudModules.ids()) assertTrue(id, c.hudBackground(id));
        c.sanitize();
        for (String id : HudModules.ids()) assertTrue(id, c.hudBackground(id));
    }

    @Test
    public void configSavedBeforeTheSettingLoadsAllOn() {
        ClientSettings c = GSON.fromJson(OLD_JSON, ClientSettings.class);
        c.sanitize();
        for (String id : HudModules.ids()) assertTrue(id, c.hudBackground(id));
        assertTrue("the legacy potionBackgroundEnabled:false does not reach the new switch", c.hudBackground("potion"));
        assertEquals("unrelated settings survive", 12, c.potionHudX);
        assertEquals(1, c.guiSize);
        assertFalse("the legacy key stays dropped", GSON.toJson(c).contains("potionBackgroundEnabled"));
    }

    @Test
    public void offSurvivesGsonRoundTripAndSettingsManager() throws Exception {
        ClientSettings c = new ClientSettings();
        c.setHudBackground("potion", false);
        c.setHudBackground("scoreboard", false);

        ClientSettings back = GSON.fromJson(GSON.toJson(c), ClientSettings.class);
        back.sanitize();
        assertFalse(back.hudBackground("potion"));
        assertFalse(back.hudBackground("scoreboard"));
        assertTrue(back.hudBackground("armor"));

        SettingsManager.save(c);
        File file = new File(dir, "cobblify.json");
        assertTrue(file.isFile());
        ClientSettings loaded = SettingsManager.load();
        assertFalse(loaded.hudBackground("potion"));
        assertFalse(loaded.hudBackground("scoreboard"));
        assertTrue(loaded.hudBackground("armor"));

        loaded.setHudBackground("potion", true);
        SettingsManager.save(loaded);
        assertTrue(SettingsManager.load().hudBackground("potion"));
    }

    @Test
    public void nullMapRepairedUnknownOffIdKept() {
        ClientSettings nulled = GSON.fromJson("{\"hudBackgrounds\":null}", ClientSettings.class);
        nulled.sanitize();
        assertNotNull(nulled.hudBackgrounds);
        assertTrue(nulled.hudBackground("potion"));
        nulled.setHudBackground("potion", false);
        assertFalse(nulled.hudBackground("potion"));

        ClientSettings unknown = GSON.fromJson(
                "{\"hudBackgrounds\":{\"someFutureItem\":false,\"armor\":true,\"potion\":false}}", ClientSettings.class);
        unknown.sanitize();
        assertFalse("an id a newer build wrote is kept", unknown.hudBackground("someFutureItem"));
        assertFalse(unknown.hudBackground("potion"));
        assertTrue(unknown.hudBackground("armor"));
        assertTrue(unknown.hudBackgrounds instanceof LinkedHashMap);
        assertEquals(2, unknown.hudBackgrounds.size());

        // a setter on a config whose map was nulled before sanitize still works
        ClientSettings raw = GSON.fromJson("{\"hudBackgrounds\":null}", ClientSettings.class);
        raw.setHudBackground("armor", false);
        assertFalse(raw.hudBackground("armor"));
    }

    @Test
    public void onlyOffEntriesAreWritten() throws Exception {
        ClientSettings c = new ClientSettings();
        c.setHudBackground("armor", true);
        c.setHudBackground("inventory", false);
        c.sanitize();
        String json = GSON.toJson(c);
        assertTrue(json, json.contains("\"hudBackgrounds\":{\"inventory\":false}"));

        SettingsManager.save(c);
        String written = new String(Files.readAllBytes(new File(dir, "cobblify.json").toPath()), StandardCharsets.UTF_8);
        ClientSettings reparsed = new GsonBuilder().create().fromJson(written, ClientSettings.class);
        assertEquals(1, reparsed.hudBackgrounds.size());
        assertEquals(Boolean.FALSE, reparsed.hudBackgrounds.get("inventory"));
        assertFalse(written.contains("\"armor\": true"));
    }
}
