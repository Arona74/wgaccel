package com.wgaccel.gen;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BuddingAmethystBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.GeodeBlockSettings;
import net.minecraft.world.level.levelgen.GeodeCrackSettings;
import net.minecraft.world.level.levelgen.GeodeLayerSettings;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.material.FluidState;

/**
 * Fast geode placement matching {@link net.minecraft.world.level.levelgen.feature.GeodeFeature#place}
 * bit-for-bit while skipping {@link NormalNoise#getValue} on blocks outside the conservative upper bound.
 */
public final class FastGeodePlace {
   @FunctionalInterface
   public interface SafeSet {
      void set(BlockPos pos, BlockState state);
   }

   private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

   private FastGeodePlace() {
   }

   public static boolean place(FeaturePlaceContext<GeodeConfiguration> context, Direction[] directions, SafeSet safeSet) {
      GeodeConfiguration config = context.config();
      RandomSource random = context.random();
      BlockPos origin = context.origin();
      WorldGenLevel level = context.level();
      int minGenOffset = config.minGenOffset;
      int maxGenOffset = config.maxGenOffset;
      int distributionPoints = config.distributionPoints.sample(random);
      WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(level.getSeed()) {
         private long localSeed;
         @Override
         public void setSeed(long s) {
            this.localSeed = (s ^ 0x5DEECE66DL) & ((1L << 48) - 1);
         }
         @Override
         public int next(int bits) {
            long next = (this.localSeed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            this.localSeed = next;
            return (int)(next >>> (48 - bits));
         }
      });
      NormalNoise normalNoise = NormalNoise.create(worldgenRandom, -4, 1.0);

      double outerWallMax = (double)distributionPoints / (double)config.outerWallDistance.getMaxValue();
      GeodeLayerSettings layerSettings = config.geodeLayerSettings;
      GeodeBlockSettings blockSettings = config.geodeBlockSettings;
      GeodeCrackSettings crackSettings = config.geodeCrackSettings;
      double fillingThreshold = 1.0 / Math.sqrt(layerSettings.filling);
      double innerThreshold = 1.0 / Math.sqrt(layerSettings.innerLayer + outerWallMax);
      double middleThreshold = 1.0 / Math.sqrt(layerSettings.middleLayer + outerWallMax);
      double outerThreshold = 1.0 / Math.sqrt(layerSettings.outerLayer + outerWallMax);
      double crackThreshold = 1.0 / Math.sqrt(
         crackSettings.baseCrackSize + random.nextDouble() / 2.0 + (distributionPoints > 3 ? outerWallMax : 0.0)
      );
      boolean generateCrack = (double)random.nextFloat() < crackSettings.generateCrackChance;

      Scratch scratch = SCRATCH.get();
      scratch.ensurePoints(distributionPoints);
      int[] px = scratch.px;
      int[] py = scratch.py;
      int[] pz = scratch.pz;
      int[] pOff = scratch.pOff;
      double[] invPts = scratch.invPts;

      int invalidCount = 0;
      for (int i = 0; i < distributionPoints; i++) {
         int ox = config.outerWallDistance.sample(random);
         int oy = config.outerWallDistance.sample(random);
         int oz = config.outerWallDistance.sample(random);
         BlockPos pointPos = origin.offset(ox, oy, oz);
         BlockState state = level.getBlockState(pointPos);
         if (state.isAir() || state.is(BlockTags.GEODE_INVALID_BLOCKS)) {
            if (++invalidCount > config.invalidBlocksThreshold) {
               return false;
            }
         }

         px[i] = pointPos.getX();
         py[i] = pointPos.getY();
         pz[i] = pointPos.getZ();
         pOff[i] = config.pointOffset.sample(random);
      }

      int crackCount = 0;
      int[] cx = scratch.cx;
      int[] cy = scratch.cy;
      int[] cz = scratch.cz;
      if (generateCrack) {
         int dirChoice = random.nextInt(4);
         int crackDist = distributionPoints * 2 + 1;
         int ox = origin.getX();
         int oy = origin.getY();
         int oz = origin.getZ();
         if (dirChoice == 0) {
            cx[0] = ox + crackDist; cy[0] = oy + 7; cz[0] = oz;
            cx[1] = ox + crackDist; cy[1] = oy + 5; cz[1] = oz;
            cx[2] = ox + crackDist; cy[2] = oy + 1; cz[2] = oz;
         } else if (dirChoice == 1) {
            cx[0] = ox; cy[0] = oy + 7; cz[0] = oz + crackDist;
            cx[1] = ox; cy[1] = oy + 5; cz[1] = oz + crackDist;
            cx[2] = ox; cy[2] = oy + 1; cz[2] = oz + crackDist;
         } else if (dirChoice == 2) {
            cx[0] = ox + crackDist; cy[0] = oy + 7; cz[0] = oz + crackDist;
            cx[1] = ox + crackDist; cy[1] = oy + 5; cz[1] = oz + crackDist;
            cx[2] = ox + crackDist; cy[2] = oy + 1; cz[2] = oz + crackDist;
         } else {
            cx[0] = ox; cy[0] = oy + 7; cz[0] = oz;
            cx[1] = ox; cy[1] = oy + 5; cz[1] = oz;
            cx[2] = ox; cy[2] = oy + 1; cz[2] = oz;
         }
         crackCount = 3;
      }

      List<BlockPos> innerPlacements = new ArrayList<>();
      double pointNoiseBound = (double)distributionPoints * Math.abs(config.noiseMultiplier) * normalNoise.maxValue()
         + 1.0e-11 * Math.max(1, distributionPoints);
      int crackOffset = crackSettings.crackPointOffset;

      for (BlockPos pos : BlockPos.betweenClosed(
            origin.offset(minGenOffset, minGenOffset, minGenOffset),
            origin.offset(maxGenOffset, maxGenOffset, maxGenOffset))) {
         int x = pos.getX();
         int y = pos.getY();
         int z = pos.getZ();
         double invSum = 0.0;
         for (int i = 0; i < distributionPoints; i++) {
            double dx = (double)(x - px[i]);
            double dy = (double)(y - py[i]);
            double dz = (double)(z - pz[i]);
            double inv = Mth.invSqrt(dx * dx + dy * dy + dz * dz + (double)pOff[i]);
            invPts[i] = inv;
            invSum += inv;
         }

         if (invSum + pointNoiseBound < outerThreshold) {
            continue;
         }

         double noiseVal = normalNoise.getValue((double)x, (double)y, (double)z) * config.noiseMultiplier;
         double d8 = 0.0;
         for (int i = 0; i < distributionPoints; i++) {
            d8 += invPts[i] + noiseVal;
         }

         if (d8 < outerThreshold) {
            continue;
         }

         double d9 = 0.0;
         for (int i = 0; i < crackCount; i++) {
            double dx = (double)(x - cx[i]);
            double dy = (double)(y - cy[i]);
            double dz = (double)(z - cz[i]);
            d9 += Mth.invSqrt(dx * dx + dy * dy + dz * dz + (double)crackOffset) + noiseVal;
         }

         if (generateCrack && d9 >= crackThreshold && d8 < fillingThreshold) {
            safeSet.set(pos, Blocks.AIR.defaultBlockState());
            for (Direction dir : directions) {
               BlockPos rel = pos.relative(dir);
               FluidState fluid = level.getFluidState(rel);
               if (!fluid.isEmpty()) {
                  level.scheduleTick(rel, fluid.getType(), 0);
               }
            }
         } else if (d8 >= fillingThreshold) {
            safeSet.set(pos, blockSettings.fillingProvider.getState(random, pos));
         } else if (d8 >= innerThreshold) {
            boolean alt = (double)random.nextFloat() < config.useAlternateLayer0Chance;
            BlockState innerState = alt
               ? blockSettings.alternateInnerLayerProvider.getState(random, pos)
               : blockSettings.innerLayerProvider.getState(random, pos);
            safeSet.set(pos, innerState);
            if ((!config.placementsRequireLayer0Alternate || alt) && (double)random.nextFloat() < config.usePotentialPlacementsChance) {
               innerPlacements.add(pos.immutable());
            }
         } else if (d8 >= middleThreshold) {
            safeSet.set(pos, blockSettings.middleLayerProvider.getState(random, pos));
         } else {
            safeSet.set(pos, blockSettings.outerLayerProvider.getState(random, pos));
         }
      }

      List<BlockState> clusters = blockSettings.innerPlacements;
      for (BlockPos clusterOrigin : innerPlacements) {
         BlockState clusterState = Util.getRandom(clusters, random);
         for (Direction dir : directions) {
            if (clusterState.hasProperty(BlockStateProperties.FACING)) {
               clusterState = clusterState.setValue(BlockStateProperties.FACING, dir);
            }
            BlockPos adj = clusterOrigin.relative(dir);
            BlockState adjState = level.getBlockState(adj);
            if (clusterState.hasProperty(BlockStateProperties.WATERLOGGED)) {
               clusterState = clusterState.setValue(BlockStateProperties.WATERLOGGED, adjState.getFluidState().isSource());
            }
            if (BuddingAmethystBlock.canClusterGrowAtState(adjState)) {
               safeSet.set(adj, clusterState);
               break;
            }
         }
      }

      return true;
   }

   private static final class Scratch {
      int[] px = new int[16];
      int[] py = new int[16];
      int[] pz = new int[16];
      int[] pOff = new int[16];
      double[] invPts = new double[16];
      final int[] cx = new int[3];
      final int[] cy = new int[3];
      final int[] cz = new int[3];

      void ensurePoints(int n) {
         if (n > this.px.length) {
            this.px = new int[n];
            this.py = new int[n];
            this.pz = new int[n];
            this.pOff = new int[n];
            this.invPts = new double[n];
         }
      }
   }
}
