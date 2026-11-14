package com.bedwarsqol.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Holding Tab in Edit HUD (G28): the only input is the player-list key's keyboard state; the binding the editor presses
 * is output only, so a physical release is always seen (plan review R1-B1).
 */
public class EditTabHoldTest {

    @Test
    public void pressThenReleaseFromTheKeyboardState() {
        EditTabHold h = new EditTabHold();
        // the reviewer's counterexample: false, true, false, false
        assertEquals(EditTabHold.Step.NONE, h.step(false));
        assertFalse(h.held());
        assertEquals(EditTabHold.Step.PRESS, h.step(true));
        assertTrue(h.held());
        assertEquals(EditTabHold.Step.RELEASE, h.step(false));
        assertFalse(h.held());
        assertEquals(EditTabHold.Step.NONE, h.step(false));
        assertFalse("ends not held", h.held());
    }

    @Test
    public void keyRepeatGivesOnePress() {
        EditTabHold h = new EditTabHold();
        assertEquals(EditTabHold.Step.PRESS, h.step(true));
        assertEquals(EditTabHold.Step.NONE, h.step(true));
        assertEquals(EditTabHold.Step.NONE, h.step(true));
        assertTrue(h.held());
        assertEquals(EditTabHold.Step.RELEASE, h.step(false));
    }

    @Test
    public void theOnlyInputIsTheKeyboardState() throws Exception {
        // no input for the binding: step(boolean) is the one way in, so the pressed binding cannot feed back
        int steppers = 0;
        for (java.lang.reflect.Method m : EditTabHold.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers()) && m.getParameterTypes().length > 0) {
                assertEquals("step", m.getName());
                assertEquals(1, m.getParameterTypes().length);
                assertEquals(boolean.class, m.getParameterTypes()[0]);
                steppers++;
            }
        }
        assertEquals(1, steppers);
    }
}
