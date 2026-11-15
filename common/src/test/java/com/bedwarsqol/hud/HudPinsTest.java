package com.bedwarsqol.hud;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pin-on-change: every other shown Auto item keeps the spot it has, so a change to one item moves nothing else. */
public class HudPinsTest {

    private static final float EPS = 1e-3f;

    @Test
    public void onlyOtherShownAutoItemsPin() {
        assertTrue(HudPins.shouldPin("armor", true, HudPlacement.AUTO, Collections.singleton("potion")));
        assertFalse("hidden", HudPins.shouldPin("armor", false, HudPlacement.AUTO, Collections.<String>emptySet()));
        assertFalse("already placed", HudPins.shouldPin("armor", true, 0, Collections.<String>emptySet()));
        assertFalse("the item being changed", HudPins.shouldPin("potion", true, HudPlacement.AUTO,
                Arrays.asList("potion", "armor")));
    }

    @Test
    public void aPinnedItemReproducesItsBoxForEveryAnchor() {
        float sw = 427f, sh = 240f, w = 51.5f, h = 22f;
        float[][] spots = {{2f, 2f}, {188.25f, 2f}, {373.5f, 3f}, {2f, 109f}, {188.75f, 100.5f}, {373f, 109f},
                {2f, 216f}, {188f, 215.5f}, {373.5f, 216f}};
        HudModuleState auto = new HudModuleState(0, 0, HudPlacement.AUTO, 1.5f, true, false);
        for (float[] p : spots) {
            HudModuleState s = HudPins.pinned(auto, p[0], p[1], w, h, sw, sh);
            assertTrue(s.anchor >= 0 && s.anchor <= 8);
            assertEquals(HudPlacement.anchorFor(p[0], p[1], w, h, sw, sh), s.anchor);
            assertEquals(p[0], HudPlacement.absoluteX(s.exactX(), s.anchor, w, sw), EPS);
            assertEquals(p[1], HudPlacement.absoluteY(s.exactY(), s.anchor, h, sh), EPS);
            assertEquals("scale kept", 1.5f, s.scale, 0f);
            assertTrue("on/off kept", s.enabled);
            assertFalse("Background kept", s.background);
        }
    }

    @Test
    public void anItemHoldingItsTopKeepsItsSmallerLivePanelWhereItWas() {
        // outcome review round 1, I1: GUI 369x447, Team Upgrades' Full box (5,159,130,129), live Compact panel 30 high
        float sw = 369f, sh = 447f, w = 130f, h = 129f, liveH = 30f;
        HudModuleState auto = new HudModuleState(0, 0, HudPlacement.AUTO, 1f, true, true);
        float[][] spots = {{5f, 159f}, {5f, 2f}, {120f, 160f}, {234f, 316f}, {5f, 316f}};
        for (float[] p : spots) {
            HudModuleState s = HudPins.pinnedHoldingTop(auto, p[0], p[1], w, h, sw, sh);
            assertTrue("top third's anchor", s.anchor >= 0 && s.anchor <= 2);
            assertEquals(HudPlacement.anchorFor(p[0], p[1], w, h, sw, sh) % 3, s.anchor);
            assertEquals(p[0], HudPlacement.absoluteX(s.exactX(), s.anchor, w, sw), EPS);
            assertEquals("the box stays", p[1], HudPlacement.absoluteY(s.exactY(), s.anchor, h, sh), EPS);
            assertEquals("the live panel stays at the box's top", p[1],
                    HudPlacement.absoluteY(s.exactY(), s.anchor, liveH, sh), EPS);
            assertEquals(1f, s.scale, 0f);
            assertTrue(s.background);
        }
        // the plain pin anchors this box to the screen's middle, which moved the live panel down 49.5 GUI pixels
        HudModuleState plain = HudPins.pinned(auto, 5f, 159f, w, h, sw, sh);
        assertEquals(208.5f, HudPlacement.absoluteY(plain.exactY(), plain.anchor, liveH, sh), EPS);
    }

    @Test
    public void showingPutsTheItemOnAutoFromAnyPreviousSpot() {
        HudModuleState hidden = new HudModuleState(120, -30, 8, 2f, false, false, 0.25f, -0.5f);
        HudModuleState s = HudPins.shown(hidden);
        assertTrue(s.enabled);
        assertEquals(HudPlacement.AUTO, s.anchor);
        assertEquals(0f, s.fracX, 0f);
        assertEquals(0f, s.fracY, 0f);
        assertEquals(2f, s.scale, 0f);
        assertFalse(s.background);
    }
}
