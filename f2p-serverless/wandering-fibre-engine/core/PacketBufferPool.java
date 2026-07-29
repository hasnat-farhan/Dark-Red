package com.antor.f2p.engine.core;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal byte-array pool that reuses fixed-size buffers to reduce GC
 * pressure during heavy packet bursts.
 * <p>
 * The pool is a bounded ring buffer. If the pool is empty a new buffer is
 * allocated; if the pool is full the returned buffer is discarded (no
 * unbounded growth).
 * </p>
 *
 * @deprecated Reserved for future integration. The current packet pipeline
 *             does not track payload <em>length</em> separately from the
 *             backing {@code byte[]}, so pooled buffers (fixed 4096 bytes)
 *             would be cloned in their entirety by {@code FibrePacket},
 *             negating any GC benefit. Re-activate once the pipeline
 *             supports length-aware serialisation.
 */
@Deprecated
public final class PacketBufferPool {

    private final byte[][] pool;
    private final int bufferSize;
    private final AtomicInteger head;

    /**
     * @param poolSize    maximum number of cached buffers (e.g. 64)
     * @param bufferSize  size of each buffer in bytes (e.g. 4096)
     */
    public PacketBufferPool(int poolSize, int bufferSize) {
        this.pool = new byte[poolSize][];
        this.bufferSize = bufferSize;
        this.head = new AtomicInteger(0);
    }

    /** Obtain a buffer (either reused or freshly allocated). */
    public byte[] acquire() {
        for (int i = 0; i < pool.length; i++) {
            int idx = Math.floorMod(head.getAndIncrement(), pool.length);
            byte[] buf = pool[idx];
            if (buf != null) {
                pool[idx] = null;
                return buf;
            }
        }
        return new byte[bufferSize];
    }

    /**
     * Return a buffer to the pool.
     * @param buf the buffer to recycle; may be null (no-op)
     */
    public void release(byte[] buf) {
        if (buf == null || buf.length != bufferSize) return;
        for (int i = 0; i < pool.length; i++) {
            int idx = (head.getAndIncrement() & Integer.MAX_VALUE) % pool.length;
            if (pool[idx] == null) {
                pool[idx] = buf;
                return;
            }
        }
        // pool full — discard
    }

    /** Total capacity of the pool. */
    public int capacity() { return pool.length; }

    /** Approximate number of buffers currently in the pool. */
    public int available() {
        int count = 0;
        for (byte[] buf : pool) {
            if (buf != null) count++;
        }
        return count;
    }
}
