package com.sandymandy.strut.physics.world;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * A ragdoll thrown hard lands on the ground wherever it lands. The ground is a floor of one-block boxes, as the game gives it, listed only for the volume
 * asked for: a ragdoll that leaves the volume around the entity must still find the blocks under itself
 */
class RagdollWorldTest {
	private static final String JSON = "{\"ragdoll\": {\"bones\": [\"upperBody\", \"torso\", \"armR\", \"lowerArmR\", \"armL\", \"lowerArmL\", \"head\", \"legR\", \"shinR\", \"legL\", \"shinL\"],"
			+ " \"parents\": {\"legR\": \"upperBody\", \"legL\": \"upperBody\"}}}";

	/** Grass everywhere: one box per block at y 63 to 64, only for the blocks inside the volume asked for */
	private static final WorldSource FLOOR = (x0, y0, z0, x1, y1, z1, sink) -> {
		if (y0 > 64 || y1 < 63)
			return;

		for (int x = (int)Math.floor(x0); x < Math.ceil(x1); x++) {
			for (int z = (int)Math.floor(z0); z < Math.ceil(z1); z++)
				sink.box(x, 63, z, x + 1, 64, z + 1);
		}
	};

	@Test
	void aRagdollThrownFarAndHighLandsOnTheGroundAndStaysOnIt() throws Exception {
		Path file = Path.of("src", "main", "resources", "assets", "strut", "strut", "models", "entity", "test_dummy.strutmodel");

		Assumptions.assumeTrue(Files.exists(file));

		BakedStrutModel model = BinaryModelBaker.bake(BinaryModelReader.read(Files.readAllBytes(file), "test_dummy"), "test_dummy");
		PhysicsInstance instance = new PhysicsInstance(model, PhysicsConfig.parse(JSON, "test"));
		ModelInstance modelInstance = new ModelInstance();

		modelInstance.poseFor(model);
		modelInstance.resetPoseHistory();

		Pose animated = modelInstance.renderedPose();
		Pose pose = new Pose(animated.boneCount());
		PhysicsControls controls = new PhysicsControls();

		instance.entityId(1);
		instance.world(FLOOR);
		controls.ragdoll(0f);

		double lowest = Double.MAX_VALUE;
		double furthest = 0;

		for (int i = 0; i < 360; i++) {
			if (i == 20) {
				// flung up and away, every joint alike
				for (String bone : instance.ragdoll().boneNames())
					controls.ragdollHit(bone, 9f, 14f, 4f);
			}

			pose.copyFrom(animated);
			instance.update(model, animated, pose, new PhysicsInstance.Sample(0, 64, 0, 0f, i / 60.0), controls);

			if (!instance.ragdoll().solverState().initialised)
				continue;

			for (double[] p : instance.ragdoll().solverState().x) {
				lowest = Math.min(lowest, p[1]);
				furthest = Math.max(furthest, Math.hypot(p[0], p[2]));
			}
		}

		assertTrue(furthest > 8, "the throw carried it well away from the entity: " + furthest);
		assertTrue(lowest > 64 - 0.02, "a joint went into the ground: " + lowest);
	}
}
