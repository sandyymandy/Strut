package com.sandymandy.strut.physics.solver;

import com.sandymandy.strut.animation.state.RigidDeform;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Turns the rigid motion a carried mesh has (about the point {@code ra} to the point {@code rc}, by the rotation {@code r}, all in the model's space) into the
 * form the GPU applies to each vertex: a map of the vertex's own place in the mesh's space. Every vertex goes through the same motion, so the whole mesh costs
 * twelve numbers and nine, worked out once, instead of five transforms for each corner
 * <p>
 * For a vertex at {@code rest} in the mesh's space, the bone's matrix being {@code L} with translation {@code t}, the motion moves it from {@code p = L * rest + t}
 * to {@code rc + R * (p - ra)}; in the mesh's space (through the inverse of {@code L}) and weighted:
 * <pre>offset = inverse * weight * ((R - I) * (L * rest + t) + rc - R * ra)</pre>
 * which is {@code A * rest + b} with {@code A = inverse * weight * (R - I) * L} and {@code b = inverse * weight * ((R - I) * t + rc - R * ra)}. The normal is
 * turned by {@code inverse * R * L}
 */
final class RigidMotion {
	private final Matrix3f bone = new Matrix3f();
	private final Matrix3f rMinusI = new Matrix3f();
	private final Matrix3f map = new Matrix3f();
	private final Matrix3f turn = new Matrix3f();
	private final Vector3f translation = new Vector3f();
	private final Vector3f shift = new Vector3f();
	private final Vector3f raV = new Vector3f();

	/**
	 * @param matrix the bone's matrix: where a vertex of the mesh is in the model's space
	 * @param inverse the inverse of that matrix's linear part: from the model's space into the mesh's
	 * @param r the rotation of the motion
	 * @param ra where the motion rotates from, model space
	 * @param rc where it goes to, model space
	 * @param weight how much of it is shown, 0 to 1
	 * @param out receives {@code A | b} and the turn of the normals
	 */
	void collapse(Matrix4f matrix, Matrix3f inverse, Matrix3f r, double[] ra, double[] rc, float weight, RigidDeform out) {
		Matrix3f bone = this.bone.set(matrix);
		Vector3f t = matrix.getTranslation(this.translation);
		Matrix3f rMinusI = this.rMinusI.set(r);

		rMinusI.m00(rMinusI.m00() - 1f);
		rMinusI.m11(rMinusI.m11() - 1f);
		rMinusI.m22(rMinusI.m22() - 1f);

		Matrix3f map = this.map.set(inverse).mul(rMinusI).mul(bone).scale(weight);
		Vector3f shift = this.shift.set(t);

		rMinusI.transform(shift);

		// rc - R * ra
		Vector3f raV = this.raV.set((float)ra[0], (float)ra[1], (float)ra[2]);

		r.transform(raV);
		shift.add((float)rc[0] - raV.x, (float)rc[1] - raV.y, (float)rc[2] - raV.z).mul(weight);
		inverse.transform(shift);

		Matrix3f turn = this.turn.set(inverse).mul(r).mul(bone);
		float[] offset = out.offset;
		float[] normal = out.turn;

		for (int row = 0; row < 3; row++) {
			offset[row * 4] = map.getRowColumn(row, 0);
			offset[row * 4 + 1] = map.getRowColumn(row, 1);
			offset[row * 4 + 2] = map.getRowColumn(row, 2);
			normal[row * 3] = turn.getRowColumn(row, 0);
			normal[row * 3 + 1] = turn.getRowColumn(row, 1);
			normal[row * 3 + 2] = turn.getRowColumn(row, 2);
		}

		offset[3] = shift.x;
		offset[7] = shift.y;
		offset[11] = shift.z;
	}
}
