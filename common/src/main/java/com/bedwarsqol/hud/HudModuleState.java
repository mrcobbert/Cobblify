package com.bedwarsqol.hud;

/**
 * One HUD module's editable layout: where it sits, how big it is, whether it shows and whether it draws its
 * background (Ornithe's per-item Background switch; the 5-arg form, used by Forge and Lunar, means On).
 *
 * <p>Ornithe stores positions to a fraction of a GUI pixel: {@link #x}/{@link #y} hold the rounded stored
 * position (exactly what the int-only path would store) and {@link #fracX}/{@link #fracY} the rest, in
 * [-0.5, 0.5). Forge and Lunar never set a fraction, so it stays 0 there.
 */
public final class HudModuleState {

    public final int x;
    public final int y;
    public final int anchor;
    public final float scale;
    public final boolean enabled;
    public final boolean background;
    /** Sub-pixel part of the stored position, in [-0.5, 0.5) GUI px: the exact position is {@code x + fracX}. */
    public final float fracX;
    public final float fracY;

    public HudModuleState(int x, int y, int anchor, float scale, boolean enabled) {
        this(x, y, anchor, scale, enabled, true);
    }

    public HudModuleState(int x, int y, int anchor, float scale, boolean enabled, boolean background) {
        this(x, y, anchor, scale, enabled, background, 0f, 0f);
    }

    public HudModuleState(int x, int y, int anchor, float scale, boolean enabled, boolean background,
                          float fracX, float fracY) {
        this.x = x;
        this.y = y;
        this.anchor = anchor;
        this.scale = scale;
        this.enabled = enabled;
        this.background = background;
        this.fracX = fracX == 0f ? 0f : fracX; // no -0: equal positions are equal states
        this.fracY = fracY == 0f ? 0f : fracY;
    }

    /** The exact stored X: the rounded int plus its fraction. */
    public float exactX() {
        return x + fracX;
    }

    public float exactY() {
        return y + fracY;
    }

    /** A whole-pixel position; it clears the fraction (a reset to AUTO has none either). */
    public HudModuleState withPosition(int x, int y, int anchor) {
        return new HudModuleState(x, y, anchor, scale, enabled, background);
    }

    /**
     * An exact stored position: the whole part is {@code Math.round}, as {@link HudPlacement#storedX} rounds, and the
     * rest goes in the fraction.
     */
    public HudModuleState withExactPosition(float x, float y, int anchor) {
        int wx = Math.round(x);
        int wy = Math.round(y);
        return new HudModuleState(wx, wy, anchor, scale, enabled, background, x - wx, y - wy);
    }

    public HudModuleState withFraction(float fracX, float fracY) {
        return new HudModuleState(x, y, anchor, scale, enabled, background, fracX, fracY);
    }

    public HudModuleState withScale(float scale) {
        return new HudModuleState(x, y, anchor, scale, enabled, background, fracX, fracY);
    }

    public HudModuleState withEnabled(boolean enabled) {
        return new HudModuleState(x, y, anchor, scale, enabled, background, fracX, fracY);
    }

    public HudModuleState withBackground(boolean background) {
        return new HudModuleState(x, y, anchor, scale, enabled, background, fracX, fracY);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HudModuleState)) return false;
        HudModuleState s = (HudModuleState) o;
        return x == s.x && y == s.y && anchor == s.anchor && Float.compare(scale, s.scale) == 0
                && enabled == s.enabled && background == s.background
                && Float.compare(fracX, s.fracX) == 0 && Float.compare(fracY, s.fracY) == 0;
    }

    @Override
    public int hashCode() {
        int h = x;
        h = 31 * h + y;
        h = 31 * h + anchor;
        h = 31 * h + Float.floatToIntBits(scale);
        h = 31 * h + (enabled ? 1 : 0);
        h = 31 * h + (background ? 1 : 0);
        h = 31 * h + Float.floatToIntBits(fracX);
        return 31 * h + Float.floatToIntBits(fracY);
    }
}
