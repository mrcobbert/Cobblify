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

    // ----- sub-pixel positions (edit-hud): the stored int plus a fraction in [-0.5, 0.5) -----

    private static final float SPLIT_EPS = 1e-4f;

    @Test
    public void exactPositionSplitsIntoTheRoundedIntAndAFraction() {
        float[] values = {2.5f, -2.5f, 0.5f, -0.5f, 1f / 3f, -1f / 3f, 4000.333f, 0f, 7f, -12.75f};
        for (float v : values) {
            HudModuleState s = new HudModuleState(0, 0, -1, 1f, true).withExactPosition(v, -v, 4);
            assertEquals("x of " + v, v, s.x + s.fracX, SPLIT_EPS);
            assertEquals("y of " + -v, -v, s.y + s.fracY, SPLIT_EPS);
            assertEquals(v, s.exactX(), SPLIT_EPS);
            assertEquals(-v, s.exactY(), SPLIT_EPS);
            assertTrue("fracX " + s.fracX + " of " + v, s.fracX >= -0.5f && s.fracX < 0.5f);
            assertTrue("fracY " + s.fracY + " of " + -v, s.fracY >= -0.5f && s.fracY < 0.5f);
            assertEquals(Math.round(v), s.x);
            assertEquals(4, s.anchor);
        }
    }

    @Test
    public void wholePartIsWhatTheIntPathWouldHaveStored() {
        float[][] cases = {{110.5f, 206f, 427f}, {10.4f, 40f, 480f}, {300.6f, 51f, 640f}, {-3.25f, 22f, 427.5f}};
        for (float[] c : cases) {
            for (int anchor = 0; anchor <= 8; anchor++) {
                float exact = HudPlacement.exactStoredX(c[0], anchor, c[1], c[2]);
                float exactY = HudPlacement.exactStoredY(c[0], anchor, c[1], c[2]);
                HudModuleState s = new HudModuleState(0, 0, -1, 1f, true).withExactPosition(exact, exactY, anchor);
                assertEquals(HudPlacement.storedX(c[0], anchor, c[1], c[2]), s.x);
                assertEquals(HudPlacement.storedY(c[0], anchor, c[1], c[2]), s.y);
            }
        }
    }

    @Test
    public void olderConstructorsHaveNoFraction() {
        HudModuleState five = new HudModuleState(3, 4, 2, 1.5f, true);
        HudModuleState six = new HudModuleState(3, 4, 2, 1.5f, true, false);
        assertEquals(0f, five.fracX, 0f);
        assertEquals(0f, five.fracY, 0f);
        assertEquals(0f, six.fracX, 0f);
        assertEquals(0f, six.fracY, 0f);
        assertEquals(3f, six.exactX(), 0f);
        assertEquals(new HudModuleState(3, 4, 2, 1.5f, true, false, 0f, 0f), six);
    }

    @Test
    public void eightArgConstructorCarriesTheFraction() {
        HudModuleState s = new HudModuleState(10, -4, 8, 2f, false, false, 0.25f, -0.5f);
        assertState(s, 10, -4, 8, 2f, false, false);
        assertEquals(0.25f, s.fracX, 0f);
        assertEquals(-0.5f, s.fracY, 0f);
        assertEquals(10.25f, s.exactX(), 0f);
        assertEquals(-4.5f, s.exactY(), 0f);
    }

    @Test
    public void equalsAndHashCodeIncludeTheFraction() {
        HudModuleState a = new HudModuleState(3, 4, 2, 1.5f, true, true, 0.25f, 0f);
        HudModuleState b = new HudModuleState(3, 4, 2, 1.5f, true, true, 0f, 0f);
        HudModuleState c = new HudModuleState(3, 4, 2, 1.5f, true, true, 0.25f, 0.125f);
        assertNotEquals("undo must see a sub-pixel move", a, b);
        assertNotEquals(a, c);
        assertNotEquals(a.hashCode(), b.hashCode());
        assertEquals(a, new HudModuleState(3, 4, 2, 1.5f, true, true, 0.25f, 0f));
        assertEquals(a.hashCode(), new HudModuleState(3, 4, 2, 1.5f, true, true, 0.25f, 0f).hashCode());
    }

    @Test
    public void withPositionClearsTheFractionOtherWithersKeepIt() {
        HudModuleState s = new HudModuleState(3, 4, 2, 1.5f, true, false, 0.25f, -0.25f);
        HudModuleState moved = s.withPosition(0, 0, -1);
        assertEquals(0f, moved.fracX, 0f);
        assertEquals(0f, moved.fracY, 0f);
        assertFalse(moved.background);
        for (HudModuleState kept : new HudModuleState[]{s.withScale(2f), s.withEnabled(false), s.withBackground(true)}) {
            assertEquals(0.25f, kept.fracX, 0f);
            assertEquals(-0.25f, kept.fracY, 0f);
        }
    }

    @Test
    public void fractionWithersKeepEverythingElse() {
        HudModuleState s = new HudModuleState(3, 4, 2, 1.5f, false, false);
        HudModuleState f = s.withFraction(0.375f, -0.125f);
        assertState(f, 3, 4, 2, 1.5f, false, false);
        assertEquals(0.375f, f.fracX, 0f);
        assertEquals(-0.125f, f.fracY, 0f);
        HudModuleState e = s.withExactPosition(20.25f, 30.75f, 5);
        assertState(e, 20, 31, 5, 1.5f, false, false);
        assertEquals(0.25f, e.fracX, 0f);
        assertEquals(-0.25f, e.fracY, 0f);
    }
}
