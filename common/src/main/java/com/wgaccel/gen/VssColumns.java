package com.wgaccel.gen;

import com.wgaccel.platform.Mc;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;

/**
 * {@link VoxyWriter}'s sections made into the columns Voxy Server Side (LOD Server Support, MIT) sends its players
 * ({@link VssLods}): each chunk's sections as Minecraft's own sections (blocks and biomes) with their block and sky light,
 * in VSS's column format (per section: its y, {@code LevelChunkSection.write}, block light, sky light), handed to VSS's
 * translation into its wire format. Block ids are Minecraft's own state ids; biome ids this target's own, made once each.
 */
public final class VssColumns implements LodTarget {
   private static final BlockState AIR = Blocks.AIR.defaultBlockState();
   /** Voxy's biome bits (VoxyAccess.id). */
   private static final int MAX_BIOMES = 512;

   /** The column's bytes in VSS's format, from the native bytes. */
   public interface Translator {
      byte[] translate(byte[] nativeBody, ServerLevel level);
   }

   /** Where a finished chunk's column goes: chunk key to its bytes (null: all air); rough: not to be sent (LodBuilder.Column). */
   interface Out {
      void column(long key, byte[] bytes, boolean rough);
   }

   private final ServerLevel level;
   private final Translator translator;
   private final ConcurrentHashMap<Holder<Biome>, Integer> biomeIds = new ConcurrentHashMap<>();
   private final List<Holder<Biome>> biomes = new CopyOnWriteArrayList<>();
   private final ThreadLocal<Chunk> chunks = new ThreadLocal<>();
   /** Where this thread's chunks go (set by the caller around a tile). */
   final ThreadLocal<Out> out = new ThreadLocal<>();

   VssColumns(ServerLevel level, Translator translator) {
      this.level = level;
      this.translator = translator;
   }

   /** One chunk's sections as they are inserted (one per thread). */
   private static final class Chunk {
      int x = Integer.MIN_VALUE;
      int z = Integer.MIN_VALUE;
      ProtoChunk proto;
      final List<Integer> ys = new ArrayList<>();
      final List<DataLayer> sky = new ArrayList<>();
      final List<DataLayer> blockLight = new ArrayList<>();
      /** Each column's biome id so far (index z << 4 | x), -1 none yet. */
      final int[] columnBiome = new int[256];
   }

   @Override
   public int block(BlockState state) {
      return state.isAir() ? 0 : Block.BLOCK_STATE_REGISTRY.getId(state);
   }

   @Override
   public int biome(Holder<Biome> biome) {
      Integer id = this.biomeIds.get(biome);
      if (id != null) {
         return id;
      }

      synchronized (this.biomes) {
         id = this.biomeIds.get(biome);
         if (id == null) {
            id = this.biomes.size() < MAX_BIOMES ? this.biomes.size() : 0;
            if (id == this.biomes.size()) {
               this.biomes.add(biome);
            }

            this.biomeIds.put(biome, id);
         }

         return id;
      }
   }

   @Override
   public Object section(int x, int y, int z) {
      return new long[4096];
   }

   @Override
   public long[] data(Object section) {
      return (long[])section;
   }

   @Override
   public void insert(Object section, int x, int y, int z, int nonAir) {
      Chunk chunk = this.chunks.get();
      if (chunk == null) {
         chunk = new Chunk();
         this.chunks.set(chunk);
      }

      if (chunk.x != x || chunk.z != z || chunk.proto == null) {
         chunk.x = x;
         chunk.z = z;
         chunk.proto = Mc.emptyChunk(new ChunkPos(x, z), this.level, this.level.registryAccess());
         chunk.ys.clear();
         chunk.sky.clear();
         chunk.blockLight.clear();
         java.util.Arrays.fill(chunk.columnBiome, -1);
      }

      int index = y - Mc.minSection(this.level);
      LevelChunkSection[] sections = chunk.proto.getSections();
      if (index < 0 || index >= sections.length) {
         return;
      }

      LevelChunkSection target = sections[index];
      long[] ids = (long[])section;
      DataLayer sky = new DataLayer();
      DataLayer blockLight = null;
      for (int i = 0; i < 4096; i++) {
         long id = ids[i];
         int bx = i & 15;
         int bz = i >> 4 & 15;
         int by = i >> 8;
         int light = (int)(id >>> 56) & 0xFF;
         if ((light & 15) != 0) {
            sky.set(bx, by, bz, light & 15);
         }

         if (light >> 4 != 0) {
            if (blockLight == null) {
               blockLight = new DataLayer();
            }

            blockLight.set(bx, by, bz, light >> 4);
         }

         int block = (int)(id >>> 27) & 0xFFFFF;
         if (block != 0) {
            // Air carries no biome (VoxyAccess.air): a column's biome is its blocks', kept for the sections above.
            chunk.columnBiome[i & 255] = (int)(id >>> 47) & 0x1FF;
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(block);
            if (state != null && state != AIR) {
               target.setBlockState(bx, by, bz, state, false);
            }
         }
      }

      // Biomes: one per 4 x 4 x 4 blocks, the middle column's.
      PalettedContainer<Holder<Biome>> biomeContainer = (PalettedContainer<Holder<Biome>>)target.getBiomes();
      for (int qz = 0; qz < 4; qz++) {
         for (int qx = 0; qx < 4; qx++) {
            int b = chunk.columnBiome[(qz * 4 + 2) << 4 | qx * 4 + 2];
            if (b >= 0 && b < this.biomes.size()) {
               Holder<Biome> holder = this.biomes.get(b);
               for (int qy = 0; qy < 4; qy++) {
                  biomeContainer.getAndSetUnchecked(qx, qy, qz, holder);
               }
            }
         }
      }

      chunk.ys.add(y);
      chunk.sky.add(sky.isEmpty() ? null : sky);
      chunk.blockLight.add(blockLight);
   }

   @Override
   public void chunkDone(int x, int z, boolean rough) {
      Chunk chunk = this.chunks.get();
      Out out = this.out.get();
      if (chunk == null || chunk.proto == null || chunk.x != x || chunk.z != z) {
         if (out != null) {
            out.column(Mc.key(x, z), null, rough);
         }

         return;
      }

      byte[] bytes = null;
      try {
         bytes = rough ? null : this.serialize(chunk);
      } finally {
         chunk.proto = null;
      }

      if (out != null) {
         out.column(Mc.key(x, z), bytes, rough);
      }
   }

   /** The chunk's sections in VSS's format (null: nothing in them). */
   private byte[] serialize(Chunk chunk) {
      LevelChunkSection[] sections = chunk.proto.getSections();
      int minSection = Mc.minSection(this.level);
      int count = 0;
      for (int i = 0; i < chunk.ys.size(); i++) {
         LevelChunkSection section = sections[chunk.ys.get(i) - minSection];
         if (!section.hasOnlyAir() || chunk.sky.get(i) != null || chunk.blockLight.get(i) != null) {
            count++;
         }
      }

      if (count == 0) {
         return null;
      }

      FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(count * 2048 + 64));
      try {
         buf.writeVarInt(count);
         for (int i = 0; i < chunk.ys.size(); i++) {
            int y = chunk.ys.get(i);
            LevelChunkSection section = sections[y - minSection];
            DataLayer sky = chunk.sky.get(i);
            DataLayer blockLight = chunk.blockLight.get(i);
            if (section.hasOnlyAir() && sky == null && blockLight == null) {
               continue;
            }

            buf.writeByte(y);
            section.write(buf);
            buf.writeBoolean(blockLight != null);
            if (blockLight != null) {
               buf.writeBytes(blockLight.getData());
            }

            buf.writeBoolean(sky != null);
            if (sky != null) {
               buf.writeBytes(sky.getData());
            }
         }

         byte[] body = new byte[buf.readableBytes()];
         buf.readBytes(body);
         return this.translator.translate(body, this.level);
      } finally {
         buf.release();
      }
   }
}
