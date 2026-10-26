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
			double smin;
			double smax;

			switch (s.shape) {
				case SPHERE -> {
					smin = s.p[a] - s.radius;
					smax = s.p[a] + s.radius;
				}
				case CAPSULE -> {
					smin = Math.min(s.p[a], s.q[a]) - s.radius;
					smax = Math.max(s.p[a], s.q[a]) + s.radius;
				}
				case MESH -> {
					smin = s.p[a] - s.radius;
					smax = s.p[a] + s.radius;
				}
				default -> {
					double reach = Math.sqrt(s.half[0] * s.half[0] + s.half[1] * s.half[1] + s.half[2] * s.half[2]);

					smin = s.p[a] - reach;
					smax = s.p[a] + reach;
				}
			}

			if (smax < lo[a] || smin > hi[a])
				return false;
		}

		return true;
	}

	private static final ThreadLocal<double[][]> SWEEP_SCRATCH = ThreadLocal.withInitial(() -> new double[2][3]);

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

		shapes.removeIf(shape -> {
			if (!shape.generated)
				return false;

			for (int k = from; k < to; k++) {
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

			double t = sweepBox(s, from, to, r, n);

			if (t >= 0 && t < best) {
				best = t;
				hit = s;
				System.arraycopy(n, 0, normal, 0, 3);
			}
		}

		if (hit == null)
			return false;

		double[] velocity = {to[0] - from[0], to[1] - from[1], to[2] - from[2]};
		double into = velocity[0] * normal[0] + velocity[1] * normal[1] + velocity[2] * normal[2];
		double eps = 1e-7;

		for (int a = 0; a < 3; a++) {
			double entry = from[a] + (to[a] - from[a]) * best + normal[a] * eps;

			// what is left of the motion slides along the face
			double slide = (velocity[a] - normal[a] * into) * (1.0 - best);

			to[a] = entry + slide;
			prev[a] = to[a] - (velocity[a] - normal[a] * into);
		}

		return true;
	}

	/** The time in 0..1 at which a ball of {@code r} moving from {@code a} to {@code b} first touches the box, with the face's outward normal, or -1 */
	private static double sweepBox(Placed s, double[] a, double[] b, double r, double[] normalOut) {
		double[] la = new double[3];
		double[] lb = new double[3];

		for (int axis = 0; axis < 3; axis++) {
			la[axis] = (a[0] - s.p[0]) * s.axes.get(axis, 0) + (a[1] - s.p[1]) * s.axes.get(axis, 1) + (a[2] - s.p[2]) * s.axes.get(axis, 2);
			lb[axis] = (b[0] - s.p[0]) * s.axes.get(axis, 0) + (b[1] - s.p[1]) * s.axes.get(axis, 1) + (b[2] - s.p[2]) * s.axes.get(axis, 2);
		}

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

		Matrix3f m = s.axes;
		double[] n = MESH_NORMAL.get();
		boolean moved = false;

		for (int pass = 0; pass < 6; pass++) {
			// into the bone's space: the columns of the axes are the bone's axes in the solver's
			double dx = x[0] - s.q[0];
			double dy = x[1] - s.q[1];
			double dz = x[2] - s.q[2];
			float distance = sdf.distanceAndNormal(dx * m.get(0, 0) + dy * m.get(0, 1) + dz * m.get(0, 2), dx * m.get(1, 0) + dy * m.get(1, 1) + dz * m.get(1, 2),
					dx * m.get(2, 0) + dy * m.get(2, 1) + dz * m.get(2, 2), n);

			// at the band on the outside the field says nothing: the ball is farther than that
			if (distance >= r || distance >= sdf.band - 1e-6f)
				return moved;

			double push = r - distance;

			x[0] += (m.get(0, 0) * n[0] + m.get(1, 0) * n[1] + m.get(2, 0) * n[2]) * push;
			x[1] += (m.get(0, 1) * n[0] + m.get(1, 1) * n[1] + m.get(2, 1) * n[2]) * push;
			x[2] += (m.get(0, 2) * n[0] + m.get(1, 2) * n[1] + m.get(2, 2) * n[2]) * push;
			moved = true;
		}

		return moved;
	}

	private static final ThreadLocal<double[]> MESH_NORMAL = ThreadLocal.withInitial(() -> new double[3]);

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
		Matrix3f m = s.axes;
		double l0 = dx * m.get(0, 0) + dy * m.get(0, 1) + dz * m.get(0, 2);
		double l1 = dx * m.get(1, 0) + dy * m.get(1, 1) + dz * m.get(1, 2);
		double l2 = dx * m.get(2, 0) + dy * m.get(2, 1) + dz * m.get(2, 2);

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
		for (int axis = 0; axis < 3; axis++)
			x[axis] = s.p[axis] + o0 * m.get(0, axis) + o1 * m.get(1, axis) + o2 * m.get(2, axis);

		return true;
	}
}
