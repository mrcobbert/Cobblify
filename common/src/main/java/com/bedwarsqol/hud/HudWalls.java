package com.bedwarsqol.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Walls (G20): in Edit HUD every other visible item, the scoreboard, chat and the hotbar zone are walls. A dragged box
 * never pushes another; it stops at a wall, slides along it, and goes around it once the far side is nearer to where
 * the pointer wants it. Corner resize grows only as far as the first wall. A Java port of the approved kit's
 * {@code resolveWalls} and {@code wallScale}. All rectangles are visual boxes (panel padding included), GUI px.
 */
public final class HudWalls {

    /** Flush is not overlap: boxes may share an edge to within this many GUI px. */
    public static final float EPS = 1e-3f;
    /** The vanilla hotbar, and the zone above it with the health, armour and food rows the Auto layout keeps clear. */
    public static final float HOTBAR_W = 182f;
    public static final float HOTBAR_H = 22f;
    public static final float HOTBAR_ZONE_H = 50f;
    /** How many rounds of stepping past walls {@link #resolve} tries before it gives up. */
    private static final int DEPTH = 4;
    /** Binary-search steps of {@link #largestFreeScale}. */
    private static final int SCALE_STEPS = 24;

    private HudWalls() {
    }

    /** A wall or a box: x, y, width, height. */
    public static final class Rect {
        public final float x;
        public final float y;
        public final float w;
        public final float h;

        public Rect(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        @Override
        public String toString() {
            return "Rect(" + x + ", " + y + ", " + w + ", " + h + ")";
        }
    }

    /** Gives the rectangle a resized box would really cover at {@code scale}. */
    public interface ScaleProbe {
        Rect at(float scale);
    }

    /** The 182x50 hotbar zone at the bottom centre of an {@code sw} x {@code sh} screen. */
    public static Rect hotbarZone(float sw, float sh) {
        return new Rect((sw - HOTBAR_W) / 2f, sh - HOTBAR_ZONE_H, HOTBAR_W, HOTBAR_ZONE_H);
    }

    /**
     * Whether item {@code other} (shown or not) is a wall for a drag of {@code moving}: every other shown item, the
     * scoreboard and chat included (chat and HUD items are walls to each other, G29), but never the tab list. While
     * Tab is held ({@code tabHeld}, moving the tab list) the Cobblify items are hidden, so its walls are the
     * scoreboard and chat only (G28). Ids are the HUD item ids ({@code scoreboard}, {@code chat}, {@code tablist}).
     */
    public static boolean isWall(String moving, String other, boolean otherShown, boolean tabHeld) {
        if (!otherShown || other == null || other.equals(moving) || TAB_LIST.equals(other)) return false;
        return !tabHeld || SCOREBOARD.equals(other) || CHAT.equals(other);
    }

    /**
     * The hotbar wall for a drag of {@code moving}: the 182x50 zone with the health, armour and food rows, except for
     * chat, which may sit over those rows as vanilla's does on a narrow screen, so its wall is the 182x22 hotbar (D10).
     */
    public static Rect hotbarWall(String moving, float sw, float sh) {
        if (CHAT.equals(moving)) return new Rect((sw - HOTBAR_W) / 2f, sh - HOTBAR_H, HOTBAR_W, HOTBAR_H);
        return hotbarZone(sw, sh);
    }

    private static final String SCOREBOARD = "scoreboard";
    private static final String CHAT = "chat";
    private static final String TAB_LIST = "tablist";

    /** Whether the box (x, y, w, h) overlaps {@code o} by more than {@link #EPS}. */
    public static boolean overlaps(float x, float y, float w, float h, Rect o) {
        return x < o.x + o.w - EPS && x + w > o.x + EPS && y < o.y + o.h - EPS && y + h > o.y + EPS;
    }

    /** Whether the box (x, y, w, h) overlaps none of {@code walls}. */
    public static boolean free(float x, float y, float w, float h, List<Rect> walls) {
        return hit(x, y, w, h, walls) == null;
    }

    private static Rect hit(float x, float y, float w, float h, List<Rect> walls) {
        for (Rect o : walls) {
            if (overlaps(x, y, w, h, o)) return o;
        }
        return null;
    }

    /**
     * The nearest spot to (x, y) where a {@code w} x {@code h} box is on the {@code sw} x {@code sh} screen and clear of
     * {@code walls}: the target itself when free, else found by stepping past the wall it hits (left of it, right of
     * it, above, below), keeping the nearest free candidate and trying again from the blocked ones, {@value #DEPTH}
     * rounds deep. With no free spot found, the box stays at ({@code curX}, {@code curY}).
     */
    public static float[] resolve(float x, float y, float w, float h, float sw, float sh, List<Rect> walls,
                                  float curX, float curY) {
        float vx = HudPlacement.clamp(x, w, 0f, sw);
        float vy = HudPlacement.clamp(y, h, 0f, sh);
        if (free(vx, vy, w, h, walls)) return new float[]{vx, vy};
        List<float[]> frontier = new ArrayList<float[]>();
        frontier.add(new float[]{vx, vy});
        float[] best = null;
        float bestD = Float.POSITIVE_INFINITY;
        for (int depth = 0; depth < DEPTH && !frontier.isEmpty(); depth++) {
            List<float[]> next = new ArrayList<float[]>();
            for (float[] p : frontier) {
                Rect o = hit(p[0], p[1], w, h, walls);
                if (o == null) continue;
                float[][] candidates = {{o.x - w, p[1]}, {o.x + o.w, p[1]}, {p[0], o.y - h}, {p[0], o.y + o.h}};
                for (float[] c : candidates) {
                    float px = HudPlacement.clamp(c[0], w, 0f, sw);
                    float py = HudPlacement.clamp(c[1], h, 0f, sh);
                    float d = (px - vx) * (px - vx) + (py - vy) * (py - vy);
                    if (d >= bestD) continue;
                    if (free(px, py, w, h, walls)) {
                        best = new float[]{px, py};
                        bestD = d;
                    } else {
                        next.add(new float[]{px, py});
                    }
                }
            }
            frontier = next;
        }
        return best != null ? best : new float[]{curX, curY};
    }

    /**
     * The scale a resize from {@code from} towards {@code target} may take: {@code target} when shrinking or when it is
     * clear of {@code walls}, else the largest scale between the two whose {@code probe} rectangle is clear (a
     * {@value #SCALE_STEPS}-step binary search). A box already in a wall cannot grow, but can always shrink.
     */
    public static float largestFreeScale(float from, float target, ScaleProbe probe, List<Rect> walls) {
        if (target <= from || fits(probe.at(target), walls)) return target;
        float lo = from, hi = target;
        for (int k = 0; k < SCALE_STEPS; k++) {
            float mid = (lo + hi) / 2f;
            if (fits(probe.at(mid), walls)) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /**
     * As {@link #largestFreeScale}, for an item drawn only at whole steps {@code k / sf} ({@link ScaleSteps}):
     * {@code target} when shrinking or when it is clear, else the largest step between {@code from} and {@code target}
     * whose {@code probe} rectangle is clear, else {@code from}. It never returns a value between two steps, so the
     * scale shown is the size drawn and a later rescale cannot tip a value parked under a step edge into the wall
     * (HUD tweaks scaling report, cause b). {@code from} and {@code target} are steps.
     */
    public static float largestFreeStep(float from, float target, int sf, ScaleProbe probe, List<Rect> walls) {
        if (target <= from || fits(probe.at(target), walls)) return target;
        int lo = Math.round(from * sf);
        for (int k = Math.round(target * sf) - 1; k > lo; k--) {
            float s = k / (float) sf;
            if (fits(probe.at(s), walls)) return s;
        }
        return from;
    }

    private static boolean fits(Rect r, List<Rect> walls) {
        return r == null || free(r.x, r.y, r.w, r.h, walls);
    }
}
