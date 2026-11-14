package com.bedwarsqol.hud;

/**
 * Box math for the vanilla overlays drawn as HUD items (scoreboard, tab list, chat). A vanilla rect is
 * where vanilla draws the overlay at scale 1, {@code {x, y, w, h}} in GUI px; the item's box is where it
 * is drawn instead. The draw maps one onto the other with
 * {@code translate(box.x, box.y); scale(s); translate(-rect.x, -rect.y)}.
 */
public final class OverlayBox {

    private OverlayBox() {
    }

    /** {@code rect} scaled by {@code s} about the point (ax, ay): where a scale about that point draws it. */
    public static float[] scaledAbout(float[] rect, float ax, float ay, float s) {
        return new float[]{ax + s * (rect[0] - ax), ay + s * (rect[1] - ay), rect[2] * s, rect[3] * s};
    }

    /**
     * The item's box: {@code autoBox} while it was never moved ({@link HudPlacement#AUTO}), otherwise a box
     * {@code rect} scaled by {@code s} at its stored spot from {@code anchor}, pulled fully on screen (no
     * padding; a box larger than the screen keeps its leading edge on screen).
     */
    public static float[] placed(float[] rect, float s, int x, int y, int anchor, float sw, float sh, float[] autoBox) {
        if (anchor == HudPlacement.AUTO) return autoBox;
        float w = rect[2] * s, h = rect[3] * s;
        float bx = HudPlacement.clamp(HudPlacement.absoluteX(x, anchor, w, sw), w, 0f, sw);
        float by = HudPlacement.clamp(HudPlacement.absoluteY(y, anchor, h, sh), h, 0f, sh);
        return new float[]{bx, by, w, h};
    }

    /** Where vanilla point (vx, vy) is drawn once {@code rect} is mapped onto {@code box} at scale {@code s}. */
    public static float[] toScreen(float[] rect, float[] box, float s, float vx, float vy) {
        return new float[]{box[0] + s * (vx - rect[0]), box[1] + s * (vy - rect[1])};
    }

    /** The inverse of {@link #toScreen}: the vanilla point drawn at screen point (sx, sy). */
    public static float[] toVanilla(float[] rect, float[] box, float s, float sx, float sy) {
        return new float[]{rect[0] + (sx - box[0]) / s, rect[1] + (sy - box[1]) / s};
    }
}
