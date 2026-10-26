package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.ParsedEvent;
import com.sandymandy.strut.event.type.ReversePolicy;

/**
 * One event in a {@link ClipTimeline}: what fires, when, and with which delivery rules already
 * resolved
 * <p>
 * An authored keyframe carrying several instruction lines becomes several of these, sharing a
 * {@code tick} and numbered in line order - {@code index} is the flattened position, and is the
 * event half of an occurrence's exactly-once key
 *
 * @param index Position in the timeline's event list. Also breaks ties between events at one tick
 * @param tick Where in the clip this fires, in clip ticks
 * @param parsed The typed event and its parsed payload
 * @param payloadText The payload as authored, kept so the timeline's content hash covers exactly what
 *                    the clip said, not how a particular build happens to parse it
 * @param authority Where it runs: the clip entry's override if it had one, otherwise the type's default
 * @param reverse Whether it fires when playback crosses it backwards
 */
public record TimelineEvent(int index, double tick, ParsedEvent<?> parsed, String payloadText,
							EventAuthority authority, ReversePolicy reverse) {}
