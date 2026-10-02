package com.bedwarsqol.hud;

import com.bedwarsqol.config.ClientSettings;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every HUD module the editor can move, by id: its name and its layout fields in
 * {@link ClientSettings}. The two gen timers share one toggle, so showing or hiding one does both.
 */
public final class HudModules {

    private static final List<String> IDS = Collections.unmodifiableList(Arrays.asList(
            BedwarsHudRenderer.INVENTORY_HUD,
            BedwarsHudRenderer.DIAMOND_TIMER_HUD,
            BedwarsHudRenderer.EMERALD_TIMER_HUD,
            BedwarsHudRenderer.SESSION_HUD,
            BedwarsHudRenderer.HEIGHT_LIMIT_HUD));

    private HudModules() {
    }

    /** Every module id, in draw order. */
    public static List<String> ids() {
        return IDS;
    }

    public static String title(String id) {
        if (BedwarsHudRenderer.INVENTORY_HUD.equals(id)) return "Inventory";
        if (BedwarsHudRenderer.DIAMOND_TIMER_HUD.equals(id)) return "Diamond Timer";
        if (BedwarsHudRenderer.EMERALD_TIMER_HUD.equals(id)) return "Emerald Timer";
        if (BedwarsHudRenderer.SESSION_HUD.equals(id)) return "Session Stats";
        if (BedwarsHudRenderer.HEIGHT_LIMIT_HUD.equals(id)) return "Map Info";
        return id;
    }

    public static HudModuleState get(ClientSettings c, String id) {
        if (BedwarsHudRenderer.INVENTORY_HUD.equals(id)) {
            return new HudModuleState(c.inventoryHudX, c.inventoryHudY, c.inventoryHudAnchor, c.inventoryHudScale, c.inventoryHudEnabled);
        }
        if (BedwarsHudRenderer.DIAMOND_TIMER_HUD.equals(id)) {
            return new HudModuleState(c.diamondTimerHudX, c.diamondTimerHudY, c.diamondTimerHudAnchor, c.diamondTimerHudScale, c.genTimersEnabled);
        }
        if (BedwarsHudRenderer.EMERALD_TIMER_HUD.equals(id)) {
            return new HudModuleState(c.emeraldTimerHudX, c.emeraldTimerHudY, c.emeraldTimerHudAnchor, c.emeraldTimerHudScale, c.genTimersEnabled);
        }
        if (BedwarsHudRenderer.SESSION_HUD.equals(id)) {
            return new HudModuleState(c.sessionStatsHudX, c.sessionStatsHudY, c.sessionStatsHudAnchor, c.sessionStatsHudScale, c.sessionStatsEnabled);
        }
        if (BedwarsHudRenderer.HEIGHT_LIMIT_HUD.equals(id)) {
            return new HudModuleState(c.heightLimitHudX, c.heightLimitHudY, c.heightLimitHudAnchor, c.heightLimitHudScale, c.heightLimitEnabled);
        }
        return null;
    }

    public static void set(ClientSettings c, String id, HudModuleState s) {
        if (s == null) return;
        if (BedwarsHudRenderer.INVENTORY_HUD.equals(id)) {
            c.inventoryHudX = s.x; c.inventoryHudY = s.y; c.inventoryHudAnchor = s.anchor; c.inventoryHudScale = s.scale;
            c.inventoryHudEnabled = s.enabled;
        } else if (BedwarsHudRenderer.DIAMOND_TIMER_HUD.equals(id)) {
            c.diamondTimerHudX = s.x; c.diamondTimerHudY = s.y; c.diamondTimerHudAnchor = s.anchor; c.diamondTimerHudScale = s.scale;
            c.genTimersEnabled = s.enabled;
        } else if (BedwarsHudRenderer.EMERALD_TIMER_HUD.equals(id)) {
            c.emeraldTimerHudX = s.x; c.emeraldTimerHudY = s.y; c.emeraldTimerHudAnchor = s.anchor; c.emeraldTimerHudScale = s.scale;
            c.genTimersEnabled = s.enabled;
        } else if (BedwarsHudRenderer.SESSION_HUD.equals(id)) {
            c.sessionStatsHudX = s.x; c.sessionStatsHudY = s.y; c.sessionStatsHudAnchor = s.anchor; c.sessionStatsHudScale = s.scale;
            c.sessionStatsEnabled = s.enabled;
        } else if (BedwarsHudRenderer.HEIGHT_LIMIT_HUD.equals(id)) {
            c.heightLimitHudX = s.x; c.heightLimitHudY = s.y; c.heightLimitHudAnchor = s.anchor; c.heightLimitHudScale = s.scale;
            c.heightLimitEnabled = s.enabled;
        }
    }

    /** The module as a fresh install has it, at the current HUD Size; it keeps its on/off state. */
    public static HudModuleState defaults(ClientSettings c, String id) {
        HudModuleState d = get(new ClientSettings(), id);
        HudModuleState now = get(c, id);
        if (d == null || now == null) return now;
        return new HudModuleState(d.x, d.y, d.anchor, c.defaultTextSizeScale(), now.enabled);
    }

    /** Every module's state, for undo. */
    public static Map<String, HudModuleState> snapshot(ClientSettings c) {
        Map<String, HudModuleState> out = new LinkedHashMap<String, HudModuleState>();
        for (String id : IDS) out.put(id, get(c, id));
        return out;
    }

    public static void restore(ClientSettings c, Map<String, HudModuleState> snapshot) {
        for (Map.Entry<String, HudModuleState> e : snapshot.entrySet()) set(c, e.getKey(), e.getValue());
    }
}
