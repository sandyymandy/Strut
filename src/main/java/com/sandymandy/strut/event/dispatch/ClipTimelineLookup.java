package com.sandymandy.strut.event.dispatch;

import com.sandymandy.strut.event.timeline.ClipTimeline;

import java.util.Optional;

/**
 * How the dispatcher finds a clip's timeline for one entity: given the clip name a playback carries,
 * the timeline - or nothing, if no source knows it
 * <p>
 * A playback names a clip but not the file it lives in, and the server has no model to ask, so the
 * caller decides how to map one to the other (see {@link ClipResolution}). The dispatcher only ever
 * asks by name
 */
@FunctionalInterface
public interface ClipTimelineLookup {
	Optional<ClipTimeline> find(String clip);
}
