package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.binary.BinaryFixtures;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves {@link Pose} storage itself is sound - one buffer per instance, no aliasing, correct
 * rebinding on a model swap - the same guarantees {@link SkinningIsolationTest} already proves
 * for {@link SkinningState}. This is also the actual pose
 * every {@link com.sandymandy.strut.cache.object.GroupBoneHandle} reads and writes through - see
 * {@code AnimationProcessorPoseSyncTest} for that wiring proven end to end
 */
class ModelInstanceIsolationTest {
	private BakedStrutModel model;
	private GroupBoneDefinition[] flatBones;

	@BeforeEach
	void bakeSharedFixture() {
		this.model = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");
		this.flatBones = this.model.flatBones();

		assertTrue(this.flatBones.length > 0, "fixture must actually contain bones");
	}

	@Test
	@DisplayName("two instances of one model do not share a Pose")
	void poseDoesNotAlias() {
		ModelInstance first = new ModelInstance();
		ModelInstance second = new ModelInstance();

		assertNotSame(first.poseFor(this.model), second.poseFor(this.model),
				"each instance must own its Pose");
	}

	@Test
	@DisplayName("writing one instance's Pose leaves another instance's Pose, and the shared bones, untouched")
	void writingOnePoseDoesNotDisturbAnother() {
		ModelInstance first = new ModelInstance();
		ModelInstance second = new ModelInstance();

		Pose firstPose = first.poseFor(this.model);
		Pose secondPose = second.poseFor(this.model);
		int index = 0;

		firstPose.setRotation(index, 1f, 2f, 3f);
		firstPose.setPosition(index, 4f, 5f, 6f);
		firstPose.setHidden(index, true);

		secondPose.setRotation(index, -1f, -2f, -3f);

		assertEquals(1f, firstPose.getRotX(index), "writing the second instance's pose must not touch the first's");
		assertEquals(-1f, secondPose.getRotX(index), "the second instance's own write must stick");
		assertNotEquals(firstPose.getRotX(index), secondPose.getRotX(index),
				"the two poses were written differently, so they must actually differ - "
						+ "if they match, the test is not exercising two instances");
	}

	@Test
	@DisplayName("Pose is sized to the model's flat bone count and defaults to an identity transform")
	void poseDefaultsToIdentity() {
		ModelInstance instance = new ModelInstance();
		Pose pose = instance.poseFor(this.model);

		assertEquals(this.flatBones.length, pose.boneCount(), "Pose must have one slot per flat bone");

		for (int i = 0; i < pose.boneCount(); i++) {
			assertEquals(0f, pose.getPosX(i));
			assertEquals(0f, pose.getRotX(i));
			assertEquals(1f, pose.getScaleX(i), "scale must default to 1, not 0");
			assertFalse(pose.isHidden(i));
		}
	}

	@Test
	@DisplayName("Pose survives across frames but is dropped when the model is replaced")
	void rebindingOnModelSwapDropsStalePose() {
		ModelInstance instance = new ModelInstance();
		Pose before = instance.poseFor(this.model);

		assertSame(before, instance.poseFor(this.model),
				"re-posing the same model must reuse the Pose rather than reallocate it every frame");

		BakedStrutModel reloaded = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");

		assertNotSame(before, instance.poseFor(reloaded),
				"a resource reload produces a new baked model, so the old Pose must be dropped rather than reused at the wrong size");
	}
}
