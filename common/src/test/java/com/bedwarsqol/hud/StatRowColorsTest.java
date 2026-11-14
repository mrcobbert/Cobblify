package com.bedwarsqol.hud;

import com.bedwarsqol.gui.StatRowSegments;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Map Info labels take the accent with white values; Session Stats rows keep the neutral stat colours. */
public class StatRowColorsTest {

    private static final int ACCENT = 0xFFC0DCFF; // pale blue

    /** Each run of {@code row} as "text=colour". */
    private static List<String> coloured(String row, int labelColor) {
        List<String> out = new ArrayList<String>();
        for (StatRowSegments.Segment seg : StatRowSegments.split(row)) {
            out.add(seg.text + "=" + Integer.toHexString(StatRowColors.of(seg.kind, labelColor)));
        }
        return out;
    }

    @Test
    public void mapInfoLabelsTakeTheAccentValuesStayWhite() {
        assertEquals(Arrays.asList("Map: =ffc0dcff", "Lighthouse=ffffffff"), coloured("Map: Lighthouse", ACCENT));
        assertEquals(Arrays.asList("Height Limit: =ffc0dcff", "110=ffffffff"), coloured("Height Limit: 110", ACCENT));
        assertEquals(Arrays.asList("Distance: =ffc0dcff", "27=ffffffff"), coloured("Distance: 27", ACCENT));
    }

    @Test
    public void sessionRowsKeepNeutralColours() {
        assertEquals(Arrays.asList("Finals: =ffaaaaaa", "12=ffffffff", " / =ff666666", "FKDR: =ffaaaaaa",
                "3.00=ffffffff"), coloured("Finals: 12 / FKDR: 3.00", StatRowColors.LABEL_NEUTRAL));
        assertEquals(0xFFAAAAAA, StatRowColors.LABEL_NEUTRAL);
        assertEquals(0xFFFFFFFF, StatRowColors.VALUE);
        assertEquals(0xFF666666, StatRowColors.SLASH);
    }
}
