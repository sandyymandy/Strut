package com.sandymandy.strut.event.dispatch;

import java.util.SplittableRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;
import com.sandymandy.strut.event.server.EventContext;

/**
 * The seeded random source behind {@code EventContext#random()}
 * <p>
 * A pure function of the occurrence's identity, so the server and every client draw the same sequence
 * for the same firing. It only uses arithmetic the language specifies ({@link String#hashCode} is
 * spelled out in the spec, {@link SplittableRandom} is a fixed algorithm), so the result does not
 * depend on the JVM or the platform.
 */
public final class EventRandom {
	private EventRandom() {}

	public static RandomGenerator forOccurrence(UUID entity, String controller, long sequence, long loop, int eventIndex) {
		long seed = mix(entity.getMostSignificantBits());

		seed = mix(seed ^ entity.getLeastSignificantBits());
		seed = mix(seed ^ controller.hashCode());
		seed = mix(seed ^ sequence);
		seed = mix(seed ^ loop);
		seed = mix(seed ^ eventIndex);

		return new SplittableRandom(seed);
	}

	/**
	 * A 64-bit finaliser (MurmurHash3's), so nearby inputs - sequence 5 and 6, loop 0 and 1 - give
	 * unrelated seeds instead of correlated ones
	 */
	private static long mix(long value) {
		value ^= value >>> 33;
		value *= 0xff51afd7ed558ccdL;
		value ^= value >>> 33;
		value *= 0xc4ceb9fe1a85ec53L;
		value ^= value >>> 33;

		return value;
	}
}
