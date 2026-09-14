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
        // The user's Lunar screenshot: Map Lighthouse, Height Limit 110.
        assertEquals(110, HeightLimitTable.lookup("Lighthouse").maxY);
        assertEquals(8, HeightLimitTable.lookup("Lighthouse").teams);
        // Forum-measured "111 first denied" maps land one below.
        assertEquals(100, HeightLimitTable.lookup("Playground").maxY);
        assertEquals(100, HeightLimitTable.lookup("Waterfall").maxY);
        assertEquals(105, HeightLimitTable.lookup("Temple").maxY);
        assertEquals(114, HeightLimitTable.lookup("Zarzul").maxY);
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
        assertTrue("expected a full table, got " + all.size(), all.size() >= 170);
        for (Map.Entry<String, HeightLimitMap> e : all.entrySet()) {
            HeightLimitMap m = e.getValue();
            assertEquals(e.getKey(), HeightLimitTable.normalize(m.name));
            assertTrue(m.name, m.teams == 4 || m.teams == 8);
            assertTrue(m.name + " maxY " + m.maxY, m.maxY >= 50 && m.maxY <= 200);
            assertTrue(m.name + " minY " + m.minY, m.minY >= 0 && m.minY < m.maxY);
            assertTrue(m.name + " radius " + m.radius, m.radius >= 0 && m.radius <= 250);
        }
    }
}
