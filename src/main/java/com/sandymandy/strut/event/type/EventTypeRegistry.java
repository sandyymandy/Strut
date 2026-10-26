package com.sandymandy.strut.event.type;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A set of registered {@link StrutEventType}s, with the load-time resolution of clip text against it
 * <p>
 * An instance rather than static state so tests get a fresh one; the game uses the single global
 * instance behind {@link StrutEventTypes}.
 *
 * <h2>Freezing</h2>
 *
 * <p>Types register during mod initialisation and the registry is frozen before any world or clip
 * loads, so a clip parsed at load time always sees the complete set - a type registered late would
 * make an already-parsed clip's "unknown type" warning wrong. Registering after the freeze is a loud
 * {@link IllegalStateException} naming the type, not a silent no-op
 */
public final class EventTypeRegistry {
	private final Map<Identifier, StrutEventType<?>> types = new LinkedHashMap<>();
	private boolean frozen;

	/**
	 * @throws IllegalStateException if the registry is frozen, or the id is already registered
	 */
	public <P> StrutEventType<P> register(StrutEventType<P> type) {
		if (this.frozen)
			throw new IllegalStateException("cannot register event type " + type.id()
					+ ": the registry is frozen. Register event types during mod initialisation");

		if (this.types.putIfAbsent(type.id(), type) != null)
			throw new IllegalStateException("event type " + type.id() + " is already registered");

		return type;
	}

	/**
	 * Ends registration. Idempotent
	 */
	public void freeze() {
		this.frozen = true;
	}

	public boolean isFrozen() {
		return this.frozen;
	}

	@Nullable
	public StrutEventType<?> get(Identifier id) {
		return this.types.get(id);
	}

	public Collection<StrutEventType<?>> all() {
		return Collections.unmodifiableCollection(this.types.values());
	}

	/**
	 * The result of resolving one instruction line: either a typed event, or why not
	 *
	 * @param event The typed event, or null
	 * @param error Why the line could not be resolved, worded for the author, or null
	 */
	public record Resolution(@Nullable ParsedEvent<?> event, @Nullable String error) {
		public boolean isOk() {
			return this.event != null;
		}
	}

	/**
	 * Resolves one already-split line against the registered types: unknown id, a type that cannot be
	 * authored, and an unparsable payload are all a {@link Resolution} error rather than an exception,
	 * because a bad line in one clip must warn and be skipped, not stop a resource reload
	 */
	public Resolution resolve(EventLine.Line line) {
		if (line.error() != null)
			return new Resolution(null, line.error());

		StrutEventType<?> type = this.types.get(line.type());

		if (type == null)
			return new Resolution(null, "unknown event type " + line.type()
					+ " (is the mod that registers it installed, and did it register before the registry froze?)");

		return resolveAgainst(type, line.payload());
	}

	/**
	 * Splits and resolves a whole instruction keyframe's text
	 */
	public List<Resolution> resolveAll(String instructionText) {
		List<Resolution> resolutions = new ArrayList<>();

		for (EventLine.Line line : EventLine.parse(instructionText)) {
			resolutions.add(resolve(line));
		}

		return resolutions;
	}

	private static <P> Resolution resolveAgainst(StrutEventType<P> type, String payloadText) {
		try {
			return new Resolution(new ParsedEvent<>(type, type.parse(payloadText)), null);
		}
		catch (IllegalArgumentException ex) {
			return new Resolution(null, ex.getMessage());
		}
	}
}
