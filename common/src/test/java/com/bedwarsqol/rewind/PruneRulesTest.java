package com.bedwarsqol.rewind;

import com.bedwarsqol.rewind.PruneRules.Recording;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Keep the 10 newest closed, unstarred, unmarked games; never touch the others (A5). */
public class PruneRulesTest {

    private static final long DAY0 = 1_763_000_000_000L;

    /** Game n starts n minutes after DAY0; ids sort with start time. */
    private static Recording game(int n) {
        return new Recording(id(n), DAY0 + n * 60_000L, true, true, false, false, false);
    }

    private static String id(int n) {
        return String.format("20261117-%02d%02d00-%04x", 10 + n / 60, n % 60, n);
    }

    private static List<Recording> games(int count) {
        List<Recording> list = new ArrayList<Recording>();
        for (int i = 0; i < count; i++) list.add(game(i));
        Collections.shuffle(list, new java.util.Random(7));             // input order must not matter
        return list;
    }

    private static Set<String> ids(int fromInclusive, int toExclusive) {
        Set<String> s = new HashSet<String>();
        for (int i = fromInclusive; i < toExclusive; i++) s.add(id(i));
        return s;
    }

    @Test
    public void zeroNineAndTenGamesDeleteNothing() {
        assertTrue(PruneRules.idsToDelete(games(0)).isEmpty());
        assertTrue(PruneRules.idsToDelete(games(9)).isEmpty());
        assertTrue(PruneRules.idsToDelete(games(10)).isEmpty());
    }

    @Test
    public void elevenGamesDeleteTheOldest() {
        assertEquals(Collections.singletonList(id(0)), PruneRules.idsToDelete(games(11)));
    }

    @Test
    public void twentyFiveGamesKeepTheTenNewest() {
        List<String> deleted = PruneRules.idsToDelete(games(25));
        assertEquals(15, deleted.size());
        assertEquals(ids(0, 15), new HashSet<String>(deleted));
    }

    @Test
    public void starredAndKeyMarkedGamesAreNeverDeletedNorCounted() {
        List<Recording> list = new ArrayList<Recording>(games(25));
        // The three oldest games are kept forever; they must not take slots from the newest ten.
        list.add(new Recording(id(100), DAY0 - 3_000_000L, true, true, false, true, false));   // starred
        list.add(new Recording(id(101), DAY0 - 2_000_000L, true, true, false, false, true));   // key-marked
        list.add(new Recording(id(102), DAY0 - 1_000_000L, true, true, false, true, true));    // both
        List<String> deleted = PruneRules.idsToDelete(list);
        assertEquals(ids(0, 15), new HashSet<String>(deleted));
        assertFalse(deleted.contains(id(100)));
        assertFalse(deleted.contains(id(101)));
        assertFalse(deleted.contains(id(102)));

        // Ten newest are all starred or marked: the ten unmarked games after them are still kept.
        List<Recording> kept = new ArrayList<Recording>(games(10));
        for (int i = 50; i < 60; i++) {
            kept.add(new Recording(id(i), DAY0 + i * 60_000L, true, true, false, i % 2 == 0, i % 2 == 1));
        }
        assertTrue(PruneRules.idsToDelete(kept).isEmpty());
    }

    @Test
    public void openUnlockableAndUnclosedFilesAreKeptAndNotCounted() {
        List<Recording> list = new ArrayList<Recording>(games(10));
        // Older than every closed game, so they would be the first to go if they were candidates.
        list.add(new Recording(id(200), DAY0 - 5_000L, true, true, true, false, false));    // open here
        list.add(new Recording(id(201), DAY0 - 4_000L, true, false, false, false, false));  // locked elsewhere
        list.add(new Recording(id(202), DAY0 - 3_000L, false, true, false, false, false));  // not closed
        assertTrue(PruneRules.idsToDelete(list).isEmpty());

        list.add(game(10));                                             // one closed game too many
        assertEquals(Collections.singletonList(id(0)), PruneRules.idsToDelete(list));
    }

    @Test
    public void equalStartTimesBreakTiesById() {
        List<Recording> list = new ArrayList<Recording>();
        for (int i = 0; i < 12; i++) {
            list.add(new Recording(String.format("20261117-120000-%04x", i), DAY0, true, true, false, false, false));
        }
        Collections.reverse(list);
        List<String> deleted = PruneRules.idsToDelete(list);
        // Newest = larger id at equal start time, so the two smallest ids go.
        assertEquals(new HashSet<String>(Arrays.asList("20261117-120000-0000", "20261117-120000-0001")),
                new HashSet<String>(deleted));
        // Deterministic regardless of input order.
        Collections.shuffle(list, new java.util.Random(3));
        assertEquals(new HashSet<String>(deleted), new HashSet<String>(PruneRules.idsToDelete(list)));
    }

    @Test
    public void malformedIdsAreNeverDeletedNorCounted() {
        List<Recording> list = new ArrayList<Recording>(games(10));
        list.add(new Recording("../20261117-120000-abcd", DAY0 - 9L, true, true, false, false, false));
        list.add(new Recording("20261117-120000-ABCD", DAY0 - 8L, true, true, false, false, false));
        list.add(new Recording(null, DAY0 - 7L, true, true, false, false, false));
        assertTrue(PruneRules.idsToDelete(list).isEmpty());
    }

    @Test
    public void duplicateIdsCountOnce() {
        List<Recording> list = new ArrayList<Recording>(games(10));
        list.add(game(9));
        assertTrue(PruneRules.idsToDelete(list).isEmpty());
    }

    @Test
    public void fileNamePatternIsStrict() {
        assertEquals("20261117-213005-0a9f", PruneRules.idOf("20261117-213005-0a9f.cbr"));
        assertEquals("20261117-213005-0a9f", PruneRules.idOf("20261117-213005-0a9f.json"));
        assertEquals("20261117-213005-0a9f", PruneRules.idOf("20261117-213005-0a9f.stats.json"));
        assertNull(PruneRules.idOf("20261117-213005-0A9F.cbr"));        // upper-case hex
        assertNull(PruneRules.idOf("20261117-213005-0a9f.cbr.tmp"));
        assertNull(PruneRules.idOf("x20261117-213005-0a9f.cbr"));
        assertNull(PruneRules.idOf("20261117-21300-0a9f.cbr"));
        assertNull(PruneRules.idOf("20261117-213005-0a9f.txt"));
        assertNull(PruneRules.idOf("20261117-213005-0a9f.stats.cbr"));
        assertNull(PruneRules.idOf("sub/20261117-213005-0a9f.cbr"));
        assertNull(PruneRules.idOf(null));
        assertTrue(PruneRules.isValidId("20261117-213005-0a9f"));
        assertFalse(PruneRules.isValidId("20261117-213005-0a9f.cbr"));
        assertEquals(Arrays.asList("20261117-213005-0a9f.cbr", "20261117-213005-0a9f.json",
                "20261117-213005-0a9f.stats.json"), PruneRules.fileNames("20261117-213005-0a9f"));
        for (String name : PruneRules.fileNames("20261117-213005-0a9f")) {
            assertEquals("20261117-213005-0a9f", PruneRules.idOf(name));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void fileNamesRefuseAnInvalidId() {
        PruneRules.fileNames("../etc");
    }
}
