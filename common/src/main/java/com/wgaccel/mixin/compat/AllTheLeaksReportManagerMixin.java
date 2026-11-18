package com.wgaccel.mixin.compat;

import com.wgaccel.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents AllTheLeaks from scheduling its periodic leak-summary scan, memory-usage GC/EmptyWorkingSet trigger, and
 * weak-reference map cleanup tasks when {@code disableAllTheLeaksChunkMonitor} is active.
 */
@Pseudo
@Mixin(targets = "dev.uncandango.alltheleaks.report.ReportManager")
public abstract class AllTheLeaksReportManagerMixin {
   @Inject(method = "registerTask", at = @At("HEAD"), cancellable = true, remap = false)
   private static void wgaccel$skipMemoryMonitorTasks(String name, int interval, Runnable task, CallbackInfo ci) {
      if (Config.DISABLE_ATL_CHUNK_MONITOR.getAsBoolean()
         && ("update_leak_summary".equals(name) || "too_much_memory_usage".equals(name) || "clear_memory_leak_map".equals(name))) {
         ci.cancel();
      }
   }
}
