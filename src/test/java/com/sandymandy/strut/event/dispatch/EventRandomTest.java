package com.sandymandy.strut.event.dispatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-occurrence random source - the property that matters is that the
 * server and every client draw the same values for the same firing
 */
class EventRandomTest {
	private static final UUID ENTITY = new UUID(0x1234_5678_9abc_def0L, 0x0fed_cba9_8765_4321L);

	private static long first(UUID entity, String controller, long sequence, long loop, int index) {
		return EventRandom.forOccurrence(entity, controller, sequence, loop, index).nextLong();
	}

	@Test
	@DisplayName("the same occurrence always gives the same sequence of values")
	void sameOccurrenceSameValues() {
		RandomGenerator a = EventRandom.forOccurrence(ENTITY, "base", 7, 2, 3);
		RandomGenerator b = EventRandom.forOccurrence(ENTITY, "base", 7, 2, 3);

		for (int i = 0; i < 20; i++)
			assertEquals(a.nextLong(), b.nextLong());
	}

	@Test
	@DisplayName("a pinned value: the seed arithmetic must never drift between JVMs or releases")
	void pinnedValue() {
		// Server and clients agree only if this never changes - a client on another build must draw the
		// same voice line. If this fails, the seeding changed, which is a protocol break, not a test fix
		assertEquals(PINNED, first(ENTITY, "base", 7, 2, 3));
	}

	/** Recorded from the first run of this implementation: it detects drift, it is not an independent derivation */
	private static final long PINNED = 4041816483330083155L;

	@Test
	@DisplayName("every part of the occurrence's identity changes the draw")
	void everyComponentMatters() {
		long base = first(ENTITY, "base", 7, 2, 3);

		assertNotEquals(base, first(new UUID(1, 2), "base", 7, 2, 3), "entity");
		assertNotEquals(base, first(ENTITY, "attack", 7, 2, 3), "controller");
		assertNotEquals(base, first(ENTITY, "base", 8, 2, 3), "sequence");
		assertNotEquals(base, first(ENTITY, "base", 7, 3, 3), "loop");
		assertNotEquals(base, first(ENTITY, "base", 7, 2, 4), "event index");
	}

	@Test
	@DisplayName("neighbouring occurrences are not correlated")
	void neighboursAreUnrelated() {
		// Consecutive loops of one event should not draw consecutive-looking values
		long a = first(ENTITY, "base", 1, 0, 0);
		long b = first(ENTITY, "base", 1, 1, 0);

		assertTrue(Long.bitCount(a ^ b) > 16, "adjacent seeds should differ in about half their bits, differed in " + Long.bitCount(a ^ b));
	}
}
