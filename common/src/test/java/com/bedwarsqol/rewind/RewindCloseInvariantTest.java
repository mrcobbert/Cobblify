package com.bedwarsqol.rewind;

import com.bedwarsqol.feature.HypixelLocationCodec.Location;
import com.bedwarsqol.feature.SessionChatLine.Kind;
import com.bedwarsqol.rewind.RewindGameRules.Command;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertTrue;

/**
 * Code review 3, I1: randomized interleavings of everything the rules hear on one Hypixel connection (client ticks,
 * the capture handler starting segments ahead of the client, WORLD_LOADs, locations of the same server, another
 * server or a lobby, results, the 2 h cap, capture/client/writer failures, Marker Key and automatic markers,
 * disconnects and Record Games off), checked against the command stream:
 *
 * <ul>
 *   <li>A JOIN only while a game is open; an OPEN only while none is; a DROP never takes a segment routed to a game.</li>
 *   <li><b>No CLOSE leaves its game's segments behind:</b> at a game's CLOSE, every segment the capture handler had
 *       started on that game's server (after its first segment) is already OPENed/JOINed into it, or it is part of a
 *       run that also holds another server's segment (it was handed to the next run, the plan's 5 s rule); such a
 *       handed segment must later be OPENed or DROPped together with that other server's segment, never JOINed or
 *       dropped on its own as "the same game".</li>
 *   <li>Every OPEN is CLOSEd by the end of its connection.</li>
 *   <li>Code review 3, I3: a CLOSE is key-marked exactly when a KEY marker's TRACK segment is one of its segments.</li>
 * </ul>
 *
 * The world model: servers never come back once left, a new server's location reaches the client within 4 s of its
 * WORLD_LOAD and before the next one (a same-server respawn gets one only sometimes), and the capture handler's
 * progress is settled before every rules call, as the wiring does. Seeds: {@code REWIND_INVARIANT_SEED} (environment)
 * picks the first one, so repeated runs can use fresh seeds.
 */
public class RewindCloseInvariantTest {

    private static final long S = RewindGameRules.SECOND_US;
    private static final int SEEDS = 3000;
    private static final int STEPS = 400;

    private static final int BW = 0;
    private static final int OTHER = 1;
    private static final int LOBBY = 2;

    /** What the seeds exercised, so a generator change cannot make the test vacuous. */
    static final class Coverage {
        int opens;
        int closes;
        int dueCloses;
        int closesWaitingForAJoin;
        int closesAtAnEarlierDueTime;
        int handed;
        int heldMarkers;
        int ticksWithCaptureAhead;
        int keyMarkedCloses;

        @Override
        public String toString() {
            return "opens=" + opens + " closes=" + closes + " dueCloses=" + dueCloses + " closesWaitingForAJoin="
                    + closesWaitingForAJoin + " closesAtAnEarlierDueTime=" + closesAtAnEarlierDueTime + " handed=" + handed
                    + " heldMarkers=" + heldMarkers + " ticksWithCaptureAhead=" + ticksWithCaptureAhead + " keyMarkedCloses="
                    + keyMarkedCloses;
        }
    }

    @Test
    public void noCloseEverLeavesItsGamesCapturedSegmentsBehind() {
        long base = baseSeed();
        Coverage cov = new Coverage();
        for (long seed = base; seed < base + SEEDS; seed++) {
            try {
                new Sim(seed, false, cov).run();
            } catch (AssertionError e) {
                String trace;
                try {
                    new Sim(seed, true, new Coverage()).run();
                    trace = "(replay passed?)";
                } catch (AssertionError again) {
                    trace = again.getMessage();
                }
                throw new AssertionError("seed " + seed + " (base " + base + "): " + trace, e);
            }
        }
        String c = cov.toString();
        if (!c.isEmpty() && Boolean.parseBoolean(System.getenv("REWIND_INVARIANT_VERBOSE"))) {
            System.out.println("base " + base + ": " + c);
        }
        assertTrue(c, cov.opens > SEEDS);
        assertTrue(c, cov.dueCloses > SEEDS / 2);
        assertTrue("due closes that waited and then JOINed: " + c, cov.closesWaitingForAJoin > 50);
        assertTrue("closes stamped with an earlier due time: " + c, cov.closesAtAnEarlierDueTime > 50);
        assertTrue("segments handed to the next run: " + c, cov.handed > 20);
        assertTrue("markers held by an undecided candidate: " + c, cov.heldMarkers > 50);
        assertTrue("ticks with a game open while the capture handler was ahead of the client: " + c, cov.ticksWithCaptureAhead > 50);
        assertTrue(c, cov.keyMarkedCloses > 50);
    }

    static long baseSeed() {
        String env = System.getenv("REWIND_INVARIANT_SEED");
        if (env != null && !env.trim().isEmpty()) {
            try {
                return Long.parseLong(env.trim());
            } catch (NumberFormatException ignored) {
                // fall through to the fixed seeds
            }
        }
        return 1L;
    }

    /** One randomized connection sequence with the oracle. */
    private static final class Sim {
        final long seed;
        final Random rnd;
        final boolean tracing;
        final Coverage cov;
        final StringBuilder trace = new StringBuilder();
        final RewindGameRules rules = new RewindGameRules();
        long t = 1_000_000L;

        boolean connected;
        boolean recording;

        // ---- per connection world
        int netSeg;
        int clientSeg;
        final List<Integer> segSrv = new ArrayList<Integer>();
        final Map<Integer, Integer> kind = new HashMap<Integer, Integer>();
        final Set<Integer> started = new HashSet<Integer>();
        int nextSrv = 1;
        int owedLocSeg;
        long owedDeadline;

        // ---- capture model (mirrors CaptureSession's latch and rearm)
        boolean latched;
        int rearmRequests;
        int rearmSeen;
        int failCount;
        int failSeg;
        int seenFail;
        int resumeCount;
        int resumeSeg;
        int seenResume;

        // ---- oracle, per connection
        long openId;
        int reported;
        final Map<Integer, Long> routedTo = new HashMap<Integer, Long>();
        final Set<Integer> routedAny = new HashSet<Integer>();
        final Set<Integer> joined = new HashSet<Integer>();
        final Map<Long, Integer> gameSrv = new HashMap<Long, Integer>();
        final Map<Long, Integer> gameFirst = new HashMap<Long, Integer>();
        final List<int[]> openOrDropRanges = new ArrayList<int[]>();
        final List<long[]> mustBeHanded = new ArrayList<long[]>();
        final List<Integer> keySegs = new ArrayList<Integer>();
        final Set<Long> everOpened = new HashSet<Long>();

        Sim(long seed, boolean tracing, Coverage cov) {
            this.seed = seed;
            this.rnd = new Random(seed);
            this.tracing = tracing;
            this.cov = cov;
        }

        void note(String s) {
            if (tracing) {
                trace.append('\n').append(t).append(' ').append(s);
            }
        }

        void check(boolean ok, String what) {
            if (!ok) {
                throw new AssertionError(what + (tracing ? "\ntrace:" + trace : ""));
            }
        }

        void run() {
            connect();
            for (int i = 0; i < STEPS; i++) {
                stepOnce();
            }
            if (connected) {
                disconnect();
            }
        }

        // ------------------------------------------------------------ the world

        void connect() {
            connected = true;
            recording = rnd.nextInt(10) != 0;
            netSeg = 0;
            clientSeg = 0;
            segSrv.clear();
            segSrv.add(-1);
            owedLocSeg = 0;
            latched = false;
            rearmRequests = rearmSeen = failCount = seenFail = resumeCount = seenResume = 0;
            openId = 0;
            reported = 0;
            routedTo.clear();
            routedAny.clear();
            joined.clear();
            openOrDropRanges.clear();
            mustBeHanded.clear();
            keySegs.clear();
            note("connect recording=" + recording);
            take(rules.onConnect(t, false, recording));
        }

        void disconnect() {
            settle();
            note("disconnect");
            take(rules.onDisconnect(t, rnd.nextBoolean()));
            endOfConnection();
            connected = false;
        }

        void stepOnce() {
            long dt;
            int d = rnd.nextInt(100);
            if (d < 25) {
                dt = 0;
            } else if (d < 85) {
                dt = rnd.nextInt(1_500_000);
            } else if (d < 98) {
                dt = 5 * S + (long) rnd.nextInt(35) * S;                 // a stall
            } else {
                dt = RewindGameRules.GAME_CAP_US;                         // past the cap
            }
            if (owedLocSeg != 0 && t + dt > owedDeadline) {
                location();                                               // it reached the client in time
            }
            t += dt;
            if (!connected) {
                if (rnd.nextInt(3) == 0) {
                    connect();
                }
                return;
            }
            int e = rnd.nextInt(100);
            if (e < 22) {
                nettyBoundary();
            } else if (e < 40) {
                clientBoundary();
            } else if (e < 48) {
                if (owedLocSeg != 0) {
                    location();
                } else {
                    tick();
                }
            } else if (e < 72) {
                tick();
            } else if (e < 76) {
                settle();
                note("result");
                take(rules.onGameLine(t, rnd.nextBoolean() ? Kind.WIN : Kind.LOSS));
            } else if (e < 81) {
                settle();
                note("key");
                take(rules.onMarkerKey(t));
            } else if (e < 85) {
                settle();
                Kind k = rnd.nextBoolean() ? Kind.FINAL_KILL : Kind.DEATH;
                note("line " + k);
                take(rules.onGameLine(t, k));
            } else if (e < 88) {
                captureFailure();
            } else if (e < 90) {
                settle();
                note("client failure " + Math.max(1, clientSeg));
                take(rules.onClientFailure(t, Math.max(1, clientSeg)));
            } else if (e < 92) {
                settle();
                long id = openId != 0 && rnd.nextBoolean() ? openId : rnd.nextInt(5) + 1;
                note("writer failure game=" + id);
                take(rules.onWriterFailure(t, id));
            } else if (e < 95) {
                if (owedLocSeg != 0) {
                    location();
                }
                disconnect();
            } else if (e < 96) {
                settle();
                note("record off");
                take(rules.onRecordSetting(t, false));
            } else {
                tick();
            }
        }

        int srv(int seg) {
            return segSrv.get(seg);
        }

        int newServer(int k) {
            int id = nextSrv++;
            kind.put(id, k);
            return id;
        }

        /** Netty reads a boundary packet: same server (dimension change), or another server or lobby. */
        void nettyBoundary() {
            int s;
            int r = rnd.nextInt(100);
            if (netSeg > 0 && r < 45) {
                s = srv(netSeg);
            } else if (r < 72) {
                s = newServer(BW);
            } else if (r < 90) {
                s = newServer(LOBBY);
            } else {
                s = newServer(OTHER);
            }
            netSeg++;
            segSrv.add(s);
            if (latched && rearmRequests != rearmSeen) {
                rearmSeen = rearmRequests;
                latched = false;
                resumeSeg = netSeg;
                resumeCount++;
            }
            note("netty boundary " + netSeg + " srv=" + s + " kind=" + kind.get(s));
        }

        void captureFailure() {
            if (!latched) {
                latched = true;
                rearmSeen = rearmRequests;
                failSeg = netSeg;
                failCount++;
                note("capture failure in " + netSeg);
            }
        }

        void clientBoundary() {
            if (clientSeg >= netSeg) {
                tick();
                return;
            }
            if (owedLocSeg != 0) {
                location();                                               // queued before the next boundary
            }
            clientSeg++;
            settle();
            note("WORLD_LOAD " + clientSeg);
            take(rules.onBoundary(t, clientSeg));
            boolean changed = clientSeg == 1 || srv(clientSeg) != srv(clientSeg - 1);
            if (changed || rnd.nextInt(10) < 3) {
                owedLocSeg = clientSeg;
                owedDeadline = t + rnd.nextInt(4_000_000);
            }
        }

        void location() {
            int seg = owedLocSeg;
            owedLocSeg = 0;
            settle();
            int s = srv(seg);
            Location loc;
            switch (kind.get(s)) {
                case BW:
                    loc = new Location("fakeBw" + s, "BEDWARS", null, "BEDWARS_EIGHT_ONE", "FakeMap");
                    break;
                case LOBBY:
                    loc = new Location("fakeLobby" + s, "MAIN", null, null, null);
                    break;
                default:
                    loc = new Location("fakeSw" + s, "SKYWARS", null, "solo_normal", "FakeMap");
                    break;
            }
            note("location seg=" + seg + " srv=" + s);
            take(rules.onLocation(t, seg, loc));
        }

        void tick() {
            settle();
            boolean queue = false;
            boolean active = false;
            if (clientSeg > 0) {
                int s = srv(clientSeg);
                if (kind.get(s) == BW) {
                    if (!started.contains(s) && rnd.nextInt(8) == 0) {
                        started.add(s);
                    }
                    active = started.contains(s);
                    queue = !active;
                }
            }
            if (openId != 0 && netSeg > clientSeg) cov.ticksWithCaptureAhead++;
            note("tick q=" + queue + " a=" + active);
            take(rules.tick(t, queue, active));
        }

        /** What the wiring reports before every rules call (Rewind.settle). */
        void settle() {
            if (!connected) {
                return;
            }
            rules.onCaptureProgress(t, netSeg);
            if (netSeg > reported) {
                reported = netSeg;
            }
            if (resumeCount != seenResume) {
                seenResume = resumeCount;
                rules.onCaptureResumed(resumeSeg);
            }
            if (failCount != seenFail) {
                seenFail = failCount;
                take(rules.onCaptureFailure(t, failSeg));
            }
        }

        // ------------------------------------------------------------ the oracle

        void take(List<Command> commands) {
            boolean joinedHere = false;
            for (Command c : commands) {
                note("  -> " + c.type + (c.firstSegment >= 0 ? " [" + c.firstSegment + ".." + c.lastSegment + "]" : "")
                        + (c.type == RewindGameRules.CommandType.CLOSE ? " game=" + c.gameId + " " + c.endedBy
                        + " keyMarked=" + c.keyMarked : "")
                        + (c.type == RewindGameRules.CommandType.OPEN ? " game=" + c.gameId : ""));
                switch (c.type) {
                    case OPEN:
                        cov.opens++;
                        check(openId == 0, "OPEN while game " + openId + " is open");
                        check(everOpened.add(c.gameId), "game id reused " + c.gameId);
                        openId = c.gameId;
                        gameSrv.put(c.gameId, srv(c.firstSegment));
                        gameFirst.put(c.gameId, c.firstSegment);
                        for (int s = c.firstSegment; s <= c.lastSegment; s++) {
                            route(s, c.gameId);
                        }
                        openOrDropRanges.add(new int[]{c.firstSegment, c.lastSegment});
                        break;
                    case JOIN:
                        check(openId != 0, "JOIN [" + c.firstSegment + ".." + c.lastSegment + "] with no game open");
                        joinedHere = true;
                        for (int s = c.firstSegment; s <= c.lastSegment; s++) {
                            route(s, openId);
                            joined.add(s);
                        }
                        break;
                    case DROP:
                        for (int s = c.firstSegment; s <= c.lastSegment; s++) {
                            check(!routedTo.containsKey(s), "DROP of segment " + s + " routed to game " + routedTo.get(s));
                            routedAny.add(s);
                        }
                        openOrDropRanges.add(new int[]{c.firstSegment, c.lastSegment});
                        break;
                    case CLOSE:
                        check(openId != 0 && openId == c.gameId, "CLOSE of game " + c.gameId + " while " + openId + " open");
                        check(c.timeUs <= t, "CLOSE stamped in the future");
                        cov.closes++;
                        boolean due = c.endedBy == RewindGameRules.EndReason.RESULT
                                || c.endedBy == RewindGameRules.EndReason.CAP || c.endedBy == RewindGameRules.EndReason.FAILED;
                        if (due) {
                            cov.dueCloses++;
                            if (joinedHere) cov.closesWaitingForAJoin++;
                        }
                        if (c.timeUs < t) cov.closesAtAnEarlierDueTime++;
                        if (c.keyMarked) cov.keyMarkedCloses++;
                        close(c);
                        openId = 0;
                        break;
                    case MARKER:
                        if (c.gameId == 0 && openId != 0) cov.heldMarkers++;
                        if (c.marker == MarkerRules.Marker.KEY && clientSeg > 0) {
                            keySegs.add(clientSeg);                        // the wiring's TRACK segment
                        }
                        break;
                    case REARM:
                        rearmRequests++;
                        break;
                    default:
                        break;
                }
            }
        }

        void route(int s, long game) {
            check(!routedAny.contains(s), "segment " + s + " routed twice (game " + game + ")");
            routedAny.add(s);
            routedTo.put(s, game);
        }

        void close(Command c) {
            long g = c.gameId;
            int server = gameSrv.get(g);
            for (int s = gameFirst.get(g) + 1; s <= reported; s++) {
                if (srv(s) != server) {
                    continue;
                }
                Long to = routedTo.get(s);
                if (to != null && to == g) {
                    continue;
                }
                check(to == null, "segment " + s + " of game " + g + "'s server went to game " + to);
                mustBeHanded.add(new long[]{s, server});
                cov.handed++;
            }
            boolean key = false;
            for (int s : keySegs) {
                Long to = routedTo.get(s);
                key |= to != null && to == g;
            }
            check(c.keyMarked == key, "game " + g + " CLOSE keyMarked=" + c.keyMarked + " but its KEY records say " + key);
        }

        void endOfConnection() {
            check(openId == 0, "game " + openId + " still open after the connection ended");
            for (long[] h : mustBeHanded) {
                int s = (int) h[0];
                int server = (int) h[1];
                check(!joined.contains(s), "segment " + s + " left behind by its game's close was JOINed later");
                boolean handed = false;
                for (int[] r : openOrDropRanges) {
                    if (r[0] <= s && s <= r[1]) {
                        for (int x = r[0]; x <= r[1]; x++) {
                            handed |= srv(x) != server;
                        }
                    }
                }
                check(handed, "segment " + s + " was left behind by its game's close and never handed to the next run");
            }
        }
    }
}
