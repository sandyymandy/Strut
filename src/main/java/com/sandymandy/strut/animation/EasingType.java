package com.sandymandy.strut.animation;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import it.unimi.dsi.fastutil.doubles.Double2DoubleFunction;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.animation.keyframe.AnimationPoint;
import com.sandymandy.strut.animation.keyframe.Keyframe;
import com.sandymandy.strut.loading.math.MathValue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Functional interface defining an easing function
 * <p>
 * {@code value} is the easing value provided from the keyframe's {@link Keyframe#easingArgs()}
 *
 * @see <a href="https://easings.net/">Easings.net</a>
 * @see <a href="https://cubic-bezier.com">Cubic-Bezier.com</a>
 */
@FunctionalInterface
public interface EasingType {
	final Map<String, EasingType> EASING_TYPES = new ConcurrentHashMap<>(64);

	EasingType LINEAR = register("linear", register("none", value -> easeIn(EasingType::linear)));
	EasingType STEP = register("step", value -> easeIn(step(value)));
	EasingType EASE_IN_SINE = register("easeinsine", value -> easeIn(EasingType::sine));
	EasingType EASE_OUT_SINE = register("easeoutsine", value -> easeOut(EasingType::sine));
	EasingType EASE_IN_OUT_SINE = register("easeinoutsine", value -> easeInOut(EasingType::sine));
	EasingType EASE_IN_QUAD = register("easeinquad", value -> easeIn(EasingType::quadratic));
	EasingType EASE_OUT_QUAD = register("easeoutquad", value -> easeOut(EasingType::quadratic));
	EasingType EASE_IN_OUT_QUAD = register("easeinoutquad", value -> easeInOut(EasingType::quadratic));
	EasingType EASE_IN_CUBIC = register("easeincubic", value -> easeIn(EasingType::cubic));
	EasingType EASE_OUT_CUBIC = register("easeoutcubic", value -> easeOut(EasingType::cubic));
	EasingType EASE_IN_OUT_CUBIC = register("easeinoutcubic", value -> easeInOut(EasingType::cubic));
	EasingType EASE_IN_QUART = register("easeinquart", value -> easeIn(pow(4)));
	EasingType EASE_OUT_QUART = register("easeoutquart", value -> easeOut(pow(4)));
	EasingType EASE_IN_OUT_QUART = register("easeinoutquart", value -> easeInOut(pow(4)));
	EasingType EASE_IN_QUINT = register("easeinquint", value -> easeIn(pow(5)));
	EasingType EASE_OUT_QUINT = register("easeoutquint", value -> easeOut(pow(5)));
	EasingType EASE_IN_OUT_QUINT = register("easeinoutquint", value -> easeInOut(pow(5)));
	EasingType EASE_IN_EXPO = register("easeinexpo", value -> easeIn(EasingType::exp));
	EasingType EASE_OUT_EXPO = register("easeoutexpo", value -> easeOut(EasingType::exp));
	EasingType EASE_IN_OUT_EXPO = register("easeinoutexpo", value -> easeInOut(EasingType::exp));
	EasingType EASE_IN_CIRC = register("easeincirc", value -> easeIn(EasingType::circle));
	EasingType EASE_OUT_CIRC = register("easeoutcirc", value -> easeOut(EasingType::circle));
	EasingType EASE_IN_OUT_CIRC = register("easeinoutcirc", value -> easeInOut(EasingType::circle));
	EasingType EASE_IN_BACK = register("easeinback", value -> easeIn(back(value)));
	EasingType EASE_OUT_BACK = register("easeoutback", value -> easeOut(back(value)));
	EasingType EASE_IN_OUT_BACK = register("easeinoutback", value -> easeInOut(back(value)));
	EasingType EASE_IN_ELASTIC = register("easeinelastic", value -> easeIn(elastic(value)));
	EasingType EASE_OUT_ELASTIC = register("easeoutelastic", value -> easeOut(elastic(value)));
	EasingType EASE_IN_OUT_ELASTIC = register("easeinoutelastic", value -> easeInOut(elastic(value)));
	EasingType EASE_IN_BOUNCE = register("easeinbounce", value -> easeIn(bounce(value)));
	EasingType EASE_OUT_BOUNCE = register("easeoutbounce", value -> easeOut(bounce(value)));
	EasingType EASE_IN_OUT_BOUNCE = register("easeinoutbounce", value -> easeInOut(bounce(value)));
	EasingType CATMULLROM = register("catmullrom", new CatmullRomEasing());
	EasingType BEZIER = register("bezier", new BezierEasing());

	Double2DoubleFunction buildTransformer(@Nullable Double value);

	static double lerpWithOverride(AnimationPoint animationPoint, EasingType override) {
		EasingType easingType = override;

		if (override == null)
			easingType = animationPoint.keyFrame() == null ? LINEAR : animationPoint.keyFrame().easingType();

		return easingType.apply(animationPoint);
	}

	default double apply(AnimationPoint animationPoint) {
		Double easingVariable = null;

		if (animationPoint.keyFrame() != null && !animationPoint.keyFrame().easingArgs().isEmpty())
			easingVariable = animationPoint.keyFrame().easingArgs().getFirst().get();

		return apply(animationPoint, easingVariable, animationPoint.currentTick() / animationPoint.transitionLength());
	}

	default double apply(AnimationPoint animationPoint, @Nullable Double easingValue, double lerpValue) {
		if (animationPoint.currentTick() >= animationPoint.transitionLength())
			return (float)animationPoint.animationEndValue();

		return MathHelper.lerp(buildTransformer(easingValue).apply(lerpValue), animationPoint.animationStartValue(), animationPoint.animationEndValue());
	}

	/**
	 * Register an {@code EasingType} with Strut for handling animation transitions and value curves
	 * <p>
	 * <b><u>MUST be called during mod construct</u></b>
	 * <p>
	 * It is recommended you don't call this directly, and instead call it via {@code StrutUtil#addCustomEasingType}
	 *
	 * @param name The name of the easing type
	 * @param easingType The {@code EasingType} to associate with the given name
	 * @return The {@code EasingType} you registered
	 */
	static EasingType register(String name, EasingType easingType) {
		EASING_TYPES.putIfAbsent(name, easingType);

		return easingType;
	}

	/**
	 * Retrieve an {@code EasingType} instance based on a {@link JsonElement}. Returns one of the default {@code EasingTypes} if the name matches, or any other registered {@code EasingType} with a matching name
	 *
	 * @param json The {@code easing} {@link JsonElement} to attempt to parse.
	 * @return A usable {@code EasingType} instance
	 */
	static EasingType fromJson(JsonElement json) {
		if (!(json instanceof JsonPrimitive primitive) || !primitive.isString())
			return LINEAR;

		return fromString(primitive.getAsString().toLowerCase(Locale.ROOT));
	}

	/**
	 * Get an existing {@code EasingType} from a given string, matching the string to its name
	 *
	 * @param name The name of the easing function
	 * @return The relevant {@code EasingType}, or {@link EasingType#LINEAR} if none match
	 */
	static EasingType fromString(String name) {
		return EASING_TYPES.getOrDefault(name, EasingType.LINEAR);
	}

	// ---> Easing Transition Type Functions <--- //

	/**
	 * Returns an easing function running linearly. Functionally equivalent to no easing
	 */
	static Double2DoubleFunction linear(Double2DoubleFunction function) {
		return function;
	}
	
	/**
	 * Performs an approximation of Catmull-Rom interpolation, used to get smooth interpolated motion between keyframes
	 * <p>
	 * Given that by necessity, this only accepts a single argument, making this only technically a spline interpolation for n=1
	 * <p>
	 * <a href="https://pub.dev/documentation/latlong2/latest/spline/CatmullRom-class.html">CatmullRom#position</a>
	 */
	static double catmullRom(double n) {
		return 0.5d * (2d * (n + 1d) + 2d
				+ (2d * n - 5d * (n + 1d) + 4d * (n + 2d) - (n + 3d))
				+ (3d * (n + 1d) - n - 3d * (n + 2d) + (n + 3d)));
	}

	/**
	 * Returns an easing function running forward in time
	 */
	static Double2DoubleFunction easeIn(Double2DoubleFunction function) {
		return function;
	}

	/**
	 * Returns an easing function running backwards in time
	 */
	static Double2DoubleFunction easeOut(Double2DoubleFunction function) {
		return time -> 1 - function.apply(1 - time);
	}

	/**
	 * Returns an easing function that runs equally both forwards and backwards in time based on the halfway point, generating a symmetrical curve
	 */
	static Double2DoubleFunction easeInOut(Double2DoubleFunction function) {
		return time -> {
			if (time < 0.5d)
				return function.apply(time * 2d) / 2d;

			return 1 - function.apply((1 - time) * 2d) / 2d;
		};
	}

	// ---> Stepping Functions <--- //

	/**
	 * Returns a stepping function that returns 1 for any input value greater than 0, or otherwise returning 0
	 */
	static Double2DoubleFunction stepPositive(Double2DoubleFunction function) {
		return n -> n > 0 ? 1 : 0;
	}

	/**
	 * Returns a stepping function that returns 1 for any input value greater than or equal to 0, or otherwise returning 0
	 */
	static Double2DoubleFunction stepNonNegative(Double2DoubleFunction function) {
		return n -> n >= 0 ? 1 : 0;
	}

	// ---> Mathematical Functions <--- //

	/**
	 * A linear function, equivalent to a null-operation
	 * <p>
	 * {@code f(n) = n}
	 */
	static double linear(double n) {
		return n;
	}

	/**
	 * A quadratic function, equivalent to the square (<i>n</i>^2) of elapsed time
	 * <p>
	 * {@code f(n) = n^2}
	 * <p>
	 * <a href="http://easings.net/#easeInQuad">Easings.net#easeInQuad</a>
	 */
	static double quadratic(double n) {
		return n * n;
	}

	/**
	 * A cubic function, equivalent to cube (<i>n</i>^3) of elapsed time
	 * <p>
	 * {@code f(n) = n^3}
	 * <p>
	 * <a href="http://easings.net/#easeInCubic">Easings.net#easeInCubic</a>
	 */
	static double cubic(double n) {
		return n * n * n;
	}

	/**
	 * A sinusoidal function, equivalent to a sine curve output
	 * <p>
	 * {@code f(n) = 1 - cos(n * π / 2)}
	 * <p>
	 * <a href="http://easings.net/#easeInSine">Easings.net#easeInSine</a>
	 */
	static double sine(double n) {
		return 1 - Math.cos(n * Math.PI / 2f);
	}

	/**
	 * A circular function, equivalent to a normally symmetrical curve
	 * <p>
	 * {@code f(n) = 1 - sqrt(1 - n^2)}
	 * <p>
	 * <a href="http://easings.net/#easeInCirc">Easings.net#easeInCirc</a>
	 */
	static double circle(double n) {
		return 1 - Math.sqrt(1 - n * n);
	}

	/**
	 * An exponential function, equivalent to an exponential curve
	 * <p>
	 * {@code f(n) = 2^(10 * (n - 1))}
	 * <p>
	 * <a href="http://easings.net/#easeInExpo">Easings.net#easeInExpo</a>
	 */
	static double exp(double n) {
		return Math.pow(2, 10 * (n - 1));
	}

	// ---> Easing Curve Functions <--- //

	/**
	 * An elastic function, equivalent to an oscillating curve
	 * <p>
	 * <i>n</i> defines the elasticity of the output
	 * <p>
	 * {@code f(t) = 1 - (cos(t * π) / 2))^3 * cos(t * n * π)}
	 * <p>
	 * <a href="http://easings.net/#easeInElastic">Easings.net#easeInElastic</a>
	 */
	static Double2DoubleFunction elastic(Double n) {
		double n2 = n == null ? 1 : n;

		return t -> 1 - Math.pow(Math.cos(t * Math.PI / 2f), 3) * Math.cos(t * n2 * Math.PI);
	}

	/**
	 * A bouncing function, equivalent to a bouncing ball curve
	 * <p>
	 * <i>n</i> defines the bounciness of the output
	 * <p>
	 * Thanks to <b>Waterded#6455</b> for making the bounce adjustable, and <b>GiantLuigi4#6616</b> for additional cleanup
	 * <p>
	 * <a href="http://easings.net/#easeInBounce">Easings.net#easeInBounce</a>
	 */
	static Double2DoubleFunction bounce(Double n) {
		final double n2 = n == null ? 0.5d : n;

		Double2DoubleFunction one = x -> 121f / 16f * x * x;
		Double2DoubleFunction two = x -> 121f / 4f * n2 * Math.pow(x - 6f / 11f, 2) + 1 - n2;
		Double2DoubleFunction three = x -> 121 * n2 * n2 * Math.pow(x - 9f / 11f, 2) + 1 - n2 * n2;
		Double2DoubleFunction four = x -> 484 * n2 * n2 * n2 * Math.pow(x - 10.5f / 11f, 2) + 1 - n2 * n2 * n2;

		return t -> Math.min(Math.min(one.apply(t), two.apply(t)), Math.min(three.apply(t), four.apply(t)));
	}

	/**
	 * A negative elastic function, equivalent to inverting briefly before increasing
	 * <p>
	 * <code>f(t) = t^2 * ((n * 1.70158 + 1) * t - n * 1.70158)</code>
	 * <p>
	 * <a href="https://easings.net/#easeInBack">Easings.net#easeInBack</a>
	 */
	static Double2DoubleFunction back(Double n) {
		final double n2 = n == null ? 1.70158d : n * 1.70158d;

		return t -> t * t * ((n2 + 1) * t - n2);
	}

	/**
	 * An exponential function, equivalent to an exponential curve to the {@code n} root
	 * <p>
	 * <code>f(t) = t^n</code>
	 *
	 * @param n The exponent
	 */
	static Double2DoubleFunction pow(double n) {
		return t -> Math.pow(t, n);
	}

	// The MIT license notice below applies to the function step
	/**
	 * The MIT License (MIT)
	 *<br><br>
	 * Copyright (c) 2015 Boris Chumichev
	 *<br><br>
	 * Permission is hereby granted, free of charge, to any person obtaining a copy
	 * of this software and associated documentation files (the "Software"), to deal
	 * in the Software without restriction, including without limitation the rights
	 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
	 * copies of the Software, and to permit persons to whom the Software is
	 * furnished to do so, subject to the following conditions:
	 *<br><br>
	 * The above copyright notice and this permission notice shall be included in
	 * all copies or substantial portions of the Software.
	 *<br><br>
	 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
	 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
	 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
	 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
	 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
	 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
	 * SOFTWARE.
	 * <br><br>
	 * Returns a stepped value based on the nearest step to the input value.<br>
	 * The size (grade) of the steps depends on the provided value of {@code n}
	 **/
	static Double2DoubleFunction step(Double n) {
		double n2 = n == null ? 2 : n;

		if (n2 < 2)
			throw new IllegalArgumentException("Steps must be >= 2, got: " + n2);

		final int steps = (int)n2;

		return t -> {
			double result = 0;

			if (t < 0)
				return result;

			double stepLength = (1 / (double)steps);

			if (t > (result = (steps - 1) * stepLength))
				return result;

			int testIndex;
			int leftBorderIndex = 0;
			int rightBorderIndex = steps - 1;

			while (rightBorderIndex - leftBorderIndex != 1) {
				testIndex = leftBorderIndex + (rightBorderIndex - leftBorderIndex) / 2;

				if (t >= testIndex * stepLength) {
					leftBorderIndex = testIndex;
				}
				else {
					rightBorderIndex = testIndex;
				}
			}

			return leftBorderIndex * stepLength;
		};
	}

	/**
	 * Custom EasingType implementation required for special-handling of spline-based interpolation
	 */
	class CatmullRomEasing implements EasingType {
		/**
         * Generates a value from a given Catmull-Rom spline range with Centripetal parameterization (alpha=0.5)
         * <p>
         * Per standard implementation, this generates a spline curve over control points p1-p2, with p0 and p3
         * acting as curve anchors.<br>
		 * We then apply the delta to determine the point on the generated spline to return.
		 * <p>
		 * Functionally equivalent to {@code float, float, float, float)}
         *
         * @see <a href="https://en.wikipedia.org/wiki/Centripetal_Catmull%E2%80%93Rom_spline">Wikipedia</a>
         */
		public static double getPointOnSpline(double delta, double p0, double p1, double p2, double p3) {
			return 0.5d * (2d * p1 + (p2 - p0) * delta +
						  (2d * p0 - 5d * p1 + 4d * p2 - p3) * delta * delta +
						  (3d * p1 - p0 - 3d * p2 + p3) * delta * delta * delta);
		}

		@Override
		public Double2DoubleFunction buildTransformer(Double value) {
			return easeInOut(EasingType::catmullRom);
		}

		@Override
		public double apply(AnimationPoint animationPoint, Double easingValue, double lerpValue) {
			if (animationPoint.currentTick() >= animationPoint.transitionLength())
				return animationPoint.animationEndValue();

			List<? extends MathValue> easingArgs = animationPoint.keyFrame().easingArgs();

			if (easingArgs.size() < 2)
				return MathHelper.lerp(buildTransformer(easingValue).apply(lerpValue), animationPoint.animationStartValue(), animationPoint.animationEndValue());

			return getPointOnSpline(lerpValue, easingArgs.get(0).get(), animationPoint.animationStartValue(), animationPoint.animationEndValue(), easingArgs.get(1).get());
		}
	}

	/**
	 * Custom {@code EasingType} matching Blockbench's native bezier keyframe, {@code Keyframe#getBezierLerp}
	 * in {@code keyframe.js} - verified against real Blockbench 5.1.6 source while writing the plugin
	 * (which retracts this class's original,
	 * incorrect CSS-{@code cubic-bezier()} convention)
	 * <p>
	 * A segment's curve is built from <b>two different keyframes</b>: the segment-start keyframe's
	 * own <i>right</i> handle, and the segment-end keyframe's own <i>left</i> handle - not both
	 * handles of one keyframe. Each handle is an <b>absolute offset</b> from its owning keyframe,
	 * in the segment's own time (ticks) and value units, not a normalised 0..1 pair:
	 * <pre>
	 * P0 = (0,                          startValue)
	 * P1 = (rightHandleTime,            startValue + rightHandleValue)   - the start keyframe's own right handle
	 * P2 = (segmentLength + leftHandleTime, endValue + leftHandleValue)  - the end keyframe's own left handle
	 * P3 = (segmentLength,               endValue)
	 * </pre>
	 * {@link Keyframe#easingArgs()} therefore carries four values per axis in that order -
	 * {@code (rightHandleTime, rightHandleValue, leftHandleTime, leftHandleValue)} - assembled by
	 * {@code BinaryAnimationBaker.easingArgsOf} from <b>two</b> {@code RawKey}s (this key's own
	 * left handle, the previous key's own right handle), converted to ticks and (for a rotation
	 * channel) to radians at bake time, and clamped to the segment exactly as Blockbench clamps
	 * {@code bezier_right_time} to {@code [0, time_gap]} and {@code bezier_left_time} to
	 * {@code [-time_gap, 0]} - which is why {@code P1.time} and {@code P2.time} never need
	 * clamping again here.
	 * <p>
	 * Like the retracted convention, the curve's time axis is not simply invertible, so
	 * {@link #solveParameterForTime} finds the curve parameter {@code s} whose time-component
	 * equals the elapsed tick by Newton-Raphson iteration, then reads the eased value off that
	 * same parameter on the value axis - the same technique, now applied to a general (not
	 * fixed-endpoint) cubic bezier.
	 */
	class BezierEasing implements EasingType {
		private static final int NEWTON_RAPHSON_ITERATIONS = 8;
		private static final double DERIVATIVE_EPSILON = 1e-6;

		/**
		 * A general cubic bezier curve's value at parameter {@code s}, given all four control
		 * points - unlike a timing-function curve, none of Blockbench's four points are fixed
		 */
		private static double cubicBezier(double s, double p0, double p1, double p2, double p3) {
			double oneMinus = 1 - s;

			return oneMinus * oneMinus * oneMinus * p0 + 3 * oneMinus * oneMinus * s * p1
					+ 3 * oneMinus * s * s * p2 + s * s * s * p3;
		}

		private static double cubicBezierDerivative(double s, double p0, double p1, double p2, double p3) {
			double oneMinus = 1 - s;

			return 3 * oneMinus * oneMinus * (p1 - p0) + 6 * oneMinus * s * (p2 - p1) + 3 * s * s * (p3 - p2);
		}

		/**
		 * Finds the curve parameter {@code s} whose time-component equals {@code targetTime}, given
		 * the curve's four time-axis control points ({@code t0} and {@code t3} are the segment's own
		 * start (0) and end (its length), not fixed at 0 and 1 the way a timing function's are)
		 */
		private static double solveParameterForTime(double targetTime, double t0, double t1, double t2, double t3) {
			double range = t3 - t0;
			double s = range > DERIVATIVE_EPSILON ? MathHelper.clamp((targetTime - t0) / range, 0, 1) : 0;

			for (int i = 0; i < NEWTON_RAPHSON_ITERATIONS; i++) {
				double derivative = cubicBezierDerivative(s, t0, t1, t2, t3);

				if (Math.abs(derivative) < DERIVATIVE_EPSILON)
					break;

				s -= (cubicBezier(s, t0, t1, t2, t3) - targetTime) / derivative;
				s = MathHelper.clamp(s, 0, 1);
			}

			return s;
		}

		@Override
		public Double2DoubleFunction buildTransformer(Double value) {
			return linear(t -> t);
		}

		@Override
		public double apply(AnimationPoint animationPoint, Double easingValue, double lerpValue) {
			if (animationPoint.currentTick() >= animationPoint.transitionLength())
				return animationPoint.animationEndValue();

			List<? extends MathValue> args = animationPoint.keyFrame() != null ? animationPoint.keyFrame().easingArgs() : List.of();

			if (args.size() < 4)
				return MathHelper.lerp(lerpValue, animationPoint.animationStartValue(), animationPoint.animationEndValue());

			double segmentLength = animationPoint.transitionLength();
			double startValue = animationPoint.animationStartValue();
			double endValue = animationPoint.animationEndValue();

			double p1Time = args.get(0).get();
			double p1Value = startValue + args.get(1).get();
			double p2Time = segmentLength + args.get(2).get();
			double p2Value = endValue + args.get(3).get();

			double s = solveParameterForTime(animationPoint.currentTick(), 0, p1Time, p2Time, segmentLength);

			return cubicBezier(s, startValue, p1Value, p2Value, endValue);
		}
	}
}
