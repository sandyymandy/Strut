package com.sandymandy.strut.event.binding;

import com.google.gson.JsonElement;
import com.sandymandy.strut.event.client.ClientEventContext;

/**
 * A mod's own thing a binding key can do, beyond playing sounds - a chat line, a camera shake, a particle
 * burst - registered with {@link EventBindings#registerAction}
 * <p>
 * The binding hands over the JSON value it was given, untouched, and the action decides what that means:
 * Strut knows nothing about the shape of a mod's data. Runs on the client, once per firing, with the same
 * {@link ClientEventContext} a client handler gets - including {@code isLocalPlayerParticipant()} and a
 * {@code random()} identical on every machine
 */
@FunctionalInterface
public interface BindingAction {
	void run(ClientEventContext context, JsonElement config);
}
