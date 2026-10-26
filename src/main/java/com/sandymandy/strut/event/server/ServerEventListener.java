package com.sandymandy.strut.event.server;

import com.sandymandy.strut.event.StrutEvents;

/**
 * Somebody reacting to an event on the server: registered with {@link StrutEvents#onServer} for a type,
 * or {@link StrutEvents#onServerMarker} for a named marker
 * <p>
 * Called once per occurrence, in playback order, on the server thread. An exception thrown here is
 * logged and does not stop the other listeners or the rest of the sweep
 */
@FunctionalInterface
public interface ServerEventListener {
	void onEvent(EventContext context);
}
