package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.event.type.EventTypeRegistry;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats;
import java.util.LinkedHashMap;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.util.Map;

/**
 * Timelines read from {@code data/<namespace>/animations/**}{@code /*.strutanim} in the server's own
 * data packs
 * <p>
 * This is the path for content players add: a custom character's data pack carries the animation
 * file beside its definition, so the server has the timeline even though the character's textures,
 * sounds and geometry live only in a client resource pack. The path mirrors the asset layout, so the
 * exact file exported for the resource pack drops into {@code data/} unchanged and gets the same id.
 * Reloads with {@code /reload}.
 */
public final class DataPackTimelineSource extends MapTimelineSource {
	private final EventTypeRegistry registry;

	public DataPackTimelineSource(EventTypeRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void reload(ResourceManager serverData) {
		Map<Identifier, Map<String, ClipTimeline>> loaded = newFileMap();

		Map<Identifier, Resource> found = new LinkedHashMap<>();

		for (String folder : StrutBinaryFormats.ANIMATION_FOLDERS)
			found.putAll(serverData.findResources(folder, id -> id.getPath().endsWith(StrutBinaryFormats.ANIMATION_EXTENSION)));

		for (Map.Entry<Identifier, Resource> entry : found.entrySet()) {
			try {
				Map<String, ClipTimeline> clips = read(entry.getKey(), entry.getValue().getInputStream(), this.registry);

				if (!clips.isEmpty())
					loaded.put(entry.getKey(), clips);
			}
			catch (IOException ex) {
				LOGGER.warn("[Strut events] could not open {} for its event timeline: {}", entry.getKey(), ex.toString());
			}
		}

		replaceAll(loaded);
	}
}
