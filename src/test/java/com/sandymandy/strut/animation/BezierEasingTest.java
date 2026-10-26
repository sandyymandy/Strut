package com.sandymandy.strut.animation;

import com.sandymandy.strut.animation.keyframe.AnimationPoint;
import com.sandymandy.strut.animation.keyframe.Keyframe;
import com.sandymandy.strut.loading.math.MathValue;
import com.sandymandy.strut.loading.math.value.Constant;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EasingType#BEZIER}'s Newton-Raphson curve solve, in isolation from
 * {@link com.sandymandy.strut.loading.binary.BinaryAnimationBaker}, which is what actually
 * produces its four arguments from a keyframe's handles - see {@code BinaryAnimationBakerTest}
 * for that wiring, and {@link EasingType.BezierEasing}'s javadoc for what the four arguments mean
 * and why (Blockbench's real {@code getBezierLerp} semantics, not the retracted CSS
 * {@code cubic-bezier()} convention this class used to implement)
 * <p>
 * The four arguments here are {@code (startHandleTime, startHandleValue, endHandleTime,
 * endHandleValue)} - absolute offsets in the segment's own tick and value units, not a
 * normalised 0..1 pair. {@code startHandleTime}/{@code Value} offset from the segment's start
 * ({@code P0}); {@code endHandleTime}/{@code Value} offset from its end ({@code P3})
 */
class BezierEasingTest {
	private static final double EPSILON = 1e-4;
	private static final double START = 0;
	private static final double END = 100;
	private static final double TRANSITION_LENGTH = 20;

	private static double apply(double currentTick, double startHandleTime, double startHandleValue, double endHandleTime, double endHandleValue) {
		Keyframe<MathValue> keyframe = new Keyframe<>(TRANSITION_LENGTH, new Constant(START), new Constant(END), EasingType.BEZIER,
				List.of(new Constant(startHandleTime), new Constant(startHandleValue), new Constant(endHandleTime), new Constant(endHandleValue)));
		AnimationPoint point = new AnimationPoint(keyframe, currentTick, TRANSITION_LENGTH, START, END);

		return EasingType.BEZIER.apply(point, null, currentTick / TRANSITION_LENGTH);
	}

	@Test
	@DisplayName("both endpoints of the curve land exactly on the start and end values")
	void endpointsAreExact() {
		assertEquals(START, apply(0, 5, 30, -5, -20), EPSILON,
				"at the segment's own start, the solved parameter is 0 regardless of the handles");
		assertEquals(END, apply(TRANSITION_LENGTH, 5, 30, -5, -20), EPSILON,
				"at or past the transition length, apply() returns the end value directly rather than solving the curve");
	}

	/**
	 * With every handle at zero, both axes' control points collapse to the same degenerate shape -
	 * time: {@code (0, 0, L, L)}; value: {@code (START, START, END, END)} - so both are the
	 * identical cubic {@code s² (3 - 2s)}, just scaled: {@code X(s) = L · s²(3-2s)} and
	 * {@code Y(s) = START + (END-START) · s²(3-2s)}. Their ratio is constant, so {@code Y} as a
	 * function of elapsed time (not of the parameter {@code s}, which individually traces a
	 * nonlinear curve on both axes) is exactly a plain linear lerp. This is the one case exact
	 * enough to assert a precise numeric value at an arbitrary fraction without needing an
	 * independent reference implementation of the curve solve - nonzero handles (either axis)
	 * break the proportionality and are only checkable at a hand-derivable point like a segment's
	 * exact midpoint, which {@code BinaryAnimationBakerTest.bezierEvaluatesAgainstAHandComputedValue}
	 * covers instead
	 */
	@Test
	@DisplayName("zero handles produce the identity curve at any fraction")
	void zeroHandlesAreIdentity() {
		double fraction = 0.3;
		double result = apply(fraction * TRANSITION_LENGTH, 0, 0, 0, 0);

		assertEquals(START + (END - START) * fraction, result, EPSILON);
	}

	@Test
	@DisplayName("a lopsided ease-in curve stays monotonic and within the value range")
	void easeInCurveIsMonotonicAndBounded() {
		double previous = START;

		for (double fraction = 0.1; fraction <= 1.0; fraction += 0.1) {
			// A standard "ease-in" shape: the start handle barely moves in time (slow to start),
			// the end handle pulls far back (accelerating toward the end)
			double result = apply(fraction * TRANSITION_LENGTH, 2, 0, -15, 0);

			assertTrue(result >= START - EPSILON && result <= END + EPSILON,
					"result " + result + " at fraction " + fraction + " must stay within [" + START + ", " + END + "]");
			assertTrue(result >= previous - EPSILON,
					"an ease-in curve must not move backwards: " + result + " followed " + previous + " at fraction " + fraction);

			previous = result;
		}

		assertTrue(previous > END - EPSILON, "the curve must reach the end value by the last sample");
	}

	@Test
	@DisplayName("with fewer than four arguments, falls back to a plain linear lerp rather than failing")
	void fallsBackToLinearWithTooFewArguments() {
		Keyframe<MathValue> keyframe = new Keyframe<>(TRANSITION_LENGTH, new Constant(START), new Constant(END), EasingType.BEZIER,
				new ObjectArrayList<>(0));
		AnimationPoint point = new AnimationPoint(keyframe, TRANSITION_LENGTH / 2, TRANSITION_LENGTH, START, END);

		assertEquals((START + END) / 2, EasingType.BEZIER.apply(point, null, 0.5), EPSILON);
	}
}
