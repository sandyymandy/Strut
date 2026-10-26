package com.sandymandy.strut.physics.collision;

import java.util.Arrays;

/**
 * A signed distance field of a triangle mesh, on a regular grid: how far a point is from the surface (negative inside) and which way is out. This is what makes a
 * collider "mesh perfect": a point is tested against the model's own shape instead of a box or a capsule around it, and a lookup costs the same however detailed
 * the mesh is (a trilinear sample of eight numbers), so it is as fast as the simplest shape
 * <p>
 * Built once per bone from its geometry, off the game thread. The grid covers the mesh and a band around it (the field is only meaningful that far out: past it,
 * and deeper inside than that, it reads the band's value), at a cell of at most {@link #MIN_CELL} (half a model pixel) and at most {@code maxDim} cells along the
 * longest side. A cube is exact up to the cell; a curved mesh is as good as its triangles
 * <p>
 * Distance is the true distance to the nearest triangle (the closest point of every triangle near a cell). The sign is a vote of three axis-aligned rays
 * through the cell, each counting the surface it crosses: even outside, odd inside, two of three win. That is exact for a closed mesh (every cube) and
 * degrades gracefully for a mesh with a hole in it
 * <p>
 * Pure: no game classes
 */
public final class MeshSdf {
	/** The finest cell, in blocks: half of a model pixel */
	static final float MIN_CELL = 1f / 32f;

	/** The grid, from its first node: a node's place is {@code origin + index * cell}. Bone-local blocks, relative to the bone's pivot */
	final float ox;
	final float oy;
	final float oz;
	final float cell;
	final int nx;
	final int ny;
	final int nz;
	/** The distance at each node, {@code x} fastest, clamped to plus and minus {@link #band} */
	final float[] value;
	final float band;
	/** The bounding sphere of the grid, bone-local */
	final float cx;
	final float cy;
	final float cz;
	final float radius;

	private MeshSdf(float ox, float oy, float oz, float cell, int nx, int ny, int nz, float[] value, float band) {
		this.ox = ox;
		this.oy = oy;
		this.oz = oz;
		this.cell = cell;
		this.nx = nx;
		this.ny = ny;
		this.nz = nz;
		this.value = value;
		this.band = band;

		float ex = (nx - 1) * cell * 0.5f;
		float ey = (ny - 1) * cell * 0.5f;
		float ez = (nz - 1) * cell * 0.5f;

		this.cx = ox + ex;
		this.cy = oy + ey;
		this.cz = oz + ez;
		this.radius = (float)Math.sqrt(ex * ex + ey * ey + ez * ez);
	}

	/** How many numbers the field holds */
	int nodes() {
		return this.value.length;
	}

	// ---------------------------------------------------------------------------------------------
	// building
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param triangles Nine floats a triangle (three corners, x y z each), in the space the field is wanted in
	 * @param count How many triangles
	 * @param band How far from the surface the field is meaningful, in blocks
	 * @param maxDim The most cells along the longest side
	 * @return The field, or null if there is no geometry
	 */
	static MeshSdf build(float[] triangles, int count, float band, int maxDim) {
		if (count <= 0)
			return null;

		float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

		for (int i = 0; i < count * 9; i += 3) {
			for (int a = 0; a < 3; a++) {
				lo[a] = Math.min(lo[a], triangles[i + a]);
				hi[a] = Math.max(hi[a], triangles[i + a]);
			}
		}

		float extent = 0f;

		for (int a = 0; a < 3; a++)
			extent = Math.max(extent, hi[a] - lo[a] + 2f * band);

		float cell = Math.max(MIN_CELL, extent / Math.max(8, maxDim));
		float ox = lo[0] - band;
		float oy = lo[1] - band;
		float oz = lo[2] - band;
		int nx = (int)Math.ceil((hi[0] - lo[0] + 2f * band) / cell) + 1;
		int ny = (int)Math.ceil((hi[1] - lo[1] + 2f * band) / cell) + 1;
		int nz = (int)Math.ceil((hi[2] - lo[2] + 2f * band) / cell) + 1;
		float[] dist2 = new float[nx * ny * nz];

		Arrays.fill(dist2, band * band);
		nearest(triangles, count, ox, oy, oz, cell, nx, ny, nz, band, dist2);

		byte[] votes = new byte[dist2.length];

		vote(triangles, count, ox, oy, oz, cell, nx, ny, nz, votes, 0);
		vote(triangles, count, ox, oy, oz, cell, nx, ny, nz, votes, 1);
		vote(triangles, count, ox, oy, oz, cell, nx, ny, nz, votes, 2);

		float[] value = dist2;

		for (int i = 0; i < value.length; i++) {
			float d = (float)Math.sqrt(dist2[i]);

			value[i] = votes[i] >= 2 ? -d : d;
		}

		return new MeshSdf(ox, oy, oz, cell, nx, ny, nz, value, band);
	}

	/** The squared distance from each node to its nearest triangle, within the band: every triangle updates the nodes around it */
	private static void nearest(float[] t, int count, float ox, float oy, float oz, float cell, int nx, int ny, int nz, float band, float[] dist2) {
		double[] closest = new double[3];
		int[] from = new int[3];
		int[] to = new int[3];
		float[] origin = {ox, oy, oz};
		int[] size = {nx, ny, nz};

		for (int tri = 0; tri < count; tri++) {
			int b = tri * 9;

			for (int a = 0; a < 3; a++) {
				float lo = Math.min(t[b + a], Math.min(t[b + 3 + a], t[b + 6 + a])) - band;
				float hi = Math.max(t[b + a], Math.max(t[b + 3 + a], t[b + 6 + a])) + band;

				from[a] = Math.max(0, (int)Math.floor((lo - origin[a]) / cell));
				to[a] = Math.min(size[a] - 1, (int)Math.ceil((hi - origin[a]) / cell));
			}

			for (int k = from[2]; k <= to[2]; k++) {
				double pz = oz + k * cell;

				for (int j = from[1]; j <= to[1]; j++) {
					double py = oy + j * cell;
					int row = (k * ny + j) * nx;

					for (int i = from[0]; i <= to[0]; i++) {
						double d2 = closestPoint(ox + i * cell, py, pz, t[b], t[b + 1], t[b + 2], t[b + 3], t[b + 4], t[b + 5], t[b + 6], t[b + 7], t[b + 8], closest);

						if (d2 < dist2[row + i])
							dist2[row + i] = (float)d2;
					}
				}
			}
		}
	}

	/**
	 * The squared distance from p to triangle abc, and the nearest point in {@code out} (Ericson, Real-Time Collision Detection 5.1.5)
	 */
	static double closestPoint(double px, double py, double pz, double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz,
							   double[] out) {
		double abx = bx - ax;
		double aby = by - ay;
		double abz = bz - az;
		double acx = cx - ax;
		double acy = cy - ay;
		double acz = cz - az;
		double apx = px - ax;
		double apy = py - ay;
		double apz = pz - az;
		double d1 = abx * apx + aby * apy + abz * apz;
		double d2 = acx * apx + acy * apy + acz * apz;

		if (d1 <= 0 && d2 <= 0)
			return set(out, ax, ay, az, px, py, pz);

		double bpx = px - bx;
		double bpy = py - by;
		double bpz = pz - bz;
		double d3 = abx * bpx + aby * bpy + abz * bpz;
		double d4 = acx * bpx + acy * bpy + acz * bpz;

		if (d3 >= 0 && d4 <= d3)
			return set(out, bx, by, bz, px, py, pz);

		double vc = d1 * d4 - d3 * d2;

		if (vc <= 0 && d1 >= 0 && d3 <= 0) {
			double v = d1 / (d1 - d3);

			return set(out, ax + abx * v, ay + aby * v, az + abz * v, px, py, pz);
		}

		double cpx = px - cx;
		double cpy = py - cy;
		double cpz = pz - cz;
		double d5 = abx * cpx + aby * cpy + abz * cpz;
		double d6 = acx * cpx + acy * cpy + acz * cpz;

		if (d6 >= 0 && d5 <= d6)
			return set(out, cx, cy, cz, px, py, pz);

		double vb = d5 * d2 - d1 * d6;

		if (vb <= 0 && d2 >= 0 && d6 <= 0) {
			double w = d2 / (d2 - d6);

			return set(out, ax + acx * w, ay + acy * w, az + acz * w, px, py, pz);
		}

		double va = d3 * d6 - d5 * d4;

		if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
			double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));

			return set(out, bx + (cx - bx) * w, by + (cy - by) * w, bz + (cz - bz) * w, px, py, pz);
		}

		double denom = 1.0 / (va + vb + vc);
		double v = vb * denom;
		double w = vc * denom;

		return set(out, ax + abx * v + acx * w, ay + aby * v + acy * w, az + abz * v + acz * w, px, py, pz);
	}

	private static double set(double[] out, double x, double y, double z, double px, double py, double pz) {
		out[0] = x;
		out[1] = y;
		out[2] = z;

		double dx = px - x;
		double dy = py - y;
		double dz = pz - z;

		return dx * dx + dy * dy + dz * dz;
	}

	/**
	 * Counts, for every node, whether a ray along {@code axis} from outside the grid to the node has crossed the surface an odd number of times, adding one to
	 * {@code votes} if so
	 */
	private static void vote(float[] t, int count, float ox, float oy, float oz, float cell, int nx, int ny, int nz, byte[] votes, int axis) {
		int u = (axis + 1) % 3;
		int v = (axis + 2) % 3;
		float[] origin = {ox, oy, oz};
		int[] size = {nx, ny, nz};
		int nu = size[u];
		int nv = size[v];
		int na = size[axis];
		float[][] hits = new float[nu * nv][];
		int[] hitCount = new int[nu * nv];
		// the rays are nudged off the lattice, so one never runs exactly along an edge or through a corner that two triangles share
		double ju = cell * 0.0173;
		double jv = cell * 0.0291;

		for (int tri = 0; tri < count; tri++) {
			int b = tri * 9;
			double a0 = t[b + axis];
			double a1 = t[b + 3 + axis];
			double a2 = t[b + 6 + axis];
			double u0 = t[b + u];
			double u1 = t[b + 3 + u];
			double u2 = t[b + 6 + u];
			double v0 = t[b + v];
			double v1 = t[b + 3 + v];
			double v2 = t[b + 6 + v];
			// twice the signed area of the triangle seen along the ray: zero for one edge-on, which no ray can cross
			double area = (u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0);

			if (Math.abs(area) < 1e-12)
				continue;

			int j0 = Math.max(0, (int)Math.ceil((Math.min(u0, Math.min(u1, u2)) - origin[u] - ju) / cell));
			int j1 = Math.min(nu - 1, (int)Math.floor((Math.max(u0, Math.max(u1, u2)) - origin[u] - ju) / cell));
			int k0 = Math.max(0, (int)Math.ceil((Math.min(v0, Math.min(v1, v2)) - origin[v] - jv) / cell));
			int k1 = Math.min(nv - 1, (int)Math.floor((Math.max(v0, Math.max(v1, v2)) - origin[v] - jv) / cell));

			for (int k = k0; k <= k1; k++) {
				double pv = origin[v] + k * cell + jv;

				for (int j = j0; j <= j1; j++) {
					double pu = origin[u] + j * cell + ju;
					double w0 = ((u1 - pu) * (v2 - pv) - (u2 - pu) * (v1 - pv)) / area;
					double w1 = ((u2 - pu) * (v0 - pv) - (u0 - pu) * (v2 - pv)) / area;
					double w2 = 1.0 - w0 - w1;

					if (w0 < 0 || w1 < 0 || w2 < 0)
						continue;

					int row = k * nu + j;

					if (hits[row] == null)
						hits[row] = new float[4];
					else if (hitCount[row] == hits[row].length)
						hits[row] = Arrays.copyOf(hits[row], hitCount[row] * 2);

					hits[row][hitCount[row]++] = (float)(w0 * a0 + w1 * a1 + w2 * a2);
				}
			}
		}

		for (int k = 0; k < nv; k++) {
			for (int j = 0; j < nu; j++) {
				int row = k * nu + j;
				int n = hitCount[row];

				if (n == 0)
					continue;

				float[] list = hits[row];

				Arrays.sort(list, 0, n);

				int passed = 0;

				for (int a = 0; a < na; a++) {
					float at = origin[axis] + a * cell;

					while (passed < n && list[passed] < at)
						passed++;

					if ((passed & 1) == 1) {
						int index = axis == 0 ? (k * ny + j) * nx + a : axis == 1 ? (j * ny + a) * nx + k : (a * ny + k) * nx + j;

						votes[index]++;
					}
				}
			}
		}
	}

	// ---------------------------------------------------------------------------------------------
	// asking
	// ---------------------------------------------------------------------------------------------

	/**
	 * The signed distance at a bone-local point, about {@code band} when it is far from the surface (outside the grid, or deep inside)
	 */
	float distance(double x, double y, double z) {
		double fx = (x - this.ox) / this.cell;
		double fy = (y - this.oy) / this.cell;
		double fz = (z - this.oz) / this.cell;

		if (fx < 0 || fy < 0 || fz < 0 || fx >= this.nx - 1 || fy >= this.ny - 1 || fz >= this.nz - 1)
			return this.band;

		int i = (int)fx;
		int j = (int)fy;
		int k = (int)fz;
		float tx = (float)(fx - i);
		float ty = (float)(fy - j);
		float tz = (float)(fz - k);
		int s = (k * this.ny + j) * this.nx + i;
		int ys = this.nx;
		int zs = this.nx * this.ny;
		float[] v = this.value;
		float c00 = v[s] + (v[s + 1] - v[s]) * tx;
		float c10 = v[s + ys] + (v[s + ys + 1] - v[s + ys]) * tx;
		float c01 = v[s + zs] + (v[s + zs + 1] - v[s + zs]) * tx;
		float c11 = v[s + ys + zs] + (v[s + ys + zs + 1] - v[s + ys + zs]) * tx;
		float c0 = c00 + (c10 - c00) * ty;
		float c1 = c01 + (c11 - c01) * ty;

		return c0 + (c1 - c0) * tz;
	}

	/**
	 * The signed distance at a bone-local point and, in {@code normal}, the unit direction in which it grows (out of the surface). The normal is left alone and
	 * the distance is the band when the point is outside the grid
	 */
	float distanceAndNormal(double x, double y, double z, double[] normal) {
		double fx = (x - this.ox) / this.cell;
		double fy = (y - this.oy) / this.cell;
		double fz = (z - this.oz) / this.cell;

		if (fx < 0 || fy < 0 || fz < 0 || fx >= this.nx - 1 || fy >= this.ny - 1 || fz >= this.nz - 1)
			return this.band;

		int i = (int)fx;
		int j = (int)fy;
		int k = (int)fz;
		float tx = (float)(fx - i);
		float ty = (float)(fy - j);
		float tz = (float)(fz - k);
		int s = (k * this.ny + j) * this.nx + i;
		int ys = this.nx;
		int zs = this.nx * this.ny;
		float[] v = this.value;
		float v000 = v[s];
		float v100 = v[s + 1];
		float v010 = v[s + ys];
		float v110 = v[s + ys + 1];
		float v001 = v[s + zs];
		float v101 = v[s + zs + 1];
		float v011 = v[s + ys + zs];
		float v111 = v[s + ys + zs + 1];

		// the analytic gradient of the trilinear blend
		double gx = ((v100 - v000) * (1 - ty) + (v110 - v010) * ty) * (1 - tz) + ((v101 - v001) * (1 - ty) + (v111 - v011) * ty) * tz;
		double gy = ((v010 - v000) * (1 - tx) + (v110 - v100) * tx) * (1 - tz) + ((v011 - v001) * (1 - tx) + (v111 - v101) * tx) * tz;
		double gz = ((v001 - v000) * (1 - tx) + (v101 - v100) * tx) * (1 - ty) + ((v011 - v010) * (1 - tx) + (v111 - v110) * tx) * ty;
		double len = Math.sqrt(gx * gx + gy * gy + gz * gz);

		if (len > 1e-9) {
			normal[0] = gx / len;
			normal[1] = gy / len;
			normal[2] = gz / len;
		}
		else {
			// deep inside, where the field is flat: away from the middle of the grid
			double dx = x - this.cx;
			double dy = y - this.cy;
			double dz = z - this.cz;
			double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);

			normal[0] = dl > 1e-9 ? dx / dl : 0;
			normal[1] = dl > 1e-9 ? dy / dl : 1;
			normal[2] = dl > 1e-9 ? dz / dl : 0;
		}

		float c00 = v000 + (v100 - v000) * tx;
		float c10 = v010 + (v110 - v010) * tx;
		float c01 = v001 + (v101 - v001) * tx;
		float c11 = v011 + (v111 - v011) * tx;
		float c0 = c00 + (c10 - c00) * ty;
		float c1 = c01 + (c11 - c01) * ty;

		return c0 + (c1 - c0) * tz;
	}
}
