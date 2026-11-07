package com.sandymandy.strut.physics.collision;

import java.util.List;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * A collision shape attached to a bone: a sphere, a capsule or an oriented box. A particle is a sphere, so every test is
 * "is this ball inside that shape, and where does it have to go to leave". Pure maths on doubles in whatever space the caller works in
 * <p>
 * Shapes are described in the bone's own space, in model units (sixteenths of a block), relative to the bone's pivot, and turn with the bone. {@link Placed}
 * is the shape once the bone's place is known: blocks, in the space the solver runs in
 *
 * @param bone The bone the shape rides on
 * @param shape The kind of shape
 * @param a Sphere: the centre. Capsule: one end. Box: the centre
 * @param b Capsule: the other end. Box: the full size. Unused for a sphere
 * @param radius Sphere and capsule radius in model units
 * @param layer Which group this shape belongs to; a chain only collides with the layers it lists
 * @param rotation A box's turn on its bone, degrees about x, y and z (applied z, then y, then x); zero for the other shapes, whose ends say which way they point
 */
public record Collider(String bone, Shape shape, Vector3f a, Vector3f b, float radius, String layer, Vector3f rotation) {
	/**
	 * {@code MESH} is a bone's own geometry as a signed distance field ({@link MeshSdf}): the shape of the model itself rather than a box or a capsule around
	 * it. Made from the model, never authored: {@code a}, {@code b} and {@code radius} mean nothing for it
	 */
	public enum Shape { SPHERE, CAPSULE, BOX, MESH }

	public Collider(String bone, Shape shape, Vector3f a, Vector3f b, float radius, String layer) {
		this(bone, shape, a, b, radius, layer, new Vector3f());
	}

	/** The box's turn as a matrix (identity when it has none) */
	Matrix3f turn() {
		return new Matrix3f().rotationZYX((float)Math.toRadians(this.rotation.z), (float)Math.toRadians(this.rotation.y), (float)Math.toRadians(this.rotation.x));
	}

	/**
	 * The shape in solver space: an orientation (bone space to solver space, for a box) and blocks. Reused every step, so nothing is allocated
	 */
	public static final class Placed {
		public Shape shape;
		public final double[] p = new double[3];
		public final double[] q = new double[3];
		public double radius;
		/** A box's half extents in its own space, and its orientation (its columns are its axes in solver space) */
		public final double[] half = new double[3];
		/** Scratch for a field's direction, so asking it allocates nothing and looks nothing up (shapes are used from the render thread only) */
		final double[] normal = new double[3];
		public final Matrix3f axes = new Matrix3f();
		/** A mesh's field. Its bone's pivot in solver space is {@link #q}, its {@link #axes} turn the bone's space into the solver's, and {@link #p} and {@link #radius} are its bounding sphere */
		MeshSdf sdf;
		/** The entity this shape belongs to, or -1: what lets a drop that has just left a body not be pushed out of it again */
		public int owner = -1;
		public String layer;
		/** Made from the model's own geometry (see {@link AutoColliders}), not authored: such a shape that already overlaps a part at rest is ignored for it */
		boolean generated;
	}

	/** Whether {@code s} could touch anything inside the box {@code lo}..{@code hi}: a cheap, generous test, for cutting a long list of shapes down to those near a part */
	public static boolean near(Placed s, double[] lo, double[] hi) {
		for (int a = 0; a < 3; a++) {
			if (high(s, a) < lo[a] || low(s, a) > hi[a])
				return false;
		}

		return true;
	}

	/**
	 * How far the point is from the box round {@code s} (as {@link #near} measures it), 0 inside it: never more than the distance to the shape itself, so a
	 * point farther than some reach from the box is farther than that from the shape
	 */
	public static double boxDistance(Placed s, double x, double y, double z) {
		double dx = Math.max(0, Math.max(low(s, 0) - x, x - high(s, 0)));
		double dy = Math.max(0, Math.max(low(s, 1) - y, y - high(s, 1)));
		double dz = Math.max(0, Math.max(low(s, 2) - z, z - high(s, 2)));

		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** A ball round all of {@code s} into {@code out}: its middle, then its radius. Unlike the box round it, it is the same however the shape is turned */
	public static void bound(Placed s, double[] out) {
		switch (s.shape) {
			case SPHERE, MESH -> {
				out[0] = s.p[0];
				out[1] = s.p[1];
				out[2] = s.p[2];
				out[3] = s.radius;
			}
			case CAPSULE -> {
				double dx = s.q[0] - s.p[0];
				double dy = s.q[1] - s.p[1];
				double dz = s.q[2] - s.p[2];

				out[0] = (s.p[0] + s.q[0]) * 0.5;
				out[1] = (s.p[1] + s.q[1]) * 0.5;
				out[2] = (s.p[2] + s.q[2]) * 0.5;
				out[3] = Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.5 + s.radius;
			}
			case BOX -> {
				out[0] = s.p[0];
				out[1] = s.p[1];
				out[2] = s.p[2];
				out[3] = Math.sqrt(s.half[0] * s.half[0] + s.half[1] * s.half[1] + s.half[2] * s.half[2]);
			}
		}
	}

	/** Grows the box {@code lo}..{@code hi} to take in all of {@code s}, as generously as {@link #near} measures it */
	public static void grow(Placed s, double[] lo, double[] hi) {
		for (int a = 0; a < 3; a++) {
			lo[a] = Math.min(lo[a], low(s, a));
			hi[a] = Math.max(hi[a], high(s, a));
		}
	}

	private static double low(Placed s, int a) {
		if (s.shape == Shape.MESH && s.sdf != null)
			return gridMiddle(s, a) - gridHalf(s, a);

		return switch (s.shape) {
			case SPHERE, MESH -> s.p[a] - s.radius;
			case CAPSULE -> Math.min(s.p[a], s.q[a]) - s.radius;
			case BOX -> s.p[a] - Math.sqrt(s.half[0] * s.half[0] + s.half[1] * s.half[1] + s.half[2] * s.half[2]);
		};
	}

	private static double high(Placed s, int a) {
		if (s.shape == Shape.MESH && s.sdf != null)
			return gridMiddle(s, a) + gridHalf(s, a);

		return switch (s.shape) {
			case SPHERE, MESH -> s.p[a] + s.radius;
			case CAPSULE -> Math.max(s.p[a], s.q[a]) + s.radius;
			case BOX -> s.p[a] + Math.sqrt(s.half[0] * s.half[0] + s.half[1] * s.half[1] + s.half[2] * s.half[2]);
		};
	}

	/**
	 * The middle of a mesh field's grid along world axis {@code a}: outside the grid the field is only its band, nothing presses there. Its box is the grid
	 * turned into place, far tighter than the box round its ball for a long thin bone
	 */
	private static double gridMiddle(Placed s, int a) {
		MeshSdf f = s.sdf;
		Matrix3f m = s.axes;
		double cx = f.ox + (f.nx - 1) * f.cell * 0.5;
		double cy = f.oy + (f.ny - 1) * f.cell * 0.5;
		double cz = f.oz + (f.nz - 1) * f.cell * 0.5;

		// the bone's axes are the columns: a point of the grid is q + x column 0 + y column 1 + z column 2
		return s.q[a] + m.get(0, a) * cx + m.get(1, a) * cy + m.get(2, a) * cz;
	}

	/** Half the size of a mesh field's grid along world axis {@code a}, see {@link #gridMiddle} */
	private static double gridHalf(Placed s, int a) {
		MeshSdf f = s.sdf;
		Matrix3f m = s.axes;

		return Math.abs(m.get(0, a)) * (f.nx - 1) * f.cell * 0.5 + Math.abs(m.get(1, a)) * (f.ny - 1) * f.cell * 0.5 + Math.abs(m.get(2, a)) * (f.nz - 1) * f.cell * 0.5;
	}

	/**
	 * How far the point is outside {@code s} (negative inside), blocks, with the way out written to {@code gradient} as a unit vector. A mesh's field only
	 * reaches a band round the surface: farther out than that it reports the band, which is far enough for anything that asks
	 */
	/** {@link #signedDistance} without the way out: for a mesh's field a quarter of the work, where only how deep is asked */
	public static double distance(Placed s, double x, double y, double z) {
		if (s.shape != Shape.MESH)
			return signedDistance(s, x, y, z, s.normal);

		MeshSdf sdf = s.sdf;

		if (sdf == null)
			return Double.MAX_VALUE;

		Matrix3f m = s.axes;
		double dx = x - s.q[0];
		double dy = y - s.q[1];
		double dz = z - s.q[2];

		return sdf.distance(dx * m.m00() + dy * m.m01() + dz * m.m02(), dx * m.m10() + dy * m.m11() + dz * m.m12(), dx * m.m20() + dy * m.m21() + dz * m.m22());
	}

	public static double signedDistance(Placed s, double x, double y, double z, double[] gradient) {
		switch (s.shape) {
			case SPHERE -> {
				return fromPoint(x, y, z, s.p[0], s.p[1], s.p[2], gradient) - s.radius;
			}
			case CAPSULE -> {
				double abx = s.q[0] - s.p[0];
				double aby = s.q[1] - s.p[1];
				double abz = s.q[2] - s.p[2];
				double len2 = abx * abx + aby * aby + abz * abz;
				double t = len2 < 1e-12 ? 0 : ((x - s.p[0]) * abx + (y - s.p[1]) * aby + (z - s.p[2]) * abz) / len2;

				t = Math.max(0, Math.min(1, t));

				return fromPoint(x, y, z, s.p[0] + abx * t, s.p[1] + aby * t, s.p[2] + abz * t, gradient) - s.radius;
			}
			case BOX -> {
				Matrix3f m = s.axes;
				double dx = x - s.p[0];
				double dy = y - s.p[1];
				double dz = z - s.p[2];
				double l0 = dx * m.m00() + dy * m.m01() + dz * m.m02();
				double l1 = dx * m.m10() + dy * m.m11() + dz * m.m12();
				double l2 = dx * m.m20() + dy * m.m21() + dz * m.m22();
				double q0 = Math.abs(l0) - s.half[0];
				double q1 = Math.abs(l1) - s.half[1];
				double q2 = Math.abs(l2) - s.half[2];
				double g0;
				double g1;
				double g2;
				double distance;

				if (q0 > 0 || q1 > 0 || q2 > 0) {
					g0 = Math.max(q0, 0) * Math.signum(l0);
					g1 = Math.max(q1, 0) * Math.signum(l1);
					g2 = Math.max(q2, 0) * Math.signum(l2);
					distance = Math.sqrt(g0 * g0 + g1 * g1 + g2 * g2);
					g0 /= distance;
					g1 /= distance;
					g2 /= distance;
				}
				else {
					// inside: out through the nearest face
					distance = Math.max(q0, Math.max(q1, q2));
					g0 = distance == q0 ? Math.signum(l0) : 0;
					g1 = distance != q0 && distance == q1 ? Math.signum(l1) : 0;
					g2 = distance != q0 && distance != q1 ? Math.signum(l2) : 0;

					if (g0 == 0 && g1 == 0 && g2 == 0)
						g1 = 1;
				}

				gradient[0] = m.m00() * g0 + m.m10() * g1 + m.m20() * g2;
				gradient[1] = m.m01() * g0 + m.m11() * g1 + m.m21() * g2;
				gradient[2] = m.m02() * g0 + m.m12() * g1 + m.m22() * g2;

				return distance;
			}
			case MESH -> {
				MeshSdf sdf = s.sdf;

				if (sdf == null)
					return Double.MAX_VALUE;

				Matrix3f m = s.axes;
				double dx = x - s.q[0];
				double dy = y - s.q[1];
				double dz = z - s.q[2];
				double[] n = s.normal;
				float distance = sdf.distanceAndNormal(dx * m.m00() + dy * m.m01() + dz * m.m02(), dx * m.m10() + dy * m.m11() + dz * m.m12(),
						dx * m.m20() + dy * m.m21() + dz * m.m22(), n);

				gradient[0] = m.m00() * n[0] + m.m10() * n[1] + m.m20() * n[2];
				gradient[1] = m.m01() * n[0] + m.m11() * n[1] + m.m21() * n[2];
				gradient[2] = m.m02() * n[0] + m.m12() * n[1] + m.m22() * n[2];

				return distance;
			}
		}

		return Double.MAX_VALUE;
	}

	/**
	 * Where a point on the skin of a soft part is pushed to by something pressing into it, into {@code push} (the way and how far, blocks): nothing if it is
	 * {@code edge} or more outside {@code s}; a soft knee over the last {@code edge} of the way to the surface; and, once it is inside, out through the surface
	 * that faces the part's middle {@code (mx, my, mz)}. A point inside a thin shape (a forearm) whose nearest surface is the far side is walked toward the
	 * middle, as far as the shape's own field says it can go without leaving the shape, until the nearest surface is the near side, and is pushed out of that:
	 * flesh a hand has sunk into comes back out the way the hand came in, never through the hand
	 *
	 * @param gradient Scratch for the field's direction
	 * @return whether it is pushed
	 */
	public static boolean pressOut(Placed s, double x, double y, double z, double mx, double my, double mz, double edge, double[] push, double[] gradient) {
		double d = signedDistance(s, x, y, z, gradient);

		if (d >= edge)
			return false;

		double ux = mx - x;
		double uy = my - y;
		double uz = mz - z;
		double length = Math.sqrt(ux * ux + uy * uy + uz * uz);

		if (length < 1e-9) {
			ux = gradient[0];
			uy = gradient[1];
			uz = gradient[2];
		}
		else {
			ux /= length;
			uy /= length;
			uz /= length;
		}

		// just outside, or just inside a surface that faces the middle: the soft edge. A point just inside the far side of a shape the flesh has gone round (the
		// surface nearest it faces away) is not at an edge: it is inside, and is walked out below
		if (d > -edge && (d >= 0 || gradient[0] * ux + gradient[1] * uy + gradient[2] * uz > 0.3)) {
			double amount = (edge - d) * (edge - d) / (4 * edge);

			push[0] = gradient[0] * amount;
			push[1] = gradient[1] * amount;
			push[2] = gradient[2] * amount;

			return true;
		}

		double px = x;
		double py = y;
		double pz = z;

		for (int i = 0; i < 16; i++) {
			d = signedDistance(s, px, py, pz, gradient);

			if (d >= 0)
				break;

			double reliable = gradient[0] * gradient[0] + gradient[1] * gradient[1] + gradient[2] * gradient[2];

			if (reliable > 0.25 && (gradient[0] * ux + gradient[1] * uy + gradient[2] * uz > 0.3 || i == 15)) {
				// the nearest surface faces the middle: out through it
				px -= gradient[0] * d;
				py -= gradient[1] * d;
				pz -= gradient[2] * d;

				break;
			}

			// the nearest surface is the far side (or the field has run out so deep in): toward the middle, by what the field says is clear
			double step = reliable > 0.25 ? Math.max(-d, 0.004) : 0.05;

			px += ux * step;
			py += uy * step;
			pz += uz * step;
		}

		push[0] = px - x;
		push[1] = py - y;
		push[2] = pz - z;

		return true;
	}

	/** The distance from the point to {@code (cx, cy, cz)}, and the way away from it; straight up from the very centre */
	private static double fromPoint(double x, double y, double z, double cx, double cy, double cz, double[] gradient) {
		double dx = x - cx;
		double dy = y - cy;
		double dz = z - cz;
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);

		if (d < 1e-9) {
			gradient[0] = 0;
			gradient[1] = 1;
			gradient[2] = 0;

			return 0;
		}

		gradient[0] = dx / d;
		gradient[1] = dy / d;
		gradient[2] = dz / d;

		return d;
	}

	private static final ThreadLocal<double[][]> SWEEP_SCRATCH = ThreadLocal.withInitial(() -> new double[4][3]);

	/**
	 * Adds to {@code out} the shapes of {@code in} that could touch a ball of anything within {@code margin} of the first {@code count} of {@code points}: the box
	 * around the points, grown, tested against each shape's own box. Cuts a long list (a floor has hundreds of boxes) to the few that matter before they are tested
	 * against every particle several times a step
	 */
	public static void cull(List<Placed> in, double[][] points, int count, double margin, List<Placed> out) {
		double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
		double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};

		for (int k = 0; k < count; k++) {
			for (int a = 0; a < 3; a++) {
				lo[a] = Math.min(lo[a], points[k][a]);
				hi[a] = Math.max(hi[a], points[k][a]);
			}
		}

		for (int a = 0; a < 3; a++) {
			lo[a] -= margin;
			hi[a] += margin;
		}

		for (Placed shape : in) {
			if (near(shape, lo, hi))
				out.add(shape);
		}
	}

	/** Removes from {@code shapes} the generated ones that a ball of {@code r} at any of {@code points} is already inside, so overlap that is part of the model does not push */
	public static void dropRestingContacts(List<Placed> shapes, double[][] points, int from, int to, double r) {
		double[] probe = new double[3];
		double[] ball = new double[4];

		shapes.removeIf(shape -> {
			if (!shape.generated)
				return false;

			bound(shape, ball);

			double within = ball[3] + r;

			for (int k = from; k < to; k++) {
				// a point outside the shape's bounding ball is not in it
				double dx = points[k][0] - ball[0];
				double dy = points[k][1] - ball[1];
				double dz = points[k][2] - ball[2];

				if (dx * dx + dy * dy + dz * dz > within * within)
					continue;

				System.arraycopy(points[k], 0, probe, 0, 3);

				if (pushOut(shape, probe, r))
					return true;
			}

			return false;
		});
	}

	/**
	 * Moves {@code to} back to where a ball of radius {@code r} travelling from {@code from} to {@code to} first touches a box, if it would pass into or through
	 * one, so a particle that moves further in one step than a block is thick cannot skip over it. Only boxes are swept (blocks are boxes); shapes that a ball
	 * already overlaps at {@code from} are left to {@link #pushOut}. The motion along the box's face is kept, the motion into it is lost
	 *
	 * @param prev The previous position, which becomes the previous position of the moved particle so its velocity slides along the face; updated in place
	 * @return whether the particle was stopped by a box
	 */
	public static boolean sweep(List<Placed> shapes, double[] from, double[] to, double[] prev, double r) {
		double best = 2.0;
		double[][] scratch = SWEEP_SCRATCH.get();
		double[] normal = scratch[0];
		double[] n = scratch[1];
		Placed hit = null;

		for (Placed s : shapes) {
			if (s.shape != Shape.BOX)
				continue;

			double t = sweepBox(s, from, to, r, n, scratch);

			if (t >= 0 && t < best) {
				best = t;
				hit = s;
				System.arraycopy(n, 0, normal, 0, 3);
			}
		}

		if (hit == null)
			return false;

		double v0 = to[0] - from[0];
		double v1 = to[1] - from[1];
		double v2 = to[2] - from[2];
		double into = v0 * normal[0] + v1 * normal[1] + v2 * normal[2];
		double eps = 1e-7;

		for (int a = 0; a < 3; a++) {
			double velocity = a == 0 ? v0 : a == 1 ? v1 : v2;
			double entry = from[a] + (to[a] - from[a]) * best + normal[a] * eps;

			// what is left of the motion slides along the face
			double slide = (velocity - normal[a] * into) * (1.0 - best);

			to[a] = entry + slide;
			prev[a] = to[a] - (velocity - normal[a] * into);
		}

		return true;
	}

	/** The time in 0..1 at which a ball of {@code r} moving from {@code a} to {@code b} first touches the box, with the face's outward normal, or -1 */
	private static double sweepBox(Placed s, double[] a, double[] b, double r, double[] normalOut, double[][] scratch) {
		double[] la = scratch[2];
		double[] lb = scratch[3];
		Matrix3f m = s.axes;
		double ax = a[0] - s.p[0];
		double ay = a[1] - s.p[1];
		double az = a[2] - s.p[2];
		double bx = b[0] - s.p[0];
		double by = b[1] - s.p[1];
		double bz = b[2] - s.p[2];

		la[0] = ax * m.m00() + ay * m.m01() + az * m.m02();
		la[1] = ax * m.m10() + ay * m.m11() + az * m.m12();
		la[2] = ax * m.m20() + ay * m.m21() + az * m.m22();
		lb[0] = bx * m.m00() + by * m.m01() + bz * m.m02();
		lb[1] = bx * m.m10() + by * m.m11() + bz * m.m12();
		lb[2] = bx * m.m20() + by * m.m21() + bz * m.m22();

		double enter = -Double.MAX_VALUE;
		double leave = Double.MAX_VALUE;
		int enterAxis = -1;
		double enterSign = 0;

		for (int i = 0; i < 3; i++) {
			double h = s.half[i] + r;
			double d = lb[i] - la[i];

			if (Math.abs(d) < 1e-12) {
				if (Math.abs(la[i]) > h)
					return -1;

				continue;
			}

			double t0 = (-h - la[i]) / d;
			double t1 = (h - la[i]) / d;
			double sign = -1;

			if (t0 > t1) {
				double tmp = t0;

				t0 = t1;
				t1 = tmp;
				sign = 1;
			}

			if (t0 > enter) {
				enter = t0;
				enterAxis = i;
				enterSign = sign;
			}

			leave = Math.min(leave, t1);

			if (enter > leave)
				return -1;
		}

		// it must enter during this step, from outside (a start inside the box is push-out's business)
		if (enterAxis < 0 || enter < 0 || enter > 1)
			return -1;

		for (int a2 = 0; a2 < 3; a2++)
			normalOut[a2] = enterSign * s.axes.get(enterAxis, a2);

		return enter;
	}

	/**
	 * If a ball of {@code r} at {@code x} is inside this shape, moves it to the nearest point where it just touches and returns true
	 */
	public static boolean pushOut(Placed s, double[] x, double r) {
		return switch (s.shape) {
			case SPHERE -> pushFromPoint(x, s.p[0], s.p[1], s.p[2], s.radius + r);
			case CAPSULE -> {
				double abx = s.q[0] - s.p[0];
				double aby = s.q[1] - s.p[1];
				double abz = s.q[2] - s.p[2];
				double len2 = abx * abx + aby * aby + abz * abz;
				double t = len2 < 1e-12 ? 0 : ((x[0] - s.p[0]) * abx + (x[1] - s.p[1]) * aby + (x[2] - s.p[2]) * abz) / len2;

				t = Math.max(0, Math.min(1, t));

				yield pushFromPoint(x, s.p[0] + abx * t, s.p[1] + aby * t, s.p[2] + abz * t, s.radius + r);
			}
			case BOX -> pushFromBox(s, x, r);
			case MESH -> pushFromMesh(s, x, r);
		};
	}

	/**
	 * Out of the model's own surface: the field says how deep the ball is in it and which way is out. A ball deeper in than the field reaches is moved along the
	 * direction away from the middle of the grid and asked again, a few times, so it comes out of a thick part by the nearest way the field can show
	 */
	private static boolean pushFromMesh(Placed s, double[] x, double r) {
		MeshSdf sdf = s.sdf;

		if (sdf == null)
			return false;

		// outside the field's bounding sphere (grown by the ball and the field's band) there is nothing to find: most particles, for most shapes
		double bx = x[0] - s.p[0];
		double by = x[1] - s.p[1];
		double bz = x[2] - s.p[2];
		double within = s.radius + r;

		if (bx * bx + by * by + bz * bz > within * within)
			return false;

		Matrix3f m = s.axes;
		double[] n = s.normal;
		boolean moved = false;

		for (int pass = 0; pass < 6; pass++) {
			// into the bone's space: the columns of the axes are the bone's axes in the solver's
			double dx = x[0] - s.q[0];
			double dy = x[1] - s.q[1];
			double dz = x[2] - s.q[2];
			float distance = sdf.distanceAndNormal(dx * m.m00() + dy * m.m01() + dz * m.m02(), dx * m.m10() + dy * m.m11() + dz * m.m12(),
					dx * m.m20() + dy * m.m21() + dz * m.m22(), n);

			// at the band on the outside the field says nothing: the ball is farther than that
			if (distance >= r || distance >= sdf.band - 1e-6f)
				return moved;

			double push = r - distance;

			x[0] += (m.m00() * n[0] + m.m10() * n[1] + m.m20() * n[2]) * push;
			x[1] += (m.m01() * n[0] + m.m11() * n[1] + m.m21() * n[2]) * push;
			x[2] += (m.m02() * n[0] + m.m12() * n[1] + m.m22() * n[2]) * push;
			moved = true;
		}

		return moved;
	}

	private static boolean pushFromPoint(double[] x, double cx, double cy, double cz, double reach) {
		double dx = x[0] - cx;
		double dy = x[1] - cy;
		double dz = x[2] - cz;
		double d2 = dx * dx + dy * dy + dz * dz;

		if (d2 >= reach * reach)
			return false;

		double d = Math.sqrt(d2);

		if (d < 1e-9) {
			// exactly at the centre: leave upward, any direction is as good as another
			x[1] = cy + reach;

			return true;
		}

		double k = reach / d;

		x[0] = cx + dx * k;
		x[1] = cy + dy * k;
		x[2] = cz + dz * k;

		return true;
	}

	private static boolean pushFromBox(Placed s, double[] x, double r) {
		// into the box's own space
		double dx = x[0] - s.p[0];
		double dy = x[1] - s.p[1];
		double dz = x[2] - s.p[2];
		// no nearer the box's centre than its corners are, plus the ball: the ball cannot reach it, which is what the exact test below would find too
		double reach = Math.sqrt(s.half[0] * s.half[0] + s.half[1] * s.half[1] + s.half[2] * s.half[2]) + r;

		if (dx * dx + dy * dy + dz * dz >= reach * reach)
			return false;

		Matrix3f m = s.axes;
		double l0 = dx * m.m00() + dy * m.m01() + dz * m.m02();
		double l1 = dx * m.m10() + dy * m.m11() + dz * m.m12();
		double l2 = dx * m.m20() + dy * m.m21() + dz * m.m22();

		// the nearest point of the box, and how far the ball's centre is from it
		double n0 = Math.max(-s.half[0], Math.min(s.half[0], l0));
		double n1 = Math.max(-s.half[1], Math.min(s.half[1], l1));
		double n2 = Math.max(-s.half[2], Math.min(s.half[2], l2));
		double e0 = l0 - n0;
		double e1 = l1 - n1;
		double e2 = l2 - n2;
		double dist2 = e0 * e0 + e1 * e1 + e2 * e2;
		double o0;
		double o1;
		double o2;

		if (dist2 > 1e-18) {
			double d = Math.sqrt(dist2);

			if (d >= r)
				return false;

			double k = r / d;

			o0 = n0 + e0 * k;
			o1 = n1 + e1 * k;
			o2 = n2 + e2 * k;
		}
		else {
			// the centre is inside the box: leave through the nearest face
			double g0 = s.half[0] - Math.abs(l0);
			double g1 = s.half[1] - Math.abs(l1);
			double g2 = s.half[2] - Math.abs(l2);

			o0 = l0;
			o1 = l1;
			o2 = l2;

			if (g0 <= g1 && g0 <= g2)
				o0 = Math.signum(l0 == 0 ? 1 : l0) * (s.half[0] + r);
			else if (g1 <= g2)
				o1 = Math.signum(l1 == 0 ? 1 : l1) * (s.half[1] + r);
			else
				o2 = Math.signum(l2 == 0 ? 1 : l2) * (s.half[2] + r);
		}

		// back to solver space
		x[0] = s.p[0] + o0 * m.m00() + o1 * m.m10() + o2 * m.m20();
		x[1] = s.p[1] + o0 * m.m01() + o1 * m.m11() + o2 * m.m21();
		x[2] = s.p[2] + o0 * m.m02() + o1 * m.m12() + o2 * m.m22();

		return true;
	}
}
