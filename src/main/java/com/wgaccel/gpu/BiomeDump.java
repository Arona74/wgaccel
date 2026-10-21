package com.wgaccel.gpu;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The biome GPU's inputs saved from the game for the offline bench (cudaBiomeBench): the table (brute-force points and
 * the flattened R-tree), the noise data, and the first chunks' jobs with the game GPU's answers. No Minecraft classes.
 */
public final class BiomeDump {
   public int[] points;
   public int[] biomeIds;
   public int[] nodeBounds;
   public int[] nodeFirst;
   public int[] nodeCount;
   public int[] nodeBiome;
   public long sourceMin;
   public long sourceMax;
   public long targetMax;
   public double[] noise;
   public int[] permutations;
   public final List<BiomeGpu.Job> jobs = new ArrayList<>();
   public final List<int[]> chosen = new ArrayList<>();
   public final List<int[]> tied = new ArrayList<>();

   public static final int JOBS = 8;

   public static void writeTable(Path file, BiomeTable table, double[] noise, int[] permutations) throws IOException {
      try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(file)))) {
         ints(out, table.points);
         ints(out, table.biomeIds);
         ints(out, table.nodeBounds);
         ints(out, table.nodeFirst);
         ints(out, table.nodeCount);
         ints(out, table.nodeBiome);
         out.writeLong(table.sourceMin);
         out.writeLong(table.sourceMax);
         out.writeLong(table.targetMax);
         out.writeInt(noise.length);
         for (double d : noise) {
            out.writeDouble(d);
         }

         ints(out, permutations);
      }
   }

   /** Appends one job and the GPU's answers. */
   public static synchronized void appendJob(Path file, BiomeGpu.Job job, int[] chosen, int[] tied) throws IOException {
      try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE,
         java.nio.file.StandardOpenOption.APPEND)))) {
         int n = job.count;
         out.writeInt(n);
         for (int i = 0; i < 3 * n; i++) {
            out.writeInt(job.at[i]);
         }

         out.writeInt(job.cells.length);
         for (double d : job.cells) {
            out.writeDouble(d);
         }

         out.writeInt(job.chunkX);
         out.writeInt(job.chunkZ);
         out.writeBoolean(job.banding);
         out.writeLong(job.seed);
         out.writeFloat(job.coverage);
         out.writeInt(job.size);
         out.writeInt(job.verticalSize);
         out.writeInt(job.surfaceDepth);
         out.writeInt(job.endDepth);
         out.writeInt(job.add21875);
         out.writeInt(job.add03125);
         for (int i = 0; i < 16; i++) {
            out.writeInt(job.minimumSurface[i]);
         }

         for (int i = 0; i < n; i++) {
            out.writeInt(chosen[i]);
            out.writeInt(tied[i]);
         }
      }
   }

   public static BiomeDump read(Path table, Path jobs) throws IOException {
      BiomeDump dump = new BiomeDump();
      try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(table)))) {
         dump.points = ints(in);
         dump.biomeIds = ints(in);
         dump.nodeBounds = ints(in);
         dump.nodeFirst = ints(in);
         dump.nodeCount = ints(in);
         dump.nodeBiome = ints(in);
         dump.sourceMin = in.readLong();
         dump.sourceMax = in.readLong();
         dump.targetMax = in.readLong();
         dump.noise = new double[in.readInt()];
         for (int i = 0; i < dump.noise.length; i++) {
            dump.noise[i] = in.readDouble();
         }

         dump.permutations = ints(in);
      }

      try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(jobs)))) {
         while (in.available() > 0) {
            BiomeGpu.Job job = new BiomeGpu.Job();
            int n = in.readInt();
            job.count = n;
            job.at = new int[3 * n];
            for (int i = 0; i < 3 * n; i++) {
               job.at[i] = in.readInt();
            }

            job.cells = new double[in.readInt()];
            for (int i = 0; i < job.cells.length; i++) {
               job.cells[i] = in.readDouble();
            }

            job.chunkX = in.readInt();
            job.chunkZ = in.readInt();
            job.banding = in.readBoolean();
            job.seed = in.readLong();
            job.coverage = in.readFloat();
            job.size = in.readInt();
            job.verticalSize = in.readInt();
            job.surfaceDepth = in.readInt();
            job.endDepth = in.readInt();
            job.add21875 = in.readInt();
            job.add03125 = in.readInt();
            for (int i = 0; i < 16; i++) {
               job.minimumSurface[i] = in.readInt();
            }

            int[] chosen = new int[n];
            int[] tied = new int[n];
            for (int i = 0; i < n; i++) {
               chosen[i] = in.readInt();
               tied[i] = in.readInt();
            }

            dump.jobs.add(job);
            dump.chosen.add(chosen);
            dump.tied.add(tied);
         }
      }

      return dump;
   }

   private static void ints(DataOutputStream out, int[] values) throws IOException {
      out.writeInt(values.length);
      for (int v : values) {
         out.writeInt(v);
      }
   }

   private static int[] ints(DataInputStream in) throws IOException {
      int[] values = new int[in.readInt()];
      for (int i = 0; i < values.length; i++) {
         values[i] = in.readInt();
      }

      return values;
   }
}
