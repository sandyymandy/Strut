package com.sandymandy.strut.physics.solver;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import java.util.ArrayList;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * Physics module: spring bones on a scripted walk. The gates: the same motion looks the same at any
 * frame rate, limits hold, a weight of 0 is exactly the animation, an animated rotation is added to and not overwritten, and a bone settles to rest
 */
class SpringPhysicsTest {
	private static BakedStrutModel model() {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);
		GroupBoneDefinition bone = new GroupBoneDefinition(root, "pouchL", false, null, null, null);

		root.bakePivot(0, 0, 0);
		bone.bakePivot(2, 20, 0);
		root.getChildBones().add(bone);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		return new BakedStrutModel(List.of(root), properties);
	}

	private static PhysicsConfig config(String boneJson) {
		return PhysicsConfig.parse("{\"bones\": {\"pouchL\": " + boneJson + "}}", "test");
	}

	/** The result of running an entity's scripted motion at some frame rate */
	private record Run(PhysicsInstance instance, Pose animated, Pose pose, ModelInstance modelInstance) {}

	private static Run start(PhysicsConfig config) {
		BakedStrutModel model = model();
		ModelInstance modelInstance = new ModelInstance();

		modelInstance.poseFor(model);
		modelInstance.resetPoseHistory();

		Pose animated = modelInstance.renderedPose();
		Pose pose = new Pose(animated.boneCount());

		pose.copyFrom(animated);

		return new Run(new PhysicsInstance(model, config), animated, pose, modelInstance);
	}

	private static void frame(Run run, PhysicsControls controls, double t, double z, float yaw) {
		run.pose().copyFrom(run.animated());
		run.instance().update(model(), run.animated(), run.pose(), new PhysicsInstance.Sample(0, 64, z, yaw, t), controls);
	}

	/** Forward motion: z accelerates to 6 blocks per second and back to rest over two seconds, then stays */
	private static double walk(double t) {
		double s = Math.min(t, 2.0);

		return 3 * s - (3 / Math.PI) * Math.sin(Math.PI * s) + (t > 2.0 ? 0 : 0);
	}

	private static float[] runWalk(PhysicsConfig config, int fps, double until, double sampleAt, PhysicsControls controls) {
		Run run = start(config);
		double[] atSample = {Double.NaN};
		float[] peak = {0f};
		float[] at = new float[3];

		for (int i = 0; i * (1.0 / fps) <= until + 1e-9; i++) {
			double t = i / (double)fps;

			frame(run, controls, t, walk(t), 0f);

			float r = run.instance().rotationOf("pouchL")[0];

			if (Math.abs(r) > Math.abs(peak[0]))
				peak[0] = r;

			if (Math.abs(t - sampleAt) < 0.5 / fps)
				at = run.instance().rotationOf("pouchL");
		}

		return new float[] {peak[0], at[0], at[1], at[2]};
	}

	@Test
	void accelerationForwardTurnsAHangingBoneBackward() {
		Run run = start(config("{\"preset\": \"jiggle-soft\"}"));
		PhysicsControls controls = new PhysicsControls();

		// facing south (yaw 0) and speeding up southward: the mass lags, which for a bone hanging down is a rotation about -X in model space
		for (int i = 0; i <= 30; i++) {
			double t = i / 60.0;

			frame(run, controls, t, 6.0 * t * t, 0f);
		}

		assertTrue(run.instance().rotationOf("pouchL")[0] < -0.001f, "rotX was " + run.instance().rotationOf("pouchL")[0]);
		assertTrue(run.pose().getRotX(1) < -0.001f, "and it reaches the pose");
	}

	@Test
	void theSameMotionGivesTheSameResultAtAnyFrameRate() {
		PhysicsConfig config = config("{\"preset\": \"jiggle-soft\"}");
		float[] at30 = runWalk(config, 30, 3.0, 2.4, new PhysicsControls());
		float[] at60 = runWalk(config, 60, 3.0, 2.4, new PhysicsControls());
		float[] at144 = runWalk(config, 144, 3.0, 2.4, new PhysicsControls());

		assertTrue(Math.abs(at144[0]) > 0.01f, "the walk must move the bone: peak " + at144[0]);
		assertEquals(at144[0], at60[0], Math.abs(at144[0]) * 0.12f, "peak at 60 vs 144 fps");
		assertEquals(at144[0], at30[0], Math.abs(at144[0]) * 0.20f, "peak at 30 vs 144 fps");
		assertEquals(at144[1], at60[1], Math.abs(at144[0]) * 0.12f, "rotation at 2.4 s, 60 vs 144 fps");
		assertEquals(at144[1], at30[1], Math.abs(at144[0]) * 0.20f, "rotation at 2.4 s, 30 vs 144 fps");
	}

	@Test
	void aWeightOfZeroIsExactlyTheAnimation() {
		Run run = start(config("{\"preset\": \"jiggle-soft\", \"weight\": 0}"));
		PhysicsControls controls = new PhysicsControls();

		run.animated().setRotation(1, 0.3f, -0.2f, 0.1f);

		for (int i = 0; i <= 120; i++) {
			double t = i / 60.0;

			frame(run, controls, t, walk(t), 0f);

			assertEquals(0.3f, run.pose().getRotX(1), 0f);
			assertEquals(-0.2f, run.pose().getRotY(1), 0f);
			assertEquals(0.1f, run.pose().getRotZ(1), 0f);
		}
	}

	@Test
	void anAnimatedRotationIsAddedToNotOverwritten() {
		Run run = start(config("{\"preset\": \"jiggle-soft\"}"));
		PhysicsControls controls = new PhysicsControls();

		run.animated().setRotation(1, 0.5f, 0f, 0f);

		for (int i = 0; i <= 90; i++) {
			double t = i / 60.0;

			frame(run, controls, t, 6.0 * t * t, 0f);
		}

		float offset = run.instance().rotationOf("pouchL")[0];

		assertEquals(0.5f + offset, run.pose().getRotX(1), 1e-5f, "the animation's own 0.5 rad is still there");
		assertNotEquals(0f, offset);
	}

	@Test
	void limitsAreNeverExceeded() {
		Run run = start(config("{\"preset\": \"jiggle-soft\", \"limit\": {\"x\": [-3, 3]}}"));
		PhysicsControls controls = new PhysicsControls();
		float limit = (float)Math.toRadians(3);

		for (int i = 0; i <= 240; i++) {
			double t = i / 120.0;

			// a violent motion: a hard shove every half second
			frame(run, controls, t, 40.0 * Math.sin(t * 10.0), 0f);

			float r = run.instance().rotationOf("pouchL")[0];

			assertTrue(r >= -limit - 1e-6f && r <= limit + 1e-6f, "rotX " + r + " at " + t);
		}
	}

	@Test
	void aBoneSettlesToRestOnceTheEntityStops() {
		float[] rest = runWalk(config("{\"preset\": \"jiggle-soft\"}"), 60, 6.0, 5.8, new PhysicsControls());

		assertEquals(0f, rest[1], 1e-3f, "rotation 3.8 s after the entity stopped");
	}

	@Test
	void anImpulseKicksAndDecays() {
		Run run = start(config("{\"preset\": \"jiggle-soft\"}"));
		PhysicsControls controls = new PhysicsControls();

		frame(run, controls, 0.0, 0.0, 0f);
		controls.impulse("pouchL", 0f, 0f, 3f);
		frame(run, controls, 1 / 60.0, 0.0, 0f);
		frame(run, controls, 2 / 60.0, 0.0, 0f);

		float early = Math.abs(run.instance().rotationOf("pouchL")[0]);

		assertTrue(early > 0.01f, "the kick moves it: " + early);

		for (int i = 3; i <= 300; i++)
			frame(run, controls, i / 60.0, 0.0, 0f);

		assertEquals(0f, run.instance().rotationOf("pouchL")[0], 1e-3f, "and it settles");
	}

	@Test
	void aTeleportOrABigGapRestartsTheSprings() {
		Run run = start(config("{\"preset\": \"jiggle-soft\"}"));
		PhysicsControls controls = new PhysicsControls();

		for (int i = 0; i <= 40; i++)
			frame(run, controls, i / 60.0, 6.0 * (i / 60.0) * (i / 60.0), 0f);

		assertNotEquals(0f, run.instance().rotationOf("pouchL")[0]);

		frame(run, controls, 41 / 60.0, 500.0, 0f);

		assertEquals(0f, run.instance().rotationOf("pouchL")[0], 0f, "a jump of hundreds of blocks is a teleport, not a shove");
	}

	@Test
	void pausingKeepsTheSpringsWhereTheyAre() {
		Run run = start(config("{\"preset\": \"jiggle-soft\"}"));
		PhysicsControls controls = new PhysicsControls();

		for (int i = 0; i <= 40; i++)
			frame(run, controls, i / 60.0, 6.0 * (i / 60.0) * (i / 60.0), 0f);

		float before = run.instance().rotationOf("pouchL")[0];

		run.pose().copyFrom(run.animated());
		run.instance().update(model(), run.animated(), run.pose(), null, controls);

		assertEquals(before, run.instance().rotationOf("pouchL")[0], 0f);
		assertEquals(before, run.pose().getRotX(1), 1e-6f, "and the pose still shows them");
	}

	@Test
	void turningTheEntityFlingsABoneOffTheAxisOutward() {
		// A bone 2/16 of a block off the entity's axis on a spinning entity: the centrifugal push is along the lever, so it has to move
		Run run = start(config("{\"preset\": \"jiggle-soft\", \"tip\": [0, -1, 0]}"));
		PhysicsControls controls = new PhysicsControls();
		float yaw = 0f;

		for (int i = 0; i <= 120; i++) {
			double t = i / 60.0;

			yaw += 12f;
			frame(run, controls, t, 0.0, yaw);
		}

		float[] r = run.instance().rotationOf("pouchL");

		assertTrue(Math.abs(r[0]) + Math.abs(r[1]) + Math.abs(r[2]) > 1e-5f, "a spin moves the bone");
	}

	@Test
	void theRuntimeControlsScaleAndSilenceABone() {
		PhysicsConfig config = config("{\"preset\": \"jiggle-soft\"}");
		float[] normal = runWalk(config, 60, 3.0, 2.4, new PhysicsControls());
		PhysicsControls held = new PhysicsControls().weight("pouchL", 0f);
		Run run = start(config);

		for (int i = 0; i <= 120; i++) {
			double t = i / 60.0;

			frame(run, held, t, walk(t), 0f);

			assertEquals(0f, run.pose().getRotX(1), 0f, "held still");
		}

		assertTrue(Math.abs(normal[0]) > 0.01f);

		float[] firm = runWalk(config, 60, 3.0, 2.4, new PhysicsControls().firmness(2f));

		assertTrue(Math.abs(firm[0]) < Math.abs(normal[0]), "a firmer bone moves less: " + firm[0] + " vs " + normal[0]);
	}

	@Test
	void translationMovesThePoseWithTheXFlipTheRendererApplies() {
		Run run = start(config("{\"preset\": \"soft-bone\", \"rotate\": false, \"translate\": true, \"maxTranslate\": 50}"));
		PhysicsControls controls = new PhysicsControls();

		for (int i = 0; i <= 30; i++) {
			double t = i / 60.0;

			frame(run, controls, t, 6.0 * t * t, 0f);
		}

		assertEquals(0f, run.pose().getRotX(1), 0f, "rotation off");
		assertTrue(Math.abs(run.pose().getPosZ(1)) > 1e-4f, "it moves in Z: " + run.pose().getPosZ(1));
	}

	/**
	 * a consumer mod's jiggle is a semi-implicit Euler spring at 25 steps a second with per-step stiffness 0.18 and damping 0.2. The preset
	 * that stands for it must oscillate at the same rate and die away at the same rate, or the models will feel different
	 */
	@Test
	void theJiggleSoftPresetMatchesConsumerModIntegratorInPeriodAndDecay() {
		// The consumer mod's own step: a = -d * stiffness - v * damping; v += a; d += v, at 25 Hz
		List<double[]> reference = referenceSpring(0.18, 0.2, 25, 6.0);
		// This module's solver from the same start (a small offset at rest), stepped through the same solver the game uses
		SpringParams params = new PhysicsConfigTestAccess().jiggleSoft();
		SpringSolver.State state = new SpringSolver.State();
		Vector3f force = new Vector3f();
		Vector3f scratch = new Vector3f();

		state.rot[0] = 0.01f;

		List<double[]> mine = new ArrayList<>();

		for (int i = 0; i < 180; i++) {
			SpringSolver.step(state, params, SpringSolver.STEP, force, scratch);
			mine.add(new double[] {(i + 1) * (double)SpringSolver.STEP, state.rot[0]});
		}

		double periodReference = period(reference);
		double periodMine = period(mine);

		assertEquals(periodReference, periodMine, periodReference * 0.15, "oscillation period in seconds");
		assertEquals(decay(reference), decay(mine), 0.15, "amplitude kept after one full oscillation");
	}

	/** the consumer mod's integrator, as (time, displacement) samples */
	private static List<double[]> referenceSpring(double stiffness, double damping, int hz, double seconds) {
		double d = 1.0;
		double v = 0.0;
		List<double[]> samples = new ArrayList<>();

		samples.add(new double[] {0.0, d});

		for (int i = 1; i <= (int)(seconds * hz); i++) {
			double a = -d * stiffness - v * damping;

			v += a;
			d += v;
			samples.add(new double[] {i / (double)hz, d});
		}

		return samples;
	}

	private static double period(List<double[]> samples) {
		List<Double> maxima = new ArrayList<>();

		for (int i = 1; i + 1 < samples.size(); i++) {
			if (samples.get(i)[1] > samples.get(i - 1)[1] && samples.get(i)[1] > samples.get(i + 1)[1])
				maxima.add(samples.get(i)[0]);
		}

		return maxima.size() >= 2 ? maxima.get(1) - maxima.get(0) : Double.NaN;
	}

	private static double decay(List<double[]> samples) {
		List<Double> maxima = new ArrayList<>();

		for (int i = 1; i + 1 < samples.size(); i++) {
			if (samples.get(i)[1] > samples.get(i - 1)[1] && samples.get(i)[1] > samples.get(i + 1)[1])
				maxima.add(samples.get(i)[1]);
		}

		return maxima.size() >= 2 ? maxima.get(1) / maxima.get(0) : Double.NaN;
	}

	/** Reads the parsed preset without exposing a test-only hook in the module */
	private static final class PhysicsConfigTestAccess {
		SpringParams jiggleSoft() {
			return PhysicsConfig.parse("{\"bones\": {\"b\": {\"preset\": \"jiggle-soft\"}}}", "test").bones().get("b");
		}
	}
}
