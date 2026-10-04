package com.bedwarsqol.gui.sheet;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class SheetTextTest {

    private static FontMetrics inter() throws IOException {
        String file = "inter 600 upm 2048 ascent 1984 descent 494\n"
                + "a 65 1400\na 86 1300\na 32 500\nk 65 86 -100\n";
        return FontMetrics.read(new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void baselineFollowsCssHalfLeadingWithRoundedMetrics() throws IOException {
        FontMetrics m = inter();
        // "Potion": 10 su in a 12 su line at 2 px/su, line top at 120 px -> baseline 139 in the reference
        assertEquals(139f, SheetText.baseline(m, 20f, 120f, 24f), 0f);
        // line-height 1 is shorter than the content area: the half-leading is negative
        assertEquals(19, SheetText.ascent(m, 20f));
        assertEquals(5, SheetText.descent(m, 20f));
    }

    @Test
    public void widthsAddKerningAndFallBackToQuestionMark() throws IOException {
        FontMetrics m = inter();
        assertEquals((1400 + 1300 - 100) / 2048f * 10f, m.width("AV", 10f), 1e-5f);
        assertEquals((1300 + 1400) / 2048f * 10f, m.width("VA", 10f), 1e-5f);
        assertEquals('?', m.mapped('€'));
    }
}
