package com.bedwarsqol.feature;

import com.mojang.authlib.GameProfile;
import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.BedwarsMode;
import com.bedwarsqol.stats.BedwarsModeDetector;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.HypixelContext;
import com.bedwarsqol.stats.StatsCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ChatComponentText;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;
import net.weavemc.api.event.WorldEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Once per Bedwars game, after enemy/team stats have resolved, broadcasts a sweat rundown to party
 * chat via {@code /pc}: one line per team, sweatiest first (see {@link SweatReportText}), routed
 * through {@link OutgoingChat}. Delivery is acknowledged line by line by the coordinator —
 * cancelled reports may retry in the same active game without duplicate in-flight submits.
 */
public final class SweatReport {

    private static final boolean DEBUG = false;

    private static final int TICK_INTERVAL = 10;
    private static final int REARM_GRACE_SLOTS = 3;
    /** Give up waiting on unresolved players after this; they report as {@code ...} rather than stall. */
    private static final long MAX_WAIT_MS = 60_000;

    private int ticks;
    private long activeSinceMs;
    private int notActiveSlots;

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        rearm("world load");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.Post event) {
        if (++ticks < TICK_INTERVAL) return;
        ticks = 0;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;

        boolean inActive = HypixelContext.isOnHypixel() && HypixelContext.isInActiveBedwarsGame();
        if (!inActive) {
            if (++notActiveSlots >= REARM_GRACE_SLOTS) rearm("left active game");
            return;
        }
        notActiveSlots = 0;

        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.playerStats || !cfg.statsSweatReport) return;

        OutgoingChatCore.SweatFlight flight = OutgoingChat.get().sweatFlight();
        if (flight == OutgoingChatCore.SweatFlight.IN_FLIGHT
                || flight == OutgoingChatCore.SweatFlight.DONE) {
            return;
        }
        if (flight == OutgoingChatCore.SweatFlight.RETRYABLE) {
            // Resumes a partly-sent report, or re-arms us to rebuild it on a later slot.
            OutgoingChat.get().consumeSweatRetry();
            return;
        }

        if (activeSinceMs == 0L) activeSinceMs = System.currentTimeMillis();

        NetHandlerPlayClient net = mc.getNetHandler();
        Scoreboard board = mc.theWorld != null ? mc.theWorld.getScoreboard() : null;
        if (net == null || board == null) return;

        char myColor = mc.thePlayer.getGameProfile() != null
                ? teamColor(board, mc.thePlayer.getGameProfile().getName()) : 0;
        List<NetworkPlayerInfo> enemies = collectEnemies(net, board, myColor);
        List<NetworkPlayerInfo> team = collectTeam(net, board, myColor);
        boolean timedOut = System.currentTimeMillis() - activeSinceMs > MAX_WAIT_MS;

        if (enemies.isEmpty() && !timedOut) return;

        int resolved = 0;
        int needed = enemies.size() + team.size();
        for (NetworkPlayerInfo info : enemies) {
            if (countResolved(info)) resolved++;
        }
        for (NetworkPlayerInfo info : team) {
            if (countResolved(info)) resolved++;
        }
        if (resolved < needed && !timedOut) return;

        List<String> lines = SweatReportText.lines(buildTeams(enemies, team, board, myColor));
        if (DEBUG) {
            System.out.println("[BedwarsQol][SweatReport] fire attempt: " + lines.size() + " line(s), "
                    + resolved + "/" + needed + " players resolved, timedOut=" + timedOut);
            local(mc, lines.isEmpty()
                    ? "§8[Sweat] §7No teams detected."
                    : "§8[Sweat] §7Queued report for party.");
        }
        if (lines.isEmpty()) {
            OutgoingChat.get().markSweatDone();
            return;
        }
        List<String> out = new ArrayList<String>(lines.size());
        for (String line : lines) out.add("/pc " + line);
        OutgoingChat.get().submitSweat(out);
    }

    /**
     * Whether this player's stats are settled for reporting purposes, queueing a fetch when not.
     * An ERROR entry does not count: it would otherwise pass the gate instantly and print as a
     * bogus unknown, where waiting lets the cache's backoff retry inside our window.
     */
    private static boolean countResolved(NetworkPlayerInfo info) {
        UUID id = info.getGameProfile().getId();
        BedwarsStats stats = StatsCache.getCached(id);
        if (stats != null && stats.state != BedwarsStats.State.ERROR) return true;
        StatsCache.ensureFetched(id, StatsCache.PRIORITY_TAB);
        return false;
    }

    private void rearm(String reason) {
        if (DEBUG) System.out.println("[BedwarsQol][SweatReport] re-armed (" + reason + ")");
        activeSinceMs = 0L;
        notActiveSlots = 0;
        OutgoingChat.get().resetSweatForNewGame();
    }

    private static List<NetworkPlayerInfo> collectEnemies(NetHandlerPlayClient net, Scoreboard board, char myColor) {
        List<NetworkPlayerInfo> out = new ArrayList<NetworkPlayerInfo>();
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) continue;
            GameProfile prof = info.getGameProfile();
            if (prof.getName() == null || prof.getId() == null) continue;
            char color = teamColor(board, prof.getName());
            if (color == 0 || color == myColor) continue;
            out.add(info);
        }
        return out;
    }

    private static List<NetworkPlayerInfo> collectTeam(NetHandlerPlayClient net, Scoreboard board, char myColor) {
        List<NetworkPlayerInfo> out = new ArrayList<NetworkPlayerInfo>();
        if (myColor == 0) return out;
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) continue;
            GameProfile prof = info.getGameProfile();
            if (prof.getName() == null || prof.getId() == null) continue;
            if (teamColor(board, prof.getName()) != myColor) continue;
            out.add(info);
        }
        return out;
    }

    /** Group every player under their team color — Hypixel gives each player a 1-person scoreboard
     *  team, so nametag color is the only shared team signal. Our own team is labelled {@code (US)}. */
    private static List<SweatReportText.Team> buildTeams(List<NetworkPlayerInfo> enemies,
                                                         List<NetworkPlayerInfo> team,
                                                         Scoreboard board, char myColor) {
        BedwarsMode mode = BedwarsModeDetector.current();
        Map<Character, List<SweatReportText.Entry>> byColor =
                new LinkedHashMap<Character, List<SweatReportText.Entry>>();

        for (NetworkPlayerInfo info : enemies) {
            GameProfile prof = info.getGameProfile();
            char color = teamColor(board, prof.getName());
            if (color == 0 || color == myColor) continue;
            List<SweatReportText.Entry> list = byColor.get(color);
            if (list == null) {
                list = new ArrayList<SweatReportText.Entry>();
                byColor.put(color, list);
            }
            list.add(toEntry(prof, StatsCache.getCached(prof.getId()), mode));
        }

        if (myColor != 0 && !team.isEmpty()) {
            List<SweatReportText.Entry> ours = new ArrayList<SweatReportText.Entry>();
            for (NetworkPlayerInfo info : team) {
                GameProfile prof = info.getGameProfile();
                ours.add(toEntry(prof, StatsCache.getCached(prof.getId()), mode));
            }
            byColor.put(myColor, ours);
        }

        List<SweatReportText.Team> teams = new ArrayList<SweatReportText.Team>();
        for (Map.Entry<Character, List<SweatReportText.Entry>> e : byColor.entrySet()) {
            char color = e.getKey().charValue();
            String label = teamName(color) + (color == myColor ? " (US)" : "");
            teams.add(new SweatReportText.Team(label, e.getValue()));
        }
        return teams;
    }

    private static SweatReportText.Entry toEntry(GameProfile prof, BedwarsStats stats, BedwarsMode mode) {
        if (stats != null && stats.state == BedwarsStats.State.OK) {
            double fkdr = stats.statsFor(mode).fkdr;
            return new SweatReportText.Entry(prof.getName(), SweatReportText.fmt1(fkdr), fkdr, true);
        }
        return new SweatReportText.Entry(prof.getName(), tag(stats), 0.0, false);
    }

    /** Short status for a player with no usable FKDR — each cause reads distinctly. */
    private static String tag(BedwarsStats stats) {
        if (stats == null) return "..."; // still fetching when the report fired
        switch (stats.state) {
            case NICKED:       return "nick";
            case NEVER_PLAYED: return "new";
            case ERROR:        return "err";
            default:           return "?";
        }
    }

    private static void local(Minecraft mc, String msg) {
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(ModChat.mark(new ChatComponentText(msg)));
    }

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

    private static String teamName(char color) {
        switch (color) {
            case 'c': return "RED";
            case '9': return "BLUE";
            case 'a': return "GREEN";
            case 'e': return "YELLOW";
            case 'b': return "AQUA";
            case 'd': return "PINK";
            case 'f': return "WHITE";
            case '6': return "GOLD";
            case '7':
            case '8': return "GRAY";
            default:  return "TEAM";
        }
    }
}
