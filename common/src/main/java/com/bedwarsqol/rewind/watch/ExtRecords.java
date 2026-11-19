package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The format-v2 extension records (TRACK tags {@code >= 0x20}) of a loaded recording, as playback reads them
 * (PLAN "B-seam", "Event order", "Tick scheduler"). One flat list over the playable segments, in time order (stable:
 * records that share a time keep their file order), each with its tag, its segment's position in
 * {@link LoadedRecording#segments()} and its body (the bytes after the length prefix, as {@code RecordingReader}
 * hands them over and {@code ExtCodec}'s decoders read them).
 *
 * <p>Three uses, each with its own index so no reader walks records it does not want:
 * <ul>
 *   <li>{@link RewindFormat#TAG_TICK_HEAD}: the tick table of a v2 recording ({@link TickTable#forRecording});</li>
 *   <li>tick-scoped records ({@link Scope#TICK}: OUT, DIG, BLOCK, USE, INV, CONT, HUD, SETTINGS, IDENTITY): the
 *       local phase of the tick they were stamped in ({@link TickFeeder}, {@link PacketSink#local});</li>
 *   <li>time-scoped records ({@link Scope#TIME}: SOUND, TAP, VIEW, MOUSE, CHAT_INPUT, FOV, INPUT): dispatched when
 *       playback time reaches their own stamp ({@link TickClock}, {@link PacketSink#timed}).</li>
 * </ul>
 *
 * <p>A v1 recording has {@link #EMPTY}. Phase 10 fills this from the file while loading; until then it is built in
 * memory ({@link Builder}) and attached with {@link LoadedRecording#withExt}. Immutable after {@link Builder#build};
 * reading allocates nothing.
 */
public final class ExtRecords {

    /** When playback applies a record of a tag. */
    public enum Scope {
        /** In the local phase of the tick whose interval holds its stamp, after that tick's NET records. */
        TICK,
        /** When playback time reaches its stamp, outside the tick feed. */
        TIME,
        /** Not handed to parts (TICK_HEAD: the tick table; an unknown tag). */
        NONE
    }

    /** No extension records (every v1 recording). */
    public static final ExtRecords EMPTY = new Builder().build();

    private final int size;
    private final long[] time;
    private final int[] tag;
    private final int[] segment;
    private final int[] offset;
    private final int[] length;
    private final byte[] data;
    private final int[] local;
    private final int[] timed;
    private final boolean[] present = new boolean[256];

    private ExtRecords(int size, long[] time, int[] tag, int[] segment, int[] offset, int[] length, byte[] data) {
        this.size = size;
        this.time = time;
        this.tag = tag;
        this.segment = segment;
        this.offset = offset;
        this.length = length;
        this.data = data;
        int nLocal = 0;
        int nTimed = 0;
        for (int i = 0; i < size; i++) {
            present[tag[i] & 0xFF] = true;
            Scope s = scope(tag[i]);
            if (s == Scope.TICK) {
                nLocal++;
            } else if (s == Scope.TIME) {
                nTimed++;
            }
        }
        local = new int[nLocal];
        timed = new int[nTimed];
        nLocal = 0;
        nTimed = 0;
        for (int i = 0; i < size; i++) {
            Scope s = scope(tag[i]);
            if (s == Scope.TICK) {
                local[nLocal++] = i;
            } else if (s == Scope.TIME) {
                timed[nTimed++] = i;
            }
        }
    }

    /** The scope of an extension tag (PLAN "Event order"). */
    public static Scope scope(int tag) {
        switch (tag) {
            case RewindFormat.TAG_OUT:
            case RewindFormat.TAG_DIG:
            case RewindFormat.TAG_BLOCK:
            case RewindFormat.TAG_USE:
            case RewindFormat.TAG_INV:
            case RewindFormat.TAG_CONT:
            case RewindFormat.TAG_HUD:
            case RewindFormat.TAG_SETTINGS:
            case RewindFormat.TAG_IDENTITY:
                return Scope.TICK;
            case RewindFormat.TAG_SOUND:
            case RewindFormat.TAG_TAP:
            case RewindFormat.TAG_VIEW:
            case RewindFormat.TAG_MOUSE:
            case RewindFormat.TAG_CHAT_INPUT:
            case RewindFormat.TAG_FOV:
            case RewindFormat.TAG_INPUT:
                return Scope.TIME;
            default:
                return Scope.NONE;
        }
    }

    /** Every record, all tags. */
    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** True if the recording holds at least one record of {@code tag} (a v2 part checks this before acting). */
    public boolean has(int tag) {
        return tag >= 0 && tag < present.length && present[tag];
    }

    public long timeUs(int i) {
        return time[i];
    }

    public int tag(int i) {
        return tag[i];
    }

    /** The record's segment as a position in {@link LoadedRecording#segments()} (as {@link NetCursor#segmentIndex}). */
    public int segmentIndex(int i) {
        return segment[i];
    }

    /** The shared body bytes; record i's body is {@code [offset(i), offset(i) + length(i))}. Do not modify. */
    public byte[] buffer() {
        return data;
    }

    public int offset(int i) {
        return offset[i];
    }

    public int length(int i) {
        return length[i];
    }

    /** Tick-scoped records, in time order: {@code local(j)} is a record index. */
    public int localCount() {
        return local.length;
    }

    public int local(int j) {
        return local[j];
    }

    /** Time-scoped records, in time order: {@code timed(j)} is a record index. */
    public int timedCount() {
        return timed.length;
    }

    public int timed(int j) {
        return timed[j];
    }

    /** The first position in the time-scoped list whose stamp is after {@code tUs} ({@link #timedCount()} if none). */
    public int firstTimedAfter(long tUs) {
        int lo = 0;
        int hi = timed.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (time[timed[mid]] <= tUs) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * The body of a record as {@code ExtCodec}'s encoders return it ({@code record[0, n)} = varint body length + body).
     *
     * @throws RewindFormat.FormatException if the prefix does not match {@code n}
     */
    public static byte[] body(byte[] record, int n) {
        RewindFormat.Cursor c = new RewindFormat.Cursor(record, 0, n);
        int len = c.length(n);
        int from = c.pos();
        if (from + len != n) {
            throw new RewindFormat.FormatException("extension record length");
        }
        return Arrays.copyOfRange(record, from, n);
    }

    /** Collects records in any order; {@link #build} sorts them by time (stable). */
    public static final class Builder {
        private static final class Rec {
            final int order;
            final int segment;
            final long time;
            final int tag;
            final byte[] body;

            Rec(int order, int segment, long time, int tag, byte[] body) {
                this.order = order;
                this.segment = segment;
                this.time = time;
                this.tag = tag;
                this.body = body;
            }
        }

        private final List<Rec> recs = new ArrayList<Rec>();

        /**
         * One extension record.
         *
         * @param segmentIndex the record's segment as a position in {@link LoadedRecording#segments()}
         * @param body         the body without the length prefix (copied)
         */
        public Builder add(int segmentIndex, long timeUs, int tag, byte[] body, int off, int len) {
            recs.add(new Rec(recs.size(), segmentIndex, timeUs, tag & 0xFF, Arrays.copyOfRange(body, off, off + len)));
            return this;
        }

        public Builder add(int segmentIndex, long timeUs, int tag, byte[] body) {
            return add(segmentIndex, timeUs, tag, body, 0, body.length);
        }

        /**
         * One record as {@code ExtCodec}'s encoders return it: {@code record[0, n)} = varint body length + body (what
         * the recorder appends to TRACK); the length prefix is dropped.
         */
        public Builder addEncoded(int segmentIndex, long timeUs, int tag, byte[] record, int n) {
            byte[] b = body(record, n);
            return add(segmentIndex, timeUs, tag, b, 0, b.length);
        }

        public ExtRecords build() {
            List<Rec> sorted = new ArrayList<Rec>(recs);
            Collections.sort(sorted, new Comparator<Rec>() {
                @Override
                public int compare(Rec a, Rec b) {
                    if (a.time != b.time) {
                        return a.time < b.time ? -1 : 1;
                    }
                    return a.order < b.order ? -1 : a.order > b.order ? 1 : 0;
                }
            });
            int n = sorted.size();
            long[] time = new long[n];
            int[] tag = new int[n];
            int[] segment = new int[n];
            int[] offset = new int[n];
            int[] length = new int[n];
            int total = 0;
            for (Rec r : sorted) {
                total += r.body.length;
            }
            byte[] data = new byte[total];
            int at = 0;
            for (int i = 0; i < n; i++) {
                Rec r = sorted.get(i);
                time[i] = r.time;
                tag[i] = r.tag;
                segment[i] = r.segment;
                offset[i] = at;
                length[i] = r.body.length;
                System.arraycopy(r.body, 0, data, at, r.body.length);
                at += r.body.length;
            }
            return new ExtRecords(n, time, tag, segment, offset, length, data);
        }
    }
}
