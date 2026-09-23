package com.bedwarsqol.feature;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HeightLimitCoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Object W1 = new Object();
    private static final Object W2 = new Object();
    private static final HeightLimitCore.BlockProbe NOTHING = (x, y, z) -> false;
    private static final HeightLimitCore.BlockProbe EVERYTHING = (x, y, z) -> true;

    private static final class Saved implements HeightLimitCore.Persist {
        Map<String, Integer> last;
        int calls;

        @Override
        public void save(Map<String, Integer> learnedByName) {
            last = new LinkedHashMap<>(learnedByName);
            calls++;
        }
    }

    private static HeightLimitCore fresh(Saved saved) {
        return new HeightLimitCore(null, saved);
    }

    @Test
    public void tableValueAndDistanceFromLocationEvent() {
        HeightLimitCore c = fresh(null);
        c.onLocation(W1, "BEDWARS_EIGHT_ONE", "Lighthouse");
        assertEquals("Lighthouse", c.map());
        assertEquals(110, c.limit());
        assertEquals(HeightLimitCore.Source.TABLE, c.source());
        assertEquals(27, c.distance(83.0));       // feet at 83 -> 27 below the limit (Lunar's screenshot)
        assertEquals(27, c.distance(83.9));
        assertEquals(0, c.distance(110.0));
        assertEquals(0, c.distance(115.0));       // above it: clamp, never negative
    }

    @Test
    public void unknownMapReadsUnknownUntilLearned() {
        Saved saved = new Saved();
        HeightLimitCore c = fresh(saved);
        c.onLocation(W1, "BEDWARS_EIGHT_TWO", "Brand New Map");
        assertEquals(-1, c.limit());
        assertEquals(-1, c.distance(70));
        assertEquals(HeightLimitCore.Source.NONE, c.source());
        // A denied placement at Y=96 near the centre teaches 95.
        c.onPlaceAttempt(3, 96, -2, 1000);
        c.onHeightLimitMessage(1200);
        assertEquals(95, c.limit());
        assertEquals(HeightLimitCore.Source.LEARNED, c.source());
        assertEquals(Integer.valueOf(95), saved.last.get("Brand New Map"));
    }

    @Test
    public void stoodBlockRaisesAnUnderReportingTable() {
        Saved saved = new Saved();
        HeightLimitCore c = fresh(saved);
        c.onLocation(W1, null, "Lighthouse");
        c.onPlaceAttempt(0, 111, 0, 1000);
        c.onTick(1000 + HeightLimitCore.SETTLE_MS, W1, true, EVERYTHING);
        assertEquals(111, c.limit());
        assertEquals(HeightLimitCore.Source.LEARNED, c.source());
        assertEquals(Integer.valueOf(111), saved.last.get("Lighthouse"));
        assertEquals(1, saved.calls);
        // Settling the same Y again changes nothing and does not re-persist.
        c.onPlaceAttempt(0, 111, 0, 5000);
        c.onTick(5000 + HeightLimitCore.SETTLE_MS, W1, true, EVERYTHING);
        assertEquals(1, saved.calls);
    }

    @Test
    public void deniedNearCentreLowersAnOverReportingTable() {
        Saved saved = new Saved();
        HeightLimitCore c = fresh(saved);
        c.onLocation(W1, null, "Lighthouse");
        c.onPlaceAttempt(10, 110, -5, 1000);
        c.onHeightLimitMessage(1100);
        assertEquals(109, c.limit());
        assertEquals(Integer.valueOf(109), saved.last.get("Lighthouse"));
    }

    @Test
    public void deniedNearTheEdgeIsDomeNotCeiling() {
        Saved saved = new Saved();
        HeightLimitCore c = fresh(saved);
        c.onLocation(W1, null, "Lighthouse");              // radius 99 -> centre zone is 59 blocks
        c.onPlaceAttempt(80, 100, 40, 1000);               // ~89 blocks out
        c.onHeightLimitMessage(1100);
        assertEquals(110, c.limit());
        assertEquals(HeightLimitCore.Source.TABLE, c.source());
        assertEquals(0, saved.calls);
    }

    @Test
    public void lateOrUnattributedMessagesAreIgnored() {
        HeightLimitCore c = fresh(null);
        c.onLocation(W1, null, "Lighthouse");
        c.onHeightLimitMessage(1000);                       // nothing pending
        assertEquals(110, c.limit());
        c.onPlaceAttempt(0, 105, 0, 1000);
        c.onHeightLimitMessage(1000 + HeightLimitCore.SETTLE_MS + 1); // too late to belong to it
        assertEquals(110, c.limit());
    }

    @Test
    public void airAfterSettleIsNoEvidence() {
        HeightLimitCore c = fresh(null);
        c.onLocation(W1, null, "Lighthouse");
        c.onPlaceAttempt(0, 120, 0, 1000);
        c.onTick(1000 + HeightLimitCore.SETTLE_MS, W1, true, NOTHING);  // reverted by the server
        assertEquals(110, c.limit());
        assertEquals(HeightLimitCore.Source.TABLE, c.source());
        assertEquals(Integer.MIN_VALUE, c.observedOkY());
    }

    @Test
    public void stoodBlockOutranksAContradictingDenial() {
        HeightLimitCore c = fresh(null);
        c.onLocation(W1, null, "Lighthouse");
        c.onPlaceAttempt(0, 110, 0, 1000);
        c.onHeightLimitMessage(1050);                       // says < 110
        assertEquals(109, c.limit());
        c.onPlaceAttempt(0, 110, 0, 2000);
        c.onTick(2000 + HeightLimitCore.SETTLE_MS, W1, true, EVERYTHING); // but a block stood at 110
        assertEquals(110, c.limit());
    }

    @Test
    public void learnedValueOverridesTableAndSurvivesRestart() {
        Map<String, Integer> remembered = new HashMap<>();
        remembered.put("Lighthouse", 108);
        remembered.put("Nonsense", 0);                      // ignored
        HeightLimitCore c = new HeightLimitCore(remembered, null);
        assertEquals(1, c.learnedCount());
        c.onLocation(W1, null, "lighthouse");
        assertEquals(108, c.limit());
        assertEquals(HeightLimitCore.Source.LEARNED, c.source());
    }

    @Test
    public void sidebarFallbackAndPrecedence() {
        HeightLimitCore c = fresh(null);
        c.onTick(0, W1, false, null);
        c.onSidebarMap(W1, "Playground");
        assertEquals("Playground", c.map());
        assertEquals(100, c.limit());
        // The Mod API's word for this world wins over the sidebar...
        c.onLocation(W1, "BEDWARS_EIGHT_ONE", "Lighthouse");
        c.onSidebarMap(W1, "Playground");
        assertEquals("Lighthouse", c.map());
        // ...but a location event without a map (a lobby) lets the sidebar speak again.
        c.onLocation(W1, null, null);
        assertNull(c.map());
        c.onSidebarMap(W1, "Playground");
        assertEquals("Playground", c.map());
        // Blank sidebar values never clear a known map.
        c.onSidebarMap(W1, "  ");
        assertEquals("Playground", c.map());
    }

    @Test
    public void worldChangeDropsTheMapAndObservations() {
        HeightLimitCore c = fresh(null);
        c.onLocation(W1, null, "Lighthouse");
        c.onPlaceAttempt(0, 100, 0, 1000);
        c.onTick(1000 + HeightLimitCore.SETTLE_MS, W1, true, EVERYTHING);
        assertEquals(100, c.observedOkY());
        c.onTick(5000, W2, false, null);
        assertNull(c.map());
        assertEquals(-1, c.limit());
        assertEquals(Integer.MIN_VALUE, c.observedOkY());
        assertNull(c.mode());
        // A location tagged with the new world is not wiped by the tick that notices the change.
        c.onLocation(W2, "BEDWARS_FOUR_FOUR", "Temple");
        c.onTick(5100, W2, true, null);
        assertEquals("Temple", c.map());
        assertEquals(106, c.limit());
    }

    @Test
    public void attemptsAreIgnoredOutsideAGameAndWithoutAMap() {
        HeightLimitCore c = fresh(null);
        c.onPlaceAttempt(0, 200, 0, 1000);                   // no map yet
        c.onLocation(W1, null, "Lighthouse");
        c.onHeightLimitMessage(1100);
        assertEquals(110, c.limit());
        c.onPlaceAttempt(0, 120, 0, 2000);
        c.onTick(2100, W1, false, EVERYTHING);              // left the game: pending dropped
        c.onTick(2000 + HeightLimitCore.SETTLE_MS, W1, true, EVERYTHING);
        assertEquals(110, c.limit());
    }

    @Test
    public void recognisesOnlyTheCeilingRefusal() {
        assertTrue(HeightLimitCore.isLimitMessage("Build height limit reached!"));
        assertTrue(HeightLimitCore.isLimitMessage("  build limit reached! "));
        assertTrue(!HeightLimitCore.isLimitMessage("You cannot build this far out!"));
        assertTrue(!HeightLimitCore.isLimitMessage("You cannot build any further down!"));
        assertTrue(!HeightLimitCore.isLimitMessage("You can't place blocks here!"));
        assertTrue(!HeightLimitCore.isLimitMessage(null));
    }

    @Test
    public void storeRoundTrip() throws Exception {
        File f = File.createTempFile("height-limits", ".txt");
        try {
            Map<String, Integer> in = new LinkedHashMap<>();
            in.put("Lighthouse", 110);
            in.put("Santa's Rush", 92);
            in.put("Weird=Name", 80);
            HeightLimitStore.save(f, in);
            Map<String, Integer> out = HeightLimitStore.load(f);
            assertEquals(Integer.valueOf(110), out.get("Lighthouse"));
            assertEquals(Integer.valueOf(92), out.get("Santa's Rush"));
            assertEquals(Integer.valueOf(80), out.get("Weird Name"));
            assertEquals(3, out.size());
            assertTrue(HeightLimitStore.load(new File(f, "missing")).isEmpty());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /**
     * The save writes atomically and replaces the whole file: the directory holds nothing but the
     * target (no {@code *.tmp} sibling) and a second save leaves only its own entries behind.
     */
    @Test
    public void saveLeavesNoTempFileAndReplacesEverything() throws Exception {
        File dir = tmp.newFolder("limits");
        File f = new File(dir, "height-limits.txt");
        Map<String, Integer> first = new LinkedHashMap<>();
        first.put("A", 1);
        first.put("B", 2);
        HeightLimitStore.save(f, first);
        Map<String, Integer> second = new LinkedHashMap<>();
        second.put("C", 3);
        HeightLimitStore.save(f, second);

        assertArrayEquals(new String[]{"height-limits.txt"}, dir.list());
        Map<String, Integer> out = HeightLimitStore.load(f);
        assertEquals(1, out.size());
        assertEquals(Integer.valueOf(3), out.get("C"));
    }
}
