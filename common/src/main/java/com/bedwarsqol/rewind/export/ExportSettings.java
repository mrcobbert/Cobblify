package com.bedwarsqol.rewind.export;

import com.bedwarsqol.rewind.RewindDefaults;

/**
 * The five export settings (G10, G12, G42; Rewind Export PLAN Solution 5; TASK U4): resolution (a 16:9 height),
 * frame rate, quality, codec and Chat. The first export uses {@link RewindDefaults}; later ones the values last
 * exported with ({@code ClientSettings.rewindExport*}). A value outside its list is reset to its default.
 *
 * <p>Immutable.
 */
public final class ExportSettings {

    /** 720p, 1080p, 1440p, 4K (PLAN D6). */
    public static final int[] HEIGHTS = {720, 1080, 1440, 2160};
    public static final int[] RATES = {30, 60, 120, 240};
    /** Standard, High, Max, as stored. */
    public static final String[] QUALITIES = {"standard", "high", "max"};
    /** H.264, HEVC, as stored and as the helper's {@code --codec} takes them. */
    public static final String[] CODECS = {"h264", "hevc"};

    private static final ExportSettings DEFAULTS = new ExportSettings(RewindDefaults.DEFAULT_EXPORT_HEIGHT,
            RewindDefaults.DEFAULT_EXPORT_FPS, RewindDefaults.DEFAULT_EXPORT_QUALITY,
            RewindDefaults.DEFAULT_EXPORT_CODEC, RewindDefaults.DEFAULT_EXPORT_CHAT);

    private final int height;
    private final int fps;
    private final String quality;
    private final String codec;
    private final boolean chat;

    private ExportSettings(int height, int fps, String quality, String codec, boolean chat) {
        this.height = height;
        this.fps = fps;
        this.quality = quality;
        this.codec = codec;
        this.chat = chat;
    }

    public static ExportSettings defaults() {
        return DEFAULTS;
    }

    /** The given values, each one outside its list replaced by its default. */
    public static ExportSettings of(int height, int fps, String quality, String codec, boolean chat) {
        return new ExportSettings(contains(HEIGHTS, height) ? height : DEFAULTS.height,
                contains(RATES, fps) ? fps : DEFAULTS.fps,
                contains(QUALITIES, quality) ? quality : DEFAULTS.quality,
                contains(CODECS, codec) ? codec : DEFAULTS.codec, chat);
    }

    /** The 16:9 width for a height, rounded to an even number. */
    public static int width(int height) {
        return (int) Math.round(height * 16 / 9.0 / 2) * 2;
    }

    public int height() {
        return height;
    }

    public int fps() {
        return fps;
    }

    public String quality() {
        return quality;
    }

    public String codec() {
        return codec;
    }

    public boolean chat() {
        return chat;
    }

    static boolean contains(int[] list, int v) {
        for (int x : list) {
            if (x == v) return true;
        }
        return false;
    }

    static boolean contains(String[] list, String v) {
        for (String x : list) {
            if (x.equals(v)) return true;
        }
        return false;
    }

    static int indexOf(int[] list, int v) {
        for (int i = 0; i < list.length; i++) {
            if (list[i] == v) return i;
        }
        return -1;
    }

    static int indexOf(String[] list, String v) {
        for (int i = 0; i < list.length; i++) {
            if (list[i].equals(v)) return i;
        }
        return -1;
    }
}
