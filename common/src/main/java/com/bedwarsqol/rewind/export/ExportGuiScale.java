package com.bedwarsqol.rewind.export;

/**
 * The GUI scale an export frame is drawn at (Rewind Export PLAN Solution 2 "Shared rules"; TASK P6): the window's
 * scale factor times the export height over the window height, rounded, at least 1. So the HUD, chat and recorded
 * screens take the same share of the picture as in the window, within one GUI-scale step, whatever the export size.
 * Pure.
 */
public final class ExportGuiScale {

    private ExportGuiScale() {
    }

    /**
     * @param windowScale  the window's effective GUI scale factor ({@code ScaledResolution.getScaleFactor()})
     * @param windowHeight the window's height in pixels
     * @param exportHeight the export's height in pixels
     * @return {@code max(1, round(windowScale x exportHeight / windowHeight))}
     */
    public static int of(int windowScale, int windowHeight, int exportHeight) {
        if (windowScale <= 0 || windowHeight <= 0 || exportHeight <= 0) {
            return 1;
        }
        long s = Math.round(windowScale * (double) exportHeight / windowHeight);
        return (int) Math.max(1L, Math.min(s, 64L));
    }
}
