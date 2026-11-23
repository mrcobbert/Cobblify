package com.bedwarsqol.rewind.export;

import java.nio.ByteBuffer;

/**
 * Where captured export frames go (Rewind Export PLAN Phase 2: the spike's PNG writer; Phase 5: the encoder helper).
 * Called on the game thread.
 */
public interface FrameSink {

    /**
     * Frame {@code index}, BGRA, bottom row first, {@code width x height x 4} bytes from the buffer's position. The
     * buffer is reused for the next frame: copy what you keep before returning.
     */
    void frame(long index, ByteBuffer bgra, int width, int height);

    /** A counts-only line about the export (per-frame state, settle counts, timings, failures). */
    void log(String line);
}
