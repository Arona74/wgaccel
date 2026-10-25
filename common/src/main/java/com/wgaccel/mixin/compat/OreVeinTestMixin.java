package com.wgaccel.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.wgaccel.gen.VeinTests;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** No Man's Land's ore vein target tests answered from the block just read ({@link VeinTests}). */
@Pseudo
@Mixin(targets = "com.farcr.nomansland.common.world.orevein.OreVeinSystem")
public abstract class OreVeinTestMixin {
   @WrapOperation(method = "fillColumnForVein", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/blockpredicates/BlockPredicate;test(Ljava/lang/Object;Ljava/lang/Object;)Z"))
   private static boolean wgaccel$test(BlockPredicate predicate, Object level, Object pos, Operation<Boolean> original,
         @Local(ordinal = 0) BlockState current) {
      return VeinTests.test(predicate, current, () -> original.call(predicate, level, pos));
   }
}
