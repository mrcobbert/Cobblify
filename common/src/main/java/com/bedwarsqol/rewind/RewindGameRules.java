package com.bedwarsqol.rewind;

import com.bedwarsqol.feature.HypixelLocationCodec.Location;
import com.bedwarsqol.feature.SessionChatLine;
import com.bedwarsqol.rewind.MarkerRules.Marker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Decides which world segments of a connection are a game (PLAN "Game rules"). Pure state machine
 * on the client thread: events and time (microseconds, any monotonic origin) in, {@link Command}s
 * out. It never touches files, Netty or Minecraft; the wiring executes the commands.
 *
 * <p><b>Segments and runs.</b> Segment k is the stream from the k-th boundary (S01, or an S07 that
 * changes dimension) to the next; the caller passes k as the capture handler numbers it. New
 * segments are pending (held in memory by the writer) until a command routes them: {@code OPEN}
 * (into a new file), {@code JOIN} (into the open file) or {@code DROP}. A <i>visit</i> is the
 * current server; its first run is classified by a location tagged with one of its segments.
 *
 * <ul>
 *   <li>Lobby: {@code serverType} MAIN or TOURNAMENT, or a {@code lobbyName}. The verdict waits
 *       {@link #LOBBY_VERDICT_WAIT_US}: the Bed Wars queue or game on the sidebar turns it into a
 *       Bed Wars run ({@code lobbyOverridden}), otherwise the run is dropped.</li>
 *   <li>Every other location is a game server (a bare {@code serverName} is "unclassified"). Bed
 *       Wars ({@code serverType} BEDWARS, a {@code BEDWARS_*} mode, or the Bed Wars sidebar) opens
 *       at the active-game rising edge with no minimum length; others open once the visit reaches
 *       {@link #MIN_VISIT_US}, starting at the visit start.</li>
 *   <li>No location within {@link #LOCATION_WAIT_US}: the Bed Wars sidebar makes it a Bed Wars
 *       run; otherwise it stays pending until a location arrives or the visit ends (then
 *       {@code visitsWithoutLocation}).</li>
 *   <li>Single-player: a game at once.</li>
 * </ul>
 *
 * <p><b>Continuation.</b> Once a visit is classified, a new boundary is a candidate: it continues
 * the visit if its location names the same server or none arrives within {@link #CONTINUE_WAIT_US};
 * a different server ends the visit (closing an open game) and starts a new run.
 *
 * <p><b>Failure ownership.</b> A failure closes the open game as failed (or drops a pending run).
 * Its later same-visit segments are dropped and nothing rearms capture until the failed game
 * provably ended: its result, a lobby or other-server run, or a new connection ({@code REARM}). A
 * run whose boundary passed before that rearm was not captured, so it is skipped.
 *
 * <p><b>Settings.</b> Record off closes the game, drops pending segments and removes the handler;
 * recording resumes only at a connection started with it on.
 */
public final class RewindGameRules {

    public static final long SECOND_US = 1_000_000L;
    /** A candidate segment with no location after this continues the current visit. */
    public static final long CONTINUE_WAIT_US = 5 * SECOND_US;
    /** An unlocated run may become a Bed Wars run by the sidebar after this. */
    public static final long LOCATION_WAIT_US = 10 * SECOND_US;
    /** How long a lobby verdict waits for the Bed Wars sidebar before the run is dropped. */
    public static final long LOBBY_VERDICT_WAIT_US = 10 * SECOND_US;
    /** Non-Bed-Wars game-server visits shorter than this are not kept. */
    public static final long MIN_VISIT_US = 60 * SECOND_US;
    /** A game closes this long after its result. */
    public static final long RESULT_CLOSE_US = 15 * SECOND_US;
    /** Pending runs are dropped after this long ... */
    public static final long PENDING_MAX_US = 20 * 60 * SECOND_US;
    /** ... or above this many pending bytes. */
    public static final long PENDING_MAX_BYTES = 32L * 1024 * 1024;
    /** A game is closed at this length. */
    public static final long GAME_CAP_US = 2 * 3600 * SECOND_US;
    /** Location events kept for the stats file. */
    public static final int LOCATION_LOG_CAP = 16;

    /** Location verdict codes as logged in the stats file. */
    public static final int VERDICT_LOBBY = 1;
    public static final int VERDICT_GAME_SERVER = 2;
    public static final int VERDICT_UNCLASSIFIED = 3;

    private static final int BOUNDARY_RING = 32;
    private static final long UNKNOWN_DT = -1L;

    // ------------------------------------------------------------------ public types

    public enum CommandType {
        /** Create the game file; segments first..last (pending) go into it. */
        OPEN,
        /** Segments first..last go into the open game file. */
        JOIN,
        /** Segments first..last are discarded (pending data and any later records); capture may skip them. */
        DROP,
        /** Finalize the open game. */
        CLOSE,
        /** Write a TRACK marker record now. */
        MARKER,
        /** Show the Marker Key flash. */
        FLASH,
        /** Clear the capture failure latch; copying resumes at the next boundary. */
        REARM,
        /** Remove the capture handler (Record Games / Rewind turned off). */
        REMOVE_HANDLER
    }

    /** Why a game closed; {@link #json} is the {@code .json} {@code endedBy} value. */
    public enum EndReason {
        RESULT("result"),
        LOBBY("lobby"),
        SERVER("server"),
        DISCONNECT("disconnect"),
        QUIT("quit"),
        SETTINGS("settings"),
        FAILED("failed"),
        CAP("cap");

        public final String json;

        EndReason(String json) {
            this.json = json;
        }
    }

    public enum Result { WIN, LOSS, UNKNOWN }

    /** How a game was opened; {@link #code} is the stats {@code startSource}. */
    public enum StartSource {
        BEDWARS(1),
        VISIT(2),
        SINGLEPLAYER(3);

        public final int code;

        StartSource(int code) {
            this.code = code;
        }
    }

    /** {@code .json} {@code classification}. */
    public enum Classification {
        /** A location with at least one of serverType, lobbyName, mode or map. */
        CLASSIFIED("classified"),
        /** A bare {@code serverName}. */
        UNCLASSIFIED("unclassified"),
        /** The Bed Wars sidebar decided it (no location, or it overrode a lobby verdict). */
        SIDEBAR("sidebar"),
        SINGLEPLAYER("singleplayer");

        public final String json;

        Classification(String json) {
            this.json = json;
        }
    }

    /** Gallery metadata known at open. Never holds the server name or address. */
    public static final class GameInfo {
        /** Hypixel mode id, or {@code SINGLEPLAYER}; null when unknown. */
        public final String mode;
        public final String map;
        public final String serverType;
        public final Classification classification;
        public final boolean singlePlayer;

        GameInfo(String mode, String map, String serverType, Classification classification, boolean singlePlayer) {
            this.mode = mode;
            this.map = map;
            this.serverType = serverType;
            this.classification = classification;
            this.singlePlayer = singlePlayer;
        }

        @Override
        public String toString() {
            return "GameInfo{mode=" + mode + ", map=" + map + ", serverType=" + serverType
                    + ", classification=" + classification + ", singlePlayer=" + singlePlayer + "}";
        }
    }

    /** One accepted marker and when it happened. */
    public static final class MarkerEvent {
        public final Marker marker;
        public final long timeUs;

        MarkerEvent(Marker marker, long timeUs) {
            this.marker = marker;
            this.timeUs = timeUs;
        }

        @Override
        public String toString() {
            return marker + "@" + timeUs;
        }
    }

    /** One location event for the stats file: numbers and booleans only. */
    public static final class LocationLogEntry {
        public final int segment;
        /** Receipt time minus the segment's boundary time; negative if it came first; -1 unknown. */
        public final long dtUsFromBoundary;
        public final boolean hasServerType;
        /** 0 absent, 1 BEDWARS, 2 MAIN, 3 TOURNAMENT, 4 SKYBLOCK, 5 HOUSING, 6 other. */
        public final int serverTypeCode;
        public final boolean hasLobbyName;
        public final boolean hasMode;
        public final boolean hasMap;
        /** {@link #VERDICT_LOBBY}, {@link #VERDICT_GAME_SERVER} or {@link #VERDICT_UNCLASSIFIED}. */
        public final int verdict;

        LocationLogEntry(int segment, long dtUsFromBoundary, boolean hasServerType, int serverTypeCode,
                         boolean hasLobbyName, boolean hasMode, boolean hasMap, int verdict) {
            this.segment = segment;
            this.dtUsFromBoundary = dtUsFromBoundary;
            this.hasServerType = hasServerType;
            this.serverTypeCode = serverTypeCode;
            this.hasLobbyName = hasLobbyName;
            this.hasMode = hasMode;
            this.hasMap = hasMap;
            this.verdict = verdict;
        }

        @Override
        public String toString() {
            return "Loc{seg=" + segment + ", dt=" + dtUsFromBoundary + ", type=" + serverTypeCode
                    + ", lobby=" + hasLobbyName + ", mode=" + hasMode + ", map=" + hasMap + ", verdict=" + verdict + "}";
        }
    }

    /** The rules' part of the stats file, covering everything since the previous file closed. */
    public static final class RunStats {
        public final List<LocationLogEntry> locations;
        public final int lobbyOverridden;
        public final int visitsWithoutLocation;
        public final int bedwarsVisitsWithoutStart;
        public final int shortVisitsDropped;
        public final StartSource startSource;

        RunStats(List<LocationLogEntry> locations, int lobbyOverridden, int visitsWithoutLocation,
                 int bedwarsVisitsWithoutStart, int shortVisitsDropped, StartSource startSource) {
            this.locations = Collections.unmodifiableList(locations);
            this.lobbyOverridden = lobbyOverridden;
            this.visitsWithoutLocation = visitsWithoutLocation;
            this.bedwarsVisitsWithoutStart = bedwarsVisitsWithoutStart;
            this.shortVisitsDropped = shortVisitsDropped;
            this.startSource = startSource;
        }

        @Override
        public String toString() {
            return "RunStats{locations=" + locations.size() + ", lobbyOverridden=" + lobbyOverridden
                    + ", visitsWithoutLocation=" + visitsWithoutLocation + ", bedwarsVisitsWithoutStart="
                    + bedwarsVisitsWithoutStart + ", shortVisitsDropped=" + shortVisitsDropped
                    + ", startSource=" + startSource + "}";
        }
    }

    /**
     * One instruction for the wiring. Fields not used by a type keep their defaults (segments -1,
     * nulls, false).
     */
    public static final class Command {
        public final CommandType type;
        /** OPEN, JOIN, DROP: inclusive segment range. */
        public final int firstSegment;
        public final int lastSegment;
        /** OPEN: game start; MARKER: marker time; CLOSE: decision time. */
        public final long timeUs;
        /** OPEN. */
        public final StartSource startSource;
        /** OPEN. */
        public final GameInfo info;
        /** OPEN: markers accepted while the run was pending, in order. */
        public final List<MarkerEvent> markers;
        /** OPEN, MARKER: the game is key-marked (kept forever). */
        public final boolean keyMarked;
        /** MARKER. */
        public final Marker marker;
        /** CLOSE. */
        public final EndReason endedBy;
        public final Result result;
        public final boolean failed;
        public final RunStats stats;

        private Command(CommandType type, int firstSegment, int lastSegment, long timeUs, StartSource startSource,
                        GameInfo info, List<MarkerEvent> markers, boolean keyMarked, Marker marker,
                        EndReason endedBy, Result result, boolean failed, RunStats stats) {
            this.type = type;
            this.firstSegment = firstSegment;
            this.lastSegment = lastSegment;
            this.timeUs = timeUs;
            this.startSource = startSource;
            this.info = info;
            this.markers = markers;
            this.keyMarked = keyMarked;
            this.marker = marker;
            this.endedBy = endedBy;
            this.result = result;
            this.failed = failed;
            this.stats = stats;
        }

        static Command simple(CommandType type) {
            return new Command(type, -1, -1, 0L, null, null, Collections.<MarkerEvent>emptyList(), false, null,
                    null, null, false, null);
        }

        static Command range(CommandType type, int first, int last) {
            return new Command(type, first, last, 0L, null, null, Collections.<MarkerEvent>emptyList(), false, null,
                    null, null, false, null);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(type.name());
            if (firstSegment >= 0) sb.append(" [").append(firstSegment).append("..").append(lastSegment).append(']');
            switch (type) {
                case OPEN:
                    sb.append(" start=").append(timeUs).append(' ').append(startSource).append(' ').append(info)
                            .append(" markers=").append(markers).append(" keyMarked=").append(keyMarked);
                    break;
                case MARKER:
                    sb.append(' ').append(marker).append('@').append(timeUs).append(" keyMarked=").append(keyMarked);
                    break;
                case CLOSE:
                    sb.append(" at=").append(timeUs).append(' ').append(endedBy).append(' ').append(result)
                            .append(failed ? " failed" : "").append(' ').append(stats);
                    break;
                default:
                    break;
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ state

    private enum State {
        /** No location yet; the run grows with each boundary. */
        UNCLASSIFIED,
        /** A lobby verdict waiting for a Bed Wars sidebar override. */
        LOBBY_WAIT,
        /** A Bed Wars run waiting for the active-game edge. */
        BEDWARS_PENDING,
        /** A game-server visit waiting to reach the minimum length. */
        SERVER_PENDING,
        OPEN,
        /** The game (or pending run) failed; same-visit segments are dropped until it provably ends. */
        FAILED,
        /** Nothing of this visit is recorded any more (lobby, closed game, cap, skipped run). */
        DROPPED
    }

    private static final class Visit {
        State state = State.UNCLASSIFIED;
        /** Server name, only for same-server checks; never leaves this class. Null = unknown. */
        String server;
        int firstSeg;
        int lastSeg;
        long startUs;
        long lobbyAtUs;
        Classification classification = Classification.CLASSIFIED;
        String mode;
        String map;
        String serverType;
        long gameStartUs;
        StartSource source;
        Result result;
        long resultAtUs;
        boolean keyMarked;
        final List<MarkerEvent> pendingMarkers = new ArrayList<MarkerEvent>();

        Visit(int seg, long startUs) {
            this.firstSeg = seg;
            this.lastSeg = seg;
            this.startUs = startUs;
        }
    }

    /** Segments after a boundary on a classified visit, waiting for their location. */
    private static final class Candidate {
        final int firstSeg;
        int lastSeg;
        final long firstUs;
        long lastUs;

        Candidate(int seg, long nowUs) {
            this.firstSeg = seg;
            this.lastSeg = seg;
            this.firstUs = nowUs;
            this.lastUs = nowUs;
        }
    }

    private final List<Command> out = new ArrayList<Command>();

    private boolean connected;
    private boolean active;
    private boolean singlePlayer;
    private Visit visit;
    private Candidate cand;
    private boolean awaitingRearm;

    private boolean sidebarValid;
    private boolean bwQueue;
    private boolean bwActive;
    private boolean prevActive;
    private long activeEdgeUs = Long.MIN_VALUE;

    private boolean anyBoundary;
    private int lastBoundarySeg;
    private final int[] boundarySegs = new int[BOUNDARY_RING];
    private final long[] boundaryTimes = new long[BOUNDARY_RING];
    private int boundaryCount;

    private Location earlyLoc;
    private int earlySeg;
    private long earlyUs;

    private final ArrayDeque<LocationLogEntry> log = new ArrayDeque<LocationLogEntry>();
    private int lobbyOverridden;
    private int visitsWithoutLocation;
    private int bedwarsVisitsWithoutStart;
    private int shortVisitsDropped;
    private int skippedAfterFailure;

    // ------------------------------------------------------------------ events

    /**
     * A new connection (Hypixel or a single-player world). {@code recording} is whether the capture
     * handler was installed for it (Rewind and Record Games on at connect).
     */
    public List<Command> onConnect(long nowUs, boolean singlePlayerWorld, boolean recording) {
        if (connected && active) {
            advance(nowUs);
            endConnection(nowUs, EndReason.DISCONNECT);
        }
        resetConnection();
        connected = true;
        singlePlayer = singlePlayerWorld;
        active = recording;
        if (active && awaitingRearm) emitRearm();
        awaitingRearm = false;
        return flush();
    }

    /** The connection ended ({@code quit}: the game is quitting). */
    public List<Command> onDisconnect(long nowUs, boolean quit) {
        if (active) {
            advance(nowUs);
            endConnection(nowUs, quit ? EndReason.QUIT : EndReason.DISCONNECT);
        }
        resetConnection();
        connected = false;
        active = false;
        return flush();
    }

    /** Record Games (or the Rewind module) changed. Off applies now; on from the next connection. */
    public List<Command> onRecordSetting(long nowUs, boolean on) {
        if (on || !active) return flush();
        advance(nowUs);
        dropCandidate();
        if (visit != null) endVisit(nowUs, EndReason.SETTINGS, false);
        visit = null;
        out.add(Command.simple(CommandType.REMOVE_HANDLER));
        active = false;
        awaitingRearm = false;
        return flush();
    }

    /** The world boundary that starts segment {@code segment} was applied (WORLD_LOAD). */
    public List<Command> onBoundary(long nowUs, int segment) {
        if (!active) return flush();
        advance(nowUs);
        recordBoundary(segment, nowUs);
        sidebarValid = false;
        if (singlePlayer) {
            singlePlayerBoundary(nowUs, segment);
            return flush();
        }
        if (visit == null || (visit.state == State.DROPPED && visit.server == null)) {
            visit = new Visit(segment, nowUs);
        } else if (visit.state == State.UNCLASSIFIED) {
            visit.lastSeg = segment;
        } else if (cand == null) {
            cand = new Candidate(segment, nowUs);
        } else {
            cand.lastSeg = segment;
            cand.lastUs = nowUs;
        }
        if (earlyLoc != null && earlySeg <= segment) {
            Location l = earlyLoc;
            earlyLoc = null;
            applyLocation(nowUs, earlySeg, l, earlyUs);
        }
        advance(nowUs);
        return flush();
    }

    /** A {@code hyevent:location} frame arrived in segment {@code segment}. Null (malformed) is ignored. */
    public List<Command> onLocation(long nowUs, int segment, Location location) {
        if (!active || singlePlayer || location == null || location.serverName == null) return flush();
        advance(nowUs);
        if (!anyBoundary || segment > lastBoundarySeg) {
            earlyLoc = location;
            earlySeg = segment;
            earlyUs = nowUs;
            return flush();
        }
        applyLocation(nowUs, segment, location, nowUs);
        advance(nowUs);
        return flush();
    }

    /**
     * Client tick: timers plus the existing Bed Wars sidebar detection ({@code isInBedwarsQueue},
     * {@code isInActiveBedwarsGame}). Flags count only once a tick follows the latest boundary.
     */
    public List<Command> tick(long nowUs, boolean bedwarsQueue, boolean bedwarsActive) {
        if (!active) return flush();
        if (bedwarsActive && !prevActive) activeEdgeUs = nowUs;
        prevActive = bedwarsActive;
        bwQueue = bedwarsQueue;
        bwActive = bedwarsActive;
        sidebarValid = true;
        advance(nowUs);
        return flush();
    }

    /** A classified own game line (WIN/LOSS results, automatic markers). */
    public List<Command> onGameLine(long nowUs, SessionChatLine.Kind kind) {
        if (!active || kind == null) return flush();
        advance(nowUs);
        if ((kind == SessionChatLine.Kind.WIN || kind == SessionChatLine.Kind.LOSS) && visit != null) {
            Result r = kind == SessionChatLine.Kind.WIN ? Result.WIN : Result.LOSS;
            if (visit.state == State.OPEN && visit.result == null) {
                visit.result = r;
                visit.resultAtUs = nowUs;
            } else if (visit.state == State.FAILED) {
                emitRearm();                                    // the failed game provably ended
                visit.state = State.DROPPED;
            }
        }
        Marker m = MarkerRules.forLine(kind);
        if (m != null) acceptMarker(nowUs, m);
        advance(nowUs);
        return flush();
    }

    /** The Marker Key was pressed (rising edge, no screen open). FLASH follows only when accepted. */
    public List<Command> onMarkerKey(long nowUs) {
        if (!active) return flush();
        advance(nowUs);
        acceptMarker(nowUs, Marker.KEY);
        return flush();
    }

    /** A recorder failure: capture latch, writer error or client-thread exception. */
    public List<Command> onFailure(long nowUs) {
        if (!active) return flush();
        advance(nowUs);
        if (visit == null) {
            emitRearm();
            return flush();
        }
        switch (visit.state) {
            case OPEN:
                closeGame(nowUs, EndReason.FAILED, true);
                awaitingRearm = true;
                break;
            case UNCLASSIFIED:
            case BEDWARS_PENDING:
            case SERVER_PENDING:
                emitDrop(visit.firstSeg, visit.lastSeg);
                visit.pendingMarkers.clear();
                visit.keyMarked = false;
                visit.state = State.FAILED;
                awaitingRearm = true;
                break;
            case LOBBY_WAIT:
                emitDrop(visit.firstSeg, visit.lastSeg);       // a lobby is not a game: nothing to wait for
                visit.state = State.DROPPED;
                emitRearm();
                break;
            case DROPPED:
                emitRearm();
                break;
            case FAILED:
            default:
                break;
        }
        return flush();
    }

    /** Bytes the writer holds for the pending run; above {@link #PENDING_MAX_BYTES} it is dropped. */
    public List<Command> onPendingBytes(long nowUs, long bytes) {
        if (!active) return flush();
        advance(nowUs);
        if (visit != null && isPending(visit.state) && bytes > PENDING_MAX_BYTES) capDrop();
        return flush();
    }

    // ------------------------------------------------------------------ queries

    public boolean active() {
        return active;
    }

    public boolean gameOpen() {
        return active && visit != null && visit.state == State.OPEN;
    }

    /** Location events since the previous file, oldest first, at most {@link #LOCATION_LOG_CAP}. */
    public List<LocationLogEntry> locationLog() {
        return Collections.unmodifiableList(new ArrayList<LocationLogEntry>(log));
    }

    public int lobbyOverridden() {
        return lobbyOverridden;
    }

    public int visitsWithoutLocation() {
        return visitsWithoutLocation;
    }

    public int bedwarsVisitsWithoutStart() {
        return bedwarsVisitsWithoutStart;
    }

    public int shortVisitsDropped() {
        return shortVisitsDropped;
    }

    /** Runs skipped because their boundary passed before a failed game's rearm (cumulative, DiagLog). */
    public int skippedAfterFailure() {
        return skippedAfterFailure;
    }

    /** Stats code for a location {@code serverType}. */
    public static int serverTypeCode(String serverType) {
        if (serverType == null) return 0;
        String t = serverType.toUpperCase(Locale.ROOT);
        if (t.equals("BEDWARS")) return 1;
        if (t.equals("MAIN")) return 2;
        if (t.equals("TOURNAMENT")) return 3;
        if (t.equals("SKYBLOCK")) return 4;
        if (t.equals("HOUSING")) return 5;
        return 6;
    }

    /** Lobby only on a lobby signal; a bare {@code serverName} is unclassified; anything else a game server. */
    public static int verdictOf(Location loc) {
        int type = serverTypeCode(loc.serverType);
        if (type == 2 || type == 3 || loc.lobbyName != null) return VERDICT_LOBBY;
        if (loc.serverType == null && loc.mode == null && loc.map == null) return VERDICT_UNCLASSIFIED;
        return VERDICT_GAME_SERVER;
    }

    /** {@code serverType} BEDWARS or a {@code BEDWARS_*} mode. */
    public static boolean isBedwarsLocation(Location loc) {
        if (serverTypeCode(loc.serverType) == 1) return true;
        return loc.mode != null && loc.mode.toUpperCase(Locale.ROOT).startsWith("BEDWARS_");
    }

    // ------------------------------------------------------------------ internals

    private List<Command> flush() {
        if (out.isEmpty()) return Collections.emptyList();
        List<Command> c = new ArrayList<Command>(out);
        out.clear();
        return c;
    }

    private void resetConnection() {
        visit = null;
        cand = null;
        sidebarValid = false;
        bwQueue = false;
        bwActive = false;
        prevActive = false;
        activeEdgeUs = Long.MIN_VALUE;
        anyBoundary = false;
        boundaryCount = 0;
        earlyLoc = null;
    }

    private void singlePlayerBoundary(long nowUs, int segment) {
        if (visit == null) {
            visit = new Visit(segment, nowUs);
            visit.classification = Classification.SINGLEPLAYER;
            visit.mode = "SINGLEPLAYER";
            open(nowUs, StartSource.SINGLEPLAYER);
            return;
        }
        visit.lastSeg = segment;
        out.add(Command.range(visit.state == State.OPEN ? CommandType.JOIN : CommandType.DROP, segment, segment));
    }

    /** Time-driven transitions and sidebar checks; idempotent for an unchanged time and input. */
    private void advance(long nowUs) {
        if (!active || visit == null) return;
        if (visit.state == State.OPEN) {
            if (visit.result != null && nowUs - visit.resultAtUs >= RESULT_CLOSE_US) {
                closeGame(nowUs, EndReason.RESULT, false);
            } else if (nowUs - visit.gameStartUs >= GAME_CAP_US) {
                closeGame(nowUs, EndReason.CAP, false);
            }
        }
        if (cand != null && nowUs - cand.lastUs >= CONTINUE_WAIT_US) mergeCandidate();

        boolean sidebarBedwars = sidebarValid && (bwQueue || bwActive);
        switch (visit.state) {
            case UNCLASSIFIED:
                if (sidebarBedwars && nowUs - visit.startUs >= LOCATION_WAIT_US) {
                    visit.state = State.BEDWARS_PENDING;
                    visit.classification = Classification.SIDEBAR;
                }
                break;
            case LOBBY_WAIT:
                if (sidebarBedwars) {
                    lobbyOverridden++;
                    visit.state = State.BEDWARS_PENDING;
                    visit.classification = Classification.SIDEBAR;
                } else if (nowUs - visit.lobbyAtUs >= LOBBY_VERDICT_WAIT_US) {
                    emitDrop(visit.firstSeg, visit.lastSeg);
                    visit.state = State.DROPPED;
                }
                break;
            case SERVER_PENDING:
                if (sidebarBedwars) visit.state = State.BEDWARS_PENDING;
                break;
            default:
                break;
        }
        if (visit.state == State.BEDWARS_PENDING && sidebarValid && bwActive) {
            // The rising edge; if the game was already active when the visit began, its start.
            visit.gameStartUs = activeEdgeUs >= visit.startUs ? activeEdgeUs : visit.startUs;
            open(nowUs, StartSource.BEDWARS);
        } else if (visit.state == State.SERVER_PENDING) {
            long liveUntil = cand != null ? cand.firstUs : nowUs;   // a pending switch may have ended it
            if (liveUntil - visit.startUs >= MIN_VISIT_US) {
                visit.gameStartUs = visit.startUs;
                open(nowUs, StartSource.VISIT);
            }
        }
        if (isPending(visit.state) && nowUs - visit.startUs >= PENDING_MAX_US) capDrop();
    }

    private static boolean isPending(State s) {
        return s == State.UNCLASSIFIED || s == State.LOBBY_WAIT || s == State.BEDWARS_PENDING
                || s == State.SERVER_PENDING;
    }

    private void applyLocation(long nowUs, int segment, Location loc, long receivedUs) {
        int verdict = verdictOf(loc);
        logLocation(segment, receivedUs, loc, verdict);
        if (visit == null) return;
        if (cand != null && segment >= cand.firstSeg) {
            resolveCandidate(nowUs, loc, verdict);
            return;
        }
        if (segment < visit.firstSeg) return;                    // an earlier visit's location
        if (visit.state == State.UNCLASSIFIED) {
            classify(nowUs, loc, verdict);
            return;
        }
        // A late or repeated location for an already classified visit.
        if (visit.server == null) {
            if (verdict != VERDICT_LOBBY) {
                adopt(loc);
            } else if (visit.state == State.FAILED) {
                emitRearm();                                     // the failed run was a lobby
                visit.state = State.DROPPED;
                visit.server = loc.serverName;
            }
        }
    }

    private void resolveCandidate(long nowUs, Location loc, int verdict) {
        boolean same = visit.server != null ? visit.server.equals(loc.serverName) : verdict != VERDICT_LOBBY;
        if (same) {
            if (visit.server == null) adopt(loc);
            mergeCandidate();
            return;
        }
        Candidate c = cand;
        cand = null;
        if (visit.state == State.FAILED) {
            // The failed game provably ended. This run's boundary passed while the latch was set,
            // so it was not captured: skip it, and its same-server segments with it.
            emitRearm();
            emitDrop(c.firstSeg, c.lastSeg);
            if (verdict != VERDICT_LOBBY) skippedAfterFailure++;
            Visit skipped = new Visit(c.firstSeg, c.firstUs);
            skipped.lastSeg = c.lastSeg;
            skipped.server = loc.serverName;
            skipped.state = State.DROPPED;
            visit = skipped;
            return;
        }
        endVisit(nowUs, verdict == VERDICT_LOBBY ? EndReason.LOBBY : EndReason.SERVER, true);
        visit = new Visit(c.firstSeg, c.firstUs);
        visit.lastSeg = c.lastSeg;
        classify(nowUs, loc, verdict);
    }

    private void mergeCandidate() {
        Candidate c = cand;
        cand = null;
        switch (visit.state) {
            case OPEN:
                out.add(Command.range(CommandType.JOIN, c.firstSeg, c.lastSeg));
                break;
            case FAILED:
            case DROPPED:
                emitDrop(c.firstSeg, c.lastSeg);
                break;
            default:
                break;                                           // pending: stays pending with its visit
        }
        visit.lastSeg = c.lastSeg;
    }

    private void classify(long nowUs, Location loc, int verdict) {
        visit.server = loc.serverName;
        visit.mode = loc.mode;
        visit.map = loc.map;
        visit.serverType = loc.serverType;
        if (verdict == VERDICT_LOBBY) {
            visit.state = State.LOBBY_WAIT;
            visit.lobbyAtUs = nowUs;
            return;
        }
        visit.classification = verdict == VERDICT_UNCLASSIFIED ? Classification.UNCLASSIFIED : Classification.CLASSIFIED;
        visit.state = isBedwarsLocation(loc) ? State.BEDWARS_PENDING : State.SERVER_PENDING;
    }

    private void adopt(Location loc) {
        visit.server = loc.serverName;
        if (visit.mode == null) visit.mode = loc.mode;
        if (visit.map == null) visit.map = loc.map;
        if (visit.serverType == null) visit.serverType = loc.serverType;
    }

    /** The visit ends (switch, disconnect, quit, settings): close or drop it, counting natural ends. */
    private void endVisit(long nowUs, EndReason reason, boolean count) {
        switch (visit.state) {
            case UNCLASSIFIED:
                emitDrop(visit.firstSeg, visit.lastSeg);
                if (count) visitsWithoutLocation++;
                break;
            case LOBBY_WAIT:
                emitDrop(visit.firstSeg, visit.lastSeg);
                break;
            case BEDWARS_PENDING:
                emitDrop(visit.firstSeg, visit.lastSeg);
                if (count) bedwarsVisitsWithoutStart++;
                break;
            case SERVER_PENDING:
                emitDrop(visit.firstSeg, visit.lastSeg);
                if (count) shortVisitsDropped++;
                break;
            case OPEN:
                closeGame(nowUs, reason, false);
                break;
            default:
                break;
        }
    }

    private void endConnection(long nowUs, EndReason reason) {
        dropCandidate();
        if (visit != null) endVisit(nowUs, reason, true);
        visit = null;
    }

    private void dropCandidate() {
        if (cand == null) return;
        emitDrop(cand.firstSeg, cand.lastSeg);
        cand = null;
    }

    private void capDrop() {
        emitDrop(visit.firstSeg, visit.lastSeg);
        visit.pendingMarkers.clear();
        visit.keyMarked = false;
        visit.state = State.DROPPED;
    }

    private void open(long nowUs, StartSource source) {
        if (source == StartSource.SINGLEPLAYER) visit.gameStartUs = nowUs;
        visit.state = State.OPEN;
        visit.source = source;
        List<MarkerEvent> markers = visit.pendingMarkers.isEmpty()
                ? Collections.<MarkerEvent>emptyList()
                : Collections.unmodifiableList(new ArrayList<MarkerEvent>(visit.pendingMarkers));
        visit.pendingMarkers.clear();
        GameInfo info = new GameInfo(visit.mode, visit.map, visit.serverType, visit.classification, singlePlayer);
        out.add(new Command(CommandType.OPEN, visit.firstSeg, visit.lastSeg, visit.gameStartUs, source, info,
                markers, visit.keyMarked, null, null, null, false, null));
    }

    private void closeGame(long nowUs, EndReason reason, boolean failed) {
        RunStats stats = new RunStats(new ArrayList<LocationLogEntry>(log), lobbyOverridden, visitsWithoutLocation,
                bedwarsVisitsWithoutStart, shortVisitsDropped, visit.source);
        out.add(new Command(CommandType.CLOSE, -1, -1, nowUs, null, null, Collections.<MarkerEvent>emptyList(),
                visit.keyMarked, null, reason, visit.result != null ? visit.result : Result.UNKNOWN, failed, stats));
        log.clear();
        lobbyOverridden = 0;
        visitsWithoutLocation = 0;
        bedwarsVisitsWithoutStart = 0;
        shortVisitsDropped = 0;
        visit.state = failed ? State.FAILED : State.DROPPED;
    }

    private void acceptMarker(long nowUs, Marker m) {
        boolean open = visit != null && visit.state == State.OPEN;
        boolean pendingServer = visit != null
                && (visit.state == State.BEDWARS_PENDING || visit.state == State.SERVER_PENDING);
        if (!MarkerRules.accepts(open, pendingServer)) return;
        if (MarkerRules.keepsForever(m)) visit.keyMarked = true;
        if (pendingServer) visit.pendingMarkers.add(new MarkerEvent(m, nowUs));
        out.add(new Command(CommandType.MARKER, -1, -1, nowUs, null, null, Collections.<MarkerEvent>emptyList(),
                visit.keyMarked, m, null, null, false, null));
        if (m == Marker.KEY) out.add(Command.simple(CommandType.FLASH));
    }

    private void emitDrop(int first, int last) {
        out.add(Command.range(CommandType.DROP, first, last));
    }

    private void emitRearm() {
        out.add(Command.simple(CommandType.REARM));
        awaitingRearm = false;
    }

    private void recordBoundary(int segment, long nowUs) {
        int i = boundaryCount % BOUNDARY_RING;
        boundarySegs[i] = segment;
        boundaryTimes[i] = nowUs;
        boundaryCount++;
        anyBoundary = true;
        lastBoundarySeg = segment;
    }

    private long boundaryTime(int segment) {
        int n = Math.min(boundaryCount, BOUNDARY_RING);
        for (int k = 0; k < n; k++) {
            int i = (boundaryCount - 1 - k) % BOUNDARY_RING;
            if (boundarySegs[i] == segment) return boundaryTimes[i];
        }
        return Long.MIN_VALUE;
    }

    private void logLocation(int segment, long receivedUs, Location loc, int verdict) {
        long bt = boundaryTime(segment);
        long dt = bt == Long.MIN_VALUE ? UNKNOWN_DT : receivedUs - bt;
        log.addLast(new LocationLogEntry(segment, dt, loc.serverType != null, serverTypeCode(loc.serverType),
                loc.lobbyName != null, loc.mode != null, loc.map != null, verdict));
        while (log.size() > LOCATION_LOG_CAP) log.removeFirst();
    }
}
