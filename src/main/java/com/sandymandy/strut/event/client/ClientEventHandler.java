package com.sandymandy.strut.event.client;

import com.sandymandy.strut.event.type.StrutEventType;

/**
 * What a {@link StrutEventType} does on each client every time one of its events fires - the type's
 * own behaviour, as opposed to a {@link ClientEventListener}, which is somebody else reacting to it
 * <p>
 * Runs on the client thread, once per occurrence, from the client's own mirrored timeline - so it
 * costs no packet and cannot disagree with the pose about timing. Fired from the client tick, not from
 * rendering, so it runs for an entity that is off screen or culled too
 *
 * @param <P> The type's payload
 */
@FunctionalInterface
public interface ClientEventHandler<P> {
	void handle(ClientEventContext context, P payload);
}
