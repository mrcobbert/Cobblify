package com.bedwarsqol.rewind.export;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link SettleRule} with a fake wall clock (Rewind Export PLAN Solution 2 "Settle rule", review I6; Phase 2): a held
 * frame is taken once at least {@code holdMs} has passed since the terrain tick and the last three draws agree; a draw
 * that differs restarts the three; the 2 s cap ends it.
 */
public class SettleRuleTest {

    private static float[] grid(float v) {
        float[] g = new float[SettleRule.GRID_W * SettleRule.GRID_H];
        Arrays.fill(g, v);
        return g;
    }

    @Test
    public void threeEqualGridsInsideTheHoldDoNotSettle() {
        SettleRule r = SettleRule.bestEffort(100, 1_000);
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(10), 1_000));
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(10), 1_005));
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(10), 1_010));
        for (long t = 1_015; t < 1_100; t += 5) {
            assertEquals("at " + t, SettleRule.State.SETTLING, r.offer(grid(10), t));
        }
        assertEquals(SettleRule.State.SETTLED, r.offer(grid(10), 1_100));
        assertEquals(1_100 - 1_000, r.waitedMs());
    }

    @Test
    public void aChangedGridRestartsTheCount() {
        // Hold 100 ms: the old picture is stable for 75 ms, then at 80 ms the mesh lands late.
        SettleRule r2 = SettleRule.bestEffort(100, 0);
        for (long t = 0; t <= 75; t += 5) {
            assertEquals(SettleRule.State.SETTLING, r2.offer(grid(10), t));
        }
        assertEquals(SettleRule.State.SETTLING, r2.offer(grid(40), 80)); // differs: count restarts at 1
        assertEquals(SettleRule.State.SETTLING, r2.offer(grid(40), 85));
        assertEquals(SettleRule.State.SETTLING, r2.offer(grid(40), 90)); // three equal, hold not passed
        assertEquals(SettleRule.State.SETTLED, r2.offer(grid(40), 100));

        SettleRule r3 = SettleRule.bestEffort(50, 0);
        for (long t = 0; t < 50; t += 5) {
            assertEquals(SettleRule.State.SETTLING, r3.offer(grid(10), t));
        }
        assertEquals(SettleRule.State.SETTLING, r3.offer(grid(40), 80));
        assertEquals(SettleRule.State.SETTLING, r3.offer(grid(40), 85));
        assertEquals(SettleRule.State.SETTLED, r3.offer(grid(40), 90)); // only after three equal grids past it
    }

    @Test
    public void smallDifferencesCountAsEqual() {
        SettleRule r = SettleRule.bestEffort(0, 0);
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(10f), 0));
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(10.9f), 1));
        assertEquals(SettleRule.State.SETTLED, r.offer(grid(11.8f), 2)); // each step 0.9 <= 1.0
        SettleRule s = SettleRule.bestEffort(0, 0);
        s.offer(grid(10f), 0);
        s.offer(grid(11.2f), 1); // 1.2 > 1.0: restarts
        assertEquals(SettleRule.State.SETTLING, s.offer(grid(11.2f), 2));
        assertEquals(SettleRule.State.SETTLED, s.offer(grid(11.2f), 3));
    }

    @Test
    public void aClassWithNoHoldSettlesOnStabilityAlone() {
        SettleRule r = SettleRule.bestEffort(0, 500);
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(3), 500));
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(3), 501));
        assertEquals(SettleRule.State.SETTLED, r.offer(grid(3), 502));
    }

    @Test
    public void theCapEndsItAndReportsCapped() {
        SettleRule r = SettleRule.bestEffort(100, 0);
        float v = 0;
        SettleRule.State s = SettleRule.State.SETTLING;
        long t = 0;
        for (; t < SettleRule.CAP_MS; t += 16) {
            s = r.offer(grid(v += 5), t);
            assertEquals("at " + t, SettleRule.State.SETTLING, s);
        }
        assertEquals(SettleRule.State.CAPPED, r.offer(grid(v + 5), SettleRule.CAP_MS));
        assertTrue(r.capped());
    }

    @Test
    public void readySignalIgnoresTheHoldButWaitsForReady() {
        SettleRule r = SettleRule.readySignal(0);
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(1), 0, false));
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(1), 1, false));
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(1), 2, false));
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(1), 3, true)); // stable draws count once ready
        assertEquals(SettleRule.State.SETTLING, r.offer(grid(1), 4, true));
        assertEquals(SettleRule.State.SETTLED, r.offer(grid(1), 5, true));
        SettleRule late = SettleRule.readySignal(0);
        late.offer(grid(1), 0, true);
        late.offer(grid(1), 1, true);
        assertEquals(SettleRule.State.SETTLING, late.offer(grid(1), 2, false)); // not ready again: restart
        late.offer(grid(1), 3, true);
        late.offer(grid(1), 4, true);
        assertEquals(SettleRule.State.SETTLED, late.offer(grid(1), 5, true));
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
