package com.wgaccel.gen;

import com.wgaccel.WorldgenAccelerator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Voxy's (MCRcortex, All Rights Reserved) section insert, called by reflection: nothing of Voxy is compiled in or
 * shipped, and when a class or method is missing (another Voxy version) the Voxy LOD generator is off, with a log line.
 *
 * <p>The calls, as Voxy's own ingest and other mods use them (Voxy marks WorldConversionFactory.mipSection "Support for
 * other mods etc that use this entry point"): the level's world engine ({@code VoxyCommon.getInstance().getOrCreate(
 * WorldIdentifier.of(level))}, holding a reference while in use), a {@code VoxelizedSection} (16^3 ids, then its 8^3 ..
 * 1 mips) filled with the engine's {@code Mapper} ids, mipped, then {@code WorldUpdater.insertUpdate(engine, section)}.
 *
 * <p>An id is 64 bits: light in the top 8 (sky in its low 4, block light in its high 4), the biome id from bit 47 (9 bits),
 * the block id from bit 27 (20 bits); air keeps only its light. Checked against Voxy's Mapper.composeMappingId when the
 * access is made.
 */
public final class VoxyAccess {
   private static volatile VoxyAccess instance;
   private static volatile String failure;

   private final MethodHandle getInstance;
   private final MethodHandle worldIdOf;
   private final MethodHandle getOrCreate;
   private final boolean getOrCreateRefs;
   private final MethodHandle acquireRef;
   private final MethodHandle releaseRef;
   private final MethodHandle getMapper;
   private final MethodHandle blockId;
   private final MethodHandle biomeId;
   private final MethodHandle createEmpty;
   private final MethodHandle setPosition;
   private final Field data;
   private final Field nonAir;
   private final MethodHandle mip;
   private final MethodHandle insert;
   /**
    * WorldEngine.acquire(lvl, x, y, z) and WorldSection.release(): a reference of ours on a world section while we write
    * into it (null when missing: no pinning).
    */
   private final MethodHandle acquireSection;
   private final MethodHandle releaseSection;
   /** VoxelIngestService.tryAutoIngestChunk(LevelChunk): Voxy's own ingest of a real chunk (null when missing). */
   private final MethodHandle ingest;

   private VoxyAccess() throws ReflectiveOperationException {
      MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      ClassLoader loader = VoxyAccess.class.getClassLoader();
      Class<?> common = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon", false, loader);
      Class<?> identifier = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier", false, loader);
      Class<?> voxyInstance = Class.forName("me.cortex.voxy.commonImpl.VoxyInstance", false, loader);
      Class<?> engine = Class.forName("me.cortex.voxy.common.world.WorldEngine", false, loader);
      Class<?> mapper = Class.forName("me.cortex.voxy.common.world.other.Mapper", false, loader);
      Class<?> section = Class.forName("me.cortex.voxy.common.voxelization.VoxelizedSection", false, loader);
      Class<?> updater = Class.forName("me.cortex.voxy.common.world.WorldUpdater", false, loader);
      this.getInstance = lookup.unreflect(common.getMethod("getInstance"));
      this.worldIdOf = lookup.unreflect(identifier.getMethod("of", Level.class));
      Method refs = null;
      try {
         refs = voxyInstance.getMethod("getOrCreate", identifier, boolean.class);
      } catch (NoSuchMethodException old) {
         // Older Voxy: the reference taken after.
      }

      this.getOrCreateRefs = refs != null;
      this.getOrCreate = lookup.unreflect(refs != null ? refs : voxyInstance.getMethod("getOrCreate", identifier));
      this.acquireRef = lookup.unreflect(engine.getMethod("acquireRef"));
      this.releaseRef = lookup.unreflect(engine.getMethod("releaseRef"));
      this.getMapper = lookup.unreflect(engine.getMethod("getMapper"));
      this.blockId = lookup.unreflect(mapper.getMethod("getIdForBlockState", BlockState.class));
      this.biomeId = lookup.unreflect(mapper.getMethod("getIdForBiome", Holder.class));
      this.createEmpty = lookup.unreflect(section.getMethod("createEmpty"));
      this.setPosition = lookup.unreflect(section.getMethod("setPosition", int.class, int.class, int.class));
      this.data = section.getField("section");
      this.nonAir = section.getField("lvl0NonAirCount");
      Method mipper;
      try {
         mipper = Class.forName("me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper", false, loader).getMethod("mipSection", section, mapper);
      } catch (ClassNotFoundException | NoSuchMethodException old) {
         mipper = Class.forName("me.cortex.voxy.common.voxelization.WorldConversionFactory", false, loader).getMethod("mipSection", section, mapper);
      }

      this.mip = lookup.unreflect(mipper);
      this.insert = lookup.unreflect(updater.getMethod("insertUpdate", engine, section));
      MethodHandle ingest = null;
      try {
         ingest = lookup.unreflect(Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService", false, loader)
            .getMethod("tryAutoIngestChunk", net.minecraft.world.level.chunk.LevelChunk.class));
      } catch (ReflectiveOperationException missing) {
         // Then loaded chunks are not handed to Voxy again.
      }

      this.ingest = ingest;
      MethodHandle acquireSection = null;
      MethodHandle releaseSection = null;
      try {
         Method acquire = engine.getMethod("acquire", int.class, int.class, int.class, int.class);
         acquireSection = lookup.unreflect(acquire);
         releaseSection = lookup.unreflect(acquire.getReturnType().getMethod("release"));
      } catch (ReflectiveOperationException missing) {
         // Then sections are not pinned.
      }

      this.acquireSection = acquireSection;
      this.releaseSection = releaseSection;
      // The id layout checked against Voxy's own.
      Method compose = mapper.getMethod("composeMappingId", byte.class, int.class, int.class);
      long theirs = (long)compose.invoke(null, (byte)0x5A, 12345, 321);
      if (theirs != id(0x5A, 12345, 321) || (long)compose.invoke(null, (byte)0x0F, 0, 7) != air(0x0F)) {
         throw new IllegalStateException("Voxy's id layout differs from the one expected");
      }
   }

   /** The access, or null when Voxy is missing or its classes differ (logged once). */
   public static VoxyAccess get() {
      VoxyAccess access = instance;
      if (access != null || failure != null) {
         return access;
      }

      synchronized (VoxyAccess.class) {
         if (instance == null && failure == null) {
            try {
               instance = new VoxyAccess();
            } catch (Throwable error) {
               failure = error.toString();
               WorldgenAccelerator.LOG.warn("WGA Voxy LOD generator: Voxy's section insert not found ({}): off", failure);
            }
         }

         return instance;
      }
   }

   /** Why the access is missing, or null. */
   public static String failure() {
      return failure;
   }

   /** A block's (non-air) id with its biome and light (light: sky | block light << 4). */
   public static long id(int light, int block, int biome) {
      if (block == 0) {
         return air(light);
      }

      return (long)(light & 0xFF) << 56 | (long)(biome & 0x1FF) << 47 | (long)(block & 0xFFFFF) << 27;
   }

   /** Air with its light. */
   public static long air(int light) {
      return (long)(light & 0xFF) << 56;
   }

   /**
    * Voxy's own ingest of a real chunk (its blocks with the light engine's light), as when the client shows the chunk:
    * false when Voxy did not take it (no light yet, Voxy's ingest off or not running).
    */
   public boolean ingest(net.minecraft.world.level.chunk.LevelChunk chunk) {
      if (this.ingest == null) {
         return false;
      }

      try {
         return (boolean)this.ingest.invoke(chunk);
      } catch (Throwable error) {
         return false;
      }
   }

   /** A level's Voxy world (its engine and mapper, a reference held until {@link Target#release}), or null. */
   public Target target(Level level) {
      try {
         Object voxy = this.getInstance.invoke();
         if (voxy == null) {
            return null;
         }

         Object id = this.worldIdOf.invoke(level);
         if (id == null) {
            return null;
         }

         Object engine = this.getOrCreateRefs ? this.getOrCreate.invoke(voxy, id, true) : this.getOrCreate.invoke(voxy, id);
         if (engine == null) {
            return null;
         }

         if (!this.getOrCreateRefs) {
            this.acquireRef.invoke(engine);
         }

         return new Target(engine, this.getMapper.invoke(engine));
      } catch (Throwable error) {
         WorldgenAccelerator.LOG.warn("WGA Voxy LOD generator: no Voxy world for {}", level.dimension(), error);
         return null;
      }
   }

   /** One level's Voxy world while the generator writes to it. */
   public final class Target {
      private final Object engine;
      private final Object mapper;
      private final ConcurrentHashMap<BlockState, Integer> blocks = new ConcurrentHashMap<>();
      private final ConcurrentHashMap<Holder<Biome>, Integer> biomes = new ConcurrentHashMap<>();
      private boolean released;

      Target(Object engine, Object mapper) {
         this.engine = engine;
         this.mapper = mapper;
      }

      /** The block state's id in this world (made once each). */
      public int block(BlockState state) {
         Integer id = this.blocks.get(state);
         if (id == null) {
            try {
               id = (int)VoxyAccess.this.blockId.invoke(this.mapper, state);
            } catch (Throwable error) {
               throw new IllegalStateException("Voxy's block id for " + state, error);
            }

            this.blocks.put(state, id);
         }

         return id;
      }

      /** The biome's id in this world (made once each). */
      public int biome(Holder<Biome> biome) {
         Integer id = this.biomes.get(biome);
         if (id == null) {
            try {
               id = (int)VoxyAccess.this.biomeId.invoke(this.mapper, biome);
            } catch (Throwable error) {
               throw new IllegalStateException("Voxy's biome id for " + biome, error);
            }

            this.biomes.put(biome, id);
         }

         return id;
      }

      /** A new section (Voxy's VoxelizedSection; reusable once inserted: Voxy copies it in) at section position (x, y, z). */
      public Object section(int x, int y, int z) {
         try {
            Object section = VoxyAccess.this.createEmpty.invoke();
            VoxyAccess.this.setPosition.invoke(section, x, y, z);
            return section;
         } catch (Throwable error) {
            throw new IllegalStateException("Voxy's section", error);
         }
      }

      /** A section's ids: the first 4,096 are its blocks, index y << 8 | z << 4 | x; the mips follow. */
      public long[] data(Object section) {
         try {
            return (long[])VoxyAccess.this.data.get(section);
         } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
         }
      }

      /** The section, its ids written and moved to (x, y, z), mipped and put into the world. */
      public void insert(Object section, int x, int y, int z, int nonAir) {
         try {
            VoxyAccess.this.setPosition.invoke(section, x, y, z);
            VoxyAccess.this.nonAir.setInt(section, nonAir);
            VoxyAccess.this.mip.invoke(section, this.mapper);
            VoxyAccess.this.insert.invoke(this.engine, section);
         } catch (Throwable error) {
            throw new IllegalStateException("Voxy's section insert", error);
         }
      }

      /** Whether world sections can be pinned ({@link #pin}). */
      public boolean pins() {
         return VoxyAccess.this.acquireSection != null;
      }

      /** A reference of ours on world section (lvl, x, y, z) (loaded if need be), or null when it failed. */
      public Object pin(int lvl, int x, int y, int z) {
         try {
            return VoxyAccess.this.acquireSection.invoke(this.engine, lvl, x, y, z);
         } catch (Throwable error) {
            return null;
         }
      }

      /** Our reference given back. */
      public void unpin(Object section) {
         try {
            VoxyAccess.this.releaseSection.invoke(section);
         } catch (Throwable error) {
            WorldgenAccelerator.LOG.debug("WGA Voxy LOD generator: a section's reference not given back", error);
         }
      }

      /** The world's reference given back (once). */
      public synchronized void release() {
         if (!this.released) {
            this.released = true;
            try {
               VoxyAccess.this.releaseRef.invoke(this.engine);
            } catch (Throwable error) {
               WorldgenAccelerator.LOG.warn("WGA Voxy LOD generator: releasing Voxy's world failed", error);
            }
         }
      }
   }
}
