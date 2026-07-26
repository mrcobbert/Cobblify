package com.bedwarsqol.config;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the Gson-driven compatibility path for configs written by builds that still had the anticheat
 * module + Urchin fusion toggle: unknown members are ignored on load, surviving settings are honored,
 * and the stale keys drop on the next save.
 */
public class ClientSettingsStaleKeysTest {

    private static final Gson GSON = new Gson();

    private static final String ANTICHEAT_JSON = "{\"anticheat\":true,\"acAntiKb\":false,"
            + "\"acThroughWall\":true,\"acAutoblock\":false,\"acEating\":true,\"acNoSlow\":false,"
            + "\"urchinAcFusion\":true,\"guiSize\":1,\"urchinTags\":true}";

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
    }
}
