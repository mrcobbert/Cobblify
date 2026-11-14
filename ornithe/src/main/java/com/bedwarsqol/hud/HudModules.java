package com.bedwarsqol.hud;

import com.bedwarsqol.config.ClientSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every HUD module the editor can move, by id: its name and its layout fields in
 * {@link ClientSettings}. The two gen timers share one toggle and one Background, so switching one does both.
 */
public final class HudModules {

    private static final List<String> IDS = Collections.unmodifiableList(Arrays.asList(
            BedwarsHudRenderer.POTION_HUD,
            BedwarsHudRenderer.ARMOR_HUD,
            BedwarsHudRenderer.INVENTORY_HUD,
            BedwarsHudRenderer.DIAMOND_TIMER_HUD,
            BedwarsHudRenderer.EMERALD_TIMER_HUD,
            BedwarsHudRenderer.KEYSTROKES_HUD,
            BedwarsHudRenderer.SESSION_HUD,
            BedwarsHudRenderer.HEIGHT_LIMIT_HUD));

    /** Vanilla's scoreboard, chat and tab list as HUD items: placed and resized, but drawn by vanilla. */
    private static final List<String> OVERLAY_IDS = Collections.unmodifiableList(Arrays.asList(
            BedwarsHudRenderer.SCOREBOARD_HUD,
            BedwarsHudRenderer.CHAT_HUD,
            BedwarsHudRenderer.TAB_LIST_HUD));

    private static final List<String> ALL_IDS;
    static {
        List<String> all = new java.util.ArrayList<String>(IDS);
        all.addAll(OVERLAY_IDS);
        ALL_IDS = Collections.unmodifiableList(all);
    }

    private static final List<String> BACKGROUND_IDS;

    static {
        List<String> all = new ArrayList<String>(IDS);
        all.addAll(HudBackground.OVERLAY_IDS);
        BACKGROUND_IDS = Collections.unmodifiableList(all);
    }

    private HudModules() {
    }

    /** Every module id, in draw order. */
    public static List<String> ids() {
        return IDS;
    }

    /** The scoreboard, chat and tab list items. Not in {@link #ids()}: vanilla draws them. */
    public static List<String> overlayIds() {
        return OVERLAY_IDS;
    }

    /** Every item the editor can place: the modules, then the overlays. */
    public static List<String> allIds() {
        return ALL_IDS;
    }

    /** Whether {@code id} is the scoreboard, chat or tab list item. */
    public static boolean isOverlay(String id) {
        return OVERLAY_IDS.contains(id);
    }

    public static String title(String id) {
        if (BedwarsHudRenderer.POTION_HUD.equals(id)) return "Potion";
        if (BedwarsHudRenderer.ARMOR_HUD.equals(id)) return "Armor";
        if (BedwarsHudRenderer.INVENTORY_HUD.equals(id)) return "Inventory";
        if (BedwarsHudRenderer.DIAMOND_TIMER_HUD.equals(id)) return "Diamond Timer";
        if (BedwarsHudRenderer.EMERALD_TIMER_HUD.equals(id)) return "Emerald Timer";
        if (BedwarsHudRenderer.KEYSTROKES_HUD.equals(id)) return "Keystrokes";
        if (BedwarsHudRenderer.SESSION_HUD.equals(id)) return "Session Stats";
        if (BedwarsHudRenderer.HEIGHT_LIMIT_HUD.equals(id)) return "Map Info";
        if (BedwarsHudRenderer.SCOREBOARD_HUD.equals(id)) return "Scoreboard";
        if (BedwarsHudRenderer.CHAT_HUD.equals(id)) return "Chat";
        if (BedwarsHudRenderer.TAB_LIST_HUD.equals(id)) return "Tab List";
        return id;
    }

    public static HudModuleState get(ClientSettings c, String id) {
        if (BedwarsHudRenderer.POTION_HUD.equals(id)) {
            return new HudModuleState(c.potionHudX, c.potionHudY, c.potionHudAnchor, c.potionHudScale, c.potionStatusEnabled, c.hudBackground(id));
        }
        if (BedwarsHudRenderer.ARMOR_HUD.equals(id)) {
            return new HudModuleState(c.armorHudX, c.armorHudY, c.armorHudAnchor, c.armorHudScale, c.armorTypeEnabled, c.hudBackground(id));
        }
        if (BedwarsHudRenderer.INVENTORY_HUD.equals(id)) {
            return new HudModuleState(c.inventoryHudX, c.inventoryHudY, c.inventoryHudAnchor, c.inventoryHudScale, c.inventoryHudEnabled, c.hudBackground(id));
        }
        if (BedwarsHudRenderer.DIAMOND_TIMER_HUD.equals(id)) {
            return new HudModuleState(c.diamondTimerHudX, c.diamondTimerHudY, c.diamondTimerHudAnchor, c.diamondTimerHudScale, c.genTimersEnabled, c.hudBackground(id));
        }
        if (BedwarsHudRenderer.EMERALD_TIMER_HUD.equals(id)) {
            return new HudModuleState(c.emeraldTimerHudX, c.emeraldTimerHudY, c.emeraldTimerHudAnchor, c.emeraldTimerHudScale, c.genTimersEnabled, c.hudBackground(id));
        }
        if (BedwarsHudRenderer.KEYSTROKES_HUD.equals(id)) {
            return new HudModuleState(c.keystrokesHudX, c.keystrokesHudY, c.keystrokesHudAnchor, c.keystrokesHudScale, c.keystrokesEnabled, c.hudBackground(id));
        }
        if (BedwarsHudRenderer.SESSION_HUD.equals(id)) {
            return new HudModuleState(c.sessionStatsHudX, c.sessionStatsHudY, c.sessionStatsHudAnchor, c.sessionStatsHudScale, c.sessionStatsEnabled, c.hudBackground(id));
        }
        if (BedwarsHudRenderer.HEIGHT_LIMIT_HUD.equals(id)) {
            return new HudModuleState(c.heightLimitHudX, c.heightLimitHudY, c.heightLimitHudAnchor, c.heightLimitHudScale, c.heightLimitEnabled, c.hudBackground(id));
        }
        if (BedwarsHudRenderer.SCOREBOARD_HUD.equals(id)) {
            return new HudModuleState(c.scoreboardHudX, c.scoreboardHudY, c.scoreboardHudAnchor, c.scoreboardHudScale, c.scoreboardHudEnabled);
        }
        // chat and the tab list have no Hide: always enabled
        if (BedwarsHudRenderer.CHAT_HUD.equals(id)) {
            return new HudModuleState(c.chatHudX, c.chatHudY, c.chatHudAnchor, c.chatHudScale, true);
        }
        if (BedwarsHudRenderer.TAB_LIST_HUD.equals(id)) {
            return new HudModuleState(c.tabListHudX, c.tabListHudY, c.tabListHudAnchor, c.tabListHudScale, true);
        }
        return null;
    }

    public static void set(ClientSettings c, String id, HudModuleState s) {
        if (s == null) return;
        if (BedwarsHudRenderer.POTION_HUD.equals(id)) {
            c.potionHudX = s.x; c.potionHudY = s.y; c.potionHudAnchor = s.anchor; c.potionHudScale = s.scale;
            c.potionStatusEnabled = s.enabled;
            c.setHudBackground(id, s.background);
        } else if (BedwarsHudRenderer.ARMOR_HUD.equals(id)) {
            c.armorHudX = s.x; c.armorHudY = s.y; c.armorHudAnchor = s.anchor; c.armorHudScale = s.scale;
            c.armorTypeEnabled = s.enabled;
            c.setHudBackground(id, s.background);
        } else if (BedwarsHudRenderer.INVENTORY_HUD.equals(id)) {
            c.inventoryHudX = s.x; c.inventoryHudY = s.y; c.inventoryHudAnchor = s.anchor; c.inventoryHudScale = s.scale;
            c.inventoryHudEnabled = s.enabled;
            c.setHudBackground(id, s.background);
        } else if (BedwarsHudRenderer.DIAMOND_TIMER_HUD.equals(id)) {
            c.diamondTimerHudX = s.x; c.diamondTimerHudY = s.y; c.diamondTimerHudAnchor = s.anchor; c.diamondTimerHudScale = s.scale;
            c.genTimersEnabled = s.enabled;
            setTimersBackground(c, s.background);
        } else if (BedwarsHudRenderer.EMERALD_TIMER_HUD.equals(id)) {
            c.emeraldTimerHudX = s.x; c.emeraldTimerHudY = s.y; c.emeraldTimerHudAnchor = s.anchor; c.emeraldTimerHudScale = s.scale;
            c.genTimersEnabled = s.enabled;
            setTimersBackground(c, s.background);
        } else if (BedwarsHudRenderer.KEYSTROKES_HUD.equals(id)) {
            c.keystrokesHudX = s.x; c.keystrokesHudY = s.y; c.keystrokesHudAnchor = s.anchor; c.keystrokesHudScale = s.scale;
            c.keystrokesEnabled = s.enabled;
            c.setHudBackground(id, s.background);
        } else if (BedwarsHudRenderer.SESSION_HUD.equals(id)) {
            c.sessionStatsHudX = s.x; c.sessionStatsHudY = s.y; c.sessionStatsHudAnchor = s.anchor; c.sessionStatsHudScale = s.scale;
            c.sessionStatsEnabled = s.enabled;
            c.setHudBackground(id, s.background);
        } else if (BedwarsHudRenderer.HEIGHT_LIMIT_HUD.equals(id)) {
            c.heightLimitHudX = s.x; c.heightLimitHudY = s.y; c.heightLimitHudAnchor = s.anchor; c.heightLimitHudScale = s.scale;
            c.heightLimitEnabled = s.enabled;
            c.setHudBackground(id, s.background);
        } else if (BedwarsHudRenderer.SCOREBOARD_HUD.equals(id)) {
            c.scoreboardHudX = s.x; c.scoreboardHudY = s.y; c.scoreboardHudAnchor = s.anchor; c.scoreboardHudScale = s.scale;
            c.scoreboardHudEnabled = s.enabled;
        } else if (BedwarsHudRenderer.CHAT_HUD.equals(id)) {
            c.chatHudX = s.x; c.chatHudY = s.y; c.chatHudAnchor = s.anchor; c.chatHudScale = s.scale;
        } else if (BedwarsHudRenderer.TAB_LIST_HUD.equals(id)) {
            c.tabListHudX = s.x; c.tabListHudY = s.y; c.tabListHudAnchor = s.anchor; c.tabListHudScale = s.scale;
        }
    }

    /**
     * The module reset: back in the default layout at the current HUD Size; it keeps its on/off and Background.
     * An overlay goes back to vanilla's spot at vanilla's size, whatever the HUD Size.
     */
    public static HudModuleState defaults(ClientSettings c, String id) {
        HudModuleState now = get(c, id);
        if (now == null) return null;
        float scale = isOverlay(id) ? OverlayItems.DEFAULT_SCALE : c.defaultTextSizeScale();
        return new HudModuleState(0, 0, HudPlacement.AUTO, scale, now.enabled, now.background);
    }

    /** Every item's state (modules and overlays), for undo. */
    public static Map<String, HudModuleState> snapshot(ClientSettings c) {
        Map<String, HudModuleState> out = new LinkedHashMap<String, HudModuleState>();
        for (String id : ALL_IDS) out.put(id, get(c, id));
        return out;
    }

    public static void restore(ClientSettings c, Map<String, HudModuleState> snapshot) {
        for (Map.Entry<String, HudModuleState> e : snapshot.entrySet()) set(c, e.getKey(), e.getValue());
    }

    /**
     * Every id with a Background switch: the modules, then {@link HudBackground#OVERLAY_IDS} (the scoreboard, chat and
     * tab list, which are not Edit HUD modules).
     */
    public static List<String> backgroundIds() {
        return BACKGROUND_IDS;
    }

    /** Whether HUD item {@code id} draws its background; On for an id without a Background switch. */
    public static boolean background(ClientSettings c, String id) {
        if (IDS.contains(id)) return get(c, id).background;
        if (HudBackground.OVERLAY_IDS.contains(id)) return c.hudBackground(id);
        return true;
    }

    /**
     * Switches HUD item {@code id}'s background: a module through {@link #set} (both gen timers together), an overlay
     * straight in {@link ClientSettings}; any other id is ignored. The caller saves.
     */
    public static void setBackground(ClientSettings c, String id, boolean on) {
        if (IDS.contains(id)) set(c, id, get(c, id).withBackground(on));
        else if (HudBackground.OVERLAY_IDS.contains(id)) c.setHudBackground(id, on);
    }

    /** The two gen timers share one Background, as they share their on/off toggle. */
    private static void setTimersBackground(ClientSettings c, boolean on) {
        c.setHudBackground(BedwarsHudRenderer.DIAMOND_TIMER_HUD, on);
        c.setHudBackground(BedwarsHudRenderer.EMERALD_TIMER_HUD, on);
    }
}
