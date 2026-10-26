package com.sandymandy.strut.event.server;

import com.sandymandy.strut.event.type.StrutEventType;

/**
 * What a {@link StrutEventType} does on the server each time one of its events fires - the type's own
 * behaviour, as opposed to a {@link ServerEventListener}, which is somebody else reacting to it
 * <p>
 * Runs on the server thread, once per occurrence, after the sweep has worked out what was crossed.
 * Anything it does to animation state (starting or stopping a clip) takes effect on the next sweep, so
 * a handler can never recurse into the dispatch that called it
 *
 * @param <P> The type's payload
 */
@FunctionalInterface
public interface ServerEventHandler<P> {
	void handle(EventContext context, P payload);
}
