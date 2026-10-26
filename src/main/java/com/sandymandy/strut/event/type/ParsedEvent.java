package com.sandymandy.strut.event.type;

/**
 * An event type together with a payload already parsed for it - what a clip's instruction line
 * becomes once the registry has resolved it
 *
 * @param <P> The type's payload type
 */
public record ParsedEvent<P>(StrutEventType<P> type, P payload) {}
