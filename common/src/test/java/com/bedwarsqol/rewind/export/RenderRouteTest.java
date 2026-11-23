package com.bedwarsqol.rewind.export;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link RenderRoute}'s start-up check (Rewind Export PLAN Solution 2, Phase 2; TASK P9): each failed check selects
 * WINDOW with its own code; a pass keeps the route tried.
 */
public class RenderRouteTest {

    private static RenderRoute.Check passing() {
        RenderRoute.Check c = new RenderRoute.Check();
        c.complete = true;
        c.boundAtReadBack = true;
        c.glError = 0;
        c.uniform = false;
        return c;
    }

    @Test
    public void aPassKeepsTheRoute() {
        for (RenderRoute.Route r : new RenderRoute.Route[]{RenderRoute.Route.OWN, RenderRoute.Route.MAIN}) {
            RenderRoute.Decision d = RenderRoute.decide(r, passing());
            assertEquals(r, d.route);
            assertEquals("ok", d.code);
        }
    }

    @Test
    public void eachFailedCheckSelectsWindow() {
        RenderRoute.Check c = passing();
        c.complete = false;
        assertWindow(RenderRoute.decide(RenderRoute.Route.OWN, c), "incomplete");
        c = passing();
        c.boundAtReadBack = false;
        assertWindow(RenderRoute.decide(RenderRoute.Route.OWN, c), "binding");
        c = passing();
        c.glError = 0x0502;
        assertWindow(RenderRoute.decide(RenderRoute.Route.MAIN, c), "gl-error-0x502");
        c = passing();
        c.uniform = true;
        assertWindow(RenderRoute.decide(RenderRoute.Route.OWN, c), "uniform");
    }

    @Test
    public void windowIsAlwaysWindow() {
        RenderRoute.Decision d = RenderRoute.decide(RenderRoute.Route.WINDOW, passing());
        assertEquals(RenderRoute.Route.WINDOW, d.route);
        assertEquals(RenderRoute.Route.WINDOW, RenderRoute.decide(RenderRoute.Route.WINDOW, null).route);
        assertEquals(RenderRoute.Route.WINDOW, RenderRoute.decide(RenderRoute.Route.OWN, null).route);
    }

    @Test
    public void uniformDetectsAOneColourPicture() {
        int w = 32, h = 18;
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < w * h; i++) b.putInt(0xFF102030);
        b.flip();
        assertTrue(RenderRoute.uniform(b, w, h));
        b.putInt(4 * (w * h / 2), 0xFF112233);
        assertFalse(RenderRoute.uniform(b, w, h));
        assertEquals(0, b.position());
    }

    private static void assertWindow(RenderRoute.Decision d, String code) {
        assertEquals(RenderRoute.Route.WINDOW, d.route);
        assertEquals(code, d.code);
    }
}
