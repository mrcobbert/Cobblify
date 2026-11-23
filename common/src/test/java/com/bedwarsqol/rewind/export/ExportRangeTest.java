package com.bedwarsqol.rewind.export;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link ExportRange} (G38; Rewind Export PLAN Solution 5, Phase 6; TASK U3): where the handles start, that they stay
 * inside the game, and that the range is never shorter than 1 s. Times are microseconds from the game start.
 */
public class ExportRangeTest {

    private static final long S = 1_000_000L;
    private static final long GAME = 600 * S;

    private static void assertRange(long start, long end, ExportRange r) {
        assertEquals("start", start, r.startUs());
        assertEquals("end", end, r.endUs());
    }

    @Test
    public void aMarkerAt100sGives90To105() {
        assertRange(90 * S, 105 * S, ExportRange.around(GAME, 100 * S));
    }

    @Test
    public void nearTheStartOrTheEndItIsClampedAndStillAtLeastOneSecond() {
        assertRange(0, 9 * S, ExportRange.around(GAME, 4 * S));
        assertRange(0, 5 * S, ExportRange.around(GAME, 0));
        assertRange(GAME - 12 * S, GAME, ExportRange.around(GAME, GAME - 2 * S));
        assertRange(GAME - 10 * S, GAME, ExportRange.around(GAME, GAME));
        assertRange(GAME - 10 * S, GAME, ExportRange.around(GAME, GAME + 30 * S)); // a centre past the end
        assertRange(0, 5 * S, ExportRange.around(GAME, -3 * S));
        // a 3 s game: the whole game
        assertRange(0, 3 * S, ExportRange.around(3 * S, 1 * S));
        for (long c = 0; c <= GAME; c += 7 * S) {
            ExportRange r = ExportRange.around(GAME, c);
            assertEquals(true, r.startUs() >= 0 && r.endUs() <= GAME && r.endUs() - r.startUs() >= S);
        }
    }

    @Test
    public void withNoMarkerItSitsAroundThePlayhead() {
        assertRange(190 * S, 205 * S, ExportRange.forViewer(GAME, -1, 200 * S));
        assertRange(90 * S, 105 * S, ExportRange.forViewer(GAME, 100 * S, 200 * S)); // the marker wins
    }

    @Test
    public void fromTheGalleryAroundTheFirstMarkerOrTheFirst15Seconds() {
        assertRange(30 * S, 45 * S, ExportRange.forGallery(GAME, 40 * S));
        assertRange(0, 15 * S, ExportRange.forGallery(GAME, -1));
        assertRange(0, 8 * S, ExportRange.forGallery(8 * S, -1));
    }

    @Test
    public void draggingStartPastTheEndStopsOneSecondShort() {
        ExportRange r = ExportRange.around(GAME, 100 * S);
        assertRange(95 * S, 105 * S, r.dragStart(95 * S));
        assertRange(104 * S, 105 * S, r.dragStart(200 * S));
        assertRange(0, 105 * S, r.dragStart(-5 * S));
        assertRange(90 * S, 91 * S, r.dragEnd(50 * S));
        assertRange(90 * S, GAME, r.dragEnd(GAME + S));
        assertRange(90 * S, 300 * S, r.dragEnd(300 * S));
        // which handle: 0 start, 1 end
        assertRange(95 * S, 105 * S, r.drag(0, 95 * S));
        assertRange(90 * S, 120 * S, r.drag(1, 120 * S));
    }

    @Test
    public void aGameShorterThanOneSecondIsTheWholeGame() {
        long len = 600_000L;
        assertRange(0, len, ExportRange.around(len, 300_000L));
        assertRange(0, len, ExportRange.forGallery(len, -1));
        ExportRange r = ExportRange.around(len, 0);
        assertRange(0, len, r.dragStart(400_000L));
        assertRange(0, len, r.dragEnd(100_000L));
    }

    @Test
    public void theRangeKeepsTheGamesLength() {
        assertEquals(GAME, ExportRange.around(GAME, 100 * S).lengthUs());
    }
}
