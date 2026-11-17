package com.wgaccel.neoforge;

import com.wgaccel.platform.LoaderHooks;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/** {@link LoaderHooks} on NeoForge: its patched LevelChunkSection and its biome modifiers. */
public final class NeoForgeHooks implements LoaderHooks {
   @Override
   public boolean isEmpty(BlockState state) {
      return state.isEmpty();
   }

   @Override
   public Biome.ClimateSettings climate(Biome biome) {
      return biome.getModifiedClimateSettings();
   }
}
