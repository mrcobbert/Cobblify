package com.bedwarsqol.rewind;

import com.bedwarsqol.feature.HypixelLocationCodec.Location;
import com.bedwarsqol.feature.SessionChatLine.Kind;
import com.bedwarsqol.rewind.RewindGameRules.Classification;
import com.bedwarsqol.rewind.RewindGameRules.Command;
import com.bedwarsqol.rewind.RewindGameRules.CommandType;
import com.bedwarsqol.rewind.RewindGameRules.EndReason;
import com.bedwarsqol.rewind.RewindGameRules.LocationLogEntry;
import com.bedwarsqol.rewind.RewindGameRules.Result;
import com.bedwarsqol.rewind.RewindGameRules.StartSource;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Game-boundary rules fed with scripted event sequences (A6, A8 rules side, A10). Time is in
 * microseconds; {@code S} is one second. Server names are fake.
 */
public class RewindGameRulesTest {

    private static final long S = RewindGameRules.SECOND_US;

    // Lobby signals, one each.
    private static final Location MAIN = new Location("lobby7", "MAIN", null, null, null);
    private static final Location TOURNAMENT = new Location("tlobby1", "TOURNAMENT", null, null, null);
    private static final Location LOBBY_NAME_ONLY = new Location("lobby9", null, "lobby9", null, null);
    private static final Location BW_HUB = new Location("bwlobby3", "BEDWARS", "bedwarslobby3", null, null);

    private static final Location BW_A = new Location("mini12A", "BEDWARS", null, "BEDWARS_EIGHT_ONE", "Lighthouse");
    private static final Location BW_B = new Location("mini30C", "BEDWARS", null, "BEDWARS_FOUR_FOUR", "Aquarium");
    private static final Location BW_MODE_ONLY = new Location("mini5Z", null, null, "BEDWARS_EIGHT_TWO", null);
    private static final Location SKYWARS = new Location("mini77D", "SKYWARS", null, "solo_normal", "Fakeland");
    private static final Location NO_MODE = new Location("m55Q", "SKYWARS", null, null, null);
    private static final Location BARE = new Location("limbo2", null, null, null, null);
    private static final Location SKYBLOCK = new Location("mega3X", "SKYBLOCK", null, "hub", null);
    private static final Location HOUSING = new Location("housing2", "HOUSING", null, null, null);

    /**
     * A rules instance plus every command it emitted, in order. It stands in for the capture handler too: a failure
     * latches in the latest boundary's segment, and after a REARM copying resumes at the next boundary.
     */
    private static final class Script {
        final RewindGameRules rules = new RewindGameRules();
        final List<Command> all = new ArrayList<Command>();
        List<Command> last = new ArrayList<Command>();
        int lastSeg;
        boolean rearmPending;

        Script take(List<Command> c) {
            last = c;
            all.addAll(c);
            for (Command x : c) if (x.type == CommandType.REARM) rearmPending = true;
            return this;
        }

        Script connect(long t) { return take(rules.onConnect(t, false, true)); }
        Script connectSinglePlayer(long t) { return take(rules.onConnect(t, true, true)); }
        Script boundary(long t, int seg) {
            if (rearmPending) {
                rules.onCaptureResumed(seg);
                rearmPending = false;
            }
            lastSeg = seg;
            return take(rules.onBoundary(t, seg));
        }
        Script location(long t, int seg, Location l) { return take(rules.onLocation(t, seg, l)); }
        Script tick(long t, boolean queue, boolean active) { return take(rules.tick(t, queue, active)); }
        Script idle(long t) { return tick(t, false, false); }
        Script line(long t, Kind k) { return take(rules.onGameLine(t, k)); }
        /** A capture failure latched in the latest boundary's segment. */
        Script fail(long t) { return take(rules.onCaptureFailure(t, lastSeg)); }
        Script captureFail(long t, int seg) { return take(rules.onCaptureFailure(t, seg)); }
        Script clientFail(long t, int seg) { return take(rules.onClientFailure(t, seg)); }
        Script writerFail(long t, long gameId) { return take(rules.onWriterFailure(t, gameId)); }
        Script progress(long t, int seg) { rules.onCaptureProgress(t, seg); return this; }
        Script disconnect(long t) { return take(rules.onDisconnect(t, false)); }
        Script quit(long t) { return take(rules.onDisconnect(t, true)); }
        Script record(long t, boolean on) { return take(rules.onRecordSetting(t, on)); }
        Script pendingBytes(long t, long b) { return take(rules.onPendingBytes(t, b)); }

        boolean onMarkerKeyIgnored(long t) { return take(rules.onMarkerKey(t)).last.isEmpty(); }

        /** Ticks every second from {@code from} to {@code to} inclusive with fixed sidebar flags. */
        Script ticks(long from, long to, boolean queue, boolean active) {
            for (long t = from; t <= to; t += S) tick(t, queue, active);
            return this;
        }

        List<Command> of(CommandType type) {
            List<Command> out = new ArrayList<Command>();
            for (Command c : all) if (c.type == type) out.add(c);
            return out;
        }

        int count(CommandType type) { return of(type).size(); }

        Command one(CommandType type) {
            List<Command> c = of(type);
            assertEquals("exactly one " + type + " in " + all, 1, c.size());
            return c.get(0);
        }

        boolean lastHas(CommandType type) {
            for (Command c : last) if (c.type == type) return true;
            return false;
        }
    }

    private static void assertRange(Command c, int first, int last) {
        assertEquals("first segment of " + c, first, c.firstSegment);
        assertEquals("last segment of " + c, last, c.lastSegment);
    }

    // ------------------------------------------------------------------ lobbies and Bed Wars

    @Test
    public void eachLobbySignalAloneNeverOpens() {
        for (Location lobby : new Location[]{MAIN, TOURNAMENT, LOBBY_NAME_ONLY, BW_HUB}) {
            Script s = new Script().connect(0).boundary(S, 1).location(S + 200_000, 1, lobby);
            s.ticks(2 * S, 9 * S, false, false);
            assertEquals(lobby.toString(), 0, s.count(CommandType.DROP));     // verdict waits 10 s
            s.ticks(10 * S, 30 * 60 * S, false, false);
            assertEquals(lobby.toString(), 0, s.count(CommandType.OPEN));
            assertRange(s.one(CommandType.DROP), 1, 1);
            s.disconnect(31 * 60 * S);
            assertEquals(0, s.count(CommandType.OPEN));
            assertEquals(0, s.count(CommandType.CLOSE));
            assertEquals(0, s.rules.visitsWithoutLocation());
            assertEquals(0, s.rules.bedwarsVisitsWithoutStart());
            assertEquals(0, s.rules.shortVisitsDropped());
            assertEquals(0, s.rules.lobbyOverridden());
        }
    }

    @Test
    public void lobbyThenBedwarsPregameThenGameThenWinClosesFifteenSecondsLater() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, MAIN);
        s.ticks(2 * S, 12 * S, false, false);                            // lobby dropped
        s.boundary(30 * S, 2).location(30 * S + 300_000, 2, BW_A);
        s.ticks(31 * S, 89 * S, true, false);                            // pregame queue
        assertEquals(0, s.count(CommandType.OPEN));
        s.tick(90 * S, false, true);                                      // active rising edge
        Command open = s.one(CommandType.OPEN);
        assertRange(open, 2, 2);
        assertEquals(90 * S, open.timeUs);                               // start = the active edge
        assertEquals(StartSource.BEDWARS, open.startSource);
        assertEquals("BEDWARS_EIGHT_ONE", open.info.mode);
        assertEquals("Lighthouse", open.info.map);
        assertEquals("BEDWARS", open.info.serverType);
        assertEquals(Classification.CLASSIFIED, open.info.classification);
        assertFalse(open.info.singlePlayer);

        s.ticks(91 * S, 600 * S, false, true);
        s.line(600 * S, Kind.WIN);
        s.ticks(601 * S, 614 * S, false, true);
        assertEquals(0, s.count(CommandType.CLOSE));
        s.tick(615 * S, false, true);
        Command close = s.one(CommandType.CLOSE);
        assertEquals(EndReason.RESULT, close.endedBy);
        assertEquals(Result.WIN, close.result);
        assertFalse(close.failed);
        assertEquals(StartSource.BEDWARS, close.stats.startSource);
        assertEquals("only the lobby segment dropped", 1, s.count(CommandType.DROP));
    }

    @Test
    public void leavingTheQueueDropsTheRunAndCountsIt() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.ticks(2 * S, 40 * S, true, false);
        s.boundary(41 * S, 2).location(41 * S + 100_000, 2, MAIN);      // back to a lobby
        assertTrue(s.lastHas(CommandType.DROP));
        assertRange(s.one(CommandType.DROP), 1, 1);
        assertEquals(1, s.rules.bedwarsVisitsWithoutStart());
        s.ticks(42 * S, 120 * S, false, false);
        assertEquals(0, s.count(CommandType.OPEN));

        Script d = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        d.ticks(2 * S, 40 * S, true, false).disconnect(41 * S);
        assertRange(d.one(CommandType.DROP), 1, 1);
        assertEquals(1, d.rules.bedwarsVisitsWithoutStart());
        assertEquals(0, d.count(CommandType.OPEN));
    }

    @Test
    public void aFortySecondBedwarsGameIsKeptAfterAWin() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.ticks(2 * S, 20 * S, true, false).tick(21 * S, false, true);
        assertEquals(21 * S, s.one(CommandType.OPEN).timeUs);
        s.ticks(22 * S, 60 * S, false, true).line(61 * S, Kind.WIN);    // 40 s after the start
        s.ticks(62 * S, 80 * S, false, false);
        Command close = s.one(CommandType.CLOSE);
        assertEquals(Result.WIN, close.result);
        assertEquals(EndReason.RESULT, close.endedBy);
        assertEquals("no minimum length for Bed Wars: nothing dropped", 0, s.count(CommandType.DROP));
        assertEquals(0, s.rules.shortVisitsDropped());
    }

    @Test
    public void aFortySecondBedwarsGameIsKeptAfterADisconnect() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.ticks(2 * S, 20 * S, true, false).tick(21 * S, false, true);
        s.ticks(22 * S, 60 * S, false, true).disconnect(61 * S);
        Command close = s.one(CommandType.CLOSE);
        assertEquals(EndReason.DISCONNECT, close.endedBy);
        assertEquals(Result.UNKNOWN, close.result);
        assertEquals(0, s.count(CommandType.DROP));
    }

    @Test
    public void aLossAlsoClosesFifteenSecondsLater() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true);
        s.line(100 * S, Kind.LOSS).line(101 * S, Kind.WIN);             // the first result wins
        s.ticks(102 * S, 116 * S, false, false);
        Command close = s.one(CommandType.CLOSE);
        assertEquals(Result.LOSS, close.result);
        assertEquals(115 * S, close.timeUs);
    }

    @Test
    public void aLobbyNameRunBecomesBedwarsWhenTheQueueAppearsWithinTenSeconds() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, LOBBY_NAME_ONLY);
        s.ticks(2 * S, 5 * S, false, false).tick(6 * S, true, false);
        assertEquals(1, s.rules.lobbyOverridden());
        s.ticks(7 * S, 30 * S, true, false);
        assertEquals(0, s.count(CommandType.DROP));
        s.tick(31 * S, false, true);
        Command open = s.one(CommandType.OPEN);
        assertRange(open, 1, 1);
        assertEquals(31 * S, open.timeUs);
        assertEquals(StartSource.BEDWARS, open.startSource);
    }

    @Test
    public void aQueueAfterTheLobbyVerdictDoesNotOverride() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, LOBBY_NAME_ONLY);
        s.ticks(2 * S, 12 * S, false, false);
        assertRange(s.one(CommandType.DROP), 1, 1);
        s.ticks(13 * S, 20 * S, true, false).tick(21 * S, false, true);
        assertEquals(0, s.rules.lobbyOverridden());
        assertEquals(0, s.count(CommandType.OPEN));
    }

    @Test
    public void staleSidebarFlagsFromThePreviousWorldDoNotOverrideALobby() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.ticks(2 * S, 10 * S, true, false);                             // queue flag set
        s.boundary(11 * S, 2).location(11 * S + 100_000, 2, MAIN);       // no tick in between
        s.ticks(12 * S, 30 * S, false, false);
        assertEquals(0, s.rules.lobbyOverridden());
        assertEquals(0, s.count(CommandType.OPEN));
    }

    // ------------------------------------------------------------------ short non-Bed-Wars visits

    @Test
    public void aGameServerVisitEndingAt59sByDisconnectIsDropped() {
        Script s = new Script().connect(0).boundary(10 * S, 1).location(10 * S + 100_000, 1, SKYWARS);
        s.ticks(11 * S, 68 * S, false, false).disconnect(69 * S);        // 59 s after the visit start
        assertEquals(0, s.count(CommandType.OPEN));
        assertRange(s.one(CommandType.DROP), 1, 1);
        assertEquals(1, s.rules.shortVisitsDropped());
    }

    @Test
    public void aGameServerVisitEndingAt59sByALobbySwitchIsDropped() {
        Script s = new Script().connect(0).boundary(10 * S, 1).location(10 * S + 100_000, 1, SKYWARS);
        s.ticks(11 * S, 68 * S, false, false);
        s.boundary(69 * S, 2);                                           // the switch happens at 59 s
        s.location(70 * S + 500_000, 2, MAIN);                           // its location lands after 60 s
        s.ticks(71 * S, 90 * S, false, false);
        assertEquals(0, s.count(CommandType.OPEN));
        assertEquals(1, s.rules.shortVisitsDropped());
        assertRange(s.of(CommandType.DROP).get(0), 1, 1);
    }

    @Test
    public void aGameServerVisitReaching60sOpensAtTheVisitStartAndIsKept() {
        Script s = new Script().connect(0).boundary(10 * S, 1).location(10 * S + 100_000, 1, SKYWARS);
        s.ticks(11 * S, 69 * S, false, false);
        assertEquals(0, s.count(CommandType.OPEN));
        s.idle(70 * S);
        Command open = s.one(CommandType.OPEN);
        assertEquals(10 * S, open.timeUs);                               // start = visit start
        assertEquals(StartSource.VISIT, open.startSource);
        assertRange(open, 1, 1);
        assertEquals("solo_normal", open.info.mode);
        s.boundary(80 * S, 2).location(80 * S + 100_000, 2, MAIN);
        Command close = s.one(CommandType.CLOSE);
        assertEquals(EndReason.LOBBY, close.endedBy);
        assertEquals(Result.UNKNOWN, close.result);
        assertEquals(0, s.rules.shortVisitsDropped());
    }

    @Test
    public void aDisconnectAtExactly60sStillKeepsTheVisit() {
        Script s = new Script().connect(0).boundary(10 * S, 1).location(10 * S + 100_000, 1, SKYWARS);
        s.ticks(11 * S, 69 * S, false, false).disconnect(70 * S);        // no tick at 60 s
        assertEquals(10 * S, s.one(CommandType.OPEN).timeUs);
        assertEquals(EndReason.DISCONNECT, s.one(CommandType.CLOSE).endedBy);
        assertEquals(0, s.rules.shortVisitsDropped());
    }

    @Test
    public void aBareServerNameIsAnUnclassifiedGameServer() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BARE);
        s.ticks(2 * S, 61 * S, false, false);
        Command open = s.one(CommandType.OPEN);
        assertEquals(Classification.UNCLASSIFIED, open.info.classification);
        assertEquals(StartSource.VISIT, open.startSource);
        LocationLogEntry e = s.rules.locationLog().get(0);
        assertEquals(RewindGameRules.VERDICT_UNCLASSIFIED, e.verdict);
        assertFalse(e.hasServerType);
        assertEquals(0, e.serverTypeCode);
    }

    @Test
    public void noModeSkyblockAndHousingAreClassifiedGameServersNotLobbies() {
        for (Location l : new Location[]{NO_MODE, SKYBLOCK, HOUSING}) {
            Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, l);
            s.ticks(2 * S, 61 * S, false, false);
            Command open = s.one(CommandType.OPEN);
            assertEquals(l.toString(), Classification.CLASSIFIED, open.info.classification);
            assertEquals(0, s.count(CommandType.DROP));
            assertEquals(RewindGameRules.VERDICT_GAME_SERVER, s.rules.locationLog().get(0).verdict);
        }
    }

    @Test
    public void aBedwarsModeWithoutServerTypeIsABedwarsRun() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_MODE_ONLY);
        s.ticks(2 * S, 90 * S, true, false);                             // long queue: no 60 s open
        assertEquals(0, s.count(CommandType.OPEN));
        s.tick(91 * S, false, true);
        assertEquals(StartSource.BEDWARS, s.one(CommandType.OPEN).startSource);
    }

    @Test
    public void aGameServerRunWithTheBedwarsSidebarIsABedwarsRun() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BARE);
        s.ticks(2 * S, 90 * S, true, false);                             // Bed Wars queue sidebar
        assertEquals(0, s.count(CommandType.OPEN));
        s.tick(91 * S, false, true);
        Command open = s.one(CommandType.OPEN);
        assertEquals(StartSource.BEDWARS, open.startSource);
        assertEquals(91 * S, open.timeUs);
    }

    @Test
    public void noLocationAndNoSidebarStaysPendingThenDropsAtVisitEnd() {
        Script s = new Script().connect(0).boundary(S, 1);
        s.ticks(2 * S, 10 * 60 * S, false, false);
        assertEquals(0, s.count(CommandType.OPEN));
        assertEquals(0, s.count(CommandType.DROP));                      // still pending
        s.disconnect(10 * 60 * S + 1);
        assertRange(s.one(CommandType.DROP), 1, 1);
        assertEquals(1, s.rules.visitsWithoutLocation());
        assertEquals(0, s.count(CommandType.OPEN));
    }

    @Test
    public void aLateLocationStillClassifiesAnUnlocatedRun() {
        Script s = new Script().connect(0).boundary(S, 1);
        s.ticks(2 * S, 30 * S, false, false);
        s.location(31 * S, 1, SKYWARS);                                  // 30 s late, visit already > 0 s
        s.ticks(32 * S, 61 * S, false, false);
        Command open = s.one(CommandType.OPEN);
        assertEquals(S, open.timeUs);                                    // start = visit start
        assertEquals(30 * S, s.rules.locationLog().get(0).dtUsFromBoundary);
    }

    @Test
    public void noLocationButTheBedwarsSidebarAfterTenSecondsIsABedwarsRun() {
        Script s = new Script().connect(0).boundary(S, 1);
        s.ticks(2 * S, 10 * S, true, false);
        s.ticks(11 * S, 30 * S, true, false);
        s.tick(31 * S, false, true);
        Command open = s.one(CommandType.OPEN);
        assertEquals(StartSource.BEDWARS, open.startSource);
        assertEquals(Classification.SIDEBAR, open.info.classification);
        assertNull(open.info.mode);
        assertEquals(31 * S, open.timeUs);
    }

    @Test
    public void theLocationLogIsCappedAt16AndHoldsNoStrings() {
        Script s = new Script().connect(0);
        for (int i = 1; i <= 20; i++) {
            long t = i * 100 * S;
            s.boundary(t, i).location(t + i * 1000L, i, i % 2 == 0 ? MAIN : BW_A);
        }
        List<LocationLogEntry> log = s.rules.locationLog();
        assertEquals(16, log.size());
        assertEquals(5, log.get(0).segment);                             // oldest four fell out
        assertEquals(20, log.get(15).segment);
        assertEquals(20_000L, log.get(15).dtUsFromBoundary);
        LocationLogEntry lobby = log.get(15);
        assertTrue(lobby.hasServerType);
        assertEquals(2, lobby.serverTypeCode);                           // MAIN
        assertFalse(lobby.hasLobbyName);
        assertEquals(RewindGameRules.VERDICT_LOBBY, lobby.verdict);
        LocationLogEntry bw = log.get(14);
        assertEquals(1, bw.serverTypeCode);                              // BEDWARS
        assertTrue(bw.hasMode);
        assertTrue(bw.hasMap);
        assertEquals(RewindGameRules.VERDICT_GAME_SERVER, bw.verdict);

        for (Field f : LocationLogEntry.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            assertTrue(f.getName() + " must be a number or boolean", f.getType().isPrimitive());
        }
    }

    @Test
    public void serverTypeCodes() {
        assertEquals(0, RewindGameRules.serverTypeCode(null));
        assertEquals(1, RewindGameRules.serverTypeCode("BEDWARS"));
        assertEquals(2, RewindGameRules.serverTypeCode("MAIN"));
        assertEquals(3, RewindGameRules.serverTypeCode("TOURNAMENT"));
        assertEquals(4, RewindGameRules.serverTypeCode("SKYBLOCK"));
        assertEquals(5, RewindGameRules.serverTypeCode("HOUSING"));
        assertEquals(6, RewindGameRules.serverTypeCode("SKYWARS"));
    }

    @Test
    public void closeCarriesTheLogAndCountersSinceThePreviousFileThenResets() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false);
        s.boundary(10 * S, 2).location(10 * S + 100_000, 2, MAIN);       // queue left: counted
        s.ticks(11 * S, 25 * S, false, false);
        s.boundary(30 * S, 3).location(30 * S + 100_000, 3, BW_B);
        s.tick(31 * S, true, false).tick(32 * S, false, true).disconnect(40 * S);
        Command close = s.one(CommandType.CLOSE);
        assertEquals(1, close.stats.bedwarsVisitsWithoutStart);
        assertEquals(3, close.stats.locations.size());
        assertEquals(StartSource.BEDWARS, close.stats.startSource);
        assertEquals(0, s.rules.bedwarsVisitsWithoutStart());
        assertTrue(s.rules.locationLog().isEmpty());
    }

    // ------------------------------------------------------------------ continuation

    @Test
    public void aDoubleS07WithNoLocationWhileOpenStaysOneGame() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true);
        s.boundary(100 * S, 2).boundary(100 * S + 50_000, 3);            // S07 pair
        s.ticks(101 * S, 104 * S, false, true);
        assertEquals(0, s.count(CommandType.JOIN));
        s.tick(106 * S, false, true);                                    // 5 s without a location
        assertRange(s.one(CommandType.JOIN), 2, 3);
        assertEquals(0, s.count(CommandType.CLOSE));
        s.boundary(200 * S, 4).boundary(200 * S + 50_000, 5).ticks(201 * S, 206 * S, false, true);
        assertEquals(2, s.count(CommandType.JOIN));
        assertEquals(1, s.count(CommandType.OPEN));
        assertEquals(0, s.count(CommandType.CLOSE));
    }

    @Test
    public void aSameServerLocationContinuesAndAnotherServerClosesAndStartsANewRun() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true);
        s.boundary(100 * S, 2).location(100 * S + 100_000, 2, BW_A);
        assertRange(s.one(CommandType.JOIN), 2, 2);

        s.line(300 * S, Kind.WIN);
        s.boundary(305 * S, 3).tick(305 * S + 1, false, false);           // Play Again before +15 s
        s.location(306 * S, 3, BW_B);
        Command close = s.one(CommandType.CLOSE);
        assertEquals(EndReason.SERVER, close.endedBy);
        assertEquals(Result.WIN, close.result);
        s.ticks(307 * S, 340 * S, true, false).tick(341 * S, false, true);
        List<Command> opens = s.of(CommandType.OPEN);
        assertEquals(2, opens.size());
        assertRange(opens.get(1), 3, 3);
        assertEquals("BEDWARS_FOUR_FOUR", opens.get(1).info.mode);
        assertEquals(1, s.count(CommandType.CLOSE));
    }

    @Test
    public void aDisconnectMidGameCloses() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true).ticks(4 * S, 100 * S, false, true);
        s.boundary(101 * S, 2);                                          // a candidate is pending
        s.disconnect(102 * S);
        Command close = s.one(CommandType.CLOSE);
        assertEquals(EndReason.DISCONNECT, close.endedBy);
        assertRange(s.one(CommandType.JOIN), 2, 2);                      // its captured data is the game's
        assertEquals(0, s.count(CommandType.DROP));
        assertFalse(s.rules.gameOpen());

        Script q = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        q.tick(2 * S, true, false).tick(3 * S, false, true).quit(50 * S);
        assertEquals(EndReason.QUIT, q.one(CommandType.CLOSE).endedBy);
    }

    @Test
    public void singlePlayerOpensAtOnceJoinsDimensionChangesAndClosesOnLeave() {
        Script s = new Script().connectSinglePlayer(0).boundary(S, 1);
        Command open = s.one(CommandType.OPEN);
        assertEquals(StartSource.SINGLEPLAYER, open.startSource);
        assertEquals(S, open.timeUs);
        assertRange(open, 1, 1);
        assertTrue(open.info.singlePlayer);
        assertEquals("SINGLEPLAYER", open.info.mode);
        assertEquals(Classification.SINGLEPLAYER, open.info.classification);
        s.boundary(50 * S, 2);                                           // the Nether
        assertRange(s.one(CommandType.JOIN), 2, 2);
        s.location(51 * S, 2, MAIN);                                     // ignored in single-player
        s.ticks(52 * S, 70 * S, false, false);
        assertEquals(0, s.count(CommandType.CLOSE));
        s.disconnect(80 * S);
        assertEquals(EndReason.DISCONNECT, s.one(CommandType.CLOSE).endedBy);
        s.connectSinglePlayer(90 * S).boundary(91 * S, 1);
        assertEquals(2, s.count(CommandType.OPEN));
    }

    @Test
    public void aPendingRunDropsAfter20Minutes() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.ticks(2 * S, 20 * 60 * S, true, false);
        assertEquals(0, s.count(CommandType.DROP));
        s.tick(20 * 60 * S + S, true, false);
        assertRange(s.one(CommandType.DROP), 1, 1);
        s.tick(20 * 60 * S + 2 * S, false, true);                         // too late
        assertEquals(0, s.count(CommandType.OPEN));
    }

    @Test
    public void aPendingRunDropsAbove32MiB() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false);
        s.pendingBytes(3 * S, RewindGameRules.PENDING_MAX_BYTES);
        assertEquals(0, s.count(CommandType.DROP));
        s.pendingBytes(4 * S, RewindGameRules.PENDING_MAX_BYTES + 1);
        assertRange(s.one(CommandType.DROP), 1, 1);
        s.tick(5 * S, false, true);
        assertEquals(0, s.count(CommandType.OPEN));
        // The same visit's next segment stays pending without a location (code review 1, I1: no 5 s
        // continuation into a dropped visit); its same-server location, or the byte cap, drops it.
        s.boundary(6 * S, 2).ticks(7 * S, 12 * S, false, true);
        assertEquals(1, s.count(CommandType.DROP));
        s.location(13 * S, 2, BW_A);
        assertEquals(2, s.count(CommandType.DROP));
        assertRange(s.of(CommandType.DROP).get(1), 2, 2);
        s.boundary(20 * S, 3).pendingBytes(21 * S, RewindGameRules.PENDING_MAX_BYTES + 1);
        assertEquals(3, s.count(CommandType.DROP));
        assertRange(s.of(CommandType.DROP).get(2), 3, 3);
        assertEquals(0, s.count(CommandType.OPEN));
    }

    @Test
    public void pendingBytesDoNotAffectAnOpenGame() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true);
        s.pendingBytes(4 * S, 100L * 1024 * 1024);
        assertEquals(0, s.count(CommandType.DROP));
        assertEquals(0, s.count(CommandType.CLOSE));
    }

    @Test
    public void aGameClosesAtTheTwoHourCap() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, SKYBLOCK);
        s.ticks(2 * S, 61 * S, false, false);
        assertEquals(S, s.one(CommandType.OPEN).timeUs);
        s.idle(S + 2 * 3600 * S - 1);
        assertEquals(0, s.count(CommandType.CLOSE));
        s.idle(S + 2 * 3600 * S);
        assertEquals(EndReason.CAP, s.one(CommandType.CLOSE).endedBy);
        s.boundary(S + 2 * 3600 * S + 10, 2).ticks(2 * 3600 * S + 10 * S, 2 * 3600 * S + 20 * S, false, false);
        assertEquals("pending until its location (code review 1, I1)", 0, s.count(CommandType.DROP));
        s.location(2 * 3600 * S + 21 * S, 2, SKYBLOCK);
        assertRange(s.one(CommandType.DROP), 2, 2);                      // same visit, after the cap
        assertEquals(1, s.count(CommandType.OPEN));
    }

    @Test
    public void anEarlyLocationIsAppliedAtItsBoundary() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, MAIN);
        s.location(5 * S, 2, BW_A);                                      // tagged with a boundary not yet seen
        assertEquals(1, s.rules.locationLog().size());
        s.boundary(5 * S + 10_000, 2);
        assertEquals(2, s.rules.locationLog().size());
        assertEquals(-10_000L, s.rules.locationLog().get(1).dtUsFromBoundary);
        assertRange(s.one(CommandType.DROP), 1, 1);                      // the lobby ended
        s.tick(6 * S, true, false).tick(7 * S, false, true);
        assertRange(s.one(CommandType.OPEN), 2, 2);
    }

    // ------------------------------------------------------------------ late locations (code review 1, I1)

    /** The reviewer's timeline: the 5 s continuation must not merge a new run into a dropped lobby. */
    @Test
    public void aDelayedLocationAfterADroppedLobbyStillOpensTheNextGame() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, MAIN);
        s.tick(12 * S, false, false);                                     // the lobby verdict drops it
        assertRange(s.one(CommandType.DROP), 1, 1);
        s.boundary(30 * S, 2);
        s.tick(35 * S, true, false);                                      // queue; the location is late
        assertEquals("segment 2 stays pending without its location", 1, s.count(CommandType.DROP));
        s.location(36 * S, 2, BW_B);                                      // a different server
        s.tick(60 * S, false, true);                                      // the game starts
        Command open = s.one(CommandType.OPEN);
        assertRange(open, 2, 2);
        assertEquals(60 * S, open.timeUs);
        assertEquals(StartSource.BEDWARS, open.startSource);
        assertEquals("BEDWARS_FOUR_FOUR", open.info.mode);
        assertTrue(s.rules.gameOpen());
        assertEquals(1, s.count(CommandType.DROP));
    }

    @Test
    public void aDelayedLocationAfterAShortVisitStartsItsOwnRun() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, SKYWARS);
        s.ticks(2 * S, 29 * S, false, false);
        s.boundary(30 * S, 2);                                            // switch after 29 s
        s.ticks(31 * S, 35 * S, true, false);                             // the new world's queue sidebar
        assertEquals(0, s.count(CommandType.DROP));
        s.location(36 * S, 2, BW_B);
        assertRange(s.one(CommandType.DROP), 1, 1);                       // the short visit
        assertEquals(1, s.rules.shortVisitsDropped());
        s.ticks(37 * S, 59 * S, true, false).tick(60 * S, false, true);
        Command open = s.one(CommandType.OPEN);
        assertRange(open, 2, 2);
        assertEquals(60 * S, open.timeUs);
        assertEquals("BEDWARS_FOUR_FOUR", open.info.mode);
    }

    @Test
    public void theBedwarsSidebarBeforeAnyLocationAfterADroppedLobbyIsABedwarsRun() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, MAIN);
        s.tick(12 * S, false, false);
        s.boundary(30 * S, 2);
        s.ticks(31 * S, 39 * S, true, false);
        assertEquals(1, s.count(CommandType.DROP));                       // still pending before 10 s
        s.ticks(40 * S, 59 * S, true, false).tick(60 * S, false, true);   // no location at all
        Command open = s.one(CommandType.OPEN);
        assertRange(open, 2, 2);
        assertEquals(60 * S, open.timeUs);
        assertEquals(Classification.SIDEBAR, open.info.classification);
        s.location(70 * S, 2, BW_B);                                      // late: fills in, changes nothing
        assertEquals(1, s.count(CommandType.OPEN));
        assertEquals(0, s.count(CommandType.CLOSE));
    }

    @Test
    public void anUnlocatedSameServerSegmentAfterAClosedGameIsDroppedAtTheNextBoundary() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true).line(100 * S, Kind.WIN);
        s.ticks(101 * S, 115 * S, false, true);
        assertEquals(EndReason.RESULT, s.one(CommandType.CLOSE).endedBy);
        s.boundary(120 * S, 2).ticks(121 * S, 140 * S, false, true);      // no fresh edge: not a new game
        assertEquals(0, s.count(CommandType.DROP));
        s.boundary(150 * S, 3).location(150 * S + 100_000, 3, MAIN);     // the next switch
        assertRange(s.of(CommandType.DROP).get(0), 2, 2);                 // the old run was a continuation
        assertEquals(1, s.count(CommandType.OPEN));
    }

    // ------------------------------------------------------------------ disconnect with a candidate (code review 1, I5)

    /** The reviewer's sequence: the captured S07 segment belongs to the game and is joined before CLOSE. */
    @Test
    public void aDisconnectRightAfterASameServerRespawnJoinsItBeforeTheClose() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true).ticks(4 * S, 99 * S, false, true);
        s.boundary(100 * S, 2);                                           // dimension-changing S07
        s.disconnect(101 * S);
        assertEquals(0, s.count(CommandType.DROP));
        assertEquals(2, s.last.size());
        assertEquals(CommandType.JOIN, s.last.get(0).type);
        assertRange(s.last.get(0), 2, 2);
        assertEquals(CommandType.CLOSE, s.last.get(1).type);
        assertEquals(EndReason.DISCONNECT, s.last.get(1).endedBy);
        assertEquals(101 * S, s.last.get(1).timeUs);

        Script off = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        off.tick(2 * S, true, false).tick(3 * S, false, true).boundary(100 * S, 2).record(101 * S, false);
        assertEquals(0, off.count(CommandType.DROP));
        assertEquals(CommandType.JOIN, off.last.get(0).type);
        assertRange(off.last.get(0), 2, 2);
        assertEquals(CommandType.CLOSE, off.last.get(1).type);
        assertEquals(EndReason.SETTINGS, off.last.get(1).endedBy);
    }

    // ------------------------------------------------------------------ timed closes with a candidate (code review 2, I1)

    /** A Bed Wars game on BW_A open in segment 1, then WIN at 100 s and a dimension-changing S07 (segment 2) at 114 s. */
    private static Script winThenRespawnAt114() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true).ticks(4 * S, 99 * S, false, true);
        s.line(100 * S, Kind.WIN);
        s.ticks(101 * S, 113 * S, false, true);
        s.boundary(114 * S, 2);
        return s;
    }

    private static int indexOf(Script s, CommandType type) {
        for (int i = 0; i < s.all.size(); i++) if (s.all.get(i).type == type) return i;
        return -1;
    }

    /** Segment 2 is JOINed to the game before its one CLOSE, and nothing is dropped. */
    private static Command assertJoinedThenClosed(Script s) {
        assertEquals(s.all.toString(), 0, s.count(CommandType.DROP));
        assertRange(s.one(CommandType.JOIN), 2, 2);
        Command close = s.one(CommandType.CLOSE);
        assertTrue("JOIN before CLOSE: " + s.all, indexOf(s, CommandType.JOIN) < indexOf(s, CommandType.CLOSE));
        assertEquals(Result.WIN, close.result);
        return close;
    }

    /** The reviewer's sequence: WIN 100 s, same-server S07 114 s, tick 115 s, its location 116 s. */
    @Test
    public void aSameServerRespawnInsideTheResultWaitIsJoinedBeforeTheResultClose() {
        Script s = winThenRespawnAt114();
        s.tick(115 * S, false, true);
        assertEquals("the result close waits for the undecided segment", 0, s.count(CommandType.CLOSE));
        s.location(116 * S, 2, BW_A);
        Command close = assertJoinedThenClosed(s);
        assertEquals(EndReason.RESULT, close.endedBy);
        assertFalse(close.failed);
    }

    /** The reviewer's disconnect variant: the timer is already due when the disconnect comes. */
    @Test
    public void aDisconnectWhileTheResultCloseIsDueJoinsTheRespawnFirst() {
        Script s = winThenRespawnAt114();
        s.disconnect(115 * S);
        assertJoinedThenClosed(s);
    }

    @Test
    public void aQuitWhileTheResultCloseIsDueJoinsTheRespawnFirst() {
        Script s = winThenRespawnAt114();
        s.quit(115 * S);
        assertJoinedThenClosed(s);
    }

    @Test
    public void recordOffWhileTheResultCloseIsDueJoinsTheRespawnFirst() {
        Script s = winThenRespawnAt114();
        s.record(115 * S, false);
        Command close = assertJoinedThenClosed(s);
        assertEquals(EndReason.SETTINGS, close.endedBy);
    }

    @Test
    public void aRespawnWithNoLocationIsJoinedFiveSecondsLaterThenTheResultCloses() {
        Script s = winThenRespawnAt114();
        s.ticks(115 * S, 118 * S, false, true);
        assertEquals(0, s.count(CommandType.CLOSE));
        s.tick(119 * S, false, true);                                     // 5 s after the boundary
        Command close = assertJoinedThenClosed(s);
        assertEquals(EndReason.RESULT, close.endedBy);
        assertEquals(119 * S, close.timeUs);
    }

    @Test
    public void aSameServerLocationBeforeTheDueTickJoinsThenTheResultCloses() {
        Script s = winThenRespawnAt114();
        s.location(114 * S + 500_000, 2, BW_A);
        assertRange(s.one(CommandType.JOIN), 2, 2);
        s.tick(115 * S, false, true);
        assertEquals(EndReason.RESULT, assertJoinedThenClosed(s).endedBy);
    }

    @Test
    public void anotherServerAfterTheDueTickClosesTheGameAndTheRespawnStartsTheNextRun() {
        Script s = winThenRespawnAt114();
        s.tick(115 * S, false, true);
        s.location(116 * S, 2, BW_B);                                     // Play Again: another server
        assertEquals(0, s.count(CommandType.JOIN));
        assertEquals(0, s.count(CommandType.DROP));
        Command close = s.one(CommandType.CLOSE);
        assertEquals(EndReason.SERVER, close.endedBy);
        assertEquals(Result.WIN, close.result);
        s.ticks(117 * S, 140 * S, true, false).tick(141 * S, false, true);
        List<Command> opens = s.of(CommandType.OPEN);
        assertEquals(2, opens.size());
        assertRange(opens.get(1), 2, 2);
        assertEquals("BEDWARS_FOUR_FOUR", opens.get(1).info.mode);
    }

    @Test
    public void aLobbyAfterTheDueTickClosesTheGameAndTheRespawnIsTheLobbyRun() {
        Script s = winThenRespawnAt114();
        s.tick(115 * S, false, false);                                    // the lobby world: no Bed Wars sidebar
        s.location(116 * S, 2, MAIN);
        assertEquals(0, s.count(CommandType.JOIN));
        assertEquals(EndReason.LOBBY, s.one(CommandType.CLOSE).endedBy);
        s.ticks(117 * S, 130 * S, false, false);
        assertRange(s.one(CommandType.DROP), 2, 2);                       // the lobby verdict, not the game's
        assertEquals(1, s.count(CommandType.OPEN));
    }

    @Test
    public void aCaptureFailureInTheRespawnSegmentJoinsItsPrefixBeforeTheFailedClose() {
        Script s = winThenRespawnAt114();
        s.fail(114 * S + 500_000);                                        // latched in segment 2
        s.tick(115 * S, false, true);
        s.location(116 * S, 2, BW_A);                                     // segment 2 is this game's
        assertEquals(s.all.toString(), 0, s.count(CommandType.DROP));
        assertRange(s.one(CommandType.JOIN), 2, 2);
        Command close = s.one(CommandType.CLOSE);
        assertTrue(indexOf(s, CommandType.JOIN) < indexOf(s, CommandType.CLOSE));
        assertTrue(close.failed);
        assertEquals("the game had its result: the failure's owner ended", 1, s.count(CommandType.REARM));
    }

    /** A game on SKYBLOCK open since 1 s, a same-server S07 one second before the 2 h cap. */
    private static Script respawnBeforeTheCap() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, SKYBLOCK);
        s.ticks(2 * S, 61 * S, false, false);
        assertEquals(S, s.one(CommandType.OPEN).timeUs);
        s.boundary(S + RewindGameRules.GAME_CAP_US - S, 2);
        return s;
    }

    @Test
    public void theCapCloseWaitsForARespawnSegmentAndJoinsIt() {
        Script s = respawnBeforeTheCap();
        s.idle(S + RewindGameRules.GAME_CAP_US);
        assertEquals(0, s.count(CommandType.CLOSE));
        s.location(S + RewindGameRules.GAME_CAP_US + S, 2, SKYBLOCK);
        assertEquals(0, s.count(CommandType.DROP));
        assertRange(s.one(CommandType.JOIN), 2, 2);
        assertEquals(EndReason.CAP, s.one(CommandType.CLOSE).endedBy);
        assertTrue(indexOf(s, CommandType.JOIN) < indexOf(s, CommandType.CLOSE));
    }

    @Test
    public void aDisconnectWhileTheCapCloseIsDueJoinsTheRespawnFirst() {
        Script s = respawnBeforeTheCap();
        s.disconnect(S + RewindGameRules.GAME_CAP_US);
        assertEquals(0, s.count(CommandType.DROP));
        assertRange(s.one(CommandType.JOIN), 2, 2);
        assertEquals(EndReason.DISCONNECT, s.one(CommandType.CLOSE).endedBy);
        assertTrue(indexOf(s, CommandType.JOIN) < indexOf(s, CommandType.CLOSE));
    }

    // ------------------------------------------------------------------ the capture handler runs ahead (code review 2, I1)

    /** Netty started segment 2 at 114 s; the client's tick at 115 s runs before its WORLD_LOAD. */
    private static Script winThenUnloadedRespawn() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true).ticks(4 * S, 99 * S, false, true);
        s.line(100 * S, Kind.WIN).ticks(101 * S, 113 * S, false, true);
        s.progress(114 * S, 2);
        return s;
    }

    @Test
    public void aRespawnCapturedButNotLoadedHoldsTheResultCloseUntilItIsDecided() {
        Script s = winThenUnloadedRespawn();
        s.tick(115 * S, false, true);
        assertEquals("the close waits for the segment the client has not loaded", 0, s.count(CommandType.CLOSE));
        s.boundary(115 * S + 500_000, 2).location(116 * S, 2, BW_A);
        Command close = assertJoinedThenClosed(s);
        assertEquals(EndReason.RESULT, close.endedBy);
    }

    // ---- code review 3, I1: a stalled client never lets a close leave a captured segment behind

    /** {@link #winThenUnloadedRespawn()}, one tick at 115 s sees the capture progress, then the client stalls. */
    private static Script winThenStalledRespawn() {
        Script s = winThenUnloadedRespawn();
        s.tick(115 * S, false, true);
        assertEquals("due, but segment 2 is undecided", 0, s.count(CommandType.CLOSE));
        return s;
    }

    /** The reviewer's sequence: the WORLD_LOAD comes at 130 s, 15 s after the close was due, then its location. */
    @Test
    public void aStalledClientThenASameServerLocationJoinsTheRespawnBeforeTheResultClose() {
        Script s = winThenStalledRespawn();
        s.boundary(130 * S, 2);
        assertEquals("the WORLD_LOAD itself never closes first: " + s.all, 0, s.count(CommandType.CLOSE));
        s.location(130 * S + 100_000, 2, BW_A);
        Command close = assertJoinedThenClosed(s);
        assertEquals(EndReason.RESULT, close.endedBy);
        assertEquals("a JOINed segment runs past the due time: the decision time", 130 * S + 100_000, close.timeUs);
    }

    @Test
    public void aStalledClientWithoutALocationJoinsTheRespawnFiveSecondsAfterItsWorldLoad() {
        Script s = winThenStalledRespawn();
        s.boundary(130 * S, 2).ticks(131 * S, 134 * S, false, true);
        assertEquals(0, s.count(CommandType.CLOSE));
        s.tick(135 * S, false, true);                                      // 5 s after the client processed it
        assertEquals(EndReason.RESULT, assertJoinedThenClosed(s).endedBy);
    }

    @Test
    public void aStalledClientThenAnotherServerClosesAtTheDueTimeAndTheRespawnStartsTheNextRun() {
        Script s = winThenStalledRespawn();
        s.boundary(130 * S, 2).location(130 * S + 100_000, 2, BW_B);
        assertEquals(0, s.count(CommandType.JOIN));
        assertEquals(0, s.count(CommandType.DROP));
        Command close = s.one(CommandType.CLOSE);
        assertEquals(EndReason.SERVER, close.endedBy);
        assertEquals(Result.WIN, close.result);
        assertEquals("nothing JOINed after the due time: the due time", 115 * S, close.timeUs);
        s.ticks(131 * S, 150 * S, true, false).tick(151 * S, false, true);
        assertRange(s.of(CommandType.OPEN).get(1), 2, 2);
    }

    @Test
    public void aStalledClientThenALobbyClosesAtTheDueTimeAndTheRespawnIsTheLobbyRun() {
        Script s = winThenStalledRespawn();
        s.boundary(130 * S, 2).location(130 * S + 100_000, 2, MAIN);
        assertEquals(0, s.count(CommandType.JOIN));
        Command close = s.one(CommandType.CLOSE);
        assertEquals(EndReason.LOBBY, close.endedBy);
        assertEquals(115 * S, close.timeUs);
        s.ticks(131 * S, 145 * S, false, false);
        assertRange(s.one(CommandType.DROP), 2, 2);                       // the lobby verdict, not the game's
        assertEquals(1, s.count(CommandType.OPEN));
    }

    @Test
    public void aStalledClientThenADisconnectJoinsTheUnloadedRespawn() {
        Script s = winThenStalledRespawn();
        s.disconnect(130 * S);
        assertJoinedThenClosed(s);
        assertEquals(EndReason.DISCONNECT, s.one(CommandType.CLOSE).endedBy);
    }

    @Test
    public void aStalledClientThenRecordOffJoinsTheUnloadedRespawn() {
        Script s = winThenStalledRespawn();
        s.record(130 * S, false);
        assertEquals(EndReason.SETTINGS, assertJoinedThenClosed(s).endedBy);
    }

    @Test
    public void aStalledClientHoldsTheCapCloseUntilTheRespawnIsDecided() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, SKYBLOCK);
        s.ticks(2 * S, 61 * S, false, false);
        s.progress(RewindGameRules.GAME_CAP_US, 2);                        // 1 s before the cap
        s.idle(S + RewindGameRules.GAME_CAP_US);                           // due
        s.boundary(S + RewindGameRules.GAME_CAP_US + 20 * S, 2);           // the stall ends 20 s later
        assertEquals(0, s.count(CommandType.CLOSE));
        s.location(S + RewindGameRules.GAME_CAP_US + 20 * S + 100_000, 2, SKYBLOCK);
        assertEquals(0, s.count(CommandType.DROP));
        assertRange(s.one(CommandType.JOIN), 2, 2);
        assertEquals(EndReason.CAP, s.one(CommandType.CLOSE).endedBy);
        assertTrue(indexOf(s, CommandType.JOIN) < indexOf(s, CommandType.CLOSE));
    }

    @Test
    public void aStalledClientHoldsAFailureCloseUntilTheRespawnIsDecided() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true).ticks(4 * S, 99 * S, false, true);
        s.progress(100 * S, 2);                                            // Netty: a same-server respawn
        s.clientFail(101 * S, 1);                                          // a failure in the open game's segment
        assertEquals(0, s.count(CommandType.CLOSE));
        s.boundary(130 * S, 2);
        assertEquals(0, s.count(CommandType.CLOSE));
        s.location(130 * S + 100_000, 2, BW_A);
        assertEquals(s.all.toString(), 0, s.count(CommandType.DROP));
        assertRange(s.one(CommandType.JOIN), 2, 2);
        Command close = s.one(CommandType.CLOSE);
        assertTrue(close.failed);
        assertTrue(indexOf(s, CommandType.JOIN) < indexOf(s, CommandType.CLOSE));
    }

    @Test
    public void twoCapturedButUnloadedSameServerSegmentsAreBothJoinedBeforeTheResultClose() {
        Script s = winThenUnloadedRespawn();
        s.progress(114 * S + 300_000, 3);                                  // and a second dimension change
        s.tick(115 * S, false, true);
        s.boundary(130 * S, 2);
        s.boundary(130 * S + 50_000, 3);
        assertEquals(0, s.count(CommandType.CLOSE));
        s.location(130 * S + 100_000, 3, BW_A);
        assertEquals(s.all.toString(), 0, s.count(CommandType.DROP));
        assertRange(s.one(CommandType.JOIN), 2, 3);
        assertEquals(EndReason.RESULT, s.one(CommandType.CLOSE).endedBy);
        assertTrue(indexOf(s, CommandType.JOIN) < indexOf(s, CommandType.CLOSE));
    }

    /** The second unloaded segment is another server: both go to the next run, which the location names. */
    @Test
    public void twoCapturedButUnloadedSegmentsEndingOnAnotherServerAreHandedToTheNextRun() {
        Script s = winThenUnloadedRespawn();
        s.progress(114 * S + 300_000, 3);
        s.tick(115 * S, false, true);
        s.boundary(130 * S, 2).boundary(130 * S + 50_000, 3).location(130 * S + 100_000, 3, BW_B);
        assertEquals(0, s.count(CommandType.JOIN));
        assertEquals(0, s.count(CommandType.DROP));
        assertEquals(EndReason.SERVER, s.one(CommandType.CLOSE).endedBy);
        s.ticks(131 * S, 150 * S, true, false).tick(151 * S, false, true);
        assertRange(s.of(CommandType.OPEN).get(1), 2, 3);
    }

    /** A game line (or Marker Key) is a rules event too: it never closes past a segment the capture handler started. */
    @Test
    public void aGameLineDuringTheStallDoesNotCloseFirst() {
        Script s = winThenStalledRespawn();
        s.line(125 * S, Kind.FINAL_KILL);
        s.take(s.rules.onMarkerKey(126 * S));
        assertEquals(0, s.count(CommandType.CLOSE));
        s.boundary(130 * S, 2).location(130 * S + 100_000, 2, BW_A);
        assertJoinedThenClosed(s);
    }

    @Test
    public void aResultWithNoEventUntilTheDisconnectClosesAtItsDueTime() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true).line(100 * S, Kind.WIN);
        s.disconnect(200 * S);
        Command close = s.one(CommandType.CLOSE);
        assertEquals(EndReason.RESULT, close.endedBy);
        assertEquals(115 * S, close.timeUs);
    }

    @Test
    public void aDisconnectJoinsSegmentsTheClientNeverLoaded() {
        Script s = winThenUnloadedRespawn();
        s.progress(114 * S + 300_000, 3);                                 // and a second one
        s.disconnect(115 * S);
        assertEquals(0, s.count(CommandType.DROP));
        assertRange(s.one(CommandType.JOIN), 2, 3);
        assertFalse(s.one(CommandType.CLOSE).failed);
        assertTrue(indexOf(s, CommandType.JOIN) < indexOf(s, CommandType.CLOSE));
    }

    @Test
    public void aDisconnectJoinsAnUnloadedSegmentAndItsFailureFailsTheGame() {
        Script s = winThenUnloadedRespawn();
        s.captureFail(114 * S + 300_000, 2);                              // latched in segment 2, not loaded
        s.tick(115 * S, false, true);
        assertEquals(0, s.count(CommandType.CLOSE));
        s.disconnect(116 * S);
        assertRange(s.one(CommandType.JOIN), 2, 2);
        assertTrue(s.one(CommandType.CLOSE).failed);
    }

    @Test
    public void recordOffJoinsSegmentsTheClientNeverLoaded() {
        Script s = winThenUnloadedRespawn();
        s.record(115 * S, false);
        assertRange(s.one(CommandType.JOIN), 2, 2);
        assertEquals(EndReason.SETTINGS, s.one(CommandType.CLOSE).endedBy);
        assertTrue(indexOf(s, CommandType.JOIN) < indexOf(s, CommandType.CLOSE));
    }

    @Test
    public void theCapCloseWaitsForASegmentTheClientHasNotLoaded() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, SKYBLOCK);
        s.ticks(2 * S, 61 * S, false, false);
        s.progress(RewindGameRules.GAME_CAP_US, 2);
        s.idle(S + RewindGameRules.GAME_CAP_US);
        assertEquals(0, s.count(CommandType.CLOSE));
        s.boundary(S + RewindGameRules.GAME_CAP_US + 100_000, 2);
        s.location(S + RewindGameRules.GAME_CAP_US + 200_000, 2, SKYBLOCK);
        assertRange(s.one(CommandType.JOIN), 2, 2);
        assertEquals(EndReason.CAP, s.one(CommandType.CLOSE).endedBy);
    }

    // ------------------------------------------------------------------ failure ownership by segment (code review 2, I2)

    /** A failure latched in segment 1 before the client loaded it: no REARM until its game provably ends. */
    @Test
    public void aCaptureFailureInAnUnloadedSegmentWaitsForItsBoundaryAndNeverRearmsEarly() {
        Script s = new Script().connect(0);
        s.progress(S, 1).captureFail(S + 100_000, 1);
        s.tick(S + 200_000, false, false);                                // a tick before the WORLD_LOAD
        assertEquals(0, s.count(CommandType.REARM));
        s.boundary(S + 300_000, 1);
        assertRange(s.one(CommandType.DROP), 1, 1);                       // its run failed
        s.location(S + 400_000, 1, BW_A).tick(2 * S, true, false).tick(30 * S, false, true);
        assertEquals("the failed game never opens", 0, s.count(CommandType.OPEN));
        s.boundary(60 * S, 2).location(60 * S + 100_000, 2, BW_A);        // same server: still that game
        assertEquals(2, s.count(CommandType.DROP));
        assertEquals(0, s.count(CommandType.REARM));
        s.line(300 * S, Kind.WIN);                                        // it ended
        assertEquals(1, s.count(CommandType.REARM));
        s.boundary(320 * S, 3).location(320 * S + 100_000, 3, BW_B).tick(321 * S, true, false).tick(350 * S, false, true);
        assertRange(s.one(CommandType.OPEN), 3, 3);
    }

    @Test
    public void aCaptureFailureLoadedBeforeATickIsOwnedTheSameWay() {
        Script s = new Script().connect(0);
        s.progress(S, 1).captureFail(S + 100_000, 1).boundary(S + 300_000, 1);
        s.tick(S + 400_000, false, false);
        assertRange(s.one(CommandType.DROP), 1, 1);
        assertEquals(0, s.count(CommandType.REARM));
    }

    @Test
    public void aCaptureFailureBeforeTheFirstBoundaryRearmsAtOnce() {
        Script s = new Script().connect(0).captureFail(S / 2, 0);
        assertEquals(1, s.count(CommandType.REARM));
        s.boundary(S, 1).location(S + 100_000, 1, BW_A).tick(2 * S, true, false).tick(30 * S, false, true);
        assertRange(s.one(CommandType.OPEN), 1, 1);
    }

    /** Segment 0 failed, but Netty had already read segment 1's boundary: segment 1 holds no packets. */
    @Test
    public void aRunWhoseBoundaryPassedBeforeTheRearmIsSkipped() {
        RewindGameRules r = new RewindGameRules();
        List<Command> all = new ArrayList<Command>(r.onConnect(0, false, true));
        r.onCaptureProgress(S, 1);
        all.addAll(r.onCaptureFailure(S, 0));
        assertEquals(CommandType.REARM, all.get(all.size() - 1).type);
        all.addAll(r.onBoundary(S + 100_000, 1));                         // capture resumes only at segment 2
        all.addAll(r.onLocation(S + 200_000, 1, BW_A));
        all.addAll(r.tick(2 * S, true, false));
        all.addAll(r.tick(30 * S, false, true));
        assertEquals(1, r.skippedAfterFailure());
        for (Command c : all) assertFalse(all.toString(), c.type == CommandType.OPEN);
        r.onCaptureResumed(2);
        all.addAll(r.onBoundary(40 * S, 2));
        all.addAll(r.onLocation(40 * S + 100_000, 2, BW_B));
        all.addAll(r.tick(41 * S, true, false));
        all.addAll(r.tick(60 * S, false, true));
        assertTrue("the next game is recorded", r.gameOpen());
    }

    @Test
    public void aCandidateFailureWithNoLocationJoinsItAfterFiveSecondsThenFails() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true).ticks(4 * S, 99 * S, false, true);
        s.boundary(100 * S, 2).captureFail(101 * S, 2);
        s.ticks(102 * S, 104 * S, false, true);
        assertEquals(0, s.count(CommandType.CLOSE));
        s.tick(105 * S, false, true);
        assertRange(s.one(CommandType.JOIN), 2, 2);
        assertTrue(s.one(CommandType.CLOSE).failed);
        assertEquals("no result yet: no rearm", 0, s.count(CommandType.REARM));
    }

    @Test
    public void aCandidateFailureOnAnotherServerLeavesTheOpenGameHealthyAndFailsTheNewRun() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true).ticks(4 * S, 99 * S, false, true);
        s.boundary(100 * S, 2).captureFail(100 * S + 50_000, 2);
        s.location(100 * S + 100_000, 2, BW_B);
        Command close = s.one(CommandType.CLOSE);
        assertFalse(close.failed);
        assertEquals(EndReason.SERVER, close.endedBy);
        assertRange(s.one(CommandType.DROP), 2, 2);                       // the new run failed
        assertEquals(0, s.count(CommandType.REARM));
        s.tick(101 * S, true, false).tick(120 * S, false, true);
        assertEquals(1, s.count(CommandType.OPEN));
        s.line(300 * S, Kind.LOSS);                                       // the failed run's game ended
        assertEquals(1, s.count(CommandType.REARM));
    }

    @Test
    public void aFailureDuringALobbyVerdictWaitsForIt() {
        Script lobby = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, MAIN);
        lobby.fail(2 * S);
        assertEquals(0, lobby.count(CommandType.DROP));
        assertEquals(0, lobby.count(CommandType.REARM));
        lobby.ticks(3 * S, 12 * S, false, false);                         // a lobby: nothing to wait for
        assertRange(lobby.one(CommandType.DROP), 1, 1);
        assertEquals(1, lobby.count(CommandType.REARM));

        Script queue = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, LOBBY_NAME_ONLY);
        queue.fail(2 * S).tick(3 * S, true, false);                       // the Bed Wars queue: a game after all
        assertRange(queue.one(CommandType.DROP), 1, 1);
        assertEquals(0, queue.count(CommandType.REARM));
        queue.tick(30 * S, false, true);
        assertEquals(0, queue.count(CommandType.OPEN));
        queue.line(300 * S, Kind.WIN);
        assertEquals(1, queue.count(CommandType.REARM));
    }

    /** A client-thread failure fails its game but never latches capture: no REARM, and the next run is not skipped. */
    @Test
    public void aClientFailureFailsTheGameButNeverSkipsTheNextRun() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true).clientFail(50 * S, 1);
        assertTrue(s.one(CommandType.CLOSE).failed);
        s.boundary(60 * S, 2).location(60 * S + 100_000, 2, BW_B);        // Play Again mid-game, another server
        assertEquals(0, s.count(CommandType.REARM));
        assertEquals(0, s.rules.skippedAfterFailure());
        s.tick(61 * S, true, false).tick(90 * S, false, true);
        assertEquals(2, s.count(CommandType.OPEN));
        assertRange(s.of(CommandType.OPEN).get(1), 2, 2);
    }

    // ------------------------------------------------------------------ writer failures by game (code review 2, I3)

    @Test
    public void aWriterFailureReachesOnlyTheGameWhoseFileFailed() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true);
        long a = s.one(CommandType.OPEN).gameId;
        s.boundary(50 * S, 2).location(50 * S + 100_000, 2, BW_B);        // A closes, B is pending
        s.writerFail(51 * S, a);                                          // A's file failed late
        assertEquals(0, s.count(CommandType.DROP));
        s.tick(52 * S, true, false).tick(60 * S, false, true);
        List<Command> opens = s.of(CommandType.OPEN);
        assertEquals(2, opens.size());
        long b = opens.get(1).gameId;
        assertNotEquals(a, b);
        s.writerFail(61 * S, a);                                          // again, while B is open
        assertTrue(s.rules.gameOpen());
        s.writerFail(62 * S, b);                                          // B's own file
        assertFalse(s.rules.gameOpen());
        List<Command> closes = s.of(CommandType.CLOSE);
        assertEquals(2, closes.size());
        assertFalse(closes.get(0).failed);
        assertTrue(closes.get(1).failed);
        assertEquals(b, closes.get(1).gameId);
        assertEquals(0, s.count(CommandType.REARM));
    }

    @Test
    public void aWriterFailureWaitsForTheCandidateAndNeverSkipsTheNextRun() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true);
        long a = s.one(CommandType.OPEN).gameId;
        s.boundary(50 * S, 2).writerFail(50 * S + 100_000, a);
        assertEquals(0, s.count(CommandType.CLOSE));
        s.location(50 * S + 200_000, 2, BW_B);                           // Play Again on another server
        assertTrue(s.one(CommandType.CLOSE).failed);
        assertEquals(0, s.count(CommandType.JOIN));
        s.tick(51 * S, true, false).tick(70 * S, false, true);
        assertRange(s.of(CommandType.OPEN).get(1), 2, 2);
        assertEquals(0, s.rules.skippedAfterFailure());
    }

    /** A failed close after the result: the game provably ended, so the next unlocated run is not merged into it. */
    @Test
    public void aFailedCloseAfterTheResultEndsTheGameForTheNextUnlocatedRun() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true);
        long a = s.one(CommandType.OPEN).gameId;
        s.line(100 * S, Kind.WIN).boundary(110 * S, 2).writerFail(111 * S, a);
        s.location(112 * S, 2, BW_A);
        assertRange(s.one(CommandType.JOIN), 2, 2);
        assertTrue(s.one(CommandType.CLOSE).failed);
        s.boundary(200 * S, 3).ticks(201 * S, 215 * S, true, false).tick(216 * S, false, true); // no location
        assertEquals(2, s.count(CommandType.OPEN));
        assertRange(s.of(CommandType.OPEN).get(1), 3, 3);
        assertEquals(0, s.count(CommandType.REARM));
    }

    // ------------------------------------------------------------------ failure ownership

    private static Script failedBedwarsGame() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true).ticks(4 * S, 50 * S, false, true);
        s.fail(51 * S);
        return s;
    }

    @Test
    public void aFailureInAnActiveGameClosesItAsFailed() {
        Script s = failedBedwarsGame();
        Command close = s.one(CommandType.CLOSE);
        assertTrue(close.failed);
        assertEquals(EndReason.FAILED, close.endedBy);
        assertFalse(s.rules.gameOpen());
        assertEquals(0, s.count(CommandType.REARM));
        s.fail(52 * S);                                                   // a second report changes nothing
        assertEquals(1, s.count(CommandType.CLOSE));
    }

    @Test
    public void sameGameSegmentsOfAFailedGameAreDroppedWithoutRearm() {
        Script s = failedBedwarsGame();
        s.boundary(60 * S, 2).boundary(60 * S + 50_000, 3);              // same-server S07 pair
        s.location(60 * S + 200_000, 3, BW_A);                            // same-server location
        assertRange(s.one(CommandType.DROP), 2, 3);
        s.boundary(80 * S, 4).boundary(80 * S + 50_000, 5).ticks(81 * S, 90 * S, false, true);  // no location
        assertEquals(2, s.count(CommandType.DROP));
        assertRange(s.of(CommandType.DROP).get(1), 4, 5);
        assertEquals(0, s.count(CommandType.REARM));
        assertEquals(0, s.count(CommandType.JOIN));
        assertEquals(1, s.count(CommandType.OPEN));
    }

    @Test
    public void aResultEndsTheFailedGameRearmsAndPlayAgainIsRecorded() {
        Script s = failedBedwarsGame();
        s.line(100 * S, Kind.WIN);
        assertTrue(s.lastHas(CommandType.REARM));
        assertEquals(1, s.count(CommandType.REARM));
        s.boundary(110 * S, 2).location(110 * S + 100_000, 2, BW_B);     // Play Again
        s.tick(111 * S, true, false).tick(140 * S, false, true);
        List<Command> opens = s.of(CommandType.OPEN);
        assertEquals(2, opens.size());
        assertRange(opens.get(1), 2, 2);
        assertEquals(1, s.count(CommandType.CLOSE));                     // the failed one only
    }

    @Test
    public void aLobbyLocationRearms() {
        Script s = failedBedwarsGame();
        s.boundary(60 * S, 2).location(60 * S + 100_000, 2, MAIN);
        assertEquals(1, s.count(CommandType.REARM));
        assertRange(s.one(CommandType.DROP), 2, 2);
        // A later game is recorded normally.
        s.ticks(61 * S, 80 * S, false, false);
        s.boundary(90 * S, 3).location(90 * S + 100_000, 3, BW_B).tick(91 * S, true, false).tick(120 * S, false, true);
        assertRange(s.of(CommandType.OPEN).get(1), 3, 3);
    }

    @Test
    public void aDifferentServerBeforeAnyResultRearmsAndSkipsThatRun() {
        Script s = failedBedwarsGame();
        s.boundary(60 * S, 2).location(60 * S + 100_000, 2, BW_B);        // Play Again mid-game
        assertEquals(1, s.count(CommandType.REARM));
        assertRange(s.one(CommandType.DROP), 2, 2);
        assertEquals(1, s.rules.skippedAfterFailure());
        s.tick(61 * S, true, false).tick(90 * S, false, true);           // that game starts: not recorded
        s.boundary(200 * S, 3).boundary(200 * S + 50_000, 4).ticks(201 * S, 210 * S, false, true);
        assertEquals(1, s.count(CommandType.OPEN));
        assertEquals("pending until a location or the next boundary (code review 1, I1)", 1,
                s.count(CommandType.DROP));
        // the next real switch is recorded; the unlocated pair before it was the skipped game's
        s.line(300 * S, Kind.WIN);
        s.boundary(320 * S, 5);
        assertRange(s.of(CommandType.DROP).get(1), 3, 4);
        s.location(320 * S + 100_000, 5, BW_A).tick(321 * S, true, false).tick(350 * S, false, true);
        assertEquals(2, s.count(CommandType.OPEN));
        assertRange(s.of(CommandType.OPEN).get(1), 5, 5);
    }

    @Test
    public void aFailureInAPendingRunDropsIt() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).fail(3 * S);
        assertRange(s.one(CommandType.DROP), 1, 1);
        s.tick(10 * S, false, true);
        assertEquals(0, s.count(CommandType.OPEN));
        assertEquals(0, s.count(CommandType.REARM));
        assertEquals(0, s.rules.bedwarsVisitsWithoutStart());
    }

    @Test
    public void aFailureOutsideAnyGameRearmsAtOnce() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, MAIN).ticks(2 * S, 12 * S, false, false);
        s.fail(13 * S);
        assertEquals(1, s.count(CommandType.REARM));
    }

    @Test
    public void aNewConnectionRearms() {
        Script s = failedBedwarsGame();
        s.disconnect(60 * S);
        assertEquals(0, s.count(CommandType.REARM));
        s.connect(70 * S);
        assertEquals(1, s.count(CommandType.REARM));
        s.boundary(71 * S, 1).location(71 * S + 100_000, 1, BW_B).tick(72 * S, true, false).tick(80 * S, false, true);
        assertEquals(2, s.count(CommandType.OPEN));
    }

    // ------------------------------------------------------------------ settings

    @Test
    public void recordOffClosesRemovesAndNothingOpensUntilTheNextConnection() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true);
        s.boundary(50 * S, 2);                                           // a pending candidate too
        s.record(51 * S, false);
        Command close = s.one(CommandType.CLOSE);
        assertEquals(EndReason.SETTINGS, close.endedBy);
        assertTrue(s.lastHas(CommandType.REMOVE_HANDLER));
        assertRange(s.one(CommandType.JOIN), 2, 2);                      // joined before the close
        assertEquals(0, s.count(CommandType.DROP));
        assertFalse(s.rules.active());

        s.boundary(60 * S, 3).location(60 * S + 100_000, 3, BW_B).tick(61 * S, true, false).tick(70 * S, false, true);
        s.record(71 * S, true);                                           // on applies at the next connection
        s.boundary(80 * S, 4).location(80 * S + 100_000, 4, SKYWARS).ticks(81 * S, 200 * S, false, false);
        assertTrue(s.onMarkerKeyIgnored(201 * S));
        assertEquals(1, s.count(CommandType.OPEN));
        assertEquals(1, s.count(CommandType.REMOVE_HANDLER));

        s.disconnect(300 * S).connect(310 * S).boundary(311 * S, 1).location(311 * S + 100_000, 1, BW_A);
        s.tick(312 * S, true, false).tick(320 * S, false, true);
        assertEquals(2, s.count(CommandType.OPEN));
    }

    @Test
    public void recordOffDropsAPendingRunWithoutCountingIt() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).record(3 * S, false);
        assertRange(s.one(CommandType.DROP), 1, 1);
        assertEquals(1, s.count(CommandType.REMOVE_HANDLER));
        assertEquals(0, s.rules.bedwarsVisitsWithoutStart());
        assertEquals(0, s.count(CommandType.CLOSE));
    }

    @Test
    public void aConnectionStartedWithRecordOffNeverOpens() {
        RewindGameRules r = new RewindGameRules();
        List<Command> all = new ArrayList<Command>();
        all.addAll(r.onConnect(0, false, false));
        all.addAll(r.onBoundary(S, 1));
        all.addAll(r.onLocation(S + 1, 1, BW_A));
        all.addAll(r.tick(2 * S, true, false));
        all.addAll(r.tick(3 * S, false, true));
        all.addAll(r.onMarkerKey(4 * S));
        all.addAll(r.onGameLine(5 * S, Kind.WIN));
        all.addAll(r.tick(30 * S, false, false));
        all.addAll(r.onRecordSetting(31 * S, true));
        all.addAll(r.tick(32 * S, false, true));
        all.addAll(r.onDisconnect(40 * S, false));
        assertTrue(all.toString(), all.isEmpty());

        RewindGameRules sp = new RewindGameRules();
        assertTrue(sp.onConnect(0, true, false).isEmpty());
        assertTrue(sp.onBoundary(S, 1).isEmpty());
    }

    // ------------------------------------------------------------------ markers during an undecided switch (code review 3, I3)

    /** Game A open on BW_A in segment 1; WORLD_LOAD of segment 2 at 4 s; its location has not come yet. */
    private static Script openThenUndecidedSwitch() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true);
        assertEquals(1, s.count(CommandType.OPEN));
        s.boundary(4 * S, 2);
        return s;
    }

    private static Command markerCommand(Script s, int index) {
        return s.of(CommandType.MARKER).get(index);
    }

    /** The reviewer's sequence: the key's TRACK record is in segment 2, which turns out to be game B's. */
    @Test
    public void aKeyDuringAnUndecidedSwitchToAnotherServerMarksTheNextGameNotTheOpenOne() {
        Script s = openThenUndecidedSwitch();
        s.take(s.rules.onMarkerKey(4 * S + 100_000));
        assertTrue("accepted: it flashes", s.lastHas(CommandType.FLASH));
        Command held = markerCommand(s, 0);
        assertEquals("held for segment 2, not applied to game A", 0L, held.gameId);
        s.location(4 * S + 200_000, 2, BW_B);
        Command closeA = s.one(CommandType.CLOSE);
        assertFalse("A holds no KEY record", closeA.keyMarked);
        s.tick(5 * S, true, false).tick(30 * S, false, true);
        Command openB = s.of(CommandType.OPEN).get(1);
        assertRange(openB, 2, 2);
        assertTrue("B holds the KEY record", openB.keyMarked);
        assertEquals(1, openB.markers.size());
        assertEquals(MarkerRules.Marker.KEY, openB.markers.get(0).marker);
        s.disconnect(60 * S);
        assertTrue(s.of(CommandType.CLOSE).get(1).keyMarked);
    }

    @Test
    public void aKeyDuringAnUndecidedSameServerSwitchMarksTheOpenGameWhenJoined() {
        Script s = openThenUndecidedSwitch();
        s.take(s.rules.onMarkerKey(4 * S + 100_000));
        assertEquals(0L, markerCommand(s, 0).gameId);
        s.location(4 * S + 200_000, 2, BW_A);
        assertRange(s.one(CommandType.JOIN), 2, 2);
        s.line(50 * S, Kind.WIN).ticks(51 * S, 70 * S, false, false);
        assertTrue(s.one(CommandType.CLOSE).keyMarked);
    }

    @Test
    public void aKeyDuringAnUndecidedSwitchToALobbyProtectsNothing() {
        Script s = openThenUndecidedSwitch();
        s.take(s.rules.onMarkerKey(4 * S + 100_000));
        assertTrue(s.lastHas(CommandType.FLASH));
        s.location(4 * S + 200_000, 2, MAIN);
        assertFalse(s.one(CommandType.CLOSE).keyMarked);
        s.ticks(5 * S, 20 * S, false, false);
        assertRange(s.one(CommandType.DROP), 2, 2);
        s.boundary(30 * S, 3).location(30 * S + 100_000, 3, BW_B).tick(31 * S, true, false).tick(50 * S, false, true);
        Command next = s.of(CommandType.OPEN).get(1);
        assertFalse("the lobby's key never reaches a later game", next.keyMarked);
        assertTrue(next.markers.isEmpty());
    }

    @Test
    public void aKeyDuringAnUndecidedSwitchThenADisconnectMarksTheOpenGame() {
        Script s = openThenUndecidedSwitch();
        s.take(s.rules.onMarkerKey(4 * S + 100_000));
        s.disconnect(5 * S);
        assertRange(s.one(CommandType.JOIN), 2, 2);
        assertTrue("segment 2 and its KEY record are joined to A", s.one(CommandType.CLOSE).keyMarked);
    }

    @Test
    public void automaticMarkersDuringAnUndecidedSwitchGoToTheGameThatOwnsTheSegment() {
        Script other = openThenUndecidedSwitch();
        other.line(4 * S + 100_000, Kind.FINAL_KILL);
        assertEquals(0L, markerCommand(other, 0).gameId);
        other.location(4 * S + 200_000, 2, BW_B);
        other.tick(5 * S, true, false).tick(30 * S, false, true);
        Command openB = other.of(CommandType.OPEN).get(1);
        assertEquals(1, openB.markers.size());
        assertEquals(MarkerRules.Marker.FINAL_KILL, openB.markers.get(0).marker);
        assertFalse(openB.keyMarked);

        Script lobby = openThenUndecidedSwitch();
        lobby.line(4 * S + 100_000, Kind.DEATH);
        lobby.location(4 * S + 200_000, 2, MAIN);
        lobby.ticks(5 * S, 20 * S, false, false);
        lobby.boundary(30 * S, 3).location(30 * S + 100_000, 3, BW_B).tick(31 * S, true, false).tick(50 * S, false, true);
        assertTrue(lobby.of(CommandType.OPEN).get(1).markers.isEmpty());

        Script same = openThenUndecidedSwitch();
        same.line(4 * S + 100_000, Kind.BED_BREAK);
        same.location(4 * S + 200_000, 2, BW_A);
        assertRange(same.one(CommandType.JOIN), 2, 2);
        assertEquals(1, same.count(CommandType.OPEN));
    }

    @Test
    public void aKeyInTheOpenGamesOwnSegmentCarriesItsGameId() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true);
        long a = s.one(CommandType.OPEN).gameId;
        s.take(s.rules.onMarkerKey(10 * S));
        Command m = markerCommand(s, 0);
        assertEquals(a, m.gameId);
        assertTrue(m.keyMarked);
    }

    // ------------------------------------------------------------------ metadata after OPEN (code review 2, I4)

    private static List<Command> infos(Script s) {
        return s.of(CommandType.valueOf("INFO"));
    }

    @Test
    public void aLocationAfterASidebarOpenUpdatesTheGamesModeAndMap() {
        Script s = new Script().connect(0).boundary(S, 1);
        s.ticks(2 * S, 10 * S, true, false).tick(11 * S, false, true);   // the sidebar fallback opens it
        Command open = s.one(CommandType.OPEN);
        assertNull(open.info.mode);
        assertNull(open.info.map);
        s.location(21 * S, 1, BW_A);                                      // the same segment's location, late
        List<Command> info = infos(s);
        assertEquals(s.all.toString(), 1, info.size());
        assertEquals("BEDWARS_EIGHT_ONE", info.get(0).info.mode);
        assertEquals("Lighthouse", info.get(0).info.map);
        assertEquals("BEDWARS", info.get(0).info.serverType);
        s.location(30 * S, 1, BW_A);                                      // unchanged: no second update
        assertEquals(1, infos(s).size());
        assertEquals(0, s.count(CommandType.CLOSE));
    }

    @Test
    public void aSameServerLocationWithAnotherMapUpdatesTheGameLatestWins() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true);
        assertEquals("Lighthouse", s.one(CommandType.OPEN).info.map);
        Location moved = new Location("mini12A", "BEDWARS", null, "BEDWARS_EIGHT_ONE", "Fakeport");
        s.boundary(50 * S, 2).location(50 * S + 100_000, 2, moved);
        assertRange(s.one(CommandType.JOIN), 2, 2);
        List<Command> info = infos(s);
        assertEquals(1, info.size());
        assertEquals("Fakeport", info.get(0).info.map);
        assertEquals("BEDWARS_EIGHT_ONE", info.get(0).info.mode);
        assertTrue(indexOf(s, CommandType.JOIN) < indexOf(s, CommandType.valueOf("INFO")));
        Location partial = new Location("mini12A", "BEDWARS", null, null, null);
        s.boundary(60 * S, 3).location(60 * S + 100_000, 3, partial);   // absent fields never erase
        assertEquals(1, infos(s).size());
    }

    @Test
    public void noLocationAtAllLeavesModeAndMapAbsent() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_B);
        s.tick(2 * S, true, false).tick(3 * S, false, true).line(50 * S, Kind.WIN).ticks(51 * S, 70 * S, false, false);
        s.boundary(80 * S, 2).ticks(81 * S, 90 * S, true, false).tick(91 * S, false, true); // next game, no location
        List<Command> opens = s.of(CommandType.OPEN);
        assertEquals(2, opens.size());
        assertNull("never the previous server's mode", opens.get(1).info.mode);
        assertNull(opens.get(1).info.map);
        s.disconnect(100 * S);
        assertEquals(0, infos(s).size());
    }

    @Test
    public void commandsCarryNoServerName() {
        Script s = new Script().connect(0).boundary(S, 1).location(S + 100_000, 1, BW_A);
        s.tick(2 * S, true, false).tick(3 * S, false, true).disconnect(10 * S);
        for (Command c : s.all) {
            assertFalse(c.toString(), c.toString().contains("mini12A"));
        }
        assertNotNull(s.one(CommandType.OPEN).info);
    }
}
