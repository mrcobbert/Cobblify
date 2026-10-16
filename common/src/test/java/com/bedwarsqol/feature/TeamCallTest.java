package com.bedwarsqol.feature;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TeamCallTest {

    private static TeamCall all(String body) {
        return TeamCall.match(body, true, true, true);
    }

    @Test
    public void wordsMatchInAnyCase() {
        for (String body : new String[]{" BED", " bed!!", " Bed?", " bed", " they're at bed", " bEd 2"}) {
            assertSame(body, TeamCall.BED, all(body));
        }
        for (String body : new String[]{" bg", " BG", " Bg!", " bg?"}) assertSame(body, TeamCall.BG, all(body));
        for (String body : new String[]{" inc", " INC", " Incoming!", " inc mid"}) {
            assertSame(body, TeamCall.INC, all(body));
        }
    }

    @Test
    public void lettersOnEitherSideDisqualify() {
        for (String body : new String[]{" beds", " bedwars", " bedrock", " bgs", " abed", " sbg", " since",
                " include", " incom", ""}) {
            assertNull(body, all(body));
        }
        assertNull(all(null));
    }

    @Test
    public void nonLettersAroundTheWordStillMatch() {
        assertSame(TeamCall.BED, all(" (bed)"));
        assertSame(TeamCall.BED, all(" bed2"));
        assertSame(TeamCall.BG, all(" bg's"));
    }

    @Test
    public void incBeatsBedBeatsBg() {
        assertSame(TeamCall.INC, all(" bed inc"));
        assertSame(TeamCall.INC, all(" bg bed incoming"));
        assertSame(TeamCall.BED, all(" bg bed"));
        assertSame(TeamCall.BED, all(" bed bg"));
    }

    @Test
    public void aDisabledCallFallsThroughToTheNextEnabledOne() {
        assertSame(TeamCall.BED, TeamCall.match(" bed inc", false, true, true));
        assertSame(TeamCall.BG, TeamCall.match(" bed inc bg", false, false, true));
        assertSame(TeamCall.INC, TeamCall.match(" bed inc", true, false, true));
        assertNull(TeamCall.match(" inc", false, true, true));
        assertNull(TeamCall.match(" bed", true, false, true));
        assertNull(TeamCall.match(" bg", true, true, false));
        assertNull(TeamCall.match(" inc bed bg", false, false, false));
    }

    @Test
    public void incKeepsItsRisingDoublePling() {
        TeamCallAlerts alerts = new TeamCallAlerts();
        assertNotes(alerts.start(TeamCall.INC, 10_000L), "note.pling", 1.6f);
        assertTrue(alerts.tick().isEmpty());
        assertTrue(alerts.tick().isEmpty());
        assertNotes(alerts.tick(), "note.pling", 2.0f);
        assertTrue(alerts.tick().isEmpty());
    }

    @Test
    public void bedIsAQuickTriplePlingAndBgOneBassNote() {
        TeamCallAlerts alerts = new TeamCallAlerts();
        assertNotes(alerts.start(TeamCall.BED, 10_000L), "note.pling", 1.2f);
        assertTrue(alerts.tick().isEmpty());
        assertNotes(alerts.tick(), "note.pling", 1.2f);
        assertTrue(alerts.tick().isEmpty());
        assertNotes(alerts.tick(), "note.pling", 1.2f);
        assertTrue(alerts.tick().isEmpty());

        assertNotes(alerts.start(TeamCall.BG, 10_000L), "note.bass", 1.0f);
        assertTrue(alerts.tick().isEmpty());
    }

    @Test
    public void eachCallHasItsOwnDebounce() {
        TeamCallAlerts alerts = new TeamCallAlerts();
        long t = 10_000L;
        assertEquals(1, alerts.start(TeamCall.BED, t).size());
        assertTrue("the same call inside the window is silent", alerts.start(TeamCall.BED, t + 1500L).isEmpty());
        assertEquals("another call is not held back", 1, alerts.start(TeamCall.BG, t + 100L).size());
        assertEquals(1, alerts.start(TeamCall.INC, t + 100L).size());
        assertEquals("a silenced repeat does not extend the window", 1, alerts.start(TeamCall.BED, t + 1501L).size());
    }

    @Test
    public void overlappingCallsKeepTheirLaterNotes() {
        TeamCallAlerts alerts = new TeamCallAlerts();
        alerts.start(TeamCall.INC, 10_000L);
        alerts.tick();
        alerts.start(TeamCall.BED, 10_050L);
        assertTrue(alerts.tick().isEmpty());
        List<TeamCall.Note> due = alerts.tick();
        assertEquals("Inc's second pling and Bed's second land on the same tick", 2, due.size());
        assertEquals(2.0f, due.get(0).pitch, 0f);
        assertEquals(1.2f, due.get(1).pitch, 0f);
    }

    private static void assertNotes(List<TeamCall.Note> notes, String sound, float pitch) {
        assertEquals(1, notes.size());
        assertEquals(sound, notes.get(0).sound);
        assertEquals(pitch, notes.get(0).pitch, 0f);
    }
}
