package com.wgaccel.gpu;

import static org.lwjgl.cuda.CU.*;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * One CUDA context and one module compiled from CUDA C by NVRTC ({@link GpuSelfTest#compile}). Uses no Minecraft or
 * FreeTerraForged classes, so the standalone check can use it too. Device buffers made by {@link #upload} and
 * {@link #allocate} are freed when it closes.
 * <p>
 * With {@link GpuBackend#HIP} the same on an AMD card: HIP's calls in place of CUDA's, the module compiled by hipRTC
 * ({@link HipSelfTest#compile}). HIP has no contexts of its own to manage here: the "context" is the device's primary
 * one, made current by selecting device 0 on the calling thread.
 */
public final class Gpu implements AutoCloseable {
   /** The context value under HIP (any non-zero value: there is only the device's primary context). */
   private static final long HIP_CONTEXT = 1L;

   private final long context;
   /** The stream this Gpu works on: 0 the legacy stream (every call waits), or its own (useStream). */
   private long stream;
   private final boolean ownsContext;
   private final long module;
   private final List<Long> buffers = new ArrayList<>();

   public Gpu(String source, String name) {
      this(createContext(), true, source, name);
   }

   /** A module in an existing context (made current on this thread), which closing leaves alone. */
   public Gpu(long context, String source, String name) {
      this(context, false, source, name);
   }

   private Gpu(long context, boolean ownsContext, String source, String name) {
      this.context = context;
      this.ownsContext = ownsContext;
      try {
         makeCurrent(context);
         this.module = loadModule(source, name);
      } catch (RuntimeException failure) {
         if (ownsContext && !GpuBackend.HIP) {
            cuCtxDestroy(context);
         }

         throw failure;
      }
   }

   /** A new CUDA context on the chosen device ({@link GpuDevice}), current on this thread. */
   public static long createContext() {
      if (GpuBackend.HIP) {
         Hip.check(Hip.hipInit(0), "hipInit");
         Hip.check(Hip.hipSetDevice(GpuDevice.index()), "hipSetDevice");
         return HIP_CONTEXT;
      }

      GpuSelfTest.check(cuInit(0), "cuInit");
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer device = stack.mallocInt(1);
         GpuSelfTest.check(cuDeviceGet(device, GpuDevice.index()), "cuDeviceGet");
         PointerBuffer pointer = stack.mallocPointer(1);
         GpuSelfTest.check(cuCtxCreate(pointer, 0, device.get(0)), "cuCtxCreate");
         return pointer.get(0);
      }
   }

   /** Compiles CUDA C with NVRTC and loads it into the current context; returns the module. */
   public static long loadModule(String source, String name) {
      if (GpuBackend.HIP) {
         return loadHipModule(source, name);
      }

      // S2: the driver's finished machine code from the disk cache, so it is not translated again at every world load.
      java.nio.file.Path cubin = GpuSelfTest.cubinPath(source);
      if (cubin != null && java.nio.file.Files.exists(cubin)) {
         try (MemoryStack stack = MemoryStack.stackPush()) {
            byte[] bytes = java.nio.file.Files.readAllBytes(cubin);
            ByteBuffer image = MemoryUtil.memAlloc(bytes.length);
            try {
               image.put(0, bytes);
               PointerBuffer pointer = stack.mallocPointer(1);
               if (cuModuleLoadData(pointer, image) == CUDA_SUCCESS) {
                  return pointer.get(0);
               }
            } finally {
               MemoryUtil.memFree(image);
            }
         } catch (Exception ignored) {
         }

         try {
            java.nio.file.Files.deleteIfExists(cubin);
         } catch (Exception ignored) {
         }
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer pointer = stack.mallocPointer(1);
         ByteBuffer ptx = GpuSelfTest.compile(source, name);
         try {
            if (cubin != null) {
               Long linked = linkAndKeep(ptx, name, cubin);
               if (linked != null) {
                  return linked;
               }
            }

            GpuSelfTest.check(cuModuleLoadData(pointer, ptx), "cuModuleLoadData");
         } finally {
            MemoryUtil.memFree(ptx);
         }

         return pointer.get(0);
      }
   }

   /**
    * {@link #loadModule} under HIP: hipRTC makes the machine code itself (no PTX for the driver to translate), kept on
    * disk as the code object ({@link HipSelfTest#codeObjectPath}) and loaded from there next time.
    */
   private static long loadHipModule(String source, String name) {
      java.nio.file.Path cached = HipSelfTest.codeObjectPath(source);
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer pointer = stack.mallocPointer(1);
         if (cached != null && java.nio.file.Files.exists(cached)) {
            try {
               byte[] bytes = java.nio.file.Files.readAllBytes(cached);
               ByteBuffer image = MemoryUtil.memAlloc(bytes.length);
               try {
                  image.put(0, bytes);
                  if (Hip.hipModuleLoadData(pointer, image) == Hip.hipSuccess) {
                     return pointer.get(0);
                  }
               } finally {
                  MemoryUtil.memFree(image);
               }
            } catch (Exception ignored) {
            }

            try {
               java.nio.file.Files.deleteIfExists(cached);
            } catch (Exception ignored) {
            }
         }

         ByteBuffer code = HipSelfTest.compile(source, name);
         try {
            Hip.check(Hip.hipModuleLoadData(pointer, code), "hipModuleLoadData");
            if (cached != null) {
               try {
                  byte[] bytes = new byte[code.remaining()];
                  code.get(0, bytes);
                  java.nio.file.Files.createDirectories(cached.getParent());
                  java.nio.file.Path temporary = cached.resolveSibling(cached.getFileName() + ".tmp" + Thread.currentThread().getId());
                  java.nio.file.Files.write(temporary, bytes);
                  java.nio.file.Files.move(temporary, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
               } catch (Exception ignored) {
               }
            }

            return pointer.get(0);
         } finally {
            MemoryUtil.memFree(code);
         }
      }
   }

   /**
    * The PTX translated by the driver's own linker (its JIT with the default options, as cuModuleLoadData of PTX uses),
    * the machine code kept at cubin and loaded; null (the caller loads the PTX) on any trouble.
    */
   /** Why the last link to machine code did not happen (for the log and the standalone checks), or null. */
   public static volatile String linkProblem;

   private static Long linkAndKeep(ByteBuffer ptx, String name, java.nio.file.Path cubin) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer state = stack.mallocPointer(1);
         int created = cuLinkCreate(stack.mallocInt(0), stack.mallocPointer(0), state);
         if (created != CUDA_SUCCESS) {
            linkProblem = "cuLinkCreate " + created;
            return null;
         }

         long link = state.get(0);
         try {
            int added = cuLinkAddData(link, CU_JIT_INPUT_PTX, ptx, name, stack.mallocInt(0), stack.mallocPointer(0));
            if (added != CUDA_SUCCESS) {
               linkProblem = "cuLinkAddData " + added;
               return null;
            }

            PointerBuffer image = stack.mallocPointer(1);
            PointerBuffer size = stack.mallocPointer(1);
            int completed = cuLinkComplete(link, image, size);
            if (completed != CUDA_SUCCESS) {
               linkProblem = "cuLinkComplete " + completed;
               return null;
            }

            ByteBuffer code = MemoryUtil.memByteBuffer(image.get(0), (int)size.get(0));
            PointerBuffer module = stack.mallocPointer(1);
            int loaded = cuModuleLoadData(module, code);
            if (loaded != CUDA_SUCCESS) {
               linkProblem = "cuModuleLoadData of the linked code " + loaded;
               return null;
            }

            try {
               byte[] bytes = new byte[code.remaining()];
               code.get(0, bytes);
               java.nio.file.Files.createDirectories(cubin.getParent());
               java.nio.file.Path temporary = cubin.resolveSibling(cubin.getFileName() + ".tmp" + Thread.currentThread().getId());
               java.nio.file.Files.write(temporary, bytes);
               java.nio.file.Files.move(temporary, cubin, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
            }

            return module.get(0);
         } finally {
            cuLinkDestroy(link);
         }
      } catch (RuntimeException failure) {
         linkProblem = failure.toString();
         return null;
      }
   }

   public long context() {
      return this.context;
   }

   /** Copies a float, int or double buffer to a new device buffer; returns its address. */
   public long upload(Buffer buffer) {
      long device = this.allocate((buffer instanceof DoubleBuffer ? 8L : buffer instanceof ByteBuffer ? 1L : 4L) * buffer.remaining());
      this.copyIn(device, buffer);
      return device;
   }

   /**
    * From now on this Gpu's copies and kernels go to the given stream (its own, non-blocking, possibly of a higher
    * priority) and each call waits for that stream only: the erosion filter's tiles no longer queue on the legacy
    * stream behind the card's other work. The results are the same.
    */
   public void useStream(long stream) {
      this.stream = stream;
   }

   private void sync(String what) {
      if (GpuBackend.HIP) {
         Hip.check(Hip.hipStreamSynchronize(this.stream), "hipStreamSynchronize " + what);
         return;
      }

      GpuSelfTest.check(cuStreamSynchronize(this.stream), "cuStreamSynchronize " + what);
   }

   /** Bytes from a direct buffer's position to its limit. */
   private static long bytes(Buffer buffer) {
      int size = buffer instanceof DoubleBuffer ? 8 : buffer instanceof ByteBuffer ? 1 : 4;
      return (long)buffer.remaining() * size;
   }

   /** Copies a float, int or double buffer into an existing device buffer. */
   public void copyIn(long device, Buffer buffer) {
      this.current();
      if (GpuBackend.HIP) {
         if (this.stream != 0L) {
            Hip.check(Hip.hipMemcpyHtoDAsync(device, MemoryUtil.memAddress(buffer), bytes(buffer), this.stream), "copy in");
            this.sync("copy in");
         } else {
            Hip.check(Hip.hipMemcpyHtoD(device, MemoryUtil.memAddress(buffer), bytes(buffer)), "copy in");
         }

         return;
      }

      if (this.stream != 0L && buffer.isDirect()) {
         GpuSelfTest.check(ncuMemcpyHtoDAsync(device, MemoryUtil.memAddress(buffer), bytes(buffer), this.stream), "copy in");
         this.sync("copy in");
         return;
      }

      if (buffer instanceof FloatBuffer floats) {
         GpuSelfTest.check(cuMemcpyHtoD(device, floats), "copy in");
      } else if (buffer instanceof DoubleBuffer doubles) {
         GpuSelfTest.check(cuMemcpyHtoD(device, doubles), "copy in");
      } else if (buffer instanceof ByteBuffer bytes) {
         GpuSelfTest.check(cuMemcpyHtoD(device, bytes), "copy in");
      } else {
         GpuSelfTest.check(cuMemcpyHtoD(device, (IntBuffer)buffer), "copy in");
      }
   }

   /**
    * G6c: as {@link #copyIn}, not waited for: it runs in order with the kernels queued after it ({@link #enqueue}), and
    * the buffer must stay as it is until the next wait ({@link #finish} or a download).
    */
   public void enqueueCopyIn(long device, Buffer buffer) {
      this.current();
      if (GpuBackend.HIP) {
         Hip.check(Hip.hipMemcpyHtoDAsync(device, MemoryUtil.memAddress(buffer), bytes(buffer), this.stream), "copy in");
         return;
      }

      GpuSelfTest.check(ncuMemcpyHtoDAsync(device, MemoryUtil.memAddress(buffer), bytes(buffer), this.stream), "copy in");
   }

   /** Copies one device buffer into another. */
   public void copy(long from, long to, long bytes) {
      this.current();
      if (GpuBackend.HIP) {
         if (this.stream != 0L) {
            Hip.check(Hip.hipMemcpyDtoDAsync(to, from, bytes, this.stream), "copy on the GPU");
            this.sync("copy on the GPU");
         } else {
            Hip.check(Hip.hipMemcpyDtoD(to, from, bytes), "copy on the GPU");
         }

         return;
      }

      if (this.stream != 0L) {
         GpuSelfTest.check(cuMemcpyDtoDAsync(to, from, bytes, this.stream), "copy on the GPU");
         this.sync("copy on the GPU");
         return;
      }

      GpuSelfTest.check(cuMemcpyDtoD(to, from, bytes), "copy on the GPU");
   }

   /** A new device buffer of this many bytes; returns its address. */
   public long allocate(long bytes) {
      this.current();
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer pointer = stack.mallocPointer(1);
         if (GpuBackend.HIP) {
            Hip.check(Hip.hipMalloc(pointer, Math.max(4L, bytes)), "hipMalloc");
         } else {
            GpuSelfTest.check(cuMemAlloc(pointer, Math.max(4L, bytes)), "cuMemAlloc");
         }

         this.buffers.add(pointer.get(0));
         return pointer.get(0);
      }
   }

   /** Frees a buffer made by {@link #allocate} or {@link #upload} before close. */
   public void free(long device) {
      this.current();
      if (this.buffers.remove(device)) {
         freeDevice(device);
      }
   }

   private static void freeDevice(long device) {
      if (GpuBackend.HIP) {
         Hip.hipFree(device);
      } else {
         cuMemFree(device);
      }
   }

   /** Makes this context current on the calling thread (several contexts may share a thread, as the checks do). */
   private void current() {
      makeCurrent(this.context);
   }

   private static void makeCurrent(long context) {
      if (GpuBackend.HIP) {
         Hip.check(Hip.hipSetDevice(GpuDevice.index()), "hipSetDevice");
      } else {
         GpuSelfTest.check(cuCtxSetCurrent(context), "cuCtxSetCurrent");
      }
   }

   private boolean downloadOnStream(long device, Buffer into) {
      if (GpuBackend.HIP) {
         if (this.stream != 0L) {
            Hip.check(Hip.hipMemcpyDtoHAsync(MemoryUtil.memAddress(into), device, bytes(into), this.stream), "copy out");
            this.sync("copy out");
         } else {
            Hip.check(Hip.hipMemcpyDtoH(MemoryUtil.memAddress(into), device, bytes(into)), "copy out");
         }

         return true;
      }

      if (this.stream == 0L || !into.isDirect()) {
         return false;
      }

      GpuSelfTest.check(ncuMemcpyDtoHAsync(MemoryUtil.memAddress(into), device, bytes(into), this.stream), "copy out");
      this.sync("copy out");
      return true;
   }

   public void download(long device, FloatBuffer into) {
      this.current();
      if (!this.downloadOnStream(device, into)) {
         GpuSelfTest.check(cuMemcpyDtoH(into, device), "copy out");
      }
   }

   public void download(long device, IntBuffer into) {
      this.current();
      if (!this.downloadOnStream(device, into)) {
         GpuSelfTest.check(cuMemcpyDtoH(into, device), "copy out");
      }
   }

   public void download(long device, DoubleBuffer into) {
      this.current();
      if (!this.downloadOnStream(device, into)) {
         GpuSelfTest.check(cuMemcpyDtoH(into, device), "copy out");
      }
   }

   /**
    * Runs a kernel on a grid of gridX x gridY blocks of 256 threads and waits for it. Arguments in the kernel's order:
    * Integer, Float or Double values, Long device addresses, or float, int or double buffers (copied to the GPU for this run only).
    */
   public void run(String kernel, int gridX, int gridY, Object... arguments) {
      List<Long> temporary = new ArrayList<>();
      this.current();
      try (MemoryStack stack = MemoryStack.stackPush()) {
         PointerBuffer function = stack.mallocPointer(1);
         if (GpuBackend.HIP) {
            Hip.check(Hip.hipModuleGetFunction(function, this.module, stack.ASCII(kernel)), "hipModuleGetFunction " + kernel);
         } else {
            GpuSelfTest.check(cuModuleGetFunction(function, this.module, stack.ASCII(kernel)), "cuModuleGetFunction " + kernel);
         }

         PointerBuffer params = stack.mallocPointer(arguments.length);
         for (int i = 0; i < arguments.length; i++) {
            Object argument = arguments[i];
            if (argument instanceof Integer value) {
               params.put(i, MemoryUtil.memAddress(stack.ints(value)));
            } else if (argument instanceof Float value) {
               params.put(i, MemoryUtil.memAddress(stack.floats(value)));
            } else if (argument instanceof Double value) {
               params.put(i, MemoryUtil.memAddress(stack.doubles(value)));
            } else if (argument instanceof Long address) {
               params.put(i, stack.pointers(address).address());
            } else {
               long device = this.upload((Buffer)argument);
               temporary.add(device);
               params.put(i, stack.pointers(device).address());
            }
         }

         if (GpuBackend.HIP) {
            Hip.check(Hip.hipModuleLaunchKernel(function.get(0), gridX, gridY, 1, 256, 1, 1, 0, this.stream, params, 0L), "hipModuleLaunchKernel " + kernel);
         } else {
            GpuSelfTest.check(cuLaunchKernel(function.get(0), gridX, gridY, 1, 256, 1, 1, 0, this.stream, params, null), "cuLaunchKernel " + kernel);
         }

         // The legacy stream only: the noise fill's lanes share the context on their own non-blocking streams.
         this.sync(kernel);
      } finally {
         for (long device : temporary) {
            this.buffers.remove(device);
            freeDevice(device);
         }
      }
   }

   /** Kernel handles by name, looked up once ({@link #enqueue}). */
   private final java.util.Map<String, Long> functions = new java.util.HashMap<>();

   /**
    * G6c: as {@link #run} with only Integer, Float, Double or Long arguments, but not waited for: kernels queued this way
    * run in order on this Gpu's stream, and the next copy (which waits) or {@link #finish} waits for them all. An erosion
    * grid batch was 11 launches each waited for (2.17 ms a batch on the 23 Oct 2026 run, mostly the waiting).
    */
   public void enqueue(String kernel, int gridX, int gridY, Object... arguments) {
      this.current();
      try (MemoryStack stack = MemoryStack.stackPush()) {
         Long function = this.functions.get(kernel);
         if (function == null) {
            PointerBuffer found = stack.mallocPointer(1);
            if (GpuBackend.HIP) {
               Hip.check(Hip.hipModuleGetFunction(found, this.module, stack.ASCII(kernel)), "hipModuleGetFunction " + kernel);
            } else {
               GpuSelfTest.check(cuModuleGetFunction(found, this.module, stack.ASCII(kernel)), "cuModuleGetFunction " + kernel);
            }

            function = found.get(0);
            this.functions.put(kernel, function);
         }

         PointerBuffer params = stack.mallocPointer(arguments.length);
         for (int i = 0; i < arguments.length; i++) {
            Object argument = arguments[i];
            if (argument instanceof Integer value) {
               params.put(i, MemoryUtil.memAddress(stack.ints(value)));
            } else if (argument instanceof Float value) {
               params.put(i, MemoryUtil.memAddress(stack.floats(value)));
            } else if (argument instanceof Double value) {
               params.put(i, MemoryUtil.memAddress(stack.doubles(value)));
            } else if (argument instanceof Long address) {
               params.put(i, stack.pointers(address).address());
            } else {
               throw new IllegalArgumentException("enqueue takes values and device addresses only, not " + argument);
            }
         }

         // The launch copies the argument values, so the stack may be released before the kernel runs.
         if (GpuBackend.HIP) {
            Hip.check(Hip.hipModuleLaunchKernel(function, gridX, gridY, 1, 256, 1, 1, 0, this.stream, params, 0L), "hipModuleLaunchKernel " + kernel);
         } else {
            GpuSelfTest.check(cuLaunchKernel(function, gridX, gridY, 1, 256, 1, 1, 0, this.stream, params, null), "cuLaunchKernel " + kernel);
         }
      }
   }

   /** Waits for everything queued on this Gpu's stream ({@link #enqueue}). */
   public void finish() {
      this.current();
      this.sync("finish");
   }

   /** Runs a one-thread-per-result kernel whose last argument is its float output; returns the results (the caller frees them). */
   public FloatBuffer launch(String kernel, int threads, Object... arguments) {
      long result = this.allocate(4L * threads);
      try {
         Object[] all = new Object[arguments.length + 1];
         System.arraycopy(arguments, 0, all, 0, arguments.length);
         all[arguments.length] = result;
         this.run(kernel, (threads + 255) / 256, 1, all);
         FloatBuffer out = MemoryUtil.memAllocFloat(threads);
         this.download(result, out);
         return out;
      } finally {
         this.buffers.remove(result);
         freeDevice(result);
      }
   }

   @Override
   public void close() {
      this.current();
      for (long device : this.buffers) {
         freeDevice(device);
      }

      if (GpuBackend.HIP) {
         Hip.hipModuleUnload(this.module);
         return;
      }

      cuModuleUnload(this.module);
      if (this.ownsContext) {
         cuCtxDestroy(this.context);
      }
   }
}
