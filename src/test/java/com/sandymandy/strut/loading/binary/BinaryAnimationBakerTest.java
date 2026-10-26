package com.sandymandy.strut.loading.binary;

import com.sandymandy.strut.animation.Animation;
import com.sandymandy.strut.animation.EasingType;
import com.sandymandy.strut.animation.keyframe.AnimationPoint;
import com.sandymandy.strut.animation.keyframe.BoneAnimation;
import com.sandymandy.strut.animation.keyframe.Keyframe;
import com.sandymandy.strut.animation.keyframe.KeyframeStack;
import com.sandymandy.strut.animation.keyframe.event.data.SoundKeyframeData;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.Interpolation;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.Property;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.TargetKind;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawBezier;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawChannel;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawClip;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawKey;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawValue;
import com.sandymandy.strut.loading.math.MathValue;
import com.sandymandy.strut.loading.math.value.Constant;
import com.sandymandy.strut.loading.object.BakedAnimations;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bakes {@link BinaryFixtures#animation()} - the fixture that exercises every keyframe shape the
 * format allows - and checks the exact segment lengths, values, easing identities and argument
 * lists {@link BinaryAnimationBaker} must produce, mirroring {@code BakedAnimationsAdapter}
 * exactly
 */
class BinaryAnimationBakerTest {
	private static final float EPSILON = 1e-3f;
	private static final String SOURCE = "test.strutanim";

	private static BoneAnimation onlyBoneAnimation(Animation animation) {
		assertEquals(1, animation.boneAnimations().length, "the fixture drives exactly one bone");

		return animation.boneAnimations()[0];
	}

	/**
	 * Asserts {@code args} holds exactly the given values, within float precision - the values
	 * baked from a {@code Vector3f} handle component went through an actual {@code float} on the
	 * way in, so comparing {@link Constant} records for exact equality against a double literal
	 * fails on the last few bits even when the value is, for every practical purpose, the one
	 * expected
	 */
	private static void assertArgs(List<MathValue> args, double... expected) {
		assertEquals(expected.length, args.size());

		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], args.get(i).get(), EPSILON, "argument " + i);
		}
	}

	private static float literal(MathValue value, String what) {
		assertInstanceOf(Constant.class, value, what + " should be a literal in this fixture");

		return (float)value.get();
	}

	@Test
	@DisplayName("bakes the clip's name, duration and loop mode")
	void bakesClipMetadata() {
		BakedAnimations baked = BinaryAnimationBaker.bake(BinaryAnimationReader.read(BinaryFixtures.animation(), SOURCE), SOURCE);
		Animation animation = baked.getAnimation("walk");

		assertNotNull(animation, "the fixture's one clip is named \"walk\"");
		assertEquals(20d, animation.length(), EPSILON, "a 1-second clip is 20 ticks long");
		assertEquals(Animation.LoopType.LOOP, animation.loopType());
	}

	@Test
	@DisplayName("binds the channel to its declared target kind, not group-first")
	void bindsToTheDeclaredTargetKind() {
		BakedAnimations baked = BinaryAnimationBaker.bake(BinaryAnimationReader.read(BinaryFixtures.animation(), SOURCE), SOURCE);
		BoneAnimation boneAnimation = onlyBoneAnimation(baked.getAnimation("walk"));

		assertEquals("upper", boneAnimation.boneName());
		assertEquals(TargetKind.ARMATURE_BONE, boneAnimation.targetKind(),
				"the fixture's channel explicitly declares ARMATURE_BONE; the baked BoneAnimation must carry it through, not resolve it away");
	}

	@Test
	@DisplayName("a property with no channel bakes to an empty stack")
	void unchanneledPropertiesAreEmpty() {
		BakedAnimations baked = BinaryAnimationBaker.bake(BinaryAnimationReader.read(BinaryFixtures.animation(), SOURCE), SOURCE);
		BoneAnimation boneAnimation = onlyBoneAnimation(baked.getAnimation("walk"));

		assertTrue(boneAnimation.positionKeyFrames().xKeyframes().isEmpty(), "the fixture has no position channel");
		assertTrue(boneAnimation.scaleKeyFrames().xKeyframes().isEmpty(), "the fixture has no scale channel");
	}

	/**
	 * The fixture's four keyframes (t = 0, 0.25, 0.5, 0.75) drive the X component with literal
	 * degrees 0, 10→20 (split by the "post" value), 30, 40. Flattened, that is five entries - the
	 * anchor at t=0, the STEP key's pre and post halves, the catmull-rom key, and the bezier key -
	 * so five segments per axis, with lengths in ticks of 0, ~4.98, ~0.02, 5, 5
	 */
	@Test
	@DisplayName("flattens a keyframe with a post value into two segments at the right lengths")
	void splitsAPostValueIntoTwoSegments() {
		BakedAnimations baked = BinaryAnimationBaker.bake(BinaryAnimationReader.read(BinaryFixtures.animation(), SOURCE), SOURCE);
		BoneAnimation boneAnimation = onlyBoneAnimation(baked.getAnimation("walk"));
		List<Keyframe<MathValue>> x = boneAnimation.rotationKeyFrames().xKeyframes();

		assertEquals(5, x.size(), "one anchor, a pre/post pair for the STEP key, and one each for the catmull-rom and bezier keys");

		assertEquals(0f, x.get(0).length(), EPSILON, "the first entry is a zero-length anchor");
		assertEquals(4.98f, x.get(1).length(), EPSILON, "0.249s (0.25 minus the pre/post epsilon) at 20 ticks/second");
		assertEquals(0.02f, x.get(2).length(), EPSILON, "the near-instant pre-to-post micro-segment");
		assertEquals(5f, x.get(3).length(), EPSILON, "0.25s to 0.5s");
		assertEquals(5f, x.get(4).length(), EPSILON, "0.5s to 0.75s");
	}

	@Test
	@DisplayName("converts a literal rotation component with the binary convention: X unchanged, Y and Z negated, degrees to radians")
	void convertsLiteralRotationWithTheBinaryConvention() {
		BakedAnimations baked = BinaryAnimationBaker.bake(BinaryAnimationReader.read(BinaryFixtures.animation(), SOURCE), SOURCE);
		BoneAnimation boneAnimation = onlyBoneAnimation(baked.getAnimation("walk"));
		List<Keyframe<MathValue>> x = boneAnimation.rotationKeyFrames().xKeyframes();

		// The catmull-rom key's literal X component is 30 degrees, unmirrored (X is not negated)
		assertEquals((float)Math.toRadians(30), literal(x.get(3).endValue(), "the catmull-rom key's X value"), EPSILON);

		List<Keyframe<MathValue>> z = boneAnimation.rotationKeyFrames().zKeyframes();

		// Every Z literal in the fixture is 0 - negation is invisible on zero, so this only proves
		// the value converts and lands as a literal at all, not that the sign flips. The Y-axis
		// sign is exercised indirectly: Y and Z share the same negation rule, and Y's non-expression
		// entries are also 0 for the same reason
		assertEquals(0f, literal(z.get(3).endValue(), "the catmull-rom key's Z value"), EPSILON);
	}

	@Test
	@DisplayName("leaves a Molang expression component unconverted, per the inherited JSON quirk")
	void leavesAnExpressionComponentUnconverted() {
		BakedAnimations baked = BinaryAnimationBaker.bake(BinaryAnimationReader.read(BinaryFixtures.animation(), SOURCE), SOURCE);
		BoneAnimation boneAnimation = onlyBoneAnimation(baked.getAnimation("walk"));
		Keyframe<MathValue> bezierSegment = boneAnimation.rotationKeyFrames().yKeyframes().get(4);

		assertFalse(bezierSegment.endValue() instanceof Constant,
				"the bezier key's Y component is a Molang expression in the fixture, not a literal, so it must not have been converted to a Constant");
	}

	/**
	 * Segment N's easing is the one declared on the keyframe ENDING it - the classic off-by-one
	 * bug. Segment indices here match {@link #splitsAPostValueIntoTwoSegments()}
	 */
	@Test
	@DisplayName("applies each keyframe's easing to the segment ending at it, not the one leaving it")
	void easingLandsOnTheSegmentEndingAtItsKeyframe() {
		BakedAnimations baked = BinaryAnimationBaker.bake(BinaryAnimationReader.read(BinaryFixtures.animation(), SOURCE), SOURCE);
		BoneAnimation boneAnimation = onlyBoneAnimation(baked.getAnimation("walk"));
		List<Keyframe<MathValue>> x = boneAnimation.rotationKeyFrames().xKeyframes();

		assertEquals(EasingType.LINEAR, x.get(0).easingType(), "the anchor segment");
		assertEquals(EasingType.LINEAR, x.get(1).easingType(),
				"the STEP key's pre-split segment carries no easing of its own - it defaults to linear, exactly as BakedAnimationsAdapter's bare-array pre entry does");
		assertEquals(EasingType.STEP, x.get(2).easingType(),
				"the STEP key's post-split segment carries the key's own (implicit, interpolation-derived) easing");
		assertEquals(EasingType.EASE_IN_OUT_ELASTIC, x.get(3).easingType(),
				"the catmull-rom key's explicit named easing overrides its CATMULLROM interpolation");
		assertEquals(List.of(new Constant(1.5)), x.get(3).easingArgs(), "the named easing's one declared argument");

		assertEquals(EasingType.BEZIER, x.get(4).easingType(),
				"the bezier key has no explicit named easing, so its BEZIER interpolation maps directly onto EasingType.BEZIER");

		// The fixture's bezier key's own left handle (leftTime -0.1s -> -2 ticks over this 5-tick
		// segment, leftValue -1 degree -> radians, X unmirrored) is args 2-3. Args 0-1 are the
		// *previous* (catmull-rom) key's right handle - which doesn't exist, since that key isn't
		// itself bezier-interpolated, so it defaults to (0, 0) exactly as an absent
		// bezier_right_time/bezier_right_value does in Blockbench
		assertArgs(x.get(4).easingArgs(), 0, 0, -2, Math.toRadians(-1));
	}

	/**
	 * {@link BinaryFixtures#animation()}'s bezier key has no bezier-interpolated predecessor, so
	 * its segment's start handle always defaults to zero there - this constructs two adjacent
	 * bezier-interpolated keys directly (bypassing the wire format, which
	 * {@code BinaryAnimationReaderTest} already covers) so the segment actually pairs a real start
	 * handle (the first key's own right handle) with a real end handle (the second key's own left
	 * handle), matching Blockbench's {@code getBezierLerp} rather than reading both handles off one
	 * keyframe the way the retracted CSS convention did
	 */
	@Test
	@DisplayName("a segment's bezier handles come from two different keyframes: the start key's right handle and the end key's left handle")
	void bezierHandlesArePairedAcrossTheSegmentsTwoKeyframes() {
		// Deliberately the Y axis: a position channel's X is negated at bake to cancel the
		// renderer's own negation (see bakesPositionXNegatedAndNothingElse), and threading that
		// through every hand-computed number here would obscure what this test is actually about
		//
		// RawBezier(leftTime, leftValue, rightTime, rightValue). The start key's LEFT handle and
		// the end key's RIGHT handle are never read for this segment - only its own right handle
		// (start key) and the end key's own left handle are, so they're left zero to prove that
		RawBezier startKeyBezier = new RawBezier(new Vector3f(0), new Vector3f(0), new Vector3f(0, 0.3f, 0), new Vector3f(0, 2, 0));
		RawBezier endKeyBezier = new RawBezier(new Vector3f(0, -0.3f, 0), new Vector3f(0, -5, 0), new Vector3f(0), new Vector3f(0));
		RawKey start = new RawKey(0f, Interpolation.BEZIER, RawValue.ofLiterals(0, 0, 0), null, null, new float[0], startKeyBezier);
		RawKey end = new RawKey(1f, Interpolation.BEZIER, RawValue.ofLiterals(0, 10, 0), null, null, new float[0], endKeyBezier);
		RawChannel channel = new RawChannel("root", TargetKind.GROUP_BONE, Property.POSITION, null, List.of(start, end));
		RawClip clip = new RawClip("bezier_pairing", 1f, LoopMode.LOOP, List.of(channel), List.of());
		RawBinaryAnimation raw = new RawBinaryAnimation(100, 0, 0, List.of(), List.of(clip));

		BoneAnimation boneAnimation = onlyBoneAnimation(BinaryAnimationBaker.bake(raw, SOURCE).getAnimation("bezier_pairing"));

		// startKeyBezier's right handle time is 0.3s = 6 ticks over this 20-tick segment; entirely
		// within [0, 20], so unclamped. endKeyBezier's left handle time is -0.3s = -6 ticks,
		// likewise unclamped
		assertArgs(boneAnimation.positionKeyFrames().yKeyframes().get(1).easingArgs(), 6, 2, -6, -5);
	}

	/**
	 * The check that matters: not merely that a bezier was applied, but that the eased value at a
	 * specific elapsed tick matches Blockbench's own formula by hand
	 * <p>
	 * Both handles' time components are chosen as zero deltas, which collapses the curve's time-axis
	 * control points to {@code (0, 0, 20, 20)} - a shape whose own symmetry makes {@code s = 0.5}
	 * the exact parameter for the segment's exact midpoint (tick 10 of 20), with no Newton-Raphson
	 * rounding to account for by hand. At {@code s = 0.5}, a general cubic bezier's value is exactly
	 * {@code 0.125 * (p0 + p3) + 0.375 * (p1 + p2)} - elementary enough to check on a calculator
	 * <p>
	 * {@code p0 = 0} (start value), {@code p3 = 10} (end value), {@code p1 = p0 + 6 = 6} (start
	 * key's own right handle value), {@code p2 = p3 + (-4) = 6} (end key's own left handle value) -
	 * so the expected value is {@code 0.125 * (0 + 10) + 0.375 * (6 + 6) = 1.25 + 4.5 = 5.75}
	 */
	@Test
	@DisplayName("evaluates a bezier segment at its midpoint against a hand-computed Blockbench-formula value")
	void bezierEvaluatesAgainstAHandComputedValue() {
		// The Y axis, for the same reason as the pairing test above
		RawBezier startKeyBezier = new RawBezier(new Vector3f(0), new Vector3f(0), new Vector3f(0), new Vector3f(0, 6, 0));
		RawBezier endKeyBezier = new RawBezier(new Vector3f(0), new Vector3f(0, -4, 0), new Vector3f(0), new Vector3f(0));
		RawKey start = new RawKey(0f, Interpolation.BEZIER, RawValue.ofLiterals(0, 0, 0), null, null, new float[0], startKeyBezier);
		RawKey end = new RawKey(1f, Interpolation.BEZIER, RawValue.ofLiterals(0, 10, 0), null, null, new float[0], endKeyBezier);
		RawChannel channel = new RawChannel("root", TargetKind.GROUP_BONE, Property.POSITION, null, List.of(start, end));
		RawClip clip = new RawClip("bezier_midpoint", 1f, LoopMode.LOOP, List.of(channel), List.of());
		RawBinaryAnimation raw = new RawBinaryAnimation(100, 0, 0, List.of(), List.of(clip));

		BoneAnimation boneAnimation = onlyBoneAnimation(BinaryAnimationBaker.bake(raw, SOURCE).getAnimation("bezier_midpoint"));
		Keyframe<MathValue> segment = boneAnimation.positionKeyFrames().yKeyframes().get(1);

		assertEquals(20d, segment.length(), EPSILON, "1 second at 20 ticks/second");

		AnimationPoint midpoint = new AnimationPoint(segment, 10d, segment.length(), segment.startValue().get(), segment.endValue().get());

		assertEquals(5.75d, segment.easingType().apply(midpoint), EPSILON);
	}

	/**
	 * A channel whose first key is not at 0 - every armature channel on a real authored rig - has to
	 * hold that first value until its own time, like the JSON path, or the whole channel runs early
	 * and drifts out of sync with channels that do start at 0
	 */
	@Test
	@DisplayName("a first keyframe at 0.25s holds until 0.25s, rather than starting the channel early")
	void firstKeyframeKeepsItsOwnTime() {
		RawKey first = new RawKey(0.25f, Interpolation.LINEAR, RawValue.ofLiterals(0, 0, 0), null, null, new float[0], null);
		RawKey second = new RawKey(0.75f, Interpolation.LINEAR, RawValue.ofLiterals(0, 4, 0), null, null, new float[0], null);
		RawClip clip = new RawClip("late_start", 1f, LoopMode.LOOP, List.of(
				new RawChannel("root", TargetKind.ARMATURE_BONE, Property.POSITION, Interpolation.LINEAR, List.of(first, second))),
				List.of());
		RawBinaryAnimation raw = new RawBinaryAnimation(100, 0, 0, List.of(), List.of(clip));

		List<Keyframe<MathValue>> y = onlyBoneAnimation(BinaryAnimationBaker.bake(raw, SOURCE).getAnimation("late_start"))
				.positionKeyFrames().yKeyframes();

		assertEquals(5f, y.get(0).length(), EPSILON, "0s to 0.25s, holding the first key's value");
		assertEquals(10f, y.get(1).length(), EPSILON, "0.25s to 0.75s");
	}

	/**
	 * The one place the binary path has to meet the JSON path's coordinate convention rather than
	 * its own
	 * <p>
	 * {@code RenderUtil.translateMatrixToBone} negates a bone's animated X offset for every model,
	 * whatever format it came from, because a Bedrock {@code .animation.json} stores that offset
	 * already negated and the two cancel. A {@code .strutanim} stores the authoring value, so the
	 * bake has to negate X itself or the renderer's negation has nothing to cancel and every
	 * animated sideways movement runs backwards. Rotation and scale are not touched - only
	 * position, and only X
	 */
	@Test
	@DisplayName("negates an animated position's X, and only that")
	void bakesPositionXNegatedAndNothingElse() {
		RawKey key = new RawKey(0f, Interpolation.LINEAR, RawValue.ofLiterals(3, 5, 7), null, null, new float[0], null);
		RawClip clip = new RawClip("axes", 1f, LoopMode.LOOP, List.of(
				new RawChannel("root", TargetKind.GROUP_BONE, Property.POSITION, Interpolation.LINEAR, List.of(key)),
				new RawChannel("root", TargetKind.GROUP_BONE, Property.SCALE, Interpolation.LINEAR, List.of(key))),
				List.of());
		RawBinaryAnimation raw = new RawBinaryAnimation(100, 0, 0, List.of(), List.of(clip));

		BoneAnimation boneAnimation = onlyBoneAnimation(BinaryAnimationBaker.bake(raw, SOURCE).getAnimation("axes"));

		assertEquals(-3d, boneAnimation.positionKeyFrames().xKeyframes().getFirst().endValue().get(), EPSILON,
				"position X is negated to cancel the renderer's own negation");
		assertEquals(5d, boneAnimation.positionKeyFrames().yKeyframes().getFirst().endValue().get(), EPSILON,
				"position Y passes through");
		assertEquals(7d, boneAnimation.positionKeyFrames().zKeyframes().getFirst().endValue().get(), EPSILON,
				"position Z passes through");

		assertEquals(3d, boneAnimation.scaleKeyFrames().xKeyframes().getFirst().endValue().get(), EPSILON,
				"scale is never negated - a negative scale would turn the bone inside out");
	}

	@Test
	@DisplayName("bakes the clip's sound event")
	void bakesTheSoundEvent() {
		BakedAnimations baked = BinaryAnimationBaker.bake(BinaryAnimationReader.read(BinaryFixtures.animation(), SOURCE), SOURCE);
		Animation animation = baked.getAnimation("walk");

		assertEquals(1, animation.keyFrames().sounds().length);

		SoundKeyframeData sound = animation.keyFrames().sounds()[0];

		assertEquals(10d, sound.getStartTick(), EPSILON, "0.5s at 20 ticks/second");
		assertEquals("strut:test.step", sound.getSound());
	}

	// ===============================================================
	// A group bone, constructed directly rather than via a new binary fixture -
	// BinaryAnimationReaderTest already covers the wire format itself
	// ===============================================================

	@Test
	@DisplayName("bakes a channel targeting a group bone the same way as one targeting an armature bone")
	void bakesAGroupBoneChannel() {
		RawKey start = new RawKey(0f, Interpolation.LINEAR, RawValue.ofLiterals(0, 0, 0), null, null, new float[0], null);
		RawKey end = new RawKey(1f, Interpolation.LINEAR, RawValue.ofLiterals(90, 0, 0), null, null, new float[0], null);
		RawChannel channel = new RawChannel("root", TargetKind.GROUP_BONE, Property.ROTATION, Interpolation.LINEAR, List.of(start, end));
		RawClip clip = new RawClip("swing", 1f, LoopMode.LOOP, List.of(channel), List.of());
		RawBinaryAnimation raw = new RawBinaryAnimation(100, 0, 0, List.of(), List.of(clip));

		BakedAnimations baked = BinaryAnimationBaker.bake(raw, SOURCE);
		Animation animation = baked.getAnimation("swing");

		assertNotNull(animation);

		BoneAnimation boneAnimation = onlyBoneAnimation(animation);

		assertEquals("root", boneAnimation.boneName());
		assertEquals(TargetKind.GROUP_BONE, boneAnimation.targetKind());

		KeyframeStack<Keyframe<MathValue>> rotation = boneAnimation.rotationKeyFrames();

		assertEquals(2, rotation.xKeyframes().size());
		assertEquals(20f, rotation.xKeyframes().get(1).length(), EPSILON, "1 second at 20 ticks/second");
		assertEquals((float)Math.toRadians(90), literal(rotation.xKeyframes().get(1).endValue(), "the group bone's X rotation"), EPSILON);
	}
}
