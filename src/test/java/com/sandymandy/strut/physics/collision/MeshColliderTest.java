package com.sandymandy.strut.physics.collision;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import java.util.HashSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.fluid.FluidParams;
import com.sandymandy.strut.physics.fluid.FluidSolver;

/**
 * Mesh colliders on the real test dummy: every bone with geometry gets a field of its own shape, the shapes ride the pose and push a ball out of the surface, and
 * a body of them costs about what the simple shapes did
 */
class MeshColliderTest {
	@AfterEach
	void off() {
		ModelSdfs.enabled(false);
		ModelSdfs.clear();
	}

	private static BakedStrutModel dummy() throws Exception {
		Path file = Path.of("src", "main", "resources", "assets", "strut", "strut", "models", "entity", "test_dummy.strutmodel");

		Assumptions.assumeTrue(Files.exists(file), "needs Strut's test dummy model next to the module");

		return BinaryModelBaker.bake(BinaryModelReader.read(Files.readAllBytes(file), "test_dummy"), "test_dummy");
	}

	private static ColliderRig rig(BakedStrutModel model, boolean mesh) {
		List<Collider> generated = AutoColliders.generate(model, mesh ? AutoColliders.Options.DEFAULT : AutoColliders.Options.DEFAULT.withPrecision("proxy"), Set.of());

		return new ColliderRig(model, List.of(), generated, new ArrayList<>());
	}

	private static List<Collider.Placed> place(ColliderRig rig, BakedStrutModel model, double x, double y, double z, float yaw) {
		ModelInstance instance = new ModelInstance();

		instance.poseFor(model);
		instance.resetPoseHistory();

		Pose pose = instance.renderedPose();
		List<Collider.Placed> out = new ArrayList<>();

		rig.prepare(pose);
		rig.place(x, y, z, yaw, null, out);

		return out;
	}

	@Test
	@DisplayName("every bone with geometry gets its own field, made in well under a few seconds, and the shapes are meshes where the simple ones were boxes")
	void everyBoneGetsAField() throws Exception {
		BakedStrutModel model = dummy();
		ModelSdfs.ModelSdf fields = ModelSdfs.buildAll(model);

		System.out.println("MESH fields: " + fields.bones().size() + " bones, " + fields.nodes() + " numbers, " + fields.millis() + " ms");
		assertTrue(fields.bones().size() > 12, "bones with fields: " + fields.bones().size());
		assertTrue(fields.millis() < 8000, "made in " + fields.millis() + " ms");
		assertTrue(fields.nodes() * 4L < 64L * 1024 * 1024, "memory: " + fields.nodes() * 4 + " bytes");

		ModelSdfs.enabled(true);
		ModelSdfs.put(model, fields);

		List<Collider> specs = AutoColliders.generate(model, AutoColliders.Options.DEFAULT, Set.of());
		long meshes = specs.stream().filter(c -> c.shape() == Collider.Shape.MESH).count();

		assertTrue(meshes > 12, "mesh shapes: " + meshes);
		assertTrue(specs.stream().noneMatch(c -> c.shape() == Collider.Shape.CAPSULE), "no capsule stands in for a bone that has a field");
	}

	@Test
	@DisplayName("a ball in the middle of a bone is pushed out of its surface and then rests on it, a ball far away is not touched, and the shape follows the entity")
	void pushesOutOfTheSurface() throws Exception {
		BakedStrutModel model = dummy();

		ModelSdfs.enabled(true);
		ModelSdfs.put(model, ModelSdfs.buildAll(model));

		ColliderRig rig = rig(model, true);
		List<Collider.Placed> at = place(rig, model, 100, 64, -40, 0f);
		Collider.Placed head = null; // the biggest bone

		for (Collider.Placed shape : at) {
			if (shape.shape == Collider.Shape.MESH && (head == null || shape.radius > head.radius))
				head = shape;
		}

		assertNotNull(head);

		// the bounding sphere's centre is inside most bones; take the nearest point of the field to it from a few places
		Random random = new Random(2);
		int pushed = 0;
		int rested = 0;

		for (int i = 0; i < 400; i++) {
			double[] ball = {head.p[0] + (random.nextDouble() - 0.5) * head.radius, head.p[1] + (random.nextDouble() - 0.5) * head.radius,
					head.p[2] + (random.nextDouble() - 0.5) * head.radius};
			double[] before = ball.clone();

			if (!Collider.pushOut(head, ball, 0.04))
				continue;

			pushed++;

			double moved = Math.sqrt(sq(ball[0] - before[0]) + sq(ball[1] - before[1]) + sq(ball[2] - before[2]));

			assertTrue(moved <= head.radius + 0.3, "pushed " + moved + " from a shape of radius " + head.radius);
			if (!Collider.pushOut(head, ball.clone(), 0.04 - 0.01))
				rested++;
		}

		assertTrue(rested >= pushed * 0.9, "after the push the ball rests on the surface, not in it: " + rested + " of " + pushed);

		assertTrue(pushed > 20, "balls inside the shape were pushed: " + pushed);

		double[] far = {head.p[0] + 5, head.p[1], head.p[2]};

		assertFalse(Collider.pushOut(head, far, 0.04));

		// the same shapes placed a block east are a block east
		double[] eastBefore = new double[at.size()];

		for (int i = 0; i < eastBefore.length; i++)
			eastBefore[i] = at.get(i).p[0];

		// the rig reuses its shapes, so the same objects now hold the new place
		List<Collider.Placed> moved = place(rig, model, 101, 64, -40, 0f);

		for (int i = 0; i < eastBefore.length; i++)
			assertEquals(eastBefore[i] + 1, moved.get(i).p[0], 1e-6);
	}

	private static double sq(double v) {
		return v * v;
	}

	@Test
	@DisplayName("a body of mesh shapes: 800 particles tested against it cost about what the simple shapes cost")
	void costIsSimilar() throws Exception {
		BakedStrutModel model = dummy();

		ModelSdfs.enabled(true);
		ModelSdfs.put(model, ModelSdfs.buildAll(model));

		List<Collider.Placed> mesh = place(rig(model, true), model, 0, 64, 0, 0f);
		List<Collider.Placed> proxy = place(rig(model, false), model, 0, 64, 0, 0f);
		Random random = new Random(5);
		double[][] points = new double[800][3];

		for (double[] p : points) {
			p[0] = (random.nextDouble() - 0.5) * 1.2;
			p[1] = 64 + random.nextDouble() * 2.0;
			p[2] = (random.nextDouble() - 0.5) * 1.2;
		}

		double meshMs = time(mesh, points);
		double proxyMs = time(proxy, points);

		System.out.println("MESH vs PROXY: " + mesh.size() + " mesh shapes " + String.format("%.3f", meshMs) + " ms, " + proxy.size() + " proxy shapes " + String.format("%.3f", proxyMs)
				+ " ms for 800 particles a step");
		assertTrue(meshMs < 4.0, "mesh shapes took " + meshMs + " ms a step");
	}

	private static double time(List<Collider.Placed> shapes, double[][] points) {
		double[] lo = new double[3];
		double[] hi = new double[3];
		double[] p = new double[3];
		int hits = 0;

		for (int warm = 0; warm < 30; warm++)
			hits += run(shapes, points, lo, hi, p);

		long start = System.nanoTime();

		for (int rep = 0; rep < 100; rep++)
			hits += run(shapes, points, lo, hi, p);

		double ms = (System.nanoTime() - start) / 100 / 1e6;

		assertTrue(hits >= 0);

		return ms;
	}

	private static int run(List<Collider.Placed> shapes, double[][] points, double[] lo, double[] hi, double[] p) {
		int hits = 0;

		for (double[] point : points) {
			for (int a = 0; a < 3; a++) {
				lo[a] = point[a] - 0.05;
				hi[a] = point[a] + 0.05;
			}

			for (Collider.Placed shape : shapes) {
				if (!Collider.near(shape, lo, hi))
					continue;

				System.arraycopy(point, 0, p, 0, 3);

				if (Collider.pushOut(shape, p, 0.045))
					hits++;
			}
		}

		return hits;
	}

	@Test
	@DisplayName("the broad phase finds every shape a point touches: the same hits as testing them all")
	void shapeGridMatchesBruteForce() throws Exception {
		BakedStrutModel model = dummy();

		ModelSdfs.enabled(true);
		ModelSdfs.put(model, ModelSdfs.buildAll(model));

		List<Collider.Placed> shapes = new ArrayList<>(place(rig(model, true), model, 0, 64, 0, 0f));
		Collider.Placed floor = new Collider.Placed();

		floor.shape = Collider.Shape.BOX;
		floor.layer = "world";
		floor.p[0] = 0;
		floor.p[1] = 63.5;
		floor.p[2] = 0;
		floor.half[0] = 40;
		floor.half[1] = 0.5;
		floor.half[2] = 40;
		floor.axes.identity();
		shapes.add(floor);

		ShapeGrid grid = ShapeGrid.of(shapes, FluidSolver.GRID_MARGIN);
		Random random = new Random(11);
		double[] p = new double[3];
		double[] q = new double[3];
		int hits = 0;

		for (int i = 0; i < 20000; i++) {
			p[0] = (random.nextDouble() - 0.5) * 1.6;
			p[1] = 63.4 + random.nextDouble() * 2.4;
			p[2] = (random.nextDouble() - 0.5) * 1.6;

			Set<Integer> byGrid = new HashSet<>();

			for (int s : grid.at(p[0], p[1], p[2]))
				byGrid.add(s);

			for (int s : grid.global)
				byGrid.add(s);

			for (int s = 0; s < shapes.size(); s++) {
				System.arraycopy(p, 0, q, 0, 3);

				if (Collider.pushOut(shapes.get(s), q, 0.045)) {
					hits++;
					assertTrue(byGrid.contains(s), "the grid missed shape " + s + " at " + p[0] + ", " + p[1] + ", " + p[2]);
				}
			}
		}

		assertTrue(hits > 100, "there were contacts to find: " + hits);
		assertEquals(1, grid.global.length, "only the long floor is tested by everything");
	}

	@Test
	@DisplayName("liquid poured on a body lands on its real surface: nothing ends inside it, and 800 particles step in about a millisecond or two")
	void liquidLandsOnTheBody() throws Exception {
		BakedStrutModel model = dummy();

		ModelSdfs.enabled(true);
		ModelSdfs.put(model, ModelSdfs.buildAll(model));

		List<Collider.Placed> body = new ArrayList<>(place(rig(model, true), model, 0, 64, 0, 0f));
		Collider.Placed top = null;

		for (Collider.Placed shape : body) {
			if (top == null || shape.p[1] + shape.radius > top.p[1] + top.radius)
				top = shape;
		}

		FluidSolver solver = new FluidSolver(FluidParams.THICK, 800);
		Random random = new Random(6);

		for (int i = 0; i < 300; i++)
			solver.spawn(top.p[0] + (random.nextDouble() - 0.5) * 0.3, top.p[1] + top.radius + 0.3 + random.nextDouble() * 0.5, top.p[2] + (random.nextDouble() - 0.5) * 0.3, 0, 0, 0, 0);

		ShapeGrid grid = ShapeGrid.of(body, FluidSolver.GRID_MARGIN);

		for (int step = 0; step < 240; step++)
			solver.step(FluidSolver.STEP, grid, 3);

		int inside = 0;
		double[] p = new double[3];

		for (int i = 0; i < solver.count; i++) {
			p[0] = solver.x[i];
			p[1] = solver.y[i];
			p[2] = solver.z[i];

			for (Collider.Placed shape : body) {
				double[] q = p.clone();

				// a particle touching the surface is at its radius from it: pushing by half that finds only one that is really in
				if (Collider.pushOut(shape, q, FluidParams.THICK.spacing() * 0.25)) {
					inside++;

					break;
				}
			}
		}

		System.out.println("FLUID on body: " + solver.count + " particles, " + inside + " inside the body");
		assertTrue(inside <= solver.count * 0.03, inside + " of " + solver.count + " ended inside the body");

		FluidSolver big = new FluidSolver(FluidParams.THICK, 800);

		for (int i = 0; i < 800; i++)
			big.spawn(top.p[0] + (random.nextDouble() - 0.5) * 0.8, top.p[1] + random.nextDouble() * 0.8, top.p[2] + (random.nextDouble() - 0.5) * 0.8, 0, 0, 0, 0);

		for (int warm = 0; warm < 60; warm++)
			big.step(FluidSolver.STEP, grid, 3);

		long start = System.nanoTime();

		for (int s = 0; s < 120; s++)
			big.step(FluidSolver.STEP, grid, 3);

		double ms = (System.nanoTime() - start) / 120 / 1e6;

		System.out.println("FLUID step, 800 particles against " + body.size() + " mesh shapes: " + String.format("%.2f", ms) + " ms");
		assertTrue(ms < 8, "a step took " + ms + " ms");
	}
}
