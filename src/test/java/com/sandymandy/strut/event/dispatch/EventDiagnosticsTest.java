package com.sandymandy.strut.event.dispatch;

import com.sandymandy.strut.animation.authority.ServerAnimationState;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.server.EventContext;
import com.sandymandy.strut.event.type.ParsedEvent;
import com.sandymandy.strut.event.type.ReversePolicy;
import com.sandymandy.strut.event.StrutEvents;
import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import com.sandymandy.strut.event.timeline.ClipTimeline;
import com.sandymandy.strut.event.timeline.TimelineEvent;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The things that let a person see the event system work - the counters behind the F9
 * line and the per-entity trace behind {@code /strut events trace}
 */
class EventDiagnosticsTest {
	private static final UUID ENTITY = new UUID(9, 9);

	@AfterEach
	void stopTracing() {
		StrutEvents.trace().stop(1);
		StrutEvents.trace().stop(2);
	}

	private static ClipTimeline oneMarker() {
		return new ClipTimeline(100, LoopMode.PLAY_ONCE, List.of(new TimelineEvent(0, 5, new ParsedEvent<>(StrutBuiltinEvents.MARKER, "swap"),
				"swap", EventAuthority.SERVER, ReversePolicy.SKIP)), 0L);
	}

	private static void play(ServerEventDispatcher dispatcher, int entityId, long gameTime) {
		ServerAnimationState state = new ServerAnimationState();

		state.play("base", "c", 0, 0, 1, LoopMode.PLAY_ONCE);
		dispatcher.sweep(new ServerEventDispatcher.Subject(entityId, ENTITY, null, null), state, clip -> Optional.of(oneMarker()), gameTime);
	}

	@Test
	@DisplayName("each server delivery is counted")
	void serverDeliveriesAreCounted() {
		ServerEventDispatcher dispatcher = new ServerEventDispatcher(new ServerEventListeners(), new EventLog());
		long before = EventStats.serverDelivered();

		play(dispatcher, 1, 10);

		assertEquals(before + 1, EventStats.serverDelivered());
	}

	@Test
	@DisplayName("a runaway sweep is counted as capped")
	void cappedSweepsAreCounted() {
		ServerEventDispatcher dispatcher = new ServerEventDispatcher(new ServerEventListeners(), new EventLog());
		ServerAnimationState state = new ServerAnimationState();
		ClipTimeline looping = new ClipTimeline(1, LoopMode.LOOP, List.of(new TimelineEvent(0, 0.5, new ParsedEvent<>(StrutBuiltinEvents.MARKER, "t"),
				"t", EventAuthority.SERVER, ReversePolicy.SKIP)), 0L);
		long before = EventStats.truncatedSweeps();

		state.play("base", "c", 0, 0, 100_000f, LoopMode.LOOP);
		dispatcher.sweep(new ServerEventDispatcher.Subject(1, ENTITY, null, null), state, clip -> Optional.of(looping), 0);
		dispatcher.sweep(new ServerEventDispatcher.Subject(1, ENTITY, null, null), state, clip -> Optional.of(looping), 1);

		assertEquals(before + 1, EventStats.truncatedSweeps());
	}

	@Test
	@DisplayName("a traced entity gets one line per delivered event, and an untraced one gets none")
	void traceReportsOnlyTracedEntities() {
		List<String> lines = new ArrayList<>();
		ServerEventDispatcher dispatcher = new ServerEventDispatcher(new ServerEventListeners(), new EventLog());

		StrutEvents.trace().start(1, lines::add);

		play(dispatcher, 1, 10);
		play(dispatcher, 2, 10);

		assertEquals(1, lines.size(), lines.toString());
	}

	@Test
	@DisplayName("a trace line says which entity, controller, playback, event, type and payload, and when")
	void traceLineIsInformative() {
		List<String> lines = new ArrayList<>();
		ServerEventDispatcher dispatcher = new ServerEventDispatcher(new ServerEventListeners(), new EventLog());

		StrutEvents.trace().start(1, lines::add);
		play(dispatcher, 1, 10);

		String line = lines.get(0);

		for (String expected : new String[] {"#1", "base", "seq1", "loop0", "ev0", "strut:marker", "swap", "@10", "SERVER"})
			assertTrue(line.contains(expected), "missing \"" + expected + "\" in: " + line);
	}

	@Test
	@DisplayName("stopping a trace stops its lines")
	void stoppingATraceStopsIt() {
		List<String> lines = new ArrayList<>();
		ServerEventDispatcher dispatcher = new ServerEventDispatcher(new ServerEventListeners(), new EventLog());

		StrutEvents.trace().start(1, lines::add);
		StrutEvents.trace().stop(1);
		play(dispatcher, 1, 10);

		assertTrue(lines.isEmpty());
		assertFalse(StrutEvents.trace().isTracing(1));
	}

	@Test
	@DisplayName("trace bookkeeping: start replaces, count reflects, report to nobody is harmless")
	void traceBookkeeping() {
		EventTrace trace = new EventTrace();

		assertEquals(0, trace.tracedCount());
		trace.report(5, "nobody listening");

		List<String> first = new ArrayList<>();
		List<String> second = new ArrayList<>();

		trace.start(5, first::add);
		trace.start(5, second::add);
		trace.report(5, "x");

		assertEquals(1, trace.tracedCount());
		assertTrue(first.isEmpty());
		assertEquals(List.of("x"), second);
	}

	@Test
	@DisplayName("describe reads the authored time as well as the delivery time")
	void describeShowsAuthoredTime() {
		EventContext context = new EventContext(null, 7, ENTITY, "attack", 3, 2, 4, 105.5, 110,
				new ParsedEvent<>(StrutBuiltinEvents.MARKER, "hit"), EventAuthority.BOTH, new ServerAnimationState());

		String line = ServerEventDispatcher.describe(context);

		assertTrue(line.contains("#7") && line.contains("attack") && line.contains("seq3") && line.contains("loop2") && line.contains("ev4"), line);
		assertTrue(line.contains("@110") && line.contains("105.50") && line.contains("BOTH"), line);
	}
}
