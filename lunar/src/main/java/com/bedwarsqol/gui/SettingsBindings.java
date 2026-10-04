package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.SessionHoldKey;
import com.bedwarsqol.feature.SessionStatsWatch;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.gui.sheet.SheetSchema;
import com.bedwarsqol.gui.sheet.SheetValues;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.stats.StatsCache;
import org.lwjgl.input.Keyboard;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The settings sheet's rows on this platform, read from and written to {@link ClientSettings}. Every write saves.
 * Lunar ships Potion, Armor, Keystrokes, TNT Countdown, Block Overlay, the generic chat modules and Auto GG itself,
 * so those are absent here; with no Chat Notifications module, Inc Alert is a module of its own.
 */
final class SettingsBindings implements SheetValues {

    private static final String[] SIZES = {"Small", "Medium", "Large"};
    private static final Set<String> IDS = new HashSet<String>(Arrays.asList(
            "inventory", "inventoryInGame", "genTimers", "genTimersInGame",
            "session", "sessionHold", "sessionKey", "sessionReset", "mapInfo", "mapInfoInGame",
            "handPos", "handX", "handY", "handZ", "handScale", "noEsc", "tabHF", "incAlert", "incKey",
            "stats", "statsNametag", "statsTab", "statsHover", "statsChat", "statsRank", "statsReport",
            "nickUtils", "nickNotify", "autoDenick",
            "tagUtils", "tagUrchin", "tagSeraph", "tagTab", "tagNametag", "tagChat", "tagSound", "partyJoin",
            "hudSize", "sbSize", "tabSize", "guiSize", "font", "accent"));

    static ClientSettings settings() {
        if (BedwarsQol.config == null) {
            // Sanitized, so the one-time migrations stamp it now and cannot undo a later toggle on save.
            BedwarsQol.config = new ClientSettings();
            BedwarsQol.config.sanitize();
        }
        return BedwarsQol.config;
    }

    /** The key that opens and closes the sheet (Weave has no KeyBinding; it lives in the settings). */
    static int settingsKey() {
        return BedwarsQol.config == null ? Keyboard.KEY_RSHIFT : BedwarsQol.config.settingsKeyCode;
    }

    /** The sheet module for an Edit HUD module, or null. */
    static String moduleForHud(String hudId) {
        if (BedwarsHudRenderer.INVENTORY_HUD.equals(hudId)) return "inventory";
        if (BedwarsHudRenderer.DIAMOND_TIMER_HUD.equals(hudId) || BedwarsHudRenderer.EMERALD_TIMER_HUD.equals(hudId)) {
            return "genTimers";
        }
        if (BedwarsHudRenderer.SESSION_HUD.equals(hudId)) return "session";
        if (BedwarsHudRenderer.HEIGHT_LIMIT_HUD.equals(hudId)) return "mapInfo";
        return null;
    }

    @Override
    public boolean has(String id) {
        return IDS.contains(id);
    }

    @Override
    public boolean on(String id) {
        ClientSettings c = settings();
        switch (id) {
            case "inventory": return c.inventoryHudEnabled;
            case "inventoryInGame": return c.inventoryInGameOnly;
            case "genTimers": return c.genTimersEnabled;
            case "genTimersInGame": return c.genTimersInGameOnly;
            case "session": return c.sessionStatsEnabled;
            case "sessionHold": return c.sessionStatsHoldKey;
            case "mapInfo": return c.heightLimitEnabled;
            case "mapInfoInGame": return c.heightLimitInGameOnly;
            case "handPos": return c.handPositionEnabled;
            case "noEsc": return c.suppressEscMenu;
            case "tabHF": return c.tabHideHeaderFooter;
            case "incAlert": return c.chatNotifyInc;
            case "incKey": return c.pcIncKey;
            case "stats": return c.playerStats;
            case "statsNametag": return c.playerStatsNametag;
            case "statsTab": return c.playerStatsTab;
            case "statsHover": return c.playerStatsChatHover;
            case "statsChat": return c.playerStatsChat;
            case "statsRank": return c.playerStatsShowRank;
            case "statsReport": return c.statsSweatReport;
            case "nickUtils": return c.nickUtils;
            case "nickNotify": return c.nickNotify;
            case "autoDenick": return c.autoDenick;
            case "tagUtils": return c.tagUtils;
            case "tagUrchin": return c.urchinTags;
            case "tagSeraph": return c.seraphTags;
            case "tagTab": return c.tagBadgeTab;
            case "tagNametag": return c.tagBadgeNametag;
            case "tagChat": return c.tagChatAlert;
            case "tagSound": return c.tagAlertSound;
            case "partyJoin": return c.partyJoinAlert;
            default: return false;
        }
    }

    @Override
    public void setOn(String id, boolean v) {
        ClientSettings c = settings();
        switch (id) {
            case "inventory": c.inventoryHudEnabled = v; break;
            case "inventoryInGame": c.inventoryInGameOnly = v; break;
            case "genTimers": c.genTimersEnabled = v; break;
            case "genTimersInGame": c.genTimersInGameOnly = v; break;
            case "session": c.sessionStatsEnabled = v; break;
            case "sessionHold": c.sessionStatsHoldKey = v; break;
            case "mapInfo": c.heightLimitEnabled = v; break;
            case "mapInfoInGame": c.heightLimitInGameOnly = v; break;
            case "handPos": c.handPositionEnabled = v; break;
            case "noEsc": c.suppressEscMenu = v; break;
            case "tabHF": c.tabHideHeaderFooter = v; break;
            case "incAlert": c.chatNotifyInc = v; break;
            case "incKey": c.pcIncKey = v; break;
            case "stats": c.playerStats = v; break;
            case "statsNametag": c.playerStatsNametag = v; break;
            case "statsTab": c.playerStatsTab = v; break;
            case "statsHover": c.playerStatsChatHover = v; break;
            case "statsChat": c.playerStatsChat = v; break;
            case "statsRank": c.playerStatsShowRank = v; break;
            case "statsReport": c.statsSweatReport = v; break;
            case "nickUtils": c.nickUtils = v; break;
            case "nickNotify": c.nickNotify = v; break;
            case "autoDenick": c.autoDenick = v; break;
            case "tagUtils":
                c.tagUtils = v;
                StatsCache.invalidateUrchinResolution();
                StatsCache.invalidateSeraphResolution();
                break;
            case "tagUrchin": c.urchinTags = v; StatsCache.invalidateUrchinResolution(); break;
            case "tagSeraph": c.seraphTags = v; StatsCache.invalidateSeraphResolution(); break;
            case "tagTab": c.tagBadgeTab = v; break;
            case "tagNametag": c.tagBadgeNametag = v; break;
            case "tagChat": c.tagChatAlert = v; break;
            case "tagSound": c.tagAlertSound = v; break;
            case "partyJoin": c.partyJoinAlert = v; break;
            default: return;
        }
        c.save();
    }

    @Override
    public String choice(String id) {
        ClientSettings c = settings();
        switch (id) {
            case "hudSize": return SIZES[clamp(c.defaultTextSize, 0, 2)];
            case "sbSize": return SIZES[clamp(c.scoreboardSize, 0, 2)];
            case "tabSize": return SIZES[clamp(c.styledTabListSize, 0, 2)];
            case "guiSize": return SIZES[clamp(c.guiSize, 0, 2)];
            case "font": return c.minecraftFont() ? "Minecraft" : "Modern";
            case "accent": return GuiTheme.fromToken(c.guiAccent).label();
            default: return "";
        }
    }

    @Override
    public void setChoice(String id, String option) {
        ClientSettings c = settings();
        String[] opts = SheetSchema.row(id).options();
        int i = 0;
        while (i < opts.length && !opts[i].equals(option)) i++;
        if (i == opts.length) return;
        switch (id) {
            case "hudSize": c.setHudSize(i); break;
            case "sbSize": c.scoreboardSize = i; break;
            case "tabSize": c.styledTabListSize = i; break;
            case "guiSize": c.guiSize = i; break;
            case "font": c.guiFont = i == 1 ? GuiTheme.FONT_MINECRAFT : GuiTheme.FONT_MODERN; break;
            case "accent": c.guiAccent = GuiTheme.Accent.values()[i].token(); break;
            default: return;
        }
        c.save();
    }

    @Override
    public double number(String id) {
        ClientSettings c = settings();
        switch (id) {
            case "handX": return c.handPosX;
            case "handY": return c.handPosY;
            case "handZ": return c.handPosZ;
            case "handScale": return c.handScale;
            default: return 0;
        }
    }

    @Override
    public void setNumber(String id, double v) {
        ClientSettings c = settings();
        float f = (float) v;
        switch (id) {
            case "handX": c.handPosX = f; break;
            case "handY": c.handPosY = f; break;
            case "handZ": c.handPosZ = f; break;
            case "handScale": c.handScale = f; break;
            default: break;
        }
    }

    @Override
    public void save() {
        settings().save();
    }

    @Override
    public String keyLabel(String id) {
        int code = settings().sessionStatsKeyCode;
        return SessionHoldKey.displayName(code, code > 0 ? Keyboard.getKeyName(code) : null);
    }

    /** Store a key row's binding (0 clears it). */
    void setKey(String id, int binding) {
        ClientSettings c = settings();
        c.sessionStatsKeyCode = binding;
        c.save();
    }

    @Override
    public void run(String id) {
        if ("sessionReset".equals(id)) SessionStatsWatch.reset();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(hi, v);
    }
}
