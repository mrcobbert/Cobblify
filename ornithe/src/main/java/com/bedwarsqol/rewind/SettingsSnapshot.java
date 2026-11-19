package com.bedwarsqol.rewind;

import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.hud.HudBackground;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.GameSettings;

import java.util.Map;

/**
 * The SETTINGS record's values (PLAN section A "SETTINGS keys", numbered in {@link RewindFormat}): vanilla's FOV
 * setting, GUI scale, view bobbing, particle setting and gamma, the scaled and display size, Cobblify's view-changing
 * switches (font, accent, text size, 1.7 Animations and its five parts, Hand Position, No Hurt Cam, Dynamic FOV,
 * Zoom, Fullbright, Motion Blur, Item Physics, Particles) and every HUD item's placing and styling fields (enabled,
 * In Game Only, X, Y, anchor, scale, Background, sub-pixel X and Y, and the item's options). A field an item does not
 * have is not a key ({@link #keys()}).
 *
 * <p>{@link #read} copies the current values into fixed arrays; {@link #write} encodes every key (a segment's first
 * tick) or only the keys whose value differs from the last write, and remembers what it wrote. Floats compare by
 * bits, strings by reference and then {@code equals}. Client thread only; nothing allocates in steady state (the
 * scaled size is recomputed through vanilla's {@link ScaledResolution} only when the display size, GUI scale or
 * Unicode flag changed).
 */
public final class SettingsSnapshot {

    private static final int F_ENABLED = 1;
    private static final int F_IN_GAME_ONLY = 2;
    private static final int F_OPTION_A = 4;
    private static final int F_OPTION_B = 8;

    /** The optional fields each HUD item has, index = item - 1 ({@code RewindFormat.HUD_*} order). */
    private static final int[] HUD_FIELDS_OF = {
            F_ENABLED | F_IN_GAME_ONLY,               // potion
            F_ENABLED | F_IN_GAME_ONLY,               // armor
            F_ENABLED | F_IN_GAME_ONLY,               // inventory
            F_ENABLED | F_IN_GAME_ONLY,               // diamondtimer (the shared gen timer switches)
            F_ENABLED | F_IN_GAME_ONLY,               // emeraldtimer
            F_ENABLED | F_IN_GAME_ONLY,               // keystrokes
            F_ENABLED | F_OPTION_A | F_OPTION_B,      // sessionstats: hold key, key code
            F_ENABLED | F_IN_GAME_ONLY,               // heightlimit (Map Info)
            F_ENABLED | F_OPTION_A,                   // teamupgrades: layout
            F_ENABLED | F_OPTION_A | F_OPTION_B,      // fps: brackets, label
            F_ENABLED | F_OPTION_A | F_OPTION_B,      // cps
            F_ENABLED | F_OPTION_A | F_OPTION_B,      // ping
            F_ENABLED,                                // scoreboard
            0,                                        // chat (always shown)
            0};                                       // tab list (always shown)

    private static final int[] KEYS;
    /** Position of a key in {@link #KEYS}, or -1. */
    private static final int[] INDEX = new int[256];

    static {
        int[] keys = new int[256];
        int n = 0;
        for (int k = RewindFormat.SETTING_FOV; k <= RewindFormat.SETTING_GAMMA; k++) {
            keys[n++] = k;
        }
        for (int k = RewindFormat.SETTING_GUI_FONT; k <= RewindFormat.SETTING_PT_MULTIPLIER; k++) {
            keys[n++] = k;
        }
        for (int item = RewindFormat.HUD_POTION; item <= RewindFormat.HUD_ITEM_TAB_LIST; item++) {
            int has = HUD_FIELDS_OF[item - 1];
            for (int field = 0; field < RewindFormat.HUD_FIELDS; field++) {
                boolean present;
                switch (field) {
                    case RewindFormat.HUD_FIELD_ENABLED:
                        present = (has & F_ENABLED) != 0;
                        break;
                    case RewindFormat.HUD_FIELD_IN_GAME_ONLY:
                        present = (has & F_IN_GAME_ONLY) != 0;
                        break;
                    case RewindFormat.HUD_FIELD_OPTION_A:
                        present = (has & F_OPTION_A) != 0;
                        break;
                    case RewindFormat.HUD_FIELD_OPTION_B:
                        present = (has & F_OPTION_B) != 0;
                        break;
                    default:
                        present = true;
                }
                if (present) {
                    keys[n++] = RewindFormat.settingHudKey(item, field);
                }
            }
        }
        KEYS = new int[n];
        System.arraycopy(keys, 0, KEYS, 0, n);
        java.util.Arrays.fill(INDEX, -1);
        for (int i = 0; i < n; i++) {
            if (RewindFormat.settingType(KEYS[i]) < 0) {
                throw new IllegalStateException("unnumbered setting key " + KEYS[i]);
            }
            INDEX[KEYS[i]] = i;
        }
    }

    private final int[] type = new int[KEYS.length];
    private final int[] ints = new int[KEYS.length];
    private final float[] floats = new float[KEYS.length];
    private final String[] strings = new String[KEYS.length];
    private final int[] lastInts = new int[KEYS.length];
    private final float[] lastFloats = new float[KEYS.length];
    private final String[] lastStrings = new String[KEYS.length];
    private boolean written;

    // the scaled size, recomputed only when one of its inputs changes
    private boolean scaledKnown;
    private int scaledFor;
    private int scaledForW;
    private int scaledForH;
    private boolean scaledForUnicode;
    private int scaledW;
    private int scaledH;

    public SettingsSnapshot() {
        for (int i = 0; i < KEYS.length; i++) {
            type[i] = RewindFormat.settingType(KEYS[i]);
        }
    }

    /** Every key a full snapshot writes, in write order (a copy). */
    static int[] keys() {
        return KEYS.clone();
    }

    /** Reads the current values from the client and Cobblify's settings. Client thread. */
    public void read(Minecraft mc, ClientSettings cfg) {
        GameSettings gs = mc.gameSettings;
        int dw = mc.displayWidth;
        int dh = mc.displayHeight;
        boolean unicode = mc.isUnicode();
        if (!scaledKnown || dw != scaledForW || dh != scaledForH || gs.guiScale != scaledFor
                || unicode != scaledForUnicode) {
            ScaledResolution sr = new ScaledResolution(mc);
            scaledW = sr.getScaledWidth();
            scaledH = sr.getScaledHeight();
            scaledForW = dw;
            scaledForH = dh;
            scaledFor = gs.guiScale;
            scaledForUnicode = unicode;
            scaledKnown = true;
        }
        read(gs, scaledW, scaledH, dw, dh, cfg);
    }

    /** Reads the current values; {@code cfg} null leaves Cobblify's keys as they were. */
    void read(GameSettings gs, int scaledWidth, int scaledHeight, int displayWidth, int displayHeight,
              ClientSettings cfg) {
        if (gs != null) {
            f(RewindFormat.SETTING_FOV, gs.fovSetting);
            i(RewindFormat.SETTING_GUI_SCALE, gs.guiScale);
            b(RewindFormat.SETTING_VIEW_BOBBING, gs.viewBobbing);
            i(RewindFormat.SETTING_PARTICLES_VANILLA, gs.particleSetting);
            f(RewindFormat.SETTING_GAMMA, gs.gammaSetting);
        }
        i(RewindFormat.SETTING_SCALED_WIDTH, scaledWidth);
        i(RewindFormat.SETTING_SCALED_HEIGHT, scaledHeight);
        i(RewindFormat.SETTING_DISPLAY_WIDTH, displayWidth);
        i(RewindFormat.SETTING_DISPLAY_HEIGHT, displayHeight);
        if (cfg == null) {
            return;
        }
        s(RewindFormat.SETTING_GUI_FONT, cfg.guiFont);
        s(RewindFormat.SETTING_GUI_ACCENT, cfg.guiAccent);
        i(RewindFormat.SETTING_HUD_TEXT_SIZE, cfg.defaultTextSize);
        b(RewindFormat.SETTING_OLD_ANIMATIONS, cfg.oldAnimations);
        b(RewindFormat.SETTING_OA_BLOCK_HIT, cfg.oaBlockHit);
        b(RewindFormat.SETTING_OA_ITEM_POSITION, cfg.oaItemPosition);
        b(RewindFormat.SETTING_OA_SMOOTH_SNEAK, cfg.oaSmoothSneak);
        b(RewindFormat.SETTING_OA_HEARTS, cfg.oaHearts);
        b(RewindFormat.SETTING_OA_RED_ARMOR, cfg.oaRedArmor);
        b(RewindFormat.SETTING_HAND_POSITION, cfg.handPositionEnabled);
        f(RewindFormat.SETTING_HAND_POS_X, cfg.handPosX);
        f(RewindFormat.SETTING_HAND_POS_Y, cfg.handPosY);
        f(RewindFormat.SETTING_HAND_POS_Z, cfg.handPosZ);
        f(RewindFormat.SETTING_HAND_SCALE, cfg.handScale);
        b(RewindFormat.SETTING_NO_HURT_CAM, cfg.noHurtCam);
        b(RewindFormat.SETTING_DYNAMIC_FOV, cfg.dynamicFov);
        b(RewindFormat.SETTING_ZOOM, cfg.zoomEnabled);
        b(RewindFormat.SETTING_FULLBRIGHT, cfg.fullbright);
        b(RewindFormat.SETTING_MOTION_BLUR, cfg.motionBlur);
        i(RewindFormat.SETTING_MOTION_BLUR_STRENGTH, cfg.mbStrength);
        b(RewindFormat.SETTING_ITEM_PHYSICS, cfg.itemPhysics);
        b(RewindFormat.SETTING_PARTICLES, cfg.particles);
        b(RewindFormat.SETTING_PT_ALWAYS_CRIT, cfg.ptAlwaysCrit);
        b(RewindFormat.SETTING_PT_HIDE_OWN_POTION, cfg.ptHideOwnPotion);
        b(RewindFormat.SETTING_PT_HIDE_BLOCK_BREAK, cfg.ptHideBlockBreak);
        i(RewindFormat.SETTING_PT_MULTIPLIER, cfg.ptMultiplier);

        hud(cfg, RewindFormat.HUD_POTION, cfg.potionStatusEnabled, cfg.potionInGameOnly,
                cfg.potionHudX, cfg.potionHudY, cfg.potionHudAnchor, cfg.potionHudScale);
        hud(cfg, RewindFormat.HUD_ARMOR, cfg.armorTypeEnabled, cfg.armorInGameOnly,
                cfg.armorHudX, cfg.armorHudY, cfg.armorHudAnchor, cfg.armorHudScale);
        hud(cfg, RewindFormat.HUD_INVENTORY, cfg.inventoryHudEnabled, cfg.inventoryInGameOnly,
                cfg.inventoryHudX, cfg.inventoryHudY, cfg.inventoryHudAnchor, cfg.inventoryHudScale);
        hud(cfg, RewindFormat.HUD_DIAMOND_TIMER, cfg.genTimersEnabled, cfg.genTimersInGameOnly,
                cfg.diamondTimerHudX, cfg.diamondTimerHudY, cfg.diamondTimerHudAnchor, cfg.diamondTimerHudScale);
        hud(cfg, RewindFormat.HUD_EMERALD_TIMER, cfg.genTimersEnabled, cfg.genTimersInGameOnly,
                cfg.emeraldTimerHudX, cfg.emeraldTimerHudY, cfg.emeraldTimerHudAnchor, cfg.emeraldTimerHudScale);
        hud(cfg, RewindFormat.HUD_KEYSTROKES, cfg.keystrokesEnabled, cfg.keystrokesInGameOnly,
                cfg.keystrokesHudX, cfg.keystrokesHudY, cfg.keystrokesHudAnchor, cfg.keystrokesHudScale);
        hud(cfg, RewindFormat.HUD_SESSION_STATS, cfg.sessionStatsEnabled, false,
                cfg.sessionStatsHudX, cfg.sessionStatsHudY, cfg.sessionStatsHudAnchor, cfg.sessionStatsHudScale);
        b(RewindFormat.settingHudKey(RewindFormat.HUD_SESSION_STATS, RewindFormat.HUD_FIELD_OPTION_A),
                cfg.sessionStatsHoldKey);
        i(RewindFormat.settingHudKey(RewindFormat.HUD_SESSION_STATS, RewindFormat.HUD_FIELD_OPTION_B),
                cfg.sessionStatsKeyCode);
        hud(cfg, RewindFormat.HUD_HEIGHT_LIMIT, cfg.heightLimitEnabled, cfg.heightLimitInGameOnly,
                cfg.heightLimitHudX, cfg.heightLimitHudY, cfg.heightLimitHudAnchor, cfg.heightLimitHudScale);
        hud(cfg, RewindFormat.HUD_TEAM_UPGRADES, cfg.teamUpgradesEnabled, false,
                cfg.teamUpgradesHudX, cfg.teamUpgradesHudY, cfg.teamUpgradesHudAnchor, cfg.teamUpgradesHudScale);
        i(RewindFormat.settingHudKey(RewindFormat.HUD_TEAM_UPGRADES, RewindFormat.HUD_FIELD_OPTION_A),
                cfg.teamUpgradesLayout);
        hud(cfg, RewindFormat.HUD_FPS, cfg.fpsEnabled, false,
                cfg.fpsHudX, cfg.fpsHudY, cfg.fpsHudAnchor, cfg.fpsHudScale);
        options(RewindFormat.HUD_FPS, cfg.fpsBrackets, cfg.fpsLabel);
        hud(cfg, RewindFormat.HUD_CPS, cfg.cpsEnabled, false,
                cfg.cpsHudX, cfg.cpsHudY, cfg.cpsHudAnchor, cfg.cpsHudScale);
        options(RewindFormat.HUD_CPS, cfg.cpsBrackets, cfg.cpsLabel);
        hud(cfg, RewindFormat.HUD_PING, cfg.pingEnabled, false,
                cfg.pingHudX, cfg.pingHudY, cfg.pingHudAnchor, cfg.pingHudScale);
        options(RewindFormat.HUD_PING, cfg.pingBrackets, cfg.pingLabel);
        hud(cfg, RewindFormat.HUD_ITEM_SCOREBOARD, cfg.scoreboardHudEnabled, false,
                cfg.scoreboardHudX, cfg.scoreboardHudY, cfg.scoreboardHudAnchor, cfg.scoreboardHudScale);
        hud(cfg, RewindFormat.HUD_ITEM_CHAT, true, false,
                cfg.chatHudX, cfg.chatHudY, cfg.chatHudAnchor, cfg.chatHudScale);
        hud(cfg, RewindFormat.HUD_ITEM_TAB_LIST, true, false,
                cfg.tabListHudX, cfg.tabListHudY, cfg.tabListHudAnchor, cfg.tabListHudScale);
    }

    /**
     * Encodes the SETTINGS body into {@code codec}: every key when {@code full} (or before the first write),
     * otherwise only the keys whose value changed since the last write. Returns the record length, or 0 when
     * nothing is to be written.
     */
    int write(ExtCodec codec, boolean full) {
        boolean all = full || !written;
        boolean any = false;
        for (int i = 0; i < KEYS.length; i++) {
            if (!all && same(i)) {
                continue;
            }
            if (!any) {
                codec.beginSettings();
                any = true;
            }
            switch (type[i]) {
                case RewindFormat.SETTING_TYPE_F32:
                    codec.settingFloat(KEYS[i], floats[i]);
                    lastFloats[i] = floats[i];
                    break;
                case RewindFormat.SETTING_TYPE_STRING:
                    codec.settingString(KEYS[i], strings[i]);
                    lastStrings[i] = strings[i];
                    break;
                default:
                    codec.settingInt(KEYS[i], ints[i]);
                    lastInts[i] = ints[i];
            }
        }
        written = true;
        return any ? codec.endSettings() : 0;
    }

    private boolean same(int i) {
        switch (type[i]) {
            case RewindFormat.SETTING_TYPE_F32:
                return Float.floatToIntBits(floats[i]) == Float.floatToIntBits(lastFloats[i]);
            case RewindFormat.SETTING_TYPE_STRING: {
                String a = strings[i];
                String b = lastStrings[i];
                return a == b || (a != null && a.equals(b));
            }
            default:
                return ints[i] == lastInts[i];
        }
    }

    // ------------------------------------------------------------------ value setters by key

    private static int index(int key) {
        int i = key >= 0 && key < INDEX.length ? INDEX[key] : -1;
        if (i < 0) {
            throw new IllegalStateException("setting key " + key + " is not in the snapshot");
        }
        return i;
    }

    private void i(int key, int v) {
        ints[index(key)] = v;
    }

    private void b(int key, boolean v) {
        ints[index(key)] = v ? 1 : 0;
    }

    private void f(int key, float v) {
        floats[index(key)] = v;
    }

    private void s(int key, String v) {
        strings[index(key)] = v;
    }

    private void hud(ClientSettings cfg, int item, boolean enabled, boolean inGameOnly, int x, int y, int anchor,
                     float scale) {
        int has = HUD_FIELDS_OF[item - 1];
        if ((has & F_ENABLED) != 0) {
            b(RewindFormat.settingHudKey(item, RewindFormat.HUD_FIELD_ENABLED), enabled);
        }
        if ((has & F_IN_GAME_ONLY) != 0) {
            b(RewindFormat.settingHudKey(item, RewindFormat.HUD_FIELD_IN_GAME_ONLY), inGameOnly);
        }
        i(RewindFormat.settingHudKey(item, RewindFormat.HUD_FIELD_X), x);
        i(RewindFormat.settingHudKey(item, RewindFormat.HUD_FIELD_Y), y);
        i(RewindFormat.settingHudKey(item, RewindFormat.HUD_FIELD_ANCHOR), anchor);
        f(RewindFormat.settingHudKey(item, RewindFormat.HUD_FIELD_SCALE), scale);
        String id = RewindFormat.hudModuleName(item);
        b(RewindFormat.settingHudKey(item, RewindFormat.HUD_FIELD_BACKGROUND), HudBackground.on(cfg.hudBackgrounds, id));
        Map<String, float[]> sub = cfg.hudSubPixel;
        float[] frac = sub == null ? null : sub.get(id);
        boolean has2 = frac != null && frac.length == 2;
        f(RewindFormat.settingHudKey(item, RewindFormat.HUD_FIELD_SUB_X), has2 ? frac[0] : 0f);
        f(RewindFormat.settingHudKey(item, RewindFormat.HUD_FIELD_SUB_Y), has2 ? frac[1] : 0f);
    }

    private void options(int item, boolean brackets, int label) {
        b(RewindFormat.settingHudKey(item, RewindFormat.HUD_FIELD_OPTION_A), brackets);
        i(RewindFormat.settingHudKey(item, RewindFormat.HUD_FIELD_OPTION_B), label);
    }
}
