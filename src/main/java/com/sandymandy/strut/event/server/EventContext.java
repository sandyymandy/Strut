package com.sandymandy.strut.event.server;

import com.sandymandy.strut.animation.authority.ServerAnimationState;
import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import com.sandymandy.strut.event.dispatch.EventRandom;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.ParsedEvent;

/**
 * Everything a handler or listener is told about one firing of an event on the server
 * <p>
 * The same firing is the same {@code (entity,
 * controller, sequence, loop, eventIndex)} on every machine, which is what makes {@link #random()}
 * agree everywhere
 */
public final class EventContext {
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
	private final ServerAnimationState animations;

	/**
	 * Built by the dispatcher. {@code entity} is null only in tests that have no world
	 */
	public EventContext(@Nullable Entity entity, int entityId, UUID entityUuid, String controller, long sequence, long loop,
						int eventIndex, double exactGameTime, long gameTime, ParsedEvent<?> event, EventAuthority authority,
						ServerAnimationState animations) {
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
		this.animations = animations;
	}

	/**
	 * The entity whose clip fired this
	 */
	public Entity entity() {
		return Objects.requireNonNull(this.entity, "this event context has no entity (a test context)");
	}

	/**
	 * {@link #entity()} as a subtype, for code that knows what it is listening on
	 *
	 * @throws ClassCastException naming the actual type, if it is not a {@code type}
	 */
	public <E extends Entity> E entity(Class<E> type) {
		return type.cast(entity());
	}

	public int entityId() {
		return this.entityId;
	}

	/**
	 * The controller whose clip fired this
	 */
	public String controller() {
		return this.controller;
	}

	/**
	 * The playback sequence this belongs to
	 */
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
	 * @throws ClassCastException if it is not one - the caller asked for the wrong type for this event
	 */
	public <P> P payload(Class<P> type) {
		return type.cast(this.event.payload());
	}

	/**
	 * Whether this is an event of the given type
	 */
	public boolean is(Identifier type) {
		return type().equals(type);
	}

	/**
	 * Whether this is a {@code strut:marker} with the given name. Names compare case-insensitively,
	 * because clips store them lower-cased
	 */
	public boolean isMarker(String name) {
		return this.event.payload() instanceof String marker
				&& type().equals(StrutBuiltinEvents.MARKER.id())
				&& marker.equals(name.toLowerCase(Locale.ROOT));
	}

	/**
	 * Where this event sits on the game clock, as a fraction of a tick if it fell between two
	 * <p>
	 * A sweep runs once per tick and reports everything crossed since the last one, so an event is
	 * usually delivered a little after the moment it was authored at. This is that authored moment.
	 * Hand it to {@link ServerAnimationState#playAt} to chain the next clip onto the marker itself with
	 * no lag; it is never later than {@link #gameTime()}
	 */
	public double exactGameTime() {
		return this.exactGameTime;
	}

	/**
	 * The game time of the sweep that delivered this
	 */
	public long gameTime() {
		return this.gameTime;
	}

	public EventAuthority authority() {
		return this.authority;
	}

	/**
	 * The entity's authoritative animation state, for starting, stopping or seeking clips in response.
	 * Changes take effect on the next sweep
	 */
	public ServerAnimationState animations() {
		return this.animations;
	}

	/**
	 * A random source that is <b>identical for this firing on every machine</b>: seeded from the
	 * entity's UUID, controller, sequence, loop and event index. So a client choosing which voice line
	 * to play and the server choosing what to do about it draw the same values, and a random pick can be
	 * reasoned about and reproduced. It is the same source however many times it is asked for; take one
	 * value per decision, in a fixed order
	 */
	public RandomGenerator random() {
		return EventRandom.forOccurrence(this.entityUuid, this.controller, this.sequence, this.loop, this.eventIndex);
	}
}
