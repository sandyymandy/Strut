package com.sandymandy.strut.physics.collision;

import java.util.Arrays;
import java.util.List;

/**
 * A broad phase for a few hundred particles against a hundred shapes: the shapes are put in a grid of half-block cells, each in every cell its bounds (grown by a
 * margin) touch, so a particle looks at the handful of shapes in its own cell instead of all of them. A shape too big for that (a long floor box) goes in a list
 * every particle checks. The margin is what a particle may move or reach in the step the grid is used for, so the answer is the same as testing every shape
 * <p>
 * Built once a frame from the shapes of that frame; allocation is the arrays of the build, nothing per query. Pure
 */
public final class ShapeGrid {
	static final int[] NONE = new int[0];
	private static final double CELL = 0.5;
	/** A shape that would fill more cells than this is tested by everything instead */
	private static final int MAX_CELLS = 512;

	public final List<Collider.Placed> shapes;
	/** The shapes every particle must test */
	public final int[] global;

	private final long[] keys;
	private final int[][] lists;
	private final boolean[] used;
	private final int mask;

	private ShapeGrid(List<Collider.Placed> shapes, int[] global, long[] keys, int[][] lists, boolean[] used) {
		this.shapes = shapes;
		this.global = global;
		this.keys = keys;
		this.lists = lists;
		this.used = used;
		this.mask = keys.length - 1;
	}

	private static long key(int x, int y, int z) {
		return ((long)(x & 0x1FFFFF)) | ((long)(y & 0x1FFFFF) << 21) | ((long)(z & 0x1FFFFF) << 42);
	}

	private static int hash(long key, int mask) {
		return (int)((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
	}

	/**
	 * @param margin How far from a shape's own bounds a particle may be and still need testing against it, in blocks
	 */
	public static ShapeGrid of(List<Collider.Placed> shapes, double margin) {
		int n = shapes.size();
		int[][] range = new int[n][];
		int[] globals = new int[n];
		int globalCount = 0;
		long total = 0;
		double[] lo = new double[3];
		double[] hi = new double[3];

		// first, which cells each shape needs: a shape that would need many is tested by every particle instead
		for (int s = 0; s < n; s++) {
			bounds(shapes.get(s), lo, hi, margin);

			int[] r = {(int)Math.floor(lo[0] / CELL), (int)Math.floor(lo[1] / CELL), (int)Math.floor(lo[2] / CELL), (int)Math.floor(hi[0] / CELL),
					(int)Math.floor(hi[1] / CELL), (int)Math.floor(hi[2] / CELL)};
			long cells = (long)(r[3] - r[0] + 1) * (r[4] - r[1] + 1) * (r[5] - r[2] + 1);

			if (cells > MAX_CELLS) {
				globals[globalCount++] = s;

				continue;
			}

			range[s] = r;
			total += cells;
		}

		int size = Integer.highestOneBit((int)Math.max(16, Math.min(1 << 24, total * 2 - 1))) << 1;
		long[] keys = new long[size];
		int[][] lists = new int[size][];
		boolean[] used = new boolean[size];
		int mask = size - 1;
		int[] counts = new int[size];

		for (int s = 0; s < n; s++) {
			int[] r = range[s];

			if (r == null)
				continue;

			for (int x = r[0]; x <= r[3]; x++) {
				for (int y = r[1]; y <= r[4]; y++) {
					for (int z = r[2]; z <= r[5]; z++) {
						long k = key(x, y, z);
						int h = hash(k, mask);

						while (used[h] && keys[h] != k)
							h = (h + 1) & mask;

						if (!used[h]) {
							used[h] = true;
							keys[h] = k;
							lists[h] = new int[4];
						}

						if (counts[h] == lists[h].length)
							lists[h] = Arrays.copyOf(lists[h], counts[h] * 2);

						lists[h][counts[h]++] = s;
					}
				}
			}
		}

		for (int h = 0; h < size; h++) {
			if (used[h] && lists[h].length != counts[h])
				lists[h] = Arrays.copyOf(lists[h], counts[h]);
		}

		return new ShapeGrid(shapes, Arrays.copyOf(globals, globalCount), keys, lists, used);
	}

	/** The indices of the shapes that may touch a particle at (x, y, z): those in its cell. Do not modify */
	public int[] at(double x, double y, double z) {
		long k = key((int)Math.floor(x / CELL), (int)Math.floor(y / CELL), (int)Math.floor(z / CELL));
		int h = hash(k, this.mask);

		while (this.used[h]) {
			if (this.keys[h] == k)
				return this.lists[h];

			h = (h + 1) & this.mask;
		}

		return NONE;
	}

	private static void bounds(Collider.Placed s, double[] lo, double[] hi, double margin) {
		for (int a = 0; a < 3; a++) {
			double min;
			double max;

			switch (s.shape) {
				case CAPSULE -> {
					min = Math.min(s.p[a], s.q[a]) - s.radius;
					max = Math.max(s.p[a], s.q[a]) + s.radius;
				}
				case BOX -> {
					double reach = Math.sqrt(s.half[0] * s.half[0] + s.half[1] * s.half[1] + s.half[2] * s.half[2]);

					// an axis-aligned box (a block) is exactly its extents: the long floor is not a sphere around its corner
					boolean aligned = s.axes.m00() == 1f && s.axes.m11() == 1f && s.axes.m22() == 1f;

					min = s.p[a] - (aligned ? s.half[a] : reach);
					max = s.p[a] + (aligned ? s.half[a] : reach);
				}
				default -> {
					min = s.p[a] - s.radius;
					max = s.p[a] + s.radius;
				}
			}

			lo[a] = min - margin;
			hi[a] = max + margin;
		}
	}
}
