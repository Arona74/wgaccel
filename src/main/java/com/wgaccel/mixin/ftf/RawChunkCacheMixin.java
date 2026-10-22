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
 * A bigger cache for FreeTerraForged 1.0.0013's raw chunk cells (config {@code ftfRawChunkCache}): its RawChunkCache keeps
 * MAX_ENTRIES = 1024 chunks (inlined into getRawChunk's eviction test) and drops the oldest past that, so a pregeneration
 * front wider than that makes chunks' raw cells again (the 23 Oct 2026 profile: ~6,000 of 22,745 misses). The cells are
 * unchanged.
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.cell.heightmap.RawChunkCache")
public abstract class RawChunkCacheMixin {
   @Unique
   private static final AtomicBoolean wgaccel$logged = new AtomicBoolean();

   @ModifyConstant(method = "getRawChunk", constant = @Constant(intValue = 1024))
   private int wgaccel$capacity(int own) {
      int wanted = Config.FTF_RAW_CHUNK_CACHE.getAsInt();
      int capacity = wanted > 0 ? Math.max(own, wanted) : own;
      if (wgaccel$logged.compareAndSet(false, true)) {
         WorldgenAccelerator.LOG.info("WGA FreeTerraForged raw chunk cache: {} chunks (FreeTerraForged's own size {})", capacity, own);
      }

      return capacity;
   }
}
