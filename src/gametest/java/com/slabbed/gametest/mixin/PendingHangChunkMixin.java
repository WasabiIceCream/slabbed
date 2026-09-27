package com.slabbed.gametest.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.slabbed.gametest.PendingHangChunkProbe;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Test only, never shipped: while the probe is armed on one chunk source, no chunk is ready and
 * every blocking chunk request is counted. Both hooks are allocation-free, so the suite-wide
 * presence of this mixin cannot move the hot-path allocation budget.
 */
@Mixin(ServerChunkCache.class)
public abstract class PendingHangChunkMixin {

    @ModifyReturnValue(method = "getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("RETURN"))
    private LevelChunk slabbed$unavailable(LevelChunk original) {
        if (PendingHangChunkProbe.covers(this)) {
            PendingHangChunkProbe.nonblockingReads++;
            return null;
        }
        return original;
    }

    @ModifyVariable(
            method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int slabbed$countBlockingReads(int chunkX) {
        if (PendingHangChunkProbe.covers(this)) {
            PendingHangChunkProbe.blockingReads++;
        }
        return chunkX;
    }
}
