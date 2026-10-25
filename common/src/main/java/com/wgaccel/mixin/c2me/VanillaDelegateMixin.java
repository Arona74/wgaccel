package com.wgaccel.mixin.c2me;

import com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import com.ishland.c2me.rewrites.chunksystem.common.ChunkState;
import com.ishland.c2me.rewrites.chunksystem.common.statuses.VanillaWorldGenerationDelegate;
import com.ishland.flowsched.scheduler.ItemHolder;
import com.ishland.flowsched.scheduler.ItemStatus;
import com.ishland.flowsched.scheduler.KeyStatusPair;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.c2me.AreaStatus;
import com.wgaccel.c2me.AreaStep;
import java.util.Arrays;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The 4 x 4 area step's dependency: with gpuAreaStep on, a generating chunk's BIOMES step (outside its area's origin)
 * also waits for the origin to be at the area status. C2ME's delegate tells its generation and loading dependencies
 * apart by how many a chunk has now (getCurDepLen), so that count leaves the added one out.
 */
@Mixin(value = VanillaWorldGenerationDelegate.class, remap = false)
public abstract class VanillaDelegateMixin {
   @Shadow
   @Final
   private ChunkStatus status;

   @ModifyReturnValue(method = "getDependencies", at = @At("RETURN"))
   private KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] wgaccel$areaDependency(KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] dependencies,
         ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder) {
      AreaStatus area = AreaStatus.instance();
      if (this.status != ChunkStatus.BIOMES || area == null || !AreaStep.on()) {
         return dependencies;
      }

      // The same whatever the chunk's state (generating or loaded): C2ME releases what getDependencies answers when the
      // chunk goes down again (0.9.86 added it only while generating, so it was never released and the world could not
      // unload). The delegate's own generation/loading swap still sees its own count (getCurDepLen below).
      ChunkPos pos = holder.getKey();
      if (AreaStatus.isOrigin(pos)) {
         return dependencies;
      }

      KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] more = Arrays.copyOf(dependencies, dependencies.length + 1);
      more[dependencies.length] = new KeyStatusPair<>(AreaStatus.origin(pos), area);
      return more;
   }

   @WrapOperation(method = "getCurDepLen", at = @At(value = "INVOKE", target = "Lcom/ishland/flowsched/scheduler/ItemHolder;getDependencies(Lcom/ishland/flowsched/scheduler/ItemStatus;)[Lcom/ishland/flowsched/scheduler/KeyStatusPair;"))
   private KeyStatusPair<?, ?, ?>[] wgaccel$withoutAreaDependency(ItemHolder<?, ?, ?, ?> holder, ItemStatus<?, ?, ?> status, Operation<KeyStatusPair<?, ?, ?>[]> original) {
      KeyStatusPair<?, ?, ?>[] dependencies = original.call(holder, status);
      AreaStatus area = AreaStatus.instance();
      if (area == null || dependencies == null) {
         return dependencies;
      }

      int ours = 0;
      for (KeyStatusPair<?, ?, ?> dependency : dependencies) {
         if (dependency.status() == area) {
            ours++;
         }
      }

      if (ours == 0) {
         return dependencies;
      }

      return Arrays.stream(dependencies).filter(dependency -> dependency.status() != area).toArray(KeyStatusPair[]::new);
   }
}
