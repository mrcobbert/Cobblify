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

import java.util.ArrayList;
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
        boolean queue = HypixelContext.isInBedwarsQueue();
        boolean game = HypixelContext.isInActiveBedwarsGame();
        String sidebar = HypixelContext.sidebarModeLabel();
        String retained = LobbyExport.retainedSupportedMode();
        if (game && (sidebar == null || sidebar.trim().isEmpty())
                && !LobbyExport.isSupportedDashboardMode(retained)) {
            BedwarsMode detected = BedwarsModeDetector.current();
            if (detected != BedwarsMode.UNKNOWN) {
                sidebar = detected.label();
            }
        }
        LobbyExport.EvalResult r = LobbyExport.evaluate(
                inHypixel,
                HypixelContext.sidebarSaysBedwars(),
                queue,
                game,
                sidebar,
                retained);
        LobbyExport.rememberSupportedMode(r.modeToRetain);
        lobby.inHypixel = inHypixel;

        if (mc != null && mc.thePlayer != null) {
            lobby.self = mc.thePlayer.getName();
            if (r.autoFetch && game && mc.thePlayer.getGameProfile() != null) {
                StatsCache.ensureFetched(mc.thePlayer.getGameProfile().getId(), StatsCache.PRIORITY_TAB);
            }
        }

        ClientSettings cfg = BedwarsQol.config;
        lobby.partyCount = (cfg != null && cfg.partyJoinAlert)
                ? Integer.valueOf(PartyJoinAlert.partiesInLobby()) : null;

        final Map<String, UUID> ids = new LinkedHashMap<String, UUID>();
        List<LobbyExport.TabRow> tab = new ArrayList<LobbyExport.TabRow>();
        if (r.scanFullRoster || r.readQueuePartyFromTab) {
            NetHandlerPlayClient net = mc == null ? null : mc.getNetHandler();
            if (net != null) {
                for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
                    if (info == null || info.getGameProfile() == null) continue;
                    GameProfile gp = info.getGameProfile();
                    String name = gp.getName();
                    UUID id = gp.getId();
                    if (name == null || id == null || !NAME.matcher(name).matches()) continue;
                    if (id.version() == 2) continue;
                    ids.put(name, id);
                    String team = (r.scanFullRoster && "GAME".equals(r.context))
                            ? teamName(TeamColors.code(name)) : null;
                    tab.add(new LobbyExport.TabRow(name, team));
                }
            }
        }

        List<String> extra = (r.eligible && "QUEUE".equals(r.context))
                ? LobbyChatState.queueTypers(GameSessionTracker.currentSessionId())
                : null;
        LobbyExport.applySnapshot(lobby, r, LobbyChatState.party(), tab, extra,
                new LobbyExport.PlayerFactory() {
                    public LobbyExport.Player player(String name) {
                        return LobbySnapshot.player(name, ids.get(name));
                    }
                },
                new LobbyExport.FetchSink() {
                    public void fetch(LobbyExport.TabRow row) {
                        UUID id = ids.get(row.name);
                        if (id != null) StatsCache.ensureFetched(id, StatsCache.PRIORITY_TAB);
                    }
                });
        return lobby;
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
