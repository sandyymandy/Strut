package com.sandymandy.strut.event.binding;

import java.util.List;
import java.util.random.RandomGenerator;
import com.sandymandy.strut.event.client.ClientEventContext;
import com.sandymandy.strut.event.server.EventContext;

/**
 * Picks one entry from a weighted pool, deterministically for a given random source
 * <p>
 * Fed by {@code EventContext#random()} / {@code ClientEventContext#random()}, which are identical on every
 * machine for the same firing - so every client picks the same line, and so does the server. That is the
 * whole point of routing the pick through here instead of {@code Random.nextInt} per client
 */
public final class WeightedPick {
	private WeightedPick() {}

	/**
	 * @param pool The entries; must not be empty
	 * @return One of them, chosen with probability proportional to its weight. Exactly one draw is taken
	 *         from {@code random}, so what a caller draws after this is unaffected by the pool's size
	 */
	public static WeightedSound pick(List<WeightedSound> pool, RandomGenerator random) {
		if (pool.isEmpty())
			throw new IllegalArgumentException("cannot pick from an empty pool");

		long total = 0;

		for (WeightedSound entry : pool) {
			total += entry.weight();
		}

		long roll = random.nextLong(total);

		for (WeightedSound entry : pool) {
			roll -= entry.weight();

			if (roll < 0)
				return entry;
		}

		// Unreachable: roll < total and the weights sum to total
		return pool.get(pool.size() - 1);
	}
}
