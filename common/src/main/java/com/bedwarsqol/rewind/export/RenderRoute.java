package com.bedwarsqol.rewind.export;

import java.nio.ByteBuffer;

/**
 * How export frames are drawn, chosen once per game session by a start-up check (Rewind Export PLAN Solution 2;
 * TASK P9). {@link Route#OWN}: our own framebuffer at the export size. {@link Route#MAIN}: vanilla's main framebuffer
 * resized. {@link Route#WINDOW}: the window's own pixels at its own size, the fallback when a check fails.
 *
 * <p>The check draws one paused frame through the route and reads it back: the framebuffer must be complete, still
 * bound at read-back, {@code glGetError} clean and the picture not one colour. Pure.
 */
public final class RenderRoute {

    /** The three routes, first choice first. */
    public enum Route { OWN, MAIN, WINDOW }

    /** What the start-up frame found. */
    public static final class Check {
        /** {@code glCheckFramebufferStatus} said complete. */
        public boolean complete;
        /** The framebuffer bound at read-back was ours. */
        public boolean boundAtReadBack;
        /** The first {@code glGetError} after the frame (0: none). */
        public int glError;
        /** Every pixel of the read-back had the same colour. */
        public boolean uniform;
    }

    /** The route to use and why ({@code ok} or the failed check's code, for the log). */
    public static final class Decision {
        public final Route route;
        public final String code;

        Decision(Route route, String code) {
            this.route = route;
            this.code = code;
        }

        @Override
        public String toString() {
            return route + " (" + code + ")";
        }
    }

    private RenderRoute() {
    }

    /** {@code tried} if every check passed, else WINDOW with the first failed check's code. */
    public static Decision decide(Route tried, Check c) {
        if (tried == null || tried == Route.WINDOW) {
            return new Decision(Route.WINDOW, "window");
        }
        if (c == null) {
            return new Decision(Route.WINDOW, "no-check");
        }
        if (!c.complete) {
            return new Decision(Route.WINDOW, "incomplete");
        }
        if (!c.boundAtReadBack) {
            return new Decision(Route.WINDOW, "binding");
        }
        if (c.glError != 0) {
            return new Decision(Route.WINDOW, "gl-error-0x" + Integer.toHexString(c.glError));
        }
        if (c.uniform) {
            return new Decision(Route.WINDOW, "uniform");
        }
        return new Decision(tried, "ok");
    }

    /** True if every pixel of a BGRA read-back ({@code w x h}) has the same colour. The buffer is not consumed. */
    public static boolean uniform(ByteBuffer bgra, int w, int h) {
        int n = w * h;
        if (n <= 0) {
            return true;
        }
        int base = bgra.position();
        int first = bgra.getInt(base) & 0x00FFFFFF; // alpha is not shown
        for (int i = 1; i < n; i++) {
            if ((bgra.getInt(base + 4 * i) & 0x00FFFFFF) != first) {
                return false;
            }
        }
        return true;
    }
}
