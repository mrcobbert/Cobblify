package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ScaleStepsTest {

    @Test
    public void nearestRoundsToWholeDevicePixelsPerFontPixel() {
        assertEquals(1f, ScaleSteps.nearest(1.2f, 2), 0f);
        assertEquals(1.5f, ScaleSteps.nearest(1.3f, 2), 0f);
        assertEquals(1f, ScaleSteps.nearest(1.2499999f, 2), 0f); // parked under a step edge: draws one step lower
        assertEquals(2f / 3f, ScaleSteps.nearest(0.7f, 3), 1e-6f);
        assertEquals(1f, ScaleSteps.nearest(1f, 1), 0f);
    }

    @Test
    public void nothingIsSmallerThanOneDevicePixelPerFontPixel() {
        assertEquals(0.5f, ScaleSteps.nearest(0.3f, 2), 0f);
        assertEquals(1f, ScaleSteps.nearest(0.3f, 1), 0f);
        assertEquals(0.25f, ScaleSteps.atMost(0.1f, 4), 0f);
    }

    @Test
    public void atMostNeverRoundsUp() {
        assertEquals(1f, ScaleSteps.atMost(1.49f, 2), 0f);
        assertEquals(1.5f, ScaleSteps.atMost(1.5f, 2), 0f);
        assertEquals(1.5f, ScaleSteps.atMost(1.4999f, 2), 0f); // within float noise of the step
        assertEquals(4f / 3f, ScaleSteps.atMost(1.6f, 3), 1e-6f);
    }
}
