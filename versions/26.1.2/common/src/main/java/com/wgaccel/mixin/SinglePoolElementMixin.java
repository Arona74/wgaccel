package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.gen.JigsawLists;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A jigsaw piece's rotated jigsaw blocks made once per template and rotation ({@link JigsawLists}); shuffled and sorted as before. */
@Mixin(SinglePoolElement.class)
public abstract class SinglePoolElementMixin {
   @WrapOperation(method = "getShuffledJigsawBlocks", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;getJigsaws(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Rotation;)Ljava/util/List;"))
   private List<StructureTemplate.JigsawBlockInfo> wgaccel$jigsaws(StructureTemplate template, BlockPos position, Rotation rotation,
         Operation<List<StructureTemplate.JigsawBlockInfo>> original) {
      List<StructureTemplate.JigsawBlockInfo> kept = JigsawLists.get(template, position, rotation, () -> original.call(template, position, rotation));
      return kept != null ? new ArrayList<>(kept) : original.call(template, position, rotation);
   }
}
