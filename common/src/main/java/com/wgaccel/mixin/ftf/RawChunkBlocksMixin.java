package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import etcodehome.freeterraforged.world.worldgen.cell.Cell;
import com.wgaccel.Config;
import com.wgaccel.util.SingleFlight;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** FreeTerraForged 1.0.0013's raw chunk cells made 4 x 4 chunks at a time, the populators' noise on the GPU ({@link com.wgaccel.ftf.FtfRawBlocks}). */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.cell.heightmap.RawChunkCache")
public abstract class RawChunkBlocksMixin {
   @Shadow @Final private ConcurrentHashMap<Long, Cell[]> cache;
   @Unique private final SingleFlight<Long, Cell[]> wgaccel$rawFlights = new SingleFlight<>();

   @WrapMethod(method = "getRawChunk(II)[Letcodehome/freeterraforged/world/worldgen/cell/Cell;")
   private Cell[] wgaccel$blocks(int chunkX, int chunkZ, Operation<Cell[]> original) {
      long key = etcodehome.freeterraforged.world.worldgen.util.PosUtil.pack(chunkX, chunkZ);
      if (!Config.FTF_COALESCE_RAW_CHUNKS.get() || this.cache.containsKey(key)) {
         return com.wgaccel.ftf.FtfRawBlocks.get(this, chunkX, chunkZ, () -> original.call(chunkX, chunkZ));
      }
      return this.wgaccel$rawFlights.get(key,
         () -> com.wgaccel.ftf.FtfRawBlocks.get(this, chunkX, chunkZ, () -> original.call(chunkX, chunkZ)));
   }
}
