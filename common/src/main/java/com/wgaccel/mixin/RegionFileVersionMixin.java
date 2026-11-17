package com.wgaccel.mixin;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * fastChunkCompression: Deflate (the default region format) fed in large blocks and at zlib's fastest level. Minecraft
 * writes a chunk's NBT straight into the DeflaterOutputStream (RegionFile.getChunkDataOutputStream: a DataOutputStream
 * on it, no buffer), so each writeLong of the block and heightmap data is one native deflate call for 8 bytes: the
 * 0.50.1 profiles had compressing at 7-13% of the generation threads' time, nearly all of it in those calls. A 64 KB
 * buffer in front (a chunk is usually one or two) leaves one call per block. The same format, read back by any
 * Minecraft. Deflaters come from a small pool and go back when the stream closes: making and ending one per chunk was
 * 3.3% of the generation threads' time once the calls were batched.
 */
@Mixin(RegionFileVersion.class)
public abstract class RegionFileVersionMixin {
   private static final java.util.concurrent.ConcurrentLinkedQueue<Deflater> WGACCEL$POOL = new java.util.concurrent.ConcurrentLinkedQueue<>();
   private static final java.util.concurrent.atomic.AtomicInteger WGACCEL$POOLED = new java.util.concurrent.atomic.AtomicInteger();

   @Inject(method = "wrap(Ljava/io/OutputStream;)Ljava/io/OutputStream;", at = @At("HEAD"), cancellable = true)
   private void wgaccel$fastDeflate(OutputStream out, CallbackInfoReturnable<OutputStream> cir) throws IOException {
      if ((Object)this != RegionFileVersion.VERSION_DEFLATE || !com.wgaccel.Config.FAST_CHUNK_COMPRESSION.get()) {
         return;
      }

      Deflater pooled = WGACCEL$POOL.poll();
      Deflater deflater;
      if (pooled != null) {
         WGACCEL$POOLED.decrementAndGet();
         deflater = pooled;
      } else {
         deflater = new Deflater(Deflater.BEST_SPEED);
      }

      DeflaterOutputStream deflating = new DeflaterOutputStream(out, deflater, 16384) {
         private boolean released;

         @Override
         public void close() throws IOException {
            boolean finished = false;
            try {
               super.close();
               finished = true;
            } finally {
               if (!this.released) {
                  this.released = true;
                  // Back to the pool only after a clean finish (at most 64 kept); otherwise ended as usual.
                  if (finished && WGACCEL$POOLED.incrementAndGet() <= 64) {
                     deflater.reset();
                     WGACCEL$POOL.add(deflater);
                  } else {
                     if (finished) {
                        WGACCEL$POOLED.decrementAndGet();
                     }

                     deflater.end();
                  }
               }
            }
         }
      };
      cir.setReturnValue(new BufferedOutputStream(deflating, 65536));
   }
}
