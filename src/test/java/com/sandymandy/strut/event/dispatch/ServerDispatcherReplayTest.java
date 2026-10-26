package com.sandymandy.strut.event.dispatch;

import com.sandymandy.strut.animation.authority.ServerAnimationState;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.ParsedEvent;
import com.sandymandy.strut.event.type.ReversePolicy;
import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import com.sandymandy.strut.event.timeline.ClipTimeline;
import com.sandymandy.strut.event.timeline.TimelineEvent;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import java.util.function.BiConsumer;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The server dispatcher replays every change since its last sweep, so a server that
 * stalled fires exactly what one that swept every tick would - the same events, under the same playback, with the
 * same exact times
 * <p>
 * Each test runs one script twice, once sweeping every tick and once sweeping only at the end, and compares
 * everything about what fired. These are minimal reproductions of what the delivery simulator's soak found
 */
class ServerDispatcherReplayTest {
	private static final UUID ENTITY = new UUID(8, 8);

	/** What one delivery looked like, including which playback it belonged to and when it was authored */
	private record Seen(long sequence, long loop, int index, String marker, double exact) {}

	private static TimelineEvent marker(int index, double tick, String name) {
		return new TimelineEvent(index, tick, new ParsedEvent<>(StrutBuiltinEvents.MARKER, name), name, EventAuthority.SERVER, ReversePolicy.SKIP);
	}

	private static ClipTimeline clip(double length, LoopMode mode, TimelineEvent... events) {
		return new ClipTimeline(length, mode, List.of(events), 0L);
	}

	/**
	 * Runs {@code script} against a fresh state, sweeping at every tick in {@code sweepAt}, and returns what fired
	 *
	 * @param actions Called with the state at each tick from 0 to {@code end}, before that tick's sweep
	 */
	private static List<Seen> run(ClipTimeline timeline, long end, LongPredicate sweepAt, BiConsumer<ServerAnimationState, Long> actions) {
		ServerAnimationState state = new ServerAnimationState();
		ServerEventListeners listeners = new ServerEventListeners();
		ServerEventDispatcher dispatcher = new ServerEventDispatcher(listeners, new EventLog());
		List<Seen> seen = new ArrayList<>();

		listeners.add(StrutBuiltinEvents.MARKER.id(), ctx -> seen.add(new Seen(ctx.sequence(), ctx.loop(), ctx.eventIndex(), (String)ctx.payload(), ctx.exactGameTime())));

		for (long tick = 0; tick <= end; tick++) {
			actions.accept(state, tick);

			if (sweepAt.test(tick))
				dispatcher.sweep(new ServerEventDispatcher.Subject(1, ENTITY, null, null), state, name -> Optional.of(timeline), tick);
		}

		return seen;
	}

	private static void assertLagIndependent(ClipTimeline timeline, long end, BiConsumer<ServerAnimationState, Long> actions) {
		List<Seen> everyTick = run(timeline, end, tick -> true, actions);
		List<Seen> onlyAtTheEnd = run(timeline, end, tick -> tick == end, actions);

		assertFalse(everyTick.isEmpty(), "the scenario must fire something or it proves nothing");
		assertEquals(everyTick, onlyAtTheEnd, "sweeping once at the end must fire exactly what sweeping every tick did");
	}

	@Test
	@DisplayName("a play then a rate change, both swept in one go, fire the first playback's events under the first playback")
	void stallAcrossPlayAndRebase() {
		ClipTimeline clip = clip(1000, LoopMode.PLAY_ONCE, marker(0, 10, "early"), marker(1, 60, "late"));

		assertLagIndependent(clip, 200, (state, tick) -> {
			if (tick == 100)
				state.play("base", "c", 100, 0, 1f, LoopMode.PLAY_ONCE);

			if (tick == 130)
				state.setRate("base", 2f, tick);
		});
	}

	@Test
	@DisplayName("an event authored under an earlier speed keeps the exact time that speed gives it, however late the sweep")
	void exactTimesUseTheirOwnPlaybacksSpeed() {
		ClipTimeline clip = clip(1000, LoopMode.PLAY_ONCE, marker(0, 20, "under_slow"));
		List<Seen> late = run(clip, 200, tick -> tick == 200, (state, tick) -> {
			if (tick == 100)
				state.play("base", "c", 100, 0, 0.5f, LoopMode.PLAY_ONCE);

			if (tick == 150)
				state.setRate("base", 4f, tick);
		});

		// Rate 0.5 from tick 100: clip tick 20 is reached at game tick 140 - before the change at 150. Computed against
		// the later, faster playback it would read 100 + (20 - 25) / 4 + 50: nonsense
		assertEquals(1, late.size());
		assertEquals(140.0, late.get(0).exact(), 1e-9);
		assertEquals(1, late.get(0).sequence(), "and it belongs to the first playback");
	}

	@Test
	@DisplayName("several rate changes between two sweeps fire each epoch's events under that epoch")
	void severalRebasesBetweenSweeps() {
		ClipTimeline clip = clip(1000, LoopMode.PLAY_ONCE, marker(0, 8, "a"), marker(1, 30, "b"), marker(2, 55, "c"), marker(3, 120, "d"));

		assertLagIndependent(clip, 260, (state, tick) -> {
			if (tick == 100)
				state.play("base", "c", 100, 0, 1f, LoopMode.PLAY_ONCE);

			if (tick == 120)
				state.setRate("base", 3f, tick);

			if (tick == 130)
				state.setRate("base", 0.5f, tick);

			if (tick == 150)
				state.setRate("base", 2f, tick);
		});
	}

	@Test
	@DisplayName("a rate change in the very tick a clip starts does not lose an event on the start instant")
	void rebaseInTheStartTickKeepsTheStartEvent() {
		ClipTimeline clip = clip(1000, LoopMode.PLAY_ONCE, marker(0, 0, "on_start"), marker(1, 40, "later"));

		List<Seen> fired = run(clip, 200, tick -> true, (state, tick) -> {
			if (tick == 100) {
				state.play("base", "c", 100, 0, 1f, LoopMode.PLAY_ONCE);
				state.setRate("base", 2f, 100);
			}
		});

		assertEquals(List.of("on_start", "later"), fired.stream().map(Seen::marker).toList(), "the start-instant event survives being rate-changed at once");
	}

	@Test
	@DisplayName("a fire-crossed seek that jumps backwards behaves the same whether the sweep was per tick or once, late")
	void backwardsFireSeekIsLagIndependent() {
		ClipTimeline clip = clip(1000, LoopMode.PLAY_ONCE, marker(0, 10, "a"), marker(1, 26, "just_after_landing"), marker(2, 30, "b"), marker(3, 150, "far"));

		assertLagIndependent(clip, 260, (state, tick) -> {
			if (tick == 100)
				state.play("base", "c", 100, 0, 1f, LoopMode.PLAY_ONCE);

			// At 190 the clip is at 90; jump back to 25. The events just past 25 must still fire on the way up
			if (tick == 190)
				state.seek("base", 25, tick, true);
		});
	}

	@Test
	@DisplayName("a silent seek is lag independent too")
	void silentSeekIsLagIndependent() {
		ClipTimeline clip = clip(1000, LoopMode.PLAY_ONCE, marker(0, 10, "a"), marker(1, 60, "skipped"), marker(2, 130, "b"), marker(3, 150, "c"));

		assertLagIndependent(clip, 320, (state, tick) -> {
			if (tick == 100)
				state.play("base", "c", 100, 0, 1f, LoopMode.PLAY_ONCE);

			if (tick == 130)
				state.seek("base", 120, tick, false);
		});
	}

	@Test
	@DisplayName("a timed stop under a stall still fires everything up to the stop, and nothing after")
	void timedStopUnderStall() {
		ClipTimeline clip = clip(1000, LoopMode.PLAY_ONCE, marker(0, 20, "before"), marker(1, 70, "after"));

		assertLagIndependent(clip, 260, (state, tick) -> {
			if (tick == 100)
				state.play("base", "c", 100, 0, 1f, LoopMode.PLAY_ONCE);

			if (tick == 150)
				state.stop("base", tick);
		});
	}

	@Test
	@DisplayName("a replacement played and swapped again before any sweep fires the middle clip's events up to the swap")
	void intermediateClipFiresUnderStall() {
		ClipTimeline clip = clip(1000, LoopMode.LOOP, marker(0, 6, "tick"));

		assertLagIndependent(clip, 260, (state, tick) -> {
			if (tick == 100)
				state.play("base", "c", 100, 0, 1f, LoopMode.LOOP);

			if (tick == 120)
				state.play("base", "c", tick, 0, 1f, LoopMode.LOOP);

			if (tick == 140)
				state.play("base", "c", tick, 0, 1f, LoopMode.LOOP);
		});
	}

	@Test
	@DisplayName("a playback replaced in the very tick it began never ran, so it fires nothing - stalled or not")
	void zeroDurationPlaybackFiresNothing() {
		ClipTimeline clip = clip(1000, LoopMode.PLAY_ONCE, marker(0, 0, "never_ran"));
		Consumer<ServerAnimationState> script = state -> {
			state.play("base", "first", 100, 0, 1f, LoopMode.PLAY_ONCE);
			state.play("base", "second", 100, 0, 1f, LoopMode.PLAY_ONCE);
		};
		List<Seen> perTick = run(clip, 110, tick -> true, (state, tick) -> {
			if (tick == 100)
				script.accept(state);
		});
		List<Seen> late = run(clip, 110, tick -> tick == 110, (state, tick) -> {
			if (tick == 100)
				script.accept(state);
		});

		assertEquals(perTick, late);
		assertEquals(List.of(2L), perTick.stream().map(Seen::sequence).distinct().toList(), "only the surviving playback's start event fires");
	}
}
