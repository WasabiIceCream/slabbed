package com.slabbed.anchor;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongConsumer;

/**
 * The height a placement transaction already resolved on the client, held only until the
 * authoritative fact for that cell arrives.
 *
 * <p>The client runs the same placement transaction the server does and reaches the same height,
 * then drops it, because only the server may write a fact. Its chunk mesh then falls back to the
 * live neighbourhood lane, which deliberately answers something else - the placement answer may
 * legitimately be deeper. The block therefore draws at the wrong height until the fact syncs, and
 * visibly jumps when it lands.
 *
 * <p><b>This is not a fact and must never be read as one.</b> It is consulted from exactly one
 * place, the chunk-mesh height lookup, so collision, targeting, and every interaction keep reading
 * the authoritative store and cannot be moved by a prediction. Reading it anywhere else would make
 * a guess indistinguishable from a frozen height, which is the first law's whole subject.
 *
 * <p>Entries expire on a tick deadline whether or not a fact ever arrives, because some accepted
 * placements never produce one - a refused write, or a cell the store excludes. Without the
 * deadline those entries would live forever and become exactly the fact they must not be.
 *
 * <p>An expiry changes what the mesh draws without changing any block state, so nothing in
 * vanilla would rebuild the section. Every expired cell is therefore reported to the installed
 * expiry hook, always from {@link #advanceTick()} on the ticking thread, never from a mesh worker.
 */
public final class ClientRenderDyPrediction {
    /** Long enough to cover a sync round trip, short enough that a stale guess cannot persist. */
    private static final int LIFETIME_TICKS = 40;

    private static final Map<Long, Entry> PENDING = new ConcurrentHashMap<>();
    /** Cells dropped by a read on a mesh worker, waiting for the next tick to be reported. */
    private static final Queue<Long> EXPIRED_BY_READ = new ConcurrentLinkedQueue<>();
    private static volatile int currentTick;
    private static volatile LongConsumer expiryHook;

    private ClientRenderDyPrediction() {
    }

    private record Entry(int halfSteps, int expiresAtTick) {
    }

    /**
     * Installs the hook told about every cell whose prediction expired, so the client can rebuild
     * the geometry that prediction drew. Common code cannot name the renderer; the client can.
     */
    public static void installExpiryHook(LongConsumer hook) {
        expiryHook = hook;
    }

    /** Records what the client resolved for a cell it just placed into. */
    public static void record(long packedPos, int halfSteps) {
        PENDING.put(packedPos, new Entry(halfSteps, currentTick + LIFETIME_TICKS));
    }

    /**
     * The predicted height for a cell, or {@link SlabPlacementHeightAttachment#ABSENT_HALF_STEPS}.
     * An expired entry answers absent and is dropped, so a stale guess never renders.
     */
    public static int halfStepsOrAbsent(long packedPos) {
        Entry entry = PENDING.get(packedPos);
        if (entry == null) {
            return SlabPlacementHeightAttachment.ABSENT_HALF_STEPS;
        }
        if (entry.expiresAtTick() - currentTick <= 0) {
            if (PENDING.remove(packedPos, entry) && expiryHook != null) {
                EXPIRED_BY_READ.add(packedPos);
            }
            return SlabPlacementHeightAttachment.ABSENT_HALF_STEPS;
        }
        return entry.halfSteps();
    }

    /** Drops a prediction, which the authoritative fact for that cell must do on arrival. */
    public static void forget(long packedPos) {
        PENDING.remove(packedPos);
    }

    /** True while any prediction is outstanding, so the caller can skip work when none is. */
    public static boolean isEmpty() {
        return PENDING.isEmpty();
    }

    /**
     * Advances the deadline clock, drops everything already past it, and reports every cell
     * dropped since the last tick to the expiry hook.
     */
    public static void advanceTick() {
        int now = ++currentTick;
        LongConsumer hook = expiryHook;
        if (!PENDING.isEmpty()) {
            for (Map.Entry<Long, Entry> pending : PENDING.entrySet()) {
                Entry entry = pending.getValue();
                // Conditional removal: a cell re-recorded after this read keeps its new entry.
                if (entry.expiresAtTick() - now <= 0
                        && PENDING.remove(pending.getKey(), entry)
                        && hook != null) {
                    hook.accept(pending.getKey());
                }
            }
        }
        Long expired;
        while ((expired = EXPIRED_BY_READ.poll()) != null) {
            if (hook != null) {
                hook.accept(expired);
            }
        }
    }

    /** Drops every prediction; a disconnect or a level change invalidates all of them. */
    public static void clear() {
        PENDING.clear();
        EXPIRED_BY_READ.clear();
    }
}
