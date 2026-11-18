package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Marker navigation (round 1 I2, round 2 I3): landing 3 s before a marker, never before the game start; a cursor on
 * the marker last jumped to, so next and previous visit every marker even when landings coincide.
 */
public class MarkerNavTest {

    private static final long S = 1_000_000L;
    /** Two ticks: "the clock 0.1 s past each landing". */
    private static final long PAST = 2;

    /** Origin and game start at 0, so tick k is k x 50 ms of game time. */
    private static MarkerNav nav(long... markerUs) {
        List<Timeline.Marker> ms = new ArrayList<Timeline.Marker>();
        for (long m : markerUs) {
            ms.add(new Timeline.Marker(m, RewindFormat.MARKER_DEATH));
        }
        return new MarkerNav(Timeline.build(0, 120 * S, 0, 0, ms, null));
    }

    private static long tick(long us) {
        return us / Timeline.TICK_US;
    }

    @Test
    public void landingIsThreeSecondsBeforeTheMarkerNeverBeforeTheStart() {
        MarkerNav n = nav(10 * S, 2 * S);
        assertEquals(2, n.count());
        assertEquals(2 * S, n.markerUs(0));
        assertEquals(0, n.landingUs(0));
        assertEquals(10 * S, n.markerUs(1));
        assertEquals(7 * S, n.landingUs(1));
        assertEquals(140, n.landingTick(1));
        assertEquals(200, n.markerTick(1));
    }

    @Test
    public void nextVisitsEachMarkerPaused() {
        MarkerNav n = nav(10 * S, 20 * S, 30 * S);
        long p = 0;
        long[] landings = {7 * S, 17 * S, 27 * S};
        for (int i = 0; i < 3; i++) {
            assertEquals(i, n.next(p));
            assertEquals(landings[i], n.landingUs(i));
            p = n.landingTick(i);
            assertEquals("cursor", i, n.cursor(p));
        }
        assertEquals("then nothing", -1, n.next(p));
        assertEquals("the cursor stays", 2, n.cursor(p));
    }

    @Test
    public void nextVisitsEachMarkerWithTheClockPastEachLanding() {
        MarkerNav n = nav(10 * S, 20 * S, 30 * S);
        long p = 0;
        for (int i = 0; i < 3; i++) {
            assertEquals(i, n.next(p));
            p = n.landingTick(i) + PAST;
            assertEquals(i, n.cursor(p));
        }
        assertEquals(-1, n.next(p));
    }

    @Test
    public void previousStepsBackFromTheCursor() {
        MarkerNav n = nav(10 * S, 20 * S, 30 * S);
        assertEquals(2, n.jump(2));
        long p = n.landingTick(2);
        assertEquals(tick(27 * S), p);
        assertEquals(1, n.previous(p));
        p = n.landingTick(1);
        assertEquals(tick(17 * S), p);
        assertEquals(0, n.previous(p + PAST));
        p = n.landingTick(0);
        assertEquals(tick(7 * S), p);
        assertEquals(-1, n.previous(p));
        assertEquals(0, n.cursor(p));
    }

    @Test
    public void previousWithoutACursorGoesToTheLastMarkerPassed() {
        MarkerNav n = nav(10 * S, 20 * S, 30 * S);
        assertEquals(-1, n.cursor(tick(15 * S)));
        assertEquals(0, n.base(tick(15 * S)));
        assertEquals(0, n.previous(tick(15 * S)));
        assertEquals(7 * S, n.landingUs(0));
        assertEquals(-1, nav(10 * S).previous(tick(5 * S)));
    }

    @Test
    public void markersInTheFirstThreeSecondsAreEachVisited() {
        MarkerNav n = nav(1 * S, 2 * S, 10 * S);
        long p = 0;
        assertEquals(0, n.next(p));
        assertEquals(0, n.landingUs(0));
        p = n.landingTick(0);
        assertEquals(0, n.cursor(p));
        assertEquals(1, n.next(p));
        assertEquals(0, n.landingUs(1));
        p = n.landingTick(1);
        assertEquals(1, n.cursor(p));
        assertEquals(2, n.next(p));
        assertEquals(7 * S, n.landingUs(2));
        p = n.landingTick(2);
        assertEquals(1, n.previous(p));
        p = n.landingTick(1);
        assertEquals(0, n.previous(p));
        assertEquals(0, n.cursor(n.landingTick(0)));
    }

    @Test
    public void markersThirtyMillisecondsApartAreVisitedOneByOne() {
        MarkerNav n = nav(10 * S, 10 * S + 30_000L, 10 * S + 60_000L, 10 * S + 90_000L);
        long p = 0;
        for (int i = 0; i < 4; i++) {
            assertEquals("next " + i, i, n.next(p));
            p = n.landingTick(i);
            assertEquals(i, n.cursor(p));
        }
        assertEquals(-1, n.next(p));
        for (int i = 2; i >= 0; i--) {
            assertEquals("previous " + i, i, n.previous(p));
            p = n.landingTick(i);
        }
    }

    @Test
    public void markersWhoseLandingsShareTheStartTickAreEachVisited() {
        MarkerNav n = nav(20_000L, 40_000L, 60_000L);
        long p = 0;
        for (int i = 0; i < 3; i++) {
            assertEquals(i, n.next(p));
            p = n.landingTick(i);
            assertEquals(0, p);
            assertEquals(i, n.cursor(p));
        }
        assertEquals(-1, n.next(p));
    }

    @Test
    public void playingPastTheCursorsMarkerClearsIt() {
        MarkerNav n = nav(10 * S, 20 * S, 30 * S);
        n.next(0);
        long p = n.landingTick(0);
        assertEquals(0, n.cursor(p));
        p = n.markerTick(0);
        assertEquals("still in the lead-in up to the marker's own tick", 0, n.cursor(p));
        p = n.markerTick(0) + 1;
        assertEquals("played past", -1, n.cursor(p));
        assertEquals(-1, n.cursor(n.landingTick(0)));
        assertEquals("next from base", 1, n.next(p));
    }

    @Test
    public void anyOtherSeekClearsIt() {
        MarkerNav n = nav(10 * S, 20 * S, 30 * S);
        n.next(0);
        n.next(n.landingTick(0));
        assertEquals(1, n.cursor(n.landingTick(1)));
        n.clear(); // e.g. Left 5 s from 17 s to 12 s
        long p = tick(12 * S);
        assertEquals(-1, n.cursor(p));
        assertEquals(1, n.next(p));
        n.clear();
        assertEquals("a seek inside the lead-in also clears it", -1, n.cursor(n.landingTick(1)));
        assertEquals(1, n.next(tick(16 * S)));
    }

    @Test
    public void aClickedMarkerBecomesTheCursor() {
        MarkerNav n = nav(10 * S, 20 * S, 30 * S);
        assertEquals(1, n.jump(1));
        assertEquals(1, n.cursor(n.landingTick(1)));
        assertEquals(2, n.next(n.landingTick(1)));
        assertEquals(-1, n.jump(3));
        assertEquals(-1, n.jump(-1));
    }

    @Test
    public void noMarkers() {
        MarkerNav n = nav();
        assertEquals(0, n.count());
        assertEquals(-1, n.next(0));
        assertEquals(-1, n.previous(100));
        assertEquals(-1, n.cursor(0));
    }
}
