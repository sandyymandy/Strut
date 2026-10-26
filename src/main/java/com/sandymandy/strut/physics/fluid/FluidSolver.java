package com.sandymandy.strut.physics.fluid;

import java.util.Arrays;
import java.util.List;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.collision.ShapeGrid;
import com.sandymandy.strut.physics.solver.SpringSolver;

/**
 * A cloud of soft particles standing in for a thick liquid, stepped at a fixed time step like the rest of the module. Pure and allocation-free per step, so it
 * is tested without a game
 * <p>
 * Not a full fluid simulation: the particles are soft balls. Two closer than {@link FluidParams#spacing()} push apart, two between that and
 * {@link FluidParams#radius()} pull together (the cohesion that makes it a blob and a string rather than a spray), and neighbours share their velocity
 * (viscosity). What they touch takes their speed (friction), so it clings and creeps down a wall. A step is position based, like the cloth: predict, relax the
 * pairs and the contacts a few times, and read the velocity back from what moved. That never blows up however hard it is thrown
 */
public final class FluidSolver {
	/** The fixed step */
	public static final float STEP = SpringSolver.STEP;

	private static final float MAX_SPEED = 14f;

	/** How long a particle ignores the shapes of the entity it came from, seconds */
	static final float OWN_BODY_GRACE = 0.8f;

	final FluidParams params;
	private final int capacity;
	public int count;
	/** The most particles alive at once, at most the capacity: the quality level's cap, so a lower level trims the pool and a new splash replaces the oldest */
	private int limit;
	public final double[] x;
	public final double[] y;
	public final double[] z;
	final float[] vx;
	final float[] vy;
	final float[] vz;
	final float[] age;
	final float[] life;
	/** The entity each particle came from, or -1: a young particle is not pushed by that entity's own shapes, so it can leave the body it started inside */
	final int[] owner;
	private final double[] px;
	private final double[] py;
	private final double[] pz;
	private final boolean[] touched;
	private final float[] nx;
	private final float[] ny;
	private final float[] nz;

	// the spatial hash of the predicted positions: cell -> a chain of particles
	private final long[] tableKey;
	private final int[] tableHead;
	private final boolean[] tableUsed;
	private final int[] next;
	private final int tableMask;
	/** The pairs of particles within reach at the start of the step, found once and relaxed several times */
	private final int[] pairA;
	private final int[] pairB;
	private int pairs;
	// the averaged corrections of the pairs (Jacobi, so a dense clump spreads gently instead of exploding)
	private final double[] cx;
	private final double[] cy;
	private final double[] cz;
	private final int[] cn;
	/** The shapes each particle tests this step: {@code shortlist[i * SHORT .. + shortCount[i]]} index into the grid's shapes */
	private static final int SHORT = 12;
	private final int[] shortlist;
	private final int[] shortCount;
	private final double[] lo = new double[3];
	private final double[] hi = new double[3];
	private final double[] contact = new double[3];

	public FluidSolver(FluidParams params, int capacity) {
		this.params = params;
		this.capacity = capacity;
		this.limit = capacity;
		this.x = new double[capacity];
		this.y = new double[capacity];
		this.z = new double[capacity];
		this.vx = new float[capacity];
		this.vy = new float[capacity];
		this.vz = new float[capacity];
		this.age = new float[capacity];
		this.life = new float[capacity];
		this.owner = new int[capacity];
		this.px = new double[capacity];
		this.py = new double[capacity];
		this.pz = new double[capacity];
		this.touched = new boolean[capacity];
		this.nx = new float[capacity];
		this.ny = new float[capacity];
		this.nz = new float[capacity];

		int size = Integer.highestOneBit(Math.max(16, capacity * 2 - 1)) << 1;

		this.tableKey = new long[size];
		this.tableHead = new int[size];
		this.tableUsed = new boolean[size];
		this.tableMask = size - 1;
		this.next = new int[capacity];
		this.pairA = new int[capacity * 48];
		this.pairB = new int[capacity * 48];
		this.cx = new double[capacity];
		this.cy = new double[capacity];
		this.cz = new double[capacity];
		this.cn = new int[capacity];
		this.shortlist = new int[capacity * SHORT];
		this.shortCount = new int[capacity];
	}

	int capacity() {
		return this.capacity;
	}

	/** Allows at most {@code limit} particles; the oldest beyond it are dropped now */
	void limit(int limit) {
		this.limit = Math.max(1, Math.min(this.capacity, limit));

		while (this.count > this.limit)
			oldestOut();
	}

	private void oldestOut() {
		int oldest = 0;

		for (int k = 1; k < this.count; k++) {
			if (this.age[k] > this.age[oldest])
				oldest = k;
		}

		remove(oldest);
	}

	/**
	 * Adds a particle. When the pool is full the oldest one makes room: a new splash replaces the stalest drop
	 *
	 * @param life Seconds it lives, or 0 for the preset's {@link FluidParams#lifetime()}
	 */
	public void spawn(double x, double y, double z, float vx, float vy, float vz, float life) {
		spawn(x, y, z, vx, vy, vz, life, -1);
	}

	/** As above, for a particle that came from entity {@code owner} (see {@link #owner}) */
	void spawn(double x, double y, double z, float vx, float vy, float vz, float life, int owner) {
		int i;

		if (this.count < this.limit) {
			i = this.count++;
		}
		else {
			i = 0;

			for (int k = 1; k < this.count; k++) {
				if (this.age[k] > this.age[i])
					i = k;
			}
		}

		this.x[i] = x;
		this.y[i] = y;
		this.z[i] = z;
		this.vx[i] = vx;
		this.vy[i] = vy;
		this.vz[i] = vz;
		this.age[i] = 0f;
		this.life[i] = life > 0 ? life : this.params.lifetime();
		this.owner[i] = owner;
	}

	void clear() {
		this.count = 0;
	}

	/** How big particle {@code i}'s blob is, 0 to 1: full until it starts to fade, then shrinking to nothing, and a young one swells in over a tenth of a second */
	float scaleOf(int i) {
		float left = this.life[i] - this.age[i];
		float fade = this.params.fade();
		float out = fade > 0 && left < fade ? Math.max(0f, left / fade) : 1f;
		float in = Math.min(1f, this.age[i] / 0.1f);

		return out * (0.4f + 0.6f * in);
	}

	/**
	 * One step
	 *
	 * @param shapes What the particles must stay out of: blocks and bodies, already cut down to those near the cloud
	 * @param iterations How many times the pairs and contacts are relaxed (2 to 4)
	 */
	void step(float h, List<Collider.Placed> shapes, int iterations) {
		step(h, ShapeGrid.of(shapes, GRID_MARGIN), iterations);
	}

	/** How far from a shape a particle may be and need testing against it within a step: its cling reach and a step of travel */
	public static final double GRID_MARGIN = 0.4;

	/**
	 * One step against the shapes of a {@link ShapeGrid} built for {@link #GRID_MARGIN}: the same answer as testing every shape, found from the few in the
	 * cell of the particle
	 */
	public void step(float h, ShapeGrid grid, int iterations) {
		FluidParams p = this.params;
		int n = this.count;

		if (n == 0)
			return;

		float dragKeep = 1f / (1f + p.drag() * h);

		for (int i = 0; i < n; i++) {
			// a step moves a particle no further than a thin wall is thick
			float speed = (float)Math.sqrt(this.vx[i] * this.vx[i] + this.vy[i] * this.vy[i] + this.vz[i] * this.vz[i]);

			if (speed > MAX_SPEED) {
				float k = MAX_SPEED / speed;

				this.vx[i] *= k;
				this.vy[i] *= k;
				this.vz[i] *= k;
			}

			this.vy[i] -= p.gravity() * h;
			this.vx[i] *= dragKeep;
			this.vy[i] *= dragKeep;
			this.vz[i] *= dragKeep;
			this.px[i] = this.x[i] + this.vx[i] * h;
			this.py[i] = this.y[i] + this.vy[i] * h;
			this.pz[i] = this.z[i] + this.vz[i] * h;
			this.touched[i] = false;
			this.nx[i] = 0f;
			this.ny[i] = 0f;
			this.nz[i] = 0f;
		}

		float contactRadius = p.spacing() * 0.5f;

		buildGrid(n);
		findPairs(n);

		// each particle's few shapes: those in its cell that are within reach of it now, found once, so that the relaxing and the clinging below test a
		// handful of shapes (usually none, or the one it rests on) instead of every one in the cell
		shortlist(n, grid, contactRadius * 4.0);

		for (int it = 0; it < iterations; it++) {
			relaxPairs(n);
			collide(n, grid, contactRadius);
		}

		// near a surface a thick liquid clings, even when nothing pushes it into it
		cling(n, grid, contactRadius);

		// what moved is the velocity; friction and the loss of the part heading into a surface are applied to it
		for (int i = 0; i < n; i++) {
			float ux = (float)((this.px[i] - this.x[i]) / h);
			float uy = (float)((this.py[i] - this.y[i]) / h);
			float uz = (float)((this.pz[i] - this.z[i]) / h);

			if (this.touched[i]) {
				float len = (float)Math.sqrt(this.nx[i] * this.nx[i] + this.ny[i] * this.ny[i] + this.nz[i] * this.nz[i]);

				if (len > 1e-9f) {
					float ax = this.nx[i] / len;
					float ay = this.ny[i] / len;
					float az = this.nz[i] / len;
					float into = ux * ax + uy * ay + uz * az;

					if (into < 0f) {
						ux -= into * ax;
						uy -= into * ay;
						uz -= into * az;
					}
				}

				// the sliding that is left is slowed: a thick liquid clings
				float keep = 1f - p.friction();

				ux *= keep;
				uy *= keep;
				uz *= keep;

				// and the position it settled at is where it stays this step, so a resting drop does not creep from rounding
				this.px[i] = this.x[i] + ux * h;
				this.py[i] = this.y[i] + uy * h;
				this.pz[i] = this.z[i] + uz * h;
			}

			this.vx[i] = ux;
			this.vy[i] = uy;
			this.vz[i] = uz;
		}

		share(n);

		for (int i = 0; i < n; i++) {
			float speed = (float)Math.sqrt(this.vx[i] * this.vx[i] + this.vy[i] * this.vy[i] + this.vz[i] * this.vz[i]);

			if (speed > MAX_SPEED) {
				float s = MAX_SPEED / speed;

				this.vx[i] *= s;
				this.vy[i] *= s;
				this.vz[i] *= s;
			}

			this.x[i] = this.px[i];
			this.y[i] = this.py[i];
			this.z[i] = this.pz[i];
			this.age[i] += h;
		}

		// the ones that have lived out their time leave: the last takes the place
		for (int i = n - 1; i >= 0; i--) {
			if (this.age[i] >= this.life[i])
				remove(i);
		}
	}

	private void remove(int i) {
		int last = --this.count;

		if (i != last) {
			this.x[i] = this.x[last];
			this.y[i] = this.y[last];
			this.z[i] = this.z[last];
			this.vx[i] = this.vx[last];
			this.vy[i] = this.vy[last];
			this.vz[i] = this.vz[last];
			this.age[i] = this.age[last];
			this.life[i] = this.life[last];
			this.owner[i] = this.owner[last];
		}
	}

	// ---------------------------------------------------------------------------------------------
	// neighbours
	// ---------------------------------------------------------------------------------------------

	private static long cellKey(int cx, int cy, int cz) {
		return ((long)(cx & 0x1FFFFF)) | ((long)(cy & 0x1FFFFF) << 21) | ((long)(cz & 0x1FFFFF) << 42);
	}

	private int slotOf(long key) {
		int slot = (int)((key * 0x9E3779B97F4A7C15L) >>> 40) & this.tableMask;

		while (this.tableUsed[slot] && this.tableKey[slot] != key)
			slot = (slot + 1) & this.tableMask;

		return slot;
	}

	private void buildGrid(int n) {
		Arrays.fill(this.tableUsed, false);

		double inv = 1.0 / this.params.radius();

		for (int i = 0; i < n; i++) {
			long key = cellKey((int)Math.floor(this.px[i] * inv), (int)Math.floor(this.py[i] * inv), (int)Math.floor(this.pz[i] * inv));
			int slot = slotOf(key);

			if (!this.tableUsed[slot]) {
				this.tableUsed[slot] = true;
				this.tableKey[slot] = key;
				this.tableHead[slot] = -1;
			}

			this.next[i] = this.tableHead[slot];
			this.tableHead[slot] = i;
		}
	}

	/** The pairs (i, j), j after i, that are within a little more than the radius now: the ones the step will relax */
	private void findPairs(int n) {
		double r = this.params.radius() * 1.2;
		double inv = 1.0 / this.params.radius();
		double r2 = r * r;
		int max = this.pairA.length;

		this.pairs = 0;

		for (int i = 0; i < n; i++) {
			int gx = (int)Math.floor(this.px[i] * inv);
			int gy = (int)Math.floor(this.py[i] * inv);
			int gz = (int)Math.floor(this.pz[i] * inv);

			for (int a = -1; a <= 1; a++) {
				for (int b = -1; b <= 1; b++) {
					for (int c = -1; c <= 1; c++) {
						int slot = slotOf(cellKey(gx + a, gy + b, gz + c));

						if (!this.tableUsed[slot])
							continue;

						for (int j = this.tableHead[slot]; j >= 0; j = this.next[j]) {
							if (j <= i)
								continue;

							double dx = this.px[j] - this.px[i];
							double dy = this.py[j] - this.py[i];
							double dz = this.pz[j] - this.pz[i];

							if (dx * dx + dy * dy + dz * dz < r2 && this.pairs < max) {
								this.pairA[this.pairs] = i;
								this.pairB[this.pairs] = j;
								this.pairs++;
							}
						}
					}
				}
			}
		}
	}

	private void relaxPairs(int n) {
		double spacing = this.params.spacing();
		double cohesion = this.params.cohesion();
		double reach = this.params.radius();

		Arrays.fill(this.cn, 0, n, 0);
		Arrays.fill(this.cx, 0, n, 0);
		Arrays.fill(this.cy, 0, n, 0);
		Arrays.fill(this.cz, 0, n, 0);

		for (int k = 0; k < this.pairs; k++) {
			int i = this.pairA[k];
			int j = this.pairB[k];
			double dx = this.px[j] - this.px[i];
			double dy = this.py[j] - this.py[i];
			double dz = this.pz[j] - this.pz[i];
			double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

			if (dist >= reach)
				continue;

			double move;

			if (dist < spacing) {
				// too close: push apart, half each. Particles on top of each other are sent along an arbitrary axis rather than nowhere
				move = -(spacing - dist) * 0.5;

				if (dist < 1e-9) {
					dx = 0;
					dy = 1;
					dz = 0;
					dist = 1;
				}
			}
			else {
				// in reach: pull together, so the gap closes by a fraction a step
				move = (dist - spacing) * cohesion * 0.5;
			}

			double s = move / dist;

			this.cx[i] += dx * s;
			this.cy[i] += dy * s;
			this.cz[i] += dz * s;
			this.cx[j] -= dx * s;
			this.cy[j] -= dy * s;
			this.cz[j] -= dz * s;
			this.cn[i]++;
			this.cn[j]++;
		}

		// the average of what each pair asked, a little over-relaxed
		for (int i = 0; i < n; i++) {
			if (this.cn[i] == 0)
				continue;

			double k = 1.4 / this.cn[i];

			this.px[i] += this.cx[i] * k;
			this.py[i] += this.cy[i] * k;
			this.pz[i] += this.cz[i] * k;
		}
	}

	/** Viscosity: every pair in reach shares a part of its velocity difference, more the closer they are */
	private void share(int n) {
		float viscosity = this.params.viscosity();

		if (viscosity <= 0f)
			return;

		double r = this.params.radius();

		for (int k = 0; k < this.pairs; k++) {
			int i = this.pairA[k];
			int j = this.pairB[k];
			double dx = this.px[j] - this.px[i];
			double dy = this.py[j] - this.py[i];
			double dz = this.pz[j] - this.pz[i];
			double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

			if (dist >= r)
				continue;

			float w = (float)(1.0 - dist / r) * viscosity;
			float ex = (this.vx[j] - this.vx[i]) * w;
			float ey = (this.vy[j] - this.vy[i]) * w;
			float ez = (this.vz[j] - this.vz[i]) * w;

			this.vx[i] += ex;
			this.vy[i] += ey;
			this.vz[i] += ez;
			this.vx[j] -= ex;
			this.vy[j] -= ey;
			this.vz[j] -= ez;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// contacts
	// ---------------------------------------------------------------------------------------------

	/** Fills the shortlists: the shapes within {@code reach} of each particle, of those in its cell and those every particle tests */
	private void shortlist(int n, ShapeGrid grid, double reach) {
		Arrays.fill(this.shortCount, 0, n, 0);

		if (grid.shapes.isEmpty())
			return;

		for (int i = 0; i < n; i++) {
			this.lo[0] = this.px[i] - reach;
			this.lo[1] = this.py[i] - reach;
			this.lo[2] = this.pz[i] - reach;
			this.hi[0] = this.px[i] + reach;
			this.hi[1] = this.py[i] + reach;
			this.hi[2] = this.pz[i] + reach;

			int[] list = grid.at(this.px[i], this.py[i], this.pz[i]);

			for (int k = 0; k < list.length; k++)
				consider(i, list[k], grid, reach);

			for (int k = 0; k < grid.global.length; k++)
				consider(i, grid.global[k], grid, reach);
		}
	}

	private void consider(int i, int index, ShapeGrid grid, double reach) {
		if (this.shortCount[i] >= SHORT)
			return;

		Collider.Placed shape = grid.shapes.get(index);

		if (shape.owner == this.owner[i] && shape.owner >= 0 && this.age[i] < OWN_BODY_GRACE)
			return;

		if (!Collider.near(shape, this.lo, this.hi))
			return;

		this.contact[0] = this.px[i];
		this.contact[1] = this.py[i];
		this.contact[2] = this.pz[i];

		if (Collider.pushOut(shape, this.contact, reach))
			this.shortlist[i * SHORT + this.shortCount[i]++] = index;
	}

	private void collide(int n, ShapeGrid grid, double radius) {
		for (int i = 0; i < n; i++) {
			int count = this.shortCount[i];

			if (count == 0)
				continue;

			this.lo[0] = this.px[i] - radius;
			this.lo[1] = this.py[i] - radius;
			this.lo[2] = this.pz[i] - radius;
			this.hi[0] = this.px[i] + radius;
			this.hi[1] = this.py[i] + radius;
			this.hi[2] = this.pz[i] + radius;

			for (int k = 0; k < count; k++)
				touch(i, grid.shapes.get(this.shortlist[i * SHORT + k]), radius);
		}
	}

	/** Pushes particle {@code i} out of {@code shape} and notes the contact; nothing if it is not touching, or the shape is its own entity's and it is young */
	private void touch(int i, Collider.Placed shape, double radius) {
		if (shape.owner == this.owner[i] && shape.owner >= 0 && this.age[i] < OWN_BODY_GRACE)
			return;

		if (!Collider.near(shape, this.lo, this.hi))
			return;

		this.contact[0] = this.px[i];
		this.contact[1] = this.py[i];
		this.contact[2] = this.pz[i];

		if (!Collider.pushOut(shape, this.contact, radius))
			return;

		double mx = this.contact[0] - this.px[i];
		double my = this.contact[1] - this.py[i];
		double mz = this.contact[2] - this.pz[i];

		this.px[i] = this.contact[0];
		this.py[i] = this.contact[1];
		this.pz[i] = this.contact[2];
		this.touched[i] = true;
		this.nx[i] += (float)mx;
		this.ny[i] += (float)my;
		this.nz[i] += (float)mz;
	}

	/**
	 * What makes the liquid sticky to a surface. A particle within a few particle widths of one is marked as touching it, with the surface direction, so it
	 * is slowed like one that is pressed against it, and is drawn a little toward it: a drop hangs on a wall instead of drifting off it as if the wall were
	 * not there. Both scale with the preset's friction (0 is a liquid that does not cling)
	 */
	private void cling(int n, ShapeGrid grid, double radius) {
		if (grid.shapes.isEmpty() || this.params.friction() <= 0f)
			return;

		double touch = radius * 1.8;
		double reach = radius * 4.0;
		double adhesion = this.params.friction() * 0.2;

		for (int i = 0; i < n; i++) {
			int count = this.shortCount[i];

			if (count == 0)
				continue;

			this.lo[0] = this.px[i] - reach;
			this.lo[1] = this.py[i] - reach;
			this.lo[2] = this.pz[i] - reach;
			this.hi[0] = this.px[i] + reach;
			this.hi[1] = this.py[i] + reach;
			this.hi[2] = this.pz[i] + reach;

			for (int k = 0; k < count; k++)
				clingTo(i, grid.shapes.get(this.shortlist[i * SHORT + k]), touch, reach, radius, adhesion);
		}
	}

	private void clingTo(int i, Collider.Placed shape, double touch, double reach, double radius, double adhesion) {
		if (shape.owner == this.owner[i] && shape.owner >= 0 && this.age[i] < OWN_BODY_GRACE)
			return;

		if (!Collider.near(shape, this.lo, this.hi))
			return;

		this.contact[0] = this.px[i];
		this.contact[1] = this.py[i];
		this.contact[2] = this.pz[i];

		if (!Collider.pushOut(shape, this.contact, reach))
			return;

		// the push is out of the surface and as long as the reach is deep: the particle is that much nearer than the reach
		double mx = this.contact[0] - this.px[i];
		double my = this.contact[1] - this.py[i];
		double mz = this.contact[2] - this.pz[i];
		double len = Math.sqrt(mx * mx + my * my + mz * mz);

		if (len < 1e-9)
			return;

		double distance = reach - len;

		if (distance < touch) {
			this.touched[i] = true;
			this.nx[i] += (float)mx;
			this.ny[i] += (float)my;
			this.nz[i] += (float)mz;
		}

		if (distance > radius) {
			double pull = (distance - radius) * adhesion / len;

			this.px[i] -= mx * pull;
			this.py[i] -= my * pull;
			this.pz[i] -= mz * pull;
		}
	}

	/** The largest distance between a particle and its nearest neighbour, for tests of cohesion: 0 with fewer than two */
	double widestGap() {
		double worst = 0;

		for (int i = 0; i < this.count; i++) {
			double best = Double.MAX_VALUE;

			for (int j = 0; j < this.count; j++) {
				if (i != j)
					best = Math.min(best, Math.sqrt(sq(this.x[i] - this.x[j]) + sq(this.y[i] - this.y[j]) + sq(this.z[i] - this.z[j])));
			}

			if (best != Double.MAX_VALUE)
				worst = Math.max(worst, best);
		}

		return worst;
	}

	private static double sq(double v) {
		return v * v;
	}
}
