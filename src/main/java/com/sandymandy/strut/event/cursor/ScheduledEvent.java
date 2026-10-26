package com.sandymandy.strut.event.cursor;

/**
 * One authored event in a clip, reduced to what deciding <i>when it fires</i> needs
 * <p>
 * Deliberately knows nothing about what the event <i>is</i> (its type, payload, authority) - that
 * belongs to the registry and the dispatcher. Keeping this to a time, an index and a reverse flag is
 * what lets {@link EventCursor} stay a pure function with no Minecraft types, testable on its own.
 *
 *
 * @param index Position in the clip's authored event list. Breaks ties between events at the same
 *              time (list order is authored order), and is part of an occurrence's exactly-once key
 * @param tick Where in the clip the event sits, in clip ticks
 * @param fireInReverse Whether the event fires when playback crosses it while running backwards
 */
public record ScheduledEvent(int index, double tick, boolean fireInReverse) {}
