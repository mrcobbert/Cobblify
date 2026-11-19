package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The derived FOV multiplier (TASK B6; PLAN Phase 7): {@link DerivedFov} against vanilla's
 * {@code AbstractClientPlayer.getFovModifier()} for walking, sprinting (+30 %), Speed II (+40 %) and a bow draw. The
 * movement-speed values are what vanilla's attribute gives with those operation-2 modifiers on the player's base
 * {@code 0.1F}.
 */
public class DerivedFovTest {

    private static final float WALK = 0.1f;
    private static final double BASE = 0.1f;

    @Test
    public void walkingIsOne() {
        assertEquals(1f, DerivedFov.modifier(BASE, WALK, false, false, 0), 0f);
    }

    @Test
    public void sprintWidensByFifteenPercent() {
        assertEquals(1.15f, DerivedFov.modifier(BASE * 1.3, WALK, false, false, 0), 1e-6f);
    }

    @Test
    public void speedTwoWidensByTwentyPercentAndAddsUpWithSprint() {
        assertEquals(1.2f, DerivedFov.modifier(BASE * 1.4, WALK, false, false, 0), 1e-6f);
        assertEquals(1.41f, DerivedFov.modifier(BASE * 1.3 * 1.4, WALK, false, false, 0), 1e-6f);
    }

    @Test
    public void aBowNarrowsBySquaredDrawUpToFifteenPercent() {
        assertEquals(1f, DerivedFov.modifier(BASE, WALK, false, true, 0), 0f);
        assertEquals(1f - 0.25f * 0.15f, DerivedFov.modifier(BASE, WALK, false, true, 10), 1e-6f);
        assertEquals("full draw", 0.85f, DerivedFov.modifier(BASE, WALK, false, true, 20), 1e-6f);
        assertEquals("beyond a full draw", 0.85f, DerivedFov.modifier(BASE, WALK, false, true, 400), 1e-6f);
        assertEquals("sprinting while drawing", 1.15f * 0.85f,
                DerivedFov.modifier(BASE * 1.3, WALK, false, true, 20), 1e-6f);
        assertEquals("only a bow narrows", 1f, DerivedFov.modifier(BASE, WALK, false, false, 20), 0f);
    }

    @Test
    public void flyingAndAZeroWalkSpeedFollowVanilla() {
        assertEquals(1.1f, DerivedFov.modifier(BASE, WALK, true, false, 0), 1e-6f);
        assertEquals(1f, DerivedFov.modifier(BASE * 1.3, 0f, false, false, 0), 0f);
        assertEquals(1f, DerivedFov.modifier(Double.NaN, WALK, false, false, 0), 0f);
        assertEquals(0.85f, DerivedFov.modifier(BASE, 0f, false, true, 20), 1e-6f);
    }
}
