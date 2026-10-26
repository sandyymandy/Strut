package com.sandymandy.strut.event.cursor;

import com.sandymandy.strut.animation.authority.AnimationPlayback;
import com.sandymandy.strut.animation.authority.ServerAnimationState;
import com.sandymandy.strut.event.cursor.EventOccurrence.Kind;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The exactly-once cursor, on its own
 * <p>
 * Every case here is the same claim in a different shape: what fires is decided by the <i>span</i> of
 * time crossed, never by when a sweep happened to look.
 */
class EventCursorTest {
	private static ScheduledEvent event(int index, double tick) {
		return new ScheduledEvent(index, tick, false);
	}

	private static EventSchedule clip(double length, LoopMode mode, ScheduledEvent... events) {
		return new EventSchedule(length, mode, List.of(events));
	}

	/** Compact readable form: {@code E1@0}, {@code LOOP2}, {@code END} - loop suffix only past loop 0 */
	private static List<String> names(EventCursor.Result result) {
		List<String> names = new ArrayList<>();

		for (EventOccurrence o : result.occurrences()) {
			names.add(switch (o.kind()) {
				case EVENT -> "E" + o.eventIndex() + (o.loop() != 0 ? "/" + o.loop() : "");
				case LOOP -> "LOOP" + o.loop();
				case END -> "END";
			});
		}

		return names;
	}

	@Test
	@DisplayName("an event fires once as time passes it, never again")
	void firesOnceAsTimePassesIt() {
		EventSchedule clip = clip(40, LoopMode.PLAY_ONCE, event(0, 10));
		EventCursor cursor = EventCursor.startingAt(0);

		assertEquals(List.of(), names(cursor.advance(clip, 5)));
		assertEquals(List.of("E0"), names(cursor.advance(clip, 12)));
		assertEquals(List.of(), names(cursor.advance(clip, 20)), "already fired");
	}

	@Test
	@DisplayName("an event exactly on the start fires on the first call, and only then")
	void closedStartFiresAnEventAtZero() {
		EventSchedule clip = clip(40, LoopMode.PLAY_ONCE, event(0, 0));
		EventCursor cursor = EventCursor.startingAt(0);

		assertEquals(List.of("E0"), names(cursor.advance(clip, 0)), "the start tick itself counts");
		assertEquals(List.of(), names(cursor.advance(clip, 3)));
	}

	@Test
	@DisplayName("an event on a boundary between two calls fires in exactly one of them")
	void boundaryBelongsToOneSide() {
		EventSchedule clip = clip(40, LoopMode.PLAY_ONCE, event(0, 10));
		EventCursor cursor = EventCursor.startingAt(0);

		assertEquals(List.of("E0"), names(cursor.advance(clip, 10)), "hi bound is inclusive");
		assertEquals(List.of(), names(cursor.advance(clip, 15)), "lo bound is exclusive");
	}

	@Test
	@DisplayName("splitting a stretch of time into any number of calls fires the same occurrences")
	void splittingTimeDoesNotChangeWhatFires() {
		EventSchedule clip = clip(10, LoopMode.LOOP, event(0, 0), event(1, 4), event(2, 10));

		List<String> whole = names(EventCursor.startingAt(0).advance(clip, 37));
		List<String> pieces = new ArrayList<>();
		EventCursor cursor = EventCursor.startingAt(0);

		for (double now : new double[] {3, 3, 10, 10.5, 19.999, 20, 31, 37})
			pieces.addAll(names(cursor.advance(clip, now)));

		assertEquals(whole, pieces);
	}

	@Test
	@DisplayName("a lag spike spanning several loops fires each loop's events once, in order")
	void lagSpansManyLoops() {
		EventSchedule clip = clip(10, LoopMode.LOOP, event(0, 2), event(1, 8));
		EventCursor cursor = EventCursor.startingAt(1);

		assertEquals(List.of("E0", "E1", "LOOP1", "E0/1", "E1/1", "LOOP2", "E0/2"),
				names(cursor.advance(clip, 23)));
	}

	@Test
	@DisplayName("a looping clip reports each wrap, and never reports loop 0")
	void loopMarkersAtEveryWrap() {
		EventSchedule clip = clip(10, LoopMode.LOOP);
		EventCursor cursor = EventCursor.startingAt(0);

		assertEquals(List.of("LOOP1"), names(cursor.advance(clip, 12)));
		assertEquals(List.of(), names(cursor.advance(clip, 15)));
		assertEquals(List.of("LOOP2", "LOOP3"), names(cursor.advance(clip, 31)));
	}

	@Test
	@DisplayName("at a loop boundary: the closing event, then the wrap, then the next loop's time-0 event")
	void boundaryOrdering() {
		EventSchedule clip = clip(10, LoopMode.LOOP, event(0, 0), event(1, 10));
		EventCursor cursor = EventCursor.resumingAt(5);

		assertEquals(List.of("E1", "LOOP1", "E0/1"), names(cursor.advance(clip, 10)));
	}

	@Test
	@DisplayName("a fresh loop starting at 0 does not fire an end-of-lap event as a lap that never happened")
	void freshStartDoesNotFireThePreviousLapsEnd() {
		EventSchedule clip = clip(20, LoopMode.LOOP, event(0, 20), event(1, 5));

		// The event at time == length shares position 0 with the start. It closes lap 0 at position 20; it is
		// not an event of a lap -1 that the clip never played
		assertEquals(List.of("E1", "E0", "LOOP1"), names(EventCursor.startingAt(0).advance(clip, 21)),
				"event 1 at 5, then event 0 closing lap 0 at 20, then the wrap - and no E0/-1 at the very start");
	}

	@Test
	@DisplayName("the same holds when a fresh loop starts a whole number of laps in")
	void freshStartAWholeLapIn() {
		EventSchedule clip = clip(20, LoopMode.LOOP, event(0, 20));

		// Position 40 is the end of lap 1 (and the start of lap 2). Starting there owes nothing to lap 1
		assertEquals(List.of("E0/2", "LOOP3"), names(EventCursor.startingAt(40).advance(clip, 60)),
				"lap 2 closes at 60 and the clip wraps into lap 3 - but there is no LOOP2: the clip began inside lap 2");
	}

	@Test
	@DisplayName("a resumed cursor still fires an end-of-lap event when it crosses the boundary - only a fresh start skips the previous lap")
	void resumedCursorStillCrossesLapEnds() {
		EventSchedule clip = clip(20, LoopMode.LOOP, event(0, 20));

		assertEquals(List.of("E0", "LOOP1"), names(EventCursor.resumingAt(15).advance(clip, 25)));
	}

	@Test
	@DisplayName("a play-once clip reports its end once, after the events at the end")
	void playOnceReportsEndOnce() {
		EventSchedule clip = clip(20, LoopMode.PLAY_ONCE, event(0, 20), event(1, 5));
		EventCursor cursor = EventCursor.startingAt(0);

		assertEquals(List.of("E1", "E0", "END"), names(cursor.advance(clip, 25)));
		assertEquals(List.of(), names(cursor.advance(clip, 500)), "held past the end, nothing more");
	}

	@Test
	@DisplayName("hold-on-last-frame behaves like play-once for events")
	void holdBehavesLikePlayOnce() {
		EventSchedule clip = clip(20, LoopMode.HOLD_ON_LAST_FRAME, event(0, 19));
		EventCursor cursor = EventCursor.startingAt(0);

		assertEquals(List.of("E0", "END"), names(cursor.advance(clip, 100)));
	}

	@Test
	@DisplayName("events at the same time fire in authored order")
	void sameTimeFiresInIndexOrder() {
		EventSchedule clip = clip(10, LoopMode.PLAY_ONCE, event(2, 5), event(0, 5), event(1, 5));

		assertEquals(List.of("E0", "E1", "E2"), names(EventCursor.startingAt(0).advance(clip, 6)));
	}

	@Test
	@DisplayName("an event authored outside the clip is clamped onto it")
	void outOfRangeEventsAreClamped() {
		EventSchedule clip = clip(10, LoopMode.PLAY_ONCE, event(0, -5), event(1, 99));

		assertEquals(0, clip.events().get(0).tick());
		assertEquals(10, clip.events().get(1).tick());
	}

	@Test
	@DisplayName("running backwards fires only events that opt in, in descending order")
	void reverseFiresOnlyOptedInEvents() {
		EventSchedule clip = clip(40, LoopMode.PLAY_ONCE,
				new ScheduledEvent(0, 10, false), new ScheduledEvent(1, 20, true), new ScheduledEvent(2, 30, true));
		EventCursor cursor = EventCursor.resumingAt(35);

		assertEquals(List.of("E2", "E1"), names(cursor.advance(clip, 5)));
	}

	@Test
	@DisplayName("running backwards never reports a loop wrap or an end")
	void reverseHasNoBuiltIns() {
		EventSchedule looping = clip(10, LoopMode.LOOP, new ScheduledEvent(0, 5, true));
		EventSchedule once = clip(10, LoopMode.PLAY_ONCE, new ScheduledEvent(0, 5, true));

		assertEquals(List.of("E0/1", "E0"), names(EventCursor.resumingAt(16).advance(looping, 2)));
		assertEquals(List.of("E0"), names(EventCursor.resumingAt(9).advance(once, 2)));
	}

	@Test
	@DisplayName("a reversed clip with no opted-in events costs nothing however far it runs")
	void reverseWithoutOptInIsFree() {
		EventSchedule clip = clip(0.5, LoopMode.LOOP, event(0, 0.1));

		assertTrue(EventCursor.resumingAt(1_000_000_000).advance(clip, 0).isEmpty());
	}

	@Test
	@DisplayName("a runaway span is capped, reports truncation, and still moves the cursor")
	void capTruncatesButAdvances() {
		EventSchedule clip = clip(1, LoopMode.LOOP, event(0, 0.5));
		EventCursor cursor = EventCursor.startingAt(0);

		EventCursor.Result result = cursor.advance(clip, 1_000_000, 256);

		assertEquals(256, result.occurrences().size());
		assertTrue(result.truncated());
		assertEquals(1_000_000, cursor.position(), "the cursor still lands on now");
		assertTrue(cursor.advance(clip, 1_000_000).isEmpty(), "and the dropped ones are not owed later");
	}

	@Test
	@DisplayName("a zero-length or empty clip fires nothing and does not loop forever")
	void inertSchedulesAreSafe() {
		assertTrue(EventCursor.startingAt(0).advance(clip(0, LoopMode.LOOP, event(0, 0)), 1000).isEmpty());
		assertTrue(EventCursor.startingAt(0).advance(clip(10, LoopMode.PLAY_ONCE), 5).isEmpty());
	}

	@Test
	@DisplayName("a non-finite position fires nothing rather than corrupting the cursor")
	void nanIsIgnored() {
		EventSchedule clip = clip(10, LoopMode.LOOP, event(0, 1));

		assertTrue(EventCursor.startingAt(0).advance(clip, Double.NaN).isEmpty());
	}

	@Test
	@DisplayName("resuming does not replay the instant it resumes on, carryOver keeps the position")
	void resumingSkipsItsOwnInstant() {
		EventSchedule clip = clip(40, LoopMode.PLAY_ONCE, event(0, 10));
		EventCursor cursor = EventCursor.startingAt(0);

		cursor.advance(clip, 10);

		EventCursor carried = cursor.carryOver();

		assertEquals(10, carried.position());
		assertEquals(List.of(), names(carried.advance(clip, 20)), "the event on the carried instant already fired");
		assertEquals(List.of(), names(EventCursor.resumingAt(10).advance(clip, 20)));
	}

	@Test
	@DisplayName("a setRate re-base neither repeats nor skips events, when the cursor is carried over")
	void rateChangeRebaseIsSeamless() {
		EventSchedule clip = clip(100, LoopMode.LOOP, event(0, 30), event(1, 50), event(2, 70));
		ServerAnimationState state = new ServerAnimationState();
		state.play("base", "clip", 1000, 0, 1, LoopMode.LOOP);

		AnimationPlayback before = state.get("base");
		EventCursor cursor = EventCursor.startingAt(before.elapsedTicks(1000, 0));
		List<String> fired = new ArrayList<>(names(cursor.advance(clip, before.elapsedTicks(1030, 0))));

		// Doubling the rate exactly on top of an event: the new playback is a new sequence, but starts
		// at the same elapsed position, so its cursor is the old one carried over
		state.setRate("base", 2, 1030);

		AnimationPlayback after = state.get("base");

		assertEquals(before.elapsedTicks(1030, 0), after.elapsedTicks(1030, 0), 1e-4, "re-base is continuous");
		assertTrue(after.sequence() > before.sequence());

		EventCursor rebased = cursor.carryOver();

		for (long tick = 1031; tick <= 1080; tick++)
			fired.addAll(names(rebased.advance(clip, after.elapsedTicks(tick, 0))));

		// 0..30 at rate 1 then 30..130 at rate 2: E0@30 once, E1@50, E2@70, then wrap and E0 of loop 1
		assertEquals(List.of("E0", "E1", "E2", "LOOP1", "E0/1"), fired);
	}

	@Test
	@DisplayName("a mid-clip start offset only fires what is still ahead")
	void startOffsetSkipsWhatIsBehind() {
		EventSchedule clip = clip(40, LoopMode.PLAY_ONCE, event(0, 5), event(1, 25));
		AnimationPlayback playback = new AnimationPlayback("base", "clip", 0, 20, 1, LoopMode.PLAY_ONCE, 1);
		EventCursor cursor = EventCursor.startingAt(playback.elapsedTicks(0, 0));

		assertEquals(List.of("E1"), names(cursor.advance(clip, playback.elapsedTicks(10, 0))));
	}

	@Test
	@DisplayName("built-in kinds carry no authored event index")
	void builtInsHaveNoEventIndex() {
		EventOccurrence loop = EventCursor.startingAt(0).advance(clip(10, LoopMode.LOOP), 11).occurrences().get(0);

		assertEquals(Kind.LOOP, loop.kind());
		assertEquals(EventOccurrence.NO_EVENT, loop.eventIndex());
		assertEquals(10, loop.tick());
	}
}
