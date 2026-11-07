package com.sandymandy.strut.physics.flesh;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * One region's cage, fitted to its mesh once: the box the mesh fills (from the 3rd to the 97th percentile on each axis, so a stray vertex does not stretch it),
 * how far the mesh reaches in each node's direction, where each node rests, which end is the free tip, and how firmly each node is held to the body. All of it
 * in the region's own frame (the root bone's space at rest), in blocks
 */
public final class FleshCage {
	/** The blend kernel's width: a node counts for a vertex within this angle of its direction */
	static final double KERNEL = Math.cos(0.42);
	/** The most nodes a vertex follows */
	static final int MOST_NODES = 6;
	/** The angle within which a vertex counts toward a node's reach when fitting */
	private static final double FIT = Math.cos(0.3);

	/** The middle of the box the mesh fills, and its half size on each axis */
	public final double[] centre = new double[3];
	public final double[] half = new double[3];
	/** How far the mesh reaches in each surface node's direction, as a share of the box */
	public final double[] radius = new double[FleshCageGeometry.SURFACE];
	/** Where each node rests, x, y, z each */
	public final double[] rest = new double[FleshCageGeometry.NODES * 3];
	/** The surface's normal at each surface node at rest, x, y, z each */
	public final double[] restNormals = new double[FleshCageGeometry.SURFACE * 3];
	/** How firmly each node is held where the animation puts it: 1 at the base and the top, 0 at the free end */
	public final double[] anchor = new double[FleshCageGeometry.NODES];
	/** The unit direction from the base to the free tip, and up (where the region hangs from) */
	public final double[] tip = new double[3];
	public final double[] up = new double[3];
	/** The point the region turns about when it swings: the middle of its base */
	public final double[] pivot = new double[3];
	/** How long the region is from its base to its tip */
	public final double length;
	/** The farthest a node may move, blocks */
	public final double limit;

	private FleshCage(double[][] points, @Nullable float[] tipAxis, @Nullable float[] upAxis, Matrix4f restMatrix, FleshParams params) {
		FleshCageGeometry geometry = FleshCageGeometry.get();
		int n = points.length;
		double[] axis = new double[n];

		for (int a = 0; a < 3; a++) {
			for (int k = 0; k < n; k++)
				axis[k] = points[k][a];

			Arrays.sort(axis);

			double lo = axis[Math.min(n - 1, (int)Math.floor(0.03 * (n - 1)))];
			double hi = axis[Math.min(n - 1, (int)Math.ceil(0.97 * (n - 1)))];

			this.centre[a] = (lo + hi) * 0.5;
			this.half[a] = Math.max(1.0 / 320.0, (hi - lo) * 0.5);
		}

		// how far the mesh reaches in each node's direction: the farthest of the vertices about that way
		boolean[] reached = new boolean[FleshCageGeometry.SURFACE];

		for (double[] p : points) {
			double qx = (p[0] - this.centre[0]) / this.half[0];
			double qy = (p[1] - this.centre[1]) / this.half[1];
			double qz = (p[2] - this.centre[2]) / this.half[2];
			double l = Math.sqrt(qx * qx + qy * qy + qz * qz);

			if (l < 1e-9)
				continue;

			for (int i : geometry.near(qx, qy, qz)) {
				if ((qx * geometry.dir[i * 3] + qy * geometry.dir[i * 3 + 1] + qz * geometry.dir[i * 3 + 2]) / l >= FIT) {
					this.radius[i] = reached[i] ? Math.max(this.radius[i], l) : l;
					reached[i] = true;
				}
			}
		}

		// a direction no vertex points along takes its neighbours' reach, a few rings deep; then each is evened out with the ones around it
		for (int pass = 0; pass < 8; pass++) {
			boolean[] next = reached.clone();

			for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
				if (reached[i])
					continue;

				double sum = 0;
				int count = 0;

				for (int j : geometry.neighbours[i]) {
					if (reached[j]) {
						sum += this.radius[j];
						count++;
					}
				}

				if (count > 0) {
					this.radius[i] = sum / count;
					next[i] = true;
				}
			}

			reached = next;
		}

		double[] smooth = new double[FleshCageGeometry.SURFACE];

		for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
			if (!reached[i])
				this.radius[i] = 1.0;
		}

		for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
			double sum = 0;

			for (int j : geometry.neighbours[i])
				sum += this.radius[j];

			smooth[i] = Math.max(0.25, Math.min(1.8, this.radius[i] * 0.7 + sum / geometry.neighbours[i].length * 0.3));
		}

		System.arraycopy(smooth, 0, this.radius, 0, smooth.length);

		for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
			for (int a = 0; a < 3; a++)
				this.rest[i * 3 + a] = this.centre[a] + this.half[a] * geometry.dir[i * 3 + a] * this.radius[i];
		}

		System.arraycopy(this.centre, 0, this.rest, FleshCageGeometry.CENTRE * 3, 3);
		geometry.normals(this.rest, this.restNormals);

		chooseAxes(tipAxis, upAxis, restMatrix);

		// depth along the tip: 0 at the free end, 1 at the base
		double front = -Double.MAX_VALUE;
		double back = Double.MAX_VALUE;

		for (int i = 0; i < FleshCageGeometry.NODES; i++) {
			double along = dot(this.rest, i, this.tip, this.centre);

			front = Math.max(front, along);
			back = Math.min(back, along);
		}

		this.length = Math.max(1.0 / 320.0, front - back);

		for (int a = 0; a < 3; a++)
			this.pivot[a] = this.centre[a] + this.tip[a] * back;

		float from = params.anchorFrom();
		float full = Math.max(from + 0.05f, params.anchorFull());

		for (int i = 0; i < FleshCageGeometry.NODES; i++) {
			double depth = (front - dot(this.rest, i, this.tip, this.centre)) / this.length;
			double upward = 0;

			if (i < FleshCageGeometry.SURFACE)
				upward = geometry.dir[i * 3] * this.up[0] + geometry.dir[i * 3 + 1] * this.up[1] + geometry.dir[i * 3 + 2] * this.up[2];

			double hold = params.anchorHold() * smoothstep(from, full, Math.abs(upward)) * smoothstep(params.depthFrom(), params.depthTo(), depth);

			this.anchor[i] = Math.max(depth * depth, hold);

			// the soft zone: outside its radius a node is sewn on, blending to free toward its middle
			if (params.softRadius() > 0f) {
				float[] zone = params.softCenter() != null ? params.softCenter() : new float[] {(float)this.centre[0], (float)this.centre[1], (float)this.centre[2]};
				double dx = this.rest[i * 3] - zone[0];
				double dy = this.rest[i * 3 + 1] - zone[1];
				double dz = this.rest[i * 3 + 2] - zone[2];
				double t = Math.min(1.0, Math.sqrt(dx * dx + dy * dy + dz * dz) / params.softRadius());

				this.anchor[i] = Math.max(this.anchor[i], t * t * (3 - 2 * t));
			}
		}

		double diagonal = Math.sqrt(this.half[0] * this.half[0] + this.half[1] * this.half[1] + this.half[2] * this.half[2]);

		this.limit = Math.min(diagonal, params.limit()) * params.reach();
	}

	/**
	 * Fits a cage to {@code points}, the vertices of the region's meshes in its own frame, blocks
	 *
	 * @param restMatrix The region's frame in the model's space at rest: what says which way is out from the body and which is up when the entry does not
	 */
	public static FleshCage fit(double[][] points, @Nullable float[] tip, @Nullable float[] up, Matrix4f restMatrix, FleshParams params) {
		return new FleshCage(points, tip, up, restMatrix, params);
	}

	/**
	 * The tip is the end of the region farthest out from the middle of the body, of the six ends of its box, unless the entry names it; up is the model's up
	 * in the region's frame, unless named
	 */
	private void chooseAxes(@Nullable float[] tipAxis, @Nullable float[] upAxis, Matrix4f restMatrix) {
		if (tipAxis != null) {
			set(this.tip, tipAxis[0], tipAxis[1], tipAxis[2]);
		}
		else {
			Vector3f middle = restMatrix.transformPosition((float)this.centre[0], (float)this.centre[1], (float)this.centre[2], new Vector3f());
			double best = -Double.MAX_VALUE;
			Vector3f end = new Vector3f();

			for (int a = 0; a < 3; a++) {
				for (int sign = -1; sign <= 1; sign += 2) {
					double[] at = this.centre.clone();

					at[a] += sign * this.half[a];
					restMatrix.transformPosition((float)at[0], (float)at[1], (float)at[2], end);

					// out from the body's upright middle line, and by more than the region's own middle is
					double out = Math.hypot(end.x, end.z) - Math.hypot(middle.x, middle.z);

					if (out > best) {
						best = out;
						set(this.tip, a == 0 ? sign : 0, a == 1 ? sign : 0, a == 2 ? sign : 0);
					}
				}
			}
		}

		if (upAxis != null) {
			set(this.up, upAxis[0], upAxis[1], upAxis[2]);
		}
		else {
			Vector3f modelUp = new Matrix4f(restMatrix).invert().transformDirection(new Vector3f(0, 1, 0));

			set(this.up, modelUp.x, modelUp.y, modelUp.z);
		}
	}

	/**
	 * How each of {@code points} (in the region's frame) follows the cage: the surface nodes about its direction, each by how near, and how much it moves with
	 * the centre instead (a vertex deep inside the region with the centre, one at the skin with the nodes there)
	 */
	public Binding bind(double[][] points) {
		FleshCageGeometry geometry = FleshCageGeometry.get();
		int n = points.length;
		int[] start = new int[n + 1];
		IntArrayList nodes = new IntArrayList();
		FloatArrayList weights = new FloatArrayList();
		float[] surface = new float[n];
		float[] softness = new float[n];

		for (int k = 0; k < n; k++) {
			double qx = (points[k][0] - this.centre[0]) / this.half[0];
			double qy = (points[k][1] - this.centre[1]) / this.half[1];
			double qz = (points[k][2] - this.centre[2]) / this.half[2];
			double s = Math.sqrt(qx * qx + qy * qy + qz * qz);

			start[k] = nodes.size();

			if (s < 1e-9) {
				surface[k] = 0f;
				softness[k] = (float)(1 - this.anchor[FleshCageGeometry.CENTRE]);

				continue;
			}

			double sum = 0;
			double reach = 0;
			double soft = 0;
			int from = nodes.size();

			for (int i : geometry.near(qx, qy, qz)) {
				double d = (qx * geometry.dir[i * 3] + qy * geometry.dir[i * 3 + 1] + qz * geometry.dir[i * 3 + 2]) / s;

				if (d <= KERNEL)
					continue;

				double kernel = (d - KERNEL) / (1 - KERNEL);

				kernel *= kernel;
				nodes.add(i);
				weights.add((float)kernel);
				sum += kernel;
				reach += kernel * this.radius[i];
				soft += kernel * (1 - this.anchor[i]);
			}

			if (sum <= 0) {
				// between the listed nodes and the kernel: the nearest one alone
				int nearest = 0;
				double best = -2;

				for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
					double d = qx * geometry.dir[i * 3] + qy * geometry.dir[i * 3 + 1] + qz * geometry.dir[i * 3 + 2];

					if (d > best) {
						best = d;
						nearest = i;
					}
				}

				nodes.add(nearest);
				weights.add(1f);
				sum = 1;
				reach = this.radius[nearest];
				soft = 1 - this.anchor[nearest];
			}

			// the strongest few: the kernel's tail adds nothing that shows, and every vertex reads its nodes every frame
			while (nodes.size() - from > MOST_NODES) {
				int weakest = from;

				for (int j = from + 1; j < nodes.size(); j++) {
					if (weights.getFloat(j) < weights.getFloat(weakest))
						weakest = j;
				}

				int node = nodes.getInt(weakest);
				double w = weights.getFloat(weakest);

				sum -= w;
				reach -= w * this.radius[node];
				soft -= w * (1 - this.anchor[node]);
				nodes.removeInt(weakest);
				weights.removeFloat(weakest);
			}

			for (int j = from; j < nodes.size(); j++)
				weights.set(j, (float)(weights.getFloat(j) / sum));

			double t = Math.min(1.0, s / Math.max(1e-6, reach / sum));

			surface[k] = (float)t;
			softness[k] = (float)(t * soft / sum + (1 - t) * (1 - this.anchor[FleshCageGeometry.CENTRE]));
		}

		start[n] = nodes.size();

		return new Binding(start, nodes.toIntArray(), weights.toFloatArray(), surface, softness);
	}

	/**
	 * How each vertex follows the cage: its nodes are {@code node[start[k]]} to {@code node[start[k + 1] - 1]} with their {@code weight}s (summing to 1), blended
	 * with the centre node by {@code 1 - surface[k]}; {@code softness[k]} is how free it is (0 held to the body, 1 free)
	 */
	public record Binding(int[] start, int[] node, float[] weight, float[] surface, float[] softness) {}

	private static double dot(double[] points, int i, double[] axis, double[] origin) {
		return (points[i * 3] - origin[0]) * axis[0] + (points[i * 3 + 1] - origin[1]) * axis[1] + (points[i * 3 + 2] - origin[2]) * axis[2];
	}

	private static void set(double[] out, double x, double y, double z) {
		double l = Math.sqrt(x * x + y * y + z * z);

		if (l < 1e-9) {
			out[0] = 0;
			out[1] = 1;
			out[2] = 0;

			return;
		}

		out[0] = x / l;
		out[1] = y / l;
		out[2] = z / l;
	}

	static double smoothstep(double from, double to, double x) {
		double t = Math.max(0, Math.min(1, (x - from) / Math.max(1e-9, to - from)));

		return t * t * (3 - 2 * t);
	}
}
