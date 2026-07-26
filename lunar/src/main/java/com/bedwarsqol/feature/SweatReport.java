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
import java.util.List;
import java.util.UUID;

/**
 * Once per Bedwars game, after enemy/team stats have resolved, broadcasts a condensed sweat summary
 * to party chat via {@code /pc}. Output is a single line (see {@link SweatReportText}) routed through
 * {@link OutgoingChat}. Delivery is acknowledged by the coordinator — cancelled reports may retry in
 * the same active game without duplicate in-flight submits.
 */
public final class SweatReport {

    private static final boolean DEBUG = false;

    private static final int TICK_INTERVAL = 10;
    private static final int REARM_GRACE_SLOTS = 3;
    private static final long MAX_WAIT_MS = 25_000;

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
        // RETRYABLE → IDLE so we may rebuild and resubmit once for this game.
        OutgoingChat.get().consumeSweatRetry();

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
            UUID id = info.getGameProfile().getId();
            if (StatsCache.getCached(id) != null) {
                resolved++;
            } else {
                StatsCache.ensureFetched(id, StatsCache.PRIORITY_TAB);
            }
        }
        for (NetworkPlayerInfo info : team) {
            UUID id = info.getGameProfile().getId();
            if (StatsCache.getCached(id) != null) {
                resolved++;
            } else {
                StatsCache.ensureFetched(id, StatsCache.PRIORITY_TAB);
            }
        }
        if (resolved < needed && !timedOut) return;

        List<String> lines = SweatReportText.condense(buildEntries(enemies, team, board, myColor));
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
        OutgoingChat.get().submitSweat("/pc " + lines.get(0));
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

    private static List<SweatReportText.Entry> buildEntries(List<NetworkPlayerInfo> enemies,
                                                           List<NetworkPlayerInfo> team,
                                                           Scoreboard board, char myColor) {
        BedwarsMode mode = BedwarsModeDetector.current();
        List<SweatReportText.Entry> entries = new ArrayList<SweatReportText.Entry>();
        for (NetworkPlayerInfo info : enemies) {
            GameProfile prof = info.getGameProfile();
            char color = teamColor(board, prof.getName());
            if (color == 0 || color == myColor) continue;
            entries.add(toEntry(prof, StatsCache.getCached(prof.getId()), mode, color, false));
        }
        if (myColor != 0) {
            for (NetworkPlayerInfo info : team) {
                GameProfile prof = info.getGameProfile();
                entries.add(toEntry(prof, StatsCache.getCached(prof.getId()), mode, myColor, true));
            }
        }
        return entries;
    }

    private static SweatReportText.Entry toEntry(GameProfile prof, BedwarsStats stats, BedwarsMode mode,
                                                 char color, boolean ours) {
        String team = teamName(color);
        if (stats != null && stats.state == BedwarsStats.State.OK) {
            double fkdr = stats.statsFor(mode).fkdr;
            return new SweatReportText.Entry(team, ours, prof.getName(), SweatReportText.fmt1(fkdr), fkdr, true);
        }
        return new SweatReportText.Entry(team, ours, prof.getName(), tag(stats), 0.0, false);
    }

    private static String tag(BedwarsStats stats) {
        if (stats == null) return "?";
        switch (stats.state) {
            case NICKED:       return "nick";
            case NEVER_PLAYED: return "new";
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
