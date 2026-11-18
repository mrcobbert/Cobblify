package com.bedwarsqol.rewind.watch;

/**
 * The viewer's keys (G37, G45, Q1) as LWJGL 2 {@code Keyboard.KEY_*} codes (common code cannot import LWJGL; the
 * values are pinned by {@code ViewerKeysTest}).
 *
 * <ul>
 *   <li>Space: play/pause in your view; in the free camera Space and Shift fly up and down instead (Q1).</li>
 *   <li>K: play/pause in both views.</li>
 *   <li>Left/Right: 5 s; with Shift: 30 s.</li>
 *   <li>Comma/period: previous/next marker. {@code [} {@code ]}: slower/faster.</li>
 *   <li>1: your view; 2: free camera. Esc: the Esc menu. Tab (held): the recorded tab list.</li>
 * </ul>
 *
 * Pure.
 */
public final class ViewerKeys {

    public static final int KEY_ESCAPE = 1;
    public static final int KEY_1 = 2;
    public static final int KEY_2 = 3;
    public static final int KEY_TAB = 15;
    public static final int KEY_LBRACKET = 26;
    public static final int KEY_RBRACKET = 27;
    public static final int KEY_K = 37;
    public static final int KEY_LSHIFT = 42;
    public static final int KEY_COMMA = 51;
    public static final int KEY_PERIOD = 52;
    public static final int KEY_RSHIFT = 54;
    public static final int KEY_SPACE = 57;
    public static final int KEY_LEFT = 203;
    public static final int KEY_RIGHT = 205;

    private static final long SHORT_US = 5_000_000L;
    private static final long LONG_US = 30_000_000L;

    public enum Action {
        NONE, TOGGLE_PLAY, BACK_SHORT, FORWARD_SHORT, BACK_LONG, FORWARD_LONG, PREVIOUS_MARKER, NEXT_MARKER, SLOWER,
        FASTER, VIEW_YOU, VIEW_FREE, ESC_MENU,
        /** Held: shows the recorded tab list while down. */
        TAB_LIST
    }

    private ViewerKeys() {
    }

    /** The action of a key press; {@code shiftDown} is the modifier, {@code freeCamera} the active view. */
    public static Action action(int key, boolean shiftDown, boolean freeCamera) {
        switch (key) {
            case KEY_SPACE:
                return freeCamera ? Action.NONE : Action.TOGGLE_PLAY;
            case KEY_K:
                return Action.TOGGLE_PLAY;
            case KEY_LEFT:
                return shiftDown ? Action.BACK_LONG : Action.BACK_SHORT;
            case KEY_RIGHT:
                return shiftDown ? Action.FORWARD_LONG : Action.FORWARD_SHORT;
            case KEY_COMMA:
                return Action.PREVIOUS_MARKER;
            case KEY_PERIOD:
                return Action.NEXT_MARKER;
            case KEY_LBRACKET:
                return Action.SLOWER;
            case KEY_RBRACKET:
                return Action.FASTER;
            case KEY_1:
                return Action.VIEW_YOU;
            case KEY_2:
                return Action.VIEW_FREE;
            case KEY_ESCAPE:
                return Action.ESC_MENU;
            case KEY_TAB:
                return Action.TAB_LIST;
            default:
                return Action.NONE;
        }
    }

    /** The seek step of a seek action ({@link SeekPlanner#relative}), else 0. */
    public static long seekDeltaUs(Action a) {
        switch (a) {
            case BACK_SHORT:
                return -SHORT_US;
            case FORWARD_SHORT:
                return SHORT_US;
            case BACK_LONG:
                return -LONG_US;
            case FORWARD_LONG:
                return LONG_US;
            default:
                return 0;
        }
    }

    /** Either Shift key: the 30 s modifier (and, in the free camera, down). */
    public static boolean isShift(int key) {
        return key == KEY_LSHIFT || key == KEY_RSHIFT;
    }
}
