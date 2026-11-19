package com.bedwarsqol.rewind.watch;

/**
 * Where {@link TickFeeder} hands a recording's packets, on the client thread. Each record is a varint packet id plus
 * body at {@code buf[off, off + len)}; the bytes are valid only during the call. {@link TickFeeder#recordIndex()} and
 * {@link TickFeeder#recordTimeUs()} describe the record being handed over.
 *
 * <p>Per tick k the order is fixed (PLAN "Event order"): the NET records handled before tick k ({@link #apply},
 * {@link #boundary}, {@link #sessionHook}, {@link #window}), then the tick-scoped extension records stamped in tick
 * k's interval ({@link #local}), then {@link #sample}; then the world tick. Time-scoped extension records reach
 * {@link #timed} from the session's {@link TickClock} when playback time reaches their stamp, outside the feed.
 *
 * <p>{@link #apply}, {@link #sessionHook} and {@link #window} report failure by returning false or by throwing a
 * {@link RuntimeException}: the feeder skips that record and counts it. Exceptions from {@link #boundary},
 * {@link #local}, {@link #sample} and {@link #timed} are the session's own and propagate.
 */
public interface PacketSink {

    /** A {@link PacketPolicy.Decision#PASS} or {@link PacketPolicy.Decision#BOUNDARY} packet: decode and process it. */
    boolean apply(int packetId, byte[] buf, int off, int len);

    /** Right after a successful {@link #apply} of an S01 or S07 (header or NET). */
    void boundary(int packetId, byte[] buf, int off, int len);

    /** A {@link PacketPolicy.Decision#SESSION_HOOK} packet: not for vanilla; the session acts on it. */
    boolean sessionHook(int packetId, byte[] buf, int off, int len);

    /**
     * A {@link PacketPolicy.Decision#WINDOW} packet (S2D, S2E, and S2F/S30 for a window other than the inventory):
     * only a sink that holds recorded windows processes it (Phase 9); the default takes it and does nothing, so no
     * container traffic reaches vanilla without a holder.
     */
    default boolean window(int packetId, byte[] buf, int off, int len) {
        return true;
    }

    /**
     * A tick-scoped extension record ({@link ExtRecords.Scope#TICK}) stamped in the interval of the tick being fed,
     * after that tick's NET records and before its {@link #sample}. {@code buf[off, off + len)} is the body without
     * its length prefix ({@code ExtCodec} decoders read it), valid only during the call.
     */
    default void local(int tag, long timeUs, byte[] buf, int off, int len) {
    }

    /**
     * A time-scoped extension record ({@link ExtRecords.Scope#TIME}), dispatched once in the first frame whose
     * playback time has reached its stamp; never during a catch-up or seek ({@link TickClock}).
     */
    default void timed(int tag, long timeUs, byte[] buf, int off, int len) {
    }

    /** After the records of tick {@code tick}: the puppet's track sample for that tick ({@code timeUs} = T[k]). */
    void sample(long tick, long timeUs);
}
