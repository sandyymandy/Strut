package com.sandymandy.strut.loading;

import com.google.gson.JsonObject;
import net.minecraft.util.Identifier;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.JsonHelper;
import org.apache.commons.io.IOUtils;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.Animation;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.loading.json.raw.Model;
import com.sandymandy.strut.loading.json.typeadapter.KeyFramesAdapter;
import com.sandymandy.strut.loading.object.BakedAnimations;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Extracts raw information from given files, and other similar functions
 */
public final class FileLoader {
	/**
	 * Load up and deserialize an animation json file to its respective {@link Animation} components
	 *
	 * @param location The resource path of the animations file
	 * @param manager The Minecraft {@code ResourceManager} responsible for maintaining in-memory resource access
	 */
	public static BakedAnimations loadAnimationsFile(Identifier location, ResourceManager manager) {
		if (location.getPath().endsWith(".geo.json"))
			throw new IllegalArgumentException("Geo model file found in animations folder!");

		if (!location.getPath().endsWith(".animation.json"))
			StrutConstants.LOGGER.warn("Found animation file with improper file name format; animation files should end in .animation.json: '" + location + "'");

		return KeyFramesAdapter.GEO_GSON.fromJson(JsonHelper.getObject(loadFile(location, manager), "animations"), BakedAnimations.class);
	}

	/**
	 * Load up and deserialize a geo model json file to its respective {@link BakedStrutModel} format
	 *
	 * @param location The resource path of the model file
	 * @param manager The Minecraft {@code ResourceManager} responsible for maintaining in-memory resource access
	 */
	public static Model loadModelFile(Identifier location, ResourceManager manager) {
		if (location.getPath().endsWith(".animation.json"))
			throw new IllegalArgumentException("Animation file found in geo models folder!");

		if (!location.getPath().endsWith(".geo.json"))
			StrutConstants.LOGGER.warn("Found geo model file with improper file name format; geo model files should end in .geo.json: '" + location + "'");

		return KeyFramesAdapter.GEO_GSON.fromJson(loadFile(location, manager), Model.class);
	}

	/**
	 * Load a given json file into memory
	 *
	 * @param location The resource path of the json file
	 * @param manager The Minecraft {@code ResourceManager} responsible for maintaining in-memory resource access
	 */
	public static JsonObject loadFile(Identifier location, ResourceManager manager) {
		return JsonHelper.deserialize(KeyFramesAdapter.GEO_GSON, getFileContents(location, manager), JsonObject.class);
	}

	/**
	 * Read a text-based file into memory in the form of a single string
	 *
	 * @param location The resource path of the file
	 * @param manager The Minecraft {@code ResourceManager} responsible for maintaining in-memory resource access
	 */
	public static String getFileContents(Identifier location, ResourceManager manager) {
		try (InputStream inputStream = manager.getResourceOrThrow(location).getInputStream()) {
			return IOUtils.toString(inputStream, Charset.defaultCharset());
		}
		catch (Exception e) {
			StrutConstants.LOGGER.error("Couldn't load " + location, e);

			throw new RuntimeException(new FileNotFoundException(location.toString()));
		}
	}
}
