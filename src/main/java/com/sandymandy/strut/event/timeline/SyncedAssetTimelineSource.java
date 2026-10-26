package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.ManifestEntry;
import com.sandymandy.strut.assetsync.server.ServerAssetStore;
import com.sandymandy.strut.event.type.EventTypeRegistry;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Timelines read from the assets the server syncs to clients ({@code data/<namespace>/asset_sync/animations/**})
 * <p>
 * This is what makes a synced clip's events work on the server with no second copy: the same {@code .strutanim} the
 * client receives is the one the server builds its timeline from. Fed from the {@link ServerAssetStore} (so it also sees
 * animations a mod adds from code), and registered above the plain data-pack source, since what clients are given is
 * what should fire
 */
public final class SyncedAssetTimelineSource extends MapTimelineSource {
	private final EventTypeRegistry registry;

	public SyncedAssetTimelineSource(EventTypeRegistry registry) {
		this.registry = registry;
	}

	/**
	 * Rebuilds from a freshly built store
	 */
	public void reload(ServerAssetStore store) {
		Map<Identifier, Map<String, ClipTimeline>> loaded = newFileMap();
		AssetManifest manifest = store.manifest();

		for (ManifestEntry entry : manifest.entries()) {
			if (!entry.path().endsWith(StrutBinaryFormats.ANIMATION_EXTENSION))
				continue;

			int slash = entry.path().indexOf('/');
			String path = entry.path().substring(slash + 1);

			if (!StrutBinaryFormats.isInAnimationFolder(path))
				continue;

			Identifier file = Identifier.of(entry.path().substring(0, slash), path);
			byte[] bytes;

			try {
				bytes = store.reader().read(entry, 0, (int)entry.size());
			}
			catch (IOException | RuntimeException ex) {
				LOGGER.warn("[Strut events] could not read synced {} for its event timeline: {}", file, ex.toString());

				continue;
			}

			Map<String, ClipTimeline> clips = read(file, new ByteArrayInputStream(bytes), this.registry);

			if (!clips.isEmpty())
				loaded.put(file, clips);
		}

		replaceAll(loaded);
	}
}
