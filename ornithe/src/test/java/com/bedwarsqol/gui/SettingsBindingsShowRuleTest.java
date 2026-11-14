package com.bedwarsqol.gui;

import com.bedwarsqol.gui.sheet.SheetSchema;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.HudModules;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Switching a module on in the settings shows its HUD items, which then take their first free Auto spot. Only the
 * module enable switches count: never a Background switch (an item keeps its spot when its Background changes), never
 * In Game Only, never the scoreboard, chat or tab list.
 */
public class SettingsBindingsShowRuleTest {

    @Test
    public void moduleSwitchesShowTheirItems() {
        assertEquals(Collections.singletonList(BedwarsHudRenderer.KEYSTROKES_HUD), SettingsBindings.hudIdsShownBy("keystrokes"));
        assertEquals(Arrays.asList(BedwarsHudRenderer.DIAMOND_TIMER_HUD, BedwarsHudRenderer.EMERALD_TIMER_HUD),
                SettingsBindings.hudIdsShownBy("genTimers"));
        assertEquals(Collections.singletonList(BedwarsHudRenderer.POTION_HUD), SettingsBindings.hudIdsShownBy("potion"));
        assertEquals(Collections.singletonList(BedwarsHudRenderer.ARMOR_HUD), SettingsBindings.hudIdsShownBy("armor"));
        assertEquals(Collections.singletonList(BedwarsHudRenderer.INVENTORY_HUD), SettingsBindings.hudIdsShownBy("inventory"));
        assertEquals(Collections.singletonList(BedwarsHudRenderer.SESSION_HUD), SettingsBindings.hudIdsShownBy("session"));
        assertEquals(Collections.singletonList(BedwarsHudRenderer.HEIGHT_LIMIT_HUD), SettingsBindings.hudIdsShownBy("mapInfo"));
    }

    @Test
    public void backgroundInGameOnlyAndOverlayRowsShowNothing() {
        for (String row : new String[]{"keystrokesBg", "potionBg", "genTimersBg", "scoreboardBg", "chatBg", "tabListBg",
                "potionInGame", "genTimersInGame", "scoreboard", "chat", "tablist", "sessionHold", "hudSize", null, ""}) {
            assertTrue(String.valueOf(row), SettingsBindings.hudIdsShownBy(row).isEmpty());
        }
    }

    @Test
    public void everySchemaRowShowsOnlyEditHudModules() {
        List<String> ids = new ArrayList<String>();
        for (SheetSchema.Category c : SheetSchema.CATS) {
            for (SheetSchema.Module m : c.modules) {
                ids.add(m.id);
                for (SheetSchema.Row r : m.rows) ids.add(r.id);
            }
            for (SheetSchema.Section s : c.sections) {
                for (SheetSchema.Row r : s.rows) ids.add(r.id);
            }
        }
        assertFalse(ids.isEmpty());
        for (String id : ids) {
            for (String shown : SettingsBindings.hudIdsShownBy(id)) {
                assertTrue(id + " -> " + shown, HudModules.ids().contains(shown));
                assertFalse(id, Arrays.asList("scoreboard", "chat", "tablist").contains(shown));
            }
            if (SheetSchema.hudItem(id) != null) {
                assertTrue("a Background row never shows an item: " + id, SettingsBindings.hudIdsShownBy(id).isEmpty());
            }
        }
    }
}
