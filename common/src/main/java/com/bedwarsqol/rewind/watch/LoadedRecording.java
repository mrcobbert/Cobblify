package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.BlockCodec;
import com.bedwarsqol.rewind.RecordingMeta;
import com.bedwarsqol.rewind.RecordingReader;
import com.bedwarsqol.rewind.RewindFormat;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * A {@code .cbr} recording loaded for playback (format v1, {@link RewindFormat}).
 *
 * <p>{@link #load} makes one pass with R1's {@link RecordingReader} (validation, torn-tail stop: a torn or recovered
 * file plays its complete prefix). It keeps each NET block's segment, first and last record time, record count, file
 * offset and its <b>stored</b> (still compressed) bytes, then closes the file, so a prune or delete during playback
 * cannot hurt. HEADER, TRACK and END are decoded at once; NET blocks are inflated on demand by {@link NetCursor}
 * through a two-block cache.
 *
 * <p><b>Segments.</b> Segments play in ascending number. The <b>start segment</b> is the first one with a HEADER
 * source 0 (normally the file's first); segments before it cannot be played and are skipped. {@link #segments()}
 * lists the start segment and every later one. TRACK records of skipped segments are left out of the self track;
 * GAME_START and MARKER records count from every segment.
 *
 * <p>{@link #problem()} is null for a playable recording, or the first of: {@link Problem#UNREADABLE} (no file, no
 * valid file header, an I/O error), {@link Problem#UNSUPPORTED_VERSION}, {@link Problem#NO_NET} (no NET record at all,
 * or none from the start segment on), {@link Problem#NO_HEADER} (no segment has a source-0 header) and
 * {@link Problem#START_OUTSIDE_DATA} (the game start lies before the origin or after the last NET record).
 *
 * <p>Loading runs on any thread; the result may then be handed to the client thread. {@link NetCursor}s of one
 * recording share its cache and are meant for one thread at a time.
 */
public final class LoadedRecording {

    public enum Problem {
        UNREADABLE, UNSUPPORTED_VERSION, NO_NET, NO_HEADER, START_OUTSIDE_DATA
    }

    /** One NET block kept for playback. */
    static final class NetBlock {
        final int segmentIndex;
        final int segment;
        /** The block header's base time: record 0 is at this plus its {@code dtUs}. */
        final long firstTimeUs;
        final long firstRecordUs;
        final int firstPacketId;
        final long lastTimeUs;
        final int records;
        final int codec;
        final int rawLength;
        final long fileOffset;
        /** The payload as stored in the file (deflated unless {@link #codec} is stored); null after a quick check. */
        final byte[] stored;

        NetBlock(int segmentIndex, int segment, long firstTimeUs, long firstRecordUs, int firstPacketId,
                 long lastTimeUs, int records, int codec, int rawLength, long fileOffset, byte[] stored) {
            this.segmentIndex = segmentIndex;
            this.segment = segment;
            this.firstTimeUs = firstTimeUs;
            this.firstRecordUs = firstRecordUs;
            this.firstPacketId = firstPacketId;
            this.lastTimeUs = lastTimeUs;
            this.records = records;
            this.codec = codec;
            this.rawLength = rawLength;
            this.fileOffset = fileOffset;
            this.stored = stored;
        }
    }

    /** A segment of the recording: its headers and a summary of its NET records. */
    public static final class Segment {
        private final int number;
        private final List<byte[]> header0;
        private final List<byte[]> header1;
        private final int netBlocks;
        private final long netRecords;
        private final long firstNetTimeUs;
        private final long lastNetTimeUs;
        private final int firstPacketId;

        Segment(int number, List<byte[]> header0, List<byte[]> header1, int netBlocks, long netRecords,
                long firstNetTimeUs, long lastNetTimeUs, int firstPacketId) {
            this.number = number;
            this.header0 = header0;
            this.header1 = header1;
            this.netBlocks = netBlocks;
            this.netRecords = netRecords;
            this.firstNetTimeUs = firstNetTimeUs;
            this.lastNetTimeUs = lastNetTimeUs;
            this.firstPacketId = firstPacketId;
        }

        /** The segment number in the file. */
        public int number() {
            return number;
        }

        /**
         * The packets of HEADER {@code source} ({@code RewindFormat.SOURCE_*}), each varint packet id + body, in
         * feed order; empty when the segment has none. Shared and unmodifiable: do not change the arrays.
         */
        public List<byte[]> header(int source) {
            return source == RewindFormat.SOURCE_CLIENT_SNAPSHOT ? header0
                    : source == RewindFormat.SOURCE_CAPTURE_CARRY ? header1 : Collections.<byte[]>emptyList();
        }

        public int netBlockCount() {
            return netBlocks;
        }

        public long netRecordCount() {
            return netRecords;
        }

        /** Time of the segment's first NET record, or {@link Timeline#NONE} without one. */
        public long firstNetTimeUs() {
            return firstNetTimeUs;
        }

        /** Time of the segment's last NET record, or {@link Timeline#NONE} without one. */
        public long lastNetTimeUs() {
            return lastNetTimeUs;
        }

        /** Packet id of the segment's first NET record (its boundary packet), or -1 without one. */
        public int firstPacketId() {
            return firstPacketId;
        }
    }

    private final Problem problem;
    private final boolean tornTail;
    private final RecordingMeta meta;
    private final List<Segment> segments;
    private final int skippedSegments;
    private final NetBlock[] netBlocks;
    private final long netRecords;
    private final long retainedNetBytes;
    private final long rawNetBytes;
    private final SelfTrack selfTrack;
    private final Timeline timeline;
    private final int trackMarkers;

    /** Created on the first inflate, so a quick check or a failed load holds no native zlib state. */
    private BlockCodec codec;
    private final int[] cacheIndex = {-1, -1};
    private final byte[][] cacheRaw = new byte[2][];
    private int cacheNext;
    private boolean closed;

    private LoadedRecording(Problem problem, boolean tornTail, RecordingMeta meta, List<Segment> segments,
                            int skippedSegments, NetBlock[] netBlocks, long netRecords, long retainedNetBytes,
                            long rawNetBytes, SelfTrack selfTrack, Timeline timeline, int trackMarkers) {
        this.problem = problem;
        this.tornTail = tornTail;
        this.meta = meta;
        this.segments = segments;
        this.skippedSegments = skippedSegments;
        this.netBlocks = netBlocks;
        this.netRecords = netRecords;
        this.retainedNetBytes = retainedNetBytes;
        this.rawNetBytes = rawNetBytes;
        this.selfTrack = selfTrack;
        this.timeline = timeline;
        this.trackMarkers = trackMarkers;
    }

    /**
     * Loads {@code cbr} for playback; {@code json} is its {@code .json} metadata (may be null or missing: the END
     * block's metadata is used instead). Never throws for a bad or missing file: see {@link #problem()}.
     */
    public static LoadedRecording load(File cbr, File json) {
        return read(cbr, json, true);
    }

    /** The same checks as {@link #load} without keeping NET bytes: null when the recording can be played. */
    public static Problem quickCheck(File cbr, File json) {
        return read(cbr, json, false).problem;
    }

    /** {@link #quickCheck(File, File)} with the sibling {@code <id>.json}. */
    public static Problem quickCheck(File cbr) {
        String name = cbr.getName();
        String base = name.endsWith(".cbr") ? name.substring(0, name.length() - 4) : name;
        return quickCheck(cbr, new File(cbr.getAbsoluteFile().getParentFile(), base + ".json"));
    }

    public Problem problem() {
        return problem;
    }

    /** True if bytes after the complete prefix were dropped. */
    public boolean tornTail() {
        return tornTail;
    }

    /** The {@code .json} metadata, else the END block's, else null. */
    public RecordingMeta meta() {
        return meta;
    }

    /** The start segment and every later one, in playback order; empty when there is no start segment. */
    public List<Segment> segments() {
        return segments;
    }

    /** The first playable segment, or null. */
    public Segment startSegment() {
        return segments.isEmpty() ? null : segments.get(0);
    }

    /** Segments before the start segment (no source-0 header), which cannot be played. */
    public int skippedSegments() {
        return skippedSegments;
    }

    /** The timeline, or null when the recording has no playable NET record. */
    public Timeline timeline() {
        return timeline;
    }

    /** The recorded player's track over the playable segments (empty when there is none). */
    public SelfTrack selfTrack() {
        return selfTrack;
    }

    /** MARKER records found in the TRACK, before the timeline keeps those inside the game. */
    public int trackMarkerCount() {
        return trackMarkers;
    }

    /** NET records playback can reach. */
    public long netRecordCount() {
        return netRecords;
    }

    public int netBlockCount() {
        return netBlocks.length;
    }

    /** Stored (compressed) NET payload bytes kept in memory. */
    public long retainedNetBytes() {
        return retainedNetBytes;
    }

    /** Inflated size of the kept NET payloads. */
    public long rawNetBytes() {
        return rawNetBytes;
    }

    /** A cursor at the first NET record of the start segment. */
    public NetCursor newCursor() {
        return new NetCursor(this);
    }

    /** Frees the inflater; cursors fail afterwards. */
    public synchronized void close() {
        if (!closed) {
            closed = true;
            if (codec != null) {
                codec.close();
                codec = null;
            }
            cacheRaw[0] = null;
            cacheRaw[1] = null;
        }
    }

    // ---------------- for NetCursor ----------------

    NetBlock[] blocks() {
        return netBlocks;
    }

    /** The inflated payload of NET block {@code i}, from the two-block cache. */
    synchronized byte[] payload(int i) {
        for (int k = 0; k < 2; k++) {
            if (cacheIndex[k] == i) {
                return cacheRaw[k];
            }
        }
        if (closed) {
            throw new IllegalStateException("recording closed");
        }
        NetBlock b = netBlocks[i];
        if (b.stored == null) {
            throw new IllegalStateException("NET bytes were not kept");
        }
        if (b.codec != RewindFormat.CODEC_STORED && codec == null) {
            codec = new BlockCodec();
        }
        byte[] raw = b.codec == RewindFormat.CODEC_STORED ? b.stored
                : codec.inflate(b.stored, 0, b.stored.length, b.rawLength);
        if (raw == null) {
            throw new RewindFormat.FormatException("NET block " + i + " no longer inflates");
        }
        int k = cacheNext;
        cacheNext = 1 - k;
        cacheIndex[k] = i;
        cacheRaw[k] = raw;
        return raw;
    }

    // ---------------- loading ----------------

    /** Copies every byte read into a buffer, so a block's stored bytes can be kept after the reader inflated it. */
    private static final class TeeInputStream extends FilterInputStream {
        byte[] buf = new byte[1 << 16];
        int len;

        TeeInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                ensure(1);
                buf[len++] = (byte) b;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int n) throws IOException {
            int r = super.read(b, off, n);
            if (r > 0) {
                ensure(r);
                System.arraycopy(b, off, buf, len, r);
                len += r;
            }
            return r;
        }

        @Override
        public long skip(long n) throws IOException {
            throw new IOException("skip");
        }

        private void ensure(int n) {
            if (len + n > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(len + n, buf.length * 2));
            }
        }

        void clear() {
            len = 0;
            if (buf.length > (1 << 20)) {
                buf = new byte[1 << 16];
            }
        }
    }

    /** What one pass over the file collects per segment. */
    private static final class SegAcc {
        final int number;
        List<byte[]> header0;
        List<byte[]> header1;
        final List<NetBlock> net = new ArrayList<NetBlock>();
        final List<RecordingReader.Block> track = new ArrayList<RecordingReader.Block>();

        SegAcc(int number) {
            this.number = number;
        }
    }

    private static final class NetSummary implements RecordingReader.NetVisitor {
        int records;
        int firstId = -1;
        long firstUs;

        @Override
        public void net(long timeUs, byte[] buf, int off, int len) {
            if (records++ == 0) {
                firstId = RewindFormat.packetId(buf, off, len);
                firstUs = timeUs;
            }
        }
    }

    private static final Comparator<RecordingReader.Block> BY_FIRST_TIME = new Comparator<RecordingReader.Block>() {
        @Override
        public int compare(RecordingReader.Block a, RecordingReader.Block b) {
            return a.firstTimeUs < b.firstTimeUs ? -1 : a.firstTimeUs > b.firstTimeUs ? 1 : 0;
        }
    };

    private static LoadedRecording failed(Problem p, boolean torn, RecordingMeta meta) {
        return new LoadedRecording(p, torn, meta, Collections.<Segment>emptyList(), 0, new NetBlock[0], 0, 0, 0,
                new SelfTrack.Builder().build(), null, 0);
    }

    private static LoadedRecording read(File cbr, File json, boolean keepNet) {
        RecordingMeta jsonMeta = readJson(json);
        TreeMap<Integer, SegAcc> segs = new TreeMap<Integer, SegAcc>();
        String endJson = null;
        boolean torn;
        long anyNet = 0;
        if (cbr == null || !cbr.isFile()) {
            return failed(Problem.UNREADABLE, false, jsonMeta);
        }
        try {
            TeeInputStream tee = new TeeInputStream(new BufferedInputStream(new FileInputStream(cbr), 1 << 16));
            RecordingReader reader = null;
            try {
                reader = new RecordingReader(tee, cbr.length());
                if (reader.unsupportedVersion()) {
                    return failed(Problem.UNSUPPORTED_VERSION, false, jsonMeta);
                }
                if (reader.header() == null) {
                    return failed(Problem.UNREADABLE, reader.tornTail(), jsonMeta);
                }
                long teeBase = RewindFormat.FILE_HEADER_LENGTH;
                tee.clear();
                RecordingReader.Block b;
                while ((b = reader.next()) != null) {
                    SegAcc acc = segs.get(b.segment);
                    switch (b.type) {
                        case RewindFormat.BLOCK_NET: {
                            if (acc == null) {
                                acc = new SegAcc(b.segment);
                                segs.put(b.segment, acc);
                            }
                            NetSummary sum = new NetSummary();
                            long last = RecordingReader.walkNet(b.payload, 0, b.payload.length, b.firstTimeUs, sum);
                            if (sum.records == 0) {
                                break;
                            }
                            anyNet += sum.records;
                            byte[] stored = null;
                            if (keepNet) {
                                if (b.codec == RewindFormat.CODEC_STORED) {
                                    stored = b.payload;
                                } else {
                                    long from = b.fileOffset + RewindFormat.BLOCK_HEADER_LENGTH - teeBase;
                                    if (from < 0 || from + b.storedLength > tee.len) {
                                        throw new RewindFormat.FormatException("stored bytes not captured");
                                    }
                                    stored = Arrays.copyOfRange(tee.buf, (int) from, (int) from + b.storedLength);
                                }
                            }
                            acc.net.add(new NetBlock(-1, b.segment, b.firstTimeUs, sum.firstUs, sum.firstId, last,
                                    sum.records, b.codec, b.rawLength, b.fileOffset, stored));
                            break;
                        }
                        case RewindFormat.BLOCK_TRACK:
                            if (acc == null) {
                                acc = new SegAcc(b.segment);
                                segs.put(b.segment, acc);
                            }
                            acc.track.add(b);
                            break;
                        case RewindFormat.BLOCK_HEADER: {
                            if (acc == null) {
                                acc = new SegAcc(b.segment);
                                segs.put(b.segment, acc);
                            }
                            RecordingReader.HeaderPayload hp = RecordingReader.headerPayload(b);
                            if (hp.source == RewindFormat.SOURCE_CLIENT_SNAPSHOT && acc.header0 == null) {
                                acc.header0 = hp.packets;
                            } else if (hp.source == RewindFormat.SOURCE_CAPTURE_CARRY && acc.header1 == null) {
                                acc.header1 = hp.packets;
                            }
                            break;
                        }
                        case RewindFormat.BLOCK_END:
                            endJson = RecordingReader.endJson(b);
                            break;
                        default:
                            break; // INDEX: not needed, blocks are read in one pass
                    }
                    teeBase = reader.validLength();
                    tee.clear();
                }
                torn = reader.tornTail();
            } finally {
                if (reader != null) {
                    reader.close();
                }
                tee.close();
            }
        } catch (IOException e) {
            return failed(Problem.UNREADABLE, false, jsonMeta);
        } catch (RewindFormat.FormatException e) {
            return failed(Problem.UNREADABLE, false, jsonMeta);
        }

        RecordingMeta meta = jsonMeta != null ? jsonMeta : RecordingMeta.fromJson(endJson);
        if (anyNet == 0) {
            return failed(Problem.NO_NET, torn, meta);
        }

        // GAME_START and MARKER from every segment's TRACK, in time order.
        long gameStartRecord = Timeline.NONE;
        long backUs = 0;
        List<Timeline.Marker> markers = new ArrayList<Timeline.Marker>();
        List<RecordingReader.Block> allTrack = new ArrayList<RecordingReader.Block>();
        for (SegAcc acc : segs.values()) {
            Collections.sort(acc.track, BY_FIRST_TIME);
            allTrack.addAll(acc.track);
        }
        Collections.sort(allTrack, BY_FIRST_TIME);
        for (RecordingReader.Block tb : allTrack) {
            for (RecordingReader.TrackRecord tr : RecordingReader.trackRecords(tb)) {
                if (tr.tag == RewindFormat.TAG_GAME_START) {
                    if (gameStartRecord == Timeline.NONE || tr.timeUs < gameStartRecord) {
                        gameStartRecord = tr.timeUs;
                        backUs = new RewindFormat.Cursor(tr.body, 0, tr.body.length).varLong();
                    }
                } else if (tr.tag == RewindFormat.TAG_MARKER) {
                    int kind = tr.body[0] & 0xFF;
                    if (RewindFormat.markerName(kind) != null) {
                        markers.add(new Timeline.Marker(tr.timeUs, kind));
                    }
                }
            }
        }

        // Start segment: the first with a source-0 header.
        List<SegAcc> playable = new ArrayList<SegAcc>();
        int skipped = 0;
        for (SegAcc acc : segs.values()) {
            if (playable.isEmpty() && acc.header0 == null) {
                skipped++;
            } else {
                playable.add(acc);
            }
        }
        if (playable.isEmpty()) {
            return failed(Problem.NO_HEADER, torn, meta);
        }

        List<Segment> segments = new ArrayList<Segment>();
        List<NetBlock> blocks = new ArrayList<NetBlock>();
        SelfTrack.Builder track = new SelfTrack.Builder();
        long records = 0;
        long storedBytes = 0;
        long rawBytes = 0;
        long origin = Timeline.NONE;
        long lastNet = Timeline.NONE;
        for (SegAcc acc : playable) {
            int index = segments.size();
            long segRecords = 0;
            long first = Timeline.NONE;
            long last = Timeline.NONE;
            int firstId = -1;
            for (NetBlock nb : acc.net) {
                blocks.add(new NetBlock(index, nb.segment, nb.firstTimeUs, nb.firstRecordUs, nb.firstPacketId,
                        nb.lastTimeUs, nb.records, nb.codec, nb.rawLength, nb.fileOffset, nb.stored));
                segRecords += nb.records;
                last = nb.lastTimeUs;
                rawBytes += nb.rawLength;
                storedBytes += nb.stored != null ? nb.stored.length : 0;
            }
            if (!acc.net.isEmpty()) {
                first = acc.net.get(0).firstRecordUs;
                firstId = acc.net.get(0).firstPacketId;
            }
            if (first != Timeline.NONE) {
                if (origin == Timeline.NONE) {
                    origin = first;
                }
                lastNet = lastNet == Timeline.NONE ? last : Math.max(lastNet, last);
            }
            records += segRecords;
            segments.add(new Segment(acc.number, unmodifiable(acc.header0), unmodifiable(acc.header1),
                    acc.net.size(), segRecords, first, last, firstId));
            track.segment(acc.number);
            for (RecordingReader.Block tb : acc.track) {
                for (RecordingReader.TrackRecord tr : RecordingReader.trackRecords(tb)) {
                    track.record(tr.timeUs, tr.tag, tr.body, 0, tr.body.length);
                }
            }
        }
        if (records == 0) {
            return failed(Problem.NO_NET, torn, meta);
        }
        Timeline timeline = Timeline.build(origin, lastNet, gameStartRecord, backUs, markers, meta);
        Problem problem = timeline.startInsideData() ? null : Problem.START_OUTSIDE_DATA;
        return new LoadedRecording(problem, torn, meta, Collections.unmodifiableList(segments), skipped,
                blocks.toArray(new NetBlock[0]), records, storedBytes, rawBytes, track.build(), timeline,
                markers.size());
    }

    private static List<byte[]> unmodifiable(List<byte[]> l) {
        return l == null ? Collections.<byte[]>emptyList() : Collections.unmodifiableList(l);
    }

    private static RecordingMeta readJson(File json) {
        if (json == null || !json.isFile()) {
            return null;
        }
        try {
            return RecordingMeta.fromJson(new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }
}
