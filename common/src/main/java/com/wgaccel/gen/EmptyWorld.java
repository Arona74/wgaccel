package com.wgaccel.gen;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * An empty world (air everywhere) for the game's tree placers to grow a tree in ({@link LodTrees}): a proxy of whatever
 * world interfaces they ask for (NeoForge's trunk placer casts to LevelReader; 26.1.2's placers take a WorldGenLevel).
 * Blocks and fluids: air and none; heightmaps: the position asked; anything else: null, 0 or false (a placer that needs
 * more fails, and the caller draws a simple shape).
 */
public final class EmptyWorld {
   private static final BlockState AIR = Blocks.AIR.defaultBlockState();

   private EmptyWorld() {
   }

   @SuppressWarnings("unchecked")
   public static <T> T of(Class<T> type, Class<?>... more) {
      Class<?>[] types = new Class<?>[more.length + 1];
      types[0] = type;
      System.arraycopy(more, 0, types, 1, more.length);
      return (T)Proxy.newProxyInstance(type.getClassLoader(), types, (proxy, method, args) -> {
         String name = method.getName();
         Class<?> returns = method.getReturnType();
         switch (name) {
            case "isStateAtPosition":
               return ((Predicate<BlockState>)args[1]).test(AIR);
            case "isFluidAtPosition":
               return ((Predicate<FluidState>)args[1]).test(Fluids.EMPTY.defaultFluidState());
            case "getHeightmapPos":
               return args[1];
            case "hashCode":
               return System.identityHashCode(proxy);
            case "equals":
               return proxy == args[0];
            case "toString":
               return "WGA empty world";
            default:
               break;
         }

         if (returns == BlockState.class) {
            return AIR;
         } else if (returns == FluidState.class) {
            return Fluids.EMPTY.defaultFluidState();
         } else if (returns == Optional.class) {
            return Optional.empty();
         } else if (returns == boolean.class) {
            return false;
         } else if (returns == int.class) {
            return 0;
         } else if (returns == long.class) {
            return 0L;
         } else if (returns == float.class) {
            return 0.0F;
         } else if (returns == double.class) {
            return 0.0;
         } else if (returns == byte.class) {
            return (byte)0;
         } else if (returns == short.class) {
            return (short)0;
         } else if (returns == char.class) {
            return (char)0;
         }

         return null;
      });
   }
}
