package com.wgaccel.platform;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Where NeoForge's patched Minecraft and vanilla's (Fabric) differ in a way the mod's results must follow, answered by
 * the loader's module. Game classes in its calls: used only once the game runs, never from {@link Platform}'s early
 * callers (the mixin plugins).
 */
public interface LoaderHooks {
   /**
    * Whether a chunk section counts this state as no block (its non-empty count): NeoForge's sections ask isEmpty()
    * (air, cave air, void air, or a modded block saying so), vanilla's isAir(). The same for every vanilla block.
    */
   boolean isEmpty(BlockState state);

   /** A biome's climate as the game uses it: NeoForge's biome modifiers keep theirs apart; Fabric's write the biome's own. */
   Biome.ClimateSettings climate(Biome biome);

   /** The loader's implementation (one per game, so a hot path's call is a direct one once compiled). */
   static LoaderHooks get() {
      return Holder.INSTANCE;
   }

   final class Holder {
      private static final LoaderHooks INSTANCE = find();

      private Holder() {
      }

      private static LoaderHooks find() {
         for (String name : new String[] {"com.wgaccel.neoforge.NeoForgeHooks", "com.wgaccel.fabric.FabricHooks"}) {
            try {
               return (LoaderHooks)Class.forName(name).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError absent) {
               // The other loader's class.
            }
         }

         throw new IllegalStateException("no mod loader (outside the game)");
      }
   }
}
