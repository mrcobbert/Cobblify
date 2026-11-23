package com.bedwarsqol.rewind.export;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExportQuality} (Rewind Export PLAN D7; TASK P10): base x (pixels / 1080p)^0.85 x (fps / 60)^0.6, HEVC x 0.6,
 * rounded to 0.5 Mbit/s, capped at 150.
 */
public class ExportQualityTest {

    private static final String[] Q = {"standard", "high", "max"};

    private static void anchors(int w, int h, int fps, double... mbps) {
        for (int i = 0; i < 3; i++) {
            assertEquals(w + "x" + h + "@" + fps + " " + Q[i], mbps[i], ExportQuality.mbps(w, h, fps, Q[i], "h264"),
                    1e-9);
            assertEquals((long) Math.round(mbps[i] * 1_000_000), ExportQuality.bitsPerSecond(w, h, fps, Q[i], "h264"));
        }
    }

    @Test
    public void theD7AnchorsHold() {
        anchors(1280, 720, 60, 4, 8, 15);
        anchors(1920, 1080, 30, 5.5, 10.5, 20);
        anchors(1920, 1080, 60, 8, 16, 30);
        anchors(2560, 1440, 60, 13, 26, 49);
        anchors(3840, 2160, 60, 26, 52, 97.5);
    }

    @Test
    public void hevcIsSixTenthsBeforeRounding() {
        assertEquals(9.5, ExportQuality.mbps(1920, 1080, 60, "high", "hevc"), 1e-9); // 16 x 0.6 = 9.6
        assertEquals(5.0, ExportQuality.mbps(1920, 1080, 60, "standard", "hevc"), 1e-9); // 4.8
        assertEquals(18.0, ExportQuality.mbps(1920, 1080, 60, "max", "hevc"), 1e-9);
    }

    @Test
    public void theRateIsCappedAt150AndAlwaysAHalfMegabitStep() {
        assertEquals(150.0, ExportQuality.mbps(3840, 2160, 240, "max", "h264"), 1e-9);
        for (int h : ExportSettings.HEIGHTS) {
            for (int fps : ExportSettings.RATES) {
                for (String q : Q) {
                    for (String c : ExportSettings.CODECS) {
                        double m = ExportQuality.mbps(ExportSettings.width(h), h, fps, q, c);
                        assertTrue(m > 0 && m <= ExportQuality.CAP_MBPS);
                        assertEquals(0.0, m * 2 - Math.rint(m * 2), 1e-9);
                    }
                }
            }
        }
    }

    @Test
    public void theThreeQualitiesGiveThreeRatesInOrder() {
        long s = ExportQuality.bitsPerSecond(1920, 1080, 60, "standard", "h264");
        long hi = ExportQuality.bitsPerSecond(1920, 1080, 60, "high", "h264");
        long m = ExportQuality.bitsPerSecond(1920, 1080, 60, "max", "h264");
        assertTrue(s < hi && hi < m);
        assertEquals("an unknown quality reads as High", hi, ExportQuality.bitsPerSecond(1920, 1080, 60, "ultra", "h264"));
    }
}
