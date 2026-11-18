package com.wgaccel.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wgaccel.Config;
import com.wgaccel.WgaChat;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retain the exact lazy sequence; remove its per-block iterator only for lists with immutable contents/order. */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRule")
public abstract class SurfaceSequenceMixin {
   @Shadow @Final private List<SurfaceRules.SurfaceRule> rules;
   @Unique private SurfaceRules.SurfaceRule[] wgaccel$compiled;
   @Unique private static volatile boolean wgaccel$disabled;
   @Unique private static final ThreadLocal<int[]> wgaccel$turn = ThreadLocal.withInitial(() -> new int[1]);

   @Inject(method = "<init>", at = @At("RETURN"))
   private void wgaccel$compile(CallbackInfo callback) {
      if (this.rules instanceof ImmutableList<?> || this.rules.getClass().getName().startsWith("java.util.ImmutableCollections$")) {
         this.wgaccel$compiled = this.rules.toArray(SurfaceRules.SurfaceRule[]::new);
      }
   }

   @WrapMethod(method = "tryApply")
   private BlockState wgaccel$sequence(int x,int y,int z,Operation<BlockState> original) {
      SurfaceRules.SurfaceRule[] sequence=this.wgaccel$compiled;
      if (sequence==null || wgaccel$disabled || !Config.FAST_SURFACE_SEQUENCES.get()) return original.call(x,y,z);
      int[] count = wgaccel$turn.get();
      if ((++count[0] & 255) == 0 && !wgaccel$matches(sequence, this.rules)) {
         wgaccel$disabled = true;
         WgaChat.warnOff("WGA fast surface sequences off: compiled rule sequence out of sync with rules list");
         return original.call(x, y, z);
      }
      for (SurfaceRules.SurfaceRule rule:sequence) {
         BlockState value=rule.tryApply(x,y,z);
         if (value!=null) return value;
      }
      return null;
   }

   @Unique
   private static boolean wgaccel$matches(SurfaceRules.SurfaceRule[] sequence, List<SurfaceRules.SurfaceRule> rules) {
      if (sequence.length != rules.size()) {
         return false;
      }
      for (int i = 0; i < sequence.length; i++) {
         if (sequence[i] != rules.get(i)) {
            return false;
         }
      }
      return true;
   }
}
