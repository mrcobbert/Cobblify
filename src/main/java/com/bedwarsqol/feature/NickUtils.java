package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.BedwarsMode;
import com.bedwarsqol.stats.BedwarsModeDetector;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.GameSessionTracker;
import com.bedwarsqol.stats.HypixelContext;
import com.bedwarsqol.stats.IdentitySnapshot;
import com.bedwarsqol.stats.StatsCache;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Nick Utils — the detection core. Every ~0.5s it scans the tab list, decodes each player's
 * Mojang-signed skin (see {@link Denicks}), and feeds the shared denick cache that the inline chat
 * tags ({@link ChatNameTags}) and the tab-list overlay both read. It also owns the two announce paths:
 *
 * <ul>
 *   <li><b>Lobby (immediate).</b> In a real-identity Bedwars lobby, each newly-denicked player is
 *       announced once, right away, neutral-coloured: {@code "Nick is Nicked, denick successful.
 *       Real Name: Real"} (or just {@code "Nick is Nicked"} when only Nick Notify is on).</li>
 *   <li><b>In game (batched).</b> Mirrors the Sweat Report: once per game, after every denicked
 *       player's real-account FKDR has resolved (or a timeout), it prints one local line per denicked
 *       player, coloured by their Bedwars team, with the real name and FKDR appended.</li>
 * </ul>
 *
 * <p>All output is local ({@code addChatMessage}) — nothing is ever sent to the server, so the denick
 * stays undetectable.
 *
 * <p><b>Why obfuscated names can never be denicked.</b> Hypixel's pregame lobby obfuscates tab names,
 * and the pregame→game transition briefly pairs those obfuscated names with players' <i>real</i> signed
 * skins — a raw name-vs-skin comparison then "denicks" the entire lobby (ourselves included). Three
 * independent layers make that impossible:
 * <ol>
 *   <li><b>UUID version gate.</b> A row is only ever a nick candidate when its UUID is version 1 —
 *       Hypixel's spoofed-profile marker. Real accounts are always v4 (never denickable, whatever name
 *       the row wears) and lobby NPCs are v2 (skipped outright).</li>
 *   <li><b>Window tripwires.</b> A scan is discarded wholesale — nothing cached, nothing announced,
 *       identities-visible false — when it sees any §k-rendered player row, a foreign row wearing OUR
 *       signed skin, or one account's skin on two rows where one is the account's real row. Each is
 *       impossible in a legitimate lobby and characteristic of the obfuscation window.</li>
 *   <li><b>Settle buffer.</b> A candidate must hold the same nick+skin pairing for
 *       {@link #SETTLE_SCANS} consecutive clean scans before it reaches the cache or an announcement;
 *       tripped scans reset the count.</li>
 * </ol>
 * The pregame lobby therefore stays fully suppressed (§k rows all the way through), the transition
 * window is discarded, and denicking begins only once real names are actually on the tab list.
 */
public final class NickUtils {

    private static final int TICK_INTERVAL = 10;     // ~0.5s between scans/report ticks (Sweat Report cadence)
    private static final int REARM_GRACE_SLOTS = 3;  // ~1.5s out of the active game before we re-arm the report
    private static final long MAX_WAIT_MS = 25_000L; // give up waiting on unresolved real-account FKDRs
    private static final int SETTLE_SCANS = 2;       // consecutive clean scans a nick candidate must survive

    /** Real Minecraft username shape — skips Hypixel's fake tab rows (team headers, info lines). */
    private static final Pattern MC_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    // Immutable identity publication consumed by all Urchin transport/surfaces (both trees). A clean
    // scan replaces it; a tripped/empty scan, lost context, or a session transition publishes EMPTY.
    private static volatile IdentitySnapshot identitySnapshot = IdentitySnapshot.EMPTY;
    private static final java.util.concurrent.atomic.AtomicLong SCAN_GEN =
            new java.util.concurrent.atomic.AtomicLong();

    public static IdentitySnapshot identitySnapshot() {
        return identitySnapshot;
    }

    /** Session-transition hook (called by {@link GameSessionTracker} on the client thread). */
    public static void clearIdentitySnapshot() {
        identitySnapshot = IdentitySnapshot.EMPTY;
        Denicks.publishVerified(null); // session changed → drop verified denicks
    }

    private static void publishEmptyIdentity() {
        identitySnapshot = IdentitySnapshot.EMPTY;
        Denicks.publishVerified(null); // identities untrustworthy (off-Hypixel/no net/window) → no denick
    }

    private static void publishIdentity(Set<String> confirmedRows) {
        identitySnapshot = new IdentitySnapshot(
                confirmedRows, SCAN_GEN.incrementAndGet(), GameSessionTracker.currentSessionId());
    }

    private final Set<String> announcedLobby = new HashSet<String>();     // nicks announced in-lobby this world
    private final List<String[]> currentDenicks = new ArrayList<String[]>(); // [nick, real] present in tab this scan
    private final Map<String, Integer> settleCounts = new HashMap<String, Integer>(); // "nick|real" -> clean scans seen

    private int ticks;
    private boolean reportSent;
    private long activeSinceMs;
    private int notActiveSlots;
    private boolean windowLoggedCold; // logged a skins-stripped window (whole pregame lobby) this window
    private boolean windowLoggedHot;  // logged a real-skins window (the dangerous transition) this window

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        announcedLobby.clear();
        currentDenicks.clear();
        settleCounts.clear();
        Denicks.clear();
        rearmReport();
        ticks = 0;
        windowLoggedCold = false;
        windowLoggedHot = false;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++ticks < TICK_INTERVAL) return;
        ticks = 0;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        if (!HypixelContext.isOnHypixel() || mc.theWorld == null) {
            Denicks.setIdentitiesVisible(false);
            publishEmptyIdentity();
            if (!announcedLobby.isEmpty()) announcedLobby.clear();
            rearmReport();
            return;
        }

        // The identities-visible verdict is shared CONTEXT, not a Nick Utils feature: ChatNameTags
        // trusts a lobby's broadcast lines only while skins are provably real, so the scan must run
        // with the module off too (gated behind the toggle, lobby chat silently lost its FKDR tags
        // for anyone who never enabled Nick Utils). The scan is a passive local decode of tab skin
        // blobs; everything nick-facing below stays behind the toggle.
        boolean visible = scanPopulate(mc);
        Denicks.setIdentitiesVisible(visible);

        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.nickUtils) return;

        if (HypixelContext.isInActiveBedwarsGame() && HypixelContext.isSupportedBedwarsSurface()) {
            notActiveSlots = 0;
            runReport(mc, cfg);
        } else if (HypixelContext.isInActiveBedwarsGame()) {
            notActiveSlots = 0;
        } else {
            if (++notActiveSlots >= REARM_GRACE_SLOTS) rearmReport();
            if (visible && HypixelContext.isInBedwars()) lobbyAnnounce(mc, cfg);
        }
    }

    /**
     * Decode every real tab player's signed skin once: populate {@link Denicks} with settled nick
     * candidates, collect the denicked players present this scan, and report whether identities are
     * real here (identities-visible gate). Writes NOTHING and returns false when an obfuscation-window
     * tripwire fires — every row in such a scan is suspect. See the class javadoc for the gate design.
     */
    private boolean scanPopulate(Minecraft mc) {
        currentDenicks.clear();
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) {
            settleCounts.clear();
            publishEmptyIdentity();
            return false;
        }
        String selfName = mc.thePlayer.getName();
        UUID selfId = mc.thePlayer.getUniqueID();

        // Phase 1: decode every judgeable row (player-shaped name, non-NPC UUID, signed skin present).
        List<Row> rows = new ArrayList<Row>();
        List<String> windowDiag = new ArrayList<String>();
        boolean tripped = false;
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) continue;
            GameProfile profile = info.getGameProfile();
            String inGame = profile.getName();
            UUID id = profile.getId();
            if (inGame == null || id == null || !MC_NAME.matcher(inGame).matches()) continue;
            int version = id.version();
            if (version == 2) continue; // Hypixel NPC rows — never players, never window evidence

            Denicks.SkinIdentity skin = Denicks.identityFromSkin(profile);
            // Tripwire: a §k-rendered player row means obfuscation is active right now.
            if (renderedTabName(info, inGame).contains("§k")) {
                tripped = true;
                windowDiag.add(diagLine(inGame, version, true, skin));
                continue;
            }
            boolean self = id.equals(selfId) || inGame.equalsIgnoreCase(selfName);
            if (skin == null) continue;
            rows.add(new Row(inGame, id, version, skin, self));
        }

        // Phase 2: cross-row tripwires — each impossible in a legitimate lobby.
        int consistent = 0;
        Map<String, Integer> ownerRows = new HashMap<String, Integer>();
        Set<String> consistentOwners = new HashSet<String>();
        Set<String> confirmedRows = new HashSet<String>();
        for (Row r : rows) {
            String owner = r.skin.name.toLowerCase();
            Integer n = ownerRows.get(owner);
            ownerRows.put(owner, n == null ? 1 : n + 1);
            if (r.consistent()) {
                consistentOwners.add(owner);
                if (r.version == 4) {
                    consistent++;
                    // Confirmed identity for automatic Urchin: a v4 row whose signed skin names its
                    // own account. v1 (nick) rows are never included (amendment R11).
                    String key = IdentitySnapshot.rowKey(r.name, r.id);
                    if (key != null) confirmedRows.add(key);
                }
            }
            // Tripwire: a row that isn't ours wearing OUR Mojang-signed skin.
            if (r.skin.name.equalsIgnoreCase(selfName) && !r.self) {
                tripped = true;
                windowDiag.add(diagLine(r.name, r.version, false, r.skin));
            }
        }
        // Tripwire: one account's skin on two rows, one of them the account's real row. (Without the
        // real row present it is instead two skin-changed nicks sharing a pool skin — legitimate, and
        // must not suppress the lobby.)
        for (Map.Entry<String, Integer> e : ownerRows.entrySet()) {
            if (e.getValue() > 1 && consistentOwners.contains(e.getKey())) {
                tripped = true;
                windowDiag.add("duplicate rows for account " + e.getKey());
            }
        }

        if (tripped) {
            settleCounts.clear(); // identities are in flux — candidates must re-settle from scratch
            publishEmptyIdentity();
            logWindowOnce(windowDiag);
            return false;
        }
        windowLoggedCold = false;
        windowLoggedHot = false;

        // Phase 3: v1-gated candidates, settled over consecutive clean scans before they take effect.
        Set<String> present = new HashSet<String>();
        List<String[]> settled = new ArrayList<String[]>(); // [nick, proposed real account] this scan
        for (Row r : rows) {
            if (r.version != 1 || r.consistent() || r.self) continue;
            // Layer 1: a v1 row wearing a known Hypixel /nick pool skin embeds the pool skin's
            // uploader, not the player — denicking it would publish a wrong name. Skip it (stays a
            // nick, unresolved). Kept-personal-skin nicks are not pool skins and fall through.
            if (Denicks.isNickSkin(r.skin.textureHash)) continue;
            String key = r.name.toLowerCase() + "|" + r.skin.name.toLowerCase();
            present.add(key);
            Integer prior = settleCounts.get(key);
            int seen = Math.min(prior == null ? 1 : prior + 1, SETTLE_SCANS);
            settleCounts.put(key, seen);
            if (seen >= SETTLE_SCANS) settled.add(new String[]{r.name, r.skin.name});
        }
        settleCounts.keySet().retainAll(present);

        // Layer 2: publish a denick only after its real account resolves OK + elevated rank — only an
        // elevated rank (MVP++/YOUTUBE/staff) can /nick, so a non-elevated resolution is a false
        // denick (e.g. an unknown pool skin naming a plain uploader). The verified map and
        // currentDenicks are rebuilt from the live rows every scan, so a departed / owner-changed /
        // rank-downgraded nick is pruned at once and no unverified or stale name is ever exposed.
        // UNKNOWN provenance (uncached, old cache, or fetch error) refetches and stays pending.
        Map<String, String> verified = new HashMap<String, String>();
        for (String[] c : settled) {
            String nick = c[0], real = c[1];
            switch (Denicks.decide(StatsCache.getCachedByName(real))) {
                case VERIFY:
                    verified.put(nick.toLowerCase(), real);
                    currentDenicks.add(new String[]{nick, real});
                    break;
                case PENDING:
                    // force: a stale pre-rankCode cache entry reads UNKNOWN; upgrade it with a fresh
                    // lookup now instead of staying pending until the full TTL expires.
                    if (HypixelContext.isSupportedBedwarsSurface()) {
                        StatsCache.ensureFetchedByName(real, StatsCache.PRIORITY_TAB, true);
                    }
                    break;
                default: // DROP — this account cannot have nicked
                    break;
            }
        }

        Denicks.publishVerified(verified);

        publishIdentity(confirmedRows);
        return consistent > 0;
    }

    /** The tab row's rendered text, exactly as vanilla draws it (display name, else team-formatted name). */
    private static String renderedTabName(NetworkPlayerInfo info, String inGame) {
        IChatComponent display = info.getDisplayName();
        if (display != null) {
            String s = display.getFormattedText();
            if (s != null) return s;
        }
        String s = ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), inGame);
        return s != null ? s : inGame;
    }

    private static String diagLine(String name, int uuidVersion, boolean obfuscatedStyle, Denicks.SkinIdentity skin) {
        return name + " uuidV" + uuidVersion + (obfuscatedStyle ? " [k]" : "")
                + " skin=" + (skin == null ? "-" : skin.name);
    }

    /**
     * Log the rows that tripped an obfuscation window — Forge log only, never chat, and at most once
     * per window per kind: "cold" (skins stripped, the whole pregame lobby) and "hot" (real skins under
     * wrong names, the transition). The hot dump records the UUID versions Hypixel uses there — the one
     * fact no public source documents.
     */
    private void logWindowOnce(List<String> diag) {
        if (diag.isEmpty()) return;
        boolean hot = false;
        for (String line : diag) {
            if (!line.contains("skin=-")) {
                hot = true;
                break;
            }
        }
        if (hot ? windowLoggedHot : windowLoggedCold) return;
        if (hot) windowLoggedHot = true;
        else windowLoggedCold = true;
        System.out.println("[BedwarsQol] NickUtils: obfuscation window ("
                + (hot ? "real skins under wrong names" : "skins stripped") + "), scan suppressed:");
        for (String line : diag) {
            System.out.println("[BedwarsQol]   " + line);
        }
    }

    /** One judgeable tab row this scan: a real-player-shaped name with a decoded signed skin. */
    private static final class Row {
        final String name;
        final UUID id;
        final int version;
        final Denicks.SkinIdentity skin;
        final boolean self;

        Row(String name, UUID id, int version, Denicks.SkinIdentity skin, boolean self) {
            this.name = name;
            this.id = id;
            this.version = version;
            this.skin = skin;
            this.self = self;
        }

        /** The signed skin names this row's own account — a real, un-nicked player. */
        boolean consistent() {
            return skin.name.equalsIgnoreCase(name)
                    || (skin.idHex != null && skin.idHex.equalsIgnoreCase(id.toString().replace("-", "")));
        }
    }

    /** Announce each newly-denicked lobby player once, immediately, neutral-coloured (no team, no FKDR). */
    private void lobbyAnnounce(Minecraft mc, ClientSettings cfg) {
        for (String[] d : currentDenicks) {
            String nick = d[0], real = d[1];
            if (!announcedLobby.add(nick.toLowerCase())) continue;
            if (cfg.autoDenick) {
                local(mc, "§e" + nick + " §7is Nicked, §adenick successful. Real Name: §f" + real);
            } else if (cfg.nickNotify) {
                local(mc, "§e" + nick + " §7is Nicked");
            }
        }
    }

    /**
     * Once per active game, after every denicked player's real-account FKDR has resolved (or a timeout),
     * print one local, team-coloured line per denicked player.
     */
    private void runReport(Minecraft mc, ClientSettings cfg) {
        if (reportSent) return;
        if (!cfg.autoDenick && !cfg.nickNotify) return;
        if (activeSinceMs == 0L) activeSinceMs = System.currentTimeMillis();
        boolean timedOut = System.currentTimeMillis() - activeSinceMs > MAX_WAIT_MS;

        List<String[]> denicks = new ArrayList<String[]>(currentDenicks);
        if (denicks.isEmpty()) {
            if (timedOut) reportSent = true; // no nicks this game — stop waiting, print nothing
            return;
        }

        // When we reveal names we also show FKDR, so wait until each real account's FKDR lands.
        if (cfg.autoDenick) {
            int resolved = 0;
            for (String[] d : denicks) {
                if (StatsCache.getCachedByName(d[1]) != null) resolved++;
                else if (HypixelContext.isSupportedBedwarsSurface()) {
                    StatsCache.ensureFetchedByName(d[1], StatsCache.PRIORITY_TAB);
                }
            }
            if (resolved < denicks.size() && !timedOut) return;
        }

        reportSent = true;
        BedwarsMode mode = BedwarsModeDetector.current();
        for (String[] d : denicks) {
            String nick = d[0], real = d[1];
            char col = teamColorChar(nick);
            String nc = col != 0 ? "§" + col : "§e";
            String rc = col != 0 ? "§" + col : "§f";
            if (cfg.autoDenick) {
                BedwarsStats st = StatsCache.getCachedByName(real);
                String fkdr = "";
                if (st != null && st.state == BedwarsStats.State.OK) {
                    double f = st.statsFor(mode).fkdr;
                    fkdr = " §7[" + BedwarsStats.fkdrColor(f) + "FKDR " + fmt2(f) + "§7]";
                }
                local(mc, nc + nick + " §7is Nicked, §adenick successful. Real Name: " + rc + real + fkdr);
            } else {
                local(mc, nc + nick + " §7is Nicked");
            }
        }
    }

    private void rearmReport() {
        reportSent = false;
        activeSinceMs = 0L;
        notActiveSlots = 0;
    }

    /** The first Minecraft colour code in a player's rendered nametag = their team colour (0 if none). */
    private static char teamColorChar(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return 0;
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return 0;
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

    private static void local(Minecraft mc, String text) {
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(ModChat.mark(new ChatComponentText(text)));
    }

    private static String fmt2(double d) {
        return String.format(Locale.US, "%.2f", d);
    }
}
