package com.wgaccel.gen;

import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;

/**
 * S8/Option 10: exact drop-in acceleration of {@code OreFeature.doPlace}.
 *
 * <p>Preserves vanilla's sphere geometry, overlap pruning, {@code RandomSource} consumption order (including
 * air-exposure discard checks), {@code (x, y, z)} traversal order, and {@code BitSet} deduplication while
 * eliminating wasted inner-loop work via analytical exact IEEE {@code z}-intervals, hoisted height-limit and
 * section-run lookups, hoisted {@code BulkSectionAccess::getBlockState} lambdas, interior 6-neighbor checks,
 * empty non-matching section skipping, and per-call {@code BlockState} {@code RuleTest} memoization.
 */
public final class FastOreDoPlace {
   private static final int CACHE_SIZE = 16;
   private static final int CACHE_MASK = CACHE_SIZE - 1;
   private static final BlockState AIR = Blocks.AIR.defaultBlockState();
   private static final BlockState CAVE_AIR = Blocks.CAVE_AIR.defaultBlockState();
   private static final BlockState VOID_AIR = Blocks.VOID_AIR.defaultBlockState();

   private static final class Scratch {
      final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      long[] seenWords = new long[64];
      final BlockState[] cacheKeys = new BlockState[CACHE_SIZE];
      final int[] cacheTargets = new int[CACHE_SIZE];
      double[] spheres = new double[64];
      OreConfiguration.TargetBlockState[] targets = new OreConfiguration.TargetBlockState[8];

      boolean lastSecValid;
      int lastCx;
      int lastSy;
      int lastCz;
      LevelChunkSection lastSec;

      long[] secKeys = new long[128];
      boolean[] secOccupied = new boolean[128];
      LevelChunkSection[] secRefs = new LevelChunkSection[128];
      boolean[] secAcquired = new boolean[128];
      int[] secUsed = new int[128];
      int secUsedCount;
      LevelChunkSection[] acquired = new LevelChunkSection[64];
      int acquiredCount;

      void releaseAll() {
         for (int i = 0; i < this.acquiredCount; i++) {
            this.acquired[i].release();
            this.acquired[i] = null;
         }
         this.acquiredCount = 0;
         for (int i = 0; i < this.secUsedCount; i++) {
            int slot = this.secUsed[i];
            this.secOccupied[slot] = false;
            this.secRefs[slot] = null;
            this.secAcquired[slot] = false;
         }
         this.secUsedCount = 0;
         this.lastSecValid = false;
         this.lastSec = null;
      }

      void putSection(long key, LevelChunkSection section, boolean acquireNow) {
         if ((this.secUsedCount + 1) * 2 >= this.secKeys.length) {
            rehash();
         }

         int mask = this.secKeys.length - 1;
         int slot = mix(key) & mask;
         while (this.secOccupied[slot]) {
            slot = (slot + 1) & mask;
         }

         this.secOccupied[slot] = true;
         this.secKeys[slot] = key;
         this.secRefs[slot] = section;
         boolean acq = acquireNow && section != null;
         this.secAcquired[slot] = acq;
         this.secUsed[this.secUsedCount++] = slot;

         if (acq) {
            acquire(section);
         }
      }

      void acquire(LevelChunkSection section) {
         section.acquire();
         if (this.acquiredCount == this.acquired.length) {
            this.acquired = Arrays.copyOf(this.acquired, this.acquired.length * 2);
         }
         this.acquired[this.acquiredCount++] = section;
      }

      private void rehash() {
         int oldCap = this.secKeys.length;
         int newCap = oldCap * 2;
         long[] oldKeys = this.secKeys;
         LevelChunkSection[] oldRefs = this.secRefs;
         boolean[] oldAcq = this.secAcquired;
         long[] newKeys = new long[newCap];
         boolean[] newOcc = new boolean[newCap];
         LevelChunkSection[] newRefs = new LevelChunkSection[newCap];
         boolean[] newAcq = new boolean[newCap];
         int[] newUsed = new int[newCap];
         int mask = newCap - 1;
         int count = 0;
         for (int i = 0; i < this.secUsedCount; i++) {
            int oldSlot = this.secUsed[i];
            long key = oldKeys[oldSlot];
            int slot = mix(key) & mask;
            while (newOcc[slot]) {
               slot = (slot + 1) & mask;
            }
            newOcc[slot] = true;
            newKeys[slot] = key;
            newRefs[slot] = oldRefs[oldSlot];
            newAcq[slot] = oldAcq[oldSlot];
            newUsed[count++] = slot;
         }
         this.secKeys = newKeys;
         this.secOccupied = newOcc;
         this.secRefs = newRefs;
         this.secAcquired = newAcq;
         this.secUsed = newUsed;
      }

      private static int mix(long k) {
         k ^= k >>> 33;
         k *= 0xff51afd7ed558ccdL;
         k ^= k >>> 33;
         return (int)k;
      }
   }

   private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

   private FastOreDoPlace() {
   }

   public static boolean doPlace(
         WorldGenLevel level,
         RandomSource random,
         OreConfiguration config,
         double minX,
         double maxX,
         double minZ,
         double maxZ,
         double minY,
         double maxY,
         int xStart,
         int yStart,
         int zStart,
         int sizeXZ,
         int sizeY) {
      int size = config.size;
      if (size <= 0) {
         return false;
      }

      Scratch scratch = SCRATCH.get();
      int sphereDoubles = size * 4;
      double[] spheres = scratch.spheres;
      if (spheres.length < sphereDoubles) {
         spheres = new double[sphereDoubles];
         scratch.spheres = spheres;
      }

      for (int i = 0; i < size; i++) {
         float t = (float)i / (float)size;
         double sx = Mth.lerp(t, minX, maxX);
         double sy = Mth.lerp(t, minY, maxY);
         double sz = Mth.lerp(t, minZ, maxZ);
         double radiusSpread = random.nextDouble() * (double)size / 16.0D;
         double radius = ((double)(Mth.sin((float)Math.PI * t) + 1.0F) * radiusSpread + 1.0D) / 2.0D;
         int base = i * 4;
         spheres[base] = sx;
         spheres[base + 1] = sy;
         spheres[base + 2] = sz;
         spheres[base + 3] = radius;
      }

      List<OreConfiguration.TargetBlockState> targetList = config.targetStates;
      int targetCount = targetList.size();
      if (targetCount == 0) {
         return false;
      }

      for (int i = 0; i < size - 1; i++) {
         int iBase = i * 4;
         double ri = spheres[iBase + 3];
         if (ri <= 0.0D) {
            continue;
         }
         double xi = spheres[iBase];
         double yi = spheres[iBase + 1];
         double zi = spheres[iBase + 2];
         for (int j = i + 1; j < size; j++) {
            int jBase = j * 4;
            double rj = spheres[jBase + 3];
            if (rj <= 0.0D) {
               continue;
            }
            double dx = xi - spheres[jBase];
            double dy = yi - spheres[jBase + 1];
            double dz = zi - spheres[jBase + 2];
            double dr = ri - rj;
            if (dr * dr > dx * dx + dy * dy + dz * dz) {
               if (dr > 0.0D) {
                  spheres[jBase + 3] = -1.0D;
               } else {
                  spheres[iBase + 3] = -1.0D;
                  break;
               }
            }
         }
      }

      OreConfiguration.TargetBlockState[] targets = scratch.targets;
      if (targets.length < targetCount) {
         targets = new OreConfiguration.TargetBlockState[targetCount];
         scratch.targets = targets;
      }
      boolean allPure = true;
      for (int i = 0; i < targetCount; i++) {
         OreConfiguration.TargetBlockState entry = targetList.get(i);
         targets[i] = entry;
         RuleTest rule = entry.target;
         if (!(rule instanceof AlwaysTrueTest
               || rule instanceof BlockMatchTest
               || rule instanceof BlockStateMatchTest
               || rule instanceof TagMatchTest)) {
            allPure = false;
         }
      }

      boolean skipAirSections = false;
      if (allPure) {
         Arrays.fill(scratch.cacheKeys, null);
         skipAirSections = matchPure(targets, targetCount, AIR) < 0
               && matchPure(targets, targetCount, CAVE_AIR) < 0
               && matchPure(targets, targetCount, VOID_AIR) < 0;
      }

      int placed = 0;
      long[] seenWords = scratch.seenWords;
      int usedWords = 0;
      BlockPos.MutableBlockPos pos = scratch.pos;
      BlockState[] cacheKeys = scratch.cacheKeys;
      int[] cacheTargets = scratch.cacheTargets;
      int strideZ = sizeXZ * sizeY;
      float discardChance = config.discardChanceOnAirExposure;
      boolean chunkRegion = level instanceof WorldGenRegion;
      int lastChkCx = Integer.MIN_VALUE;
      int chkCz0 = Integer.MIN_VALUE;
      int chkCz1 = Integer.MIN_VALUE;
      boolean chkOk0 = false;
      boolean chkOk1 = false;
      int minSy = level.getMinSection();
      int sectionsCount = level.getSectionsCount();

      try {
         for (int i = 0; i < size; i++) {
            int base = i * 4;
            double sr = spheres[base + 3];
            if (sr < 0.0D) {
               continue;
            }

            double sx = spheres[base];
            double sy = spheres[base + 1];
            double sz = spheres[base + 2];
            int x0 = Math.max(Mth.floor(sx - sr), xStart);
            int y0 = Math.max(Mth.floor(sy - sr), yStart);
            int z0 = Math.max(Mth.floor(sz - sr), zStart);
            int x1 = Math.max(Mth.floor(sx + sr), x0);
            int y1 = Math.max(Mth.floor(sy + sr), y0);
            int z1 = Math.max(Mth.floor(sz + sr), z0);

            int maxBit = (x1 - xStart) + (y1 - yStart) * sizeXZ + (z1 - zStart) * strideZ;
            int reqWords = (maxBit >>> 6) + 1;
            if (reqWords > seenWords.length) {
               seenWords = Arrays.copyOf(seenWords, Math.max(reqWords, seenWords.length * 2));
               scratch.seenWords = seenWords;
            }
            if (reqWords > usedWords) {
               usedWords = reqWords;
            }

            for (int x = x0; x <= x1; x++) {
               double dx = ((double)x + 0.5D - sx) / sr;
               double dx2 = dx * dx;
               if (!(dx2 < 1.0D)) {
                  continue;
               }

               int cx = x >> 4;
               if (chunkRegion && cx != lastChkCx) {
                  lastChkCx = cx;
                  chkCz0 = Integer.MIN_VALUE;
                  chkCz1 = Integer.MIN_VALUE;
               }
               int lx = x & 15;
               boolean xInterior = lx >= 1 && lx <= 14;
               int xOffset = x - xStart;

               for (int y = y0; y <= y1; y++) {
                  int secIdx = (y >> 4) - minSy;
                  if (secIdx < 0 || secIdx >= sectionsCount || FastOres.outside(level, y)) {
                     continue;
                  }

                  double dy = ((double)y + 0.5D - sy) / sr;
                  double dxy2 = dx2 + dy * dy;
                  if (!(dxy2 < 1.0D)) {
                     continue;
                  }

                  double halfZ = sr * Math.sqrt(1.0D - dxy2);
                  int zMin = Math.max(z0, Mth.floor(sz - 0.5D - halfZ) - 1);
                  int zMax = Math.min(z1, Mth.floor(sz - 0.5D + halfZ) + 1);
                  while (zMin <= zMax && !inSphere(dxy2, zMin, sz, sr)) {
                     zMin++;
                  }
                  while (zMax >= zMin && !inSphere(dxy2, zMax, sz, sr)) {
                     zMax--;
                  }
                  if (zMin > zMax) {
                     continue;
                  }

                  int ly = y & 15;
                  boolean xyInterior = xInterior && ly >= 1 && ly <= 14;
                  int baseIdx = xOffset + (y - yStart) * sizeXZ;

                  int z = zMin;
                  while (z <= zMax) {
                     int cz = z >> 4;
                     int zSegEnd = Math.min(zMax, (cz << 4) + 15);
                     if (chunkRegion) {
                        boolean canWrite;
                        if (cz == chkCz0) {
                           canWrite = chkOk0;
                        } else if (cz == chkCz1) {
                           canWrite = chkOk1;
                        } else {
                           canWrite = level.ensureCanWrite(pos.set(x, y, z));
                           if (chkCz0 == Integer.MIN_VALUE) {
                              chkCz0 = cz;
                              chkOk0 = canWrite;
                           } else {
                              chkCz1 = cz;
                              chkOk1 = canWrite;
                           }
                        }
                        if (!canWrite) {
                           z = zSegEnd + 1;
                           continue;
                        }
                     }

                     LevelChunkSection section = getSection(level, scratch, cx, secIdx, cz);
                     if (section == null || (skipAirSections && section.hasOnlyAir())) {
                        z = zSegEnd + 1;
                        continue;
                     }

                     int bitIdx = baseIdx + (z - zStart) * strideZ;
                     for (int zz = z; zz <= zSegEnd; zz++, bitIdx += strideZ) {
                        int wordIdx = bitIdx >>> 6;
                        long bitMask = 1L << bitIdx;
                        if ((seenWords[wordIdx] & bitMask) != 0L) {
                           continue;
                        }
                        seenWords[wordIdx] |= bitMask;
                        if (!chunkRegion) {
                           pos.set(x, y, zz);
                           if (!level.ensureCanWrite(pos)) {
                              continue;
                           }
                        }

                        int lz = zz & 15;
                        BlockState state = section.getBlockState(lx, ly, lz);

                        int matchedIdx;
                        if (allPure) {
                           int slot = (System.identityHashCode(state) * 0x9E3779B9) >>> 28;
                           if (cacheKeys[slot] == state) {
                              matchedIdx = cacheTargets[slot];
                           } else {
                              matchedIdx = matchPure(targets, targetCount, state);
                              cacheKeys[slot] = state;
                              cacheTargets[slot] = matchedIdx;
                           }
                        } else {
                           matchedIdx = matchGeneral(targets, targetCount, state, random);
                        }

                        if (matchedIdx < 0) {
                           continue;
                        }

                        boolean canPlace;
                        if (discardChance <= 0.0F) {
                           canPlace = true;
                        } else if (discardChance >= 1.0F || random.nextFloat() < discardChance) {
                           if (xyInterior && lz >= 1 && lz <= 14) {
                              canPlace = !isInteriorAdjacentToAir(section, lx, ly, lz);
                           } else {
                              canPlace = !isBoundaryAdjacentToAir(level, scratch, minSy, sectionsCount, section, lx, ly, lz, x, y, zz);
                           }
                        } else {
                           canPlace = true;
                        }

                        if (canPlace) {
                           section.setBlockState(lx, ly, lz, targets[matchedIdx].state, false);
                           placed++;
                        }
                     }

                     z = zSegEnd + 1;
                  }
               }
            }
         }
      } finally {
         if (usedWords > 0) {
            Arrays.fill(seenWords, 0, Math.min(usedWords, seenWords.length), 0L);
         }
         scratch.releaseAll();
      }

      return placed > 0;
   }

   private static LevelChunkSection getSection(WorldGenLevel level, Scratch scratch, int cx, int secIdx, int cz) {
      if (scratch.lastSecValid && scratch.lastCx == cx && scratch.lastSy == secIdx && scratch.lastCz == cz) {
         return scratch.lastSec;
      }

      long key = SectionPos.asLong(cx, secIdx, cz);
      long[] keys = scratch.secKeys;
      boolean[] occ = scratch.secOccupied;
      int mask = keys.length - 1;
      int slot = Scratch.mix(key) & mask;
      while (occ[slot]) {
         if (keys[slot] == key) {
            LevelChunkSection found = scratch.secRefs[slot];
            if (found != null && !scratch.secAcquired[slot]) {
               scratch.acquire(found);
               scratch.secAcquired[slot] = true;
            }
            scratch.lastSecValid = true;
            scratch.lastCx = cx;
            scratch.lastSy = secIdx;
            scratch.lastCz = cz;
            scratch.lastSec = found;
            return found;
         }
         slot = (slot + 1) & mask;
      }

      LevelChunkSection section = level.getChunk(cx, cz).getSection(secIdx);
      scratch.putSection(key, section, true);
      scratch.lastSecValid = true;
      scratch.lastCx = cx;
      scratch.lastSy = secIdx;
      scratch.lastCz = cz;
      scratch.lastSec = section;
      return section;
   }

   private static LevelChunkSection getSectionReadOnly(WorldGenLevel level, Scratch scratch, int cx, int secIdx, int cz) {
      if (scratch.lastSecValid && scratch.lastCx == cx && scratch.lastSy == secIdx && scratch.lastCz == cz) {
         return scratch.lastSec;
      }

      long key = SectionPos.asLong(cx, secIdx, cz);
      long[] keys = scratch.secKeys;
      boolean[] occ = scratch.secOccupied;
      int mask = keys.length - 1;
      int slot = Scratch.mix(key) & mask;
      while (occ[slot]) {
         if (keys[slot] == key) {
            return scratch.secRefs[slot];
         }
         slot = (slot + 1) & mask;
      }

      LevelChunkSection section = level.getChunk(cx, cz).getSection(secIdx);
      scratch.putSection(key, section, false);
      return section;
   }

   private static BlockState getAdjacentBlockState(WorldGenLevel level, Scratch scratch, int minSy, int sectionsCount, int x, int y, int z) {
      int secIdx = (y >> 4) - minSy;
      if (secIdx < 0 || secIdx >= sectionsCount) {
         return AIR;
      }

      LevelChunkSection section = getSectionReadOnly(level, scratch, x >> 4, secIdx, z >> 4);
      return section == null ? AIR : section.getBlockState(x & 15, y & 15, z & 15);
   }

   private static boolean inSphere(double dxy2, int z, double sz, double sr) {
      double dz = ((double)z + 0.5D - sz) / sr;
      return dxy2 + dz * dz < 1.0D;
   }

   private static int matchPure(OreConfiguration.TargetBlockState[] targets, int count, BlockState state) {
      for (int i = 0; i < count; i++) {
         if (targets[i].target.test(state, null)) {
            return i;
         }
      }
      return -1;
   }

   private static int matchGeneral(OreConfiguration.TargetBlockState[] targets, int count, BlockState state, RandomSource random) {
      for (int i = 0; i < count; i++) {
         if (targets[i].target.test(state, random)) {
            return i;
         }
      }
      return -1;
   }

   private static boolean isInteriorAdjacentToAir(LevelChunkSection section, int lx, int ly, int lz) {
      return section.getBlockState(lx, ly - 1, lz).isAir()
            || section.getBlockState(lx, ly + 1, lz).isAir()
            || section.getBlockState(lx, ly, lz - 1).isAir()
            || section.getBlockState(lx, ly, lz + 1).isAir()
            || section.getBlockState(lx - 1, ly, lz).isAir()
            || section.getBlockState(lx + 1, ly, lz).isAir();
   }

   private static boolean isBoundaryAdjacentToAir(
         WorldGenLevel level,
         Scratch scratch,
         int minSy,
         int sectionsCount,
         LevelChunkSection curSection,
         int lx,
         int ly,
         int lz,
         int x,
         int y,
         int z) {
      if ((ly > 0 ? curSection.getBlockState(lx, ly - 1, lz) : getAdjacentBlockState(level, scratch, minSy, sectionsCount, x, y - 1, z)).isAir()) {
         return true;
      }
      if ((ly < 15 ? curSection.getBlockState(lx, ly + 1, lz) : getAdjacentBlockState(level, scratch, minSy, sectionsCount, x, y + 1, z)).isAir()) {
         return true;
      }
      if ((lz > 0 ? curSection.getBlockState(lx, ly, lz - 1) : getAdjacentBlockState(level, scratch, minSy, sectionsCount, x, y, z - 1)).isAir()) {
         return true;
      }
      if ((lz < 15 ? curSection.getBlockState(lx, ly, lz + 1) : getAdjacentBlockState(level, scratch, minSy, sectionsCount, x, y, z + 1)).isAir()) {
         return true;
      }
      if ((lx > 0 ? curSection.getBlockState(lx - 1, ly, lz) : getAdjacentBlockState(level, scratch, minSy, sectionsCount, x - 1, y, z)).isAir()) {
         return true;
      }
      return (lx < 15 ? curSection.getBlockState(lx + 1, ly, lz) : getAdjacentBlockState(level, scratch, minSy, sectionsCount, x + 1, y, z)).isAir();
   }
}
