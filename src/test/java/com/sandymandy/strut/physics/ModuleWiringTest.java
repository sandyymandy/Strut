package com.sandymandy.strut.physics;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.PoseProcessors;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The physics module: it plugs into core's pose processor hook and, until the solver exists, changes nothing for any model
 */
class ModuleWiringTest {
	@AfterEach
	void unregister() {
		PoseProcessors.unregister(PhysicsPoseProcessor.INSTANCE);
	}

	private static BakedStrutModel model(Map<String, byte[]> extensions) {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);

		root.bakePivot(0, 0, 0);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		return new BakedStrutModel(List.of(root), List.of(), properties, extensions);
	}

	private static final byte[] JSON = "{\"bones\": {\"root\": {\"preset\": \"jiggle-soft\"}}}".getBytes(StandardCharsets.UTF_8);

	@Test
	void aModelAcceptsPhysicsOnlyThroughItsExtensionSection() {
		assertFalse(StrutPhysics.hasPhysicsData(model(Map.of())));
		assertFalse(StrutPhysics.hasPhysicsData(model(Map.of("other:section", new byte[] {1}))));
		assertTrue(StrutPhysics.hasPhysicsData(model(Map.of(StrutPhysics.SECTION, JSON))));
		assertFalse(PhysicsPoseProcessor.INSTANCE.appliesTo(model(Map.of())));
		assertTrue(PhysicsPoseProcessor.INSTANCE.appliesTo(model(Map.of(StrutPhysics.SECTION, JSON))), "a section with a spring bone");
		assertFalse(PhysicsPoseProcessor.INSTANCE.appliesTo(model(Map.of(StrutPhysics.SECTION, "not json".getBytes(StandardCharsets.UTF_8)))), "a section that cannot be read is ignored, not thrown");
	}

	@Test
	void withTheModuleRegisteredAModelWithoutPhysicsDataIsRenderedFromItsOwnPose() {
		PoseProcessors.register(PhysicsPoseProcessor.INSTANCE);

		ModelInstance instance = new ModelInstance();

		instance.poseFor(model(Map.of()));
		instance.resetPoseHistory();
		instance.prepareForRender(3);

		assertSame(instance.renderedPose(), instance.effectivePose(), "no physics data: no copy, no work");
	}

	@Test
	void aModelWithPhysicsDataButNoWorldEntityIsLeftAsAnimated() {
		PoseProcessors.register(PhysicsPoseProcessor.INSTANCE);

		ModelInstance instance = new ModelInstance();

		instance.poseFor(model(Map.of(StrutPhysics.SECTION, JSON)));
		instance.resetPoseHistory();
		instance.prepareForRender(3);

		assertEquals(instance.renderedPose().getRotX(0), instance.effectivePose().getRotX(0), 0f);
		assertEquals(instance.renderedPose().getPosY(0), instance.effectivePose().getPosY(0), 0f);
	}
}
