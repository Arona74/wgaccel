package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * S8 C1: the carvers' biome for each chunk within 8 (vanilla's applyCarvers asks the biome source for the biome at the
 * chunk's corner, y 0, once per chunk: 30% of the carvers' time with FreeTerraForged, 0.9.65's profile). A chunk past
 * the biome step already holds that biome (the biome step stores the biome source's answer for every quart, the GPU's
 * checked identical), so it is read from the chunk; other chunks ask the biome source as before. Every 64th read is
 * compared with the biome source's answer; a difference turns this off and the biome source's answer is used.
 */
public final class CarverBiomes {
   private static volatile boolean disabled;
   private static final LongAdder FROM_CHUNK = new LongAdder();
   private static final LongAdder ASKED = new LongAdder();
   private static final LongAdder CHECKED = new LongAdder();
   private static final LongAdder DIFFERENT = new LongAdder();
   private static final LongAdder ALL = new LongAdder();

   private CarverBiomes() {
   }

   /** The supplier applyCarvers hands to carverBiome for this chunk, or the original when off. */
   public static Supplier<BiomeGenerationSettings> supplier(ChunkGenerator generator, ChunkAccess chunk, Supplier<BiomeGenerationSettings> original) {
      if (disabled || !Config.GPU_CARVER_BIOMES.get()) {
         return original;
      }

      return () -> {
         ALL.increment();
         if (!chunk.getPersistedStatus().isOrAfter(ChunkStatus.BIOMES)) {
            ASKED.increment();
            return original.get();
         }

         int x = chunk.getPos().getMinBlockX();
         int z = chunk.getPos().getMinBlockZ();
         Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), 0, QuartPos.fromBlock(z));
         BiomeGenerationSettings settings = generator.getBiomeGenerationSettings(biome);
         FROM_CHUNK.increment();
         if (FROM_CHUNK.sum() % 64 == 1) {
            BiomeGenerationSettings asked = original.get();
            CHECKED.increment();
            if (asked != settings) {
               DIFFERENT.increment();
               disabled = true;
               WorldgenAccelerator.LOG.warn("WGA carver biomes: chunk {} holds {} at its corner, y 0, but the biome source's settings differ; off, the biome source is asked again",
                  chunk.getPos(), biome.getRegisteredName());
               return asked;
            }
         }

         long all = ALL.sum();
         if (all % 2048 == 0) {
            WorldgenAccelerator.LOG.info(summary());
         }

         return settings;
      };
   }

   public static String summary() {
      return String.format(Locale.ROOT, "WGA carver biomes: %,d chunks' carver biomes, %,d read from the chunk, %,d asked from the biome source; checked %,d: %s",
         ALL.sum(), FROM_CHUNK.sum(), ASKED.sum(), CHECKED.sum(), DIFFERENT.sum() == 0 ? "IDENTICAL" : DIFFERENT.sum() + " DIFFER (off)");
   }
}
