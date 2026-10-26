package com.sandymandy.strut.physics;

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
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.tools.PhysicsControls;
import com.sandymandy.strut.physics.world.WorldSource;

/**
 * What physics costs a frame in the cases that made a game lag: a ragdoll thrown fast and far over a large, dense world. The bounds are loose (a test machine
 * is not the player's) and only catch a return of the kind of cost that was found: blocks read every frame and every box tested against every joint
 */
class PhysicsPerformanceTest {
	private static final String JSON = "{\"ragdoll\": {\"bones\": [\"upperBody\", \"torso\", \"armR\", \"lowerArmR\", \"armL\", \"lowerArmL\", \"head\", \"legR\", \"shinR\", \"legL\", \"shinL\"],"
			+ " \"parents\": {\"legR\": \"upperBody\", \"legL\": \"upperBody\"}}}";

	/** A floor with a wall of boxes every few blocks, so there is a lot to find in any volume */
	private static final int[] BOXES_READ = {0};
	/** Blocks looked at and reads made: what the game pays for in getBlockCollisions */
	private static final long[] SCANNED = {0, 0};
	private static final WorldSource TERRAIN = (x0, y0, z0, x1, y1, z1, sink) -> {
		SCANNED[0] += (long)(Math.ceil(x1) - Math.floor(x0)) * (long)(Math.ceil(y1) - Math.floor(y0)) * (long)(Math.ceil(z1) - Math.floor(z0));
		SCANNED[1]++;

		for (int x = (int)Math.floor(x0); x < Math.ceil(x1); x++) {
			for (int z = (int)Math.floor(z0); z < Math.ceil(z1); z++) {
				if (y0 <= 64 && y1 >= 63) {
					sink.box(x, 63, z, x + 1, 64, z + 1);
					BOXES_READ[0]++;
				}

				// pillars: a column of blocks every fifth block
				if (x % 5 == 0 && z % 5 == 0) {
					for (int y = 64; y < 70; y++) {
						if (y1 >= y && y0 <= y + 1) {
							sink.box(x, y, z, x + 1, y + 1, z + 1);
							BOXES_READ[0]++;
						}
					}
				}
			}
		}
	};

	@Test
	void aRagdollThrownFastAndFarCostsLittleAFrame() throws Exception {
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
		instance.world(TERRAIN);
		controls.ragdoll(0f);

		double total = 0;
		double worst = 0;
		int frames = 0;

		BOXES_READ[0] = 0;
		SCANNED[0] = 0;
		SCANNED[1] = 0;

		for (int i = 0; i < 480; i++) {
			if (i == 20) {
				for (String bone : instance.ragdoll().boneNames())
					controls.ragdollHit(bone, 30f, 25f, 20f);
			}

			pose.copyFrom(animated);

			long started = System.nanoTime();

			instance.update(model, animated, pose, new PhysicsInstance.Sample(0, 64, 0, 0f, i / 60.0), controls);

			double ms = (System.nanoTime() - started) / 1e6;

			// the first frames are the JVM warming up
			if (i >= 60) {
				total += ms;
				worst = Math.max(worst, ms);
				frames++;
			}
		}

		System.out.println("PERF ragdoll thrown: mean " + String.format("%.2f", total / frames) + " ms, worst " + String.format("%.2f", worst) + " ms, boxes read " + BOXES_READ[0] + ", blocks scanned a frame " + SCANNED[0] / 480 + " in " + SCANNED[1] + " reads");

		// the game looks at every block of a volume (a shape lookup each): a thrown body must not read tens of thousands a frame
		assertTrue(SCANNED[0] / 480 < 400, "blocks scanned a frame " + SCANNED[0] / 480);
		assertTrue(SCANNED[1] < 150, "reads of the world: " + SCANNED[1]);
		assertTrue(total / frames < 4.0, "a thrown ragdoll costs " + total / frames + " ms a frame");
	}
}
