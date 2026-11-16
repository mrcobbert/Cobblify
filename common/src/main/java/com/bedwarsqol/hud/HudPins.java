package com.bedwarsqol.hud;

import java.util.Collection;

/**
 * Pin-on-change: an item that was never moved sits in the Auto layout, which re-flows around every moved item. So
 * before any change to one item (drag, resize, nudge, menu action, hide or show), every other shown Auto item is
 * stored where it is drawn now, and nothing but the changed item moves.
 */
public final class HudPins {

    private HudPins() {
    }

    /** Whether item {@code id} gets pinned: shown, still on Auto, and not one of the items being changed. */
    public static boolean shouldPin(String id, boolean shown, int anchor, Collection<String> changingIds) {
        return shown && anchor == HudPlacement.AUTO && !changingIds.contains(id);
    }

    /**
     * {@code state} stored with its content box's top-left at (x, y) ({@code w} x {@code h}) on an {@code sw} x
     * {@code sh} screen: anchored to the screen third the box is in, to a fraction of a GUI pixel. Scale, on/off and
     * Background are kept.
     */
    public static HudModuleState pinned(HudModuleState state, float x, float y, float w, float h, float sw, float sh) {
        int anchor = HudPlacement.anchorFor(x, y, w, h, sw, sh);
        return state.withExactPosition(HudPlacement.exactStoredX(x, anchor, w, sw),
                HudPlacement.exactStoredY(y, anchor, h, sh), anchor);
    }

    /**
     * As {@link #pinned}, but anchored to the top third's column: the anchor the Auto layout gives an item that grows
     * down from its top edge (Team Upgrades). Pinned the same way, its live panel, smaller than the editor's box, stays
     * at the box's top edge rather than moving to the box's middle or bottom.
     */
    public static HudModuleState pinnedHoldingTop(HudModuleState state, float x, float y, float w, float h, float sw, float sh) {
        int anchor = HudPlacement.anchorFor(x, y, w, h, sw, sh) % 3;
        return state.withExactPosition(HudPlacement.exactStoredX(x, anchor, w, sw),
                HudPlacement.exactStoredY(y, anchor, h, sh), anchor);
    }

    /**
     * A newly shown item: switched on, everything else kept. One the player moved comes back on the spot it had when it
     * was hidden (the caller moves it to the nearest free spot if something covers that now); one still on Auto takes
     * the first free Auto spot.
     */
    public static HudModuleState shown(HudModuleState state) {
        return state.withEnabled(true);
    }
}
