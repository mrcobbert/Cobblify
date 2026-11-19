package com.bedwarsqol.rewind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Test sink for {@link ActionCapture}: keeps each appended extension record (tag, time from {@link #nowUs}, body
 * without its length prefix), or, with {@link #keep} off, only counts them, allocating nothing. It can refuse
 * appends, throw from them, and say the segment is not recorded.
 */
class RecordingCaptureSink implements ActionCapture.Sink {

    static final class Rec {
        final int tag;
        final long timeUs;
        final byte[] body;

        Rec(int tag, long timeUs, byte[] body) {
            this.tag = tag;
            this.timeUs = timeUs;
            this.body = body;
        }

        @Override
        public String toString() {
            return Integer.toHexString(tag) + "@" + timeUs;
        }
    }

    final List<Rec> recs = new ArrayList<Rec>();
    boolean keep = true;
    boolean recording = true;
    boolean accept = true;
    RuntimeException throwOnAppend;
    long nowUs;
    long appends;
    long lastTag;
    int failures;
    Throwable lastFailure;

    @Override
    public boolean recording() {
        return recording;
    }

    @Override
    public boolean append(int tag, byte[] buf, int len) {
        if (throwOnAppend != null) {
            throw throwOnAppend;
        }
        appends++;
        lastTag = tag;
        if (keep) {
            int[] lenAndSize = readVarInt(buf, 0);
            int bodyLen = lenAndSize[0];
            int at = lenAndSize[1];
            if (at + bodyLen != len) {
                throw new AssertionError("record length " + len + " != prefix " + at + " + body " + bodyLen);
            }
            recs.add(new Rec(tag, nowUs, Arrays.copyOfRange(buf, at, at + bodyLen)));
        }
        return accept;
    }

    @Override
    public void failed(Throwable cause) {
        failures++;
        lastFailure = cause;
    }

    List<Rec> ofTag(int tag) {
        List<Rec> out = new ArrayList<Rec>();
        for (Rec r : recs) {
            if (r.tag == tag) {
                out.add(r);
            }
        }
        return out;
    }

    /** value, length */
    static int[] readVarInt(byte[] b, int pos) {
        int v = 0;
        for (int i = 0; i < 5; i++) {
            int x = b[pos + i] & 0xFF;
            v |= (x & 0x7F) << 7 * i;
            if ((x & 0x80) == 0) {
                return new int[]{v, i + 1};
            }
        }
        throw new AssertionError("bad varint");
    }
}
