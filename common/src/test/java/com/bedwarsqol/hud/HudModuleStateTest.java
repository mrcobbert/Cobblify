package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** A module's editable state carries its Background switch; the 5-arg form (Forge, Lunar) means Background On. */
public class HudModuleStateTest {

    private static void assertState(HudModuleState s, int x, int y, int anchor, float scale, boolean enabled,
                                    boolean background) {
        assertEquals(x, s.x);
        assertEquals(y, s.y);
        assertEquals(anchor, s.anchor);
        assertEquals(scale, s.scale, 0f);
        assertEquals(enabled, s.enabled);
        assertEquals(background, s.background);
    }

    @Test
    public void fiveArgStateHasBackgroundOn() {
        HudModuleState s = new HudModuleState(3, 4, 2, 1.5f, true);
        assertState(s, 3, 4, 2, 1.5f, true, true);
        assertEquals(new HudModuleState(3, 4, 2, 1.5f, true, true), s);
    }

    @Test
    public void withBackgroundKeepsTheRest() {
        HudModuleState s = new HudModuleState(3, 4, 2, 1.5f, true).withBackground(false);
        assertState(s, 3, 4, 2, 1.5f, true, false);
        assertState(s.withBackground(true), 3, 4, 2, 1.5f, true, true);
    }

    @Test
    public void otherWithersKeepBackground() {
        HudModuleState off = new HudModuleState(3, 4, 2, 1.5f, true, false);
        assertState(off.withPosition(7, 8, 5), 7, 8, 5, 1.5f, true, false);
        assertState(off.withScale(0.75f), 3, 4, 2, 0.75f, true, false);
        assertState(off.withEnabled(false), 3, 4, 2, 1.5f, false, false);
    }

    @Test
    public void equalsAndHashCodeIncludeBackground() {
        HudModuleState on = new HudModuleState(3, 4, 2, 1.5f, true, true);
        HudModuleState off = new HudModuleState(3, 4, 2, 1.5f, true, false);
        assertNotEquals("Edit HUD's undo must see a Background change", on, off);
        assertNotEquals(on.hashCode(), off.hashCode());
        assertEquals(off, new HudModuleState(3, 4, 2, 1.5f, true, false));
        assertEquals(off.hashCode(), new HudModuleState(3, 4, 2, 1.5f, true, false).hashCode());
        assertTrue(on.equals(on));
        assertFalse(on.equals(null));
    }
}
