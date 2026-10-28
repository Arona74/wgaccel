package com.wgaccel.gpu;

import com.wgaccel.platform.Names;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

/**
 * Surface conditions that depend only on the block column and that the GPU cannot work out, answered on the CPU once
 * per column and handed to the surface kernels as bits (bit k: condition k), in the high 16 bits of the column's
 * preliminary surface base (minBase) and of a recorded rule call's column. Conquest ReTerraForged's conquest_beach and
 * conquest_sea: its terrain cell's type at (x, z) (a LazyXZCondition reading only the context's block x and z). Each
 * thread has its own conditions, made by the mod's own apply from a context holding only what they read, and asked
 * through the mod's own test: the same answers as in the game's column loop.
 *
 * Each of those conditions works the column's whole terrain cell out on its own (~8 us; both: ~4 ms a chunk, as much as
 * the GPU saves). With Conquest's landform function ({@link #useLandforms}: its api's class of the column, one cell for
 * both, remembered by LandformMemo) sea is class 1 (ocean) and beach class 2 (beach), the same tests on the same cell
 * in its classify. Every 256th column is also asked the mod's own conditions; a difference turns the shortcut off.
 */
public final class ColumnConditions {
   /** The conditions the GPU takes from here, by registry id (only column conditions: the answer must not depend on y). */
   static final List<String> IDS = List.of("conquest-reterraforged:conquest_beach", "conquest-reterraforged:conquest_sea");
   static final int MAX = 16;
   /** The landform class (ConquestLandforms: 1 ocean, 2 beach) each id's condition holds for. */
   private static final java.util.Map<String, Integer> LANDFORMS = java.util.Map.of("conquest-reterraforged:conquest_beach", 2, "conquest-reterraforged:conquest_sea", 1);

   private final List<String> ids = new ArrayList<>();
   private volatile java.util.function.IntBinaryOperator landforms;
   private int[] classes;

   private final List<Object> sources = new ArrayList<>();
   private final RandomState random;
   private final WorldGenerationContext context;
   private final Registry<Biome> biomes;
   private final Object system;
   private final ThreadLocal<PerThread> state = ThreadLocal.withInitial(this::make);

   ColumnConditions(RandomState random, WorldGenerationContext context, Registry<Biome> biomes, Object system) {
      this.random = random;
      this.context = context;
      this.biomes = biomes;
      this.system = system;
   }

   /** The condition's bit (it is added when new); -1 when there are already {@link #MAX}. */
   int bit(Object source, String id) {
      int index = this.sources.indexOf(source);
      if (index < 0) {
         if (this.sources.size() == MAX) {
            return -1;
         }

         index = this.sources.size();
         this.sources.add(source);
         this.ids.add(id);
      }

      return index;
   }

   /** Answers the columns from Conquest's landform function (when every condition has a landform class). */
   public void useLandforms(java.util.function.IntBinaryOperator function) {
      int[] classes = new int[this.ids.size()];
      for (int k = 0; k < classes.length; k++) {
         Integer landform = LANDFORMS.get(this.ids.get(k));
         if (landform == null) {
            return;
         }

         classes[k] = landform;
      }

      this.classes = classes;
      this.landforms = function;
   }

   public boolean isEmpty() {
      return this.sources.isEmpty();
   }

   /** The column's bits. */
   public int flags(int x, int z) {
      java.util.function.IntBinaryOperator function = this.landforms;
      if (function == null) {
         return this.conditions(x, z);
      }

      int landform = function.applyAsInt(x, z) & 255;
      int flags = 0;
      for (int k = 0; k < this.classes.length; k++) {
         if (this.classes[k] == landform) {
            flags |= 1 << k;
         }
      }

      if (java.util.concurrent.ThreadLocalRandom.current().nextInt(256) == 0) {
         int real = this.conditions(x, z);
         CHECKED.increment();
         if (real != flags) {
            this.landforms = null;
            com.wgaccel.WorldgenAccelerator.LOG.warn("WGA surface column conditions: column ({}, {}) has landform {} (bits {}) but the mod's conditions give bits {}; off, the conditions are asked",
               x, z, landform, flags, real);
         }

         return real;
      }

      return flags;
   }

   /** Columns compared with the mod's own conditions. */
   public static final java.util.concurrent.atomic.LongAdder CHECKED = new java.util.concurrent.atomic.LongAdder();

   /** The column's bits from the mod's own conditions. */
   private int conditions(int x, int z) {
      PerThread thread = this.state.get();
      try {
         thread.blockX.setInt(thread.context, x);
         thread.blockZ.setInt(thread.context, z);
         // The conditions' LazyXZCondition works its answer out again when the context's update count moved.
         thread.lastUpdateXZ.setLong(thread.context, ++thread.updates);
         int flags = 0;
         for (int k = 0; k < thread.conditions.length; k++) {
            if ((Boolean)thread.test.invoke(thread.conditions[k])) {
               flags |= 1 << k;
            }
         }

         return flags;
      } catch (ReflectiveOperationException failure) {
         throw new IllegalStateException(failure);
      }
   }

   /** A thread's context and conditions. */
   private static final class PerThread {
      Object context;
      Object[] conditions;
      Field blockX;
      Field blockZ;
      Field lastUpdateXZ;
      Method test;
      long updates;
   }

   private PerThread make() {
      try {
         // SurfaceRules.Context is protected: reached by name (as GpuSurface's strata source).
         Class<?> contextClass = Names.forName("net.minecraft.world.level.levelgen.SurfaceRules$Context");
         Constructor<?> make = contextClass.getDeclaredConstructor(SurfaceSystem.class, RandomState.class, ChunkAccess.class, NoiseChunk.class, Function.class,
            Registry.class, WorldGenerationContext.class);
         make.setAccessible(true);
         PerThread thread = new PerThread();
         thread.context = make.newInstance(this.system, this.random, null, null, null, this.biomes, this.context);
         thread.blockX = field(contextClass, "blockX");
         thread.blockZ = field(contextClass, "blockZ");
         thread.lastUpdateXZ = field(contextClass, "lastUpdateXZ");
         thread.conditions = new Object[this.sources.size()];
         for (int k = 0; k < thread.conditions.length; k++) {
            // Through Function (a ConditionSource is one): an obfuscated mod's own apply has another name.
            @SuppressWarnings("unchecked")
            Function<Object, Object> source = (Function<Object, Object>)this.sources.get(k);
            thread.conditions[k] = source.apply(thread.context);
         }

         Class<?> condition = Names.forName("net.minecraft.world.level.levelgen.SurfaceRules$Condition");
         thread.test = Names.method(condition, "test");
         thread.test.setAccessible(true);
         return thread;
      } catch (ReflectiveOperationException failure) {
         throw new IllegalStateException(failure);
      }
   }

   private static Field field(Class<?> type, String name) throws NoSuchFieldException {
      Field field = Names.declaredField(type, name);
      field.setAccessible(true);
      return field;
   }
}
