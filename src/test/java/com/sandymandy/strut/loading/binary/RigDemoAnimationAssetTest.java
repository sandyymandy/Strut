package com.sandymandy.strut.loading.binary;

import com.sandymandy.strut.animation.Animation;
import com.sandymandy.strut.animation.keyframe.BoneAnimation;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.TargetKind;
import com.sandymandy.strut.loading.binary.raw.RawBinaryAnimation;
import com.sandymandy.strut.loading.object.BakedAnimations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shipped demo asset - written by {@code tools/make_rig_demo_animation.py} and read by this
 * Java parser - is two independent implementations of one binary layout, same as
 * {@code SkinningTest.readsGeneratedDemoAsset}. Nothing but a test keeps them in step
 */
class RigDemoAnimationAssetTest {
	@Test
	@DisplayName("reads and bakes the demo animation asset written by the Python generator")
	void readsGeneratedDemoAnimationAsset() throws IOException {
		byte[] bytes;

		try (InputStream in = RigDemoAnimationAssetTest.class.getResourceAsStream("/assets/strut/strut/animations/entity/rig_demo.strutanim")) {
			assertNotNull(in, "the rig demo animation asset is missing; run tools/make_rig_demo_animation.py");

			bytes = in.readAllBytes();
		}

		BakedAnimations baked = BinaryAnimationBaker.bake(BinaryAnimationReader.read(bytes, "rig_demo.strutanim"), "rig_demo.strutanim");
		Animation animation = baked.getAnimation("swing");

		assertNotNull(animation, "the demo asset's one clip is named \"swing\"");
		assertEquals(40d, animation.length(), 1e-3, "a 2-second clip is 40 ticks long");
		assertEquals(Animation.LoopType.LOOP, animation.loopType());
		assertEquals(1, animation.boneAnimations().length);

		BoneAnimation boneAnimation = animation.boneAnimations()[0];

		assertEquals("upper", boneAnimation.boneName());
		assertEquals(TargetKind.ARMATURE_BONE, boneAnimation.targetKind());
		assertFalse(boneAnimation.rotationKeyFrames().zKeyframes().isEmpty(), "the swing is on the Z axis");
	}

	/**
	 * Confirms the demo animation's target actually exists on the shipped demo model - the two
	 * files are authored independently and nothing else would catch one drifting from the other's
	 * bone name
	 */
	@Test
	@DisplayName("the demo animation's target bone exists on the demo model")
	void targetsABoneThatExistsOnTheDemoModel() throws IOException {
		assertTrue(demoModel().armatures().stream().anyMatch(armature -> armature.getBone("upper") != null),
				"the demo animation targets an armature bone named \"upper\", which the demo model must actually have");
	}

	/**
	 * {@code tools/make_rig_demo_animation.py} hardcodes the demo model's skeleton fingerprint as
	 * a Python-side constant, rather than computing it dynamically - which means nothing forces
	 * the two to agree if the demo model's bones (or their order) ever change. This is that force:
	 * a stale fingerprint here would make every fresh install log a spurious mismatch warning for
	 * an asset that actually matches, exactly as it briefly did while it was being built
	 */
	@Test
	@DisplayName("the demo animation's declared fingerprint matches the demo model's computed one")
	void fingerprintMatchesTheDemoModel() throws IOException {
		try (InputStream in = RigDemoAnimationAssetTest.class.getResourceAsStream("/assets/strut/strut/animations/entity/rig_demo.strutanim")) {
			assertNotNull(in);

			RawBinaryAnimation raw = BinaryAnimationReader.read(in.readAllBytes(), "rig_demo.strutanim");

			assertEquals(demoModel().skeletonFingerprint(), raw.skeletonFingerprint(),
					"if tools/make_rig_demo.py's bone names or order changed, tools/make_rig_demo_animation.py's SKELETON_FINGERPRINT constant must be regenerated to match");
		}
	}

	private static BakedStrutModel demoModel() throws IOException {
		try (InputStream in = RigDemoAnimationAssetTest.class.getResourceAsStream("/assets/strut/strut/models/entity/rig_demo.strutmodel")) {
			assertNotNull(in);

			return BinaryModelBaker.bake(BinaryModelReader.read(in, "rig_demo.strutmodel"), "rig_demo.strutmodel");
		}
	}
}
