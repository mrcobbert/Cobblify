package com.bedwarsqol.hud;

import java.util.List;

/**
 * Snaps a moving HUD box to the screen (edges and centre) and to other boxes (edges and centres),
 * one axis at a time. A box's left, centre and right (or top, centre, bottom) each try every target;
 * the closest within the threshold wins.
 */
public final class HudSnapper {

    private HudSnapper() {
    }

    /** Another box the moving one can snap to. */
    public static final class Target {
        public final String id;
        public final float x;
        public final float y;
        public final float width;
        public final float height;

        public Target(String id, float x, float y, float width, float height) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /** The outcome on one axis. */
    public static final class Snap {
        /** The box's new leading edge (left or top); unchanged when nothing snapped. */
        public final float position;
        public final boolean snapped;
        /** Where the guide line goes, on this axis. */
        public final float line;
        /** The box that was matched, or {@code null} for the screen. */
        public final Target target;

        Snap(float position, boolean snapped, float line, Target target) {
            this.position = position;
            this.snapped = snapped;
            this.line = line;
            this.target = target;
        }
    }

    /**
     * Snaps a box whose leading edge is at {@code position} and which is {@code size} long, on the
     * X axis when {@code xAxis}, against the screen ({@code screenSize} long) and {@code targets}.
     */
    public static Snap snap(float position, float size, boolean xAxis, float screenSize,
                            List<Target> targets, float threshold) {
        float[] own = {position, position + size / 2f, position + size};
        float bestDistance = threshold;
        float bestPosition = position;
        float bestLine = 0f;
        Target bestTarget = null;
        boolean found = false;

        float[] screen = {0f, screenSize / 2f, screenSize};
        for (float line : screen) {
            for (int i = 0; i < own.length; i++) {
                float distance = Math.abs(line - own[i]);
                if (distance <= bestDistance && (!found || distance < bestDistance)) {
                    bestDistance = distance;
                    bestPosition = line - i * size / 2f;
                    bestLine = line;
                    bestTarget = null;
                    found = true;
                }
            }
        }
        for (Target t : targets) {
            float start = xAxis ? t.x : t.y;
            float length = xAxis ? t.width : t.height;
            float[] lines = {start, start + length / 2f, start + length};
            for (float line : lines) {
                for (int i = 0; i < own.length; i++) {
                    float distance = Math.abs(line - own[i]);
                    if (distance <= bestDistance && (!found || distance < bestDistance)) {
                        bestDistance = distance;
                        bestPosition = line - i * size / 2f;
                        bestLine = line;
                        bestTarget = t;
                        found = true;
                    }
                }
            }
        }
        return new Snap(found ? bestPosition : position, found, bestLine, bestTarget);
    }
}
