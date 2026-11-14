package com.bedwarsqol.hud;

import java.util.List;

/**
 * One step of an Edit HUD drag or nudge: where the moved box goes for a raw position (its spot when the press began
 * plus the pointer's travel since, unrounded). It snaps each axis to the screen and the other boxes (unless Alt is
 * held), keeps the box on screen, then keeps it out of every wall (unless Alt is held). All positions are the box as
 * drawn (its visual box, panel padding included), in GUI px.
 */
public final class HudDrag {

    private HudDrag() {
    }

    /** The box's new top-left and the snap on each axis (null when snapping is off). */
    public static final class Result {
        public final float x;
        public final float y;
        public final HudSnapper.Snap snapX;
        public final HudSnapper.Snap snapY;

        Result(float x, float y, HudSnapper.Snap snapX, HudSnapper.Snap snapY) {
            this.x = x;
            this.y = y;
            this.snapX = snapX;
            this.snapY = snapY;
        }
    }

    /**
     * Moves a {@code w} x {@code h} box towards ({@code rawX}, {@code rawY}) on an {@code sw} x {@code sh} screen at GUI
     * scale {@code scale}. With {@code snap}, an edge or centre within {@link HudSnapper#threshold} of a screen line or
     * a {@code targets} box lands exactly on it.
     */
    public static Result move(float rawX, float rawY, float w, float h, float sw, float sh, int scale, boolean snap,
                              List<HudSnapper.Target> targets) {
        return move(rawX, rawY, w, h, sw, sh, scale, snap, targets, false, null, rawX, rawY);
    }

    /**
     * As {@link #move(float, float, float, float, float, float, int, boolean, List)}, then, with {@code walls}, to the
     * nearest spot clear of {@code wallRects} ({@link HudWalls#resolve}); with no free spot the box stays at
     * ({@code curX}, {@code curY}), where it is now. An axis the walls moved loses its snap guide.
     */
    public static Result move(float rawX, float rawY, float w, float h, float sw, float sh, int scale, boolean snap,
                              List<HudSnapper.Target> targets, boolean walls, List<HudWalls.Rect> wallRects,
                              float curX, float curY) {
        float x = rawX, y = rawY;
        HudSnapper.Snap snapX = null, snapY = null;
        if (snap) {
            float threshold = HudSnapper.threshold(scale);
            snapX = HudSnapper.snap(x, w, true, sw, targets, threshold);
            snapY = HudSnapper.snap(y, h, false, sh, targets, threshold);
            x = snapX.position;
            y = snapY.position;
        }
        x = HudPlacement.clamp(x, w, 0f, sw);
        y = HudPlacement.clamp(y, h, 0f, sh);
        if (walls && wallRects != null) {
            float[] r = HudWalls.resolve(x, y, w, h, sw, sh, wallRects, curX, curY);
            if (r[0] != x) snapX = null;
            if (r[1] != y) snapY = null;
            x = r[0];
            y = r[1];
        }
        return new Result(x, y, snapX, snapY);
    }
}
