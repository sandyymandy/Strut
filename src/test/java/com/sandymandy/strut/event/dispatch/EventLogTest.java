package com.sandymandy.strut.event.dispatch;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The event log that replaces a mod's hand-rolled event queue and edge flags
 */
class EventLogTest {
	private static final Identifier THRUST = Identifier.of("mymod", "strike");
	private static final Identifier MARKER = Identifier.of("strut", "marker");

	@Test
	@DisplayName("polled once per tick, an occurrence is visible for exactly one tick - an edge, not a level")
	void firedLastTickIsAnEdge() {
		EventLog log = new EventLog();

		log.record(1, THRUST, null, 100);

		assertTrue(log.firedLastTick(1, THRUST, 100), "readable by code running after the sweep, in the same tick");
		assertTrue(log.firedLastTick(1, THRUST, 101), "and by the next tick's entity tick, which runs before the next sweep");
		assertFalse(log.firedLastTick(1, THRUST, 102), "and then it is gone, so nobody needs a 'did I already see it' flag");
	}

	@Test
	@DisplayName("entities and types are kept apart")
	void keyedByEntityAndType() {
		EventLog log = new EventLog();

		log.record(1, THRUST, null, 100);

		assertFalse(log.firedLastTick(2, THRUST, 100), "another entity");
		assertFalse(log.firedLastTick(1, Identifier.of("mymod", "other"), 100), "another type");
	}

	@Test
	@DisplayName("markers are found by name, case-insensitively, without matching a longer name")
	void markersByName() {
		EventLog log = new EventLog();

		log.record(1, MARKER, "swap_end", 100);

		assertTrue(log.firedMarkerLastTick(1, "swap_end", 100));
		assertTrue(log.firedMarkerLastTick(1, "SWAP_END", 100));
		assertFalse(log.firedMarkerLastTick(1, "swap", 100), "swap must not match swap_end - the bug a consumer mod's substring check had");
		assertTrue(log.firedSince(1, MARKER, 100), "and it is also visible as an event of the marker type");
	}

	@Test
	@DisplayName("firedSince covers any span, not just the last tick")
	void firedSince() {
		EventLog log = new EventLog();

		log.record(1, THRUST, null, 100);

		assertTrue(log.firedSince(1, THRUST, 50));
		assertTrue(log.firedSince(1, THRUST, 100));
		assertFalse(log.firedSince(1, THRUST, 101));
	}

	@Test
	@DisplayName("an entity remembers only its most recent occurrences")
	void boundedMemory() {
		EventLog log = new EventLog();

		log.record(1, THRUST, null, 1);

		for (int i = 0; i < EventLog.CAPACITY; i++)
			log.record(1, Identifier.of("mymod", "filler"), null, 2 + i);

		assertFalse(log.firedSince(1, THRUST, 0), "the oldest fell off once the log filled");
		assertTrue(log.firedSince(1, Identifier.of("mymod", "filler"), 0));
	}

	@Test
	@DisplayName("forgetting an entity drops its memory")
	void forget() {
		EventLog log = new EventLog();

		log.record(1, THRUST, null, 100);
		log.forget(1);

		assertFalse(log.firedSince(1, THRUST, 0));
	}
}
