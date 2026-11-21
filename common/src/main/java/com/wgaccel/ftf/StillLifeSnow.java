package com.wgaccel.ftf;

import com.wgaccel.WorldgenAccelerator;
import com.wgaccel.platform.Platform;
import java.lang.reflect.Method;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Still Life x FreeTerraForged bridge's snow caps (5.x, its ScreeFeatureMixin) for the LOD scree, which works FTF's
 * erodeColumn out itself and so never meets the bridge's hook at its head: a snow block or powder snow top no steeper
 * than the cliff steepness keeps its snow (BridgeHooks.keepSnow). Its snow_cap_fade (bare patches at the cap's lowest
 * part) is not followed: the LODs keep the whole cap. The bridge's classes are package-private: reached by reflection,
 * and off (as without the bridge) when they cannot be.
 */
final class StillLifeSnow {
   private static final Method SETTINGS;
   private static final Method ACTIVE;
   private static final Method IS_SNOW;
   private static final Method CLIFF;
   private static volatile boolean broken;

   static {
      Method settings = null;
      Method active = null;
      Method isSnow = null;
      Method cliff = null;
      if (Platform.get().isModLoaded("stilllife_ftf_bridge")) {
         try {
            ClassLoader loader = StillLifeSnow.class.getClassLoader();
            Class<?> lock = Class.forName("stilllife.ftfbridge.WorldLock", false, loader);
            Class<?> caps = Class.forName("stilllife.ftfbridge.SnowCaps", false, loader);
            Class<?> settingsClass = Class.forName("stilllife.ftfbridge.Settings", false, loader);
            settings = lock.getDeclaredMethod("activeSettings");
            active = caps.getDeclaredMethod("active", settingsClass);
            isSnow = caps.getDeclaredMethod("isSnow", BlockState.class);
            for (Method method : caps.getDeclaredMethods()) {
               if (method.getName().equals("cliffSteepness") && method.getParameterCount() == 2) {
                  cliff = method;
               }
            }

            if (cliff == null) {
               throw new NoSuchMethodException("SnowCaps.cliffSteepness");
            }

            for (Method method : new Method[]{settings, active, isSnow, cliff}) {
               method.setAccessible(true);
            }
         } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            // An older bridge (3.x has no snow caps) or a changed one.
            settings = null;
            WorldgenAccelerator.LOG.info("WGA LOD columns: the Still Life FTF bridge's snow caps are not followed in the LODs ({})", failure.toString());
         }
      }

      SETTINGS = settings;
      ACTIVE = active;
      IS_SNOW = isSnow;
      CLIFF = cliff;
   }

   private StillLifeSnow() {
   }

   /** Whether the bridge keeps this column's snow top from FTF's erosion (screeConfig: ScreeFeature.Config). */
   static boolean keep(Object screeConfig, BlockState top, float steepness) {
      if (SETTINGS == null || broken) {
         return false;
      }

      try {
         if (!(Boolean)IS_SNOW.invoke(null, top)) {
            return false;
         }

         Object settings = SETTINGS.invoke(null);
         return settings != null && (Boolean)ACTIVE.invoke(null, settings) && steepness <= (Float)CLIFF.invoke(null, settings, screeConfig);
      } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
         broken = true;
         WorldgenAccelerator.LOG.warn("WGA LOD columns: the Still Life FTF bridge's snow caps failed; LODs without them from now: {}", failure.toString());
         return false;
      }
   }
}
