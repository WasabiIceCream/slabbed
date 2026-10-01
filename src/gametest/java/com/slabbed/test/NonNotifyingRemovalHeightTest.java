package com.slabbed.test;

import com.slabbed.anchor.SlabAnchorAttachment;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;

/** A departed occupant cannot leave a stored height behind when notification flags are absent. */
public final class NonNotifyingRemovalHeightTest {
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void removalWithoutNeighborNotificationsClearsTheHeight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlock(pos, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
        SlabAnchorAttachment.writePlacementDy(level, pos, -0.5d);
        if (SlabAnchorAttachment.storedPlacementDy(level, pos) != -0.5d) {
            throw helper.assertionException("premise: the departing block must carry a stored lowered height");
        }
        level.getChunkAt(pos).setBlockState(pos, Blocks.AIR.defaultBlockState(), 0);
        if (!Double.isNaN(SlabAnchorAttachment.storedPlacementDy(level, pos))) {
            throw helper.assertionException("a non-notifying removal left the departed block's height behind");
        }
        level.getChunkAt(pos).setBlockState(pos, Blocks.STONE.defaultBlockState(), 0);
        if (!Double.isNaN(SlabAnchorAttachment.storedPlacementDy(level, pos))) {
            throw helper.assertionException("a fresh occupant inherited a removed block's stored height");
        }
        helper.succeed();
    }
}
