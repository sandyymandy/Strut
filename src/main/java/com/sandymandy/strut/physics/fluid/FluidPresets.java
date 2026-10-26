package com.sandymandy.strut.physics.fluid;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.sandymandy.strut.physics.StrutPhysics;

/**
 * The liquids by id: the built-in thick one, those mods register in code, and those resource packs ship at
 * {@code assets/<namespace>/strut/fluids/<name>.json}. A file wins over a registration of the same id, so a pack can retune a mod's liquid
 */
public final class FluidPresets implements SimpleSynchronousResourceReloadListener {
	static final Identifier ID = Identifier.of(StrutPhysics.MOD_ID, "fluids");
	private static final Logger LOGGER = LogManager.getLogger("Strut Physics");
	private static final String FOLDER = "strut/fluids";

	private static final Map<Identifier, FluidParams> REGISTERED = new HashMap<>();
	private static volatile Map<Identifier, FluidParams> files = Map.of();
	private static final Set<Identifier> WARNED = new HashSet<>();

	static {
		REGISTERED.put(StrutFluids.THICK, FluidParams.THICK);
	}

	@Override
	public Identifier getFabricId() {
		return ID;
	}

	@Override
	public void reload(ResourceManager manager) {
		Map<Identifier, FluidParams> found = new HashMap<>();

		for (Map.Entry<Identifier, Resource> entry : manager.findResources(FOLDER, id -> id.getPath().endsWith(".json")).entrySet()) {
			Identifier file = entry.getKey();
			String name = file.getPath().substring(FOLDER.length() + 1, file.getPath().length() - ".json".length());

			try (InputStreamReader in = new InputStreamReader(entry.getValue().getInputStream(), StandardCharsets.UTF_8)) {
				JsonObject json = JsonParser.parseReader(in).getAsJsonObject();

				found.put(Identifier.of(file.getNamespace(), name), FluidParams.parse(json));
			}
			catch (IOException | RuntimeException ex) {
				LOGGER.warn("Could not read fluid preset {}: {}", file, ex.toString());
			}
		}

		synchronized (FluidPresets.class) {
			files = found;
			WARNED.clear();
		}

		LOGGER.info("Read {} fluid preset file(s)", found.size());
	}

	static synchronized void register(Identifier id, FluidParams params) {
		REGISTERED.put(id, params);
	}

	static synchronized FluidParams get(Identifier id) {
		FluidParams found = files.get(id);

		if (found == null)
			found = REGISTERED.get(id);

		if (found == null) {
			if (WARNED.add(id))
				LOGGER.warn("No fluid preset {}: using strut_physics:thick", id);

			return FluidParams.THICK;
		}

		return found;
	}
}
