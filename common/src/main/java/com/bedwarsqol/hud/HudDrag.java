package com.bedwarsqol.hud;

import java.util.List;

/**
 * One step of an Edit HUD drag: where the dragged box goes for a raw position (its spot when the press began plus the
 * pointer's travel since, unrounded). It snaps each axis to the screen and the other boxes (unless Alt is held), then
 * keeps the box on screen. All positions are the box as drawn (its visual box, panel padding included), in GUI px.
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
        float x = rawX, y = rawY;
        HudSnapper.Snap snapX = null, snapY = null;
        if (snap) {
            float threshold = HudSnapper.threshold(scale);
            snapX = HudSnapper.snap(x, w, true, sw, targets, threshold);
            snapY = HudSnapper.snap(y, h, false, sh, targets, threshold);
            x = snapX.position;
            y = snapY.position;
        }
        return new Result(HudPlacement.clamp(x, w, 0f, sw), HudPlacement.clamp(y, h, 0f, sh), snapX, snapY);
    }
}
