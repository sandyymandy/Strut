package com.sandymandy.strut.physics;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.config.PhysicsSettings;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * Quality levels and the frame budget. A lower level relaxes less and keeps less; the budget holds the
 * instances after an expensive frame to one step
 */
class QualityTest {
	@AfterEach
	void back() {
		PhysicsOptions.quality(PhysicsOptions.Quality.MEDIUM);
	}

	@Test
	void theLevelsScaleTheRelaxationPassesAndTheCaps() {
		PhysicsOptions.quality(PhysicsOptions.Quality.MEDIUM);
		assertEquals(4, PhysicsOptions.iterations(4), "medium is what the module did before quality levels");
		assertEquals(12, PhysicsOptions.iterations(12));

		PhysicsOptions.quality(PhysicsOptions.Quality.LOW);
		assertEquals(2, PhysicsOptions.iterations(4));
		assertEquals(6, PhysicsOptions.iterations(12));
		assertEquals(3, PhysicsOptions.maxSteps());
		assertTrue(PhysicsOptions.maxWorldBoxes() < 768);
		assertTrue(PhysicsOptions.effectiveRange() < PhysicsOptions.distanceLimit());

		PhysicsOptions.quality(PhysicsOptions.Quality.HIGH);
		assertEquals(6, PhysicsOptions.iterations(4));
		assertEquals(1024, PhysicsOptions.maxWorldBoxes());
	}

	@Test
	void theLevelIsSavedAndRead() {
		PhysicsOptions.quality(PhysicsOptions.Quality.LOW);

		String json = PhysicsSettings.toJson();

		assertTrue(json.contains("\"quality\": \"low\""), json);

		PhysicsOptions.quality(PhysicsOptions.Quality.HIGH);
		assertTrue(PhysicsSettings.apply(json));
		assertEquals(PhysicsOptions.Quality.LOW, PhysicsOptions.quality());
		assertTrue(PhysicsSettings.apply("{\"quality\": \"nonsense\"}"), "a bad value costs that option only");
		assertEquals(PhysicsOptions.Quality.LOW, PhysicsOptions.quality());
	}

	@Test
	void anInstanceHeldByTheBudgetTakesOneStepAndOneNotHeldCatchesUp() {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);

		root.bakePivot(0, 0, 0);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);
		BakedStrutModel model = new BakedStrutModel(List.of(root), properties);
		PhysicsConfig config = PhysicsConfig.parse("{\"bones\": {\"root\": {\"preset\": \"jiggle-soft\"}}}", "test");
		int[] steps = new int[2];

		for (int held = 0; held < 2; held++) {
			PhysicsInstance instance = new PhysicsInstance(model, config);
			ModelInstance modelInstance = new ModelInstance();

			modelInstance.poseFor(model);
			modelInstance.resetPoseHistory();

			Pose animated = modelInstance.renderedPose();
			Pose pose = new Pose(animated.boneCount());

			instance.stepLimit = held == 1 ? 1 : Integer.MAX_VALUE;
			pose.copyFrom(animated);
			instance.update(model, animated, pose, new PhysicsInstance.Sample(0, 64, 0, 0f, 0.0), new PhysicsControls());
			pose.copyFrom(animated);
			// a slow frame: 0.11 s is six steps
			instance.update(model, animated, pose, new PhysicsInstance.Sample(0, 64, 0, 0f, 0.11), new PhysicsControls());
			steps[held] = instance.lastSteps;
		}

		assertEquals(6, steps[0]);
		assertEquals(1, steps[1], "held to one step");
	}
}
