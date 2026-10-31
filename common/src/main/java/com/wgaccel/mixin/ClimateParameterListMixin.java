package com.wgaccel.mixin;

import com.wgaccel.gen.ClimateSearch;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A biome table's search on the flat copy of its R-tree ({@link ClimateSearch}): its answer at HEAD; a compared call
 * runs vanilla's search and is compared at RETURN. Only for the table class itself (a subclass may search its own way).
 */
@Mixin(Climate.ParameterList.class)
public abstract class ClimateParameterListMixin {
   @Unique
   private volatile ClimateSearch wgaccel$search;

   @Unique
   private ClimateSearch wgaccel$search() {
      ClimateSearch search = this.wgaccel$search;
      if (search == null) {
         synchronized (this) {
            search = this.wgaccel$search;
            if (search == null) {
               search = (Object)this.getClass() == Climate.ParameterList.class ? ClimateSearch.of((Climate.ParameterList<?>)(Object)this) : ClimateSearch.NONE;
               this.wgaccel$search = search;
            }
         }
      }

      return search;
   }

   @Inject(method = "findValueIndex(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Ljava/lang/Object;", at = @At("HEAD"), cancellable = true)
   private void wgaccel$find(Climate.TargetPoint target, CallbackInfoReturnable<Object> info) {
      if (!ClimateSearch.on()) {
         return;
      }

      ClimateSearch search = this.wgaccel$search();
      if (search != ClimateSearch.NONE) {
         Object found = search.find(target);
         if (found != null) {
            info.setReturnValue(found);
         }
      }
   }

   @Inject(method = "findValueIndex(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Ljava/lang/Object;", at = @At("RETURN"))
   private void wgaccel$compare(Climate.TargetPoint target, CallbackInfoReturnable<Object> info) {
      ClimateSearch search = this.wgaccel$search;
      if (search != null && search != ClimateSearch.NONE) {
         search.compare(info.getReturnValue());
      }
   }
}
