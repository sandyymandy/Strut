package com.sandymandy.strut.physics.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Locale;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The player's physics options on disk ({@code config/strut-physics.json}): read once at start and written whenever an options screen changes them. The
 * running values live in {@link PhysicsOptions}; this only moves them to and from a file. A missing, damaged or partly filled file falls back to the defaults
 * key by key, and never stops the game
 */
public final class PhysicsSettings {
	private static final Logger LOGGER = LogManager.getLogger("Strut Physics");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private PhysicsSettings() {}

	/** The options as the JSON text of the file */
	public static String toJson() {
		JsonObject o = new JsonObject();

		o.addProperty("enabled", PhysicsOptions.enabled());
		o.addProperty("worldCollision", PhysicsOptions.worldCollision());
		o.addProperty("otherCollision", PhysicsOptions.otherCollision());
		o.addProperty("playerCollision", PhysicsOptions.playerCollision());
		o.addProperty("springWorldCollision", PhysicsOptions.springWorldCollision());
		o.addProperty("cloth", PhysicsOptions.cloth());
		o.addProperty("fluid", PhysicsOptions.fluid());
		o.addProperty("water", PhysicsOptions.water());
		o.addProperty("meshColliders", PhysicsOptions.meshColliders());
		o.addProperty("quality", PhysicsOptions.quality().name().toLowerCase(Locale.ROOT));
		o.addProperty("distanceLimit", PhysicsOptions.distanceLimit());

		return GSON.toJson(o);
	}

	/** Applies the keys present in {@code json} to the running options and ignores the rest; returns false if the text is not a JSON object */
	public static boolean apply(String json) {
		JsonObject o;

		try {
			o = JsonParser.parseString(json).getAsJsonObject();
		}
		catch (RuntimeException ex) {
			return false;
		}

		// each key on its own: a value of the wrong type costs that option only
		flag(o, "enabled", PhysicsOptions::enabled);
		flag(o, "worldCollision", PhysicsOptions::worldCollision);
		flag(o, "otherCollision", PhysicsOptions::otherCollision);
		flag(o, "playerCollision", PhysicsOptions::playerCollision);
		flag(o, "springWorldCollision", PhysicsOptions::springWorldCollision);
		flag(o, "cloth", PhysicsOptions::cloth);
		flag(o, "fluid", PhysicsOptions::fluid);
		flag(o, "water", PhysicsOptions::water);
		flag(o, "meshColliders", PhysicsOptions::meshColliders);

		try {
			if (o.has("quality"))
				PhysicsOptions.quality(PhysicsOptions.Quality.valueOf(o.get("quality").getAsString().toUpperCase(Locale.ROOT)));
		}
		catch (RuntimeException ignored) {
		}

		try {
			if (o.has("distanceLimit"))
				PhysicsOptions.distanceLimit(o.get("distanceLimit").getAsDouble());
		}
		catch (RuntimeException ignored) {
		}

		return true;
	}

	private static void flag(JsonObject o, String key, Consumer<Boolean> set) {
		try {
			if (o.has(key) && o.get(key).getAsJsonPrimitive().isBoolean())
				set.accept(o.get(key).getAsBoolean());
		}
		catch (RuntimeException ignored) {
		}
	}

	static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("strut-physics.json");
	}

	public static void load() {
		Path file = file();

		if (!Files.exists(file))
			return;

		try {
			if (!apply(Files.readString(file, StandardCharsets.UTF_8)))
				LOGGER.warn("{} is not usable JSON; using the defaults", file);
		}
		catch (IOException ex) {
			LOGGER.warn("Could not read {}: {}", file, ex.getMessage());
		}
	}

	public static void save() {
		try {
			Files.writeString(file(), toJson(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			LOGGER.warn("Could not write {}: {}", file(), ex.getMessage());
		}
	}
}
