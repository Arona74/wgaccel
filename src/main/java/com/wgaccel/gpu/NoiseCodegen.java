package com.wgaccel.gpu;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GPU step G1.3, whole trees: turns FreeTerraForged's noise trees into CUDA C. Every distinct node becomes one
 * {@code __device__} function {@code nK(x, z, seed, u)} calling its children's functions (constants and holders are
 * written in place), each kind written operation for operation like its Java compute, using the leaf noises and
 * helpers in {@link NoiseKernels}. Values FreeTerraForged works out from the children's ranges on every call
 * (minValue, maxValue) are worked out once here with the same float operations and written in as exact hex floats.
 * {@code u} is set when a pow result might round differently from Java's (see {@link NoiseKernels#TREES}); such values
 * are worked out on the CPU instead.
 * <p>
 * It reads the nodes only by reflection (kinds by simple class name, fields by record component name), so the
 * standalone check can drive it with stand-in records; the tables come from FreeTerraForged's NoiseUtil.
 */
public final class NoiseCodegen {
   /** A kind with no CUDA version yet (a node, a domain or a curve function), so its node and those above it stay on the CPU. */
   public static final class Unsupported extends RuntimeException {
      public final String kind;

      Unsupported(String kind) {
         super(kind, null, false, false);
         this.kind = kind;
      }
   }

   private final Map<Object, Integer> ids = new IdentityHashMap<>();
   private final Map<Object, String> failed = new IdentityHashMap<>();
   private final Map<Object, String[]> domains = new IdentityHashMap<>();
   private final Map<Object, Long> costs = new IdentityHashMap<>();
   private final Set<Object> children = Collections.newSetFromMap(new IdentityHashMap<>());
   private final StringBuilder code = new StringBuilder();
   private final String tables;
   private int next;
   private int arrays;

   /**
    * @param grad24 FTF's GRAD_2D_24 as x, y pairs (64 floats)
    * @param cell FTF's CELL_2D as x, y pairs (512 floats)
    * @param sin FTF's sine table (a power of two long)
    * @param radToIndex FTF's radians-to-index factor for the sine table
    */
   public NoiseCodegen(float[] grad24, float[] cell, float[] sin, float radToIndex) {
      if (Integer.bitCount(sin.length) != 1) {
         throw new IllegalArgumentException("sine table length " + sin.length);
      }

      this.tables = array("FTF_G24", grad24) + array("FTF_CELL", cell) + array("FTF_SIN", sin)
         + "#define FTF_SIN_MASK " + (sin.length - 1) + "\n#define FTF_RAD_TO_INDEX " + lit(radToIndex) + "\n";
   }

   /**
    * The whole CUDA source: the leaf noises, the tables, the helpers, every node emitted so far and the check kernel
    * (row first + blockIdx.y is rootFunctions' entry; points from offset on; out and unsure point at the first row).
    */
   public String source(List<String> rootFunctions) {
      StringBuilder kernel = new StringBuilder("""
         extern "C" __global__ void treeCheck(const float* xs, const float* zs, const int* seeds, int points, int first, int offset, float* out,
                                              int* unsure) {
             int p = offset + blockIdx.x * blockDim.x + threadIdx.x;
             if (p >= points) return;
             int u = 0;
             float x = xs[p];
             float z = zs[p];
             int seed = seeds[p];
             float v;
             switch (first + (int)blockIdx.y) {
         """);
      for (int i = 0; i < rootFunctions.size(); i++) {
         kernel.append("    case ").append(i).append(": v = ").append(rootFunctions.get(i)).append("(x, z, seed, u); break;\n");
      }

      kernel.append("""
                 default: v = 0.0f;
             }
             out[blockIdx.y * points + p] = v;
             unsure[blockIdx.y * points + p] = u;
         }
         """);
      return NoiseKernels.NOISE_UTIL + NoiseKernels.PERLIN + NoiseKernels.RIDGE + NoiseKernels.SIMPLEX + NoiseKernels.PERLIN2 + NoiseKernels.WORLEY
         + NoiseKernels.JAVA_MATH + this.tables + NoiseKernels.TREES + this.code + kernel;
   }

   /** The node's function name, emitting it and everything below it first; throws {@link Unsupported}. */
   public String function(Object node) throws ReflectiveOperationException {
      Integer id = this.ids.get(node);
      if (id != null) {
         return "n" + id;
      }

      String missing = this.failed.get(node);
      if (missing != null) {
         throw new Unsupported(missing);
      }

      try {
         String body = this.body(node);
         id = this.next++;
         this.code.append("__device__ __noinline__ float n").append(id).append("(float x, float z, int seed, int& u) {\n").append(body).append("}\n");
         this.ids.put(node, id);
         return "n" + id;
      } catch (Unsupported unsupported) {
         this.failed.put(node, unsupported.kind);
         throw unsupported;
      }
   }

   /** The node's function number (emission order: children before parents), or -1. */
   public int id(Object node) {
      return this.ids.getOrDefault(node, -1);
   }

   /** Whether another emitted node or domain uses this node (if not, it is the top of a tree). */
   public boolean isChild(Object node) {
      return this.children.contains(node);
   }

   /** A rough count of noise samples per evaluation (to size the CPU's share of a check). */
   public long cost(Object node) throws ReflectiveOperationException {
      Long known = this.costs.get(node);
      if (known != null) {
         return known;
      }

      long cost = 1;
      String kind = kind(node);
      if (kind.equals("Constant")) {
         cost = 0;
      } else {
         Map<String, Object> c = components(node);
         long multiplier = kind.equals("Erosion") ? 1 + 25L * (Integer)c.get("octaves") : 1;
         if (c.get("octaves") instanceof Integer octaves) {
            cost += octaves;
         }

         for (Object value : c.values()) {
            if (value != null && (value.getClass().isRecord() || kind(value).equals("Reference"))) {
               cost += multiplier * this.cost(value instanceof Record ? value : invoke(value, "value"));
            }
         }
      }

      this.costs.put(node, cost);
      return cost;
   }

   /** A class's simple name (an enum constant's enum name). */
   public static String kind(Object object) {
      if (object instanceof Enum<?> constant) {
         return constant.getDeclaringClass().getSimpleName();
      }

      String name = object.getClass().getSimpleName();
      return name.isEmpty() ? object.getClass().getName() : name;
   }

   /** The C expression for a node at (x, z, seed): a constant or holder in place, otherwise a call. */
   private String call(Object node, String x, String z, String seed) throws ReflectiveOperationException {
      String kind = kind(node);
      if (kind.equals("Constant")) {
         return lit((Float)components(node).get("value"));
      }

      if (kind.equals("HolderHolder")) {
         Object holder = components(node).get("holder");
         if (!(Boolean)invoke(holder, "isBound")) {
            throw new Unsupported("unbound holder");
         }

         return this.call(invoke(holder, "value"), x, z, seed);
      }

      this.children.add(node);
      return this.function(node) + "(" + x + ", " + z + ", " + seed + ", u)";
   }

   private String call(Object node) throws ReflectiveOperationException {
      return this.call(node, "x", "z", "seed");
   }

   private String body(Object node) throws ReflectiveOperationException {
      String kind = kind(node);
      Map<String, Object> c = components(node);
      return switch (kind) {
         // A tree that is only a constant or a holder (a populator's noise can be): as call() places them inline.
         case "Constant", "HolderHolder" -> line("return %s;", this.call(node));
         case "Perlin" -> line("return ftf_perlin(x, z, %s, %s, %s, %s, %s, %d, %s, %s);", i(c, "seed"), f(c, "frequency"), i(c, "octaves"),
            f(c, "lacunarity"), f(c, "gain"), ordinal(c, "interpolation"), f(c, "min"), f(c, "max"));
         case "Perlin2" -> line("return ftf_perlin2(FTF_G24, x, z, %s, %s, %s, %s, %s, %d, %s, %s);", i(c, "seed"), f(c, "frequency"),
            i(c, "octaves"), f(c, "lacunarity"), f(c, "gain"), ordinal(c, "interpolation"), f(c, "min"), f(c, "max"));
         case "PerlinRidge", "Billow" -> line("return ftf_ridge(x, z, seed, %s, %s, %s, %s, %d, %s, %s, %s, %d);", f(c, "frequency"),
            i(c, "octaves"), f(c, "lacunarity"), f(c, "gain"), ordinal(c, "interpolation"), this.constArray((float[])c.get("spectralWeights")),
            f(c, "min"), f(c, "max"), kind.equals("Billow") ? 1 : 0);
         case "Simplex", "Simplex2" -> line("return ftf_simplex(FTF_G24, x, z, seed, %s, %s, %s, %s, %s, %s, %s);", f(c, "frequency"),
            i(c, "octaves"), f(c, "lacunarity"), f(c, "gain"), f(c, "min"), f(c, "max"), lit(kind.equals("Simplex") ? 79.869484F : 99.83685F));
         case "Worley" -> this.worley(c);
         case "WorleyEdge" -> {
            Object edge = c.get("edgeFunction");
            yield line("return ftf_worley_edge(FTF_CELL, x, z, seed, %s, %s, %d, %d, %s, %s, %s);", f(c, "frequency"), f(c, "distance"),
               ordinal(c, "distanceFunction"), ((Enum<?>)edge).ordinal(), lit((Float)invoke(edge, "min")), lit((Float)invoke(edge, "max")),
               lit((Float)invoke(edge, "range")));
         }
         case "Cubic" -> line("return ftf_cubic(x, z, seed, %s, %s, %s, %s, %s, %s);", f(c, "frequency"), i(c, "octaves"), f(c, "lacunarity"),
            f(c, "gain"), f(c, "minValue"), f(c, "maxValue"));
         case "Add" -> line("return %s + %s;", this.call(c.get("input1")), this.call(c.get("input2")));
         case "Multiply" -> line("float a = %s;", this.call(c.get("input1"))) + line("return a != 0.0f ? a * %s : 0.0f;", this.call(c.get("input2")));
         case "Min" -> line("return java_minf(%s, %s);", this.call(c.get("input1")), this.call(c.get("input2")));
         case "Max" -> line("return java_maxf(%s, %s);", this.call(c.get("input1")), this.call(c.get("input2")));
         case "Clamp" -> line("float v = %s;", this.call(c.get("input"))) + line("float lo = %s;", this.call(c.get("min")))
            + line("float hi = %s;", this.call(c.get("max"))) + line("return ftf_clamp(v, lo, hi);");
         case "Map" -> {
            Object alpha = c.get("alpha");
            float alphaMin = minValue(alpha);
            float alphaMax = maxValue(alpha);
            yield line("float v = %s;", this.call(alpha)) + line("float a = (v - %s) / %s;", lit(alphaMin), lit(alphaMax - alphaMin))
               + line("float lo = %s;", this.call(c.get("from"))) + line("float hi = %s;", this.call(c.get("to"))) + line("return lo + a * (hi - lo);");
         }
         case "Blend" -> this.blend(c);
         case "Alpha" -> line("float i = %s;", this.call(c.get("input"))) + line("float a = %s;", this.call(c.get("alpha")))
            + line("return i * a + (1.0f - a);");
         case "Threshold" -> line("float v = %s;", this.call(c.get("input"))) + line("float t = %s;", this.call(c.get("threshold")))
            + line("return v > t ? %s : %s;", this.call(c.get("upper")), this.call(c.get("lower")));
         case "Power" -> line("return ftf_pow(%s, %s, u);", this.call(c.get("input")), f(c, "power"));
         case "ShiftSeed" -> line("return %s;", this.call(c.get("input"), "x", "z", "ftf_add(seed, " + i(c, "shift") + ")"));
         case "Cache2d" -> line("return %s;", this.call(c.get("noise")));
         case "Warp" -> {
            String[] domain = this.domain(c.get("domain"));
            yield line("float wx = x + %s(x, z, seed, u);", domain[0]) + line("float wz = z + %s(x, z, seed, u);", domain[1])
               + line("return %s;", this.call(c.get("input"), "wx", "wz", "seed"));
         }
         case "Erosion" -> this.erosion(c);
         case "PowerCurve" -> {
            float mid = (Float)c.get("mid");
            float min = (Float)c.get("min");
            float max = (Float)c.get("max");
            yield line("float v = %s;", this.call(c.get("input")))
               + line("if (v >= %s) { v = %s + ftf_pow(v - %s, %s, u); } else { v = %s - ftf_pow(%s - v, %s, u); }", lit(mid), lit(mid), lit(mid),
                  f(c, "power"), lit(mid), lit(mid), f(c, "power"))
               + line("return ftf_map(v, %s, %s, %s);", lit(min), lit(max), lit(max - min));
         }
         case "Invert" -> {
            Object input = c.get("input");
            float min = minValue(input);
            float max = maxValue(input);
            yield line("return %s - ftf_clamp(%s, %s, %s);", lit(max), this.call(input), lit(min), lit(max));
         }
         case "Boost" -> line("float v = %s;", this.call(c.get("input"))) + line("for (int i = 0; i < %s; i++) v = ftf_pow(v, 1.0f - v, u);",
            i(c, "iterations")) + line("return v;");
         case "Steps" -> line("float nv = %s;", this.call(c.get("input"))) + line("float mn = %s;", this.call(c.get("slopeMin")))
            + line("float mx = %s;", this.call(c.get("slopeMax"))) + line("float sc = %s;", this.call(c.get("steps")))
            + line("float range = mx - mn;") + line("if (range <= 0.0f) return (float)__float2int_rz(nv * sc) / sc;")
            + line("nv = 1.0f - nv;") + line("float value = (float)__float2int_rz(nv * sc) / sc;") + line("float delta = nv - value;")
            + line("float alpha = ftf_map(delta * sc, mn, mx, range);")
            + line("return 1.0f - ftf_lerp(value, nv, %s);", this.curve(c.get("slopeCurve"), "alpha"));
         case "Terrace" -> this.terrace(c);
         case "AdvancedTerrace" -> this.advancedTerrace(c);
         case "Curve" -> line("float v = %s;", this.call(c.get("input"))) + line("return %s;", this.curve(c.get("curveFunction"), "v"));
         case "Gradient" -> line("float nv = %s;", this.call(c.get("input"))) + line("float ub = %s;", this.call(c.get("upper")))
            + line("if (nv > ub) return nv;") + line("float amount = %s;", this.call(c.get("strength")))
            + line("float lb = %s;", this.call(c.get("lower"))) + line("if (nv < lb) return ftf_pow(nv, 1.0f - amount, u);")
            + line("float alpha = 1.0f - (nv - lb) / (ub - lb);") + line("return ftf_pow(nv, 1.0f - amount * alpha, u);");
         case "Frequency" -> line("float fx = %s;", this.call(c.get("xFreq"))) + line("float fz = %s;", this.call(c.get("zFreq")))
            + line("return %s;", this.call(c.get("input"), "x * fx", "z * fz", "seed"));
         case "LegacyMoisture" -> {
            int power = (Integer)c.get("power");
            String source = this.call(c.get("source"));
            yield power < 2 ? line("return %s;", source) : line("float n = %s;", source) + line("n = (n - 0.5f) * 2.0f;")
               + line("float v = ftf_copysign(ftf_powi(n, %d), n);", power) + line("return ftf_map(v, -1.0f, 1.0f, 2.0f);");
         }
         case "LegacyTemperature" -> line("float s = ftf_clamp(ftf_sin(z * %s), -1.0f, 1.0f);", f(c, "frequency"))
            + line("float v = ftf_copysign(ftf_powi(s, %s), s);", i(c, "power")) + line("return ftf_map(v, -1.0f, 1.0f, 2.0f);");
         default -> throw new Unsupported(kind);
      };
   }

   private String worley(Map<String, Object> c) throws ReflectiveOperationException {
      String cellFunction = ((Enum<?>)c.get("cellFunction")).name();
      if (!cellFunction.equals("NOISE_LOOKUP")) {
         return line("return ftf_worley(FTF_CELL, x, z, seed, %s, %s, %d, %d, %s, %s);", f(c, "frequency"), f(c, "distance"),
            ordinal(c, "distanceFunction"), ordinal(c, "cellFunction"), f(c, "min"), f(c, "max"));
      }

      return line("auto lookup = [&](float lx, float lz, int ls) { return %s; };", this.call(c.get("lookup"), "lx", "lz", "ls"))
         + line("return ftf_worley_lookup(lookup, x, z, seed, %s, %s, %d);", f(c, "frequency"), f(c, "distance"), ordinal(c, "distanceFunction"));
   }

   private String blend(Map<String, Object> c) throws ReflectiveOperationException {
      Object alpha = c.get("alpha");
      float alphaMin = minValue(alpha);
      float alphaMax = maxValue(alpha);
      float mid = alphaMin + (alphaMax - alphaMin) * (Float)c.get("mid");
      float range = (Float)c.get("range");
      float blendLower = Math.max(alphaMin, mid - range / 2.0F);
      float blendUpper = Math.min(alphaMax, mid + range / 2.0F);
      float blendRange = blendUpper - blendLower;
      String lower = this.call(c.get("lower"));
      String upper = this.call(c.get("upper"));
      return line("float a = %s;", this.call(alpha)) + line("if (a < %s) return %s;", lit(blendLower), lower)
         + line("if (a > %s) return %s;", lit(blendUpper), upper) + line("float t = (a - %s) / %s;", lit(blendLower), lit(blendRange))
         + line("return ftf_lerp(%s, %s, %s);", lower, upper, this.curve(c.get("interpolation"), "t"));
   }

   private String erosion(Map<String, Object> c) throws ReflectiveOperationException {
      Object input = c.get("input");
      String strength = f(c, "strength");
      String blend = switch (((Enum<?>)c.get("blendMode")).name()) {
         case "CONSTANT" -> "1.0f - " + strength;
         case "INPUT_LINEAR" -> "1.0f - " + strength + " * value";
         case "OUTPUT_LINEAR" -> "1.0f - " + strength + " * erosion";
         default -> throw new Unsupported("Erosion blend " + c.get("blendMode"));
      };
      return line("float value = %s;", this.call(input)) + line("auto input = [&](float px, float pz) { return %s; };", this.call(input, "px", "pz", "0"))
         + line("float erosion = ftf_erosion_value(input, %s, %s, %s, %s, %s, %s, x, z);", i(c, "seed"), i(c, "octaves"), f(c, "gridSize"),
            f(c, "amplitude"), f(c, "lacunarity"), f(c, "distanceFallOff"))
         + line("return ftf_lerp(erosion, value, %s);", blend);
   }

   private String terrace(Map<String, Object> c) throws ReflectiveOperationException {
      Object[] steps = (Object[])c.get("steps");
      float[] values = new float[steps.length];
      float[] lower = new float[steps.length];
      float[] upper = new float[steps.length];
      for (int s = 0; s < steps.length; s++) {
         Map<String, Object> step = components(steps[s]);
         values[s] = (Float)step.get("value");
         lower[s] = (Float)step.get("lowerBound");
         upper[s] = (Float)step.get("upperBound");
      }

      String v = this.constArray(values);
      String lo = this.constArray(lower);
      String hi = this.constArray(upper);
      int n = steps.length;
      return line("float in = ftf_clamp(%s, 0.0f, %s);", this.call(c.get("input")), lit(0.999999F))
         + line("int index = ftf_floor(in * (float)%d);", n)
         + line("if (index < 0 || index >= %d) return __int_as_float(0x7fc00000);", n)
         + line("if (index == %d) return %s[index];", n - 1, v)
         + line("if (in < %s[index]) return %s[index];", lo, v)
         + line("if (in > %s[index]) return %s[index + 1];", hi, v)
         + line("float ramp = 1.0f - %s * 0.5f;", this.call(c.get("ramp")))
         + line("float cliff = 1.0f - %s * 0.5f;", this.call(c.get("cliff")))
         + line("float alpha = (in - %s[index]) / (%s[index] - %s[index]);", lo, hi, lo)
         + line("float value = %s[index];", v)
         + line("if (alpha > ramp) {")
         + line("float rampAlpha = (alpha - ramp) / (1.0f - ramp);")
         + line("float rampHeight = %s;", this.call(c.get("rampHeight")))
         + line("value += (%s[index + 1] - value) * rampAlpha * rampHeight;", v)
         + line("}")
         + line("if (alpha > cliff) {")
         + line("float cliffAlpha = (alpha - cliff) / (1.0f - cliff);")
         + line("value = ftf_lerp(value, %s[index + 1], cliffAlpha);", v)
         + line("}")
         + line("return value;");
   }

   private String advancedTerrace(Map<String, Object> c) throws ReflectiveOperationException {
      Object source = c.get("source");
      Object modulation = c.get("modulation");
      float blendMin = (Float)c.get("blendMin");
      float blendMax = (Float)c.get("blendMax");
      return line("float value = %s;", this.call(source)) + line("if (value <= %s) return value;", lit(blendMin))
         + line("float mask = %s;", this.call(c.get("mask"))) + line("if (mask == 0.0f) return value;")
         + line("float result = value;") + line("float slope = %s;", this.call(c.get("slope")))
         + line("float modulation = %s;", this.call(modulation))
         + line("for (int i = 1; i <= %s; i++) {", i(c, "octaves"))
         + line("int steps = %s * i;", i(c, "steps"))
         + line("result = (float)ftf_round(result * (float)steps) / (float)steps;")
         + line("result = result + (value - result) * slope;")
         + line("}")
         + line("result = (result + modulation) / %s;", lit(maxValue(source) + maxValue(modulation)))
         + line("float alpha = value > %s ? 1.0f : (value - %s) / %s;", lit(blendMax), lit(blendMin), lit(blendMax - blendMin))
         + line("if (mask != 1.0f) alpha *= mask;")
         + line("return ftf_lerp(value, result, alpha);");
   }

   /** The C expression applying a CurveFunction to a variable. */
   private String curve(Object function, String variable) throws ReflectiveOperationException {
      String kind = kind(function);
      if (kind.equals("Interpolation")) {
         return "ftf_interp(" + ((Enum<?>)function).ordinal() + ", " + variable + ")";
      }

      if (kind.equals("SCurveFunction")) {
         Map<String, Object> c = components(function);
         return "ftf_pow(" + variable + ", " + f(c, "lower") + " + " + f(c, "upper") + " * " + variable + ", u)";
      }

      throw new Unsupported("curve " + kind);
   }

   /** The two offset functions (x, then z) of a Domain, emitting them and their nodes first. */
   private String[] domain(Object domain) throws ReflectiveOperationException {
      String[] known = this.domains.get(domain);
      if (known != null) {
         return known;
      }

      String kind = kind(domain);
      Map<String, Object> c = components(domain);
      String offsetX;
      String offsetZ;
      switch (kind) {
         case "DomainWarp" -> {
            offsetX = line("return %s * %s;", this.call(c.get("mappedX")), this.call(c.get("distance")));
            offsetZ = line("return %s * %s;", this.call(c.get("mappedZ")), this.call(c.get("distance")));
         }
         case "DirectionWarp" -> {
            String angle = line("float angle = %s * %s;", this.call(c.get("direction")), lit((float)(Math.PI * 2)));
            String strength = this.call(c.get("strength"));
            offsetX = angle + line("return ftf_sin(angle) * %s;", strength);
            offsetZ = angle + line("return ftf_sin(angle + %s) * %s;", lit(1.5708F), strength);
         }
         case "AddWarp" -> {
            String[] a = this.domain(c.get("input1"));
            String[] b = this.domain(c.get("input2"));
            offsetX = line("return %s(x, z, seed, u) + %s(x, z, seed, u);", a[0], b[0]);
            offsetZ = line("return %s(x, z, seed, u) + %s(x, z, seed, u);", a[1], b[1]);
         }
         case "CompoundWarp" -> {
            String[] a = this.domain(c.get("input1"));
            String[] b = this.domain(c.get("input2"));
            String warped = line("float ax = x + %s(x, z, seed, u);", a[0]) + line("float az = z + %s(x, z, seed, u);", a[1]);
            offsetX = warped + line("return %s(ax, az, seed, u);", b[0]);
            offsetZ = warped + line("return %s(ax, az, seed, u);", b[1]);
         }
         case "DirectWarp" -> {
            offsetX = line("return 0.0f;");
            offsetZ = offsetX;
         }
         default -> throw new Unsupported("domain " + kind);
      }

      int id = this.next++;
      String[] names = {"d" + id + "x", "d" + id + "z"};
      this.code.append("__device__ __noinline__ float ").append(names[0]).append("(float x, float z, int seed, int& u) {\n").append(offsetX).append("}\n");
      this.code.append("__device__ __noinline__ float ").append(names[1]).append("(float x, float z, int seed, int& u) {\n").append(offsetZ).append("}\n");
      this.domains.put(domain, names);
      return names;
   }

   /** Emits a constant float array (before the function being built) and returns its name. */
   private String constArray(float[] values) {
      String name = "a" + this.arrays++;
      this.code.append(array(name, values.length == 0 ? new float[] {0.0F} : values));
      return name;
   }

   private static String array(String name, float[] values) {
      StringBuilder text = new StringBuilder("__device__ const float ").append(name).append('[').append(values.length).append("] = {");
      for (int i = 0; i < values.length; i++) {
         text.append(i % 8 == 0 ? "\n    " : " ").append(lit(values[i])).append(i + 1 < values.length ? "," : "");
      }

      return text.append("\n};\n").toString();
   }

   private static String line(String format, Object... arguments) {
      return "    " + (arguments.length == 0 ? format : String.format(java.util.Locale.ROOT, format, arguments)) + "\n";
   }

   /** An exact C float literal (hex), in parentheses. */
   static String lit(float value) {
      if (Float.isNaN(value) || Float.isInfinite(value)) {
         return String.format(java.util.Locale.ROOT, "__int_as_float((int)0x%08xu)", Float.floatToRawIntBits(value));
      }

      return "(" + Float.toHexString(value) + "f)";
   }

   static String lit(int value) {
      return value == Integer.MIN_VALUE ? "((int)0x80000000u)" : value < 0 ? "(" + value + ")" : Integer.toString(value);
   }

   private static String f(Map<String, Object> c, String name) {
      return lit((Float)c.get(name));
   }

   private static String i(Map<String, Object> c, String name) {
      return lit((Integer)c.get(name));
   }

   private static int ordinal(Map<String, Object> c, String name) {
      return ((Enum<?>)c.get(name)).ordinal();
   }

   private static float minValue(Object node) throws ReflectiveOperationException {
      return (Float)invoke(node, "minValue");
   }

   private static float maxValue(Object node) throws ReflectiveOperationException {
      return (Float)invoke(node, "maxValue");
   }

   static Object invoke(Object target, String method) throws ReflectiveOperationException {
      Method found = target.getClass().getMethod(method);
      found.setAccessible(true);
      return found.invoke(target);
   }

   /** A record's components by name (FTF's modules are package-private records). */
   static Map<String, Object> components(Object record) throws ReflectiveOperationException {
      Map<String, Object> values = new HashMap<>();
      RecordComponent[] components = record.getClass().getRecordComponents();
      if (components == null) {
         return values;
      }

      for (RecordComponent component : components) {
         Method accessor = component.getAccessor();
         accessor.setAccessible(true);
         values.put(component.getName(), accessor.invoke(record));
      }

      return values;
   }
}
