package com.sandymandy.strut.physics.solver;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.animation.state.PosePalette;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * Physics module: chains, colliders on bones and self collision. The gates: segment lengths hold, the
 * chain lags a moving entity, a bend limit holds, no particle ends a step inside a collider even when the entity is thrown at 20 blocks a second, and above
 * all that the rotations written to the bones put every bone's pivot where the solver put its particle
 */
class ChainPhysicsTest {
	/** root (the body) with hair1 - hair2 - hair3 hanging one below the other, 4 model units apart, and a side bone */
	private static BakedStrutModel model() {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);
		GroupBoneDefinition hair1 = new GroupBoneDefinition(root, "hair1", false, null, null, null);
		GroupBoneDefinition hair2 = new GroupBoneDefinition(hair1, "hair2", false, null, null, null);
		GroupBoneDefinition hair3 = new GroupBoneDefinition(hair2, "hair3", false, null, null, null);
		GroupBoneDefinition side = new GroupBoneDefinition(root, "side", false, null, null, null);

		root.bakePivot(0, 0, 0);
		hair1.bakePivot(0, 24, 0);
		hair2.bakePivot(0, 20, 0);
		hair3.bakePivot(0, 16, 0);
		side.bakePivot(6, 12, 0);
		root.getChildBones().add(hair1);
		root.getChildBones().add(side);
		hair1.getChildBones().add(hair2);
		hair2.getChildBones().add(hair3);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		return new BakedStrutModel(List.of(root), properties);
	}

	private static final String HAIR = "\"chains\": {\"ponytail\": {\"bones\": [\"hair1\", \"hair2\", \"hair3\"], \"preset\": \"hair\", \"tipLength\": 0.25%s}}";

	private record Run(BakedStrutModel model, PhysicsInstance instance, Pose animated, Pose pose) {}

	private static Run start(String json) {
		BakedStrutModel model = model();
		ModelInstance modelInstance = new ModelInstance();

		modelInstance.poseFor(model);
		modelInstance.resetPoseHistory();

		Pose animated = modelInstance.renderedPose();
		Pose pose = new Pose(animated.boneCount());

		pose.copyFrom(animated);

		return new Run(model, new PhysicsInstance(model, PhysicsConfig.parse(json, "test")), animated, pose);
	}

	private static String chainJson(String extra, String colliders) {
		return "{" + String.format(HAIR, extra) + (colliders.isEmpty() ? "" : ", \"colliders\": [" + colliders + "]") + "}";
	}

	private static void frame(Run run, PhysicsControls controls, double t, double x, double z, float yaw) {
		run.pose().copyFrom(run.animated());
		run.instance().update(run.model(), run.animated(), run.pose(), new PhysicsInstance.Sample(x, 64, z, yaw, t), controls);
	}

	@Test
	void aChainAtRestHangsWhereTheAnimationPutsIt() {
		Run run = start(chainJson("", ""));
		PhysicsControls controls = new PhysicsControls();

		for (int i = 0; i <= 240; i++)
			frame(run, controls, i / 60.0, 0, 0, 0f);

		for (String bone : List.of("hair1", "hair2", "hair3")) {
			int index = run.model().getBone(bone).orElseThrow().getIndex();

			assertEquals(0f, run.pose().getRotX(index), 0.12f, bone + " rotX");
			assertEquals(0f, run.pose().getRotZ(index), 0.12f, bone + " rotZ");
		}

		assertTrue(run.instance().chain("ponytail").worstLengthError() < 0.01);
	}

	@Test
	void theRotationsWrittenToTheBonesPutEveryPivotWhereTheSolverPutItsParticle() {
		Run run = start(chainJson(", \"gravity\": 9.8", ""));
		PhysicsControls controls = new PhysicsControls();
		double x = 0;
		double z = 0;
		double vz = 0;

		// a swerving walk so the chain hangs at an angle in several directions
		for (int i = 0; i <= 150; i++) {
			double t = i / 60.0;

			vz = 4.0 * Math.sin(t * 5.0);
			z += vz / 60.0;
			x += 2.0 * Math.cos(t * 3.0) / 60.0;
			frame(run, controls, t, x, z, 25f * (float)Math.sin(t * 2));
		}

		ChainInstance chain = run.instance().chain("ponytail");
		Matrix4f m = new Matrix4f();
		// the bones are turned to where the particles are relative to the entity's place at the last step
		double[] at = run.instance().stepPlace();
		float yaw = (float)at[3];

		assertTrue(Math.abs(chain.state.x[1][2] - chain.state.x[0][2]) + Math.abs(chain.state.x[1][0] - chain.state.x[0][0]) > 1e-4, "the chain has actually swung");

		for (int k = 1; k < 3; k++) {
			GroupBoneDefinition def = chain.bones[k];

			PosePalette.computeBoneAtPivot(run.pose(), def, m);

			Vector3f pivot = m.transformPosition(0f, 0f, 0f, new Vector3f());
			Vector3f particle = new Vector3f((float)(chain.state.x[k][0] - at[0]), (float)(chain.state.x[k][1] - at[1]), (float)(chain.state.x[k][2] - at[2]));

			Kinematics.worldToModel(particle, yaw);

			assertEquals(particle.x, pivot.x, 2e-3f, "bone " + k + " pivot x");
			assertEquals(particle.y, pivot.y, 2e-3f, "bone " + k + " pivot y");
			assertEquals(particle.z, pivot.z, 2e-3f, "bone " + k + " pivot z");
		}
	}

	@Test
	void segmentLengthsHoldUnderViolentMotion() {
		Run run = start(chainJson("", ""));
		PhysicsControls controls = new PhysicsControls();
		double worst = 0;

		for (int i = 0; i <= 300; i++) {
			double t = i / 60.0;

			frame(run, controls, t, 6.0 * Math.sin(t * 9), 6.0 * Math.cos(t * 7), (float)(90 * Math.sin(t * 4)));
			worst = Math.max(worst, run.instance().chain("ponytail").worstLengthError());
		}

		assertTrue(worst < 0.02, "worst relative segment length error " + worst);
	}

	@Test
	void theChainLagsBehindAnEntityThatAccelerates() {
		Run run = start(chainJson("", ""));
		PhysicsControls controls = new PhysicsControls();
		double z = 0;

		// facing south (yaw 0), speeding up south: the tip stays behind, to the north, so its world z is lower than the animated tip's
		for (int i = 0; i <= 45; i++) {
			double t = i / 60.0;

			z = 8.0 * t * t;
			frame(run, controls, t, 0, z, 0f);
		}

		ChainInstance chain = run.instance().chain("ponytail");
		float rootToTipZ = (float)(chain.state.x[3][2] - chain.state.x[0][2]);

		assertTrue(rootToTipZ < -0.005f, "the tip trails the root: " + rootToTipZ);
	}

	@Test
	void aBendLimitHolds() {
		Run run = start(chainJson(", \"maxAngle\": 15, \"frequency\": 0.5", ""));
		PhysicsControls controls = new PhysicsControls();
		double limit = Math.toRadians(15) + 0.02;
		double worst = 0;

		for (int i = 0; i <= 300; i++) {
			double t = i / 60.0;

			frame(run, controls, t, 8.0 * Math.sin(t * 6), 0, 0f);

			ChainInstance chain = run.instance().chain("ponytail");

			if (!chain.state.initialised)
				continue;

			// the animated chain hangs straight down
			for (int k = 0; k < 3; k++) {
				double dx = chain.state.x[k + 1][0] - chain.state.x[k][0];
				double dy = chain.state.x[k + 1][1] - chain.state.x[k][1];
				double dz = chain.state.x[k + 1][2] - chain.state.x[k][2];
				double angle = Math.acos(Math.max(-1, Math.min(1, -dy / Math.sqrt(dx * dx + dy * dy + dz * dz))));

				worst = Math.max(worst, angle);
			}
		}

		assertTrue(worst <= limit, "worst bend " + Math.toDegrees(worst) + " deg");
		assertTrue(worst > Math.toRadians(5), "the motion did bend it: " + Math.toDegrees(worst));
	}

	@Test
	void aWeightOfZeroIsExactlyTheAnimation() {
		Run run = start(chainJson(", \"weight\": 0", ""));
		PhysicsControls controls = new PhysicsControls();

		run.animated().setRotation(run.model().getBone("hair2").orElseThrow().getIndex(), 0.2f, 0.1f, -0.3f);

		for (int i = 0; i <= 120; i++) {
			frame(run, controls, i / 60.0, 5.0 * Math.sin(i / 10.0), 0, 0f);

			int index = run.model().getBone("hair2").orElseThrow().getIndex();

			assertEquals(0.2f, run.pose().getRotX(index), 0f);
			assertEquals(-0.3f, run.pose().getRotZ(index), 0f);
		}
	}

	@Test
	void noParticleEndsAStepInsideACollider() {
		// two spheres a chain swinging sideways would run into, on the root bone either side of it (5 units out, radius 3 units)
		Run run = start(chainJson(", \"frequency\": 1", "{\"bone\": \"root\", \"shape\": \"sphere\", \"center\": [5, 20, 0], \"radius\": 3}, {\"bone\": \"root\", \"shape\": \"sphere\", \"center\": [-5, 20, 0], \"radius\": 3}"));
		PhysicsControls controls = new PhysicsControls();
		double worst = Double.MAX_VALUE;

		for (int i = 0; i <= 300; i++) {
			double t = i / 60.0;
			double x = 3.0 * Math.sin(t * 5);

			frame(run, controls, t, x, 0, 0f);

			ChainInstance chain = run.instance().chain("ponytail");
			// the particles are solved where the entity was at the last step, which is a little behind the frame
			double[] at = run.instance().stepPlace();

			if (at == null)
				continue;

			// the spheres sit on the root: the entity's world position plus the model's offset, which yaw 0 turns half way round
			for (double side : new double[] {5 / 16.0, -5 / 16.0}) {
				for (int k = 1; k < 4; k++) {
					double dx = chain.state.x[k][0] - (at[0] + side);
					double dy = chain.state.x[k][1] - (at[1] + 20 / 16.0);
					double dz = chain.state.x[k][2] - at[2];
					double clearance = Math.sqrt(dx * dx + dy * dy + dz * dz) - (3 / 16.0 + 0.05);

					worst = Math.min(worst, clearance);
				}
			}
		}

		assertTrue(worst > -0.005, "deepest a particle got into the collider: " + worst + " blocks");
	}

	@Test
	void noTunnellingWhenTheEntityIsThrownAtTwentyBlocksASecond() {
		Run run = start(chainJson(", \"frequency\": 1", "{\"bone\": \"root\", \"shape\": \"capsule\", \"from\": [5, 10, 0], \"to\": [5, 28, 0], \"radius\": 3}, {\"bone\": \"root\", \"shape\": \"capsule\", \"from\": [-5, 10, 0], \"to\": [-5, 28, 0], \"radius\": 3}"));
		PhysicsControls controls = new PhysicsControls();
		double worst = Double.MAX_VALUE;
		double z = 0;

		for (int i = 0; i <= 240; i++) {
			double t = i / 60.0;

			// 20 blocks a second one way, then the other, with an instant reversal
			z += (i < 120 ? 20.0 : -20.0) / 60.0;
			frame(run, controls, t, 0, z, 0f);

			ChainInstance chain = run.instance().chain("ponytail");

			for (double side : new double[] {5 / 16.0, -5 / 16.0}) {
				for (int k = 1; k < 4; k++) {
					double dx = chain.state.x[k][0] - side;
					double dz = chain.state.x[k][2] - z;
					double dy = chain.state.x[k][1] - 64;
					// distance to the capsule axis (x = side, z = 0 in the model's space, y from 10 to 28 units)
					double y = Math.max(10 / 16.0, Math.min(28 / 16.0, dy));
					double clearance = Math.sqrt(dx * dx + dz * dz + (dy - y) * (dy - y)) - (3 / 16.0 + 0.05);

					worst = Math.min(worst, clearance);
				}
			}
		}

		assertTrue(worst > -0.01, "deepest a particle got into the capsule: " + worst + " blocks");
	}

	@Test
	void aChainOfBonesThatAreNotEachOthersChildrenIsRefusedWithAReason() {
		BakedStrutModel model = model();
		PhysicsConfig config = PhysicsConfig.parse("{\"chains\": {\"bad\": {\"bones\": [\"hair1\", \"side\"]}}}", "test");
		PhysicsInstance instance = new PhysicsInstance(model, config);

		assertTrue(instance.missingBones().stream().anyMatch(m -> m.contains("not a child")), instance.missingBones().toString());
		assertThrows(IllegalArgumentException.class, () -> instance.chain("bad"));
	}

	@Test
	void anImpulseKicksAChainAndItSettles() {
		Run run = start(chainJson("", ""));
		PhysicsControls controls = new PhysicsControls();

		for (int i = 0; i <= 10; i++)
			frame(run, controls, i / 60.0, 0, 0, 0f);

		controls.impulse("ponytail", 3f, 0f, 0f);
		frame(run, controls, 11 / 60.0, 0, 0, 0f);
		frame(run, controls, 13 / 60.0, 0, 0, 0f);

		ChainInstance chain = run.instance().chain("ponytail");

		assertTrue(Math.abs(chain.state.x[3][0] - chain.state.x[0][0]) > 0.005, "kicked sideways: " + (chain.state.x[3][0] - chain.state.x[0][0]));

		for (int i = 14; i <= 400; i++)
			frame(run, controls, i / 60.0, 0, 0, 0f);

		assertEquals(0.0, chain.state.x[3][0] - chain.state.x[0][0], 0.02);
	}

	@Test
	void collidersPushBallsOutOfSpheresCapsulesAndBoxes() {
		Collider.Placed sphere = new Collider.Placed();

		sphere.shape = Collider.Shape.SPHERE;
		sphere.radius = 1;

		double[] p = {0.5, 0, 0};

		assertTrue(Collider.pushOut(sphere, p, 0.25));
		assertEquals(1.25, p[0], 1e-9);
		assertFalse(Collider.pushOut(sphere, new double[] {2, 0, 0}, 0.25), "outside is untouched");

		Collider.Placed capsule = new Collider.Placed();

		capsule.shape = Collider.Shape.CAPSULE;
		capsule.radius = 0.5;
		capsule.p[1] = 0;
		capsule.q[1] = 4;

		double[] c = {0.1, 2, 0};

		assertTrue(Collider.pushOut(capsule, c, 0.25));
		assertEquals(0.75, c[0], 1e-9);
		assertEquals(2.0, c[1], 1e-9, "pushed along the shortest way, sideways from the axis");

		Collider.Placed box = new Collider.Placed();

		box.shape = Collider.Shape.BOX;
		box.half[0] = 1;
		box.half[1] = 1;
		box.half[2] = 1;
		new Matrix3f().identity().get(0, 0);
		box.axes.identity();

		double[] inside = {0.9, 0.2, 0.1};

		assertTrue(Collider.pushOut(box, inside, 0.25));
		assertEquals(1.25, inside[0], 1e-9, "leaves through the nearest face");

		double[] corner = {1.1, 1.1, 0};

		assertTrue(Collider.pushOut(box, corner, 0.5));

		double dist = Math.sqrt(Math.pow(corner[0] - 1, 2) + Math.pow(corner[1] - 1, 2));

		assertEquals(0.5, dist, 1e-9, "a ball near an edge is held off it by its radius");

		List<double[]> unused = new ArrayList<>();

		assertTrue(unused.isEmpty());
	}

	@Test
	void chainAndColliderJsonParsesAndWarnsInsteadOfThrowing() {
		PhysicsConfig config = PhysicsConfig.parse("{\"chains\": {\"c\": {\"bones\": [\"a\", \"b\"], \"preset\": \"tail\", \"maxAngle\": 30, \"collidesWith\": [\"body\", \"hair\"]}, \"empty\": {}},"
				+ " \"colliders\": [{\"bone\": \"head\", \"shape\": \"sphere\", \"center\": [0, 8, 0], \"radius\": 5}, {\"bone\": \"x\", \"shape\": \"cone\"}, {\"bone\": \"torso\", \"shape\": \"box\", \"center\": [0,5,0], \"size\": [8,10,4], \"layer\": \"cloth\"}]}", "t");
		ChainParams c = config.chains().get("c");

		assertEquals(List.of("a", "b"), c.bones());
		assertEquals(1.6f, c.frequency(), 0f, "the tail preset");
		assertEquals(Math.toRadians(30), c.maxAngle(), 1e-6, "the explicit key over the preset's 70");
		assertTrue(c.collidesWith().contains("hair"));
		assertEquals(2, config.colliders().size(), "the unknown shape is skipped");
		assertEquals("cloth", config.colliders().get(1).layer());

		String warnings = String.join("\n", config.warnings());

		assertTrue(warnings.contains("needs a \"bones\" list"), warnings);
		assertTrue(warnings.contains("unknown shape"), warnings);
	}

	@Test
	void uneventFramePacingDoesNotJoltAChainThatIsCarriedAtAConstantSpeed() {
		Run run = start(chainJson(", \"gravity\": 9.8", ""));
		PhysicsControls controls = new PhysicsControls();
		double t = 0;
		double z = 0;
		double lowest = Double.MAX_VALUE;
		double highest = -Double.MAX_VALUE;

		// 3 blocks a second in a straight line, with frames 1/144 and 1/40 of a second apart in an irregular pattern, so steps and frames never line up
		for (int i = 0; i < 900; i++) {
			double dt = (i % 3 == 0 ? 1 / 40.0 : 1 / 144.0) * (1 + 0.15 * Math.sin(i));

			t += dt;
			z += 3.0 * dt;
			frame(run, controls, t, 0, z, 0f);

			double[] at = run.instance().stepPlace();

			if (t > 4.0 && at != null) {
				// how far the tip trails the entity's place at the step: at a constant speed this settles to one value and must stay there
				double lag = run.instance().chain("ponytail").state.x[3][2] - at[2];

				lowest = Math.min(lowest, lag);
				highest = Math.max(highest, lag);
			}
		}

		assertTrue(highest - lowest < 0.004, "the tip's lag wandered by " + (highest - lowest) + " blocks at a constant speed");
	}
}
