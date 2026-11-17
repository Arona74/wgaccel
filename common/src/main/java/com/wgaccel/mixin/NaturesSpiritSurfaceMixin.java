package com.wgaccel.mixin;

import com.wgaccel.Config;
import com.wgaccel.gen.GpuSurface;
import com.wgaccel.gen.NaturesSpiritPillars;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Coordinates Nature's Spirit {@code SurfaceBuilderMixin} pillar placement with {@link GpuSurface}:
 * skips redundant CPU pillar re-application when GPU surface already computed and wrote the pillars,
 * and removes the dead initial scan loop on CPU fallback columns while keeping {@code CHECK} mode unmodified.
 */
@Mixin(value = SurfaceSystem.class, priority = 2000)
public abstract class NaturesSpiritSurfaceMixin {
   @Shadow @Final private BlockState defaultBlock;

   @Inject(method = "naturespirit$placeSugiPillar(Lnet/minecraft/world/level/chunk/BlockColumn;IIILnet/minecraft/world/level/LevelHeightAccessor;)V",
      at = @At("HEAD"), cancellable = true, require = 0)
   private void wgaccel$sugiPillar(BlockColumn column, int x, int z, int surfaceY, LevelHeightAccessor level, CallbackInfo ci) {
      if (!(level instanceof ChunkAccess chunk)) {
         return;
      }

      if (GpuSurface.skipping(chunk)) {
         ci.cancel();
         return;
      }

      if (Config.NS_FAST_PILLARS.get() && !GpuSurface.checking(chunk)
            && NaturesSpiritPillars.fastCpuSugi((SurfaceSystem)(Object)this, column, this.defaultBlock, x, z, surfaceY, level)) {
         ci.cancel();
      }
   }

   @Inject(method = "naturespirit$placeStratifiedDesertPillar(Lnet/minecraft/world/level/chunk/BlockColumn;IIILnet/minecraft/world/level/LevelHeightAccessor;)V",
      at = @At("HEAD"), cancellable = true, require = 0)
   private void wgaccel$desertPillar(BlockColumn column, int x, int z, int surfaceY, LevelHeightAccessor level, CallbackInfo ci) {
      if (!(level instanceof ChunkAccess chunk)) {
         return;
      }

      if (GpuSurface.skipping(chunk)) {
         ci.cancel();
         return;
      }

      if (Config.NS_FAST_PILLARS.get() && !GpuSurface.checking(chunk)
            && NaturesSpiritPillars.fastCpuStratifiedDesert((SurfaceSystem)(Object)this, column, this.defaultBlock, x, z, surfaceY, level)) {
         ci.cancel();
      }
   }
}
