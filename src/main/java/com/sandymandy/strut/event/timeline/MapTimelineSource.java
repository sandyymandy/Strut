package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.event.type.EventTypeRegistry;
import com.sandymandy.strut.loading.binary.BinaryAnimationReader;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shared shape of a source that loads whole files into memory: a map of file to clip to
 * timeline, swapped in atomically so a reload never shows a half-built map to the tick thread
 * <p>
 * Subclasses only say <i>where the bytes are</i>; reading, event conversion and per-file error
 * handling live here. A file that fails to read or parse is logged and skipped - one broken data pack
 * entry must not empty the whole source
 */
public abstract class MapTimelineSource implements ClipTimelineSource {
	protected static final Logger LOGGER = LogManager.getLogger("Strut");

	private volatile Map<Identifier, Map<String, ClipTimeline>> files = Map.of();

	@Override
	public Optional<ClipTimeline> find(Identifier file, String clip) {
		Map<String, ClipTimeline> clips = this.files.get(file);

		return clips == null ? Optional.empty() : Optional.ofNullable(clips.get(clip));
	}

	@Override
	public List<String> clipsOf(Identifier file) {
		Map<String, ClipTimeline> clips = this.files.get(file);

		// the map the factory builds keeps the file's own order
		return clips == null ? List.of() : List.copyOf(clips.keySet());
	}

	@Override
	public List<Identifier> filesContaining(String clip) {
		List<Identifier> found = new ArrayList<>();

		for (Map.Entry<Identifier, Map<String, ClipTimeline>> entry : this.files.entrySet()) {
			if (entry.getValue().containsKey(clip))
				found.add(entry.getKey());
		}

		return found;
	}

	/**
	 * How many files this source currently holds - for diagnostics and tests
	 */
	public int fileCount() {
		return this.files.size();
	}

	/**
	 * Replaces this source's contents
	 */
	protected void replaceAll(Map<Identifier, Map<String, ClipTimeline>> loaded) {
		this.files = Map.copyOf(loaded);
	}

	/**
	 * Reads one {@code .strutanim} into timelines, or logs and returns nothing if it cannot
	 */
	protected static Map<String, ClipTimeline> read(Identifier id, InputStream stream, EventTypeRegistry registry) {
		try (stream) {
			return ClipTimelineFactory.fromBinary(BinaryAnimationReader.read(stream, id.toString()), id.toString(), registry,
					message -> LOGGER.warn("[Strut events] {}", message));
		}
		catch (IOException | RuntimeException ex) {
			LOGGER.warn("[Strut events] could not read {} for its event timeline: {}", id, ex.toString());

			return Map.of();
		}
	}

	protected static Map<Identifier, Map<String, ClipTimeline>> newFileMap() {
		return new HashMap<>();
	}
}
