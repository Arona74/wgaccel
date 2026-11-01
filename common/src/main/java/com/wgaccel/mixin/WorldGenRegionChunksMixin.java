package com.wgaccel.mixin;

import com.wgaccel.gen.RegionChunks;
import com.wgaccel.gen.RegionExtras;
import com.wgaccel.gen.RegionHeights;
import com.wgaccel.platform.Mc;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

   /**
    * The features step's worldgen heights kept once read ({@link RegionHeights}), then the region's own answer as in
    * Minecraft (the chunk's height + 1). An overwrite: until 0.61.7 a cancellable inject at its head, which made a
    * CallbackInfoReturnable and boxed the height at every call (1 Nov 2026 Conquest profile: getHeight 5% of all samples,
    * most of it that); a redirect of the chunk's height in it still looked the chunk up first (as costly). No other mod
    * of the Conquest or FreeTerraForged packs changes this method.
    *
    * @author wgaccel
    * @reason kept heights without an object per call
    */
   @Overwrite
   public int getHeight(Heightmap.Types type, int x, int z) {
      RegionHeights.Table table = this.wgaccel$heights;
      if (table == null && this.wgaccel$features && RegionHeights.on()) {
         ChunkPos pos = this.center.getPos();
         table = this.wgaccel$heights = RegionHeights.table(Mc.x(pos), Mc.z(pos), this.generatingStep.directDependencies().size() - 1);
      }

      int[] heights = table == null || RegionHeights.off() ? null : RegionHeights.heights(table, type);
      int i = heights == null ? -1 : RegionHeights.index(table, x, z);
      int kept = i < 0 ? 0 : heights[i];
      if (i >= 0 && RegionHeights.known(kept) && (++this.wgaccel$heightCalls & 255) != 0) {
         return kept;
      }

      int real = ((WorldGenRegion)(Object)this).getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)).getHeight(type, x & 15, z & 15) + 1;
      if (i >= 0) {
         if (RegionHeights.known(kept)) {
            return RegionHeights.verify(kept, real, type, x, z);
         }

         heights[i] = real;
      }

      return real;
   }
}
