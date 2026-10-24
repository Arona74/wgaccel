package com.wgaccel.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mod's CUDA C kernels as OpenCL C 2.0 for {@link OpenCl}: the same source text every backend gets, turned by
 * replacements that keep its maths as it is. CUDA's keywords, types and intrinsics become OpenCL's (long long is long,
 * __float2int_rz is convert_int_sat_rtz, blockIdx.x is get_group_id(0), ...); C++'s references become pointers (OpenCL C
 * has none, and neither AMD's nor Intel's compiler takes C++ for OpenCL); structs get their typedef; the kernels' pointer
 * parameters are __global, every other pointer stays unqualified (OpenCL 2.0's generic address space). Each kernel's
 * argument sizes are kept for the launches, which OpenCL sets one by one (CUDA takes them as a list of addresses).
 */
final class OpenClSource {
   /** The translated source and, by kernel, each argument's size in bytes (0: a device pointer). */
   record Translated(String text, Map<String, int[]> kernels) {
   }

   /**
    * Before every source. FP_CONTRACT OFF as NVRTC's --fmad=false. CUDA's float functions are OpenCL's overloaded ones.
    * CUDA's min, max and abs as C++ overloads: min and max of floats are fminf / fmaxf there (a NaN gives the other
    * value), abs of an int is an int (OpenCL's gives an unsigned one). The warp vote and shuffle compile but are not
    * meant to run: the surface's warp prepass is off under OpenCL (a sub-group is not 32 threads on every card).
    */
   static final String PRELUDE = """
      #pragma OPENCL EXTENSION cl_khr_fp64 : enable
      #pragma OPENCL FP_CONTRACT OFF
      #define floorf(x) floor(x)
      #define ceilf(x) ceil(x)
      #define sqrtf(x) sqrt(x)
      #define fabsf(x) fabs(x)
      #define truncf(x) trunc(x)
      #define roundf(x) round(x)
      #define rintf(x) rint(x)
      #define expf(x) exp(x)
      #define exp2f(x) exp2(x)
      #define logf(x) log(x)
      #define log2f(x) log2(x)
      #define log10f(x) log10(x)
      #define sinf(x) sin(x)
      #define cosf(x) cos(x)
      #define tanf(x) tan(x)
      #define asinf(x) asin(x)
      #define acosf(x) acos(x)
      #define atanf(x) atan(x)
      #define cbrtf(x) cbrt(x)
      #define fminf(a, b) fmin(a, b)
      #define fmaxf(a, b) fmax(a, b)
      #define fmodf(a, b) fmod(a, b)
      #define powf(a, b) pow(a, b)
      #define atan2f(a, b) atan2(a, b)
      #define hypotf(a, b) hypot(a, b)
      #define copysignf(a, b) copysign(a, b)
      #define nextafterf(a, b) nextafter(a, b)
      #define __popc(x) ((int)popcount((uint)(x)))
      #define __popcll(x) ((int)popcount((ulong)(x)))
      #define __clz(x) ((int)clz((int)(x)))
      #define __clzll(x) ((int)clz((long)(x)))
      #define __ffs(x) ((uint)(x) == 0u ? 0 : (int)ctz((uint)(x)) + 1)
      #define __ballot_sync(mask, predicate) 0u
      #define __shfl_sync(mask, value, source) (value)
      __attribute__((overloadable)) int wga_abs(int x) { return (int)abs(x); }
      __attribute__((overloadable)) long wga_abs(long x) { return (long)abs(x); }
      __attribute__((overloadable)) float wga_abs(float x) { return fabs(x); }
      __attribute__((overloadable)) double wga_abs(double x) { return fabs(x); }
      __attribute__((overloadable)) int wga_min(int a, int b) { return min(a, b); }
      __attribute__((overloadable)) uint wga_min(uint a, uint b) { return min(a, b); }
      __attribute__((overloadable)) long wga_min(long a, long b) { return min(a, b); }
      __attribute__((overloadable)) ulong wga_min(ulong a, ulong b) { return min(a, b); }
      __attribute__((overloadable)) float wga_min(float a, float b) { return fmin(a, b); }
      __attribute__((overloadable)) double wga_min(double a, double b) { return fmin(a, b); }
      __attribute__((overloadable)) int wga_max(int a, int b) { return max(a, b); }
      __attribute__((overloadable)) uint wga_max(uint a, uint b) { return max(a, b); }
      __attribute__((overloadable)) long wga_max(long a, long b) { return max(a, b); }
      __attribute__((overloadable)) ulong wga_max(ulong a, ulong b) { return max(a, b); }
      __attribute__((overloadable)) float wga_max(float a, float b) { return fmax(a, b); }
      __attribute__((overloadable)) double wga_max(double a, double b) { return fmax(a, b); }
      #define abs(x) wga_abs(x)
      #define min(a, b) wga_min(a, b)
      #define max(a, b) wga_max(a, b)
      """;

   private static final Pattern STRUCT = Pattern.compile("\\bstruct\\s+(\\w+)\\s*\\{");
   private static final Pattern CONSTANT_TABLE = Pattern.compile("(?:__constant__|__device__)\\s+(?:const\\s+)?([A-Za-z_][\\w ]*?)\\s+(\\w+)\\s*\\[");
   private static final Pattern INDEX = Pattern.compile("\\b(threadIdx|blockIdx|blockDim|gridDim)\\.([xyz])\\b");
   private static final Pattern ROUNDING = Pattern.compile("__(float|double)2(int|uint|ll|ull)_r([nzdu])\\(");
   private static final Pattern BITS = Pattern.compile("__(int|uint|float|double|longlong|ulonglong)_as_(int|uint|float|double|longlong|ulonglong)\\(");
   private static final Pattern FUNCTION = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\(([^()]*)\\)\\s*([{;])");
   private static final Pattern REFERENCE = Pattern.compile("^(.*\\S)\\s*&\\s*([A-Za-z_]\\w*)$", Pattern.DOTALL);
   private static final Pattern KERNEL = Pattern.compile("__kernel\\s+void\\s+(\\w+)\\s*\\(([^)]*)\\)");
   private static final Set<String> NOT_TYPES = Set.of("return", "else", "do", "case", "sizeof", "goto", "if", "while", "for", "switch");

   private OpenClSource() {
   }

   static Translated translate(String cuda) {
      return translate(cuda, false, false);
   }

   /**
    * explicit: for a device without the generic address space (NVIDIA's OpenCL C 1.2): the tables are __constant and
    * every pointer gets its address space written out ({@link #addressSpaces}). buffers: the kernels take their buffers
    * as GPU addresses ({@link #kernelParameters}).
    */
   static Translated translate(String cuda, boolean explicit, boolean buffers) {
      String s = stripComments(cuda);
      // OpenCL's reserved words used as names.
      s = s.replaceAll("\\b(half|global|local|constant|private|kernel|pipe|read_only|write_only|read_write)\\b", "wga_$1");
      s = lambdas(s);
      s = typedefStructs(s);
      s = s.replace("extern \"C\" __global__ void", "__kernel void").replace("__global__ void", "__kernel void");
      s = s.replace("unsigned long long", "ulong").replace("long long", "long");
      s = s.replaceAll("\\b(0[xX][0-9a-fA-F]+|\\d+)(?:[uU](?:ll|LL)|(?:ll|LL)[uU])\\b", "$1UL").replaceAll("\\b(0[xX][0-9a-fA-F]+|\\d+)(?:ll|LL)\\b", "$1L");
      // Tables in the global address space (OpenCL 2.0's program-scope variables): __constant's pointers do not convert to
      // the generic ones the functions take. OpenCL C 1.2 has program-scope variables only in __constant (explicit).
      s = CONSTANT_TABLE.matcher(s).replaceAll(explicit ? "__constant $1 $2[" : "__global const $1 $2[").replace("__constant__", "__constant");
      s = s.replace("__forceinline__", "__attribute__((always_inline))").replace("__noinline__", "__attribute__((noinline))")
         .replace("__device__", "__attribute__((overloadable))").replace("__restrict__", "restrict").replaceAll("\\binline\\s+", "");
      s = replaceAll(INDEX, s, m -> "((uint)" + switch (m.group(1)) {
         case "threadIdx" -> "get_local_id";
         case "blockIdx" -> "get_group_id";
         case "blockDim" -> "get_local_size";
         default -> "get_num_groups";
      } + "(" + (m.group(2).charAt(0) - 'x') + "))");
      s = replaceAll(ROUNDING, s, m -> "convert_" + switch (m.group(2)) {
         case "ll" -> "long";
         case "ull" -> "ulong";
         default -> m.group(2);
      } + "_sat_" + switch (m.group(3)) {
         case "n" -> "rte";
         case "z" -> "rtz";
         case "d" -> "rtn";
         default -> "rtp";
      } + "(");
      s = replaceAll(BITS, s, m -> "as_" + m.group(2).replace("longlong", "long") + "(");
      s = references(s);
      Map<String, int[]> kernels = new LinkedHashMap<>();
      s = kernelParameters(s, kernels, buffers);
      if (explicit) {
         s = addressSpaces(s);
      }

      return new Translated(PRELUDE + s, kernels);
   }

   private interface Replacement {
      String apply(Matcher match);
   }

   private static String replaceAll(Pattern pattern, String text, Replacement replacement) {
      Matcher match = pattern.matcher(text);
      StringBuilder built = new StringBuilder();
      while (match.find()) {
         match.appendReplacement(built, Matcher.quoteReplacement(replacement.apply(match)));
      }

      match.appendTail(built);
      return built.toString();
   }

   private static String stripComments(String text) {
      StringBuilder out = new StringBuilder(text.length());
      for (int i = 0; i < text.length(); i++) {
         char c = text.charAt(i);
         if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
            while (i < text.length() && text.charAt(i) != '\n') {
               i++;
            }

            out.append('\n');
         } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
            int end = text.indexOf("*/", i + 2);
            i = end < 0 ? text.length() : end + 1;
            out.append(' ');
         } else {
            out.append(c);
         }
      }

      return out.toString();
   }

   /**
    * struct X { ... }; becomes typedef struct X { ... } X;, so X names the type as in C++. A member's default (C++'s
    * double* ccache = 0;, always zero in the mod's kernels) is dropped, and every variable of that struct declared
    * without a value starts zeroed (X c = {0};) instead.
    */
   private static String typedefStructs(String text) {
      StringBuilder out = new StringBuilder();
      List<String> defaulted = new ArrayList<>();
      Matcher match = STRUCT.matcher(text);
      int from = 0;
      while (match.find(from)) {
         int close = matching(text, match.end() - 1);
         int semicolon = close < 0 ? -1 : text.indexOf(';', close);
         if (close < 0 || semicolon < 0 || !text.substring(close + 1, semicolon).isBlank()) {
            out.append(text, from, match.end());
            from = match.end();
            continue;
         }

         String body = text.substring(match.end(), close);
         String plain = body.replaceAll("(\\w)\\s*=\\s*[^;{}]+;", "$1;");
         if (!plain.equals(body)) {
            defaulted.add(match.group(1));
         }

         out.append(text, from, match.start()).append("typedef struct ").append(match.group(1)).append(" {").append(plain).append("} ").append(match.group(1))
            .append(';');
         from = semicolon + 1;
      }

      String result = out.append(text.substring(from)).toString();
      for (String name : defaulted) {
         result = result.replaceAll("\\b" + name + "\\s+(\\w+)\\s*;", name + " $1 = {0};");
      }

      return result;
   }

   private static final Pattern TEMPLATE = Pattern.compile(
      "template\\s*<\\s*typename\\s+(\\w+)\\s*>\\s*((?:__device__\\s+|__forceinline__\\s+|__noinline__\\s+)*)(\\w+)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*\\{");
   private static final Pattern LAMBDA = Pattern.compile("auto\\s+(\\w+)\\s*=\\s*\\[&\\]\\s*\\(([^)]*)\\)\\s*\\{\\s*return\\s+(.*?);\\s*\\}\\s*;", Pattern.DOTALL);

   /** A function template taking a callable as its first parameter (FreeTerraForged's Worley lookup and erosion). */
   private record Template(String qualifiers, String type, String name, String callable, String rest, String body) {
   }

   /**
    * C++'s function templates called with a lambda (NoiseCodegen: auto lookup = [&](float lx, float lz, int ls) { return
    * n5(lx, lz, ls, u); }; then ftf_worley_lookup(lookup, ...)): for each lambda, a copy of each template it reaches with
    * the lambda's expression in place of its calls, and the captured u passed as a parameter. The templates themselves go.
    */
   private static String lambdas(String text) {
      Map<String, Template> templates = new LinkedHashMap<>();
      StringBuilder out = new StringBuilder();
      Matcher match = TEMPLATE.matcher(text);
      int from = 0;
      while (match.find(from)) {
         int close = matching(text, match.end() - 1);
         List<String> parameters = split(match.group(5));
         String first = parameters.get(0).trim();
         if (close < 0 || !first.startsWith(match.group(1) + " ")) {
            out.append(text, from, match.end());
            from = match.end();
            continue;
         }

         templates.put(match.group(4), new Template(match.group(2), match.group(3), match.group(4), first.substring(match.group(1).length()).trim(),
            String.join(",", parameters.subList(1, parameters.size())).trim(), text.substring(match.end(), close)));
         out.append(text, from, match.start());
         from = close + 1;
      }

      text = out.append(text.substring(from)).toString();
      if (templates.isEmpty()) {
         return text;
      }

      for (int count = 0; ; count++) {
         Matcher lambda = LAMBDA.matcher(text);
         if (!lambda.find()) {
            return text;
         }

         // The lambda's function ends at the first closing brace below its own depth.
         int end = lambda.end();
         for (int depth = 0; end < text.length(); end++) {
            char c = text.charAt(end);
            if (c == '{') {
               depth++;
            } else if (c == '}' && depth-- == 0) {
               break;
            }
         }

         List<String> names = new ArrayList<>();
         for (String parameter : split(lambda.group(2))) {
            names.add(parameter.trim().replaceAll("^.*?(\\w+)$", "$1"));
         }

         StringBuilder code = new StringBuilder();
         String rest = specialize(text.substring(lambda.end(), end), lambda.group(1), names, lambda.group(3).trim(), templates, "__l" + count, new HashMap<>(), code);
         int header = text.lastIndexOf("__device__", lambda.start());
         text = text.substring(0, header) + code + text.substring(header, lambda.start()) + rest + text.substring(end);
      }
   }

   /**
    * In segment, every call of a template with the callable as its first argument becomes a call of its copy for this
    * lambda (made once into code, the copies it calls first), with u added last; every call of the callable itself
    * becomes the lambda's expression.
    */
   private static String specialize(String segment, String callable, List<String> names, String expression, Map<String, Template> templates, String suffix,
         Map<String, String> made, StringBuilder code) {
      for (Template template : templates.values()) {
         Matcher call = Pattern.compile("\\b" + template.name() + "\\s*\\(\\s*" + callable + "\\s*,").matcher(segment);
         StringBuilder out = new StringBuilder();
         int from = 0;
         while (call.find(from)) {
            int open = segment.indexOf('(', call.start());
            int close = matching(segment, open);
            String copy = made.get(template.name());
            if (copy == null) {
               copy = template.name() + suffix;
               made.put(template.name(), copy);
               String body = specialize(template.body(), template.callable(), names, expression, templates, suffix, made, code);
               code.append(template.qualifiers()).append(template.type()).append(' ').append(copy).append('(').append(template.rest())
                  .append(", int& u) {").append(body).append("}\n");
            }

            out.append(segment, from, call.start()).append(copy).append('(').append(segment, call.end(), close).append(", u)");
            from = close + 1;
         }

         segment = out.append(segment.substring(from)).toString();
      }

      Matcher call = Pattern.compile("\\b" + callable + "\\s*\\(").matcher(segment);
      StringBuilder out = new StringBuilder();
      int from = 0;
      while (call.find(from)) {
         int open = call.end() - 1;
         int close = matching(segment, open);
         List<String> arguments = split(segment.substring(open + 1, close));
         Map<String, String> values = new HashMap<>();
         for (int i = 0; i < names.size() && i < arguments.size(); i++) {
            values.put(names.get(i), "(" + arguments.get(i).trim() + ")");
         }

         String value = names.isEmpty() ? expression : replaceAll(Pattern.compile("\\b(" + String.join("|", names) + ")\\b"), expression,
            m -> values.getOrDefault(m.group(1), m.group(1)));
         out.append(segment, from, call.start()).append('(').append(value).append(')');
         from = close + 1;
      }

      return out.append(segment.substring(from)).toString();
   }

   /** The index of the bracket closing the one at open ((, [ or {), or -1. */
   private static int matching(String text, int open) {
      char opening = text.charAt(open);
      char closing = opening == '(' ? ')' : opening == '[' ? ']' : '}';
      int depth = 0;
      for (int i = open; i < text.length(); i++) {
         char c = text.charAt(i);
         if (c == opening) {
            depth++;
         } else if (c == closing && --depth == 0) {
            return i;
         }
      }

      return -1;
   }

   /** Whether the name at start is a function's definition or declaration: a type before it, not an operator or a keyword. */
   private static boolean declared(String text, int start) {
      int i = start - 1;
      while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
         i--;
      }

      if (i < 0) {
         return false;
      }

      char c = text.charAt(i);
      if (c == '*' || c == ')' && i > 0 && text.charAt(i - 1) == ')') {
         // int* f( or __attribute__((noinline)) f(; a cast, (double)f(, ends in one bracket.
         return true;
      }

      if (!Character.isJavaIdentifierPart(c)) {
         return false;
      }

      int end = i + 1;
      while (i >= 0 && Character.isJavaIdentifierPart(text.charAt(i))) {
         i--;
      }

      return !NOT_TYPES.contains(text.substring(i + 1, end));
   }

   private static List<String> split(String parameters) {
      List<String> parts = new ArrayList<>();
      int depth = 0;
      int from = 0;
      for (int i = 0; i < parameters.length(); i++) {
         char c = parameters.charAt(i);
         if (c == '(' || c == '[' || c == '{') {
            depth++;
         } else if (c == ')' || c == ']' || c == '}') {
            depth--;
         } else if (c == ',' && depth == 0) {
            parts.add(parameters.substring(from, i));
            from = i + 1;
         }
      }

      parts.add(parameters.substring(from));
      return parts;
   }

   /**
    * C++'s reference parameters as C: a const reference (const DCtx& c) as a value (the callee only reads it, and the
    * caller may pass a function's result, which has no address); any other (int& u, Xrs& r) as a pointer: the parameter
    * becomes int* u__r, its uses in the function (*u__r), and the calls pass &(argument).
    */
   private static String references(String text) {
      text = text.replaceAll("\\bconst\\s+(\\w+)\\s*&\\s*([A-Za-z_]\\w*)\\s*([,)])", "const $1 $2$3");
      Map<String, boolean[]> functions = new HashMap<>();
      int[] depths = depths(text);
      Matcher match = FUNCTION.matcher(text);
      while (match.find()) {
         if (NOT_TYPES.contains(match.group(1)) || depths[match.start(1)] != 0 || !declared(text, match.start(1))) {
            continue;
         }

         List<String> parameters = split(match.group(2));
         boolean[] reference = new boolean[parameters.size()];
         boolean any = false;
         for (int i = 0; i < parameters.size(); i++) {
            reference[i] = REFERENCE.matcher(parameters.get(i).trim()).matches();
            any |= reference[i];
         }

         if (any) {
            functions.put(match.group(1), reference);
         }
      }

      if (functions.isEmpty()) {
         return text;
      }

      text = calls(text, functions, false);
      depths = depths(text);
      StringBuilder out = new StringBuilder();
      match = FUNCTION.matcher(text);
      int from = 0;
      while (match.find(from)) {
         if (!functions.containsKey(match.group(1)) || depths[match.start(1)] != 0 || !declared(text, match.start(1))) {
            out.append(text, from, match.end(1));
            from = match.end(1);
            continue;
         }

         List<String> parameters = split(match.group(2));
         List<String> names = new ArrayList<>();
         StringBuilder rewritten = new StringBuilder();
         for (int i = 0; i < parameters.size(); i++) {
            Matcher reference = REFERENCE.matcher(parameters.get(i).trim());
            if (i > 0) {
               rewritten.append(", ");
            }

            if (reference.matches()) {
               names.add(reference.group(2));
               rewritten.append(reference.group(1)).append("* ").append(reference.group(2)).append("__r");
            } else {
               rewritten.append(parameters.get(i).trim());
            }
         }

         out.append(text, from, match.start(2)).append(rewritten).append(text, match.end(2), match.end());
         from = match.end();
         if (match.group(3).equals("{")) {
            int close = matching(text, match.end() - 1);
            String body = text.substring(match.end(), close);
            for (String name : names) {
               body = body.replaceAll("(?<![.\\w])(?<!->)\\b" + name + "\\b", "(*" + name + "__r)");
            }

            out.append(body);
            from = close;
         }
      }

      return out.append(text.substring(from)).toString();
   }

   /** The brace depth at each position of the text (0: outside every function). */
   private static int[] depths(String text) {
      int[] depths = new int[text.length() + 1];
      int depth = 0;
      for (int i = 0; i < text.length(); i++) {
         depths[i] = depth;
         char c = text.charAt(i);
         if (c == '{') {
            depth++;
         } else if (c == '}') {
            depth--;
         }
      }

      depths[text.length()] = depth;
      return depths;
   }

   /**
    * Every call of a function with reference parameters passes the address of those arguments. A name followed by a
    * bracket is a declaration only outside every function (inside, a * f(x) is a product); inside says the text is a
    * piece of a function already (a call's argument).
    */
   private static String calls(String text, Map<String, boolean[]> functions, boolean inside) {
      StringBuilder out = new StringBuilder();
      int depth = 0;
      int i = 0;
      while (i < text.length()) {
         char c = text.charAt(i);
         if (c == '{') {
            depth++;
         } else if (c == '}') {
            depth--;
         }

         if (!Character.isJavaIdentifierStart(c) || i > 0 && Character.isJavaIdentifierPart(text.charAt(i - 1))) {
            out.append(c);
            i++;
            continue;
         }

         int end = i;
         while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
            end++;
         }

         String name = text.substring(i, end);
         int open = end;
         while (open < text.length() && Character.isWhitespace(text.charAt(open))) {
            open++;
         }

         boolean[] reference = functions.get(name);
         if (reference == null || open >= text.length() || text.charAt(open) != '(' || !inside && depth == 0 && declared(text, i)) {
            out.append(name);
            i = end;
            continue;
         }

         int close = matching(text, open);
         List<String> arguments = split(text.substring(open + 1, close));
         out.append(name).append('(');
         for (int a = 0; a < arguments.size(); a++) {
            String argument = calls(arguments.get(a), functions, true);
            if (a > 0) {
               out.append(',');
            }

            out.append(a < reference.length && reference[a] ? "&(" + argument.trim() + ")" : argument);
         }

         out.append(')');
         i = close + 1;
      }

      return out.toString();
   }

   /** The kernels' pointer parameters in the global address space, and their argument sizes. */
   /**
    * buffers: the pointer parameters are passed as numbers (ulong name__a), each turned back into its __global pointer
    * first thing in the kernel's body: for plain buffers used by their GPU address ({@link OpenCl}'s buffers, NVIDIA's).
    */
   private static String kernelParameters(String text, Map<String, int[]> kernels, boolean buffers) {
      Matcher m = KERNEL.matcher(text);
      StringBuilder out = new StringBuilder();
      int from = 0;
      while (m.find(from)) {
         List<String> parameters = split(m.group(2));
         int[] sizes = new int[parameters.size()];
         StringBuilder rewritten = new StringBuilder();
         StringBuilder pointers = new StringBuilder();
         for (int i = 0; i < parameters.size(); i++) {
            String parameter = parameters.get(i).trim();
            if (i > 0) {
               rewritten.append(", ");
            }

            if (parameter.contains("*")) {
               String global = parameter.startsWith("__global") ? parameter : "__global " + parameter;
               if (buffers) {
                  Matcher name = Pattern.compile("(\\w+)\\s*$").matcher(global);
                  name.find();
                  String type = global.substring(0, name.start()).trim();
                  rewritten.append("ulong ").append(name.group(1)).append("__a");
                  pointers.append(' ').append(type).append(' ').append(name.group(1)).append(" = (").append(type.replaceAll("\\brestrict\\b", "").trim())
                     .append(')').append(name.group(1)).append("__a;");
               } else {
                  rewritten.append(global);
               }

               sizes[i] = 0;
            } else {
               rewritten.append(parameter);
               sizes[i] = size(parameter, m.group(1));
            }
         }

         kernels.put(m.group(1), sizes);
         int open = text.indexOf('{', m.end());
         out.append(text, from, m.start()).append("__kernel void ").append(m.group(1)).append('(').append(rewritten).append(')');
         if (open >= 0 && text.substring(m.end(), open).isBlank()) {
            out.append(text, m.end(), open + 1).append(pointers);
            from = open + 1;
         } else {
            from = m.end();
         }
      }

      return out.append(text.substring(from)).toString();
   }

   // ---- Explicit address spaces (OpenCL C 1.2: no generic one) ----

   private static final String TYPE_WORDS = "char|short|int|long|float|double|bool|void|uchar|ushort|uint|ulong|size_t|unsigned|signed";
   private static final Pattern SPACE_KEYWORD = Pattern.compile("\\b__(global|constant|private|local)\\b");
   private static final Pattern TABLE = Pattern.compile("__constant\\s+[\\w ]*?\\b(\\w+)\\s*\\[");
   private static final Pattern TYPEDEF = Pattern.compile("typedef\\s+struct\\s+(\\w+)\\s*\\{");
   private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_]\\w*");
   private static final Pattern CAST_START = Pattern.compile("^\\(\\s*((?:__\\w+\\s+)?(?:const\\s+)?(?:unsigned\\s+)?\\w+)\\s*\\*\\s*\\)");

   /** A top-level function: its text from start to its body's closing brace, its name and parameters. */
   private record Function(String name, int start, int nameStart, int paramsOpen, int paramsClose, int bodyOpen, int bodyClose, List<String> parameters) {
      /** One letter per pointer parameter with no address space written: what an instance of it takes there. */
      int open() {
         int count = 0;
         for (String parameter : this.parameters) {
            if (parameter.contains("*") && !SPACE_KEYWORD.matcher(parameter).find()) {
               count++;
            }
         }

         return count;
      }
   }

   /** The edits of one instance's text: at (relative to the function's start), length replaced, new text. */
   private record Edit(int at, int length, String with) {
   }

   /**
   * Every pointer's address space written out, for OpenCL C 1.2. The mod's kernels have two kinds of memory, the
   * buffers (__global: the kernels' parameters, the addresses read from batch tables, structs' pointer members) and a
   * thread's own variables (__private: its arrays, and the references the translation made pointers), and the tables
   * (__constant). From each kernel down every call, a function's pointer parameters take the space of the arguments it
   * is called with; a function called with other spaces gets a copy for each combination (its name with one letter per
   * parameter, g, p or c, unless all are g). Its pointer variables take their initial value's space, a cast to a pointer
   * its operand's (an address read as a number: __global).
   */
   static String addressSpaces(String text) {
      List<String> structs = new ArrayList<>();
      Matcher typedef = TYPEDEF.matcher(text);
      while (typedef.find()) {
         structs.add(typedef.group(1));
      }

      String types = TYPE_WORDS + (structs.isEmpty() ? "" : "|" + String.join("|", structs));
      Pattern array = Pattern.compile("(?<![\\w.>])(?:const\\s+)?(?:unsigned\\s+)?(?:" + types + ")\\s+(\\w+)\\s*\\[");
      Map<String, Character> members = privateMembers(text, array);
      text = structMembers(text, members);
      Set<String> tables = new java.util.HashSet<>();
      int[] depths = depths(text);
      Matcher table = TABLE.matcher(text);
      while (table.find()) {
         if (depths[table.start()] == 0) {
            tables.add(table.group(1));
         }
      }

      Map<String, Function> functions = new LinkedHashMap<>();
      Matcher match = FUNCTION.matcher(text);
      while (match.find()) {
         int nameStart = match.start(1);
         if (!match.group(3).equals("{") || NOT_TYPES.contains(match.group(1)) || depths[nameStart] != 0 || !declared(text, nameStart)) {
            continue;
         }

         int bodyOpen = match.end() - 1;
         functions.put(match.group(1), new Function(match.group(1), headerStart(text, depths, nameStart), nameStart, match.start(2) - 1, match.end(2), bodyOpen,
            matching(text, bodyOpen), split(match.group(2))));
      }

      Pattern pointer = Pattern.compile("(?:const\\s+)?(?:unsigned\\s+)?(?:" + types + ")\\s*\\*\\s*(?:restrict\\s+)?(\\w+)\\s*([=;,)])");
      Pattern cast = Pattern.compile("\\(\\s*((?:const\\s+)?(?:unsigned\\s+)?(?:" + types + "))\\s*\\*\\s*\\)");
      Map<String, Map<String, String>> instances = new LinkedHashMap<>();
      java.util.ArrayDeque<String[]> work = new java.util.ArrayDeque<>();
      for (Function function : functions.values()) {
         instances.put(function.name(), new LinkedHashMap<>());
         if (text.substring(function.start(), function.nameStart()).contains("__kernel")) {
            work.add(new String[] {function.name(), ""});
         }
      }

      while (true) {
         while (!work.isEmpty()) {
            String[] next = work.poll();
            Map<String, String> made = instances.get(next[0]);
            if (!made.containsKey(next[1])) {
               made.put(next[1], instance(text, functions.get(next[0]), next[1], functions, tables, members, array, pointer, cast, work));
            }
         }

         // A function no kernel reaches: one copy with __global pointers, as it compiles.
         Function unreached = functions.values().stream().filter(function -> instances.get(function.name()).isEmpty()).findFirst().orElse(null);
         if (unreached == null) {
            break;
         }

         work.add(new String[] {unreached.name(), "g".repeat(unreached.open())});
      }

      StringBuilder out = new StringBuilder();
      int from = 0;
      for (Function function : functions.values()) {
         out.append(text, from, function.start()).append(String.join("\n", instances.get(function.name()).values()));
         from = function.bodyClose() + 1;
      }

      return out.append(text.substring(from)).toString();
   }

   /** Where a top-level function's text starts: after the item before it (a ; or a closing brace) and any # lines. */
   private static int headerStart(String text, int[] depths, int nameStart) {
      int i = nameStart - 1;
      while (i >= 0 && !(text.charAt(i) == ';' && depths[i] == 0 || text.charAt(i) == '}' && depths[i] == 1)) {
         i--;
      }

      int start = i + 1;
      while (true) {
         int line = start;
         while (line < nameStart && Character.isWhitespace(text.charAt(line))) {
            line++;
         }

         if (line >= nameStart || text.charAt(line) != '#') {
            return line;
         }

         int end = text.indexOf('\n', line);
         start = end < 0 || end >= nameStart ? nameStart : end + 1;
      }
   }

   /** What an expression's space is worked out from: a function's pointers and arrays, the tables, the private members. */
   private record Scope(Map<String, Character> pointers, Set<String> arrays, Set<String> tables, Map<String, Character> members) {
      /** A struct member's space: __private when it is given a thread's array somewhere, else a buffer's. */
      char member(String name) {
         return this.members.getOrDefault(name, 'g');
      }
   }

   /**
    * The structs' pointer members that are given a thread's own array somewhere (c.ccache = ccache; with double
    * ccache[...] in the kernel: the fill's and biomes' column cache), by name: __private.
    */
   private static Map<String, Character> privateMembers(String text, Pattern array) {
      Set<String> arrays = new java.util.HashSet<>();
      Matcher arrayed = array.matcher(text);
      while (arrayed.find()) {
         arrays.add(arrayed.group(1));
      }

      Map<String, Character> members = new HashMap<>();
      Matcher assigned = Pattern.compile("\\.\\s*(\\w+)\\s*=\\s*(\\w+)\\s*;").matcher(text);
      while (assigned.find()) {
         if (arrays.contains(assigned.group(2))) {
            members.put(assigned.group(1), 'p');
         }
      }

      return members;
   }

   /** Structs' pointer members are __global (the mod's structs point at buffers), or __private as members says. */
   private static String structMembers(String text, Map<String, Character> members) {
      StringBuilder out = new StringBuilder();
      Matcher match = TYPEDEF.matcher(text);
      int from = 0;
      while (match.find(from)) {
         int close = matching(text, match.end() - 1);
         if (close < 0) {
            break;
         }

         out.append(text, from, match.end());
         for (String member : text.substring(match.end(), close).split(";", -1)) {
            if (member.contains("*") && !SPACE_KEYWORD.matcher(member).find()) {
               Matcher name = Pattern.compile("(\\w+)\\s*$").matcher(member);
               char space = name.find() ? members.getOrDefault(name.group(1), 'g') : 'g';
               member = member.replaceFirst("^(\\s*)", "$1" + qualifier(space));
            }

            out.append(member).append(';');
         }

         out.setLength(out.length() - 1);
         from = close;
      }

      return out.append(text.substring(from)).toString();
   }

   /** The text of one copy of a function, its open pointer parameters in these spaces; the copies it calls go to work. */
   private static String instance(String text, Function function, String spaces, Map<String, Function> functions, Set<String> tables,
         Map<String, Character> members, Pattern array, Pattern pointer, Pattern cast, java.util.ArrayDeque<String[]> work) {
      int base = function.start();
      String own = text.substring(base, function.bodyClose() + 1);
      List<Edit> edits = new ArrayList<>();
      Map<String, Character> pointers = new HashMap<>();
      Set<String> arrays = new java.util.HashSet<>();
      Scope scope = new Scope(pointers, arrays, tables, members);
      StringBuilder parameters = new StringBuilder();
      int open = 0;
      for (int i = 0; i < function.parameters().size(); i++) {
         String parameter = function.parameters().get(i).trim();
         Matcher name = Pattern.compile("(\\w+)\\s*(\\[[^\\]]*\\])?$").matcher(parameter);
         String named = name.find() ? name.group(1) : "";
         if (parameter.contains("*")) {
            Matcher keyword = SPACE_KEYWORD.matcher(parameter);
            char space = keyword.find() ? keyword.group(1).charAt(0) : open < spaces.length() ? spaces.charAt(open++) : 'g';
            pointers.put(named, space);
            if (!SPACE_KEYWORD.matcher(parameter).find()) {
               parameter = qualifier(space) + parameter;
            }
         }

         parameters.append(i > 0 ? ", " : "").append(parameter);
      }

      edits.add(new Edit(function.paramsOpen() + 1 - base, function.paramsClose() - function.paramsOpen() - 1, parameters.toString()));
      String copy = copyName(function.name(), spaces);
      if (!copy.equals(function.name())) {
         edits.add(new Edit(function.nameStart() - base, function.name().length(), copy));
      }

      int bodyFrom = function.bodyOpen() - base;
      String body = own.substring(bodyFrom);
      Matcher arrayed = array.matcher(body);
      while (arrayed.find()) {
         arrays.add(arrayed.group(1));
      }

      // Pointer variables with their space written already (a buffers kernel's parameters turned back into pointers).
      Matcher qualified = Pattern.compile("__(global|private|constant)\\s+(?:const\\s+)?(?:unsigned\\s+)?\\w+\\s*\\*\\s*(?:restrict\\s+)?(\\w+)\\s*=").matcher(body);
      while (qualified.find()) {
         pointers.put(qualified.group(2), qualified.group(1).charAt(0));
      }

      // Pointer variables, in order (an earlier one may be a later one's value).
      Matcher declared = pointer.matcher(body);
      while (declared.find()) {
         // A declaration starts a statement: after ; { } or (, or at the start of a line (after a macro such as EVB_CELL).
         int before = declared.start() - 1;
         boolean line = false;
         while (before >= 0 && Character.isWhitespace(body.charAt(before))) {
            line |= body.charAt(before) == '\n';
            before--;
         }

         if (before >= 0 && !line && ";{}(".indexOf(body.charAt(before)) < 0) {
            continue;
         }

         char space = 'g';
         if (declared.group(2).equals("=")) {
            int end = declared.end();
            int depth = 0;
            while (end < body.length() && !(depth == 0 && (body.charAt(end) == ';' || body.charAt(end) == ','))) {
               char c = body.charAt(end);
               depth += c == '(' || c == '[' ? 1 : c == ')' || c == ']' ? -1 : 0;
               end++;
            }

            space = pointerSpace(space(body.substring(declared.end(), end), scope));
         }

         pointers.put(declared.group(1), space);
         edits.add(new Edit(bodyFrom + declared.start(), 0, qualifier(space)));
      }

      Matcher casts = cast.matcher(body);
      while (casts.find()) {
         if (SPACE_KEYWORD.matcher(casts.group(1)).find()) {
            continue;
         }

         char space = pointerSpace(space(operand(body, casts.end()), scope));
         edits.add(new Edit(bodyFrom + casts.start(1), 0, qualifier(space)));
      }

      // Calls: each callee's copy for its arguments' spaces.
      Matcher call = Pattern.compile("(?<![\\w.])([A-Za-z_]\\w*)\\s*\\(").matcher(body);
      while (call.find()) {
         Function callee = functions.get(call.group(1));
         if (callee == null) {
            continue;
         }

         int close = matching(body, call.end() - 1);
         List<String> arguments = split(body.substring(call.end(), close));
         StringBuilder wanted = new StringBuilder();
         for (int i = 0; i < callee.parameters().size() && i < arguments.size(); i++) {
            String parameter = callee.parameters().get(i);
            if (parameter.contains("*") && !SPACE_KEYWORD.matcher(parameter).find()) {
               wanted.append(pointerSpace(space(arguments.get(i), scope)));
            }
         }

         work.add(new String[] {callee.name(), wanted.toString()});
         String calleeCopy = copyName(callee.name(), wanted.toString());
         if (!calleeCopy.equals(callee.name())) {
            edits.add(new Edit(bodyFrom + call.start(1), callee.name().length(), calleeCopy));
         }
      }

      edits.sort((a, b) -> Integer.compare(b.at(), a.at()));
      StringBuilder out = new StringBuilder(own);
      for (Edit edit : edits) {
         out.replace(edit.at(), edit.at() + edit.length(), edit.with());
      }

      return out.toString();
   }

   private static String copyName(String name, String spaces) {
      return spaces.replace("g", "").isEmpty() ? name : name + "_" + spaces;
   }

   private static String qualifier(char space) {
      return space == 'p' ? "__private " : space == 'c' ? "__constant " : "__global ";
   }

   /** A value (v) where a pointer is wanted: a number (0) or an address read as one, which point at buffers. */
   private static char pointerSpace(char space) {
      return space == 'v' ? 'g' : space;
   }

   /** The operand of a cast ending at from: a bracketed expression, or a name with its indexing and members. */
   private static String operand(String body, int from) {
      int i = from;
      while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
         i++;
      }

      if (i < body.length() && body.charAt(i) == '(') {
         int close = matching(body, i);
         return close < 0 ? "" : body.substring(i, close + 1);
      }

      int end = i;
      while (end < body.length()) {
         char c = body.charAt(end);
         if (Character.isJavaIdentifierPart(c) || c == '.' || c == '&' && end == i) {
            end++;
         } else if (c == '[') {
            int close = matching(body, end);
            end = close < 0 ? body.length() : close + 1;
         } else {
            break;
         }
      }

      return body.substring(i, end);
   }

   /**
    * The address space an expression points into: g, p or c, or v when it is no pointer (a number, an element). The
    * first name decides (p + 4 is p's); a struct's member is a buffer's.
    */
   private static char space(String expression, Scope scope) {
      String e = unbracket(expression);
      Matcher cast = CAST_START.matcher(e);
      if (cast.lookingAt()) {
         Matcher keyword = SPACE_KEYWORD.matcher(cast.group(1));
         return keyword.find() ? keyword.group(1).charAt(0) : pointerSpace(space(e.substring(cast.end()), scope));
      }

      if (e.startsWith("&")) {
         return addressSpace(e.substring(1), scope);
      }

      Matcher name = IDENTIFIER.matcher(e);
      if (e.startsWith("*") || !name.find()) {
         return 'v';
      }

      String rest = e.substring(name.end()).trim();
      boolean element = rest.startsWith("[") || rest.startsWith("->");
      if (scope.pointers().containsKey(name.group())) {
         return element ? 'v' : scope.pointers().get(name.group());
      } else if (scope.arrays().contains(name.group())) {
         return element ? 'v' : 'p';
      } else if (scope.tables().contains(name.group())) {
         return element ? 'v' : 'c';
      }

      // A struct's member: c.ccache is its member's space, c.ccache[i] a value.
      Matcher member = Pattern.compile("^\\.\\s*(\\w+)\\s*(\\[)?").matcher(rest);
      return !member.find() ? 'v' : member.group(2) != null ? 'v' : scope.member(member.group(1));
   }

   /** The space of &lvalue: a pointer's element is in its space, a table's in __constant, anything else the thread's. */
   private static char addressSpace(String lvalue, Scope scope) {
      String e = unbracket(lvalue);
      if (e.startsWith("*")) {
         char space = space(e.substring(1), scope);
         return space == 'v' ? 'p' : space;
      }

      if (e.startsWith("(")) {
         // (*r__r).field, (p[i]).x: the bracketed part decides.
         int close = matching(e, 0);
         return close < 0 ? 'p' : addressSpace(e.substring(1, close), scope);
      }

      Matcher name = IDENTIFIER.matcher(e);
      if (!name.find()) {
         return 'p';
      }

      String rest = e.substring(name.end()).trim();
      if (scope.pointers().containsKey(name.group()) && (rest.startsWith("[") || rest.startsWith("->"))) {
         return scope.pointers().get(name.group());
      } else if (scope.tables().contains(name.group())) {
         return 'c';
      }

      // An element of a struct's pointer member is in that member's space.
      Matcher member = Pattern.compile("(?s)^\\.\\s*(\\w+)\\s*\\[").matcher(rest);
      return member.find() ? scope.member(member.group(1)) : 'p';
   }

   private static String unbracket(String expression) {
      String e = expression.trim();
      while (e.startsWith("(") && matching(e, 0) == e.length() - 1) {
         e = e.substring(1, e.length() - 1).trim();
      }

      return e;
   }

   private static int size(String parameter, String kernel) {
      String type = parameter.replaceAll("\\b(const|restrict)\\b", "").trim().replaceAll("\\s+\\w+$", "").trim();
      return switch (type) {
         case "int", "unsigned int", "uint", "float", "unsigned" -> 4;
         case "double", "long", "ulong", "unsigned long" -> 8;
         case "short", "unsigned short", "ushort" -> 2;
         case "char", "unsigned char", "uchar", "signed char" -> 1;
         default -> throw new IllegalArgumentException("kernel " + kernel + ": no OpenCL argument size for parameter '" + parameter + "'");
      };
   }
}
