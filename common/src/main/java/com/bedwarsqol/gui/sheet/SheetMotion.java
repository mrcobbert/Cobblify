package com.bedwarsqol.gui.sheet;

import java.util.HashMap;
import java.util.Map;

/**
 * The sheet's transitions, timed like the prototype's CSS: the toggle knob (90 ms, cubic-bezier(.2,0,0,1)), the
 * toggle fill and O fades (90 ms, ease), the sheet sliding in (140 ms, cubic-bezier(0,0,0,1)) and out (100 ms,
 * cubic-bezier(.4,0,1,1)), and the toast (120 ms, ease). Times are in milliseconds.
 */
public final class SheetMotion {

    public static final Curve KNOB = new Curve(0.2, 0, 0, 1);
    public static final Curve EASE = new Curve(0.25, 0.1, 0.25, 1);
    public static final Curve SLIDE_IN = new Curve(0, 0, 0, 1);
    public static final Curve SLIDE_OUT = new Curve(0.4, 0, 1, 1);

    public static final long TOGGLE_MS = 90, OPEN_MS = 140, CLOSE_MS = 100, TOAST_FADE_MS = 120, TOAST_SHOW_MS = 2200;

    /** CSS cubic-bezier timing, solved as WebKit's UnitBezier does. */
    public static final class Curve {
        private final double cx, bx, ax, cy, by, ay;

        public Curve(double x1, double y1, double x2, double y2) {
            cx = 3 * x1;
            bx = 3 * (x2 - x1) - cx;
            ax = 1 - cx - bx;
            cy = 3 * y1;
            by = 3 * (y2 - y1) - cy;
            ay = 1 - cy - by;
        }

        private double x(double t) {
            return ((ax * t + bx) * t + cx) * t;
        }

        private double y(double t) {
            return ((ay * t + by) * t + cy) * t;
        }

        private double dx(double t) {
            return (3 * ax * t + 2 * bx) * t + cx;
        }

        /** Eased progress for linear progress {@code p} in [0, 1]. */
        public float at(double p) {
            if (p <= 0) return 0f;
            if (p >= 1) return 1f;
            double t = p;
            for (int i = 0; i < 8; i++) {
                double e = x(t) - p;
                if (Math.abs(e) < 1e-7) return (float) y(t);
                double d = dx(t);
                if (Math.abs(d) < 1e-6) break;
                t -= e / d;
            }
            double lo = 0, hi = 1;
            t = p;
            while (lo < hi) {
                double v = x(t);
                if (Math.abs(v - p) < 1e-7) break;
                if (p > v) lo = t;
                else hi = t;
                t = (lo + hi) / 2;
                if (hi - lo < 1e-9) break;
            }
            return (float) y(t);
        }
    }

    /** A value moving from {@code from} to {@code to} over {@code ms} from {@code start}. */
    private static final class Move {
        float from, to;
        long start;
        boolean target;
    }

    private final Map<String, Move> knobs = new HashMap<String, Move>();
    private final Map<String, Move> fades = new HashMap<String, Move>();

    /** The knob's position (0 off, 1 on) for a toggle whose value is {@code on}. */
    public float toggle(String id, boolean on, long now) {
        return value(knobs, id, on, now, KNOB);
    }

    /** The fill's opacity (the O's is one minus it). */
    public float toggleFade(String id, boolean on, long now) {
        return value(fades, id, on, now, EASE);
    }

    private static float value(Map<String, Move> map, String id, boolean on, long now, Curve curve) {
        Move m = map.get(id);
        float target = on ? 1f : 0f;
        if (m == null) {
            m = new Move();
            m.from = m.to = target;
            m.target = on;
            map.put(id, m);
            return target;
        }
        if (m.target != on) {
            m.from = current(m, now, curve);
            m.to = target;
            m.start = now;
            m.target = on;
        }
        return current(m, now, curve);
    }

    private static float current(Move m, long now, Curve curve) {
        float p = (now - m.start) / (float) TOGGLE_MS;
        if (p >= 1f) return m.to;
        return m.from + (m.to - m.from) * curve.at(p);
    }

    /** Whether any toggle is still moving (so the screen keeps drawing frames). */
    public boolean moving(long now) {
        for (Move m : knobs.values()) if (now - m.start < TOGGLE_MS) return true;
        return false;
    }
}
