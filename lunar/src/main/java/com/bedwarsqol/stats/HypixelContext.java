package com.bedwarsqol.stats;

import com.bedwarsqol.feature.LobbyExport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumChatFormatting;

public final class HypixelContext {

    private HypixelContext() {}

    public static boolean isOnHypixel() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return false;
        if (mc.isSingleplayer()) return false;
        ServerData server = mc.getCurrentServerData();
        if (server == null || server.serverIP == null) return false;
        // Exact hypixel.net / *.hypixel.net only (port-tolerant). Spoof hosts get zero Hypixel-tab
        // feature traffic even when those modules default on.
        return EligibilitySnapshot.isExactHypixelHost(server.serverIP);
    }

    /** How long a confirmed Bedwars context outlives the raw sidebar check (see {@link #isInBedwars}). */
    private static final long CONTEXT_GRACE_MS = 2000L;
    private static volatile long lastInBedwarsMs;

    /**
     * Whether the sidebar says we're anywhere in Bedwars, held last-known-good for a short grace.
     * Hypixel rebuilds the sidebar objective whenever it updates — often triggered by the very lobby
     * join that also fires a chat broadcast — so a packet processed inside the remove→re-add window
     * sees no slot-1 objective at all. Receive-time consumers (ChatNameTags) drop a line rejected in
     * that window permanently; the grace keeps the context true across the rebuild.
     */
    public static boolean isInBedwars() {
        if (rawIsInBedwars()) {
            lastInBedwarsMs = System.currentTimeMillis();
            return true;
        }
        return System.currentTimeMillis() - lastInBedwarsMs < CONTEXT_GRACE_MS;
    }

    /** Raw sidebar title, no grace. Overlay eligibility must not treat SkyWars as the BedWars hub. */
    public static boolean sidebarSaysBedwars() {
        return rawIsInBedwars();
    }

    /** Drop the BedWars grace so a world change cannot leak hub eligibility into SkyWars. */
    public static void clearBedwarsGrace() {
        lastInBedwarsMs = 0L;
    }

    /** Hub or a supported Solo/Doubles/3s/4s/4v4 queue/game on exact Hypixel. */
    public static boolean isSupportedBedwarsSurface() {
        return LobbyExport.evaluate(
                isOnHypixel(),
                rawIsInBedwars(),
                isInBedwarsQueue(),
                isInActiveBedwarsGame(),
                sidebarModeLabel(),
                LobbyExport.retainedSupportedMode()).eligible;
    }

    private static boolean rawIsInBedwars() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return false;
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return false;
        ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
        if (objective == null) return false;
        String title = stripFormatting(objective.getDisplayName());
        if (title == null) return false;
        String upper = title.toUpperCase();
        return upper.contains("BED WARS") || upper.contains("BEDWARS");
    }

    public static boolean isInActiveBedwarsGame() {
        if (!isInBedwars()) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return false;
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return false;
        return board.getObjectiveInDisplaySlot(2) != null;
    }

    /**
     * Whether we're in a Bedwars <b>pregame queue</b> — the waiting lobby on a game server, not the
     * Bedwars hub. Both carry a "BED WARS" sidebar and neither has the active game's slot-2 objective,
     * so {@code isInBedwars() && !isInActiveBedwarsGame()} matches the hub too; only the queue's
     * sidebar lists the match being assembled. The {@code Mode:} line is that marker — present for
     * every queue including modes {@link BedwarsModeDetector} doesn't map, and absent in the hub.
     */
    public static boolean isInBedwarsQueue() {
        if (!isInBedwars() || isInActiveBedwarsGame()) return false;
        return sidebarModeLabel() != null;
    }

    /**
     * The sidebar {@code Mode: <label>} value (pregame queue), or {@code null} when that line is
     * absent. Used both to detect the queue and to title the launcher dashboard.
     */
    public static String sidebarModeLabel() {
        return sidebarValue("Mode:");
    }

    /**
     * The sidebar {@code Map: <name>} value the Bedwars pregame queue prints (e.g. {@code Lighthouse}),
     * or {@code null} when absent. Feeds the Height Limit HUD when no Mod API location event arrives.
     */
    public static String sidebarMapLabel() {
        return sidebarValue("Map:");
    }

    /** The text after {@code label} on the first sidebar line containing it, trimmed; {@code null} if none. */
    private static String sidebarValue(String label) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return null;
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return null;
        ScoreObjective sidebar = board.getObjectiveInDisplaySlot(1);
        if (sidebar == null) return null;
        for (net.minecraft.scoreboard.Score score : board.getSortedScores(sidebar)) {
            ScorePlayerTeam team = board.getPlayersTeam(score.getPlayerName());
            String line = EnumChatFormatting.getTextWithoutFormattingCodes(
                    ScorePlayerTeam.formatPlayerName(team, score.getPlayerName()));
            if (line == null) continue;
            int i = line.indexOf(label);
            if (i < 0) continue;
            String v = line.substring(i + label.length()).trim();
            if (!v.isEmpty()) return v;
        }
        return null;
    }

    /**
     * Whether the sidebar objective is a Duels game (title contains "DUELS"). Unlike the Bedwars check
     * this doesn't require a second objective, so it's true in the Duels lobby too — harmless, since the
     * lobby has no combat to judge; combat only happens once a match starts. Used to let the cheater
     * detector run in duels, where a 1v1 puts the opponent right on you (ideal for it).
     */
    public static boolean isInDuels() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return false;
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return false;
        ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
        if (objective == null) return false;
        String title = stripFormatting(objective.getDisplayName());
        return title != null && title.toUpperCase().contains("DUELS");
    }

    private static String stripFormatting(String s) {
        if (s == null) return null;
        return EnumChatFormatting.getTextWithoutFormattingCodes(s);
    }

    public static ScorePlayerTeam teamForPlayer(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return null;
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return null;
        return board.getPlayersTeam(name);
    }
}
