package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SprintRulesTest {

    @Test
    public void aPressFlipsTheToggle() {
        assertTrue(SprintRules.toggled(false, true, true, false));
        assertFalse(SprintRules.toggled(true, true, true, false));
    }

    @Test
    public void noPressKeepsTheState() {
        assertTrue(SprintRules.toggled(true, true, false, false));
        assertFalse(SprintRules.toggled(false, true, false, false));
    }

    @Test
    public void aPressWithAScreenOpenIsIgnored() {
        assertFalse(SprintRules.toggled(false, true, true, true));
        assertTrue(SprintRules.toggled(true, true, true, true));
    }

    @Test
    public void turningTheOptionOffTurnsTheToggleOff() {
        assertFalse(SprintRules.toggled(true, false, false, false));
        assertFalse(SprintRules.toggled(false, false, true, false));
    }

    @Test
    public void sprintIsHeldOnlyWhileToggledInAWorldWithNoScreen() {
        assertTrue(SprintRules.holdSprint(true, true, false));
        assertFalse(SprintRules.holdSprint(false, true, false));
        assertFalse(SprintRules.holdSprint(true, false, false));
        assertFalse(SprintRules.holdSprint(true, true, true));
    }

    @Test
    public void defaultsAreOnAndKeyGNamedToggleSprint() {
        assertTrue(SprintRules.DEFAULT_TOGGLE_SPRINT_ENABLED);
        assertEquals(34, SprintRules.DEFAULT_TOGGLE_SPRINT_KEY); // LWJGL Keyboard.KEY_G
        assertEquals("Toggle Sprint", SprintRules.TOGGLE_SPRINT_KEY_NAME); // options.txt key_Toggle Sprint
    }
}
