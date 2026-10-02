package com.bedwarsqol.hud;

/** One HUD module's editable layout: where it sits, how big it is and whether it shows. */
public final class HudModuleState {

    public final int x;
    public final int y;
    public final int anchor;
    public final float scale;
    public final boolean enabled;

    public HudModuleState(int x, int y, int anchor, float scale, boolean enabled) {
        this.x = x;
        this.y = y;
        this.anchor = anchor;
        this.scale = scale;
        this.enabled = enabled;
    }

    public HudModuleState withPosition(int x, int y, int anchor) {
        return new HudModuleState(x, y, anchor, scale, enabled);
    }

    public HudModuleState withScale(float scale) {
        return new HudModuleState(x, y, anchor, scale, enabled);
    }

    public HudModuleState withEnabled(boolean enabled) {
        return new HudModuleState(x, y, anchor, scale, enabled);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HudModuleState)) return false;
        HudModuleState s = (HudModuleState) o;
        return x == s.x && y == s.y && anchor == s.anchor && Float.compare(scale, s.scale) == 0
                && enabled == s.enabled;
    }

    @Override
    public int hashCode() {
        int h = x;
        h = 31 * h + y;
        h = 31 * h + anchor;
        h = 31 * h + Float.floatToIntBits(scale);
        return 31 * h + (enabled ? 1 : 0);
    }
}
