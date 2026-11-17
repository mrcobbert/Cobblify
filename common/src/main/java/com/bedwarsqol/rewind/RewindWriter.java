package com.bedwarsqol.rewind;

import com.bedwarsqol.feature.DiagLog;
import com.bedwarsqol.rewind.RewindGameRules.Command;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * The Rewind writer thread (PLAN "Writer thread"): drains the producers' chunk rings and the command
 * queue, compresses one block per chunk range, routes it by segment (held pending in memory, written
 * to the open game file, or dropped), finalizes games, recovers crashed files at start and prunes.
 *
 * <p><b>Threads.</b> Producers ({@link RecordChunk.Producer}) append on the Netty thread (NET) and the
 * client thread (TRACK) and are never waited on. Every public enqueue method is non-blocking and
 * thread-safe; it puts an item on a synchronized queue, so an item enqueued after a producer's commit
 * on the same thread is processed after that commit is visible. Everything else runs on the writer
 * thread ({@link #start}) or, in tests, on the caller of {@link #step} (step mode; never both).
 *
 * <p><b>Times.</b> Producers, rules commands and headers all use one monotonic microsecond clock
 * ({@link #SYSTEM_CLOCK}: {@code System.nanoTime() / 1000}). File {@code timeUs} values are that clock;
 * the file header's {@code baseEpochMs} maps it to wall time.
 *
 * <p><b>Sync to a cutoff</b> (close, the 2 s flush, shutdown). Per producer: read the watermark once
 * as {@code (s, o)}; take chunks from the full ring while their seq is below s (all present by the
 * rollover order, so this never waits); then copy chunk s from its consumed offset up to o, from the
 * live chunk or the ring head. Bytes of chunk s below o are immutable until this thread releases it.
 *
 * <p><b>Close.</b> CLOSE syncs every producer to its cutoff, then writes INDEX and END and forces the
 * file. Data for a closed game's segments that arrives later is dropped and counted
 * ({@link #lateBytes}); nothing is ever appended after END.
 *
 * <p><b>Connections.</b> {@link #attach} starts a new routing generation: segment numbers of rules
 * commands and headers refer to the producers attached last. Call it on the client thread right after
 * submitting the commands of {@code RewindGameRules.onConnect}, so those commands (the previous
 * connection's CLOSE and DROPs) are routed under the previous generation.
 *
 * <p><b>Errors.</b> A disk error fails that game's file only: its valid prefix stays on disk, later
 * blocks are dropped, {@link #gameFailures} counts it, and nothing is thrown to any caller. Each failed
 * file is reported once with its game id ({@link #pollFailedGame}, the id of the rules' OPEN), so the
 * wiring tells only the rules visit that owns that game ({@code onWriterFailure}); a failure that
 * happens while a game is being closed (its END or {@code force()}) marks that file failed and touches
 * no other game (code review 2, I3).
 *
 * <p><b>Metadata updates.</b> An INFO command (mode or map learned after OPEN) updates the open
 * game's metadata and rewrites its {@code .json}; the wiring writes matching TRACK INFO records, so
 * END and a recovery without the {@code .json} use the latest values (code review 2, I4).
 */
public final class RewindWriter {

    public static final String THREAD_NAME = "Cobblify Rewind Writer";
    /** Live tails are synced at least this often while recording (a crash loses at most this much). */
    public static final long SYNC_INTERVAL_US = 2_000_000L;
    /** How long the shutdown hook waits for the final close. */
    public static final long SHUTDOWN_DEADLINE_MS = 2_000L;
    private static final long IDLE_PARK_NS = 100_000_000L;
    private static final int MAX_RETIRED = 8;

    /** The recorder's clocks. */
    public interface Clock {
        /** Monotonic microseconds, shared with the producers and the rules. */
        long nowUs();

        /** Wall clock, epoch milliseconds. */
        long wallMs();
    }

    public static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long nowUs() {
            return System.nanoTime() / 1000L;
        }

        @Override
        public long wallMs() {
            return System.currentTimeMillis();
        }
    };

    /** Points inside a closing sync where a test may act (test-only). */
    public enum HookPoint { AFTER_CUTOFF, AFTER_DRAIN }

    /** Test-only: runs on the writer thread at each {@link HookPoint} of a close or shutdown sync. */
    public interface CloseHooks {
        void at(HookPoint point, RecordChunk.Producer producer);
    }

    // ------------------------------------------------------------------ queue items

    private static final int ITEM_ATTACH = 1;
    private static final int ITEM_HEADER = 2;
    private static final int ITEM_COMMAND = 3;
    private static final int ITEM_SHUTDOWN = 4;
    private static final int ITEM_DIAG = 5;

    private static final class Item {
        final int kind;
        Command command;
        RecordChunk.Producer net;
        RecordChunk.Producer track;
        PacketStats.Feed feed;
        int segment;
        long timeUs;
        List<byte[]> packets;
        String line;
        final CountDownLatch done = new CountDownLatch(1);

        Item(int kind) {
            this.kind = kind;
        }
    }

    // ------------------------------------------------------------------ writer-thread state types

    private static final int PENDING = 0;
    private static final int GAME = 1;
    private static final int DROPPED = 2;
    /** Belonged to a game that is finalized: anything later is late. */
    private static final int CLOSED = 3;

    private static final class PendingBlock {
        final int type;
        final int segment;
        final long firstTimeUs;
        final byte[] bytes;
        final int raw;

        PendingBlock(int type, int segment, long firstTimeUs, byte[] bytes, int raw) {
            this.type = type;
            this.segment = segment;
            this.firstTimeUs = firstTimeUs;
            this.bytes = bytes;
            this.raw = raw;
        }
    }

    /** What the writer learns from the records it routes: stats and TRACK metadata. */
    private static final class Acc {
        final PacketStats stats = new PacketStats();
        final List<RecordingMeta.Marker> markers = new ArrayList<RecordingMeta.Marker>();
        boolean keySeen;
        String infoMode;
        String infoMap;
        String infoLabel;
        /** Time of the INFO record each value came from: the latest one wins, also across merges. */
        long infoModeUs = Long.MIN_VALUE;
        long infoMapUs = Long.MIN_VALUE;
        long infoLabelUs = Long.MIN_VALUE;
        long gameStartUs = Long.MIN_VALUE;
        int gameEnd = -1;
        long firstUs = Long.MAX_VALUE;
        long lastUs = Long.MIN_VALUE;

        void time(long t) {
            if (t < firstUs) firstUs = t;
            if (t > lastUs) lastUs = t;
        }

        void merge(Acc o) {
            stats.merge(o.stats);
            markers.addAll(o.markers);
            keySeen |= o.keySeen;
            if (o.infoMode != null && o.infoModeUs >= infoModeUs) {
                infoMode = o.infoMode;
                infoModeUs = o.infoModeUs;
            }
            if (o.infoMap != null && o.infoMapUs >= infoMapUs) {
                infoMap = o.infoMap;
                infoMapUs = o.infoMapUs;
            }
            if (o.infoLabel != null && o.infoLabelUs >= infoLabelUs) {
                infoLabel = o.infoLabel;
                infoLabelUs = o.infoLabelUs;
            }
            if (o.gameStartUs != Long.MIN_VALUE && (gameStartUs == Long.MIN_VALUE || o.gameStartUs < gameStartUs)) {
                gameStartUs = o.gameStartUs;
            }
            if (o.gameEnd >= 0) gameEnd = o.gameEnd;
            firstUs = Math.min(firstUs, o.firstUs);
            lastUs = Math.max(lastUs, o.lastUs);
        }
    }

    private static final class Seg {
        final int gen;
        final int segment;
        int state = PENDING;
        Game game;
        List<PendingBlock> blocks = new ArrayList<PendingBlock>();
        long pendingBytes;
        Acc acc = new Acc();

        Seg(int gen, int segment) {
            this.gen = gen;
            this.segment = segment;
        }
    }

    private static final class Game {
        /** The rules' game id from OPEN (code review 2, I3). */
        long gameId;
        String id;
        RecordingStore.Handle handle;
        long pos;
        /** End of the last block written whole. */
        long valid;
        boolean failed;
        long baseEpochMs;
        long raw;
        final List<RecordingReader.IndexEntry> index = new ArrayList<RecordingReader.IndexEntry>();
        final RecordingMeta meta = new RecordingMeta();
        final Acc acc = new Acc();
        final List<Seg> segs = new ArrayList<Seg>();
    }

    /** One attached producer and how far the writer has consumed it. */
    private static final class Source {
        final RecordChunk.Producer producer;
        final boolean net;
        int gen;
        int consumedSeq;
        int consumedOff;
        long consumedTimeUs;
        int headerSeq;
        /** Chunks up to this seq belong to an earlier generation (a producer attached again). */
        int staleThroughSeq;

        Source(RecordChunk.Producer producer, boolean net, int gen) {
            this.producer = producer;
            this.net = net;
            this.gen = gen;
        }
    }

    // ------------------------------------------------------------------ fields

    private final RecordingStore store;
    private final String cobblifyVersion;
    private final Clock clock;
    private final Random random;
    private final LinkedBlockingQueue<Item> queue = new LinkedBlockingQueue<Item>();
    private final Object stepLock = new Object();
    private final BlockCodec codec = new BlockCodec();
    private volatile Thread thread;
    private volatile boolean stopped;
    private volatile CloseHooks hooks;

    // writer thread only
    private int gen;
    private final List<Source> sources = new ArrayList<Source>();
    private final List<Source> retired = new ArrayList<Source>();
    private PacketStats.Feed feed;
    private final PacketStats.FeedWindow feedWindow = new PacketStats.FeedWindow();
    private final Map<Long, Seg> segs = new HashMap<Long, Seg>();
    private Game game;
    private long lastSyncUs = Long.MIN_VALUE;
    private long pendingTotal;
    private long peakPending;
    private Acc visitTarget;

    // published counters (single writer)
    private volatile long pendingBytes;
    private volatile int gameFailures;
    /** Game ids of failed files, in order, for the wiring (written by the writer thread only). */
    private final ConcurrentLinkedQueue<Long> failedGames = new ConcurrentLinkedQueue<Long>();
    private volatile Throwable lastFailure;
    private volatile long lateBytes;
    private volatile long lateRanges;
    private volatile int recovered;
    private volatile int recoveryDeleted;
    private volatile int pruned;

    public RewindWriter(RecordingStore store, String cobblifyVersion) {
        this(store, cobblifyVersion, SYSTEM_CLOCK, new Random());
    }

    public RewindWriter(RecordingStore store, String cobblifyVersion, Clock clock, Random random) {
        this.store = store;
        this.cobblifyVersion = cobblifyVersion;
        this.clock = clock;
        this.random = random;
    }

    // ------------------------------------------------------------------ public API (any thread)

    /**
     * Starts the daemon writer thread (MIN_PRIORITY): recovery and pruning first, then the loop. No-op
     * if already started or shut down.
     */
    public synchronized void start() {
        if (thread != null || stopped) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, THREAD_NAME);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        thread = t;
        t.start();
    }

    /**
     * A connection's producers (either may be null) and its stats feed. Starts a new segment
     * generation; see the class comment for when to call it.
     */
    public void attach(RecordChunk.Producer net, RecordChunk.Producer track, PacketStats.Feed feed) {
        Item it = new Item(ITEM_ATTACH);
        it.net = net;
        it.track = track;
        it.feed = feed;
        enqueue(it);
    }

    /** HEADER source 0 (client snapshot) of {@code segment}, as of the boundary at {@code timeUs}. */
    public void header(int segment, long timeUs, List<byte[]> packets) {
        if (packets == null) {
            return;
        }
        List<byte[]> copy = new ArrayList<byte[]>(packets.size());
        for (byte[] p : packets) {
            if (p != null) {
                copy.add(p.clone());
            }
        }
        Item it = new Item(ITEM_HEADER);
        it.segment = segment;
        it.timeUs = timeUs;
        it.packets = copy;
        enqueue(it);
    }

    /** One rules command: OPEN, JOIN, DROP, CLOSE, MARKER and INFO are executed; the rest are not the writer's. */
    public void submit(Command c) {
        if (c == null) {
            return;
        }
        switch (c.type) {
            case OPEN:
            case JOIN:
            case DROP:
            case CLOSE:
            case MARKER:
            case INFO:
                Item it = new Item(ITEM_COMMAND);
                it.command = c;
                enqueue(it);
                break;
            default:
                break;
        }
    }

    /**
     * A diagnostic line (ids and counts only), written to {@link DiagLog} later by the writer thread, so the
     * caller (the client thread) never does log I/O. Non-blocking; dropped once the writer has stopped.
     */
    public void diag(String line) {
        if (line == null) {
            return;
        }
        Item it = new Item(ITEM_DIAG);
        it.line = line;
        enqueue(it);
    }

    public void submitAll(List<Command> commands) {
        if (commands == null) {
            return;
        }
        for (Command c : commands) {
            submit(c);
        }
    }

    /**
     * The JVM shutdown hook's close: every open game closes with {@code endedBy:"quit"} after a sync
     * to its cutoffs, pending runs are dropped, and the writer stops. Waits at most {@code timeoutMs}
     * for the writer thread; without a running thread it runs on the caller. Returns whether it
     * finished; never throws.
     */
    public boolean shutdown(long timeoutMs) {
        Item it = new Item(ITEM_SHUTDOWN);
        if (stopped) {
            return true;
        }
        queue.offer(it);
        Thread t = thread;
        if (t != null && t.isAlive() && t != Thread.currentThread()) {
            LockSupport.unpark(t);
            try {
                return it.done.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        step();
        return it.done.getCount() == 0;
    }

    /** Bytes held for pending runs of the current connection (for {@code RewindGameRules.onPendingBytes}). */
    public long pendingBytes() {
        return pendingBytes;
    }

    /** Games failed by a writer error (disk, folder), cumulative. */
    public int gameFailures() {
        return gameFailures;
    }

    /**
     * The game id of the next file failed by a writer error, each reported once and in order, or -1 when there is
     * none. The wiring hands each to the rules ({@code onWriterFailure}), which fail only that game if it is still
     * open (code review 2, I3). Non-blocking; any thread.
     */
    public long pollFailedGame() {
        Long id = failedGames.poll();
        return id == null ? -1L : id;
    }

    /** The last writer error, for a counts-only log line (class name). */
    public Throwable lastFailure() {
        return lastFailure;
    }

    /** Bytes committed for a finalized game (or a superseded connection) after its cutoff, dropped. */
    public long lateBytes() {
        return lateBytes;
    }

    /** Chunk ranges dropped as late. */
    public long lateRanges() {
        return lateRanges;
    }

    /** Crashed files finalized by recovery (cumulative). */
    public int recovered() {
        return recovered;
    }

    /** Crashed prefixes with no NET record deleted by recovery. */
    public int recoveryDeleted() {
        return recoveryDeleted;
    }

    /** Recordings deleted by pruning (cumulative). */
    public int pruned() {
        return pruned;
    }

    // ------------------------------------------------------------------ step mode and loop

    /**
     * One round of writer work: every queued item in order, then every handed-off chunk, then (every
     * {@link #SYNC_INTERVAL_US}) a sync of each producer to its cutoff. Never blocks on a producer and
     * never throws.
     */
    public void step() {
        synchronized (stepLock) {
            if (stopped) {
                releaseWaiters();
                return;
            }
            Item it;
            while (!stopped && (it = queue.poll()) != null) {
                execute(it);
            }
            if (stopped) {
                releaseWaiters();
                return;
            }
            try {
                drainAll();
                long now = clock.nowUs();
                if (lastSyncUs == Long.MIN_VALUE || now - lastSyncUs >= SYNC_INTERVAL_US) {
                    syncAll(false);
                    lastSyncUs = now;
                }
                pruneRetired();
            } catch (Throwable t) {
                internalError(t);
            }
        }
    }

    /** Recovery of crashed files, then pruning (the writer thread runs it first). Never throws. */
    public void recoverAndPrune() {
        synchronized (stepLock) {
            try {
                recoverAll();
            } catch (Throwable ignored) {
                // a folder we cannot list: nothing to recover
            }
            prune();
        }
    }

    void setCloseHooks(CloseHooks h) {
        hooks = h;
    }

    private void loop() {
        recoverAndPrune();
        while (!stopped) {
            step();
            if (stopped) {
                break;
            }
            if (queue.isEmpty()) {
                LockSupport.parkNanos(this, IDLE_PARK_NS);
            }
        }
    }

    private void enqueue(Item it) {
        if (stopped) {
            return;
        }
        queue.offer(it);
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    private void releaseWaiters() {
        Item it;
        while ((it = queue.poll()) != null) {
            it.done.countDown();
        }
    }

    private void execute(Item it) {
        try {
            switch (it.kind) {
                case ITEM_ATTACH:
                    doAttach(it.net, it.track, it.feed);
                    break;
                case ITEM_HEADER:
                    emitHeader(route(gen, it.segment), it.segment, RewindFormat.SOURCE_CLIENT_SNAPSHOT, it.timeUs,
                            it.packets);
                    break;
                case ITEM_COMMAND:
                    command(it.command);
                    break;
                case ITEM_SHUTDOWN:
                    doShutdown();
                    break;
                case ITEM_DIAG:
                    try {
                        DiagLog.log(it.line); // synchronous file I/O: only ever on this thread
                    } catch (Throwable ignored) {
                        // a log line never fails a game
                    }
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            internalError(t);
        } finally {
            it.done.countDown();
        }
    }

    private void internalError(Throwable t) {
        if (game != null) {
            failGame(game, t);
        } else {
            lastFailure = t;
        }
    }

    // ------------------------------------------------------------------ commands

    private void command(Command c) {
        switch (c.type) {
            case OPEN:
                open(c);
                break;
            case JOIN:
                if (game != null) {
                    join(game, c.firstSegment, c.lastSegment);
                }
                break;
            case DROP:
                drop(c.firstSegment, c.lastSegment);
                break;
            case CLOSE:
                if (game == null) {
                    startWindow();
                    return;
                }
                syncAll(true);
                finish(game, c.endedBy != null ? c.endedBy.json : "unknown", c.result, c.failed, c.timeUs,
                        c.keyMarked, c.stats);
                break;
            case INFO:
                info(c);
                break;
            case MARKER:
                // The TRACK MARKER record itself comes through the client thread's producer.
                if (game != null && (c.keyMarked || c.marker == MarkerRules.Marker.KEY)) {
                    game.meta.keyMarked = true;
                    if (c.marker == MarkerRules.Marker.KEY) {
                        writeMetaQuietly(game); // .json on Marker Key: kept forever from now on
                    }
                }
                break;
            default:
                break;
        }
    }

    /** INFO: the open game's mode, map or server type learned after OPEN; present values replace, absent ones stay. */
    private void info(Command c) {
        Game g = game;
        RewindGameRules.GameInfo info = c.info;
        if (g == null || g.gameId != c.gameId || info == null) {
            return; // not the open game (already closed): nothing to update
        }
        RecordingMeta m = g.meta;
        if (info.mode != null) m.mode = info.mode;
        if (info.map != null) m.map = info.map;
        if (info.serverType != null) m.serverType = info.serverType;
        writeMetaQuietly(g);
    }

    private void open(Command c) {
        if (game != null) {
            finish(game, "replaced", RewindGameRules.Result.UNKNOWN, false, c.timeUs, false, null);
        }
        Game g = new Game();
        g.gameId = c.gameId;
        game = g;
        long now = clock.nowUs();
        g.baseEpochMs = clock.wallMs() - Math.floorDiv(now, 1000L);
        RecordingMeta m = g.meta;
        m.complete = false;
        m.gameStartUs = c.timeUs;
        m.startedAtMs = g.baseEpochMs + Math.floorDiv(c.timeUs, 1000L);
        RewindGameRules.GameInfo info = c.info;
        if (info != null) {
            m.mode = info.mode;
            m.map = info.map;
            m.serverType = info.serverType;
            m.classification = info.classification != null ? info.classification.json : null;
            m.server = info.singlePlayer ? RecordingMeta.SERVER_SINGLEPLAYER : RecordingMeta.SERVER_HYPIXEL;
        }
        m.keyMarked = c.keyMarked;
        m.cobblifyVersion = cobblifyVersion;
        try {
            g.id = store.newId(m.startedAtMs, random);
            m.id = g.id;
            g.handle = store.create(g.id);
            byte[] fh = BlockCodec.fileHeader(g.baseEpochMs);
            g.handle.write(0, fh, 0, fh.length);
            g.pos = fh.length;
            g.valid = g.pos;
            store.writeMeta(g.id, m);
        } catch (Throwable t) {
            failGame(g, t);
        }
        join(g, c.firstSegment, c.lastSegment);
    }

    private void join(Game g, int first, int last) {
        if (first < 0 || last < first) {
            return;
        }
        for (int s = first; s <= last; s++) {
            Seg seg = route(gen, s);
            if (seg == null || seg.state == CLOSED || seg.game == g) {
                continue;
            }
            if (seg.state == PENDING) {
                for (PendingBlock b : seg.blocks) {
                    writeBlock(g, b.type, b.segment, b.firstTimeUs, b.bytes, b.bytes.length, b.raw);
                }
                subPending(seg.pendingBytes);
                g.acc.merge(seg.acc);
                seg.blocks = null;
                seg.pendingBytes = 0;
                seg.acc = null;
            }
            seg.state = GAME;
            seg.game = g;
            g.segs.add(seg);
        }
    }

    private void drop(int first, int last) {
        if (first < 0 || last < first) {
            return;
        }
        for (int s = first; s <= last; s++) {
            Seg seg = route(gen, s);
            if (seg == null || seg.state == CLOSED) {
                continue;
            }
            dropSeg(seg);
        }
    }

    private void dropSeg(Seg seg) {
        if (seg.state == PENDING) {
            subPending(seg.pendingBytes);
            seg.blocks = null;
            seg.pendingBytes = 0;
            seg.acc = null;
        }
        seg.state = DROPPED;
        seg.game = null;
    }

    private void doAttach(RecordChunk.Producer net, RecordChunk.Producer track, PacketStats.Feed newFeed) {
        syncAll(false); // everything committed so far is routed under the old generation
        gen++;
        List<Source> keep = new ArrayList<Source>();
        for (Source s : sources) {
            if (s.producer == net || s.producer == track) {
                s.gen = gen;
                s.staleThroughSeq = Math.max(s.staleThroughSeq, s.consumedSeq);
                keep.add(s);
            } else {
                retired.add(s);
            }
        }
        while (retired.size() > MAX_RETIRED) {
            retired.remove(0);
        }
        sources.clear();
        addSource(keep, net, true);
        addSource(keep, track, false);
        // Pending runs never survive a connection; old generations' closed and dropped segments are
        // forgotten (their late data is then counted as late).
        for (Iterator<Seg> i = segs.values().iterator(); i.hasNext(); ) {
            Seg s = i.next();
            if (s.gen == gen) {
                continue;
            }
            if (s.state == PENDING) {
                dropSeg(s);
            }
            if (s.state != GAME) {
                i.remove();
            }
        }
        if (newFeed != feed) {
            feed = newFeed;
            feedWindow.zero(); // a connection's feed counts from its creation, before this item ran
            peakPending = pendingTotal;
        } else {
            startWindow();
        }
    }

    private void addSource(List<Source> keep, RecordChunk.Producer p, boolean net) {
        if (p == null) {
            return;
        }
        for (Source s : keep) {
            if (s.producer == p) {
                sources.add(s);
                return;
            }
        }
        sources.add(new Source(p, net, gen));
    }

    private void doShutdown() {
        try {
            if (game != null) {
                syncAll(true);
                finish(game, RewindGameRules.EndReason.QUIT.json, RewindGameRules.Result.UNKNOWN, false,
                        clock.nowUs(), false, null);
            }
            for (Seg s : segs.values()) {
                if (s.state == PENDING) {
                    dropSeg(s);
                }
            }
        } finally {
            stopped = true;
            codec.close();
        }
    }

    // ------------------------------------------------------------------ draining and sync

    private void drainAll() {
        for (Source s : sources) {
            drainFull(s);
        }
        for (Source s : retired) {
            drainFull(s);
        }
    }

    private void drainFull(Source src) {
        RecordChunk.Producer p = src.producer;
        RecordChunk c;
        while ((c = p.pollFull()) != null) {
            consumeWhole(src, c);
            p.release(c);
        }
    }

    /** A handed-off chunk: everything from the consumed offset to its final committed length. */
    private void consumeWhole(Source src, RecordChunk c) {
        int seq = c.seq();
        int from = src.consumedSeq == seq ? src.consumedOff : 0;
        deliver(src, c, seq, from, c.committedLength());
    }

    private void syncAll(boolean closing) {
        for (Source s : sources) {
            syncTo(s, closing);
        }
        for (Source s : retired) {
            syncTo(s, closing);
        }
    }

    /** Sync to a cutoff (PLAN "Sync to a cutoff"); never waits on the producer. */
    private void syncTo(Source src, boolean closing) {
        RecordChunk.Producer p = src.producer;
        // (1) Cutoff: everything committed before this read is in a chunk with seq < s, or in s below o.
        long w = p.watermark();
        int s = RecordChunk.watermarkSeq(w);
        int o = RecordChunk.watermarkOffset(w);
        hook(closing, HookPoint.AFTER_CUTOFF, p);
        // (2) Drain through seq < s: by the rollover order they are all in the ring already.
        RecordChunk c;
        while ((c = p.peekFull()) != null) {
            int seq = c.seq();
            if (seq >= s) {
                break;
            }
            p.pollFull();
            consumeWhole(src, c);
            p.release(c);
        }
        hook(closing, HookPoint.AFTER_DRAIN, p);
        if (s == 0) {
            return; // nothing published yet
        }
        // (3) Tail of chunk s up to o, from the live chunk or the ring head.
        if (src.consumedSeq > s || src.consumedSeq == s && src.consumedOff >= o) {
            return;
        }
        c = p.chunkForSeq(s);
        if (c == null) {
            return; // already consumed whole and released
        }
        int from = src.consumedSeq == s ? src.consumedOff : 0;
        deliver(src, c, s, from, o);
    }

    private void hook(boolean closing, HookPoint point, RecordChunk.Producer p) {
        CloseHooks h = hooks;
        if (closing && h != null) {
            h.at(point, p);
        }
    }

    private void pruneRetired() {
        for (Iterator<Source> i = retired.iterator(); i.hasNext(); ) {
            Source s = i.next();
            if (s.producer.liveChunk() == null && s.producer.peekFull() == null) {
                i.remove();
            }
        }
    }

    // ------------------------------------------------------------------ routing

    private Seg route(int g, int segment) {
        long key = (long) g << 32 | segment & 0xFFFFFFFFL;
        Seg s = segs.get(key);
        if (s == null) {
            if (g != gen) {
                return null; // an earlier connection's forgotten segment: late
            }
            s = new Seg(g, segment);
            segs.put(key, s);
        }
        return s;
    }

    /** Where records of {@code seg} are accounted: its pending accumulator or its game's. */
    private static Acc target(Seg seg) {
        if (seg == null) {
            return null;
        }
        if (seg.state == PENDING) {
            return seg.acc;
        }
        if (seg.state == GAME && seg.game != null) {
            return seg.game.acc;
        }
        return null;
    }

    /** Routes chunk {@code c}'s committed bytes {@code [from, to)}; {@code seq} was read before any release. */
    private void deliver(Source src, RecordChunk c, int seq, int from, int to) {
        if (src.consumedSeq != seq) {
            src.consumedSeq = seq;
            src.consumedOff = 0;
            src.consumedTimeUs = c.startTimeUs();
        }
        if (from < src.consumedOff) {
            from = src.consumedOff;
        }
        boolean stale = seq <= src.staleThroughSeq;
        Seg seg = stale ? null : route(src.gen, c.segment());
        if (src.net && c.firstOfSegment() && src.headerSeq != seq) {
            src.headerSeq = seq;
            List<byte[]> carry = c.carryLength() > 0
                    ? Collections.singletonList(Arrays.copyOf(c.carry(), c.carryLength()))
                    : Collections.<byte[]>emptyList();
            emitHeader(seg, c.segment(), RewindFormat.SOURCE_CAPTURE_CARRY, c.startTimeUs(), carry);
        }
        if (to <= from) {
            return;
        }
        // A range from offset 0 is measured from the chunk's start time, which a long gap before its first record
        // may have moved forward after this chunk was first seen (RecordChunk.Producer, code review 2, I6).
        long base = from == 0 ? c.startTimeUs() : src.consumedTimeUs;
        long last;
        try {
            last = walk(src.net, c.data(), from, to, base, target(seg));
        } catch (RuntimeException e) {
            // Not a producer's records: never write them. The game (if any) is failed, not the writer.
            src.consumedOff = to;
            if (seg != null && seg.state == GAME && seg.game != null) {
                failGame(seg.game, e);
            }
            return;
        }
        src.consumedOff = to;
        src.consumedTimeUs = last;
        deliverBlock(seg, src.net ? RewindFormat.BLOCK_NET : RewindFormat.BLOCK_TRACK, c.segment(), base,
                c.data(), from, to - from);
    }

    private void emitHeader(Seg seg, int segment, int source, long timeUs, List<byte[]> packets) {
        if (seg == null || seg.state == CLOSED) {
            late(0);
            return;
        }
        if (seg.state == DROPPED) {
            return;
        }
        Acc acc = target(seg);
        if (acc != null) {
            acc.stats.addHeader(segment, source, packets.size());
        }
        byte[] payload = BlockCodec.headerPayload(source, packets);
        deliverBlock(seg, RewindFormat.BLOCK_HEADER, segment, timeUs, payload, 0, payload.length);
    }

    private void deliverBlock(Seg seg, int type, int segment, long firstTimeUs, byte[] raw, int off, int len) {
        if (seg == null || seg.state == CLOSED) {
            late(len);
            return;
        }
        if (seg.state == DROPPED) {
            return;
        }
        if (seg.state == GAME && (seg.game == null || seg.game.failed || seg.game.handle == null)) {
            return; // a failed game keeps its prefix and takes nothing more
        }
        long t0 = System.nanoTime();
        int n = codec.encode(type, segment, firstTimeUs, raw, off, len, true);
        if (seg.state == PENDING) {
            seg.blocks.add(new PendingBlock(type, segment, firstTimeUs, Arrays.copyOf(codec.out(), n), len));
            seg.pendingBytes += n;
            addPending(n);
            seg.acc.stats.addWriterWork(System.nanoTime() - t0, len);
        } else {
            Game g = seg.game;
            writeBlock(g, type, segment, firstTimeUs, codec.out(), n, len);
            g.acc.stats.addWriterWork(System.nanoTime() - t0, len);
        }
    }

    private void writeBlock(Game g, int type, int segment, long firstTimeUs, byte[] bytes, int n, int raw) {
        if (g.failed || g.handle == null) {
            return;
        }
        try {
            long at = g.pos;
            g.handle.write(at, bytes, 0, n);
            g.pos = at + n;
            g.valid = g.pos;
            g.raw += raw;
            g.index.add(new RecordingReader.IndexEntry(type, segment, firstTimeUs, at));
        } catch (Throwable t) {
            failGame(g, t);
        }
    }

    private void late(long bytes) {
        lateRanges = lateRanges + 1;
        lateBytes = lateBytes + bytes;
    }

    private void addPending(long n) {
        pendingTotal += n;
        if (pendingTotal > peakPending) {
            peakPending = pendingTotal;
        }
        pendingBytes = pendingTotal;
    }

    private void subPending(long n) {
        pendingTotal = Math.max(0, pendingTotal - n);
        pendingBytes = pendingTotal;
    }

    private void failGame(Game g, Throwable t) {
        lastFailure = t;
        if (!g.failed) {
            g.failed = true;
            g.meta.failed = true;
            failedGames.offer(g.gameId); // before the count: a reader that sees the count finds the id
            gameFailures = gameFailures + 1;
        }
    }

    // ------------------------------------------------------------------ record walking

    private final RecordingReader.NetVisitor netVisitor = new RecordingReader.NetVisitor() {
        @Override
        public void net(long timeUs, byte[] buf, int off, int len) {
            Acc a = visitTarget;
            if (a != null) {
                a.time(timeUs);
                a.stats.addNet(timeUs, buf, off, len);
            }
        }
    };

    private final RecordingReader.TrackVisitor trackVisitor = new RecordingReader.TrackVisitor() {
        @Override
        public void track(long timeUs, int tag, byte[] buf, int bodyOff, int bodyLen) {
            Acc a = visitTarget;
            if (a != null) {
                a.time(timeUs);
                trackMeta(a, timeUs, tag, buf, bodyOff, bodyLen);
            }
        }
    };

    /** Walks NET or TRACK records of {@code [from, to)} into {@code acc} (may be null); returns the last time. */
    private long walk(boolean net, byte[] data, int from, int to, long base, Acc acc) {
        visitTarget = acc;
        try {
            return net ? RecordingReader.walkNet(data, from, to, base, netVisitor)
                    : RecordingReader.walkTrack(data, from, to, base, trackVisitor);
        } finally {
            visitTarget = null;
        }
    }

    private static void trackMeta(Acc a, long timeUs, int tag, byte[] buf, int off, int len) {
        switch (tag) {
            case RewindFormat.TAG_MARKER: {
                int kind = buf[off] & 0xFF;
                String name = RewindFormat.markerName(kind);
                if (name != null) {
                    a.markers.add(new RecordingMeta.Marker(timeUs, name));
                }
                if (kind == RewindFormat.MARKER_KEY) {
                    a.keySeen = true;
                }
                break;
            }
            case RewindFormat.TAG_INFO: {
                RewindFormat.Cursor c = new RewindFormat.Cursor(buf, off, off + len);
                int key = c.u8();
                String value = c.string(RewindFormat.MAX_STRING_BYTES);
                if (key == RewindFormat.INFO_MODE && timeUs >= a.infoModeUs) {
                    a.infoMode = value;
                    a.infoModeUs = timeUs;
                } else if (key == RewindFormat.INFO_MAP && timeUs >= a.infoMapUs) {
                    a.infoMap = value;
                    a.infoMapUs = timeUs;
                } else if (key == RewindFormat.INFO_MODE_LABEL && timeUs >= a.infoLabelUs) {
                    a.infoLabel = value;
                    a.infoLabelUs = timeUs;
                }
                break;
            }
            case RewindFormat.TAG_GAME_START:
                if (a.gameStartUs == Long.MIN_VALUE) {
                    // The record is written when the game opens; its body says how much earlier it started.
                    long back = new RewindFormat.Cursor(buf, off, off + len).varLong();
                    a.gameStartUs = timeUs - Math.max(0L, back);
                }
                break;
            case RewindFormat.TAG_GAME_END:
                a.gameEnd = buf[off] & 0xFF;
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ finalizing

    private static final Comparator<RecordingMeta.Marker> BY_TIME = new Comparator<RecordingMeta.Marker>() {
        @Override
        public int compare(RecordingMeta.Marker a, RecordingMeta.Marker b) {
            return a.tUs < b.tUs ? -1 : a.tUs > b.tUs ? 1 : 0;
        }
    };

    private static String resultName(RewindGameRules.Result r, int gameEnd) {
        if (r == RewindGameRules.Result.WIN) return RecordingMeta.RESULT_WIN;
        if (r == RewindGameRules.Result.LOSS) return RecordingMeta.RESULT_LOSS;
        if (gameEnd == RewindFormat.GAME_END_WIN) return RecordingMeta.RESULT_WIN;
        if (gameEnd == RewindFormat.GAME_END_LOSS) return RecordingMeta.RESULT_LOSS;
        return RecordingMeta.RESULT_UNKNOWN;
    }

    /**
     * Closes {@code g} after its sync: its segments become CLOSED (later data is late), then INDEX + END +
     * force, the {@code .json} and the {@code .stats.json}, the lock is released and pruning runs.
     */
    private void finish(Game g, String endedBy, RewindGameRules.Result result, boolean failed, long endUs,
                        boolean keyMarked, RewindGameRules.RunStats runStats) {
        if (game == g) {
            game = null;
        }
        for (Seg s : g.segs) {
            s.state = CLOSED;
            s.game = null;
        }
        RecordingMeta m = g.meta;
        Acc a = g.acc;
        m.endedBy = endedBy;
        m.failed |= failed || g.failed;
        m.endUs = endUs;
        m.lengthUs = Math.max(0L, endUs - m.gameStartUs);
        m.result = resultName(result, a.gameEnd);
        m.keyMarked |= keyMarked || a.keySeen;
        List<RecordingMeta.Marker> markers = new ArrayList<RecordingMeta.Marker>(a.markers);
        Collections.sort(markers, BY_TIME);
        m.markers = markers;
        m.segments = g.segs.size();
        if (m.mode == null) m.mode = a.infoMode;
        if (m.map == null) m.map = a.infoMap;
        if (m.modeLabel == null) m.modeLabel = a.infoLabel;
        m.rawBytes = g.raw;
        if (g.id != null) {
            RecordingMeta disk = store.readMeta(g.id);
            if (disk != null && disk.starred) {
                m.starred = true;
            }
        }
        if (g.handle != null) {
            try {
                if (g.failed) {
                    g.handle.truncate(g.valid);
                    g.pos = g.valid;
                }
                append(g, RewindFormat.BLOCK_INDEX, endUs, BlockCodec.indexPayload(g.index), true);
                m.storedBytes = g.pos;
                m.complete = true;
                append(g, RewindFormat.BLOCK_END, endUs, m.toUtf8(), false);
                g.handle.force();
            } catch (Throwable t) {
                m.complete = false;
                failGame(g, t);
                m.failed = true;
            }
            store.release(g.id, g.handle);
            g.handle = null;
        }
        if (g.id != null) {
            try {
                store.writeMeta(g.id, m);
            } catch (Throwable ignored) {
                // END (if written) still holds the metadata; recovery rebuilds the .json from it
            }
            PacketStats s = a.stats;
            s.setGame(m.gameStartUs, m.lengthUs);
            s.setSegments(m.segments);
            s.setBytes(g.raw, m.storedBytes);
            s.setRunStats(runStats);
            s.setPeakPendingBytes(peakPending);
            feedWindow.applyTo(feed, s);
            try {
                store.writeStats(g.id, s.toJson());
            } catch (Throwable ignored) {
                // stats are best effort
            }
        }
        prune();
        startWindow();
    }

    /** Appends one block at the end of {@code g}; throws on any I/O error. */
    private void append(Game g, int type, long timeUs, byte[] payload, boolean deflate) throws IOException {
        int n = codec.encode(type, 0, timeUs, payload, 0, payload.length, deflate);
        g.handle.write(g.pos, codec.out(), 0, n);
        g.pos += n;
        g.valid = g.pos;
    }

    private void writeMetaQuietly(Game g) {
        if (g.id == null || g.failed) {
            return;
        }
        try {
            store.writeMeta(g.id, g.meta);
        } catch (Throwable t) {
            failGame(g, t);
        }
    }

    /** A new stats window (location log and counters reset with each file). */
    private void startWindow() {
        feedWindow.reset(feed);
        peakPending = pendingTotal;
    }

    private void prune() {
        try {
            pruned = pruned + store.prune();
        } catch (Throwable ignored) {
            // pruning is retried after the next close
        }
    }

    // ------------------------------------------------------------------ recovery

    /** Reads a locked file through its handle (closing the stream leaves the handle open). */
    private static final class HandleInput extends InputStream {
        private final RecordingStore.Handle h;
        private long pos;

        HandleInput(RecordingStore.Handle h) {
            this.h = h;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            int n = h.read(pos, b, off, len);
            if (n > 0) {
                pos += n;
            }
            return n == 0 ? -1 : n;
        }

        @Override
        public void close() {
            // the handle is released by the store
        }
    }

    private void recoverAll() {
        for (String id : store.listIds()) {
            if (!store.hasCbr(id) || store.isOpenHere(id)) {
                continue;
            }
            if (!store.supportedCbr(id)) {
                continue; // another format version or an unreadable header: never recovered or deleted (I5)
            }
            RecordingMeta old = store.readMeta(id);
            if (old != null && old.complete) {
                continue;
            }
            try {
                recoverOne(id, old);
            } catch (Throwable ignored) {
                // left as it is; tried again at the next start
            }
        }
    }

    /** Finalizes one crashed file under its lock (PLAN "Recovery at start"). */
    private void recoverOne(String id, RecordingMeta old) throws IOException {
        RecordingStore.Handle h = store.openExisting(id);
        if (h == null) {
            return; // another instance holds it, or it is not a regular file
        }
        boolean delete = false;
        try {
            RecordingReader r = new RecordingReader(new BufferedInputStream(new HandleInput(h), 1 << 16), h.size());
            try {
                if (r.unsupportedVersion() || r.header() == null) {
                    return; // another format, or a header we cannot read: leave it alone, uncounted (I5)
                }
                Acc acc = new Acc();
                List<RecordingReader.IndexEntry> index = new ArrayList<RecordingReader.IndexEntry>();
                Set<Integer> segments = new HashSet<Integer>();
                long raw = 0;
                long cut = r.validLength();
                boolean sawIndex = false;
                boolean hasNet = false;
                RecordingMeta endMeta = null;
                boolean hasEnd = false;
                RecordingReader.Block b;
                while ((b = r.next()) != null) {
                    if (b.type == RewindFormat.BLOCK_END) {
                        hasEnd = true;
                        endMeta = RecordingMeta.fromJson(RecordingReader.endJson(b));
                        break;
                    }
                    if (sawIndex) {
                        continue; // only END may follow INDEX; anything else is cut with it
                    }
                    if (b.type == RewindFormat.BLOCK_INDEX) {
                        sawIndex = true;
                        cut = b.fileOffset;
                        continue;
                    }
                    index.add(new RecordingReader.IndexEntry(b.type, b.segment, b.firstTimeUs, b.fileOffset));
                    segments.add(b.segment);
                    raw += b.rawLength;
                    if (b.type == RewindFormat.BLOCK_NET) {
                        hasNet |= b.payload.length > 0;
                        walk(true, b.payload, 0, b.payload.length, b.firstTimeUs, acc);
                    } else if (b.type == RewindFormat.BLOCK_TRACK) {
                        walk(false, b.payload, 0, b.payload.length, b.firstTimeUs, acc);
                    } else if (b.type == RewindFormat.BLOCK_HEADER) {
                        RecordingReader.HeaderPayload hp = RecordingReader.headerPayload(b);
                        acc.stats.addHeader(b.segment, hp.source, hp.packets.size());
                    }
                    cut = r.validLength();
                }
                RecordingReader.FileHeader fh = r.header();
                if (hasEnd) {
                    // Closed in the .cbr; only the .json was lost. Rebuild it from END.
                    RecordingMeta m = endMeta != null ? endMeta : build(id, old, acc, fh, segments, raw);
                    m.id = id;
                    m.complete = true;
                    store.writeMeta(id, m);
                    recovered = recovered + 1;
                    return;
                }
                if (!hasNet) {
                    delete = true; // a v1 header and not one packet: nothing to replay
                    return;
                }
                RecordingMeta m = build(id, old, acc, fh, segments, raw);
                h.truncate(cut);
                Game g = new Game();
                g.handle = h;
                g.pos = cut;
                append(g, RewindFormat.BLOCK_INDEX, m.endUs, BlockCodec.indexPayload(index), true);
                m.storedBytes = g.pos;
                append(g, RewindFormat.BLOCK_END, m.endUs, m.toUtf8(), false);
                h.force();
                store.writeMeta(id, m);
                PacketStats s = acc.stats;
                s.setGame(m.gameStartUs, m.lengthUs);
                s.setSegments(m.segments);
                s.setBytes(raw, m.storedBytes);
                try {
                    store.writeStats(id, s.toJson());
                } catch (IOException ignored) {
                    // stats are best effort
                }
                recovered = recovered + 1;
            } finally {
                r.close();
            }
        } finally {
            store.release(id, h);
            if (delete && store.delete(id)) {
                recoveryDeleted = recoveryDeleted + 1;
            }
        }
    }

    /** Metadata of a crashed game: the old {@code .json} where it has a value, else what TRACK says. */
    private static RecordingMeta build(String id, RecordingMeta old, Acc acc, RecordingReader.FileHeader fh,
                                       Set<Integer> segments, long raw) {
        RecordingMeta m = old != null ? old : new RecordingMeta();
        m.format = RewindFormat.FORMAT_VERSION;
        m.id = id;
        m.complete = true;
        m.recovered = true;
        m.endedBy = "recovered";
        long start;
        if (old != null && old.gameStartUs != 0) {
            start = old.gameStartUs;
        } else if (acc.gameStartUs != Long.MIN_VALUE) {
            start = acc.gameStartUs;
        } else {
            start = acc.firstUs != Long.MAX_VALUE ? acc.firstUs : 0L;
        }
        m.gameStartUs = start;
        if (m.startedAtMs <= 0 && fh != null) {
            m.startedAtMs = fh.baseEpochMs + Math.floorDiv(start, 1000L);
        }
        m.endUs = acc.lastUs != Long.MIN_VALUE ? Math.max(acc.lastUs, start) : start;
        m.lengthUs = m.endUs - start;
        if (acc.gameEnd == RewindFormat.GAME_END_WIN) {
            m.result = RecordingMeta.RESULT_WIN;
        } else if (acc.gameEnd == RewindFormat.GAME_END_LOSS) {
            m.result = RecordingMeta.RESULT_LOSS;
        } else if (m.result == null) {
            m.result = RecordingMeta.RESULT_UNKNOWN;
        }
        m.keyMarked |= acc.keySeen;
        List<RecordingMeta.Marker> markers = new ArrayList<RecordingMeta.Marker>(acc.markers);
        Collections.sort(markers, BY_TIME);
        m.markers = markers;
        m.segments = segments.size();
        // The stream's latest INFO wins over the cache: INFO records are written with every update (I4).
        if (acc.infoMode != null) m.mode = acc.infoMode;
        if (acc.infoMap != null) m.map = acc.infoMap;
        if (acc.infoLabel != null) m.modeLabel = acc.infoLabel;
        m.rawBytes = raw;
        return m;
    }
}
