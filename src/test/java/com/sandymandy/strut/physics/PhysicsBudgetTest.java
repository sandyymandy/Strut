package com.sandymandy.strut.physics;

import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.tools.PhysicsStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The frame's physics budget: once it is spent the instances wait their turn, and every one of them gets it in the end */
class PhysicsBudgetTest {
	@BeforeEach
	void fresh() {
		PhysicsStats.reset();
	}

	@Test
	void aCrowdSharesTheBudgetInTurnAndNoInstanceWaitsForever() {
		int crowd = 40;
		int[] starved = new int[crowd];

		for (int i = 0; i < crowd; i++)
			starved[i] = i % PhysicsStats.MAX_CADENCE;

		int[] longestWait = {0};
		int[] ranTotal = {0};

		// each instance costs a fifth of the budget, so five of the forty fit in a frame without the rotation
		long cost = (long)(PhysicsOptions.quality().budgetMillis * 1_000_000.0 / 5);

		for (int frame = 0; frame < 60; frame++) {
			PhysicsStats.nextFrame();

			for (int i = 0; i < crowd; i++) {
				PhysicsStats.Turn turn = PhysicsStats.admit(starved[i]);

				if (turn == PhysicsStats.Turn.HOLD) {
					starved[i]++;
					longestWait[0] = Math.max(longestWait[0], starved[i]);
					PhysicsStats.record(0, 0, 0);
				}
				else {
					starved[i] = 0;
					ranTotal[0]++;
					PhysicsStats.record(10, turn == PhysicsStats.Turn.ONE ? 1 : 3, cost);
				}
			}
		}

		assertTrue(longestWait[0] <= PhysicsStats.MAX_CADENCE + 1, "no instance waits more than a few frames for its turn: " + longestWait[0]);
		// the budget bounds the work: far fewer turns than every instance every frame
		assertTrue(ranTotal[0] < crowd * 60 / 2, "turns taken " + ranTotal[0] + " of " + crowd * 60);
		assertTrue(ranTotal[0] > 60 * 5, "the budget's own turns are still taken " + ranTotal[0]);
	}

	@Test
	void underTheBudgetEveryoneHasAFullTurn() {
		PhysicsStats.nextFrame();

		for (int i = 0; i < 5; i++) {
			assertEquals(PhysicsStats.Turn.FULL, PhysicsStats.admit(0));
			PhysicsStats.record(10, 1, 1000);
		}
	}
}
