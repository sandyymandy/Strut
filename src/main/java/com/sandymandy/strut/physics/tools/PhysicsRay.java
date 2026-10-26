package com.sandymandy.strut.physics.tools;

/**
 * Rays from the camera through a point of the screen, and what they hit: the maths behind the cursor tool, on plain doubles so it is tested without a game
 * <p>
 * Minecraft's angles: a yaw of 0 faces south (+Z) and turning it to 90 faces west (-X); a pitch of 90 looks straight down
 */
public final class PhysicsRay {
	private PhysicsRay() {}

	/** The unit vector a camera with this yaw and pitch looks along */
	static double[] forward(double yawDegrees, double pitchDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		double pitch = Math.toRadians(pitchDegrees);

		return new double[] {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
	}

	/**
	 * The direction of the ray through a point of the screen
	 *
	 * @param fovDegrees The vertical field of view
	 * @param aspect Screen width over height
	 * @param ndcX -1 at the left edge of the screen, +1 at the right
	 * @param ndcY -1 at the bottom, +1 at the top
	 */
	static double[] direction(double yawDegrees, double pitchDegrees, double fovDegrees, double aspect, double ndcX, double ndcY) {
		double[] f = forward(yawDegrees, pitchDegrees);
		double yaw = Math.toRadians(yawDegrees);
		// facing south the right hand is west (-X)
		double[] right = {-Math.cos(yaw), 0, -Math.sin(yaw)};
		double[] up = cross(right, f);
		double t = Math.tan(Math.toRadians(fovDegrees) / 2.0);
		double[] d = new double[3];

		for (int i = 0; i < 3; i++)
			d[i] = f[i] + right[i] * ndcX * t * aspect + up[i] * ndcY * t;

		return normalise(d);
	}

	/** The distance along the ray to a sphere, or -1 if it misses (or is behind the ray) */
	static double sphere(double[] origin, double[] dir, double cx, double cy, double cz, double radius) {
		double ox = origin[0] - cx;
		double oy = origin[1] - cy;
		double oz = origin[2] - cz;
		double b = ox * dir[0] + oy * dir[1] + oz * dir[2];
		double c = ox * ox + oy * oy + oz * oz - radius * radius;
		double disc = b * b - c;

		if (disc < 0)
			return -1;

		double t = -b - Math.sqrt(disc);

		if (t < 0)
			t = -b + Math.sqrt(disc);

		return t < 0 ? -1 : t;
	}

	/** The distance along the ray to an axis-aligned box, or -1 */
	static double box(double[] origin, double[] dir, double[] min, double[] max) {
		double tNear = -Double.MAX_VALUE;
		double tFar = Double.MAX_VALUE;

		for (int a = 0; a < 3; a++) {
			if (Math.abs(dir[a]) < 1e-12) {
				if (origin[a] < min[a] || origin[a] > max[a])
					return -1;

				continue;
			}

			double t1 = (min[a] - origin[a]) / dir[a];
			double t2 = (max[a] - origin[a]) / dir[a];

			tNear = Math.max(tNear, Math.min(t1, t2));
			tFar = Math.min(tFar, Math.max(t1, t2));
		}

		if (tNear > tFar || tFar < 0)
			return -1;

		return tNear < 0 ? 0 : tNear;
	}

	/**
	 * The point where a ray meets a plane through {@code point} with normal {@code normal}, or null if it is parallel or behind
	 */
	static double[] plane(double[] origin, double[] dir, double[] point, double[] normal) {
		double denom = dir[0] * normal[0] + dir[1] * normal[1] + dir[2] * normal[2];

		if (Math.abs(denom) < 1e-9)
			return null;

		double t = ((point[0] - origin[0]) * normal[0] + (point[1] - origin[1]) * normal[1] + (point[2] - origin[2]) * normal[2]) / denom;

		if (t < 0)
			return null;

		return new double[] {origin[0] + dir[0] * t, origin[1] + dir[1] * t, origin[2] + dir[2] * t};
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static double[] normalise(double[] v) {
		double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);

		return new double[] {v[0] / l, v[1] / l, v[2] / l};
	}
}
