package com.bedwarsqol.config;

import com.bedwarsqol.gui.render.GuiTheme;
import com.bedwarsqol.stats.BackendDefaults;
import com.bedwarsqol.stats.BackendTarget;
import org.lwjgl.input.Keyboard;

import java.util.Locale;

public class ClientSettings {

    /**
     * Stamp of the last {@link #migrate} pass. Gson leaves an absent member at its initialiser, so
     * a file written before the stamp existed reads 0 and migrates exactly once; a fresh instance
     * migrates as a no-op. Older builds ignore the key.
     */
    static final int CURRENT_SETTINGS_VERSION = 1;
    public int settingsVersion = 0;

    public int defaultTextSize = 1;
    /** Global Text/Image style for every HUD element that supports it. 0 = text, 1 = icons + numbers. */
    public int hudDisplayMode = 1;
    /** Font for HUD text. 0 = modern (bundled Inter atlas), 1 = vanilla Minecraft font. */
    public int hudFont = 0;
    /** Size of the settings GUI panel. 0 = small, 1 = medium, 2 = large. */
    public int guiSize = 2;
    /** GUI accent color token: orange (default) / red / blue / green. Drives only the settings-GUI accent; HUD stays neutral. */
    public String guiAccent = "orange";

    public boolean potionStatusEnabled = false;
    /** Only render this HUD while in an active BedWars game (off = render everywhere). */
    public boolean potionInGameOnly = false;
    public int potionHudX = 5;
    public int potionHudY = 5;
    public int potionHudAnchor = 0;
    public float potionHudScale = 1.0f;
    /** Draw a modern translucent panel behind this HUD element. */
    public boolean potionBackgroundEnabled = false;

    public boolean armorTypeEnabled = false;
    public boolean armorInGameOnly = false;
    public int armorHudX = 5;
    public int armorHudY = 34;
    public int armorHudAnchor = 0;
    public float armorHudScale = 1.0f;

    // --- BedWars HUDs (only render in an active BedWars game) ---

    public boolean inventoryHudEnabled = false;
    public boolean inventoryInGameOnly = false;
    public int inventoryHudX = 5;
    public int inventoryHudY = 5;
    public int inventoryHudAnchor = 6; // bottom-left by default
    public float inventoryHudScale = 1.0f;
    public boolean inventoryBackgroundEnabled = false;

    // One toggle controls both gen timers; each stays independently draggable below.
    public boolean genTimersEnabled = false;
    /** One shared toggle: draws a matching panel behind BOTH the diamond and emerald timer boxes. */
    public boolean genTimersBackgroundEnabled = false;
    public int diamondTimerHudX = -5; // right-anchored: negative keeps the box inside the screen
    public int diamondTimerHudY = 5;
    public int diamondTimerHudAnchor = 2; // top-right by default
    public float diamondTimerHudScale = 1.0f;

    public int emeraldTimerHudX = -5;
    public int emeraldTimerHudY = 27;
    public int emeraldTimerHudAnchor = 2;
    public float emeraldTimerHudScale = 1.0f;

    public boolean keystrokesEnabled = false;
    public boolean keystrokesInGameOnly = false;
    public int keystrokesHudX = -10;
    public int keystrokesHudY = -20;
    public int keystrokesHudAnchor = 8; // bottom-right by default
    public float keystrokesHudScale = 1.0f;

    /** Session Stats: Lunar-layout game + session tally; always drawn on its panel (no Background toggle). */
    public boolean sessionStatsEnabled = false;
    public boolean sessionStatsInGameOnly = false;
    public int sessionStatsHudX = -5;
    public int sessionStatsHudY = 60;
    public int sessionStatsHudAnchor = 2; // top-right, under the gen timers
    public float sessionStatsHudScale = 1.0f;

    /** Height Limit: Lunar-style map name, build limit (highest placeable Y) and blocks left below it. */
    public boolean heightLimitEnabled = false;
    public boolean heightLimitInGameOnly = false;
    public int heightLimitHudX = 5;
    public int heightLimitHudY = 5;
    public int heightLimitHudAnchor = 3; // middle-left
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
     * Which gamemode's FKDR the in-chat Chat Stats bracket shows. {@code "auto"} follows the detected
     * game mode (overall in the lobby); the fixed values {@code overall}/{@code solo}/{@code doubles}/
     * {@code threes}/{@code fours} always show that mode. Set with {@code /bw mode <...>}.
     */
    public String chatStatsMode = "auto";
    /** When in an active Bedwars game, broadcast one condensed sweat line to party chat once. */
    public boolean statsSweatReport = true;

    /** Auto GG: say "gg" in chat once each time a BedWars game ends. */
    public boolean autoGg = true;

    /** Party Join Alert: red "Party Joined" in chat when a premade team queues a 2s/3s/4s game. */
    public boolean partyJoinAlert = true;

    // --- Chat module ---

    /** Unlimited Chat: raise the vanilla 100-line chat history cap to 32,767 lines. */
    public boolean chatUnlimited = true;
    /**
     * Keep Chat History: stop the return to the main menu (server switches, disconnects) from wiping
     * chat within a session. F3+D still clears; nothing is written to disk.
     */
    public boolean chatKeepHistory = true;
    /**
     * Stack Spam Messages: collapse consecutive identical chat lines into one line with a gray (xN).
     * The counter is edited into the original line in place; absorbed repeats are still written to
     * the game log. Decorative lines (no letter or digit, e.g. Hypixel's separator bars) never stack.
     */
    public boolean chatStackSpam = true;
    /** Sub of Stack Spam: only stack when the repeat arrives within {@link #chatStackWindowSec} of the last. */
    public boolean chatStackTimeBased = true;
    /** Seconds a line stays stackable when time-based stacking is on (1-30). */
    public float chatStackWindowSec = 5.0f;
    /** Sub of Stack Spam: whitespace-only lines never stack and never break a stacking chain. */
    public boolean chatStackIgnoreBlanks = true;
    /** Chat Notifications: master toggle for the chat-driven sound alerts below. */
    public boolean chatNotifications = true;
    /** Sub: pling when another player's typed message contains your name (whole word). */
    public boolean chatNotifyMention = true;
    /** Sub: double pling when a teammate or party member says "inc"/"incoming" in an active Bedwars game. */
    public boolean chatNotifyInc = true;
    /** Copy Chat: right-click a chat line while chat is open to copy the full message to the clipboard. */
    public boolean chatCopy = true;
    /**
     * Longer Messages: raise the 1.8.9 chat cap from 100 to 256 characters (the modern-vanilla limit)
     * while connected to Hypixel. Applies to typing, pasting, and every message the mod sends.
     * See {@link com.bedwarsqol.feature.ChatLengthLimit}.
     */
    public boolean chatLongMessages = true;
    /** Send INC keybind: the "Send /pc INC" key (Controls menu) sends /pc INC with a 2s cooldown. */
    public boolean pcIncKey = true;

    /**
     * Nick Utils: master toggle for the nicked-player module. Detects Hypixel-nicked players entirely
     * client-side (see {@link com.bedwarsqol.feature.NickUtils}). On by default; inert off Hypixel.
     */
    public boolean nickUtils = true;

    /**
     * Nick Notify (sub-setting of {@link #nickUtils}): print "&lt;name&gt; is Nicked" once per nicked
     * player in the lobby/queue or game. On by default once Nick Utils is enabled.
     */
    public boolean nickNotify = true;

    /**
     * Auto Denick (sub-setting of {@link #nickUtils}): when a nick kept their own skin, decode the
     * Mojang-signed skin to reveal the real account and append "Real Name: &lt;realName&gt;". On by
     * default once Nick Utils is enabled.
     */
    public boolean autoDenick = true;

    /**
     * Urchin Tags: master toggle for community-reported blacklist tags from urchin.ws, resolved
     * server-side by the stats Worker (see {@link com.bedwarsqol.feature.UrchinAlert}). On by
     * default; inert off Hypixel. When off the mod causes zero Urchin traffic and shows no tags.
     */
    public boolean urchinTags = true;
    /** Sub of Urchin Tags: append the priority tag badge to the tab-list overlay. */
    public boolean urchinBadgeTab = true;
    /** Sub of Urchin Tags: one private chat line the first time a tagged player is seen each game. */
    public boolean urchinChatAlert = true;
    /** Sub of Urchin Tags: play a pling with the chat alert (cheater-type tags only). */
    public boolean urchinAlertSound = true;
    /** Sub of Urchin Tags: append the priority tag badge above the in-game nametag. */
    public boolean urchinBadgeNametag = true;

    /**
     * Seraph Tags: master toggle for the Seraph community blacklist/safelist provider (api.seraph.si),
     * resolved server-side by the stats Worker. On by default; inert off Hypixel. When off the mod
     * causes zero Seraph traffic and shows no tags. Independent of Urchin — either, both, or neither
     * may be enabled.
     */
    public boolean seraphTags = true;
    /** Sub of Seraph Tags: append the priority tag badge to the tab-list overlay. */
    public boolean seraphBadgeTab = true;
    /** Sub of Seraph Tags: one private chat line the first time a tagged player is seen each game. */
    public boolean seraphChatAlert = true;
    /** Sub of Seraph Tags: play a pling with the chat alert (blacklist tags only). */
    public boolean seraphAlertSound = true;
    /** Sub of Seraph Tags: append the priority tag badge above the in-game nametag. */
    public boolean seraphBadgeNametag = true;

    /**
     * Queue Tag Alert: in the Bedwars <b>pregame queue only</b>, print one Urchin/Seraph cheater-tag
     * line for a player who <b>types</b> in chat (the queue's tab list is anonymized, so nobody else
     * can be checked). On by default; inert off Hypixel — each new name costs an outbound provider
     * lookup, capped per queue (see {@link com.bedwarsqol.feature.QueueAlert}). Needs
     * {@link #urchinTags} and/or {@link #seraphTags} plus a configured stats backend; disabled
     * providers are never queried.
     */
    public boolean queueTagAlert = true;

    /**
     * Queue Nick Alert: in the Bedwars <b>pregame queue only</b>, print one line when a player who
     * <b>types</b> in chat has no Mojang account (i.e. is nicked). On by default; inert off Hypixel —
     * each new name costs an outbound name resolution, capped per queue. Independent of Nick Utils,
     * which cannot see the queue's anonymized tab list.
     */
    public boolean queueNickAlert = true;

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

    /**
     * Suppress the hardcoded Esc -> pause-menu open while in-world, so an accidental tap in combat no
     * longer opens the menu (or fumbles onward into Options/Language). Esc still closes open screens;
     * the menu can be reopened via the "Open Game Menu" KeyBinding in Minecraft's Controls menu.
     */
    public boolean suppressEscMenu = false;

    // --- Visual / gameplay tweaks ---

    /** Custom highlight on the block you're looking at. */
    public boolean blockOverlayEnabled = false;
    public int blockOverlayColor = 0x804A90E2;  // ARGB
    public int blockOverlayStyle = 2;           // 0 = outline, 1 = fill, 2 = both
    public boolean blockOverlaySeeThrough = false;
    public float blockOverlayLineWidth = 2.0f;

    /** Center-screen countdown for nearby primed TNT. */
    public boolean tntFuseEnabled = false;
    public int tntFuseRadius = 10;

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
     * </ul>
     */
    void migrate() {
        if (settingsVersion < 1) {
            if (sessionStatsHudAnchor == 2 && sessionStatsHudX == 5) sessionStatsHudX = -5;
            if (diamondTimerHudAnchor == 2 && diamondTimerHudX == 5) diamondTimerHudX = -5;
            if (emeraldTimerHudAnchor == 2 && emeraldTimerHudX == 5) emeraldTimerHudX = -5;
        }
        settingsVersion = CURRENT_SETTINGS_VERSION;
    }

    public void sanitize() {
        migrate();
        defaultTextSize = clamp(defaultTextSize, 0, 2);
        hudDisplayMode = clamp(hudDisplayMode, 0, 1);
        hudFont = clamp(hudFont, 0, 1);
        guiSize = clamp(guiSize, 0, 2);
        guiAccent = GuiTheme.normalizeToken(guiAccent);
        potionHudAnchor = clamp(potionHudAnchor, 0, 8);
        armorHudAnchor = clamp(armorHudAnchor, 0, 8);
        if (potionHudScale < 0.3f || potionHudScale > 10.0f) potionHudScale = defaultTextSizeScale();
        if (armorHudScale < 0.3f || armorHudScale > 10.0f) armorHudScale = defaultTextSizeScale();

        inventoryHudAnchor = clamp(inventoryHudAnchor, 0, 8);
        if (inventoryHudScale < 0.3f || inventoryHudScale > 10.0f) inventoryHudScale = defaultTextSizeScale();
        diamondTimerHudAnchor = clamp(diamondTimerHudAnchor, 0, 8);
        if (diamondTimerHudScale < 0.3f || diamondTimerHudScale > 10.0f) diamondTimerHudScale = defaultTextSizeScale();
        emeraldTimerHudAnchor = clamp(emeraldTimerHudAnchor, 0, 8);
        if (emeraldTimerHudScale < 0.3f || emeraldTimerHudScale > 10.0f) emeraldTimerHudScale = defaultTextSizeScale();
        keystrokesHudAnchor = clamp(keystrokesHudAnchor, 0, 8);
        if (keystrokesHudScale < 0.3f || keystrokesHudScale > 10.0f) keystrokesHudScale = defaultTextSizeScale();
        sessionStatsHudAnchor = clamp(sessionStatsHudAnchor, 0, 8);
        if (sessionStatsHudScale < 0.3f || sessionStatsHudScale > 10.0f) sessionStatsHudScale = defaultTextSizeScale();
        heightLimitHudAnchor = clamp(heightLimitHudAnchor, 0, 8);
        if (heightLimitHudScale < 0.3f || heightLimitHudScale > 10.0f) heightLimitHudScale = defaultTextSizeScale();
        scoreboardSize = clamp(scoreboardSize, 0, 2);
        styledTabListSize = clamp(styledTabListSize, 0, 2);

        blockOverlayStyle = clamp(blockOverlayStyle, 0, 2);
        if (blockOverlayLineWidth < 0.5f || blockOverlayLineWidth > 10.0f) blockOverlayLineWidth = 2.0f;
        tntFuseRadius = clamp(tntFuseRadius, 1, 64);
        chatStackWindowSec = clampf(chatStackWindowSec, 1.0f, 30.0f);

        handPosX = clampf(handPosX, -1.0f, 1.0f);
        handPosY = clampf(handPosY, -1.0f, 1.0f);
        handPosZ = clampf(handPosZ, -1.0f, 1.0f);
        handScale = clampf(handScale, 0.5f, 2.0f);

        chatStatsMode = normalizeChatStatsMode(chatStatsMode);

        if (statsBackendUrl == null) statsBackendUrl = "";
        statsBackendUrl = statsBackendUrl.trim();
        if (statsBackendToken == null) statsBackendToken = "";
        statsBackendToken = statsBackendToken.trim();
        if (settingsKeyCode < 0) settingsKeyCode = Keyboard.KEY_RSHIFT;
    }

    /** Coerce {@link #chatStatsMode} to a known token, defaulting anything unrecognised to "auto". */
    private static String normalizeChatStatsMode(String mode) {
        if (mode == null) return "auto";
        switch (mode.trim().toLowerCase(Locale.US)) {
            case "overall": return "overall";
            case "solo": return "solo";
            case "doubles": return "doubles";
            case "threes": return "threes";
            case "fours": return "fours";
            default: return "auto";
        }
    }

    public float defaultTextSizeScale() {
        switch (defaultTextSize) {
            case 0: return 0.75f;
            case 2: return 1.5f;
            default: return 1.0f;
        }
    }

    public void applyDefaultTextSize() {
        float scale = defaultTextSizeScale();
        potionHudScale = scale;
        armorHudScale = scale;
        inventoryHudScale = scale;
        diamondTimerHudScale = scale;
        emeraldTimerHudScale = scale;
        keystrokesHudScale = scale;
        sessionStatsHudScale = scale;
        heightLimitHudScale = scale;
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
