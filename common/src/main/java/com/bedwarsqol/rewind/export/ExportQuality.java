package com.bedwarsqol.rewind.export;

/**
 * The bit rate an export asks the helper for (Rewind Export PLAN D7; TASK P10): {@code base x (pixels / 1080p
 * pixels)^0.85 x (fps / 60)^0.6}, HEVC {@code x 0.6}, rounded to 0.5 Mbit/s, capped at {@value #CAP_MBPS}. Bases at
 * 1080p60 H.264: Standard 8, High 16, Max 30 Mbit/s (decision 6). Anchors: 720p60 4 / 8 / 15; 1080p30 5.5 / 10.5 / 20;
 * 1080p60 8 / 16 / 30; 1440p60 13 / 26 / 49; 4K60 26 / 52 / 97.5. Pure.
 */
public final class ExportQuality {

    /** The highest rate asked for, Mbit/s. */
    public static final double CAP_MBPS = 150;

    private static final double PIXELS_1080P = 1920.0 * 1080.0;

    private ExportQuality() {
    }

    /** Mbit/s for the size, rate, quality ({@code standard|high|max}; anything else reads as High) and codec. */
    public static double mbps(int width, int height, int fps, String quality, String codec) {
        double base = "standard".equals(quality) ? 8 : "max".equals(quality) ? 30 : 16;
        double m = base * Math.pow(width * (double) height / PIXELS_1080P, 0.85) * Math.pow(fps / 60.0, 0.6);
        if ("hevc".equals(codec)) {
            m *= 0.6;
        }
        m = Math.round(m * 2) / 2.0;
        return Math.max(0.5, Math.min(CAP_MBPS, m));
    }

    /** {@link #mbps} in bits per second, as the helper's {@code --bitrate} takes it. */
    public static long bitsPerSecond(int width, int height, int fps, String quality, String codec) {
        return Math.round(mbps(width, height, fps, quality, codec) * 1_000_000);
    }
}
