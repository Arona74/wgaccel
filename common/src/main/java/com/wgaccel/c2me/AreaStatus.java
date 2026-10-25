package com.wgaccel.c2me;

import com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import com.ishland.c2me.rewrites.chunksystem.common.ChunkState;
import com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus;
import com.ishland.flowsched.scheduler.Cancellable;
import com.ishland.flowsched.scheduler.ItemHolder;
import com.ishland.flowsched.scheduler.KeyStatusPair;
import io.reactivex.rxjava3.core.Completable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * The 4 x 4 area step (config gpuAreaStep; the user's pick, an idea from C2ME OpenCL, our own code): a C2ME chunk
 * status just before BIOMES. At an aligned area's origin chunk (x and z multiples of 4), while it is generating, it
 * waits for the area's other 15 chunks to reach STRUCTURE_REFERENCES and then runs the area's GPU work at once
 * ({@link AreaStep#run}); every area chunk's BIOMES step waits for it (the dependency added in
 * {@code VanillaDelegateMixin}), so their GPU results are always ready when their biome and noise steps come. Anywhere
 * else, and with gpuAreaStep off, it does nothing. Loaded only with C2ME (the mixins that use it apply only then).
 */
public final class AreaStatus extends NewChunkStatus {
   @SuppressWarnings("unchecked")
   private static final KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] NONE = new KeyStatusPair[0];
   private static volatile AreaStatus instance;

   AreaStatus(int ordinal) {
      super(ordinal, ChunkStatus.STRUCTURE_REFERENCES);
      instance = this;
   }

   /** Made by C2ME's status hook (NewStatusHookMixin) with the next ordinal. */
   public static AreaStatus create(int ordinal) {
      return new AreaStatus(ordinal);
   }

   /** The status, once C2ME has built its list (null before, or without C2ME). */
   public static AreaStatus instance() {
      return instance;
   }

   public static boolean isOrigin(ChunkPos pos) {
      return (pos.x & 3) == 0 && (pos.z & 3) == 0;
   }

   public static ChunkPos origin(ChunkPos pos) {
      return new ChunkPos(pos.x & ~3, pos.z & ~3);
   }

   /** Whether the chunk is still to be generated past this status (its saved status before BIOMES). */
   static boolean generating(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder) {
      ChunkState state = holder.getItem().get();
      ChunkAccess chunk = state == null ? null : state.chunk();
      return chunk == null || !chunk.getPersistedStatus().isOrAfter(ChunkStatus.BIOMES);
   }

   @Override
   public Completable upgradeToThis(ChunkLoadingContext context, Cancellable cancellable) {
      ChunkPos pos = context.holder().getKey();
      if (!AreaStep.on() || !isOrigin(pos) || !generating(context.holder())) {
         return Completable.complete();
      }

      return Completable.fromCompletionStage(AreaStep.run(context, pos));
   }

   @Override
   public Completable postUpgradeToThis(ChunkLoadingContext context) {
      return Completable.complete();
   }

   @Override
   public Completable preDowngradeFromThis(ChunkLoadingContext context, Cancellable cancellable) {
      return Completable.complete();
   }

   @Override
   public Completable downgradeFromThis(ChunkLoadingContext context, Cancellable cancellable) {
      return Completable.complete();
   }

   /**
    * At an origin: the area's other 15 chunks at STRUCTURE_REFERENCES; nothing anywhere else. The same whatever the
    * chunk's state (0.9.86 asked only while generating: when C2ME released the chunk later it got none back, the 15
    * stayed held and the world could not unload, "waiting for 67584 chunks to unload").
    */
   @Override
   public KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] getDependencies(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder) {
      ChunkPos pos = holder.getKey();
      if (!AreaStep.on() || !isOrigin(pos)) {
         return NONE;
      }

      return areaDependencies(pos);
   }

   @Override
   public KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] getDependenciesToRemove(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder) {
      return NONE;
   }

   @Override
   public KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] getDependenciesToAdd(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder) {
      return NONE;
   }

   @SuppressWarnings("unchecked")
   private static KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] areaDependencies(ChunkPos origin) {
      NewChunkStatus references = NewChunkStatus.fromVanillaStatus(ChunkStatus.STRUCTURE_REFERENCES);
      KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] pairs = new KeyStatusPair[15];
      int n = 0;
      for (int dx = 0; dx < 4; dx++) {
         for (int dz = 0; dz < 4; dz++) {
            if (dx != 0 || dz != 0) {
               pairs[n++] = new KeyStatusPair<>(new ChunkPos(origin.x + dx, origin.z + dz), references);
            }
         }
      }

      return pairs;
   }

   @Override
   public String toString() {
      return "wgaccel:area_step";
   }
}
