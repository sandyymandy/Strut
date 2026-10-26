package com.sandymandy.strut.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.event.binding.BindingSet;
import com.sandymandy.strut.event.binding.BindingSetParser;
import com.sandymandy.strut.event.binding.EventBindings;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads every event binding file from the client's resource packs whenever resources reload
 * <p>
 * A binding file lives at {@code assets/<namespace>/strut/event_bindings/<path>.json} and its set id is
 * {@code <namespace>:<path>}. It is a <b>resource pack</b> asset, so a player-made character can ship her own
 * voice beside her textures and sounds, and a pack that lists the same id overrides another - the normal
 * resource pack rules, with nothing to install on the server.
 */
public final class ClientBindingsReloader {
	private static final Logger LOGGER = LogManager.getLogger("Strut");
	private static final String DIRECTORY = "strut/event_bindings";
	private static final String EXTENSION = ".json";

	private ClientBindingsReloader() {}

	/**
	 * Called once from {@link com.sandymandy.strut.StrutClient#onInitializeClient()}
	 */
	public static void init() {
		ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public Identifier getFabricId() {
				return Identifier.of(StrutConstants.MODID, "event_bindings");
			}

			@Override
			public void reload(ResourceManager manager) {
				load(manager);
			}
		});
	}

	private static void load(ResourceManager manager) {
		Map<Identifier, BindingSet> loaded = new HashMap<>();

		for (Map.Entry<Identifier, Resource> entry : manager.findResources(DIRECTORY, id -> id.getPath().endsWith(EXTENSION)).entrySet()) {
			Identifier file = entry.getKey();
			String path = file.getPath().substring(DIRECTORY.length() + 1, file.getPath().length() - EXTENSION.length());
			Identifier setId = Identifier.of(file.getNamespace(), path);

			try (Reader reader = entry.getValue().getReader()) {
				JsonElement root = JsonParser.parseReader(reader);

				if (!root.isJsonObject()) {
					LOGGER.warn("[Strut events] {} is not a JSON object; skipped", file);

					continue;
				}

				JsonObject object = root.getAsJsonObject();

				loaded.put(setId, BindingSetParser.parse(setId, object, EventBindings.actions(), file.toString(),
						message -> LOGGER.warn("[Strut events] {}", message)));
			}
			catch (Exception ex) {
				LOGGER.warn("[Strut events] could not read {}: {}", file, ex.toString());
			}
		}

		EventBindings.sets().set(loaded);
		LOGGER.info("[Strut events] loaded {} event binding set(s)", loaded.size());
	}
}
