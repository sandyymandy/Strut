package com.sandymandy.strut.event.client;

import com.sandymandy.strut.event.StrutEvents;

/**
 * Somebody reacting to an event on the client: registered with {@link StrutEvents#onClient} for a
 * type, or {@link StrutEvents#onClientMarker} for a named marker
 * <p>
 * Called once per occurrence, in playback order, on the client thread. An exception thrown here is
 * logged and does not stop the other listeners or the rest of the sweep
 */
@FunctionalInterface
public interface ClientEventListener {
	void onEvent(ClientEventContext context);
}
