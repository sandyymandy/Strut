package com.sandymandy.strut.physics.tools;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.solver.ClothInstance;

/**
 * The in-game test tooling: the ray maths behind the cursor tool, the parts it can pick up, and above all the guard that every test scenario is valid on the
 * real test dummy model, so a scenario cannot rot (a bone renamed in the model, a chain whose bones are not each other's children, JSON that warns)
 */
class ToolAndScenarioTest {
	private static final double EPS = 1e-9;

	@Test
	void theCentreOfTheScreenIsWhereTheCameraLooks() {
		double[] d = PhysicsRay.direction(30, 20, 70, 16.0 / 9.0, 0, 0);
		double[] f = PhysicsRay.forward(30, 20);

		for (int i = 0; i < 3; i++)
			assertEquals(f[i], d[i], EPS);
	}

	@Test
	void facingSouthTheScreenRightIsWestAndUpIsUp() {
		// yaw 0 faces +Z; the right-hand edge of the screen is toward -X, the top toward +Y
		double[] right = PhysicsRay.direction(0, 0, 90, 1.0, 1, 0);
		double[] up = PhysicsRay.direction(0, 0, 90, 1.0, 0, 1);

		assertTrue(right[0] < -0.5 && Math.abs(right[1]) < EPS && right[2] > 0.5, Arrays.toString(right));
		assertTrue(up[1] > 0.5 && up[2] > 0.5 && Math.abs(up[0]) < EPS, Arrays.toString(up));

		// yaw 90 faces west (-X): the right-hand edge is now toward -Z
		double[] west = PhysicsRay.direction(90, 0, 90, 1.0, 1, 0);

		assertTrue(west[0] < -0.5 && west[2] < -0.5, Arrays.toString(west));
	}

	@Test
	void theVerticalFieldOfViewIsTheAngleFromTheTopEdgeToTheBottomEdge() {
		double[] top = PhysicsRay.direction(0, 0, 70, 2.0, 0, 1);
		double[] bottom = PhysicsRay.direction(0, 0, 70, 2.0, 0, -1);
		double angle = Math.toDegrees(Math.acos(top[0] * bottom[0] + top[1] * bottom[1] + top[2] * bottom[2]));

		assertEquals(70.0, angle, 1e-6);

		double[] left = PhysicsRay.direction(0, 0, 70, 2.0, -1, 0);
		double[] rightEdge = PhysicsRay.direction(0, 0, 70, 2.0, 1, 0);
		double horizontal = Math.toDegrees(Math.acos(left[0] * rightEdge[0] + left[1] * rightEdge[1] + left[2] * rightEdge[2]));

		assertTrue(horizontal > 70.0, "a wide screen sees more sideways than up: " + horizontal);
	}

	@Test
	void raysHitSpheresBoxesAndPlanes() {
		double[] origin = {0, 0, 0};
		double[] dir = {0, 0, 1};

		assertEquals(4.0, PhysicsRay.sphere(origin, dir, 0, 0, 5, 1), EPS);
		assertEquals(-1.0, PhysicsRay.sphere(origin, dir, 3, 0, 5, 1), EPS, "a miss");
		assertEquals(-1.0, PhysicsRay.sphere(origin, dir, 0, 0, -5, 1), EPS, "behind the ray");
		assertEquals(4.0, PhysicsRay.box(origin, dir, new double[] {-1, -1, 4}, new double[] {1, 1, 6}), EPS);
		assertEquals(-1.0, PhysicsRay.box(origin, dir, new double[] {2, -1, 4}, new double[] {3, 1, 6}), EPS);

		double[] hit = PhysicsRay.plane(new double[] {0, 10, 0}, new double[] {0, -1, 0}, new double[] {0, 2, 0}, new double[] {0, 1, 0});

		assertArrayEquals(new double[] {0, 2, 0}, hit, EPS);
		assertNull(PhysicsRay.plane(origin, new double[] {1, 0, 0}, new double[] {0, 2, 0}, new double[] {0, 1, 0}), "parallel");
		assertNull(PhysicsRay.plane(new double[] {0, 10, 0}, new double[] {0, 1, 0}, new double[] {0, 2, 0}, new double[] {0, 1, 0}), "behind");
	}

	// ---- the scenarios against the real test dummy ----------------------------------------------------------------------------------------------

	private static BakedStrutModel dummy() throws Exception {
		Path file = Path.of("src", "main", "resources", "assets", "strut", "strut", "models", "entity", "test_dummy.strutmodel");

		Assumptions.assumeTrue(Files.exists(file), "needs Strut's test dummy model next to the module");

		return BinaryModelBaker.bake(BinaryModelReader.read(Files.readAllBytes(file), "test_dummy"), "test_dummy");
	}

	@Test
	void everyScenarioIsValidOnTheRealTestDummy() throws Exception {
		BakedStrutModel model = dummy();

		assertFalse(PhysicsScenarios.all().isEmpty());

		for (PhysicsScenarios.Scenario scenario : PhysicsScenarios.all()) {
			DebugSkirt.sync(model, PhysicsScenarios.propOf(scenario));

			PhysicsConfig config = PhysicsConfig.parse(scenario.json(), scenario.id());

			assertTrue(config.warnings().isEmpty(), scenario.id() + " warns: " + config.warnings());
			assertFalse(config.isEmpty(), scenario.id() + " configures nothing");

			PhysicsInstance instance = new PhysicsInstance(model, config);

			assertTrue(instance.missingBones().isEmpty(), scenario.id() + " names something the model lacks: " + instance.missingBones());
			assertTrue(instance.problems().isEmpty(), scenario.id() + " could not be set up: " + instance.problems());
			assertFalse(scenario.steps().isEmpty(), scenario.id() + " says what to do");
			assertFalse(scenario.expect().isEmpty(), scenario.id() + " says what you should see");
		}
	}

	@Test
	void everyPhysicsSystemHasAScenario() {
		Set<String> systems = new HashSet<>();

		PhysicsScenarios.all().forEach(s -> systems.add(s.system()));

		// one scenario per system: add the next system's scenario in the change that adds the system
		for (String stage : List.of("springs", "chains", "colliders", "blocks", "other models", "spring bones collide", "self collision", "ragdoll", "cloth")) {
			assertTrue(systems.stream().anyMatch(s -> s.startsWith(stage)), "no scenario for " + stage + ": " + systems);
		}
	}

	@Test
	void theScenariosReallyMoveTheDummyAndAskedForPartsCanBePicked() throws Exception {
		BakedStrutModel model = dummy();

		for (PhysicsScenarios.Scenario scenario : PhysicsScenarios.all()) {
			DebugSkirt.sync(model, PhysicsScenarios.propOf(scenario));

			PhysicsInstance instance = new PhysicsInstance(model, PhysicsConfig.parse(scenario.json(), scenario.id()));
			ModelInstance modelInstance = new ModelInstance();

			modelInstance.poseFor(model);
			modelInstance.resetPoseHistory();

			Pose animated = modelInstance.renderedPose();
			Pose pose = new Pose(animated.boneCount());
			PhysicsControls controls = new PhysicsControls();

			// a floor where the dummy stands, for the scenarios that let it fall
			instance.world((x0, y0, z0, x1, y1, z1, sink) -> sink.box(-50, 63, -50, 50, 64, 50));
			PhysicsScenarios.start(scenario, controls);

			// the dummy is thrown about: back and forth, spinning
			for (int i = 0; i <= 240; i++) {
				double t = i / 60.0;

				pose.copyFrom(animated);
				instance.update(model, animated, pose, new PhysicsInstance.Sample(4.0 * Math.sin(t * 5), 64, 0, (float)(60 * Math.sin(t * 3)), t), controls);
			}

			boolean moved = false;

			for (int b = 0; b < pose.boneCount(); b++) {
				moved |= Math.abs(pose.getRotX(b) - animated.getRotX(b)) + Math.abs(pose.getRotY(b) - animated.getRotY(b)) + Math.abs(pose.getRotZ(b) - animated.getRotZ(b)) > 1e-3;
			}

			// cloth moves vertices, not bones
			double[] deformed = {0};

			instance.clothOutputs((mesh, offsets) -> {
				for (float o : offsets)
					deformed[0] = Math.max(deformed[0], Math.abs(o));
			});
			moved |= deformed[0] > 1e-3;

			assertTrue(moved, scenario.id() + ": shaking the dummy moved no bone, so the scenario shows nothing");

			List<PhysicsInstance.Pickable> pickables = new ArrayList<>();

			instance.pickables(pickables);

			assertFalse(pickables.isEmpty(), scenario.id() + ": nothing to pick up with the cursor");

			for (PhysicsInstance.Pickable p : pickables) {
				assertTrue(Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()), scenario.id() + " " + p.part());
				assertTrue(Math.abs(p.y() - 64) < 4, scenario.id() + " " + p.part() + " is somewhere near the dummy, y = " + p.y());
			}
		}
	}

	@Test
	void theTestSkirtDrapesOnTheRealDummyAndIsPickable() throws Exception {
		BakedStrutModel model = dummy();
		PhysicsScenarios.Scenario scenario = PhysicsScenarios.byId("cloth");

		DebugSkirt.sync(model, DebugSkirt.DRESS);

		PhysicsInstance instance = new PhysicsInstance(model, PhysicsConfig.parse(scenario.json(), "cloth"));
		ModelInstance modelInstance = new ModelInstance();

		modelInstance.poseFor(model);
		modelInstance.resetPoseHistory();

		Pose animated = modelInstance.renderedPose();
		Pose pose = new Pose(animated.boneCount());
		PhysicsControls controls = new PhysicsControls();
		double worstEdge = 0;
		double worstOffset = 0;
		long spent = 0;

		instance.world((x0, y0, z0, x1, y1, z1, sink) -> sink.box(-50, 63, -50, 50, 64, 50));

		for (int i = 0; i <= 300; i++) {
			double t = i / 60.0;

			pose.copyFrom(animated);

			long started = System.nanoTime();

			instance.update(model, animated, pose, new PhysicsInstance.Sample(3.0 * Math.sin(t * 2), 64, 0, (float)(50 * Math.sin(t)), t), controls);
			spent += System.nanoTime() - started;
			worstEdge = Math.max(worstEdge, instance.cloth("skirt").worstEdgeError());

			for (float o : instance.cloth("skirt").cornerOffsets())
				worstOffset = Math.max(worstOffset, Math.abs(o));
		}

		assertEquals(160, instance.cloth("skirt").particles(), "16 around, 10 rings, the seam welded");
		assertTrue(worstEdge < 0.35, "the skirt did not tear at 6 blocks a second: worst edge error " + worstEdge);
		assertTrue(worstOffset < 1.0 && Float.isFinite((float)worstOffset), "the skirt stayed with the body: offset " + worstOffset);
		assertTrue(spent / 301 / 1e6 < 3.0, "the skirt cost " + spent / 301 / 1e6 + " ms a frame");

		List<PhysicsInstance.Pickable> pickables = new ArrayList<>();

		instance.pickables(pickables);

		assertTrue(pickables.stream().anyMatch(p -> p.part().equals("skirt") && p.index() >= 1), "skirt dots to pick up");

		// holding a dot at a place pulls the cloth to it
		PhysicsInstance.Pickable dot = pickables.stream().filter(p -> p.part().equals("skirt")).findFirst().orElseThrow();
		double[] target = {dot.x() + 0.5, dot.y(), dot.z()};

		controls.grab("skirt", dot.index(), target[0], target[1], target[2]);

		for (int i = 301; i <= 360; i++) {
			pose.copyFrom(animated);
			instance.update(model, animated, pose, new PhysicsInstance.Sample(0, 64, 0, 0f, i / 60.0), controls);
		}

		assertEquals(target[0], instance.cloth("skirt").state.x[dot.index() - 1][0], 1e-6);
	}


	@Test
	void theTestSkirtSurvivesViolentMotionAndComesBackToRest() throws Exception {
		BakedStrutModel model = dummy();
		PhysicsScenarios.Scenario scenario = PhysicsScenarios.byId("cloth");

		DebugSkirt.sync(model, DebugSkirt.DRESS);

		PhysicsInstance instance = new PhysicsInstance(model, PhysicsConfig.parse(scenario.json(), "cloth"));
		ModelInstance modelInstance = new ModelInstance();

		modelInstance.poseFor(model);
		modelInstance.resetPoseHistory();

		Pose animated = modelInstance.renderedPose();
		Pose pose = new Pose(animated.boneCount());
		PhysicsControls controls = new PhysicsControls();

		instance.world((x0, y0, z0, x1, y1, z1, sink) -> sink.box(-50, 63, -50, 50, 64, 50));

		// thrown about at 12 blocks a second, turning 50 degrees either way: the cloth is stretched and pushed about, and must stay finite and bounded
		for (int i = 0; i <= 300; i++) {
			double t = i / 60.0;

			pose.copyFrom(animated);
			instance.update(model, animated, pose, new PhysicsInstance.Sample(3.0 * Math.sin(t * 4), 64, 0, (float)(50 * Math.sin(t * 2)), t), controls);

			for (float o : instance.cloth("skirt").cornerOffsets())
				assertTrue(Float.isFinite(o) && Math.abs(o) < 2.0f, "offset " + o + " at frame " + i);
		}

		// and then the dummy stops: the skirt hangs again, edges at their length
		for (int i = 301; i <= 600; i++) {
			pose.copyFrom(animated);
			instance.update(model, animated, pose, new PhysicsInstance.Sample(3.0 * Math.sin(5.0 * 4), 64, 0, (float)(50 * Math.sin(5.0 * 2)), i / 60.0), controls);
		}

		assertTrue(instance.cloth("skirt").worstEdgeError() < 0.15, "after coming to rest the worst edge error is " + instance.cloth("skirt").worstEdgeError());
	}

	@Test
	void chainAndSpringPartsAreBothPickableAndNamedForTheGrabApi() throws Exception {
		BakedStrutModel model = dummy();
		PhysicsInstance instance = new PhysicsInstance(model, PhysicsConfig.parse(PhysicsScenarios.byId("chains").json(), "chains"));
		PhysicsInstance springs = new PhysicsInstance(model, PhysicsConfig.parse(PhysicsScenarios.byId("springs").json(), "springs"));

		for (PhysicsInstance i : List.of(instance, springs)) {
			ModelInstance modelInstance = new ModelInstance();

			modelInstance.poseFor(model);
			modelInstance.resetPoseHistory();

			Pose animated = modelInstance.renderedPose();
			Pose pose = new Pose(animated.boneCount());

			for (int f = 0; f <= 10; f++) {
				pose.copyFrom(animated);
				i.update(model, animated, pose, new PhysicsInstance.Sample(0, 64, 0, 0f, f / 60.0), new PhysicsControls());
			}
		}

		List<PhysicsInstance.Pickable> chainParts = new ArrayList<>();
		List<PhysicsInstance.Pickable> springParts = new ArrayList<>();

		instance.pickables(chainParts);
		springs.pickables(springParts);

		assertTrue(chainParts.stream().anyMatch(p -> p.part().equals("armL") && p.index() >= 1), chainParts.toString());
		assertTrue(springParts.stream().anyMatch(p -> p.part().equals("pouchL") && p.index() == 0), springParts.toString());
	}
}
