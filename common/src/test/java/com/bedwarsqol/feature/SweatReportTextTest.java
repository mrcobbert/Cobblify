package com.bedwarsqol.feature;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pins the SweatReport payload: one line per team, sweatiest first, length cap, line cap. */
public class SweatReportTextTest {

    private static SweatReportText.Entry known(String name, double fkdr) {
        return new SweatReportText.Entry(name, SweatReportText.fmt1(fkdr), fkdr, true);
    }

    private static SweatReportText.Entry unknown(String name, String tag) {
        return new SweatReportText.Entry(name, tag, 0.0, false);
    }

    @Test
    public void emptyInputYieldsNoLines() {
        assertTrue(SweatReportText.lines(null).isEmpty());
        assertTrue(SweatReportText.lines(new ArrayList<SweatReportText.Team>()).isEmpty());
        assertTrue(SweatReportText.lines(Arrays.asList(
                new SweatReportText.Team("RED", new ArrayList<SweatReportText.Entry>()))).isEmpty());
    }

    @Test
    public void oneLinePerTeamWithAverageAndMembers() {
        List<String> lines = SweatReportText.lines(Arrays.asList(
                new SweatReportText.Team("RED", Arrays.asList(known("Techno", 8.4), known("Skeppy", 2.6))),
                new SweatReportText.Team("BLUE (US)", Arrays.asList(known("You", 3.0), known("Mate", 1.0)))));
        assertEquals(2, lines.size());
        assertEquals("RED(5.5): Techno 8.4, Skeppy 2.6", lines.get(0));
        assertEquals("BLUE (US)(2.0): You 3.0, Mate 1.0", lines.get(1));
    }

    @Test
    public void teamsOrderedBySweatiestAverageAndUnknownTeamsLast() {
        List<String> lines = SweatReportText.lines(Arrays.asList(
                new SweatReportText.Team("GREEN", Arrays.asList(unknown("Ghost", "err"))),
                new SweatReportText.Team("BLUE", Arrays.asList(known("Mid", 3.0))),
                new SweatReportText.Team("RED", Arrays.asList(known("High", 9.0)))));
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("RED("));
        assertTrue(lines.get(1).startsWith("BLUE("));
        assertEquals("GREEN(?): Ghost err", lines.get(2));
    }

    @Test
    public void unknownMembersSortLastAndKeepTheirTag() {
        String line = SweatReportText.lines(Arrays.asList(new SweatReportText.Team("RED", Arrays.asList(
                unknown("Nicked", "nick"), known("Pro", 7.0), unknown("Broken", "err"),
                unknown("Slow", "..."), known("Rookie", 0.5))))).get(0);
        assertTrue(line.indexOf("Pro") < line.indexOf("Rookie"));
        assertTrue(line.indexOf("Rookie") < line.indexOf("Nicked"));
        assertTrue(line.contains("Nicked nick"));
        assertTrue(line.contains("Broken err"));
        assertTrue(line.contains("Slow ..."));
    }

    @Test
    public void averageIgnoresUnknownMembers() {
        String line = SweatReportText.lines(Arrays.asList(new SweatReportText.Team("RED", Arrays.asList(
                known("A", 4.0), known("B", 2.0), unknown("C", "nick"))))).get(0);
        assertTrue(line.startsWith("RED(3.0): "));
    }

    @Test
    public void longTeamTruncatesWithPlusCountUnderCap() {
        List<SweatReportText.Entry> members = new ArrayList<SweatReportText.Entry>();
        for (int i = 0; i < 20; i++) members.add(known("LongPlayerName" + i, 7.7));
        String line = SweatReportText.lines(
                Arrays.asList(new SweatReportText.Team("YELLOW", members))).get(0);
        assertTrue(line.length() <= SweatReportText.MAX_CONTENT);
        assertTrue(line.matches(".*\\+\\d+$"));
    }

    @Test
    public void capsLineCountAndReportsDroppedTeams() {
        List<SweatReportText.Team> teams = new ArrayList<SweatReportText.Team>();
        for (int i = 0; i < 8; i++) {
            teams.add(new SweatReportText.Team("T" + i, Arrays.asList(known("P" + i, i))));
        }
        List<String> lines = SweatReportText.lines(teams);
        assertEquals(SweatReportText.MAX_LINES, lines.size());
        assertTrue(lines.get(0).startsWith("T7(")); // sweatiest first
        assertTrue(lines.get(lines.size() - 1).endsWith(", +4 teams"));
        for (String line : lines) {
            assertTrue(line.length() <= SweatReportText.MAX_CONTENT);
        }
    }

    @Test
    public void noDroppedTeamsTailWhenEverythingFits() {
        List<String> lines = SweatReportText.lines(Arrays.asList(
                new SweatReportText.Team("RED", Arrays.asList(known("A", 1.0)))));
        assertEquals(1, lines.size());
        assertFalse(lines.get(0).contains("teams"));
    }
}
