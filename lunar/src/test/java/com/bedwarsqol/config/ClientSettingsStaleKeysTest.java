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
