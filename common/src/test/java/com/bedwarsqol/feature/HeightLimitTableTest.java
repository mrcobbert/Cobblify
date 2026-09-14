package com.bedwarsqol.feature;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HeightLimitTableTest {

    @Test
    public void lunarReferenceValues() {
        // Lunar's hypixel/bedwars.json stores the first denied Y; its HUD (and this table) shows one less.
        // The user's Lunar screenshot: Map Lighthouse, Height Limit 110 (stored 111).
        assertEquals(110, HeightLimitTable.lookup("Lighthouse").maxY);
        assertEquals(8, HeightLimitTable.lookup("Lighthouse").teams);
        assertEquals(100, HeightLimitTable.lookup("Playground").maxY);   // 101
        assertEquals(100, HeightLimitTable.lookup("Waterfall").maxY);    // 101
        assertEquals(106, HeightLimitTable.lookup("Temple").maxY);       // 107
        assertEquals(114, HeightLimitTable.lookup("Zarzul").maxY);       // 115
        assertEquals(95, HeightLimitTable.lookup("Atlas Prime").maxY);   // 96
        // Lunar-only maps carry no floor / radius / pool.
        HeightLimitMap ivory = HeightLimitTable.lookup("Ivory Castle");
        assertEquals(110, ivory.maxY);
        assertEquals(0, ivory.teams);
        assertEquals(0, ivory.radius);
        // Lunar's typo keys are folded: mortuss -> Mortuus, pharoah/build_side dropped for the real names.
        assertEquals(90, HeightLimitTable.lookup("Mortuus").maxY);
        assertEquals(95, HeightLimitTable.lookup("Pharaoh").maxY);
        assertEquals(96, HeightLimitTable.lookup("Build Site").maxY);
        assertNull(HeightLimitTable.lookup("Pharoah"));
    }

    @Test
    public void normalizationTolerantLookup() {
        assertNotNull(HeightLimitTable.lookup("lighthouse"));
        assertNotNull(HeightLimitTable.lookup("  LIGHTHOUSE "));
        assertNotNull(HeightLimitTable.lookup("Bio-Hazard"));
        assertNotNull(HeightLimitTable.lookup("biohazard"));
        assertNotNull(HeightLimitTable.lookup("Santa's Rush"));
        assertNotNull(HeightLimitTable.lookup("Planet 98"));
        assertEquals("santasrush", HeightLimitTable.normalize("Santa's Rush"));
        assertEquals("", HeightLimitTable.normalize(null));
        assertNull(HeightLimitTable.lookup("Not A Map"));
        assertNull(HeightLimitTable.lookup(""));
        assertNull(HeightLimitTable.lookup(null));
    }

    @Test
    public void everyEntryIsSane() {
        Map<String, HeightLimitMap> all = HeightLimitTable.all();
        assertTrue("expected a full table, got " + all.size(), all.size() >= 200);
        for (Map.Entry<String, HeightLimitMap> e : all.entrySet()) {
            HeightLimitMap m = e.getValue();
            assertEquals(e.getKey(), HeightLimitTable.normalize(m.name));
            assertTrue(m.name, m.teams == 0 || m.teams == 4 || m.teams == 8);
            assertTrue(m.name + " maxY " + m.maxY, m.maxY >= 50 && m.maxY <= 200);
            assertTrue(m.name + " minY " + m.minY, m.minY >= 0 && m.minY < m.maxY);
            assertTrue(m.name + " radius " + m.radius, m.radius >= 0 && m.radius <= 250);
        }
    }
}
