package com.wgaccel.cudacheck;

import java.util.List;

/** Stand-ins named like vanilla's CubicSpline records. */
final class CubicSpline {
   private CubicSpline() {
   }

   record Constant(float value) {
   }

   record Multipoint(Object coordinate, float[] locations, List<Object> values, float[] derivatives, float minValue, float maxValue) {
   }
}
