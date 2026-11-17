package com.wgaccel.gen;

import com.wgaccel.Config;
import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Names;
import java.lang.reflect.Field;

/**
 * FreeTerraForged's PlacedFeature wrapper (manageSurfaceFeature) opens and closes a scope on every placement through two
 * plain ThreadLocals (SurfaceFeatureRescue.ACTIVE_FEATURES, ChunkLocalFeaturePlacement.ACTIVE), and its finish calls
 * ThreadLocal.remove() when the scope ends; the next placement's get() then misses (ThreadLocalMap.getEntryAfterMiss)
 * and set() makes the entry again. With Still Life's stone_cliff (~9,900 placements per chunk) that was ~15% of feature
 * time (2026-10-22 profile). For a ThreadLocal without an initial value, set(null) is the same as remove() to every
 * later get() (both give null), but keeps the entry, so the look-ups stay on the fast path. Checked once that both
 * fields are plain ThreadLocals (an initialValue override would make the two differ); otherwise this stays off.
 */
public final class FtfPlacementScopes {
   private static volatile Boolean plain;

   private FtfPlacementScopes() {
   }

   /** Whether finish may clear with set(null) instead of remove(). */
   public static boolean on(ThreadLocal<?> local) {
      if (!Config.FTF_FAST_PLACEMENT_SCOPES.get()) {
         return false;
      }

      Boolean known = plain;
      if (known == null) {
         known = plain = check();
      }

      return known && local.getClass() == ThreadLocal.class;
   }

   private static boolean check() {
      try {
         boolean all = plainField("etcodehome.freeterraforged.world.worldgen.feature.placement.SurfaceFeatureRescue", "ACTIVE_FEATURES")
            && plainField("etcodehome.freeterraforged.world.worldgen.feature.placement.ChunkLocalFeaturePlacement", "ACTIVE");
         WorldgenAccelerator.LOG.info("WGA FTF placement scopes: {}", all ? "cleared with set(null) (plain ThreadLocals)" : "left as FreeTerraForged does (not plain ThreadLocals)");
         return all;
      } catch (ReflectiveOperationException | RuntimeException failure) {
         WorldgenAccelerator.LOG.info("WGA FTF placement scopes: left as FreeTerraForged does ({})", failure.toString());
         return false;
      }
   }

   private static boolean plainField(String type, String name) throws ReflectiveOperationException {
      Field field = Names.declaredField(Names.forName(type), name);
      field.setAccessible(true);
      Object value = field.get(null);
      return value != null && value.getClass() == ThreadLocal.class;
   }
}
