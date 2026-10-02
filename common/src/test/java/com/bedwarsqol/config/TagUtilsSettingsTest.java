package com.bedwarsqol.config;

import com.bedwarsqol.stats.SeraphTag;
import com.bedwarsqol.stats.UrchinTag;
import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tag Utils (settings v2) replaces the Urchin Tags and Seraph Tags modules and the two Queue alert
 * toggles. Urchin and Seraph become sub-options under one master, the badge and alert sub-options are
 * shared, and a saved v1 file carries the player's choices over once.
 */
public class TagUtilsSettingsTest {

    private static final Gson GSON = new Gson();

    private static ClientSettings load(String json) {
        ClientSettings s = GSON.fromJson(json, ClientSettings.class);
        s.sanitize();
        return s;
    }

    @Test
    public void freshDefaultsHaveEverythingOn() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        assertTrue(s.tagUtils);
        assertTrue(s.urchinOn());
        assertTrue(s.seraphOn());
        assertTrue(s.tagBadgeTab);
        assertTrue(s.tagBadgeNametag);
        assertTrue(s.tagChatAlert);
        assertTrue(s.tagAlertSound);
    }

    @Test
    public void tagUtilsOffSwitchesBothSourcesOff() {
        ClientSettings s = new ClientSettings();
        s.tagUtils = false;
        assertFalse(s.urchinOn());
        assertFalse(s.seraphOn());
        s.playerStats = false;
        assertFalse(UrchinTag.needsTabIdentity(s));
        assertFalse(UrchinTag.needsNametagIdentity(s));
        assertFalse(SeraphTag.needsTabIdentity(s));
        assertFalse(SeraphTag.needsNametagIdentity(s));
    }

    @Test
    public void identityFollowsTheSharedSubOptions() {
        ClientSettings s = new ClientSettings();
        s.playerStats = false;
        s.tagBadgeTab = false;
        s.tagChatAlert = false;
        assertFalse("no tab badge and no alert: no tab identity", UrchinTag.needsTabIdentity(s));
        assertFalse(SeraphTag.needsTabIdentity(s));
        assertTrue("nametag badge still wants identity", UrchinTag.needsNametagIdentity(s));
        s.seraphTags = false;
        assertFalse("a source switched off wants nothing", SeraphTag.needsNametagIdentity(s));
        assertTrue(UrchinTag.needsNametagIdentity(s));
    }

    @Test
    public void v1WithBothSourcesOnAndUntouchedKeepsEverythingOn() {
        ClientSettings s = load("{\"settingsVersion\":1,\"urchinTags\":true,\"seraphTags\":true}");
        assertTrue(s.tagUtils);
        assertTrue(s.urchinTags);
        assertTrue(s.seraphTags);
        assertTrue(s.tagBadgeTab && s.tagBadgeNametag && s.tagChatAlert && s.tagAlertSound);
    }

    @Test
    public void v1SourcesCarryOverAndSubOptionsFollowTheSourcesThatWereOn() {
        // Urchin was off, so its (default) sub-values must not switch on what the player turned off
        // for Seraph.
        ClientSettings s = load("{\"settingsVersion\":1,\"urchinTags\":false,\"seraphTags\":true,"
                + "\"urchinChatAlert\":true,\"seraphChatAlert\":false,"
                + "\"urchinBadgeTab\":true,\"seraphBadgeTab\":true,"
                + "\"urchinAlertSound\":true,\"seraphAlertSound\":false,"
                + "\"urchinBadgeNametag\":false,\"seraphBadgeNametag\":true}");
        assertTrue("Tag Utils is on while either source was on", s.tagUtils);
        assertFalse(s.urchinTags);
        assertTrue(s.seraphTags);
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
    public void v1WithBothSourcesOffTurnsTagUtilsOffAndKeepsTheSources() {
        ClientSettings s = load("{\"settingsVersion\":1,\"urchinTags\":false,\"seraphTags\":false,"
                + "\"urchinBadgeTab\":false,\"seraphBadgeTab\":true,"
                + "\"urchinChatAlert\":false,\"seraphChatAlert\":false}");
        assertFalse(s.tagUtils);
        assertFalse(s.urchinTags);
        assertFalse(s.seraphTags);
        assertTrue("with no source on, either source's value counts", s.tagBadgeTab);
        assertFalse(s.tagChatAlert);
    }

    @Test
    public void anUnstampedFileRunsBothUpgrades() {
        ClientSettings s = load("{\"sessionStatsHudX\":5,\"sessionStatsHudAnchor\":2,"
                + "\"urchinTags\":false,\"seraphTags\":true,\"seraphChatAlert\":false}");
        assertEquals("the v1 HUD fix still runs", -5, s.sessionStatsHudX);
        assertTrue(s.tagUtils);
        assertFalse(s.tagChatAlert);
        assertEquals(2, s.settingsVersion);
    }

    @Test
    public void aLiteralNullReadsAsTheOldDefault() {
        ClientSettings s = load("{\"settingsVersion\":1,\"urchinTags\":true,\"seraphTags\":false,"
                + "\"urchinChatAlert\":null,\"urchinBadgeTab\":false}");
        assertTrue(s.tagChatAlert);
        assertFalse(s.tagBadgeTab);
    }

    @Test
    public void savingDropsTheOldPerSourceAndQueueKeys() {
        ClientSettings s = load("{\"settingsVersion\":1,\"urchinTags\":true,\"seraphTags\":true,"
                + "\"urchinBadgeTab\":false,\"seraphChatAlert\":false,\"urchinAlertSound\":true,"
                + "\"seraphBadgeNametag\":true,\"queueTagAlert\":false,\"queueNickAlert\":true}");
        String out = GSON.toJson(s);
        for (String gone : new String[]{"urchinBadgeTab", "urchinChatAlert", "urchinAlertSound",
                "urchinBadgeNametag", "seraphBadgeTab", "seraphChatAlert", "seraphAlertSound",
                "seraphBadgeNametag", "queueTagAlert", "queueNickAlert"}) {
            assertFalse(gone + " must not be saved", out.contains("\"" + gone + "\""));
        }
        for (String kept : new String[]{"tagUtils", "urchinTags", "seraphTags", "tagBadgeTab",
                "tagBadgeNametag", "tagChatAlert", "tagAlertSound"}) {
            assertTrue(kept + " must be saved", out.contains("\"" + kept + "\""));
        }
        assertEquals(2, s.settingsVersion);
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
