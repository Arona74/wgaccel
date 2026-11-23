package com.wgaccel.mixin.compat;

import com.wgaccel.gen.VssLods;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.RequestRegistration;
import dev.vox.lss.common.processing.TickSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Voxy Server Side (LOD Server Support, MIT) on a server: the chunks its generation service would generate for a
 * player's distant LODs (never generated: not loaded, not in the world's files, not in its LOD store) are made from the
 * GPU columns instead ({@link VssLods}, config voxyForVss), their columns handed back from its {@code tick()} as its
 * own generations' results are (VSS then sends them and keeps them in its store). A request we do not take (another
 * dimension, within voxyVssRealRadius, no GPU fill) is VSS's own generation as before.
 */
@Pseudo
@Mixin(targets = "dev.vox.lss.networking.server.ChunkGenerationService")
public abstract class VssGenerationMixin {
   @Shadow(remap = false)
   private boolean admissionEnabled;
   @Shadow(remap = false)
   private volatile long totalSubmitted;
   @Shadow(remap = false)
   private volatile long totalCompleted;
   @Shadow(remap = false)
   private volatile long totalTimeouts;

   @Unique
   private static volatile boolean wgaccel$wired;

   @Inject(method = "submitGeneration", at = @At("HEAD"), cancellable = true, remap = false)
   private void wgaccel$fromGpuColumns(UUID playerUuid, RequestRegistration registration, ServerLevel level, int cx, int cz,
      long submissionOrder, CallbackInfoReturnable<Boolean> cir) {
      if (!this.admissionEnabled || registration.isRetired()) {
         return;
      }

      if (!wgaccel$wired) {
         wgaccel$wired = true;
         wgaccel$wireTranslation();
      }

      if (VssLods.submit(level, playerUuid, new Object[]{playerUuid, registration, submissionOrder}, cx, cz)) {
         this.totalSubmitted++;
         cir.setReturnValue(true);
      }
   }

   /**
    * VSS's NbtSectionSerializer.toV20 (a package-private method of a package-private class): looked up from here, merged
    * into VSS's generation service, in the same package (an invoker or reflection from our classes may not reach it).
    */
   @Unique
   private static void wgaccel$wireTranslation() {
      try {
         java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
         Class<?> serializer = Class.forName("dev.vox.lss.networking.server.NbtSectionSerializer", false, lookup.lookupClass().getClassLoader());
         java.lang.invoke.MethodHandle toV20 = lookup.findStatic(serializer, "toV20",
            java.lang.invoke.MethodType.methodType(byte[].class, byte[].class, net.minecraft.core.RegistryAccess.class));
         VssLods.translator((body, at) -> {
            try {
               return (byte[])toV20.invokeExact(body, (net.minecraft.core.RegistryAccess)at.registryAccess());
            } catch (RuntimeException | Error failure) {
               throw failure;
            } catch (Throwable failure) {
               throw new IllegalStateException(failure);
            }
         });
      } catch (ReflectiveOperationException | RuntimeException failure) {
         com.wgaccel.WorldgenAccelerator.LOG.warn("WGA LODs for Voxy Server Side: VSS's column translation not found (another VSS version?);"
            + " VSS generates its chunks itself", failure);
      }
   }

   @Inject(method = "tick", at = @At("RETURN"), cancellable = true, remap = false)
   private void wgaccel$gpuColumnsReady(CallbackInfoReturnable<List<TickSnapshot.GenerationReadyData>> cir) {
      List<VssLods.Result> done = VssLods.drain();
      if (done.isEmpty()) {
         return;
      }

      List<TickSnapshot.GenerationReadyData> ready = new ArrayList<>(cir.getReturnValue());
      for (VssLods.Result result : done) {
         Object[] ticket = (Object[])result.ticket();
         UUID player = (UUID)ticket[0];
         RequestRegistration registration = (RequestRegistration)ticket[1];
         long order = (Long)ticket[2];
         if (result.failed()) {
            // Transient, as VSS's own timeout: dropped quietly, the client asks again.
            ready.add(new TickSnapshot.GenerationReadyData(player, registration, result.x(), result.z(), result.dimension(), null, 0L, order, true));
            this.totalTimeouts++;
         } else {
            byte[] bytes = result.bytes();
            LoadedColumnData column = new LoadedColumnData(result.x(), result.z(), bytes, bytes == null ? 0 : bytes.length);
            ready.add(new TickSnapshot.GenerationReadyData(player, registration, result.x(), result.z(), result.dimension(), column, result.stamp(), order));
            this.totalCompleted++;
         }
      }

      cir.setReturnValue(ready);
   }
}
