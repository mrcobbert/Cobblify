package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ScaleStepsTest {

    @Test
    public void aStepCatchesASizeWithinReach() {
        assertEquals(1.5f, ScaleSteps.snap(1.46f, 2, 0.05f), 0f);
        assertEquals(1.5f, ScaleSteps.snap(1.55f, 2, 0.05f), 0f);
        assertEquals(2f / 3f, ScaleSteps.snap(0.7f, 3, 0.05f), 1e-6f);
        assertEquals(1f, ScaleSteps.snap(1.03f, 1, 0.05f), 0f);
    }

    @Test
    public void sizesBetweenStepsStayAsTheyAre() {
        assertEquals(1.25f, ScaleSteps.snap(1.25f, 2, 0.05f), 0f);
        assertEquals(1.44f, ScaleSteps.snap(1.44f, 2, 0.05f), 0f);
        assertEquals(1.2f, ScaleSteps.snap(1.2f, 3, 0.05f), 0f); // GUI 3: steps at 1.0 and 1.333
    }

    @Test
    public void nothingSnapsBelowOneDevicePixelPerFontPixel() {
        assertEquals(0.5f, ScaleSteps.snap(0.47f, 2, 0.05f), 0f);
        assertEquals(0.3f, ScaleSteps.snap(0.3f, 2, 0.05f), 0f); // far from 0.5: left alone
        assertEquals(0.25f, ScaleSteps.atMost(0.1f, 4), 0f);
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
