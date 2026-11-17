package com.wgaccel.gpu;

import com.wgaccel.WorldgenAccelerator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

/**
 * The GPU for world generation: one daemon thread owns the CUDA context and the compiled module (made on first use)
 * and runs jobs one after another, so the generation threads that ask (FreeTerraForged's tile threads) never touch
 * CUDA themselves. A failure to start makes every call throw, and the caller then does the work on the CPU. The noise
 * fill ({@link FillGpu}) shares the same context from the generation threads, each on its own stream.
 */
public final class GpuService {
   private static GpuService instance;

   private final LinkedBlockingQueue<java.util.function.Consumer<Gpu>> jobs = new LinkedBlockingQueue<>();
   private final CompletableFuture<Gpu> gpu = new CompletableFuture<>();
   private final CompletableFuture<Long> context = new CompletableFuture<>();

   private GpuService() {
      Thread thread = new Thread(this::loop, "WGA GPU");
      thread.setDaemon(true);
      thread.start();
      // I2 (run of 0.9.58: 29 ms per erosion tile for ~1.7 ms of kernels, the tiles queueing on one thread): more threads
      // serve the queue, each with its own module copy (from the disk cache), stream and buffers in the shared context.
      for (int i = 1; i < Math.max(1, GpuStreams.serviceThreads); i++) {
         Thread more = new Thread(this::helper, "WGA GPU " + i);
         more.setDaemon(true);
         more.start();
      }
   }

   /** A further thread serving the queue once the first has the context and the kernels ready. */
   private void helper() {
      Gpu own;
      try {
         long shared = this.context.get();
         this.gpu.get();
         own = new Gpu(shared, ErosionKernels.SOURCE, "worldgen.cu");
         if (GpuStreams.erosionStream) {
            own.useStream(GpuStreams.create());
         }
      } catch (Throwable failure) {
         WorldgenAccelerator.LOG.warn("WGA GPU: a further GPU thread did not start: {}", GpuSelfTest.describe(failure));
         return;
      }

      serve(own);
   }

   private void serve(Gpu own) {
      while (true) {
         try {
            this.jobs.take().accept(own);
         } catch (InterruptedException interrupted) {
            return;
         }
      }
   }

   private static GpuService service() {
      synchronized (GpuService.class) {
         if (instance == null) {
            instance = new GpuService();
         }

         return instance;
      }
   }

   private static Gpu gpu(GpuService service) throws Exception {
      try {
         return service.gpu.get();
      } catch (ExecutionException failure) {
         throw new IllegalStateException("the GPU did not start: " + failure.getCause(), failure.getCause());
      }
   }

   /**
    * The world generation context (starting the GPU thread if needed), which other threads may make current and share;
    * ready before the erosion kernels finish compiling, so other programs can compile at the same time.
    */
   public static long context() throws Exception {
      try {
         return service().context.get();
      } catch (ExecutionException failure) {
         throw new IllegalStateException("the GPU did not start: " + failure.getCause(), failure.getCause());
      }
   }

   /** Starts the GPU thread and its erosion kernels now (at world creation, tracked on the loading screen). */
   public static void warmUp() {
      GpuPrepare.Task task = GpuPrepare.begin("erosion filter");
      Thread thread = new Thread(() -> {
         try {
            gpu(service());
            task.done();
         } catch (Throwable failure) {
            task.failed(failure.toString());
         }
      }, "WGA GPU warm-up");
      thread.setDaemon(true);
      thread.start();
   }

   /** Queues a job for one of the GPU threads without waiting for it (G6c: {@link ErosionGridBatch}, which reports back itself). */
   static void post(java.util.function.Consumer<Gpu> job) throws Exception {
      GpuService service = service();
      gpu(service);
      service.jobs.add(job);
   }

   /** Runs a job on one of the GPU threads (with that thread's Gpu) and waits for its result. */
   public static <T> T call(Function<Gpu, T> job) throws Exception {
      GpuService service = service();
      gpu(service);
      CompletableFuture<T> result = new CompletableFuture<>();
      service.jobs.add(own -> {
         try {
            result.complete(job.apply(own));
         } catch (Throwable failure) {
            result.completeExceptionally(failure);
         }
      });
      try {
         // Never forever: a job past this is the caller's failure (it then does the work on the CPU and turns its part off).
         return result.get(60L, java.util.concurrent.TimeUnit.SECONDS);
      } catch (java.util.concurrent.TimeoutException late) {
         throw new IllegalStateException("a GPU job did not finish in 60 s");
      } catch (ExecutionException failure) {
         throw failure.getCause() instanceof Exception exception ? exception : new IllegalStateException(failure.getCause());
      }
   }

   private void loop() {
      try {
         long start = System.nanoTime();
         long shared;
         try {
            shared = Gpu.createContext();
         } catch (Throwable failure) {
            this.context.completeExceptionally(failure);
            throw failure;
         }

         this.context.complete(shared);
         Gpu made = new Gpu(shared, ErosionKernels.SOURCE, "worldgen.cu");
         if (GpuStreams.erosionStream) {
            // This thread's tier is the highest: FreeTerraForged's tile threads wait for these tiles.
            made.useStream(GpuStreams.create());
         }

         WorldgenAccelerator.LOG.info("WGA GPU: world generation kernels compiled and loaded in {} ms", (System.nanoTime() - start) / 1_000_000);
         this.gpu.complete(made);
      } catch (Throwable failure) {
         WorldgenAccelerator.LOG.warn("WGA GPU unavailable for world generation: {}", GpuSelfTest.describe(failure));
         this.gpu.completeExceptionally(failure);
         return;
      }

      this.serve(this.gpu.join());
   }
}
