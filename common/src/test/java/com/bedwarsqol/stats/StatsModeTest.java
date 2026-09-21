package com.bedwarsqol.stats;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the one {@code /bw mode} vocabulary: every typed synonym, the stored-token sanitiser, the
 * forced-mode resolver and the command's description all agree, and garbage degrades to auto.
 */
public class StatsModeTest {

    @Test
    public void everyTypedSynonymParsesToItsStoredToken() {
        for (String w : new String[]{"auto", "detect", "default"}) assertEquals(w, "auto", StatsMode.parse(w));
        for (String w : new String[]{"all", "overall"}) assertEquals(w, "overall", StatsMode.parse(w));
        for (String w : new String[]{"solo", "solos", "1s", "1v1"}) assertEquals(w, "solo", StatsMode.parse(w));
        for (String w : new String[]{"doubles", "double", "2s", "2v2v2v2"}) assertEquals(w, "doubles", StatsMode.parse(w));
        for (String w : new String[]{"threes", "three", "3s", "3v3v3v3"}) assertEquals(w, "threes", StatsMode.parse(w));
        for (String w : new String[]{"fours", "four", "4s", "4v4v4v4"}) assertEquals(w, "fours", StatsMode.parse(w));
    }

    @Test
    public void parseIsCaseAndWhitespaceInsensitiveAndRejectsGarbage() {
        assertEquals("fours", StatsMode.parse("  4S "));
        assertEquals("overall", StatsMode.parse("ALL"));
        assertNull(StatsMode.parse("4v4"));      // a different Hypixel mode, never mapped to fours
        assertNull(StatsMode.parse("castle"));
        assertNull(StatsMode.parse(""));
        assertNull(StatsMode.parse(null));
    }

    @Test
    public void everyOptionWordParses() {
        for (String w : StatsMode.OPTIONS) assertTrue(w, StatsMode.parse(w) != null);
        assertArrayEquals(new String[]{"auto", "all", "solo", "2s", "3s", "4s"}, StatsMode.OPTIONS);
    }

    @Test
    public void normalizeKeepsStoredTokensAndDefaultsEverythingElseToAuto() {
        for (String t : new String[]{"auto", "overall", "solo", "doubles", "threes", "fours"}) {
            assertEquals(t, StatsMode.normalize(t));
            assertEquals(t, StatsMode.normalize(" " + t.toUpperCase() + " "));
        }
        // Typed synonyms are NOT stored spellings: a hand-edited file saying "4s" is unrecognised.
        assertEquals("auto", StatsMode.normalize("4s"));
        assertEquals("auto", StatsMode.normalize("all"));
        assertEquals("auto", StatsMode.normalize("garbage"));
        assertEquals("auto", StatsMode.normalize(""));
        assertEquals("auto", StatsMode.normalize(null));
    }

    @Test
    public void parsedTokensSurviveNormalize() {
        for (String w : StatsMode.OPTIONS) {
            String stored = StatsMode.parse(w);
            assertEquals(w, stored, StatsMode.normalize(stored));
        }
    }

    @Test
    public void forcedResolvesStoredTokensAndAutoToNull() {
        assertNull(StatsMode.forced("auto"));
        assertNull(StatsMode.forced(null));
        assertNull(StatsMode.forced("nonsense"));
        assertEquals(BedwarsMode.UNKNOWN, StatsMode.forced("overall"));
        assertEquals(BedwarsMode.SOLO, StatsMode.forced("solo"));
        assertEquals(BedwarsMode.DOUBLES, StatsMode.forced("doubles"));
        assertEquals(BedwarsMode.THREES, StatsMode.forced("threes"));
        assertEquals(BedwarsMode.FOURS, StatsMode.forced("fours"));
        assertFalse(StatsMode.isForced("auto"));
        assertTrue(StatsMode.isForced("overall"));
        assertTrue(StatsMode.isForced("fours"));
    }

    @Test
    public void describeNamesTheStoredToken() {
        assertEquals("Auto (per-game)", StatsMode.describe("auto"));
        assertEquals("Auto (per-game)", StatsMode.describe(null));
        assertEquals("Overall", StatsMode.describe("overall"));
        assertEquals("Solo", StatsMode.describe("solo"));
        assertEquals("Doubles", StatsMode.describe("doubles"));
        assertEquals("3v3v3v3", StatsMode.describe("threes"));
        assertEquals("4v4v4v4", StatsMode.describe("fours"));
    }

    @Test
    public void shortLabelsAreTheDisplayTags() {
        assertEquals("Solo", BedwarsMode.SOLO.shortLabel());
        assertEquals("2s", BedwarsMode.DOUBLES.shortLabel());
        assertEquals("3s", BedwarsMode.THREES.shortLabel());
        assertEquals("4s", BedwarsMode.FOURS.shortLabel());
        assertEquals("All", BedwarsMode.UNKNOWN.shortLabel());
    }
}
