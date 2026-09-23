package com.bedwarsqol.feature;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.bedwarsqol.stats.PlayerCard;
import com.bedwarsqol.stats.SidebarModeLabel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the Cobblify lobby dashboard file, {@code ~/.cobblify/lobby.json}, that the launcher polls to
 * render the roster. Pure IO/JSON: it takes a plain-Java {@link Lobby} snapshot (built on the client
 * thread by {@code LobbySnapshot}), serializes it to the shared contract shape with Gson, and writes it
 * atomically (temp file + rename) on a background thread so the reader never sees a half-written file.
 *
 * <p>Platform-neutral by construction — no Minecraft/Forge/Weave types — so it lives in {@code common/}
 * and is compiled once for both trees. Fail-soft everywhere: a submit is a cheap store on the client
 * thread, all disk work is swallowed on error, and the {@code seq} counter is bumped only when the
 * serialized body actually changed, so the launcher can cheaply detect real updates.
 */
public final class LobbyExport {

    /** Debounce floor between disk writes; the client submits a fresh snapshot every tick. */
    private static final long WRITE_INTERVAL_MS = 500L;

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private static volatile Lobby latest;
    private static volatile boolean pending;
    /** Last supported canonical mode; consulted only when the sidebar Mode line is absent. */
    private static volatile String retainedSupportedMode;
    /** Queue-tab party names, kept into GAME when {@code /party list} was never parsed. */
    private static volatile List<String> retainedQueueParty = Collections.emptyList();
    /**
     * Last QUEUE/GAME time. Hub-shaped samples inside this window keep a supported retain and
     * do not clear {@link #sawUnsupportedMode}; they are still eligible as the hub.
     */
    private static volatile long lastQueueOrGameMs;
    private static final long MATCH_GAP_MS = 2000L;
    /**
     * True after a present unsupported Mode line (Castle, Rush, …). Cleared by a supported Mode
     * line, an eligible hub, or leaving Hypixel. Blocks the team-shape detector fallback so a
     * Doubles Rush game is not injected as {@code Doubles}.
     */
    private static volatile boolean sawUnsupportedMode;

    // Background-thread only.
    private static String lastBody = "";
    private static int seq;
    /** Immutable for this JVM; null when identity cannot be validated. */
    private static final JvmWriterIdentity WRITER = JvmWriterIdentity.current();
    /** Per-JVM temp path; set once when identity is valid. */
    private static final File TEMP_FILE = tempFileFor(WRITER);

    static {
        Thread t = new Thread(LobbyExport::writeLoop, "BedwarsQol-LobbyExport");
        t.setDaemon(true);
        t.start();
    }

    private LobbyExport() {}

    /** Store the newest snapshot for the writer thread. Cheap; safe to call every client tick. */
    public static void submit(Lobby lobby) {
        latest = lobby;
        pending = true;
    }

    /** Force the next debounce cycle to re-evaluate (e.g. a stat just resolved to a real value). */
    public static void markDirty() {
        pending = true;
    }

    public static String retainedSupportedMode() {
        return retainedSupportedMode;
    }

    public static void rememberSupportedMode(String canonicalOrNull) {
        retainedSupportedMode = canonicalOrNull;
    }

    /** True after an explicit unsupported Mode line; see {@code sawUnsupportedMode}. */
    public static boolean sawUnsupportedMode() {
        return sawUnsupportedMode;
    }

    /** Test-only: drop retain, queue-party memory, the Mode-line gap clock, and the Rush sticky. */
    public static void resetEligibilityStateForTest() {
        retainedSupportedMode = null;
        retainedQueueParty = Collections.emptyList();
        lastQueueOrGameMs = 0L;
        sawUnsupportedMode = false;
    }

    private static void writeLoop() {
        while (true) {
            try {
                Thread.sleep(WRITE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!pending) continue;
            pending = false;
            Lobby snapshot = latest;
            if (snapshot != null) writeIfChanged(snapshot);
        }
    }

    private static void writeIfChanged(Lobby lobby) {
        if (WRITER == null || TEMP_FILE == null) return;
        try {
            JsonObject tree = GSON.toJsonTree(lobby).getAsJsonObject();
            tree.addProperty("jvmPid", WRITER.pid);
            tree.addProperty("jvmStartTimeMs", WRITER.startTimeMs);
            String body = tree.toString(); // seq-free, so it changes only when the roster does
            if (body.equals(lastBody)) return;
            seq++;
            tree.addProperty("seq", seq);
            writeAtomically(GSON.toJson(tree));
            lastBody = body;
        } catch (Throwable ignored) {
            deleteOwnedTempBestEffort();
            // never propagate into the game; the launcher degrades to its idle state on a missing file
        }
    }

    private static File tempFileFor(JvmWriterIdentity identity) {
        if (identity == null) return null;
        File dir = new File(System.getProperty("user.home"), ".cobblify");
        return new File(dir, identity.tempFileName());
    }

    private static void writeAtomically(String json) throws Exception {
        File dir = new File(System.getProperty("user.home"), ".cobblify");
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File file = new File(dir, "lobby.json");
        File tmp = TEMP_FILE;
        if (tmp == null) return;
        Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
        try {
            w.write(json);
        } finally {
            w.close();
        }
        try {
            Files.move(tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicUnsupported) {
            if (!tmp.renameTo(file)) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Best-effort delete of this JVM's temp file only; used after a failed write. */
    static void deleteOwnedTempBestEffort() {
        File tmp = TEMP_FILE;
        if (tmp != null && tmp.isFile()) {
            try {
                tmp.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Strip Minecraft {@code §x} color codes from a rank label, leaving plain text (e.g. {@code [MVP+]}). */
    public static String stripColors(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                i++; // skip the code char too
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ---- contract DTOs (field names ARE the JSON keys; do not rename) ----

    /**
     * Launcher header for a queue/game snapshot. Maps Hypixel's sidebar {@code Mode:} value (and
     * {@link com.bedwarsqol.stats.BedwarsMode#label()} fallbacks) onto the demo titles:
     * {@code Solos}, {@code Doubles}, {@code 3v3v3v3}, {@code 4v4v4v4}, {@code 4v4}.
     * Recognized labels may carry a decorative, non-alphanumeric suffix from the scoreboard; that
     * suffix is discarded so a seasonal glyph cannot leak into the launcher title. {@code Overall}
     * and blank input are not a mode — returns null so the UI stays generic.
     */
    public static String dashboardModeLabel(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        if (isLabelWithDecoration(v, "overall")) return null;
        if (isLabelWithDecoration(v, "solo") || isLabelWithDecoration(v, "solos")) return "Solos";
        if (isLabelWithDecoration(v, "doubles")) return "Doubles";
        if (isLabelWithDecoration(v, "3v3v3v3")) return "3v3v3v3";
        if (isLabelWithDecoration(v, "4v4v4v4")) return "4v4v4v4";
        if (isLabelWithDecoration(v, "4v4")) return "4v4";
        return v;
    }

    /**
     * Whether {@code raw} canonicalizes to a mode the overlay may show: Solos, Doubles, 3v3v3v3,
     * 4v4v4v4, or 4v4. Unknown strings (Castle, Rush, Doubles Rush) are not supported even though
     * {@link #dashboardModeLabel} still passes them through for diagnostics.
     */
    public static boolean isSupportedDashboardMode(String raw) {
        String mapped = dashboardModeLabel(raw);
        return "Solos".equals(mapped)
                || "Doubles".equals(mapped)
                || "3v3v3v3".equals(mapped)
                || "4v4v4v4".equals(mapped)
                || "4v4".equals(mapped);
    }

    /**
     * Overlay eligibility. {@code sidebarMode} is the live {@code Mode:} line, or {@code null} when
     * that line is absent. {@code retainedIfNoSidebar} is consulted only in that absent-line branch
     * (a present unsupported label clears retention; it must not fall back to a previous Solo).
     */
    public static EvalResult evaluate(boolean inHypixel, boolean rawInBedwars,
                                      boolean queue, boolean game,
                                      String sidebarMode, String retainedIfNoSidebar) {
        return evaluate(inHypixel, rawInBedwars, queue, game, sidebarMode, retainedIfNoSidebar,
                System.currentTimeMillis());
    }

    public static EvalResult evaluate(boolean inHypixel, boolean rawInBedwars,
                                      boolean queue, boolean game,
                                      String sidebarMode, String retainedIfNoSidebar,
                                      long nowMs) {
        EvalResult r = new EvalResult();
        if (!inHypixel) {
            r.context = "MENU";
            lastQueueOrGameMs = 0L;
            sawUnsupportedMode = false;
            return r;
        }
        if (game) r.context = "GAME";
        else if (queue) r.context = "QUEUE";
        else r.context = "LOBBY";

        if (queue || game) lastQueueOrGameMs = nowMs;

        if (!queue && !game) {
            r.eligible = rawInBedwars;
            boolean modeLineGap = rawInBedwars && lastQueueOrGameMs > 0L
                    && nowMs - lastQueueOrGameMs < MATCH_GAP_MS;
            if (modeLineGap && isSupportedDashboardMode(retainedIfNoSidebar)) {
                r.modeToRetain = dashboardModeLabel(retainedIfNoSidebar);
            }
            if (r.eligible && !modeLineGap) sawUnsupportedMode = false;
        } else if (sidebarMode != null && !sidebarMode.trim().isEmpty()) {
            if (isSupportedDashboardMode(sidebarMode)) {
                r.eligible = true;
                r.modeToRetain = dashboardModeLabel(sidebarMode);
                sawUnsupportedMode = false;
            } else {
                sawUnsupportedMode = true;
            }
        } else if (isSupportedDashboardMode(retainedIfNoSidebar)) {
            r.eligible = true;
            r.modeToRetain = dashboardModeLabel(retainedIfNoSidebar);
        }
        r.scanFullRoster = r.eligible && !"QUEUE".equals(r.context);
        r.readQueuePartyFromTab = "QUEUE".equals(r.context);
        // The BedWars lobby is listed but never fetched: its tab is the whole lobby population
        // (tens of names), and enqueueing all of them on every visit starves the chat/hover/game
        // lookups that share the backend's per-IP budget. Queue party and game rosters still fetch.
        r.autoFetch = r.eligible && !"LOBBY".equals(r.context);
        return r;
    }

    /**
     * Fill party / roster / teams from already-filtered rows. Never fetches unless
     * {@link EvalResult#autoFetch} is true. Queue tab names populate {@code yourParty} even when
     * ineligible (Hypixel's queue tab <i>is</i> the party); they still do not fetch.
     */
    public static void applySnapshot(Lobby lobby, EvalResult r,
                                     List<String> chatParty, List<TabRow> tab,
                                     List<String> extraPlayers,
                                     PlayerFactory factory, FetchSink sink) {
        if (lobby == null || r == null) return;
        PlayerFactory make = factory != null ? factory : new PlayerFactory() {
            public Player player(String name) { return new Player(name); }
        };
        lobby.context = r.context;
        lobby.dashboardEligible = r.eligible;
        lobby.mode = r.eligible ? r.modeToRetain : null;
        lobby.yourParty.clear();
        lobby.players.clear();
        lobby.teams.clear();

        boolean usedQueueTab = false;
        if (r.readQueuePartyFromTab && tab != null) {
            for (int i = 0; i < tab.size(); i++) {
                TabRow row = tab.get(i);
                if (row == null || row.name == null) continue;
                if (r.autoFetch && sink != null) sink.fetch(row);
                lobby.yourParty.add(make.player(row.name));
                usedQueueTab = true;
            }
        }
        if (!usedQueueTab && chatParty != null) {
            for (int i = 0; i < chatParty.size(); i++) {
                String name = chatParty.get(i);
                if (name != null) lobby.yourParty.add(make.player(name));
            }
        }
        if (usedQueueTab) {
            List<String> names = new ArrayList<String>(lobby.yourParty.size());
            for (int i = 0; i < lobby.yourParty.size(); i++) {
                names.add(lobby.yourParty.get(i).name);
            }
            retainedQueueParty = Collections.unmodifiableList(names);
        } else if ("MENU".equals(r.context) || (r.eligible && "LOBBY".equals(r.context))) {
            retainedQueueParty = Collections.emptyList();
        } else if (lobby.yourParty.isEmpty() && !retainedQueueParty.isEmpty()
                && ("GAME".equals(r.context) || !r.eligible)) {
            for (int i = 0; i < retainedQueueParty.size(); i++) {
                String name = retainedQueueParty.get(i);
                if (name != null) lobby.yourParty.add(make.player(name));
            }
        }

        if (r.scanFullRoster) {
            List<TabRow> rows = tab != null ? tab : java.util.Collections.<TabRow>emptyList();
            Map<String, Team> teams = new LinkedHashMap<String, Team>();
            for (int i = 0; i < rows.size(); i++) {
                TabRow row = rows.get(i);
                if (row == null || row.name == null) continue;
                if (r.autoFetch && sink != null) sink.fetch(row);
                Player p = make.player(row.name);
                p.presence = row.presence;
                lobby.players.add(p);
                if (row.teamName != null) {
                    Team team = teams.get(row.teamName);
                    if (team == null) {
                        team = new Team(row.teamName);
                        teams.put(row.teamName, team);
                    }
                    team.players.add(p);
                }
            }
            lobby.teams.addAll(teams.values());
        } else if (r.eligible && extraPlayers != null) {
            for (int i = 0; i < extraPlayers.size(); i++) {
                String name = extraPlayers.get(i);
                if (name != null) lobby.players.add(make.player(name));
            }
        }
    }

    /**
     * Exact label, case-insensitive, optionally followed only by whitespace/symbol decoration.
     * The rule itself lives in {@link SidebarModeLabel}, so the launcher titles here and the
     * in-game mode detector read a sidebar label the same way.
     */
    private static boolean isLabelWithDecoration(String value, String label) {
        return SidebarModeLabel.isLabelWithDecoration(value, label);
    }

    /** Root of {@code lobby.json}; {@code seq} and writer identity are injected by the writer. */
    public static final class Lobby {
        public final int v = 2;
        public long jvmPid;
        public long jvmStartTimeMs;
        public String context = "MENU";     // MENU | LOBBY | QUEUE | GAME
        public boolean inHypixel;
        public boolean dashboardEligible;
        public String self;
        public String mode;                  // Solos|Doubles|3v3v3v3|4v4v4v4|4v4|…; null in lobby/menu
        public Integer partyCount;           // null = unknown / feature off
        public List<Player> yourParty = new ArrayList<Player>();
        public List<Player> players = new ArrayList<Player>();
        public List<Team> teams = new ArrayList<Team>();
    }

    public static final class Team {
        public String name;                  // Red|Blue|Green|Yellow|Aqua|White|Pink|Gray|Unknown
        public List<Player> players = new ArrayList<Player>();

        public Team(String name) {
            this.name = name;
        }
    }

    /**
     * One overlay row. Every presentation decision (tier, cheater, badge, chips, mode, nick reveal)
     * is filled from a {@link PlayerCard}; the launcher draws these fields and derives nothing.
     */
    public static final class Player {
        public String name;
        public String state = "LOADING";     // OK|NICKED|NEVER_PLAYED|ERROR|LOADING
        public boolean nicked;
        public String realName;              // verified + revealed real IGN, else null
        public String rank = "";             // plain text, no color codes
        public String rankCodes = "";        // the same label with its §-color codes
        public String mode = PlayerCard.OVERALL; // Overall|Solo|2s|3s|4s - where the numbers came from
        public double fkdr;
        public double wlr;
        public int finalKills;
        public double kd;
        public int fkdrTier;                 // 0..3, BedwarsStats.fkdrTier
        public boolean cheater;              // priority tag is a cheater type
        public PlayerCard.Chip badge;        // single priority chip, or null
        public List<PlayerCard.Chip> chips = new ArrayList<PlayerCard.Chip>();
        public int seraphThreat = -1;        // display only; -1 = unknown / gated
        /** ACTIVE|DISCONNECTED|ELIMINATED|MISSING — standing in the current game; see {@link GameRoster}. */
        public String presence = GameRoster.ACTIVE;

        public Player(String name) {
            this.name = name;
        }

        /** Copy every decision from {@code c}; {@code name} and {@code presence} are left alone. */
        public void apply(PlayerCard c) {
            if (c == null) return;
            state = c.stateName();
            nicked = c.nicked;
            realName = c.realName;
            rank = c.rank;
            rankCodes = c.rankCodes;
            mode = c.modeLabel;
            fkdr = c.fkdr;
            wlr = c.wlr;
            finalKills = c.finalKills;
            kd = c.kd;
            fkdrTier = c.fkdrTier;
            cheater = c.cheater;
            badge = c.badge;
            chips = new ArrayList<PlayerCard.Chip>(c.chips);
            seraphThreat = c.seraphThreat;
        }
    }

    /** Outcome of {@link LobbyExport#evaluate}. Default flags are all false / MENU. */
    public static final class EvalResult {
        public String context = "MENU";
        public boolean eligible;
        public String modeToRetain;
        public boolean scanFullRoster;
        public boolean readQueuePartyFromTab;
        public boolean autoFetch;
    }

    /**
     * Already-filtered tab row. {@code teamName} is set only for GAME grouping; {@code presence} is
     * the roster's standing for the row (ACTIVE for a plain tab scan).
     */
    public static final class TabRow {
        public final String name;
        public final String teamName;
        public final String presence;

        public TabRow(String name) {
            this(name, null);
        }

        public TabRow(String name, String teamName) {
            this(name, teamName, GameRoster.ACTIVE);
        }

        public TabRow(String name, String teamName, String presence) {
            this.name = name;
            this.teamName = teamName;
            this.presence = presence != null ? presence : GameRoster.ACTIVE;
        }
    }

    public interface PlayerFactory {
        Player player(String name);
    }

    public interface FetchSink {
        void fetch(TabRow row);
    }
}
