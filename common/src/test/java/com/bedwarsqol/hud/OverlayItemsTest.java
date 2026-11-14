package com.bedwarsqol.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OverlayItemsTest {

    @Test
    public void legacySizesMapToTheScalesTheyDrewAt() {
        assertEquals(0.7f, OverlayItems.legacySizeScale(0), 0f);
        assertEquals(0.85f, OverlayItems.legacySizeScale(1), 0f);
        assertEquals(1.0f, OverlayItems.legacySizeScale(2), 0f);
    }

    @Test
    public void outOfRangeSizesClampFirstLikeTheOldSanitize() {
        // the old sanitize clamped Scoreboard Size / Tab List Size to 0..2 before the scale switch
        assertEquals(0.7f, OverlayItems.legacySizeScale(-1), 0f);
        assertEquals(1.0f, OverlayItems.legacySizeScale(5), 0f);
    }

    @Test
    public void theDefaultScaleIsVanillaSize() {
        assertEquals(1f, OverlayItems.DEFAULT_SCALE, 0f);
    }
}
