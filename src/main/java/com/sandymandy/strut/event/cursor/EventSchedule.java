package com.sandymandy.strut.event.cursor;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;

import java.util.Comparator;
import java.util.List;

/**
 * The events of one clip, ordered, together with the clip facts needed to unfold them over time
 * <p>
 * "Unfold" because a looping clip has the same events again every {@code length} ticks: event
 * {@code t} of loop {@code n} sits at {@code n * length + t} on the unbounded elapsed axis that
 * {@link com.sandymandy.strut.animation.authority.AnimationPlayback#elapsedTicks} produces. Working
 * on that axis, rather than on a folded clip tick, is what makes "which loops did this tick cross"
 * an arithmetic question instead of something a controller has to notice
 *
 * @param length The clip's length in ticks. A non-positive length has no time to fire anything in,
 *               so a schedule over one is inert
 * @param loopMode What happens at the clip's end. {@link LoopMode#LOOP} repeats; anything else plays
 *                 once, and reports the end as an event
 * @param events The clip's events. Sorted by time, then index, on construction. Times outside
 *               {@code [0, length]} are clamped, since an event cannot sit off the clip
 */
public record EventSchedule(double length, LoopMode loopMode, List<ScheduledEvent> events) {
	public EventSchedule {
		events = events.stream()
				.map(event -> new ScheduledEvent(event.index(), Math.clamp(event.tick(), 0, Math.max(length, 0)),
						event.fireInReverse()))
				.sorted(Comparator.comparingDouble(ScheduledEvent::tick).thenComparingInt(ScheduledEvent::index))
				.toList();
	}

	/**
	 * Whether this schedule can ever fire anything
	 */
	public boolean isInert() {
		return this.length <= 0;
	}

	public boolean loops() {
		return this.loopMode == LoopMode.LOOP;
	}
}
