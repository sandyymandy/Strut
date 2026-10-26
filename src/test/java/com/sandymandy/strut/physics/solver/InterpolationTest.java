package com.sandymandy.strut.physics.solver;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * The solvers step 60 times a second; at 180 frames a second the pose must still change on every frame, not three frames in a row the same
 */
class InterpolationTest {
	private static BakedStrutModel model() {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);
		GroupBoneDefinition pouch = new GroupBoneDefinition(root, "pouch", false, null, null, null);
		GroupBoneDefinition hair1 = new GroupBoneDefinition(root, "hair1", false, null, null, null);
		GroupBoneDefinition hair2 = new GroupBoneDefinition(hair1, "hair2", false, null, null, null);

		root.bakePivot(0, 0, 0);
		pouch.bakePivot(0, 20, 0);
		hair1.bakePivot(0, 24, 0);
		hair2.bakePivot(0, 20, 0);
		root.getChildBones().add(pouch);
		root.getChildBones().add(hair1);
		hair1.getChildBones().add(hair2);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		return new BakedStrutModel(List.of(root), properties);
	}

	private static final String CONFIG = "{\"bones\": {\"pouch\": {\"preset\": \"jiggle-soft\", \"length\": 0.2}},"
			+ " \"chains\": {\"hair\": {\"bones\": [\"hair1\", \"hair2\"], \"preset\": \"hair\", \"tipLength\": 0.25}}}";

	/** One frame's rotation of the spring bone and of the first chain bone, at a frame rate, while the entity walks and stops */
	private static float[][] run(int fps, int seconds) {
		BakedStrutModel model = model();
		PhysicsInstance instance = new PhysicsInstance(model, PhysicsConfig.parse(CONFIG, "test"));
		ModelInstance modelInstance = new ModelInstance();

		modelInstance.poseFor(model);
		modelInstance.resetPoseHistory();

		Pose animated = modelInstance.renderedPose();
		Pose pose = new Pose(animated.boneCount());
		int pouch = model.getBone("pouch").orElseThrow().getIndex();
		int hair = model.getBone("hair1").orElseThrow().getIndex();
		float[][] out = new float[fps * seconds][2];

		for (int f = 0; f < out.length; f++) {
			double t = f / (double)fps;
			// walks at 4 blocks a second for a second, then stops dead: the stop is what makes things swing
			double x = t < 1.0 ? t * 4.0 : 4.0;

			// a kick as it stops sets the springs and the chain swinging whatever the walk's own push was
			PhysicsControls controls = f == fps ? new PhysicsControls().impulse("pouch", 2, 1, 1).impulse("hair", 2, 1, 1) : new PhysicsControls();

			pose.copyFrom(animated);
			instance.update(model, animated, pose, new PhysicsInstance.Sample(x, 64, 0, 0f, t), controls);
			out[f][0] = pose.getRotX(pouch) + pose.getRotY(pouch) + pose.getRotZ(pouch);
			out[f][1] = pose.getRotX(hair) + pose.getRotY(hair) + pose.getRotZ(hair);
		}

		return out;
	}

	/** How many frames, while the value is clearly moving, repeat the previous frame's value exactly */
	private static int repeats(float[][] frames, int from, int to, int channel) {
		int repeated = 0;

		for (int f = from + 1; f < to; f++) {
			float a = frames[f - 1][channel];
			float b = frames[f][channel];

			if (b == a && Math.abs(frames[Math.min(f + 4, frames.length - 1)][channel] - a) > 1e-3f)
				repeated++;
		}

		return repeated;
	}

	@Test
	@DisplayName("at 180 frames a second the spring bone and the chain change on (nearly) every frame, not every third")
	void aFrameRateAboveTheStepRateStillMovesEveryFrame() {
		float[][] frames = run(180, 3);

		// from the stop at 1 s until the swing has largely died down
		assertTrue(repeats(frames, 180, 300, 0) <= 3, "spring repeats: " + repeats(frames, 180, 300, 0));
		assertTrue(repeats(frames, 180, 300, 1) <= 3, "chain repeats: " + repeats(frames, 180, 300, 1));
	}

	@Test
	@DisplayName("the pose at 180 frames a second follows the same path as at 60: it only lags by up to a step")
	void theSamePathAtAnyFrameRate() {
		float[][] at60 = run(60, 3);
		float[][] at180 = run(180, 3);
		float worst = 0f;
		float peak = 0f;

		for (int s = 62; s < 150; s++) {
			// a 60 fps frame s is the 180 fps frame 3s; the interpolated one shows the state up to a step earlier
			float a = at60[s][0];
			float b = at180[s * 3][0];

			peak = Math.max(peak, Math.abs(a));
			worst = Math.max(worst, Math.abs(a - b));
		}

		assertTrue(peak > 1e-3f, "it swings");
		assertTrue(worst < peak * 0.45f, "the two frame rates agree to within the step of lag: worst " + worst + " of a peak " + peak);
	}

	/**
	 * The entity as a game moves it: once a tick it jumps ahead by that tick's own speed (a little different each time, as a walking mob's is), and the
	 * frames in between show it lerped. {@code smoothing} is the kinematics filter, so the same walk can be run with and without it. Returns the spring bone's
	 * pose rotation, summed over the axes, at each frame
	 */
	private static float[] walkWithTickNoise(int fps, int seconds, float smoothing) {
		float before = Kinematics.smoothingSeconds;

		Kinematics.smoothingSeconds = smoothing;

		try {
			BakedStrutModel model = model();
			PhysicsInstance instance = new PhysicsInstance(model, PhysicsConfig.parse(CONFIG, "test"));
			ModelInstance modelInstance = new ModelInstance();

			modelInstance.poseFor(model);
			modelInstance.resetPoseHistory();

			Pose animated = modelInstance.renderedPose();
			Pose pose = new Pose(animated.boneCount());
			int pouch = model.getBone("pouch").orElseThrow().getIndex();
			Random random = new Random(7);
			double[] tickX = new double[seconds * 20 + 2];

			for (int t = 1; t < tickX.length; t++)
				tickX[t] = tickX[t - 1] + 0.15 * (1.0 + (random.nextDouble() - 0.5) * 0.4);

			float[] out = new float[fps * seconds];

			for (int f = 0; f < out.length; f++) {
				double time = f / (double)fps;
				int tick = (int)(time * 20);
				double x = tickX[tick] + (tickX[tick + 1] - tickX[tick]) * (time * 20 - tick);

				pose.copyFrom(animated);
				instance.update(model, animated, pose, new PhysicsInstance.Sample(x, 64, 0, 0f, time), new PhysicsControls());
				out[f] = pose.getRotX(pouch) + pose.getRotY(pouch) + pose.getRotZ(pouch);
			}

			return out;
		}
		finally {
			Kinematics.smoothingSeconds = before;
		}
	}

	/** The mean size of the frame-to-frame change of the slope: the jitter a signal has that a smooth swing does not */
	private static double roughness(float[] signal, int from) {
		double sum = 0;

		for (int f = from + 1; f < signal.length - 1; f++)
			sum += Math.abs(signal[f + 1] - 2 * signal[f] + signal[f - 1]);

		return sum / (signal.length - from - 2);
	}

	@Test
	@DisplayName("a mob moved once a tick at a slightly different speed each time does not make the springs jitter: the filter cuts the roughness to under a third")
	void aTickyWalkDoesNotJitter() {
		float[] raw = walkWithTickNoise(144, 8, 1e-5f);
		float[] filtered = walkWithTickNoise(144, 8, Kinematics.SMOOTHING_SECONDS);
		double rough = roughness(raw, 30);
		double smooth = roughness(filtered, 30);

		assertTrue(rough > 0, "the unfiltered walk is rough");
		assertTrue(smooth < rough / 3, "filtered " + smooth + " against unfiltered " + rough);
	}
}
