package com.slabbed.test;

import com.slabbed.anchor.SlabPlacementHeightAttachment;
import com.slabbed.compat.sable.SableHitGeometry;
import com.slabbed.compat.sable.SablePhysicsHeight;
import com.slabbed.compat.sable.SablePlacementRefresh;
import com.slabbed.util.SlabSupport;
import com.slabbed.util.SlabbedOffsetColliderClip;
import com.slabbed.util.SlabbedOffsetRaycast;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drops real Sable physics objects onto a flush pad and a Slabbed-lowered pad, and onto a pad whose
 * stored height changes while an object already rests on it.
 *
 * <p>A physics object's resting height is the only thing measured: Sable's own rigid-body pose after
 * the object has settled. With the height bridge the lowered pad holds the object half a block lower
 * than the flush pad, and the refreshed pad lets its resting object down by the same half block.
 * The RED lane runs the same world with the bridge switched off and must see both pads alike.
 *
 * <p>Once the objects have settled, rays through them check that Slabbed's crosshair pick and its
 * arrow and sight clip corrections all stop at a Sable object rather than passing through it.
 */
public final class P11SablePhysicsHeightProof {
    private static final String EXPECT_RED_PROPERTY = "slabbed.p11.sable.expect_red";
    private static final int SETTLE_TICKS = 120;
    private static final double TOLERANCE = 0.08d;

    private static boolean registered;
    private static boolean expectRed;
    private static int tick;
    private static int ground;
    private static BlockPos controlCenter;
    private static BlockPos loweredCenter;
    private static BlockPos refreshCenter;
    private static ServerSubLevel controlObject;
    private static ServerSubLevel loweredObject;
    private static ServerSubLevel refreshObject;
    private static BlockPos clipFixture;
    private static BlockPos compoundBase;
    private static ServerSubLevel compoundObject;
    private static double refreshBefore = Double.NaN;
    private static boolean done;

    private P11SablePhysicsHeightProof() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        expectRed = Boolean.getBoolean(EXPECT_RED_PROPERTY);
        NeoForge.EVENT_BUS.addListener(P11SablePhysicsHeightProof::onServerStarted);
        NeoForge.EVENT_BUS.addListener(P11SablePhysicsHeightProof::onServerTick);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        try {
            ServerLevel level = event.getServer().overworld();
            require(SablePhysicsHeight.ENABLED == !expectRed, "bridge_switch_matches_lane");
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            require(container != null, "sable_container_present");
            require(container.physicsSystem() instanceof SablePlacementRefresh, "sable_mixins_applied");

            BlockPos spawn = level.getSharedSpawnPos();
            ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ());
            controlCenter = new BlockPos(spawn.getX() - 10, ground, spawn.getZ());
            loweredCenter = new BlockPos(spawn.getX(), ground, spawn.getZ());
            refreshCenter = new BlockPos(spawn.getX() + 10, ground, spawn.getZ());

            // Flat worlds generate animals; one standing on a pad would refuse a real placement.
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, event.getServer());
            for (Entity entity : level.getEntities((Entity) null,
                    new AABB(controlCenter).inflate(40.0d, 16.0d, 16.0d),
                    entity -> !(entity instanceof Player))) {
                entity.discard();
            }

            Player player = FakePlayerFactory.getMinecraft(level);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    buildFlushCell(level, player, controlCenter.offset(dx, 0, dz));
                    buildLoweredCell(level, player, loweredCenter.offset(dx, 0, dz));
                    buildFlushCell(level, player, refreshCenter.offset(dx, 0, dz));
                }
            }
            requireDy(level, controlCenter.above(), 0.0d, "control_pad_flush");
            requireDy(level, loweredCenter.above(), -0.5d, "lowered_pad_lowered");
            requireDy(level, refreshCenter.above(), 0.0d, "refresh_pad_starts_flush");

            // A wall behind the control object, for the pick check once the object has settled.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 2; dy <= 4; dy++) {
                    level.setBlock(controlCenter.offset(dx, dy, 4), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }

            // A lowered stone on the -x side of the control object, for the collider-clip check.
            BlockPos clipColumn = controlCenter.offset(-3, 0, 0);
            clipFixture = clipColumn.above(3);
            level.setBlock(clipColumn, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(clipColumn.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            buildLoweredCell(level, player, clipColumn.above(2));
            requireDy(level, clipFixture, -0.5d, "clip_fixture_lowered");

            // An object that is itself a slab carrying a lowered stone.
            compoundBase = new BlockPos(spawn.getX(), ground, spawn.getZ() + 10);
            buildLoweredCell(level, player, compoundBase);
            requireDy(level, compoundBase.above(), -0.5d, "compound_stone_lowered");

            measureSectionCost(level, container, spawn);

            controlObject = drop(level, controlCenter.above(4));
            loweredObject = drop(level, loweredCenter.above(4));
            refreshObject = drop(level, refreshCenter.above(4));
            compoundObject = SubLevelAssemblyHelper.assembleBlocks(level, compoundBase,
                    List.of(compoundBase, compoundBase.above()),
                    new BoundingBox3i(compoundBase.getX(), compoundBase.getY(), compoundBase.getZ(),
                            compoundBase.getX(), compoundBase.getY() + 1, compoundBase.getZ()));
            require(compoundObject != null && !compoundObject.isRemoved(), "assembled_compound");
        } catch (Throwable t) {
            fail(event.getServer(), t);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (done || controlObject == null) {
            return;
        }
        MinecraftServer server = event.getServer();
        tick++;
        try {
            if (tick == SETTLE_TICKS) {
                refreshBefore = restingY(refreshObject);
                ServerLevel level = server.overworld();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos cell = refreshCenter.offset(dx, 1, dz);
                        require(SlabPlacementHeightAttachment.putHalfSteps(
                                level.getChunkAt(cell), cell, -1), "refresh_fact_written");
                    }
                }
                requireDy(level, refreshCenter.above(), -0.5d, "refresh_pad_now_lowered");
            } else if (tick == 2 * SETTLE_TICKS) {
                double control = restingY(controlObject);
                double lowered = restingY(loweredObject);
                double refreshAfter = restingY(refreshObject);
                double top = ground + 2.0d;
                String numbers = String.format(Locale.ROOT,
                        "control=%.3f lowered=%.3f refreshBefore=%.3f refreshAfter=%.3f",
                        control - top, lowered - top, refreshBefore - top, refreshAfter - top);
                System.out.println("P11_SABLE | " + (expectRed ? "RED " : "") + numbers);
                require(near(control - top, 0.5d), "control_rests_on_flush_top " + numbers);
                require(near(refreshBefore - top, 0.5d), "refresh_rests_on_flush_top " + numbers);
                double expectedLowered = expectRed ? 0.5d : 0.0d;
                require(near(lowered - top, expectedLowered), "lowered_pad_resting_height " + numbers);
                require(near(refreshAfter - top, expectedLowered), "refreshed_pad_resting_height " + numbers);
                numbers += " " + pickCheck(server.overworld());
                numbers += " " + colliderClipCheck(server.overworld());
                writeReceipt(expectRed ? "p11-sable-red.ok" : "p11-sable.ok", numbers + "\n");
                done = true;
                server.halt(false);
            }
        } catch (Throwable t) {
            fail(server, t);
        }
    }

    /**
     * A ray through the settled control object into the wall behind it. Sable's own clip reports
     * the object, at the position it is stored in Sable's plot grid; Slabbed's offset raycast sees
     * only the world and reports the wall. The composed pick must choose the object, which it does
     * only when the object's hit is measured with Sable's own distance. A second ray beside the
     * object reaches the wall through Sable's clip as a world hit, which must never displace the
     * Slabbed hit.
     */
    private static String pickCheck(ServerLevel level) {
        Vec3 center = new Vec3(controlObject.logicalPose().position().x(),
                controlObject.logicalPose().position().y(), controlObject.logicalPose().position().z());
        Vec3 eye = center.add(0.0d, 0.0d, -3.5d);
        Vec3 end = center.add(0.0d, 0.0d, 6.5d);
        BlockHitResult offset = SlabbedOffsetRaycast.raycast(level, eye, end, CollisionContext.empty());
        require(offset.getType() == HitResult.Type.BLOCK
                        && offset.getBlockPos().getZ() == controlCenter.getZ() + 4,
                "pick_offset_raycast_sees_the_wall " + offset.getBlockPos().toShortString());
        BlockHitResult external = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        require(SableHitGeometry.isSubLevelHit(level, external),
                "pick_sable_clip_reports_the_object " + external.getType() + " " + external.getBlockPos().toShortString());
        HitResult composed = SableHitGeometry.composePick(level, eye, external, offset);
        HitResult plain = SlabbedOffsetRaycast.selectNearestOwnedHit(eye, external, offset);
        String detail = String.format(Locale.ROOT, "pick: sableDistance=%.2f plainDistance=%.1f wallDistance=%.2f",
                Math.sqrt(SableHitGeometry.distanceSq(level, eye, external.getLocation())),
                Math.sqrt(external.getLocation().distanceToSqr(eye)),
                Math.sqrt(offset.getLocation().distanceToSqr(eye)));
        String result = detail + " composed=" + (composed == external ? "object" : "wall")
                + " plain=" + (plain == external ? "object" : "wall");
        System.out.println("P11_SABLE_PICK | " + result);
        require(composed == external, "pick_composed_chooses_the_object " + result);

        Vec3 besideEye = eye.add(1.0d, 0.0d, 0.0d);
        Vec3 besideEnd = end.add(1.0d, 0.0d, 0.0d);
        BlockHitResult besideOffset = SlabbedOffsetRaycast.raycast(level, besideEye, besideEnd, CollisionContext.empty());
        BlockHitResult besideSable = level.clip(new ClipContext(besideEye, besideEnd, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        require(besideSable.getType() == HitResult.Type.BLOCK && !SableHitGeometry.isSubLevelHit(level, besideSable),
                "pick_beside_sable_clip_reports_the_world " + besideSable.getType() + " "
                        + besideSable.getBlockPos().toShortString());
        require(SableHitGeometry.composePick(level, besideEye, besideSable, besideOffset) == besideOffset,
                "pick_beside_world_hit_keeps_the_slabbed_hit");
        return result + " beside=slabbed";
    }

    /**
     * The arrow and sight clip corrections on rays through Sable objects. Sable's clip reports a
     * sub-level hit in its plot grid; the corrections must never let the ray pass through the
     * object. Ray A crosses the control object, then a lowered stone the owner-window search
     * finds. Ray B crosses an object that is itself a slab carrying a lowered stone, whose plot-grid
     * cell is dy-shifted. Ray C crosses the lowered pad's vacated band, then the object resting on
     * that pad — which the band re-march alone, seeing only the world, cannot find.
     */
    private static String colliderClipCheck(ServerLevel level) {
        Vec3 center = position(controlObject);
        Vec3 aFrom = center.add(3.5d, 0.25d, 0.0d);
        Vec3 aTo = center.add(-6.5d, 0.25d, 0.0d);
        ClipContext a = collider(aFrom, aTo);
        BlockHitResult aVanilla = level.clip(a);
        require(SableHitGeometry.isSubLevelHit(level, aVanilla), "clip_a_sable_reports_the_object "
                + describe(level, aFrom, aVanilla));
        BlockHitResult aWithoutObject = SlabbedOffsetColliderClip.clip(level, a, null,
                BlockHitResult.miss(aTo, Direction.EAST, BlockPos.containing(aTo)));
        require(aWithoutObject.getType() == HitResult.Type.BLOCK && aWithoutObject.getBlockPos().equals(clipFixture),
                "clip_a_owner_window_finds_the_lowered_stone " + describe(level, aFrom, aWithoutObject));
        BlockHitResult aArrow = SlabbedOffsetColliderClip.clip(level, a, null, aVanilla);
        BlockHitResult aSight = SlabbedOffsetColliderClip.clipForOcclusion(level, a, null, aVanilla);
        require(aArrow == aVanilla, "clip_a_arrow_stops_at_the_object " + describe(level, aFrom, aArrow));
        require(aSight == aVanilla, "clip_a_sight_stops_at_the_object " + describe(level, aFrom, aSight));

        Vec3 compound = position(compoundObject);
        Vec3 bFrom = compound.add(0.0d, 0.0d, -3.5d);
        ClipContext b = collider(bFrom, compound.add(0.0d, 0.0d, 6.5d));
        BlockHitResult bVanilla = level.clip(b);
        require(SableHitGeometry.isSubLevelHit(level, bVanilla)
                        && Math.abs(SlabSupport.getYOffset(level, bVanilla.getBlockPos(),
                                level.getBlockState(bVanilla.getBlockPos()))) > 1.0e-6d,
                "clip_b_sable_reports_a_shifted_object_cell " + describe(level, bFrom, bVanilla));
        BlockHitResult bArrow = SlabbedOffsetColliderClip.clip(level, b, null, bVanilla);
        BlockHitResult bSight = SlabbedOffsetColliderClip.clipForOcclusion(level, b, null, bVanilla);
        require(bArrow == bVanilla, "clip_b_arrow_stops_at_the_object " + describe(level, bFrom, bArrow));
        require(bSight == bVanilla, "clip_b_sight_stops_at_the_object " + describe(level, bFrom, bSight));

        Vec3 resting = position(loweredObject);
        double bandY = ground + 1.75d;
        Vec3 cFrom = new Vec3(resting.x + 3.5d, bandY, resting.z);
        ClipContext c = collider(cFrom, new Vec3(resting.x - 6.5d, bandY, resting.z));
        BlockHitResult cVanilla = level.clip(c);
        require(cVanilla.getType() == HitResult.Type.BLOCK && !SableHitGeometry.isSubLevelHit(level, cVanilla)
                        && Math.abs(SlabSupport.getYOffset(level, cVanilla.getBlockPos(),
                                level.getBlockState(cVanilla.getBlockPos()))) > 1.0e-6d,
                "clip_c_sable_reports_the_vacated_band " + describe(level, cFrom, cVanilla));
        BlockHitResult cArrow = SlabbedOffsetColliderClip.clip(level, c, null, cVanilla);
        BlockHitResult cSight = SlabbedOffsetColliderClip.clipForOcclusion(level, c, null, cVanilla);
        String numbers = "clip: a=" + describe(level, aFrom, aArrow) + " b=" + describe(level, bFrom, bArrow)
                + " c=" + describe(level, cFrom, cArrow);
        System.out.println("P11_SABLE_CLIP | " + (expectRed ? "RED " : "") + numbers);
        // Without the bridge the object rests half a block higher, above this ray.
        if (expectRed) {
            require(cArrow.getType() == HitResult.Type.MISS, "clip_c_red_arrow_clears_the_band " + numbers);
            require(cSight.getType() == HitResult.Type.MISS, "clip_c_red_sight_clears_the_band " + numbers);
        } else {
            require(isObjectHitAt(level, cFrom, cArrow, 3.0d), "clip_c_arrow_stops_at_the_object " + numbers);
            require(isObjectHitAt(level, cFrom, cSight, 3.0d), "clip_c_sight_stops_at_the_object "
                    + describe(level, cFrom, cSight));
        }
        return numbers;
    }

    private static ClipContext collider(Vec3 from, Vec3 to) {
        return new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
    }

    private static Vec3 position(ServerSubLevel object) {
        return new Vec3(object.logicalPose().position().x(), object.logicalPose().position().y(),
                object.logicalPose().position().z());
    }

    private static boolean isObjectHitAt(ServerLevel level, Vec3 from, BlockHitResult hit, double distance) {
        return SableHitGeometry.isSubLevelHit(level, hit)
                && near(Math.sqrt(SableHitGeometry.distanceSq(level, from, hit.getLocation())), distance);
    }

    private static String describe(ServerLevel level, Vec3 from, BlockHitResult hit) {
        if (hit.getType() == HitResult.Type.MISS) {
            return "MISS";
        }
        BlockPos pos = hit.getBlockPos();
        return String.format(Locale.ROOT, "%s{object=%s,dy=%.2f,distance=%.2f}",
                level.getBlockState(pos).getBlock().getName().getString().replace(' ', '_'),
                SableHitGeometry.isSubLevelHit(level, hit),
                SlabSupport.getYOffset(level, pos, level.getBlockState(pos)),
                Math.sqrt(SableHitGeometry.distanceSq(level, from, hit.getLocation())));
    }

    /**
     * Diagnostic only: the cost of uploading one solid section (terrain interior, the common case)
     * with the bridge on versus the RED lane's bridge off. Printed, never asserted.
     */
    private static void measureSectionCost(ServerLevel level, ServerSubLevelContainer container, BlockPos spawn) {
        int sectionX = (spawn.getX() >> 4) + 2;
        int sectionY = (ground >> 4) + 2;
        int sectionZ = spawn.getZ() >> 4;
        BlockPos origin = new BlockPos(sectionX << 4, sectionY << 4, sectionZ << 4);
        // Buried like terrain interior: the measured section stands on another solid section.
        for (int x = 0; x < 16; x++) {
            for (int y = -16; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        var chunk = level.getChunkAt(origin);
        var section = chunk.getSection(chunk.getSectionIndex(origin.getY()));
        var pipeline = container.physicsSystem().getPipeline();
        for (int warm = 0; warm < 5; warm++) {
            pipeline.handleChunkSectionAddition(section, sectionX, sectionY, sectionZ, false);
        }
        int runs = 20;
        long start = System.nanoTime();
        for (int run = 0; run < runs; run++) {
            pipeline.handleChunkSectionAddition(section, sectionX, sectionY, sectionZ, false);
        }
        long perSection = (System.nanoTime() - start) / runs;
        // A lowered block elsewhere in the same chunk forces the per-cell pass: the worst case.
        BlockPos marker = origin.offset(8, 20, 8);
        SlabPlacementHeightAttachment.putHalfSteps(chunk, marker, -1);
        for (int warm = 0; warm < 5; warm++) {
            pipeline.handleChunkSectionAddition(section, sectionX, sectionY, sectionZ, false);
        }
        start = System.nanoTime();
        for (int run = 0; run < runs; run++) {
            pipeline.handleChunkSectionAddition(section, sectionX, sectionY, sectionZ, false);
        }
        long perNearLowered = (System.nanoTime() - start) / runs;
        SlabPlacementHeightAttachment.remove(chunk, marker);
        System.out.println("P11_SABLE_COST | " + (expectRed ? "bridge off" : "bridge on")
                + " | solid section upload " + perSection / 1000 + " us"
                + " | same section in a chunk with a lowered block " + perNearLowered / 1000 + " us");
    }

    /** Stone on stone, both through real item use: the upper block stores a flush fact. */
    private static void buildFlushCell(ServerLevel level, Player player, BlockPos cell) {
        level.setBlock(cell, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        use(level, player, Blocks.STONE, cell, cell.getY() + 1.0d);
    }

    /** A bottom slab on the ground, then stone on the slab: the stone stores a -0.5 fact. */
    private static void buildLoweredCell(ServerLevel level, Player player, BlockPos cell) {
        use(level, player, Blocks.STONE_SLAB, cell.below(), cell.getY());
        use(level, player, Blocks.STONE, cell, cell.getY() + 0.5d);
    }

    private static void use(ServerLevel level, Player player, Block held, BlockPos clicked, double hitY) {
        ItemStack stack = new ItemStack(held.asItem());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        InteractionResult result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(new Vec3(clicked.getX() + 0.5d, hitY, clicked.getZ() + 0.5d),
                        Direction.UP, clicked, false)));
        require(result != null && result.consumesAction(), "placement_" + held + "_at_" + clicked.toShortString());
    }

    private static ServerSubLevel drop(ServerLevel level, BlockPos at) {
        level.setBlock(at, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
        ServerSubLevel object = SubLevelAssemblyHelper.assembleBlocks(level, at, List.of(at),
                new BoundingBox3i(at.getX(), at.getY(), at.getZ(), at.getX(), at.getY(), at.getZ()));
        require(object != null && !object.isRemoved(), "assembled_" + at.toShortString());
        return object;
    }

    /** The object's center of mass; a one-block object resting on a surface sits half a block above it. */
    private static double restingY(ServerSubLevel object) {
        require(!object.isRemoved(), "object_still_present");
        return object.logicalPose().position().y();
    }

    private static void requireDy(ServerLevel level, BlockPos pos, double expected, String check) {
        double dy = SlabSupport.getYOffset(level, pos, level.getBlockState(pos));
        require(Math.abs(dy - expected) < 1.0e-6d, check + " dy=" + dy);
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) <= TOLERANCE;
    }

    private static void require(boolean condition, String check) {
        if (!condition) {
            throw new IllegalStateException("P11 Sable proof check failed: " + check);
        }
    }

    private static void fail(MinecraftServer server, Throwable t) {
        if (done) {
            return;
        }
        done = true;
        System.out.println("P11_SABLE | FAILED " + t.getMessage());
        writeReceipt("p11-sable.failed", String.valueOf(t.getMessage()) + "\n");
        server.halt(false);
    }

    private static void writeReceipt(String fileName, String content) {
        try {
            Path proofDirectory = Path.of("proof");
            Files.createDirectories(proofDirectory);
            Files.writeString(proofDirectory.resolve(fileName), content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write the P11 Sable proof receipt", exception);
        }
    }
}
