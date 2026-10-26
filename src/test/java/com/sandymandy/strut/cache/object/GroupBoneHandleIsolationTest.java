package com.sandymandy.strut.cache.object;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.loading.binary.BinaryFixtures;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The isolation gate, exercised through {@link GroupBoneHandle}
 * itself rather than through {@link com.sandymandy.strut.animation.state.Pose} directly
 * <p>
 * This is the API every renderer and animation call site actually uses - {@code bone.setRotX(...)},
 * {@code bone.setHidden(...)}, {@code bone.updateScale(...)} - so it is what has to be proven not
 * to alias across instances, not just the array storage underneath it.
 * {@code AnimationProcessorPoseSyncTest} already proves a handle's reads mirror its instance's
 * {@code Pose}; this class proves two instances' handles never share that {@code Pose} in the
 * first place, which is the concrete bug class ({@code StrutArmorRenderer.setHidden} on a bone
 * shared by every wearer) this test exists to close
 */
class GroupBoneHandleIsolationTest {
	@Test
	@DisplayName("two instances of the same model animated differently through their handles never alias")
	void animatingTwoInstancesNeverAliases() {
		BakedStrutModel model = riggedModel();
		ModelInstance first = new ModelInstance();
		ModelInstance second = new ModelInstance();

		GroupBoneHandle firstRoot = first.handlesFor(model)[0];
		GroupBoneHandle secondRoot = second.handlesFor(model)[0];

		firstRoot.updateRotation(1f, 2f, 3f);
		secondRoot.updateRotation(-1f, -2f, -3f);

		assertEquals(1f, firstRoot.getRotX(), "the first instance's own write must stick");
		assertEquals(-1f, secondRoot.getRotX(), "the second instance's own write must stick");
		assertNotEquals(firstRoot.getRotX(), secondRoot.getRotX(),
				"animating one instance's handle must never move the other's");
	}

	@Test
	@DisplayName("hiding a bone on one instance's handle does not hide it on another instance's handle")
	void hidingOneInstanceDoesNotHideAnother() {
		BakedStrutModel model = riggedModel();
		ModelInstance first = new ModelInstance();
		ModelInstance second = new ModelInstance();

		GroupBoneHandle firstRoot = first.handlesFor(model)[0];
		GroupBoneHandle secondRoot = second.handlesFor(model)[0];

		assertFalse(firstRoot.isHidden(), "fixture bone must not start hidden");
		assertFalse(secondRoot.isHidden(), "fixture bone must not start hidden");

		firstRoot.setHidden(true);

		assertTrue(firstRoot.isHidden(), "the instance that was hidden must reflect that");
		assertFalse(secondRoot.isHidden(),
				"hiding a bone on one instance must not hide the corresponding bone on another - "
						+ "this is exactly the StrutArmorRenderer.setHidden bug this test closes");
	}

	@Test
	@DisplayName("scaling a bone on one instance's handle does not scale it on another instance's handle")
	void scalingOneInstanceDoesNotScaleAnother() {
		BakedStrutModel model = riggedModel();
		ModelInstance first = new ModelInstance();
		ModelInstance second = new ModelInstance();

		GroupBoneHandle firstRoot = first.handlesFor(model)[0];
		GroupBoneHandle secondRoot = second.handlesFor(model)[0];

		firstRoot.updateScale(2f, 2f, 2f);

		assertEquals(2f, firstRoot.getScaleX());
		assertEquals(1f, secondRoot.getScaleX(), "scaling one instance's bone must leave another instance's at its default");
	}

	@Test
	@DisplayName("two instances rendering different models get independently sized, non-aliased handle trees")
	void twoDifferentModelsStayIndependent() {
		BakedStrutModel small = riggedModel();
		BakedStrutModel large = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.meshModel(), "mesh.strutmodel"), "mesh.strutmodel");

		ModelInstance smallInstance = new ModelInstance();
		ModelInstance largeInstance = new ModelInstance();

		GroupBoneHandle[] smallHandles = smallInstance.handlesFor(small);
		GroupBoneHandle[] largeHandles = largeInstance.handlesFor(large);

		assertEquals(small.flatBones().length, smallHandles.length);
		assertEquals(large.flatBones().length, largeHandles.length);

		smallHandles[0].setHidden(true);

		assertFalse(largeHandles[0].isHidden(),
				"hiding a bone belonging to one animatable's model must never affect a different animatable's model");
	}

	private static BakedStrutModel riggedModel() {
		return BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");
	}
}
