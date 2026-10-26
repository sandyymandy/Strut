package com.sandymandy.strut.event.timeline;

import net.minecraft.util.Identifier;

import java.util.Map;

/**
 * The client's timelines: built from the animation files the client's own resource packs loaded, at
 * the same moment {@code StrutCache} loads the clips themselves
 * <p>
 * The client fires its cosmetic events (sounds, particles, overlays) from <i>its</i> clip, not the
 * server's, so what a player hears follows the resource pack they are actually using. Built by the same
 * {@link ClipTimelineFactory} the server uses, so both sides derive an identical content hash for an
 * identical clip. Pure - it is only filled from outside, so it lives beside the other sources without
 * touching a client class.
 */
public final class ClientTimelineSource extends MapTimelineSource {
	/**
	 * Replaces every timeline, on a resource reload. Never merges: a clip removed from a pack must stop
	 * being found
	 */
	public void set(Map<Identifier, Map<String, ClipTimeline>> timelines) {
		replaceAll(timelines);
	}
}
