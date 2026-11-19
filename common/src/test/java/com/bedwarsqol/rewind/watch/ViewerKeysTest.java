package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.watch.ViewerKeys.Action;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Viewer keys (G37, G45, Q1). Codes are LWJGL 2's {@code Keyboard.KEY_*} values. */
public class ViewerKeysTest {

    private static final long S = 1_000_000L;

    @Test
    public void codesAreLwjglKeyboardCodes() {
        // javap -constants org.lwjgl.input.Keyboard (lwjgl 2.9.4)
        assertEquals(1, ViewerKeys.KEY_ESCAPE);
        assertEquals(2, ViewerKeys.KEY_1);
        assertEquals(3, ViewerKeys.KEY_2);
        assertEquals(15, ViewerKeys.KEY_TAB);
        assertEquals(26, ViewerKeys.KEY_LBRACKET);
        assertEquals(27, ViewerKeys.KEY_RBRACKET);
        assertEquals(37, ViewerKeys.KEY_K);
        assertEquals(42, ViewerKeys.KEY_LSHIFT);
        assertEquals(51, ViewerKeys.KEY_COMMA);
        assertEquals(52, ViewerKeys.KEY_PERIOD);
        assertEquals(54, ViewerKeys.KEY_RSHIFT);
        assertEquals(57, ViewerKeys.KEY_SPACE);
        assertEquals(203, ViewerKeys.KEY_LEFT);
        assertEquals(205, ViewerKeys.KEY_RIGHT);
    }

    @Test
    public void spacePlaysInYourViewAndFliesInTheFreeCamera() {
        assertEquals(Action.TOGGLE_PLAY, ViewerKeys.action(ViewerKeys.KEY_SPACE, false, false));
        assertEquals(Action.NONE, ViewerKeys.action(ViewerKeys.KEY_SPACE, false, true));
        assertEquals(Action.NONE, ViewerKeys.action(ViewerKeys.KEY_SPACE, true, true));
    }

    @Test
    public void kPlaysInBothViews() {
        assertEquals(Action.TOGGLE_PLAY, ViewerKeys.action(ViewerKeys.KEY_K, false, false));
        assertEquals(Action.TOGGLE_PLAY, ViewerKeys.action(ViewerKeys.KEY_K, false, true));
    }

    @Test
    public void arrowsSeekFiveAndWithShiftThirtySeconds() {
        for (boolean free : new boolean[]{false, true}) {
            assertEquals(Action.BACK_SHORT, ViewerKeys.action(ViewerKeys.KEY_LEFT, false, free));
            assertEquals(Action.FORWARD_SHORT, ViewerKeys.action(ViewerKeys.KEY_RIGHT, false, free));
            assertEquals(Action.BACK_LONG, ViewerKeys.action(ViewerKeys.KEY_LEFT, true, free));
            assertEquals(Action.FORWARD_LONG, ViewerKeys.action(ViewerKeys.KEY_RIGHT, true, free));
        }
        assertEquals(-5 * S, ViewerKeys.seekDeltaUs(Action.BACK_SHORT));
        assertEquals(5 * S, ViewerKeys.seekDeltaUs(Action.FORWARD_SHORT));
        assertEquals(-30 * S, ViewerKeys.seekDeltaUs(Action.BACK_LONG));
        assertEquals(30 * S, ViewerKeys.seekDeltaUs(Action.FORWARD_LONG));
        assertEquals(0, ViewerKeys.seekDeltaUs(Action.TOGGLE_PLAY));
        assertEquals(0, ViewerKeys.seekDeltaUs(Action.NONE));
    }

    @Test
    public void markerSpeedViewMenuAndTabKeys() {
        for (boolean free : new boolean[]{false, true}) {
            assertEquals(Action.PREVIOUS_MARKER, ViewerKeys.action(ViewerKeys.KEY_COMMA, false, free));
            assertEquals(Action.NEXT_MARKER, ViewerKeys.action(ViewerKeys.KEY_PERIOD, false, free));
            assertEquals(Action.SLOWER, ViewerKeys.action(ViewerKeys.KEY_LBRACKET, false, free));
            assertEquals(Action.FASTER, ViewerKeys.action(ViewerKeys.KEY_RBRACKET, false, free));
            assertEquals(Action.VIEW_YOU, ViewerKeys.action(ViewerKeys.KEY_1, false, free));
            assertEquals(Action.VIEW_FREE, ViewerKeys.action(ViewerKeys.KEY_2, false, free));
            assertEquals(Action.ESC_MENU, ViewerKeys.action(ViewerKeys.KEY_ESCAPE, false, free));
            assertEquals(Action.TAB_LIST, ViewerKeys.action(ViewerKeys.KEY_TAB, false, free));
            assertEquals(Action.NONE, ViewerKeys.action(ViewerKeys.KEY_LSHIFT, true, free));
            assertEquals(Action.NONE, ViewerKeys.action(30 /* A */, false, free));
            assertEquals(Action.NONE, ViewerKeys.action(0, false, free));
        }
    }

    @Test
    public void viewKeyCodesAreLwjglKeyboardCodes() {
        assertEquals(4, ViewerKeys.KEY_3);
        assertEquals(59, ViewerKeys.KEY_F1);
        assertEquals(63, ViewerKeys.KEY_F5);
    }

    /** B4, D1, D6, Q3: F5 cycles the perspective, F1 and the Clean View Key toggle the clean view, 3 rides players. */
    @Test
    public void f5F1ThreeShiftThreeAndTheCleanViewKey() {
        int clean = 47; // V, a binding the user chose
        for (boolean free : new boolean[]{false, true}) {
            assertEquals(Action.CYCLE_PERSPECTIVE, ViewerKeys.action(ViewerKeys.KEY_F5, false, free, clean));
            assertEquals(Action.CLEAN_VIEW, ViewerKeys.action(ViewerKeys.KEY_F1, false, free, clean));
            assertEquals(Action.CLEAN_VIEW, ViewerKeys.action(clean, false, free, clean));
            assertEquals(Action.NEXT_PLAYER, ViewerKeys.action(ViewerKeys.KEY_3, false, free, clean));
            assertEquals(Action.PREVIOUS_PLAYER, ViewerKeys.action(ViewerKeys.KEY_3, true, free, clean));
            assertEquals("the three-argument form knows the fixed keys", Action.CYCLE_PERSPECTIVE,
                    ViewerKeys.action(ViewerKeys.KEY_F5, false, free));
            assertEquals(Action.NEXT_PLAYER, ViewerKeys.action(ViewerKeys.KEY_3, false, free));
        }
        assertEquals("unbound (0) is no key", Action.NONE, ViewerKeys.action(0, false, false, 0));
        assertEquals("a fixed viewer key wins over the same binding", Action.TOGGLE_PLAY,
                ViewerKeys.action(ViewerKeys.KEY_K, false, false, ViewerKeys.KEY_K));
        assertEquals("a mouse binding never matches a key", Action.NONE, ViewerKeys.action(30, false, false, -100));
    }

    @Test
    public void bothShiftKeysAreTheModifier() {
        assertTrue(ViewerKeys.isShift(ViewerKeys.KEY_LSHIFT));
        assertTrue(ViewerKeys.isShift(ViewerKeys.KEY_RSHIFT));
        assertFalse(ViewerKeys.isShift(ViewerKeys.KEY_SPACE));
    }
}
