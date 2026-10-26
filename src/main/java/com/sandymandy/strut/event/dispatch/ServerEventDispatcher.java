package com.sandymandy.strut.event.dispatch;

import com.sandymandy.strut.animation.authority.AnimationPlayback;
import com.sandymandy.strut.animation.authority.ServerAnimationState;
import com.sandymandy.strut.animation.authority.ServerAnimationState.Change;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.server.EventContext;
import com.sandymandy.strut.event.type.ParsedEvent;
import com.sandymandy.strut.event.server.ServerEventListener;
import com.sandymandy.strut.event.type.StrutEventType;
import com.sandymandy.strut.event.StrutEvents;
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
 * The server half of typed events: once per tick per entity, works out which events each playing
 * clip crossed since the last tick and delivers them exactly once
 * <p>
 * All the timing arithmetic is {@link EventCursor}'s; this class is the bookkeeping around it - one
 * cursor per controller per playback sequence, told apart from a fresh start, a rate change or a seek
 * by {@link ServerAnimationState#lastChange} - plus delivery to handlers, listeners and the log. It
 * has no Minecraft world in it (an {@link Entity} is only passed through to handlers), so all of it is
 * tested without one.
 *
 * <h2>Reentrancy</h2>
 *
 * <p>Everything a sweep will deliver is decided <i>before</i> any handler runs, and a handler that
 * plays, stops or seeks a clip only changes state the <i>next</i> sweep reads. So dispatch cannot
 * recurse and a handler cannot lengthen the sweep that called it - the bound a queue would give,
 * met structurally rather than with a queue
 *
 * <h2>What a replacement flushes</h2>
 *
 * <p>When a clip is replaced, stopped or seeked quietly between sweeps, the old clip's events up to the
 * instant of the change still fire first: otherwise a change landing partway through a tick would
 * silently drop that part-tick of events. Events after the change never fire
 */
public final class ServerEventDispatcher {
	private static final Logger LOGGER = LogManager.getLogger("Strut");

	private final ServerEventListeners listeners;
	private final EventLog log;
	private final Int2ObjectMap<Map<String, Tracked>> tracked = new Int2ObjectOpenHashMap<>();
	/** Per entity, per controller: the highest change sequence already replayed */
	private final Int2ObjectMap<Map<String, Long>> seen = new Int2ObjectOpenHashMap<>();

	public ServerEventDispatcher(ServerEventListeners listeners, EventLog log) {
		this.listeners = listeners;
		this.log = log;
	}

	/**
	 * Who a sweep is for
	 *
	 * @param entity The entity, passed to handlers; null only in tests
	 * @param entityHook Called after a type's own handler and before listeners - the entity's own
	 *                   {@code onAnimationEvent}. Null if the entity has none
	 */
	public record Subject(int entityId, UUID uuid, @Nullable Entity entity, @Nullable Consumer<EventContext> entityHook) {}

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
	 * Runs one tick's dispatch for one entity
	 * <p>
	 * Replays every change made to each controller since the last sweep, in order, then advances the playback
	 * that is current now. Replaying rather than looking only at the current playback is what makes the result
	 * independent of how the server ticked: a server that stalled across a play and a rate change fires the
	 * first playback's events under the first playback, exactly as one that swept every tick would
	 *
	 * @param gameTime The current {@code World#getTime()}
	 */
	public void sweep(Subject subject, ServerAnimationState state, ClipTimelineLookup lookup, long gameTime) {
		Map<String, Tracked> mine = this.tracked.computeIfAbsent(subject.entityId(), id -> new LinkedHashMap<>());

		// Names first, copied: handlers change the state while this runs
		Set<String> controllers = new LinkedHashSet<>(state.controllersWithHistory());

		controllers.addAll(mine.keySet());

		for (String controller : controllers) {
			sweepController(subject, state, lookup, gameTime, controller, mine);
		}

		if (mine.isEmpty())
			this.tracked.remove(subject.entityId());
	}

	/**
	 * Drops everything remembered about an entity - when it unloads
	 */
	public void forget(int entityId) {
		this.tracked.remove(entityId);
		this.seen.remove(entityId);
	}

	private void sweepController(Subject subject, ServerAnimationState state, ClipTimelineLookup lookup, long gameTime, String controller,
								 Map<String, Tracked> mine) {
		Map<String, Long> seenHere = this.seen.computeIfAbsent(subject.entityId(), id -> new HashMap<>());
		Tracked current = mine.get(controller);
		long after = seenHere.getOrDefault(controller, 0L);

		for (ServerAnimationState.ChangeRecord record : state.changesAfter(controller, after)) {
			EventCursor carried = handleDeparture(subject, state, gameTime, controller, current, record);

			current = record.playback() != null ? begin(lookup, record, carried) : null;

			// A seek that fires what it crosses makes its jump at the instant of the change - not whenever the
			// next sweep happens to run. Otherwise a stalled server would sweep the jump and the forward motion
			// after it as one stretch, and a backward jump would swallow everything just past its landing point
			if (current != null && carried != null && record.kind() == ServerAnimationState.ChangeKind.SEEK_FIRE && current.schedule != null) {
				double landing = current.playback.elapsedTicks(current.playback.startGameTick(), 0);

				deliver(subject, state, gameTime, controller, current, current.cursor.advance(current.schedule, landing));
			}

			after = record.sequence();
		}

		seenHere.put(controller, after);

		if (current == null) {
			mine.remove(controller);

			return;
		}

		mine.put(controller, current);
		advance(subject, state, gameTime, controller, current);
	}

	/**
	 * The tracked playback {@code old} is replaced by, or ended by, {@code record}. Fires what it owes up to the
	 * change, and says whether its successor should inherit its cursor
	 * <p>
	 * Every kind of change flushes first, including a rate change: the old playback's events up to the instant of
	 * the change belong to the old playback, with its sequence and its clock. Skipping that for a continuous change
	 * would leave them to be swept by the new playback instead, which would attribute them to the wrong sequence
	 * and compute their exact times against the wrong speed
	 *
	 * @param old The playback being left, or null if the dispatcher never saw one
	 * @return The old cursor to carry over, or null for a fresh one
	 */
	@Nullable
	private EventCursor handleDeparture(Subject subject, ServerAnimationState state, long gameTime, String controller,
										@Nullable Tracked old, ServerAnimationState.ChangeRecord record) {
		if (old == null)
			return null;

		// A playback that lasted no game time at all was replaced before it was ever seen. It fires nothing:
		// the animation never ran, so an event on its very first instant never happened
		boolean lastedSomeTime = record.gameTime() > old.playback.startGameTick();

		if (record.gameTime() != Change.UNKNOWN_TIME && lastedSomeTime && old.schedule != null) {
			double until = old.playback.elapsedTicks(Math.min(record.gameTime(), gameTime), 0);

			// Never backwards: a handler that chained a clip onto an earlier marker leaves a change time behind
			// the cursor, and stepping back would fire the interval in reverse
			if (until > old.cursor.position())
				deliver(subject, state, gameTime, controller, old, old.cursor.advance(old.schedule, until));
		}

		// A rate change is the same clip on the same elapsed axis, and a seek that fires crossed events should
		// count the jump from where the old playback got to, so both carry the cursor on. Anything else starts fresh
		boolean continues = record.kind() == ServerAnimationState.ChangeKind.REBASE || record.kind() == ServerAnimationState.ChangeKind.SEEK_FIRE;

		return continues && record.playback() != null ? old.cursor.carryOver() : null;
	}

	private Tracked begin(ClipTimelineLookup lookup, ServerAnimationState.ChangeRecord record, @Nullable EventCursor carried) {
		AnimationPlayback playback = record.playback();
		Optional<ClipTimeline> timeline = lookup.find(playback.animation());
		double startElapsed = playback.elapsedTicks(playback.startGameTick(), 0);

		if (carried != null)
			return new Tracked(playback, timeline.orElse(null), carried);

		boolean quiet = record.kind() == ServerAnimationState.ChangeKind.SEEK_QUIET;

		return new Tracked(playback, timeline.orElse(null),
				quiet ? EventCursor.resumingAt(startElapsed) : EventCursor.startingAt(startElapsed));
	}

	private void advance(Subject subject, ServerAnimationState state, long gameTime, String controller, Tracked tracked) {
		ClipTimeline timeline = tracked.timeline;

		if (timeline == null)
			return;

		// A clip with nothing for the server to do costs nothing - unless somebody listens for the engine's
		// own loop/end events, which every clip raises
		if (!timeline.hasServerEvents() && !this.listeners.hasListenersFor(StrutBuiltinEvents.LOOP.id())
				&& !this.listeners.hasListenersFor(StrutBuiltinEvents.END.id()))
			return;

		EventCursor.Result result = tracked.cursor.advance(tracked.schedule, tracked.playback.elapsedTicks(gameTime, 0));

		if (result.truncated())
			EventStats.recordTruncatedSweep();

		if (result.truncated() && !tracked.truncationLogged) {
			tracked.truncationLogged = true;

			LOGGER.warn("[Strut events] clip \"{}\" on entity {} crossed more than {} events in one tick; the excess was dropped. "
					+ "Is its playback rate or loop length extreme?", tracked.playback.animation(), subject.entityId(),
					EventCursor.DEFAULT_MAX_OCCURRENCES);
		}

		deliver(subject, state, gameTime, controller, tracked, result);
	}

	private void deliver(Subject subject, ServerAnimationState state, long gameTime, String controller, Tracked tracked,
						 EventCursor.Result result) {
		if (result.isEmpty() || tracked.timeline == null)
			return;

		// Decide everything first, run handlers second, so a handler cannot change what this sweep delivers
		List<EventContext> contexts = new ArrayList<>(result.occurrences().size());

		for (EventOccurrence occurrence : result.occurrences()) {
			EventContext context = contextFor(subject, state, gameTime, controller, tracked, occurrence);

			if (context != null)
				contexts.add(context);
		}

		for (EventContext context : contexts) {
			run(subject, context);
		}
	}

	@Nullable
	private EventContext contextFor(Subject subject, ServerAnimationState state, long gameTime, String controller, Tracked tracked,
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

		if (!authority.runsOnServer())
			return null;

		AnimationPlayback playback = tracked.playback;
		double exact = playback.playbackRate() != 0
				? playback.startGameTick() + (occurrence.tick() - playback.startOffsetTicks()) / playback.playbackRate()
				: playback.startGameTick();

		return new EventContext(subject.entity(), subject.entityId(), subject.uuid(), controller, playback.sequence(),
				occurrence.loop(), occurrence.eventIndex(), Math.min(exact, gameTime), gameTime, parsed, authority, state);
	}

	private void run(Subject subject, EventContext context) {
		StrutEventType<?> type = context.event().type();
		String marker = context.event().payload() instanceof String name && type == StrutBuiltinEvents.MARKER ? name : null;

		EventStats.recordServerDelivered();

		if (StrutEvents.trace().isTracing(context.entityId()))
			StrutEvents.trace().report(context.entityId(), describe(context));

		this.log.record(context.entityId(), type.id(), marker, context.gameTime());

		guarded(type, "handler", () -> type.runServerHandler(context, context.payload()));

		if (subject.entityHook() != null)
			guarded(type, "entity hook", () -> subject.entityHook().accept(context));

		for (ServerEventListener listener : this.listeners.forType(type.id())) {
			guarded(type, "listener", () -> listener.onEvent(context));
		}

		if (marker != null) {
			for (ServerEventListener listener : this.listeners.forMarker(marker)) {
				guarded(type, "marker listener", () -> listener.onEvent(context));
			}
		}
	}

	/**
	 * One line describing a delivered event, for {@code /strut events trace}: enough to tell at a glance which
	 * clip, playback and loop it came from, what it was, and when it was authored versus delivered
	 */
	static String describe(EventContext context) {
		return String.format("#%d %s seq%d loop%d ev%d %s %s @%d (authored %.2f) [%s]", context.entityId(), context.controller(),
				context.sequence(), context.loop(), context.eventIndex(), context.type(), context.payload(), context.gameTime(),
				context.exactGameTime(), context.authority());
	}

	/**
	 * One failing handler must not stop the others, the rest of the sweep, or the tick
	 */
	private static void guarded(StrutEventType<?> type, String what, Runnable action) {
		try {
			action.run();
		}
		catch (RuntimeException ex) {
			LOGGER.error("[Strut events] a {} for {} threw", what, type.id(), ex);
		}
	}
}
