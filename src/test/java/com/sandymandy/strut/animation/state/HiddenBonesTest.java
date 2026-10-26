package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.binary.BinaryFixtures;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The bones a model never draws are hidden in the pose it is drawn with, and the pose it keeps is left as it was */
class HiddenBonesTest {
	@Test
	void theNamedBonesAreHiddenInACopyAndTheRestIsAsItWas() {
		BakedStrutModel model = BinaryModelBaker.bake(BinaryModelReader.read(BinaryFixtures.cubeModel(), "m.strutmodel"), "m.strutmodel");
		ModelInstance instance = new ModelInstance();

		instance.poseFor(model);

		String name = model.flatBones()[0].getName();
		int index = model.flatBones()[0].getIndex();
		Pose before = instance.effectivePose();

		assertFalse(before.isHidden(index));
		assertSame(before, instance.effectivePoseWithHidden(model, List.of()), "nothing to hide: the pose itself");

		Pose drawn = instance.effectivePoseWithHidden(model, List.of(name, "no such bone"));

		assertTrue(drawn.isHidden(index));
		assertTrue(drawn.isChildrenHidden(index));
		assertFalse(instance.effectivePose().isHidden(index), "the kept pose is not changed");
	}

	/** root, child under it, grandchild under that: what a bed's halves are, a group with geometry on bones under it */
	private static BakedStrutModel tree() {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);
		GroupBoneDefinition child = new GroupBoneDefinition(root, "child", false, null, null, null);
		GroupBoneDefinition grandchild = new GroupBoneDefinition(child, "grandchild", false, null, null, null);

		root.bakePivot(0, 0, 0);
		child.bakePivot(0, 8, 0);
		grandchild.bakePivot(0, 12, 0);
		root.getChildBones().add(child);
		child.getChildBones().add(grandchild);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null,
				null, null, "test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		return new BakedStrutModel(List.of(root), properties);
	}

	@Test
	void anItemHidingABoneWithItsChildrenHidesEveryBoneUnderIt() {
		BakedStrutModel model = tree();
		ModelInstance instance = new ModelInstance();

		instance.poseFor(model);
		instance.resetPoseHistory();

		// what an item's setCustomAnimations does: it is never prepared for render (it has no overrides), and the GPU path reads each bone's own bit only
		Arrays.stream(instance.handlesFor(model)).filter(h -> h.getName().equals("child")).findFirst().orElseThrow().setHidden(true);
		instance.interpolateRenderedPose(model, 1f);

		Pose drawn = instance.effectivePose();

		assertFalse(drawn.isHidden(model.getBone("root").orElseThrow().getIndex()));
		assertTrue(drawn.isHidden(model.getBone("child").orElseThrow().getIndex()));
		assertTrue(drawn.isHidden(model.getBone("grandchild").orElseThrow().getIndex()), "what is under a hidden bone is hidden");

		// and the same through the list of bones a model never draws
		ModelInstance other = new ModelInstance();

		other.poseFor(model);
		other.resetPoseHistory();

		Pose listed = other.effectivePoseWithHidden(model, List.of("child"));

		assertTrue(listed.isHidden(model.getBone("grandchild").orElseThrow().getIndex()));
	}

	@Test
	void aModelWithMoreBonesRebindsEveryPoseAndWhatIsMadeFromIt() {
		BakedStrutModel small = tree();
		BakedStrutModel big = BinaryModelBaker.bake(BinaryModelReader.read(BinaryFixtures.cubeModel(), "m.strutmodel"), "m.strutmodel");
		ModelInstance instance = new ModelInstance();

		instance.poseFor(small);
		instance.resetPoseHistory();
		Arrays.stream(instance.handlesFor(small)).filter(h -> h.getName().equals("child")).findFirst().orElseThrow().setHidden(true);
		instance.interpolateRenderedPose(small, 1f);
		instance.effectivePoseWithHidden(small, List.of("root"));
		instance.effectivePose();

		// the model is swapped (a body's variant): the next draw binds the instance to it first
		instance.poseFor(big);

		assertEquals(big.flatBones().length, instance.effectivePose().boneCount());
		assertEquals(big.flatBones().length, instance.renderedPose().boneCount());
		assertEquals(big.flatBones().length, instance.effectivePoseWithHidden(big, List.of(big.flatBones()[0].getName())).boneCount());
	}
}
