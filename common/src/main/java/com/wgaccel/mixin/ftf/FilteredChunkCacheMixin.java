package com.wgaccel.mixin.ftf;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import java.util.concurrent.atomic.AtomicBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * A bigger cache for FreeTerraForged's filtered chunk cells (1.0.0013 and later, config {@code ftfFilteredChunkCache}): its
 * FilteredChunkCache keeps MAX_ENTRIES chunks (2,048, inlined into generateRegion's eviction test) and drops the oldest
 * past that, and a chunk asked for after its drop makes its whole 4 x 4 region again. The 1 Nov 2026 runs (1.0.0018, r1000,
 * about 1,100 regions needed) made 1,479 regions. The cells are unchanged.
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.cell.heightmap.FilteredChunkCache")
public abstract class FilteredChunkCacheMixin {
   @Unique
   private static final AtomicBoolean wgaccel$logged = new AtomicBoolean();

   @ModifyConstant(method = "generateRegion", constant = @Constant(intValue = 2048))
   private int wgaccel$capacity(int own) {
      int wanted = Config.FTF_FILTERED_CHUNK_CACHE.getAsInt();
      int capacity = wanted > 0 ? Math.max(own, wanted) : own;
      if (wgaccel$logged.compareAndSet(false, true)) {
         WorldgenAccelerator.LOG.info("WGA FreeTerraForged filtered chunk cache: {} chunks (FreeTerraForged's own size {})", capacity, own);
      }

      return capacity;
   }
}
