package com.sandymandy.strut.event.timeline;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.event.type.StrutEventTypes;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires {@link ClipTimelines} to Fabric: creates the game's instance and rebuilds it whenever the
 * server's data reloads
 * <p>
 * Kept apart from {@link ClipTimelines} so that class - and every source - stays loadable and
 * testable without a mod loader. Both entry points here are common-side: a
 * {@link ResourceType#SERVER_DATA} listener fires on a dedicated server and on an integrated one, and
 * never touches a client class
 */
public final class ClipTimelinesFabric {
	private ClipTimelinesFabric() {}

	/**
	 * Called once from {@link com.sandymandy.strut.Strut#onInitialize()}
	 */
	public static void init() {
		ClipTimelines timelines = ClipTimelines.create(StrutEventTypes.registry(), ClipTimelinesFabric::loadedModAssetRoots);

		ClipTimelines.setInstance(timelines);

		ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public Identifier getFabricId() {
				return Identifier.of(StrutConstants.MODID, "clip_timelines");
			}

			@Override
			public void reload(ResourceManager manager) {
				timelines.reload(manager);
			}
		});
	}

	/**
	 * The {@code assets} directory inside every loaded mod that has one
	 */
	private static List<Path> loadedModAssetRoots() {
		List<Path> roots = new ArrayList<>();

		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			for (Path root : mod.getRootPaths()) {
				roots.add(root.resolve("assets"));
			}
		}

		return roots;
	}
}
