package com.sandymandy.strut.cache.object;

import com.sandymandy.strut.loading.binary.BinaryFixtures;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BakedStrutModel#flatBones()} is what lets a bone be addressed by index instead of by
 * name - the pose storage depends on the ordering guarantee holding for both bake paths,
 * not just the one it happened to be written against
 */
class BakedStrutModelFlatteningTest {
	@Test
	@DisplayName("every bone's parent has a lower flat index than the bone itself (binary bake path)")
	void parentBeforeChild_binaryPath() {
		BakedStrutModel model = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");

		assertParentBeforeChildInvariant(model);
	}

	@Test
	@DisplayName("every bone's parent has a lower flat index than the bone itself (JSON bake path)")
	void parentBeforeChild_jsonPath() {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);
		GroupBoneDefinition child = new GroupBoneDefinition(root, "child", false, null, null, null);
		GroupBoneDefinition grandchild = new GroupBoneDefinition(child, "grandchild", false, null, null, null);
		GroupBoneDefinition sibling = new GroupBoneDefinition(root, "sibling", false, null, null, null);

		root.getChildBones().add(child);
		root.getChildBones().add(sibling);
		child.getChildBones().add(grandchild);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);
		BakedStrutModel model = new BakedStrutModel(List.of(root), properties);

		assertParentBeforeChildInvariant(model);
		assertEquals(4, model.flatBones().length, "root, child, grandchild and sibling must all be present");
	}

	@Test
	@DisplayName("flat indices are contiguous, zero-based and match each bone's own getIndex()")
	void indicesAreContiguousAndSelfConsistent() {
		BakedStrutModel model = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");

		GroupBoneDefinition[] flat = model.flatBones();
		Set<Integer> seen = new HashSet<>();

		for (int i = 0; i < flat.length; i++) {
			assertEquals(i, flat[i].getIndex(), "bone at flat position " + i + " must report that as its own index");
			assertTrue(seen.add(flat[i].getIndex()), "index " + flat[i].getIndex() + " was assigned to more than one bone");
			assertSame(flat[i], model.getFlatBone(i), "getFlatBone(i) must return the same bone as flatBones()[i]");
		}
	}

	@Test
	@DisplayName("a fresh bone not yet part of a baked model reports -1")
	void unbakedBoneHasNoIndex() {
		GroupBoneDefinition bone = new GroupBoneDefinition(null, "loose", false, null, null, null);

		assertEquals(-1, bone.getIndex(), "a bone only gets an index when the model containing it is baked");
	}

	private static void assertParentBeforeChildInvariant(BakedStrutModel model) {
		GroupBoneDefinition[] flat = model.flatBones();

		assertTrue(flat.length > 0, "fixture must actually contain bones");

		for (GroupBoneDefinition bone : flat) {
			GroupBoneDefinition parent = bone.getParent();

			if (parent == null)
				continue;

			assertTrue(parent.getIndex() < bone.getIndex(),
					"bone '" + bone.getName() + "' (index " + bone.getIndex() + ") must come after its parent '"
							+ parent.getName() + "' (index " + parent.getIndex() + ")");
		}
	}
}
