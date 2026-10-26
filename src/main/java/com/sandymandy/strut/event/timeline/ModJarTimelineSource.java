package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.event.type.EventTypeRegistry;
import com.sandymandy.strut.loading.binary.StrutBinaryFormats;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Timelines read from {@code assets/<namespace>/strut/animations/**} and {@code assets/<namespace>/animations/**}{@code /*.strutanim} inside installed
 * mods' own jars
 * <p>
 * For built-in content: a mod author keeps one copy of an animation, in {@code assets/} where the
 * client needs it, and the server reads it from the same jar. It reads the mod's files directly, not
 * through resource packs - a client's pack never reaches the server, and a server has no
 * {@code assets} pack of its own. A data pack (see {@link DataPackTimelineSource}) outranks it, so
 * a server owner can override a mod's timelines.
 * <p>
 * Takes the asset roots as a supplier rather than asking the mod loader itself, so the class works
 * without one; {@code ClipTimelinesFabric} supplies the loaded mods' roots
 */
public final class ModJarTimelineSource extends MapTimelineSource {
	private final Supplier<List<Path>> assetRoots;
	private final EventTypeRegistry registry;

	/**
	 * @param assetRoots The {@code assets} directory of each mod to read, resolved when reloading
	 */
	public ModJarTimelineSource(Supplier<List<Path>> assetRoots, EventTypeRegistry registry) {
		this.assetRoots = assetRoots;
		this.registry = registry;
	}

	@Override
	public void reload(ResourceManager serverData) {
		load();
	}

	/**
	 * Rebuilds from the asset roots. Also callable directly - a jar's contents do not change with a
	 * data reload, but tests and tools want to trigger it without a resource manager
	 */
	public void load() {
		Map<Identifier, Map<String, ClipTimeline>> loaded = newFileMap();

		for (Path assets : this.assetRoots.get()) {
			readAssets(assets, loaded);
		}

		replaceAll(loaded);
	}

	private void readAssets(Path assets, Map<Identifier, Map<String, ClipTimeline>> loaded) {
		if (!Files.isDirectory(assets))
			return;

		try (Stream<Path> namespaces = Files.list(assets)) {
			for (Path namespace : (Iterable<Path>)namespaces.filter(Files::isDirectory)::iterator) {
				readNamespace(namespace, loaded);
			}
		}
		catch (IOException ex) {
			LOGGER.warn("[Strut events] could not list {} for event timelines: {}", assets, ex.toString());
		}
	}

	private void readNamespace(Path namespace, Map<Identifier, Map<String, ClipTimeline>> loaded) {
		for (String folder : StrutBinaryFormats.ANIMATION_FOLDERS)
			readFolder(namespace, namespace.resolve(folder), loaded);
	}

	private void readFolder(Path namespace, Path animations, Map<Identifier, Map<String, ClipTimeline>> loaded) {
		if (!Files.isDirectory(animations))
			return;

		String namespaceName = namespace.getFileName().toString();

		try (Stream<Path> walk = Files.walk(animations)) {
			for (Path file : (Iterable<Path>)walk.filter(Files::isRegularFile)::iterator) {
				if (!file.getFileName().toString().endsWith(StrutBinaryFormats.ANIMATION_EXTENSION))
					continue;

				// The id a resource manager would give the same file: namespace, then the path from the
				// namespace directory down, with '/' separators regardless of platform
				String path = namespace.relativize(file).toString().replace('\\', '/');
				Identifier id = Identifier.tryParse(namespaceName + ":" + path);

				if (id == null) {
					LOGGER.warn("[Strut events] skipping {}: not a valid resource id", file);

					continue;
				}

				try (InputStream stream = Files.newInputStream(file)) {
					Map<String, ClipTimeline> clips = read(id, stream, this.registry);

					if (!clips.isEmpty())
						loaded.put(id, clips);
				}
			}
		}
		catch (IOException ex) {
			LOGGER.warn("[Strut events] could not read event timelines under {}: {}", animations, ex.toString());
		}
	}
}
