package com.bedwarsqol.hud;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** One drag step: snap (unless Alt), then keep the box on screen. Positions are the visual box, GUI px. */
public class HudDragTest {

    private static final float EPS = 1e-4f;
    private static final List<HudSnapper.Target> NONE = Collections.emptyList();

    /** The raw position after the pointer moved from device x {@code e0} to {@code e1}: start + travel, no rounding. */
    private static float raw(float start, int e0, int e1, int scale) {
        return start + HudGrid.pointer(e1, 0, 720, scale)[0] - HudGrid.pointer(e0, 0, 720, scale)[0];
    }

    @Test
    public void oneDevicePixelMovesOneScreenPixelFromAnOffGridStart() {
        for (int scale : new int[]{2, 3}) {
            float start = 110.5f; // review I1's centred box, here 40 wide so no line is near
            HudDrag.Result a = HudDrag.move(raw(start, 500, 506, scale), 60f, 40f, 20f, 427f, 240f, scale, true, NONE);
            HudDrag.Result b = HudDrag.move(raw(start, 500, 507, scale), 60f, 40f, 20f, 427f, 240f, scale, true, NONE);
            assertEquals(start + 6f / scale, a.x, EPS);
            assertEquals("scale " + scale, 1f / scale, b.x - a.x, EPS);
            assertFalse(a.snapX.snapped);
        }
    }

    @Test
    public void altNeverSnaps() {
        HudDrag.Result r = HudDrag.move(1f, 1f, 40f, 20f, 427f, 240f, 2, false, NONE);
        assertEquals(1f, r.x, 0f);
        assertEquals(1f, r.y, 0f);
        assertNull(r.snapX);
        assertNull(r.snapY);
        // centred 206-wide box, one screen pixel off centre at GUI 3: Alt keeps it there
        HudDrag.Result c = HudDrag.move(110.5f + 1f / 3f, 60f, 206f, 20f, 427f, 240f, 3, false, NONE);
        assertEquals(110.8333f, c.x, EPS);
    }

    @Test
    public void aSnappedEdgeLandsExactlyOnItsTarget() {
        HudSnapper.Target t = new HudSnapper.Target("timer", 300.25f, 50f, 60f, 20f);
        // left edge 1.5 screen px (0.75 GUI px at GUI 2) right of the target's right edge 360.25
        HudDrag.Result r = HudDrag.move(361f, 100f, 40f, 20f, 480f, 270f, 2, true, Collections.singletonList(t));
        assertTrue(r.snapX.snapped);
        assertEquals(360.25f, r.x, 0f);
        assertEquals(100f, r.y, 0f);
    }

    @Test
    public void snapReachIsFourScreenPixels() {
        for (int scale : new int[]{2, 3}) {
            assertEquals(0f, HudDrag.move(3f / scale, 100f, 40f, 20f, 427f, 240f, scale, true, NONE).x, 0f);
            assertEquals(5f / scale, HudDrag.move(5f / scale, 100f, 40f, 20f, 427f, 240f, scale, true, NONE).x, EPS);
        }
    }

    @Test
    public void theBoxStaysOnScreen() {
        HudDrag.Result r = HudDrag.move(-30f, 500f, 40f, 20f, 427f, 240f, 2, false, NONE);
        assertEquals(0f, r.x, 0f);
        assertEquals(220f, r.y, 0f);
        HudDrag.Result far = HudDrag.move(420f, -5f, 40f, 20f, 427f, 240f, 3, true, NONE);
        assertEquals(387f, far.x, 0f);
        assertEquals(0f, far.y, 0f);
    }
}
