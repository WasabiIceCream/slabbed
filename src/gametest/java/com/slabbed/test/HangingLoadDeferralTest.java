package com.slabbed.test;

import com.slabbed.util.HangingSeatDyHolder;
import com.slabbed.util.SlabSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.decoration.PaintingVariants;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Restoring a hung decoration from save data must never synchronously load a chunk that is still
 * pending (LAW.md Law 1 corollary: a remembered seat survives the reload untouched, and a missing
 * one is minted once, later, from the real wall).
 *
 * <p>Each row saves a decoration hung on a real -0.5 wall, moves the saved copy into a far chunk
 * that is scheduled but not yet loaded (a ticket is applied, and the chunk's final loading step
 * needs the server thread this row is running on), and reads it back there. A blocking chunk read
 * during that restore would load the far chunk before the read returns; the rows assert it is still
 * unloaded. Once the chunk really loads, the same lowered wall is built there and one tick must
 * restore or mint -0.5 and lay the box out exactly once; a later flush rebuild must change nothing.
 *
 * <p>MUTATIONS each row names, per LAW.md's reachability rule.
 * <ul>
 *   <li>In {@code HangingEntityRememberedSeatMixin.slabbed$readyChunk}, return
 *       {@code level.getChunk(chunkX, chunkZ)} (the blocking lookup): every row reddens on the
 *       "still unloaded" assertion.</li>
 *   <li>Delete the {@code tick()} retry in {@code HangingEntityRememberedSeatMixin}: both legacy
 *       rows redden at the ready tick (no seat is ever minted).</li>
 *   <li>Restore the pre-fix mint ({@code hasChunkAt} then {@code level.getBlockState}): every row
 *       reddens on "still unloaded"; the legacy rows also mint a flush seat during the read.</li>
 * </ul>
 */
@GameTestHolder("fabric-gametest-api-v1")
@PrefixGameTestTemplate(false)
public final class HangingLoadDeferralTest {

    private static final String TEMPLATE = "empty";
    private static final double EPS = 1.0e-6d;
    private static final int TIMEOUT_TICKS = 400;
    private static final String SEAT_KEY = "slabbed:hang_dy";

    @GameTest(templateNamespace = "fabric-gametest-api-v1", template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void savedFrameKeepsSeatWithoutLoadingChunks(GameTestHelper ctx) {
        check(ctx, false, true, 0);
    }

    @GameTest(templateNamespace = "fabric-gametest-api-v1", template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void legacyFrameDefersAndMintsOnce(GameTestHelper ctx) {
        check(ctx, false, false, 1);
    }

    @GameTest(templateNamespace = "fabric-gametest-api-v1", template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void savedWidePaintingKeepsSeatWithoutLoadingChunks(GameTestHelper ctx) {
        check(ctx, true, true, 2);
    }

    @GameTest(templateNamespace = "fabric-gametest-api-v1", template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void legacyWidePaintingDefersAndMintsOnce(GameTestHelper ctx) {
        check(ctx, true, false, 3);
    }

    private static double seatOf(Object entity) {
        return ((HangingSeatDyHolder) entity).slabbed$hangSeatDy();
    }

    private static boolean hasSeat(Object entity) {
        return ((HangingSeatDyHolder) entity).slabbed$hasHangSeat();
    }

    /** Builds a wall that reads -0.5 by really placing stone on a bottom slab's top face, then clears the slab. */
    private static void loweredWall(GameTestHelper ctx, BlockPos wall) {
        ServerLevel level = ctx.getLevel();
        BlockPos slab = wall.below();
        level.setBlock(slab, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM),
                Block.UPDATE_ALL);
        Player player = ctx.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(wall.getX() + 0.5d, wall.getY(), wall.getZ() + 0.5d);
        ItemStack stack = new ItemStack(Blocks.STONE);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        InteractionResult result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(slab), Direction.UP, slab, false)));
        ctx.assertTrue(result.consumesAction() && level.getBlockState(wall).is(Blocks.STONE),
                "premise: the wall placement must be accepted and create the wall block");
        level.setBlock(slab, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        double dy = SlabSupport.getYOffset(level, wall, level.getBlockState(wall));
        ctx.assertTrue(Math.abs(dy + 0.5d) <= EPS,
                "premise: the placed wall must keep -0.5 after its slab is removed, got " + dy);
    }

    /** Moves saved entity data sideways by whole blocks; the height is untouched. */
    private static void translate(CompoundTag nbt, int dx, int dz) {
        ListTag pos = nbt.getList("Pos", Tag.TAG_DOUBLE);
        ListTag moved = new ListTag();
        moved.add(DoubleTag.valueOf(pos.getDouble(0) + dx));
        moved.add(DoubleTag.valueOf(pos.getDouble(1)));
        moved.add(DoubleTag.valueOf(pos.getDouble(2) + dz));
        nbt.put("Pos", moved);
        nbt.putInt("TileX", nbt.getInt("TileX") + dx);
        nbt.putInt("TileZ", nbt.getInt("TileZ") + dz);
    }

    private static void check(GameTestHelper ctx, boolean painting, boolean savedSeat, int row) {
        ServerLevel level = ctx.getLevel();
        ServerChunkCache chunks = level.getChunkSource();

        // The original is hung for real on a lowered wall inside the test area.
        BlockPos wall = ctx.absolutePos(new BlockPos(3, 3, 3));
        loweredWall(ctx, wall);
        BlockPos attachment = wall.relative(Direction.NORTH);
        HangingEntity original = painting
                ? new Painting(level, attachment, Direction.NORTH,
                        level.registryAccess().registryOrThrow(Registries.PAINTING_VARIANT)
                                .getHolderOrThrow(PaintingVariants.POINTER))
                : new ItemFrame(level, attachment, Direction.NORTH);
        ctx.assertTrue(Math.abs(seatOf(original) + 0.5d) <= EPS,
                "premise: the original decoration must be lowered, got " + seatOf(original));
        AABB originalBox = original.getBoundingBox();
        CompoundTag nbt = original.saveWithoutId(new CompoundTag());
        if (!savedSeat) {
            nbt.remove(SEAT_KEY); // an old save: no remembered seat
        }

        // A far chunk nobody has loaded, one per row, with the attachment in its middle so every
        // cell the wall and its resolver touch stays inside it.
        ChunkPos far = new ChunkPos(20_000 + 4 * row, 20_000);
        int dx = far.getMinBlockX() + 8 - attachment.getX();
        int dz = far.getMinBlockZ() + 8 - attachment.getZ();
        translate(nbt, dx, dz);
        BlockPos farWall = wall.offset(dx, 0, dz);

        // Schedule it without letting it finish: the ticket is applied now, and its final loading
        // step can only run on this thread once this row returns. The ticket is always released:
        // by the sequence once it has run, or here when the row fails before handing it over.
        chunks.addRegionTicket(TicketType.FORCED, far, 0, far);
        boolean ticketHandedOver = false;
        try {
            chunks.pollTask();
            ctx.assertTrue(level.hasChunk(far.x, far.z) && chunks.getChunkNow(far.x, far.z) == null,
                    "premise: the far chunk must be scheduled but not loaded; scheduled=" + level.hasChunk(far.x, far.z)
                            + " loaded=" + (chunks.getChunkNow(far.x, far.z) != null));

            HangingEntity restored = painting ? EntityType.PAINTING.create(level) : EntityType.ITEM_FRAME.create(level);
            ctx.assertTrue(restored != null, "premise: the restored entity must exist");
            restored.load(nbt);
            ctx.assertTrue(chunks.getChunkNow(far.x, far.z) == null,
                    "restoring the decoration loaded its pending chunk: a blocking chunk read ran during the read");
            ctx.assertTrue(hasSeat(restored) == savedSeat,
                    "a pending restore must keep a saved seat and defer an absent one; saved=" + savedSeat
                            + " hasSeat=" + hasSeat(restored) + " seat=" + seatOf(restored));

            ctx.startSequence()
                    .thenWaitUntil(() -> ctx.assertTrue(chunks.getChunkNow(far.x, far.z) != null,
                            "waiting for the far chunk to finish loading"))
                    .thenExecute(() -> {
                        try {
                            loweredWall(ctx, farWall);
                            restored.tick();
                            ctx.assertTrue(Math.abs(seatOf(restored) + 0.5d) <= EPS,
                                    "the first ready tick must restore or mint the lowered seat, got " + seatOf(restored));
                            ctx.assertTrue(Math.abs(restored.getBoundingBox().minY - originalBox.minY) <= EPS,
                                    "the deferred layout must match the original lowered box; minY "
                                            + restored.getBoundingBox().minY + " vs " + originalBox.minY);
                            level.setBlock(farWall, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                            level.setBlock(farWall, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
                            restored.tick();
                            ctx.assertTrue(Math.abs(seatOf(restored) + 0.5d) <= EPS,
                                    "a later wall change must not remint the seat, got " + seatOf(restored));
                            ctx.assertTrue(Math.abs(restored.getBoundingBox().minY - originalBox.minY) <= EPS,
                                    "later ticks must not apply the height twice; minY "
                                            + restored.getBoundingBox().minY + " vs " + originalBox.minY);
                        } finally {
                            chunks.removeRegionTicket(TicketType.FORCED, far, 0, far);
                        }
                    })
                    .thenSucceed();
            ticketHandedOver = true;
        } finally {
            if (!ticketHandedOver) {
                chunks.removeRegionTicket(TicketType.FORCED, far, 0, far);
            }
        }
    }
}
