package com.sandymandy.strut.physics.solver;

import com.sandymandy.strut.physics.world.Liquid;
import java.util.Arrays;
import java.util.List;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.config.PhysicsOptions;

/**
 * The chain solver: particles joined by fixed-length segments, pulled toward their animated places, bent no further than a limit, and pushed out of colliders
 *
 * <p>
 * A chain of {@code n} bones is {@code n + 1} particles: the root bone's pivot, each following bone's pivot, and the last bone's tip. The root particle is
 * kinematic, it goes where the animation puts it. Everything is simulated in world space (positions in blocks, in doubles), so the entity's own motion is
 * inertia with no special cases: the root is carried and the rest lags. Position based: velocities are the difference to the last step, so a correction never
 * injects energy
 * <p>
 * Pure and allocation-light per step; tested without a game
 */
public final class ChainSolver {
	static final int ITERATIONS = 4;

	/** One chain's particles: current and previous positions, {@code (n + 1) x 3} */
	public static final class State {
		public final double[][] x;
		public final double[][] prev;
		/** Where each particle was before the last step: what a frame between the last two steps blends from (see {@link StepFrame}) */
		final double[][] before;
		/** The liquid the particles are in this step, or null: set by the instance each frame */
		public Liquid liquid;
		public boolean initialised;
		/** A particle held where the user or a test put it (world space), or null: the tool's cursor grab */
		final double[][] pin;
		/** Whether each particle touched a shape in the last step */
		final boolean[] touched;
		/** Where each particle was at the start of the step, for the swept test against blocks */
		final double[][] start;

		public State(int particles) {
			this.start = new double[particles][3];
			this.x = new double[particles][3];
			this.prev = new double[particles][3];
			this.before = new double[particles][3];
			this.pin = new double[particles][];
			this.touched = new boolean[particles];
		}

		/** Puts every particle at its animated place with no velocity */
		public void snapTo(double[][] anim) {
			for (int k = 0; k < this.x.length; k++) {
				System.arraycopy(anim[k], 0, this.x[k], 0, 3);
				System.arraycopy(anim[k], 0, this.prev[k], 0, 3);
				System.arraycopy(anim[k], 0, this.before[k], 0, 3);
			}

			this.initialised = true;
		}

		/** Remembers where the particles are, called as a step begins */
		public void mark() {
			for (int k = 0; k < this.x.length; k++)
				System.arraycopy(this.x[k], 0, this.before[k], 0, 3);
		}
	}

	private ChainSolver() {}

	/**
	 * Advances a chain by one step
	 *
	 * @param anim Where the animation puts each particle this step, world space
	 * @param rest The rest length of each segment, in blocks ({@code n} entries)
	 * @param entityDelta How far the entity moved this step (world), of which {@code 1 - inertia} carries the chain
	 * @param colliders The shapes this chain collides with this step, already placed in world space
	 */
	public static void step(State st, ChainParams p, float h, double[][] anim, double[] rest, double[] entityDelta, List<Collider.Placed> colliders) {
		st.mark();
		int n = st.x.length;
		double omega = 2 * Math.PI * p.frequency();
		double keep = Math.exp(-2 * p.dampingRatio() * omega * h);
		double stiffen = 1.0 / (1.0 + h * h * omega * omega);
		double carry = 1.0 - p.inertia();

		Arrays.fill(st.touched, false);

		for (int k = 0; k < n; k++)
			System.arraycopy(st.x[k], 0, st.start[k], 0, 3);

		for (int k = 1; k < n; k++) {
			double wet = st.liquid == null ? 0 : st.liquid.submersion(st.x[k][1]);
			double drag = wet > 0 ? st.liquid.keep(wet) : 1;

			for (int a = 0; a < 3; a++) {
				double v = (st.x[k][a] - st.prev[k][a]) * keep * drag;
				double move = entityDelta[a] * carry;

				st.prev[k][a] = st.x[k][a] + move;
				st.x[k][a] += v + move;
			}

			if (wet > 0) {
				st.x[k][0] += st.liquid.pushX(wet) * h * h;
				st.x[k][2] += st.liquid.pushZ(wet) * h * h;
				st.x[k][1] -= st.liquid.gravity(p.gravity(), wet) * h * h;
			}
			else {
				st.x[k][1] -= p.gravity() * h * h;
			}
		}

		System.arraycopy(anim[0], 0, st.x[0], 0, 3);
		System.arraycopy(anim[0], 0, st.prev[0], 0, 3);
		applyPins(st);

		// pull toward the animated pose: the chain's own stiffness, as a spring solved implicitly
		for (int k = 1; k < n; k++) {
			for (int a = 0; a < 3; a++)
				st.x[k][a] = anim[k][a] + (st.x[k][a] - anim[k][a]) * stiffen;
		}

		double[] tmp = new double[3];

		int iterations = PhysicsOptions.iterations(ITERATIONS);

		for (int it = 0; it < iterations; it++) {
			for (int i = 0; i < n - 1; i++) {
				keepLength(st, i, rest[i]);

				if (p.maxAngle() > 0)
					limitBend(st, i, rest[i], anim, p.maxAngle());
			}

			applyPins(st);

			for (int k = 1; k < n; k++) {
				for (Collider.Placed shape : colliders)
					st.touched[k] |= Collider.pushOut(shape, st.x[k], p.radius());
			}

			// a segment can pass through a shape between its particles: test its middle and push both ends
			for (int i = 0; i < n - 1; i++) {
				for (int a = 0; a < 3; a++)
					tmp[a] = (st.x[i][a] + st.x[i + 1][a]) * 0.5;

				double m0 = tmp[0];
				double m1 = tmp[1];
				double m2 = tmp[2];
				boolean moved = false;

				for (Collider.Placed shape : colliders)
					moved |= Collider.pushOut(shape, tmp, p.radius());

				if (moved) {
					st.touched[i] |= i > 0;
					st.touched[i + 1] = true;
					double[] d = {tmp[0] - m0, tmp[1] - m1, tmp[2] - m2};

					for (int a = 0; a < 3; a++) {
						if (i == 0)
							st.x[1][a] += d[a] * 2;
						else {
							st.x[i][a] += d[a];
							st.x[i + 1][a] += d[a];
						}
					}
				}
			}
		}

		// a last length pass, root to tip and moving only the child, so every segment has exactly its length whatever the iterations left (a root that
		// jumps by a block between steps is more than a few iterations can fully absorb)
		for (int i = 0; i < n - 1; i++) {
			keepLengthFromParent(st, i, rest[i]);

			if (p.maxAngle() > 0)
				limitBend(st, i, rest[i], anim, p.maxAngle());
		}

		// a shape has the last word: a solid thing is never left with a particle inside it, at the price of a segment a little long while it is in contact
		for (int k = 1; k < n; k++) {
			for (Collider.Placed shape : colliders)
				st.touched[k] |= Collider.pushOut(shape, st.x[k], p.radius());
		}

		// and a particle that travelled further this step than a block is thick is stopped where it first met one, instead of popping out the far side
		for (int k = 1; k < n; k++) {
			if (st.pin[k] == null)
				st.touched[k] |= Collider.sweep(colliders, st.start[k], st.x[k], st.prev[k], p.radius());
		}

		applyPins(st);

		// friction: a particle in contact loses a share of its motion this step, so a tail laid on the floor does not slide away forever
		if (p.friction() > 0) {
			for (int k = 1; k < n; k++) {
				if (!st.touched[k])
					continue;

				for (int a = 0; a < 3; a++)
					st.prev[k][a] = st.x[k][a] - (st.x[k][a] - st.prev[k][a]) * (1.0 - p.friction());
			}
		}
	}

	/** A pinned particle is exactly where it was put; the segments pull the rest of the chain after it */
	private static void applyPins(State st) {
		for (int k = 0; k < st.x.length; k++) {
			if (st.pin[k] != null) {
				System.arraycopy(st.pin[k], 0, st.x[k], 0, 3);
				System.arraycopy(st.pin[k], 0, st.prev[k], 0, 3);
			}
		}
	}

	private static void keepLengthFromParent(State st, int i, double rest) {
		double[] a = st.x[i];
		double[] b = st.x[i + 1];
		double dx = b[0] - a[0];
		double dy = b[1] - a[1];
		double dz = b[2] - a[2];
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);

		if (len < 1e-12)
			return;

		double k = rest / len;

		b[0] = a[0] + dx * k;
		b[1] = a[1] + dy * k;
		b[2] = a[2] + dz * k;
	}

	/** Moves segment {@code i}'s end (and its start too, unless it is the root) so the segment has its rest length */
	private static void keepLength(State st, int i, double rest) {
		double[] a = st.x[i];
		double[] b = st.x[i + 1];
		double dx = b[0] - a[0];
		double dy = b[1] - a[1];
		double dz = b[2] - a[2];
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);

		if (len < 1e-12)
			return;

		double k = (len - rest) / len;
		double share = i == 0 ? 1.0 : 0.5;

		b[0] -= dx * k * share;
		b[1] -= dy * k * share;
		b[2] -= dz * k * share;

		if (i > 0) {
			a[0] += dx * k * share;
			a[1] += dy * k * share;
			a[2] += dz * k * share;
		}
	}

	/** Bends segment {@code i} back toward its animated direction if it has swung further from it than {@code maxAngle} */
	private static void limitBend(State st, int i, double rest, double[][] anim, double maxAngle) {
		double sx = st.x[i + 1][0] - st.x[i][0];
		double sy = st.x[i + 1][1] - st.x[i][1];
		double sz = st.x[i + 1][2] - st.x[i][2];
		double ax = anim[i + 1][0] - anim[i][0];
		double ay = anim[i + 1][1] - anim[i][1];
		double az = anim[i + 1][2] - anim[i][2];
		double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
		double al = Math.sqrt(ax * ax + ay * ay + az * az);

		if (sl < 1e-12 || al < 1e-12)
			return;

		sx /= sl;
		sy /= sl;
		sz /= sl;
		ax /= al;
		ay /= al;
		az /= al;

		double dot = Math.max(-1, Math.min(1, sx * ax + sy * ay + sz * az));
		double angle = Math.acos(dot);

		if (angle <= maxAngle)
			return;

		// the direction at exactly maxAngle from the animated one, on the arc toward the current one
		double perpX = sx - ax * dot;
		double perpY = sy - ay * dot;
		double perpZ = sz - az * dot;
		double pl = Math.sqrt(perpX * perpX + perpY * perpY + perpZ * perpZ);

		if (pl < 1e-12) {
			// exactly opposite: any perpendicular will do
			perpX = Math.abs(ax) < 0.9 ? 1 : 0;
			perpY = Math.abs(ax) < 0.9 ? 0 : 1;
			perpZ = 0;
			double d2 = perpX * ax + perpY * ay + perpZ * az;

			perpX -= ax * d2;
			perpY -= ay * d2;
			perpZ -= az * d2;
			pl = Math.sqrt(perpX * perpX + perpY * perpY + perpZ * perpZ);
		}

		perpX /= pl;
		perpY /= pl;
		perpZ /= pl;

		double c = Math.cos(maxAngle);
		double s = Math.sin(maxAngle);

		st.x[i + 1][0] = st.x[i][0] + (ax * c + perpX * s) * rest;
		st.x[i + 1][1] = st.x[i][1] + (ay * c + perpY * s) * rest;
		st.x[i + 1][2] = st.x[i][2] + (az * c + perpZ * s) * rest;
	}
}
