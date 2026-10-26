package com.sandymandy.strut.physics.solver;

import org.joml.Vector3f;

/**
 * Where the entity stood at the last two fixed steps, and how far between them the frame being drawn falls (0 is the earlier step, 1 the later). The solvers
 * only change at a step, 60 times a second, so a game drawing 180 frames would show every pose three times; the outputs are read at this fraction between the
 * state before the last step and the state after it, and the motion is as smooth as the frames are
 * <p>
 * Particles are measured from the entity's place at the step they were solved in, so the interpolation is done in the model's space: the earlier state against
 * the earlier place, the later against the later, and then blended
 */
public final class StepFrame {
	double x;
	double y;
	double z;
	float yaw;
	double px;
	double py;
	double pz;
	float pyaw;
	/** 1 is the later step (no blending): the value for a test, a tool, or before a second step exists */
	float alpha = 1f;
	private final Vector3f other = new Vector3f();

	/** A frame with no earlier step: everything is read as it is at {@code x, y, z} */
	static StepFrame at(double x, double y, double z, float yaw) {
		StepFrame frame = new StepFrame();

		frame.set(x, y, z, yaw, x, y, z, yaw, 1f);

		return frame;
	}

	public void set(double x, double y, double z, float yaw, double px, double py, double pz, float pyaw, float alpha) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.yaw = yaw;
		this.px = px;
		this.py = py;
		this.pz = pz;
		this.pyaw = pyaw;
		this.alpha = alpha;
	}

	/**
	 * The particle at world place {@code now} (placed by the last step) whose place before that step was {@code before} (or null), in the entity's model space
	 * at this frame's fraction between the two
	 */
	void toModel(double[] now, double[] before, Vector3f out) {
		out.set((float)(now[0] - this.x), (float)(now[1] - this.y), (float)(now[2] - this.z));
		Kinematics.worldToModel(out, this.yaw);

		if (this.alpha >= 1f || before == null)
			return;

		this.other.set((float)(before[0] - this.px), (float)(before[1] - this.py), (float)(before[2] - this.pz));
		Kinematics.worldToModel(this.other, this.pyaw);
		this.other.lerp(out, this.alpha);
		out.set(this.other);
	}
}
