package com.bedwarsqol.gui;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StatRowSegmentsTest {

    private static String render(List<StatRowSegments.Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (StatRowSegments.Segment s : segments) sb.append(s);
        return sb.toString();
    }

    @Test
    public void labelAndValue() {
        assertEquals("LABEL(Map: )VALUE(Lighthouse)", render(StatRowSegments.split("Map: Lighthouse")));
        assertEquals("LABEL(Height Limit: )VALUE(110)", render(StatRowSegments.split("Height Limit: 110")));
    }

    @Test
    public void counterAndRatioPair() {
        assertEquals("LABEL(Finals: )VALUE(12)SEPARATOR( / )LABEL(FKDR: )VALUE(3.00)",
                render(StatRowSegments.split("Finals: 12 / FKDR: 3.00")));
    }

    @Test
    public void headerIsOneLabel() {
        assertEquals("LABEL(Session)", render(StatRowSegments.split("Session")));
    }

    @Test
    public void unknownValueStillColoursAsValue() {
        assertEquals("LABEL(Distance: )VALUE(?)", render(StatRowSegments.split("Distance: ?")));
    }

    @Test
    public void emptyRowsHaveNoRuns() {
        assertTrue(StatRowSegments.split("").isEmpty());
        assertTrue(StatRowSegments.split(null).isEmpty());
    }

    @Test
    public void runsRebuildTheRowExactly() {
        String row = "Kills: 40 / KDR: 2.00";
        StringBuilder sb = new StringBuilder();
        for (StatRowSegments.Segment s : StatRowSegments.split(row)) sb.append(s.text);
        assertEquals(row, sb.toString());
    }
}
