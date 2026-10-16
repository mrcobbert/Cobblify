package com.bedwarsqol.config;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Bed Alert and BG Alert default on, for a new install and for a saved file that predates them. */
public class TeamCallSettingsTest {

    private static final Gson GSON = new Gson();

    @Test
    public void freshDefaultsHaveEveryCallOn() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        assertTrue(s.chatNotifyInc);
        assertTrue(s.chatNotifyBed);
        assertTrue(s.chatNotifyBg);
    }

    @Test
    public void aFileWithoutTheNewKeysLoadsThemOn() {
        ClientSettings s = GSON.fromJson("{\"chatNotifyInc\":false,\"guiSize\":1}", ClientSettings.class);
        s.sanitize();
        assertTrue(s.chatNotifyBed);
        assertTrue(s.chatNotifyBg);
        assertFalse("the saved Inc choice survives", s.chatNotifyInc);
    }

    @Test
    public void aSavedChoiceSurvivesTheRoundTrip() {
        ClientSettings s = new ClientSettings();
        s.chatNotifyBed = false;
        s.chatNotifyBg = false;
        ClientSettings back = GSON.fromJson(GSON.toJson(s), ClientSettings.class);
        back.sanitize();
        assertFalse(back.chatNotifyBed);
        assertFalse(back.chatNotifyBg);
    }
}
