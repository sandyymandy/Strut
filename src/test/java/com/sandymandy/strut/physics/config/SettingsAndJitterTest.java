package com.sandymandy.strut.physics.config;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * The options file, and the clock jitter that used to restart the springs now and then
 */
class SettingsAndJitterTest {
	@AfterEach
	void restore() {
		PhysicsSettings.apply("{\"enabled\": true, \"worldCollision\": true, \"otherCollision\": true, \"playerCollision\": true, \"distanceLimit\": 48}");
	}

	@Test
	void theOptionsRoundTripThroughTheirJson() {
		PhysicsOptions.enabled(false);
		PhysicsOptions.otherCollision(false);
		PhysicsOptions.distanceLimit(72);

		String json = PhysicsSettings.toJson();

		PhysicsOptions.enabled(true);
		PhysicsOptions.otherCollision(true);
		PhysicsOptions.distanceLimit(48);

		assertTrue(PhysicsSettings.apply(json));
		assertFalse(PhysicsOptions.enabled());
		assertFalse(PhysicsOptions.otherCollision());
		assertTrue(PhysicsOptions.playerCollision());
		assertEquals(72.0, PhysicsOptions.distanceLimit(), 1e-9);
	}

	@Test
	void aDamagedOrPartialFileNeverThrowsAndKeepsTheRest() {
		assertFalse(PhysicsSettings.apply("not json"));
		assertFalse(PhysicsSettings.apply("[1, 2]"));

		PhysicsOptions.playerCollision(true);

		assertTrue(PhysicsSettings.apply("{\"worldCollision\": false, \"enabled\": {\"nested\": 1}, \"distanceLimit\": \"far\"}"));
		assertFalse(PhysicsOptions.worldCollision());
		assertTrue(PhysicsOptions.enabled(), "a value of the wrong type is ignored");
		assertTrue(PhysicsOptions.playerCollision(), "a key that is absent is left alone");
	}

	@Test
	void aFrameWhoseClockReadsAHairEarlierDoesNotRestartTheSprings() throws Exception {
		Path file = Path.of("src", "main", "resources", "assets", "strut", "strut", "models", "entity", "test_dummy.strutmodel");

		Assumptions.assumeTrue(Files.exists(file), "needs Strut's test dummy model next to the module");

		BakedStrutModel model = BinaryModelBaker.bake(BinaryModelReader.read(Files.readAllBytes(file), "test_dummy"), "test_dummy");
		PhysicsInstance instance = new PhysicsInstance(model, PhysicsConfig.parse("{\"bones\": {\"pouchL\": {\"preset\": \"jiggle-soft\", \"length\": 0.2}}}", "test"));
		ModelInstance modelInstance = new ModelInstance();

		modelInstance.poseFor(model);
		modelInstance.resetPoseHistory();

		Pose animated = modelInstance.renderedPose();
		Pose pose = new Pose(animated.boneCount());
		double t = 0;

		for (int i = 0; i < 20; i++) {
			pose.copyFrom(animated);
			instance.update(model, animated, pose, new PhysicsInstance.Sample(0, 64, 0, 0f, t), new PhysicsControls());
			t += 1.0 / 60.0;
		}

		PhysicsControls controls = new PhysicsControls().impulse("pouchL", 3, 0, 0);

		for (int i = 0; i < 10; i++) {
			pose.copyFrom(animated);
			instance.update(model, animated, pose, new PhysicsInstance.Sample(0, 64, 0, 0f, t), controls);
			t += 1.0 / 60.0;
		}

		float[] before = instance.rotationOf("pouchL");

		assertTrue(Math.abs(before[0]) + Math.abs(before[1]) + Math.abs(before[2]) > 1e-4f, "it is swinging");

		// the next frame reads a time 30 ms before the last one, as the tick and the partial tick do now and then
		pose.copyFrom(animated);
		instance.update(model, animated, pose, new PhysicsInstance.Sample(0, 64, 0, 0f, t - 0.03), new PhysicsControls());

		float[] after = instance.rotationOf("pouchL");

		assertTrue(Math.abs(after[0]) + Math.abs(after[1]) + Math.abs(after[2]) > 1e-4f, "the springs were reset by a small step back in time");
	}
}
