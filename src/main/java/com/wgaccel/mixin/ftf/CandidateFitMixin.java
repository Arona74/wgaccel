package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Pair;
import com.wgaccel.Config;
import com.wgaccel.gen.BiomeFill;
import com.wgaccel.gen.CandidateTree;
import etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * FreeTerraForged's closest table entries of the chosen biome and of any other (Biolith's sub-biome criteria ask for them
 * per cell) from {@link CandidateTree} instead of a scan of the whole table (config {@code fasterBiomeChoice}); the same
 * entries, checked against the scan every 4,096th time.
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.runtime.WorldgenPlans$CandidateFit")
public abstract class CandidateFitMixin {
   @WrapMethod(method = "find(Ljava/util/List;Lnet/minecraft/world/level/biome/Climate$TargetPoint;Lnet/minecraft/core/Holder;)Letcodehome/freeterraforged/world/worldgen/runtime/WorldgenPlans$CandidateFit;")
   private static WorldgenPlans.CandidateFit wgaccel$find(List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries, Climate.TargetPoint target,
         Holder<Biome> selected, Operation<WorldgenPlans.CandidateFit> original) {
      if (!Config.FASTER_BIOME_CHOICE.getAsBoolean() || entries.size() < 64) {
         return original.call(entries, target, selected);
      }

      CandidateTree tree = CandidateTree.of(entries);
      long[] found = tree.find(target, selected);
      if (found == null || found[0] < 0L) {
         return original.call(entries, target, selected);
      }

      int u = (int)found[0];
      int p = (int)found[2];
      WorldgenPlans.CandidateFit fit = new WorldgenPlans.CandidateFit(new WorldgenPlans.CandidateMatch(tree.point(u), tree.holder(u), found[1]),
         p < 0 ? Optional.empty() : Optional.of(new WorldgenPlans.CandidateMatch(tree.point(p), tree.holder(p), found[3])));
      if (CandidateTree.checkNow()) {
         WorldgenPlans.CandidateFit normal = original.call(entries, target, selected);
         BiomeFill.checked(same(normal.ultimate(), fit.ultimate()) && normal.penultimate().isPresent() == fit.penultimate().isPresent()
            && (normal.penultimate().isEmpty() || same(normal.penultimate().get(), fit.penultimate().get())), "Biolith candidate fit");
         return normal;
      }

      return fit;
   }

   private static boolean same(WorldgenPlans.CandidateMatch a, WorldgenPlans.CandidateMatch b) {
      return a.point() == b.point() && a.biome().equals(b.biome()) && a.distance() == b.distance();
   }
}
