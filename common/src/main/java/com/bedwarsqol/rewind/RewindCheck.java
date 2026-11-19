package com.bedwarsqol.rewind;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The reader check for gate G6 and the cobdev {@code rewind-latest} probe: reads a {@code .cbr} and
 * prints one line of counts. JDK only (no Gson), so it runs as
 * {@code java -cp <mod jar> com.bedwarsqol.rewind.RewindCheck <file.cbr>}.
 *
 * <p>The line holds numbers and booleans only: never a packet body, name, chat line, map, server or
 * the file path. For format v2 it also gives the version and a count per extension tag ({@code ext<Name>=n},
 * {@code extUnknown=n}); extension records add no pass criterion.
 *
 * <p>Exit 0 only if the recording is complete, has no torn tail, holds at least one NET record, one
 * TICK and one KEY marker, and every segment's first NET record is S01 or S07. Otherwise the exit code
 * is the OR of the {@code FAIL_*} bits that apply; {@link #EXIT_UNREADABLE} means no file, an I/O
 * error, an unknown format version or bad usage.
 */
public final class RewindCheck {

    public static final int FAIL_INCOMPLETE = 1;
    public static final int FAIL_TORN_TAIL = 1 << 1;
    public static final int FAIL_NO_NET = 1 << 2;
    public static final int FAIL_NO_TICK = 1 << 3;
    public static final int FAIL_NO_KEY = 1 << 4;
    public static final int FAIL_SEGMENT_BOUNDARY = 1 << 5;
    public static final int EXIT_UNREADABLE = 64;

    private RewindCheck() {
    }

    /** Counts from one recording. */
    public static final class Summary {
        public int blocks;
        public boolean tornTail;
        public boolean complete;
        /** Distinct segment numbers among NET, TRACK and HEADER blocks. */
        public int segments;
        /** Segments whose first NET record is S01 or S07. */
        public int segmentsStartingAtBoundary;
        public int headerSource0;
        public int headerSource1;
        public long netRecords;
        public long trackTicks;
        public long cameraSamples;
        public int markerKey;
        public int markerDeath;
        public int markerFinalKill;
        public int markerBedBroken;
        public int markerBedLost;
        public boolean unsupportedVersion;
        /** The file header's format version (0 if the header could not be read). */
        public int formatVersion;
        /** Extension records ({@code tag >= 0x20}) whose tag this build has no name for. */
        public long extUnknown;
        private final long[] ext = new long[RewindFormat.EXT_TAGS.length];

        /** Records of a known extension tag (format v2); 0 for any other tag. */
        public long extCount(int tag) {
            int i = extIndex(tag);
            return i >= 0 ? ext[i] : 0;
        }

        void countExt(int tag) {
            int i = extIndex(tag);
            if (i >= 0) {
                ext[i]++;
            } else {
                extUnknown++;
            }
        }

        private static int extIndex(int tag) {
            for (int i = 0; i < RewindFormat.EXT_TAGS.length; i++) {
                if (RewindFormat.EXT_TAGS[i] == tag) {
                    return i;
                }
            }
            return -1;
        }

        public int exitCode() {
            if (unsupportedVersion) {
                return EXIT_UNREADABLE;
            }
            int code = 0;
            if (!complete) {
                code |= FAIL_INCOMPLETE;
            }
            if (tornTail) {
                code |= FAIL_TORN_TAIL;
            }
            if (netRecords < 1) {
                code |= FAIL_NO_NET;
            }
            if (trackTicks < 1) {
                code |= FAIL_NO_TICK;
            }
            if (markerKey < 1) {
                code |= FAIL_NO_KEY;
            }
            if (segmentsStartingAtBoundary != segments) {
                code |= FAIL_SEGMENT_BOUNDARY;
            }
            return code;
        }

        public String line() {
            StringBuilder exts = new StringBuilder();
            for (int i = 0; i < RewindFormat.EXT_TAGS.length; i++) {
                exts.append(" ext").append(RewindFormat.extName(RewindFormat.EXT_TAGS[i])).append('=').append(ext[i]);
            }
            return "blocks=" + blocks
                    + " tornTail=" + tornTail
                    + " complete=" + complete
                    + " segments=" + segments
                    + " segmentsStartingAtBoundary=" + segmentsStartingAtBoundary
                    + " headerSource0=" + headerSource0
                    + " headerSource1=" + headerSource1
                    + " netRecords=" + netRecords
                    + " trackTicks=" + trackTicks
                    + " cameraSamples=" + cameraSamples
                    + " markerKey=" + markerKey
                    + " markerDeath=" + markerDeath
                    + " markerFinalKill=" + markerFinalKill
                    + " markerBedBroken=" + markerBedBroken
                    + " markerBedLost=" + markerBedLost
                    + " formatVersion=" + formatVersion
                    + exts
                    + " extUnknown=" + extUnknown
                    + " unsupportedVersion=" + unsupportedVersion
                    + " pass=" + (exitCode() == 0);
        }
    }

    /** Reads the whole recording, one block at a time, and counts. */
    public static Summary summarize(File file) throws IOException {
        final Summary s = new Summary();
        // segment -> first NET record is a boundary (null = segment seen, no NET record yet)
        final Map<Integer, Boolean> firstNet = new HashMap<Integer, Boolean>();
        RecordingReader r = RecordingReader.open(file);
        try {
            s.unsupportedVersion = r.unsupportedVersion();
            s.formatVersion = r.header() != null ? r.header().formatVersion : 0;
            RecordingReader.Block b;
            while ((b = r.next()) != null) {
                s.blocks++;
                final int seg = b.segment;
                switch (b.type) {
                    case RewindFormat.BLOCK_NET:
                        if (!firstNet.containsKey(seg)) {
                            firstNet.put(seg, null);
                        }
                        RecordingReader.walkNet(b.payload, 0, b.payload.length, b.firstTimeUs,
                                new RecordingReader.NetVisitor() {
                                    @Override
                                    public void net(long timeUs, byte[] buf, int off, int len) {
                                        s.netRecords++;
                                        if (firstNet.get(seg) == null) {
                                            int id = RewindFormat.packetId(buf, off, len);
                                            firstNet.put(seg, id == RewindFormat.PACKET_JOIN_GAME
                                                    || id == RewindFormat.PACKET_RESPAWN);
                                        }
                                    }
                                });
                        break;
                    case RewindFormat.BLOCK_TRACK:
                        if (!firstNet.containsKey(seg)) {
                            firstNet.put(seg, null);
                        }
                        RecordingReader.walkTrack(b.payload, 0, b.payload.length, b.firstTimeUs,
                                new RecordingReader.TrackVisitor() {
                                    @Override
                                    public void track(long timeUs, int tag, byte[] buf, int bodyOff, int bodyLen) {
                                        count(s, tag, buf, bodyOff);
                                    }
                                });
                        break;
                    case RewindFormat.BLOCK_HEADER:
                        if (!firstNet.containsKey(seg)) {
                            firstNet.put(seg, null);
                        }
                        if (RecordingReader.headerPayload(b).source == RewindFormat.SOURCE_CLIENT_SNAPSHOT) {
                            s.headerSource0++;
                        } else {
                            s.headerSource1++;
                        }
                        break;
                    default:
                        break;
                }
            }
            s.tornTail = r.tornTail();
            s.complete = r.complete();
        } finally {
            r.close();
        }
        s.segments = firstNet.size();
        for (Boolean v : firstNet.values()) {
            if (Boolean.TRUE.equals(v)) {
                s.segmentsStartingAtBoundary++;
            }
        }
        return s;
    }

    private static void count(Summary s, int tag, byte[] buf, int bodyOff) {
        switch (tag) {
            case RewindFormat.TAG_TICK:
                s.trackTicks++;
                break;
            case RewindFormat.TAG_CAMERA:
                s.cameraSamples++;
                break;
            case RewindFormat.TAG_MARKER:
                switch (buf[bodyOff]) {
                    case RewindFormat.MARKER_KEY:
                        s.markerKey++;
                        break;
                    case RewindFormat.MARKER_DEATH:
                        s.markerDeath++;
                        break;
                    case RewindFormat.MARKER_FINAL_KILL:
                        s.markerFinalKill++;
                        break;
                    case RewindFormat.MARKER_BED_BROKEN:
                        s.markerBedBroken++;
                        break;
                    case RewindFormat.MARKER_BED_LOST:
                        s.markerBedLost++;
                        break;
                    default:
                        break;
                }
                break;
            default:
                if (RewindFormat.isExtensionTag(tag)) {
                    s.countExt(tag);
                }
                break;
        }
    }

    /** Prints the summary line of {@code args[0]} and returns the exit code. */
    public static int run(String[] args, PrintStream out) {
        if (args == null || args.length != 1) {
            out.println("usage: RewindCheck <recording.cbr>");
            return EXIT_UNREADABLE;
        }
        File f = new File(args[0]);
        if (!f.isFile()) {
            out.println("unreadable=true pass=false");
            return EXIT_UNREADABLE;
        }
        Summary s;
        try {
            s = summarize(f);
        } catch (IOException | RuntimeException e) {
            out.println("unreadable=true pass=false");
            return EXIT_UNREADABLE;
        }
        out.println(s.line());
        return s.exitCode();
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }
}
