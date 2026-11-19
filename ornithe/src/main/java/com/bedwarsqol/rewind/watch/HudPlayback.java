package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.DiagLog;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.HudSource;
import com.bedwarsqol.hud.RecordedHudSource;
import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import net.minecraft.client.Minecraft;

/**
 * Your Cobblify HUD as you saw it (PLAN "Cobblify HUD as recorded" and "Clean view", Phase 15; TASK D5, D6): while a
 * format-v2 recording plays, the HUD layer hooks ({@code GuiIngameHudMixin}) call {@link #drawText} and
 * {@link #drawPost} where the live HUD draws (Cobblify's own HUD events stay muted during playback). Each draws the
 * live painters ({@link BedwarsHudRenderer#renderRecorded}, {@link BedwarsHudRenderer#renderRecordedPost}) from a
 * {@link RecordedHudSource} at playback time, inside the recorded settings, so every item that was on screen shows
 * the recorded values at the recorded place, size, font, accent and background, re-anchored to this window.
 *
 * <ul>
 *   <li><b>When.</b> Only in your view ({@link ViewState#recordedHud}): not in the free camera, another player's view
 *       or the clean view, and not while the recorded Tab list or F3 hid it. A v1 recording (no HUD records) draws
 *       none.</li>
 *   <li><b>Keystrokes.</b> TAP records ({@link #timed}, dispatched when playback time reaches their stamp) flash the
 *       recording's own mouse caps; a restart (a backward seek) forgets them.</li>
 *   <li><b>The clean view</b> (D6): {@link #hidesEveryHud} tells the HUD layer to skip vanilla's whole in-game
 *       overlay, as vanilla does under F1 with no screen open; the viewer bar is the viewer screen's and still
 *       draws.</li>
 * </ul>
 *
 * Nothing here counts, looks up or sends: the live trackers are never fed and the recorder is handed nothing
 * ({@code PlaybackIsolationScanTest}). With nothing playing, every entry returns at once. Registered by
 * {@link #register()} (Phase 16 calls it from {@code BedwarsQol}). Client thread.
 */
public final class HudPlayback implements PlaybackPart {

    /** The live painters' two passes (a test swaps in a spy). */
    interface Painters {
        void text(Minecraft mc, ClientSettings settings, HudSource source);

        void post(Minecraft mc, ClientSettings settings, HudSource source);
    }

    static final Painters LIVE_PAINTERS = new Painters() {
        @Override
        public void text(Minecraft mc, ClientSettings settings, HudSource source) {
            BedwarsHudRenderer.renderRecorded(mc, settings, source);
        }

        @Override
        public void post(Minecraft mc, ClientSettings settings, HudSource source) {
            BedwarsHudRenderer.renderRecordedPost(mc, settings, source);
        }
    };

    private static final HudPlayback INSTANCE = new HudPlayback(LIVE_PAINTERS);

    private final Painters painters;
    private final RecordedHudSource source = new RecordedHudSource();
    private final ExtCodec.Tap tapDecoder = new ExtCodec.Tap();
    private boolean failureLogged;

    HudPlayback(Painters painters) {
        this.painters = painters;
    }

    /** Adds the part to Rewind playback once ({@link RewindPlayback#register}). Client thread. */
    public static void register() {
        RewindPlayback.register(INSTANCE);
    }

    /**
     * True while a recorded world plays and the view shows no HUD at all ({@link ViewState#hidesEveryHud}: the clean
     * view, or the recorded F1 with no recorded screen open). False at once when nothing plays.
     */
    public static boolean hidesEveryHud() {
        if (!RewindPlayback.active()) {
            return false;
        }
        PlaybackSession s = RewindPlayback.session();
        return s.playingWorld() && s.view().hidesEveryHud();
    }

    /**
     * The HUD layer's wrap of {@code GuiIngame.renderGameOverlay} (review code-2 B1): true when the overlay is skipped
     * this frame ({@link #hidesEveryHud}). With a world loaded, vanilla sets the 2D GUI projection only at the top of
     * {@code renderGameOverlay} (javap: {@code EntityRenderer.setupOverlayRendering} at offset 30), before the current
     * screen draws; so a skipped overlay sets it up here the same way, and the viewer bar, the Esc menu and the
     * loading card draw in screen space, not in the world's perspective. False at once (nothing set) when nothing
     * plays or the overlay is drawn.
     */
    public static boolean skipsOverlay(Minecraft mc) {
        if (!hidesEveryHud()) {
            return false;
        }
        mc.entityRenderer.setupOverlayRendering();
        return true;
    }

    /** The HUD's text pass: the recorded items but Session Stats. Returns at once when nothing plays. */
    public static void drawText(Minecraft mc) {
        INSTANCE.draw(mc, false);
    }

    /** The HUD's post pass: the recorded Session Stats, over everything. Returns at once when nothing plays. */
    public static void drawPost(Minecraft mc) {
        INSTANCE.draw(mc, true);
    }

    private void draw(Minecraft mc, boolean post) {
        if (!RewindPlayback.active()) {
            return;
        }
        PlaybackSession s = RewindPlayback.session();
        LoadedRecording r = s.playingWorld() ? s.recording() : null;
        if (r == null) {
            return;
        }
        draw(mc, post, s.view(), r.extTrack(), ViewPart.segmentNow(s), s.renderTimeUs());
    }

    /**
     * Draws one pass of the recorded HUD at {@code timeUs} of {@code segment} (-1: the latest segment that began) if
     * {@code view} shows it and the recording has HUD records and settings. @return true if the painters were called
     */
    boolean draw(Minecraft mc, boolean post, ViewState view, ExtTrack track, int segment, long timeUs) {
        if (track == null || view == null || !track.has(RewindFormat.TAG_HUD) || !view.recordedHud()) {
            return false;
        }
        int seg = ViewTrack.segmentFor(track, RewindFormat.TAG_SETTINGS, segment, timeUs);
        source.at(track, seg, timeUs);
        ClientSettings settings = source.settings();
        if (settings == null) {
            return false;
        }
        try {
            if (post) {
                painters.post(mc, settings, source);
            } else {
                painters.text(mc, settings, source);
            }
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                DiagLog.log("rewind-watch: recorded HUD draw failed " + t.getClass().getSimpleName());
            }
        }
        return true;
    }

    /** The source the painters read (tests). */
    RecordedHudSource source() {
        return source;
    }

    // ================================================================== PlaybackPart

    @Override
    public void restart() {
        source.reset();
    }

    @Override
    public void timed(int tag, long timeUs, byte[] buf, int off, int len) {
        if (tag != RewindFormat.TAG_TAP) {
            return;
        }
        try {
            source.tap(tapDecoder.read(buf, off, len).button, timeUs);
        } catch (RewindFormat.FormatException e) {
            // the loader counted it; no flash
        }
    }

    @Override
    public void exit() {
        source.reset();
    }
}
