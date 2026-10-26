package com.sandymandy.strut.physics.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.solver.ChainParams;
import com.sandymandy.strut.physics.solver.ChainSolver;
import com.sandymandy.strut.physics.solver.RagdollSolver;

/**
 * Fast things and thin blocks: a particle that moves further in one step than a block is thick used to be pushed out of the far side, so a fast chain or a
 * falling body went through slabs, carpets and walls. The swept test stops it where it first meets the block
 */
class TunnellingTest {
	private static final float H = 1f / 60f;

	/** A thin slab: {@code thickness} blocks, its top at {@code top} */
	private static Collider.Placed slab(double top, double thickness) {
		Collider.Placed s = new Collider.Placed();

		s.shape = Collider.Shape.BOX;
		s.p[1] = top - thickness / 2;
		s.half[0] = 20;
		s.half[1] = thickness / 2;
		s.half[2] = 20;
		s.axes.identity();

		return s;
	}

	@Test
	void theSweptTestStopsABallThatWouldJumpOverABlockInOneStep() {
		Collider.Placed wall = slab(0.05, 0.1);
		double[] from = {0, 1, 0};
		double[] to = {0.3, -1, 0};
		double[] prev = from.clone();

		assertTrue(Collider.sweep(List.of(wall), from, to, prev, 0.05));
		assertEquals(0.1, to[1], 1e-6, "stopped on top of the slab, its radius above it");
		assertEquals(0.3, to[0], 1e-6, "keeping its motion along the face");
		assertEquals(0.0, to[1] - prev[1], 1e-9, "and no velocity into it");

		double[] away = {0, 2, 0};

		assertFalse(Collider.sweep(List.of(wall), new double[] {0, 1, 0}, away, new double[] {0, 1, 0}, 0.05), "moving away is untouched");
	}

	@Test
	void aRagdollDroppedFromHighDoesNotFallThroughACarpet() {
		int[] parent = {-1, 0, 1};
		double[][] rest = {{0, 30, 0}, {0, 30.5, 0}, {0, 31, 0}};
		RagdollSolver.Skeleton sk = RagdollSolver.build(parent, rest, Math.toRadians(25));
		RagdollSolver.State st = new RagdollSolver.State(3);
		// a carpet: a sixteenth of a block thick, its top at y = 0
		List<Collider.Placed> floor = List.of(slab(0, 1 / 16.0));

		// falling at 40 blocks a second, two thirds of a block per step: ten times the carpet
		st.snapTo(rest, 0, -40, 0, H);

		double lowest = Double.MAX_VALUE;

		for (int i = 0; i < 300; i++) {
			RagdollSolver.step(st, sk, H, 24, 0.2, 0.3, 0.06, rest, 0.0, floor);

			for (double[] p : st.x)
				lowest = Math.min(lowest, p[1]);
		}

		assertTrue(lowest > 0.0, "a joint went through the carpet: " + lowest);
	}

	@Test
	void aChainFlungDownAtAThinSlabStaysOnTop() {
		ChainParams params = new ChainParams(List.of("a", "b"), 0.5f, 0.05f, 1f, 9.8f, 0.04f, 0f, 0.3f, 1f, Set.of("world"), 0.1f);
		ChainSolver.State st = new ChainSolver.State(3);
		// sticking out sideways a tenth of a block above a carpet-thin slab
		double[][] anim = {{0, 1, 0}, {0.3, 1, 0}, {0.6, 1, 0}};
		List<Collider.Placed> slab = List.of(slab(0.9, 1 / 16.0));

		st.snapTo(anim);

		// flung straight down at 60 blocks a second: a block a step
		st.prev[1][1] += 60 * H;
		st.prev[2][1] += 60 * H;

		double lowest = Double.MAX_VALUE;

		for (int i = 0; i < 120; i++) {
			ChainSolver.step(st, params, H, anim, new double[] {0.3, 0.3}, new double[3], slab);

			for (double[] p : st.x)
				lowest = Math.min(lowest, p[1]);
		}

		assertTrue(lowest > 0.9, "a particle went through the slab: " + lowest);
	}
}
