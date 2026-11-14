package com.bedwarsqol.hud;

/**
 * One HUD module's editable layout: where it sits, how big it is, whether it shows and whether it draws its
 * background (Ornithe's per-item Background switch; the 5-arg form, used by Forge and Lunar, means On).
 */
public final class HudModuleState {

    public final int x;
    public final int y;
    public final int anchor;
    public final float scale;
    public final boolean enabled;
    public final boolean background;

    public HudModuleState(int x, int y, int anchor, float scale, boolean enabled) {
        this(x, y, anchor, scale, enabled, true);
    }

    public HudModuleState(int x, int y, int anchor, float scale, boolean enabled, boolean background) {
        this.x = x;
        this.y = y;
        this.anchor = anchor;
        this.scale = scale;
        this.enabled = enabled;
        this.background = background;
    }

    public HudModuleState withPosition(int x, int y, int anchor) {
        return new HudModuleState(x, y, anchor, scale, enabled, background);
    }

    public HudModuleState withScale(float scale) {
        return new HudModuleState(x, y, anchor, scale, enabled, background);
    }

    public HudModuleState withEnabled(boolean enabled) {
        return new HudModuleState(x, y, anchor, scale, enabled, background);
    }

    public HudModuleState withBackground(boolean background) {
        return new HudModuleState(x, y, anchor, scale, enabled, background);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HudModuleState)) return false;
        HudModuleState s = (HudModuleState) o;
        return x == s.x && y == s.y && anchor == s.anchor && Float.compare(scale, s.scale) == 0
                && enabled == s.enabled && background == s.background;
    }

    @Override
    public int hashCode() {
        int h = x;
        h = 31 * h + y;
        h = 31 * h + anchor;
        h = 31 * h + Float.floatToIntBits(scale);
        h = 31 * h + (enabled ? 1 : 0);
        return 31 * h + (background ? 1 : 0);
    }
}
