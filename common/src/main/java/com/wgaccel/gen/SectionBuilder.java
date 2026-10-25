package com.wgaccel.gen;

import com.wgaccel.platform.LoaderHooks;
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
    * made at once would otherwise differ from one written block by block (the blocks are the same either way).
    */
   public static net.minecraft.world.level.chunk.LevelChunkSection section(PalettedContainer<BlockState> states,
      net.minecraft.world.level.chunk.PalettedContainerRO<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes, BlockState[] blocks) {
      net.minecraft.world.level.chunk.LevelChunkSection section = new net.minecraft.world.level.chunk.LevelChunkSection(states, biomes);
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
         new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, state, PalettedContainer.Strategy.SECTION_STATES), biomes);
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

   /** The section's states, or null when they need more than a 256-entry palette. */
   public static PalettedContainer<BlockState> build(BlockState[] blocks) {
      if (broken) {
         return null;
      }

      Map<BlockState, Integer> ids = new IdentityHashMap<>();
      BlockState[] palette = new BlockState[256];
      int[] index = new int[4096];
      int size = 0;
      BlockState last = null;
      int lastId = 0;
      for (int i = 0; i < 4096; i++) {
         BlockState state = blocks[i];
         if (state == last) {
            // Runs of one state (strata layers, stone, water) skip the map.
            index[i] = lastId;
            continue;
         }

         Integer id = ids.get(state);
         if (id == null) {
            if (size == 256) {
               TOO_MANY.increment();
               return null;
            }

            id = size;
            ids.put(state, id);
            palette[size++] = state;
         }

         index[i] = id;
         last = state;
         lastId = id;
      }

      if (size == 1) {
         return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, palette[0], PalettedContainer.Strategy.SECTION_STATES);
      }

      // SECTION_STATES: 2 to 16 entries are a linear palette with 4 bits per block, 17 to 256 a hash-map palette with 5
      // to 8 (its entries in the order read, as the linear one's); SimpleBitStorage puts 64 / bits values in each long.
      int bits = size <= 16 ? 4 : net.minecraft.util.Mth.ceillog2(size);
      int perLong = 64 / bits;
      long[] data = new long[(4096 + perLong - 1) / perLong];
      for (int i = 0; i < 4096; i++) {
         data[i / perLong] |= (long)index[i] << ((i % perLong) * bits);
      }

      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(8 + 5 * size + 9 * data.length));
      buffer.writeByte(bits);
      buffer.writeVarInt(size);
      for (int i = 0; i < size; i++) {
         buffer.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(palette[i]));
      }

      buffer.writeLongArray(data);
      PalettedContainer<BlockState> states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, AIR, PalettedContainer.Strategy.SECTION_STATES);
      states.read(buffer);
      buffer.release();
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
}
