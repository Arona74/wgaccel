package com.wgaccel.stats;

import com.wgaccel.WorldgenAccelerator;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * What a benchmark run measures beyond the stage times (config profileRuns): a Java Flight Recorder CPU profile from
 * {@code /wgaccel reset} to {@code /wgaccel} (saved in {@code <game>/wgaccel-profiles}, read with the JDK's
 * {@code jfr print}), and the card's busy share from nvidia-smi once a second, in the 10 s reports and the summary. So
 * where the time goes is measured, not guessed. JFR is driven through the JVM's diagnostic commands (jfrStart, jfrDump,
 * jfrStop), which need only java.management, whatever modules the game's layer resolved.
 */
public final class RunProfile {
   private static final String NAME = "wgaccel";
   private static volatile boolean recording;
   private static volatile Process smi;
   private static final AtomicLong WINDOW_BUSY = new AtomicLong();
   private static final AtomicLong WINDOW_MEMORY = new AtomicLong();
   private static final AtomicLong WINDOW_SAMPLES = new AtomicLong();
   private static final AtomicLong RUN_BUSY = new AtomicLong();
   private static final AtomicLong RUN_SAMPLES = new AtomicLong();
   private static final AtomicLong RUN_FULL = new AtomicLong();

   private RunProfile() {
   }

   /** At {@code /wgaccel reset}: a new CPU profile and the GPU samples from zero. */
   public static synchronized void start() {
      RUN_BUSY.set(0L);
      RUN_SAMPLES.set(0L);
      RUN_FULL.set(0L);
      if (!com.wgaccel.Config.PROFILE_RUNS.get()) {
         return;
      }

      startSmi();
      try {
         if (recording) {
            command("jfrStop", "name=" + NAME);
         }

         command("jfrStart", "name=" + NAME, "settings=profile");
         recording = true;
         WorldgenAccelerator.LOG.info("WGA profile: CPU recording started (saved at /wgaccel)");
      } catch (Throwable failure) {
         recording = false;
         WorldgenAccelerator.LOG.warn("WGA profile: no CPU recording ({})", failure.toString());
      }
   }

   /** At {@code /wgaccel} and when the server stops: the recording so far written to a file (it keeps running). */
   public static synchronized void dump() {
      if (!recording) {
         return;
      }

      try {
         Path dir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("wgaccel-profiles");
         Files.createDirectories(dir);
         String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss"));
         Path file = dir.resolve("run-" + stamp + ".jfr");
         command("jfrDump", "name=" + NAME, "filename=" + file.toAbsolutePath());
         WorldgenAccelerator.LOG.info("WGA profile: CPU recording saved to {}", file);
      } catch (Throwable failure) {
         WorldgenAccelerator.LOG.warn("WGA profile: the recording could not be saved ({})", failure.toString());
      }
   }

   /** When the server stops: saved, then stopped, and nvidia-smi ended. */
   public static synchronized void stop() {
      dump();
      if (recording) {
         try {
            command("jfrStop", "name=" + NAME);
         } catch (Throwable ignored) {
         }

         recording = false;
      }

      Process process = smi;
      smi = null;
      if (process != null) {
         process.destroy();
      }
   }

   /** For the 10 s report: the card's busy share since the last one, or "" without samples. */
   public static String window() {
      long samples = WINDOW_SAMPLES.getAndSet(0L);
      long busy = WINDOW_BUSY.getAndSet(0L);
      long memory = WINDOW_MEMORY.getAndSet(0L);
      return samples == 0L ? "" : String.format(Locale.ROOT, " | GPU busy %d%% (memory controller %d%%)", busy / samples, memory / samples);
   }

   /** For the summary: the card's busy share over the run, and how often it was 95% or more. */
   public static String run() {
      long samples = RUN_SAMPLES.get();
      return samples == 0L ? "" : String.format(Locale.ROOT, ", GPU busy %d%% on average (95%% or more in %d%% of the seconds)", RUN_BUSY.get() / samples,
         100L * RUN_FULL.get() / samples);
   }

   private static void startSmi() {
      if (smi != null && smi.isAlive()) {
         return;
      }

      Path exe = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "nvidia-smi.exe");
      if (!Files.exists(exe)) {
         return;
      }

      try {
         Process process = new ProcessBuilder(exe.toString(), "--query-gpu=utilization.gpu,utilization.memory", "--format=csv,noheader,nounits", "-lms", "1000")
            .redirectErrorStream(true).start();
         smi = process;
         Thread reader = new Thread(() -> read(process), "WGA GPU busy");
         reader.setDaemon(true);
         reader.start();
      } catch (Throwable failure) {
         WorldgenAccelerator.LOG.warn("WGA profile: nvidia-smi did not start ({})", failure.toString());
      }
   }

   private static void read(Process process) {
      try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII))) {
         String line;
         while ((line = in.readLine()) != null) {
            String[] parts = line.split(",");
            if (parts.length < 2) {
               continue;
            }

            try {
               long busy = Long.parseLong(parts[0].trim());
               long memory = Long.parseLong(parts[1].trim());
               WINDOW_BUSY.addAndGet(busy);
               WINDOW_MEMORY.addAndGet(memory);
               WINDOW_SAMPLES.incrementAndGet();
               RUN_BUSY.addAndGet(busy);
               RUN_SAMPLES.incrementAndGet();
               if (busy >= 95L) {
                  RUN_FULL.incrementAndGet();
               }
            } catch (NumberFormatException ignored) {
            }
         }
      } catch (Throwable ignored) {
      }
   }

   private static void command(String name, String... arguments) throws Exception {
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      server.invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"), name, new Object[] {arguments}, new String[] {String[].class.getName()});
   }
}
