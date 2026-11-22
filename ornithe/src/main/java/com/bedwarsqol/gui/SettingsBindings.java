package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.MotionBlur;
import com.bedwarsqol.feature.MotionBlurRules;
import com.bedwarsqol.feature.ParticleRules;
import com.bedwarsqol.feature.SessionHoldKey;
import com.bedwarsqol.feature.SessionStatsWatch;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.gui.sheet.SheetSchema;
import com.bedwarsqol.gui.sheet.SheetValues;
import com.bedwarsqol.gui.sheet.SheetLayout;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.HudModuleState;
import com.bedwarsqol.hud.HudModules;
import com.bedwarsqol.hud.TextModules;
import com.bedwarsqol.rewind.RewindDir;
import com.bedwarsqol.stats.StatsCache;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The settings sheet's rows on this platform, read from and written to {@link ClientSettings}. Every write saves.
 * The Ornithe tree has every module in {@link SheetSchema} except Lunar's top-level Inc, Bed and BG Alert, and it is
 * the platform that lists the platform-only Zoom, No Hurt Cam, Toggle Sprint, Dynamic FOV, Team Upgrades, FPS, CPS,
 * Ping, Fullbright, Item Physics, 1.7 Animations, Particles, Motion Blur, Rewind and the per-item Background rows,
 * which read and write their HUD item ({@link SheetSchema#hudItem}) through {@link HudModules}.
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
        if (BedwarsHudRenderer.TEAM_UPGRADES_HUD.equals(hudId)) return "teamUpgrades";
        if (BedwarsHudRenderer.FPS_HUD.equals(hudId)) return "fps";
        if (BedwarsHudRenderer.CPS_HUD.equals(hudId)) return "cps";
        if (BedwarsHudRenderer.PING_HUD.equals(hudId)) return "ping";
        // the overlays' sheet module is their Background switch (they have no on/off of their own)
        if (BedwarsHudRenderer.SCOREBOARD_HUD.equals(hudId)) return "scoreboardBg";
        if (BedwarsHudRenderer.TAB_LIST_HUD.equals(hudId)) return "tabListBg";
        if (BedwarsHudRenderer.CHAT_HUD.equals(hudId)) return "chatBg";
        return null;
    }

    /**
     * The HUD items a sheet switch shows when it goes from off to on, so they take their first free Auto spot: only the
     * module enable switches, listed here on purpose (not derived from {@link #moduleForHud}). A Background switch, In
     * Game Only, any other row and the scoreboard, chat and tab list show nothing, so they never move an item.
     */
    static List<String> hudIdsShownBy(String rowId) {
        List<String> ids;
        switch (rowId == null ? "" : rowId) {
            case "potion": ids = Collections.singletonList(BedwarsHudRenderer.POTION_HUD); break;
            case "armor": ids = Collections.singletonList(BedwarsHudRenderer.ARMOR_HUD); break;
            case "inventory": ids = Collections.singletonList(BedwarsHudRenderer.INVENTORY_HUD); break;
            case "genTimers":
                ids = Arrays.asList(BedwarsHudRenderer.DIAMOND_TIMER_HUD, BedwarsHudRenderer.EMERALD_TIMER_HUD);
                break;
            case "keystrokes": ids = Collections.singletonList(BedwarsHudRenderer.KEYSTROKES_HUD); break;
            case "mapInfo": ids = Collections.singletonList(BedwarsHudRenderer.HEIGHT_LIMIT_HUD); break;
            case "session": ids = Collections.singletonList(BedwarsHudRenderer.SESSION_HUD); break;
            case "teamUpgrades": ids = Collections.singletonList(BedwarsHudRenderer.TEAM_UPGRADES_HUD); break;
            case "fps": ids = Collections.singletonList(BedwarsHudRenderer.FPS_HUD); break;
            case "cps": ids = Collections.singletonList(BedwarsHudRenderer.CPS_HUD); break;
            case "ping": ids = Collections.singletonList(BedwarsHudRenderer.PING_HUD); break;
            default: return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (String id : ids) {
            if (HudModules.ids().contains(id)) out.add(id);
        }
        return out;
    }

    /**
     * Every schema id but Lunar's top-level alerts, and the Scoreboard Size and Tab List Size rows: here the
     * scoreboard and tab list are HUD items, sized with Edit HUD's corner resize.
     */
    @Override
    public boolean has(String id) {
        return !"incAlert".equals(id) && !"bedAlert".equals(id) && !"bgAlert".equals(id)
                && !"sbSize".equals(id) && !"tabSize".equals(id)
                && (SheetSchema.module(id) != null || SheetSchema.row(id) != null);
    }

    @Override
    public boolean hasPlatformOnly(String id) {
        return "zoom".equals(id) || "noHurtCam".equals(id) || "toggleSprint".equals(id) || "dynamicFov".equals(id)
                || "teamUpgrades".equals(id) || "teamUpgradesLayout".equals(id)
                || "fps".equals(id) || "cps".equals(id) || "ping".equals(id)
                || "fpsBrackets".equals(id) || "cpsBrackets".equals(id) || "pingBrackets".equals(id)
                || "fpsLabel".equals(id) || "cpsLabel".equals(id) || "pingLabel".equals(id)
                || "fullbright".equals(id) || "itemPhysics".equals(id) || "oldAnimations".equals(id) || "particles".equals(id)
                || "oaBlockHit".equals(id) || "oaItemPosition".equals(id) || "oaSmoothSneak".equals(id)
                || "oaHearts".equals(id) || "oaRedArmor".equals(id)
                || "ptAlwaysCrit".equals(id) || "ptHideOwnPotion".equals(id) || "ptHideBlockBreak".equals(id)
                || "ptMultiplier".equals(id)
                || "motionBlur".equals(id) || "mbStrength".equals(id)
                || "rewind".equals(id) || "rewindRecord".equals(id) || "rewindMarkerKey".equals(id)
                || "rewindCleanKey".equals(id) || "rewindOpenFolder".equals(id)
                || SheetSchema.hudItem(id) != null || SheetLayout.RESET_POSITIONS_ID.equals(id);
    }

    @Override
    public boolean on(String id) {
        ClientSettings c = settings();
        String item = SheetSchema.hudItem(id);
        if (item != null) return HudModules.background(c, item);
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
            case "teamUpgrades": return c.teamUpgradesEnabled;
            case "fps": return c.fpsEnabled;
            case "cps": return c.cpsEnabled;
            case "ping": return c.pingEnabled;
            case "fpsBrackets": return c.fpsBrackets;
            case "cpsBrackets": return c.cpsBrackets;
            case "pingBrackets": return c.pingBrackets;
            case "handPos": return c.handPositionEnabled;
            case "tnt": return c.tntFuseEnabled;
            case "noEsc": return c.suppressEscMenu;
            case "clutch": return c.clutchPractice;
            case "clutchBlocks": return c.clutchBlockDamage;
            case "zoom": return c.zoomEnabled;
            case "noHurtCam": return c.noHurtCam;
            case "toggleSprint": return c.toggleSprintEnabled;
            case "dynamicFov": return c.dynamicFov;
            case "blockOverlay": return c.blockOverlayEnabled;
            case "boSee": return c.blockOverlaySeeThrough;
            case "boWhite": return c.blockOverlayWhite;
            case "fullbright": return c.fullbright;
            case "itemPhysics": return c.itemPhysics;
            case "oldAnimations": return c.oldAnimations;
            case "oaBlockHit": return c.oaBlockHit;
            case "oaItemPosition": return c.oaItemPosition;
            case "oaSmoothSneak": return c.oaSmoothSneak;
            case "oaHearts": return c.oaHearts;
            case "oaRedArmor": return c.oaRedArmor;
            case "particles": return c.particles;
            case "motionBlur": return c.motionBlur;
            case "ptAlwaysCrit": return c.ptAlwaysCrit;
            case "ptHideOwnPotion": return c.ptHideOwnPotion;
            case "ptHideBlockBreak": return c.ptHideBlockBreak;
            case "tabHF": return c.tabHideHeaderFooter;
            case "chatStack": return c.chatStackSpam;
            case "stackTime": return c.chatStackTimeBased;
            case "stackBlanks": return c.chatStackIgnoreBlanks;
            case "chatNotify": return c.chatNotifications;
            case "notifyMention": return c.chatNotifyMention;
            case "notifyInc": return c.chatNotifyInc;
            case "notifyBed": return c.chatNotifyBed;
            case "notifyBg": return c.chatNotifyBg;
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
            case "tagTab": return c.tagBadgeTab;
            case "tagNametag": return c.tagBadgeNametag;
            case "tagChat": return c.tagChatAlert;
            case "tagSound": return c.tagAlertSound;
            case "autoGg": return c.autoGg;
            case "partyJoin": return c.partyJoinAlert;
            case "split": return c.splitEnabled;
            case "rewind": return c.rewind;
            case "rewindRecord": return c.rewindRecordGames;
            default: return false;
        }
    }

    @Override
    public void setOn(String id, boolean v) {
        ClientSettings c = settings();
        String item = SheetSchema.hudItem(id);
        if (item != null) {
            HudModules.setBackground(c, item, v);
            c.save();
            return;
        }
        // A module switched on: its items come back where they were (the nearest free spot if that is covered now), or
        // take their first free Auto spot if never moved. Switched off: the other Auto items are pinned first, as Edit
        // HUD's Hide does, so they don't close up the gap. Either way nothing else moves.
        boolean change = v != on(id);
        List<String> items = change ? hudIdsShownBy(id) : Collections.<String>emptyList();
        if (!items.isEmpty()) {
            if (v) BedwarsHudRenderer.show(Minecraft.getMinecraft(), c, items);
            else BedwarsHudRenderer.pinShownAuto(Minecraft.getMinecraft(), c, items);
        }
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
            case "teamUpgrades": c.teamUpgradesEnabled = v; break;
            case "fps": c.fpsEnabled = v; break;
            case "cps": c.cpsEnabled = v; break;
            case "ping": c.pingEnabled = v; break;
            case "fpsBrackets": c.fpsBrackets = v; break;
            case "cpsBrackets": c.cpsBrackets = v; break;
            case "pingBrackets": c.pingBrackets = v; break;
            case "handPos": c.handPositionEnabled = v; break;
            case "tnt": c.tntFuseEnabled = v; break;
            case "noEsc": c.suppressEscMenu = v; break;
            case "clutch": c.clutchPractice = v; break;
            case "clutchBlocks": c.clutchBlockDamage = v; break;
            case "zoom": c.zoomEnabled = v; break;
            case "noHurtCam": c.noHurtCam = v; break;
            case "toggleSprint": c.toggleSprintEnabled = v; break;
            case "dynamicFov": c.dynamicFov = v; break;
            case "blockOverlay": c.blockOverlayEnabled = v; break;
            case "boSee": c.blockOverlaySeeThrough = v; break;
            case "boWhite": c.blockOverlayWhite = v; break;
            case "fullbright": c.fullbright = v; break;
            case "itemPhysics": c.itemPhysics = v; break;
            case "oldAnimations": c.oldAnimations = v; break;
            case "oaBlockHit": c.oaBlockHit = v; break;
            case "oaItemPosition": c.oaItemPosition = v; break;
            case "oaSmoothSneak": c.oaSmoothSneak = v; break;
            case "oaHearts": c.oaHearts = v; break;
            case "oaRedArmor": c.oaRedArmor = v; break;
            case "particles": c.particles = v; break;
            case "motionBlur":
                c.motionBlur = v;
                if (!v) MotionBlur.release(); // frees the texture and program; the hook then never touches GL
                break;
            case "ptAlwaysCrit": c.ptAlwaysCrit = v; break;
            case "ptHideOwnPotion": c.ptHideOwnPotion = v; break;
            case "ptHideBlockBreak": c.ptHideBlockBreak = v; break;
            case "tabHF": c.tabHideHeaderFooter = v; break;
            case "chatStack": c.chatStackSpam = v; break;
            case "stackTime": c.chatStackTimeBased = v; break;
            case "stackBlanks": c.chatStackIgnoreBlanks = v; break;
            case "chatNotify": c.chatNotifications = v; break;
            case "notifyMention": c.chatNotifyMention = v; break;
            case "notifyInc": c.chatNotifyInc = v; break;
            case "notifyBed": c.chatNotifyBed = v; break;
            case "notifyBg": c.chatNotifyBg = v; break;
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
                break;
            case "tagUrchin": c.urchinTags = v; StatsCache.invalidateUrchinResolution(); break;
            case "tagTab": c.tagBadgeTab = v; break;
            case "tagNametag": c.tagBadgeNametag = v; break;
            case "tagChat": c.tagChatAlert = v; break;
            case "tagSound": c.tagAlertSound = v; break;
            case "autoGg": c.autoGg = v; break;
            case "partyJoin": c.partyJoinAlert = v; break;
            case "split": c.splitEnabled = v; break;
            case "rewind": c.rewind = v; break;
            case "rewindRecord": c.rewindRecordGames = v; break;
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
            case "teamUpgradesLayout": return SheetSchema.row(id).options()[clamp(c.teamUpgradesLayout, 0, 1)];
            case "fpsLabel": return TextModules.Label.of(c.fpsLabel).text;
            case "cpsLabel": return TextModules.Label.of(c.cpsLabel).text;
            case "pingLabel": return TextModules.Label.of(c.pingLabel).text;
            case "ptMultiplier": return SheetSchema.row(id).options()[ParticleRules.clampMultiplier(c.ptMultiplier) - 1];
            case "boOpacity": return OPACITIES[nearest(OVERLAY_ALPHA, (c.blockOverlayColor >>> 24) & 0xFF)];
            case "hudSize": return SIZES[clamp(c.defaultTextSize, 0, 2)];
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
            case "teamUpgradesLayout": c.teamUpgradesLayout = i; break;
            case "fpsLabel": c.fpsLabel = TextModules.Label.named(option).ordinal(); break;
            case "cpsLabel": c.cpsLabel = TextModules.Label.named(option).ordinal(); break;
            case "pingLabel": c.pingLabel = TextModules.Label.named(option).ordinal(); break;
            case "ptMultiplier": c.ptMultiplier = ParticleRules.MIN_MULTIPLIER + i; break;
            case "boOpacity": c.blockOverlayColor = (c.blockOverlayColor & 0x00FFFFFF) | (OVERLAY_ALPHA[i] << 24); break;
            case "hudSize": c.setHudSize(i); break;
            case "guiSize": c.guiSize = i; break;
            case "font": c.guiFont = "Minecraft".equals(option) ? GuiTheme.FONT_MINECRAFT : GuiTheme.FONT_MODERN; break;
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
            case "mbStrength": return MotionBlurRules.clampStrength(c.mbStrength);
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
            case "mbStrength": c.mbStrength = MotionBlurRules.clampStrength((int) Math.round(v)); break;
            default: break;
        }
    }

    @Override
    public void save() {
        settings().save();
    }

    @Override
    public String keyLabel(String id) {
        int code = keyCode(settings(), id);
        return SessionHoldKey.displayName(code, code > 0 ? Keyboard.getKeyName(code) : null);
    }

    /** Store a key row's binding (0 clears it). */
    void setKey(String id, int binding) {
        ClientSettings c = settings();
        switch (id) {
            case "rewindMarkerKey": c.rewindMarkerKey = binding; break;
            case "rewindCleanKey": c.rewindCleanViewKey = binding; break;
            default: c.sessionStatsKeyCode = binding; break;
        }
        c.save();
    }

    /** The binding key row {@code id} shows: Rewind's two keys, else the Session Stats key. */
    private static int keyCode(ClientSettings c, String id) {
        switch (id) {
            case "rewindMarkerKey": return c.rewindMarkerKey;
            case "rewindCleanKey": return c.rewindCleanViewKey;
            default: return c.sessionStatsKeyCode;
        }
    }

    @Override
    public void run(String id) {
        if ("sessionReset".equals(id)) SessionStatsWatch.reset();
        else if (SheetLayout.RESET_POSITIONS_ID.equals(id)) resetPositions();
        else if ("rewindOpenFolder".equals(id)) RewindDir.open();
    }

    /** Reset Positions from the settings menu: the default layout, saved, and undoable in the next Edit HUD (D7). */
    private static void resetPositions() {
        ClientSettings c = settings();
        Map<String, HudModuleState> before = HudModules.snapshot(c);
        BedwarsHudRenderer.resetPositions(Minecraft.getMinecraft(), c);
        c.save();
        EditHudGui.offerUndo(before, HudModules.snapshot(c));
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
