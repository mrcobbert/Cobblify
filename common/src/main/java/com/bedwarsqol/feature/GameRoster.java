package com.bedwarsqol.feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The roster of one Bedwars game, remembered for the life of that game.
 *
 * <p>The tab list tells this class who <i>joined</i> the sheet; chat tells it who <i>left</i>;
 * nothing else moves a row. Hypixel drops a player from tab while they respawn (5 s), when they
 * disconnect and when they are eliminated, so a stateless tab scan makes every death look like a
 * departure. Here a player seen once stays until the session ends, and only a chat broadcast
 * ({@link GameChatLine}) or a long unexplained absence changes their {@code presence}.
 *
 * <p>Rules:
 * <ol>
 *   <li><b>Session-scoped.</b> A different session id clears everything.</li>
 *   <li><b>Tab adds and refreshes, never removes.</b> A row seen in tab is upserted: last-seen time,
 *       team upgraded from unknown to a colour (never downgraded). A sighting clears the disconnect
 *       flag only once the roster has seen the player <i>leave</i> tab since the disconnect line —
 *       a scan that lands before the tab removal propagates must not erase an authoritative
 *       broadcast. Entries whose team is still unknown are re-resolved on every observation,
 *       present in tab or not.</li>
 *   <li><b>Chat changes standing, never membership.</b> Names are matched against existing entries
 *       only; an unknown name is ignored.</li>
 *   <li><b>Presence is derived, in priority order:</b> {@code ELIMINATED} (sticky) →
 *       {@code DISCONNECTED} → {@code MISSING} (absent ≥ {@link #MISSING_AFTER_MS}) →
 *       {@code ACTIVE}.</li>
 *   <li><b>Stats, once resolved, stay resolved</b> for the game ({@link #withRetainedStats}).</li>
 * </ol>
 *
 * <p>Platform-neutral and synchronized; the mod drives {@link #INSTANCE} from the client thread and
 * tests drive fresh instances with an injected clock. Rows are emitted in first-seen order.
 */
public final class GameRoster {

    public static final String ACTIVE = "ACTIVE";
    public static final String DISCONNECTED = "DISCONNECTED";
    public static final String ELIMINATED = "ELIMINATED";
    public static final String MISSING = "MISSING";

    /** Six respawns and past any reconnect grace: an unexplained absence this long is not a death. */
    public static final long MISSING_AFTER_MS = 30_000L;

    /** Placeholder team the snapshot emits when the scoreboard names none; never overwrites a colour. */
    public static final String UNKNOWN_TEAM = "Unknown";

    public static final GameRoster INSTANCE = new GameRoster();

    /** Scoreboard lookup for a name; returns null or {@link #UNKNOWN_TEAM} when the team is unknown. */
    public interface TeamResolver {
        String teamOf(String name);
    }

    private static final class Entry {
        final String name;
        String team;
        long lastSeenMs;
        boolean disconnected;
        /** Seen absent from tab since the disconnect line; only then does a sighting mean "back". */
        boolean leftTabSinceDisconnect;
        boolean eliminated;
        LobbyExport.Player retained;

        Entry(String name, long nowMs) {
            this.name = name;
            this.lastSeenMs = nowMs;
        }
    }

    private boolean bound;
    private int sessionId;
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();

    /**
     * Merge one tab scan into the roster for {@code sessionId} and return the remembered rows, each
     * carrying its team and presence. {@code resolver} may be null.
     */
    public synchronized List<LobbyExport.TabRow> observe(int sessionId, long nowMs,
                                                         List<LobbyExport.TabRow> tab,
                                                         TeamResolver resolver) {
        bind(sessionId);
        Map<String, Entry> absent = new LinkedHashMap<String, Entry>(entries);
        if (tab != null) {
            for (int i = 0; i < tab.size(); i++) {
                LobbyExport.TabRow row = tab.get(i);
                if (row == null || row.name == null) continue;
                Entry e = entries.get(key(row.name));
                if (e == null) {
                    e = new Entry(row.name, nowMs);
                    entries.put(key(row.name), e);
                }
                absent.remove(key(row.name));
                e.lastSeenMs = nowMs;
                if (e.disconnected && e.leftTabSinceDisconnect) e.disconnected = false;
                if (isKnownTeam(row.teamName)) e.team = row.teamName;
                else if (e.team == null) e.team = row.teamName;
            }
        }
        for (Entry e : absent.values()) {
            if (e.disconnected) e.leftTabSinceDisconnect = true;
        }
        List<LobbyExport.TabRow> out = new ArrayList<LobbyExport.TabRow>(entries.size());
        for (Entry e : entries.values()) {
            if (!isKnownTeam(e.team) && resolver != null) {
                String t = resolver.teamOf(e.name);
                if (isKnownTeam(t)) e.team = t;
            }
            out.add(new LobbyExport.TabRow(e.name, e.team, presence(e, nowMs)));
        }
        return out;
    }

    /** Apply one colour-stripped chat line for {@code sessionId}. Lines that name nobody we know are ignored. */
    public synchronized void onChat(int sessionId, long nowMs, String plainLine) {
        bind(sessionId);
        GameChatLine line = GameChatLine.parse(plainLine);
        if (line == null) return;
        switch (line.kind) {
            case DISCONNECT: {
                Entry e = entries.get(key(line.name));
                if (e != null) {
                    e.disconnected = true;
                    e.leftTabSinceDisconnect = false;
                }
                break;
            }
            case RECONNECT: {
                Entry e = entries.get(key(line.name));
                if (e != null) {
                    e.disconnected = false;
                    e.lastSeenMs = nowMs;
                }
                break;
            }
            case FINAL_KILL: {
                Entry e = entries.get(key(line.name));
                if (e != null) e.eliminated = true;
                break;
            }
            case TEAM_ELIMINATED: {
                for (Entry e : entries.values()) {
                    if (e.team != null && e.team.equalsIgnoreCase(line.team)) e.eliminated = true;
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * Keep a player's stats for the game. A terminal {@code fresh} (OK / NEVER_PLAYED / NICKED) is
     * remembered for {@code name}; a non-terminal one (LOADING / ERROR) is replaced by a copy of the
     * remembered stats when there are any. Always returns a fresh object, never a shared one.
     */
    public synchronized LobbyExport.Player withRetainedStats(String name, LobbyExport.Player fresh) {
        if (fresh == null || name == null) return fresh;
        Entry e = entries.get(key(name));
        if (e == null) return fresh;
        if (isTerminal(fresh.state)) {
            e.retained = copy(fresh);
            return fresh;
        }
        return e.retained == null ? fresh : copy(e.retained);
    }

    /** Forget everything; the next observation starts a fresh roster. */
    public synchronized void reset() {
        entries.clear();
        bound = false;
    }

    /** Test/diagnostic: the presence {@code name} would be emitted with at {@code nowMs}, or null if unknown. */
    public synchronized String presenceOf(String name, long nowMs) {
        Entry e = entries.get(key(name));
        return e == null ? null : presence(e, nowMs);
    }

    public synchronized int size() {
        return entries.size();
    }

    private void bind(int id) {
        if (bound && id == sessionId) return;
        entries.clear();
        sessionId = id;
        bound = true;
    }

    private static String presence(Entry e, long nowMs) {
        if (e.eliminated) return ELIMINATED;
        if (e.disconnected) return DISCONNECTED;
        if (nowMs - e.lastSeenMs >= MISSING_AFTER_MS) return MISSING;
        return ACTIVE;
    }

    private static boolean isKnownTeam(String team) {
        return team != null && !team.isEmpty() && !UNKNOWN_TEAM.equals(team);
    }

    private static boolean isTerminal(String state) {
        return "OK".equals(state) || "NEVER_PLAYED".equals(state) || "NICKED".equals(state);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static LobbyExport.Player copy(LobbyExport.Player src) {
        LobbyExport.Player p = new LobbyExport.Player(src.name);
        p.state = src.state;
        p.nicked = src.nicked;
        p.realName = src.realName;
        p.rank = src.rank;
        p.fkdr = src.fkdr;
        p.wlr = src.wlr;
        p.finalKills = src.finalKills;
        p.kd = src.kd;
        p.seraphThreat = src.seraphThreat;
        p.seraphTags.addAll(src.seraphTags);
        p.urchinTags.addAll(src.urchinTags);
        p.presence = src.presence;
        return p;
    }
}
