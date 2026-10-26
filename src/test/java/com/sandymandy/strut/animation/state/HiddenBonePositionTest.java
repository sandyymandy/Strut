package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.animation.override.ClientBoneOverrideStates;
import com.sandymandy.strut.animation.override.OverrideProperty;
import com.sandymandy.strut.animation.override.OverrideScope;
import com.sandymandy.strut.animation.override.OverrideValue;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import java.util.Arrays;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A bone's position is its position whether or not it is drawn: asked for before anything has drawn the model, and while the bone, or the bone it is under,
 * is hidden, it is where the pose puts it and not zero
 */
class HiddenBonePositionTest {
	private static final int ENTITY = 940_001;

	@AfterEach
	void cleanUp() {
		ClientBoneOverrideStates.remove(ENTITY);
	}

	/** root at the origin, child 8 pixels above it, grandchild 4 pixels above that */
	private static BakedStrutModel model() {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);
		GroupBoneDefinition child = new GroupBoneDefinition(root, "child", false, null, null, null);
		GroupBoneDefinition grandchild = new GroupBoneDefinition(child, "grandchild", false, null, null, null);

		root.bakePivot(0, 0, 0);
		child.bakePivot(0, 8, 0);
		grandchild.bakePivot(0, 12, 0);
		root.getChildBones().add(child);
		child.getChildBones().add(grandchild);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		return new BakedStrutModel(List.of(root), properties);
	}

	private static GroupBoneHandle handle(ModelInstance instance, BakedStrutModel model, String name) {
		return Arrays.stream(instance.handlesFor(model)).filter(h -> h.getName().equals(name)).findFirst().orElseThrow();
	}

	private static ModelInstance ready(BakedStrutModel model) {
		ModelInstance instance = new ModelInstance();

		instance.poseFor(model);
		instance.resetPoseHistory();

		return instance;
	}

	private static void hide(String bone, OverrideProperty property) {
		ClientBoneOverrideStates.forEntity(ENTITY).setLocal(bone, property, new OverrideValue.BoolValue(true), OverrideScope.PERSISTENT, 0, 1f, 0f);
	}

	@Test
	void aBonesPositionIsKnownBeforeAnythingHasDrawnTheModel() {
		BakedStrutModel model = model();
		ModelInstance instance = ready(model);

		Vector3d position = handle(instance, model, "grandchild").getModelPosition();

		assertEquals(12, position.y, 1.0e-4, "the pivot, in pixels, not zero");
	}

	@Test
	void aHiddenBoneKeepsItsPosition() {
		BakedStrutModel model = model();
		ModelInstance instance = ready(model);

		hide("child", OverrideProperty.HIDDEN);
		instance.prepareForRender(ENTITY);

		assertEquals(8, handle(instance, model, "child").getModelPosition().y, 1.0e-4);
	}

	@Test
	void aBoneUnderAHiddenParentKeepsItsPositionToo() {
		BakedStrutModel model = model();
		ModelInstance instance = ready(model);

		hide("child", OverrideProperty.CHILDREN_HIDDEN);
		instance.prepareForRender(ENTITY);

		assertEquals(12, handle(instance, model, "grandchild").getModelPosition().y, 1.0e-4);
	}
}
