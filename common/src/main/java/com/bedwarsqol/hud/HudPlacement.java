package com.bedwarsqol.hud;

/**
 * Where a HUD module sits on screen. A position is stored as an offset from one of nine screen
 * anchors (0-2 top row, 3-5 middle, 6-8 bottom; left, centre, right in each), so a module keeps its
 * distance from the nearest edge when the window or GUI scale changes. All values are scaled GUI px.
 */
public final class HudPlacement {

    private static final float[] ANCHOR_X = {0f, 0.5f, 1f, 0f, 0.5f, 1f, 0f, 0.5f, 1f};
    private static final float[] ANCHOR_Y = {0f, 0f, 0f, 0.5f, 0.5f, 0.5f, 1f, 1f, 1f};

    private HudPlacement() {
    }

    /** Left edge of a box {@code width} wide, stored as {@code storedX} from {@code anchor}. */
    public static float absoluteX(float storedX, int anchor, float width, float screenWidth) {
        float a = ANCHOR_X[safe(anchor)];
        return storedX + a * screenWidth - a * width;
    }

    /** Top edge of a box {@code height} tall, stored as {@code storedY} from {@code anchor}. */
    public static float absoluteY(float storedY, int anchor, float height, float screenHeight) {
        float a = ANCHOR_Y[safe(anchor)];
        return storedY + a * screenHeight - a * height;
    }

    /** The stored X for a box whose left edge is at {@code x}: the inverse of {@link #absoluteX}. */
    public static int storedX(float x, int anchor, float width, float screenWidth) {
        float a = ANCHOR_X[safe(anchor)];
        return Math.round(x - a * screenWidth + a * width);
    }

    /** The stored Y for a box whose top edge is at {@code y}: the inverse of {@link #absoluteY}. */
    public static int storedY(float y, int anchor, float height, float screenHeight) {
        float a = ANCHOR_Y[safe(anchor)];
        return Math.round(y - a * screenHeight + a * height);
    }

    /** The anchor for a box: the screen third its edges reach on each axis, corners first. */
    public static int anchorFor(float x, float y, float width, float height, float screenWidth, float screenHeight) {
        float right = x + width;
        float bottom = y + height;
        boolean left = x <= screenWidth / 3f;
        boolean top = y <= screenHeight / 3f;
        boolean rightSide = right >= screenWidth / 3f * 2f;
        boolean bottomSide = bottom >= screenHeight / 3f * 2f;
        if (left && top) return 0;
        if (rightSide && top) return 2;
        if (left && bottomSide) return 6;
        if (rightSide && bottomSide) return 8;
        if (top) return 1;
        if (left) return 3;
        if (rightSide) return 5;
        if (bottomSide) return 7;
        return 4;
    }

    /**
     * Pulls one axis of a box onto the screen, keeping {@code pad} (a panel's padding around the
     * content box) visible too. A box larger than the screen keeps its leading edge on screen.
     */
    public static float clamp(float position, float size, float pad, float screenSize) {
        return Math.max(pad, Math.min(position, screenSize - size - pad));
    }

    private static int safe(int anchor) {
        return Math.max(0, Math.min(8, anchor));
    }
}
