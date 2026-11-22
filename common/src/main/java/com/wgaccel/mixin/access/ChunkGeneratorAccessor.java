package com.wgaccel.mixin.access;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The generator's features per decoration step, with each feature's index there (what seeds its random). */
@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorAccessor {
   @Accessor("featuresPerStep")
   Supplier<List<FeatureSorter.StepFeatureData>> wgaccel$featuresPerStep();
}
