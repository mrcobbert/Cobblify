package com.bedwarsqol.rewind;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded single-producer single-consumer ring of {@link RecordChunk}s. No locks and no allocation
 * after construction: the producer stores a slot and publishes the tail with an ordered store
 * ({@code lazySet}), the consumer reads the tail, takes the slot and publishes the head the same way.
 *
 * <p>Exactly one thread may call {@link #offer} and exactly one (other) thread may call {@link #poll}
 * and {@link #peek}. {@link #size} and {@link #isEmpty} are safe from either.
 */
public final class ChunkRing {

    private final RecordChunk[] slots;
    private final int mask;
    private final AtomicLong head = new AtomicLong();
    private final AtomicLong tail = new AtomicLong();

    /** @param capacity minimum number of chunks it can hold; rounded up to a power of two */
    public ChunkRing(int capacity) {
        if (capacity < 1 || capacity > 1 << 20) {
            throw new IllegalArgumentException("capacity " + capacity);
        }
        int cap = Integer.highestOneBit(capacity);
        if (cap < capacity) {
            cap <<= 1;
        }
        slots = new RecordChunk[cap];
        mask = cap - 1;
    }

    public int capacity() {
        return slots.length;
    }

    /** Producer side. False when full; the chunk is not taken. */
    public boolean offer(RecordChunk c) {
        if (c == null) {
            throw new NullPointerException();
        }
        long t = tail.get();
        if (t - head.get() >= slots.length) {
            return false;
        }
        slots[(int) t & mask] = c;
        tail.lazySet(t + 1);
        return true;
    }

    /** Consumer side. The oldest chunk without removing it, or null when empty. */
    public RecordChunk peek() {
        long h = head.get();
        if (h >= tail.get()) {
            return null;
        }
        return slots[(int) h & mask];
    }

    /** Consumer side. Removes and returns the oldest chunk, or null when empty. */
    public RecordChunk poll() {
        long h = head.get();
        if (h >= tail.get()) {
            return null;
        }
        int i = (int) h & mask;
        RecordChunk c = slots[i];
        slots[i] = null;
        head.lazySet(h + 1);
        return c;
    }

    public int size() {
        long h = head.get();
        long t = tail.get();
        return (int) Math.max(0, Math.min(slots.length, t - h));
    }

    public boolean isEmpty() {
        return size() == 0;
    }
}
