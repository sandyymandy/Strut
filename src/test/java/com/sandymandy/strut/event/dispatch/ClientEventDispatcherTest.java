package com.sandymandy.strut.event.dispatch;

import com.sandymandy.strut.animation.authority.AnimationPlayback;
import com.sandymandy.strut.animation.authority.ClientAnimationState;
import com.sandymandy.strut.animation.authority.SnapshotReason;
import com.sandymandy.strut.event.client.ClientEventContext;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.ParsedEvent;
import com.sandymandy.strut.event.type.ReversePolicy;
import com.sandymandy.strut.event.type.StrutEventType;
import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import com.sandymandy.strut.event.timeline.ClipTimeline;
import com.sandymandy.strut.event.timeline.TimelineEvent;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The client dispatcher, driven through a real {@link ClientAnimationState} the
 * way the network drives it - deltas and snapshots - with a fake clock
 * <p>
 * Each test is a row of the event table, or one of the network
 * misbehaviours of a bad network (late, lost, duplicated, reordered). The property throughout: a
 * client fires each event at most once, fires what it just missed if that is recent, and never replays
 * history
 */
class ClientEventDispatcherTest {
	private static final UUID ENTITY = new UUID(3, 4);
	private static final String CONTROLLER = "base";

	private ClientAnimationState state;
	private ClientEventListeners listeners;
	private EventLog log;
	private ClientEventDispatcher dispatcher;
	private Map<String, ClipTimeline> clips;
	private List<String> fired;
	private AtomicInteger lookups;
	private boolean participant;

	@BeforeEach
	void setUp() {
		this.state = new ClientAnimationState();
		this.listeners = new ClientEventListeners();
		this.log = new EventLog();
		this.dispatcher = new ClientEventDispatcher(this.listeners, this.log);
		this.clips = new HashMap<>();
		this.fired = new ArrayList<>();
		this.lookups = new AtomicInteger();
		this.participant = false;
	}

	private static TimelineEvent marker(int index, double tick, String name, EventAuthority authority) {
		return new TimelineEvent(index, tick, new ParsedEvent<>(StrutBuiltinEvents.MARKER, name), name, authority, ReversePolicy.SKIP);
	}

	private static TimelineEvent marker(int index, double tick, String name) {
		return marker(index, tick, name, EventAuthority.CLIENT);
	}

	private static ClipTimeline clip(double length, LoopMode mode, TimelineEvent... events) {
		return new ClipTimeline(length, mode, List.of(events), 0L);
	}

	private void listenTo(String... names) {
		for (String name : names)
			this.listeners.addMarker(name, ctx -> this.fired.add(name + "/" + ctx.loop()));
	}

	private void sweep(long gameTime) {
		this.dispatcher.sweep(new ClientEventDispatcher.Subject(1, ENTITY, null, this.participant, null), this.state,
				clip -> {
					this.lookups.incrementAndGet();

					return Optional.ofNullable(this.clips.get(clip));
				}, gameTime);
	}

	private static AnimationPlayback playback(String clip, long start, float offset, float rate, LoopMode loop, long sequence) {
		return new AnimationPlayback(CONTROLLER, clip, start, offset, rate, loop, sequence);
	}

	private void delta(AnimationPlayback playback) {
		this.state.applyDelta(CONTROLLER, playback.sequence(), playback);
	}

	private void snapshot(SnapshotReason reason, AnimationPlayback... playbacks) {
		this.state.applySnapshot(List.of(playbacks), reason);
	}

	private void useDemoClip() {
		this.clips.put("c", clip(1000, LoopMode.PLAY_ONCE, marker(0, 0, "a"), marker(1, 10, "b"), marker(2, 30, "c")));
		listenTo("a", "b", "c");
	}

	@Test
	@DisplayName("a delta that arrives promptly fires the clip's events on time, including one at the very start")
	void promptDelta() {
		useDemoClip();

		delta(playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(100);
		assertEquals(List.of("a/0"), this.fired);

		sweep(110);
		sweep(130);
		assertEquals(List.of("a/0", "b/0", "c/0"), this.fired);
	}

	@Test
	@DisplayName("a delta that arrives a few ticks late still fires the clip's first events - latency must not eat them")
	void lateDeltaStillFiresTheStart() {
		useDemoClip();

		delta(playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(103);

		assertEquals(List.of("a/0"), this.fired, "the start event was 3 ticks ago, well inside the late window");

		sweep(111);
		assertEquals(List.of("a/0", "b/0"), this.fired);
	}

	@Test
	@DisplayName("a delta that arrives long after the clip began fires only what is still recent, then carries on")
	void veryLateDeltaSkipsOldEvents() {
		useDemoClip();

		delta(playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(115);

		assertEquals(List.of("b/0"), this.fired,
				"a (at 100) is 15 ticks old, outside the 10-tick window; b (at 110) is 5 ticks old, inside it");

		sweep(130);
		assertEquals(List.of("b/0", "c/0"), this.fired);
	}

	@Test
	@DisplayName("the snapshot sent when tracking starts replays nothing, and later events still fire")
	void startTrackingSnapshotOwesNoHistory() {
		useDemoClip();

		// The clip began at 100 and this client learns of it at 105. Event a (at the start) is only 5 ticks
		// old - well inside the late window a delta would honour - but a client that has just started
		// tracking is owed state, not history, so it must not fire
		snapshot(SnapshotReason.START_TRACKING, playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(105);

		assertEquals(List.of(), this.fired, "a happened before this client arrived, however recently");

		sweep(110);
		sweep(130);
		assertEquals(List.of("b/0", "c/0"), this.fired, "everything after the moment of arrival still fires");
	}

	@Test
	@DisplayName("a periodic snapshot of a clip the client already holds replays nothing and duplicates nothing")
	void periodicSnapshotOfAHeldClipIsSilent() {
		useDemoClip();

		delta(playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(100);
		sweep(112);
		assertEquals(List.of("a/0", "b/0"), this.fired);

		snapshot(SnapshotReason.PERIODIC, playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(113);
		sweep(114);
		assertEquals(List.of("a/0", "b/0"), this.fired, "the same sequence again is not a new clip");

		sweep(130);
		assertEquals(List.of("a/0", "b/0", "c/0"), this.fired, "and the remaining event still fires exactly once");
	}

	@Test
	@DisplayName("a resync that introduces a clip whose delta was lost delivers what is still recent")
	void refreshRecoversALostDelta() {
		useDemoClip();

		snapshot(SnapshotReason.RESYNC, playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(104);

		assertEquals(List.of("a/0"), this.fired, "the delta was lost, but a is only 4 ticks old");
	}

	@Test
	@DisplayName("a refresh that arrives long after the loss recovers nothing old")
	void oldRefreshRecoversNothingOld() {
		useDemoClip();

		snapshot(SnapshotReason.PERIODIC, playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(125);

		assertEquals(List.of(), this.fired, "a and b are 25 and 15 ticks old");

		sweep(130);
		assertEquals(List.of("c/0"), this.fired);
	}

	@Test
	@DisplayName("a duplicated delta, and a stale delta arriving after a newer one, change nothing")
	void duplicatedAndReorderedDeltas() {
		useDemoClip();
		this.clips.put("other", clip(1000, LoopMode.PLAY_ONCE, marker(0, 0, "a")));

		AnimationPlayback first = playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1);

		delta(first);
		delta(first);
		sweep(100);
		assertEquals(List.of("a/0"), this.fired, "delivered once despite arriving twice");

		delta(playback("other", 105, 0, 1, LoopMode.PLAY_ONCE, 2));
		delta(first);
		sweep(105);
		sweep(106);

		assertEquals(List.of("a/0", "a/0"), this.fired, "the newer clip's own a fired; the stale delta did not resurrect the old clip");
		assertEquals("other", this.state.get(CONTROLLER).animation());
	}

	@Test
	@DisplayName("a mirrored state cleared and rebuilt mid-clip does not redeliver what was just delivered")
	void rebuiltStateDoesNotRedeliver() {
		useDemoClip();

		AnimationPlayback playback = playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1);

		delta(playback);
		sweep(100);
		assertEquals(List.of("a/0"), this.fired);

		// Something empties the mirror (an empty resync) and then restores the very same clip. The clip is
		// re-introduced as a refresh, whose late window reaches back over the start event it already fired
		snapshot(SnapshotReason.RESYNC);
		sweep(101);
		snapshot(SnapshotReason.RESYNC, playback);
		sweep(102);

		assertEquals(List.of("a/0"), this.fired, "the duplicate guard, not the cursor, is what stops this one");
	}

	@Test
	@DisplayName("a rate change carries the cursor across: an event between the last tick and the change still fires once")
	void rateChangeIsSeamless() {
		this.clips.put("c", clip(1000, LoopMode.PLAY_ONCE, marker(0, 24.5, "mid"), marker(1, 25, "edge"), marker(2, 40, "later")));
		listenTo("mid", "edge", "later");

		delta(playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(124);

		// The server re-based at game tick 125 to double speed, continuous at clip tick 25 - exactly where
		// "edge" sits - and it arrives a tick late
		delta(playback("c", 125, 25, 2, LoopMode.PLAY_ONCE, 2));
		sweep(126);
		assertEquals(List.of("mid/0", "edge/0"), this.fired, "edge fires once, not once for each side of the change");

		sweep(133);
		assertEquals(List.of("mid/0", "edge/0", "later/0"), this.fired, "no repeat and no gap across the change");
	}

	@Test
	@DisplayName("a replacement fires what the old clip crossed up to it, then the new clip starts fresh")
	void replacementFlushesThenStartsFresh() {
		this.clips.put("old", clip(1000, LoopMode.PLAY_ONCE, marker(0, 12, "olda"), marker(1, 50, "oldb")));
		this.clips.put("new", clip(1000, LoopMode.PLAY_ONCE, marker(0, 0, "newa")));
		listenTo("olda", "oldb", "newa");

		delta(playback("old", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(110);

		delta(playback("new", 113, 0, 1, LoopMode.PLAY_ONCE, 2));
		sweep(114);
		sweep(130);

		assertEquals(List.of("olda/0", "newa/0"), this.fired, "olda (12) was before the replacement at 113; oldb never happens");
	}

	@Test
	@DisplayName("a stop fires nothing more from the old clip - it has no time on the wire to flush to")
	void stopDropsTheRest() {
		useDemoClip();

		delta(playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(105);
		this.fired.clear();

		this.state.applyDelta(CONTROLLER, 2, null);
		sweep(120);
		sweep(140);

		assertEquals(List.of(), this.fired);
	}

	@Test
	@DisplayName("only events whose authority includes clients are delivered on a client")
	void authorityFilter() {
		this.clips.put("c", clip(1000, LoopMode.PLAY_ONCE, marker(0, 1, "serveronly", EventAuthority.SERVER),
				marker(1, 2, "clientonly", EventAuthority.CLIENT), marker(2, 3, "both", EventAuthority.BOTH)));
		listenTo("serveronly", "clientonly", "both");

		delta(playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(110);

		assertEquals(List.of("clientonly/0", "both/0"), this.fired);
	}

	@Test
	@DisplayName("the participant flag reaches handlers as decided by the caller")
	void participantFlag() {
		List<Boolean> seen = new ArrayList<>();

		this.clips.put("c", clip(1000, LoopMode.PLAY_ONCE, marker(0, 1, "cue")));
		this.listeners.addMarker("cue", ctx -> seen.add(ctx.isLocalPlayerParticipant()));

		delta(playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		this.participant = true;
		sweep(110);

		assertEquals(List.of(true), seen);
	}

	@Test
	@DisplayName("delivery order: the type's own client handler, the entity hook, type listeners, marker listeners")
	void deliveryOrder() {
		List<String> order = new ArrayList<>();
		StrutEventType<String> type = StrutEventType.builder(Identifier.of("test", "cue"), StrutBuiltinEvents.MARKER.codec())
				.clientHandler((ctx, payload) -> order.add("handler")).parser(text -> text).build();
		TimelineEvent event = new TimelineEvent(0, 1, new ParsedEvent<>(type, "x"), "x", EventAuthority.CLIENT, ReversePolicy.SKIP);

		this.clips.put("c", clip(1000, LoopMode.PLAY_ONCE, event));
		this.listeners.add(type.id(), ctx -> order.add("type listener"));
		delta(playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));

		this.dispatcher.sweep(new ClientEventDispatcher.Subject(1, ENTITY, null, false, ctx -> order.add("entity hook")), this.state,
				clip -> Optional.of(this.clips.get(clip)), 110);

		assertEquals(List.of("handler", "entity hook", "type listener"), order);
	}

	@Test
	@DisplayName("a throwing listener does not stop the others or the rest of the sweep")
	void failuresAreIsolated() {
		this.clips.put("c", clip(1000, LoopMode.PLAY_ONCE, marker(0, 1, "a"), marker(1, 2, "b")));
		this.listeners.addMarker("a", ctx -> {
			throw new IllegalStateException("boom");
		});
		listenTo("a", "b");

		delta(playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(110);

		assertEquals(List.of("a/0", "b/0"), this.fired);
	}

	@Test
	@DisplayName("a looping clip's wraps and events fire once per lap, including across a long client stall")
	void loopsAcrossAStall() {
		this.clips.put("c", clip(10, LoopMode.LOOP, marker(0, 5, "tick")));
		listenTo("tick");
		List<String> wraps = new ArrayList<>();

		this.listeners.add(StrutBuiltinEvents.LOOP.id(), ctx -> wraps.add("loop" + ctx.payload(Long.class)));
		delta(playback("c", 100, 0, 1, LoopMode.LOOP, 1));

		sweep(100);
		sweep(133);

		assertEquals(List.of("tick/0", "tick/1", "tick/2"), this.fired, "the event at 5, 15 and 25 - the one at 35 is still ahead");
		assertEquals(List.of("loop1", "loop2", "loop3"), wraps);
	}

	@Test
	@DisplayName("a clip nobody has a timeline for fires nothing and is looked up once per playback")
	void missingTimeline() {
		delta(playback("unknown", 100, 0, 1, LoopMode.LOOP, 1));

		sweep(100);
		sweep(110);
		sweep(120);

		assertEquals(List.of(), this.fired);
		assertEquals(1, this.lookups.get());
	}

	@Test
	@DisplayName("what fires is logged once so client tick code can poll it as an edge")
	void firingIsLogged() {
		this.clips.put("c", clip(1000, LoopMode.PLAY_ONCE, marker(0, 5, "hudui")));
		delta(playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));

		sweep(110);

		assertTrue(this.log.firedMarkerLastTick(1, "hudui", 111));
		assertFalse(this.log.firedMarkerLastTick(1, "hudui", 112));
	}

	@Test
	@DisplayName("forgetting an entity resets its cursors and duplicate memory")
	void forgetResets() {
		this.clips.put("c", clip(1000, LoopMode.PLAY_ONCE, marker(0, 5, "a")));
		listenTo("a");
		delta(playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(110);

		this.dispatcher.forget(1);
		this.state = new ClientAnimationState();
		delta(playback("c", 200, 0, 1, LoopMode.PLAY_ONCE, 1));
		sweep(210);

		assertEquals(List.of("a/0", "a/0"), this.fired, "the reused sequence number does not look like a duplicate");
	}

	@Test
	@DisplayName("the context carries identity and a random identical to the server's for the same firing")
	void contextMatchesTheServersIdentity() {
		List<ClientEventContext> seen = new ArrayList<>();

		this.clips.put("c", clip(1000, LoopMode.PLAY_ONCE, marker(0, 5, "a")));
		this.listeners.addMarker("a", seen::add);
		delta(playback("c", 100, 0, 1, LoopMode.PLAY_ONCE, 9));
		sweep(110);

		ClientEventContext ctx = seen.get(0);

		assertEquals(9, ctx.sequence());
		assertEquals(105.0, ctx.exactGameTime(), 1e-9);
		assertTrue(ctx.isMarker("A"));
		assertEquals(EventRandom.forOccurrence(ENTITY, CONTROLLER, 9, 0, 0).nextLong(), ctx.random().nextLong(),
				"same UUID, controller, sequence, loop and index give the server's exact draw");
	}
}
