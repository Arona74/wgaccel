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
 * game's way. Each lookup is still a few field reads, billions of times (8% of all CPU samples in a Conquest profile,
 * 28 Oct 2026), so the region decides once whether it keeps chunks (a features region), compares every 256th lookup by
 * its own counter, and hands the last chunk that can no longer change out of two fields before the table.
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
   /** A features region (set as the region is made): only those keep chunks and heights. */
   @Unique
   private boolean wgaccel$features;
   /** Lookups and height reads so far, to compare every 256th. */
   @Unique
   private int wgaccel$calls;
   @Unique
   private int wgaccel$heightCalls;
   /** The last kept chunk that can no longer change, and its position. */
   @Unique
   private ChunkAccess wgaccel$last;
   @Unique
   private int wgaccel$lastX;
   @Unique
   private int wgaccel$lastZ;

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
         this.wgaccel$features = true;
         Mc.featuresStart((WorldGenRegion)(Object)this);
      }
   }

   @Shadow
   public abstract ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean load);

   @Shadow
   public abstract int getHeight(Heightmap.Types type, int x, int z);

   public ChunkAccess getChunk(int x, int z, ChunkStatus status) {
      if (!this.wgaccel$features || status != ChunkStatus.EMPTY || !RegionChunks.on()) {
         return this.getChunk(x, z, status, true);
      }

      boolean compared = (++this.wgaccel$calls & 255) == 0;
      ChunkAccess last = this.wgaccel$last;
      if (last != null && x == this.wgaccel$lastX && z == this.wgaccel$lastZ) {
         return compared ? RegionChunks.verify(last, this.getChunk(x, z, status, true), x, z, this) : last;
      }

      RegionChunks.Entry[] table = this.wgaccel$chunks;
      if (table == null) {
         table = this.wgaccel$chunks = RegionChunks.table();
      }

      RegionChunks.Entry entry = RegionChunks.kept(table, x, z);
      if (entry != null) {
         if (entry.stable()) {
            this.wgaccel$last = entry.chunk();
            this.wgaccel$lastX = x;
            this.wgaccel$lastZ = z;
         }

         return compared ? RegionChunks.verify(entry.chunk(), this.getChunk(x, z, status, true), x, z, this) : entry.chunk();
      }

      return RegionChunks.keep(table, x, z, this.getChunk(x, z, status, true));
   }

   /** The features step's worldgen heights kept once read ({@link RegionHeights}); the region's own answer is this method again, bypassed. */
   @Inject(method = "getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I", at = @At("HEAD"), cancellable = true, require = 0)
   private void wgaccel$height(Heightmap.Types type, int x, int z, CallbackInfoReturnable<Integer> cir) {
      if (this.wgaccel$heightRead || !this.wgaccel$features || !RegionHeights.on()) {
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
      if (RegionHeights.known(kept) && (++this.wgaccel$heightCalls & 255) != 0) {
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
