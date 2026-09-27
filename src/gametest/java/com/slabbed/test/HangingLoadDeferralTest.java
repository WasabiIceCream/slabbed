package com.slabbed.test;

import com.slabbed.gametest.PendingHangChunkProbe;
import com.slabbed.util.HangingSeatDyHolder;
import com.slabbed.util.SlabSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.decoration.PaintingVariants;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Restoring a hung decoration from save data must never synchronously request a chunk that is not
 * ready yet (remembered seat, LAW.md, Law 1 corollary): restoring can run inside that chunk's own
 * promotion, where such a request never returns. A saved seat comes back during the read; a
 * missing one is minted once, on the first tick with ready chunks, laid out once, and never
 * re-derived afterwards.
 *
 * <p>Rows: saved and legacy (no seat key) restores, for an item frame and for a wide (4x4)
 * painting. The restored decoration is never added to the level, so no loader hook that only runs
 * for added entities can count as a chunk request here.
 *
 * <p>MUTATION that must redden every row: bring back the earlier mint (a {@code hasChunkAt} gate
 * and a {@code Level.getBlockState} read, with no reading flag) - the restore then enters the
 * blocking chunk API. MUTATION for the legacy rows: drop the tick retry - the seat stays unminted
 * after the first tick.
 */
@GameTestHolder("slabbed")
@PrefixGameTestTemplate(false)
public final class HangingLoadDeferralTest {

    private static final String TEMPLATE = "empty";
    private static final double EPS = 1.0e-6d;
    private static final String HANG_DY_KEY = "slabbed:hang_dy";

    @GameTest(template = TEMPLATE)
    public void savedFrameKeepsSeatWithoutLoadingChunks(GameTestHelper ctx) {
        check(ctx, false, true);
    }

    @GameTest(template = TEMPLATE)
    public void legacyFrameDefersAndMintsOnce(GameTestHelper ctx) {
        check(ctx, false, false);
    }

    @GameTest(template = TEMPLATE)
    public void savedWidePaintingKeepsSeatWithoutLoadingChunks(GameTestHelper ctx) {
        check(ctx, true, true);
    }

    @GameTest(template = TEMPLATE)
    public void legacyWidePaintingDefersAndMintsOnce(GameTestHelper ctx) {
        check(ctx, true, false);
    }

    private static double seatOf(Object entity) {
        return ((HangingSeatDyHolder) entity).slabbed$hangSeatDy();
    }

    private static void check(GameTestHelper ctx, boolean painting, boolean savedSeat) {
        ServerLevel world = ctx.getLevel();
        BlockPos wallRel = new BlockPos(3, 4, 3);
        // A wall that reads -0.5: an ordinary block sitting on a bottom slab.
        ctx.setBlock(wallRel.below(), Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        ctx.setBlock(wallRel, Blocks.OAK_PLANKS.defaultBlockState());
        BlockPos wall = ctx.absolutePos(wallRel);
        BlockPos attachment = wall.relative(Direction.NORTH);
        HangingEntity original = painting
                ? new Painting(world, attachment, Direction.NORTH,
                        world.registryAccess().registryOrThrow(Registries.PAINTING_VARIANT)
                                .getHolderOrThrow(PaintingVariants.POINTER))
                : new ItemFrame(world, attachment, Direction.NORTH);
        ctx.assertTrue(Math.abs(seatOf(original) + 0.5d) <= EPS,
                "premise: the original decoration must be lowered, seat " + seatOf(original));
        AABB originalBox = original.getBoundingBox();
        CompoundTag nbt = original.saveWithoutId(new CompoundTag());
        if (!savedSeat) {
            nbt.remove(HANG_DY_KEY);
        }
        ctx.assertTrue(nbt.contains(HANG_DY_KEY) == savedSeat, "premise: saved-seat key presence");
        HangingEntity restored = painting ? EntityType.PAINTING.create(world) : EntityType.ITEM_FRAME.create(world);
        ctx.assertTrue(restored != null, "premise: the restored entity exists");

        PendingHangChunkProbe.begin(world.getChunkSource());
        try {
            restored.load(nbt);
            ctx.assertTrue(PendingHangChunkProbe.blockingReads == 0,
                    "restoring entered the blocking chunk API " + PendingHangChunkProbe.blockingReads + " times");
            ctx.assertTrue(PendingHangChunkProbe.nonblockingReads > 0,
                    "premise: the not-ready-chunk guard was exercised");
            ctx.assertTrue(((HangingSeatDyHolder) restored).slabbed$hasHangSeat() == savedSeat,
                    "a pending restore must keep a saved seat and defer a missing one");
        } finally {
            PendingHangChunkProbe.end();
        }

        restored.tick();
        ctx.assertTrue(Math.abs(seatOf(restored) + 0.5d) <= EPS,
                "the first ready tick must restore or mint the lowered seat, got " + seatOf(restored));
        ctx.assertTrue(Math.abs(restored.getBoundingBox().minY - originalBox.minY) <= EPS,
                "the deferred layout must match the original lowered box: " + restored.getBoundingBox().minY
                        + " vs " + originalBox.minY);

        // The wall behind it is rebuilt FLUSH; the decoration's remembered seat must not follow.
        ctx.setBlock(wallRel.below(), Blocks.AIR.defaultBlockState());
        ctx.setBlock(wallRel, Blocks.AIR.defaultBlockState());
        ctx.setBlock(wallRel, Blocks.OAK_PLANKS.defaultBlockState());
        double wallNow = SlabSupport.getYOffset(world, wall, world.getBlockState(wall));
        ctx.assertTrue(Math.abs(wallNow) <= EPS, "premise: the rebuilt wall must read flush, got " + wallNow);
        restored.tick();
        ctx.assertTrue(Math.abs(seatOf(restored) + 0.5d) <= EPS,
                "a later support change must not re-mint the decoration's seat, got " + seatOf(restored));
        ctx.assertTrue(Math.abs(restored.getBoundingBox().minY - originalBox.minY) <= EPS,
                "later ticks must not apply the height twice: " + restored.getBoundingBox().minY
                        + " vs " + originalBox.minY);
        System.out.println("[HANG_LOAD_PROOF] saved=" + savedSeat + " painting=" + painting
                + " blocking=0 seat=-0.5 PASS");
        ctx.succeed();
    }
}
