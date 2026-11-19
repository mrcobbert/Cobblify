package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The recorded view (PLAN "ViewPart", Phase 12; TASK C4, Q2): a format-v2 recording's VIEW records feed the session's
 * {@link ViewState} ({@link ViewState#recorded}), so your view follows the recorded perspective (F5), hidden HUD (F1),
 * F3 and Tab list; F5 or the clean view take over until key 1 ({@link ViewState}'s rules). The free camera and another
 * player's view follow none of it.
 *
 * <ul>
 *   <li><b>When.</b> A VIEW record reaching playback time ({@link #timed}) applies in the same frame: the session
 *       dispatches time-scoped records before it writes {@code thirdPersonView}, {@code hideGUI} and
 *       {@code showDebugInfo} from the view. Every drawn frame ({@link #frame}) the state is read again by time
 *       ({@link ViewTrack}), which is what lands a seek, forward or backward, on the state recorded at its time (time
 *       records are never replayed by a seek).</li>
 *   <li><b>Tab.</b> Vanilla draws the tab list while the player-list binding is down. While the recorded Tab list
 *       shows in your view ({@link ViewState#tabList}) this part holds the binding down at the head of each world
 *       render, before the HUD draws; the viewer sets it back to the viewer's own Tab key after the HUD (each frame),
 *       and this part lets go of it when the recorded Tab ends and when playback ends. An unbound binding cannot be
 *       held (the list is then not shown).</li>
 *   <li><b>For Phase 15:</b> the recorded HUD hides on {@code RewindPlayback.view().tabList()} and
 *       {@code showDebug()}, which are the recorded Tab and F3 in your view (false under an F5 or clean-view
 *       override, in the free camera and in another player's view).</li>
 * </ul>
 * A v1 recording (no VIEW records) leaves the view as the keys set it. Never sends, opens nothing, reads no live
 * tracker. Registered by {@link #register()} (Phase 16 calls it from {@code BedwarsQol}).
 */
public final class ViewPart implements PlaybackPart {

    private static final ViewPart INSTANCE = new ViewPart();

    private final ViewTrack track = new ViewTrack();
    private boolean tabHeld;

    ViewPart() {
    }

    /** Adds the part to Rewind playback once ({@link RewindPlayback#register}). Client thread. */
    public static void register() {
        RewindPlayback.register(INSTANCE);
    }

    /**
     * The segment of the tick the picture shows ({@code TickTable.segment} of {@link PlaybackSession#renderTick});
     * -1 when it is not a recorded head ({@link ViewTrack#segmentFor} then reads the latest segment that began).
     */
    static int segmentNow(PlaybackSession s) {
        Timeline t = s == null ? null : s.timeline();
        return t == null ? -1 : t.ticks().segment(s.renderTick());
    }

    // ================================================================== PlaybackPart

    @Override
    public void restart() {
        track.reset();
    }

    @Override
    public void timed(int tag, long timeUs, byte[] buf, int off, int len) {
        if (tag == RewindFormat.TAG_VIEW) {
            follow();
        }
    }

    @Override
    public void frame(long timeUs, float partial) {
        follow();
        holdTab(RewindPlayback.view().tabList());
    }

    @Override
    public void viewChanged() {
        holdTab(RewindPlayback.view().tabList());
    }

    @Override
    public void exit() {
        holdTab(false);
        track.reset();
    }

    /** The recorded VIEW at playback time onto the session's view state. */
    private void follow() {
        PlaybackSession s = RewindPlayback.session();
        LoadedRecording r = s.recording();
        if (r == null || !r.has(RewindFormat.TAG_VIEW)) {
            return;
        }
        track.at(r.extTrack(), segmentNow(s), s.renderTimeUs());
        track.apply(s.view());
    }

    /** Holds the player-list binding while the recorded Tab list shows; lets it go (to the real key) once. */
    private void holdTab(boolean recordedTab) {
        if (!recordedTab && !tabHeld) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null || mc.gameSettings.keyBindPlayerList == null) {
            return;
        }
        int code = mc.gameSettings.keyBindPlayerList.getKeyCode();
        if (code == 0) {
            tabHeld = false;
            return;
        }
        KeyBinding.setKeyBindState(code, recordedTab || physicallyDown(code));
        tabHeld = recordedTab;
    }

    /** A binding's key or mouse button is down now (vanilla's codes: mouse buttons are {@code button - 100}). */
    private static boolean physicallyDown(int code) {
        if (code < 0) {
            return Mouse.isButtonDown(code + 100);
        }
        return code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }
}
