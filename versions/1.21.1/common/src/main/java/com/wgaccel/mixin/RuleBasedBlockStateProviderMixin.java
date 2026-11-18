package com.wgaccel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.Config;
import com.wgaccel.gen.FastPredicates;
import com.wgaccel.gen.PredicateReads;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedBlockStateProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Evaluates rule-based block choices with compiled {@link FastPredicates.RulePlan} and optional {@link PredicateReads} scopes. */
@Mixin(RuleBasedBlockStateProvider.class)
public abstract class RuleBasedBlockStateProviderMixin {
   @Unique
   private volatile FastPredicates.RulePlan wgaccel$plan;

   @Shadow
   public abstract List<RuleBasedBlockStateProvider.Rule> rules();

   @Shadow
   public abstract BlockStateProvider fallback();

   @Unique
   private FastPredicates.RulePlan wgaccel$plan() {
      FastPredicates.RulePlan plan = this.wgaccel$plan;
      if (plan == null) {
         List<RuleBasedBlockStateProvider.Rule> rules = this.rules();
         List<BlockPredicate> conditions = new ArrayList<>(rules.size());
         BlockStateProvider[] thenProviders = new BlockStateProvider[rules.size()];
         for (int i = 0; i < rules.size(); i++) {
            RuleBasedBlockStateProvider.Rule rule = rules.get(i);
            conditions.add(rule.ifTrue());
            thenProviders[i] = rule.then();
         }
         plan = FastPredicates.compileRules(conditions, thenProviders, this.fallback());
         this.wgaccel$plan = plan;
      }
      return plan;
   }

   @WrapMethod(method = "getState", require = 0)
   private BlockState wgaccel$scope(WorldGenLevel level, RandomSource random, BlockPos pos, Operation<BlockState> original) {
      if (FastPredicates.on()) {
         FastPredicates.RulePlan plan = this.wgaccel$plan();
         if (plan.pureDirect) {
            return FastPredicates.evalRulePlan(plan, level, random, pos);
         }
         if (Config.FAST_BLOCK_PREDICATES.get() && level instanceof PredicateReads.Holder holder) {
            PredicateReads.Scope scope = holder.wgaccel$predicateReads();
            if (scope.begin()) {
               try {
                  return FastPredicates.evalRulePlan(plan, level, random, pos);
               } finally {
                  scope.end();
               }
            }
         }
         return FastPredicates.evalRulePlan(plan, level, random, pos);
      }

      if (Config.FAST_BLOCK_PREDICATES.get() && level instanceof PredicateReads.Holder holder) {
         PredicateReads.Scope scope = holder.wgaccel$predicateReads();
         if (scope.begin()) {
            try {
               return original.call(level, random, pos);
            } finally {
               scope.end();
            }
         }
      }

      return original.call(level, random, pos);
   }
}
