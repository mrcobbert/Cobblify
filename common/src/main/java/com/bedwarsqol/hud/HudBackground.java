package com.bedwarsqol.hud;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-item Background switch of every HUD item, kept as a map of HUD item id to {@code false} for the items
 * switched Off: an absent id is On. A config saved before the setting existed has no map entries, so every item
 * starts On with no migration step.
 */
public final class HudBackground {

    /** Lunar's square see-through panel: black at alpha 0x6F (about 44%). */
    public static final int FILL = 0x6F000000;

    /**
     * HUD items that have a Background switch without being Edit HUD modules: the vanilla scoreboard, chat and tab
     * list (the ids cycle B uses for its rows and its rendering).
     */
    public static final List<String> OVERLAY_IDS = Collections.unmodifiableList(Arrays.asList(
            "scoreboard", "chat", "tablist"));

    private HudBackground() {
    }

    /**
     * Padding of a panel around its content at module scale {@code scale}: 3 GUI px at HUD Size Medium (the margin a
     * module without a panel keeps, so switching the Background does not move it), never under 2.
     */
    public static int pad(float scale) {
        return Math.max(2, Math.round(3f * scale));
    }

    /** Whether item {@code id} draws its background: true unless {@code offs} holds {@code false} for it. */
    public static boolean on(Map<String, Boolean> offs, String id) {
        return offs == null || id == null || !Boolean.FALSE.equals(offs.get(id));
    }

    /** Switches item {@code id}: On removes its entry, Off stores {@code false}. A null map or id is ignored. */
    public static void set(Map<String, Boolean> offs, String id, boolean on) {
        if (offs == null || id == null) return;
        if (on) offs.remove(id);
        else offs.put(id, Boolean.FALSE);
    }

    /**
     * A fresh copy holding only the Off entries of {@code offs} (null-safe). Ids this build does not know are kept,
     * so a newer build's choice survives a round trip through an older one.
     */
    public static Map<String, Boolean> sanitized(Map<String, Boolean> offs) {
        Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();
        if (offs == null) return out;
        for (Map.Entry<String, Boolean> e : offs.entrySet()) {
            if (e.getKey() != null && Boolean.FALSE.equals(e.getValue())) out.put(e.getKey(), Boolean.FALSE);
        }
        return out;
    }
}
