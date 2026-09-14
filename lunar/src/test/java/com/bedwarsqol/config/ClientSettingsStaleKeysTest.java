package com.bedwarsqol.config;

import com.bedwarsqol.stats.BackendTarget;
import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the Gson-driven compatibility path for configs written by builds that still had the anticheat
 * module, the Urchin fusion toggle, or Chat Heads: unknown members are ignored on load, surviving settings are honored,
 * and the stale keys drop on the next save.
 */
public class ClientSettingsStaleKeysTest {

    private static final Gson GSON = new Gson();

    private static final String ANTICHEAT_JSON = "{\"anticheat\":true,\"acAntiKb\":false,"
            + "\"acThroughWall\":true,\"acAutoblock\":false,\"acEating\":true,\"acNoSlow\":false,"
            + "\"urchinAcFusion\":true,\"chatPlayerHeads\":true,\"guiSize\":1,\"urchinTags\":true}";

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
        assertTrue("unrelated boolean setting survives", s.urchinTags);
        String out = GSON.toJson(s);
        assertFalse("anticheat key is gone", out.contains("anticheat"));
        assertFalse("per-check keys are gone", out.contains("acAntiKb"));
        assertFalse("fusion key is gone", out.contains("urchinAcFusion"));
        assertFalse("the removed Chat Heads key is gone", out.contains("chatPlayerHeads"));
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
}
