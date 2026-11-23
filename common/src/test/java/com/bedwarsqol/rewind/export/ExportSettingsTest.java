package com.bedwarsqol.rewind.export;

import com.bedwarsqol.rewind.RewindDefaults;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExportSettings} (G10, G12, G42; Rewind Export PLAN Solution 5, Phase 6; TASK U4): the five values, their
 * first-time defaults, and out-of-list values reset to those defaults.
 */
public class ExportSettingsTest {

    @Test
    public void defaultsAre1080p60HighH264WithChat() {
        ExportSettings d = ExportSettings.defaults();
        assertEquals(1080, d.height());
        assertEquals(60, d.fps());
        assertEquals("high", d.quality());
        assertEquals("h264", d.codec());
        assertTrue(d.chat());
        assertEquals(RewindDefaults.DEFAULT_EXPORT_HEIGHT, d.height());
        assertEquals(RewindDefaults.DEFAULT_EXPORT_FPS, d.fps());
        assertEquals(RewindDefaults.DEFAULT_EXPORT_QUALITY, d.quality());
        assertEquals(RewindDefaults.DEFAULT_EXPORT_CODEC, d.codec());
        assertEquals(RewindDefaults.DEFAULT_EXPORT_CHAT, d.chat());
        assertEquals(1920, ExportSettings.width(d.height()));
    }

    @Test
    public void listedValuesAreKept() {
        ExportSettings s = ExportSettings.of(2160, 240, "max", "hevc", false);
        assertEquals(2160, s.height());
        assertEquals(240, s.fps());
        assertEquals("max", s.quality());
        assertEquals("hevc", s.codec());
        assertFalse(s.chat());
        ExportSettings t = ExportSettings.of(720, 30, "standard", "h264", true);
        assertEquals(720, t.height());
        assertEquals(30, t.fps());
        assertEquals("standard", t.quality());
    }

    @Test
    public void outOfListValuesAreReset() {
        ExportSettings s = ExportSettings.of(1000, 59, "ultra", "av1", false);
        assertEquals(1080, s.height());
        assertEquals(60, s.fps());
        assertEquals("high", s.quality());
        assertEquals("h264", s.codec());
        assertFalse("chat is a plain switch", s.chat());
        ExportSettings n = ExportSettings.of(0, -1, null, null, true);
        assertEquals(1080, n.height());
        assertEquals(60, n.fps());
        assertEquals("high", n.quality());
        assertEquals("h264", n.codec());
    }

    @Test
    public void everyHeightIsSixteenByNine() {
        int[] heights = {720, 1080, 1440, 2160};
        int[] widths = {1280, 1920, 2560, 3840};
        for (int i = 0; i < heights.length; i++) {
            assertEquals(heights[i], ExportSettings.HEIGHTS[i]);
            assertEquals(widths[i], ExportSettings.width(heights[i]));
        }
        assertEquals(4, ExportSettings.RATES.length);
        assertEquals(3, ExportSettings.QUALITIES.length);
        assertEquals(2, ExportSettings.CODECS.length);
    }
}
