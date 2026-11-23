package com.bedwarsqol.rewind.export;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExportGuiScale} (Rewind Export PLAN Solution 2 "Shared rules", Phase 2; TASK P6): the export's GUI scale keeps
 * the HUD the same share of the picture as in the window, within one GUI-scale step.
 */
public class ExportGuiScaleTest {

    @Test
    public void window1600HighAtScale3ExportedAt1080GivesScale2() {
        assertEquals(2, ExportGuiScale.of(3, 1600, 1080));
    }

    @Test
    public void sameHeightKeepsTheWindowScale() {
        assertEquals(2, ExportGuiScale.of(2, 894, 894));
        assertEquals(4, ExportGuiScale.of(4, 1080, 1080));
    }

    @Test
    public void largerExportsScaleUp() {
        assertEquals(4, ExportGuiScale.of(2, 1080, 2160)); // 4K from a 1080p window
        assertEquals(3, ExportGuiScale.of(2, 720, 1080)); // 2 x 1.5
    }

    @Test
    public void neverBelowOne() {
        assertEquals(1, ExportGuiScale.of(1, 2160, 720));
        assertEquals(1, ExportGuiScale.of(1, 2160, 100));
        assertEquals(1, ExportGuiScale.of(0, 900, 1080)); // auto scale reported as 0 by a caller: still 1
        assertEquals(1, ExportGuiScale.of(2, 0, 1080)); // a minimised window (height 0): no division by zero
    }

    @Test
    public void hudShareStaysWithinOneStep() {
        int[] heights = {480, 600, 720, 768, 894, 900, 1080, 1440, 1600, 2160};
        int[] exports = {720, 1080, 1440, 2160};
        for (int wh : heights) {
            for (int scale = 1; scale <= 4; scale++) {
                for (int eh : exports) {
                    int s = ExportGuiScale.of(scale, wh, eh);
                    double want = scale * (double) eh / wh;
                    assertTrue(wh + "@" + scale + "->" + eh + " gave " + s, Math.abs(s - Math.max(1, want)) <= 0.5 + 1e-9
                            || s == 1 && want < 1);
                }
            }
        }
    }
}
