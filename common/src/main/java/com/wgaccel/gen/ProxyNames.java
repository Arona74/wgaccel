package com.wgaccel.gen;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The game's methods as a proxy receives them, by the names our source calls them. On Fabric 1.21.1 the game runs under
 * intermediary names (getBlockState is method_8320 there), so a proxy that dispatches on {@link Method#getName} never
 * recognises them: the stand-in worlds answered nothing, the tree placers placed nothing. Learnt by calling each method
 * once on a recording proxy from ordinary code (which the build remaps to the running names): the Method it receives is
 * the one later calls bring.
 */
final class ProxyNames {
   private final Map<Method, String> names = new HashMap<>();
   private String pending;

   private ProxyNames() {
   }

   /** The names of the methods the calls make on a proxy of these interfaces (each call preceded by {@code label}). */
   static Map<Method, String> learn(Class<?>[] types, Consumer<Learner> calls) {
      ProxyNames learnt = new ProxyNames();
      Object proxy = Proxy.newProxyInstance(types[0].getClassLoader(), types, (self, method, args) -> {
         if (learnt.pending != null) {
            learnt.names.putIfAbsent(method, learnt.pending);
            learnt.pending = null;
         }

         return zero(method.getReturnType());
      });
      calls.accept(new Learner(learnt, proxy));
      return Map.copyOf(learnt.names);
   }

   /** Labels the next call made on the proxy. */
   static final class Learner {
      private final ProxyNames learnt;
      final Object proxy;

      Learner(ProxyNames learnt, Object proxy) {
         this.learnt = learnt;
         this.proxy = proxy;
      }

      /** The proxy, labelled: the next call on it is named {@code name}. */
      @SuppressWarnings("unchecked")
      <T> T as(String name) {
         this.learnt.pending = name;
         return (T)this.proxy;
      }

      /** Runs one labelled call; a failure (an argument it did not like) only leaves that name unlearnt. */
      void call(String name, Runnable call) {
         try {
            this.learnt.pending = name;
            call.run();
         } catch (RuntimeException | LinkageError unknown) {
            // Left to the name it runs under.
         } finally {
            this.learnt.pending = null;
         }
      }
   }

   static Object zero(Class<?> type) {
      if (type == boolean.class) {
         return false;
      } else if (type == int.class) {
         return 0;
      } else if (type == long.class) {
         return 0L;
      } else if (type == float.class) {
         return 0.0F;
      } else if (type == double.class) {
         return 0.0;
      } else if (type == byte.class) {
         return (byte)0;
      } else if (type == short.class) {
         return (short)0;
      } else if (type == char.class) {
         return (char)0;
      }

      return null;
   }
}
