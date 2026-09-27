package com.slabbed.mixin;

import com.slabbed.util.HangingSeatDyHolder;
import com.slabbed.util.SlabSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A hung decoration REMEMBERS the height of the face it was hung on (LAW.md Law 1; maintainer
 * ruling, 2026-09-13: where it is placed is where it stays, for everything hung on a wall).
 *
 * <p>The seat is MINTED ONCE — when the decoration is first told which way it faces on the server,
 * with its chunks ready, which is the moment the item hangs it — from the support's stored
 * height, and is then carried in synced entity data and in save data. Every later layout reads the
 * remembered number verbatim; nothing here re-reads the support. Rebuilding the wall behind a hung
 * frame at a different height is a change to the WALL, not to the frame.
 *
 * <p>The entity's REAL position deliberately stays at grid height: moving it corrupts the derived
 * grid cell and {@code survives()} judges the wrong support. Only the bounding box shifts here, and
 * the render layer applies the same remembered seat to the drawing. Vanilla's {@code
 * recalculateBoundingBox} sets the position from the unshifted box first, so the shift is applied
 * at its TAIL and fires exactly once per layout; on this version that method is final on
 * {@code HangingEntity}, so frames and paintings share it. Every layout recomputes the box from the
 * cell and the facing before the shift, so laying out again never stacks a second shift.
 *
 * <p>A decoration saved before this seat existed carries no number; after its data is restored,
 * ready chunks allow a mint from its wall, otherwise a later server tick retries. That is a
 * one-time migration, not a re-derivation.
 *
 * <p>Entity loading must never wait on a chunk. Save data may be read inside chunk promotion, where
 * a blocking chunk request for a neighbour cannot finish until the promotion running it has
 * returned. So nothing is minted while save data is read, and a mint only reads chunks that are
 * already loaded — never the chunk this loader is still promoting, whose neighbours may not be.
 *
 * <p>Two traps this shape avoids. (1) Minting DURING a layout locks onto the default facing: a
 * painting's variant is restored before its facing is, and that restore lays the box out with the
 * default SOUTH direction, so a mint there would read the wrong wall. The mint therefore hangs off
 * the facing setter (and the post-read and tick retries), never off the layout. (2) Re-laying the
 * box out when the synced value changes is CLIENT-ONLY: the server's own layout after the mint
 * already applies it.
 *
 * <p>On this version {@code HangingEntity} declares no synched-data, facing, save or data-update
 * hook of its own, and both hung classes override those without calling super. The per-class
 * mixins forward into the bridge methods below; this class stays the only implementation.
 */
@Mixin(HangingEntity.class)
public abstract class HangingEntityRememberedSeatMixin extends BlockAttachedEntity implements HangingSeatDyHolder {

    /** Raw bits of the seat; NaN bits mean "not minted yet". Synced so the client box and drawing agree. */
    @Unique
    private static final EntityDataAccessor<Long> SLABBED$HANG_DY =
            SynchedEntityData.defineId(HangingEntity.class, EntityDataSerializers.LONG);

    @Unique
    private static final long SLABBED$UNSET = Double.doubleToRawLongBits(Double.NaN);

    /**
     * Whether this instance's synched data actually carries the seat. Deliberately declared WITHOUT
     * an initializer: the declaring hook runs from the entity constructor, before an initializer
     * would reset it. A hung class that never forwards the declaration (a third-party subclass of
     * {@code HangingEntity} that writes its own synched-data hook) keeps vanilla behaviour instead
     * of reading an accessor that was never allocated.
     */
    @Unique
    private boolean slabbed$hangSeatDeclared;

    /** True only while save data is being read; a mint waits until the read has finished. */
    @Unique
    private boolean slabbed$readingData;

    @Unique
    private AABB slabbed$shiftedBoxDuringSurvival;

    protected HangingEntityRememberedSeatMixin(EntityType<? extends BlockAttachedEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public double slabbed$hangSeatDy() {
        if (!this.slabbed$hangSeatDeclared) {
            return 0.0d;
        }
        double dy = Double.longBitsToDouble(this.getEntityData().get(SLABBED$HANG_DY));
        return Double.isFinite(dy) ? dy : 0.0d;
    }

    @Override
    public boolean slabbed$hasHangSeat() {
        return this.slabbed$hangSeatDeclared
                && Double.isFinite(Double.longBitsToDouble(this.getEntityData().get(SLABBED$HANG_DY)));
    }

    @Override
    public void slabbed$restoreHangSeatDy(double dy) {
        if (!this.slabbed$hangSeatDeclared) {
            return;
        }
        this.getEntityData().set(SLABBED$HANG_DY, Double.doubleToRawLongBits(Double.isFinite(dy) ? dy : 0.0d));
    }

    @Override
    public void slabbed$defineHangSeat(SynchedEntityData.Builder builder) {
        builder.define(SLABBED$HANG_DY, SLABBED$UNSET);
        this.slabbed$hangSeatDeclared = true;
    }

    /**
     * The ONE derivation, at the moment the decoration learns which way it faces: both the item's
     * hang path and the load path set the facing with the position already known. The load path
     * is held back until its read has finished (see {@link #load}); an unready chunk defers the
     * mint to a later tick, never to a guessed "flush" seat. A saved seat restored by the per-class
     * read hook wins over this mint.
     *
     * <p>On this version the facing setter lays the box out BEFORE its tail, so a freshly minted
     * seat needs one layout of its own. That layout recomputes the box from the cell and the facing
     * and then applies the seat once — it does not stack onto the box already there.
     */
    @Override
    public void slabbed$mintHangSeatFromWall() {
        if (this.slabbed$tryMintHangSeat()) {
            this.recalculateBoundingBox();
        }
    }

    /**
     * Save data may be read inside chunk promotion; only restore saved seats until it finishes.
     * The post-read mint also needs the chunk of the SAVED centre to be ready: that is the chunk the
     * entity was stored in, so a read running inside that chunk's promotion never mints, even when
     * the restored data moves the centre (a painting whose saved variant no longer exists falls back
     * to another size) into a chunk that is already loaded.
     */
    @Override
    public void load(CompoundTag tag) {
        ListTag savedPos = tag.getList("Pos", Tag.TAG_DOUBLE);
        BlockPos savedCentre = savedPos.size() == 3
                ? BlockPos.containing(savedPos.getDouble(0), savedPos.getDouble(1), savedPos.getDouble(2))
                : this.blockPosition();
        this.slabbed$readingData = true;
        try {
            super.load(tag);
        } finally {
            this.slabbed$readingData = false;
        }
        // The restored variant can put a painting's centre in a different chunk from its attachment.
        if (this.level() instanceof ServerLevel level && level.getServer().isSameThread()
                && slabbed$readyChunk(level, savedCentre) != null
                && slabbed$readyChunk(level, this.blockPosition()) != null
                && this.slabbed$tryMintHangSeat()) {
            this.recalculateBoundingBox();
        }
    }

    /** A decoration whose chunks were not ready when it was read mints on its first ready tick. */
    @Override
    public void tick() {
        if (this.slabbed$tryMintHangSeat()) {
            this.recalculateBoundingBox();
        }
        super.tick();
    }

    /**
     * Mints the seat when nothing is being read, none exists yet, and the decoration's cell and
     * support are both in chunks that are already loaded. Reads the support from its chunk object
     * and never asks the level to load anything. Returns whether a seat was written; the caller
     * lays the box out.
     */
    @Unique
    private boolean slabbed$tryMintHangSeat() {
        if (!this.slabbed$hangSeatDeclared || this.slabbed$readingData || this.slabbed$hasHangSeat()
                || this.pos == null || this.getDirection() == null) {
            return false;
        }
        if (!(this.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            return false;
        }
        if (slabbed$readyChunk(level, this.pos) == null) {
            return false;
        }
        BlockPos supportPos = this.pos.relative(this.getDirection().getOpposite());
        LevelChunk supportChunk = slabbed$readyChunk(level, supportPos);
        if (supportChunk == null) {
            return false;
        }
        BlockState support = supportChunk.getBlockState(supportPos);
        double dy = SlabSupport.getYOffset(level, supportPos, support);
        this.slabbed$restoreHangSeatDy(Double.isFinite(dy) ? dy : 0.0d);
        return true;
    }

    /**
     * A loaded chunk, or null. Never blocks: {@code getChunkNow} only answers from what is already
     * there, and it is asked FIRST because it is the chunk source's own answer — a mod that serves
     * its own chunks through the chunk source (with no holder in the chunk map) keeps working, so
     * a missing holder never counts as "not ready". A chunk this loader is still promoting is NOT
     * ready even though {@code getChunkNow} hands it out on this loader: minting inside that
     * promotion lets the height resolver read a neighbouring chunk, and that read would wait on a
     * promotion queued behind this one.
     */
    @Unique
    @Nullable
    private static LevelChunk slabbed$readyChunk(ServerLevel level, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        ServerChunkCache chunks = level.getChunkSource();
        LevelChunk chunk = chunks.getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return null;
        }
        ChunkHolder holder = chunks.chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
        if (holder != null && holder.currentlyLoading != null) {
            return null;
        }
        return chunk;
    }

    /**
     * The CLIENT lays its box out from the spawn packet before the seat arrives; re-lay it when it
     * does. Server side the layout that follows the mint already applies it.
     */
    @Override
    public void slabbed$onHangSeatDataUpdated(EntityDataAccessor<?> key) {
        if (SLABBED$HANG_DY.equals(key) && this.level() != null && this.level().isClientSide()
                && this.pos != null && this.getDirection() != null) {
            this.recalculateBoundingBox();
        }
    }

    /** A painting reaches the shared facing setter; an item frame overrides it and forwards itself. */
    @Inject(method = "setDirection(Lnet/minecraft/core/Direction;)V", at = @At("TAIL"))
    private void slabbed$mintSeatOnDirection(CallbackInfo ci) {
        this.slabbed$mintHangSeatFromWall();
    }

    /** Apply the remembered seat to the freshly laid-out box; the position set just before stays on the grid. */
    @Inject(method = "recalculateBoundingBox()V", at = @At("TAIL"))
    private void slabbed$hangBoxOnRememberedSeat(CallbackInfo ci) {
        double dy = this.slabbed$hangSeatDy();
        if (Math.abs(dy) >= 1.0e-6d) {
            this.setBoundingBox(this.getBoundingBox().move(0.0d, dy, 0.0d));
        }
    }

    /**
     * Popping law is untouched: a painting judges the GRID cells behind it, where its wall actually
     * is, not the cells behind its drawn box. The box is unshifted for the check and restored after.
     * (Item frames override {@code survives} on their own grid cell and never reach this.)
     */
    @Inject(method = "survives()Z", at = @At("HEAD"))
    private void slabbed$judgeSurvivalOnGridCells(CallbackInfoReturnable<Boolean> cir) {
        double dy = this.slabbed$hangSeatDy();
        if (Math.abs(dy) >= 1.0e-6d) {
            slabbed$shiftedBoxDuringSurvival = this.getBoundingBox();
            this.setBoundingBox(slabbed$shiftedBoxDuringSurvival.move(0.0d, -dy, 0.0d));
        }
    }

    @Inject(method = "survives()Z", at = @At("RETURN"))
    private void slabbed$restoreShiftedBoxAfterSurvival(CallbackInfoReturnable<Boolean> cir) {
        if (slabbed$shiftedBoxDuringSurvival != null) {
            this.setBoundingBox(slabbed$shiftedBoxDuringSurvival);
            slabbed$shiftedBoxDuringSurvival = null;
        }
    }
}
