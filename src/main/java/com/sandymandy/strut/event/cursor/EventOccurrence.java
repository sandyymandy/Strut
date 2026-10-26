package com.sandymandy.strut.event.cursor;

/**
 * One firing of one event, or of one of the engine's own built-ins
 * <p>
 * {@code (loop, eventIndex)} within a playback sequence is the exactly-once key - the dispatcher
 * widens it with the entity and controller, and the wire and the client's dedupe reuse it. Built-ins
 * carry an {@code eventIndex} of {@link #NO_EVENT} and are identified by {@link #kind} plus
 * {@code loop}
 *
 * @param kind Whether this is an authored event or a built-in
 * @param loop Which repetition of the clip this belongs to, counting from 0 at elapsed tick 0. For
 *             {@link Kind#LOOP} it is the loop being entered. Negative only for a reversed loop
 *             that has wrapped below its own start
 * @param eventIndex The authored event's index, or {@link #NO_EVENT}
 * @param tick Where this sits on the unbounded elapsed axis
 * @param clipTick Where this sits within the clip (0 to length). Differs from {@link #tick} only on
 *                 a repeat, and is what a handler that wants "the time the author placed it at"
 *                 reads
 */
public record EventOccurrence(Kind kind, long loop, int eventIndex, double tick, double clipTick) {
	public static final int NO_EVENT = -1;

	public enum Kind {
		/** An authored event from the clip */
		EVENT,
		/** The engine's {@code strut:loop}: a looping clip wrapped */
		LOOP,
		/** The engine's {@code strut:end}: a non-looping clip reached its end */
		END
	}
}
