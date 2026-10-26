package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Markers a consumer's logic depends on a clip containing
 * <p>
 * The replacement for finding out at runtime. A consumer mod's side-swap cycle waits for {@code swap} and
 * {@code swap_end}, and guards against a renamed or missing marker with a ten-second timeout that logs
 * "no animation event detected" mid-scene. Declaring the requirement once
 * ({@link com.sandymandy.strut.event.StrutEvents#requireMarkers}) turns that into a warning when the clip
 * loads, naming exactly what is missing, before anyone is in the scene
 */
public final class MarkerRequirements {
	private final Map<String, Set<String>> required = new ConcurrentHashMap<>();

	/**
	 * Declares that {@code clip} must contain each of {@code markers}. Repeated calls add to the list
	 *
	 * @param clip The clip's name, as a playback names it
	 * @param markers Marker names; compared case-insensitively
	 */
	public void require(String clip, String... markers) {
		Set<String> names = this.required.computeIfAbsent(clip, name -> ConcurrentHashMap.newKeySet());

		for (String marker : markers) {
			names.add(marker.toLowerCase(Locale.ROOT));
		}
	}

	/**
	 * Every clip with a requirement, and the markers it must have
	 */
	public Map<String, Set<String>> all() {
		return this.required;
	}

	public boolean isEmpty() {
		return this.required.isEmpty();
	}

	/**
	 * Which of the markers required of {@code clip} the timeline does not contain, sorted
	 * <p>
	 * A marker counts only if it is a {@code strut:marker} event. An event of another type whose payload
	 * happens to read the same is not the marker the consumer is waiting for
	 */
	public List<String> missing(String clip, ClipTimeline timeline) {
		Set<String> wanted = this.required.get(clip);

		if (wanted == null)
			return List.of();

		Set<String> absent = new TreeSet<>(wanted);

		for (TimelineEvent event : timeline.events()) {
			if (event.parsed().type() == StrutBuiltinEvents.MARKER && event.parsed().payload() instanceof String name)
				absent.remove(name);
		}

		return new ArrayList<>(absent);
	}
}
