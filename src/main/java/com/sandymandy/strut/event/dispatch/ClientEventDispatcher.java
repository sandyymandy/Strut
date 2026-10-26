package com.sandymandy.strut.event.dispatch;

import com.sandymandy.strut.animation.authority.AnimationPlayback;
import com.sandymandy.strut.animation.authority.ClientAnimationState;
import com.sandymandy.strut.animation.authority.ClientAnimationState.Applied;
import com.sandymandy.strut.animation.authority.ClientAnimationState.ArrivalKind;
import com.sandymandy.strut.animation.authority.ServerAnimationState;
import com.sandymandy.strut.event.client.ClientEventContext;
import com.sandymandy.strut.event.client.ClientEventListener;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.ParsedEvent;
import com.sandymandy.strut.event.type.StrutEventType;
import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import com.sandymandy.strut.event.cursor.EventCursor;
import com.sandymandy.strut.event.cursor.EventOccurrence;
import com.sandymandy.strut.event.cursor.EventSchedule;
import com.sandymandy.strut.event.timeline.ClipTimeline;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.util.Unit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The client half of typed events: once per client tick per entity, works out which events each
 * mirrored clip crossed since the last tick and delivers the ones with client authority
 * <p>
 * Zero packets: it reads the {@link ClientAnimationState} the animation system already mirrors, and
 * the same synced clock the pose samples, so an event cannot disagree with the animation it belongs
 * to. All the crossing arithmetic is {@link EventCursor}'s; this class decides where a cursor
 * <i>starts</i>, which is the whole difficulty on a client, because a client learns about a clip late.
 *
 *
 * <h2>Where a cursor starts</h2>
 *
 * <table>
 * <caption>How the playback arrived, and what that means for its events</caption>
 * <tr><th>Arrived as</th><th>Cursor starts</th><th>Why</th></tr>
 * <tr><td>A delta or refresh, a fresh play</td><td>the clip's start, but only events within
 *     {@value #MAX_LATE_TICKS} ticks of now fire</td>
 *     <td>A delta is always a network latency late; starting at "now" would silently drop every sound in
 *     a clip's first fraction of a second, for everyone</td></tr>
 * <tr><td>The snapshot sent on start-tracking</td><td>now</td>
 *     <td>A late joiner is owed state, not history</td></tr>
 * <tr><td>A resync or periodic snapshot for a clip already held</td><td>unchanged</td>
 *     <td>Being told the same thing again must replay nothing</td></tr>
 * <tr><td>A new sequence continuous with the old clip (a rate change)</td><td>the old cursor, carried over</td>
 *     <td>The same clip on the same elapsed axis: no repeat, no gap</td></tr>
 * </table>
 *
 * <h2>What a client cannot know</h2>
 *
 * <p>The wire does not say whether a new sequence was a fresh play, a seek, or a silent seek, so a
 * discontinuous change is treated as a fresh start at its landing point: an event exactly on the landing
 * instant fires, and a seek that asked to fire crossed events does not fire them here. That only ever
 * affects client (cosmetic) events - gameplay events are the server's, and the server does know. A stop
 * carries no time either, so a stop drops whatever the old clip crossed since the last client tick
 *
 * <h2>Second guard</h2>
 *
 * <p>A bounded set of recently delivered occurrence keys per entity drops a duplicate that reaches
 * delivery by a path nobody has thought of yet
 */
public final class ClientEventDispatcher {
	private static final Logger LOGGER = LogManager.getLogger("Strut");

	/**
	 * How far back, in ticks, a freshly arrived clip's events may still fire: late audio beats missing
	 * audio, and anything older is stale
	 */
	public static final int MAX_LATE_TICKS = 10;

	/** Occurrence keys remembered per entity for the duplicate guard */
	public static final int DEDUPE_CAPACITY = 128;

	/** How far apart two playbacks' positions may be at a hand-over and still count as one continuous clip */
	private static final double CONTINUITY_TOLERANCE_TICKS = 0.5;

	private final ClientEventListeners listeners;
	private final EventLog log;
	private final Int2ObjectMap<Map<String, Tracked>> tracked = new Int2ObjectOpenHashMap<>();
	private final Int2ObjectMap<RecentOccurrences> recent = new Int2ObjectOpenHashMap<>();
	/** Per entity, per controller: the highest applied-change version already replayed */
	private final Int2ObjectMap<Map<String, Long>> seen = new Int2ObjectOpenHashMap<>();

	public ClientEventDispatcher(ClientEventListeners listeners, EventLog log) {
		this.listeners = listeners;
		this.log = log;
	}

	/**
	 * Who a sweep is for
	 *
	 * @param entity The entity, passed to handlers; null only in tests
	 * @param participant Whether the local player is taking part in what this entity is doing
	 * @param entityHook The entity's own client hook, called after a type's own handler; null if none
	 */
	public record Subject(int entityId, UUID uuid, @Nullable Entity entity, boolean participant,
						  @Nullable Consumer<ClientEventContext> entityHook) {}

	private static final class Tracked {
		final AnimationPlayback playback;
		@Nullable
		final ClipTimeline timeline;
		final EventSchedule schedule;
		final EventCursor cursor;
		boolean truncationLogged;

		Tracked(AnimationPlayback playback, @Nullable ClipTimeline timeline, EventCursor cursor) {
			this.playback = playback;
			this.timeline = timeline;
			this.schedule = timeline != null ? timeline.schedule() : null;
			this.cursor = cursor;
		}
	}

	/**
	 * Runs one client tick's dispatch for one entity
	 * <p>
	 * Replays every change the client applied to each controller since the last sweep, in order, then advances the
	 * playback that is current now. A client that applies several deltas between two ticks - it stalled, or packets
	 * arrived together - would otherwise see only the newest playback, and a chain of rate changes would look like
	 * one discontinuous jump: treated as a fresh start, it would fire events it had already fired
	 *
	 * @param gameTime The current {@code World#getTime()}
	 */
	public void sweep(Subject subject, ClientAnimationState state, ClipTimelineLookup lookup, long gameTime) {
		Map<String, Tracked> mine = this.tracked.computeIfAbsent(subject.entityId(), id -> new LinkedHashMap<>());

		// Names first, copied: handlers may change the state while this runs
		Set<String> controllers = new LinkedHashSet<>(state.controllersWithHistory());

		controllers.addAll(mine.keySet());

		for (String controller : controllers) {
			sweepController(subject, state, lookup, gameTime, controller, mine);
		}

		if (mine.isEmpty())
			this.tracked.remove(subject.entityId());
	}

	/**
	 * Drops everything remembered about an entity - when it unloads, or its connection is rebuilt
	 */
	public void forget(int entityId) {
		this.tracked.remove(entityId);
		this.recent.remove(entityId);
		this.seen.remove(entityId);
	}

	private void sweepController(Subject subject, ClientAnimationState state, ClipTimelineLookup lookup, long gameTime, String controller,
								 Map<String, Tracked> mine) {
		Map<String, Long> seenHere = this.seen.computeIfAbsent(subject.entityId(), id -> new HashMap<>());
		Tracked current = mine.get(controller);
		long after = seenHere.getOrDefault(controller, 0L);

		for (Applied applied : state.changesAfter(controller, after)) {
			EventCursor carried = handleDeparture(subject, gameTime, controller, current, applied);

			current = applied.playback() != null ? begin(lookup, applied, carried, gameTime) : null;

			// A seek that fires what it crosses makes its jump at the change, exactly as the server does, so a stalled
			// client does not sweep the jump and the forward motion after it as one stretch
			if (current != null && carried != null && applied.origin() == ServerAnimationState.ChangeKind.SEEK_FIRE && current.schedule != null) {
				double landing = current.playback.elapsedTicks(current.playback.startGameTick(), 0);

				deliver(subject, gameTime, controller, current, current.cursor.advance(current.schedule, landing));
			}

			after = applied.version();
		}

		seenHere.put(controller, after);

		if (current == null) {
			mine.remove(controller);

			return;
		}

		mine.put(controller, current);
		advance(subject, gameTime, controller, current);
	}

	/**
	 * The tracked playback {@code old} is replaced by, or ended by, {@code applied}. Fires what it owes up to the
	 * replacement, and says whether its successor should inherit its cursor
	 * <p>
	 * A stop carries no time on the wire, so it flushes nothing: whatever the old clip crossed since the last tick
	 * is dropped. Any other change flushes to the new playback's start - even a rate change, so the old
	 * playback's events belong to the old playback - but never by stepping the cursor the wrong way
	 *
	 * @return The old cursor to carry over, or null for a fresh one
	 */
	@Nullable
	private EventCursor handleDeparture(Subject subject, long gameTime, String controller, @Nullable Tracked old, Applied applied) {
		AnimationPlayback next = applied.playback();

		if (old == null || next == null)
			return null;

		// A playback that lasted no game time at all was replaced before it was ever seen: it fires nothing
		boolean lastedSomeTime = next.startGameTick() > old.playback.startGameTick();

		if (old.schedule != null && lastedSomeTime) {
			double until = old.playback.elapsedTicks(Math.min(next.startGameTick(), gameTime), 0);
			boolean forward = old.playback.playbackRate() >= 0;

			if (forward ? until > old.cursor.position() : until < old.cursor.position())
				deliver(subject, gameTime, controller, old, old.cursor.advance(old.schedule, until));
		}

		return carriesOn(old.playback, applied) ? old.cursor.carryOver() : null;
	}

	/**
	 * Whether the new playback continues the old one's progress, so the cursor should carry across
	 * <p>
	 * The server says what kind of change it made, and that is believed: a rate change or a fire-crossed seek
	 * carries on (if it is the same clip - a lost play could otherwise have a rate change land on a different clip),
	 * and a play, a silent seek or a stop does not. Only where the server did not say - a snapshot, which
	 * describes a state and not a change - does it fall back to judging from the numbers
	 */
	private static boolean carriesOn(AnimationPlayback previous, Applied applied) {
		ServerAnimationState.ChangeKind origin = applied.origin();
		boolean sameClip = previous.animation().equals(applied.playback().animation());

		if (origin == null)
			return isContinuous(previous, applied.playback());

		return sameClip && (origin == ServerAnimationState.ChangeKind.REBASE || origin == ServerAnimationState.ChangeKind.SEEK_FIRE);
	}

	/**
	 * Whether {@code next} is the same clip carrying on from where {@code previous} was at that instant -
	 * which is what a rate change looks like, and nothing else does
	 * <p>
	 * Judged between <i>consecutive</i> playbacks, where it is true of a rate-change chain. Judged across a gap
	 * (the newest playback against one several changes old) it would fail, since the rate changed in between
	 */
	private static boolean isContinuous(AnimationPlayback previous, AnimationPlayback next) {
		if (!previous.animation().equals(next.animation()))
			return false;

		double at = next.startGameTick();

		return Math.abs(next.elapsedTicks((long)at, 0) - previous.elapsedTicks((long)at, 0)) <= CONTINUITY_TOLERANCE_TICKS;
	}

	private Tracked begin(ClipTimelineLookup lookup, Applied applied, @Nullable EventCursor carried, long gameTime) {
		AnimationPlayback playback = applied.playback();
		Optional<ClipTimeline> timeline = lookup.find(playback.animation());

		if (carried != null)
			return new Tracked(playback, timeline.orElse(null), carried);

		// When the change arrived, not when this sweep got to it: a client whose tick stalled must not lose
		// what happened between the packet and the tick. A state with no clock falls back to the sweep's time
		long arrivedAt = applied.appliedAt() == Applied.UNKNOWN_TIME ? gameTime : applied.appliedAt();

		// A silent seek lands without firing anything, not even an event on the landing instant
		if (applied.origin() == ServerAnimationState.ChangeKind.SEEK_QUIET)
			return new Tracked(playback, timeline.orElse(null), EventCursor.resumingAt(playback.elapsedTicks(playback.startGameTick(), 0)));

		// A rate change that this client has no predecessor for - the play's own packet was overtaken or lost - starts
		// partway through a clip that has been running. Its start is not the clip's beginning, so starting the cursor
		// there would lose everything before it. Extrapolate back over the recent-events window at this rate instead,
		// clamped at the clip's own start, which is the same "what is still recent" rule a fresh play gets
		if (applied.origin() == ServerAnimationState.ChangeKind.REBASE && playback.playbackRate() > 0) {
			double from = Math.max(0.0, playback.elapsedTicks(arrivedAt - MAX_LATE_TICKS, 0));

			return new Tracked(playback, timeline.orElse(null), from <= 0.0 ? EventCursor.startingAt(0.0) : EventCursor.resumingAt(from));
		}

		if (applied.kind() == ArrivalKind.SNAPSHOT_START_TRACKING)
			return new Tracked(playback, timeline.orElse(null), EventCursor.resumingAt(playback.elapsedTicks(arrivedAt, 0)));

		// A delta, or a refresh introducing a clip whose delta was lost: it started some time ago, so
		// deliver what is still recent and nothing older
		long lateFrom = arrivedAt - MAX_LATE_TICKS;

		EventCursor cursor = lateFrom <= playback.startGameTick()
				? EventCursor.startingAt(playback.elapsedTicks(playback.startGameTick(), 0))
				: EventCursor.resumingAt(playback.elapsedTicks(lateFrom, 0));

		return new Tracked(playback, timeline.orElse(null), cursor);
	}

	private void advance(Subject subject, long gameTime, String controller, Tracked tracked) {
		ClipTimeline timeline = tracked.timeline;

		if (timeline == null)
			return;

		if (!timeline.hasClientEvents() && !this.listeners.hasListenersFor(StrutBuiltinEvents.LOOP.id())
				&& !this.listeners.hasListenersFor(StrutBuiltinEvents.END.id()))
			return;

		double target = tracked.playback.elapsedTicks(gameTime, 0);

		// A client runs ahead of the truth for as long as it has not heard about a rate change: it extrapolates
		// the old speed. When the correction arrives, the true position can be *behind* the cursor. Stepping the
		// cursor back would make it cross the same events again and fire them twice, so a cursor holds still
		// until the true position catches up - a forward clip never moves backwards, a reversed one never
		// forwards. The cost is an event that fires slightly early, never twice
		double travel = tracked.playback.playbackRate();

		if ((travel > 0 && target < tracked.cursor.position()) || (travel < 0 && target > tracked.cursor.position()))
			return;

		EventCursor.Result result = tracked.cursor.advance(tracked.schedule, target);

		if (result.truncated())
			EventStats.recordTruncatedSweep();

		if (result.truncated() && !tracked.truncationLogged) {
			tracked.truncationLogged = true;

			LOGGER.warn("[Strut events] clip \"{}\" on entity {} crossed more than {} events in one client tick; the excess "
					+ "was dropped", tracked.playback.animation(), subject.entityId(), EventCursor.DEFAULT_MAX_OCCURRENCES);
		}

		deliver(subject, gameTime, controller, tracked, result);
	}

	private void deliver(Subject subject, long gameTime, String controller, Tracked tracked, EventCursor.Result result) {
		if (result.isEmpty() || tracked.timeline == null)
			return;

		List<ClientEventContext> contexts = new ArrayList<>(result.occurrences().size());

		for (EventOccurrence occurrence : result.occurrences()) {
			ClientEventContext context = contextFor(subject, gameTime, controller, tracked, occurrence);

			if (context != null && firstTimeSeen(subject.entityId(), controller, tracked.playback.sequence(), occurrence))
				contexts.add(context);
		}

		for (ClientEventContext context : contexts) {
			run(subject, context);
		}
	}

	/**
	 * The second guard: false if this exact occurrence was already delivered
	 */
	private boolean firstTimeSeen(int entityId, String controller, long sequence, EventOccurrence occurrence) {
		String key = controller + '|' + sequence + '|' + occurrence.kind() + '|' + occurrence.loop() + '|' + occurrence.eventIndex();

		return this.recent.computeIfAbsent(entityId, id -> new RecentOccurrences(DEDUPE_CAPACITY)).firstTime(key);
	}

	@Nullable
	private ClientEventContext contextFor(Subject subject, long gameTime, String controller, Tracked tracked,
										  EventOccurrence occurrence) {
		ParsedEvent<?> parsed;
		EventAuthority authority;

		switch (occurrence.kind()) {
			case EVENT -> {
				parsed = tracked.timeline.events().get(occurrence.eventIndex()).parsed();
				authority = tracked.timeline.events().get(occurrence.eventIndex()).authority();
			}
			case LOOP -> {
				parsed = new ParsedEvent<>(StrutBuiltinEvents.LOOP, occurrence.loop());
				authority = StrutBuiltinEvents.LOOP.defaultAuthority();
			}
			default -> {
				parsed = new ParsedEvent<>(StrutBuiltinEvents.END, Unit.INSTANCE);
				authority = StrutBuiltinEvents.END.defaultAuthority();
			}
		}

		if (!authority.runsOnClient())
			return null;

		AnimationPlayback playback = tracked.playback;
		double exact = playback.playbackRate() != 0
				? playback.startGameTick() + (occurrence.tick() - playback.startOffsetTicks()) / playback.playbackRate()
				: playback.startGameTick();

		return new ClientEventContext(subject.entity(), subject.entityId(), subject.uuid(), controller, playback.sequence(),
				occurrence.loop(), occurrence.eventIndex(), Math.min(exact, gameTime), gameTime, parsed, authority,
				subject.participant());
	}

	private void run(Subject subject, ClientEventContext context) {
		StrutEventType<?> type = context.event().type();
		String marker = context.event().payload() instanceof String name && type == StrutBuiltinEvents.MARKER ? name : null;

		EventStats.recordClientDelivered();
		this.log.record(context.entityId(), type.id(), marker, context.gameTime());

		guarded(type, "handler", () -> type.runClientHandler(context, context.payload()));

		if (subject.entityHook() != null)
			guarded(type, "entity hook", () -> subject.entityHook().accept(context));

		for (ClientEventListener listener : this.listeners.forType(type.id())) {
			guarded(type, "listener", () -> listener.onEvent(context));
		}

		if (marker != null) {
			for (ClientEventListener listener : this.listeners.forMarker(marker)) {
				guarded(type, "marker listener", () -> listener.onEvent(context));
			}
		}
	}

	private static void guarded(StrutEventType<?> type, String what, Runnable action) {
		try {
			action.run();
		}
		catch (RuntimeException ex) {
			LOGGER.error("[Strut events] a client {} for {} threw", what, type.id(), ex);
		}
	}
}
