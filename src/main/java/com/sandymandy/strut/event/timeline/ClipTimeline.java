package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.event.cursor.EventSchedule;
import com.sandymandy.strut.event.cursor.ScheduledEvent;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats.LoopMode;

import java.util.List;

/**
 * Everything the event system needs to know about one clip, and nothing else: its length, how it
 * ends, and its events
 * <p>
 * This is what a server holds <b>instead of</b> the clip. It has no bones, no keyframes, no meshes -
 * a few hundred bytes - because the server owns <i>decisions</i>, never poses. That is what makes it
 * possible to give the server a timeline from somewhere other than the client's resource pack: a data
 * pack, a mod jar, or Java code.
 * <p>
 * Pure - no Minecraft client types - and the same class the client builds from its own clip, so both
 * sides derive {@link #contentHash} the same way and can tell when their copies have drifted
 *
 * @param lengthTicks The clip's length in ticks
 * @param loopMode What happens when the clip ends
 * @param events In flattened authored order; {@code events.get(i).index() == i}
 * @param contentHash A hash of length, loop mode and every event's tick, type, authored payload and
 *                    delivery rules. Two timelines with equal hashes fire identically
 */
public record ClipTimeline(double lengthTicks, LoopMode loopMode, List<TimelineEvent> events, long contentHash) {
	public ClipTimeline {
		events = List.copyOf(events);
	}

	/**
	 * This timeline in the shape {@link com.sandymandy.strut.event.cursor.EventCursor} consumes
	 */
	public EventSchedule schedule() {
		return new EventSchedule(this.lengthTicks, this.loopMode, this.events.stream()
				.map(event -> new ScheduledEvent(event.index(), event.tick(), event.reverse().firesInReverse()))
				.toList());
	}

	/**
	 * Whether any event runs on the server. A clip with none costs the server dispatcher nothing
	 */
	public boolean hasServerEvents() {
		return this.events.stream().anyMatch(event -> event.authority().runsOnServer());
	}

	/**
	 * Whether any event runs on clients - the client dispatcher's version of {@link #hasServerEvents}
	 */
	public boolean hasClientEvents() {
		return this.events.stream().anyMatch(event -> event.authority().runsOnClient());
	}
}
