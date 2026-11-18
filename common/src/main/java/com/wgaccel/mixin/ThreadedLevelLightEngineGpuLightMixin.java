package com.wgaccel.mixin;

import com.wgaccel.platform.Mc;
import com.wgaccel.gen.GpuLightTarget;
import com.wgaccel.gpu.LightGpu;
import com.wgaccel.mixin.access.LayerLightSectionStorageAccessor;
import com.wgaccel.mixin.access.LevelLightEngineAccessor;
import com.wgaccel.mixin.access.LightEngineAccessor;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineGpuLightMixin extends LevelLightEngine implements GpuLightTarget {
   protected ThreadedLevelLightEngineGpuLightMixin(LightChunkGetter getter, boolean hasBlock, boolean hasSky) {
      super(getter, hasBlock, hasSky);
   }

   @Shadow
   public abstract CompletableFuture<ChunkAccess> lightChunk(ChunkAccess chunk, boolean lighted);

   @Override
   public CompletableFuture<ChunkAccess> wgaccel$applyGpuLight(ChunkAccess chunk, int activeMinSec, LightGpu.Result result) {
      int secs = result.secCount;
      DataLayer[] blkLayers = new DataLayer[secs];
      DataLayer[] skyLayers = new DataLayer[secs];
      int[] skyFlags = new int[secs];
      for (int s = 0; s < secs; s++) {
         int f = result.flags[s];
         skyFlags[s] = f;
         if ((f & 1) != 0) {
            byte[] b = new byte[2048];
            System.arraycopy(result.blockData, s * 2048, b, 0, 2048);
            blkLayers[s] = new DataLayer(b);
         }
         if ((f & 6) == 6) {
            byte[] sk = new byte[2048];
            System.arraycopy(result.skyData, s * 2048, sk, 0, 2048);
            skyLayers[s] = new DataLayer(sk);
         }
      }

      ChunkPos pos = chunk.getPos();
      chunk.setLightCorrect(false);
      ((ThreadedLevelLightEngine)(Object)this).addTask(Mc.x(pos), Mc.z(pos), ThreadedLevelLightEngine.TaskType.PRE_UPDATE, () -> {
         super.setLightEnabled(pos, true);
         LightEngine<?, ?> blkEng = ((LevelLightEngineAccessor)this).wgaccel$blockEngine();
         LayerLightSectionStorage<?> blkStorage = blkEng != null ? ((LightEngineAccessor)blkEng).wgaccel$storage() : null;
         if (blkStorage != null) {
            LayerLightSectionStorageAccessor blkAcc = (LayerLightSectionStorageAccessor)blkStorage;
            for (int s = 0; s < secs; s++) {
               DataLayer dl = blkLayers[s];
               if (dl == null) {
                  continue;
               }
               int sy = activeMinSec + s;
               long secLong = SectionPos.asLong(Mc.x(pos), sy, Mc.z(pos));
               if (blkAcc.wgaccel$storingLightForSection(secLong)) {
                  super.queueSectionData(LightLayer.BLOCK, SectionPos.of(pos, sy), dl);
               }
            }
         }

         LightEngine<?, ?> skyEng = ((LevelLightEngineAccessor)this).wgaccel$skyEngine();
         LayerLightSectionStorage<?> skyStorage = skyEng != null ? ((LightEngineAccessor)skyEng).wgaccel$storage() : null;
         if (skyStorage != null) {
            LayerLightSectionStorageAccessor skyAcc = (LayerLightSectionStorageAccessor)skyStorage;
            for (int s = 0; s < secs; s++) {
               int sy = activeMinSec + s;
               long secLong = SectionPos.asLong(Mc.x(pos), sy, Mc.z(pos));
               if (!skyAcc.wgaccel$storingLightForSection(secLong)) {
                  continue;
               }
               int f = skyFlags[s];
               if (skyLayers[s] != null) {
                  super.queueSectionData(LightLayer.SKY, SectionPos.of(pos, sy), skyLayers[s]);
               } else if ((f & 6) == 2) {
                  DataLayer cur = skyAcc.wgaccel$getDataLayerData(secLong);
                  if (cur != null && !cur.isDefinitelyFilledWith(15)) {
                     super.queueSectionData(LightLayer.SKY, SectionPos.of(pos, sy), new DataLayer(15));
                  }
               } else if ((f & 2) == 0) {
                  DataLayer cur = skyAcc.wgaccel$getDataLayerData(secLong);
                  if (cur != null && !cur.isEmpty()) {
                     super.queueSectionData(LightLayer.SKY, SectionPos.of(pos, sy), new DataLayer());
                  }
               }
            }
         }
      });
      return this.lightChunk(chunk, true);
   }
}
