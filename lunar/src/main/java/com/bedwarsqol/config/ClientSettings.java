package com.bedwarsqol.config;

import com.bedwarsqol.gui.render.GuiTheme;
import org.lwjgl.input.Keyboard;

import java.util.Locale;

public class ClientSettings {

    public int defaultTextSize = 1;
    /** Global Text/Image style for every HUD element that supports it. 0 = text, 1 = icons + numbers. */
    public int hudDisplayMode = 1;
    /** Font for HUD text. 0 = modern (bundled Inter atlas), 1 = vanilla Minecraft font. */
    public int hudFont = 0;
    /** Size of the settings GUI panel. 0 = small, 1 = medium, 2 = large. */
    public int guiSize = 2;
    /** GUI accent color token: orange (default) / red / blue / green. Drives only the settings-GUI accent; HUD stays neutral. */
    public String guiAccent = "orange";

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
    public int diamondTimerHudX = 5;
    public int diamondTimerHudY = 5;
    public int diamondTimerHudAnchor = 2; // top-right by default
    public float diamondTimerHudScale = 1.0f;

    public int emeraldTimerHudX = 5;
    public int emeraldTimerHudY = 27;
    public int emeraldTimerHudAnchor = 2;
    public float emeraldTimerHudScale = 1.0f;

    public boolean playerStats = false;
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
    public boolean statsSweatReport = false;

    /** Party Join Alert: red "Party Joined" in chat when a premade team queues a 2s/3s/4s game. */
    public boolean partyJoinAlert = false;

    /**
     * Chat Heads: draw a small player head immediately left of the sender's name on chat lines, using
     * the skin the client already shows for that player (nicks keep the nick skin). Works on any server.
     * Off by default. Draws only for senders present in the tab list (see
     * {@link com.bedwarsqol.feature.ChatPlayerHeads}).
     */
    public boolean chatPlayerHeads = false;

    // --- Chat module (Lunar keeps only the two inc pieces; Lunar Client ships the generic chat QOL
    // natively, so Unlimited/Keep History/Stack Spam/Copy Chat/mention sound live in the Forge tree only) ---

    /**
     * Inc Alert: double pling when a teammate or party member says "inc"/"incoming" in an active
     * Bedwars game. Standalone master here; the Forge tree nests it under Chat Notifications.
     */
    public boolean chatNotifyInc = false;
    /** Send INC keybind: the "Send /pc INC" key (Controls menu) sends /pc INC with a 2s cooldown. */
    public boolean pcIncKey = true;
    /** Key code for the Send /pc INC key, echoed from the Controls menu rebind. Default unbound. */
    public int pcIncKeyCode = Keyboard.KEY_NONE;

    /**
     * Nick Utils: master toggle for the nicked-player module. Detects Hypixel-nicked players entirely
     * client-side (see {@link com.bedwarsqol.feature.NickUtils}). Off by default.
     */
    public boolean nickUtils = false;

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
     * server-side by the stats Worker (see {@link com.bedwarsqol.feature.UrchinAlert}). Off by
     * default. When off the mod causes zero Urchin traffic and shows no tags.
     */
    public boolean urchinTags = false;
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
     * resolved server-side by the stats Worker. Off by default. When off the mod causes zero Seraph
     * traffic and shows no tags. Independent of Urchin — either, both, or neither may be enabled.
     */
    public boolean seraphTags = false;
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
     * can be checked). Off by default — each new name costs an outbound provider lookup, capped per
     * queue (see {@link com.bedwarsqol.feature.QueueAlert}). Needs {@link #urchinTags} and/or
     * {@link #seraphTags} plus a configured stats backend; disabled providers are never queried.
     */
    public boolean queueTagAlert = false;

    /**
     * Queue Nick Alert: in the Bedwars <b>pregame queue only</b>, print one line when a player who
     * <b>types</b> in chat has no Mojang account (i.e. is nicked). Off by default — each new name
     * costs an outbound name resolution, capped per queue. Independent of Nick Utils, which cannot
     * see the queue's anonymized tab list.
     */
    public boolean queueNickAlert = false;

    // Stats come from a Cloudflare Worker that each user self-hosts (see server/stats-worker). No
    // public backend is shipped — never commit a real URL or token here. Users set their own via
    // /bedwarsqol statsurl <url> and (optionally) /bedwarsqol statstoken <token>.
    /** No default backend: empty until the user points the mod at their own self-hosted Worker. */
    public static final String DEFAULT_STATS_BACKEND_URL = "";
    /** Base URL of the user's stats Worker. Empty = stats disabled until set. */
    public String statsBackendUrl = DEFAULT_STATS_BACKEND_URL;
    /** No default token. */
    public static final String DEFAULT_STATS_BACKEND_TOKEN = "";
    /** Optional secret sent as the {@code X-BedwarsQol-Token} header, matching the Worker's STATS_TOKEN
     *  secret. Empty = send no token (open backend). */
    public String statsBackendToken = DEFAULT_STATS_BACKEND_TOKEN;

    public int settingsKeyCode = Keyboard.KEY_RSHIFT;

    /** Rebindable key that opens the vanilla pause menu (for when "Disable Esc Menu" is on). Default unbound. */
    public int pauseKeyCode = Keyboard.KEY_NONE;

    /** Rebindable key (default unbound) that opens the Players tab; persisted here because Weave has no
     *  vanilla options.txt keybind persistence. */
    public int playersKeyCode = Keyboard.KEY_NONE;

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

    public void sanitize() {
        defaultTextSize = clamp(defaultTextSize, 0, 2);
        hudDisplayMode = clamp(hudDisplayMode, 0, 1);
        hudFont = clamp(hudFont, 0, 1);
        guiSize = clamp(guiSize, 0, 2);
        guiAccent = GuiTheme.normalizeToken(guiAccent);

        inventoryHudAnchor = clamp(inventoryHudAnchor, 0, 8);
        if (inventoryHudScale < 0.3f || inventoryHudScale > 10.0f) inventoryHudScale = defaultTextSizeScale();
        diamondTimerHudAnchor = clamp(diamondTimerHudAnchor, 0, 8);
        if (diamondTimerHudScale < 0.3f || diamondTimerHudScale > 10.0f) diamondTimerHudScale = defaultTextSizeScale();
        emeraldTimerHudAnchor = clamp(emeraldTimerHudAnchor, 0, 8);
        if (emeraldTimerHudScale < 0.3f || emeraldTimerHudScale > 10.0f) emeraldTimerHudScale = defaultTextSizeScale();
        scoreboardSize = clamp(scoreboardSize, 0, 2);
        styledTabListSize = clamp(styledTabListSize, 0, 2);

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
        if (pauseKeyCode < 0) pauseKeyCode = Keyboard.KEY_NONE;
        if (pcIncKeyCode < 0) pcIncKeyCode = Keyboard.KEY_NONE;
        if (playersKeyCode < 0) playersKeyCode = Keyboard.KEY_NONE;
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
        inventoryHudScale = scale;
        diamondTimerHudScale = scale;
        emeraldTimerHudScale = scale;
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
