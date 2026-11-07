package com.sandymandy.strut.physics.flesh;

import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The soft body's dynamics without a game: a cage fitted to a ball of points, carried by scripted targets
 */
class FleshSolverTest {
	private static final double H = FleshSolver.STEP;

	private static FleshParams params(String json) {
		PhysicsConfig config = PhysicsConfig.parse("{\"flesh\": {\"s\": " + json + "}}", "test");

		assertTrue(config.warnings().isEmpty(), config.warnings().toString());

		return config.flesh().get("s");
	}

	/** Points on a ball of radius {@code r} about {@code (cx, cy, cz)} */
	private static double[][] ball(double cx, double cy, double cz, double r, int count) {
		double[][] points = new double[count][];
		double golden = Math.PI * (3 - Math.sqrt(5));

		for (int i = 0; i < count; i++) {
			double y = 1 - 2.0 * (i + 0.5) / count;
			double ring = Math.sqrt(1 - y * y);
			double a = golden * i;

			points[i] = new double[] {cx + r * ring * Math.cos(a), cy + r * y, cz + r * ring * Math.sin(a)};
		}

		return points;
	}

	private static FleshCage cage(FleshParams params, double cx) {
		return FleshCage.fit(ball(cx, 1.3, -0.2, 0.12, 600), null, null, new Matrix4f(), params);
	}

	/** Puts the region's targets at its rest nodes moved by {@code (x, y, z)} */
	private static void place(FleshSolver.Region r, double x, double y, double z) {
		for (int i = 0; i < FleshCageGeometry.NODES; i++) {
			r.target[i * 3] = r.cage.rest[i * 3] + x;
			r.target[i * 3 + 1] = r.cage.rest[i * 3 + 1] + y;
			r.target[i * 3 + 2] = r.cage.rest[i * 3 + 2] + z;
		}

		System.arraycopy(r.cage.tip, 0, r.tipDirection, 0, 3);
	}

	@SuppressWarnings("unchecked")
	private static void step(FleshSolver.Region[] regions, FleshParams params, List<Collider.Placed> body) {
		List<Collider.Placed>[] bodies = new List[regions.length];
		List<Collider.Placed>[] presses = new List[regions.length];

		for (int i = 0; i < regions.length; i++) {
			bodies[i] = body;
			presses[i] = List.of();
		}

		FleshSolver.step(regions, params, bodies, presses, -100, H);
	}

	private static double largest(FleshSolver.Region r) {
		double most = 0;

		for (int i = 0; i < FleshCageGeometry.NODES; i++)
			most = Math.max(most, Math.sqrt(r.displacement[i * 3] * r.displacement[i * 3] + r.displacement[i * 3 + 1] * r.displacement[i * 3 + 1]
					+ r.displacement[i * 3 + 2] * r.displacement[i * 3 + 2]));

		return most;
	}

	@Test
	void theSpringSettlesWhereTheForceHoldsIt() {
		double[] state = {0, 0};

		for (int i = 0; i < 2000; i++)
			Oscillator.step(state, 9.8, 10, 0.3, H);

		assertEquals(9.8 / 100, state[0], 1e-9);
		assertEquals(0, state[1], 1e-9);
	}

	@Test
	void theSpringRingsDownAtItsDampingRatio() {
		double zeta = 0.18;
		double omega = 10;
		double[] state = {1, 0};
		List<Double> peaks = new ArrayList<>();
		double before = 1;
		double velocityBefore = 0;

		for (int i = 0; i < 2000 && peaks.size() < 3; i++) {
			Oscillator.step(state, 0, omega, zeta, 1e-3);

			// a peak where the velocity turns from up to down
			if (velocityBefore > 0 && state[1] <= 0)
				peaks.add(state[0]);

			before = state[0];
			velocityBefore = state[1];
		}

		assertTrue(peaks.size() >= 2, "peaks " + peaks);

		double decrement = Math.log(peaks.get(0) / peaks.get(1));

		assertEquals(2 * Math.PI * zeta / Math.sqrt(1 - zeta * zeta), decrement, 0.01);
		assertTrue(before < 1);
	}

	@Test
	void criticalAndOverDampedSpringsDoNotOvershoot() {
		for (double zeta : new double[] {1.0, 2.5}) {
			double[] state = {1, 0};

			for (int i = 0; i < 600; i++) {
				Oscillator.step(state, 0, 20, zeta, H);
				assertTrue(state[0] >= -1e-9, "zeta " + zeta + " overshot to " + state[0]);
			}
		}
	}

	@Test
	void theCageIsAnIcosphereWithItsCentre() {
		FleshCageGeometry g = FleshCageGeometry.get();

		assertEquals(162, FleshCageGeometry.SURFACE);
		assertEquals(480 + 162, g.linkA.length);

		for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
			double l = Math.sqrt(g.dir[i * 3] * g.dir[i * 3] + g.dir[i * 3 + 1] * g.dir[i * 3 + 1] + g.dir[i * 3 + 2] * g.dir[i * 3 + 2]);

			assertEquals(1, l, 1e-9);
			assertTrue(g.neighbours[i].length == 5 || g.neighbours[i].length == 6);
		}
	}

	@Test
	void theLookupFindsEveryNodeTheBlendNeeds() {
		FleshCageGeometry g = FleshCageGeometry.get();
		Random random = new Random(7);

		for (int t = 0; t < 5000; t++) {
			double x = random.nextGaussian();
			double y = random.nextGaussian();
			double z = random.nextGaussian();
			double l = Math.sqrt(x * x + y * y + z * z);
			int[] near = g.near(x, y, z);

			for (int i = 0; i < FleshCageGeometry.SURFACE; i++) {
				double d = (x * g.dir[i * 3] + y * g.dir[i * 3 + 1] + z * g.dir[i * 3 + 2]) / l;

				if (d > FleshCage.KERNEL) {
					boolean listed = false;

					for (int j : near)
						listed |= j == i;

					assertTrue(listed, "node " + i + " at " + d + " missing for " + x + ", " + y + ", " + z);
				}
			}
		}
	}

	@Test
	void aCageFitsABall() {
		FleshParams params = params("{\"bones\": [\"b\"]}");
		FleshCage cage = cage(params, 0.1);

		assertEquals(0.1, cage.centre[0], 0.01);
		assertEquals(1.3, cage.centre[1], 0.01);
		assertEquals(-0.2, cage.centre[2], 0.01);

		for (int a = 0; a < 3; a++)
			assertEquals(0.12, cage.half[a], 0.012);

		for (double r : cage.radius)
			assertEquals(1, r, 0.12);

		// the ball sits in front of the body's middle line and to the side: its tip is the end farthest out, forward here
		assertEquals(-1, cage.tip[2], 1e-9);
		assertEquals(1, cage.up[1], 1e-6);

		for (double w : cage.anchor)
			assertTrue(w >= 0 && w <= 1);

		assertEquals(0.15625, cage.limit, 1e-6);
	}

	@Test
	void everyVertexIsBoundWithWeightsThatSumToOne() {
		FleshParams params = params("{\"bones\": [\"b\"]}");
		FleshCage cage = cage(params, 0.1);
		double[][] points = ball(0.1, 1.3, -0.2, 0.1, 400);
		FleshCage.Binding binding = cage.bind(points);

		for (int k = 0; k < points.length; k++) {
			double sum = 0;

			for (int j = binding.start()[k]; j < binding.start()[k + 1]; j++)
				sum += binding.weight()[j];

			assertEquals(1, sum, 1e-5);
			assertTrue(binding.surface()[k] >= 0 && binding.surface()[k] <= 1);
			assertTrue(binding.softness()[k] >= 0 && binding.softness()[k] <= 1);
		}
	}

	@Test
	void standingStillNothingMoves() {
		FleshParams params = params("{\"bones\": [\"b\"], \"step\": {\"rise\": 0}}");
		FleshSolver.Region r = new FleshSolver.Region(cage(params, 0.1));

		for (int i = 0; i < 6000; i++) {
			place(r, 0, 0, 0);
			step(new FleshSolver.Region[] {r}, params, List.of());
		}

		// only the sag of the free flesh under its own weight, which settles and stays
		double sag = largest(r);

		for (int i = 0; i < 600; i++) {
			place(r, 0, 0, 0);
			step(new FleshSolver.Region[] {r}, params, List.of());
		}

		assertEquals(sag, largest(r), 1e-9);
		assertTrue(sag < 0.05, "sag " + sag);
	}

	@Test
	void theAnimationAloneShakesIt() {
		FleshParams params = params("{\"bones\": [\"b\"], \"node\": {\"gravity\": 0}}");
		FleshSolver.Region r = new FleshSolver.Region(cage(params, 0.1));
		double most = 0;

		// the entity stands still; only the animation bobs the region up and down
		for (int i = 0; i < 600; i++) {
			place(r, 0, 0.03 * Math.sin(i * H * 2 * Math.PI * 2), 0);
			step(new FleshSolver.Region[] {r}, params, List.of());
			most = Math.max(most, Math.abs(r.lump[1]));
		}

		assertTrue(most > 0.005, "the lump moved only " + most);
		assertTrue(largest(r) <= r.cage.limit + 1e-9);
	}

	@Test
	void neverPastTheLimit() {
		FleshParams params = params("{\"bones\": [\"b\"]}");
		FleshSolver.Region r = new FleshSolver.Region(cage(params, 0.1));
		Random random = new Random(3);
		double x = 0;
		double y = 0;
		double z = 0;

		for (int i = 0; i < 3000; i++) {
			// violent jolts every few steps
			if (i % 7 == 0) {
				x += random.nextGaussian() * 0.3;
				y += random.nextGaussian() * 0.3;
				z += random.nextGaussian() * 0.3;
			}

			place(r, x, y, z);
			step(new FleshSolver.Region[] {r}, params, List.of());

			double d = largest(r);

			assertTrue(Double.isFinite(d));
			assertTrue(d <= r.cage.limit + 1e-9, "step " + i + ": " + d + " past " + r.cage.limit);
		}
	}

	@Test
	void turningSwingsItBehindTheTurn() {
		FleshParams params = params("{\"bones\": [\"b\"], \"node\": {\"gravity\": 0}}");
		FleshSolver.Region r = new FleshSolver.Region(cage(params, 0.0));
		double angle = 0;
		double lateral = 0;

		for (int i = 0; i < 30; i++) {
			// turning the positive way about up at 4 radians a second, the region in front of the axis
			angle += 4 * H;

			Matrix3f turn = new Matrix3f().rotationY((float)angle);
			org.joml.Vector3f v = new org.joml.Vector3f();

			for (int n = 0; n < FleshCageGeometry.NODES; n++) {
				v.set((float)r.cage.rest[n * 3], (float)r.cage.rest[n * 3 + 1], (float)r.cage.rest[n * 3 + 2]);
				turn.transform(v);
				r.target[n * 3] = v.x;
				r.target[n * 3 + 1] = v.y;
				r.target[n * 3 + 2] = v.z;
			}

			v.set((float)r.cage.tip[0], (float)r.cage.tip[1], (float)r.cage.tip[2]);
			turn.transform(v);
			r.tipDirection[0] = v.x;
			r.tipDirection[1] = v.y;
			r.tipDirection[2] = v.z;
			step(new FleshSolver.Region[] {r}, params, List.of());

			// the way the front moves when it turns the positive way: up crossed with the tip
			double sideX = r.tipDirection[2];
			double sideZ = -r.tipDirection[0];

			lateral = r.lump[0] * sideX + r.lump[2] * sideZ;
		}

		assertTrue(lateral < -0.005, "it should lag behind the turn, swung " + lateral);
	}

	@Test
	void aPairPressedTogetherDoesNotPassThroughItself() {
		FleshParams params = params("{\"bones\": [\"l\", \"r\"], \"node\": {\"gravity\": 0}}");
		FleshSolver.Region left = new FleshSolver.Region(cage(params, -0.13));
		FleshSolver.Region right = new FleshSolver.Region(cage(params, 0.13));
		FleshSolver.Region[] pair = {left, right};
		double worst = -Double.MAX_VALUE;

		for (int i = 0; i < 600; i++) {
			// the body shoved left and right hard: each lump is thrown at the other in turn
			double x = 0.4 * Math.signum(Math.sin(i * H * 2 * Math.PI * 1.5));

			place(left, x, 0, 0);
			place(right, x, 0, 0);
			step(pair, params, List.of());

			if (left.paired)
				worst = Math.max(worst, (left.lump[0] - right.lump[0]));
		}

		// they close no further than the room between them and the deepest squeeze allowed
		double gap = 0.26 - 2 * 0.12;

		assertTrue(worst <= gap + 0.09375 + 1e-6, "closed by " + worst);
	}

	@Test
	void aBodyPartTouchingItAtRestDoesNotPushIt() {
		FleshParams params = params("{\"bones\": [\"b\"], \"node\": {\"gravity\": 0}, \"step\": {\"rise\": 0}}");
		FleshSolver.Region free = new FleshSolver.Region(cage(params, 0.1));
		FleshSolver.Region touching = new FleshSolver.Region(cage(params, 0.1));
		// a torso box that the back of the region sinks into as modelled
		Collider.Placed torso = new Collider.Placed();

		torso.shape = Collider.Shape.BOX;
		torso.p[0] = 0.1;
		torso.p[1] = 1.3;
		torso.p[2] = 0.05;
		torso.half[0] = 0.3;
		torso.half[1] = 0.3;
		torso.half[2] = 0.2;
		torso.layer = "body";

		for (int i = 0; i < 300; i++) {
			place(free, 0, 0, 0);
			place(touching, 0, 0, 0);
			step(new FleshSolver.Region[] {free}, params, List.of());
			step(new FleshSolver.Region[] {touching}, params, List.of(torso));
		}

		assertEquals(largest(free), largest(touching), 1e-9);
	}

	@Test
	void aCalmRegionRestsAndWakesWhenStirredOrPressed() {
		FleshParams params = params("{\"bones\": [\"b\"]}");
		FleshSolver.Region r = new FleshSolver.Region(cage(params, 0.1));

		for (int i = 0; i < 600; i++) {
			place(r, 0, 0, 0);
			step(new FleshSolver.Region[] {r}, params, List.of());
		}

		assertTrue(FleshSolver.asleep(r), "a region standing still rests");

		double[] settled = r.displacement.clone();

		// a jolt wakes it
		place(r, 0, 0.05, 0);
		step(new FleshSolver.Region[] {r}, params, List.of());
		place(r, 0, 0.05, 0);
		step(new FleshSolver.Region[] {r}, params, List.of());
		assertFalse(FleshSolver.asleep(r));

		for (int i = 0; i < 600; i++) {
			place(r, 0, 0.05, 0);
			step(new FleshSolver.Region[] {r}, params, List.of());
		}

		assertTrue(FleshSolver.asleep(r));
		// to within a sliver of a pixel: it falls asleep once it moves less than anyone could see
		assertArrayEquals(settled, r.displacement, 1e-3, "it settles back to the same sag");

		// a hand coming near wakes it
		Collider.Placed hand = new Collider.Placed();

		hand.shape = Collider.Shape.SPHERE;
		hand.p[0] = 0.1;
		hand.p[1] = 1.35;
		hand.p[2] = -0.35;
		hand.radius = 0.05;
		hand.layer = "other";

		@SuppressWarnings("unchecked")
		List<Collider.Placed>[] bodies = new List[] {List.of()};
		@SuppressWarnings("unchecked")
		List<Collider.Placed>[] presses = new List[] {List.of(hand)};

		place(r, 0, 0.05, 0);
		FleshSolver.step(new FleshSolver.Region[] {r}, params, bodies, presses, -100, FleshSolver.STEP);
		assertFalse(FleshSolver.asleep(r));
		assertTrue(largest(r) > 0.01, "the hand presses the flesh in");
	}

	@Test
	void somethingPressingIntoFleshPushesItBackOutTheWayItCameIn() {
		// an arm laid across in front of the region's middle: its back face, the one facing the middle at the origin, is at z = -0.1
		Collider.Placed arm = new Collider.Placed();

		arm.shape = Collider.Shape.BOX;
		arm.p[2] = -0.2;
		arm.half[0] = 0.3;
		arm.half[1] = 0.05;
		arm.half[2] = 0.1;
		arm.layer = "other";

		double[] push = new double[3];
		double[] g = new double[3];

		// clear of it: nothing
		assertFalse(Collider.pressOut(arm, 0, 0, 0, 0, 0, 0, 0.025, push, g));

		// 0.03 inside the back face: out through it, toward the middle, to the surface
		assertTrue(Collider.pressOut(arm, 0, 0, -0.13, 0, 0, 0, 0.025, push, g));
		assertEquals(0.03, push[2], 1e-6);
		assertEquals(0, push[0], 1e-9);

		// deeper than the middle of the arm, nearer its far face: still out the near side, never through the arm
		assertTrue(Collider.pressOut(arm, 0, 0, -0.27, 0, 0, 0, 0.025, push, g));
		assertTrue(push[2] > 0.15, "pushed toward the middle, by " + push[2]);
	}

	@Test
	void theConfigReadsASoftSectionAndTakesTheBoneFromTheSprings() {
		PhysicsConfig config = PhysicsConfig.parse("{\"bones\": {\"pouchL\": {}, \"tail\": {}}, \"soft\": {\"chest\": {\"preset\": \"pouch\", \"bones\": [\"pouchL\", \"pouchR\"],"
				+ " \"lump\": {\"damping\": 0.25}, \"tip\": \"-z\"}, \"seat\": {\"preset\": \"seat\", \"bones\": [\"seat\"]}}}", "test");

		assertEquals(2, config.flesh().size());
		assertEquals(0.25f * 1.4f, config.flesh().get("chest").lumpDamping(), 1e-6f);
		assertArrayEquals(new float[] {0, 0, -1}, config.flesh().get("chest").tip());
		assertEquals(0.8f, config.flesh().get("seat").reach(), 1e-6f);
		assertFalse(config.bones().containsKey("pouchL"));
		assertTrue(config.bones().containsKey("tail"));
		assertTrue(config.warnings().stream().anyMatch(w -> w.contains("pouchL")), config.warnings().toString());
	}
}
