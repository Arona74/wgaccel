package com.wgaccel.mixin;

import com.wgaccel.gen.RegionChunks;
import com.wgaccel.gen.RegionExtras;
import com.wgaccel.gen.RegionHeights;
import com.wgaccel.platform.Mc;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The features step's region chunks kept once found ({@link RegionChunks}). The region's block reads, writes and
 * heights reach getChunk(x, z, status, load) through LevelReader's default getChunk(x, z, status): this overrides that
 * one, so no allocation per call (it was a @WrapMethod on the four-argument one, whose every call to the game's lookup
 * boxed its arguments into an Object[]: 13% of Lithos's time, 28 Oct 2026 profile). Direct four-argument callers go the
 * game's way.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionChunksMixin implements RegionExtras {
   @Shadow
   @Final
   private ChunkStep generatingStep;
   @Shadow
   @Final
   private ChunkAccess center;
   @Unique
   private RegionChunks.Entry[] wgaccel$chunks;
   @Unique
   private RegionHeights.Table wgaccel$heights;
   /** While the region's own getHeight runs for a height not kept yet (or compared). */
   @Unique
   private boolean wgaccel$heightRead;
   @Unique
   private Object wgaccel$extra;

   @Override
   public Object wgaccel$extra() {
      return this.wgaccel$extra;
   }

   @Override
   public void wgaccel$extra(Object extra) {
      this.wgaccel$extra = extra;
   }

   @Inject(method = "<init>", at = @At("TAIL"), require = 0)
   private void wgaccel$made(CallbackInfo info) {
      if (this.generatingStep.targetStatus() == ChunkStatus.FEATURES) {
         Mc.featuresStart((WorldGenRegion)(Object)this);
      }
   }

   @Shadow
   public abstract ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean load);

   @Shadow
   public abstract int getHeight(Heightmap.Types type, int x, int z);

   public ChunkAccess getChunk(int x, int z, ChunkStatus status) {
      if (this.generatingStep.targetStatus() != ChunkStatus.FEATURES) {
         return this.getChunk(x, z, status, true);
      }

      RegionChunks.Entry[] table = this.wgaccel$chunks;
      if (table == null) {
         // Made on first use (only features regions need one); a race makes two tables, each correct.
         table = this.wgaccel$chunks = RegionChunks.table();
      }

      ChunkAccess kept = RegionChunks.kept(table, x, z, status);
      if (kept != null) {
         return RegionChunks.sample() ? RegionChunks.verify(kept, this.getChunk(x, z, status, true), x, z, this) : kept;
      }

      return RegionChunks.keep(table, x, z, status, this.getChunk(x, z, status, true));
   }

   /** The features step's worldgen heights kept once read ({@link RegionHeights}); the region's own answer is this method again, bypassed. */
   @Inject(method = "getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I", at = @At("HEAD"), cancellable = true, require = 0)
   private void wgaccel$height(Heightmap.Types type, int x, int z, CallbackInfoReturnable<Integer> cir) {
      if (this.wgaccel$heightRead || this.generatingStep.targetStatus() != ChunkStatus.FEATURES || !RegionHeights.on()) {
         return;
      }

      RegionHeights.Table table = this.wgaccel$heights;
      if (table == null) {
         ChunkPos pos = this.center.getPos();
         table = this.wgaccel$heights = RegionHeights.table(Mc.x(pos), Mc.z(pos), this.generatingStep.directDependencies().size() - 1);
      }

      int[] heights = RegionHeights.heights(table, type);
      int i = heights != null ? RegionHeights.index(table, x, z) : -1;
      if (i < 0) {
         return;
      }

      int kept = heights[i];
      if (RegionHeights.known(kept) && !RegionHeights.sample()) {
         cir.setReturnValue(kept);
         return;
      }

      int real;
      this.wgaccel$heightRead = true;
      try {
         real = this.getHeight(type, x, z);
      } finally {
         this.wgaccel$heightRead = false;
      }

      if (RegionHeights.known(kept)) {
         real = RegionHeights.verify(kept, real, type, x, z);
      } else {
         heights[i] = real;
      }

      cir.setReturnValue(real);
   }
}
