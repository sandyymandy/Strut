package com.sandymandy.strut.event.timeline;

import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Timelines a mod registers from Java - the highest-priority source
 * <p>
 * For a mod that builds its events in code, or wants to pin a timeline that no pack may override.
 * Survives data reloads, since nothing about it comes from a pack
 */
public final class CodeTimelineSource implements ClipTimelineSource {
	private record Key(Identifier file, String clip) {}

	private final Map<Key, ClipTimeline> timelines = new ConcurrentHashMap<>();

	/**
	 * Registers (or replaces) the timeline for one clip
	 */
	public void register(Identifier file, String clip, ClipTimeline timeline) {
		this.timelines.put(new Key(file, clip), timeline);
	}

	@Override
	public Optional<ClipTimeline> find(Identifier file, String clip) {
		return Optional.ofNullable(this.timelines.get(new Key(file, clip)));
	}

	@Override
	public List<Identifier> filesContaining(String clip) {
		return this.timelines.keySet().stream().filter(key -> key.clip().equals(clip)).map(Key::file).toList();
	}
}
