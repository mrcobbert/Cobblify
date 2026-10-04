package com.bedwarsqol.hud;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MouseKeyLabelTest {

    @Test
    public void bothLabelsAreEightPixelsTallAndTwentyOneWide() {
        assertTrue(MouseKeyLabel.has("LMB"));
        assertTrue(MouseKeyLabel.has("RMB"));
        assertFalse(MouseKeyLabel.has("W"));
        assertEquals(21, MouseKeyLabel.width("LMB"));
        assertEquals(21, MouseKeyLabel.width("RMB"));
        for (String label : new String[]{"LMB", "RMB"}) {
            final int[] bottom = {0}, right = {0};
            MouseKeyLabel.runs(label, (x, y, w) -> {
                bottom[0] = Math.max(bottom[0], y + 1);
                right[0] = Math.max(right[0], x + w);
            });
            assertEquals(MouseKeyLabel.HEIGHT, bottom[0]);
            assertEquals(MouseKeyLabel.width(label), right[0]);
        }
    }

    @Test
    public void lettersAreOnePixelApart() {
        // L's foot ends at 6, M starts at 7; M ends at 14, B starts at 15
        List<int[]> runs = new ArrayList<int[]>();
        MouseKeyLabel.runs("LMB", (x, y, w) -> runs.add(new int[]{x, y, w}));
        boolean[] used = new boolean[21];
        for (int[] r : runs) for (int i = r[0]; i < r[0] + r[2]; i++) used[i] = true;
        assertFalse(used[6]);
        assertFalse(used[14]);
        assertTrue(used[7]);
        assertTrue(used[15]);
    }

    @Test
    public void pixelsMatchTheModernLabelsHeightInWholePixels() {
        assertEquals(1, MouseKeyLabel.pixel(4f));
        assertEquals(1, MouseKeyLabel.pixel(8.3f));
        assertEquals(2, MouseKeyLabel.pixel(12.5f));
        assertEquals(2, MouseKeyLabel.pixel(16.6f));
    }
}
