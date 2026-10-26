package com.sandymandy.strut.physics.solver;

import com.sandymandy.strut.physics.world.Liquid;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.config.PhysicsOptions;

/**
 * The cloth solver: particles (a mesh's welded vertices) joined by edges that keep their length, folds that resist bending, some of them sewn to the animation,
 * pushed out of colliders
 * <p>
 * Like the chain solver it works in world space, in doubles, position based, so the entity's own motion is inertia with no special cases and a correction never
 * injects energy. A particle's inverse mass is {@code 1 - pin}: a fully pinned one (a waistband) is exactly where the animation puts it and the rest of the cloth
 * is pulled after it. Pure and tested without a game
 */
public final class ClothSolver {
	private ClothSolver() {}

	/** What is joined to what: fixed once the mesh is known */
	public static final class Topology {
		final int n;
		public final int[] edgeA;
		public final int[] edgeB;
		final double[] edgeRest;
		/** Pairs of vertices across a fold (the two corners opposite a shared edge): kept at their rest distance, softly, so the cloth does not crumple */
		final int[] bendA;
		final int[] bendB;
		final double[] bendRest;
		/** The triangles (three particle indices each) and the volume the mesh has at rest, for a closed mesh (0 when it is open or flat) */
		int[] triangles = new int[0];
		double restVolume;
		/**
		 * The connected pieces of the mesh (a mesh may hold several that share no vertex: two pouches merged into one): each piece's triangles, its particles and
		 * the volume it has at rest. Volume is kept piece by piece, each around its own middle
		 */
		int[][] pieceTriangles = new int[0][];
		int[][] pieceParticles = new int[0][];
		double[] pieceVolume = new double[0];
		/** Each particle's edges: {@code adjOther} and {@code adjRest} from {@code adjStart[k]} to {@code adjStart[k + 1]} */
		final int[] adjStart;
		final int[] adjOther;
		final double[] adjRest;

		private Topology(int n, int[] edgeA, int[] edgeB, double[] edgeRest, int[] bendA, int[] bendB, double[] bendRest) {
			this.adjStart = new int[n + 1];

			for (int e = 0; e < edgeA.length; e++) {
				this.adjStart[edgeA[e] + 1]++;
				this.adjStart[edgeB[e] + 1]++;
			}

			for (int k = 0; k < n; k++)
				this.adjStart[k + 1] += this.adjStart[k];

			this.adjOther = new int[edgeA.length * 2];
			this.adjRest = new double[edgeA.length * 2];

			int[] fill = Arrays.copyOf(this.adjStart, n);

			for (int e = 0; e < edgeA.length; e++) {
				this.adjOther[fill[edgeA[e]]] = edgeB[e];
				this.adjRest[fill[edgeA[e]]++] = edgeRest[e];
				this.adjOther[fill[edgeB[e]]] = edgeA[e];
				this.adjRest[fill[edgeB[e]]++] = edgeRest[e];
			}

			this.n = n;
			this.edgeA = edgeA;
			this.edgeB = edgeB;
			this.edgeRest = edgeRest;
			this.bendA = bendA;
			this.bendB = bendB;
			this.bendRest = bendRest;
		}

		/**
		 * @param rest Each particle's rest position
		 * @param triangles Three particle indices per triangle
		 */
		static Topology build(double[][] rest, int[] triangles) {
			Map<Long, int[]> edges = new HashMap<>();
			List<int[]> edgeList = new ArrayList<>();

			for (int t = 0; t + 2 < triangles.length; t += 3) {
				int a = triangles[t];
				int b = triangles[t + 1];
				int c = triangles[t + 2];

				if (a == b || b == c || a == c)
					continue;

				addEdge(edges, edgeList, a, b, c);
				addEdge(edges, edgeList, b, c, a);
				addEdge(edges, edgeList, c, a, b);
			}

			int[] ea = new int[edgeList.size()];
			int[] eb = new int[edgeList.size()];
			double[] er = new double[edgeList.size()];
			List<int[]> bendList = new ArrayList<>();

			for (int i = 0; i < ea.length; i++) {
				int[] e = edgeList.get(i);

				ea[i] = e[0];
				eb[i] = e[1];
				er[i] = distance(rest[e[0]], rest[e[1]]);

				// an edge shared by two triangles is a fold line: its two opposite corners are the bend pair
				if (e[3] >= 0 && e[2] != e[3] && distance(rest[e[2]], rest[e[3]]) > 1e-9)
					bendList.add(new int[] {e[2], e[3]});
			}

			int[] ba = new int[bendList.size()];
			int[] bb = new int[bendList.size()];
			double[] br = new double[bendList.size()];

			for (int i = 0; i < ba.length; i++) {
				ba[i] = bendList.get(i)[0];
				bb[i] = bendList.get(i)[1];
				br[i] = distance(rest[ba[i]], rest[bb[i]]);
			}

			Topology topo = new Topology(rest.length, ea, eb, er, ba, bb, br);

			topo.triangles = triangles;
			topo.splitIntoPieces(rest, triangles);

			return topo;
		}

		private void splitIntoPieces(double[][] rest, int[] triangles) {
			int[] parent = new int[this.n];

			for (int i = 0; i < parent.length; i++)
				parent[i] = i;

			for (int t = 0; t + 2 < triangles.length; t += 3) {
				union(parent, triangles[t], triangles[t + 1]);
				union(parent, triangles[t + 1], triangles[t + 2]);
			}

			Map<Integer, Integer> pieceOfRoot = new HashMap<>();
			List<List<Integer>> particles = new ArrayList<>();
			List<List<Integer>> tris = new ArrayList<>();

			for (int k = 0; k < this.n; k++) {
				int root = find(parent, k);
				Integer piece = pieceOfRoot.get(root);

				if (piece == null) {
					piece = particles.size();
					pieceOfRoot.put(root, piece);
					particles.add(new ArrayList<>());
					tris.add(new ArrayList<>());
				}

				particles.get(piece).add(k);
			}

			for (int t = 0; t + 2 < triangles.length; t += 3) {
				List<Integer> list = tris.get(pieceOfRoot.get(find(parent, triangles[t])));

				list.add(triangles[t]);
				list.add(triangles[t + 1]);
				list.add(triangles[t + 2]);
			}

			this.pieceParticles = new int[particles.size()][];
			this.pieceTriangles = new int[particles.size()][];
			this.pieceVolume = new double[particles.size()];
			this.restVolume = 0;

			for (int c = 0; c < particles.size(); c++) {
				this.pieceParticles[c] = particles.get(c).stream().mapToInt(Integer::intValue).toArray();
				this.pieceTriangles[c] = tris.get(c).stream().mapToInt(Integer::intValue).toArray();
				this.pieceVolume[c] = Math.abs(volume(rest, this.pieceTriangles[c], this.pieceParticles[c], null));
				this.restVolume += this.pieceVolume[c];
			}
		}

		private static int find(int[] parent, int k) {
			while (parent[k] != k) {
				parent[k] = parent[parent[k]];
				k = parent[k];
			}

			return k;
		}

		private static void union(int[] parent, int a, int b) {
			parent[find(parent, a)] = find(parent, b);
		}

		static double volume(double[][] x, int[] triangles, double[] area) {
			return volume(x, triangles, null, area);
		}

		/**
		 * The signed volume inside {@code triangles}, measured from the centroid of {@code particles} (all of {@code x} when null); {@code area} receives the surface
		 * area if not null
		 */
		static double volume(double[][] x, int[] triangles, int[] particles, double[] area) {
			double cx = 0;
			double cy = 0;
			double cz = 0;
			int count = particles == null ? x.length : particles.length;

			for (int i = 0; i < count; i++) {
				double[] p = x[particles == null ? i : particles[i]];

				cx += p[0];
				cy += p[1];
				cz += p[2];
			}

			cx /= count;
			cy /= count;
			cz /= count;

			double v = 0;
			double a = 0;

			for (int t = 0; t + 2 < triangles.length; t += 3) {
				double[] p = x[triangles[t]];
				double[] q = x[triangles[t + 1]];
				double[] r = x[triangles[t + 2]];
				double ax = p[0] - cx;
				double ay = p[1] - cy;
				double az = p[2] - cz;
				double bx = q[0] - cx;
				double by = q[1] - cy;
				double bz = q[2] - cz;
				double dx = r[0] - cx;
				double dy = r[1] - cy;
				double dz = r[2] - cz;
				double nx = (by - ay) * (dz - az) - (bz - az) * (dy - ay);
				double ny = (bz - az) * (dx - ax) - (bx - ax) * (dz - az);
				double nz = (bx - ax) * (dy - ay) - (by - ay) * (dx - ax);

				v += (ax * (by * dz - bz * dy) + ay * (bz * dx - bx * dz) + az * (bx * dy - by * dx)) / 6.0;
				a += 0.5 * Math.sqrt(nx * nx + ny * ny + nz * nz);
			}

			if (area != null)
				area[0] = a;

			return v;
		}

		private static void addEdge(Map<Long, int[]> edges, List<int[]> list, int a, int b, int opposite) {
			int lo = Math.min(a, b);
			int hi = Math.max(a, b);
			long key = ((long)lo << 32) | hi;
			int[] e = edges.get(key);

			if (e == null) {
				e = new int[] {lo, hi, opposite, -1};
				edges.put(key, e);
				list.add(e);
			}
			else if (e[3] < 0) {
				e[3] = opposite;
			}
		}

		static double distance(double[] a, double[] b) {
			double dx = a[0] - b[0];
			double dy = a[1] - b[1];
			double dz = a[2] - b[2];

			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	/** One cloth's particles: current and previous positions, and how firmly each is sewn to the animation */
	public static final class State {
		public final double[][] x;
		public final double[][] prev;
		/** Where each particle was before the last step: what a frame between the last two steps blends from (see {@link StepFrame}) */
		final double[][] before;
		final double[][] start;
		/** 0 free to 1 sewn on */
		final float[] pin;
		final boolean[] touched;
		/** Scratch for {@link #limitStrain}: how many edges from something fixed each particle is, and the queue that walks outward */
		final int[] level;
		final int[] queue;
		/** A particle held where the user or a test put it (world space), or null: the tool's cursor grab */
		final double[][] hold;
		/** The liquid the particles are in this step, or null: set by the instance each frame */
		public Liquid liquid;
		public boolean initialised;
		/** How freely each particle moves this step (1 minus its pin, 0 for a held one), and which edges and folds have anything free to move */
		final double[] weight;
		int[] liveEdge = new int[0];
		int[] liveBend = new int[0];

		public State(int particles) {
			this.weight = new double[particles];
			this.hold = new double[particles][];
			this.level = new int[particles];
			this.queue = new int[particles];
			this.x = new double[particles][3];
			this.prev = new double[particles][3];
			this.before = new double[particles][3];
			this.start = new double[particles][3];
			this.pin = new float[particles];
			this.touched = new boolean[particles];
		}

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

	/**
	 * Advances a cloth by one step
	 *
	 * @param anim Where the animation puts each particle this step, world space
	 * @param entityDelta How far the entity moved this step (world), of which {@code 1 - inertia} carries the cloth
	 * @param colliders The shapes this cloth collides with this step, already placed in world space and already cut down to those near it
	 */
	static void step(Topology topo, State st, ClothParams p, float h, double[][] anim, double[] entityDelta, List<Collider.Placed> colliders) {
		st.mark();
		int n = st.x.length;
		double keep = Math.exp(-p.damping() * h);
		double carry = 1.0 - p.inertia();
		double pull = 1.0 - Math.exp(-p.anchor() * h);

		Arrays.fill(st.touched, false);

		for (int k = 0; k < n; k++)
			System.arraycopy(st.x[k], 0, st.start[k], 0, 3);

		for (int k = 0; k < n; k++) {
			if (st.pin[k] >= 0.999f || st.hold[k] != null)
				continue;

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

			if (pull > 0) {
				for (int a = 0; a < 3; a++)
					st.x[k][a] += (anim[k][a] - st.x[k][a]) * pull;
			}
		}

		applyPins(st, anim);

		double stiffness = p.stiffness();
		double bend = p.bend() * 0.5;

		int iterations = PhysicsOptions.iterations(p.iterations());

		// an edge between two particles that are both sewn on or held moves nothing: it is found once a step and left out of every pass (the firm part of a mesh
		// is most of it, for flesh, and its edges were looked at and passed over each of the passes)
		double[] w = st.weight;

		for (int k = 0; k < n; k++)
			w[k] = st.hold[k] != null ? 0.0 : 1.0 - st.pin[k];

		int liveEdges = live(topo.edgeA, topo.edgeB, w, st);
		int liveBends = bend > 0 ? liveBends(topo, w, st) : 0;
		int[] liveE = st.liveEdge;
		int[] liveB = st.liveBend;

		for (int it = 0; it < iterations; it++) {
			for (int i = 0; i < liveEdges; i++) {
				int e = liveE[i];

				relax(st.x, w, topo.edgeA[e], topo.edgeB[e], topo.edgeRest[e], stiffness);
			}

			for (int i = 0; i < liveBends; i++) {
				int b = liveB[i];

				relax(st.x, w, topo.bendA[b], topo.bendB[b], topo.bendRest[b], bend);
			}

			applyPins(st, anim);

			if (!colliders.isEmpty()) {
				for (int k = 0; k < n; k++) {
					if (st.pin[k] >= 0.999f)
						continue;

					for (Collider.Placed shape : colliders)
						st.touched[k] |= Collider.pushOut(shape, st.x[k], p.radius());
				}
			}
		}

		// a sewn-on edge that moves further in one step than the edges are long is more than a few relaxations can follow: from what is fixed outward, no edge
		// is left more than a little longer than it is
		limitStrain(topo, st, 0.02 + (1.0 - stiffness) * 0.5);

		if (p.volume() > 0f && topo.restVolume > 1e-9)
			restoreVolume(topo, st, p.volume());

		if (!colliders.isEmpty()) {
			// a cloth can pass over a shape between its particles: test the middle of each edge and push both ends
			double[] mid = new double[3];

			for (int e = 0; e < topo.edgeA.length; e++) {
				int a = topo.edgeA[e];
				int b = topo.edgeB[e];

				for (int axis = 0; axis < 3; axis++)
					mid[axis] = (st.x[a][axis] + st.x[b][axis]) * 0.5;

				double m0 = mid[0];
				double m1 = mid[1];
				double m2 = mid[2];
				boolean moved = false;

				for (Collider.Placed shape : colliders)
					moved |= Collider.pushOut(shape, mid, p.radius());

				if (moved) {
					double[] d = {mid[0] - m0, mid[1] - m1, mid[2] - m2};

					for (int axis = 0; axis < 3; axis++) {
						if (st.pin[a] < 0.999f)
							st.x[a][axis] += d[axis];

						if (st.pin[b] < 0.999f)
							st.x[b][axis] += d[axis];
					}

					st.touched[a] = true;
					st.touched[b] = true;
				}
			}

			// a shape has the last word, and a particle that travelled further this step than a block is thick is stopped where it first met one
			for (int k = 0; k < n; k++) {
				if (st.pin[k] >= 0.999f)
					continue;

				for (Collider.Placed shape : colliders)
					st.touched[k] |= Collider.pushOut(shape, st.x[k], p.radius());

				st.touched[k] |= Collider.sweep(colliders, st.start[k], st.x[k], st.prev[k], p.radius());
			}
		}

		applyPins(st, anim);

		if (p.friction() > 0) {
			for (int k = 0; k < n; k++) {
				if (!st.touched[k])
					continue;

				for (int a = 0; a < 3; a++)
					st.prev[k][a] = st.x[k][a] - (st.x[k][a] - st.prev[k][a]) * (1.0 - p.friction());
			}
		}
	}

	/**
	 * Follow the leader: walking outward from the particles that are fixed (sewn on or held), each particle is brought back toward the one it was reached from
	 * until that edge is at most {@code 1 + maxStretch} times its rest length. Only the farther particle moves, so what is fixed stays and the correction is
	 * exact however far the fixed part jumped. A cloth with nothing fixed is left alone
	 */
	private static void limitStrain(Topology topo, State st, double maxStretch) {
		int n = st.x.length;
		int head = 0;
		int tail = 0;

		for (int k = 0; k < n; k++) {
			boolean fixed = st.pin[k] >= 0.999f || st.hold[k] != null;

			st.level[k] = fixed ? 0 : -1;

			if (fixed)
				st.queue[tail++] = k;
		}

		if (tail == 0)
			return;

		double grow = 1.0 + maxStretch;

		while (head < tail) {
			int u = st.queue[head++];

			for (int i = topo.adjStart[u]; i < topo.adjStart[u + 1]; i++) {
				int v = topo.adjOther[i];

				if (st.level[v] == 0 || (st.level[v] > 0 && st.level[v] <= st.level[u]))
					continue;

				if (st.level[v] < 0) {
					st.level[v] = st.level[u] + 1;
					st.queue[tail++] = v;
				}

				double dx = st.x[v][0] - st.x[u][0];
				double dy = st.x[v][1] - st.x[u][1];
				double dz = st.x[v][2] - st.x[u][2];
				double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
				double limit = topo.adjRest[i] * grow;

				if (len <= limit || len < 1e-12)
					continue;

				double k = limit / len;

				st.x[v][0] = st.x[u][0] + dx * k;
				st.x[v][1] = st.x[u][1] + dy * k;
				st.x[v][2] = st.x[u][2] + dz * k;
			}
		}
	}

	/**
	 * A closed mesh keeps its volume: where it was pressed in, the rest of it bulges. Every free particle is moved out from the middle of the mesh by the share
	 * of the lost volume per unit of surface, so a pressed ball gets fatter across and a stretched one thinner
	 */
	private static void restoreVolume(Topology topo, State st, double strength) {
		for (int piece = 0; piece < topo.pieceVolume.length; piece++) {
			if (topo.pieceVolume[piece] > 1e-9)
				restorePieceVolume(topo, st, piece, strength);
		}
	}

	/** One connected piece keeps its own volume, pushed out from its own middle: a merged mesh of two parts is two bodies, not one */
	private static void restorePieceVolume(Topology topo, State st, int piece, double strength) {
		int[] members = topo.pieceParticles[piece];
		double[] area = {0};
		double v = Math.abs(Topology.volume(st.x, topo.pieceTriangles[piece], members, area));

		if (area[0] < 1e-9)
			return;

		double push = Math.max(-0.04, Math.min(0.04, (topo.pieceVolume[piece] - v) / area[0] * strength));
		double cx = 0;
		double cy = 0;
		double cz = 0;

		for (int k : members) {
			cx += st.x[k][0];
			cy += st.x[k][1];
			cz += st.x[k][2];
		}

		cx /= members.length;
		cy /= members.length;
		cz /= members.length;

		for (int k : members) {
			if (st.pin[k] >= 0.999f || st.hold[k] != null)
				continue;

			double dx = st.x[k][0] - cx;
			double dy = st.x[k][1] - cy;
			double dz = st.x[k][2] - cz;
			double len = Math.sqrt(dx * dx + dy * dy + dz * dz);

			if (len < 1e-9)
				continue;

			double s = push * (1.0 - st.pin[k]) / len;

			st.x[k][0] += dx * s;
			st.x[k][1] += dy * s;
			st.x[k][2] += dz * s;
		}
	}

	/** A sewn-on particle is drawn toward the animation by its pin; fully sewn it is exactly there */
	private static void applyPins(State st, double[][] anim) {
		for (int k = 0; k < st.x.length; k++) {
			if (st.hold[k] != null) {
				System.arraycopy(st.hold[k], 0, st.x[k], 0, 3);
				System.arraycopy(st.hold[k], 0, st.prev[k], 0, 3);

				continue;
			}

			float pin = st.pin[k];

			if (pin <= 0f)
				continue;

			if (pin >= 0.999f) {
				System.arraycopy(anim[k], 0, st.x[k], 0, 3);
				System.arraycopy(anim[k], 0, st.prev[k], 0, 3);
			}
			else {
				for (int a = 0; a < 3; a++)
					st.x[k][a] += (anim[k][a] - st.x[k][a]) * pin;
			}
		}
	}

	/** Fills {@code st.liveEdge} with the edges that have a free end and returns how many */
	private static int live(int[] from, int[] to, double[] w, State st) {
		if (st.liveEdge.length < from.length)
			st.liveEdge = new int[from.length];

		int count = 0;

		for (int e = 0; e < from.length; e++) {
			if (w[from[e]] + w[to[e]] >= 1e-6)
				st.liveEdge[count++] = e;
		}

		return count;
	}

	private static int liveBends(Topology topo, double[] w, State st) {
		if (st.liveBend.length < topo.bendA.length)
			st.liveBend = new int[topo.bendA.length];

		int count = 0;

		for (int b = 0; b < topo.bendA.length; b++) {
			if (w[topo.bendA[b]] + w[topo.bendB[b]] >= 1e-6)
				st.liveBend[count++] = b;
		}

		return count;
	}

	/** Moves two particles toward (or from) each other to their rest distance, the lighter one further, by {@code strength} of the error */
	private static void relax(double[][] x, double[] w, int a, int b, double rest, double strength) {
		double wa = w[a];
		double wb = w[b];
		double wsum = wa + wb;

		if (wsum < 1e-6)
			return;

		double[] pa = x[a];
		double[] pb = x[b];
		double dx = pb[0] - pa[0];
		double dy = pb[1] - pa[1];
		double dz = pb[2] - pa[2];
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);

		if (len < 1e-12)
			return;

		double c = (len - rest) / len * strength / wsum;

		pa[0] += dx * c * wa;
		pa[1] += dy * c * wa;
		pa[2] += dz * c * wa;
		pb[0] -= dx * c * wb;
		pb[1] -= dy * c * wb;
		pb[2] -= dz * c * wb;
	}

	/** The largest relative difference between an edge's length and its rest length, for tests */
	static double worstEdgeError(Topology topo, State st) {
		double worst = 0;

		for (int e = 0; e < topo.edgeA.length; e++) {
			if (topo.edgeRest[e] < 1e-9)
				continue;

			worst = Math.max(worst, Math.abs(Topology.distance(st.x[topo.edgeA[e]], st.x[topo.edgeB[e]]) - topo.edgeRest[e]) / topo.edgeRest[e]);
		}

		return worst;
	}
}
