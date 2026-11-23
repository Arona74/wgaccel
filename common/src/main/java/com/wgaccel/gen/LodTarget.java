package com.wgaccel.gen;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Where {@link VoxyWriter} puts the LOD sections it makes: Voxy's own world ({@link VoxyAccess.Target}, in the game with
 * Voxy installed) or the columns Voxy Server Side sends to its players ({@link VssColumns}, on a server). A section is
 * 4,096 ids as {@link VoxyAccess#id} packs them (light, biome, block), index y << 8 | z << 4 | x.
 */
public interface LodTarget {
   /** The block state's id (0 is air). */
   int block(BlockState state);

   /** The biome's id. */
   int biome(Holder<Biome> biome);

   /** A new section at section position (x, y, z), reusable once inserted. */
   Object section(int x, int y, int z);

   /** A section's ids: the first 4,096 are its blocks; more may follow (Voxy's mips). */
   long[] data(Object section);

   /** The section's ids written, taken at (x, y, z) (copied: the section is written again for the next one). */
   void insert(Object section, int x, int y, int z, int nonAir);

   /** All of chunk (x, z)'s sections were inserted (this thread); rough: some of its columns are rough (LodBuilder.Column). */
   default void chunkDone(int x, int z, boolean rough) {
   }

   /** Whether world sections can be pinned ({@link #pin}). */
   default boolean pins() {
      return false;
   }

   /** A reference of ours on world section (lvl, x, y, z), or null. */
   default Object pin(int lvl, int x, int y, int z) {
      return null;
   }

   /** Our reference given back. */
   default void unpin(Object section) {
   }

   /** The target given back (once). */
   default void release() {
   }
}
