package com.slabbed.compat.sable;

import com.slabbed.util.SlabbedOffsetRaycast;
import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

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
}
