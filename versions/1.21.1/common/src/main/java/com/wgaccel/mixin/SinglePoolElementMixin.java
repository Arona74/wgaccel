package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wgaccel.gen.JigsawLists;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A jigsaw piece's rotated jigsaw blocks made once per template and rotation ({@link JigsawLists}); shuffled and sorted
 * as before. Here the call is filterBlocks(position, settings with only the rotation set, Blocks.JIGSAW, true).
 */
@Mixin(SinglePoolElement.class)
public abstract class SinglePoolElementMixin {
   @WrapOperation(method = "getShuffledJigsawBlocks", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;filterBlocks(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/world/level/block/Block;Z)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
   private ObjectArrayList<StructureTemplate.StructureBlockInfo> wgaccel$jigsaws(StructureTemplate template, BlockPos position, StructurePlaceSettings settings,
         Block block, boolean relative, Operation<ObjectArrayList<StructureTemplate.StructureBlockInfo>> original) {
      List<StructureTemplate.StructureBlockInfo> kept = JigsawLists.get(template, position, settings.getRotation(),
         () -> original.call(template, position, settings, block, relative));
      return kept != null ? new ObjectArrayList<>(kept) : original.call(template, position, settings, block, relative);
   }
}
