package com.sandymandy.strut.physics.fluid;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.collision.Collider;

/**
 * The thick liquid: soft particles that cling, and the metaball surface drawn around them
 */
class FluidTest {
	private static Collider.Placed box(double cx, double cy, double cz, double hx, double hy, double hz) {
		Collider.Placed box = new Collider.Placed();

		box.shape = Collider.Shape.BOX;
		box.layer = "world";
		box.p[0] = cx;
		box.p[1] = cy;
		box.p[2] = cz;
		box.half[0] = hx;
		box.half[1] = hy;
		box.half[2] = hz;
		box.axes.identity();

		return box;
	}

	private static FluidParams params(float cohesion, float friction, float gravity, float lifetime) {
		FluidParams d = FluidParams.THICK;

		return new FluidParams(d.radius(), d.spacing(), cohesion, d.viscosity(), friction, gravity, d.drag(), lifetime, d.fade(), d.color(), d.threshold(), d.cell());
	}

	private static void run(FluidSolver solver, List<Collider.Placed> shapes, double seconds) {
		for (int i = 0; i < (int)Math.round(seconds / FluidSolver.STEP); i++)
			solver.step(FluidSolver.STEP, shapes, 3);
	}

	private static void blob(FluidSolver solver, Random random, int n, double cx, double cy, double cz, double size, float vx, float vy, float vz, float jitter) {
		for (int i = 0; i < n; i++) {
			solver.spawn(cx + (random.nextDouble() - 0.5) * size, cy + (random.nextDouble() - 0.5) * size, cz + (random.nextDouble() - 0.5) * size,
					vx + (random.nextFloat() - 0.5f) * jitter, vy + (random.nextFloat() - 0.5f) * jitter, vz + (random.nextFloat() - 0.5f) * jitter, 0);
		}
	}

	@Test
	@DisplayName("a splash falls onto a floor, stays above it, loses its speed and keeps every particle")
	void landsAndRests() {
		FluidSolver solver = new FluidSolver(FluidParams.THICK, 200);
		List<Collider.Placed> floor = List.of(box(0, -0.5, 0, 5, 0.5, 5));

		blob(solver, new Random(1), 40, 0, 1, 0, 0.2, 0, 0, 0, 2f);
		run(solver, floor, 3.0);

		assertEquals(40, solver.count);

		double fastest = 0;

		for (int i = 0; i < solver.count; i++) {
			assertTrue(solver.y[i] > -0.005, "above the floor: " + solver.y[i]);
			assertTrue(Double.isFinite(solver.x[i]) && Double.isFinite(solver.y[i]) && Double.isFinite(solver.z[i]));
			fastest = Math.max(fastest, Math.hypot(solver.vx[i], Math.hypot(solver.vy[i], solver.vz[i])));
		}

		assertTrue(fastest < 0.6, "it has come to rest: " + fastest);
	}

	@Test
	@DisplayName("cohesion keeps a thrown blob together, where the same throw without it flies apart")
	void cohesionHoldsABlobTogether() {
		FluidSolver sticky = new FluidSolver(params(0.06f, 0.2f, 0f, 20f), 100);
		FluidSolver dry = new FluidSolver(params(0f, 0.2f, 0f, 20f), 100);

		blob(sticky, new Random(3), 40, 0, 5, 0, 0.12, 0, 0, 0, 4f);
		blob(dry, new Random(3), 40, 0, 5, 0, 0.12, 0, 0, 0, 4f);
		run(sticky, List.of(), 1.5);
		run(dry, List.of(), 1.5);

		assertTrue(sticky.widestGap() < FluidParams.THICK.radius() * 1.2, "sticky: " + sticky.widestGap());
		assertTrue(dry.widestGap() > sticky.widestGap() * 1.5, "dry " + dry.widestGap() + " against sticky " + sticky.widestGap());
	}

	@Test
	@DisplayName("a liquid that clings slides down a wall more slowly than one that does not")
	void frictionMakesItCling() {
		double[] fallen = new double[2];
		float[] friction = {0.4f, 0f};

		for (int k = 0; k < 2; k++) {
			FluidSolver solver = new FluidSolver(params(0.04f, friction[k], 9.8f, 20f), 100);
			// a wall at x = 1 and a floor far below
			List<Collider.Placed> shapes = List.of(box(1.5, 0, 0, 0.5, 20, 5), box(0, -20.5, 0, 5, 0.5, 5));

			blob(solver, new Random(5), 30, 0.85, 10, 0, 0.1, 3f, 0, 0, 0.5f);
			run(solver, shapes, 1.5);

			double sum = 0;

			for (int i = 0; i < solver.count; i++)
				sum += solver.y[i];

			fallen[k] = 10 - sum / solver.count;
		}

		assertTrue(fallen[0] < fallen[1] * 0.8, "clinging fell " + fallen[0] + ", slippery fell " + fallen[1]);
	}

	@Test
	@DisplayName("particles are removed when their time is up, and a full pool gives its oldest place to a new one")
	void lifetimeAndCapacity() {
		FluidSolver solver = new FluidSolver(params(0.04f, 0.2f, 0f, 1f), 10);

		for (int i = 0; i < 12; i++)
			solver.spawn(i * 0.5, 5, 0, 0, 0, 0, 0);

		assertEquals(10, solver.count, "the pool is full: the oldest were replaced");

		run(solver, List.of(), 0.5);
		solver.spawn(100, 5, 0, 0, 0, 0, 0);

		assertEquals(10, solver.count);
		run(solver, List.of(), 0.7);

		assertEquals(1, solver.count, "the first nine are past their second, the newest is not");
		assertEquals(100, solver.x[0], 1.0);
	}

	@Test
	@DisplayName("a fast throw into a corner of two walls and a floor stays finite and inside")
	void anExtremeThrowIsContained() {
		FluidSolver solver = new FluidSolver(FluidParams.THICK, 300);
		List<Collider.Placed> shapes = List.of(box(0, -0.5, 0, 5, 0.5, 5), box(-5.5, 5, 0, 0.5, 5, 5), box(0, 5, -5.5, 5, 5, 0.5));

		blob(solver, new Random(9), 200, 0, 1, 0, 0.3, -30, -10, -30, 10f);
		run(solver, shapes, 4.0);

		for (int i = 0; i < solver.count; i++) {
			assertTrue(Double.isFinite(solver.x[i]) && Double.isFinite(solver.y[i]) && Double.isFinite(solver.z[i]));
			assertTrue(solver.y[i] > -0.05 && solver.x[i] > -5.05 && solver.z[i] > -5.05, "inside: " + solver.x[i] + ", " + solver.y[i] + ", " + solver.z[i]);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// the surface
	// ---------------------------------------------------------------------------------------------

	private static FluidSolver cloud(double... xyz) {
		FluidSolver solver = new FluidSolver(FluidParams.THICK, 64);

		for (int i = 0; i < xyz.length; i += 3) {
			solver.spawn(xyz[i], xyz[i + 1], xyz[i + 2], 0, 0, 0, 0);
			solver.age[i / 3] = 1f;
		}

		return solver;
	}

	private static int components(FluidMesh mesh) {
		int[] parent = new int[mesh.vertexCount];

		for (int i = 0; i < parent.length; i++)
			parent[i] = i;

		for (int t = 0; t < mesh.triangleCount; t++) {
			join(parent, mesh.indices[t * 3], mesh.indices[t * 3 + 1]);
			join(parent, mesh.indices[t * 3], mesh.indices[t * 3 + 2]);
		}

		Set<Integer> roots = new HashSet<>();

		for (int t = 0; t < mesh.triangleCount; t++)
			roots.add(find(parent, mesh.indices[t * 3]));

		return roots.size();
	}

	private static int find(int[] parent, int i) {
		while (parent[i] != i)
			i = parent[i] = parent[parent[i]];

		return i;
	}

	private static void join(int[] parent, int a, int b) {
		parent[find(parent, a)] = find(parent, b);
	}

	@Test
	@DisplayName("one particle is a closed little blob about half its reach across, facing outward, with unit normals")
	void oneParticleIsABlob() {
		FluidSolver solver = cloud(10.3, 64.2, -7.9);
		FluidMesh mesh = new FluidMesh();

		assertTrue(mesh.build(solver, 100_000));
		assertTrue(mesh.triangleCount >= 8, "a blob has faces: " + mesh.triangleCount);

		float r = FluidParams.THICK.radius();

		for (int v = 0; v < mesh.vertexCount; v++) {
			double dx = mesh.originX + mesh.positions[v * 3] - 10.3;
			double dy = mesh.originY + mesh.positions[v * 3 + 1] - 64.2;
			double dz = mesh.originZ + mesh.positions[v * 3 + 2] + 7.9;
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			float nx = mesh.normals[v * 3];
			float ny = mesh.normals[v * 3 + 1];
			float nz = mesh.normals[v * 3 + 2];

			assertTrue(distance > r * 0.3 && distance < r * 0.8, "vertex at " + distance + " of a reach " + r);
			assertEquals(1.0, Math.sqrt(nx * nx + ny * ny + nz * nz), 1e-3);
			assertTrue(nx * dx + ny * dy + nz * dz > 0, "the normal points out");
		}
	}

	@Test
	@DisplayName("the triangles wind counter-clockwise seen from outside, so a face culled by the winding is the inside")
	void windingFacesOutward() {
		FluidSolver solver = cloud(0, 0, 0, 0.07, 0.02, 0);
		FluidMesh mesh = new FluidMesh();

		assertTrue(mesh.build(solver, 100_000));

		int outward = 0;

		for (int t = 0; t < mesh.triangleCount; t++) {
			int a = mesh.indices[t * 3] * 3;
			int b = mesh.indices[t * 3 + 1] * 3;
			int c = mesh.indices[t * 3 + 2] * 3;
			float[] p = mesh.positions;
			double ux = p[b] - p[a];
			double uy = p[b + 1] - p[a + 1];
			double uz = p[b + 2] - p[a + 2];
			double wx = p[c] - p[a];
			double wy = p[c + 1] - p[a + 1];
			double wz = p[c + 2] - p[a + 2];
			double gx = uy * wz - uz * wy;
			double gy = uz * wx - ux * wz;
			double gz = ux * wy - uy * wx;
			// the vertex normals say which way is out
			double nx = mesh.normals[a] + mesh.normals[b] + mesh.normals[c];
			double ny = mesh.normals[a + 1] + mesh.normals[b + 1] + mesh.normals[c + 1];
			double nz = mesh.normals[a + 2] + mesh.normals[b + 2] + mesh.normals[c + 2];

			if (gx * nx + gy * ny + gz * nz > 0)
				outward++;
		}

		assertTrue(outward > mesh.triangleCount * 0.95, outward + " of " + mesh.triangleCount + " face outward");
	}

	@Test
	@DisplayName("the normals are the field's own gradient, exactly: a vertex's normal is minus the numerical gradient of the metaball sum there")
	void normalsAreTheFieldGradient() {
		FluidSolver solver = cloud(0, 0, 0, 0.08, 0.03, 0.02, -0.05, 0.07, 0.03);
		FluidMesh mesh = new FluidMesh();

		assertTrue(mesh.build(solver, 100_000));

		double eps = 1e-5;
		double worst = 1;

		for (int v = 0; v < mesh.vertexCount; v++) {
			double x = mesh.originX + mesh.positions[v * 3];
			double y = mesh.originY + mesh.positions[v * 3 + 1];
			double z = mesh.originZ + mesh.positions[v * 3 + 2];
			double gx = (field(solver, x + eps, y, z) - field(solver, x - eps, y, z)) / (2 * eps);
			double gy = (field(solver, x, y + eps, z) - field(solver, x, y - eps, z)) / (2 * eps);
			double gz = (field(solver, x, y, z + eps) - field(solver, x, y, z - eps)) / (2 * eps);
			double len = Math.sqrt(gx * gx + gy * gy + gz * gz);
			double dot = -(gx * mesh.normals[v * 3] + gy * mesh.normals[v * 3 + 1] + gz * mesh.normals[v * 3 + 2]) / len;

			worst = Math.min(worst, dot);
		}

		assertTrue(worst > 0.999, "the worst vertex normal is off the gradient: cos " + worst);
	}

	/** The metaball sum at a point, as the mesh defines it */
	private static double field(FluidSolver solver, double x, double y, double z) {
		double r2 = (double)solver.params.radius() * solver.params.radius();
		double sum = 0;

		for (int i = 0; i < solver.count; i++) {
			double d2 = (x - solver.x[i]) * (x - solver.x[i]) + (y - solver.y[i]) * (y - solver.y[i]) + (z - solver.z[i]) * (z - solver.z[i]);

			if (d2 < r2)
				sum += Math.pow(1 - d2 / r2, 3) * solver.scaleOf(i);
		}

		return sum;
	}

	@Test
	@DisplayName("two drops close together are one blob, and two far apart are two")
	void dropsJoinWhenNear() {
		FluidMesh near = new FluidMesh();
		FluidMesh far = new FluidMesh();

		assertTrue(near.build(cloud(0, 0, 0, 0.1, 0, 0), 100_000));
		assertTrue(far.build(cloud(0, 0, 0, 0.6, 0, 0), 100_000));
		assertEquals(1, components(near));
		assertEquals(2, components(far));
	}

	@Test
	@DisplayName("nothing to draw gives no mesh, and a mesh built again replaces the last")
	void emptyAndReused() {
		FluidMesh mesh = new FluidMesh();

		assertFalse(mesh.build(new FluidSolver(FluidParams.THICK, 4), 1000));
		assertTrue(mesh.isEmpty());
		assertTrue(mesh.build(cloud(0, 0, 0), 1000));

		int first = mesh.triangleCount;

		assertTrue(mesh.build(cloud(0, 0, 0), 1000));
		assertEquals(first, mesh.triangleCount);
	}

	@Test
	@DisplayName("a few hundred particles build a mesh quickly and never run a lookup forever")
	void aBigCloudBuilds() {
		FluidSolver solver = new FluidSolver(FluidParams.THICK, 800);

		blob(solver, new Random(2), 800, 3, 70, 3, 0.8, 0, 0, 0, 0f);

		for (int i = 0; i < solver.count; i++)
			solver.age[i] = 1f;

		FluidMesh mesh = new FluidMesh();
		long start = System.nanoTime();

		for (int i = 0; i < 20; i++)
			assertTrue(mesh.build(solver, 100_000));

		double perBuild = (System.nanoTime() - start) / 20 / 1e6;

		System.out.println("FLUID mesh of 800 particles: " + String.format("%.2f", perBuild) + " ms, " + mesh.triangleCount + " triangles");
		assertTrue(mesh.triangleCount > 100);
		assertTrue(perBuild < 40, "a build took " + perBuild + " ms");

		List<Collider.Placed> none = new ArrayList<>();
		long stepStart = System.nanoTime();

		for (int i = 0; i < 60; i++)
			solver.step(FluidSolver.STEP, none, 3);

		System.out.println("FLUID step of 800 particles: " + String.format("%.2f", (System.nanoTime() - stepStart) / 60 / 1e6) + " ms");
	}
}
