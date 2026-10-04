package com.bedwarsqol.gui.sheet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class SheetSearchTest {

    private final SheetFixtures.Values values = new SheetFixtures.Values();

    @Test
    public void reachesRowsDescriptionsAndKeywords() {
        List<SheetSearch.Hit> hits = SheetSearch.search("sound", values);
        assertEquals(2, hits.size());
        assertEquals("chatNotify", hits.get(0).module.id);
        assertTrue(hits.get(0).moduleHit);
        assertEquals(2, hits.get(0).rows.size());
        assertEquals("tagUtils", hits.get(1).module.id);
        assertEquals(1, hits.get(1).rows.size());
        assertEquals(3, SheetSearch.count(hits));
    }

    @Test
    public void optionNamesMatchRowsAndModuleNamesRankFirst() {
        List<SheetSearch.Hit> hits = SheetSearch.search("outline", values);
        assertEquals("blockOverlay", hits.get(0).module.id);
        assertEquals("boStyle", hits.get(0).rows.iterator().next().id);
        hits = SheetSearch.search("stats", values);
        assertEquals("session", hits.get(0).module.id);
        assertEquals("stats", hits.get(1).module.id);
    }

    @Test
    public void aModuleMatchedOnlyByItselfCountsOnce() {
        List<SheetSearch.Hit> hits = SheetSearch.search("auto gg", values);
        assertEquals(1, hits.size());
        assertEquals(1, SheetSearch.count(hits));
        assertTrue(SheetSearch.search("zzz", values).isEmpty());
        assertTrue(SheetSearch.search("   ", values).isEmpty());
    }

    @Test
    public void settingsSectionsMatchByRow() {
        List<SheetSearch.Hit> hits = SheetSearch.search("accent", values);
        // Block Overlay's White ("instead of your accent") matches too; the Settings page lists its Accent row
        assertEquals(2, hits.size());
        SheetSearch.Hit settings = hits.get(1);
        assertEquals("settings", settings.section.category().id);
        assertEquals("accent", settings.rows.iterator().next().id);
        assertEquals("boWhite", hits.get(0).rows.iterator().next().id);
    }

    @Test
    public void onlyThisPlatformsModules() {
        values.missing.clear();
        values.missing.add("chatNotify");
        values.missing.add("notifyMention");
        values.missing.add("notifyInc");
        List<SheetSearch.Hit> hits = SheetSearch.search("sound", values);
        assertEquals("incAlert", hits.get(0).module.id);
        assertEquals(2, SheetSearch.count(hits));
    }

    @Test
    public void markIsTheFirstCaseInsensitiveMatch() {
        assertEquals(8, SheetSearch.markAt("Mention Sound", " sound "));
        assertEquals(-1, SheetSearch.markAt("Inc Alert", "sound"));
        assertEquals(-1, SheetSearch.markAt("Inc Alert", null));
    }
}
