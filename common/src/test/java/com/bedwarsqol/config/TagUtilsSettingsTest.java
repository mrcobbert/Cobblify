package com.bedwarsqol.config;

import com.bedwarsqol.stats.UrchinTag;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tag Utils (settings v2) replaced the Urchin Tags and Seraph Tags modules and the two Queue alert
 * toggles: one master, a source sub-option and shared badge/alert sub-options, and a saved v1 file
 * carries the player's choices over once. Seraph closed its API in October 2026, so Urchin is the
 * only source now; a saved file's Seraph keys still feed the v1 carry-over and then drop on save,
 * and nothing else in the file changes.
 */
public class TagUtilsSettingsTest {

    private static final Gson GSON = new Gson();

    private static ClientSettings load(String json) {
        ClientSettings s = GSON.fromJson(json, ClientSettings.class);
        s.sanitize();
        return s;
    }

    private static JsonObject tree(ClientSettings s) {
        return new JsonParser().parse(GSON.toJson(s)).getAsJsonObject();
    }

    @Test
    public void freshDefaultsHaveEverythingOn() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        assertTrue(s.tagUtils);
        assertTrue(s.urchinOn());
        assertTrue(s.tagBadgeTab);
        assertTrue(s.tagBadgeNametag);
        assertTrue(s.tagChatAlert);
        assertTrue(s.tagAlertSound);
        assertFalse("a fresh file saves no Seraph key", GSON.toJson(s).toLowerCase().contains("seraph"));
    }

    @Test
    public void tagUtilsOffSwitchesUrchinOff() {
        ClientSettings s = new ClientSettings();
        s.tagUtils = false;
        assertFalse(s.urchinOn());
        s.playerStats = false;
        assertFalse(UrchinTag.needsTabIdentity(s));
        assertFalse(UrchinTag.needsNametagIdentity(s));
    }

    @Test
    public void identityFollowsTheSharedSubOptions() {
        ClientSettings s = new ClientSettings();
        s.playerStats = false;
        s.tagBadgeTab = false;
        s.tagChatAlert = false;
        assertFalse("no tab badge and no alert: no tab identity", UrchinTag.needsTabIdentity(s));
        assertTrue("nametag badge still wants identity", UrchinTag.needsNametagIdentity(s));
        s.urchinTags = false;
        assertFalse("the source switched off wants nothing", UrchinTag.needsNametagIdentity(s));
    }

    @Test
    public void v1WithBothSourcesOnAndUntouchedKeepsEverythingOn() {
        ClientSettings s = load("{\"settingsVersion\":1,\"urchinTags\":true,\"seraphTags\":true}");
        assertTrue(s.tagUtils);
        assertTrue(s.urchinTags);
        assertTrue(s.tagBadgeTab && s.tagBadgeNametag && s.tagChatAlert && s.tagAlertSound);
    }

    @Test
    public void v1SourcesCarryOverAndSubOptionsFollowTheSourcesThatWereOn() {
        // Urchin was off, so its (default) sub-values must not switch on what the player turned off
        // for Seraph. The Seraph values still decide the carry-over, exactly as before the removal.
        ClientSettings s = load("{\"settingsVersion\":1,\"urchinTags\":false,\"seraphTags\":true,"
                + "\"urchinChatAlert\":true,\"seraphChatAlert\":false,"
                + "\"urchinBadgeTab\":true,\"seraphBadgeTab\":true,"
                + "\"urchinAlertSound\":true,\"seraphAlertSound\":false,"
                + "\"urchinBadgeNametag\":false,\"seraphBadgeNametag\":true}");
        assertTrue("Tag Utils is on while either source was on", s.tagUtils);
        assertFalse("the player's Urchin choice is kept", s.urchinTags);
        assertFalse(s.urchinOn());
        assertFalse(s.tagChatAlert);
        assertTrue(s.tagBadgeTab);
        assertFalse(s.tagAlertSound);
        assertTrue(s.tagBadgeNametag);
    }

    @Test
    public void v1WithBothSourcesOnTurnsASubOptionOnIfEitherHadIt() {
        ClientSettings s = load("{\"settingsVersion\":1,\"urchinTags\":true,\"seraphTags\":true,"
                + "\"urchinChatAlert\":false,\"seraphChatAlert\":true,"
                + "\"urchinBadgeTab\":false,\"seraphBadgeTab\":false}");
        assertTrue(s.tagChatAlert);
        assertFalse(s.tagBadgeTab);
    }

    @Test
    public void v1WithBothSourcesOffTurnsTagUtilsOffAndKeepsUrchinOff() {
        ClientSettings s = load("{\"settingsVersion\":1,\"urchinTags\":false,\"seraphTags\":false,"
                + "\"urchinBadgeTab\":false,\"seraphBadgeTab\":true,"
                + "\"urchinChatAlert\":false,\"seraphChatAlert\":false}");
        assertFalse(s.tagUtils);
        assertFalse(s.urchinTags);
        assertTrue("with no source on, either source's value counts", s.tagBadgeTab);
        assertFalse(s.tagChatAlert);
    }

    @Test
    public void v1WithoutASeraphKeyReadsSeraphAsTheOldDefaultOn() {
        ClientSettings s = load("{\"settingsVersion\":1,\"urchinTags\":false,\"seraphChatAlert\":false}");
        assertTrue("an absent seraphTags was on", s.tagUtils);
        assertFalse(s.tagChatAlert);
    }

    @Test
    public void anUnstampedFileRunsEveryUpgrade() {
        ClientSettings s = load("{\"sessionStatsHudX\":5,\"sessionStatsHudAnchor\":2,"
                + "\"urchinTags\":false,\"seraphTags\":true,\"seraphChatAlert\":false}");
        assertEquals("the v1 HUD fix still runs", -5, s.sessionStatsHudX);
        assertTrue(s.tagUtils);
        assertFalse(s.tagChatAlert);
        assertEquals(ClientSettings.CURRENT_SETTINGS_VERSION, s.settingsVersion);
    }

    @Test
    public void aLiteralNullReadsAsTheOldDefault() {
        ClientSettings s = load("{\"settingsVersion\":1,\"urchinTags\":true,\"seraphTags\":false,"
                + "\"urchinChatAlert\":null,\"urchinBadgeTab\":false}");
        assertTrue(s.tagChatAlert);
        assertFalse(s.tagBadgeTab);
    }

    @Test
    public void savingDropsEveryOldPerSourceSeraphAndQueueKey() {
        ClientSettings s = load("{\"settingsVersion\":1,\"urchinTags\":true,\"seraphTags\":true,"
                + "\"urchinBadgeTab\":false,\"seraphChatAlert\":false,\"urchinAlertSound\":true,"
                + "\"seraphBadgeNametag\":true,\"queueTagAlert\":false,\"queueNickAlert\":true}");
        String out = GSON.toJson(s);
        for (String gone : new String[]{"urchinBadgeTab", "urchinChatAlert", "urchinAlertSound",
                "urchinBadgeNametag", "seraphTags", "seraphBadgeTab", "seraphChatAlert", "seraphAlertSound",
                "seraphBadgeNametag", "queueTagAlert", "queueNickAlert"}) {
            assertFalse(gone + " must not be saved", out.contains("\"" + gone + "\""));
        }
        for (String kept : new String[]{"tagUtils", "urchinTags", "tagBadgeTab",
                "tagBadgeNametag", "tagChatAlert", "tagAlertSound"}) {
            assertTrue(kept + " must be saved", out.contains("\"" + kept + "\""));
        }
        assertEquals(ClientSettings.CURRENT_SETTINGS_VERSION, s.settingsVersion);
    }

    /**
     * The Seraph-removal step on a file saved by the previous version: every Seraph key goes and every
     * other key keeps its exact value; only the version stamp moves.
     */
    @Test
    public void theSeraphStepDropsItsKeysAndChangesNothingElse() {
        ClientSettings before = new ClientSettings();
        before.sanitize();
        // Non-default choices across the file, including the Tag Utils ones that must survive.
        before.tagUtils = true;
        before.urchinTags = false;
        before.tagBadgeTab = false;
        before.tagChatAlert = false;
        before.nickNotify = false;
        before.guiSize = 0;
        before.playerStatsShowRank = !before.playerStatsShowRank;
        JsonObject saved = tree(before);
        saved.addProperty("settingsVersion", ClientSettings.CURRENT_SETTINGS_VERSION - 1);
        saved.addProperty("seraphTags", false);
        saved.addProperty("seraphBadgeTab", true);
        saved.addProperty("seraphChatAlert", false);
        saved.addProperty("seraphAlertSound", true);
        saved.addProperty("seraphBadgeNametag", false);

        JsonObject after = tree(load(saved.toString()));

        for (Map.Entry<String, JsonElement> e : after.entrySet()) {
            assertFalse(e.getKey() + " must not be saved", e.getKey().toLowerCase().contains("seraph"));
        }
        JsonObject expected = tree(before);
        expected.addProperty("settingsVersion", ClientSettings.CURRENT_SETTINGS_VERSION);
        assertEquals("nothing but the Seraph keys and the stamp changes", expected, after);
    }

    @Test
    public void theCarryOverRunsOnlyOnce() {
        ClientSettings s = load("{\"settingsVersion\":1,\"urchinTags\":true,\"seraphTags\":true,"
                + "\"urchinChatAlert\":false,\"seraphChatAlert\":false}");
        assertFalse(s.tagChatAlert);
        s.tagChatAlert = true; // the player turns it back on
        ClientSettings again = load(GSON.toJson(s));
        assertTrue("a saved v2 choice is not re-derived", again.tagChatAlert);
    }
}
