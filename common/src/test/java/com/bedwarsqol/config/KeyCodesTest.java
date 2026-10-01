package com.bedwarsqol.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the Lunar keybind sync rule. Vanilla's Controls menu stores a mouse bind as
 * {@code -100 + button}. The registry used to copy that into the config every tick while the save's
 * sanitize reset it, so the config file was rewritten 20 times a second and the key never fired.
 */
public class KeyCodesTest {

    private static final int RSHIFT = 54;

    @Test
    public void mouseCodesAreStorable() {
        assertEquals(-97, KeyCodes.sanitize(-97, RSHIFT));
        assertEquals(-100, KeyCodes.sanitize(-100, RSHIFT));
        assertEquals(-1, KeyCodes.sanitize(-1, 0));
        assertEquals(30, KeyCodes.sanitize(30, 0));
    }

    @Test
    public void codesBelowTheMouseRangeFallBack() {
        assertEquals(RSHIFT, KeyCodes.sanitize(-101, RSHIFT));
        assertEquals(0, KeyCodes.sanitize(Integer.MIN_VALUE, 0));
    }

    /** One save reaches a fixed point: the saved value survives sanitize, so the next tick is clean. */
    @Test
    public void mouseBindSettlesAfterOneSave() {
        int binding = -97;
        int config = 0;
        int saves = 0;
        for (int tick = 0; tick < 20; tick++) {
            int want = KeyCodes.reconcile(binding, config);
            if (want != config) {
                config = KeyCodes.sanitize(want, 0); // save() sanitizes before writing
                saves++;
            }
            binding = want;
        }
        assertEquals(1, saves);
        assertEquals(-97, config);
        assertEquals(-97, binding);
    }

    /** A code the config cannot hold is pushed back onto the KeyBinding instead of saved. */
    @Test
    public void unstorableBindingIsResetToTheConfig() {
        assertEquals(RSHIFT, KeyCodes.reconcile(-150, RSHIFT));
        int binding = -150;
        int config = RSHIFT;
        int saves = 0;
        for (int tick = 0; tick < 20; tick++) {
            int want = KeyCodes.reconcile(binding, config);
            if (want != config) {
                config = KeyCodes.sanitize(want, RSHIFT);
                saves++;
            }
            binding = want;
        }
        assertEquals(0, saves);
        assertEquals(RSHIFT, binding);
    }

    @Test
    public void keyboardRebindIsCopiedOnce() {
        assertEquals(30, KeyCodes.reconcile(30, RSHIFT));
        assertEquals(RSHIFT, KeyCodes.reconcile(RSHIFT, RSHIFT));
    }

    @Test
    public void mouseButtonsMapLikeVanillaControls() {
        assertEquals(-100, KeyCodes.fromMouseButton(0));
        assertEquals(-97, KeyCodes.fromMouseButton(3));
        // Weave posts MouseEvent for moves and wheel too, with button -1: never a bound code.
        assertFalse(KeyCodes.matches(KeyCodes.sanitize(KeyCodes.fromMouseButton(-1), 0),
                KeyCodes.fromMouseButton(-1)));
        assertFalse(KeyCodes.matches(-97, KeyCodes.fromMouseButton(-1)));
    }

    @Test
    public void matchesNeedsABoundCode() {
        assertTrue(KeyCodes.matches(-97, KeyCodes.fromMouseButton(3)));
        assertTrue(KeyCodes.matches(RSHIFT, RSHIFT));
        assertFalse(KeyCodes.matches(0, 0)); // KEY_NONE is unbound, never a press
        assertFalse(KeyCodes.matches(-97, -98));
    }
}
