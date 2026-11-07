package com.sandymandy.strut.physics.flesh;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape every soft region's cage is made from, the same for all of them and built once: an icosahedron split twice (162 points on the unit sphere, 480
 * edges) and one more node at the centre. Also the links between nodes, each surface node's neighbours, and a lookup from any direction to the nodes near it
 * (a cube map of 6 faces of 12 by 12 cells, each listing the nodes within reach of its middle), so finding the nodes around a vertex is a table read and a few
 * dot products rather than a search of all of them
 */
public final class FleshCageGeometry {
	/** Nodes on the surface */
	public static final int SURFACE = 162;
	/** All nodes: the surface ones, then the centre */
	public static final int NODES = SURFACE + 1;
	/** The centre node's index */
	public static final int CENTRE = SURFACE;
	private static final int CELLS = 12;
	/** How far from a cell's middle a node may be to be listed for it, radians: the widest search (the blend kernel) plus a cell's own half width */
	private static final double LOOKUP_REACH = 0.58;

	private static FleshCageGeometry instance;

	/** x, y, z of each surface node's unit direction */
	public final double[] dir;
	/** Each link's two ends: every edge, then a spoke from the centre to every surface node */
	public final int[] linkA;
	public final int[] linkB;
	/** Each surface node's neighbours along the edges */
	public final int[][] neighbours;
	/** The surface's triangles, three nodes each, wound outward */
	public final int[] faces;
	private final int[][] cells;

	private FleshCageGeometry() {
		List<double[]> points = new ArrayList<>();
		double t = (1.0 + Math.sqrt(5.0)) / 2.0;
		double[][] base = {{-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t}, {0, -1, -t}, {0, 1, -t}, {t, 0, -1}, {t, 0, 1}, {-t, 0, -1},
				{-t, 0, 1}};

		for (double[] p : base)
			points.add(unit(p[0], p[1], p[2]));

		int[][] faces = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8}, {3, 9, 4}, {3, 4, 2},
				{3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};

		for (int level = 0; level < 2; level++) {
			Long2IntOpenHashMap middles = new Long2IntOpenHashMap();
			int[][] split = new int[faces.length * 4][];
			int f = 0;

			for (int[] face : faces) {
				int ab = middle(face[0], face[1], points, middles);
				int bc = middle(face[1], face[2], points, middles);
				int ca = middle(face[2], face[0], points, middles);

				split[f++] = new int[] {face[0], ab, ca};
				split[f++] = new int[] {face[1], bc, ab};
				split[f++] = new int[] {face[2], ca, bc};
				split[f++] = new int[] {ab, bc, ca};
			}

			faces = split;
		}

		this.dir = new double[SURFACE * 3];

		for (int i = 0; i < SURFACE; i++)
			System.arraycopy(points.get(i), 0, this.dir, i * 3, 3);

		this.faces = new int[faces.length * 3];

		for (int f = 0; f < faces.length; f++) {
			double[] a = points.get(faces[f][0]);
			double[] b = points.get(faces[f][1]);
			double[] c = points.get(faces[f][2]);
			double nx = (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]);
			double ny = (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]);
			double nz = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
			boolean outward = nx * (a[0] + b[0] + c[0]) + ny * (a[1] + b[1] + c[1]) + nz * (a[2] + b[2] + c[2]) > 0;

			this.faces[f * 3] = faces[f][0];
			this.faces[f * 3 + 1] = outward ? faces[f][1] : faces[f][2];
			this.faces[f * 3 + 2] = outward ? faces[f][2] : faces[f][1];
		}

		// the edges, each once, and the neighbours they make
		Long2IntOpenHashMap seen = new Long2IntOpenHashMap();
		IntArrayList a = new IntArrayList();
		IntArrayList b = new IntArrayList();
		List<IntArrayList> around = new ArrayList<>();

		for (int i = 0; i < SURFACE; i++)
			around.add(new IntArrayList());

		for (int[] face : faces) {
			for (int e = 0; e < 3; e++) {
				int p = face[e];
				int q = face[(e + 1) % 3];
				long key = (long)Math.min(p, q) << 32 | Math.max(p, q);

				if (seen.containsKey(key))
					continue;

				seen.put(key, 1);
				a.add(p);
				b.add(q);
				around.get(p).add(q);
				around.get(q).add(p);
			}
		}

		for (int i = 0; i < SURFACE; i++) {
			a.add(CENTRE);
			b.add(i);
		}

		this.linkA = a.toIntArray();
		this.linkB = b.toIntArray();
		this.neighbours = new int[SURFACE][];

		for (int i = 0; i < SURFACE; i++)
			this.neighbours[i] = around.get(i).toIntArray();

		this.cells = new int[6 * CELLS * CELLS][];

		double reach = Math.cos(LOOKUP_REACH);
		double[] middle = new double[3];

		for (int face = 0; face < 6; face++) {
			for (int u = 0; u < CELLS; u++) {
				for (int v = 0; v < CELLS; v++) {
					cellDirection(face, (u + 0.5) / CELLS * 2 - 1, (v + 0.5) / CELLS * 2 - 1, middle);

					IntArrayList near = new IntArrayList();

					for (int i = 0; i < SURFACE; i++) {
						if (middle[0] * this.dir[i * 3] + middle[1] * this.dir[i * 3 + 1] + middle[2] * this.dir[i * 3 + 2] >= reach)
							near.add(i);
					}

					this.cells[(face * CELLS + u) * CELLS + v] = near.toIntArray();
				}
			}
		}
	}

	public static synchronized FleshCageGeometry get() {
		if (instance == null)
			instance = new FleshCageGeometry();

		return instance;
	}

	/** The surface nodes within {@value #LOOKUP_REACH} radians (give or take a cell) of the direction {@code (x, y, z)}, which need not be unit */
	public int[] near(double x, double y, double z) {
		double ax = Math.abs(x);
		double ay = Math.abs(y);
		double az = Math.abs(z);
		int face;
		double u;
		double v;

		if (ax >= ay && ax >= az) {
			face = x > 0 ? 0 : 1;
			u = y / ax;
			v = z / ax;
		}
		else if (ay >= az) {
			face = y > 0 ? 2 : 3;
			u = x / ay;
			v = z / ay;
		}
		else {
			face = z > 0 ? 4 : 5;
			u = x / az;
			v = y / az;
		}

		if (!(u == u) || !(v == v))
			return this.cells[0];

		int cu = Math.max(0, Math.min(CELLS - 1, (int)((u + 1) * 0.5 * CELLS)));
		int cv = Math.max(0, Math.min(CELLS - 1, (int)((v + 1) * 0.5 * CELLS)));

		return this.cells[(face * CELLS + cu) * CELLS + cv];
	}

	/**
	 * The unit normal at each surface node of a cage whose nodes are at {@code points} (x, y, z each): the faces around it, each by its area, into
	 * {@code out}
	 */
	public void normals(double[] points, double[] out) {
		java.util.Arrays.fill(out, 0, SURFACE * 3, 0);

		for (int f = 0; f < this.faces.length; f += 3) {
			int a = this.faces[f] * 3;
			int b = this.faces[f + 1] * 3;
			int c = this.faces[f + 2] * 3;
			double ux = points[b] - points[a];
			double uy = points[b + 1] - points[a + 1];
			double uz = points[b + 2] - points[a + 2];
			double vx = points[c] - points[a];
			double vy = points[c + 1] - points[a + 1];
			double vz = points[c + 2] - points[a + 2];
			double nx = uy * vz - uz * vy;
			double ny = uz * vx - ux * vz;
			double nz = ux * vy - uy * vx;

			for (int corner = 0; corner < 3; corner++) {
				int at = this.faces[f + corner] * 3;

				out[at] += nx;
				out[at + 1] += ny;
				out[at + 2] += nz;
			}
		}

		for (int i = 0; i < SURFACE; i++) {
			double length = Math.sqrt(out[i * 3] * out[i * 3] + out[i * 3 + 1] * out[i * 3 + 1] + out[i * 3 + 2] * out[i * 3 + 2]);

			if (length > 1e-18) {
				out[i * 3] /= length;
				out[i * 3 + 1] /= length;
				out[i * 3 + 2] /= length;
			}
		}
	}

	private static void cellDirection(int face, double u, double v, double[] out) {
		double x;
		double y;
		double z;

		switch (face) {
			case 0 -> { x = 1; y = u; z = v; }
			case 1 -> { x = -1; y = u; z = v; }
			case 2 -> { x = u; y = 1; z = v; }
			case 3 -> { x = u; y = -1; z = v; }
			case 4 -> { x = u; y = v; z = 1; }
			default -> { x = u; y = v; z = -1; }
		}

		double[] unit = unit(x, y, z);

		System.arraycopy(unit, 0, out, 0, 3);
	}

	private static int middle(int p, int q, List<double[]> points, Long2IntOpenHashMap middles) {
		long key = (long)Math.min(p, q) << 32 | Math.max(p, q);

		if (middles.containsKey(key))
			return middles.get(key);

		double[] a = points.get(p);
		double[] b = points.get(q);

		points.add(unit(a[0] + b[0], a[1] + b[1], a[2] + b[2]));
		middles.put(key, points.size() - 1);

		return points.size() - 1;
	}

	private static double[] unit(double x, double y, double z) {
		double l = Math.sqrt(x * x + y * y + z * z);

		return new double[] {x / l, y / l, z / l};
	}
}
