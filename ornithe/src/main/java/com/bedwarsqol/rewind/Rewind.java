package com.bedwarsqol.rewind;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.event.EventPriority;
import com.bedwarsqol.feature.HypixelLocationCodec;
import com.bedwarsqol.feature.SessionChatLine;
import com.bedwarsqol.feature.SessionHoldKey;
import com.bedwarsqol.mixin.NetworkManagerAccessor;
import com.bedwarsqol.ornithe.ModEvents;
import com.bedwarsqol.ornithe.ServerConnectEvent;
import com.bedwarsqol.rewind.RewindGameRules.Command;
import com.bedwarsqol.stats.HypixelContext;
import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Rewind's wiring (PLAN "Client thread", Phase 6): it installs the capture handler at each connection, feeds the
 * game rules from the client thread and executes their commands against the writer, the capture session and the
 * player's own TRACK producer.
 *
 * <p><b>Per connection.</b> {@code SERVER_CONNECT} (Netty thread) installs {@link RewindCapture} when Rewind and
 * Record Games are on and the connection is single-player or Hypixel, with a fresh NET producer, then posts the
 * connection to the client thread through Minecraft's task queue, ahead of every play packet. There the rules get
 * {@code onConnect}, the writer {@code attach} (fresh TRACK producer and stats feed), in that order.
 *
 * <p><b>Client thread.</b>
 * <ul>
 *   <li>{@code WORLD_LOAD} (HIGHEST): the k-th one is the capture handler's k-th boundary. It opens TRACK segment k,
 *       sends the source-0 carry-over header ({@link CarryOverSnapshot}, no S47) and tells the rules.</li>
 *   <li>A {@code hyevent:location} frame is posted by the capture handler with its segment, through the same task
 *       queue as the packets around it.</li>
 *   <li>{@code CLIENT_TICK}: disconnect and settings checks, new capture failures ({@code failureCount} changes),
 *       new writer failures, pending bytes, the Bed Wars sidebar state, the self track and the Marker Key.</li>
 *   <li>Before and after every rules event the wiring settles what changed elsewhere: the capture handler's segment
 *       and restarts, a capture failure with the segment it latched in, writer failures by game id and TRACK
 *       failures with their segment (code review 2, I2/I3). A failure thus reaches the game that owns it, whatever
 *       the rules are tracking when the client thread looks.</li>
 *   <li>{@code GAME_LINE}: the player's own classified game lines (results and automatic markers).</li>
 *   <li>A CAMERA sample from {@code EntityRenderer.renderWorld} HEAD, at most every 4 ms and only on change.</li>
 *   <li>{@code HUD_POST}: the Marker Key flash, the only thing Rewind ever draws.</li>
 * </ul>
 *
 * <p><b>Failures.</b> The event dispatcher does not catch, so every listener here catches Throwable: a recorder bug
 * never breaks the connection, the tick or world loading. A failure ends recording for the game that owns it through
 * the rules and is logged with ids and counts only, written by the writer thread (no file I/O here). No
 * packet is ever sent or changed; with Rewind or Record Games off nothing is installed, no writer exists and no file
 * is written.
 *
 * <p>The instance is the engine and touches no Minecraft type, so it runs in tests with an embedded channel, a
 * manual task queue and a writer in step mode; {@link Game} holds the Minecraft glue.
 */
public final class Rewind {

    /** CAMERA samples are taken at most this often. */
    static final long CAMERA_INTERVAL_NS = 4_000_000L;
    private static final int NET_INITIAL_CHUNKS = 2;
    private static final int TRACK_INITIAL_CHUNKS = 2;
    private static final int MAX_CHUNKS = 6;
    private static final int EARLY_LOG_CAP = 16;
    private static final int[] SKIPPED_IDS = {RewindFormat.PACKET_KEEP_ALIVE, RewindFormat.PACKET_CUSTOM_PAYLOAD,
            RewindFormat.PACKET_SET_COMPRESSION};

    /** The client state at a boundary (production: {@link CarryOverSnapshot#capture}). */
    public interface SnapshotSource {
        CarryOverEncoder.Snapshot capture();
    }

    /** Self-track work on the current TRACK segment; returns false if the producer had no chunk. */
    public interface TrackWork {
        boolean sample(SelfTrackSampler s, RecordChunk.Producer track, long nowUs);
    }

    /** One connection, as the client thread tracks it. */
    static final class Conn {
        final Object handle;
        final Channel channel;
        final boolean local;
        /** Null: this connection is not recorded. */
        final CaptureSession session;
        final PacketStats.Feed feed = new PacketStats.Feed();
        RecordChunk.Producer track;
        /** WORLD_LOADs so far = the current TRACK segment. */
        int boundaries;
        /** Highest segment the rules dropped. */
        int droppedThrough;
        /** Capture failures already reported to the rules; starts at 0 (code review 1, I2). */
        int seenCaptureFailures;
        /** Capture restarts already reported to the rules. */
        int seenResumes;
        final long[] seenSkippedCount = new long[SKIPPED_IDS.length];
        final long[] seenSkippedBytes = new long[SKIPPED_IDS.length];
        boolean restrictReported;
        boolean gameOpen;
        int gameFirst;
        int gameLast;
        /** The TRACK segment a TRACK record failed in (0: none since the last settle). */
        int trackFailedSeg;
        boolean ended;
        /** The rules themselves threw: nothing more is done for this connection. */
        boolean dead;

        Conn(Object handle, Channel channel, boolean local, CaptureSession session) {
            this.handle = handle;
            this.channel = channel;
            this.local = local;
            this.session = session;
        }
    }

    private final RewindWriter.Clock clock;
    private final Executor clientThread;
    private final Supplier<RewindWriter> writerFactory;
    private final CaptureSession.Clock captureClock;

    // client thread
    private final RewindGameRules rules = new RewindGameRules();
    private final SelfTrackSampler sampler = new SelfTrackSampler();
    private final RewindFlash flash = new RewindFlash();
    private RewindWriter writer;
    private Conn conn;
    /** Log lines from before the writer existed (client thread). */
    private final List<String> earlyLog = new ArrayList<String>();
    private long lastCameraNs;
    private boolean cameraSampled;

    /** A Throwable from the Netty-side listener, logged by the next client tick. */
    private volatile Throwable nettyError;

    Rewind(RewindWriter.Clock clock, Executor clientThread, Supplier<RewindWriter> writerFactory) {
        this.clock = clock;
        this.clientThread = clientThread;
        this.writerFactory = writerFactory;
        this.captureClock = new CaptureSession.Clock() {
            @Override
            public long nowUs() {
                return Rewind.this.clock.nowUs();
            }
        };
    }

    // ================================================================== Netty thread

    /**
     * {@code SERVER_CONNECT}: installs the capture handler if eligible (A10) and posts the connection to the client
     * thread. Returns the session, or null when this connection is not recorded. Never throws.
     */
    CaptureSession onServerConnect(Channel channel, Object handle, boolean local, String address, boolean rewindOn,
                                   boolean recordGames) {
        CaptureSession session = null;
        try {
            session = RewindCapture.installIfEligible(channel, rewindOn, recordGames, local, address,
                    new Supplier<CaptureSession>() {
                        @Override
                        public CaptureSession get() {
                            return newSession();
                        }
                    });
        } catch (Throwable t) {
            nettyError = t;
        }
        final Conn c = new Conn(handle, channel, local, session);
        try {
            clientThread.execute(new Runnable() {
                @Override
                public void run() {
                    connected(c);
                }
            });
        } catch (Throwable t) {
            nettyError = t;
            if (session != null) {
                RewindCapture.remove(channel); // nobody will ever drain it
            }
            return null;
        }
        return session;
    }

    private CaptureSession newSession() {
        RecordChunk.Producer net = new RecordChunk.Producer(RewindFormat.CHUNK_CAPACITY, NET_INITIAL_CHUNKS, MAX_CHUNKS,
                clock.nowUs());
        final CaptureSession[] self = new CaptureSession[1];
        CaptureSession s = new CaptureSession(net, captureClock, new CaptureSession.LocationSink() {
            @Override
            public void onLocation(final int segment, final byte[] payload) {
                final CaptureSession session = self[0];
                clientThread.execute(new Runnable() {
                    @Override
                    public void run() {
                        location(session, segment, payload);
                    }
                });
            }
        });
        self[0] = s;
        return s;
    }

    // ================================================================== client thread

    private void connected(Conn c) {
        try {
            long now = clock.nowUs();
            Conn old = conn;
            boolean recording = c.session != null;
            if (recording) {
                try {
                    if (writer == null) {
                        writer = writerFactory.get();
                        if (writer != null) {
                            for (int i = 0; i < earlyLog.size(); i++) {
                                writer.diag(earlyLog.get(i));
                            }
                            earlyLog.clear();
                        }
                    }
                } catch (Throwable t) {
                    log("rewind: writer unavailable " + name(t));
                    recording = false;
                }
                if (writer == null) {
                    recording = false;
                    RewindCapture.remove(c.channel);
                }
            }
            List<Command> commands = rules.onConnect(now, c.local, recording);
            if (old != null) {
                execute(old, commands); // the previous connection's CLOSE and DROPs, under its generation
                old.ended = true;
            } else if (writer != null) {
                writer.submitAll(commands);
            }
            conn = recording ? c : new Conn(c.handle, c.channel, c.local, null);
            if (recording) {
                c.track = new RecordChunk.Producer(RewindFormat.CHUNK_CAPACITY, TRACK_INITIAL_CHUNKS, MAX_CHUNKS, now);
                // seenCaptureFailures stays 0: a capture failure latched on Netty before this task ran (initial
                // world data, chunks exhausted before attach) is reported to the rules by the first settle, with
                // the segment it latched in (code review 2, I2).
                writer.attach(c.session.producer(), c.track, c.feed);
                log("rewind: connect local=" + c.local + " recording=true");
            }
        } catch (Throwable t) {
            failure("connect", t);
        }
    }

    /** {@code WORLD_LOAD}: the next boundary of this connection was applied on the client thread. */
    void onWorldLoad(SnapshotSource source) {
        Conn c = conn;
        if (!recording(c)) {
            return;
        }
        Throwable snapshotError = null;
        try {
            long now = clock.nowUs();
            settle(c, now); // failures and restarts the capture handler saw before this boundary come first
            int segment = ++c.boundaries;
            if (!c.track.startSegment(segment, null, 0, 0)) {
                trackFailed(c);
            }
            sampler.resetSegment();
            cameraSampled = false;
            try {
                CarryOverEncoder.Snapshot s = source == null ? null : source.capture();
                if (s != null) {
                    writer.header(segment, now, CarryOverSnapshot.packets(s));
                }
            } catch (Throwable t) {
                snapshotError = t; // the boundary still counts, so the segments stay aligned
            }
            execute(c, rules.onBoundary(now, segment));
            settle(c, now);
        } catch (Throwable t) {
            failure("world", t);
            return;
        }
        if (snapshotError != null) {
            failure("snapshot", snapshotError);
        }
    }

    private void location(CaptureSession session, int segment, byte[] payload) {
        Conn c = conn;
        if (!recording(c) || c.session != session) {
            return;
        }
        try {
            long now = clock.nowUs();
            settle(c, now);
            execute(c, rules.onLocation(now, segment, HypixelLocationCodec.decodeLocation(payload)));
            settle(c, now);
        } catch (Throwable t) {
            failure("location", t);
        }
    }

    /**
     * {@code CLIENT_TICK}. {@code recordSetting} is Rewind and Record Games both on; the Bed Wars flags are the
     * existing sidebar detection.
     */
    void tick(boolean recordSetting, boolean bedwarsQueue, boolean bedwarsActive) {
        try {
            Throwable netty = nettyError;
            if (netty != null) {
                nettyError = null;
                log("rewind: connect hook failed " + name(netty));
            }
            Conn c = conn;
            if (c == null || c.ended) {
                return;
            }
            long now = clock.nowUs();
            if (c.channel == null || !c.channel.isOpen()) {
                if (!c.dead) {
                    if (recording(c)) {
                        settle(c, now); // segments the client never loaded are still this game's (code review 2, I1)
                    }
                    execute(c, rules.onDisconnect(now, false)); // GAME_END is still committed before CLOSE
                }
                c.ended = true;
                return;
            }
            if (!recording(c)) {
                return;
            }
            if (!recordSetting) {
                settle(c, now);
                execute(c, rules.onRecordSetting(now, false));
                log("rewind: record games off");
                return;
            }
            settle(c, now);
            bridgeFeed(c);
            execute(c, rules.onPendingBytes(now, writer.pendingBytes()));
            execute(c, rules.tick(now, bedwarsQueue, bedwarsActive));
            settle(c, now);
        } catch (Throwable t) {
            failure("tick", t);
        }
    }

    /** Self-track work on the current segment, if it is recorded. */
    void selfTrack(TrackWork work) {
        Conn c = conn;
        if (!trackOpen(c)) {
            return;
        }
        try {
            long t0 = System.nanoTime();
            if (!work.sample(sampler, c.track, clock.nowUs())) {
                trackFailed(c);
            }
            c.feed.track.add(System.nanoTime() - t0);
        } catch (Throwable t) {
            failure("track", t);
        }
    }

    /** A CAMERA sample at {@code nanoTime}: at most every {@link #CAMERA_INTERVAL_NS}, written only on change. */
    void camera(long nanoTime, float yaw, float pitch) {
        Conn c = conn;
        if (!trackOpen(c) || cameraSampled && nanoTime - lastCameraNs < CAMERA_INTERVAL_NS) {
            return;
        }
        try {
            lastCameraNs = nanoTime;
            cameraSampled = true;
            if (!sampler.camera(c.track, clock.nowUs(), yaw, pitch)) {
                trackFailed(c);
            }
            c.feed.camera.add(System.nanoTime() - nanoTime);
        } catch (Throwable t) {
            failure("camera", t);
        }
    }

    /** A classified own game line ({@code GAME_LINE}). */
    void onGameLine(SessionChatLine.Kind kind) {
        Conn c = conn;
        if (!recording(c) || kind == null) {
            return;
        }
        try {
            long now = clock.nowUs();
            execute(c, rules.onGameLine(now, kind));
            settle(c, now);
        } catch (Throwable t) {
            failure("gameline", t);
        }
    }

    /** The Marker Key went down (rising edge, no screen open). */
    void onMarkerKey() {
        Conn c = conn;
        if (!recording(c)) {
            return;
        }
        try {
            long now = clock.nowUs();
            execute(c, rules.onMarkerKey(now));
            settle(c, now);
        } catch (Throwable t) {
            failure("marker", t);
        }
    }

    /** The flash colour to draw now, 0 when idle. */
    int flashColor() {
        return flash.colorAt(clock.nowUs() / 1000L);
    }

    RewindGameRules rules() {
        return rules;
    }

    /** The current connection's handle (production: its {@code NetworkManager}), or null. */
    Object connectionHandle() {
        Conn c = conn;
        return c == null ? null : c.handle;
    }

    boolean trackOpen() {
        return trackOpen(conn);
    }

    // ================================================================== internals

    private boolean recording(Conn c) {
        return c != null && !c.ended && !c.dead && c.session != null && writer != null && rules.active();
    }

    /** The current TRACK segment is recorded: a boundary was applied and the rules did not drop it. */
    private boolean trackOpen(Conn c) {
        return recording(c) && c.track != null && c.boundaries > 0 && c.boundaries > c.droppedThrough;
    }

    /** The current TRACK segment belongs to the open game. */
    private static boolean trackInGame(Conn c) {
        return c.gameOpen && c.boundaries >= c.gameFirst && c.boundaries <= c.gameLast;
    }

    private void execute(Conn c, List<Command> commands) {
        for (int i = 0; i < commands.size(); i++) {
            execute(c, commands.get(i));
        }
    }

    private void execute(Conn c, Command cmd) {
        long now = clock.nowUs();
        switch (cmd.type) {
            case OPEN:
                c.gameOpen = true;
                c.gameFirst = cmd.firstSegment;
                c.gameLast = cmd.lastSegment;
                submit(cmd);
                if (trackInGame(c) && c.track != null) {
                    // The logical start from the rules (visit start or active edge), so recovery without the
                    // .json rebuilds the same start, date and length (code review 1, I6).
                    if (!sampler.gameStart(c.track, now, cmd.timeUs)) {
                        trackFailed(c);
                    }
                    // Only what the rules know for this visit: never another server's live mode or map (code
                    // review 2, I4). A later location updates them through INFO.
                    writeInfo(c, now, cmd.info);
                }
                log("rewind: open segments=" + cmd.firstSegment + ".." + cmd.lastSegment + " source="
                        + (cmd.startSource == null ? 0 : cmd.startSource.code) + " markers=" + cmd.markers.size());
                break;
            case INFO:
                // Mode or map learned after OPEN (code review 2, I4): TRACK INFO records for recovery, then the
                // writer updates the .json and END.
                if (trackInGame(c) && c.track != null && !c.ended) {
                    writeInfo(c, now, cmd.info);
                }
                submit(cmd);
                break;
            case JOIN:
                if (c.gameOpen) {
                    c.gameLast = Math.max(c.gameLast, cmd.lastSegment);
                }
                submit(cmd);
                break;
            case DROP:
                if (cmd.lastSegment > c.droppedThrough) {
                    c.droppedThrough = cmd.lastSegment;
                    if (c.session != null) {
                        c.session.skipSegment(cmd.lastSegment);
                    }
                }
                submit(cmd);
                break;
            case CLOSE:
                // Terminal TRACK records are committed before CLOSE is queued (PLAN "Close protocol").
                if (trackInGame(c) && c.track != null && !c.ended) {
                    if (!sampler.gameEnd(c.track, now, gameEnd(cmd.result))) {
                        trackFailed(c);
                    }
                }
                c.gameOpen = false;
                submit(cmd);
                log("rewind: close endedBy=" + (cmd.endedBy == null ? "unknown" : cmd.endedBy.json) + " result="
                        + cmd.result + " failed=" + cmd.failed + " skippedAfterFailure=" + rules.skippedAfterFailure());
                break;
            case MARKER:
                // Every MARKER becomes a TRACK record, pending runs included: the .json markers come from them.
                if (c.track != null && c.boundaries > 0 && !c.ended) {
                    if (!sampler.marker(c.track, now, cmd.marker.code)) {
                        trackFailed(c);
                    }
                }
                submit(cmd);
                break;
            case FLASH:
                flash.start(now / 1000L);
                break;
            case REARM:
                if (c.session != null) {
                    c.session.rearm();
                }
                break;
            case REMOVE_HANDLER:
                RewindCapture.remove(c.channel);
                break;
            default:
                break;
        }
    }

    private void submit(Command cmd) {
        if (writer != null) {
            writer.submit(cmd);
        }
    }

    private static int gameEnd(RewindGameRules.Result r) {
        if (r == RewindGameRules.Result.WIN) return RewindFormat.GAME_END_WIN;
        if (r == RewindGameRules.Result.LOSS) return RewindFormat.GAME_END_LOSS;
        return RewindFormat.GAME_END_UNKNOWN;
    }

    /** Mode and map INFO records for {@code info}; absent values write nothing. */
    private void writeInfo(Conn c, long now, RewindGameRules.GameInfo info) {
        if (info == null) {
            return;
        }
        if (!sampler.info(c.track, now, RewindFormat.INFO_MODE, info.mode)
                || !sampler.info(c.track, now, RewindFormat.INFO_MAP, info.map)) {
            trackFailed(c);
        }
    }

    /** A TRACK record found no room: a failure of the current TRACK segment, reported by the next settle. */
    private static void trackFailed(Conn c) {
        if (c.trackFailedSeg == 0) {
            c.trackFailedSeg = Math.max(1, c.boundaries);
        }
    }

    /**
     * What changed outside the client thread since the last look, each reported to the rules once (code review 2,
     * I2/I3): the capture handler's segment and restarts; a new capture failure with the segment it latched in; each
     * writer failure with the id of the game whose file failed; a TRACK failure with its segment.
     */
    private void settle(Conn c, long now) {
        if (c.session != null) {
            CaptureSession s = c.session;
            rules.onCaptureProgress(now, s.segment());
            int r = s.resumeCount();
            if (r != c.seenResumes) {
                c.seenResumes = r;
                rules.onCaptureResumed(s.resumedSegment());
            }
            int f = s.failureCount();
            if (f != c.seenCaptureFailures) {
                c.seenCaptureFailures = f;
                int segment = s.failureSegment();
                log("rewind: capture failure reason=" + s.failureReason() + " count=" + f + " segment=" + segment);
                execute(c, rules.onCaptureFailure(now, segment));
            }
        }
        if (writer != null) {
            long id;
            while ((id = writer.pollFailedGame()) >= 0) {
                Throwable cause = writer.lastFailure();
                log("rewind: writer failure game=" + id + (cause == null ? "" : " " + name(cause)));
                execute(c, rules.onWriterFailure(now, id));
            }
        }
        if (c.trackFailedSeg > 0) {
            int segment = c.trackFailedSeg;
            c.trackFailedSeg = 0;
            log("rewind: track failure segment=" + segment);
            execute(c, rules.onClientFailure(now, segment));
        }
    }

    /** Skipped-packet counts and the restrict flag, from the session (Netty) into the stats feed (client thread). */
    private static void bridgeFeed(Conn c) {
        CaptureSession s = c.session;
        for (int i = 0; i < SKIPPED_IDS.length; i++) {
            int id = SKIPPED_IDS[i];
            long n = s.skippedCount(id);
            long b = s.skippedBytes(id);
            long dn = n - c.seenSkippedCount[i];
            long db = b - c.seenSkippedBytes[i];
            if (dn <= 0) {
                continue;
            }
            for (long k = 1; k < dn; k++) {
                c.feed.skipped(id, 0);
            }
            c.feed.skipped(id, (int) Math.min(Integer.MAX_VALUE, Math.max(0, db)));
            c.seenSkippedCount[i] = n;
            c.seenSkippedBytes[i] = b;
        }
        if (!c.restrictReported && s.restrictChannelSeen()) {
            c.restrictReported = true;
            c.feed.restrictChannelSeen();
        }
    }

    /** A client-thread failure: logged with its class name only, then reported to the rules (game-scoped). */
    private void failure(String where, Throwable t) {
        log("rewind: client failure in " + where + " " + name(t));
        Conn c = conn;
        if (c == null || c.dead || !rules.active()) {
            return;
        }
        try {
            execute(c, rules.onClientFailure(clock.nowUs(), c.boundaries));
        } catch (Throwable again) {
            c.dead = true;
            log("rewind: recorder stopped for this connection " + name(again));
            try {
                RewindCapture.remove(c.channel);
            } catch (Throwable ignored) {
                // the connection is never touched
            }
        }
    }

    private static String name(Throwable t) {
        return t == null ? "null" : t.getClass().getSimpleName();
    }

    /**
     * A DiagLog line with ids and counts only. Never written here: DiagLog writes and flushes synchronously and
     * this runs on the client (render) thread, so the line goes to the writer thread (code review 1, I4). Lines
     * from before the writer exists wait (at most {@link #EARLY_LOG_CAP}) and are handed over when it is made;
     * if no writer is ever made they are dropped.
     */
    private void log(String line) {
        try {
            RewindWriter w = writer;
            if (w != null) {
                w.diag(line);
            } else if (earlyLog.size() < EARLY_LOG_CAP) {
                earlyLog.add(line);
            }
        } catch (Throwable ignored) {
            // logging never matters more than the game
        }
    }

    // ================================================================== production wiring

    private static volatile Rewind instance;

    /** Registers Rewind's listeners (Ornithe client, {@code onReady}). */
    public static void register(String version) {
        Game.register(version);
    }

    /** The Marker Key, as a press (client thread; also {@code cobdev invoke}). */
    public static void markerKeyPressed() {
        try {
            Rewind r = instance;
            if (r != null) {
                r.onMarkerKey();
            }
        } catch (Throwable ignored) {
            // onMarkerKey reports its own failures
        }
    }

    /** {@code EntityRenderer.renderWorld} HEAD (mixin). Never throws. */
    public static void onRenderWorld(float partialTicks) {
        try {
            Rewind r = instance;
            if (r != null && r.trackOpen()) {
                Game.camera(r, partialTicks);
            }
        } catch (Throwable ignored) {
            // a frame is never broken by the recorder
        }
    }

    /** The Minecraft side: event listeners, settings, the player and the flash quad. */
    static final class Game {

        private static boolean markerWasDown;

        private static final SnapshotSource SNAPSHOT = new SnapshotSource() {
            @Override
            public CarryOverEncoder.Snapshot capture() {
                Rewind r = instance;
                Object h = r == null ? null : r.connectionHandle();
                INetHandler handler = h instanceof NetworkManager ? ((NetworkManager) h).getNetHandler() : null;
                return CarryOverSnapshot.capture(Minecraft.getMinecraft(),
                        handler instanceof NetHandlerPlayClient ? (NetHandlerPlayClient) handler : null);
            }
        };

        private static final TrackWork PLAYER = new TrackWork() {
            @Override
            public boolean sample(SelfTrackSampler s, RecordChunk.Producer track, long nowUs) {
                EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
                return p == null || s.player(track, nowUs, p);
            }
        };

        private Game() {
        }

        static void register(final String version) {
            instance = new Rewind(RewindWriter.SYSTEM_CLOCK, new Executor() {
                @Override
                public void execute(Runnable task) {
                    Minecraft.getMinecraft().addScheduledTask(task);
                }
            }, new Supplier<RewindWriter>() {
                @Override
                public RewindWriter get() {
                    return createWriter(version);
                }
            });
            ModEvents.SERVER_CONNECT.register(Game::onServerConnect);
            ModEvents.WORLD_LOAD.register(EventPriority.HIGHEST, false, Game::onWorldLoad);
            ModEvents.CLIENT_TICK.register(Game::onClientTick);
            ModEvents.GAME_LINE.register(Game::onGameLine);
            ModEvents.HUD_POST.register(EventPriority.LOWEST, false, Game::onHudPost);
        }

        /** The writer thread starts with the first recorded connection; the shutdown hook closes open games. */
        private static RewindWriter createWriter(String version) {
            final RewindWriter w = new RewindWriter(new RecordingStore(RewindDir.get()), version);
            w.start();
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        w.shutdown(RewindWriter.SHUTDOWN_DEADLINE_MS);
                    } catch (Throwable ignored) {
                        // the JVM is going away
                    }
                }
            }, "Cobblify Rewind Shutdown"));
            return w;
        }

        private static boolean recordSetting() {
            ClientSettings cfg = BedwarsQol.config;
            return cfg != null && cfg.rewind && cfg.rewindRecordGames;
        }

        static void onServerConnect(ServerConnectEvent event) {
            Rewind r = instance;
            if (r == null) {
                return;
            }
            try {
                if (event == null || event.manager == null) {
                    return;
                }
                NetworkManager manager = event.manager;
                Channel channel = ((NetworkManagerAccessor) manager).bedwarsqol$getChannel();
                boolean local = manager.isLocalChannel();
                ServerData server = local ? null : Minecraft.getMinecraft().getCurrentServerData();
                ClientSettings cfg = BedwarsQol.config;
                r.onServerConnect(channel, manager, local, server == null ? null : server.serverIP,
                        cfg != null && cfg.rewind, cfg != null && cfg.rewindRecordGames);
            } catch (Throwable t) {
                r.nettyError = t;
            }
        }

        static void onWorldLoad(WorldClient world) {
            try {
                Rewind r = instance;
                if (r != null) {
                    r.onWorldLoad(SNAPSHOT);
                }
            } catch (Throwable ignored) {
                // world loading is never broken by the recorder (onWorldLoad reports its own failures)
            }
        }

        static void onClientTick(Minecraft mc) {
            Rewind r = instance;
            if (r == null) {
                return;
            }
            try {
                boolean on = recordSetting();
                boolean recorded = r.recording(r.conn);
                r.tick(on, recorded && HypixelContext.isInBedwarsQueue(), recorded && HypixelContext.isInActiveBedwarsGame());
                r.selfTrack(PLAYER);
                pollMarkerKey(r, Minecraft.getMinecraft());
            } catch (Throwable t) {
                r.failure("tick", t);
            }
        }

        /** Rising edge of the Marker Key with no screen open, keyboard or mouse. */
        private static void pollMarkerKey(Rewind r, Minecraft mc) {
            ClientSettings cfg = BedwarsQol.config;
            int code = cfg == null ? 0 : cfg.rewindMarkerKey;
            boolean down = code != 0 && mc != null && mc.currentScreen == null && keyDown(code);
            if (down && !markerWasDown) {
                r.onMarkerKey();
            }
            markerWasDown = down;
        }

        private static boolean keyDown(int code) {
            if (SessionHoldKey.isMouse(code)) {
                int button = SessionHoldKey.mouseButton(code);
                return button >= 0 && button < Mouse.getButtonCount() && Mouse.isButtonDown(button);
            }
            return code > 0 && code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
        }

        static void onGameLine(SessionChatLine.Kind kind) {
            try {
                Rewind r = instance;
                if (r != null) {
                    r.onGameLine(kind);
                }
            } catch (Throwable ignored) {
                // onGameLine reports its own failures
            }
        }

        /** The flash: one full-screen white quad, only while it fades. */
        static void onHudPost(Float partialTicks) {
            try {
                Rewind r = instance;
                int color = r == null ? 0 : r.flashColor();
                if (color == 0) {
                    return;
                }
                ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
                Gui.drawRect(0, 0, sr.getScaledWidth(), sr.getScaledHeight(), color);
                GlStateManager.color(1f, 1f, 1f, 1f);
            } catch (Throwable ignored) {
                // never break the HUD
            }
        }

        static void camera(Rewind r, float partialTicks) {
            EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
            if (p == null) {
                return;
            }
            float yaw = p.prevRotationYaw + (p.rotationYaw - p.prevRotationYaw) * partialTicks;
            float pitch = p.prevRotationPitch + (p.rotationPitch - p.prevRotationPitch) * partialTicks;
            r.camera(System.nanoTime(), yaw, pitch);
        }
    }
}
