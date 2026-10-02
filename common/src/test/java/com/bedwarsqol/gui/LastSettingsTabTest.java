package com.bedwarsqol.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins "settings reopen on the last tab you used": a remembered tab inside the current tab range is
 * restored, anything outside it (unset, negative, or a tab that no longer exists) falls back to the
 * first tab.
 */
public class LastSettingsTabTest {

    @Test
    public void restoresARememberedTabInRange() {
        LastSettingsTab.remember(3);
        assertEquals(3, LastSettingsTab.restore(6));
        LastSettingsTab.remember(5);
        assertEquals("the last tab is in range", 5, LastSettingsTab.restore(6));
        LastSettingsTab.remember(0);
        assertEquals(0, LastSettingsTab.restore(6));
    }

    @Test
    public void outOfRangeFallsBackToTheFirstTab() {
        LastSettingsTab.remember(6);
        assertEquals("a removed trailing tab is not reopened", 0, LastSettingsTab.restore(6));
        LastSettingsTab.remember(-1);
        assertEquals("unset", 0, LastSettingsTab.restore(6));
        LastSettingsTab.remember(Integer.MAX_VALUE);
        assertEquals(0, LastSettingsTab.restore(6));
    }

    @Test
    public void noTabsMeansTheFirstTab() {
        LastSettingsTab.remember(0);
        assertEquals(0, LastSettingsTab.restore(0));
    }
}
