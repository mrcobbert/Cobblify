package com.bedwarsqol.feature;

import com.bedwarsqol.stats.PlayerCard;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The roster rules: tab adds, chat changes standing, the session boundary clears, stats stick. */
public class GameRosterTest {

    private static final int S = 7;
    private static final long T0 = 1_000_000L;

    private static LobbyExport.TabRow row(String name, String team) {
        return new LobbyExport.TabRow(name, team);
    }

    private static List<LobbyExport.TabRow> tab(LobbyExport.TabRow... rows) {
        return Arrays.asList(rows);
    }

    private static List<String> names(List<LobbyExport.TabRow> rows) {
        List<String> out = new ArrayList<String>();
        for (LobbyExport.TabRow r : rows) out.add(r.name);
        return out;
    }

    private static LobbyExport.TabRow find(List<LobbyExport.TabRow> rows, String name) {
        for (LobbyExport.TabRow r : rows) if (r.name.equals(name)) return r;
        throw new AssertionError("no row " + name + " in " + names(rows));
    }

    /** A1: a player seen once stays for the session, in first-seen order, whether or not in tab now. */
    @Test
    public void remembersPlayersAbsentFromLaterScans() {
        GameRoster r = new GameRoster();
        r.observe(S, T0, tab(row("Alice", "Red"), row("Bob", "Blue")), null);
        List<LobbyExport.TabRow> out = r.observe(S, T0 + 400, tab(row("Alice", "Red")), null);
        assertEquals(Arrays.asList("Alice", "Bob"), names(out));
        assertEquals("Blue", find(out, "Bob").teamName);
        assertEquals(GameRoster.ACTIVE, find(out, "Bob").presence);
        // A newcomer is appended after the players seen before them.
        out = r.observe(S, T0 + 800, tab(row("Carol", "Green")), null);
        assertEquals(Arrays.asList("Alice", "Bob", "Carol"), names(out));
    }

    /** A2: disconnect → DISCONNECTED; reconnect line or reappearance → ACTIVE. */
    @Test
    public void presenceFollowsDisconnectAndReconnect() {
        GameRoster r = new GameRoster();
        r.observe(S, T0, tab(row("Alice", "Red"), row("Bob", "Blue")), null);
        r.onChat(S, T0 + 100, "Bob disconnected.");
        assertEquals(GameRoster.DISCONNECTED, find(r.observe(S, T0 + 400, tab(row("Alice", "Red")), null), "Bob").presence);
        r.onChat(S, T0 + 5_000, "Bob reconnected.");
        assertEquals(GameRoster.ACTIVE, find(r.observe(S, T0 + 5_400, tab(row("Alice", "Red")), null), "Bob").presence);
        // Leaving tab and reappearing also clears it, without a reconnect line.
        r.onChat(S, T0 + 6_000, "Bob disconnected.");
        assertEquals(GameRoster.DISCONNECTED, r.presenceOf("Bob", T0 + 6_000));
        r.observe(S, T0 + 6_400, tab(row("Alice", "Red")), null);
        r.observe(S, T0 + 6_800, tab(row("Alice", "Red"), row("Bob", "Blue")), null);
        assertEquals(GameRoster.ACTIVE, r.presenceOf("Bob", T0 + 6_800));
    }

    /** A2 (code review I1): a scan that still lists a just-disconnected player must not clear the flag. */
    @Test
    public void disconnectSurvivesAStaleTabScan() {
        GameRoster r = new GameRoster();
        List<LobbyExport.TabRow> both = tab(row("Alice", "Red"), row("Bob", "Blue"));
        List<LobbyExport.TabRow> only = tab(row("Alice", "Red"));
        r.observe(S, T0, both, null);
        r.onChat(S, T0 + 100, "Bob disconnected.");
        // Tab removal has not propagated yet: Bob is still listed on the next scan.
        assertEquals(GameRoster.DISCONNECTED, find(r.observe(S, T0 + 400, both, null), "Bob").presence);
        // Now he is gone from tab and stays DC, never MISSING, however long he is away.
        assertEquals(GameRoster.DISCONNECTED, find(r.observe(S, T0 + 800, only, null), "Bob").presence);
        assertEquals(GameRoster.DISCONNECTED, find(r.observe(S, T0 + 60_000, only, null), "Bob").presence);
        // Seen leaving, then seen back: that is a reconnect.
        assertEquals(GameRoster.ACTIVE, find(r.observe(S, T0 + 61_000, both, null), "Bob").presence);
    }

    /** A2: a FINAL KILL line eliminates the victim; nothing in tab ever clears it. */
    @Test
    public void eliminationSurvivesTabReappearance() {
        GameRoster r = new GameRoster();
        r.observe(S, T0, tab(row("Alice", "Red"), row("Bob", "Blue")), null);
        r.onChat(S, T0 + 100, "Bob was knocked into the void by Alice. FINAL KILL!");
        assertEquals(GameRoster.ELIMINATED, r.presenceOf("Bob", T0 + 100));
        r.observe(S, T0 + 400, tab(row("Alice", "Red"), row("Bob", "Blue")), null);
        assertEquals(GameRoster.ELIMINATED, r.presenceOf("Bob", T0 + 400));
        r.onChat(S, T0 + 500, "Bob reconnected.");
        assertEquals(GameRoster.ELIMINATED, r.presenceOf("Bob", T0 + 500));
        // ELIMINATED outranks a later disconnect too.
        r.onChat(S, T0 + 600, "Bob disconnected.");
        assertEquals(GameRoster.ELIMINATED, r.presenceOf("Bob", T0 + 600));
    }

    /** A2: a team line eliminates every remembered member whose team is that colour, and only them. */
    @Test
    public void teamEliminationReachesEveryKnownMember() {
        GameRoster r = new GameRoster();
        r.observe(S, T0, tab(row("Alice", "Red"), row("Bob", "Red"), row("Carol", "Blue")), null);
        r.onChat(S, T0 + 100, "TEAM ELIMINATED > Red Team has been eliminated!");
        assertEquals(GameRoster.ELIMINATED, r.presenceOf("Alice", T0 + 100));
        assertEquals(GameRoster.ELIMINATED, r.presenceOf("Bob", T0 + 100));
        assertEquals(GameRoster.ACTIVE, r.presenceOf("Carol", T0 + 100));
    }

    /** A2 (B2): a member seen before the scoreboard named their team, who then left tab, is still reached. */
    @Test
    public void teamEliminationReachesMemberResolvedAfterLeavingTab() {
        GameRoster r = new GameRoster();
        final Map<String, String> board = new HashMap<String, String>();
        GameRoster.TeamResolver resolver = new GameRoster.TeamResolver() {
            public String teamOf(String name) { return board.get(name); }
        };
        r.observe(S, T0, tab(row("Alice", GameRoster.UNKNOWN_TEAM)), resolver);
        // Alice is gone from tab before any scan saw her colour; the scoreboard names it later.
        board.put("Alice", "Red");
        List<LobbyExport.TabRow> out = r.observe(S, T0 + 400, Collections.<LobbyExport.TabRow>emptyList(), resolver);
        assertEquals("Red", find(out, "Alice").teamName);
        r.onChat(S, T0 + 500, "TEAM ELIMINATED > Red Team has been eliminated!");
        assertEquals(GameRoster.ELIMINATED, r.presenceOf("Alice", T0 + 500));
    }

    /** A2 (documented limit): a team the scoreboard never names cannot be matched; the row falls to MISSING. */
    @Test
    public void teamEliminationCannotReachUnresolvedMember() {
        GameRoster r = new GameRoster();
        GameRoster.TeamResolver never = new GameRoster.TeamResolver() {
            public String teamOf(String name) { return null; }
        };
        r.observe(S, T0, tab(row("Alice", GameRoster.UNKNOWN_TEAM)), never);
        r.observe(S, T0 + 400, Collections.<LobbyExport.TabRow>emptyList(), never);
        r.onChat(S, T0 + 500, "TEAM ELIMINATED > Red Team has been eliminated!");
        assertEquals(GameRoster.ACTIVE, r.presenceOf("Alice", T0 + 500));
        assertEquals(GameRoster.MISSING, r.presenceOf("Alice", T0 + GameRoster.MISSING_AFTER_MS));
    }

    /** Rule 2: a colour, once known, is never downgraded by an Unknown/null row. */
    @Test
    public void knownTeamIsNeverDowngraded() {
        GameRoster r = new GameRoster();
        r.observe(S, T0, tab(row("Alice", "Red")), null);
        List<LobbyExport.TabRow> out = r.observe(S, T0 + 400, tab(row("Alice", GameRoster.UNKNOWN_TEAM)), null);
        assertEquals("Red", find(out, "Alice").teamName);
        out = r.observe(S, T0 + 800, tab(row("Alice", null)), null);
        assertEquals("Red", find(out, "Alice").teamName);
        // Unknown → colour upgrades.
        r.observe(S, T0, tab(row("Bob", GameRoster.UNKNOWN_TEAM)), null);
        out = r.observe(S, T0 + 400, tab(row("Bob", "Blue")), null);
        assertEquals("Blue", find(out, "Bob").teamName);
    }

    /** Rule 3: chat never adds a player. */
    @Test
    public void chatNeverAddsPlayers() {
        GameRoster r = new GameRoster();
        r.observe(S, T0, tab(row("Alice", "Red")), null);
        r.onChat(S, T0 + 100, "vj3x1s4w18 disconnected.");
        r.onChat(S, T0 + 100, "Mallory was killed by Alice. FINAL KILL!");
        assertEquals(1, r.size());
        assertNull(r.presenceOf("Mallory", T0 + 100));
    }

    /** A3: 29 999 ms of absence changes nothing; 30 000 ms reads MISSING; reappearance restores ACTIVE. */
    @Test
    public void missingAfterThirtySecondsAndBack() {
        GameRoster r = new GameRoster();
        r.observe(S, T0, tab(row("Alice", "Red"), row("Bob", "Blue")), null);
        List<LobbyExport.TabRow> only = tab(row("Alice", "Red"));
        assertEquals(GameRoster.ACTIVE, find(r.observe(S, T0 + 5_000, only, null), "Bob").presence);
        assertEquals(GameRoster.ACTIVE, find(r.observe(S, T0 + 29_999, only, null), "Bob").presence);
        assertEquals(GameRoster.MISSING, find(r.observe(S, T0 + 30_000, only, null), "Bob").presence);
        assertEquals(GameRoster.ACTIVE,
                find(r.observe(S, T0 + 31_000, tab(row("Alice", "Red"), row("Bob", "Blue")), null), "Bob").presence);
    }

    /** A4: a different session id discards the roster; the first scan of the new game is tab alone. */
    @Test
    public void newSessionDiscardsRoster() {
        GameRoster r = new GameRoster();
        r.observe(S, T0, tab(row("Alice", "Red"), row("Bob", "Blue")), null);
        r.onChat(S, T0 + 100, "Bob was killed by Alice. FINAL KILL!");
        List<LobbyExport.TabRow> out = r.observe(S + 1, T0 + 200, tab(row("Carol", "Green")), null);
        assertEquals(Arrays.asList("Carol"), names(out));
        // A stale chat line for the old game, arriving first for the new id, also starts clean.
        r.onChat(S + 2, T0 + 300, "Carol disconnected.");
        assertEquals(0, r.size());
    }

    /** A4: reset() empties the roster regardless of session. */
    @Test
    public void resetClearsRoster() {
        GameRoster r = new GameRoster();
        r.observe(S, T0, tab(row("Alice", "Red")), null);
        r.reset();
        assertEquals(0, r.size());
        assertEquals(Arrays.asList("Bob"), names(r.observe(S, T0 + 100, tab(row("Bob", "Blue")), null)));
    }

    /** A5: a resolved player is remembered and stands in for a later cache miss; copies are fresh objects. */
    @Test
    public void retainsResolvedStatsAcrossCacheMiss() {
        GameRoster r = new GameRoster();
        r.observe(S, T0, tab(row("Alice", "Red")), null);
        LobbyExport.Player ok = new LobbyExport.Player("Alice");
        ok.state = "OK";
        ok.fkdr = 6.8;
        ok.wlr = 4.4;
        ok.finalKills = 17700;
        ok.kd = 3.6;
        ok.rank = "[MVP+]";
        ok.seraphThreat = 2;
        ok.fkdrTier = 2;
        ok.cheater = true;
        ok.badge = new PlayerCard.Chip("BC", "§6", "Blatant Cheater", false);
        ok.chips.add(ok.badge);
        assertSame(ok, r.withRetainedStats("Alice", ok));

        LobbyExport.Player loading = new LobbyExport.Player("Alice");
        LobbyExport.Player served = r.withRetainedStats("Alice", loading);
        assertNotSame(loading, served);
        assertNotSame(ok, served);
        assertEquals("OK", served.state);
        assertEquals(6.8, served.fkdr, 0.0);
        assertEquals(17700, served.finalKills);
        assertEquals("[MVP+]", served.rank);
        assertEquals(2, served.seraphThreat);
        assertEquals(2, served.fkdrTier);
        assertTrue(served.cheater);
        assertEquals("BC", served.badge.code);
        assertEquals(1, served.chips.size());
        assertEquals("Blatant Cheater", served.chips.get(0).label);

        LobbyExport.Player error = new LobbyExport.Player("Alice");
        error.state = "ERROR";
        assertEquals("OK", r.withRetainedStats("Alice", error).state);
        // A newer terminal result replaces the memory - every terminal state is retained.
        LobbyExport.Player never = new LobbyExport.Player("Alice");
        never.state = "NEVER_PLAYED";
        r.withRetainedStats("Alice", never);
        assertEquals("NEVER_PLAYED", r.withRetainedStats("Alice", new LobbyExport.Player("Alice")).state);
        LobbyExport.Player nicked = new LobbyExport.Player("Alice");
        nicked.state = "NICKED";
        nicked.nicked = true;
        nicked.realName = "Frostbyte_";
        r.withRetainedStats("Alice", nicked);
        LobbyExport.Player servedNick = r.withRetainedStats("Alice", new LobbyExport.Player("Alice"));
        assertEquals("NICKED", servedNick.state);
        assertTrue(servedNick.nicked);
        assertEquals("Frostbyte_", servedNick.realName);
        // ERROR is not terminal: it never displaces a retained result.
        LobbyExport.Player err2 = new LobbyExport.Player("Alice");
        err2.state = "ERROR";
        assertEquals("NICKED", r.withRetainedStats("Alice", err2).state);
        assertEquals("NICKED", r.withRetainedStats("Alice", new LobbyExport.Player("Alice")).state);
    }

    @Test
    public void unknownPlayersAndUnresolvedPlayersPassThrough() {
        GameRoster r = new GameRoster();
        r.observe(S, T0, tab(row("Alice", "Red")), null);
        LobbyExport.Player stranger = new LobbyExport.Player("Zed");
        assertSame(stranger, r.withRetainedStats("Zed", stranger));
        LobbyExport.Player loading = new LobbyExport.Player("Alice");
        assertSame(loading, r.withRetainedStats("Alice", loading)); // nothing retained yet
    }

    @Test
    public void namesMatchCaseInsensitively() {
        GameRoster r = new GameRoster();
        r.observe(S, T0, tab(row("Alice", "Red")), null);
        r.onChat(S, T0 + 100, "alice disconnected.");
        assertEquals(GameRoster.DISCONNECTED, r.presenceOf("ALICE", T0 + 100));
        assertEquals(1, r.observe(S, T0 + 400, tab(row("ALICE", "Red")), null).size());
    }
}
