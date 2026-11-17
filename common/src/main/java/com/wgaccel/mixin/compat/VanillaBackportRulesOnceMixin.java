package com.wgaccel.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/**
 * VanillaBackport's sulfur cave rules made once: its NoiseBasedChunkGenerator mixin calls ModSurfaceRuleData.makeRules and
 * merge at every chunk's buildSurface, each a new tree, so a chunk's tree was never the one the GPU surface was made from
 * and every chunk stayed on the CPU ("another rule source", vanilla world with VanillaBackport 1.2.1, 24 Oct 2026). The
 * trees hold no state (their rules are made per chunk by apply), so the same object is handed back for the same inputs,
 * here and to the GPU surface (GpuSurface makes its tree by the same two calls), as FastStrataOnceMixin does for
 * FreeTerraForged's.
 */
@Pseudo
@Mixin(targets = "com.blackgear.vanillabackport.common.worldgen.surface.ModSurfaceRuleData")
public abstract class VanillaBackportRulesOnceMixin {
   @Unique
   private static volatile SurfaceRules.RuleSource wgaccel$rules;
   /** Each merged tree by its base, with the additions it was merged with (compared one by one, by identity). */
   @Unique
   private static final Map<SurfaceRules.RuleSource, Object[]> wgaccel$merged = new IdentityHashMap<>();

   @WrapMethod(method = "makeRules()Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;")
   private static SurfaceRules.RuleSource wgaccel$rulesOnce(Operation<SurfaceRules.RuleSource> original) {
      SurfaceRules.RuleSource known = wgaccel$rules;
      if (known == null) {
         known = original.call();
         wgaccel$rules = known;
      }

      return known;
   }

   @WrapMethod(method = "merge(Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;Ljava/util/List;)Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;")
   private static SurfaceRules.RuleSource wgaccel$mergeOnce(SurfaceRules.RuleSource base, List<SurfaceRules.RuleSource> additions,
         Operation<SurfaceRules.RuleSource> original) {
      if (base == null || additions == null) {
         return original.call(base, additions);
      }

      synchronized (wgaccel$merged) {
         Object[] known = wgaccel$merged.get(base);
         if (known != null && same((List<?>)known[0], additions)) {
            return (SurfaceRules.RuleSource)known[1];
         }

         // A world's few trees (a new world's are new objects): the old ones dropped now and then.
         if (wgaccel$merged.size() > 64) {
            wgaccel$merged.clear();
         }

         SurfaceRules.RuleSource made = original.call(base, additions);
         wgaccel$merged.put(base, new Object[] {List.copyOf(additions), made});
         return made;
      }
   }

   @Unique
   private static boolean same(List<?> one, List<?> other) {
      if (one.size() != other.size()) {
         return false;
      }

      for (int i = 0; i < one.size(); i++) {
         if (one.get(i) != other.get(i)) {
            return false;
         }
      }

      return true;
   }
}
