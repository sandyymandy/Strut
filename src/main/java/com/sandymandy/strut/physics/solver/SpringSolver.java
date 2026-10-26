package com.sandymandy.strut.physics.solver;

import java.util.Arrays;
import org.joml.Vector3f;

/**
 * The spring bone solver: one damped spring per axis, stepped at a fixed time step, independent of frame rate
 * <p>
 * A bone is a mass on a lever. The entity's own motion pushes on it (an inertial force in the bone's frame, computed by {@link Kinematics}), the lever turns
 * that push into a torque, and a spring pulls the bone back to the animated pose. It writes an <em>offset</em>: a rotation and optionally a translation that
 * is added to whatever the animation says, so an animation is never overwritten and a weight of 0 gives it back exactly
 * <p>
 * The spring is solved as an XPBD constraint (position based dynamics with compliance): a stiffness that means the same at any step size and never blows up,
 * which a plain explicit spring does not. Pure and allocation-free per step, so it is tested without a game
 */
public final class SpringSolver {
	/** The fixed step in seconds */
	public static final float STEP = 1f / 60f;

	/** The most steps taken for one rendered frame; more time than this is dropped, so a hitch never causes a burst of simulation */
	public static final int MAX_STEPS = 6;

	/** One bone's simulated state: rotation offset (radians) and translation offset (model units), each with its velocity */
	public static final class State {
		public final float[] rot = new float[3];
		public final float[] rotVel = new float[3];
		public final float[] pos = new float[3];
		public final float[] posVel = new float[3];
		/** Where {@code rot} and {@code pos} were before the last step, for a frame between the last two steps (see {@link StepFrame}) */
		public final float[] prevRot = new float[3];
		public final float[] prevPos = new float[3];

		/** Remembers the offsets, called as a step begins */
		public void mark() {
			System.arraycopy(this.rot, 0, this.prevRot, 0, 3);
			System.arraycopy(this.pos, 0, this.prevPos, 0, 3);
		}

		public void reset() {
			Arrays.fill(this.prevRot, 0f);
			Arrays.fill(this.prevPos, 0f);
			Arrays.fill(this.rot, 0f);
			Arrays.fill(this.rotVel, 0f);
			Arrays.fill(this.pos, 0f);
			Arrays.fill(this.posVel, 0f);
		}

		boolean isAtRest(float epsilon) {
			for (int i = 0; i < 3; i++) {
				if (Math.abs(this.rot[i]) > epsilon || Math.abs(this.rotVel[i]) > epsilon || Math.abs(this.pos[i]) > epsilon * 16f || Math.abs(this.posVel[i]) > epsilon * 16f)
					return false;
			}

			return true;
		}
	}

	private SpringSolver() {}

	/**
	 * Advances one bone by one step
	 *
	 * @param force The push on the mass in the bone's own space, in blocks per second squared: minus the frame's acceleration, plus gravity
	 * @param scratch A vector to work in, so a step allocates nothing
	 */
	public static void step(State state, SpringParams params, float h, Vector3f force, Vector3f scratch) {
		float omega = (float)(2 * Math.PI * params.frequency());
		float k = omega * omega;
		float damping = 2f * params.dampingRatio() * omega;
		float keep = (float)Math.exp(-damping * h);
		float stiffen = 1f / (1f + h * h * k);

		if (params.rotate()) {
			// torque of a mass at (tip * length) under the force, divided by the moment of inertia (mass 1, radius length): alpha = (tip x force) / length
			scratch.set(params.tip()).cross(force).div(Math.max(1e-4f, params.length()));

			for (int i = 0; i < 3; i++) {
				float velocity = (state.rotVel[i] + scratch.get(i) * h) * keep;
				float predicted = state.rot[i] + velocity * h;
				float solved = predicted * stiffen;
				float[] limit = params.rotationLimit() == null ? null : params.rotationLimit()[i];

				if (limit != null)
					solved = Math.max(limit[0], Math.min(limit[1], solved));

				state.rotVel[i] = (solved - state.rot[i]) / h;
				state.rot[i] = solved;
			}
		}

		if (params.translate()) {
			for (int i = 0; i < 3; i++) {
				// blocks per second squared to model units per second squared
				float velocity = (state.posVel[i] + force.get(i) * 16f * h) * keep;
				float predicted = state.pos[i] + velocity * h;
				float solved = predicted * stiffen;

				if (params.translationLimit() > 0)
					solved = Math.max(-params.translationLimit(), Math.min(params.translationLimit(), solved));

				state.posVel[i] = (solved - state.pos[i]) / h;
				state.pos[i] = solved;
			}
		}
	}
}
