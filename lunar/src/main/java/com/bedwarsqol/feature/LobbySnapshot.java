package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.BedwarsMode;
import com.bedwarsqol.stats.BedwarsModeDetector;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.GameSessionTracker;
import com.bedwarsqol.stats.HypixelContext;
import com.bedwarsqol.stats.SeraphTag;
import com.bedwarsqol.stats.StatsCache;
import com.bedwarsqol.stats.UrchinTag;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds the Cobblify lobby-dashboard snapshot on the client thread and hands it to {@link LobbyExport}.
 * Called every tick from {@code GameSessionTracker.onClientTick}; the heavy work (tab scan, per-player
 * cache reads) is throttled so it runs at most a couple of times a second, matching the writer's
 * debounce. Everything here touches only vanilla {@code net.minecraft} types that map identically on
 * Forge and Weave, so this file is byte-identical in both trees (not a declared divergence).
 *
 * <p>Security: the snapshot carries player IGNs and public stats only. It reads tab names, team colours
 * and cached stats — never a UUID/xuid/token/argv/accounts.json (the local player's own IGN is fine).
 * Fail-soft: any missing world/handler/player just yields a smaller snapshot (MENU with no roster),
 * never a throw into the game thread.
 */
public final class LobbySnapshot {

    /** A single Minecraft username token: 3-16 of [A-Za-z0-9_]. Filters NPC/armour-stand rows out. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    /** Throttle so a full tab scan runs at most ~twice a second regardless of tick rate. */
    private static final long CAPTURE_INTERVAL_MS = 400L;
    private static volatile long lastCaptureMs;

    /**
     * Last queue/game mode label written to {@code lobby.json}. The sidebar {@code Mode:} line is
     * gone once the match starts, so we keep the queue value (and 4v4 / dreams the detector
     * does not map) until the player is back in the hub or menu.
     */
    private static volatile String lastMode;

    private LobbySnapshot() {}

    /** Build and submit a snapshot, throttled. Never throws. */
    public static void capture(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastCaptureMs < CAPTURE_INTERVAL_MS) return;
        lastCaptureMs = now;
        try {
            LobbyExport.submit(build(mc));
        } catch (Throwable ignored) {
            // never propagate into the game thread
        }
    }

    private static LobbyExport.Lobby build(Minecraft mc) {
        LobbyExport.Lobby lobby = new LobbyExport.Lobby();
        boolean inHypixel = HypixelContext.isOnHypixel();
        lobby.inHypixel = inHypixel;
        if (HypixelContext.isInActiveBedwarsGame()) lobby.context = "GAME";
        else if (HypixelContext.isInBedwarsQueue()) lobby.context = "QUEUE";
        else if (inHypixel) lobby.context = "LOBBY";
        else lobby.context = "MENU";
        fillMode(lobby);

        if (mc != null && mc.thePlayer != null) lobby.self = mc.thePlayer.getName();

        ClientSettings cfg = BedwarsQol.config;
        lobby.partyCount = (cfg != null && cfg.partyJoinAlert)
                ? Integer.valueOf(PartyJoinAlert.partiesInLobby()) : null;

        // Your party rides along on every context (the launcher pins it at the top).
        for (String name : LobbyChatState.party()) {
            lobby.yourParty.add(player(name, null));
        }

        if ("QUEUE".equals(lobby.context)) {
            // The queue tab list is obfuscated; only players who typed are real, named roster.
            for (String name : LobbyChatState.queueTypers(GameSessionTracker.currentSessionId())) {
                lobby.players.add(player(name, null));
            }
        } else if ("LOBBY".equals(lobby.context) || "GAME".equals(lobby.context)) {
            boolean grouped = "GAME".equals(lobby.context);
            Map<String, LobbyExport.Team> teams = new LinkedHashMap<String, LobbyExport.Team>();
            NetHandlerPlayClient net = mc == null ? null : mc.getNetHandler();
            if (net != null) {
                for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
                    if (info == null || info.getGameProfile() == null) continue;
                    GameProfile gp = info.getGameProfile();
                    String name = gp.getName();
                    UUID id = gp.getId();
                    if (name == null || id == null || !NAME.matcher(name).matches()) continue;
                    if (id.version() == 2) continue; // Hypixel NPC rows are never players
                    LobbyExport.Player p = player(name, id);
                    lobby.players.add(p);
                    if (grouped) {
                        String teamName = teamName(TeamColors.code(name));
                        LobbyExport.Team team = teams.get(teamName);
                        if (team == null) {
                            team = new LobbyExport.Team(teamName);
                            teams.put(teamName, team);
                        }
                        team.players.add(p);
                    }
                }
            }
            if (grouped) lobby.teams.addAll(teams.values());
        }
        return lobby;
    }

    /** Set {@link LobbyExport.Lobby#mode} for QUEUE/GAME; clear it in the hub and menu. */
    private static void fillMode(LobbyExport.Lobby lobby) {
        if (!"QUEUE".equals(lobby.context) && !"GAME".equals(lobby.context)) {
            lastMode = null;
            lobby.mode = null;
            return;
        }
        String label = LobbyExport.dashboardModeLabel(HypixelContext.sidebarModeLabel());
        if (label == null) {
            BedwarsMode detected = BedwarsModeDetector.current();
            if (detected != BedwarsMode.UNKNOWN) {
                label = LobbyExport.dashboardModeLabel(detected.label());
            }
        }
        if (label != null) lastMode = label;
        lobby.mode = lastMode;
    }

    /** Build one player row: identity from name/UUID, stats from the cache (LOADING when not yet resolved). */
    private static LobbyExport.Player player(String name, UUID uuid) {
        LobbyExport.Player p = new LobbyExport.Player(name);
        String real = Denicks.realNameForNick(name);
        if (real != null) {
            p.nicked = true;
            p.realName = real;
        }
        BedwarsStats st = StatsCache.getCached(uuid);
        if (st == null) st = StatsCache.getCachedByName(name);
        if (st == null) {
            p.state = "LOADING";
            return p;
        }
        switch (st.state) {
            case OK:           p.state = "OK"; break;
            case NEVER_PLAYED: p.state = "NEVER_PLAYED"; break;
            case NICKED:       p.state = "NICKED"; p.nicked = true; break;
            case ERROR:        p.state = "ERROR"; break;
            default:           p.state = "LOADING"; break;
        }
        p.rank = LobbyExport.stripColors(st.rankPrefix);
        p.fkdr = st.fkdr;
        p.wlr = st.wlr;
        p.finalKills = st.finalKills;
        p.kd = st.kd;
        p.seraphThreat = st.seraphThreat;
        for (SeraphTag t : st.seraphTags) p.seraphTags.add(t.displayName());
        for (UrchinTag t : st.urchinTags) p.urchinTags.add(t.displayName());
        return p;
    }

    /** Bedwars team name from the scoreboard colour char (see the shared contract). */
    private static String teamName(char code) {
        switch (code) {
            case 'c': return "Red";
            case '9': return "Blue";
            case 'a': return "Green";
            case 'e': return "Yellow";
            case 'b': return "Aqua";
            case 'f': return "White";
            case 'd': return "Pink";
            case '8':
            case '7': return "Gray";
            default:  return "Unknown";
        }
    }
}
