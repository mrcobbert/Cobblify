package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ScaleStepsTest {

    @Test
    public void aStepPullsNearbySizesWithoutHoldingThem() {
        assertEquals(1.5f, ScaleSteps.magnet(1.5f, 2), 0f);
        float near = ScaleSteps.magnet(1.45f, 2);              // 0.1 device px per font px under 1.5
        assertTrue(near > 1.45f && near < 1.5f);                // pulled towards the step, not onto it
        assertEquals(1.5f, ScaleSteps.magnet(1.49f, 2), 0f);    // right at it: the step itself
        assertEquals(1.25f, ScaleSteps.magnet(1.25f, 2), 0f);   // halfway: out of reach, as it is
    }

    @Test
    public void theMagnetNeverJumps() {
        for (int sf = 1; sf <= 4; sf++) {
            float prev = ScaleSteps.magnet(0.3f, sf);
            for (float s = 0.3f; s <= 4f; s += 0.0005f) {
                float m = ScaleSteps.magnet(s, sf);
                assertTrue("monotonic at " + s + " GUI " + sf, m >= prev - 1e-6f);
                assertTrue("continuous at " + s + " GUI " + sf, m - prev <= 0.012f / sf + 0.002f);
                prev = m;
            }
        }
    }

    @Test
    public void theReachIsTheSameShareOfAGapAtEveryGuiScale() {
        // at the edge of the reach the size is the pointer's own
        assertEquals(1.35f, ScaleSteps.magnet(1.35f, 2), 1e-6f);   // 0.3 under 1.5 at GUI 2
        assertEquals(1.1f, ScaleSteps.magnet(1.1f, 3), 1e-6f);     // 0.3 over 1.0 at GUI 3
        assertEquals(1.7f, ScaleSteps.magnet(1.7f, 1), 1e-6f);     // 0.3 under 2.0 at GUI 1
    }

    @Test
    public void aSizeAWallStoppedRestsOnTheStepJustBelow() {
        assertEquals(1.5f, ScaleSteps.restOnStep(1.53f, 2, 0.05f), 0f);
        assertEquals(1.5f, ScaleSteps.restOnStep(1.5f, 2, 0.05f), 0f);
        assertEquals(1.62f, ScaleSteps.restOnStep(1.62f, 2, 0.05f), 0f); // too far past the step: flush with the wall
        assertEquals(1.497f, ScaleSteps.restOnStep(1.497f, 2, 0.05f), 0f); // never rounds up into the wall
        assertEquals(1.4999f, ScaleSteps.restOnStep(1.4999f, 2, 0.05f), 0f); // not even by float noise
        assertEquals(0.4f, ScaleSteps.restOnStep(0.4f, 2, 0.05f), 0f); // under the smallest step: as it is
    }

    @Test
    public void atMostNeverRoundsUp() {
        assertEquals(1f, ScaleSteps.atMost(1.49f, 2), 0f);
        assertEquals(1.5f, ScaleSteps.atMost(1.5f, 2), 0f);
        assertEquals(1.5f, ScaleSteps.atMost(1.4999f, 2), 0f); // within float noise of the step
        assertEquals(4f / 3f, ScaleSteps.atMost(1.6f, 3), 1e-6f);
    }
}
