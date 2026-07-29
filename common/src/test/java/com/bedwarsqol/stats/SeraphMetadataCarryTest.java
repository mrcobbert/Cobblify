package com.bedwarsqol.stats;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * Pins the invariant behind code-review IMPORTANT-1: Seraph threat/encounter metadata is stored by
 * {@code withSeraph} and preserved by every other copy method even when the tag list is empty, so a
 * metadata-only Seraph result is never lost across a later Urchin merge.
 */
public class SeraphMetadataCarryTest {

    private static BedwarsStats ok() {
        BedwarsStats.ModeStats m = new BedwarsStats.ModeStats(10, 5, 8, 4, 20, 10);
        return BedwarsStats.ok("Notch", "", "", m, m, m, m, m);
    }

    @Test
    public void withSeraphStoresMetadataEvenWithEmptyTags() {
        BedwarsStats s = ok().withSeraph(Collections.<SeraphTag>emptyList(), 5, 12);
        assertEquals(5, s.seraphThreat);
        assertEquals(12, s.seraphEncounters);
        assertEquals(0, s.seraphTags.size());
    }

    @Test
    public void metadataSurvivesUrchinCopy() {
        BedwarsStats s = ok().withSeraph(Collections.<SeraphTag>emptyList(), 5, 12)
                .withUrchinTags(Collections.<UrchinTag>emptyList());
        assertEquals(5, s.seraphThreat);
        assertEquals(12, s.seraphEncounters);
        assertEquals(0, s.urchinTags.size());
    }

    @Test
    public void absentMetadataIsMinusOne() {
        BedwarsStats s = ok().withSeraph(Collections.<SeraphTag>emptyList(), -1, -1);
        assertEquals(-1, s.seraphThreat);
        assertEquals(-1, s.seraphEncounters);
    }
}
