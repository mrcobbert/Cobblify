package com.bedwarsqol.feature;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pins SweatReport condensation: one line, hard length cap, sweat-first ordering. */
public class SweatReportTextTest {

    @Test
    public void emptyInputYieldsNoLines() {
        assertTrue(SweatReportText.condense(null).isEmpty());
        assertTrue(SweatReportText.condense(new ArrayList<SweatReportText.Entry>()).isEmpty());
    }

    @Test
    public void producesAtMostOneLine() {
        List<SweatReportText.Entry> entries = new ArrayList<SweatReportText.Entry>();
        for (int i = 0; i < 20; i++) {
            entries.add(new SweatReportText.Entry("RED", false, "Player" + i, "9.9", 9.9, true));
        }
        List<String> lines = SweatReportText.condense(entries);
        assertEquals(1, lines.size());
        assertEquals(SweatReportText.MAX_LINES, 1);
        assertTrue(lines.get(0).length() <= SweatReportText.MAX_CONTENT);
        assertTrue(lines.get(0).startsWith("Sweats: "));
    }

    @Test
    public void ordersByFkdrDescending() {
        List<String> lines = SweatReportText.condense(Arrays.asList(
                new SweatReportText.Entry("BLUE", false, "Low", "1.0", 1.0, true),
                new SweatReportText.Entry("RED", false, "High", "8.5", 8.5, true),
                new SweatReportText.Entry("GREEN", false, "Mid", "3.2", 3.2, true)
        ));
        assertEquals(1, lines.size());
        String line = lines.get(0);
        int high = line.indexOf("High");
        int mid = line.indexOf("Mid");
        int low = line.indexOf("Low");
        assertTrue(high >= 0 && mid >= 0 && low >= 0);
        assertTrue(high < mid && mid < low);
    }

    @Test
    public void marksOurTeamWithUsSuffix() {
        List<String> lines = SweatReportText.condense(Arrays.asList(
                new SweatReportText.Entry("RED", true, "You", "2.0", 2.0, true),
                new SweatReportText.Entry("BLUE", false, "Pro", "8.0", 8.0, true)
        ));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("RED/US"));
        assertTrue(lines.get(0).contains("You"));
    }

    @Test
    public void truncatesWithPlusCountUnderCap() {
        List<SweatReportText.Entry> entries = new ArrayList<SweatReportText.Entry>();
        for (int i = 0; i < 30; i++) {
            entries.add(new SweatReportText.Entry("BLUE", false, "Nameeeeeee" + i, "7.7", 7.7, true));
        }
        String line = SweatReportText.condense(entries).get(0);
        assertTrue(line.contains("+"));
        assertTrue(line.length() <= SweatReportText.MAX_CONTENT);
    }
}
