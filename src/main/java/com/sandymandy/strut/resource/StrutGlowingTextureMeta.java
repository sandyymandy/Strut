package com.sandymandy.strut.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.client.texture.NativeImage;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resource.metadata.ResourceMetadataReader;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.JsonHelper;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Metadata class that stores the data for Strut's {@link com.sandymandy.strut.cache.texture.AutoGlowingTexture emissive texture feature} for a given texture
 */
public class StrutGlowingTextureMeta {
	public static final ResourceMetadataReader<StrutGlowingTextureMeta> DESERIALIZER = new ResourceMetadataReader<>() {
		@Override
		public String getKey() {
			return "glowsections";
		}

		@Override
		public StrutGlowingTextureMeta fromJson(JsonObject json) {
			List<Pixel> pixels = fromSections(JsonHelper.getArray(json, "sections", null));

			if (pixels.isEmpty())
				throw new JsonParseException("Empty glowlayer sections file. Must have at least one glow section!");

			return new StrutGlowingTextureMeta(pixels);
		}

		/**
		 * Generate a {@link Pixel} collection from the "sections" array of the mcmeta file
		 */
		private List<Pixel> fromSections(@Nullable JsonArray sectionsArray) {
			if (sectionsArray == null)
				return List.of();

			List<Pixel> pixels = new ObjectArrayList<>();

			for (JsonElement element : sectionsArray) {
				if (!(element instanceof JsonObject obj))
					throw new JsonParseException("Invalid glowsections json format, expected a JsonObject, found: " + element.getClass());

				int x1 = JsonHelper.getInt(obj, "x1", JsonHelper.getInt(obj, "x", 0));
				int y1 = JsonHelper.getInt(obj, "y1", JsonHelper.getInt(obj, "y", 0));
				int x2 = JsonHelper.getInt(obj, "x2", JsonHelper.getInt(obj, "w", 0) + x1);
				int y2 = JsonHelper.getInt(obj, "y2", JsonHelper.getInt(obj, "h", 0) + y1);
				int alpha = JsonHelper.getInt(obj, "alpha", JsonHelper.getInt(obj, "a", 0));

				if (x1 + y1 + x2 + y2 == 0)
					throw new IllegalArgumentException("Invalid glowsections section object, section must be at least one pixel in size");

				for (int x = x1; x <= x2; x++) {
					for (int y = y1; y <= y2; y++) {
						pixels.add(new Pixel(x, y, alpha));
					}
				}
			}

			return pixels;
		}
	};

	private final List<Pixel> pixels;

	public StrutGlowingTextureMeta(List<Pixel> pixels) {
		this.pixels = pixels;
	}

	/**
	 * Generate the GlowLayer pixels list from an existing image resource, instead of using the .png.mcmeta file
	 */
	public static StrutGlowingTextureMeta fromExistingImage(NativeImage glowLayer) {
		List<Pixel> pixels = new ObjectArrayList<>();

		for (int x = 0; x < glowLayer.getWidth(); x++) {
			for (int y = 0; y < glowLayer.getHeight(); y++) {
				int color = glowLayer.getColor(x, y); // Actually ABGR. Blame Mojang.

				if (color != 0)
					pixels.add(new Pixel(x, y, ColorHelper.Abgr.getAlpha(color)));
			}
		}

		if (pixels.isEmpty())
			throw new IllegalStateException("Invalid glow layer texture provided, must have at least one pixel!");

		return new StrutGlowingTextureMeta(pixels);
	}

	/**
	 * Create a new mask image based on the pre-determined pixel data
	 */
	public void createImageMask(NativeImage originalImage, NativeImage newImage) {
		for (Pixel pixel : this.pixels) {
			int color = originalImage.getColor(pixel.x, pixel.y); // Actually ABGR. Blame Mojang.

			if (pixel.alpha > 0)
				color = ColorHelper.Abgr.withAlpha(pixel.alpha, color);

			newImage.setColor(pixel.x, pixel.y, color);
			originalImage.setColor(pixel.x, pixel.y, 0);
		}
	}

	/**
	 * A pixel marker for a glowlayer mask
	 *
	 * @param x The X coordinate of the pixel
	 * @param y The Y coordinate of the pixel
	 * @param alpha The alpha value of the mask
	 */
	private record Pixel(int x, int y, int alpha) {}
}
