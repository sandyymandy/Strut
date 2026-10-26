package com.sandymandy.strut.physics.solver;

import com.sandymandy.strut.animation.state.RigidDeform;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import com.sandymandy.strut.cache.object.StrutVertex;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A carried mesh moves as one piece, and what the GPU is given for it is one map of a vertex's own place. It must be the motion the mesh was given before that was
 * collapsed: each vertex taken through the bone, the rigid motion about a point and back through the inverse
 */
class RigidMotionTest {
	@Test
	void theMapGivesEachVertexWhatTheMotionDidVertexByVertex() {
		Matrix4f matrix = new Matrix4f().translate(0.3f, -0.2f, 0.9f).rotateXYZ(0.4f, -0.8f, 0.3f).scale(1f, 1.2f, 0.9f);
		Matrix3f inverse = new Matrix3f(matrix).invert();
		Matrix3f r = new Matrix3f().rotateXYZ(0.2f, 0.5f, -0.3f);
		double[] ra = {0.1, 0.8, -0.2};
		double[] rc = {0.15, 0.78, -0.25};
		float weight = 0.7f;
		Vector3f[] rests = {new Vector3f(0.2f, 0.9f, -0.4f), new Vector3f(-0.5f, 0.1f, 0.6f), new Vector3f(0f, 0f, 0f)};
		Vector3f[] normals = {new Vector3f(0, 1, 0), new Vector3f(0.6f, 0f, 0.8f), new Vector3f(0, 0, -1)};
		StrutPoly[] polys = new StrutPoly[1];
		StrutVertex[] corners = new StrutVertex[4];

		for (int c = 0; c < 4; c++)
			corners[c] = new StrutVertex(rests[c % 3], 0f, 0f, normals[c % 3]);

		polys[0] = new StrutPoly(corners, new Vector3f(0, 1, 0));

		StrutMesh mesh = new StrutMesh(polys, Vec3d.ZERO, Vec3d.ZERO, false);
		RigidDeform out = new RigidDeform(mesh);

		new RigidMotion().collapse(matrix, inverse, r, ra, rc, weight, out);

		float[] offsets = out.cornerOffsets();
		float[] shading = out.cornerNormals();

		for (int c = 0; c < 4; c++) {
			// the way it was done for each vertex: where the bone puts it, where the motion takes it from there, the difference, weighted, in the mesh's space
			Vector3f v = new Vector3f(rests[c % 3]);

			matrix.transformPosition(v);

			Vector3f moved = new Vector3f((float)(v.x - ra[0]), (float)(v.y - ra[1]), (float)(v.z - ra[2]));

			r.transform(moved);

			Vector3f off = new Vector3f((float)(rc[0] + moved.x - v.x), (float)(rc[1] + moved.y - v.y), (float)(rc[2] + moved.z - v.z)).mul(weight);

			inverse.transform(off);

			Vector3f n = new Vector3f(normals[c % 3]);

			new Matrix3f(matrix).transform(n);
			r.transform(n);
			inverse.transform(n);
			n.normalize();

			assertEquals(off.x, offsets[c * 3], 1e-5f, "x of corner " + c);
			assertEquals(off.y, offsets[c * 3 + 1], 1e-5f, "y of corner " + c);
			assertEquals(off.z, offsets[c * 3 + 2], 1e-5f, "z of corner " + c);
			assertEquals(n.x, shading[c * 3], 1e-5f, "normal x of corner " + c);
			assertEquals(n.y, shading[c * 3 + 1], 1e-5f, "normal y of corner " + c);
			assertEquals(n.z, shading[c * 3 + 2], 1e-5f, "normal z of corner " + c);
		}
	}

	@Test
	void noMotionAndNoWeightMoveNothing() {
		Matrix4f matrix = new Matrix4f().translate(1f, 2f, 3f).rotateY(0.5f);
		Matrix3f inverse = new Matrix3f(matrix).invert();
		StrutVertex corner = new StrutVertex(new Vector3f(0.3f, 0.2f, 0.1f), 0f, 0f, new Vector3f(0, 1, 0));
		StrutMesh mesh = new StrutMesh(new StrutPoly[] {new StrutPoly(new StrutVertex[] {corner, corner, corner, corner}, new Vector3f(0, 1, 0))}, Vec3d.ZERO, Vec3d.ZERO, false);
		RigidDeform out = new RigidDeform(mesh);

		// the particles are where the animation puts them, so the motion is none
		new RigidMotion().collapse(matrix, inverse, new Matrix3f(), new double[] {0.4, 0.5, 0.6}, new double[] {0.4, 0.5, 0.6}, 1f, out);

		for (float offset : out.cornerOffsets())
			assertEquals(0f, offset, 1e-6f);

		// a motion that is not shown at all
		new RigidMotion().collapse(matrix, inverse, new Matrix3f().rotateX(1f), new double[] {0, 0, 0}, new double[] {1, 1, 1}, 0f, out);
		out.changed();

		for (float offset : out.cornerOffsets())
			assertEquals(0f, offset, 1e-6f);
	}
}
