package com.bedwarsqol.rewind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Per-game packet statistics, the {@code <id>.stats.json} file (PLAN "What the stats log", Disk format
 * {@code .stats.json}, A15). Numbers and booleans only, under the fixed key schema {@link #KEYS} (arrays
 * hold flat objects with the keys in {@link #ARRAY_ITEM_KEYS}). Nothing here ever holds a string from
 * a packet, a location or a TRACK INFO record: the writer feeds it ids, lengths, times and counts.
 *
 * <p>The accumulator is writer-thread only. {@link Feed} carries what only the capture handler and the
 * client thread see (skipped packets, the Replay|Restrict-style channel flag, timings) without
 * allocation or locks.
 */
public final class PacketStats {

    /** S01/S07 entries kept per file; {@code boundaryCount} still counts every one. */
    public static final int MAX_BOUNDARIES = 256;
    /** Segments listed in {@code headerPackets}. */
    public static final int MAX_HEADER_SEGMENTS = 256;
    private static final int IDS = 256;

    /** Every top-level key, in output order. The file holds exactly these. */
    public static final List<String> KEYS = Collections.unmodifiableList(Arrays.asList(
            "format", "lengthUs", "gameStartUs", "segments",
            "boundaries", "boundaryCount", "packets", "otherPacketCount", "otherPacketBytes", "skipped",
            "headerPackets", "rawBytes", "storedBytes", "restrictChannelSeen",
            "peakPacketsPerSecond", "peakBytesPerSecond",
            "captureTimingMeasured", "trackNsPerTickMean", "trackNsPerTickMax",
            "cameraNsPerFrameMean", "cameraNsPerFrameMax", "writerMsPerMiB", "peakPendingBytes",
            "locations", "lobbyOverridden", "visitsWithoutLocation", "bedwarsVisitsWithoutStart",
            "shortVisitsDropped", "startSource",
            "extRecords", "clientTickNsMean", "clientTickNsMax", "clientFrameNsMean", "clientFrameNsMax",
            "outNsMean", "outNsMax", "handledResyncs", "handledOverflows", "orderUnknownTicks"));

    /**
     * Keys of the objects inside each array-valued key. In {@code locations}, {@code serverTypeCode} is
     * {@link RewindGameRules#serverTypeCode}: 0 absent, 1 BEDWARS, 2 MAIN, 3 TOURNAMENT, 4 SKYBLOCK, 5 HOUSING,
     * 6 other, 7 REPLAY (Hypixel's replay viewer, always {@code verdict} 1; files written before R2 log it as 6).
     * In {@code extRecords} (format v2), {@code tag} is a TRACK extension tag ({@code RewindFormat.TAG_*} 0x20 and up),
     * {@code count} its records and {@code bytes} their body bytes (length prefix, tag and time not included).
     */
    public static final Map<String, List<String>> ARRAY_ITEM_KEYS;

    static {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        m.put("boundaries", Collections.unmodifiableList(Arrays.asList("tUs", "id", "dimension", "rebuilt")));
        m.put("packets", Collections.unmodifiableList(Arrays.asList("id", "count", "bytes")));
        m.put("skipped", Collections.unmodifiableList(Arrays.asList("id", "count", "bytes")));
        m.put("headerPackets", Collections.unmodifiableList(Arrays.asList("segment", "source0", "source1")));
        m.put("locations", Collections.unmodifiableList(Arrays.asList("segment", "dtUsFromBoundary",
                "hasServerType", "serverTypeCode", "hasLobbyName", "hasMode", "hasMap", "verdict")));
        m.put("extRecords", Collections.unmodifiableList(Arrays.asList("tag", "count", "bytes")));
        ARRAY_ITEM_KEYS = Collections.unmodifiableMap(m);
    }

    private final long[] count = new long[IDS];
    private final long[] bytes = new long[IDS];
    private long otherCount;
    private long otherBytes;
    private final long[] skippedCount = new long[IDS];
    private final long[] skippedBytes = new long[IDS];

    private final List<long[]> boundaries = new ArrayList<long[]>(); // {tUs, id, dimension, rebuilt 0/1}
    private long boundaryCount;
    private boolean dimensionKnown;
    private int dimension;

    private final TreeMap<Integer, long[]> headers = new TreeMap<Integer, long[]>(); // segment -> {src0, src1}

    private long second = Long.MIN_VALUE;
    private long secondPackets;
    private long secondBytes;
    private long peakPackets;
    private long peakBytes;

    private long lengthUs;
    private long gameStartUs;
    private int segments;
    private long rawBytes;
    private long storedBytes;
    private boolean restrictChannelSeen;
    private final long[] timings = new long[4]; // track mean,max; camera mean,max
    private final long[] clientTimings = new long[6]; // client tick mean,max; frame mean,max; out mean,max
    private final long[] extCount = new long[IDS];
    private final long[] extBytes = new long[IDS];
    private long handledResyncs;
    private long handledOverflows;
    private long orderUnknownTicks;
    private int format = RewindFormat.FORMAT_VERSION;
    private long writerNs;
    private long writerRawBytes;
    private long peakPendingBytes;
    private RewindGameRules.RunStats runStats;

    // ---------------------------------------------------------------- feeding

    /** One stored NET record {@code b[off, off+len)} (varint id + body) at {@code tUs}. */
    public void addNet(long tUs, byte[] b, int off, int len) {
        int id = RewindFormat.packetId(b, off, len);
        if (id >= 0 && id < IDS) {
            count[id]++;
            bytes[id] += len;
        } else {
            otherCount++;
            otherBytes += len;
        }
        if (id == RewindFormat.PACKET_JOIN_GAME && len >= 7) {
            boundary(tUs, id, b[off + 6], true);
        } else if (id == RewindFormat.PACKET_RESPAWN && len >= 5) {
            int dim = RewindFormat.getInt(b, off + 1);
            boundary(tUs, id, dim, !dimensionKnown || dim != dimension);
        }
        long s = Math.floorDiv(tUs, 1_000_000L);
        if (s != second) {
            closeSecond();
            second = s;
        }
        secondPackets++;
        secondBytes += len;
    }

    private void boundary(long tUs, int id, int dim, boolean rebuilt) {
        boundaryCount++;
        if (boundaries.size() < MAX_BOUNDARIES) {
            boundaries.add(new long[]{tUs, id, dim, rebuilt ? 1 : 0});
        }
        dimension = dim;
        dimensionKnown = true;
    }

    private void closeSecond() {
        peakPackets = Math.max(peakPackets, secondPackets);
        peakBytes = Math.max(peakBytes, secondBytes);
        secondPackets = 0;
        secondBytes = 0;
    }

    /** A HEADER block for {@code segment} from {@code source} holding {@code packets} packets. */
    public void addHeader(int segment, int source, int packets) {
        long[] h = headers.get(segment);
        if (h == null) {
            if (headers.size() >= MAX_HEADER_SEGMENTS) {
                return;
            }
            h = new long[2];
            headers.put(segment, h);
        }
        h[source == RewindFormat.SOURCE_CAPTURE_CARRY ? 1 : 0] += packets;
    }

    public void addSkipped(int id, long n, long b) {
        if (id >= 0 && id < IDS) {
            skippedCount[id] += n;
            skippedBytes[id] += b;
        }
    }

    /**
     * Adds {@code o} (a segment's stats as it joins a game) to this one. Every accumulated field is carried: counts
     * and bytes, boundaries, headers, peaks, the restrict flag, writer work, raw and stored bytes, the pending
     * peak (code review 2, O1) and the extension record counts. The game-level values that {@code finish} sets once
     * for the whole file (length, start, segments, timings, handled-order counters, rules stats, format) are not
     * merged.
     */
    public void merge(PacketStats o) {
        for (int i = 0; i < IDS; i++) {
            count[i] += o.count[i];
            bytes[i] += o.bytes[i];
            skippedCount[i] += o.skippedCount[i];
            skippedBytes[i] += o.skippedBytes[i];
        }
        otherCount += o.otherCount;
        otherBytes += o.otherBytes;
        for (long[] b : o.boundaries) {
            if (boundaries.size() < MAX_BOUNDARIES) {
                boundaries.add(b.clone());
            }
        }
        boundaryCount += o.boundaryCount;
        if (o.dimensionKnown) {
            dimensionKnown = true;
            dimension = o.dimension;
        }
        for (Map.Entry<Integer, long[]> e : o.headers.entrySet()) {
            long[] h = headers.get(e.getKey());
            if (h == null) {
                if (headers.size() >= MAX_HEADER_SEGMENTS) {
                    continue;
                }
                h = new long[2];
                headers.put(e.getKey(), h);
            }
            h[0] += e.getValue()[0];
            h[1] += e.getValue()[1];
        }
        // Segments are time-disjoint: a peak straddling the join is approximated by the larger side.
        peakPackets = Math.max(peakPackets, Math.max(o.peakPackets, o.secondPackets));
        peakBytes = Math.max(peakBytes, Math.max(o.peakBytes, o.secondBytes));
        restrictChannelSeen |= o.restrictChannelSeen;
        writerNs += o.writerNs;
        writerRawBytes += o.writerRawBytes;
        rawBytes += o.rawBytes;
        storedBytes += o.storedBytes;
        peakPendingBytes = Math.max(peakPendingBytes, o.peakPendingBytes);
        for (int i = 0; i < IDS; i++) {
            extCount[i] += o.extCount[i];
            extBytes[i] += o.extBytes[i];
        }
    }

    public void setGame(long gameStartUs, long lengthUs) {
        this.gameStartUs = gameStartUs;
        this.lengthUs = lengthUs;
    }

    public void setSegments(int segments) {
        this.segments = segments;
    }

    public void setBytes(long raw, long stored) {
        this.rawBytes = raw;
        this.storedBytes = stored;
    }

    public void setRestrictChannelSeen(boolean seen) {
        this.restrictChannelSeen |= seen;
    }

    /**
     * Mean and max ns of the self track per tick and the camera per frame. The capture handler is not timed
     * per packet (a second clock read per packet); the file says so with {@code captureTimingMeasured:false}.
     */
    public void setTimings(long trackMean, long trackMax, long cameraMean, long cameraMax) {
        timings[0] = trackMean;
        timings[1] = trackMax;
        timings[2] = cameraMean;
        timings[3] = cameraMax;
    }

    /** Writer time spent compressing and writing {@code raw} payload bytes. */
    public void addWriterWork(long ns, long raw) {
        writerNs += ns;
        writerRawBytes += raw;
    }

    public void setPeakPendingBytes(long b) {
        peakPendingBytes = Math.max(peakPendingBytes, b);
    }

    /** The rules' location log and counters; only their numbers and booleans are written. */
    public void setRunStats(RewindGameRules.RunStats s) {
        runStats = s;
    }

    /** One extension TRACK record (format v2) of {@code tag} with a body of {@code bodyBytes}. */
    public void addExt(int tag, int bodyBytes) {
        if (tag >= 0 && tag < IDS) {
            extCount[tag]++;
            extBytes[tag] += bodyBytes;
        }
    }

    /** The recording's format version; a recovered prefix keeps its own (default {@link RewindFormat#FORMAT_VERSION}). */
    public void setFormat(int format) {
        this.format = format;
    }

    /** Mean and max ns of the v2 capture per client tick, per frame and per stored outbound packet. */
    public void setClientTimings(long tickMean, long tickMax, long frameMean, long frameMax, long outMean,
                                 long outMax) {
        clientTimings[0] = tickMean;
        clientTimings[1] = tickMax;
        clientTimings[2] = frameMean;
        clientTimings[3] = frameMax;
        clientTimings[4] = outMean;
        clientTimings[5] = outMax;
    }

    /** Handled-order counters of a game (PLAN "Handled order"): packet-id resyncs, ring overflows, unknown ticks. */
    public void setHandledOrder(long resyncs, long overflows, long unknownTicks) {
        handledResyncs = resyncs;
        handledOverflows = overflows;
        orderUnknownTicks = unknownTicks;
    }

    // ---------------------------------------------------------------- reading (tests, writer)

    public long count(int id) {
        return id >= 0 && id < IDS ? count[id] : 0;
    }

    public long bytes(int id) {
        return id >= 0 && id < IDS ? bytes[id] : 0;
    }

    public long boundaryCount() {
        return boundaryCount;
    }

    public long extCount(int tag) {
        return tag >= 0 && tag < IDS ? extCount[tag] : 0;
    }

    public long extBytes(int tag) {
        return tag >= 0 && tag < IDS ? extBytes[tag] : 0;
    }

    public long netRecords() {
        long n = otherCount;
        for (long c : count) {
            n += c;
        }
        return n;
    }

    // ---------------------------------------------------------------- output

    /** The {@code .stats.json} text: every key of {@link #KEYS}, numbers and booleans only. */
    public String toJson() {
        long pp = Math.max(peakPackets, secondPackets);
        long pb = Math.max(peakBytes, secondBytes);
        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        num(sb, "format", format);
        num(sb, "lengthUs", lengthUs);
        num(sb, "gameStartUs", gameStartUs);
        num(sb, "segments", segments);
        key(sb, "boundaries").append('[');
        for (int i = 0; i < boundaries.size(); i++) {
            long[] b = boundaries.get(i);
            if (i > 0) sb.append(',');
            sb.append('{');
            num(sb, "tUs", b[0]);
            num(sb, "id", b[1]);
            num(sb, "dimension", b[2]);
            bool(sb, "rebuilt", b[3] != 0);
            end(sb).append('}');
        }
        sb.append("],");
        num(sb, "boundaryCount", boundaryCount);
        counts(sb, "packets", count, bytes);
        num(sb, "otherPacketCount", otherCount);
        num(sb, "otherPacketBytes", otherBytes);
        counts(sb, "skipped", skippedCount, skippedBytes);
        key(sb, "headerPackets").append('[');
        boolean first = true;
        for (Map.Entry<Integer, long[]> e : headers.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('{');
            num(sb, "segment", e.getKey());
            num(sb, "source0", e.getValue()[0]);
            num(sb, "source1", e.getValue()[1]);
            end(sb).append('}');
        }
        sb.append("],");
        num(sb, "rawBytes", rawBytes);
        num(sb, "storedBytes", storedBytes);
        bool(sb, "restrictChannelSeen", restrictChannelSeen);
        num(sb, "peakPacketsPerSecond", pp);
        num(sb, "peakBytesPerSecond", pb);
        bool(sb, "captureTimingMeasured", false); // code review 1, O1: never measured, so no number
        num(sb, "trackNsPerTickMean", timings[0]);
        num(sb, "trackNsPerTickMax", timings[1]);
        num(sb, "cameraNsPerFrameMean", timings[2]);
        num(sb, "cameraNsPerFrameMax", timings[3]);
        double msPerMiB = writerRawBytes > 0 ? (writerNs / 1e6) / (writerRawBytes / 1048576.0) : 0.0;
        key(sb, "writerMsPerMiB").append(Double.isNaN(msPerMiB) || Double.isInfinite(msPerMiB)
                ? "0.0" : Double.toString(Math.round(msPerMiB * 1000.0) / 1000.0)).append(',');
        num(sb, "peakPendingBytes", peakPendingBytes);
        RewindGameRules.RunStats r = runStats;
        key(sb, "locations").append('[');
        if (r != null) {
            for (int i = 0; i < r.locations.size(); i++) {
                RewindGameRules.LocationLogEntry l = r.locations.get(i);
                if (i > 0) sb.append(',');
                sb.append('{');
                num(sb, "segment", l.segment);
                num(sb, "dtUsFromBoundary", l.dtUsFromBoundary);
                bool(sb, "hasServerType", l.hasServerType);
                num(sb, "serverTypeCode", l.serverTypeCode);
                bool(sb, "hasLobbyName", l.hasLobbyName);
                bool(sb, "hasMode", l.hasMode);
                bool(sb, "hasMap", l.hasMap);
                num(sb, "verdict", l.verdict);
                end(sb).append('}');
            }
        }
        sb.append("],");
        num(sb, "lobbyOverridden", r != null ? r.lobbyOverridden : 0);
        num(sb, "visitsWithoutLocation", r != null ? r.visitsWithoutLocation : 0);
        num(sb, "bedwarsVisitsWithoutStart", r != null ? r.bedwarsVisitsWithoutStart : 0);
        num(sb, "shortVisitsDropped", r != null ? r.shortVisitsDropped : 0);
        num(sb, "startSource", r != null && r.startSource != null ? r.startSource.code : 0);
        key(sb, "extRecords").append('[');
        boolean firstExt = true;
        for (int i = 0; i < IDS; i++) {
            if (extCount[i] == 0 && extBytes[i] == 0) continue;
            if (!firstExt) sb.append(',');
            firstExt = false;
            sb.append('{');
            num(sb, "tag", i);
            num(sb, "count", extCount[i]);
            num(sb, "bytes", extBytes[i]);
            end(sb).append('}');
        }
        sb.append("],");
        num(sb, "clientTickNsMean", clientTimings[0]);
        num(sb, "clientTickNsMax", clientTimings[1]);
        num(sb, "clientFrameNsMean", clientTimings[2]);
        num(sb, "clientFrameNsMax", clientTimings[3]);
        num(sb, "outNsMean", clientTimings[4]);
        num(sb, "outNsMax", clientTimings[5]);
        num(sb, "handledResyncs", handledResyncs);
        num(sb, "handledOverflows", handledOverflows);
        num(sb, "orderUnknownTicks", orderUnknownTicks);
        end(sb).append('}');
        return sb.toString();
    }

    private static void counts(StringBuilder sb, String name, long[] c, long[] b) {
        key(sb, name).append('[');
        boolean first = true;
        for (int i = 0; i < IDS; i++) {
            if (c[i] == 0 && b[i] == 0) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('{');
            num(sb, "id", i);
            num(sb, "count", c[i]);
            num(sb, "bytes", b[i]);
            end(sb).append('}');
        }
        sb.append("],");
    }

    // Keys are compile-time constants from the schema; values are numbers or booleans only.
    private static StringBuilder key(StringBuilder sb, String k) {
        return sb.append('"').append(k).append("\":");
    }

    private static void num(StringBuilder sb, String k, long v) {
        key(sb, k).append(v).append(',');
    }

    private static void bool(StringBuilder sb, String k, boolean v) {
        key(sb, k).append(v).append(',');
    }

    /** Drops the trailing comma of the last member. */
    private static StringBuilder end(StringBuilder sb) {
        int last = sb.length() - 1;
        if (last >= 0 && sb.charAt(last) == ',') {
            sb.setLength(last);
        }
        return sb;
    }

    // ---------------------------------------------------------------- live inputs

    /**
     * A single-writer timing counter (count, total ns, max ns) for one thread: {@link #add} does three
     * ordered stores and no allocation. The writer reads it without locks and asks for the max to be
     * reset per game through a request counter the owner thread applies on its next {@link #add}.
     */
    public static final class Timer {
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong totalNs = new AtomicLong();
        private final AtomicLong maxNs = new AtomicLong();
        private final AtomicInteger resetRequests = new AtomicInteger();
        private int resetsSeen; // owner thread only

        /** Owner thread: one measured operation of {@code ns}. */
        public void add(long ns) {
            int r = resetRequests.get();
            if (r != resetsSeen) {
                resetsSeen = r;
                maxNs.lazySet(0);
            }
            count.lazySet(count.get() + 1);
            totalNs.lazySet(totalNs.get() + ns);
            if (ns > maxNs.get()) {
                maxNs.lazySet(ns);
            }
        }

        long count() {
            return count.get();
        }

        long totalNs() {
            return totalNs.get();
        }

        long maxNs() {
            return maxNs.get();
        }

        /** Writer: the next {@link #add} starts a new max. */
        void requestReset() {
            resetRequests.incrementAndGet();
        }
    }

    /**
     * What the writer cannot see in chunks: packets the capture handler skipped (S00, S3F, S46), whether
     * a Replay|Restrict-style channel was seen, the self-track and camera timings, and (format v2) the capture
     * timings per tick, frame and outbound packet and the handled-order counters. Each method is for
     * one owner thread (capture handler: {@link #skipped}, {@link #restrictChannelSeen}, {@link #handledOverflow};
     * client thread: the timers, {@link #handledResync}, {@link #orderUnknownTick}); no allocation, no locks.
     * Cumulative; the writer takes per-game differences.
     */
    public static final class Feed {
        private final AtomicLongArray skippedCount = new AtomicLongArray(IDS);
        private final AtomicLongArray skippedBytes = new AtomicLongArray(IDS);
        private final AtomicLong restrictSeen = new AtomicLong();
        public final Timer track = new Timer();
        public final Timer camera = new Timer();
        /** Client thread: the v2 capture work of one client tick (format v2, A13). */
        public final Timer clientTick = new Timer();
        /** Client thread: the v2 capture work of one frame. */
        public final Timer clientFrame = new Timer();
        /** Client thread: storing one outbound packet (OUT). */
        public final Timer out = new Timer();
        private final AtomicLong handledResyncs = new AtomicLong();
        private final AtomicLong handledOverflows = new AtomicLong();
        private final AtomicLong orderUnknownTicks = new AtomicLong();

        /** Client thread: a handled-order pop had to skip entries to find the running packet's id. */
        public void handledResync() {
            handledResyncs.lazySet(handledResyncs.get() + 1);
        }

        /** Capture thread: the handled-order ring was full and an entry could not be pushed. */
        public void handledOverflow() {
            handledOverflows.lazySet(handledOverflows.get() + 1);
        }

        /** Client thread: a TICK_HEAD was written with the order marked unknown. */
        public void orderUnknownTick() {
            orderUnknownTicks.lazySet(orderUnknownTicks.get() + 1);
        }

        /** Capture thread: a skipped packet {@code id} of {@code len} bytes. */
        public void skipped(int id, int len) {
            if (id < 0 || id >= IDS) {
                return;
            }
            skippedCount.lazySet(id, skippedCount.get(id) + 1);
            skippedBytes.lazySet(id, skippedBytes.get(id) + len);
        }

        /** Capture thread: a Replay|Restrict-style channel name went past. */
        public void restrictChannelSeen() {
            restrictSeen.lazySet(restrictSeen.get() + 1);
        }

        long skippedCount(int id) {
            return skippedCount.get(id);
        }

        long skippedBytes(int id) {
            return skippedBytes.get(id);
        }

        long restrictSeen() {
            return restrictSeen.get();
        }

        Timer[] timers() {
            return new Timer[]{track, camera, clientTick, clientFrame, out};
        }

        long[] handledCounters() {
            return new long[]{handledResyncs.get(), handledOverflows.get(), orderUnknownTicks.get()};
        }
    }

    /** Writer side: a {@link Feed}'s values at the start of a stats window. */
    static final class FeedWindow {
        private final long[] skippedCount = new long[IDS];
        private final long[] skippedBytes = new long[IDS];
        private long restrict;
        private static final int TIMERS = 5;
        private final long[] timerCount = new long[TIMERS];
        private final long[] timerTotal = new long[TIMERS];
        private final long[] handled = new long[3];

        /** Starts a new window at the feed's current values. */
        void reset(Feed f) {
            if (f == null) {
                return;
            }
            for (int i = 0; i < IDS; i++) {
                skippedCount[i] = f.skippedCount(i);
                skippedBytes[i] = f.skippedBytes(i);
            }
            restrict = f.restrictSeen();
            Timer[] t = f.timers();
            for (int i = 0; i < TIMERS; i++) {
                timerCount[i] = t[i].count();
                timerTotal[i] = t[i].totalNs();
                t[i].requestReset();
            }
            long[] h = f.handledCounters();
            System.arraycopy(h, 0, handled, 0, handled.length);
        }

        /** Starts a window at zero: for a fresh feed whose counts all belong to the current window. */
        void zero() {
            Arrays.fill(skippedCount, 0);
            Arrays.fill(skippedBytes, 0);
            restrict = 0;
            Arrays.fill(timerCount, 0);
            Arrays.fill(timerTotal, 0);
            Arrays.fill(handled, 0);
        }

        /** Adds what the feed gathered since {@link #reset} to {@code s}. */
        void applyTo(Feed f, PacketStats s) {
            if (f == null) {
                return;
            }
            for (int i = 0; i < IDS; i++) {
                long c = f.skippedCount(i) - skippedCount[i];
                long b = f.skippedBytes(i) - skippedBytes[i];
                if (c > 0 || b > 0) {
                    s.addSkipped(i, c, b);
                }
            }
            s.setRestrictChannelSeen(f.restrictSeen() > restrict);
            Timer[] t = f.timers();
            long[] v = new long[2 * TIMERS];
            for (int i = 0; i < TIMERS; i++) {
                long n = t[i].count() - timerCount[i];
                long total = t[i].totalNs() - timerTotal[i];
                v[2 * i] = n > 0 ? total / n : 0;
                v[2 * i + 1] = n > 0 ? t[i].maxNs() : 0;
            }
            s.setTimings(v[0], v[1], v[2], v[3]);
            s.setClientTimings(v[4], v[5], v[6], v[7], v[8], v[9]);
            long[] h = f.handledCounters();
            s.setHandledOrder(h[0] - handled[0], h[1] - handled[1], h[2] - handled[2]);
        }
    }
}
