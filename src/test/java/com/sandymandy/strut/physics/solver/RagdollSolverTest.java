package com.sandymandy.strut.physics.solver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.collision.Collider;

/**
 * The ragdoll solver on a stick figure: it holds together, falls, lies on a floor without going through it, keeps its joints from folding through themselves,
 * is held up by a pinned particle, and, with muscle, stands as the animation says
 */
class RagdollSolverTest {
	// 0 pelvis, 1 chest, 2 head, 3 left hip, 4 left foot, 5 right hip, 6 right foot, 7 left shoulder, 8 left hand, 9 right shoulder, 10 right hand
	private static final int[] PARENT = {-1, 0, 1, 0, 3, 0, 5, 1, 7, 1, 9};
	private static final double[][] STAND = {
			{0, 1.0, 0}, {0, 1.5, 0}, {0, 1.9, 0},
			{0.15, 1.0, 0}, {0.15, 0.1, 0}, {-0.15, 1.0, 0}, {-0.15, 0.1, 0},
			{0.3, 1.5, 0}, {0.3, 0.8, 0}, {-0.3, 1.5, 0}, {-0.3, 0.8, 0}};
	private static final double RADIUS = 0.06;
	private static final float H = 1f / 60f;

	private static final RagdollSolver.Skeleton SKELETON = RagdollSolver.build(PARENT, STAND, Math.toRadians(25));

	private static Collider.Placed floor() {
		Collider.Placed f = new Collider.Placed();

		f.shape = Collider.Shape.BOX;
		f.p[1] = -0.5;
		f.half[0] = 50;
		f.half[1] = 0.5;
		f.half[2] = 50;
		f.axes.identity();

		return f;
	}

	private static RagdollSolver.State standing(double lift) {
		RagdollSolver.State st = new RagdollSolver.State(STAND.length);
		double[][] start = new double[STAND.length][];

		for (int k = 0; k < start.length; k++)
			start[k] = new double[] {STAND[k][0], STAND[k][1] + lift, STAND[k][2]};

		st.snapTo(start, 0, 0, 0, H);

		return st;
	}

	private static void run(RagdollSolver.State st, int steps, double strength, double[][] anim, List<Collider.Placed> colliders) {
		for (int i = 0; i < steps; i++)
			RagdollSolver.step(st, SKELETON, H, 24, 0.5, 0.3, RADIUS, anim, strength, colliders);
	}

	@Test
	void aLimpBodyFallsAndLiesOnTheFloorWithItsBonesIntact() {
		RagdollSolver.State st = standing(0.5);

		run(st, 400, 0.0, STAND, List.of(floor()));

		double lowest = Double.MAX_VALUE;
		double fastest = 0;

		for (int k = 0; k < st.x.length; k++) {
			lowest = Math.min(lowest, st.x[k][1]);
			fastest = Math.max(fastest, Math.sqrt(Math.pow(st.x[k][0] - st.prev[k][0], 2) + Math.pow(st.x[k][1] - st.prev[k][1], 2) + Math.pow(st.x[k][2] - st.prev[k][2], 2)) / H);
		}

		assertTrue(lowest >= RADIUS - 1e-6, "a particle went through the floor: " + lowest);
		assertTrue(st.x[2][1] < 0.5, "the head came down: " + st.x[2][1]);
		assertTrue(fastest < 0.2, "it came to rest: " + fastest);
		assertTrue(RagdollSolver.worstStickError(st, SKELETON) < 0.03, "the bones held their length: " + RagdollSolver.worstStickError(st, SKELETON));
	}

	@Test
	void fullMuscleIsExactlyTheAnimationEvenUnderGravity() {
		RagdollSolver.State st = standing(0);

		run(st, 120, 1.0, STAND, List.of(floor()));

		for (int k = 0; k < st.x.length; k++) {
			for (int a = 0; a < 3; a++)
				assertEquals(STAND[k][a], st.x[k][a], 1e-6, "particle " + k + " axis " + a);
		}
	}

	@Test
	void aHalfStrengthBodyStaysCloserToThePoseThanALimpOneButSagsFromIt() {
		RagdollSolver.State limp = standing(0);
		RagdollSolver.State half = standing(0);

		run(limp, 300, 0.0, STAND, List.of(floor()));
		run(half, 300, 0.6, STAND, List.of(floor()));

		double limpError = 0;
		double halfError = 0;

		for (int k = 0; k < STAND.length; k++) {
			limpError += Math.abs(limp.x[k][1] - STAND[k][1]);
			halfError += Math.abs(half.x[k][1] - STAND[k][1]);
		}

		assertTrue(halfError < limpError * 0.5, "muscle holds it up: " + halfError + " against " + limpError);
		assertTrue(halfError > 1e-3, "but not exactly, it is not at full strength");
	}

	@Test
	void aJointNeverClosesFurtherThanItsLimit() {
		RagdollSolver.State st = standing(2.0);

		// dropped from two blocks, so it crumples on landing
		run(st, 240, 0.0, STAND, List.of(floor()));

		for (int m = 0; m < SKELETON.minA().length; m++) {
			double[] a = st.x[SKELETON.minA()[m]];
			double[] b = st.x[SKELETON.minB()[m]];
			double d = Math.sqrt(Math.pow(b[0] - a[0], 2) + Math.pow(b[1] - a[1], 2) + Math.pow(b[2] - a[2], 2));

			assertTrue(d >= SKELETON.minDistance()[m] - 0.02, "joint " + m + " closed to " + d + " below " + SKELETON.minDistance()[m]);
		}
	}

	@Test
	void particlesThatAreNotNeighboursDoNotOverlap() {
		RagdollSolver.State st = standing(1.0);

		run(st, 300, 0.0, STAND, List.of(floor()));

		for (int[] pair : SKELETON.apart()) {
			double[] a = st.x[pair[0]];
			double[] b = st.x[pair[1]];
			double d = Math.sqrt(Math.pow(b[0] - a[0], 2) + Math.pow(b[1] - a[1], 2) + Math.pow(b[2] - a[2], 2));

			assertTrue(d >= 2 * RADIUS - 0.01, "particles " + pair[0] + " and " + pair[1] + " overlap: " + d);
		}
	}

	@Test
	void aPinnedParticleHoldsTheBodyUpAndTheRestHangsFromIt() {
		RagdollSolver.State st = standing(0.5);

		st.pin[2] = new double[] {0, 3.0, 0};
		run(st, 400, 0.0, STAND, List.of(floor()));

		assertEquals(3.0, st.x[2][1], 1e-9);
		assertTrue(st.x[0][1] < 2.15, "the pelvis hangs below the head: " + st.x[0][1]);
		assertTrue(RagdollSolver.worstStickError(st, SKELETON) < 0.05);

		st.pin[2] = null;
		run(st, 400, 0.0, STAND, List.of(floor()));
		assertTrue(st.x[2][1] < 1.0, "and falls when let go: " + st.x[2][1]);
	}

	@Test
	void theSkeletonHasTheSticksBracesAndLimitsItShould() {
		// 10 parent-child sticks; braces among children of one parent: pelvis has 3 children (3 pairs), chest 3 (3 pairs)
		assertEquals(10 + 3 + 3, SKELETON.stickA().length);
		// a limit for every particle with a grandparent: 2 (chest), 3 (hip), 4, 5, 6, 7, 8, 9, 10 minus those without one
		assertEquals(7, SKELETON.minA().length);

		for (double d : SKELETON.minDistance())
			assertTrue(d >= 0);
	}
}
