package com.slabbed.mixin;

import com.slabbed.util.HangingSeatDyHolder;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An item frame (glow frames included, which inherit this box math) hangs on the face it
 * REMEMBERS being hung on, and that seat survives a save and reload.
 *
 * <p>The seat itself - minted once when the frame is hung, carried in entity data, applied to the
 * bounding box - lives in {@code HangingEntityRememberedSeatMixin}, shared with paintings. This
 * class is the frame's half of two jobs:
 *
 * <ul>
 *   <li>PERSISTENCE. {@code HangingEntity} declares no save-data hooks, so each hung class writes
 *       and reads the number itself. A frame saved before the seat existed, or placed by world
 *       generation (which runs off the server thread and never mints), has no key and mints from
 *       its wall once its data is restored - at once when its chunks are ready, otherwise on a
 *       later server tick (one-time migration).</li>
 *   <li>RELAY. {@code ItemFrame} re-implements {@code defineSynchedData}, {@code setDirection},
 *       {@code recalculateBoundingBox} and {@code onSyncedDataUpdated} WITHOUT calling super, so
 *       the shared hooks on {@code HangingEntity} never run for a frame. Each relay below calls
 *       the one shared implementation; do not inline a second copy of any of them here.</li>
 * </ul>
 *
 * <p>The entity's real position stays at grid height (the box moves, the position does not);
 * moving it corrupts the derived grid cell and {@code survives()} judges the wrong support. Do NOT
 * re-add a per-read derivation from the support: the predecessor of this class recomputed the
 * support's height on every layout, which is "follow the support" - the violation itself.
 */
@Mixin(ItemFrame.class)
public abstract class ItemFrameWysiwygMixin extends HangingEntity {

    @Unique
    private static final String SLABBED$HANG_DY_KEY = "slabbed:hang_dy";

    protected ItemFrameWysiwygMixin(EntityType<? extends HangingEntity> type, Level level) {
        super(type, level);
    }

    @Inject(method = "defineSynchedData()V", at = @At("TAIL"))
    private void slabbed$defineHangSeat(CallbackInfo ci) {
        ((HangingSeatDyHolder) this).slabbed$declareHangSeatKey();
    }

    @Inject(method = "setDirection(Lnet/minecraft/core/Direction;)V", at = @At("HEAD"))
    private void slabbed$mintSeatOnDirection(Direction facing, CallbackInfo ci) {
        ((HangingSeatDyHolder) this).slabbed$mintHangSeatFor(facing);
    }

    @Inject(method = "recalculateBoundingBox()V", at = @At("TAIL"))
    private void slabbed$hangBoxOnRememberedSeat(CallbackInfo ci) {
        ((HangingSeatDyHolder) this).slabbed$seatHangBox();
    }

    @Inject(method = "onSyncedDataUpdated(Lnet/minecraft/network/syncher/EntityDataAccessor;)V", at = @At("TAIL"))
    private void slabbed$relayoutOnSyncedSeat(EntityDataAccessor<?> key, CallbackInfo ci) {
        ((HangingSeatDyHolder) this).slabbed$relayoutOnSyncedSeat(key);
    }

    @Inject(method = "addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
    private void slabbed$saveHangSeat(CompoundTag tag, CallbackInfo ci) {
        HangingSeatDyHolder holder = (HangingSeatDyHolder) this;
        if (holder.slabbed$hasHangSeat()) {
            tag.putDouble(SLABBED$HANG_DY_KEY, holder.slabbed$hangSeatDy());
        }
    }

    /** Vanilla re-lays the box while reading (direction); the seat arrives after, so lay it out again. */
    @Inject(method = "readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
    private void slabbed$loadHangSeat(CompoundTag tag, CallbackInfo ci) {
        if (!tag.contains(SLABBED$HANG_DY_KEY, Tag.TAG_DOUBLE)) {
            return;
        }
        double dy = tag.getDouble(SLABBED$HANG_DY_KEY);
        if (Double.isFinite(dy)) {
            ((HangingSeatDyHolder) this).slabbed$restoreHangSeatDy(dy);
            this.recalculateBoundingBox();
        }
    }
}
