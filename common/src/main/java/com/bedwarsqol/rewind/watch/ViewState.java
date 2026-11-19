package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;

/**
 * The single owner of what the playback view shows now (PLAN "B-seam", Q2, Q3; TASK B4, C4, D3, D6): which view
 * (your view, the free camera, another player's view), the perspective (F5), hidden HUD (F1), F3, the tab list, the
 * clean view and the recorded screen kind. Fed by the recording ({@link #recorded}, from VIEW records: Phase 12) and
 * by the viewer's keys; read by the session (which writes {@code thirdPersonView}, {@code hideGUI} and
 * {@code showDebugInfo} from it every frame) and by the playback parts.
 *
 * <ul>
 *   <li><b>Your view</b> follows the recorded perspective, F1, F3 and Tab (a v1 recording records none: first
 *       person, HUD shown). F5 ({@link #cyclePerspective}: first person, behind, front) or the clean view
 *       ({@link #toggleClean}) take over from the recording until key 1 ({@link #selectYou}) gives it back.</li>
 *   <li><b>The free camera</b> and <b>another player's view</b> are first person with no recorded F1, F3, Tab or
 *       screens; F5 does nothing there.</li>
 *   <li><b>The clean view</b> (the Clean View Key or F1 while watching) hides the HUD and recorded screens in every
 *       view until pressed again or key 1.</li>
 *   <li><b>The puppet</b> ({@link #puppetHidden}) is hidden in your view's first person, while the recorded player
 *       is invisible, and before its first sample.</li>
 *   <li><b>The camera is a spectator</b> ({@link #cameraSpectator}) only in your view of a recorded spectator.</li>
 * </ul>
 *
 * {@link #version()} changes with every change, so a consumer can apply a change once. Pure; the client thread.
 */
public final class ViewState {

    public enum View { YOU, FREE, RIDDEN }

    private View view = View.YOU;
    private int ridden = -1;
    private boolean override;
    private int userPerspective;
    private boolean clean;
    private boolean hasRecorded;
    private int recordedPerspective;
    private int recordedFlags;
    private int recordedScreen = RewindFormat.SCREEN_NONE;
    private int version;

    /** A new recording: your view, first person, HUD shown, nothing recorded yet. */
    public void reset() {
        view = View.YOU;
        ridden = -1;
        override = false;
        userPerspective = 0;
        clean = false;
        hasRecorded = false;
        recordedPerspective = 0;
        recordedFlags = 0;
        recordedScreen = RewindFormat.SCREEN_NONE;
        version++;
    }

    public View view() {
        return view;
    }

    public boolean yourView() {
        return view == View.YOU;
    }

    public boolean freeCamera() {
        return view == View.FREE;
    }

    /** The ridden player's entity id, or -1 outside {@link View#RIDDEN}. */
    public int riddenEntityId() {
        return view == View.RIDDEN ? ridden : -1;
    }

    /** Key 1: your view, and the F5 and clean-view overrides end. @return true if the view itself changed */
    public boolean selectYou() {
        boolean changed = view != View.YOU;
        view = View.YOU;
        ridden = -1;
        if (changed || override || clean) {
            override = false;
            clean = false;
            userPerspective = 0;
            version++;
        }
        return changed;
    }

    /** Key 2: the free camera. @return true if the view changed */
    public boolean selectFree() {
        if (view == View.FREE) {
            return false;
        }
        view = View.FREE;
        ridden = -1;
        version++;
        return true;
    }

    /** Another player's view (Phase 14). */
    public void ride(int entityId) {
        if (view != View.RIDDEN || ridden != entityId) {
            view = View.RIDDEN;
            ridden = entityId;
            version++;
        }
    }

    /** F5 in your view: first person, behind, front, from what is shown now. @return false outside your view */
    public boolean cyclePerspective() {
        if (view != View.YOU) {
            return false;
        }
        userPerspective = (perspective() + 1) % 3;
        override = true;
        version++;
        return true;
    }

    /** The Clean View Key or F1 while watching: on, off. The recorded F1 stays off until key 1. */
    public void toggleClean() {
        if (!override) {
            userPerspective = perspective();
        }
        clean = !clean;
        override = true;
        version++;
    }

    /** A VIEW record (Phase 12): perspective 0-2, {@code RewindFormat.VIEW_*} flags, {@code RewindFormat.SCREEN_*}. */
    public void recorded(int perspective, int flags, int screenKind) {
        int p = perspective < 0 || perspective > 2 ? 0 : perspective;
        if (hasRecorded && p == recordedPerspective && flags == recordedFlags && screenKind == recordedScreen) {
            return;
        }
        hasRecorded = true;
        recordedPerspective = p;
        recordedFlags = flags;
        recordedScreen = screenKind;
        version++;
    }

    public boolean hasRecorded() {
        return hasRecorded;
    }

    /** F5 or the clean view took over from the recording (until key 1). */
    public boolean overridden() {
        return override;
    }

    public boolean clean() {
        return clean;
    }

    /** {@code gameSettings.thirdPersonView}: 0 first person, 1 behind, 2 front. */
    public int perspective() {
        if (view != View.YOU) {
            return 0;
        }
        return override ? userPerspective : recordedPerspective;
    }

    /** {@code gameSettings.hideGUI}. */
    public boolean hideGui() {
        return clean || followsRecording() && (recordedFlags & RewindFormat.VIEW_HIDE_GUI) != 0;
    }

    /** {@code gameSettings.showDebugInfo}. */
    public boolean showDebug() {
        return followsRecording() && (recordedFlags & RewindFormat.VIEW_F3) != 0;
    }

    /** The recorded tab list is showing (the viewer's own Tab key shows it too). */
    public boolean tabList() {
        return followsRecording() && (recordedFlags & RewindFormat.VIEW_TAB) != 0;
    }

    /**
     * No HUD at all this frame (D6): the clean view in any view, or the recorded F1 in your view while no recorded
     * screen is open. Vanilla skips the in-game overlay under F1 only while no screen is open
     * ({@code EntityRenderer.updateCameraAndRender}: {@code !hideGUI || currentScreen != null}), and the viewer is a
     * screen, so playback skips it itself; with a recorded screen open the live client drew it, so it is drawn.
     */
    public boolean hidesEveryHud() {
        return hideGui() && screenKind() == RewindFormat.SCREEN_NONE;
    }

    /**
     * The recorded Cobblify HUD may draw (D5): in your view only, never in the clean view, and not while the recorded
     * Tab list or F3 hid it (an F5 override stops following those, so it shows again).
     */
    public boolean recordedHud() {
        return view == View.YOU && !clean && !tabList() && !showDebug();
    }

    /** The recorded screen to draw ({@code RewindFormat.SCREEN_*}): only in your view, never in the clean view. */
    public int screenKind() {
        return view == View.YOU && !clean ? recordedScreen : RewindFormat.SCREEN_NONE;
    }

    /**
     * Whether the recorded player's puppet is not drawn (B4): in your view only in first person (the camera is you;
     * F5's behind and front views show it as the recorded body), while the recorded player is invisible, and before
     * its first sample.
     */
    public boolean puppetHidden(boolean sampled, boolean invisible) {
        return view == View.YOU && perspective() == 0 || !sampled || invisible;
    }

    /**
     * The camera's {@code isSpectator()} (amendment 1, B1): only in your view while the recorded game mode
     * ({@code ViewGameType} id, {@link ViewGameType#NOT_SET} before any) is spectator. Vanilla reads it for the
     * viewer in {@code Entity.isInvisibleToPlayer} (translucent invisibles), the bare arm's body parts, the water and
     * fire overlays and terrain culling; never true in the free camera or another player's view.
     */
    public boolean cameraSpectator(int recordedGameMode) {
        return view == View.YOU && recordedGameMode == ViewGameType.SPECTATOR;
    }

    /** Changes with every change of what the view shows. */
    public int version() {
        return version;
    }

    private boolean followsRecording() {
        return view == View.YOU && !override;
    }
}
