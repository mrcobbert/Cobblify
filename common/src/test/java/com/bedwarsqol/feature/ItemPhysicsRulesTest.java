package com.bedwarsqol.feature;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Item Physics: rest pose, tumble, rest height and pile offsets; module off keeps vanilla. */
public class ItemPhysicsRulesTest {

    private static final float EPS = 1e-6f;

    @Test
    public void offByDefault() {
        assertFalse(ItemPhysicsRules.DEFAULT_ENABLED);
    }

    // ---- module off: every hook keeps vanilla ----

    @Test
    public void moduleOffKeepsVanillaTranslateY() {
        float vanillaY = 0.37f;
        assertEquals(vanillaY, ItemPhysicsRules.restY(false, vanillaY, 5.0, 0.25f, 0.125), 0f);
        assertEquals(vanillaY, ItemPhysicsRules.restY(false, vanillaY, 5.0, 0.25f, 0.0), 0f);
    }

    @Test
    public void moduleOffKeepsVanillaSpinAndCentering() {
        assertTrue(ItemPhysicsRules.vanillaSpin(false));
        assertTrue(ItemPhysicsRules.vanillaPileCentering(false));
        assertFalse(ItemPhysicsRules.vanillaSpin(true));
        assertFalse(ItemPhysicsRules.vanillaPileCentering(true));
    }

    // ---- 11 lie flat ----

    @Test
    public void restPitchIsFlat() {
        assertEquals(90f, ItemPhysicsRules.REST_PITCH, 0f);
        assertEquals(90f, ItemPhysicsRules.pitch(0, 0f, true), 0f);
        assertEquals(90f, ItemPhysicsRules.pitch(1234, 0.7f, true), 0f);
    }

    @Test
    public void yawIsStablePerEntityAndInRange() {
        for (int id = -50; id < 500; id++) {
            float yaw = ItemPhysicsRules.yaw(id);
            assertTrue("yaw in [0,360) for id " + id, yaw >= 0f && yaw < 360f);
            assertEquals(yaw, ItemPhysicsRules.yaw(id), 0f);
        }
        assertEquals(ItemPhysicsRules.yaw(Integer.MIN_VALUE), ItemPhysicsRules.yaw(Integer.MIN_VALUE), 0f);
        assertEquals(ItemPhysicsRules.yaw(Integer.MAX_VALUE), ItemPhysicsRules.yaw(Integer.MAX_VALUE), 0f);
    }

    @Test
    public void yawDiffersBetweenNeighbouringItems() {
        Set<Float> seen = new HashSet<>();
        for (int id = 100; id < 120; id++) {
            assertNotEquals(ItemPhysicsRules.yaw(id), ItemPhysicsRules.yaw(id + 1), 1f);
            seen.add(ItemPhysicsRules.yaw(id));
        }
        assertTrue("20 consecutive ids give at least 15 distinct yaws", seen.size() >= 15);
    }

    // ---- 12 tumble ----

    @Test
    public void tumbleAdvancesWithAgeAndPartialAtOneSpeed() {
        float speed = ItemPhysicsRules.TUMBLE_DEG_PER_TICK;
        assertTrue(speed > 0f);
        float a = ItemPhysicsRules.pitch(3, 0f, false);
        float b = ItemPhysicsRules.pitch(3, 0.5f, false);
        float c = ItemPhysicsRules.pitch(4, 0f, false);
        assertEquals(speed * 0.5f, b - a, 1e-4f);
        assertEquals(speed, c - a, 1e-4f);
        assertEquals((90f + speed * 3f) % 360f, a, 1e-4f);
    }

    @Test
    public void tumbleStartsFlatAndStaysInRange() {
        assertEquals(90f, ItemPhysicsRules.pitch(0, 0f, false), 0f);
        for (int age = 0; age < 2000; age += 7) {
            float p = ItemPhysicsRules.pitch(age, 0.99f, false);
            assertTrue("pitch in [0,360) at age " + age, p >= 0f && p < 360f);
        }
    }

    @Test
    public void tumbleStopsFlatOnLanding() {
        assertNotEquals(90f, ItemPhysicsRules.pitch(7, 0.3f, false), 0.01f);
        assertEquals(90f, ItemPhysicsRules.pitch(7, 0.3f, true), 0f);
    }

    // ---- 17 rest height: flat sprite vs 3D block, and the surface shift ----

    @Test
    public void restLiftPutsTheModelBottomOnTheSurface() {
        // Flat sprite: 1 px of a 16 px model drawn at 0.5 -> 1/32 thick; its centre 1/64 above the surface.
        float flat = ItemPhysicsRules.restLift(false);
        assertTrue(flat >= 1f / 64f && flat < 1f / 32f);
        // 3D block: unit cube at 0.5 * 0.5 -> 1/4 wide; its centre 1/8 above the surface.
        float block = ItemPhysicsRules.restLift(true);
        assertTrue(block >= 1f / 8f && block < 1f / 8f + 1f / 64f);
        assertEquals(flat - 1f / 64f, block - 1f / 8f, EPS); // the same small gap for both
    }

    private static final float[] IDENTITY_GROUND = {0, 0, 0, 0, 0, 0, 1, 1, 1};

    @Test
    public void measuredLiftMatchesTheFixedOneForASpriteAndAFullBlock() {
        float[] sprite = {0, 0, 7.5f / 16f, 1, 1, 8.5f / 16f};
        assertEquals(ItemPhysicsRules.restLift(false), ItemPhysicsRules.restLift(sprite, IDENTITY_GROUND, false), EPS);
        float[] cube = {0, 0, 0, 1, 1, 1};
        assertEquals(ItemPhysicsRules.restLift(true), ItemPhysicsRules.restLift(cube, IDENTITY_GROUND, true), EPS);
    }

    @Test
    public void measuredLiftKeepsAFenceRailAboveTheSurface() {
        // Vanilla fence_inventory: rails from Z=-2 to Z=18 of 16.
        float[] fence = {6f / 16f, 0, -2f / 16f, 10f / 16f, 1, 18f / 16f};
        float lift = ItemPhysicsRules.restLift(fence, IDENTITY_GROUND, true);
        float railBottom = lift - (18f / 16f - 0.5f) * 0.25f;
        assertEquals(ItemPhysicsRules.GROUND_GAP, railBottom, EPS);
    }

    @Test
    public void measuredLiftFollowsAPackGroundTransform() {
        float[] sprite = {0, 0, 7.5f / 16f, 1, 1, 8.5f / 16f};
        float[] scaled = {0, 0, 0, 0, 0, 0, 2, 2, 2};
        assertEquals(1f / 32f + ItemPhysicsRules.GROUND_GAP, ItemPhysicsRules.restLift(sprite, scaled, false), EPS);
        float[] moved = {0, 0, 0, 0, 0, 0.125f, 1, 1, 1};
        assertEquals(1f / 64f + 0.125f + ItemPhysicsRules.GROUND_GAP,
                ItemPhysicsRules.restLift(sprite, moved, false), EPS);
        // Turned a quarter about X, the sprite's 1 px depth is replaced by its half height.
        float[] turned = {90, 0, 0, 0, 0, 0, 1, 1, 1};
        assertEquals(0.25f + ItemPhysicsRules.GROUND_GAP, ItemPhysicsRules.restLift(sprite, turned, false), EPS);
    }

    @Test
    public void measuredLiftFallsBackWithoutVertices() {
        assertEquals(ItemPhysicsRules.restLift(true), ItemPhysicsRules.restLift(new float[0], IDENTITY_GROUND, true), 0f);
        assertEquals(ItemPhysicsRules.restLift(false), ItemPhysicsRules.restLift(null, IDENTITY_GROUND, false), 0f);
    }

    @Test
    public void restYReplacesBobWithLiftAndSurfaceShift() {
        assertEquals(5f + ItemPhysicsRules.restLift(true), ItemPhysicsRules.restY(true, 9f, 5.0, ItemPhysicsRules.restLift(true), 0.0), EPS);
        assertEquals(5f + ItemPhysicsRules.restLift(false) + 0.125f,
                ItemPhysicsRules.restY(true, 9f, 5.0, ItemPhysicsRules.restLift(false), 0.125), EPS);
        assertEquals(5f + ItemPhysicsRules.restLift(false) - 0.0625f,
                ItemPhysicsRules.restY(true, 9f, 5.0, ItemPhysicsRules.restLift(false), -0.0625), EPS);
    }

    @Test
    public void surfaceShiftOnOrdinaryGroundIsZero() {
        // Resting on stone (top y=64): nothing collidable inside, support top == posY.
        assertEquals(0.0, ItemPhysicsRules.surfaceShift(64.0, false, 0.0, true, 64.0), 0.0);
        // Bottom slab: item rests on its top (64.5), the slab is both inside and support.
        assertEquals(0.0, ItemPhysicsRules.surfaceShift(64.5, true, 64.5, true, 64.5), 0.0);
    }

    @Test
    public void surfaceShiftLiftsOutOfSnowLayersAndSoulSand() {
        // One snow layer at y=64: collision top 64.0 (the item rests on the block below), drawn top 64.125.
        assertEquals(0.125, ItemPhysicsRules.surfaceShift(64.0, true, 64.125, true, 64.0), 1e-9);
        // Three layers: collision 64.25, drawn 64.375.
        assertEquals(0.125, ItemPhysicsRules.surfaceShift(64.25, true, 64.375, true, 64.25), 1e-9);
        // Soul sand at y=64: collision top 64.875, drawn top 65.0.
        assertEquals(0.125, ItemPhysicsRules.surfaceShift(64.875, true, 65.0, true, 65.0), 1e-9);
    }

    @Test
    public void surfaceShiftLowersOntoABlockDrawnBelowItsCollisionTop() {
        // Farmland at y=63: collision top 64.0, drawn top 63.9375.
        assertEquals(-0.0625, ItemPhysicsRules.surfaceShift(64.0, false, 0.0, true, 63.9375), 1e-9);
    }

    @Test
    public void surfaceShiftIgnoresBlocksWithoutCollisionAndLargeGaps() {
        // Tall grass around the item: no collision, so no lift onto its drawn top.
        assertEquals(0.0, ItemPhysicsRules.surfaceShift(64.0, false, 64.8, true, 64.0), 0.0);
        // Ladder in the item's block: drawn top a whole block up; not a surface the item lies on.
        assertEquals(0.0, ItemPhysicsRules.surfaceShift(64.0, true, 65.0, true, 64.0), 0.0);
        // Fence below (collision 1.5, drawn 1.0): too far to drop.
        assertEquals(0.0, ItemPhysicsRules.surfaceShift(65.5, false, 0.0, true, 65.0), 0.0);
        assertTrue(ItemPhysicsRules.MAX_SURFACE_SHIFT >= 0.125 && ItemPhysicsRules.MAX_SURFACE_SHIFT < 0.25);
    }

    // ---- 14 neat pile ----

    @Test
    public void pileStacksCopiesUpAtAFixedStep() {
        for (boolean gui3d : new boolean[] {false, true}) {
            assertEquals(0f, ItemPhysicsRules.pileUp(0, gui3d), 0f);
            float step = ItemPhysicsRules.pileUp(1, gui3d);
            assertTrue(step > 0f && step <= 1f / 16f);
            for (int i = 0; i < 5; i++) assertEquals(i * step, ItemPhysicsRules.pileUp(i, gui3d), EPS);
        }
        // Flat copies (1/32 thick) do not overlap.
        assertTrue(ItemPhysicsRules.FLAT_PILE_STEP > 1f / 32f);
    }

    @Test
    public void pileSideShiftOnlyFor3dCopies() {
        for (int i = 0; i < 5; i++) assertEquals(0f, ItemPhysicsRules.pileSide(i, false), 0f);
        assertEquals(0f, ItemPhysicsRules.pileSide(0, true), 0f);
        float s = ItemPhysicsRules.pileSide(1, true);
        // 3D copies overlap vertically; a small fixed side shift keeps their faces from being coplanar.
        assertTrue(s > 0f && s < ItemPhysicsRules.BLOCK_PILE_STEP);
        for (int i = 0; i < 5; i++) assertEquals(i * s, ItemPhysicsRules.pileSide(i, true), EPS);
    }

    @Test
    public void pileIsDeterministic() {
        assertEquals(ItemPhysicsRules.pileUp(3, true), ItemPhysicsRules.pileUp(3, true), 0f);
        assertEquals(ItemPhysicsRules.pileSide(4, true), ItemPhysicsRules.pileSide(4, true), 0f);
    }
}
