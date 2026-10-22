package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionHoldKeyTest {

    private static final int TAB = 15; // LWJGL Keyboard.KEY_TAB

    private static boolean visible(boolean enabled, boolean example, boolean hold, int key, boolean screenOpen,
                                   boolean keyDown) {
        return SessionHoldKey.hudVisible(enabled, example, hold, key, screenOpen, keyDown);
    }

    @Test
    public void sessionStatsOffNeverDraws() {
        assertFalse(visible(false, false, false, 0, false, false));
        assertFalse(visible(false, true, false, 0, false, false));   // not even in the HUD editor
        assertFalse(visible(false, false, true, TAB, false, true));
    }

    @Test
    public void theHudEditorAlwaysShowsTheBox() {
        assertTrue(visible(true, true, false, 0, true, false));
        assertTrue(visible(true, true, true, 0, true, false));       // hold mode, unbound, a screen open
    }

    @Test
    public void withoutHoldModeItShowsOnAnyServer() {
        assertTrue(visible(true, false, false, 0, false, false));
        assertTrue(visible(true, false, false, 0, true, false));      // as before: under open screens too
    }

    @Test
    public void holdModeShowsOnlyWhileTheBoundKeyIsHeldWithNoScreenOpen() {
        assertTrue(visible(true, false, true, TAB, false, true));
        assertFalse(visible(true, false, true, TAB, false, false));   // released
        assertFalse(visible(true, false, true, TAB, true, true));     // chat or a menu is open
        assertFalse(visible(true, false, true, 0, false, true));      // unbound: hidden
        assertTrue(visible(true, false, true, -97, false, true));     // a mouse side button
    }

    @Test
    public void captureKeys() {
        assertEquals(SessionHoldKey.CANCEL, SessionHoldKey.bindingForKey(SessionHoldKey.KEY_ESCAPE));
        assertEquals(0, SessionHoldKey.bindingForKey(SessionHoldKey.KEY_BACK));
        assertEquals(0, SessionHoldKey.bindingForKey(SessionHoldKey.KEY_DELETE));
        assertEquals(SessionHoldKey.KEEP_WAITING, SessionHoldKey.bindingForKey(0)); // a char event without a key
        assertEquals(TAB, SessionHoldKey.bindingForKey(TAB));
        assertEquals(19, SessionHoldKey.bindingForKey(19));                          // R
    }

    /** Minecraft's own KeyBinding convention: mouse button b is stored as b - 100. */
    @Test
    public void captureMouseButtons() {
        assertEquals(-100, SessionHoldKey.bindingForMouse(0));
        assertEquals(-99, SessionHoldKey.bindingForMouse(1));
        assertEquals(-96, SessionHoldKey.bindingForMouse(4));
        assertTrue(SessionHoldKey.isMouse(-100));
        assertTrue(SessionHoldKey.isMouse(-96));
        assertFalse(SessionHoldKey.isMouse(0));
        assertFalse(SessionHoldKey.isMouse(TAB));
        assertEquals(4, SessionHoldKey.mouseButton(-96));
    }

    @Test
    public void displayNames() {
        assertEquals("None", SessionHoldKey.displayName(0, null));
        assertEquals("Left Click", SessionHoldKey.displayName(-100, null));
        assertEquals("Right Click", SessionHoldKey.displayName(-99, null));
        assertEquals("Middle Click", SessionHoldKey.displayName(-98, null));
        assertEquals("Mouse 4", SessionHoldKey.displayName(-97, null));
        assertEquals("Mouse 5", SessionHoldKey.displayName(-96, null));
        assertEquals("TAB", SessionHoldKey.displayName(TAB, "TAB"));
        assertEquals("Key 300", SessionHoldKey.displayName(300, null)); // no keyboard name known
    }

    @Test
    public void validKeyCodes() {
        assertTrue(SessionHoldKey.isValidBinding(0));
        assertTrue(SessionHoldKey.isValidBinding(TAB));
        assertTrue(SessionHoldKey.isValidBinding(255));
        assertTrue(SessionHoldKey.isValidBinding(-100));
        assertTrue(SessionHoldKey.isValidBinding(-85));  // mouse button 15
        assertFalse(SessionHoldKey.isValidBinding(256));
        assertFalse(SessionHoldKey.isValidBinding(-101));
        assertFalse(SessionHoldKey.isValidBinding(-84));
    }
}
