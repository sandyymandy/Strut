package com.sandymandy.strut.event.client;

import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import com.sandymandy.strut.event.dispatch.EventRandom;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;
import com.sandymandy.strut.event.StrutEvents;
import com.sandymandy.strut.event.server.EventContext;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.ParsedEvent;

/**
 * Everything a client handler or listener is told about one firing of an event on a client
 * <p>
 * The client's counterpart of {@link EventContext}, with the same identity - the same {@code (entity,
 * controller, sequence, loop, eventIndex)} names the same firing on the server and on every client,
 * which is why {@link #random()} draws identical values everywhere. It has no animation state to
 * change: a client mirrors the server's playback and never decides one.
 */
public final class ClientEventContext {
	@Nullable
	private final Entity entity;
	private final int entityId;
	private final UUID entityUuid;
	private final String controller;
	private final long sequence;
	private final long loop;
	private final int eventIndex;
	private final double exactGameTime;
	private final long gameTime;
	private final ParsedEvent<?> event;
	private final EventAuthority authority;
	private final boolean localPlayerParticipant;

	/**
	 * Built by the client dispatcher. {@code entity} is null only in tests that have no world
	 */
	public ClientEventContext(@Nullable Entity entity, int entityId, UUID entityUuid, String controller, long sequence,
							  long loop, int eventIndex, double exactGameTime, long gameTime, ParsedEvent<?> event,
							  EventAuthority authority, boolean localPlayerParticipant) {
		this.entity = entity;
		this.entityId = entityId;
		this.entityUuid = entityUuid;
		this.controller = controller;
		this.sequence = sequence;
		this.loop = loop;
		this.eventIndex = eventIndex;
		this.exactGameTime = exactGameTime;
		this.gameTime = gameTime;
		this.event = event;
		this.authority = authority;
		this.localPlayerParticipant = localPlayerParticipant;
	}

	/**
	 * The entity whose clip fired this
	 */
	public Entity entity() {
		return Objects.requireNonNull(this.entity, "this event context has no entity (a test context)");
	}

	/**
	 * {@link #entity()} as a subtype
	 *
	 * @throws ClassCastException naming the actual type, if it is not a {@code type}
	 */
	public <E extends Entity> E entity(Class<E> type) {
		return type.cast(entity());
	}

	public int entityId() {
		return this.entityId;
	}

	public String controller() {
		return this.controller;
	}

	public long sequence() {
		return this.sequence;
	}

	/**
	 * Which repetition of the clip this is, from 0
	 */
	public long loop() {
		return this.loop;
	}

	public int eventIndex() {
		return this.eventIndex;
	}

	public Identifier type() {
		return this.event.type().id();
	}

	public ParsedEvent<?> event() {
		return this.event;
	}

	public Object payload() {
		return this.event.payload();
	}

	/**
	 * The payload as a {@code P}
	 *
	 * @throws ClassCastException if it is not one
	 */
	public <P> P payload(Class<P> type) {
		return type.cast(this.event.payload());
	}

	public boolean is(Identifier type) {
		return type().equals(type);
	}

	/**
	 * Whether this is a {@code strut:marker} with the given name, compared case-insensitively
	 */
	public boolean isMarker(String name) {
		return this.event.payload() instanceof String marker
				&& type().equals(StrutBuiltinEvents.MARKER.id())
				&& marker.equals(name.toLowerCase(Locale.ROOT));
	}

	/**
	 * Whether the local player is taking part in what this entity is doing: it is the player, is riding
	 * it, or is carried by it - plus anything a mod added through {@link StrutEvents#addParticipantRule}
	 * <p>
	 * For cues only the person involved should see - a fade to black, an overlay - as opposed to
	 * everyone who happens to be watching. Decided when the event is delivered
	 */
	public boolean isLocalPlayerParticipant() {
		return this.localPlayerParticipant;
	}

	/**
	 * Where this event sits on the game clock, as a fraction of a tick if it fell between two - the
	 * moment it was authored at, which is never later than {@link #gameTime()}
	 */
	public double exactGameTime() {
		return this.exactGameTime;
	}

	/**
	 * The game time of the client tick that delivered this
	 */
	public long gameTime() {
		return this.gameTime;
	}

	public EventAuthority authority() {
		return this.authority;
	}

	/**
	 * A random source identical to the server's {@link EventContext#random()} for this same firing, and
	 * to every other client's: the same voice line, the same particle spread, everywhere
	 */
	public RandomGenerator random() {
		return EventRandom.forOccurrence(this.entityUuid, this.controller, this.sequence, this.loop, this.eventIndex);
	}
}
