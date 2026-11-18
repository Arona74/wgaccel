package com.wgaccel.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** A sequential flat-map plan with explicit depth-first frames. Every mapped stream is closed in nesting order. */
public final class OrderedStreams {
   private OrderedStreams() {}
   public static final class Plan<T> {
      private final List<Function<T, Stream<T>>> stages;
      public Plan(int expected) { this.stages=new ArrayList<>(expected); }
      @SuppressWarnings("unchecked")
      public void add(Function<? super T, ? extends Stream<? extends T>> mapper) {
         this.stages.add((Function<T, Stream<T>>)(Function<?,?>)Objects.requireNonNull(mapper));
      }
      @SuppressWarnings("unchecked")
      public Stream<T> stream(Stream<T> root) {
         Function<T,Stream<T>>[] copy=this.stages.toArray(Function[]::new);
         Traversal<T> traversal=new Traversal<>(Objects.requireNonNull(root),copy);
         // The root is the caller's stream. Like a terminal forEach, this plan never closes that root itself.
         return StreamSupport.stream(traversal,false).onClose(traversal::close);
      }
   }

   private static final class Traversal<T> extends Spliterators.AbstractSpliterator<T> implements AutoCloseable {
      private final Function<T,Stream<T>>[] stages;
      private final Stream<T>[] streams;
      private final Iterator<T>[] iterators;
      private int depth;
      @SuppressWarnings("unchecked")
      Traversal(Stream<T> root,Function<T,Stream<T>>[] stages) {
         super(Long.MAX_VALUE,Spliterator.ORDERED);
         this.stages=stages;
         this.streams=new Stream[stages.length+1];this.iterators=new Iterator[stages.length+1];
         this.iterators[0]=root.iterator();
      }
      @Override public boolean tryAdvance(Consumer<? super T> action) {
         Objects.requireNonNull(action);
         try {
            while(this.depth>=0) {
               Iterator<T> current=this.iterators[this.depth];
               if(!current.hasNext()) {
                  this.iterators[this.depth]=null;
                  Stream<T> exhausted=this.streams[this.depth];this.streams[this.depth]=null;
                  this.depth--;
                  if(exhausted!=null) exhausted.close();
                  continue;
               }
               T value=current.next();
               if(this.depth==this.stages.length) {action.accept(value);return true;}
               Stream<T> child=this.stages[this.depth].apply(value);
               if(child==null) continue; // Stream.flatMap treats a null mapped stream as empty.
               this.depth++;
               this.streams[this.depth]=child;
               this.iterators[this.depth]=child.sequential().iterator();
            }
            return false;
         } catch(Throwable failure) {throw unchecked(this.closeChildren(failure));}
      }
      private Throwable closeChildren(Throwable primary) {
         for(int i=this.streams.length-1;i>0;i--) {
            Stream<T> child=this.streams[i];this.streams[i]=null;this.iterators[i]=null;
            if(child!=null) try {child.close();} catch(Throwable failure) {
               if(primary==null) primary=failure;else if(primary!=failure) primary.addSuppressed(failure);
            }
         }
         this.iterators[0]=null;this.depth=-1;
         return primary;
      }
      @Override public void close() {
         Throwable failure=this.closeChildren(null);
         if(failure!=null) throw unchecked(failure);
      }
   }
   private static RuntimeException unchecked(Throwable failure) {return OrderedStreams.<RuntimeException>raise(failure);}
   @SuppressWarnings("unchecked")
   private static <E extends Throwable> RuntimeException raise(Throwable failure) throws E {throw (E)failure;}
}
