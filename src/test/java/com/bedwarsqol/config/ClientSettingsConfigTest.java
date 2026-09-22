package com.bedwarsqol.config;

import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.stats.BackendTarget;
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

    // settingsVersion v1: right-anchored HUDs shipped at X = +5 (5 GUI px off the right edge).

    private static final String V0_SHIPPED_JSON = "{\"sessionStatsHudX\":5,\"sessionStatsHudAnchor\":2,"
            + "\"diamondTimerHudX\":5,\"diamondTimerHudAnchor\":2,\"emeraldTimerHudX\":5,\"emeraldTimerHudAnchor\":2,"
            + "\"sessionStatsBackgroundEnabled\":true,\"guiSize\":1}";

    @Test
    public void shippedRightAnchoredDefaultsMigrateOnScreenOnce() {
        ClientSettings s = GSON.fromJson(V0_SHIPPED_JSON, ClientSettings.class);
        assertEquals("a pre-stamp file reads version 0", 0, s.settingsVersion);
        s.sanitize();
        assertEquals(-5, s.sessionStatsHudX);
        assertEquals(-5, s.diamondTimerHudX);
        assertEquals(-5, s.emeraldTimerHudX);
        assertEquals(ClientSettings.CURRENT_SETTINGS_VERSION, s.settingsVersion);
        assertEquals("unrelated setting survives", 1, s.guiSize);
        String out = GSON.toJson(s);
        assertTrue("the stamp is written", out.contains("\"settingsVersion\":1"));
        assertFalse("the removed Background key is gone", out.contains("sessionStatsBackgroundEnabled"));
    }

    @Test
    public void migrationKeepsBoxesTheUserMoved() {
        ClientSettings moved = GSON.fromJson("{\"sessionStatsHudX\":40,\"sessionStatsHudAnchor\":2}", ClientSettings.class);
        moved.sanitize();
        assertEquals(40, moved.sessionStatsHudX);

        ClientSettings otherAnchor = GSON.fromJson("{\"sessionStatsHudX\":5,\"sessionStatsHudAnchor\":0}", ClientSettings.class);
        otherAnchor.sanitize();
        assertEquals("a left-anchored +5 is a real position", 5, otherAnchor.sessionStatsHudX);

        ClientSettings stamped = GSON.fromJson("{\"settingsVersion\":1,\"sessionStatsHudX\":5,\"sessionStatsHudAnchor\":2}", ClientSettings.class);
        stamped.sanitize();
        assertEquals("an already-migrated file is never touched again", 5, stamped.sessionStatsHudX);
    }

    @Test
    public void freshDefaultsSitOnScreenAndAreStamped() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        assertEquals(-5, s.sessionStatsHudX);
        assertEquals(-5, s.diamondTimerHudX);
        assertEquals(-5, s.emeraldTimerHudX);
        assertEquals(ClientSettings.CURRENT_SETTINGS_VERSION, s.settingsVersion);
    }

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

    // Backend resolution (backendTarget()): the test classpath carries a canary
    // cobblify-backend.properties (common/src/test/resources), so the "baked" pair is observable
    // here without a real build-time injection. See BackendDefaultsTest for the parsing rules.
    private static final String BAKED_URL = "https://baked-canary.test";
    private static final String BAKED_TOKEN = "BAKED_CANARY_TOKEN_00000000";

    @Test
    public void emptyFieldsResolveTheBakedPair() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        BackendTarget t = s.backendTarget();
        assertEquals("a config predating the change picks up the baked URL", BAKED_URL, t.url);
        assertEquals(BAKED_TOKEN, t.token);
        assertTrue(t.isConfigured());
    }

    @Test
    public void customUrlNeverPairsWithTheBakedToken() {
        ClientSettings s = new ClientSettings();
        s.statsBackendUrl = "https://my-own.example";
        BackendTarget t = s.backendTarget();
        assertEquals("https://my-own.example", t.url);
        assertEquals("no user token set -> no token at all, never the baked one", "", t.token);
        s.statsBackendToken = "user-token";
        t = s.backendTarget();
        assertEquals("https://my-own.example", t.url);
        assertEquals("user-token", t.token);
    }

    @Test
    public void clearingTheCustomUrlFallsBackToBaked() {
        ClientSettings s = new ClientSettings();
        s.statsBackendUrl = "https://my-own.example";
        s.statsBackendToken = "user-token";
        s.statsBackendUrl = ClientSettings.DEFAULT_STATS_BACKEND_URL; // what `statsurl clear` does
        BackendTarget t = s.backendTarget();
        assertEquals(BAKED_URL, t.url);
        assertEquals(BAKED_TOKEN, t.token);
    }

    @Test
    public void savedJsonNeverContainsBakedValues() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        s.backendTarget(); // resolution must not write anything into the persisted fields
        String out = GSON.toJson(s);
        assertFalse("baked URL never persisted", out.contains(BAKED_URL));
        assertFalse("baked token never persisted", out.contains(BAKED_TOKEN));
        // The persisted key set is unchanged: the user-override fields still serialize, empty.
        assertTrue(out.contains("\"statsBackendUrl\":\"\""));
        assertTrue(out.contains("\"statsBackendToken\":\"\""));
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

    /** {@code /bw mode} persists under its original JSON key and survives a save/load; garbage sanitises to auto. */
    @Test
    public void statsModeRoundTripsAndSanitises() {
        ClientSettings s = new ClientSettings();
        s.chatStatsMode = "fours";
        s.sanitize();
        String json = GSON.toJson(s);
        assertTrue(json.contains("\"chatStatsMode\":\"fours\""));
        ClientSettings back = GSON.fromJson(json, ClientSettings.class);
        back.sanitize();
        assertEquals("fours", back.chatStatsMode);

        ClientSettings hand = GSON.fromJson("{\"chatStatsMode\":\"4s\"}", ClientSettings.class);
        hand.sanitize();
        assertEquals("auto", hand.chatStatsMode); // a typed synonym is not a stored token
        ClientSettings absent = GSON.fromJson("{}", ClientSettings.class);
        absent.sanitize();
        assertEquals("auto", absent.chatStatsMode);
    }

    /**
     * Interim code review, I2: the https-only rule guarded only {@code /cobblify statsurl}. A URL
     * saved by an older build was loaded verbatim, so an {@code http://} one kept carrying the
     * backend token in clear on every request, which is the harm the fix was for.
     */
    @Test
    public void aStoredInsecureBackendUrlIsDroppedOnLoad() {
        for (String stored : new String[] {
                "http://my-box:8787", "my-worker.workers.dev", "https://user:pass@x", "ht tp://bad" }) {
            ClientSettings s = new ClientSettings();
            s.statsBackendUrl = stored;
            s.statsBackendToken = "user-token";
            s.sanitize();
            assertEquals("kept an unusable URL: " + stored, "", s.statsBackendUrl);
            BackendTarget t = s.backendTarget();
            assertFalse("the user token must not ride on " + stored, "user-token".equals(t.token));
        }
    }

    @Test
    public void aStoredHttpsBackendUrlSurvivesLoad() {
        ClientSettings s = new ClientSettings();
        s.statsBackendUrl = "https://my-own.example/";
        s.statsBackendToken = "user-token";
        s.sanitize();
        assertEquals("https://my-own.example", s.statsBackendUrl);
        assertEquals("user-token", s.backendTarget().token);
    }
}
