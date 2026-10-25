package com.wgaccel.mixin.access;

import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A section's block counts, compared by the GPU noise fill check (step G2.5) without taking the section's lock, and set
 * for sections made at once (S3) as block-by-block writing would have counted them.
 */
@Mixin(LevelChunkSection.class)
public interface LevelChunkSectionAccessor {
   @Accessor("nonEmptyBlockCount")
   short wgaccel$nonEmpty();

   @Accessor("tickingBlockCount")
   short wgaccel$tickingBlocks();

   @Accessor("tickingFluidCount")
   short wgaccel$tickingFluids();

   @Accessor("nonEmptyBlockCount")
   void wgaccel$setNonEmpty(short count);

   @Accessor("tickingBlockCount")
   void wgaccel$setTickingBlocks(short count);

   @Accessor("tickingFluidCount")
   void wgaccel$setTickingFluids(short count);
}
