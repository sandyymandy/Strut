package com.sandymandy.strut.loading.binary;

import com.sandymandy.strut.animation.Animation;
import com.sandymandy.strut.animation.keyframe.BoneAnimation;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.Interpolation;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.Property;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.TargetKind;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawChannel;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawClip;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawKey;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation.RawValue;
import com.sandymandy.strut.loading.object.BakedAnimations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One keyframe with a component that is not valid Molang must not cost the whole clip
 * <p>
 * Found live: a re-exported {@code test_dummy.strutanim} held keyframes whose x, y and z were all the text
 * {@code "easeInOutSine"}, so six clips failed to bake and vanished, each with a stack trace whose message was just
 * {@code null}. The clip should survive with that component at 0, and the warning should say where it is
 */
class ExpressionResilienceTest {
	private static final String SOURCE = "resilience.strutanim";

	private static RawValue expressions(String x, String y, String z) {
		return new RawValue(new float[3], new String[] {x, y, z});
	}

	@Test
	@DisplayName("a keyframe whose components are not valid Molang bakes as zero, and the rest of the clip survives")
	void badExpressionDoesNotFailTheClip() {
		RawKey bad = new RawKey(0f, Interpolation.LINEAR, expressions("easeInOutSine", "easeInOutSine", "easeInOutSine"), null, null, new float[0], null);
		RawKey good = new RawKey(1f, Interpolation.LINEAR, RawValue.ofLiterals(0, 10, 0), null, null, new float[0], null);
		RawClip clip = new RawClip("mixed", 1f, LoopMode.LOOP, List.of(
				new RawChannel("root", TargetKind.GROUP_BONE, Property.POSITION, Interpolation.LINEAR, List.of(bad, good)),
				new RawChannel("root", TargetKind.GROUP_BONE, Property.SCALE, Interpolation.LINEAR, List.of(
						new RawKey(0f, Interpolation.LINEAR, RawValue.ofLiterals(2, 2, 2), null, null, new float[0], null)))), List.of());
		Animation animation = BinaryAnimationBaker.bake(new RawBinaryAnimation(100, 0, 0, List.of(), List.of(clip)), SOURCE).getAnimation("mixed");

		assertNotNull(animation, "the clip must survive one bad expression");

		BoneAnimation bone = animation.boneAnimations()[0];

		assertEquals(0d, bone.positionKeyFrames().xKeyframes().getFirst().endValue().get(), 1e-9, "the bad component is 0");
		assertEquals(0d, bone.positionKeyFrames().yKeyframes().getFirst().endValue().get(), 1e-9);
		assertEquals(10d, bone.positionKeyFrames().yKeyframes().get(1).endValue().get(), 1e-9, "the next, good keyframe is untouched");
		assertEquals(2d, bone.scaleKeyFrames().xKeyframes().getFirst().endValue().get(), 1e-9, "other channels of the bone are untouched");
	}

	@Test
	@DisplayName("a valid expression on a neighbouring component still compiles")
	void validExpressionsStillCompile() {
		RawKey key = new RawKey(0f, Interpolation.LINEAR, expressions("1 + 2", "not an expression !!", "4"), null, null, new float[0], null);
		RawClip clip = new RawClip("partly", 1f, LoopMode.LOOP, List.of(
				new RawChannel("root", TargetKind.GROUP_BONE, Property.SCALE, Interpolation.LINEAR, List.of(key))), List.of());
		BoneAnimation bone = BinaryAnimationBaker.bake(new RawBinaryAnimation(100, 0, 0, List.of(), List.of(clip)), SOURCE)
				.getAnimation("partly").boneAnimations()[0];

		assertEquals(3d, bone.scaleKeyFrames().xKeyframes().getFirst().endValue().get(), 1e-9, "1 + 2");
		assertEquals(0d, bone.scaleKeyFrames().yKeyframes().getFirst().endValue().get(), 1e-9, "the broken one");
		assertEquals(4d, bone.scaleKeyFrames().zKeyframes().getFirst().endValue().get(), 1e-9, "4");
	}

	@Test
	@DisplayName("every clip in the shipped test_dummy.strutanim bakes")
	void shippedTestDummyBakesEveryClip() throws IOException {
		byte[] bytes;

		try (InputStream in = ExpressionResilienceTest.class.getResourceAsStream("/assets/strut/strut/animations/entity/test_dummy.strutanim")) {
			assertNotNull(in);

			bytes = in.readAllBytes();
		}

		RawBinaryAnimation raw = BinaryAnimationReader.read(bytes, "test_dummy.strutanim");
		BakedAnimations baked = BinaryAnimationBaker.bake(raw, "test_dummy.strutanim");

		assertFalse(raw.clips().isEmpty());

		for (RawClip clip : raw.clips())
			assertNotNull(baked.getAnimation(clip.name()), "clip " + clip.name() + " was dropped");
	}
}
