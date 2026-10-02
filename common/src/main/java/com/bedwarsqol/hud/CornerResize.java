package com.bedwarsqol.hud;

/**
 * Resizing a HUD box by one of its corners. The opposite corner stays where it is and the scale
 * follows the mouse projected onto the box's diagonal, so dragging along either axis works. Box
 * sizes are proportional to the module's scale, so the box at scale {@code s} is the base size
 * times {@code s}.
 */
public final class CornerResize {

    public static final int TOP_LEFT = 0;
    public static final int TOP_RIGHT = 1;
    public static final int BOTTOM_LEFT = 2;
    public static final int BOTTOM_RIGHT = 3;

    public static final float MIN_SCALE = 0.3f;
    public static final float MAX_SCALE = 10f;
    /** Scales this close to 1.0 land exactly on it. */
    public static final float SNAP_TO_ONE = 0.05f;

    private final float pinX;
    private final float pinY;
    private final float baseWidth;
    private final float baseHeight;
    private final int dirX;
    private final int dirY;

    /** Starts a resize of the box (x, y, width, height), currently at {@code scale}, by {@code corner}. */
    public CornerResize(int corner, float x, float y, float width, float height, float scale) {
        boolean right = corner == TOP_RIGHT || corner == BOTTOM_RIGHT;
        boolean bottom = corner == BOTTOM_LEFT || corner == BOTTOM_RIGHT;
        this.dirX = right ? 1 : -1;
        this.dirY = bottom ? 1 : -1;
        this.pinX = right ? x : x + width;
        this.pinY = bottom ? y : y + height;
        float s = Math.max(1e-3f, scale);
        this.baseWidth = Math.max(1e-3f, width / s);
        this.baseHeight = Math.max(1e-3f, height / s);
    }

    /**
     * The scale for the mouse at (mouseX, mouseY): never below {@link #MIN_SCALE}, never so big the
     * box (plus {@code pad} on each side) leaves the screen, and snapped to 1.0 when {@code snap}.
     */
    public float scaleFor(float mouseX, float mouseY, float screenWidth, float screenHeight, float pad, boolean snap) {
        float dx = (mouseX - pinX) * dirX;
        float dy = (mouseY - pinY) * dirY;
        float scale = (dx * baseWidth + dy * baseHeight) / (baseWidth * baseWidth + baseHeight * baseHeight);
        if (snap && Math.abs(scale - 1f) <= SNAP_TO_ONE) scale = 1f;
        float roomX = (dirX > 0 ? screenWidth - pad - pinX : pinX - pad) / baseWidth;
        float roomY = (dirY > 0 ? screenHeight - pad - pinY : pinY - pad) / baseHeight;
        float max = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.min(roomX, roomY)));
        return Math.max(MIN_SCALE, Math.min(max, scale));
    }

    /** Left edge of the box at {@code scale}. */
    public float left(float scale) {
        return dirX > 0 ? pinX : pinX - baseWidth * scale;
    }

    /** Top edge of the box at {@code scale}. */
    public float top(float scale) {
        return dirY > 0 ? pinY : pinY - baseHeight * scale;
    }

    /**
     * The corner of the box (x, y, width, height) within {@code reach} of the mouse, or -1. The
     * nearest corner wins when the box is small enough for several to be in reach.
     */
    public static int cornerAt(float mouseX, float mouseY, float x, float y, float width, float height, float reach) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int corner = TOP_LEFT; corner <= BOTTOM_RIGHT; corner++) {
            float cx = cornerX(corner, x, width);
            float cy = cornerY(corner, y, height);
            float dx = Math.abs(mouseX - cx);
            float dy = Math.abs(mouseY - cy);
            if (dx > reach || dy > reach) continue;
            float distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = corner;
            }
        }
        return best;
    }

    public static float cornerX(int corner, float x, float width) {
        return corner == TOP_RIGHT || corner == BOTTOM_RIGHT ? x + width : x;
    }

    public static float cornerY(int corner, float y, float height) {
        return corner == BOTTOM_LEFT || corner == BOTTOM_RIGHT ? y + height : y;
    }
}
