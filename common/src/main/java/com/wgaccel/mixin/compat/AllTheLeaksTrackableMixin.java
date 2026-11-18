package com.wgaccel.mixin.compat;

import com.wgaccel.Config;
import java.util.Collections;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Disables AllTheLeaks' WeakReference unload tracking ({@code Trackable.startTracking}) so chunk/level unloads during
 * worldgen do not allocate {@code WeakReference}s or contend on {@code Trackable.LOCK}.
 */
@Pseudo
@Mixin(targets = "dev.uncandango.alltheleaks.mixin.Trackable")
public interface AllTheLeaksTrackableMixin {
   @Inject(method = "startTracking(Ljava/lang/Object;)V", at = @At("HEAD"), cancellable = true, remap = false)
   private static void wgaccel$skipStartTrackingObj(Object obj, CallbackInfo ci) {
      if (Config.DISABLE_ATL_CHUNK_MONITOR.getAsBoolean()) {
         ci.cancel();
      }
   }

   @Inject(method = "clearNullReferences()V", at = @At("HEAD"), cancellable = true, remap = false)
   private static void wgaccel$skipClearNullReferences(CallbackInfo ci) {
      if (Config.DISABLE_ATL_CHUNK_MONITOR.getAsBoolean()) {
         ci.cancel();
      }
   }

   @Inject(method = "getSummary()Ljava/util/Map;", at = @At("HEAD"), cancellable = true, remap = false)
   private static void wgaccel$skipSummary(CallbackInfoReturnable<Map<?, ?>> cir) {
      if (Config.DISABLE_ATL_CHUNK_MONITOR.getAsBoolean()) {
         cir.setReturnValue(Collections.emptyMap());
      }
   }
}
