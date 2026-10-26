package com.sandymandy.strut.loading.binary;

import com.sandymandy.strut.animation.Animation;
import com.sandymandy.strut.animation.keyframe.BoneAnimation;
import com.sandymandy.strut.animation.keyframe.Keyframe;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.Interpolation;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.Property;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.TargetKind;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawChannel;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawClip;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawKey;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawValue;
import com.sandymandy.strut.loading.math.MathValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A rotation written as an arithmetic expression that reads no query ({@code 12.5-9}, degrees) is folded to a constant, which the controller treats as already in
 * radians: Jenny's walk turned her jowl 3.5 radians (200 degrees) instead of 3.5 degrees
 */
class ConstantExpressionRotationTest {
	private static MathValue bake(Property property, int axis, String expression, float literal) {
		float[] literals = {0, 0, 0};
		String[] expressions = {null, null, null};

		if (expression != null)
			expressions[axis] = expression;
		else
			literals[axis] = literal;

		RawKey key = new RawKey(0f, Interpolation.LINEAR, new RawValue(literals, expressions), null, null, new float[0], null);
		RawChannel channel = new RawChannel("jowl", TargetKind.GROUP_BONE, property, Interpolation.LINEAR, List.of(key));
		RawClip clip = new RawClip("walk", 1f, LoopMode.LOOP, List.of(channel), List.of());
		RawBinaryAnimation raw = new RawBinaryAnimation(StrutBinaryFormats.ANIMATION_VERSION, 0, 0, List.of(), List.of(clip));
		Animation animation = BinaryAnimationBaker.bake(raw, "test").getAnimation("walk");
		BoneAnimation bone = animation.boneAnimations()[0];
		var stacks = property == Property.ROTATION ? bone.rotationKeyFrames() : property == Property.POSITION ? bone.positionKeyFrames() : bone.scaleKeyFrames();
		List<Keyframe<MathValue>> frames = axis == 0 ? stacks.xKeyframes() : axis == 1 ? stacks.yKeyframes() : stacks.zKeyframes();

		return frames.get(0).endValue();
	}

	@Test
	@DisplayName("a constant rotation expression is in radians, like the literal and like the same expression that changes: 12.5-9 degrees is 3.5 degrees")
	void aConstantExpressionIsConvertedLikeAnyRotation() {
		double expected = Math.toRadians(3.5);

		assertEquals(expected, bake(Property.ROTATION, 2, "12.5-9", 0).get(), 1e-9, "z is the degrees converted");
		// X and Y take the sign the controller gives an expression, so the value is what the controller would have made of it
		assertEquals(-expected, bake(Property.ROTATION, 0, "12.5-9", 0).get(), 1e-9);
		assertEquals(-expected, bake(Property.ROTATION, 1, "12.5-9", 0).get(), 1e-9);
		// a literal is unchanged by this
		assertEquals(Math.toRadians(3.5), bake(Property.ROTATION, 2, null, 3.5f).get(), 1e-6);
	}

	@Test
	@DisplayName("position and scale expressions that are constants are left as they are, and one that changes stays an expression")
	void otherPropertiesAndChangingExpressions() {
		assertEquals(3.5, bake(Property.SCALE, 1, "12.5-9", 0).get(), 1e-9);
		assertEquals(3.5, bake(Property.POSITION, 1, "12.5-9", 0).get(), 1e-9);
		assertEquals(-3.5, bake(Property.POSITION, 0, "12.5-9", 0).get(), 1e-9, "position X keeps its own sign rule");
		assertTrue(bake(Property.ROTATION, 2, "math.sin(query.anim_time * 100) * 5", 0).isMutable(), "an expression that reads a query is left for the controller");
	}
}
