package com.wgaccel.mixin.ftf;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/**
 * FreeTerraForged's FastStrataSequenceRule.optimize (its 740a69e3 build) made once per rule tree: it builds a new wrapper
 * (and a new sequence without the strata) on every call, at every chunk's buildSurface, so the tree a chunk's surface got
 * was never the one the GPU surface was made from, and every chunk stayed on the CPU ("another rule source": the 23 Oct
 * 2026 run, surface 17 ms a chunk, r1000 0:46 -> 0:59). The result depends only on the tree and holds no state (its rules
 * are made per chunk by apply), so the same object is handed back for the same tree, here and to the GPU surface.
 */
@Pseudo
@Mixin(targets = "etcodehome.freeterraforged.world.worldgen.surface.rule.FastStrataSequenceRule")
public abstract class FastStrataOnceMixin {
   @Unique
   private static final Map<Object, SurfaceRules.RuleSource> wgaccel$made = new IdentityHashMap<>();

   @WrapMethod(method = "optimize(Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;)Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;")
   private static SurfaceRules.RuleSource wgaccel$once(SurfaceRules.RuleSource root, Operation<SurfaceRules.RuleSource> original) {
      if (root == null) {
         return original.call(root);
      }

      synchronized (wgaccel$made) {
         SurfaceRules.RuleSource known = wgaccel$made.get(root);
         if (known == null) {
            // A world's few trees (a new world's are new objects): the old ones dropped now and then.
            if (wgaccel$made.size() > 64) {
               wgaccel$made.clear();
            }

            known = original.call(root);
            wgaccel$made.put(root, known);
         }

         return known;
      }
   }
}
