package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;

/**
 * The recorded FOV (PLAN "FovPart", Phase 12; TASK C7): in your view of a recording with FOV records, the world FOV
 * is the recorded final FOV at the frame's playback time ({@link ViewTrack#fovAt}: interpolated between samples at
 * most 50 ms apart), whatever the watching client's FOV setting. The recorder read it through
 * {@code EntityRenderer.getFOVModifier(partial, true)}, so sprint, Speed, the bow, water and Cobblify's Zoom are in
 * it; {@code CameraStatePart} skips its derived FOV on such a recording (Phase 7).
 *
 * <p>{@link #frame} reads the value once per drawn frame, at the head of {@code renderWorld}, before vanilla asks for
 * the FOV; {@code EntityRendererRewindPlaybackMixin}'s one injector on {@code getFOVModifier} RETURN hands every
 * return through {@link #worldFov}. The hand pass ({@code useFOVSetting} false), the free camera, another player's
 * view, a v1 recording and a frame before the first FOV sample keep vanilla's value; with nothing playing the value
 * is returned unchanged. Never sends, opens nothing, reads no live tracker.
 *
 * <p>Registered by {@link #register()} (Phase 16 calls it from {@code BedwarsQol}).
 */
public final class FovPart implements PlaybackPart {

    private static final FovPart INSTANCE = new FovPart();

    private final ExtCodec.Fov decoder = new ExtCodec.Fov();
    private float recorded = Float.NaN;

    FovPart() {
    }

    /** Adds the part to Rewind playback once ({@link RewindPlayback#register}). Client thread. */
    public static void register() {
        RewindPlayback.register(INSTANCE);
    }

    /**
     * {@code getFOVModifier}'s return value ({@code EntityRendererRewindPlaybackMixin}): the recorded FOV in your
     * view of a playing recording's world pass, else {@code vanilla} unchanged.
     */
    public static float worldFov(float vanilla, boolean useFovSetting) {
        if (!useFovSetting) {
            return vanilla;
        }
        boolean playing = RewindPlayback.active();
        return choose(vanilla, true, playing, playing && RewindPlayback.view().yourView(), INSTANCE.recorded);
    }

    /** The rule: the recorded value only for the world pass, in your view, while playing, once one is recorded. */
    static float choose(float vanilla, boolean useFovSetting, boolean playing, boolean yourView, float recorded) {
        return useFovSetting && playing && yourView && !Float.isNaN(recorded) ? recorded : vanilla;
    }

    // ================================================================== PlaybackPart

    @Override
    public void restart() {
        recorded = Float.NaN;
    }

    @Override
    public void boundary() {
        recorded = Float.NaN;
    }

    @Override
    public void frame(long timeUs, float partial) {
        PlaybackSession s = RewindPlayback.session();
        LoadedRecording r = s.recording();
        if (r == null || !r.has(RewindFormat.TAG_FOV)) {
            recorded = Float.NaN;
            return;
        }
        update(r.extTrack(), ViewPart.segmentNow(s), timeUs, s.view().yourView());
    }

    @Override
    public void viewChanged() {
        if (!RewindPlayback.view().yourView()) {
            recorded = Float.NaN; // at once, even before the next frame reads it again
        }
    }

    @Override
    public void exit() {
        recorded = Float.NaN;
    }

    // ================================================================== state (package-private for tests)

    /** The recorded FOV at {@code tUs} in your view, else none. */
    void update(ExtTrack track, int segment, long tUs, boolean yourView) {
        recorded = yourView ? ViewTrack.fovAt(track, segment, tUs, decoder) : Float.NaN;
    }

    /** The value the next {@code getFOVModifier} world pass returns (NaN: vanilla's). */
    float recorded() {
        return recorded;
    }
}
