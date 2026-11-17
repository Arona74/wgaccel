package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.gen.ClimateSearch;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * A biome table's search on the flat copy of its R-tree ({@link ClimateSearch}); compared calls run vanilla's search
 * via Operation.call without CallbackInfoReturnable allocation.
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

   @WrapMethod(method = "findValueIndex(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Ljava/lang/Object;")
   private Object wgaccel$find(Climate.TargetPoint target, Operation<Object> original) {
      if (!ClimateSearch.on()) {
         return original.call(target);
      }

      ClimateSearch search = this.wgaccel$search();
      if (search != ClimateSearch.NONE) {
         Object found = search.find(target);
         if (found != null) {
            return found;
         }

         Object real = original.call(target);
         search.compare(real);
         return real;
      }

      return original.call(target);
   }
}
