package com.sandymandy.strut.physics.fluid;

import java.util.Arrays;

/**
 * The surface of a cloud of particles as a triangle mesh: metaballs, cut on a coarse grid by naive surface nets
 * <p>
 * Every particle adds a smooth bump {@code (1 - d^2 / R^2)^3} to a scalar field around it; where the sum of bumps crosses the iso level is the surface, so two
 * drops that come near join into one blob and one that moves away pulls a thread out of it. The field is only worked out at the corners of grid cells near a
 * particle. A cell the surface passes through gets one vertex (the average of where it crosses the cell's edges) and every grid edge the surface crosses joins
 * the four vertices around it as a quad. The grid is the low-poly look: with a cell about half the particle's reach the blobs have the soft faceting of a
 * game model rather than the fineness of a render
 * <p>
 * Pure: no game classes. Reused between frames so a frame allocates nothing once the buffers have grown
 */
public final class FluidMesh {
	/** The world position the mesh's coordinates are measured from (a vertex is a float offset from it) */
	double originX;
	double originY;
	double originZ;
	/** Three floats a vertex: position from the origin, and the unit normal */
	float[] positions = new float[0];
	float[] normals = new float[0];
	int vertexCount;
	/** Three indices a triangle */
	int[] indices = new int[0];
	int triangleCount;

	// the field at the grid's corners: a hash from the corner to its slot
	private long[] pointKey = new long[0];
	private int[] pointSlot = new int[0];
	private boolean[] pointUsed = new boolean[0];
	private float[] value = new float[0];
	private int[] cornerX = new int[0];
	private int[] cornerY = new int[0];
	private int[] cornerZ = new int[0];
	private int points;
	private int tableMask;

	// the cells that have a vertex: a hash from the cell to its vertex index, -1 for one the surface misses
	private long[] cellKeyTable = new long[0];
	private int[] cellVertex = new int[0];
	private boolean[] cellUsed = new boolean[0];
	private int cellMask;
	private int cells;

	private final float[] corners = new float[8];
	/** The grid's cell size of the mesh being built */
	private double cellSize;
	/** The most grid corners a mesh may have: the tables are never filled past half, so a lookup always ends */
	private int pointLimit;

	void clear() {
		this.vertexCount = 0;
		this.triangleCount = 0;
		this.points = 0;
	}

	boolean isEmpty() {
		return this.triangleCount == 0;
	}

	private static long key(int x, int y, int z) {
		return ((long)(x & 0x1FFFFF)) | ((long)(y & 0x1FFFFF) << 21) | ((long)(z & 0x1FFFFF) << 42);
	}

	private static int hash(long key, int mask) {
		return (int)((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
	}

	private void prepareTables(int expectedPoints) {
		int size = Integer.highestOneBit(Math.max(64, expectedPoints * 2 - 1)) << 1;

		if (this.pointKey.length < size) {
			this.pointKey = new long[size];
			this.pointSlot = new int[size];
			this.pointUsed = new boolean[size];
			// a cell table four times as big: the cells a grid's corners touch outnumber the corners, and are all remembered, vertex or not
			this.cellKeyTable = new long[size * 4];
			this.cellVertex = new int[size * 4];
			this.cellUsed = new boolean[size * 4];
		}

		this.tableMask = this.pointKey.length - 1;
		this.cellMask = this.cellKeyTable.length - 1;
		this.pointLimit = this.pointKey.length / 2 - 8;
		Arrays.fill(this.pointUsed, false);
		Arrays.fill(this.cellUsed, false);

		if (this.value.length < expectedPoints) {
			this.value = new float[expectedPoints];
			this.cornerX = new int[expectedPoints];
			this.cornerY = new int[expectedPoints];
			this.cornerZ = new int[expectedPoints];
		}

		this.points = 0;
		this.cells = 0;
	}

	/** The slot of grid corner (x, y, z), creating it at 0 if {@code create}, or -1 when absent and not created */
	private int pointAt(int x, int y, int z, boolean create) {
		long k = key(x, y, z);
		int h = hash(k, this.tableMask);

		while (this.pointUsed[h]) {
			if (this.pointKey[h] == k)
				return this.pointSlot[h];

			h = (h + 1) & this.tableMask;
		}

		if (!create || this.points >= this.pointLimit)
			return -1;

		if (this.points >= this.value.length) {
			int grown = Math.max(64, this.value.length * 2);

			this.value = Arrays.copyOf(this.value, grown);
			this.cornerX = Arrays.copyOf(this.cornerX, grown);
			this.cornerY = Arrays.copyOf(this.cornerY, grown);
			this.cornerZ = Arrays.copyOf(this.cornerZ, grown);
		}

		int slot = this.points++;

		this.pointUsed[h] = true;
		this.pointKey[h] = k;
		this.pointSlot[h] = slot;
		this.value[slot] = 0f;
		this.cornerX[slot] = x;
		this.cornerY[slot] = y;
		this.cornerZ[slot] = z;

		return slot;
	}

	private float valueAt(int x, int y, int z) {
		int slot = pointAt(x, y, z, false);

		return slot < 0 ? 0f : this.value[slot];
	}

	/**
	 * Builds the surface of the particles {@code 0 .. count-1} of {@code solver}
	 *
	 * @return Whether there is anything to draw
	 */
	boolean build(FluidSolver solver, int maxTriangles) {
		clear();

		int n = solver.count;

		if (n == 0)
			return false;

		FluidParams p = solver.params;
		double cell = p.cell();
		double inv = 1.0 / cell;
		double radius = p.radius();
		double r2 = radius * radius;
		int reach = (int)Math.ceil(radius * inv);

		// measured from the first particle's cell, so a coordinate far from the world's origin still fits a 21 bit grid index
		int ox = (int)Math.floor(solver.x[0] * inv);
		int oy = (int)Math.floor(solver.y[0] * inv);
		int oz = (int)Math.floor(solver.z[0] * inv);

		this.cellSize = cell;
		this.originX = ox * cell;
		this.originY = oy * cell;
		this.originZ = oz * cell;

		int perParticle = (2 * reach + 2) * (2 * reach + 2) * (2 * reach + 2);

		prepareTables(Math.min(n * perParticle / 2 + 64, 400_000));

		for (int i = 0; i < n; i++) {
			float scale = solver.scaleOf(i);

			if (scale <= 0.01f)
				continue;

			int cx = (int)Math.floor(solver.x[i] * inv) - ox;
			int cy = (int)Math.floor(solver.y[i] * inv) - oy;
			int cz = (int)Math.floor(solver.z[i] * inv) - oz;

			// a particle farther from the first than the grid can number is not drawn (a second cloud of the same liquid across the world)
			if (Math.abs(cx) > 900_000 || Math.abs(cy) > 900_000 || Math.abs(cz) > 900_000)
				continue;

			double lx = solver.x[i] - this.originX;
			double ly = solver.y[i] - this.originY;
			double lz = solver.z[i] - this.originZ;

			for (int a = cx - reach; a <= cx + reach + 1; a++) {
				double dx = a * cell - lx;

				for (int b = cy - reach; b <= cy + reach + 1; b++) {
					double dy = b * cell - ly;

					for (int c = cz - reach; c <= cz + reach + 1; c++) {
						double dz = c * cell - lz;
						double d2 = dx * dx + dy * dy + dz * dz;

						if (d2 >= r2)
							continue;

						double q = 1.0 - d2 / r2;
						int slot = pointAt(a, b, c, true);

						// the table is full: the rest of the cloud is not drawn rather than the lookup never ending
						if (slot < 0)
							break;

						this.value[slot] += (float)(q * q * q) * scale;
					}
				}
			}
		}

		float iso = p.threshold();

		// the cells that touch a corner with a field: each gets a vertex if the surface passes through it
		int pointsNow = this.points;

		for (int s = 0; s < pointsNow; s++) {
			int x = this.cornerX[s];
			int y = this.cornerY[s];
			int z = this.cornerZ[s];

			for (int a = 0; a <= 1; a++) {
				for (int b = 0; b <= 1; b++) {
					for (int c = 0; c <= 1; c++)
						vertexOf(x - a, y - b, z - c, iso);
				}
			}
		}

		// the corners the cells just looked at which had no field are absent, so they read as 0: nothing was added by the lookups

		this.solver = solver;
		this.solverInverse = 1.0 / radius;
		buildParticleGrid(solver);

		int limit = Math.max(1, maxTriangles);
		int pointsNow2 = this.points;

		for (int s = 0; s < pointsNow2 && this.triangleCount < limit; s++) {
			int x = this.cornerX[s];
			int y = this.cornerY[s];
			int z = this.cornerZ[s];
			float f = this.value[s];

			for (int axis = 0; axis < 3; axis++) {
				int hx = x + (axis == 0 ? 1 : 0);
				int hy = y + (axis == 1 ? 1 : 0);
				int hz = z + (axis == 2 ? 1 : 0);
				// the edge from this corner up the axis: found from here when the corner is the lower end ...
				emitEdge(x, y, z, axis, f, valueAt(hx, hy, hz), iso);

				// ... and from the upper end when the lower corner has no field at all (so it is never visited itself)
				int lx = x - (axis == 0 ? 1 : 0);
				int ly = y - (axis == 1 ? 1 : 0);
				int lz = z - (axis == 2 ? 1 : 0);

				if (pointAt(lx, ly, lz, false) < 0)
					emitEdge(lx, ly, lz, axis, 0f, f, iso);
			}
		}

		// smooth normals: the field's own gradient at each vertex, summed over the particles around it, so the shading does not show the grid
		for (int v = 0; v < this.vertexCount; v++)
			smoothNormal(v);

		this.solver = null;

		return this.triangleCount > 0;
	}

	// the particles hashed by cell, for the normals
	private FluidSolver solver;
	private double solverInverse;
	private long[] gridKey = new long[0];
	private int[] gridHead = new int[0];
	private boolean[] gridUsed = new boolean[0];
	private int[] gridNext = new int[0];

	private void buildParticleGrid(FluidSolver s) {
		int n = s.count;
		int size = Integer.highestOneBit(Math.max(16, n * 2 - 1)) << 1;

		if (this.gridKey.length < size) {
			this.gridKey = new long[size];
			this.gridHead = new int[size];
			this.gridUsed = new boolean[size];
		}

		if (this.gridNext.length < n)
			this.gridNext = new int[n];

		Arrays.fill(this.gridUsed, false);

		int mask = this.gridKey.length - 1;

		for (int i = 0; i < n; i++) {
			long k = key((int)Math.floor(s.x[i] * this.solverInverse), (int)Math.floor(s.y[i] * this.solverInverse), (int)Math.floor(s.z[i] * this.solverInverse));
			int h = hash(k, mask);

			while (this.gridUsed[h] && this.gridKey[h] != k)
				h = (h + 1) & mask;

			if (!this.gridUsed[h]) {
				this.gridUsed[h] = true;
				this.gridKey[h] = k;
				this.gridHead[h] = -1;
			}

			this.gridNext[i] = this.gridHead[h];
			this.gridHead[h] = i;
		}
	}

	/** The surface's normal at vertex {@code v}: minus the gradient of the metaball field there, which is what the surface is a level of */
	private void smoothNormal(int v) {
		FluidSolver s = this.solver;
		double r = s.params.radius();
		double r2 = r * r;
		double wx = this.originX + this.positions[v * 3];
		double wy = this.originY + this.positions[v * 3 + 1];
		double wz = this.originZ + this.positions[v * 3 + 2];
		int cx = (int)Math.floor(wx * this.solverInverse);
		int cy = (int)Math.floor(wy * this.solverInverse);
		int cz = (int)Math.floor(wz * this.solverInverse);
		int mask = this.gridKey.length - 1;
		double gx = 0;
		double gy = 0;
		double gz = 0;

		for (int a = -1; a <= 1; a++) {
			for (int b = -1; b <= 1; b++) {
				for (int c = -1; c <= 1; c++) {
					long k = key(cx + a, cy + b, cz + c);
					int h = hash(k, mask);

					while (this.gridUsed[h] && this.gridKey[h] != k)
						h = (h + 1) & mask;

					if (!this.gridUsed[h])
						continue;

					for (int i = this.gridHead[h]; i >= 0; i = this.gridNext[i]) {
						double dx = wx - s.x[i];
						double dy = wy - s.y[i];
						double dz = wz - s.z[i];
						double d2 = dx * dx + dy * dy + dz * dz;

						if (d2 >= r2)
							continue;

						// the bump is (1 - d2 / r2)^3: its gradient is -6 q^2 (p - x) / r2
						double q = 1.0 - d2 / r2;
						double k2 = -6.0 * q * q / r2 * s.scaleOf(i);

						gx += k2 * dx;
						gy += k2 * dy;
						gz += k2 * dz;
					}
				}
			}
		}

		double len = Math.sqrt(gx * gx + gy * gy + gz * gz);

		if (len > 1e-12) {
			this.normals[v * 3] = (float)(-gx / len);
			this.normals[v * 3 + 1] = (float)(-gy / len);
			this.normals[v * 3 + 2] = (float)(-gz / len);
		}
	}

	/** The vertex of the cell whose lowest corner is (x, y, z), created if the surface passes through it; -1 if not */
	private int vertexOf(int x, int y, int z, float iso) {
		double cell = this.cellSize;
		long k = key(x, y, z);
		int h = hash(k, this.cellMask);

		while (this.cellUsed[h]) {
			if (this.cellKeyTable[h] == k)
				return this.cellVertex[h];

			h = (h + 1) & this.cellMask;
		}

		int mask = 0;

		for (int i = 0; i < 8; i++) {
			float f = valueAt(x + (i & 1), y + ((i >> 1) & 1), z + ((i >> 2) & 1));

			this.corners[i] = f;

			if (f >= iso)
				mask |= 1 << i;
		}

		int vertex = -1;

		if (mask != 0 && mask != 255) {
			// where the surface crosses each of the cell's twelve edges, averaged
			double sx = 0;
			double sy = 0;
			double sz = 0;
			int crossings = 0;

			for (int axis = 0; axis < 3; axis++) {
				int a1 = (axis + 1) % 3;
				int a2 = (axis + 2) % 3;

				// the four edges along this axis start at the four corners that have 0 on it (corner bits: 1 is x, 2 is y, 4 is z)
				for (int e = 0; e < 4; e++) {
					int from = ((e & 1) << a1) | (((e >> 1) & 1) << a2);
					int to = from | (1 << axis);
					float fa = this.corners[from];
					float fb = this.corners[to];

					if ((fa >= iso) == (fb >= iso))
						continue;

					double t = (iso - fa) / (double)(fb - fa);

					sx += (from & 1) + (axis == 0 ? t : 0);
					sy += ((from >> 1) & 1) + (axis == 1 ? t : 0);
					sz += ((from >> 2) & 1) + (axis == 2 ? t : 0);
					crossings++;
				}
			}

			if (crossings > 0) {
				vertex = this.vertexCount;
				addVertex((float)((x + sx / crossings) * cell), (float)((y + sy / crossings) * cell), (float)((z + sz / crossings) * cell));

				// the surface's normal points from inside to outside, down the field: minus its gradient across the cell
				float gx = 0;
				float gy = 0;
				float gz = 0;

				for (int i = 0; i < 8; i++) {
					gx += ((i & 1) != 0 ? 1 : -1) * this.corners[i];
					gy += (((i >> 1) & 1) != 0 ? 1 : -1) * this.corners[i];
					gz += (((i >> 2) & 1) != 0 ? 1 : -1) * this.corners[i];
				}

				float len = (float)Math.sqrt(gx * gx + gy * gy + gz * gz);

				if (len < 1e-9f) {
					gx = 0;
					gy = 1;
					gz = 0;
					len = 1;
				}

				int at = vertex * 3;

				this.normals[at] = -gx / len;
				this.normals[at + 1] = -gy / len;
				this.normals[at + 2] = -gz / len;
			}
		}

		int slot = h;

		this.cellUsed[slot] = true;
		this.cellKeyTable[slot] = k;
		this.cellVertex[slot] = vertex;
		this.cells++;

		return vertex;
	}

	private void addVertex(float x, float y, float z) {
		int at = this.vertexCount * 3;

		if (at + 3 > this.positions.length) {
			int grown = Math.max(192, this.positions.length * 2);

			this.positions = Arrays.copyOf(this.positions, grown);
			this.normals = Arrays.copyOf(this.normals, grown);
		}

		this.positions[at] = x;
		this.positions[at + 1] = y;
		this.positions[at + 2] = z;
		this.vertexCount++;
	}

	/**
	 * The quad around the grid edge that starts at corner (x, y, z) and runs up {@code axis}, if the surface crosses it: the four cells that share the edge
	 * each have a vertex, and they are joined facing out of the inside end
	 */
	private void emitEdge(int x, int y, int z, int axis, float low, float high, float iso) {
		boolean lowInside = low >= iso;

		if (lowInside == (high >= iso))
			return;

		// the two other axes in cyclic order, so that u x v is the edge's axis
		int u = (axis + 1) % 3;
		int v = (axis + 2) % 3;
		int[] base = {x, y, z};
		int[] c = new int[4];

		for (int i = 0; i < 4; i++) {
			// (0,0), (-1,0), (-1,-1), (0,-1) around the edge, counter-clockwise seen from +axis
			int du = (i == 1 || i == 2) ? -1 : 0;
			int dv = (i == 2 || i == 3) ? -1 : 0;
			int[] at = base.clone();

			at[u] += du;
			at[v] += dv;
			c[i] = vertexOf(at[0], at[1], at[2], iso);
		}

		if (c[0] < 0 || c[1] < 0 || c[2] < 0 || c[3] < 0)
			return;

		// the inside is below: the surface faces up the axis, which the order above is counter-clockwise for; the inside above, reversed
		if (lowInside) {
			addTriangle(c[0], c[1], c[2]);
			addTriangle(c[0], c[2], c[3]);
		}
		else {
			addTriangle(c[0], c[2], c[1]);
			addTriangle(c[0], c[3], c[2]);
		}
	}

	private void addTriangle(int a, int b, int c) {
		int at = this.triangleCount * 3;

		if (at + 3 > this.indices.length)
			this.indices = Arrays.copyOf(this.indices, Math.max(96, this.indices.length * 2));

		this.indices[at] = a;
		this.indices[at + 1] = b;
		this.indices[at + 2] = c;
		this.triangleCount++;
	}
}
