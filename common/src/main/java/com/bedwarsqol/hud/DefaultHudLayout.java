package com.bedwarsqol.hud;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The default HUD layout (G30, G33) that Reset Positions applies, for any screen. FPS, CPS, Ping, Armor and Potion
 * stack down the top-left corner; Map Info sits at the left edge, vertically centred; the Diamond timer takes the
 * top-right corner with the Emerald timer to its left and Session Stats right-aligned under them; Inventory is
 * centred at the top; Keystrokes takes the bottom-right corner. Each spot is stored with the anchor of its corner or
 * edge, so it follows other window sizes and GUI scales. Spots are exact even where they overlap on a small screen
 * (D8). A hidden item keeps no slot (shown items close up) and goes on Auto, as does every other item (the
 * scoreboard, chat and tab list go back to their vanilla spot): it takes the first free Auto spot when shown (D4).
 */
public final class DefaultHudLayout {

    /** Space between a spot and the screen edge, and between stacked items, GUI px. */
    public static final float EDGE = 2f;
    public static final float GAP = 2f;
    /** The top-left column, top to bottom (cycle A's FPS, CPS and Ping, then Armor and Potion). */
    private static final List<String> LEFT_COLUMN = Arrays.asList("fps", "cps", "ping", "armor", "potion");
    private static final Spot AUTO = new Spot(HudPlacement.AUTO, 0f, 0f);

    private DefaultHudLayout() {
    }

    /** One item to place: its content box size, the padding around it, and whether it is shown. */
    public static final class Item {
        public final String id;
        public final float contentW;
        public final float contentH;
        public final float pad;
        public final boolean shown;

        public Item(String id, float contentW, float contentH, float pad, boolean shown) {
            this.id = id;
            this.contentW = contentW;
            this.contentH = contentH;
            this.pad = pad;
            this.shown = shown;
        }

        float visualW() {
            return contentW + 2f * pad;
        }

        float visualH() {
            return contentH + 2f * pad;
        }
    }

    /** Where an item goes: a stored position (exact) from {@code anchor}, or {@link HudPlacement#AUTO}. */
    public static final class Spot {
        public final int anchor;
        public final float x;
        public final float y;

        Spot(int anchor, float x, float y) {
            this.anchor = anchor;
            this.x = x;
            this.y = y;
        }

        /** {@code state} moved to this spot; its size, on/off and Background are kept. */
        public HudModuleState apply(HudModuleState state) {
            return anchor == HudPlacement.AUTO ? state.withPosition(0, 0, HudPlacement.AUTO)
                    : state.withExactPosition(x, y, anchor);
        }
    }

    /** Every item's spot on an {@code sw} x {@code sh} screen (GUI px), by id. */
    public static Map<String, Spot> place(float sw, float sh, List<Item> items) {
        Map<String, Spot> out = new LinkedHashMap<String, Spot>();
        Map<String, Item> shown = new HashMap<String, Item>();
        for (Item it : items) {
            out.put(it.id, AUTO);
            if (it.shown) shown.put(it.id, it);
        }
        float y = EDGE;
        for (String id : LEFT_COLUMN) {
            Item it = shown.get(id);
            if (it == null) continue;
            put(out, it, 0, EDGE, y, sw, sh);
            y += it.visualH() + GAP;
        }
        Item map = shown.get("heightlimit");
        if (map != null) put(out, map, 3, EDGE, (sh - map.visualH()) / 2f, sw, sh);

        Item diamond = shown.get("diamondtimer"), emerald = shown.get("emeraldtimer");
        float timersRight = sw - EDGE, timersH = 0f;
        if (diamond != null) {
            float x = sw - EDGE - diamond.visualW();
            put(out, diamond, 2, x, EDGE, sw, sh);
            timersRight = x - GAP;
            timersH = diamond.visualH();
        }
        if (emerald != null) {
            put(out, emerald, 2, timersRight - emerald.visualW(), EDGE, sw, sh);
            timersH = Math.max(timersH, emerald.visualH());
        }
        Item session = shown.get("sessionstats");
        if (session != null) {
            float top = timersH > 0f ? EDGE + timersH + GAP : EDGE;
            put(out, session, 2, sw - EDGE - session.visualW(), top, sw, sh);
        }
        Item inventory = shown.get("inventory");
        if (inventory != null) put(out, inventory, 1, (sw - inventory.visualW()) / 2f, EDGE, sw, sh);
        Item keys = shown.get("keystrokes");
        if (keys != null) put(out, keys, 8, sw - EDGE - keys.visualW(), sh - EDGE - keys.visualH(), sw, sh);
        return out;
    }

    /** Stores {@code it} with its visual box's top-left at (vx, vy), from {@code anchor}, to a fraction of a GUI px. */
    private static void put(Map<String, Spot> out, Item it, int anchor, float vx, float vy, float sw, float sh) {
        float cx = vx + it.pad, cy = vy + it.pad;
        out.put(it.id, new Spot(anchor, HudPlacement.exactStoredX(cx, anchor, it.contentW, sw),
                HudPlacement.exactStoredY(cy, anchor, it.contentH, sh)));
    }
}
