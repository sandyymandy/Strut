package com.sandymandy.strut.event.dispatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bounded duplicate guard
 */
class RecentOccurrencesTest {
	@Test
	@DisplayName("a key is new the first time and a duplicate every time after")
	void duplicatesAreCaught() {
		RecentOccurrences recent = new RecentOccurrences(8);

		assertTrue(recent.firstTime("a"));
		assertFalse(recent.firstTime("a"));
		assertFalse(recent.firstTime("a"));
		assertTrue(recent.firstTime("b"));
	}

	@Test
	@DisplayName("memory is bounded: the oldest key is forgotten first, the newest are always kept")
	void boundedMemory() {
		RecentOccurrences recent = new RecentOccurrences(3);

		for (String key : new String[] {"a", "b", "c", "d"})
			assertTrue(recent.firstTime(key));

		assertEquals(3, recent.size());
		assertFalse(recent.firstTime("d"), "the newest is remembered");
		assertFalse(recent.firstTime("c"));
		assertFalse(recent.firstTime("b"));
		assertTrue(recent.firstTime("a"), "the oldest fell off, so it reads as new again");
	}

	@Test
	@DisplayName("a capacity below 1 is rejected")
	void capacityMustBePositive() {
		assertThrows(IllegalArgumentException.class, () -> new RecentOccurrences(0));
	}
}
