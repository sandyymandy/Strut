package com.sandymandy.strut.physics.config;

import com.sandymandy.strut.cache.StrutCache;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import java.util.List;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import com.sandymandy.strut.physics.StrutPhysics;
import com.sandymandy.strut.physics.collision.ModelSdfs;

/**
 * Finds the physics config of a baked model: the model file's own extension section, overlaid by a resource-pack sidecar at
 * {@code assets/<namespace>/strut/physics/<model path without extension>.json}. The sidecar of
 * {@code mymod:strut/models/bare/ava.strutmodel}, it is {@code strut/physics/models/bare/ava.json} (the model's path without its
 * leading {@code strut/}), and the full {@code strut/physics/strut/models/bare/ava.json} is read too
 * <p>
 * Answers are cached per model identity and dropped on a resource reload, because a reload builds new baked models
 */
public final class PhysicsConfigs implements SimpleSynchronousResourceReloadListener {
	static final Identifier ID = Identifier.of(StrutPhysics.MOD_ID, "configs");
	private static final Logger LOGGER = LogManager.getLogger("Strut Physics");
	private static final String FOLDER = "strut/physics";

	private static volatile Map<Identifier, PhysicsConfig> sidecars = Map.of();
	private static final Map<BakedStrutModel, Optional<PhysicsConfig>> RESOLVED = new IdentityHashMap<>();
	private static Map<BakedStrutModel, Identifier> stems = null;
	private static Map<Identifier, BakedStrutModel> stemsBuiltFrom = null;
	private static final Map<Identifier, PhysicsConfig> OVERRIDES = new HashMap<>();

	@Override
	public Identifier getFabricId() {
		return ID;
	}

	@Override
	public void reload(ResourceManager manager) {
		Map<Identifier, PhysicsConfig> found = new HashMap<>();

		for (Map.Entry<Identifier, Resource> entry : manager.findResources(FOLDER, id -> id.getPath().endsWith(".json")).entrySet()) {
			Identifier file = entry.getKey();
			String path = file.getPath().substring(FOLDER.length() + 1, file.getPath().length() - ".json".length());

			try {
				PhysicsConfig config = PhysicsConfig.parse(readWhole(entry.getValue()), file.toString());

				found.put(Identifier.of(file.getNamespace(), path), config);

				for (String warning : config.warnings())
					LOGGER.warn(warning);
			}
			catch (IOException | RuntimeException ex) {
				LOGGER.warn("Could not read physics config {}: {}", file, ex.toString());
			}
		}

		// a reload makes new models: the fields made for the old ones are dropped
		ModelSdfs.clear();

		synchronized (PhysicsConfigs.class) {
			sidecars = found;
			RESOLVED.clear();
			stems = null;
			stemsBuiltFrom = null;
		}

		LOGGER.info("Read {} physics sidecar file(s)", found.size());
	}

	private static String readWhole(Resource resource) throws IOException {
		try (var in = resource.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * The config for {@code model}, or null if it has none (the common case, remembered so the answer costs a map lookup)
	 */
	public static synchronized PhysicsConfig forModel(BakedStrutModel model) {
		Optional<PhysicsConfig> cached = RESOLVED.get(model);

		if (cached != null)
			return cached.orElse(null);

		PhysicsConfig config = resolve(model);

		RESOLVED.put(model, Optional.ofNullable(config));

		return config;
	}

	/**
	 * Replaces the config of the model with resource id {@code stem} (no extension) with {@code config}, until {@link #clearOverride}: what the in-game test
	 * scenarios use to switch a model's physics without touching any file
	 */
	public static synchronized void override(Identifier stem, PhysicsConfig config) {
		OVERRIDES.put(stem, config);
		RESOLVED.clear();
	}

	public static synchronized void clearOverride(Identifier stem) {
		OVERRIDES.remove(stem);
		RESOLVED.clear();
	}

	/** The model's stem the game knows it by, or null: for the tool to find which override to set */
	static synchronized Identifier stemFor(BakedStrutModel model) {
		return stemOf(model);
	}

	/** The stem of the first loaded model among {@code candidates}, or null */
	public static synchronized Identifier firstLoaded(List<Identifier> candidates) {
		Map<Identifier, BakedStrutModel> models = StrutCache.getBakedModels();

		for (Identifier candidate : candidates) {
			for (Identifier key : models.keySet()) {
				if (stem(key).equals(candidate))
					return candidate;
			}
		}

		return null;
	}

	private static PhysicsConfig resolve(BakedStrutModel model) {
		Identifier own = stemOf(model);

		if (own != null && OVERRIDES.containsKey(own)) {
			PhysicsConfig forced = OVERRIDES.get(own);

			return forced == null || forced.isEmpty() ? null : forced;
		}

		PhysicsConfig config = null;
		String name = String.valueOf(stemOf(model));

		if (StrutPhysics.hasPhysicsData(model)) {
			config = PhysicsConfig.parse(new String(model.extension(StrutPhysics.SECTION).orElseThrow(), StandardCharsets.UTF_8), name + " (model file)");

			for (String warning : config.warnings())
				LOGGER.warn(warning);
		}

		Identifier stem = stemOf(model);
		PhysicsConfig sidecar = stem == null ? null : sidecars.get(stem);

		// a model under strut/models is also found by its path without the leading strut/: strut/physics/models/bare/ava.json
		if (sidecar == null && stem != null && stem.getPath().startsWith("strut/"))
			sidecar = sidecars.get(Identifier.of(stem.getNamespace(), stem.getPath().substring("strut/".length())));

		if (sidecar != null)
			config = config == null ? sidecar : config.overlaid(sidecar);

		return config == null || config.isEmpty() ? null : config;
	}

	/** The resource id of a baked model without its extension, found by identity in the loaded models, or null if it is not a registered model */
	private static Identifier stemOf(BakedStrutModel model) {
		Map<Identifier, BakedStrutModel> models = StrutCache.getBakedModels();

		if (stems == null || stemsBuiltFrom != models) {
			Map<BakedStrutModel, Identifier> built = new IdentityHashMap<>();

			for (Map.Entry<Identifier, BakedStrutModel> entry : models.entrySet())
				built.put(entry.getValue(), stem(entry.getKey()));

			stems = built;
			stemsBuiltFrom = models;
		}

		return stems.get(model);
	}

	public static Identifier stem(Identifier model) {
		String path = model.getPath();

		for (String extension : new String[] {".strutmodel", ".geo.json"}) {
			if (path.endsWith(extension))
				return Identifier.of(model.getNamespace(), path.substring(0, path.length() - extension.length()));
		}

		return model;
	}
}
