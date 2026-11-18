package com.bedwarsqol.rewind.watch;

/**
 * Where {@link TickFeeder} hands a recording's packets, on the client thread. Each record is a varint packet id plus
 * body at {@code buf[off, off + len)}; the bytes are valid only during the call. {@link TickFeeder#recordIndex()} and
 * {@link TickFeeder#recordTimeUs()} describe the record being handed over.
 *
 * <p>{@link #apply} and {@link #sessionHook} report failure by returning false or by throwing a
 * {@link RuntimeException}: the feeder skips that record and counts it. Exceptions from {@link #boundary} and
 * {@link #sample} are the session's own and propagate.
 */
public interface PacketSink {

    /** A {@link PacketPolicy.Decision#PASS} or {@link PacketPolicy.Decision#BOUNDARY} packet: decode and process it. */
    boolean apply(int packetId, byte[] buf, int off, int len);

    /** Right after a successful {@link #apply} of an S01 or S07 (header or NET). */
    void boundary(int packetId, byte[] buf, int off, int len);

    /** A {@link PacketPolicy.Decision#SESSION_HOOK} packet: not for vanilla; the session acts on it. */
    boolean sessionHook(int packetId, byte[] buf, int off, int len);

    /** After the records of tick {@code tick}: the puppet's track sample for {@code timeUs = origin + tick x 50 ms}. */
    void sample(long tick, long timeUs);
}
