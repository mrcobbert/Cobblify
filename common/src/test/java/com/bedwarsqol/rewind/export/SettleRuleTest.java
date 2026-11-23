package com.bedwarsqol.rewind.export;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link SettleRule} with a fake wall clock, reduced to the frame-0 rule (Rewind Export PLAN Revision 4 and 4.1):
 * frame 0 is taken once the last three draws agree, with no hold; a draw that differs restarts the three; the vanilla
 * renderer's queue signal is only an extra wait (a draw while the queues are busy does not count); the 2 s cap ends it
 * and reports {@code capped}. Mid-range holds are gone: nothing in the rule knows packet classes.
 */
public class SettleRuleTest {

    private static float[] grid(float v) {
        float[] g = new float[SettleRule.GRID_W * SettleRule.GRID_H];
        Arrays.fill(g, v);
        return g;
    }

    @Test
    public void threeEqualDrawsSettleFrameZeroWithNoHold() {
        SettleRule r = SettleRule.frameZero(1_000);
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(10), 1_000));
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(10), 1_005));
        assertEquals("the third equal draw, 10 ms in: no hold", SettleRule.State.SETTLED, r.offer(grid(10), 1_010));
        assertEquals(10, r.waitedMs());
        assertFalse(r.capped());
    }

    @Test
    public void aChangedDrawRestartsTheCount() {
        SettleRule r = SettleRule.frameZero(0);
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(10), 0));
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(10), 5));
        assertEquals("differs: count restarts at 1", SettleRule.State.SETTLING, r.offer(grid(40), 80));
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(40), 85));
        assertEquals("only after three equal draws past it", SettleRule.State.SETTLED, r.offer(grid(40), 90));
    }

    @Test
    public void smallDifferencesCountAsEqual() {
        SettleRule r = SettleRule.frameZero(0);
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(10f), 0));
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(10.9f), 1));
        assertEquals(SettleRule.State.SETTLED, r.offer(grid(11.8f), 2)); // each step 0.9 <= 1.0
        SettleRule s = SettleRule.frameZero(0);
        s.offer(grid(10f), 0);
        s.offer(grid(11.2f), 1); // 1.2 > 1.0: restarts
        assertEquals(SettleRule.State.SETTLING, s.offer(grid(11.2f), 2));
        assertEquals(SettleRule.State.SETTLED, s.offer(grid(11.2f), 3));
    }

    @Test
    public void theQueueSignalIsAnExtraWaitOnly() {
        SettleRule r = SettleRule.frameZero(0);
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(1), 0, false));
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(1), 1, false));
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(1), 2, false));
        assertEquals("stable draws count once the queues read idle", SettleRule.State.SETTLING,
                r.offer(grid(1), 3, true));
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(1), 4, true));
        assertEquals(SettleRule.State.SETTLED, r.offer(grid(1), 5, true));
        SettleRule busy = SettleRule.frameZero(0);
        busy.offer(grid(1), 0, true);
        busy.offer(grid(1), 1, true);
        assertEquals("busy again: restart", SettleRule.State.SETTLING, busy.offer(grid(1), 2, false));
        busy.offer(grid(1), 3, true);
        busy.offer(grid(1), 4, true);
        assertEquals(SettleRule.State.SETTLED, busy.offer(grid(1), 5, true));
    }

    @Test
    public void theCapEndsItAndReportsCapped() {
        SettleRule r = SettleRule.frameZero(0);
        float v = 0;
        for (long t = 0; t < SettleRule.CAP_MS; t += 16) {
            assertEquals("at " + t, SettleRule.State.SETTLING, r.offer(grid(v += 5), t));
        }
        assertEquals(SettleRule.State.CAPPED, r.offer(grid(v + 5), SettleRule.CAP_MS));
        assertTrue(r.capped());
        SettleRule q = SettleRule.frameZero(0);
        for (long t = 0; t < SettleRule.CAP_MS; t += 16) {
            q.offer(grid(1), t, false);
        }
        assertEquals("busy queues end at the cap too", SettleRule.State.CAPPED, q.offer(grid(1), SettleRule.CAP_MS, false));
    }

    /** Revision 4: no per-class hold is left in the rule (no hold constants, no hold factory). */
    @Test
    public void noMidRangeHoldIsLeft() {
        for (java.lang.reflect.Field f : SettleRule.class.getDeclaredFields()) {
            assertFalse("hold constant " + f.getName(), f.getName().startsWith("HOLD_"));
        }
        for (java.lang.reflect.Method m : SettleRule.class.getDeclaredMethods()) {
            assertFalse("hold factory " + m.getName(), m.getName().equals("bestEffort") || m.getName().equals("readySignal"));
        }
    }

    @Test
    public void lumaGridAveragesCells() {
        int w = 128, h = 72;
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean white = x < w / 2;
                b.put((byte) (white ? 255 : 0)).put((byte) (white ? 255 : 0)).put((byte) (white ? 255 : 0))
                        .put((byte) 255);
            }
        }
        b.flip();
        float[] g = SettleRule.grid(b, w, h);
        assertEquals(SettleRule.GRID_W * SettleRule.GRID_H, g.length);
        assertEquals(255f, g[0], 0.5f);
        assertEquals(0f, g[SettleRule.GRID_W - 1], 0.5f);
        assertEquals(0, b.position()); // the caller's buffer is not consumed
        assertEquals(127.5, SettleRule.diff(g, grid(0)) , 0.5);
        assertEquals(0.0, SettleRule.diff(g, g), 0.0);
    }
}
