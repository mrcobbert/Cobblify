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

/**
 * The settings sheet's rows on this platform, read from and written to {@link ClientSettings}. Every write saves.
 * The Forge tree has every module in {@link SheetSchema} except Lunar's top-level Inc Alert.
 */
final class SettingsBindings implements SheetValues {

    private static final String[] SIZES = {"Small", "Medium", "Large"};
    private static final int[] TNT_RADII = {5, 10, 15, 20, 30};
    private static final int[] OVERLAY_ALPHA = {0x40, 0x80, 0xC0};
    private static final String[] OPACITIES = {"Low", "Medium", "High"};

    static ClientSettings settings() {
        if (BedwarsQol.config == null) {
            // Sanitized, so the one-time migrations stamp it now and cannot undo a later toggle on save.
            BedwarsQol.config = new ClientSettings();
            BedwarsQol.config.sanitize();
        }
        return BedwarsQol.config;
    }

    /** The key that opens and closes the sheet: the vanilla keybinding, rebindable in Controls. */
    static int settingsKey() {
        return BedwarsQol.settingsKeyBinding == null ? Keyboard.KEY_RSHIFT : BedwarsQol.settingsKeyBinding.getKeyCode();
    }

    /** The sheet module for an Edit HUD module, or null. */
    static String moduleForHud(String hudId) {
        if (BedwarsHudRenderer.POTION_HUD.equals(hudId)) return "potion";
        if (BedwarsHudRenderer.ARMOR_HUD.equals(hudId)) return "armor";
        if (BedwarsHudRenderer.KEYSTROKES_HUD.equals(hudId)) return "keystrokes";
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
        return !"incAlert".equals(id) && (SheetSchema.module(id) != null || SheetSchema.row(id) != null);
    }

    @Override
    public boolean on(String id) {
        ClientSettings c = settings();
        switch (id) {
            case "potion": return c.potionStatusEnabled;
            case "potionInGame": return c.potionInGameOnly;
            case "armor": return c.armorTypeEnabled;
            case "armorInGame": return c.armorInGameOnly;
            case "inventory": return c.inventoryHudEnabled;
            case "inventoryInGame": return c.inventoryInGameOnly;
            case "genTimers": return c.genTimersEnabled;
            case "genTimersInGame": return c.genTimersInGameOnly;
            case "keystrokes": return c.keystrokesEnabled;
            case "keystrokesInGame": return c.keystrokesInGameOnly;
            case "session": return c.sessionStatsEnabled;
            case "sessionHold": return c.sessionStatsHoldKey;
            case "mapInfo": return c.heightLimitEnabled;
            case "mapInfoInGame": return c.heightLimitInGameOnly;
            case "handPos": return c.handPositionEnabled;
            case "tnt": return c.tntFuseEnabled;
            case "noEsc": return c.suppressEscMenu;
            case "blockOverlay": return c.blockOverlayEnabled;
            case "boSee": return c.blockOverlaySeeThrough;
            case "boWhite": return c.blockOverlayWhite;
            case "tabHF": return c.tabHideHeaderFooter;
            case "chatStack": return c.chatStackSpam;
            case "stackTime": return c.chatStackTimeBased;
            case "stackBlanks": return c.chatStackIgnoreBlanks;
            case "chatNotify": return c.chatNotifications;
            case "notifyMention": return c.chatNotifyMention;
            case "notifyInc": return c.chatNotifyInc;
            case "chatUnlimited": return c.chatUnlimited;
            case "chatKeep": return c.chatKeepHistory;
            case "chatCopy": return c.chatCopy;
            case "chatLong": return c.chatLongMessages;
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
            case "autoGg": return c.autoGg;
            case "partyJoin": return c.partyJoinAlert;
            default: return false;
        }
    }

    @Override
    public void setOn(String id, boolean v) {
        ClientSettings c = settings();
        switch (id) {
            case "potion": c.potionStatusEnabled = v; break;
            case "potionInGame": c.potionInGameOnly = v; break;
            case "armor": c.armorTypeEnabled = v; break;
            case "armorInGame": c.armorInGameOnly = v; break;
            case "inventory": c.inventoryHudEnabled = v; break;
            case "inventoryInGame": c.inventoryInGameOnly = v; break;
            case "genTimers": c.genTimersEnabled = v; break;
            case "genTimersInGame": c.genTimersInGameOnly = v; break;
            case "keystrokes": c.keystrokesEnabled = v; break;
            case "keystrokesInGame": c.keystrokesInGameOnly = v; break;
            case "session": c.sessionStatsEnabled = v; break;
            case "sessionHold": c.sessionStatsHoldKey = v; break;
            case "mapInfo": c.heightLimitEnabled = v; break;
            case "mapInfoInGame": c.heightLimitInGameOnly = v; break;
            case "handPos": c.handPositionEnabled = v; break;
            case "tnt": c.tntFuseEnabled = v; break;
            case "noEsc": c.suppressEscMenu = v; break;
            case "blockOverlay": c.blockOverlayEnabled = v; break;
            case "boSee": c.blockOverlaySeeThrough = v; break;
            case "boWhite": c.blockOverlayWhite = v; break;
            case "tabHF": c.tabHideHeaderFooter = v; break;
            case "chatStack": c.chatStackSpam = v; break;
            case "stackTime": c.chatStackTimeBased = v; break;
            case "stackBlanks": c.chatStackIgnoreBlanks = v; break;
            case "chatNotify": c.chatNotifications = v; break;
            case "notifyMention": c.chatNotifyMention = v; break;
            case "notifyInc": c.chatNotifyInc = v; break;
            case "chatUnlimited": c.chatUnlimited = v; break;
            case "chatKeep": c.chatKeepHistory = v; break;
            case "chatCopy": c.chatCopy = v; break;
            case "chatLong": c.chatLongMessages = v; break;
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
            case "autoGg": c.autoGg = v; break;
            case "partyJoin": c.partyJoinAlert = v; break;
            default: return;
        }
        c.save();
    }

    @Override
    public String choice(String id) {
        ClientSettings c = settings();
        switch (id) {
            case "tntRadius": return String.valueOf(TNT_RADII[nearest(TNT_RADII, c.tntFuseRadius)]);
            case "boStyle": return SheetSchema.row(id).options()[clamp(c.blockOverlayStyle, 0, 2)];
            case "boOpacity": return OPACITIES[nearest(OVERLAY_ALPHA, (c.blockOverlayColor >>> 24) & 0xFF)];
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
            case "tntRadius": c.tntFuseRadius = TNT_RADII[i]; break;
            case "boStyle": c.blockOverlayStyle = i; break;
            case "boOpacity": c.blockOverlayColor = (c.blockOverlayColor & 0x00FFFFFF) | (OVERLAY_ALPHA[i] << 24); break;
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
            case "stackWindow": return c.chatStackWindowSec;
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
            case "stackWindow": c.chatStackWindowSec = f; break;
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

    private static int nearest(int[] values, int v) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - v) < Math.abs(values[best] - v)) best = i;
        }
        return best;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(hi, v);
    }
}
