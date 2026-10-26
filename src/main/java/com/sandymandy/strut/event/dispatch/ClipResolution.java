package com.sandymandy.strut.event.dispatch;

import com.sandymandy.strut.animatable.StrutEntity;
import com.sandymandy.strut.event.timeline.ClipTimeline;
import com.sandymandy.strut.event.timeline.ClipTimelines;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * Maps the clip name in a playback to its timeline, when the server has no model to say which file
 * the clip lives in
 * <p>
 * In order: the files the animatable declared it uses
 * ({@code StrutEntity#getAnimationFiles()}), then a lookup by name across every source that succeeds
 * only if exactly one file has it, then a one-time warning. A mod whose clip names are unique - as
 * fully qualified ones are - declares nothing and still works.
 */
public final class ClipResolution {
	private ClipResolution() {}

	/**
	 * The timeline of {@code clip} for {@code entity}, resolved the way the server's event dispatcher resolves it: through the entity's
	 * {@code getAnimationFiles()}, then by name if exactly one file has it, then a one-time warning. Use it for a clip's length
	 * ({@code .lengthTicks()}) instead of repeating the rule
	 */
	public static Optional<ClipTimeline> resolve(StrutEntity entity, String clip) {
		return resolve(ClipTimelines.instance(), entity.getAnimationFiles(), clip);
	}

	/**
	 * @param declaredFiles Files the animatable says its clips come from, in preference order; may be empty
	 */
	public static Optional<ClipTimeline> resolve(ClipTimelines timelines, List<Identifier> declaredFiles, String clip) {
		for (Identifier file : declaredFiles) {
			Optional<ClipTimeline> found = timelines.find(file, clip);

			if (found.isPresent())
				return found;
		}

		Optional<ClipTimeline> unique = timelines.findUnique(clip);

		if (unique.isPresent())
			return unique;

		// findUnique already said so if the problem was ambiguity; only a genuinely absent clip warns here
		if (!declaredFiles.isEmpty()) {
			timelines.warnMissingOnce(declaredFiles.get(0), clip);
		}
		else {
			timelines.warnMissingByNameOnce(clip);
		}

		return Optional.empty();
	}
}
