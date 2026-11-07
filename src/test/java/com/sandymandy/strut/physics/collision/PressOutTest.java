package com.sandymandy.strut.physics.collision;

import org.joml.Matrix3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What presses into soft flesh is a field like everything else: a vertex inside a thin plate comes out of the face that faces the flesh, not the nearest one
 */
class PressOutTest {
	private static float[] box(float x0, float y0, float z0, float x1, float y1, float z1) {
		float[][] v = {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
		int[][] t = {{0, 2, 1}, {0, 3, 2}, {4, 5, 6}, {4, 6, 7}, {0, 1, 5}, {0, 5, 4}, {3, 7, 6}, {3, 6, 2}, {0, 4, 7}, {0, 7, 3}, {1, 2, 6}, {1, 6, 5}};
		float[] out = new float[t.length * 9];
		int at = 0;

		for (int[] tri : t) {
			for (int corner : tri) {
				out[at++] = v[corner][0];
				out[at++] = v[corner][1];
				out[at++] = v[corner][2];
			}
		}

		return out;
	}

	@Test
	void aVertexInsideAThinPlateComesOutTheFaceThatFacesTheFlesh() {
		// a plate 0.1 thick, y from 0 to 0.1, and the flesh's middle below it
		MeshSdf field = MeshSdf.build(box(-0.5f, 0f, -0.5f, 0.5f, 0.1f, 0.5f), 12, 0.25f, 64);
		Collider.Placed plate = new Collider.Placed();

		plate.shape = Collider.Shape.MESH;
		plate.sdf = field;
		plate.axes.set(new Matrix3f());
		plate.radius = 2;

		double[] push = new double[3];
		double[] g = new double[3];

		// 0.07 up into it: the top face is the nearest, 0.03 away, but the flesh is below: out through the bottom
		assertTrue(Collider.pressOut(plate, 0, 0.07, 0, 0, -0.5, 0, 0.025, push, g));
		assertEquals(-0.07, push[1], 0.02);
		assertEquals(0, push[0], 0.01);

		// from the other side the same plate pushes the other way
		assertTrue(Collider.pressOut(plate, 0, 0.03, 0, 0, 0.6, 0, 0.025, push, g));
		assertEquals(0.07, push[1], 0.02);

		// well clear: nothing
		assertFalse(Collider.pressOut(plate, 0, 0.2, 0, 0, 0.6, 0, 0.025, push, g));
	}
}
