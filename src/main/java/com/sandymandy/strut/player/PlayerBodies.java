package com.sandymandy.strut.player;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every registered {@link PlayerBody}, by id
 * <p>
 * Register from a mod initialiser on both the server and the client (the common entrypoint runs on both), before a world is joined. A player whose
 * body is not registered here (a mod that was removed, a body registered too late) is drawn as the ordinary player until it is
 */
public final class PlayerBodies {
	private static final Map<Identifier, PlayerBody> BODIES = new LinkedHashMap<>();

	private PlayerBodies() {}

	/**
	 * Registers {@code body} under its own id
	 *
	 * @throws IllegalArgumentException If a body with that id is already registered
	 */
	public static PlayerBody register(PlayerBody body) {
		if (BODIES.putIfAbsent(body.id(), body) != null)
			throw new IllegalArgumentException("a player body with the id " + body.id() + " is already registered");

		return body;
	}

	/**
	 * The body registered under {@code id}, or null
	 */
	@Nullable
	public static PlayerBody get(@Nullable Identifier id) {
		return id == null ? null : BODIES.get(id);
	}

	/**
	 * Every registered body, in the order they were registered
	 */
	public static Collection<PlayerBody> all() {
		return Collections.unmodifiableCollection(BODIES.values());
	}
}
