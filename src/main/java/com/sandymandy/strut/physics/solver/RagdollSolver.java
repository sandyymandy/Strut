package com.sandymandy.strut.physics.solver;

import com.sandymandy.strut.physics.world.Liquid;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.config.PhysicsOptions;

/**
 * The ragdoll solver: a skeleton of particles joined by fixed-length sticks, free in the world, falling under gravity,
 * held apart from the ground and from itself, and, the Euphoria part, <em>pulled toward the animated pose by a muscle strength</em>: 0 is limp, 1 is exactly the
 * animation, and anything between is a body that keeps trying to stand and gets pushed about
 * <p>
 * Each bone is a stick from its pivot to a child's pivot (or to a tip particle for a bone with no child). The bones under one parent are also braced
 * against each other, so a shoulder cannot fold into the torso; a limit on the smallest angle at a joint stops a knee folding through itself. Position based,
 * in world space, doubles, like {@link ChainSolver}. Pure: tested without a game
 */
public final class RagdollSolver {
	static final int ITERATIONS = 12;

	/** The particle graph: sticks that keep their length, minimum distances that limit a joint, and the pairs that must not overlap */
	public record Skeleton(int particles, int[] stickA, int[] stickB, double[] stickRest, int[] minA, int[] minB, double[] minDistance, int[][] apart) {}

	/** Positions and previous positions, {@code particles x 3} */
	public static final class State {
		public final double[][] x;
		public final double[][] prev;
		/** Where each particle was before the last step: what a frame between the last two steps blends from (see {@link StepFrame}) */
		final double[][] before;
		/** A particle held where the user put it (world space), or null */
		final double[][] pin;
		final boolean[] touched;
		/** Where each particle was at the start of the step, for the swept test against blocks */
		final double[][] start;
		/** The liquid the particles are in this step, or null: set by the instance each frame */
		public Liquid liquid;
		public boolean initialised;

		public State(int particles) {
			this.start = new double[particles][3];
			this.x = new double[particles][3];
			this.prev = new double[particles][3];
			this.before = new double[particles][3];
			this.pin = new double[particles][];
			this.touched = new boolean[particles];
		}

		/** Puts every particle at its animated place with the given velocity (world blocks per second) */
		public void snapTo(double[][] anim, double vx, double vy, double vz, float h) {
			for (int k = 0; k < this.x.length; k++) {
				System.arraycopy(anim[k], 0, this.x[k], 0, 3);
				this.prev[k][0] = anim[k][0] - vx * h;
				this.prev[k][1] = anim[k][1] - vy * h;
				this.prev[k][2] = anim[k][2] - vz * h;
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

	private RagdollSolver() {}

	/**
	 * Builds the particle graph from a tree: {@code parent[k]} is the particle {@code k} hangs from (-1 for a root) and {@code rest} where each is in the rest
	 * pose. Every parent-child pair is a stick; the children of one parent are braced against each other so the cluster is rigid; the smallest distance
	 * across a joint follows from {@code minAngle} (the smallest angle the joint may close to, radians); and every pair more than two sticks apart is kept
	 * from overlapping
	 */
	public static Skeleton build(int[] parent, double[][] rest, double minAngle) {
		int n = parent.length;
		List<int[]> sticks = new ArrayList<>();

		for (int k = 0; k < n; k++) {
			if (parent[k] >= 0)
				sticks.add(new int[] {parent[k], k});
		}

		for (int p = 0; p < n; p++) {
			List<Integer> kids = new ArrayList<>();

			for (int k = 0; k < n; k++) {
				if (parent[k] == p)
					kids.add(k);
			}

			for (int i = 0; i < kids.size(); i++) {
				for (int j = i + 1; j < kids.size(); j++)
					sticks.add(new int[] {kids.get(i), kids.get(j)});
			}
		}

		int[] a = new int[sticks.size()];
		int[] b = new int[sticks.size()];
		double[] len = new double[sticks.size()];
		boolean[][] near = new boolean[n][n];

		for (int s = 0; s < a.length; s++) {
			a[s] = sticks.get(s)[0];
			b[s] = sticks.get(s)[1];
			len[s] = distance(rest[a[s]], rest[b[s]]);
			near[a[s]][b[s]] = true;
			near[b[s]][a[s]] = true;
		}

		// joint limits: a particle, its parent and its grandparent
		List<double[]> mins = new ArrayList<>();

		for (int k = 0; k < n; k++) {
			int p = parent[k];
			int g = p >= 0 ? parent[p] : -1;

			if (g >= 0) {
				double l1 = distance(rest[g], rest[p]);
				double l2 = distance(rest[p], rest[k]);

				mins.add(new double[] {g, k, Math.sqrt(Math.max(0, l1 * l1 + l2 * l2 - 2 * l1 * l2 * Math.cos(minAngle)))});
			}
		}

		int[] ma = new int[mins.size()];
		int[] mb = new int[mins.size()];
		double[] md = new double[mins.size()];

		for (int m = 0; m < ma.length; m++) {
			ma[m] = (int)mins.get(m)[0];
			mb[m] = (int)mins.get(m)[1];
			md[m] = mins.get(m)[2];
		}

		// pairs more than two sticks apart may not overlap
		boolean[][] close = new boolean[n][n];

		for (int i = 0; i < n; i++) {
			close[i][i] = true;

			for (int j = 0; j < n; j++) {
				if (near[i][j]) {
					close[i][j] = true;

					for (int k = 0; k < n; k++) {
						if (near[j][k])
							close[i][k] = true;
					}
				}
			}
		}

		List<int[]> apart = new ArrayList<>();

		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				if (!close[i][j])
					apart.add(new int[] {i, j});
			}
		}

		return new Skeleton(n, a, b, len, ma, mb, md, apart.toArray(new int[0][]));
	}

	private static double distance(double[] p, double[] q) {
		return Math.sqrt(Math.pow(p[0] - q[0], 2) + Math.pow(p[1] - q[1], 2) + Math.pow(p[2] - q[2], 2));
	}

	/**
	 * One step
	 *
	 * @param anim Where the animation puts each particle now (world)
	 * @param strength The muscle: 0 limp, 1 exactly the animation
	 * @param gravity Blocks per second squared, downward
	 * @param damping 0 to 1: the share of its velocity a particle loses each second
	 * @param friction 0 to 1: the share of its motion a particle in contact with something loses each step
	 * @param radius The size of each particle, blocks
	 * @param colliders Solid shapes in world space (blocks)
	 */
	public static void step(State st, Skeleton sk, float h, double gravity, double damping, double friction, double radius, double[][] anim, double strength, List<Collider.Placed> colliders) {
		st.mark();
		int n = sk.particles();
		double keep = Math.exp(-damping * h);
		// full strength is the animation itself; below it the pull is gentle at first and firm near the top, so a half-strength body still sags
		double pull = strength >= 0.999 ? 1.0 : strength <= 0 ? 0.0 : strength * strength * 0.5;

		Arrays.fill(st.touched, false);

		for (int k = 0; k < n; k++)
			System.arraycopy(st.x[k], 0, st.start[k], 0, 3);

		for (int k = 0; k < n; k++) {
			double wet = st.liquid == null ? 0 : st.liquid.submersion(st.x[k][1]);
			double drag = wet > 0 ? st.liquid.keep(wet) : 1;

			for (int a = 0; a < 3; a++) {
				double v = (st.x[k][a] - st.prev[k][a]) * keep * drag;

				st.prev[k][a] = st.x[k][a];
				st.x[k][a] += v;
			}

			if (wet > 0) {
				st.x[k][0] += st.liquid.pushX(wet) * h * h;
				st.x[k][2] += st.liquid.pushZ(wet) * h * h;
				st.x[k][1] -= st.liquid.gravity(gravity, wet) * h * h;
			}
			else {
				st.x[k][1] -= gravity * h * h;
			}
		}

		applyPins(st);

		if (pull > 0) {
			for (int k = 0; k < n; k++) {
				for (int a = 0; a < 3; a++)
					st.x[k][a] += (anim[k][a] - st.x[k][a]) * pull;
			}
		}

		double[] tmp = new double[3];

		int iterations = PhysicsOptions.iterations(ITERATIONS);

		for (int it = 0; it < iterations; it++) {
			for (int s = 0; s < sk.stickA().length; s++)
				stick(st, sk.stickA()[s], sk.stickB()[s], sk.stickRest()[s]);

			for (int m = 0; m < sk.minA().length; m++)
				atLeast(st, sk.minA()[m], sk.minB()[m], sk.minDistance()[m]);

			for (int[] pair : sk.apart())
				atLeast(st, pair[0], pair[1], 2 * radius);

			applyPins(st);

			for (int k = 0; k < n; k++) {
				for (Collider.Placed shape : colliders)
					st.touched[k] |= Collider.pushOut(shape, st.x[k], radius);
			}
		}

		// the sticks once more, then the solid things have the last word: a body is never left inside a block
		for (int s = 0; s < sk.stickA().length; s++)
			stick(st, sk.stickA()[s], sk.stickB()[s], sk.stickRest()[s]);

		for (int k = 0; k < n; k++) {
			for (Collider.Placed shape : colliders)
				st.touched[k] |= Collider.pushOut(shape, st.x[k], radius);
		}

		// a joint that travelled further this step than a block is thick is stopped where it first met one
		for (int k = 0; k < n; k++) {
			if (st.pin[k] == null)
				st.touched[k] |= Collider.sweep(colliders, st.start[k], st.x[k], st.prev[k], radius);
		}

		applyPins(st);

		if (friction > 0) {
			for (int k = 0; k < n; k++) {
				if (!st.touched[k])
					continue;

				for (int a = 0; a < 3; a++)
					tmp[a] = st.x[k][a] - (st.x[k][a] - st.prev[k][a]) * (1.0 - friction);

				System.arraycopy(tmp, 0, st.prev[k], 0, 3);
			}
		}
	}

	private static void applyPins(State st) {
		for (int k = 0; k < st.x.length; k++) {
			if (st.pin[k] != null) {
				System.arraycopy(st.pin[k], 0, st.x[k], 0, 3);
				System.arraycopy(st.pin[k], 0, st.prev[k], 0, 3);
			}
		}
	}

	/** Moves both ends of a stick so it has its length; a pinned end does not move */
	private static void stick(State st, int i, int j, double rest) {
		double[] a = st.x[i];
		double[] b = st.x[j];
		double dx = b[0] - a[0];
		double dy = b[1] - a[1];
		double dz = b[2] - a[2];
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);

		if (len < 1e-12)
			return;

		double k = (len - rest) / len;
		double wa = st.pin[i] != null ? 0.0 : 1.0;
		double wb = st.pin[j] != null ? 0.0 : 1.0;
		double sum = wa + wb;

		if (sum == 0.0)
			return;

		a[0] += dx * k * wa / sum;
		a[1] += dy * k * wa / sum;
		a[2] += dz * k * wa / sum;
		b[0] -= dx * k * wb / sum;
		b[1] -= dy * k * wb / sum;
		b[2] -= dz * k * wb / sum;
	}

	/** Pushes two particles apart if they are closer than {@code min} */
	private static void atLeast(State st, int i, int j, double min) {
		double[] a = st.x[i];
		double[] b = st.x[j];
		double dx = b[0] - a[0];
		double dy = b[1] - a[1];
		double dz = b[2] - a[2];
		double d2 = dx * dx + dy * dy + dz * dz;

		if (d2 >= min * min)
			return;

		double len = Math.sqrt(d2);

		if (len < 1e-12) {
			// exactly on top of each other: any direction is as good as another
			dx = 0;
			dy = 1;
			dz = 0;
			len = 1;
		}

		double push = (min - Math.sqrt(d2)) / len;
		double wa = st.pin[i] != null ? 0.0 : 1.0;
		double wb = st.pin[j] != null ? 0.0 : 1.0;
		double sum = wa + wb;

		if (sum == 0.0)
			return;

		a[0] -= dx * push * wa / sum;
		a[1] -= dy * push * wa / sum;
		a[2] -= dz * push * wa / sum;
		b[0] += dx * push * wb / sum;
		b[1] += dy * push * wb / sum;
		b[2] += dz * push * wb / sum;
	}

	/** The largest difference between a stick's length and its rest length, relative, for tests */
	static double worstStickError(State st, Skeleton sk) {
		double worst = 0;

		for (int s = 0; s < sk.stickA().length; s++) {
			double[] a = st.x[sk.stickA()[s]];
			double[] b = st.x[sk.stickB()[s]];
			double d = Math.sqrt(Math.pow(b[0] - a[0], 2) + Math.pow(b[1] - a[1], 2) + Math.pow(b[2] - a[2], 2));

			worst = Math.max(worst, Math.abs(d - sk.stickRest()[s]) / sk.stickRest()[s]);
		}

		return worst;
	}
}
