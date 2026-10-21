package com.wgaccel.cudacheck;

/** Stand-ins named like vanilla's DensityFunctions records (class and field names are all DensityCodegen reads). */
final class DensityFunctions {
   private DensityFunctions() {
   }

   enum TwoType { ADD, MUL, MIN, MAX }

   enum MappedType { ABS, SQUARE, CUBE, HALF_NEGATIVE, QUARTER_NEGATIVE, SQUEEZE }

   enum MarkerType { Interpolated, FlatCache, Cache2D, CacheOnce, CacheAllInCell }

   enum Rarity { TYPE1, TYPE2 }

   enum BlendAlpha { INSTANCE }

   record Direct(Object value) {
      public boolean isBound() {
         return true;
      }
   }

   record NoiseHolder(Object noiseData, Object noise) {
   }

   record HolderHolder(Direct function) {
   }

   record Constant(double value) {
      public double minValue() {
         return this.value;
      }

      public double maxValue() {
         return this.value;
      }
   }

   record Ap2(TwoType type, Object argument1, Object argument2, double minValue, double maxValue) {
   }

   record Mapped(MappedType type, Object input, double minValue, double maxValue) {
   }

   record Clamp(Object input, double minValue, double maxValue) {
   }

   record RangeChoice(Object input, double minInclusive, double maxExclusive, Object whenInRange, Object whenOutOfRange) {
      public double minValue() {
         return -1.0;
      }

      public double maxValue() {
         return 1.0;
      }
   }

   record YClampedGradient(int fromY, int toY, double fromValue, double toValue) {
      public double minValue() {
         return Math.min(this.fromValue, this.toValue);
      }

      public double maxValue() {
         return Math.max(this.fromValue, this.toValue);
      }
   }

   record Noise(NoiseHolder noise, double xzScale, double yScale) {
      public double minValue() {
         return -2.0;
      }

      public double maxValue() {
         return 2.0;
      }
   }

   record WeirdScaledSampler(Object input, NoiseHolder noise, Rarity rarityValueMapper) {
   }

   record Spline(Object spline) {
      record Coordinate(Direct function) {
      }
   }

   record Marker(MarkerType type, Object wrapped) {
   }

   record BlendDensity(Object input) {
   }
}
