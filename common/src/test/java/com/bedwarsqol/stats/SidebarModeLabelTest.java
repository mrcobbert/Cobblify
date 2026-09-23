package com.bedwarsqol.stats;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the exact-label rule the sidebar readers share (F2): only Hypixel's own mode labels, with
 * scoreboard decoration allowed after them, name a mode. A dream variant that merely contains a
 * label ("Rush Doubles", "Ultimate 4v4v4v4") is not that mode, and 4v4 has no stats block of its
 * own, so both fall back to overall.
 */
public class SidebarModeLabelTest {

    @Test
    public void dreamAndUnmappedLabelsAreUnknown() {
        assertEquals(BedwarsMode.UNKNOWN, SidebarModeLabel.toMode("Rush Doubles"));
        assertEquals(BedwarsMode.UNKNOWN, SidebarModeLabel.toMode("Ultimate 4v4v4v4"));
        assertEquals(BedwarsMode.UNKNOWN, SidebarModeLabel.toMode("Castle"));
        assertEquals(BedwarsMode.UNKNOWN, SidebarModeLabel.toMode("4v4"));
        assertEquals(BedwarsMode.UNKNOWN, SidebarModeLabel.toMode("Doubles Rush"));
        assertEquals(BedwarsMode.UNKNOWN, SidebarModeLabel.toMode(""));
        assertEquals(BedwarsMode.UNKNOWN, SidebarModeLabel.toMode(null));
    }

    @Test
    public void exactLabelsMapToTheirMode() {
        assertEquals(BedwarsMode.DOUBLES, SidebarModeLabel.toMode("Doubles"));
        assertEquals(BedwarsMode.DOUBLES, SidebarModeLabel.toMode("doubles ✦"));
        assertEquals(BedwarsMode.DOUBLES, SidebarModeLabel.toMode("DOUBLES"));
        assertEquals(BedwarsMode.SOLO, SidebarModeLabel.toMode("Solo"));
        assertEquals(BedwarsMode.SOLO, SidebarModeLabel.toMode("Solos"));
        assertEquals(BedwarsMode.THREES, SidebarModeLabel.toMode("3v3v3v3"));
        assertEquals(BedwarsMode.FOURS, SidebarModeLabel.toMode("4v4v4v4"));
    }

    @Test
    public void decorationIsOnlyANonAlphanumericTail() {
        assertTrue(SidebarModeLabel.isLabelWithDecoration("Doubles", "doubles"));
        assertTrue(SidebarModeLabel.isLabelWithDecoration("doubles ✦", "doubles"));
        assertFalse(SidebarModeLabel.isLabelWithDecoration("Doubles Rush", "doubles"));
        assertFalse(SidebarModeLabel.isLabelWithDecoration("4v4", "4v4v4v4"));
        assertFalse(SidebarModeLabel.isLabelWithDecoration(null, "doubles"));
        assertFalse(SidebarModeLabel.isLabelWithDecoration("Doubles", null));
    }
}
