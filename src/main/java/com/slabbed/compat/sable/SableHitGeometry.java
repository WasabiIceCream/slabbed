package com.slabbed.compat.sable;

import com.slabbed.util.SlabbedOffsetRaycast;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Where a pick hit on a Sable sub-level really is, answered by Sable's own companion API.
 *
 * <p>Sable reports a hit on a sub-level at the position the sub-level is stored in its plot grid,
 * not where it is drawn, so that hit's plain distance from the eye is meaningless. Sable measures
 * such hits with its own distance; so does Slabbed. Only loaded when Sable is.
 */
public final class SableHitGeometry {
    private SableHitGeometry() {
    }

    /**
     * Composes Sable's pick with Slabbed's offset raycast. A sub-level hit competes with the
     * Slabbed hit by Sable's distance. A world hit from Sable's pick never overrides the Slabbed
     * hit, which is already the nearest world hit — without Sable the pick is Slabbed's alone.
     */
    public static HitResult composePick(Level level, Vec3 eye, HitResult sableHit, BlockHitResult slabbedHit) {
        if (!isSubLevelHit(level, sableHit)) {
            return slabbedHit;
        }
        return SlabbedOffsetRaycast.selectNearestOwnedHit(eye, sableHit, slabbedHit,
                pos -> distanceSq(level, eye, pos));
    }

    /** True for a block hit inside Sable's plot grid, i.e. on a sub-level rather than the world. */
    public static boolean isSubLevelHit(Level level, HitResult hit) {
        return hit instanceof BlockHitResult block
                && hit.getType() == HitResult.Type.BLOCK
                && SableCompanion.INSTANCE.isInPlotGrid(level, block.getBlockPos());
    }

    /** Squared distance from {@code eye} to {@code pos}, with {@code pos} taken out of any sub-level. */
    public static double distanceSq(Level level, Vec3 eye, Vec3 pos) {
        return SableCompanion.INSTANCE.distanceSquaredWithSubLevels(level, eye, pos);
    }

    /**
     * Sable's clip of {@code context}'s ray against its sub-levels alone, keeping the original
     * clip's sub-level filters. For a Slabbed re-march, which sees only the world: whatever it
     * returns must still compete with a sub-level the same ray crosses.
     *
     * <p>Clips with {@code COLLIDER} and {@code Fluid.NONE}, the modes of every Slabbed seam that
     * re-marches ({@link com.slabbed.util.SlabbedOffsetColliderClip}); {@link ClipContext} does not
     * expose the original's modes.
     */
    public static BlockHitResult clipSubLevels(Level level, ClipContext context, CollisionContext shapeContext) {
        ClipContext subLevelsOnly = new ClipContext(context.getFrom(), context.getTo(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shapeContext);
        ClipContextExtension original = (ClipContextExtension) context;
        ClipContextExtension copy = (ClipContextExtension) subLevelsOnly;
        copy.sable$setIgnoredSubLevel(original.sable$getIgnoredSubLevel());
        copy.sable$setSubLevelIgnoring(original.sable$getSubLevelIgnoring());
        copy.sable$setDoNotProject(original.sable$doNotProject());
        copy.sable$setIgnoreMainLevel(true);
        return level.clip(subLevelsOnly);
    }
}
