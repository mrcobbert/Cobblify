package com.bedwarsqol.gui;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Edit HUD's right-click menu rows, from one rule (the kit's order: extras after Reset Size). */
public class EditHudMenuTest {

    private static List<String> list(String... rows) {
        return Arrays.asList(rows);
    }

    @Test
    public void everyCombination() {
        assertEquals(list("Reset Position", "Reset Size", "Settings", "Hide"), EditHudMenu.items(true, true, null));
        assertEquals(list("Show", "Reset Position", "Reset Size", "Settings"), EditHudMenu.items(false, true, null));
        assertEquals(list("Reset Position", "Reset Size", "Settings"), EditHudMenu.items(true, false, null));
        assertEquals(list("Reset Position", "Reset Size", "Settings"), EditHudMenu.items(false, false, null));
        assertEquals(list("Reset Position", "Reset Size", "Background: On", "Settings", "Hide"),
                EditHudMenu.items(true, true, Boolean.TRUE));
        assertEquals(list("Show", "Reset Position", "Reset Size", "Background: Off", "Settings"),
                EditHudMenu.items(false, true, Boolean.FALSE));
        assertEquals(list("Reset Position", "Reset Size", "Background: Off", "Settings"),
                EditHudMenu.items(true, false, Boolean.FALSE));
        assertEquals(list("Reset Position", "Reset Size", "Background: On", "Settings"),
                EditHudMenu.items(false, false, Boolean.TRUE));
    }

    @Test
    public void aNotHideableItemNeverListsShowOrHide() {
        for (boolean shown : new boolean[]{true, false}) {
            for (Boolean bg : new Boolean[]{null, true, false}) {
                List<String> rows = EditHudMenu.items(shown, false, bg);
                assertFalse(rows.contains(EditHudMenu.SHOW));
                assertFalse(rows.contains(EditHudMenu.HIDE));
            }
        }
    }

    @Test
    public void backgroundTextFollowsTheValue() {
        assertEquals("Background: On", EditHudMenu.background(true));
        assertEquals("Background: Off", EditHudMenu.background(false));
        assertTrue(EditHudMenu.isBackground("Background: On"));
        assertTrue(EditHudMenu.isBackground("Background: Off"));
        assertFalse(EditHudMenu.isBackground("Settings"));
        assertFalse(EditHudMenu.isBackground(null));
    }

    @Test
    public void chatAndTabListCannotBeHidden() {
        assertFalse(EditHudMenu.hideable("chat"));
        assertFalse(EditHudMenu.hideable("tablist"));
        assertTrue(EditHudMenu.hideable("scoreboard"));
        assertTrue(EditHudMenu.hideable("potion"));
        assertTrue(EditHudMenu.hideable("keystrokes"));
    }
}
