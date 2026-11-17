package com.wgaccel.fabric;

import com.wgaccel.fabric.mixin.BiomeAccessor;
import com.wgaccel.platform.LoaderHooks;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/** {@link LoaderHooks} on Fabric: vanilla's LevelChunkSection; Fabric API's biome modifications rewrite the biome's own climate. */
public final class FabricHooks implements LoaderHooks {
   @Override
   public boolean isEmpty(BlockState state) {
      return state.isAir();
   }

   @Override
   public Biome.ClimateSettings climate(Biome biome) {
      return ((BiomeAccessor)(Object)biome).wgaccel$climateSettings();
   }
}
