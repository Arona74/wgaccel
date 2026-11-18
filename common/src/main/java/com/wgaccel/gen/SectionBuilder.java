package com.wgaccel.gen;

import com.wgaccel.platform.LoaderHooks;
import com.wgaccel.platform.Mc;
import io.netty.buffer.Unpooled;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * S3: a whole 16^3 section's block states made at once from its 4,096 blocks (index (y * 16 + z) * 16 + x), instead of
 * 4,096 setBlockState calls: the palette in order of first appearance, the indexes packed as Minecraft's
 * SimpleBitStorage packs them (no value crosses a long), read into a fresh container through its own network format
 * (PalettedContainer.read: bits, palette, data). The same blocks either way; the in-memory palette may be ordered
 * otherwise, which nothing can see (saving packs the palette anew from the blocks; S3's one-block sections rely on the
 * same). Up to 16 states a linear palette of 4 bits, up to 256 a hash-map palette of 5 to 8 bits (as Minecraft's
 * SECTION_STATES makes them: FreeTerraForged's strata often put more than 16 states in a section); more returns null and
 * the caller writes block by block.
 */
public final class SectionBuilder {
   private static final BlockState AIR = Blocks.AIR.defaultBlockState();
   private static final java.util.concurrent.atomic.AtomicLong BUILT = new java.util.concurrent.atomic.AtomicLong();
   private static volatile boolean broken;

   private SectionBuilder() {
   }

   /**
    * A new section of these states and biomes with its block counts as setBlockState would have counted them, block by
    * block: non-empty blocks, randomly ticking blocks among them, and every fluid as a ticking fluid. The section's own
    * recount (its constructor's) counts fluid blocks twice as non-empty and only randomly ticking fluids, so a section
    * made at once would otherwise differ from one written block by block (the blocks are the same either way). On
    * versions whose recount matches writing ({@link Mc#SECTION_RECOUNT_EXACT}: 26.1.2) the section's own counts stay.
    */
   public static net.minecraft.world.level.chunk.LevelChunkSection section(PalettedContainer<BlockState> states,
      net.minecraft.world.level.chunk.PalettedContainerRO<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes, BlockState[] blocks) {
      net.minecraft.world.level.chunk.LevelChunkSection section = new net.minecraft.world.level.chunk.LevelChunkSection(states, biomes);
      if (Mc.SECTION_RECOUNT_EXACT) {
         return section;
      }

      int nonEmpty = 0;
      int ticking = 0;
      int fluids = 0;
      LoaderHooks hooks = LoaderHooks.get();
      for (BlockState state : blocks) {
         if (!hooks.isEmpty(state)) {
            nonEmpty++;
            if (state.isRandomlyTicking()) {
               ticking++;
            }
         }

         if (!state.getFluidState().isEmpty()) {
            fluids++;
         }
      }

      setCounts(section, nonEmpty, ticking, fluids);
      return section;
   }

   /** A new section of one state everywhere, its counts as {@link #section(PalettedContainer, net.minecraft.world.level.chunk.PalettedContainerRO, BlockState[])} gives them. */
   public static net.minecraft.world.level.chunk.LevelChunkSection uniform(BlockState state,
      net.minecraft.world.level.chunk.PalettedContainerRO<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes) {
      net.minecraft.world.level.chunk.LevelChunkSection section = new net.minecraft.world.level.chunk.LevelChunkSection(
         Mc.blockStates(state), biomes);
      if (Mc.SECTION_RECOUNT_EXACT) {
         return section;
      }

      boolean nonEmpty = !LoaderHooks.get().isEmpty(state);
      setCounts(section, nonEmpty ? 4096 : 0, nonEmpty && state.isRandomlyTicking() ? 4096 : 0, state.getFluidState().isEmpty() ? 0 : 4096);
      return section;
   }

   private static void setCounts(net.minecraft.world.level.chunk.LevelChunkSection section, int nonEmpty, int ticking, int fluids) {
      com.wgaccel.mixin.access.LevelChunkSectionAccessor access = (com.wgaccel.mixin.access.LevelChunkSectionAccessor)section;
      access.wgaccel$setNonEmpty((short)nonEmpty);
      access.wgaccel$setTickingBlocks((short)ticking);
      access.wgaccel$setTickingFluids((short)fluids);
   }

   /** Sections that needed more than 256 states (written block by block), for the report. */
   public static final java.util.concurrent.atomic.LongAdder TOO_MANY = new java.util.concurrent.atomic.LongAdder();

   private static final class Scratch {
      private static final int HT_SIZE = 512;
      private static final int HT_MASK = HT_SIZE - 1;
      final BlockState[] htKeys = new BlockState[HT_SIZE];
      final int[] htVals = new int[HT_SIZE];
      final int[] htStamp = new int[HT_SIZE];
      int epoch = 1;
      final BlockState[] palette = new BlockState[256];
      final int[] counts = new int[256];
      final int[] index = new int[4096];

      void reset() {
         int next = ++this.epoch;
         if (next == 0) {
            java.util.Arrays.fill(this.htStamp, 0);
            this.epoch = 1;
         }
      }
   }

   private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

   /**
    * Builds a full {@link net.minecraft.world.level.chunk.LevelChunkSection} in one pass over {@code blocks},
    * reusing thread-local scratch buffers and counting empty/ticking/fluid blocks once per unique palette entry.
    * Returns {@code null} when more than 256 states appear (caller writes block by block).
    */
   public static net.minecraft.world.level.chunk.LevelChunkSection buildSection(BlockState[] blocks,
         net.minecraft.world.level.chunk.PalettedContainerRO<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes) {
      if (broken) {
         return null;
      }

      Scratch scratch = SCRATCH.get();
      scratch.reset();
      BlockState[] htKeys = scratch.htKeys;
      int[] htVals = scratch.htVals;
      int[] htStamp = scratch.htStamp;
      int epoch = scratch.epoch;
      BlockState[] palette = scratch.palette;
      int[] counts = scratch.counts;
      int[] index = scratch.index;
      int size = 0;
      BlockState last = null;
      int lastId = 0;
      int run = 0;
      for (int i = 0; i < 4096; i++) {
         BlockState state = blocks[i];
         if (state == last) {
            index[i] = lastId;
            run++;
            continue;
         }

         if (run > 0) {
            counts[lastId] += run;
            run = 0;
         }

         int h = System.identityHashCode(state);
         int slot = (h ^ (h >>> 16)) & Scratch.HT_MASK;
         int id = -1;
         while (htStamp[slot] == epoch) {
            if (htKeys[slot] == state) {
               id = htVals[slot];
               break;
            }
            slot = (slot + 1) & Scratch.HT_MASK;
         }
         if (id < 0) {
            if (size == 256) {
               TOO_MANY.increment();
               return null;
            }

            id = size;
            htStamp[slot] = epoch;
            htKeys[slot] = state;
            htVals[slot] = id;
            palette[size++] = state;
            counts[id] = 0;
         }

         index[i] = id;
         last = state;
         lastId = id;
         run = 1;
      }

      if (run > 0) {
         counts[lastId] += run;
      }

      if (size == 1) {
         return uniform(palette[0], biomes);
      }

      PalettedContainer<BlockState> states = packStates(palette, index, size);
      long n = BUILT.getAndIncrement();
      if (n < 64 || n % 256 == 0) {
         for (int i = 0; i < 4096; i++) {
            if (states.get(i & 15, i >> 8, (i >> 4) & 15) != blocks[i]) {
               broken = true;
               com.wgaccel.WgaChat.warnOff("WGA bulk sections off: a built section read back {} at index {} instead of {}",
                  states.get(i & 15, i >> 8, (i >> 4) & 15), i, blocks[i]);
               return null;
            }
         }
      }

      net.minecraft.world.level.chunk.LevelChunkSection section = new net.minecraft.world.level.chunk.LevelChunkSection(states, biomes);
      if (!Mc.SECTION_RECOUNT_EXACT) {
         int nonEmpty = 0;
         int ticking = 0;
         int fluids = 0;
         LoaderHooks hooks = LoaderHooks.get();
         for (int i = 0; i < size; i++) {
            BlockState state = palette[i];
            int c = counts[i];
            if (!hooks.isEmpty(state)) {
               nonEmpty += c;
               if (state.isRandomlyTicking()) {
                  ticking += c;
               }
            }

            if (!state.getFluidState().isEmpty()) {
               fluids += c;
            }
         }

         setCounts(section, nonEmpty, ticking, fluids);
      }

      return section;
   }

   private static long[] packIndexArray(int[] index, int bits) {
      if (bits == 4) {
         long[] data = new long[256];
         for (int w = 0, b = 0; w < 256; w++, b += 16) {
            data[w] = (long)index[b]
               | ((long)index[b + 1] << 4)
               | ((long)index[b + 2] << 8)
               | ((long)index[b + 3] << 12)
               | ((long)index[b + 4] << 16)
               | ((long)index[b + 5] << 20)
               | ((long)index[b + 6] << 24)
               | ((long)index[b + 7] << 28)
               | ((long)index[b + 8] << 32)
               | ((long)index[b + 9] << 36)
               | ((long)index[b + 10] << 40)
               | ((long)index[b + 11] << 44)
               | ((long)index[b + 12] << 48)
               | ((long)index[b + 13] << 52)
               | ((long)index[b + 14] << 56)
               | ((long)index[b + 15] << 60);
         }
         return data;
      }

      int perLong = 64 / bits;
      long[] data = new long[(4096 + perLong - 1) / perLong];
      int word = 0, shift = 0, slot = 0;
      long acc = 0L;
      for (int i = 0; i < 4096; i++) {
         acc |= (long)index[i] << shift;
         if (++slot == perLong) {
            data[word++] = acc;
            acc = 0L;
            shift = 0;
            slot = 0;
         } else {
            shift += bits;
         }
      }
      if (slot > 0) {
         data[word] = acc;
      }
      return data;
   }

   private static PalettedContainer<BlockState> packStates(BlockState[] palette, int[] index, int size) {
      int bits = size <= 16 ? 4 : net.minecraft.util.Mth.ceillog2(size);
      long[] data = packIndexArray(index, bits);

      PalettedContainer<BlockState> states = Mc.packedBlockStates(palette, size, bits, data);
      if (states == null) {
         FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(8 + 5 * size + 9 * data.length));
         try {
            buffer.writeByte(bits);
            buffer.writeVarInt(size);
            for (int i = 0; i < size; i++) {
               buffer.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(palette[i]));
            }

            Mc.writeStateData(buffer, data);
            states = Mc.blockStates(AIR);
            states.read(buffer);
         } finally {
            buffer.release();
         }
      }

      return states;
   }

   /** The section's states, or null when they need more than a 256-entry palette. */
   public static PalettedContainer<BlockState> build(BlockState[] blocks) {
      if (broken) {
         return null;
      }

      Scratch scratch = SCRATCH.get();
      scratch.reset();
      BlockState[] htKeys = scratch.htKeys;
      int[] htVals = scratch.htVals;
      int[] htStamp = scratch.htStamp;
      int epoch = scratch.epoch;
      BlockState[] palette = scratch.palette;
      int[] index = scratch.index;
      int size = 0;
      BlockState last = null;
      int lastId = 0;
      for (int i = 0; i < 4096; i++) {
         BlockState state = blocks[i];
         if (state == last) {
            index[i] = lastId;
            continue;
         }

         int h = System.identityHashCode(state);
         int slot = (h ^ (h >>> 16)) & Scratch.HT_MASK;
         int id = -1;
         while (htStamp[slot] == epoch) {
            if (htKeys[slot] == state) {
               id = htVals[slot];
               break;
            }
            slot = (slot + 1) & Scratch.HT_MASK;
         }
         if (id < 0) {
            if (size == 256) {
               TOO_MANY.increment();
               return null;
            }

            id = size;
            htStamp[slot] = epoch;
            htKeys[slot] = state;
            htVals[slot] = id;
            palette[size++] = state;
         }

         index[i] = id;
         last = state;
         lastId = id;
      }

      if (size == 1) {
         return Mc.blockStates(palette[0]);
      }

      int bits = size <= 16 ? 4 : net.minecraft.util.Mth.ceillog2(size);
      long[] data = packIndexArray(index, bits);

      PalettedContainer<BlockState> states = Mc.packedBlockStates(palette, size, bits, data);
      if (states == null) {
      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(8 + 5 * size + 9 * data.length));
      try {
      buffer.writeByte(bits);
      buffer.writeVarInt(size);
      for (int i = 0; i < size; i++) {
         buffer.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(palette[i]));
      }

      Mc.writeStateData(buffer, data);
      states = Mc.blockStates(AIR);
      states.read(buffer);
      } finally { buffer.release(); }
      }
      // The first 64 sections, then every 256th, read back block by block: any difference turns this off for good.
      long n = BUILT.getAndIncrement();
      if (n < 64 || n % 256 == 0) {
         for (int i = 0; i < 4096; i++) {
            if (states.get(i & 15, i >> 8, (i >> 4) & 15) != blocks[i]) {
               broken = true;
               com.wgaccel.WgaChat.warnOff("WGA bulk sections off: a built section read back {} at index {} instead of {}",
                  states.get(i & 15, i >> 8, (i >> 4) & 15), i, blocks[i]);
               return null;
            }
         }
      }

      return states;
   }

   /** Directly materialize ordinary compact codes. Per-column sheet states keep the original resolver path. */
   public static net.minecraft.world.level.chunk.LevelChunkSection fromCodes(byte[] codes, int from, BlockState[] table, int sheetCode,
      net.minecraft.world.level.chunk.PalettedContainerRO<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes) {
      if (broken) return null;
      int[] ids = new int[16]; java.util.Arrays.fill(ids, -1);
      int[] counts = new int[16];
      BlockState[] palette = new BlockState[16];
      int size = 0;
      for (int i = 0; i < 4096; i++) {
         int code = codes[from + i] & 15;
         if (code == sheetCode || code >= table.length || table[code] == null) return null;
         if (ids[code] < 0) {
            int id = 0;
            while (id < size && palette[id] != table[code]) id++;
            if (id == size) palette[size++] = table[code];
            ids[code] = id;
         }
         counts[ids[code]]++;
      }
      PalettedContainer<BlockState> states;
      if (size == 1) states = Mc.blockStates(palette[0]);
      else {
         long[] packed = new long[256];
         for (int i = 0; i < 4096; i++) packed[i >> 4] |= (long)ids[codes[from + i] & 15] << (4 * (i & 15));
         states = Mc.packedBlockStates(palette, size, 4, packed);
         if (states == null) return null;
      }
      long n = BUILT.getAndIncrement();
      if (n < 64 || n % 256 == 0) for (int i = 0; i < 4096; i++) {
         if (states.get(i & 15, i >> 8, i >> 4 & 15) != table[codes[from + i] & 15]) {
            broken = true;
            com.wgaccel.WgaChat.warnOff("WGA compact section builder off: read-back mismatch at index {}", i);
            return null;
         }
      }
      var section = new net.minecraft.world.level.chunk.LevelChunkSection(states, biomes);
      if (!Mc.SECTION_RECOUNT_EXACT) {
         int nonEmpty = 0, ticking = 0, fluids = 0;
         LoaderHooks hooks = LoaderHooks.get();
         for (int i = 0; i < size; i++) {
            BlockState state = palette[i];
            if (!hooks.isEmpty(state)) { nonEmpty += counts[i]; if (state.isRandomlyTicking()) ticking += counts[i]; }
            if (!state.getFluidState().isEmpty()) fluids += counts[i];
         }
         setCounts(section, nonEmpty, ticking, fluids);
      }
      return section;
   }
}
