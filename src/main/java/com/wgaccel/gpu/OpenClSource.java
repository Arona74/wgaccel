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
      String s = stripComments(cuda);
      // OpenCL's reserved words used as names.
      s = s.replaceAll("\\b(half|global|local|constant|private|kernel|pipe|read_only|write_only|read_write)\\b", "wga_$1");
      s = lambdas(s);
      s = typedefStructs(s);
      s = s.replace("extern \"C\" __global__ void", "__kernel void").replace("__global__ void", "__kernel void");
      s = s.replace("unsigned long long", "ulong").replace("long long", "long");
      s = s.replaceAll("\\b(0[xX][0-9a-fA-F]+|\\d+)(?:[uU](?:ll|LL)|(?:ll|LL)[uU])\\b", "$1UL").replaceAll("\\b(0[xX][0-9a-fA-F]+|\\d+)(?:ll|LL)\\b", "$1L");
      // Tables in the global address space (OpenCL 2.0's program-scope variables): __constant's pointers do not convert to
      // the generic ones the functions take.
      s = CONSTANT_TABLE.matcher(s).replaceAll("__global const $1 $2[").replace("__constant__", "__constant");
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
      s = kernelParameters(s, kernels);
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
   private static String kernelParameters(String text, Map<String, int[]> kernels) {
      return replaceAll(KERNEL, text, m -> {
         List<String> parameters = split(m.group(2));
         int[] sizes = new int[parameters.size()];
         StringBuilder rewritten = new StringBuilder();
         for (int i = 0; i < parameters.size(); i++) {
            String parameter = parameters.get(i).trim();
            if (i > 0) {
               rewritten.append(", ");
            }

            if (parameter.contains("*")) {
               rewritten.append(parameter.startsWith("__global") ? parameter : "__global " + parameter);
               sizes[i] = 0;
            } else {
               rewritten.append(parameter);
               sizes[i] = size(parameter, m.group(1));
            }
         }

         kernels.put(m.group(1), sizes);
         return "__kernel void " + m.group(1) + "(" + rewritten + ")";
      });
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
