package com.sandymandy.strut.event.binding;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The weighted, silence-capable pick that replaces a per-client {@code Random.nextInt}
 */
class WeightedPickTest {
	private static final Identifier A = Identifier.of("m", "a");
	private static final Identifier B = Identifier.of("m", "b");

	@Test
	@DisplayName("a pool of one always gives that entry")
	void singleEntry() {
		for (long seed = 0; seed < 50; seed++)
			assertEquals(A, WeightedPick.pick(List.of(new WeightedSound(A, 1)), new SplittableRandom(seed)).sound());
	}

	@Test
	@DisplayName("the same random source always picks the same entry - which is what makes every client agree")
	void deterministic() {
		List<WeightedSound> pool = List.of(new WeightedSound(A, 1), new WeightedSound(B, 1), new WeightedSound(null, 2));

		for (long seed = 0; seed < 200; seed++)
			assertEquals(WeightedPick.pick(pool, new SplittableRandom(seed)), WeightedPick.pick(pool, new SplittableRandom(seed)));
	}

	@Test
	@DisplayName("entries are picked in proportion to their weights, silence included")
	void proportionalToWeight() {
		List<WeightedSound> pool = List.of(new WeightedSound(A, 1), new WeightedSound(B, 2), new WeightedSound(null, 3));
		Map<String, Integer> counts = new HashMap<>();
		int trials = 60_000;

		for (long seed = 0; seed < trials; seed++) {
			WeightedSound picked = WeightedPick.pick(pool, new SplittableRandom(seed * 7919 + 13));

			counts.merge(picked.isSilence() ? "silence" : picked.sound().getPath(), 1, Integer::sum);
		}

		// Expected 1/6, 2/6, 3/6 of the trials; a 2% band is many standard deviations at this size
		assertEquals(trials / 6.0, counts.get("a"), trials * 0.02);
		assertEquals(trials / 3.0, counts.get("b"), trials * 0.02);
		assertEquals(trials / 2.0, counts.get("silence"), trials * 0.02);
	}

	@Test
	@DisplayName("a pool of only silence picks silence")
	void onlySilence() {
		assertTrue(WeightedPick.pick(List.of(new WeightedSound(null, 1)), new SplittableRandom(1)).isSilence());
	}

	@Test
	@DisplayName("exactly one draw is taken, so what a caller draws next does not depend on the pool's size")
	void takesOneDraw() {
		SplittableRandom small = new SplittableRandom(99);
		SplittableRandom large = new SplittableRandom(99);

		WeightedPick.pick(List.of(new WeightedSound(A, 1)), small);
		WeightedPick.pick(List.of(new WeightedSound(A, 1), new WeightedSound(B, 5), new WeightedSound(null, 9)), large);

		assertEquals(small.nextLong(), large.nextLong());
	}

	@Test
	@DisplayName("an empty pool, and a weight below 1, are refused")
	void invalidInputs() {
		assertThrows(IllegalArgumentException.class, () -> WeightedPick.pick(List.of(), new SplittableRandom(1)));
		assertThrows(IllegalArgumentException.class, () -> new WeightedSound(A, 0));
		assertThrows(IllegalArgumentException.class, () -> new WeightedSound(A, -3));
	}
}
