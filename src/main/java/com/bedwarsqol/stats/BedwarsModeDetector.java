package com.bedwarsqol.stats;

import com.bedwarsqol.config.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Detects the current Bedwars team format (Solo / Doubles / 3s / 4s) from client state —
 * no commands sent.
 *
 * <p><b>Why not group players by scoreboard team?</b> Hypixel puts every player on their
 * own uniquely-named scoreboard team (to drive tab-list sort order and nametag color), so
 * teammates are <i>not</i> grouped — every team has size 1. The only place the exact mode
 * appears is the <b>pregame waiting-lobby sidebar</b>, which prints a {@code Mode: <X>}
 * line. We sample that on a client tick and latch it for the game you join.
 *
 * <p>If we miss the pregame (e.g. rejoining a game in progress), we fall back to grouping
 * players by their <b>nametag color</b> (the one shared team signal): distinct colors =
 * team count, players sharing your color = your team size. Anything we can't resolve stays
 * {@link BedwarsMode#UNKNOWN}, which callers treat as "use overall stats".
 */
public final class BedwarsModeDetector {

    private static final int TICK_INTERVAL = 10; // ~0.5s

    private static volatile BedwarsMode latched = BedwarsMode.UNKNOWN;
    private int ticks;

    /** The current mode, latched for the game. {@link BedwarsMode#UNKNOWN} until known. */
    public static BedwarsMode current() {
        return latched;
    }

    /**
     * The mode every stats DISPLAY uses: the user's forced {@code /bw mode} choice (all/solo/2s/3s/4s),
     * or — when that is {@code auto} — the live per-game {@link #current()} detection
     * ({@link BedwarsMode#UNKNOWN} in a lobby, which callers render as overall). Chat bracket, hover
     * card, tab list, nametags, the denick line, the Players page and the launcher overlay all resolve
     * through here so one command moves every surface together. Game LOGIC (generator timing, the sweat
     * report, the lobby export's sidebar label) keeps reading {@link #current()}: forcing a display mode
     * must not change what game the mod thinks it is in.
     */
    public static BedwarsMode displayMode(ClientSettings cfg) {
        BedwarsMode forced = cfg == null ? null : StatsMode.forced(cfg.chatStatsMode);
        return forced != null ? forced : current();
    }

    /**
     * True when the user has forced a display mode — the signal for a surface to stamp the mode its
     * numbers came from ({@code 4s …}, or {@code All …} after a fallback). Auto mode stamps nothing:
     * the numbers already match the game on screen.
     */
    public static boolean isForced(ClientSettings cfg) {
        return cfg != null && StatsMode.isForced(cfg.chatStatsMode);
    }

    public static void reset() {
        latched = BedwarsMode.UNKNOWN;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++ticks < TICK_INTERVAL) return;
        ticks = 0;
        update();
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        reset(); // each Hypixel game/lobby is a fresh world — don't carry a stale mode over.
    }

    private static void update() {
        if (!HypixelContext.isInBedwars()) {
            latched = BedwarsMode.UNKNOWN;
            return;
        }
        // Pregame sidebar names the mode exactly — prefer it and keep it fresh.
        BedwarsMode pregame = detectFromModeLine();
        if (pregame != BedwarsMode.UNKNOWN) {
            latched = pregame;
            return;
        }
        // Active game has no Mode: line. Keep the pregame value; only infer if we never got one.
        if (latched == BedwarsMode.UNKNOWN && HypixelContext.isInActiveBedwarsGame()) {
            BedwarsMode byColor = detectByTeamColors();
            if (byColor != BedwarsMode.UNKNOWN) latched = byColor;
        }
    }

    /** Read a {@code Mode: <Solo|Doubles|3v3v3v3|4v4v4v4>} line from the sidebar (pregame). */
    private static BedwarsMode detectFromModeLine() {
        Scoreboard board = scoreboard();
        if (board == null) return BedwarsMode.UNKNOWN;
        ScoreObjective sidebar = board.getObjectiveInDisplaySlot(1);
        if (sidebar == null) return BedwarsMode.UNKNOWN;

        for (Score score : board.getSortedScores(sidebar)) {
            ScorePlayerTeam team = board.getPlayersTeam(score.getPlayerName());
            String line = EnumChatFormatting.getTextWithoutFormattingCodes(
                    ScorePlayerTeam.formatPlayerName(team, score.getPlayerName()));
            if (line == null) continue;
            int i = line.indexOf("Mode:");
            if (i < 0) continue;
            String v = line.substring(i + 5).trim().toLowerCase();
            if (v.contains("solo")) return BedwarsMode.SOLO;
            if (v.contains("doubles")) return BedwarsMode.DOUBLES;
            if (v.contains("3v3v3v3")) return BedwarsMode.THREES;
            if (v.contains("4v4v4v4")) return BedwarsMode.FOURS;
            return BedwarsMode.UNKNOWN; // 4v4, Castle, or some dream mode we don't map → overall
        }
        return BedwarsMode.UNKNOWN;
    }

    /**
     * Fallback for joining mid-game: group tab players by nametag color. Distinct colors =
     * team count; players sharing the local player's color = your team size.
     */
    private static BedwarsMode detectByTeamColors() {
        Minecraft mc = Minecraft.getMinecraft();
        Scoreboard board = scoreboard();
        if (mc == null || board == null) return BedwarsMode.UNKNOWN;
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) return BedwarsMode.UNKNOWN;

        Map<Character, Integer> byColor = new HashMap<>();
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) continue;
            String name = info.getGameProfile().getName();
            if (name == null) continue;
            char color = teamColor(board, name);
            if (color == 0) continue;
            Integer prev = byColor.get(color);
            byColor.put(color, prev == null ? 1 : prev + 1);
        }

        char myColor = mc.thePlayer != null && mc.thePlayer.getGameProfile() != null
                ? teamColor(board, mc.thePlayer.getGameProfile().getName()) : 0;
        int teamCount = byColor.size();
        int mySize = myColor != 0 && byColor.containsKey(myColor) ? byColor.get(myColor) : 0;
        return BedwarsMode.fromTeams(teamCount, mySize);
    }

    /** The first Minecraft color code in a player's rendered nametag = their team color. */
    private static char teamColor(Scoreboard board, String name) {
        ScorePlayerTeam team = board.getPlayersTeam(name);
        if (team == null) return 0;
        String formatted = ScorePlayerTeam.formatPlayerName(team, name);
        if (formatted == null) return 0;
        for (int i = 0; i + 1 < formatted.length(); i++) {
            if (formatted.charAt(i) == '§') {
                char c = Character.toLowerCase(formatted.charAt(i + 1));
                if ("0123456789abcdef".indexOf(c) >= 0) return c;
            }
        }
        return 0;
    }

    private static Scoreboard scoreboard() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return null;
        return mc.theWorld.getScoreboard();
    }
}
