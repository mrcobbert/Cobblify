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
 *   <li>Hypixel's replay viewer ({@code serverType} REPLAY, stats code 7) gets a lobby verdict the
 *       sidebar never overrides: the replayed game's Bed Wars sidebar is not a game (R2, Q5). A REPLAY
 *       location that comes after the sidebar already opened the visit discards that game
 *       ({@link EndReason#REPLAY}; code review 1, I4).</li>
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
 * the visit if its location names the same server, and a different server ends the visit (closing an
 * open game) and starts a new run. Only while a game is open (or a failed game's segments are being
 * dropped) does a candidate with no location within {@link #CONTINUE_WAIT_US} continue the visit by
 * itself. Otherwise it stays pending until its location arrives, the visit ends, the Bed Wars sidebar
 * of its own world decides it (after a dropped visit), or the next boundary comes 5 s later with still
 * no location (then it was a continuation).
 *
 * <p><b>Closing (code review 2, I1; code review 3, I1).</b> One routine emits every CLOSE, and no CLOSE
 * leaves a segment of the game behind. Invariant: a CLOSE for an open game is never emitted while (a) a
 * candidate is undecided, or (b) the capture handler has started a segment the client has not processed
 * as a boundary yet ({@link #onCaptureProgress}; {@link #onBoundary} counts its own segment before
 * anything else). There is no time limit on (b): the client eventually processes the boundary, or the
 * connection ends. The result close (15 s after WIN/LOSS), the 2 h cap and a failure close are only
 * <i>due</i> until then; the candidate is decided by the plan's rules: a same-server location, or 5 s
 * without one counted from the client's WORLD_LOAD, JOINs it (then the due close follows), and a
 * different-server or lobby location closes the game without it (it starts the next run). A disconnect,
 * quit or Record Games off cannot learn more, so it JOINs the candidate and the unloaded segments before
 * its CLOSE. The CLOSE's time is the due time when nothing was JOINed after it, otherwise the decision
 * time (the JOINed segment runs past the due time). So a WIN at 100 s, a same-server S07 that Netty reads
 * at 114 s and the client loads at 130 s, and its location at 130.1 s give JOIN then CLOSE (result) at
 * 130.1 s; another server's location instead gives CLOSE (server) at 115 s and the segment starts the next
 * run.
 *
 * <p><b>Markers (code review 3, I3).</b> A marker's TRACK record is written into the client's current
 * segment, so it belongs to the game that ends up owning that segment. While a candidate is undecided an
 * accepted marker is held by the candidate (MARKER with game id 0, so the writer applies it to no game, and
 * FLASH for the key); JOIN applies it to the open game, a new game-server run takes it as a pending marker
 * (applied at OPEN), and a lobby or dropped run discards it.
 *
 * <p><b>Pending cap (code review 3, I2).</b> The writer enforces the 32 MiB pending cap itself and drops
 * every pending segment when it is reached ({@link #onPendingOverflow}); a pending run holding one of those
 * segments is then dropped here.
 *
 * <p><b>Failure ownership (code review 2, I2/I3).</b> A failure belongs to a segment: the capture
 * handler's latch to the segment it latched in ({@link #onCaptureFailure}, applied when the client has
 * loaded that segment), a client-thread failure to the current TRACK segment, a writer failure to the
 * game whose file failed ({@link #onWriterFailure}, by game id). The visit (or undecided candidate)
 * that owns the segment fails: an open game closes as failed once nothing is undecided, a pending run
 * is dropped. Its later same-visit segments are dropped. Only a capture latch needs a {@code REARM},
 * and only once the failed visit provably ended: its result, a lobby or other-server run, a lobby
 * verdict, or a new connection. A run whose first boundary passed while capture was latched holds no
 * packets and is skipped; other failures never skip the next run.
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
    /** {@link #serverTypeCode} of Hypixel's replay viewer ({@code serverType} REPLAY). */
    public static final int SERVER_TYPE_REPLAY = 7;

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
        /** The open game's mode, map or server type changed after OPEN: its metadata is updated (INFO records). */
        INFO,
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
        CAP("cap"),
        /**
         * Hypixel's replay viewer, learned from a location that came after the sidebar had opened the game (code review
         * 1, I4): the game is discarded, its file deleted, never kept.
         */
        REPLAY("replay");

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

    /** Gallery metadata known at OPEN, or at a later INFO update. Never holds the server name or address. */
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
        /** 0 absent, 1 BEDWARS, 2 MAIN, 3 TOURNAMENT, 4 SKYBLOCK, 5 HOUSING, 6 other, 7 REPLAY (since R2). */
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
        /** OPEN, INFO: the game's metadata as now known. */
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
        /**
         * OPEN, INFO, CLOSE: the game's id, unique per rules instance (code review 2, I3). The writer reports a file
         * failure with it, so a failure reaches only the game whose file failed. MARKER: the open game it applies to, or
         * 0 when it applies to no game yet (a pending run's, or held by an undecided candidate; code review 3, I3).
         */
        public final long gameId;

        private Command(CommandType type, int firstSegment, int lastSegment, long timeUs, StartSource startSource,
                        GameInfo info, List<MarkerEvent> markers, boolean keyMarked, Marker marker,
                        EndReason endedBy, Result result, boolean failed, RunStats stats, long gameId) {
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
            this.gameId = gameId;
        }

        static Command simple(CommandType type) {
            return new Command(type, -1, -1, 0L, null, null, Collections.<MarkerEvent>emptyList(), false, null,
                    null, null, false, null, 0L);
        }

        static Command range(CommandType type, int first, int last) {
            return new Command(type, first, last, 0L, null, null, Collections.<MarkerEvent>emptyList(), false, null,
                    null, null, false, null, 0L);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(type.name());
            if (firstSegment >= 0) sb.append(" [").append(firstSegment).append("..").append(lastSegment).append(']');
            switch (type) {
                case OPEN:
                    sb.append(" game=").append(gameId).append(" start=").append(timeUs).append(' ').append(startSource)
                            .append(' ').append(info).append(" markers=").append(markers).append(" keyMarked=")
                            .append(keyMarked);
                    break;
                case INFO:
                    sb.append(" game=").append(gameId).append(' ').append(info);
                    break;
                case MARKER:
                    sb.append(' ').append(marker).append('@').append(timeUs).append(" keyMarked=").append(keyMarked);
                    break;
                case CLOSE:
                    sb.append(" game=").append(gameId).append(" at=").append(timeUs).append(' ').append(endedBy)
                            .append(' ').append(result)
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
        /** Mode, map or server type changed since the game's OPEN or last INFO. */
        boolean infoChanged;
        long gameStartUs;
        /** The OPEN's game id (0 before OPEN). */
        long gameId;
        StartSource source;
        Result result;
        long resultAtUs;
        boolean keyMarked;
        /**
         * A failure belongs to this visit but is not applied yet: an open game closes as failed once none of its
         * segments is undecided; a lobby verdict decides first whether there is a game to fail.
         */
        boolean failPending;
        /** When {@link #failPending} was set: a failure close is due from then. */
        long failAtUs;
        /** When a segment was last JOINed to this open game (code review 3, I1: the CLOSE's time). */
        long lastJoinUs = Long.MIN_VALUE;
        /** A capture failure (the latch) belongs to this visit: its end rearms capture. */
        boolean captureLatched;
        /** Its first boundary passed while capture was latched: nothing of it is ever recorded. */
        boolean skip;
        /** Its location gave a lobby verdict (cleared when the Bed Wars sidebar overrides it). */
        boolean lobby;
        /** Hypixel's replay viewer ({@code serverType} REPLAY): its lobby verdict is never overridden (R2, Q5). */
        boolean replay;
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
        /** A failure in one of these segments, applied to whichever visit they turn out to be. */
        boolean failed;
        boolean captureLatched;
        /** Markers accepted while undecided: their TRACK records are in these segments (code review 3, I3). */
        final List<MarkerEvent> held = new ArrayList<MarkerEvent>();
        boolean heldKey;
        /** The writer dropped (some of) these segments at the pending cap (code review 3, I2). */
        boolean overflowed;

        Candidate(int seg, long nowUs) {
            this.firstSeg = seg;
            this.lastSeg = seg;
            this.firstUs = nowUs;
            this.lastUs = nowUs;
        }
    }

    private static final int NONE = Integer.MAX_VALUE;

    private final List<Command> out = new ArrayList<Command>();

    private boolean connected;
    private boolean active;
    private boolean singlePlayer;
    private Visit visit;
    private Candidate cand;
    private boolean awaitingRearm;
    private long nextGameId = 1;

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

    /** The capture handler's latest segment (it runs ahead of the client thread's WORLD_LOADs). */
    private int captureSeg;
    /** The writer dropped every pending segment up to this one at the pending cap (0: none; code review 3, I2). */
    private int overflowThrough;
    /** The earliest failure in a segment the client has not loaded yet ({@link #NONE}: none). */
    private int pendingFailSeg = NONE;
    private boolean pendingFailLatched;
    /** Segments {@code [uncapturedFrom, resumeAt)} started while capture was latched: they hold no packets. */
    private int uncapturedFrom = NONE;
    private int resumeAt = NONE;

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
            endConnection(nowUs, EndReason.DISCONNECT, true);
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
            endConnection(nowUs, quit ? EndReason.QUIT : EndReason.DISCONNECT, true);
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
        endConnection(nowUs, EndReason.SETTINGS, false);
        out.add(Command.simple(CommandType.REMOVE_HANDLER));
        active = false;
        awaitingRearm = false;
        return flush();
    }

    /**
     * How far the capture handler has got: {@code segment} is its current segment (it may be ahead of the
     * boundaries the client thread applied). Gives no commands; a due close waits, without a time limit, for
     * segments the capture handler started and the client has not processed yet (code review 3, I1).
     */
    public void onCaptureProgress(long nowUs, int segment) {
        if (!active || segment <= captureSeg) return;
        captureSeg = segment;
    }

    /** Capture copies again from segment {@code segment} on (after a REARM). */
    public void onCaptureResumed(int segment) {
        if (!active) return;
        if (uncapturedFrom != NONE && segment >= uncapturedFrom) resumeAt = Math.min(resumeAt, segment);
    }

    /** The world boundary that starts segment {@code segment} was applied (WORLD_LOAD). */
    public List<Command> onBoundary(long nowUs, int segment) {
        if (!active) return flush();
        // Code review 3, I1: this segment exists (the capture handler started it) before any advance may close.
        if (captureSeg < segment) captureSeg = segment;
        advance(nowUs);
        recordBoundary(segment, nowUs);
        sidebarValid = false;
        if (singlePlayer) {
            singlePlayerBoundary(nowUs, segment);
            applyPendingFailure(nowUs, segment);
            advance(nowUs);
            return flush();
        }
        if (visit == null || (visit.state == State.DROPPED && visit.server == null)) {
            if (cand != null) mergeCandidate(nowUs);           // no server to compare: it was that visit's
            visit = new Visit(segment, nowUs);
            visit.skip = isUncaptured(segment);
        } else if (visit.state == State.UNCLASSIFIED) {
            visit.lastSeg = segment;
        } else if (cand == null) {
            cand = new Candidate(segment, nowUs);
        } else if (presumedSameServer(nowUs)) {
            // The previous run went 5 s without a location before this boundary: a same-server
            // continuation (open and failed games merged it in advance() already).
            mergeCandidate(nowUs);
            cand = new Candidate(segment, nowUs);
        } else {
            cand.lastSeg = segment;
            cand.lastUs = nowUs;
        }
        applyPendingFailure(nowUs, segment);
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
                if (visit.captureLatched) emitRearm();          // the failed game provably ended
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

    /**
     * The capture handler latched a failure while it was in segment {@code segment} (code review 2, I2). The failure
     * belongs to the visit that owns that segment, decided when the client has loaded it; capture is rearmed only
     * once that visit provably ended. Segments started while latched hold no packets: a run that begins in one is
     * skipped.
     */
    public List<Command> onCaptureFailure(long nowUs, int segment) {
        return failure(nowUs, segment, true);
    }

    /**
     * A client-thread failure (the TRACK producer, or an exception) in segment {@code segment}, the current TRACK
     * segment: the owning game fails, capture itself is untouched.
     */
    public List<Command> onClientFailure(long nowUs, int segment) {
        return failure(nowUs, segment, false);
    }

    /**
     * The writer failed the file of game {@code gameId} (the id of its OPEN; code review 2, I3). Only that game, if
     * it is still open, closes as failed; a failure of an earlier, already closed game changes nothing here.
     */
    public List<Command> onWriterFailure(long nowUs, long gameId) {
        if (!active) return flush();
        advance(nowUs);
        if (visit != null && visit.state == State.OPEN && visit.gameId == gameId) {
            failVisit(nowUs, false);
            advance(nowUs);
        }
        return flush();
    }

    /** Bytes the writer holds for the pending run; above {@link #PENDING_MAX_BYTES} it is dropped. */
    public List<Command> onPendingBytes(long nowUs, long bytes) {
        if (!active) return flush();
        advance(nowUs);
        if (visit != null && bytes > PENDING_MAX_BYTES) {
            if (isPending(visit.state)) {
                capDrop();
            } else if (cand != null && visit.state == State.DROPPED) {
                mergeCandidate(nowUs);                           // an unlocated run after a dropped visit
            }
        }
        return flush();
    }

    /**
     * The writer reached the pending cap and dropped every pending segment up to {@code throughSegment} (code review 3,
     * I2). A pending run holding one of them is dropped; a candidate holding one is marked, so whichever run it turns
     * out to be is dropped too (an open game it JOINs is failed by the writer).
     */
    public List<Command> onPendingOverflow(long nowUs, int throughSegment) {
        if (!active) return flush();
        if (throughSegment > overflowThrough) overflowThrough = throughSegment;
        advance(nowUs);
        return flush();
    }

    // ------------------------------------------------------------------ queries

    public boolean active() {
        return active;
    }

    public boolean gameOpen() {
        return active && visit != null && visit.state == State.OPEN;
    }

    /**
     * What the current connection is right now, for Rewind's "Watch Last Game" pause-menu row (R2, TASK A4): the row
     * shows with no game open in a lobby run, or with a game open that has its WIN/LOSS. Everything is false when the
     * rules are inactive (no recording connection, after a disconnect, Record Games off).
     */
    public static final class EntryState {
        /** A game is being recorded on this connection. */
        public final boolean gameOpen;
        /** {@link #result} is WIN or LOSS. */
        public final boolean resultKnown;
        /** The open game's result; {@link Result#UNKNOWN} when none is known or no game is open. */
        public final Result result;
        /**
         * The current run is a lobby: a lobby verdict (waiting for, or past, its sidebar check; Hypixel's replay viewer
         * included), with no new world undecided. A lobby the Bed Wars queue overrode is a pending game run instead.
         */
        public final boolean lobby;
        /** No game is open, and the current run (or an undecided new world) may still become one: a waiting room. */
        public final boolean pendingGameRun;

        EntryState(boolean gameOpen, Result result, boolean lobby, boolean pendingGameRun) {
            this.gameOpen = gameOpen;
            this.result = result;
            this.resultKnown = result != Result.UNKNOWN;
            this.lobby = lobby;
            this.pendingGameRun = pendingGameRun;
        }

        @Override
        public String toString() {
            return "EntryState{gameOpen=" + gameOpen + ", result=" + result + ", lobby=" + lobby
                    + ", pendingGameRun=" + pendingGameRun + "}";
        }
    }

    private static final EntryState NO_ENTRY_STATE = new EntryState(false, Result.UNKNOWN, false, false);

    /** The watch-entry state now (client thread, like every other call). */
    public EntryState entryState() {
        if (!active || visit == null) return NO_ENTRY_STATE;
        boolean open = visit.state == State.OPEN;
        Result result = open && visit.result != null ? visit.result : Result.UNKNOWN;
        boolean lobby = !open && cand == null && visit.lobby
                && (visit.state == State.LOBBY_WAIT || visit.state == State.DROPPED);
        boolean pending = !open && (cand != null || visit.state == State.UNCLASSIFIED
                || visit.state == State.BEDWARS_PENDING || visit.state == State.SERVER_PENDING);
        return new EntryState(open, result, lobby, pending);
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
        if (t.equals("REPLAY")) return SERVER_TYPE_REPLAY;
        return 6;
    }

    /**
     * Lobby only on a lobby signal (Hypixel's replay viewer counts as one: it is never a game); a bare
     * {@code serverName} is unclassified; anything else a game server.
     */
    public static int verdictOf(Location loc) {
        int type = serverTypeCode(loc.serverType);
        if (type == 2 || type == 3 || type == SERVER_TYPE_REPLAY || loc.lobbyName != null) return VERDICT_LOBBY;
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
        lastBoundarySeg = 0;
        boundaryCount = 0;
        earlyLoc = null;
        captureSeg = 0;
        overflowThrough = 0;
        pendingFailSeg = NONE;
        pendingFailLatched = false;
        uncapturedFrom = NONE;
        resumeAt = NONE;
    }

    private void singlePlayerBoundary(long nowUs, int segment) {
        if (visit == null) {
            visit = new Visit(segment, nowUs);
            visit.classification = Classification.SINGLEPLAYER;
            visit.mode = "SINGLEPLAYER";
            visit.skip = isUncaptured(segment);
            open(nowUs, StartSource.SINGLEPLAYER);
            return;
        }
        visit.lastSeg = segment;
        if (visit.state == State.OPEN) visit.lastJoinUs = nowUs;
        out.add(Command.range(visit.state == State.OPEN ? CommandType.JOIN : CommandType.DROP, segment, segment));
    }

    // ---- failures (code review 2, I2/I3)

    private List<Command> failure(long nowUs, int segment, boolean latched) {
        if (!active) return flush();
        advance(nowUs);
        if (latched) {
            uncapturedFrom = segment + 1;                         // the latch outlives boundaries until a rearm
            resumeAt = NONE;
        }
        if (segment > lastBoundarySeg) {
            // The client has not loaded this segment yet: its owner is decided at its boundary.
            pendingFailSeg = Math.min(pendingFailSeg, segment);
            pendingFailLatched |= latched;
            return flush();
        }
        applyFailure(nowUs, segment, latched);
        advance(nowUs);
        return flush();
    }

    private void applyPendingFailure(long nowUs, int segment) {
        if (pendingFailSeg == NONE || pendingFailSeg > segment) return;
        int f = pendingFailSeg;
        boolean latched = pendingFailLatched;
        pendingFailSeg = NONE;
        pendingFailLatched = false;
        applyFailure(nowUs, f, latched);
    }

    /** Attaches a failure in loaded segment {@code segment} to its owner: the undecided candidate, or the visit. */
    private void applyFailure(long nowUs, int segment, boolean latched) {
        if (visit == null) {
            // Before the first boundary (segment 0): no run owns it; copying restarts at the first boundary.
            if (latched) emitRearm();
            return;
        }
        if (cand != null && segment >= cand.firstSeg) {
            cand.failed = true;                                    // decided with the candidate
            cand.captureLatched |= latched;
            return;
        }
        failVisit(nowUs, latched);
    }

    /** The current visit owns a failure. */
    private void failVisit(long nowUs, boolean latched) {
        Visit v = visit;
        switch (v.state) {
            case OPEN:
            case LOBBY_WAIT:
                if (!v.failPending) v.failAtUs = nowUs;
                v.failPending = true;                              // closed (or decided) by advance()
                v.captureLatched |= latched;
                break;
            case UNCLASSIFIED:
            case BEDWARS_PENDING:
            case SERVER_PENDING:
                failPendingRun(v, latched);
                break;
            case FAILED:
                v.captureLatched |= latched;
                if (latched) awaitingRearm = true;
                break;
            case DROPPED:
            default:
                if (latched) emitRearm();                          // its owner already ended
                break;
        }
    }

    private void failPendingRun(Visit v, boolean latched) {
        emitDrop(v.firstSeg, v.lastSeg);
        v.pendingMarkers.clear();
        v.keyMarked = false;
        v.state = State.FAILED;
        v.failPending = false;
        v.captureLatched |= latched;
        if (v.captureLatched) awaitingRearm = true;
    }

    /** Whether segment {@code segment} started while capture was latched (no packets until a rearm resumed it). */
    private boolean isUncaptured(int segment) {
        return uncapturedFrom != NONE && segment >= uncapturedFrom && segment < resumeAt;
    }

    // ---- time

    /**
     * Segments of this connection that may still belong to the open game and are not decided yet: a candidate, or
     * boundaries the capture handler started that the client has not processed (code review 3, I1: no time limit).
     */
    private boolean undecided() {
        return cand != null || captureSeg > lastBoundarySeg;
    }

    /** When the open game's first close reason fell due: a failure, 15 s after its result, or the 2 h cap. */
    private static long closeDueAt(Visit v) {
        long due = v.gameStartUs + GAME_CAP_US;
        if (v.result != null) due = Math.min(due, v.resultAtUs + RESULT_CLOSE_US);
        if (v.failPending) due = Math.min(due, v.failAtUs);
        return due;
    }

    /**
     * Code review 3, I2: a pending run holding a segment the writer dropped at the pending cap is dropped; a candidate
     * holding one is marked (decided as usual; the run it becomes is dropped, or the open game it JOINs is failed by the
     * writer).
     */
    private void dropOverflowed() {
        if (overflowThrough == 0 || visit == null) return;
        if (cand != null && cand.firstSeg <= overflowThrough) cand.overflowed = true;
        if (isPending(visit.state) && visit.firstSeg <= overflowThrough) capDrop();
    }

    /** Time-driven transitions and sidebar checks; idempotent for an unchanged time and input. */
    private void advance(long nowUs) {
        if (!active || visit == null) return;
        dropOverflowed();
        if (cand != null) {
            if (visit.state == State.OPEN || visit.state == State.FAILED) {
                // PLAN "Continue" (while a game is open) and failure ownership: no location within 5 s.
                if (presumedSameServer(nowUs)) mergeCandidate(nowUs);
            } else if (visit.state == State.DROPPED) {
                // Nothing to continue: the run stays pending until its location (or visit end), unless
                // the Bed Wars sidebar of its own world decides it first (PLAN "No location within 10 s").
                if (candidateSidebarBedwars(nowUs)) {
                    startSidebarRunFromCandidate(nowUs);
                } else if (nowUs - cand.firstUs >= PENDING_MAX_US) {
                    mergeCandidate(nowUs);
                }
            }
        }
        // Code review 3, I1: a due close (failure, result, cap) waits while any segment that may be this game's is
        // undecided; the single close routine emits it once each has been decided.
        if (visit.state == State.OPEN && nowUs >= closeDueAt(visit) && !undecided()) {
            if (visit.failPending) {
                closeGame(nowUs, EndReason.FAILED, true, false);
                if (visit.result != null) {
                    if (visit.captureLatched) emitRearm();           // its result already came: the owner ended
                    visit.state = State.DROPPED;
                }
            } else if (visit.result != null && nowUs - visit.resultAtUs >= RESULT_CLOSE_US) {
                closeGame(nowUs, EndReason.RESULT, false, false);
            } else {
                closeGame(nowUs, EndReason.CAP, false, false);
            }
        }

        // While a candidate is undecided the sidebar shows its world, not this visit's.
        boolean sidebarBedwars = cand == null && sidebarValid && (bwQueue || bwActive);
        switch (visit.state) {
            case UNCLASSIFIED:
                if (sidebarBedwars && nowUs - visit.startUs >= LOCATION_WAIT_US) {
                    visit.state = State.BEDWARS_PENDING;
                    visit.classification = Classification.SIDEBAR;
                }
                break;
            case LOBBY_WAIT:
                if (sidebarBedwars && !visit.replay) {   // a replayed game's sidebar is not a game (R2, Q5)
                    lobbyOverridden++;
                    visit.lobby = false;
                    visit.state = State.BEDWARS_PENDING;
                    visit.classification = Classification.SIDEBAR;
                    if (visit.failPending) failPendingRun(visit, visit.captureLatched);
                } else if (nowUs - visit.lobbyAtUs >= LOBBY_VERDICT_WAIT_US) {
                    emitDrop(visit.firstSeg, visit.lastSeg);
                    visit.pendingMarkers.clear();                    // a lobby keeps no marker (code review 3, I3)
                    visit.keyMarked = false;
                    visit.state = State.DROPPED;
                    if (visit.failPending && visit.captureLatched) emitRearm(); // a lobby: no game to wait for
                    visit.failPending = false;
                }
                break;
            case SERVER_PENDING:
                if (sidebarBedwars) visit.state = State.BEDWARS_PENDING;
                break;
            default:
                break;
        }
        dropOverflowed();                                        // a run just made from a candidate (I2)
        // A candidate without a location for 5 s is presumed to be this server: the visit is still current,
        // and when it opens the candidate is part of it (for a pending visit the merge only extends it).
        boolean visitIsCurrent = cand == null || presumedSameServer(nowUs);
        if (visit.state == State.BEDWARS_PENDING && sidebarValid && bwActive && visitIsCurrent) {
            if (cand != null) mergeCandidate(nowUs);
            if (visit.state == State.BEDWARS_PENDING) {          // a failure in the merged candidate fails it
                // The rising edge; if the game was already active when the visit began, its start.
                visit.gameStartUs = activeEdgeUs >= visit.startUs ? activeEdgeUs : visit.startUs;
                open(nowUs, StartSource.BEDWARS);
            }
        } else if (visit.state == State.SERVER_PENDING) {
            long liveUntil = visitIsCurrent ? nowUs : cand.firstUs; // a pending switch may have ended it
            if (liveUntil - visit.startUs >= MIN_VISIT_US) {
                if (cand != null) mergeCandidate(nowUs);
                if (visit.state == State.SERVER_PENDING) {
                    visit.gameStartUs = visit.startUs;
                    open(nowUs, StartSource.VISIT);
                }
            }
        }
        if (isPending(visit.state) && nowUs - visit.startUs >= PENDING_MAX_US) capDrop();
    }

    private static boolean isPending(State s) {
        return s == State.UNCLASSIFIED || s == State.LOBBY_WAIT || s == State.BEDWARS_PENDING
                || s == State.SERVER_PENDING;
    }

    // ---- locations

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
        if (lateReplay(nowUs, loc)) return;
        // A late or repeated location for an already classified visit.
        if (visit.server == null) {
            if (verdict != VERDICT_LOBBY) {
                updateInfo(loc);
                emitInfoIfChanged();
            } else if (visit.state == State.FAILED) {
                if (visit.captureLatched) emitRearm();           // the failed run was a lobby
                visit.state = State.DROPPED;
                visit.lobby = true;
                visit.server = loc.serverName;
            }
        } else if (visit.server.equals(loc.serverName) && verdict != VERDICT_LOBBY) {
            updateInfo(loc);                                     // latest wins (code review 2, I4)
            emitInfoIfChanged();
        }
    }

    /**
     * Code review 1, I4: a REPLAY location for this visit after the Bed Wars sidebar had already classified it (no
     * server yet) or for its own server. Hypixel's replay viewer is never recorded: an open game is discarded (a
     * {@link EndReason#REPLAY} CLOSE, whose file the writer deletes), a pending run becomes the lobby verdict it would
     * have had with an early location. Its undecided candidate is left to be decided on its own location.
     *
     * @return true when the location was such a REPLAY one and has been applied
     */
    private boolean lateReplay(long nowUs, Location loc) {
        if (serverTypeCode(loc.serverType) != SERVER_TYPE_REPLAY) return false;
        if (visit.server != null && !visit.server.equals(loc.serverName)) return false;
        switch (visit.state) {
            case OPEN: {
                boolean rearm = visit.failPending && visit.captureLatched; // its failure's owner was no game
                visit.failPending = false;
                closeGame(nowUs, EndReason.REPLAY, false, false);
                if (rearm) emitRearm();
                markReplay(loc);
                return true;
            }
            case BEDWARS_PENDING:
            case SERVER_PENDING:
                classify(nowUs, loc, VERDICT_LOBBY);
                return true;
            case LOBBY_WAIT:
                visit.replay = true;
                return true;
            default:
                return false;                                    // failed or dropped: the branches below apply
        }
    }

    /** The visit is the replay viewer's from now on: a lobby, never a game, for any later location or sidebar. */
    private void markReplay(Location loc) {
        visit.lobby = true;
        visit.replay = true;
        if (visit.server == null) visit.server = loc.serverName;
        visit.serverType = loc.serverType;
    }

    private void resolveCandidate(long nowUs, Location loc, int verdict) {
        boolean same = visit.server != null ? visit.server.equals(loc.serverName) : verdict != VERDICT_LOBBY;
        if (same) {
            updateInfo(loc);
            mergeCandidate(nowUs);
            emitInfoIfChanged();
            return;
        }
        // Evidence that the candidate is not this visit's: the visit ends without it.
        Candidate c = cand;
        cand = null;
        Visit old = visit;
        endVisit(nowUs, verdict == VERDICT_LOBBY ? EndReason.LOBBY : EndReason.SERVER, true, false);
        if (old.captureLatched && (old.state == State.FAILED || old.failPending)) {
            emitRearm();                                         // the failed game provably ended
        }
        if (isUncaptured(c.firstSeg)) {
            // This run's boundary passed while capture was latched: it holds no packets. Skip it, and its
            // same-server segments with it.
            emitDrop(c.firstSeg, c.lastSeg);
            if (verdict != VERDICT_LOBBY) skippedAfterFailure++;
            Visit skipped = new Visit(c.firstSeg, c.firstUs);
            skipped.lastSeg = c.lastSeg;
            skipped.server = loc.serverName;
            skipped.lobby = verdict == VERDICT_LOBBY;
            skipped.state = State.DROPPED;
            visit = skipped;
        } else {
            visit = new Visit(c.firstSeg, c.firstUs);
            visit.lastSeg = c.lastSeg;
            classify(nowUs, loc, verdict);
            takeHeldMarkers(c);                                  // the next run's (code review 3, I3)
        }
        if (c.failed) failVisit(nowUs, c.captureLatched);
        dropOverflowed();
    }

    /** The candidate is this visit's: JOIN (open), DROP (failed, dropped) or extend (pending), then its failure. */
    private void mergeCandidate(long nowUs) {
        Candidate c = cand;
        cand = null;
        switch (visit.state) {
            case OPEN:
                out.add(Command.range(CommandType.JOIN, c.firstSeg, c.lastSeg));
                visit.lastJoinUs = nowUs;
                break;
            case FAILED:
            case DROPPED:
                emitDrop(c.firstSeg, c.lastSeg);
                break;
            default:
                break;                                           // pending: stays pending with its visit
        }
        visit.lastSeg = c.lastSeg;
        takeHeldMarkers(c);                                      // the open game's, or the pending run's (I3)
        if (c.failed) failVisit(nowUs, c.captureLatched);
        dropOverflowed();
    }

    /**
     * Code review 3, I3: markers held by a decided candidate go to the run that now owns its segments: the open game
     * (key-marked from now on), a pending game-server or lobby-verdict run (applied at OPEN, dropped with the run), or
     * nowhere. Markers whose segments the writer dropped at the pending cap are discarded with them.
     */
    private void takeHeldMarkers(Candidate c) {
        if (c.held.isEmpty() || c.overflowed) return;
        switch (visit.state) {
            case OPEN:
                visit.keyMarked |= c.heldKey;
                break;
            case BEDWARS_PENDING:
            case SERVER_PENDING:
            case LOBBY_WAIT:
                if (visit.skip) break;
                visit.pendingMarkers.addAll(c.held);
                visit.keyMarked |= c.heldKey;
                break;
            default:
                break;                                           // failed, dropped: nothing is kept
        }
    }

    private void classify(long nowUs, Location loc, int verdict) {
        visit.server = loc.serverName;
        visit.mode = loc.mode;
        visit.map = loc.map;
        visit.serverType = loc.serverType;
        if (verdict == VERDICT_LOBBY) {
            visit.state = State.LOBBY_WAIT;
            visit.lobbyAtUs = nowUs;
            visit.lobby = true;
            visit.replay = serverTypeCode(loc.serverType) == SERVER_TYPE_REPLAY;
            return;
        }
        if (visit.skip) {
            skipRun();
            return;
        }
        visit.classification = verdict == VERDICT_UNCLASSIFIED ? Classification.UNCLASSIFIED : Classification.CLASSIFIED;
        visit.state = isBedwarsLocation(loc) ? State.BEDWARS_PENDING : State.SERVER_PENDING;
    }

    /** A game run whose first boundary passed while capture was latched: dropped whole, counted. */
    private void skipRun() {
        emitDrop(visit.firstSeg, visit.lastSeg);
        visit.pendingMarkers.clear();
        visit.keyMarked = false;
        visit.state = State.DROPPED;
        skippedAfterFailure++;
    }

    /** A location for this visit: present fields replace what is known (the latest wins), absent ones never erase. */
    private void updateInfo(Location loc) {
        if (visit.server == null) visit.server = loc.serverName;
        if (loc.mode != null && !loc.mode.equals(visit.mode)) {
            visit.mode = loc.mode;
            visit.infoChanged = true;
        }
        if (loc.map != null && !loc.map.equals(visit.map)) {
            visit.map = loc.map;
            visit.infoChanged = true;
        }
        if (loc.serverType != null && !loc.serverType.equals(visit.serverType)) {
            visit.serverType = loc.serverType;
            visit.infoChanged = true;
        }
    }

    /** INFO for the open game when its mode, map or server type changed after OPEN (code review 2, I4). */
    private void emitInfoIfChanged() {
        if (visit.state != State.OPEN || !visit.infoChanged) return;
        visit.infoChanged = false;
        out.add(new Command(CommandType.INFO, -1, -1, 0L, null, info(), Collections.<MarkerEvent>emptyList(), false,
                null, null, null, false, null, visit.gameId));
    }

    private GameInfo info() {
        return new GameInfo(visit.mode, visit.map, visit.serverType, visit.classification, singlePlayer);
    }

    // ---- visit ends and closes

    /**
     * The visit ends (switch, disconnect, quit, settings): close or drop it, counting natural ends. {@code ending}:
     * the connection ends, so no more evidence about undecided segments can come.
     */
    private void endVisit(long nowUs, EndReason reason, boolean count, boolean ending) {
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
                closeGame(nowUs, reason, false, ending);
                break;
            default:
                break;
        }
    }

    /**
     * The connection ends or Record Games goes off. An open game first takes every segment of this connection that
     * is still undecided (its candidate, and boundaries the client never loaded): they were captured after the
     * game's last segment and no later location can name another server. Otherwise they are dropped.
     */
    private void endConnection(long nowUs, EndReason reason, boolean count) {
        if (visit != null && visit.state == State.OPEN) {
            endVisit(nowUs, reason, count, true);
        } else {
            if (cand != null) {
                emitDrop(cand.firstSeg, cand.lastSeg);
                cand = null;
            }
            if (visit != null) endVisit(nowUs, reason, count, true);
        }
        visit = null;
    }

    /**
     * The only place a CLOSE is emitted (code review 2, I1; code review 3, I1). An undecided candidate is joined
     * first, so a CLOSE can never leave a segment of this game routed elsewhere: due closes (timers, failures) wait in
     * {@link #advance} until nothing is undecided, and a different-server or lobby location resolves the candidate
     * before calling this. With {@code ending} (disconnect, quit, settings) boundaries the capture handler started but
     * the client never loaded are joined too, with any failure pending in them. The CLOSE's time: the due time of a
     * close that fell due earlier, unless a segment was JOINed after it (then now). A {@link EndReason#REPLAY} close
     * discards the game (the writer deletes its file): it joins nothing and is never a failure.
     */
    private void closeGame(long nowUs, EndReason reason, boolean failed, boolean ending) {
        boolean discard = reason == EndReason.REPLAY;
        // A discarded game takes nothing more: an undecided candidate waits for its own location (code review 1, I4).
        if (cand != null && !discard) mergeCandidate(nowUs);
        if (ending && captureSeg > lastBoundarySeg) {
            out.add(Command.range(CommandType.JOIN, lastBoundarySeg + 1, captureSeg));
            visit.lastSeg = captureSeg;
            visit.lastJoinUs = nowUs;
            if (pendingFailSeg != NONE) {
                if (!visit.failPending) visit.failAtUs = nowUs;
                visit.failPending = true;
                visit.captureLatched |= pendingFailLatched;
                pendingFailSeg = NONE;
                pendingFailLatched = false;
            }
        }
        boolean f = !discard && (failed || visit.failPending);
        EndReason why = f ? EndReason.FAILED : reason;
        // Code review 3, I1: a close that fell due earlier ends at its due time, unless a segment was JOINed after it.
        long due = closeDueAt(visit);
        long endUs = due <= nowUs && visit.lastJoinUs < due ? due : nowUs;
        RunStats stats = new RunStats(new ArrayList<LocationLogEntry>(log), lobbyOverridden, visitsWithoutLocation,
                bedwarsVisitsWithoutStart, shortVisitsDropped, visit.source);
        out.add(new Command(CommandType.CLOSE, -1, -1, endUs, null, null, Collections.<MarkerEvent>emptyList(),
                visit.keyMarked, null, why, visit.result != null ? visit.result : Result.UNKNOWN, f, stats,
                visit.gameId));
        log.clear();
        lobbyOverridden = 0;
        visitsWithoutLocation = 0;
        bedwarsVisitsWithoutStart = 0;
        shortVisitsDropped = 0;
        visit.failPending = false;
        visit.state = f ? State.FAILED : State.DROPPED;
        if (f && visit.captureLatched) awaitingRearm = true;
    }

    /** The candidate has had no location for {@link #CONTINUE_WAIT_US} since its last boundary. */
    private boolean presumedSameServer(long nowUs) {
        return cand != null && nowUs - cand.lastUs >= CONTINUE_WAIT_US;
    }

    /**
     * The candidate's own world shows Bed Wars after {@link #LOCATION_WAIT_US} without a location: the
     * pregame queue, or an active game whose rising edge came after the candidate's boundary (a game that
     * was already running before it, e.g. the end screen of the previous one, does not count).
     */
    private boolean candidateSidebarBedwars(long nowUs) {
        return sidebarValid && nowUs - cand.firstUs >= LOCATION_WAIT_US
                && (bwQueue || bwActive && activeEdgeUs >= cand.firstUs);
    }

    /** A candidate after a dropped visit becomes a new Bed Wars run decided by the sidebar. */
    private void startSidebarRunFromCandidate(long nowUs) {
        Candidate c = cand;
        cand = null;
        visit = new Visit(c.firstSeg, c.firstUs);
        visit.lastSeg = c.lastSeg;
        visit.state = State.BEDWARS_PENDING;
        visit.classification = Classification.SIDEBAR;
        visit.skip = isUncaptured(c.firstSeg);
        takeHeldMarkers(c);
        if (c.failed) failVisit(nowUs, c.captureLatched);
        dropOverflowed();
    }

    private void capDrop() {
        emitDrop(visit.firstSeg, visit.lastSeg);
        visit.pendingMarkers.clear();
        visit.keyMarked = false;
        visit.state = State.DROPPED;
    }

    private void open(long nowUs, StartSource source) {
        if (visit.skip) {
            skipRun();
            return;
        }
        if (source == StartSource.SINGLEPLAYER) visit.gameStartUs = nowUs;
        visit.state = State.OPEN;
        visit.source = source;
        visit.gameId = nextGameId++;
        visit.infoChanged = false;
        List<MarkerEvent> markers = visit.pendingMarkers.isEmpty()
                ? Collections.<MarkerEvent>emptyList()
                : Collections.unmodifiableList(new ArrayList<MarkerEvent>(visit.pendingMarkers));
        visit.pendingMarkers.clear();
        out.add(new Command(CommandType.OPEN, visit.firstSeg, visit.lastSeg, visit.gameStartUs, source, info(),
                markers, visit.keyMarked, null, null, null, false, null, visit.gameId));
    }

    private void acceptMarker(long nowUs, Marker m) {
        boolean open = visit != null && visit.state == State.OPEN;
        boolean pendingServer = visit != null && !visit.skip
                && (visit.state == State.BEDWARS_PENDING || visit.state == State.SERVER_PENDING);
        if (!MarkerRules.accepts(open, pendingServer)) return;
        MarkerEvent e = new MarkerEvent(m, nowUs);
        boolean marked;
        long game = 0L;
        if (cand != null) {
            // Code review 3, I3: its TRACK record goes into the candidate's segment; the run that owns it takes it.
            cand.held.add(e);
            cand.heldKey |= MarkerRules.keepsForever(m);
            marked = false;
        } else {
            if (MarkerRules.keepsForever(m)) visit.keyMarked = true;
            if (pendingServer) visit.pendingMarkers.add(e);
            if (open) game = visit.gameId;
            marked = visit.keyMarked;
        }
        out.add(new Command(CommandType.MARKER, -1, -1, nowUs, null, null, Collections.<MarkerEvent>emptyList(),
                marked, m, null, null, false, null, game));
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
