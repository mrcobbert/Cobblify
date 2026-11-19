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
import com.bedwarsqol.rewind.watch.PlaybackGate;
import com.bedwarsqol.rewind.watch.RecordingLibrary;
import com.bedwarsqol.rewind.watch.WatchEntryRules;
import com.bedwarsqol.stats.HypixelContext;
import com.bedwarsqol.stats.EligibilitySnapshot;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
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
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Rewind's wiring (PLAN "Client thread", Phase 6): it installs the capture handler at each connection, feeds the
 * game rules from the client thread and executes their commands against the writer, the capture session and the
 * player's own TRACK producer.
 *
 * <p><b>Per connection.</b> {@code SERVER_CONNECT} (Netty thread) installs {@link RewindCapture} when Rewind and
 * Record Games are on and the connection is single-player or Hypixel, with a fresh NET producer, then posts the
 * connection to the client thread through Minecraft's task queue, ahead of every play packet. There the rules get
 * {@code onConnect}, the writer {@code attach} (fresh TRACK producer and stats feed), in that order. With Rewind on, a
 * Hypixel connection also gets a read-only {@link LocationTap}: a connection that is not recorded (Record Games off,
 * from the start or turned off later) still tells the rules its worlds, locations and sidebar, so they know when the
 * run is a lobby for Watch Last Game (code review 2, I4). Nothing of it is recorded.
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
 *       and restarts, a capture failure with the segment it latched in, writer failures by game id, TRACK failures
 *       with their segment (code review 2, I2/I3) and segments the writer dropped at the pending cap (code review 3,
 *       I2). A failure thus reaches the game that owns it, whatever the rules are tracking when the client thread
 *       looks, and no rules event (game lines and the Marker Key included) can close a game past a segment the
 *       capture handler already started (code review 3, I1).</li>
 *   <li>{@code GAME_LINE}: the player's own classified game lines (results and automatic markers).</li>
 *   <li>A CAMERA sample from {@code EntityRenderer.renderWorld} HEAD, at most every 4 ms and only on change.</li>
 *   <li>{@code HUD_POST}: the Marker Key flash, the only thing Rewind ever draws.</li>
 *   <li><b>Format v2 capture</b> (Phase 6): at connect every capture class is bound to one {@link CaptureSink} over
 *       the TRACK producer ({@link ActionCapture} with the session's {@link HandledRing}, {@link StateCapture},
 *       {@link ViewCapture}, the HUD's {@link HudCapture} and its taps); each WORLD_LOAD starts the segment in all of
 *       them; {@code runTick} HEAD writes the heartbeat and the HUD's tick head, {@code CLIENT_TICK} the end-of-tick
 *       state, the frame sample the view state, each timed into the stats feed. Disconnect, Record Games off and a
 *       stopped recorder unbind them all, so playback (which settles the disconnect first) finds every hook
 *       inert.</li>
 * </ul>
 *
 * <p><b>Failures.</b> The event dispatcher does not catch, so every listener here catches Throwable: a recorder bug
 * never breaks the connection, the tick or world loading. A failure ends recording for the game that owns it through
 * the rules and is logged with ids and counts only, written by the writer thread (no file I/O here). No
 * packet is ever sent or changed; with Rewind off nothing is installed; with Record Games off only the read-only
 * location tap is, no writer exists and no file is written.
 *
 * <p><b>While a recording plays</b> ({@link PlaybackGate#muted()}, Rewind R2) the recorder ignores everything: no
 * connection is installed or attached, and world loads, locations, ticks, self-track and camera samples, game lines
 * and the Marker Key do nothing, so no writer command is made and no file is written. Because the gate silences
 * the CLIENT_TICK in which R1 would notice the closed connection, playback calls {@link #settleDisconnect()} before
 * it mutes the gate (PLAN planner amendment PA1).
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
        /** A {@link LocationTap} was installed: while not recorded, its worlds still reach the rules (review 2, I4). */
        final boolean tapped;
        final PacketStats.Feed feed;
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
        /** Replay|Restrict-style messages already bridged into the stats feed (code review 3, O1: per message). */
        int seenRestrict;
        /** The highest segment the writer dropped at the pending cap, already reported (code review 3, I2). */
        int seenOverflow;
        boolean gameOpen;
        int gameFirst;
        int gameLast;
        /** The TRACK segment a TRACK record failed in (0: none since the last settle). */
        int trackFailedSeg;
        boolean ended;
        /** The rules themselves threw: nothing more is done for this connection. */
        boolean dead;
        /** Format v2 capture's sink while this connection's capture is bound (client thread), else null. */
        CaptureSink sink;
        /** A capture hook failed or had no room in the current segment: v2 capture is off until the next one. */
        boolean captureFailed;
        /** The hook failure the next settle logs (where, cause), or null. */
        String captureWhere;
        Throwable captureFailure;
        /** Time of the v2 work at the current tick's head, added to the end-of-tick work's (one sample per tick). */
        long headNs;

        Conn(Object handle, Channel channel, boolean local, CaptureSession session, boolean tapped,
             PacketStats.Feed feed) {
            this.handle = handle;
            this.channel = channel;
            this.local = local;
            this.session = session;
            this.tapped = tapped;
            this.feed = feed;
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
        if (muted()) {
            return null; // no connection is recorded while a recording plays
        }
        CaptureSession session = null;
        final PacketStats.Feed feed = new PacketStats.Feed();
        try {
            session = RewindCapture.installIfEligible(channel, rewindOn, recordGames, local, address,
                    new Supplier<CaptureSession>() {
                        @Override
                        public CaptureSession get() {
                            return newSession(feed);
                        }
                    });
        } catch (Throwable t) {
            nettyError = t;
        }
        // Code review 2, I4: the lobby is known without recording.
        boolean tapped = rewindOn && !local && EligibilitySnapshot.isExactHypixelHost(address)
                && LocationTap.install(channel, this);
        final Conn c = new Conn(handle, channel, local, session, tapped, feed);
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

    /** A capture session with its handled-order ring (format v2, A7), whose counters go to {@code feed}. */
    private CaptureSession newSession(PacketStats.Feed feed) {
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
        }, new HandledRing(feed));
        self[0] = s;
        return s;
    }

    // ================================================================== client thread

    private void connected(Conn c) {
        if (muted()) {
            return;
        }
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
            if (old != null && recording(old)) {
                settle(old, now); // segments the old connection's client never loaded are still its game's (I1)
            }
            List<Command> commands = rules.onConnect(now, c.local, recording);
            if (old != null) {
                execute(old, commands); // the previous connection's CLOSE and DROPs, under its generation
                old.ended = true;
                releaseCapture(old);
            } else if (writer != null) {
                writer.submitAll(commands);
            }
            conn = recording ? c : new Conn(c.handle, c.channel, c.local, null, c.tapped, c.feed);
            if (recording) {
                c.track = new RecordChunk.Producer(RewindFormat.CHUNK_CAPACITY, TRACK_INITIAL_CHUNKS, MAX_CHUNKS, now);
                // seenCaptureFailures stays 0: a capture failure latched on Netty before this task ran (initial
                // world data, chunks exhausted before attach) is reported to the rules by the first settle, with
                // the segment it latched in (code review 2, I2).
                writer.attach(c.session.producer(), c.track, c.feed);
                bindCapture(c);
                log("rewind: connect local=" + c.local + " recording=true");
            }
        } catch (Throwable t) {
            failure("connect", t);
        }
    }

    /** {@code WORLD_LOAD}: the next boundary of this connection was applied on the client thread. */
    void onWorldLoad(SnapshotSource source) {
        Conn c = conn;
        if (muted()) {
            return;
        }
        if (!recording(c)) {
            if (followed(c)) {
                try {
                    rules.onBoundary(clock.nowUs(), ++c.boundaries); // not recorded: no command (review 2, I4)
                } catch (Throwable t) {
                    failure("world", t);
                }
            }
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
            startCaptureSegment(c, segment);
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
        if (muted() || !recording(c) || c.session != session) {
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
     * A {@code hyevent:location} from {@link LocationTap} (client thread): only for the current connection while it is
     * not recorded (a recorded one gets its locations, with their segment, from the capture handler).
     */
    private void observedLocation(Channel channel, byte[] payload) {
        Conn c = conn;
        if (muted() || !followed(c) || c.channel != channel) {
            return;
        }
        try {
            rules.onLocation(clock.nowUs(), c.boundaries, HypixelLocationCodec.decodeLocation(payload));
        } catch (Throwable t) {
            failure("location", t);
        }
    }

    /**
     * {@code CLIENT_TICK}. {@code recordSetting} is Rewind and Record Games both on; the Bed Wars flags are the
     * existing sidebar detection.
     */
    void tick(boolean recordSetting, boolean bedwarsQueue, boolean bedwarsActive) {
        if (muted()) {
            return; // a closed connection is settled by closeDisconnected() before the gate goes on
        }
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
                disconnected(c, now);
                return;
            }
            if (!recording(c)) {
                releaseCapture(c);
                if (followed(c)) {
                    rules.tick(now, bedwarsQueue, bedwarsActive); // not recorded: no command (review 2, I4)
                }
                return;
            }
            if (!recordSetting) {
                bridgeFeed(c); // as above: bridge before the CLOSE
                settle(c, now);
                execute(c, rules.onRecordSetting(now, false));
                releaseCapture(c);
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

    /**
     * Recorder settle (PLAN section 3 step 2, planner amendment PA1): the caller has just closed the current
     * connection with vanilla's disconnect calls and is about to mute the gate, which silences the CLIENT_TICK where
     * the recorder would notice. The open game is closed now, exactly as that tick would close it. Runs whatever the
     * gate says (it is called before entry); with no connection, or a second time, it does nothing.
     */
    void closeDisconnected() {
        try {
            Conn c = conn;
            if (c == null || c.ended) {
                return;
            }
            disconnected(c, clock.nowUs());
        } catch (Throwable t) {
            failure("settle", t);
        }
    }

    /** The connection ended: its game is closed (GAME_END committed before CLOSE) and nothing more is done for it. */
    private void disconnected(Conn c, long now) {
        if (!c.dead) {
            if (recording(c)) {
                bridgeFeed(c); // counts seen since the last tick belong to this game's stats (outcome review O1)
                settle(c, now); // segments the client never loaded are still this game's (code review 2, I1)
            }
            execute(c, rules.onDisconnect(now, false)); // GAME_END is still committed before CLOSE
        }
        c.ended = true;
        releaseCapture(c);
    }

    /** Self-track work on the current segment, if it is recorded. */
    void selfTrack(TrackWork work) {
        Conn c = conn;
        if (muted() || !trackOpen(c)) {
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
        if (muted() || !trackOpen(c) || cameraSampled && nanoTime - lastCameraNs < CAMERA_INTERVAL_NS) {
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

    // ================================================================== format v2 capture (Phase 6)

    /** The v2 capture work at the end of a client tick (production: {@link Game}'s, reading Minecraft). */
    public interface TickCapture {
        void endOfTick();
    }

    /** The v2 capture work of one rendered frame (production: {@link Game}'s). */
    public interface FrameCapture {
        void frame(float partialTicks, long nanoTime);
    }

    /**
     * The sink every format v2 capture class appends through (one per recorded connection; client thread): records
     * go to the connection's TRACK producer, stamped with the TRACK clock, while the current segment is recorded and
     * no recording plays. All three sinks share the framing {@code buf[0, len) = varint bodyLen | body}, which is
     * exactly what follows the tag on disk ({@link RecordChunk.Producer#appendTrack}).
     *
     * <p>A full producer or a throwing hook ends v2 capture for the segment at once ({@link Conn#captureFailed}) and
     * marks a TRACK failure, which the next settle reports to the rules (the game fails, its prefix stays readable).
     * Nothing here logs, allocates or touches a file; the hooked game call goes on.
     */
    final class CaptureSink implements ActionCapture.Sink, StateCapture.Sink, HudCapture.Sink {
        private final Conn c;

        CaptureSink(Conn c) {
            this.c = c;
        }

        @Override
        public boolean recording() {
            return conn == c && !muted() && capturing(c);
        }

        @Override
        public boolean append(int tag, byte[] buf, int len) {
            if (!recording()) {
                return false;
            }
            if (c.track.appendTrack(clock.nowUs(), tag, buf, 0, len)) {
                return true;
            }
            c.captureFailed = true;
            trackFailed(c);
            return false;
        }

        /** {@link ActionCapture.Sink}'s report; HUD failures arrive as {@code failed("hud", t)}. */
        @Override
        public void failed(Throwable cause) {
            failed("action", cause);
        }

        @Override
        public void failed(String where, Throwable t) {
            if (c.captureFailed || conn != c || muted() || !trackOpen(c)) {
                return; // one report per segment; nothing to fail outside a recorded segment
            }
            c.captureFailed = true;
            c.captureWhere = where;
            c.captureFailure = t;
            trackFailed(c);
        }
    }

    /** v2 capture is on for {@code c}: its sink is bound, its segment recorded and no capture failure since. */
    private boolean capturing(Conn c) {
        return c != null && c.sink != null && !c.captureFailed && trackOpen(c);
    }

    /** Whether format v2 capture writes now (tests). */
    boolean capturing() {
        return !muted() && capturing(conn);
    }

    /** Where the current connection's last capture failure happened ("action", "state", "hud", ...), or null (tests). */
    String captureWhere() {
        Conn c = conn;
        return c == null ? null : c.captureWhere;
    }

    /** The Conn whose sink the capture classes hold (they are static: one client, one connection). */
    private Conn captureOwner;

    /** Binds every capture class to {@code c} at connect (client thread; that thread becomes the hooks' thread). */
    private void bindCapture(Conn c) {
        CaptureSink s = new CaptureSink(c);
        c.sink = s;
        captureOwner = c;
        ActionCapture.bind(s, c.session.handledRing(), c.feed);
        StateCapture.install(s);
        ViewCapture.install(s);
        HudCapture.shared().setSink(null); // set at the first segment start
        HudCapture.shared().setTapReceiver(ViewCapture::mousePressed);
    }

    /** Segment {@code segment} of {@code c} opened (WORLD_LOAD): every capture class starts it, failures re-armed. */
    private void startCaptureSegment(Conn c, int segment) {
        if (c.sink == null || captureOwner != c) {
            return;
        }
        c.captureFailed = false;
        c.headNs = 0;
        ActionCapture.startSegment(segment);
        StateCapture.resetSegment();
        ViewCapture.resetSegment();
        HudCapture.shared().setSink(c.sink);
    }

    /** Capture of {@code c} ends (disconnect, Record Games off, the recorder stopped): every hook is inert again. */
    private void releaseCapture(Conn c) {
        if (c == null || c.sink == null) {
            return;
        }
        c.sink = null;
        if (captureOwner == c) {
            captureOwner = null;
            unbindCapture();
        }
    }

    /** Every capture class unbound: no sink, no ring, no HUD capture. */
    static void unbindCapture() {
        ActionCapture.unbind();
        StateCapture.install(null);
        ViewCapture.install(null);
        HudCapture.shared().setSink(null);
    }

    /**
     * {@code Minecraft.runTick} HEAD: the TICK_HEAD heartbeat and the handled-order head ({@link ActionCapture}, which
     * also closes every local scope whenever it is bound) and the HUD's tick head. Its time joins the end-of-tick
     * work's in one per-tick sample.
     */
    void captureTickHead() {
        Conn c = conn;
        boolean timed = !muted() && capturing(c);
        long t0 = timed ? System.nanoTime() : 0L;
        ActionCapture.onRunTickHead();
        HudCapture.shared().tickHead();
        if (timed) {
            c.headNs = System.nanoTime() - t0;
        }
    }

    /**
     * {@code CLIENT_TICK} (end of the tick): every local scope closed (whenever action capture is bound), then DIG,
     * SETTINGS, IDENTITY, INV, USE and CONT, timed with the tick head.
     */
    void captureTick(TickCapture work) {
        ActionCapture.onClientTickEnd();
        Conn c = conn;
        if (muted() || !capturing(c)) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            work.endOfTick();
        } catch (Throwable t) {
            CaptureSink s = c.sink;
            if (s != null) {
                s.failed("tick", t);
            }
        }
        long head = c.headNs;
        c.headNs = 0;
        c.feed.clientTick.add(head + System.nanoTime() - t0);
    }

    /** One rendered frame of a recorded segment: VIEW, FOV, INPUT, MOUSE and CHAT_INPUT, timed. */
    void captureFrame(FrameCapture work, float partialTicks, long nanoTime) {
        Conn c = conn;
        if (muted() || !capturing(c)) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            work.frame(partialTicks, nanoTime);
        } catch (Throwable t) {
            CaptureSink s = c.sink;
            if (s != null) {
                s.failed("frame", t);
            }
        }
        c.feed.clientFrame.add(System.nanoTime() - t0);
    }

    /** A classified own game line ({@code GAME_LINE}). */
    void onGameLine(SessionChatLine.Kind kind) {
        Conn c = conn;
        if (muted() || !recording(c) || kind == null) {
            return;
        }
        try {
            long now = clock.nowUs();
            settle(c, now); // code review 3, I1: the rules see the capture handler's progress before any close
            execute(c, rules.onGameLine(now, kind));
            settle(c, now);
        } catch (Throwable t) {
            failure("gameline", t);
        }
    }

    /** The Marker Key went down (rising edge, no screen open). */
    void onMarkerKey() {
        Conn c = conn;
        if (muted() || !recording(c)) {
            return;
        }
        try {
            long now = clock.nowUs();
            settle(c, now); // code review 3, I1
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

    /** A recording plays: the recorder ignores everything (Rewind R2, PLAN section 5). */
    private static boolean muted() {
        return PlaybackGate.muted();
    }

    private boolean recording(Conn c) {
        return c != null && !c.ended && !c.dead && c.session != null && writer != null && rules.active();
    }

    /**
     * A live server connection that is not recorded: its worlds, locations and sidebar still reach the rules, which
     * follow whether the run is a lobby and emit nothing (code review 2, I4).
     */
    private boolean followed(Conn c) {
        return c != null && c.tapped && !c.ended && !c.dead && !recording(c);
    }

    /** Whether the client tick should read the Bed Wars sidebar for the rules (recorded, or followed). */
    boolean readsSidebar() {
        Conn c = conn;
        return recording(c) || followed(c);
    }

    /**
     * Reads (never consumes, never releases) {@code hyevent:location} payloads as vanilla's decoder hands them on, and
     * posts a copy to the client thread ({@link #observedLocation}); passes every message on. Installed before
     * {@code packet_handler} on a Hypixel connection while Rewind is on (code review 2, I4).
     */
    static final class LocationTap extends ChannelInboundHandlerAdapter {
        static final String NAME = "cobblify_rewind_location";
        private static final String PACKET_HANDLER = "packet_handler";

        private final Rewind rewind;
        private final Channel channel;

        private LocationTap(Rewind rewind, Channel channel) {
            this.rewind = rewind;
            this.channel = channel;
        }

        /**
         * Never throws. Returns false without a {@code packet_handler}, when one is already there or on failure.
         */
        static boolean install(Channel channel, Rewind rewind) {
            try {
                ChannelPipeline p = channel == null ? null : channel.pipeline();
                if (p == null || p.get(PACKET_HANDLER) == null || p.get(NAME) != null) {
                    return false;
                }
                p.addBefore(PACKET_HANDLER, NAME, new LocationTap(rewind, channel));
                return true;
            } catch (Throwable t) {
                rewind.nettyError = t;
                return false;
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof S3FPacketCustomPayload) {
                try {
                    S3FPacketCustomPayload p = (S3FPacketCustomPayload) msg;
                    if (HypixelLocationCodec.LOCATION_CHANNEL.equals(p.getChannelName())) {
                        final byte[] data = copy(p.getBufferData());
                        rewind.clientThread.execute(new Runnable() {
                            @Override
                            public void run() {
                                rewind.observedLocation(channel, data);
                            }
                        });
                    }
                } catch (Throwable t) {
                    rewind.nettyError = t;
                }
            }
            super.channelRead(ctx, msg);
        }

        private static byte[] copy(ByteBuf buf) {
            if (buf == null) {
                return new byte[0];
            }
            ByteBuf view = buf.duplicate(); // its own reader index: vanilla and other taps still see it all
            byte[] out = new byte[view.readableBytes()];
            view.readBytes(out);
            return out;
        }
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
     * I2/I3): the capture handler's segment and restarts; a new capture failure with the segment it latched in; the
     * segments the writer dropped at the pending cap (code review 3, I2); each writer failure with the id of the game
     * whose file failed; a TRACK failure with its segment.
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
            int dropped = writer.pendingDroppedThrough(c.feed);
            if (dropped > c.seenOverflow) {
                c.seenOverflow = dropped;
                log("rewind: pending cap reached, dropped through segment=" + dropped);
                execute(c, rules.onPendingOverflow(now, dropped));
            }
            long id;
            while ((id = writer.pollFailedGame()) >= 0) {
                Throwable cause = writer.lastFailure();
                log("rewind: writer failure game=" + id + (cause == null ? "" : " " + name(cause)));
                execute(c, rules.onWriterFailure(now, id));
            }
        }
        if (c.captureFailure != null) {
            log("rewind: capture failure in " + c.captureWhere + " " + name(c.captureFailure));
            c.captureFailure = null;
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
        int restrict = s.restrictCount();
        if (restrict != c.seenRestrict) {
            // Per message, not per connection (code review 3, O1): the writer's stats window of the game that is
            // recording now sees it, even if an earlier game on this connection saw one too.
            c.seenRestrict = restrict;
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
            long now = clock.nowUs();
            try {
                settle(c, now); // code review 3, I1: the capture handler's progress first
            } catch (Throwable ignored) {
                // the failure is still reported
            }
            execute(c, rules.onClientFailure(now, c.boundaries));
        } catch (Throwable again) {
            c.dead = true;
            releaseCapture(c);
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

    /**
     * Recorder settle before playback (client thread; PLAN section 3 step 2): call right after closing the connection
     * and before muting the gate, so the game that just ended is closed now rather than on a CLIENT_TICK the gate
     * would silence. Never throws.
     */
    public static void settleDisconnect() {
        try {
            Rewind r = instance;
            if (r != null) {
                r.closeDisconnected();
            }
        } catch (Throwable ignored) {
            // closeDisconnected reports its own failures
        }
    }

    /**
     * {@code EntityRenderer.renderWorld} HEAD (mixin): the CAMERA sample and the frame's v2 view capture. Never
     * throws.
     */
    public static void onRenderWorld(float partialTicks) {
        try {
            Rewind r = instance;
            if (r != null && r.trackOpen()) {
                Game.camera(r, partialTicks);
                r.captureFrame(Game.VIEW, partialTicks, System.nanoTime());
            }
        } catch (Throwable ignored) {
            // a frame is never broken by the recorder
        }
    }

    /** {@code Minecraft.runTick} HEAD (mixin): the v2 tick head ({@link #captureTickHead}). Never throws. */
    public static void onRunTickHead() {
        try {
            Rewind r = instance;
            if (r != null) {
                r.captureTickHead();
            } else {
                ActionCapture.onRunTickHead();
            }
        } catch (Throwable ignored) {
            // a tick is never broken by the recorder
        }
    }

    // ================================================================== watch entries (R2 Phase 6c)

    private static RecordingStore store;
    private static RecordingLibrary library;
    private static ExecutorService libraryWorker;
    private static final AtomicBoolean LIBRARY_REFRESH_QUEUED = new AtomicBoolean();

    /**
     * This process's one {@link RecordingStore}: the writer records through it and the gallery's library reads
     * through it, so the library knows which file is being written here and R1's prune shares its lock.
     */
    public static synchronized RecordingStore store() {
        if (store == null) {
            store = new RecordingStore(RewindDir.get());
        }
        return store;
    }

    /** The recordings the gallery and Watch Last Game show, over {@link #store()}. */
    public static synchronized RecordingLibrary library() {
        if (library == null) {
            library = new RecordingLibrary(store());
        }
        return library;
    }

    /** The recorder's watch-entry state (client thread), or null before Rewind is registered. */
    public static RewindGameRules.EntryState entryState() {
        Rewind r = instance;
        return r == null ? null : r.rules.entryState();
    }

    /** The newest kept game, closed or still being written (reads the {@code .json} files), or null. */
    public static RecordingLibrary.NewestGame newestGame() {
        return library().newest();
    }

    /** The newest kept game with its cached quick-check state, for Watch Last Game (no recording is read). */
    public static WatchEntryRules.Newest newestAvailability() {
        return library().newestAvailability();
    }

    /**
     * Quick-checks every closed recording not checked yet, on Rewind's library thread (never the client thread). A
     * refresh already waiting is not queued twice. Never throws.
     */
    public static void refreshLibrary() {
        try {
            if (!LIBRARY_REFRESH_QUEUED.compareAndSet(false, true)) {
                return;
            }
            libraryWorker().execute(new Runnable() {
                @Override
                public void run() {
                    LIBRARY_REFRESH_QUEUED.set(false);
                    try {
                        library().refresh();
                    } catch (Throwable ignored) {
                        // an unreadable folder leaves recordings unchecked; the gallery still lists them
                    }
                }
            });
        } catch (Throwable t) {
            LIBRARY_REFRESH_QUEUED.set(false);
        }
    }

    private static synchronized ExecutorService libraryWorker() {
        if (libraryWorker == null) {
            libraryWorker = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Cobblify Rewind Library");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        return libraryWorker;
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

        /** End of a client tick: the dig state, then the per-tick client state. */
        private static final TickCapture TICK = new TickCapture() {
            @Override
            public void endOfTick() {
                Minecraft mc = Minecraft.getMinecraft();
                ActionCapture.onTickEnd(mc.playerController, mc.objectMouseOver);
                StateCapture.tick(mc);
            }
        };

        /** One frame's view state. */
        static final FrameCapture VIEW = new FrameCapture() {
            @Override
            public void frame(float partialTicks, long nanoTime) {
                ViewCapture.frame(Minecraft.getMinecraft(), partialTicks, nanoTime);
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
            final RewindWriter w = new RewindWriter(store(), version);
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
                boolean sidebar = r.readsSidebar();
                r.tick(on, sidebar && HypixelContext.isInBedwarsQueue(), sidebar && HypixelContext.isInActiveBedwarsGame());
                r.selfTrack(PLAYER);
                r.captureTick(TICK);
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
