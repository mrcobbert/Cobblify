package com.bedwarsqol.rewind.export;

/**
 * One export's range and frame rate on the recording's clock (Rewind Export PLAN Solution 1; TASK P1, S1).
 *
 * <ul>
 *   <li>{@link #frames()}: {@code max(1, floor((end - start) x fps / 1e6))}.</li>
 *   <li>{@link #timeOfFrame}: frame i shows the game at {@code start + floor(i x 1e6 / fps)}, computed in whole
 *       seconds plus a remainder, so it neither drifts nor overflows.</li>
 *   <li>The selected end is kept as given (review r2-I1): the picture ends at {@link #pictureEndUs()}
 *       ({@code timeOfFrame(frames)}), the sound at {@link #endUs()}, so the sound track is longer than the picture by
 *       less than one frame.</li>
 * </ul>
 *
 * Immutable; pure.
 */
public final class ExportPlan {

    /** The sound track's sample rate (48 kHz stereo). */
    public static final int SAMPLE_RATE = 48_000;
    /** Every export first draws, uncaptured, up to this long before its start (PLAN "Pre-roll", review r2-I2). */
    public static final long PREROLL_US = 1_000_000L;

    private static final long US = 1_000_000L;

    private final long startUs;
    private final long endUs;
    private final int fps;
    private final long frames;

    /**
     * @param startUs the range start on the recording's clock (frame 0's time)
     * @param endUs   the selected range end, at or after {@code startUs}
     * @param fps     frames per second, above 0
     */
    public ExportPlan(long startUs, long endUs, int fps) {
        if (fps <= 0) {
            throw new IllegalArgumentException("fps " + fps);
        }
        if (endUs < startUs) {
            throw new IllegalArgumentException("end before start");
        }
        this.startUs = startUs;
        this.endUs = endUs;
        this.fps = fps;
        long d = endUs - startUs;
        this.frames = Math.max(1L, (d / US) * fps + (d % US) * fps / US);
    }

    public long startUs() {
        return startUs;
    }

    /** The selected range end (the sound runs to it; the tail step collects up to it). */
    public long endUs() {
        return endUs;
    }

    public int fps() {
        return fps;
    }

    /** Frames in the file: at least 1. */
    public long frames() {
        return frames;
    }

    /** The recording time frame {@code i} (0 or more) shows. */
    public long timeOfFrame(long i) {
        return startUs + (i / fps) * US + (i % fps) * US / fps;
    }

    /** Where the picture ends: {@code timeOfFrame(frames())}. */
    public long pictureEndUs() {
        return timeOfFrame(frames);
    }

    /**
     * The sound track's length in samples: {@code floor((end - start) x 48000 / 1e6)}, so the last sample starts
     * before the selected end. (PLAN Solution 1 says {@code round}; rounding up can make the sound exactly one frame
     * longer than the picture (240 fps over 1,004,166 us: 48,200 samples, 240 frames), against the plan's "less than
     * one frame". Both give the plan's examples, 48,000 and 48,480.)
     */
    public long audioSamples() {
        long d = endUs - startUs;
        return (d / US) * SAMPLE_RATE + (d % US) * SAMPLE_RATE / US;
    }
}
