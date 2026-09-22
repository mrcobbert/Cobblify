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

    /** The transition rule, and the only state; see {@link ModeLatch} for why it is not a bare field. */
    private static final ModeLatch LATCH = new ModeLatch();
    private int ticks;

    /** The current mode, latched for the game. {@link BedwarsMode#UNKNOWN} until known. */
    public static BedwarsMode current() {
        return LATCH.current();
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
        LATCH.reset();
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
            LATCH.reset();
            return;
        }
        // The pregame sidebar names the mode exactly; the active game has no such line, so a client
        // that never saw one counts nametag colours instead. Which of those applies - and why an
        // unmappable label has to be remembered rather than merely mapped to UNKNOWN - is
        // ModeLatch's business.
        LATCH.observe(sidebarModeLabel(), HypixelContext.isInActiveBedwarsGame(),
                BedwarsModeDetector::detectByTeamColors);
    }

    /**
     * The raw {@code Mode: <X>} value the pregame sidebar prints, or null when it has no such line
     * (the hub, and every active game). Mapping it to a {@link BedwarsMode} is the latch's job, so
     * that "no label" and "a label we do not map" stay distinguishable here.
     */
    private static String sidebarModeLabel() {
        Scoreboard board = scoreboard();
        if (board == null) return null;
        ScoreObjective sidebar = board.getObjectiveInDisplaySlot(1);
        if (sidebar == null) return null;

        for (Score score : board.getSortedScores(sidebar)) {
            ScorePlayerTeam team = board.getPlayersTeam(score.getPlayerName());
            String line = EnumChatFormatting.getTextWithoutFormattingCodes(
                    ScorePlayerTeam.formatPlayerName(team, score.getPlayerName()));
            if (line == null) continue;
            int i = line.indexOf("Mode:");
            if (i < 0) continue;
            return line.substring(i + 5).trim();
        }
        return null;
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
