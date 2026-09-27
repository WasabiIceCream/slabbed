package com.slabbed.gametest;

import net.minecraft.server.level.ServerChunkCache;

/**
 * A scoped not-ready-chunk fixture; armed only around one synchronous restore assertion, on the
 * thread that armed it. Reads from any other thread are never counted, so background chunk work
 * cannot leak into the assertion.
 */
public final class PendingHangChunkProbe {
    public static ServerChunkCache manager;
    public static Thread owner;
    public static int blockingReads;
    public static int nonblockingReads;

    private PendingHangChunkProbe() {
    }

    public static void begin(ServerChunkCache value) {
        blockingReads = 0;
        nonblockingReads = 0;
        owner = Thread.currentThread();
        manager = value;
    }

    public static void end() {
        manager = null;
        owner = null;
    }

    /** True while armed on {@code chunks} and called from the arming thread. */
    public static boolean covers(Object chunks) {
        return chunks == manager && Thread.currentThread() == owner;
    }
}
