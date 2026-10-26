package com.sandymandy.strut.event;

import com.sandymandy.strut.event.dispatch.ClientEventListeners;
import com.sandymandy.strut.event.dispatch.EventLog;
import com.sandymandy.strut.event.dispatch.EventTrace;
import com.sandymandy.strut.event.dispatch.EventParticipants;
import com.sandymandy.strut.event.timeline.MarkerRequirements;
import com.sandymandy.strut.event.dispatch.ServerEventListeners;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.BiPredicate;
import com.sandymandy.strut.event.client.ClientEventContext;
import com.sandymandy.strut.event.client.ClientEventListener;
import com.sandymandy.strut.event.server.ServerEventListener;
import com.sandymandy.strut.event.type.StrutEventType;

/**
 * The public entry point for reacting to animation events on the server, and for asking what just
 * fired
 * <p>
 * <b>Listening</b> - {@link #onServer} for a registered event type, {@link #onServerMarker} for a
 * named marker - is for code that does not own the event: a gameplay system reacting to a marker any
 * clip may carry. Code that <i>owns</i> an event type puts its behaviour on the type
 * ({@link StrutEventType.Builder#serverHandler}); code that owns an entity overrides
 * {@code StrutEntity#onAnimationEvent}
 * <p>
 * <b>Polling</b> - {@link #firedLastTick} - is for tick code that wants an edge rather than a
 * callback. It is the replacement for keeping your own event queue: true for exactly one tick of
 * polling per occurrence, so nothing needs its own "did I already see this" flag
 * <p>
 * The server-side methods fire exactly once per occurrence; the client-side ones ({@code onClient}, {@code onClientMarker},
 * {@code clientFiredLastTick}) fire once per occurrence per client, from that client's own timeline.
 */
public final class StrutEvents {
	private static final ServerEventListeners SERVER_LISTENERS = new ServerEventListeners();
	private static final EventLog LOG = new EventLog();
	private static final ClientEventListeners CLIENT_LISTENERS = new ClientEventListeners();
	private static final EventLog CLIENT_LOG = new EventLog();
	private static final EventParticipants PARTICIPANTS = new EventParticipants();
	private static final EventTrace TRACE = new EventTrace();
	private static final MarkerRequirements MARKER_REQUIREMENTS = new MarkerRequirements();

	private StrutEvents() {}

	/**
	 * Calls {@code listener} on the server each time an event of {@code type} fires, on any entity
	 */
	public static void onServer(Identifier type, ServerEventListener listener) {
		SERVER_LISTENERS.add(type, listener);
	}

	/**
	 * Calls {@code listener} on the server each time a {@code strut:marker} named {@code marker} fires,
	 * on any entity. The name is compared case-insensitively
	 */
	public static void onServerMarker(String marker, ServerEventListener listener) {
		SERVER_LISTENERS.addMarker(marker, listener);
	}

	/**
	 * Whether an event of {@code type} fired on {@code entity} in the previous tick's sweep or this
	 * tick's. Polled once per tick from the entity's tick code, this is true for exactly one tick per
	 * occurrence
	 */
	public static boolean firedLastTick(Entity entity, Identifier type) {
		return LOG.firedLastTick(entity.getId(), type, entity.getWorld().getTime());
	}

	/**
	 * {@link #firedLastTick} for a named {@code strut:marker}
	 */
	public static boolean markerFiredLastTick(Entity entity, String marker) {
		return LOG.firedMarkerLastTick(entity.getId(), marker, entity.getWorld().getTime());
	}

	/**
	 * Whether an event of {@code type} fired on {@code entity} at a sweep at or after {@code sinceGameTime}.
	 * Remembers the last {@value EventLog#CAPACITY} occurrences per entity
	 */
	public static boolean firedSince(Entity entity, Identifier type, long sinceGameTime) {
		return LOG.firedSince(entity.getId(), type, sinceGameTime);
	}

	public static boolean markerFiredSince(Entity entity, String marker, long sinceGameTime) {
		return LOG.firedMarkerSince(entity.getId(), marker, sinceGameTime);
	}

	/**
	 * Calls {@code listener} on each client every time an event of {@code type} fires on an entity that
	 * client is tracking, from that client's own timeline. Runs from the client tick, so it runs for
	 * entities that are off screen too
	 */
	public static void onClient(Identifier type, ClientEventListener listener) {
		CLIENT_LISTENERS.add(type, listener);
	}

	/**
	 * Calls {@code listener} on each client every time a {@code strut:marker} named {@code marker} fires.
	 * The name is compared case-insensitively
	 */
	public static void onClientMarker(String marker, ClientEventListener listener) {
		CLIENT_LISTENERS.addMarker(marker, listener);
	}

	/**
	 * {@link #firedLastTick} on the client: whether an event of {@code type} fired on {@code entity} in
	 * the previous client tick's sweep or this one's. True for exactly one tick of polling per occurrence
	 */
	public static boolean clientFiredLastTick(Entity entity, Identifier type) {
		return CLIENT_LOG.firedLastTick(entity.getId(), type, entity.getWorld().getTime());
	}

	/**
	 * {@link #clientFiredLastTick} for a named {@code strut:marker}
	 */
	public static boolean clientMarkerFiredLastTick(Entity entity, String marker) {
		return CLIENT_LOG.firedMarkerLastTick(entity.getId(), marker, entity.getWorld().getTime());
	}

	/**
	 * Adds a rule for {@link ClientEventContext#isLocalPlayerParticipant()}: return true if the local
	 * player is taking part in what {@code entity} is doing. Strut already counts the player itself and
	 * anything it rides or carries; add a rule when your gameplay involves the player some other way
	 */
	public static void addParticipantRule(BiPredicate<Entity, PlayerEntity> rule) {
		PARTICIPANTS.addRule(rule);
	}

	/**
	 * Declares that {@code clip} must contain each of {@code markers}, checked whenever timelines load - on
	 * the server and on each client - with a warning naming exactly what is missing
	 * <p>
	 * Use it for the markers your logic waits on, in place of a runtime timeout that only notices a renamed or
	 * missing marker mid-scene:
	 * <pre>{@code
	 * StrutEvents.requireMarkers("animation.bow.slow", "swap", "swap_end");
	 * }</pre>
	 *
	 * @param clip The clip's name, as a playback names it
	 * @param markers Marker names; compared case-insensitively
	 */
	public static void requireMarkers(String clip, String... markers) {
		MARKER_REQUIREMENTS.require(clip, markers);
	}

	/**
	 * Strut's own dispatcher reads these; mods have no use for them
	 */
	@ApiStatus.Internal
	public static MarkerRequirements markerRequirements() {
		return MARKER_REQUIREMENTS;
	}

	@ApiStatus.Internal
	public static EventTrace trace() {
		return TRACE;
	}

	@ApiStatus.Internal
	public static ServerEventListeners serverListeners() {
		return SERVER_LISTENERS;
	}

	@ApiStatus.Internal
	public static EventLog log() {
		return LOG;
	}

	@ApiStatus.Internal
	public static ClientEventListeners clientListeners() {
		return CLIENT_LISTENERS;
	}

	@ApiStatus.Internal
	public static EventLog clientLog() {
		return CLIENT_LOG;
	}

	@ApiStatus.Internal
	public static EventParticipants participants() {
		return PARTICIPANTS;
	}
}
