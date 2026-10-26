package com.sandymandy.strut.event.binding;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registered {@link BindingAction}s, by id
 * <p>
 * An instance so tests get a fresh one; the game's single instance sits behind {@link EventBindings}. Unlike
 * event types this is not frozen: binding files are read on a resource reload, long after every mod has
 * initialised, so a late registration cannot make an already-parsed file wrong in a way the parser's
 * "unknown action" warning would not already have said
 */
public final class EventBindingActions {
	private final Map<Identifier, BindingAction> actions = new ConcurrentHashMap<>();

	/**
	 * @throws IllegalArgumentException if the id has no namespace worth the name - built-in ids are reserved
	 * @throws IllegalStateException if the id is already registered
	 */
	public void register(Identifier id, BindingAction action) {
		if (id.getNamespace().equals("strut"))
			throw new IllegalArgumentException("the strut namespace is reserved for the engine's own actions: " + id);

		if (this.actions.putIfAbsent(id, action) != null)
			throw new IllegalStateException("binding action " + id + " is already registered");
	}

	@Nullable
	public BindingAction get(Identifier id) {
		return this.actions.get(id);
	}

	public boolean isRegistered(Identifier id) {
		return this.actions.containsKey(id);
	}
}
