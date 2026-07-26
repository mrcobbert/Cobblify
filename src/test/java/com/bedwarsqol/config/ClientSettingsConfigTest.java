package com.bedwarsqol.config;

import com.bedwarsqol.gui.render.GuiTheme;
import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the {@code moduleTheme} -> {@code guiAccent} config migration and the pure token normalization
 * in {@link GuiTheme}. The migration is deliberately Gson-driven: an old JSON with {@code moduleTheme}
 * loads (unknown members are ignored), defaults {@code guiAccent} to orange, and drops the legacy key on
 * the next save.
 */
public class ClientSettingsConfigTest {

    private static final Gson GSON = new Gson();

    // Legacy config: a removed moduleTheme index plus a couple of real, unrelated settings.
    private static final String LEGACY_JSON = "{\"moduleTheme\":7,\"guiSize\":1,\"autoGg\":true}";

    @Test
    public void legacyModuleThemeMigratesToOrangeAndKeepsUnrelated() {
        ClientSettings s = GSON.fromJson(LEGACY_JSON, ClientSettings.class);
        s.sanitize();
        assertEquals("legacy moduleTheme collapses to the default accent", "orange", s.guiAccent);
        assertEquals("unrelated int setting survives", 1, s.guiSize);
        assertTrue("unrelated boolean setting survives", s.autoGg);
    }

    @Test
    public void serializeWritesGuiAccentAndDropsLegacyKey() {
        ClientSettings s = GSON.fromJson(LEGACY_JSON, ClientSettings.class);
        s.sanitize();
        String out = GSON.toJson(s);
        assertTrue("guiAccent is now serialized", out.contains("guiAccent"));
        assertFalse("the legacy moduleTheme key is gone", out.contains("moduleTheme"));
    }

    // Config from a build that still had the anticheat module + Urchin fusion toggle.
    private static final String ANTICHEAT_JSON = "{\"anticheat\":true,\"acAntiKb\":false,"
            + "\"acThroughWall\":true,\"acAutoblock\":false,\"acEating\":true,\"acNoSlow\":false,"
            + "\"urchinAcFusion\":true,\"guiSize\":1,\"autoGg\":true}";

    @Test
    public void staleAnticheatKeysAreIgnoredAndDropOnSave() {
        ClientSettings s = GSON.fromJson(ANTICHEAT_JSON, ClientSettings.class);
        s.sanitize();
        assertEquals("unrelated int setting survives", 1, s.guiSize);
        assertTrue("unrelated boolean setting survives", s.autoGg);
        String out = GSON.toJson(s);
        assertFalse("anticheat key is gone", out.contains("anticheat"));
        assertFalse("per-check keys are gone", out.contains("acAntiKb"));
        assertFalse("fusion key is gone", out.contains("urchinAcFusion"));
    }

    @Test
    public void normalizeTokenDefaultsAndPreserves() {
        assertEquals("orange", GuiTheme.normalizeToken(null));
        assertEquals("orange", GuiTheme.normalizeToken(""));
        assertEquals("orange", GuiTheme.normalizeToken("ORANGE"));
        assertEquals("blue", GuiTheme.normalizeToken("  Blue  "));
        assertEquals("orange", GuiTheme.normalizeToken("purple"));
        assertEquals("red", GuiTheme.normalizeToken("red"));
    }

    @Test
    public void fromTokenResolvesAccents() {
        assertEquals(GuiTheme.Accent.ORANGE, GuiTheme.fromToken(null));
        assertEquals(GuiTheme.Accent.ORANGE, GuiTheme.fromToken(""));
        assertEquals(GuiTheme.Accent.ORANGE, GuiTheme.fromToken("purple"));
        assertEquals(GuiTheme.Accent.ORANGE, GuiTheme.fromToken("ORANGE"));
        assertEquals(GuiTheme.Accent.RED, GuiTheme.fromToken("RED"));
        assertEquals(GuiTheme.Accent.BLUE, GuiTheme.fromToken("blue"));
    }
}
