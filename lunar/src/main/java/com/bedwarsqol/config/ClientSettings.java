package com.bedwarsqol.config;

import com.bedwarsqol.feature.SessionHoldKey;
import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.hud.HudPlacement;
import com.bedwarsqol.stats.BackendDefaults;
import com.bedwarsqol.stats.BackendTarget;
import com.bedwarsqol.stats.StatsBackendUrl;
import com.bedwarsqol.stats.StatsMode;
import org.lwjgl.input.Keyboard;

public class ClientSettings {

    /**
     * Stamp of the last {@link #migrate} pass. Gson leaves an absent member at its initialiser, so
     * a file written before the stamp existed reads 0 and migrates exactly once; a fresh instance
     * migrates as a no-op. Older builds ignore the key.
     */
    static final int CURRENT_SETTINGS_VERSION = 3;
    public int settingsVersion = 0;

    /**
     * HUD Size: 0 = small, 1 = medium, 2 = large. Changing it rescales every module (see
     * {@link #setHudSize}); a reset module gets this size.
     */
    public int defaultTextSize = 1;
    /** Size of the settings GUI panel. 0 = small, 1 = medium, 2 = large. */
    public int guiSize = 2;
    /** GUI accent color token, pale-blue by default (see {@link GuiTheme.Accent}). Drives only the settings-GUI accent; HUD stays neutral. */
    public String guiAccent = "pale-blue";
    /** Font of the settings menu and the HUD: {@code minecraft} (the game's own, the default) or {@code modern} (Inter). */
    public String guiFont = GuiTheme.FONT_MINECRAFT;

    // --- BedWars HUDs (render everywhere unless their In Game Only is set) ---

    public boolean inventoryHudEnabled = false;
    public boolean inventoryInGameOnly = false;
    public int inventoryHudX = 0;
    public int inventoryHudY = 0;
    public int inventoryHudAnchor = HudPlacement.AUTO; // the default layout until moved
    public float inventoryHudScale = 1.0f;

    // One toggle controls both gen timers; each stays independently draggable below.
    public boolean genTimersEnabled = false;
    /**
     * Only render the gen timers while in an active BedWars game (off = render everywhere, reading
     * "--" until a game supplies spawn times, so a player can see the HUD is on).
     */
    public boolean genTimersInGameOnly = false;
    public int diamondTimerHudX = 0;
    public int diamondTimerHudY = 0;
    public int diamondTimerHudAnchor = HudPlacement.AUTO; // the default layout until moved
    public float diamondTimerHudScale = 1.0f;

    public int emeraldTimerHudX = 0;
    public int emeraldTimerHudY = 0;
    public int emeraldTimerHudAnchor = HudPlacement.AUTO; // the default layout until moved
    public float emeraldTimerHudScale = 1.0f;

    /** Session Stats: Lunar-layout game + session tally; always drawn on its panel (no Background toggle). */
    public boolean sessionStatsEnabled = false;
    /** Hold Key to Show: the HUD stays hidden until {@link #sessionStatsKeyCode} is held. */
    public boolean sessionStatsHoldKey = false;
    /** Session Stats key: an LWJGL key code, a mouse button as button - 100, or 0 for unbound. */
    public int sessionStatsKeyCode = 0;
    public int sessionStatsHudX = 0;
    public int sessionStatsHudY = 0;
    public int sessionStatsHudAnchor = HudPlacement.AUTO; // the default layout until moved
    public float sessionStatsHudScale = 1.0f;

    /** Height Limit: Lunar-style map name, build limit (highest placeable Y) and blocks left below it. */
    public boolean heightLimitEnabled = false;
    public boolean heightLimitInGameOnly = false;
    public int heightLimitHudX = 0;
    public int heightLimitHudY = 0;
    public int heightLimitHudAnchor = HudPlacement.AUTO; // the default layout until moved
    public float heightLimitHudScale = 1.0f;

    /** On by default; every Hypixel-tab feature is inert unless connected to Hypixel. */
    public boolean playerStats = true;
    /** Nametag/tab stat overlays no longer have toggles — forced on with Player Stats (see sanitize). */
    public boolean playerStatsNametag = true;
    public boolean playerStatsTab = true;
    public boolean playerStatsShowRank = true;
    /** Hovering a player's name in chat appends their BedWars stats to the hover card (lobby/queue/game). */
    public boolean playerStatsChatHover = true;
    /** Chat Stats: prepend the sender's FKDR (or [New] for a never-played account) to their chat lines. */
    public boolean playerStatsChat = true;
    /**
     * The ONE stats display mode, for every surface that shows a player's Bedwars numbers (chat
     * bracket, hover card, tab list, nametags, denick line, launcher overlay).
     * {@code "auto"} follows the detected game mode (overall in the lobby); the fixed values
     * {@code overall}/{@code solo}/{@code doubles}/{@code threes}/{@code fours} always show that mode,
     * stamped on each number. Set with {@code /bw mode <...>}; vocabulary in
     * {@link com.bedwarsqol.stats.StatsMode}. The JSON key keeps its original chat-era name so an
     * existing file still loads.
     */
    public String chatStatsMode = "auto";
    /** When in an active Bedwars game, broadcast one condensed sweat line to party chat once. */
    public boolean statsSweatReport = true;

    /** Party Join Alert: red "Party Joined" in chat when a premade team queues a 2s/3s/4s game. */
    public boolean partyJoinAlert = true;

    // --- Chat module (Lunar keeps only the two inc pieces; Lunar Client ships the generic chat QOL
    // natively, so Unlimited/Keep History/Stack Spam/Copy Chat/mention sound live in the Forge tree only) ---

    /**
     * Inc Alert: double pling when a teammate or party member says "inc"/"incoming" in an active
     * Bedwars game. Standalone master here; the Forge tree nests it under Chat Notifications.
     */
    public boolean chatNotifyInc = true;
    /** Send INC keybind: the "Send /pc INC" key (Controls menu) sends /pc INC with a 2s cooldown. */
    public boolean pcIncKey = true;
    /** Key code for the Send /pc INC key, echoed from the Controls menu rebind. Default unbound. */
    public int pcIncKeyCode = Keyboard.KEY_NONE;

    /**
     * Nick Utils: master toggle for the nicked-player module. Detects Hypixel-nicked players entirely
     * client-side (see {@link com.bedwarsqol.feature.NickUtils}). On by default; inert off Hypixel.
     */
    public boolean nickUtils = true;

    /**
     * Nick Notify (sub-setting of {@link #nickUtils}): print "&lt;name&gt; is Nicked" once per nicked
     * player in the lobby/queue or game, and in the pregame queue for a player who types under a nick
     * (see {@link com.bedwarsqol.feature.QueueAlert}). On by default once Nick Utils is enabled.
     */
    public boolean nickNotify = true;

    /**
     * Auto Denick (sub-setting of {@link #nickUtils}): when a nick kept their own skin, decode the
     * Mojang-signed skin to reveal the real account and append "Real Name: &lt;realName&gt;". On by
     * default once Nick Utils is enabled.
     */
    public boolean autoDenick = true;

    /**
     * Tag Utils: master toggle for community cheater tags from Urchin (urchin.ws) and Seraph
     * (api.seraph.si), both resolved server-side by the stats Worker (see
     * {@link com.bedwarsqol.feature.UrchinAlert} / {@link com.bedwarsqol.feature.SeraphAlert}). On by
     * default; inert off Hypixel. Off, or with both sources off, the mod causes zero provider traffic
     * and shows no tags. Read the sources through {@link #urchinOn()} / {@link #seraphOn()}.
     */
    public boolean tagUtils = true;
    /** Sub of Tag Utils: the Urchin source. The key predates Tag Utils, so a saved choice carries over. */
    public boolean urchinTags = true;
    /** Sub of Tag Utils: the Seraph source. The key predates Tag Utils, so a saved choice carries over. */
    public boolean seraphTags = true;
    /** Sub of Tag Utils: append the priority tag badge to the tab-list overlay. */
    public boolean tagBadgeTab = true;
    /** Sub of Tag Utils: append the priority tag badge above the in-game nametag. */
    public boolean tagBadgeNametag = true;
    /**
     * Sub of Tag Utils: one private chat line the first time a tagged player is seen each game, and in
     * the pregame queue for a player who types (see {@link com.bedwarsqol.feature.QueueAlert}).
     */
    public boolean tagChatAlert = true;
    /** Sub of Tag Utils: play a pling with the chat alert (cheater-type tags only). */
    public boolean tagAlertSound = true;

    // The per-source sub-options saved before settings v2. migrate() reads them once and nulls them;
    // Gson never writes a null member, so they drop on the next save.
    Boolean urchinBadgeTab, urchinChatAlert, urchinAlertSound, urchinBadgeNametag;
    Boolean seraphBadgeTab, seraphChatAlert, seraphAlertSound, seraphBadgeNametag;

    /** Urchin is looked up and shown only while Tag Utils and its Urchin sub-option are both on. */
    public boolean urchinOn() {
        return tagUtils && urchinTags;
    }

    /** Seraph is looked up and shown only while Tag Utils and its Seraph sub-option are both on. */
    public boolean seraphOn() {
        return tagUtils && seraphTags;
    }

    // A backend may be baked into the jar at build time via the cobblify-backend.properties
    // resource (see BackendDefaults / the generateBackendProperties Gradle task) — never commit a
    // real URL or token here. These fields hold only user overrides, set via
    // /cobblify statsurl <url> and (optionally) /cobblify statstoken <token>; the baked pair is
    // never written into them, so it is never persisted to cobblify.json, and the baked token
    // never pairs with a non-baked URL (see backendTarget()).
    /** No default in the field: empty = fall back to the baked backend, if any. */
    public static final String DEFAULT_STATS_BACKEND_URL = "";
    /** User-override base URL of the stats Worker. Empty = use the baked backend (stats disabled
     *  when none is baked either). */
    public String statsBackendUrl = DEFAULT_STATS_BACKEND_URL;
    /** No default token. */
    public static final String DEFAULT_STATS_BACKEND_TOKEN = "";
    /** Optional user secret sent as the {@code X-BedwarsQol-Token} header, matching the Worker's
     *  STATS_TOKEN secret. Only ever accompanies a user-set URL. Empty = send no token. */
    public String statsBackendToken = DEFAULT_STATS_BACKEND_TOKEN;

    /** The URL/token pair to use for the stats backend, resolved atomically.
     *  User-set URL wins and is only ever paired with the user's own token;
     *  the baked token is only ever paired with the baked URL. */
    public BackendTarget backendTarget() {
        String u = statsBackendUrl == null ? "" : statsBackendUrl.trim();
        if (!u.isEmpty()) return new BackendTarget(u, statsBackendToken == null ? "" : statsBackendToken.trim());
        return new BackendTarget(BackendDefaults.url(), BackendDefaults.token());
    }

    public int settingsKeyCode = Keyboard.KEY_RSHIFT;

    /** Rebindable key that opens the vanilla pause menu (for when "Disable Esc Menu" is on). Default unbound. */
    public int pauseKeyCode = Keyboard.KEY_NONE;

    /**
     * Suppress the hardcoded Esc -> pause-menu open while in-world, so an accidental tap in combat no
     * longer opens the menu (or fumbles onward into Options/Language). Esc still closes open screens;
     * the menu can be reopened via the "Open Game Menu" KeyBinding in Minecraft's Controls menu.
     */
    public boolean suppressEscMenu = false;

    // --- Visual / gameplay tweaks ---

    /** First-person held-item position offset (X/Y/Z, eye space) + size scale, gated by the master toggle. */
    public boolean handPositionEnabled = false;
    public float handPosX = 0f;
    public float handPosY = 0f;
    public float handPosZ = 0f;
    public float handScale = 1.0f;

    /** Vanilla scoreboard sidebar size. 0 = small, 1 = medium, 2 = large (the original full size). */
    public int scoreboardSize = 2;

    /** Vanilla tab player list size. 0 = small, 1 = medium, 2 = large (the original full size). */
    public int styledTabListSize = 2;
    /** Hide the server-sent header/footer text above and below the tab player list. */
    public boolean tabHideHeaderFooter = false;

    /**
     * One-time config upgrades, stamped by {@link #settingsVersion}.
     * <ul>
     * <li>v1: the right-anchored HUDs (session stats, diamond/emerald timers) shipped with
     * {@code X = +5}, which places the box 5 GUI px past the right screen edge. A box still at the
     * shipped value on the top-right anchor moves to {@code -5}; anything the user moved is kept.</li>
     * <li>v2: Tag Utils replaced the Urchin Tags and Seraph Tags modules. It starts on if either source
     * was on, and the sources keep their own keys. Each shared sub-option starts on if it was on for a
     * source that was on (for either source when neither was).</li>
     * <li>v3: HUD modules get a default layout worked out from their sizes, so they never overlap at
     * any HUD Size. A module still at its exact old default spot moves into it; anything the user
     * moved is kept.</li>
     * </ul>
     */
    void migrate() {
        if (settingsVersion < 1) {
            if (sessionStatsHudAnchor == 2 && sessionStatsHudX == 5) sessionStatsHudX = -5;
            if (diamondTimerHudAnchor == 2 && diamondTimerHudX == 5) diamondTimerHudX = -5;
            if (emeraldTimerHudAnchor == 2 && emeraldTimerHudX == 5) emeraldTimerHudX = -5;
        }
        if (settingsVersion < 2) {
            boolean urchin = urchinTags, seraph = seraphTags;
            tagUtils = urchin || seraph;
            tagBadgeTab = carried(urchin, urchinBadgeTab, seraph, seraphBadgeTab);
            tagBadgeNametag = carried(urchin, urchinBadgeNametag, seraph, seraphBadgeNametag);
            tagChatAlert = carried(urchin, urchinChatAlert, seraph, seraphChatAlert);
            tagAlertSound = carried(urchin, urchinAlertSound, seraph, seraphAlertSound);
        }
        if (settingsVersion < 3) {
            if (atDefault(inventoryHudX, inventoryHudY, inventoryHudAnchor, 5, 5, 6)) inventoryHudAnchor = HudPlacement.AUTO;
            if (atDefault(diamondTimerHudX, diamondTimerHudY, diamondTimerHudAnchor, -5, 5, 2)) diamondTimerHudAnchor = HudPlacement.AUTO;
            if (atDefault(emeraldTimerHudX, emeraldTimerHudY, emeraldTimerHudAnchor, -5, 27, 2)) emeraldTimerHudAnchor = HudPlacement.AUTO;
            if (atDefault(sessionStatsHudX, sessionStatsHudY, sessionStatsHudAnchor, -5, 60, 2)) sessionStatsHudAnchor = HudPlacement.AUTO;
            if (atDefault(heightLimitHudX, heightLimitHudY, heightLimitHudAnchor, 5, 5, 3)) heightLimitHudAnchor = HudPlacement.AUTO;
        }
        urchinBadgeTab = urchinChatAlert = urchinAlertSound = urchinBadgeNametag = null;
        seraphBadgeTab = seraphChatAlert = seraphAlertSound = seraphBadgeNametag = null;
        settingsVersion = CURRENT_SETTINGS_VERSION;
    }

    private static boolean atDefault(int x, int y, int anchor, int oldX, int oldY, int oldAnchor) {
        return x == oldX && y == oldY && anchor == oldAnchor;
    }

    /**
     * One v1 per-source sub-option, carried into its shared v2 one. Only the sources that were on
     * count, unless neither was; an absent value is the old default, on.
     */
    private static boolean carried(boolean urchinWasOn, Boolean urchin, boolean seraphWasOn, Boolean seraph) {
        boolean u = urchin == null || urchin;
        boolean s = seraph == null || seraph;
        if (!urchinWasOn && !seraphWasOn) return u || s;
        return (urchinWasOn && u) || (seraphWasOn && s);
    }

    public void sanitize() {
        migrate();
        defaultTextSize = clamp(defaultTextSize, 0, 2);
        guiSize = clamp(guiSize, 0, 2);
        guiAccent = GuiTheme.normalizeToken(guiAccent);
        guiFont = GuiTheme.normalizeFont(guiFont);

        inventoryHudAnchor = clamp(inventoryHudAnchor, HudPlacement.AUTO, 8);
        if (inventoryHudScale < 0.3f || inventoryHudScale > 10.0f) inventoryHudScale = defaultTextSizeScale();
        diamondTimerHudAnchor = clamp(diamondTimerHudAnchor, HudPlacement.AUTO, 8);
        if (diamondTimerHudScale < 0.3f || diamondTimerHudScale > 10.0f) diamondTimerHudScale = defaultTextSizeScale();
        emeraldTimerHudAnchor = clamp(emeraldTimerHudAnchor, HudPlacement.AUTO, 8);
        if (emeraldTimerHudScale < 0.3f || emeraldTimerHudScale > 10.0f) emeraldTimerHudScale = defaultTextSizeScale();
        sessionStatsHudAnchor = clamp(sessionStatsHudAnchor, HudPlacement.AUTO, 8);
        if (!SessionHoldKey.isValidBinding(sessionStatsKeyCode)) sessionStatsKeyCode = 0;
        if (sessionStatsHudScale < 0.3f || sessionStatsHudScale > 10.0f) sessionStatsHudScale = defaultTextSizeScale();
        heightLimitHudAnchor = clamp(heightLimitHudAnchor, HudPlacement.AUTO, 8);
        if (heightLimitHudScale < 0.3f || heightLimitHudScale > 10.0f) heightLimitHudScale = defaultTextSizeScale();
        scoreboardSize = clamp(scoreboardSize, 0, 2);
        styledTabListSize = clamp(styledTabListSize, 0, 2);

        handPosX = clampf(handPosX, -1.0f, 1.0f);
        handPosY = clampf(handPosY, -1.0f, 1.0f);
        handPosZ = clampf(handPosZ, -1.0f, 1.0f);
        handScale = clampf(handScale, 0.5f, 2.0f);

        chatStatsMode = StatsMode.normalize(chatStatsMode);

        if (statsBackendUrl == null) statsBackendUrl = "";
        // Only ever hold a URL the command would accept today. A build before the https-only rule
        // saved whatever was typed, and an http:// one would keep sending the token in clear on
        // every request; dropping it here falls back to the baked backend, or to none.
        statsBackendUrl = StatsBackendUrl.normalizeOrEmpty(statsBackendUrl);
        if (statsBackendToken == null) statsBackendToken = "";
        statsBackendToken = statsBackendToken.trim();
        // Mouse binds (-100 + button, from the Controls menu) are kept; see KeyCodes.
        settingsKeyCode = KeyCodes.sanitize(settingsKeyCode, Keyboard.KEY_RSHIFT);
        pauseKeyCode = KeyCodes.sanitize(pauseKeyCode, Keyboard.KEY_NONE);
        pcIncKeyCode = KeyCodes.sanitize(pcIncKeyCode, Keyboard.KEY_NONE);
    }

    /** Whether the menu and the HUD use Minecraft's font. */
    public boolean minecraftFont() {
        return GuiTheme.FONT_MINECRAFT.equals(guiFont);
    }

    public float defaultTextSizeScale() {
        switch (defaultTextSize) {
            case 0: return 0.75f;
            case 2: return 1.5f;
            default: return 1.0f;
        }
    }

    /**
     * Sets HUD Size and rescales every module by the same factor, so a module the player made bigger
     * or smaller than the rest stays that way.
     */
    public void setHudSize(int size) {
        float from = defaultTextSizeScale();
        defaultTextSize = clamp(size, 0, 2);
        float factor = defaultTextSizeScale() / from;
        inventoryHudScale = rescaled(inventoryHudScale, factor);
        diamondTimerHudScale = rescaled(diamondTimerHudScale, factor);
        emeraldTimerHudScale = rescaled(emeraldTimerHudScale, factor);
        sessionStatsHudScale = rescaled(sessionStatsHudScale, factor);
        heightLimitHudScale = rescaled(heightLimitHudScale, factor);
    }

    private static float rescaled(float scale, float factor) {
        float s = Math.round(scale * factor * 1000f) / 1000f;
        return Math.max(0.3f, Math.min(10f, s));
    }

    public void save() {
        sanitize();
        SettingsManager.save(this);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        return Math.min(value, max);
    }

    private static float clampf(float value, float min, float max) {
        if (value < min) return min;
        return Math.min(value, max);
    }
}
