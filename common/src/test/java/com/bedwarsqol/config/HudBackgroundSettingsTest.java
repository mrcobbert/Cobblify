package com.bedwarsqol.config;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The per-HUD Background toggles are gone (Potion, Inventory, Gen Timers): only Session Stats and
 * Height Limit draw a panel, and always. Gen Timers show everywhere unless In Game Only is set.
 */
public class HudBackgroundSettingsTest {

    private static final Gson GSON = new Gson();

    @Test
    public void genTimersShowEverywhereByDefault() {
        assertFalse(new ClientSettings().genTimersInGameOnly);
    }

    @Test
    public void genTimersInGameOnlySurvivesASave() {
        ClientSettings s = new ClientSettings();
        s.genTimersInGameOnly = true;
        ClientSettings back = GSON.fromJson(GSON.toJson(s), ClientSettings.class);
        back.sanitize();
        assertTrue(back.genTimersInGameOnly);
    }

    @Test
    public void savedBackgroundTogglesAreIgnoredAndDropOnSave() {
        ClientSettings s = GSON.fromJson("{\"potionBackgroundEnabled\":true,\"inventoryBackgroundEnabled\":true,"
                + "\"genTimersBackgroundEnabled\":true,\"guiSize\":1}", ClientSettings.class);
        s.sanitize();
        assertEquals("unrelated setting survives", 1, s.guiSize);
        String out = GSON.toJson(s);
        for (String gone : new String[]{"potionBackgroundEnabled", "inventoryBackgroundEnabled",
                "genTimersBackgroundEnabled"}) {
            assertFalse(gone + " must not be saved", out.contains("\"" + gone + "\""));
        }
    }
}
