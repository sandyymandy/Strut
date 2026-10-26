package com.sandymandy.strut.physics.collision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The signed distance field of a mesh: exact for a cube, close for a sphere, sane for an open mesh, and fast
 */
class MeshSdfTest {
	/** The 12 triangles of the box lo..hi */
	private static float[] box(float x0, float y0, float z0, float x1, float y1, float z1) {
		float[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
		int[][] faces = {{0, 1, 2, 3}, {5, 4, 7, 6}, {4, 0, 3, 7}, {1, 5, 6, 2}, {3, 2, 6, 7}, {4, 5, 1, 0}};
		float[] out = new float[12 * 9];
		int at = 0;

		for (int[] f : faces) {
			for (int[] tri : new int[][] {{0, 1, 2}, {0, 2, 3}}) {
				for (int corner : tri) {
					for (int a = 0; a < 3; a++)
						out[at++] = c[f[corner]][a];
				}
			}
		}

		return out;
	}

	/** An icosphere-ish mesh: a lat-long sphere of radius r at the origin */
	private static float[] sphere(float r, int rings, int segments) {
		List<Float> t = new ArrayList<>();

		for (int i = 0; i < rings; i++) {
			double a0 = Math.PI * i / rings;
			double a1 = Math.PI * (i + 1) / rings;

			for (int j = 0; j < segments; j++) {
				double b0 = 2 * Math.PI * j / segments;
				double b1 = 2 * Math.PI * (j + 1) / segments;
				float[] p00 = point(r, a0, b0);
				float[] p01 = point(r, a0, b1);
				float[] p10 = point(r, a1, b0);
				float[] p11 = point(r, a1, b1);

				add(t, p00, p10, p11);
				add(t, p00, p11, p01);
			}
		}

		float[] out = new float[t.size()];

		for (int i = 0; i < out.length; i++)
			out[i] = t.get(i);

		return out;
	}

	private static float[] point(float r, double a, double b) {
		return new float[] {(float)(r * Math.sin(a) * Math.cos(b)), (float)(r * Math.cos(a)), (float)(r * Math.sin(a) * Math.sin(b))};
	}

	private static void add(List<Float> t, float[]... corners) {
		for (float[] c : corners) {
			for (float v : c)
				t.add(v);
		}
	}

	@Test
	@DisplayName("a cube is exact to the cell: distance outside and just inside, the corner, and the outward direction")
	void aCube() {
		MeshSdf sdf = MeshSdf.build(box(0, 0, 0, 1, 1, 1), 12, 0.25f, 64);
		double[] n = new double[3];
		float tol = sdf.cell * 0.6f;

		assertEquals(0.1f, sdf.distanceAndNormal(1.1, 0.5, 0.5, n), tol);
		assertEquals(1, n[0], 0.05);
		assertEquals(0, n[1], 0.05);
		assertEquals(-0.1f, sdf.distanceAndNormal(0.9, 0.5, 0.5, n), tol);
		assertEquals(1, n[0], 0.05, "the direction is out, even from inside");
		assertEquals(-0.1f, sdf.distanceAndNormal(0.5, 0.1, 0.5, n), tol);
		assertEquals(-1, n[1], 0.05);

		// off a corner the distance is to the corner, not to a face
		assertEquals((float)(0.08 * Math.sqrt(3)), sdf.distance(1.08, 1.08, 1.08), tol);
		assertEquals(-0.25f, sdf.distance(0.5, 0.5, 0.5), 1e-4f, "deep inside reads the band");
		assertEquals(0.25f, sdf.distance(3, 3, 3), 1e-6f, "far outside reads the band");
	}

	@Test
	@DisplayName("a sphere's distance is the distance to its surface, and its normal points from the centre")
	void aSphere() {
		MeshSdf sdf = MeshSdf.build(sphere(0.5f, 24, 32), 24 * 32 * 2, 0.25f, 64);
		double[] n = new double[3];
		Random random = new Random(3);
		double worst = 0;

		for (int i = 0; i < 2000; i++) {
			double x = random.nextGaussian();
			double y = random.nextGaussian();
			double z = random.nextGaussian();
			double len = Math.sqrt(x * x + y * y + z * z);
			// a point from 0.3 to 0.7 from the centre: 0.2 inside to 0.2 outside
			double r = 0.3 + random.nextDouble() * 0.4;
			double px = x / len * r;
			double py = y / len * r;
			double pz = z / len * r;
			float d = sdf.distanceAndNormal(px, py, pz, n);

			worst = Math.max(worst, Math.abs(d - (r - 0.5)));
			assertTrue(n[0] * px / r + n[1] * py / r + n[2] * pz / r > 0.9, "the normal points out");
		}

		assertTrue(worst < sdf.cell + 0.01, "worst error " + worst + " with a cell of " + sdf.cell);
	}

	@Test
	@DisplayName("a mesh with a hole still has an inside, an open sheet has none, and nothing is NaN")
	void openMeshes() {
		float[] cube = box(0, 0, 0, 1, 1, 1);
		// the top face (two triangles) cut away
		float[] holed = new float[10 * 9];

		System.arraycopy(cube, 0, holed, 0, 8 * 9);
		System.arraycopy(cube, 10 * 9, holed, 8 * 9, 2 * 9);

		MeshSdf sdf = MeshSdf.build(holed, 10, 0.25f, 64);

		for (float v : sdf.value)
			assertFalse(Float.isNaN(v));

		assertTrue(sdf.distance(0.5, 0.15, 0.5) < 0f, "the side rays still say it is inside");

		float[] sheet = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0, 0, 1, 1, 0, 0, 1, 0};
		MeshSdf flat = MeshSdf.build(sheet, 2, 0.25f, 64);

		for (float v : flat.value) {
			assertFalse(Float.isNaN(v));
			assertTrue(v >= 0, "a sheet encloses nothing");
		}

		assertNull(MeshSdf.build(new float[0], 0, 0.25f, 64));
	}

	@Test
	@DisplayName("the grid keeps to its size limit however big the mesh, and the field of many cubes is built in a second")
	void sizeAndSpeed() {
		MeshSdf big = MeshSdf.build(box(0, 0, 0, 40, 30, 20), 12, 0.25f, 64);

		assertTrue(Math.max(big.nx, Math.max(big.ny, big.nz)) <= 66, big.nx + " x " + big.ny + " x " + big.nz);

		// 700 little cubes in a 1 x 2 x 0.5 body, as a detailed model has
		Random random = new Random(1);
		float[] all = new float[700 * 12 * 9];

		for (int c = 0; c < 700; c++) {
			float x = random.nextFloat();
			float y = random.nextFloat() * 2;
			float z = random.nextFloat() * 0.5f;

			System.arraycopy(box(x, y, z, x + 0.0625f, y + 0.0625f, z + 0.0625f), 0, all, c * 12 * 9, 12 * 9);
		}

		long start = System.nanoTime();
		MeshSdf sdf = MeshSdf.build(all, 700 * 12, 0.25f, 64);
		double buildMs = (System.nanoTime() - start) / 1e6;

		System.out.println("SDF build of 8400 triangles: " + String.format("%.0f", buildMs) + " ms, grid " + sdf.nx + " x " + sdf.ny + " x " + sdf.nz + " = " + sdf.nodes() + " nodes");
		assertTrue(buildMs < 4000, "build took " + buildMs + " ms");

		double[] n = new double[3];
		double sum = 0;

		for (int i = 0; i < 200_000; i++)
			sum += sdf.distanceAndNormal(random.nextDouble() * 1.2 - 0.1, random.nextDouble() * 2.2 - 0.1, random.nextDouble() * 0.7 - 0.1, n);

		start = System.nanoTime();

		for (int i = 0; i < 2_000_000; i++)
			sum += sdf.distanceAndNormal((i % 1000) * 0.0012 - 0.05, ((i >> 3) % 1000) * 0.002 - 0.05, ((i >> 6) % 500) * 0.0012 - 0.05, n);

		double queryNs = (System.nanoTime() - start) / 2_000_000.0;

		System.out.println("SDF query: " + String.format("%.0f", queryNs) + " ns (" + sum + ")");
		assertTrue(queryNs < 1000, "a query took " + queryNs + " ns");
	}
}
