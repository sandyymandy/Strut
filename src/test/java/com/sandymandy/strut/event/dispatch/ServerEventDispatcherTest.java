package com.sandymandy.strut.event.dispatch;

import com.sandymandy.strut.animation.authority.AnimationPlayback;
import com.sandymandy.strut.animation.authority.ServerAnimationState;
import com.sandymandy.strut.event.cursor.EventCursor;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.server.EventContext;
import com.sandymandy.strut.event.type.ParsedEvent;
import com.sandymandy.strut.event.type.ReversePolicy;
import com.sandymandy.strut.event.type.StrutEventType;
import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import com.sandymandy.strut.event.timeline.ClipTimeline;
import com.sandymandy.strut.event.timeline.TimelineEvent;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import java.util.HashMap;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The server dispatcher, driven through {@link ServerAnimationState} with a fake
 * clock - no world, no entity
 * <p>
 * These are the failure modes one at a time (lag, a stop or replacement partway through a
 * tick, a rate change, a seek, a handler that changes the clip it is called for), each asserting the same
 * thing: every event fires exactly once, in order, and none is skipped
 */
class ServerEventDispatcherTest {
	private static final UUID ENTITY = new UUID(1, 2);
	private static final String CONTROLLER = "base";

	private ServerAnimationState state;
	private ServerEventListeners listeners;
	private EventLog log;
	private ServerEventDispatcher dispatcher;
	private Map<String, ClipTimeline> clips;
	private List<String> fired;
	private AtomicInteger lookups;

	@BeforeEach
	void setUp() {
		this.state = new ServerAnimationState();
		this.listeners = new ServerEventListeners();
		this.log = new EventLog();
		this.dispatcher = new ServerEventDispatcher(this.listeners, this.log);
		this.clips = new HashMap<>();
		this.fired = new ArrayList<>();
		this.lookups = new AtomicInteger();
	}

	private static TimelineEvent marker(int index, double tick, String name, EventAuthority authority) {
		return new TimelineEvent(index, tick, new ParsedEvent<>(StrutBuiltinEvents.MARKER, name), name, authority, ReversePolicy.SKIP);
	}

	private static TimelineEvent marker(int index, double tick, String name) {
		return marker(index, tick, name, EventAuthority.SERVER);
	}

	private static ClipTimeline clip(double length, LoopMode mode, TimelineEvent... events) {
		return new ClipTimeline(length, mode, List.of(events), 0L);
	}

	/** Records every named marker as {@code name/loop} */
	private void listenTo(String... names) {
		for (String name : names)
			this.listeners.addMarker(name, ctx -> this.fired.add(name + "/" + ctx.loop()));
	}

	private void sweep(long gameTime) {
		sweep(1, gameTime);
	}

	private void sweep(int entityId, long gameTime) {
		this.dispatcher.sweep(new ServerEventDispatcher.Subject(entityId, ENTITY, null, null), this.state,
				clip -> {
					this.lookups.incrementAndGet();

					return Optional.ofNullable(this.clips.get(clip));
				}, gameTime);
	}

	private void play(String clip, long startTick, float rate, LoopMode loop) {
		this.state.play(CONTROLLER, clip, startTick, 0, rate, loop);
	}

	@Test
	@DisplayName("an event fires once, when time passes it, and never again")
	void firesOnce() {
		this.clips.put("c", clip(40, LoopMode.PLAY_ONCE, marker(0, 10, "hit")));
		listenTo("hit");
		play("c", 100, 1, LoopMode.PLAY_ONCE);

		sweep(100);
		sweep(105);
		assertEquals(List.of(), this.fired);

		sweep(111);
		sweep(120);
		sweep(130);
		assertEquals(List.of("hit/0"), this.fired);
	}

	@Test
	@DisplayName("an event at the very start fires on the first sweep, once")
	void startInstantEventFires() {
		this.clips.put("c", clip(40, LoopMode.PLAY_ONCE, marker(0, 0, "go")));
		listenTo("go");
		play("c", 100, 1, LoopMode.PLAY_ONCE);

		sweep(100);
		sweep(101);
		assertEquals(List.of("go/0"), this.fired);
	}

	@Test
	@DisplayName("sweeping twice at the same game time never fires anything twice")
	void sweepingTwiceIsIdempotent() {
		this.clips.put("c", clip(40, LoopMode.PLAY_ONCE, marker(0, 5, "a")));
		listenTo("a");
		play("c", 0, 1, LoopMode.PLAY_ONCE);

		sweep(10);
		sweep(10);
		assertEquals(List.of("a/0"), this.fired);
	}

	@Test
	@DisplayName("one long lagging sweep fires exactly what tick-by-tick sweeping would have, in order")
	void lagFiresTheSameAsTickByTick() {
		ClipTimeline timeline = clip(10, LoopMode.LOOP, marker(0, 2, "a"), marker(1, 8, "b"));

		this.clips.put("c", timeline);
		listenTo("a", "b");
		play("c", 0, 1, LoopMode.LOOP);

		for (long t = 0; t <= 47; t++)
			sweep(t);

		List<String> tickByTick = List.copyOf(this.fired);

		this.fired.clear();
		this.dispatcher.forget(1);
		this.state = new ServerAnimationState();
		play("c", 0, 1, LoopMode.LOOP);

		sweep(0);
		sweep(47);

		assertEquals(tickByTick, this.fired);
		assertEquals(List.of("a/0", "b/0", "a/1", "b/1", "a/2", "b/2", "a/3", "b/3", "a/4"), this.fired);
	}

	@Test
	@DisplayName("a looping clip raises the engine's loop event at every wrap")
	void loopEventsAreDelivered() {
		List<String> seen = new ArrayList<>();

		this.listeners.add(StrutBuiltinEvents.LOOP.id(), ctx -> seen.add("loop" + ctx.payload(Long.class)));
		this.clips.put("loops", clip(10, LoopMode.LOOP));
		play("loops", 0, 1, LoopMode.LOOP);

		sweep(0);
		sweep(25);

		assertEquals(List.of("loop1", "loop2"), seen);
	}

	@Test
	@DisplayName("a play-once clip raises the engine's end event once, however long the clip is then held")
	void endEventIsDeliveredOnce() {
		List<String> seen = new ArrayList<>();

		this.listeners.add(StrutBuiltinEvents.END.id(), ctx -> seen.add("end"));
		this.clips.put("once", clip(10, LoopMode.PLAY_ONCE));
		play("once", 100, 1, LoopMode.PLAY_ONCE);

		sweep(100);
		sweep(115);
		sweep(130);

		assertEquals(List.of("end"), seen);
	}

	@Test
	@DisplayName("replacing a looping clip still fires the loops it completed before the replacement")
	void replacementFlushesCompletedLoops() {
		List<String> seen = new ArrayList<>();

		this.listeners.add(StrutBuiltinEvents.LOOP.id(), ctx -> seen.add("loop" + ctx.payload(Long.class)));
		this.clips.put("loops", clip(10, LoopMode.LOOP));
		this.clips.put("other", clip(10, LoopMode.PLAY_ONCE));
		play("loops", 0, 1, LoopMode.LOOP);

		sweep(0);
		this.state.play(CONTROLLER, "other", 35, 0, 1, LoopMode.PLAY_ONCE);
		sweep(35);
		sweep(60);

		assertEquals(List.of("loop1", "loop2", "loop3"), seen, "loops 1-3 completed by 35; the old clip fires nothing after");
	}

	@Test
	@DisplayName("a stop with a time still fires what the clip crossed before it, and nothing after")
	void stopFlushesUpToTheStop() {
		this.clips.put("c", clip(100, LoopMode.PLAY_ONCE, marker(0, 10, "a"), marker(1, 30, "b")));
		listenTo("a", "b");
		play("c", 0, 1, LoopMode.PLAY_ONCE);

		sweep(5);
		this.state.stop(CONTROLLER, 15);
		sweep(15);
		sweep(40);

		assertEquals(List.of("a/0"), this.fired, "a (at 10) was crossed before the stop at 15; b (at 30) never happens");
	}

	@Test
	@DisplayName("a stop with no time drops the part-tick of events since the last sweep, but fires nothing later")
	void stopWithoutATimeDropsThePartTick() {
		this.clips.put("c", clip(100, LoopMode.PLAY_ONCE, marker(0, 10, "a"), marker(1, 30, "b")));
		listenTo("a", "b");
		play("c", 0, 1, LoopMode.PLAY_ONCE);

		sweep(5);
		this.state.stop(CONTROLLER);
		sweep(15);
		sweep(40);

		assertEquals(List.of(), this.fired);
	}

	@Test
	@DisplayName("a replacement flushes the old clip up to the change, then the new clip starts fresh")
	void replacementFlushesThenStartsFresh() {
		this.clips.put("old", clip(100, LoopMode.PLAY_ONCE, marker(0, 10, "olda"), marker(1, 30, "oldb")));
		this.clips.put("new", clip(100, LoopMode.PLAY_ONCE, marker(0, 2, "newa")));
		listenTo("olda", "oldb", "newa");
		play("old", 0, 1, LoopMode.PLAY_ONCE);

		sweep(5);
		this.state.play(CONTROLLER, "new", 12, 0, 1, LoopMode.PLAY_ONCE);
		sweep(12);
		sweep(20);
		sweep(40);

		assertEquals(List.of("olda/0", "newa/0"), this.fired);
	}

	@Test
	@DisplayName("a clip replaced before any sweep saw it fires nothing")
	void neverSweptClipFiresNothing() {
		this.clips.put("a", clip(100, LoopMode.PLAY_ONCE, marker(0, 0, "froma")));
		this.clips.put("b", clip(100, LoopMode.PLAY_ONCE, marker(0, 0, "fromb")));
		listenTo("froma", "fromb");

		play("a", 10, 1, LoopMode.PLAY_ONCE);
		this.state.play(CONTROLLER, "b", 10, 0, 1, LoopMode.PLAY_ONCE);

		sweep(10);
		sweep(20);
		assertEquals(List.of("fromb/0"), this.fired);
	}

	@Test
	@DisplayName("a rate change neither repeats nor skips an event, even one on the re-base instant")
	void rateChangeIsSeamless() {
		this.clips.put("c", clip(1000, LoopMode.PLAY_ONCE, marker(0, 25, "onrebase"), marker(1, 50, "b"), marker(2, 70, "c")));
		listenTo("onrebase", "b", "c");
		play("c", 0, 1, LoopMode.PLAY_ONCE);

		sweep(10);
		sweep(24);
		this.state.setRate(CONTROLLER, 2, 25);

		for (long t = 25; t <= 60; t++)
			sweep(t);

		assertEquals(List.of("onrebase/0", "b/0", "c/0"), this.fired);
	}

	@Test
	@DisplayName("an event between the last sweep and a rate change still fires - the cursor carries across, it does not restart")
	void rateChangeDoesNotLoseThePartTick() {
		this.clips.put("c", clip(1000, LoopMode.PLAY_ONCE, marker(0, 24.5, "mid"), marker(1, 40, "later")));
		listenTo("mid", "later");
		play("c", 0, 1, LoopMode.PLAY_ONCE);

		sweep(24);
		this.state.setRate(CONTROLLER, 2, 25);
		sweep(25);
		sweep(33); // 25 + 8 ticks at double speed = clip tick 41, past the event at 40

		assertEquals(List.of("mid/0", "later/0"), this.fired);
	}

	@Test
	@DisplayName("chaining a clip onto an earlier marker never steps the old cursor backwards into a reverse-firing event")
	void chainingDoesNotFireReverseEventsSpuriously() {
		TimelineEvent reverseEvent = new TimelineEvent(1, 11, new ParsedEvent<>(StrutBuiltinEvents.MARKER, "rev"), "rev",
				EventAuthority.SERVER, ReversePolicy.FIRE);

		this.clips.put("first", clip(1000, LoopMode.PLAY_ONCE, marker(0, 10, "swap"), reverseEvent));
		this.clips.put("second", clip(1000, LoopMode.PLAY_ONCE));
		listenTo("swap", "rev");
		this.listeners.addMarker("swap", ctx -> ctx.animations().playAt(CONTROLLER, "second", ctx.exactGameTime(), 1, LoopMode.PLAY_ONCE));
		play("first", 100, 0.5f, LoopMode.PLAY_ONCE);

		sweep(100);
		sweep(125);
		sweep(126);
		sweep(130);

		// swap (tick 10) and rev (tick 11) are both crossed by the sweep to 125 (tick 12.5), going forward.
		// The chain then replaces the clip at tick 10 - behind the cursor - and stepping back to 10 would
		// cross rev again in reverse, where it opts in to firing
		assertEquals(List.of("swap/0", "rev/0"), this.fired);
	}

	@Test
	@DisplayName("a silent seek fires nothing it jumps over, nothing on the landing instant, and what follows")
	void quietSeek() {
		this.clips.put("c", clip(100, LoopMode.PLAY_ONCE, marker(0, 10, "skipped"), marker(1, 25, "landing"), marker(2, 30, "after")));
		listenTo("skipped", "landing", "after");
		play("c", 0, 1, LoopMode.PLAY_ONCE);

		sweep(5);
		this.state.seek(CONTROLLER, 25, 8, false);

		for (long t = 8; t <= 20; t++)
			sweep(t);

		assertEquals(List.of("after/0"), this.fired);
	}

	@Test
	@DisplayName("a seek that asks for it fires the events between the old and new position, in order")
	void fireCrossedSeek() {
		this.clips.put("c", clip(100, LoopMode.PLAY_ONCE, marker(0, 10, "a"), marker(1, 25, "b"), marker(2, 60, "c")));
		listenTo("a", "b", "c");
		play("c", 0, 1, LoopMode.PLAY_ONCE);

		sweep(5);
		this.state.seek(CONTROLLER, 25, 8, true);
		sweep(8);
		sweep(9);

		assertEquals(List.of("a/0", "b/0"), this.fired);
	}

	@Test
	@DisplayName("a handler that starts another clip cannot recurse, and the new clip's events fire next sweep")
	void handlersCannotRecurse() {
		this.clips.put("first", clip(100, LoopMode.PLAY_ONCE, marker(0, 5, "swap"), marker(1, 6, "stillfirst")));
		this.clips.put("second", clip(100, LoopMode.PLAY_ONCE, marker(0, 0, "secondstart")));
		listenTo("swap", "stillfirst", "secondstart");
		this.listeners.addMarker("swap", ctx -> ctx.animations().play(CONTROLLER, "second", 10, 0, 1, LoopMode.PLAY_ONCE));
		play("first", 0, 1, LoopMode.PLAY_ONCE);

		sweep(10);

		assertEquals(List.of("swap/0", "stillfirst/0"), this.fired,
				"the sweep already decided its events; the new clip is not swept until the next one");

		sweep(11);

		assertEquals(List.of("swap/0", "stillfirst/0", "secondstart/0"), this.fired);
	}

	@Test
	@DisplayName("exactGameTime is the moment the event was authored at, which playAt chains a clip onto with no lag")
	void exactTimeAndChaining() {
		this.clips.put("first", clip(100, LoopMode.PLAY_ONCE, marker(0, 10, "swap")));
		this.clips.put("second", clip(100, LoopMode.PLAY_ONCE));
		List<Double> exact = new ArrayList<>();

		this.listeners.addMarker("swap", ctx -> {
			exact.add(ctx.exactGameTime());
			ctx.animations().playAt(CONTROLLER, "second", ctx.exactGameTime(), 1, LoopMode.PLAY_ONCE);
		});
		play("first", 100, 0.5f, LoopMode.PLAY_ONCE);

		sweep(100);
		sweep(125);

		assertEquals(List.of(120.0), exact, "tick 10 at half speed happens 20 game ticks in: 100 + 10 / 0.5");

		AnimationPlayback next = this.state.get(CONTROLLER);

		assertEquals("second", next.animation());
		assertEquals(0, next.elapsedTicks(120, 0), 1e-4, "the chained clip is at its own start exactly on the marker");
		assertEquals(5, next.elapsedTicks(125, 0), 1e-4, "and stays in step from then on");
	}

	@Test
	@DisplayName("playAt handles an instant between ticks: the start is rounded up and the offset absorbs the difference")
	void playAtBetweenTicks() {
		this.state.playAt(CONTROLLER, "c", 110.4, 1, LoopMode.PLAY_ONCE);

		AnimationPlayback playback = this.state.get(CONTROLLER);

		assertEquals(111, playback.startGameTick());
		assertEquals(0, playback.elapsedTicks(110, 0.4f), 1e-4, "elapsed is 0 at the requested instant");
		assertTrue(playback.startOffsetTicks() >= 0, "never starts before its own beginning");
	}

	@Test
	@DisplayName("events whose authority is client-only never run on the server; both-authority ones do")
	void authorityFiltersDelivery() {
		this.clips.put("c", clip(100, LoopMode.PLAY_ONCE,
				marker(0, 1, "serveronly", EventAuthority.SERVER), marker(1, 2, "clientonly", EventAuthority.CLIENT),
				marker(2, 3, "both", EventAuthority.BOTH)));
		listenTo("serveronly", "clientonly", "both");
		play("c", 0, 1, LoopMode.PLAY_ONCE);

		sweep(10);

		assertEquals(List.of("serveronly/0", "both/0"), this.fired);
	}

	@Test
	@DisplayName("a throwing listener does not stop the others, the rest of the sweep, or later sweeps")
	void failuresAreIsolated() {
		this.clips.put("c", clip(100, LoopMode.PLAY_ONCE, marker(0, 1, "a"), marker(1, 2, "b")));
		this.listeners.addMarker("a", ctx -> {
			throw new IllegalStateException("boom");
		});
		listenTo("a", "b");
		play("c", 0, 1, LoopMode.PLAY_ONCE);

		sweep(10);

		assertEquals(List.of("a/0", "b/0"), this.fired, "the second listener for a, and event b, still ran");
	}

	@Test
	@DisplayName("delivery order: the type's own handler, the entity hook, type listeners, then marker listeners")
	void deliveryOrder() {
		List<String> order = new ArrayList<>();
		StrutEventType<String> markerLike = StrutEventType.builder(Identifier.of("test", "thing"), StrutBuiltinEvents.MARKER.codec())
				.serverHandler((ctx, payload) -> order.add("handler")).parser(text -> text).build();
		TimelineEvent event = new TimelineEvent(0, 1, new ParsedEvent<>(markerLike, "x"), "x", EventAuthority.SERVER, ReversePolicy.SKIP);

		this.clips.put("c", clip(100, LoopMode.PLAY_ONCE, event));
		this.listeners.add(markerLike.id(), ctx -> order.add("type listener"));
		play("c", 0, 1, LoopMode.PLAY_ONCE);

		this.dispatcher.sweep(new ServerEventDispatcher.Subject(1, ENTITY, null, ctx -> order.add("entity hook")), this.state,
				clip -> Optional.of(this.clips.get(clip)), 10);

		assertEquals(List.of("handler", "entity hook", "type listener"), order);
	}

	@Test
	@DisplayName("marker listeners match names case-insensitively")
	void markerNamesAreCaseInsensitive() {
		this.clips.put("c", clip(100, LoopMode.PLAY_ONCE, marker(0, 1, "becomebare")));
		this.listeners.addMarker("becomeBare", ctx -> this.fired.add("bare"));
		play("c", 0, 1, LoopMode.PLAY_ONCE);

		sweep(10);

		assertEquals(List.of("bare"), this.fired);
	}

	@Test
	@DisplayName("a clip nobody has a timeline for fires nothing, and is only looked up once per playback")
	void missingTimelineIsQuiet() {
		play("unknown", 0, 1, LoopMode.LOOP);

		sweep(0);
		sweep(10);
		sweep(20);

		assertEquals(List.of(), this.fired);
		assertEquals(1, this.lookups.get(), "resolved once for this playback, not every tick");
	}

	@Test
	@DisplayName("a runaway clip is capped per sweep and the dispatcher carries on afterwards")
	void runawayIsCapped() {
		this.clips.put("c", clip(1, LoopMode.LOOP, marker(0, 0.5, "tick")));
		listenTo("tick");
		play("c", 0, 100_000f, LoopMode.LOOP);

		sweep(0);
		sweep(1);

		// The cap counts every occurrence, and each lap of this clip is two: its marker and the engine's loop
		assertEquals(EventCursorCapProbe.CAP / 2, this.fired.size(), "one sweep delivers at most the cap");

		sweep(2);

		assertEquals(EventCursorCapProbe.CAP, this.fired.size(), "the next sweep is capped again and nothing broke");
	}

	@Test
	@DisplayName("each entity is swept independently")
	void entitiesAreIndependent() {
		this.clips.put("c", clip(100, LoopMode.PLAY_ONCE, marker(0, 5, "a")));
		listenTo("a");
		play("c", 0, 1, LoopMode.PLAY_ONCE);

		ServerAnimationState other = new ServerAnimationState();

		other.play(CONTROLLER, "c", 0, 0, 1, LoopMode.PLAY_ONCE);

		sweep(1, 10);
		this.dispatcher.sweep(new ServerEventDispatcher.Subject(2, ENTITY, null, null), other,
				clip -> Optional.of(this.clips.get(clip)), 10);

		assertEquals(List.of("a/0", "a/0"), this.fired, "each entity's clip fired its own event once");
	}

	@Test
	@DisplayName("what fires is recorded in the log, once, so tick code can poll it as an edge")
	void firingIsLogged() {
		this.clips.put("c", clip(100, LoopMode.PLAY_ONCE, marker(0, 5, "swap")));
		play("c", 0, 1, LoopMode.PLAY_ONCE);

		sweep(10);

		assertTrue(this.log.firedMarkerLastTick(1, "swap", 11), "visible to the next tick's polling");
		assertTrue(this.log.firedMarkerLastTick(1, "SWAP", 11), "case-insensitive");
		assertFalse(this.log.firedMarkerLastTick(1, "swap", 12), "and gone the tick after - an edge, not a level");
	}

	@Test
	@DisplayName("forgetting an entity drops its cursors, so a reused id starts clean")
	void forgetResets() {
		this.clips.put("c", clip(100, LoopMode.PLAY_ONCE, marker(0, 5, "a")));
		listenTo("a");
		play("c", 0, 1, LoopMode.PLAY_ONCE);
		sweep(10);

		this.dispatcher.forget(1);
		this.state = new ServerAnimationState();
		play("c", 50, 1, LoopMode.PLAY_ONCE);
		sweep(60);

		assertEquals(List.of("a/0", "a/0"), this.fired);
	}

	/** The cursor's own cap, named so this test does not hard-code 256 in two places */
	private static final class EventCursorCapProbe {
		static final int CAP = EventCursor.DEFAULT_MAX_OCCURRENCES;
	}

	@Test
	@DisplayName("the handler context carries the entity, controller, sequence and a per-occurrence random")
	void contextIsComplete() {
		List<EventContext> seen = new ArrayList<>();

		this.clips.put("c", clip(100, LoopMode.PLAY_ONCE, marker(0, 5, "a")));
		this.listeners.addMarker("a", seen::add);
		play("c", 0, 1, LoopMode.PLAY_ONCE);
		sweep(10);

		EventContext ctx = seen.get(0);

		assertEquals(1, ctx.entityId());
		assertEquals(CONTROLLER, ctx.controller());
		assertEquals(this.state.get(CONTROLLER).sequence(), ctx.sequence());
		assertEquals(10, ctx.gameTime());
		assertEquals(5.0, ctx.exactGameTime(), 1e-9);
		assertTrue(ctx.isMarker("A"));
		assertFalse(ctx.isMarker("b"));
		assertEquals(ctx.random().nextLong(), ctx.random().nextLong(), "the same source however often it is asked for");
		assertSame(this.state, ctx.animations());
	}
}
