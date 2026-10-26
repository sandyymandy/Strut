package com.sandymandy.strut.physics.solver;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * The ragdoll on the real test dummy: it lets go and falls onto a floor with every joint where its particle is, the muscle holds it, a hit shoves it,
 * and recovering hands it back to the animation exactly
 */
class RagdollInstanceTest {
	private static final String JSON = "{\"ragdoll\": {\"bones\": [\"upperBody\", \"torso\", \"armR\", \"lowerArmR\", \"armL\", \"lowerArmL\", \"head\", \"legR\", \"shinR\", \"legL\", \"shinL\"],"
			+ " \"parents\": {\"legR\": \"upperBody\", \"legL\": \"upperBody\"}}}";

	private static BakedStrutModel dummy() throws Exception {
		Path file = Path.of("src", "main", "resources", "assets", "strut", "strut", "models", "entity", "test_dummy.strutmodel");

		Assumptions.assumeTrue(Files.exists(file), "needs Strut's test dummy model next to the module");

		return BinaryModelBaker.bake(BinaryModelReader.read(Files.readAllBytes(file), "test_dummy"), "test_dummy");
	}

	private static final class Rig {
		final BakedStrutModel model;
		final PhysicsInstance instance;
		final Pose animated;
		final Pose pose;
		final PhysicsControls controls = new PhysicsControls();
		double seconds;

		Rig(BakedStrutModel model, String json) {
			this.model = model;
			this.instance = new PhysicsInstance(model, PhysicsConfig.parse(json, "test"));

			ModelInstance modelInstance = new ModelInstance();

			modelInstance.poseFor(model);
			modelInstance.resetPoseHistory();
			this.animated = modelInstance.renderedPose();
			this.pose = new Pose(this.animated.boneCount());
			this.instance.entityId(1);
			// a floor whose top is at y = 64, where the entity stands
			this.instance.world((x0, y0, z0, x1, y1, z1, sink) -> sink.box(-50, 63, -50, 50, 64, 50));
		}

		void run(double duration) {
			int frames = (int)Math.round(duration * 60);

			for (int i = 0; i < frames; i++) {
				this.pose.copyFrom(this.animated);
				this.instance.update(this.model, this.animated, this.pose, new PhysicsInstance.Sample(0, 64, 0, 0f, this.seconds), this.controls);
				this.seconds += 1.0 / 60.0;
			}
		}
	}

	private static float difference(Pose a, Pose b, int bone) {
		return Math.abs(a.getRotX(bone) - b.getRotX(bone)) + Math.abs(a.getRotY(bone) - b.getRotY(bone)) + Math.abs(a.getRotZ(bone) - b.getRotZ(bone))
				+ Math.abs(a.getPosX(bone) - b.getPosX(bone)) + Math.abs(a.getPosY(bone) - b.getPosY(bone)) + Math.abs(a.getPosZ(bone) - b.getPosZ(bone));
	}

	private static float total(Pose a, Pose b) {
		float sum = 0;

		for (int i = 0; i < a.boneCount(); i++)
			sum += difference(a, b, i);

		return sum;
	}

	@Test
	void theConfigNamesABodyTheDummyHasWithNoProblems() throws Exception {
		BakedStrutModel model = dummy();
		PhysicsConfig config = PhysicsConfig.parse(JSON, "test");
		List<String> problems = new ArrayList<>();
		RagdollInstance ragdoll = RagdollInstance.create(model, config.ragdoll(), problems);

		assertTrue(config.warnings().isEmpty(), config.warnings().toString());
		assertFalse(config.isEmpty());
		assertNotNull(ragdoll);
		assertEquals(List.of(), problems);
		assertEquals(11, ragdoll.boneNames().size());
	}

	@Test
	void badRagdollConfigIsReportedNotThrown() throws Exception {
		BakedStrutModel model = dummy();

		assertFalse(PhysicsConfig.parse("{\"ragdoll\": {\"bones\": [\"a\"]}}", "t").warnings().isEmpty());
		assertFalse(PhysicsConfig.parse("{\"ragdoll\": {\"bones\": [\"a\", \"b\"], \"bogus\": 1}}", "t").warnings().isEmpty());

		List<String> problems = new ArrayList<>();

		assertNull(RagdollInstance.create(model, PhysicsConfig.parse("{\"ragdoll\": {\"bones\": [\"nope\", \"nothing\"]}}", "t").ragdoll(), problems));
		assertFalse(problems.isEmpty());
	}

	@Test
	void aRagdollThatIsNotLetGoLeavesThePoseExactlyAsAnimated() throws Exception {
		Rig rig = new Rig(dummy(), JSON);

		rig.run(2.0);

		assertEquals(0f, total(rig.animated, rig.pose), 1e-6f);
		assertFalse(rig.instance.ragdoll().active());
	}

	@Test
	void aLimpBodyFallsOntoTheFloorWithEveryJointWhereItsParticleIs() throws Exception {
		Rig rig = new Rig(dummy(), JSON);

		rig.controls.ragdoll(0f);
		rig.run(4.0);

		RagdollInstance ragdoll = rig.instance.ragdoll();
		RagdollSolver.State st = ragdoll.solverState();
		double lowest = Double.MAX_VALUE;

		for (double[] p : st.x)
			lowest = Math.min(lowest, p[1]);

		assertTrue(ragdoll.active());
		assertTrue(lowest >= 64 + 0.07 - 1e-3, "a joint went through the floor: " + lowest);
		assertTrue(total(rig.animated, rig.pose) > 1f, "the pose changed");

		// the pelvis came down from about 0.7 blocks above the feet
		int upperBody = rig.model.getBone("upperBody").orElseThrow().getIndex();

		assertTrue(rig.pose.getPosY(upperBody) < rig.animated.getPosY(upperBody) - 4f, "the pelvis dropped: " + rig.pose.getPosY(upperBody));

		// every ragdoll bone's pivot, as the pose puts it, is where its particle is (the particles are measured from the entity's place at the last step)
		double[] at = rig.instance.stepPlace();
		Matrix4f m = new Matrix4f();
		List<String> names = ragdoll.boneNames();

		for (int i = 0; i < names.size(); i++) {
			GroupBoneDefinition bone = rig.model.getBone(names.get(i)).orElseThrow();

			PosePalette.computeBoneAtPivot(rig.pose, bone, m);

			Vector3f pivot = m.transformPosition(0f, 0f, 0f, new Vector3f());
			Vector3f particle = new Vector3f((float)(st.x[i][0] - at[0]), (float)(st.x[i][1] - at[1]), (float)(st.x[i][2] - at[2]));

			Kinematics.worldToModel(particle, (float)at[3]);

			assertEquals(particle.x, pivot.x, 3e-2f, names.get(i) + " x");
			assertEquals(particle.y, pivot.y, 3e-2f, names.get(i) + " y");
			assertEquals(particle.z, pivot.z, 3e-2f, names.get(i) + " z");
		}
	}

	@Test
	void theMuscleHoldsItUpAndAHitShovesIt() throws Exception {
		BakedStrutModel model = dummy();
		Rig held = new Rig(model, JSON);
		Rig hit = new Rig(model, JSON);

		held.controls.ragdoll(0.5f);
		hit.controls.ragdoll(0.5f);
		held.run(1.5);
		hit.run(1.5);

		assertEquals(total(held.animated, held.pose), total(hit.animated, hit.pose), 0.05f, "the same so far");

		hit.controls.ragdollHit("torso", 8f, 0f, 0f);
		held.run(0.1);
		hit.run(0.1);

		int torso = model.getBone("torso").orElseThrow().getIndex();

		assertTrue(difference(held.pose, hit.pose, torso) + difference(held.pose, hit.pose, model.getBone("head").orElseThrow().getIndex()) > 1e-3f, "the hit moved the body");

		// half a muscle still keeps the body far closer to the animation than none
		Rig limp = new Rig(model, JSON);

		limp.controls.ragdoll(0f);
		limp.run(1.6);
		assertTrue(total(held.animated, held.pose) < total(limp.animated, limp.pose), "muscle: " + total(held.animated, held.pose) + " against limp " + total(limp.animated, limp.pose));
	}

	@Test
	void recoveringHandsTheBodyBackToTheAnimationExactly() throws Exception {
		Rig rig = new Rig(dummy(), JSON);

		rig.controls.ragdoll(0f);
		rig.run(2.0);
		assertTrue(total(rig.animated, rig.pose) > 1f);

		rig.controls.ragdollRecover(1.0f);
		rig.run(3.0);

		assertFalse(rig.instance.ragdoll().active(), "the ragdoll has finished");
		assertEquals(0f, total(rig.animated, rig.pose), 1e-5f, "and the pose is the animation again");
	}

	@Test
	void aGrabbedParticleIsHeldWhereItIsPut() throws Exception {
		Rig rig = new Rig(dummy(), JSON);

		rig.controls.ragdoll(0f);
		rig.run(1.0);

		int head = rig.instance.ragdoll().boneNames().indexOf("head");

		rig.controls.grab("ragdoll", head + 1, 1.0, 66.0, 0.5);
		rig.run(1.0);

		double[] p = rig.instance.ragdoll().solverState().x[head];

		assertEquals(1.0, p[0], 1e-6);
		assertEquals(66.0, p[1], 1e-6);
		assertEquals(0.5, p[2], 1e-6);

		List<PhysicsInstance.Pickable> parts = new ArrayList<>();

		rig.instance.pickables(parts);
		assertTrue(parts.stream().anyMatch(x -> x.part().equals("ragdoll") && x.index() == head + 1));
	}
}
