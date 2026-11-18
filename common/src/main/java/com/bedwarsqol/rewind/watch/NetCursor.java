package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;

/**
 * Walks a loaded recording's NET records: segments in playback order from the start segment, and within a segment
 * the records in stored order (time order). Each segment's first record is its boundary packet (S01, or its
 * dimension-changing S07).
 *
 * <p>The cursor always stands on a record until {@link #atEnd()}: read it, then {@link #advance()}. So a feeder can
 * take every record up to a time without consuming the first one after it:
 * {@code while (!c.atEnd() && c.timeUs() <= t) { apply(c); c.advance(); }}. No allocation per record; a block is
 * inflated when the cursor enters it ({@link LoadedRecording}'s two-block cache). {@link #buffer()} is valid until
 * the next {@link #advance()} or {@link #reset()}. One thread at a time.
 */
public final class NetCursor {

    private final LoadedRecording recording;
    private final LoadedRecording.NetBlock[] blocks;
    private final RewindFormat.Cursor in = new RewindFormat.Cursor(new byte[0], 0, 0);

    private int block;
    private byte[] payload;
    private long time;
    private int off;
    private int len;
    private long position;
    private int segmentIndex;
    private boolean firstOfSegment;
    private boolean atEnd;

    NetCursor(LoadedRecording recording) {
        this.recording = recording;
        this.blocks = recording.blocks();
        reset();
    }

    /** Back to the first record of the start segment. */
    public void reset() {
        position = 0;
        segmentIndex = -1;
        atEnd = false;
        enter(0);
        if (!atEnd) {
            parse();
        }
    }

    /** True once every record was passed (or there is none). */
    public boolean atEnd() {
        return atEnd;
    }

    /** Steps to the next record; at the end it stays there. */
    public void advance() {
        if (atEnd) {
            return;
        }
        position++;
        if (!in.hasMore()) {
            enter(block + 1);
            if (atEnd) {
                return;
            }
        }
        parse();
    }

    private void enter(int b) {
        while (b < blocks.length) {
            LoadedRecording.NetBlock nb = blocks[b];
            if (nb.records > 0) {
                block = b;
                payload = recording.payload(b);
                in.reset(payload, 0, payload.length);
                time = nb.firstTimeUs;
                return;
            }
            b++;
        }
        block = blocks.length;
        payload = null;
        atEnd = true;
    }

    private void parse() {
        time += in.uvarInt();
        len = in.length(RewindFormat.MAX_PACKET_LENGTH);
        off = in.pos();
        in.skip(len);
        int seg = blocks[block].segmentIndex;
        firstOfSegment = seg != segmentIndex;
        segmentIndex = seg;
    }

    /** Time of the current record on the recording's clock (µs). */
    public long timeUs() {
        return time;
    }

    /** Segment number (in the file) of the current record; -1 at the end. */
    public int segment() {
        return atEnd ? -1 : blocks[block].segment;
    }

    /** Position of the current record's segment in {@link LoadedRecording#segments()}. */
    public int segmentIndex() {
        return segmentIndex;
    }

    /** True for the first record of a segment: its boundary packet. */
    public boolean firstOfSegment() {
        return firstOfSegment;
    }

    /** Holds the current record at {@code [offset(), offset() + length())}: varint packet id + body. */
    public byte[] buffer() {
        return payload;
    }

    public int offset() {
        return off;
    }

    public int length() {
        return len;
    }

    /** Leading packet id of the current record. */
    public int packetId() {
        return RewindFormat.packetId(payload, off, len);
    }

    /** Number of records before the current one, from the start segment's first record. */
    public long position() {
        return position;
    }
}
