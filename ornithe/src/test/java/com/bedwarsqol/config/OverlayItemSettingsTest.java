package com.bedwarsqol.config;

import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.HudModuleState;
import com.bedwarsqol.hud.HudModules;
import com.bedwarsqol.hud.HudPlacement;
import com.google.gson.Gson;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The scoreboard, chat and tab list as HUD items: their state in {@link ClientSettings}, the one-time move of
 * the old Scoreboard Size and Tab List Size rows into the items' scales, and their {@link HudModules} entries.
 * The rows leaving the Ornithe sheet is pinned per tree by {@code PlatformSheetRowsTest}.
 */
public class OverlayItemSettingsTest {

    private static final Gson GSON = new Gson();
    private static final String[] OVERLAYS = {
            BedwarsHudRenderer.SCOREBOARD_HUD, BedwarsHudRenderer.CHAT_HUD, BedwarsHudRenderer.TAB_LIST_HUD};

    private static ClientSettings load(String json) {
        ClientSettings s = GSON.fromJson(json, ClientSettings.class);
        s.sanitize();
        return s;
    }

    @Test
    public void savedSizeRowsBecomeTheItemScalesTheyDrewAt() {
        ClientSettings s = load("{\"scoreboardSize\":0,\"styledTabListSize\":1,\"settingsVersion\":5}");
        assertEquals(0.7f, s.scoreboardHudScale, 0f);
        assertEquals(0.85f, s.tabListHudScale, 0f);
        assertEquals("chat had no Size row", 1.0f, s.chatHudScale, 0f);
        String out = GSON.toJson(s);
        assertFalse("the Scoreboard Size key drops on save", out.contains("\"scoreboardSize\""));
        assertFalse("the Tab List Size key drops on save", out.contains("\"styledTabListSize\""));
        assertTrue(out.contains("\"scoreboardHudScale\""));
        assertTrue(out.contains("\"tabListHudScale\""));
    }

    @Test
    public void largeAndOutOfRangeSizeRowsMigrateLikeTheOldClamp() {
        ClientSettings s = load("{\"scoreboardSize\":2,\"styledTabListSize\":7}");
        assertEquals(1.0f, s.scoreboardHudScale, 0f);
        assertEquals(1.0f, s.tabListHudScale, 0f);
        s = load("{\"scoreboardSize\":-3}");
        assertEquals(0.7f, s.scoreboardHudScale, 0f);
    }

    @Test
    public void theMigrationRunsOnceAndKeepsALaterResize() {
        ClientSettings s = load("{\"scoreboardSize\":0}");
        assertNull(s.scoreboardSize);
        s.scoreboardHudScale = 1.3f;
        s.sanitize();
        assertEquals(1.3f, s.scoreboardHudScale, 0f);
        ClientSettings again = load(GSON.toJson(s));
        assertEquals(1.3f, again.scoreboardHudScale, 0f);
    }

    @Test
    public void withoutSizeRowsTheItemsKeepVanillaSize() {
        ClientSettings s = load("{\"guiSize\":1}");
        assertEquals(1.0f, s.scoreboardHudScale, 0f);
        assertEquals(1.0f, s.tabListHudScale, 0f);
        assertEquals(1.0f, s.chatHudScale, 0f);
    }

    @Test
    public void aFreshConfigHasTheItemsAtVanillasSpotAndSize() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        for (String id : OVERLAYS) {
            HudModuleState st = HudModules.get(s, id);
            assertEquals(id, HudPlacement.AUTO, st.anchor);
            assertEquals(id, 1.0f, st.scale, 0f);
            assertTrue(id, st.enabled);
        }
        assertTrue(s.scoreboardHudEnabled);
    }

    @Test
    public void outOfRangeScalesAndAnchorsSanitize() {
        ClientSettings s = load("{\"scoreboardHudScale\":0.1,\"tabListHudScale\":40,\"chatHudScale\":-2,"
                + "\"scoreboardHudAnchor\":-7,\"tabListHudAnchor\":12,\"chatHudAnchor\":-2}");
        assertEquals(1.0f, s.scoreboardHudScale, 0f);
        assertEquals(1.0f, s.tabListHudScale, 0f);
        assertEquals(1.0f, s.chatHudScale, 0f);
        assertEquals(HudPlacement.AUTO, s.scoreboardHudAnchor);
        assertEquals("clamped like every module's anchor", 8, s.tabListHudAnchor);
        assertEquals(HudPlacement.AUTO, s.chatHudAnchor);
    }

    @Test
    public void getAndSetRoundTripTheThreeItems() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        HudModuleState sb = new HudModuleState(6, 40, 0, 0.7f, false);
        HudModuleState chat = new HudModuleState(-10, 0, 5, 1.5f, true);
        HudModuleState tab = new HudModuleState(4, 4, 1, 1.25f, true);
        HudModules.set(s, BedwarsHudRenderer.SCOREBOARD_HUD, sb);
        HudModules.set(s, BedwarsHudRenderer.CHAT_HUD, chat);
        HudModules.set(s, BedwarsHudRenderer.TAB_LIST_HUD, tab);
        assertEquals(sb, HudModules.get(s, BedwarsHudRenderer.SCOREBOARD_HUD));
        assertEquals(chat, HudModules.get(s, BedwarsHudRenderer.CHAT_HUD));
        assertEquals(tab, HudModules.get(s, BedwarsHudRenderer.TAB_LIST_HUD));
        assertFalse("Edit HUD can hide the scoreboard", s.scoreboardHudEnabled);
        ClientSettings back = load(GSON.toJson(s));
        assertEquals(sb, HudModules.get(back, BedwarsHudRenderer.SCOREBOARD_HUD));
        assertEquals(chat, HudModules.get(back, BedwarsHudRenderer.CHAT_HUD));
        assertEquals(tab, HudModules.get(back, BedwarsHudRenderer.TAB_LIST_HUD));
    }

    @Test
    public void chatAndTabListCannotBeHidden() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        HudModules.set(s, BedwarsHudRenderer.CHAT_HUD, new HudModuleState(1, 2, 0, 1f, false));
        HudModules.set(s, BedwarsHudRenderer.TAB_LIST_HUD, new HudModuleState(1, 2, 0, 1f, false));
        assertTrue(HudModules.get(s, BedwarsHudRenderer.CHAT_HUD).enabled);
        assertTrue(HudModules.get(s, BedwarsHudRenderer.TAB_LIST_HUD).enabled);
        assertEquals(1, HudModules.get(s, BedwarsHudRenderer.CHAT_HUD).x);
    }

    @Test
    public void resetPutsTheItemsBackAtVanillaSizeAtEveryHudSize() {
        for (int size = 0; size <= 2; size++) {
            ClientSettings s = new ClientSettings();
            s.sanitize();
            s.setHudSize(size);
            for (String id : OVERLAYS) {
                HudModules.set(s, id, new HudModuleState(5, 5, 0, 1.8f, true));
                HudModuleState d = HudModules.defaults(s, id);
                assertEquals(id, HudPlacement.AUTO, d.anchor);
                assertEquals(id + " at HUD Size " + size, 1.0f, d.scale, 0f);
            }
        }
    }

    @Test
    public void hudSizeLeavesTheItemsAlone() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        s.scoreboardHudScale = 0.7f;
        s.tabListHudScale = 0.85f;
        s.chatHudScale = 1.25f;
        s.setHudSize(2);
        s.setHudSize(0);
        assertEquals(0.7f, s.scoreboardHudScale, 0f);
        assertEquals(0.85f, s.tabListHudScale, 0f);
        assertEquals(1.25f, s.chatHudScale, 0f);
    }

    @Test
    public void undoSnapshotsIncludeTheItems() {
        ClientSettings s = new ClientSettings();
        s.sanitize();
        Map<String, HudModuleState> before = HudModules.snapshot(s);
        for (String id : OVERLAYS) assertTrue(id, before.containsKey(id));
        HudModules.set(s, BedwarsHudRenderer.CHAT_HUD, new HudModuleState(3, -9, 6, 1.5f, true));
        HudModules.set(s, BedwarsHudRenderer.SCOREBOARD_HUD, new HudModuleState(3, -9, 6, 0.5f, false));
        HudModules.restore(s, before);
        assertEquals(before, HudModules.snapshot(s));
        assertEquals(HudPlacement.AUTO, s.chatHudAnchor);
        assertTrue(s.scoreboardHudEnabled);
    }

    @Test
    public void overlayIdsStayOutOfTheModuleList() {
        assertEquals(Arrays.asList(OVERLAYS), HudModules.overlayIds());
        for (String id : OVERLAYS) {
            assertFalse(id, HudModules.ids().contains(id));
            assertTrue(id, HudModules.allIds().contains(id));
        }
        assertTrue(HudModules.allIds().containsAll(HudModules.ids()));
        assertEquals("Scoreboard", HudModules.title(BedwarsHudRenderer.SCOREBOARD_HUD));
        assertEquals("Chat", HudModules.title(BedwarsHudRenderer.CHAT_HUD));
        assertEquals("Tab List", HudModules.title(BedwarsHudRenderer.TAB_LIST_HUD));
    }
}
