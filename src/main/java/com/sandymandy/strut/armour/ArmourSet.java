package com.sandymandy.strut.armour;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A family of armour models for wearers that share a skeleton: one model for each slot, and the texture they are drawn with
 * <pre>{@code
 * {
 *   "slots": { "head": "mymod:armour/creature_head", "chest": "mymod:armour/creature_chest", "legs": "mymod:armour/creature_legs", "feet": "mymod:armour/creature_feet" },
 *   "texture": "mymod:textures/armor/armor.png",
 *   "texture_size": [4096, 4096],
 *   "column": [72, 0],
 *   "trims": { "texture": "mymod:textures/armour/trims/{pattern}.png", "colours": { "minecraft:quartz": "#E3D4C4" } }
 * }
 * }</pre>
 * {@code glint} is optional too: {@code false} for none, or {@code {"texture", "strength", "repeat"}} to change the game's own armour glint that an enchanted piece has.
 * {@code trims} is optional: a set without it draws no trims. Each trim pattern is a texture with the same layout as the set's slot models' UVs, transparent where there
 * is no trim, drawn over the armour tinted by the trim material (the vanilla materials' colours are built in, {@code colours} changes or adds some)
 * The slot models are looked up as {@code assets/<ns>/strut/models/<path>.strutmodel}-style ids exactly as the game loads models (a path under
 * {@code geo/}, with or without the extension: {@code mymod:armour/creature_head} is {@code mymod:strut/models/armour/creature_head.strutmodel}). The texture is an atlas of
 * materials: {@code column} is the size in pixels of one cell (a look picks the cell by its {@code column} and {@code row}), turned into the UV offset the
 * worn models are drawn with
 *
 * @param id the set's id, {@code <namespace>:<path>} of its file
 * @param slots the model of each slot, by slot name
 * @param texture the atlas
 * @param textureWidth the atlas's size in pixels
 * @param cellWidth how far one column is from the next in pixels (0 for an atlas that is not divided)
 * @param trimTexture where the trim of a pattern is, with {@code {pattern}} and {@code {namespace}} standing for the pattern's id; null if the set has no trims
 * @param trimColours the colour of a trim material, packed RGB by material id, over the vanilla ones
 */
public record ArmourSet(Identifier id, Map<String, Identifier> slots, Identifier texture, int textureWidth, int textureHeight, int cellWidth, int cellHeight,
						@Nullable String trimTexture, Map<Identifier, Integer> trimColours, Glint glint) {
	/**
	 * How an enchanted piece shines: a texture scrolled over the slot model, added to the armour
	 *
	 * @param texture the glint texture (vanilla's armour glint by default); null for a set with no glint
	 * @param strength how strongly it is added, 0 to 1
	 * @param repeat how many times the glint texture repeats over 64 texels of the atlas (the size of the game's armour sheet): the game's is 0.16, a pattern some
	 *               six sheets wide, which is the sheen; a higher number is a finer pattern, and one finer than the pieces is a flat colour
	 */
	public record Glint(@Nullable Identifier texture, float strength, float repeat) {
		/** The game's own armour glint: its texture, its strength option's default, and the scale it draws armour with */
		public static final Glint VANILLA = new Glint(Identifier.ofVanilla("textures/misc/enchanted_glint_armor.png"), 0.75f, 0.16f);

		public static final Glint NONE = new Glint(null, 0f, 0f);
	}

	public ArmourSet {
		slots = Map.copyOf(slots);
		trimColours = Map.copyOf(trimColours);
	}

	/** Whether this set draws armour trims at all: trims are optional, and a set that says nothing about them has none */
	public boolean hasTrims() {
		return this.trimTexture != null;
	}

	/** The texture of the trim pattern {@code pattern}, or null if the set has no trims or the id does not make a texture */
	@Nullable
	public Identifier trimTextureFor(Identifier pattern) {
		if (this.trimTexture == null)
			return null;

		return Identifier.tryParse(this.trimTexture.replace("{namespace}", pattern.getNamespace()).replace("{pattern}", pattern.getPath()));
	}

	/** The colour a trim of {@code material} is drawn: the set's own for it, else the vanilla one, else white */
	public int trimColour(Identifier material) {
		Integer own = this.trimColours.get(material);

		return own != null ? own : TrimColours.vanilla(material);
	}

	/** The UV offset of the cell at {@code column}, {@code row}, in fractions of the texture, to give the worn model */
	public float[] uvOffset(int column, int row) {
		return new float[] {this.textureWidth <= 0 ? 0f : (float) column * this.cellWidth / this.textureWidth,
				this.textureHeight <= 0 ? 0f : (float) row * this.cellHeight / this.textureHeight};
	}

	/** The model for a slot, or null if the set has none for it */
	@Nullable
	public Identifier model(String slot) {
		return this.slots.get(slot);
	}

	/**
	 * Reads a set from its JSON; a problem is reported to {@code warn} and the set is null if it cannot be used at all
	 */
	@Nullable
	public static ArmourSet parse(Identifier id, JsonObject json, Consumer<String> warn) {
		Map<String, Identifier> slots = new LinkedHashMap<>();

		if (json.has("slots") && json.get("slots").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("slots").entrySet()) {
				Identifier model = modelId(entry.getValue().getAsString());

				if (model == null)
					warn.accept(id + ": slot " + entry.getKey() + " has a model that is not an id: " + entry.getValue());
				else
					slots.put(entry.getKey(), model);
			}
		}

		if (slots.isEmpty()) {
			warn.accept(id + ": a set needs \"slots\"");

			return null;
		}

		Identifier texture = json.has("texture") ? Identifier.tryParse(json.get("texture").getAsString()) : null;

		if (texture == null) {
			warn.accept(id + ": a set needs a \"texture\"");

			return null;
		}

		int[] size = pair(json, "texture_size", 0, 0);
		int[] cell = pair(json, "column", 0, 0);

		String trimTexture = null;
		Map<Identifier, Integer> trimColours = new LinkedHashMap<>();

		if (json.has("trims") && json.get("trims").isJsonObject()) {
			JsonObject trims = json.getAsJsonObject("trims");

			if (trims.has("texture"))
				trimTexture = trims.get("texture").getAsString();
			else
				warn.accept(id + ": \"trims\" needs a \"texture\" such as \"mod:textures/armour/trims/{pattern}.png\"");

			if (trims.has("colours") && trims.get("colours").isJsonObject()) {
				for (Map.Entry<String, JsonElement> entry : trims.getAsJsonObject("colours").entrySet()) {
					Identifier material = Identifier.tryParse(entry.getKey());

					if (material == null)
						warn.accept(id + ": trim colour for " + entry.getKey() + " is not an id");
					else
						trimColours.put(material, ArmourLook.colour(entry.getValue().getAsString(), 0xFFFFFF));
				}
			}
		}

		Glint glint = Glint.VANILLA;

		if (json.has("glint")) {
			JsonElement element = json.get("glint");

			if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
				glint = element.getAsBoolean() ? Glint.VANILLA : Glint.NONE;
			}
			else if (element.isJsonObject()) {
				JsonObject object = element.getAsJsonObject();
				Identifier glintTexture = object.has("texture") ? Identifier.tryParse(object.get("texture").getAsString()) : Glint.VANILLA.texture();

				glint = new Glint(glintTexture, object.has("strength") ? object.get("strength").getAsFloat() : object.has("alpha") ? object.get("alpha").getAsFloat() : Glint.VANILLA.strength(),
						object.has("repeat") ? object.get("repeat").getAsFloat() : Glint.VANILLA.repeat());
			}
		}

		return new ArmourSet(id, slots, texture, size[0], size[1], cell[0], cell[1], trimTexture, trimColours, glint);
	}

	/**
	 * A model given in a file as {@code ns:armour/head}, {@code ns:strut/models/armour/head} or {@code ns:strut/models/armour/head.strutmodel}, as the id the game loads it as
	 * ({@code ns:strut/models/armour/head.strutmodel}); {@code geo/} in place of {@code strut/models/} is still understood; null if it is not an id
	 */
	@Nullable
	public static Identifier modelId(String text) {
		Identifier given = Identifier.tryParse(text);

		if (given == null)
			return null;

		String path = given.getPath();

		// the older folder name maps to the one models are in now
		if (path.startsWith("geo/"))
			path = "strut/models/" + path.substring("geo/".length());
		else if (!path.startsWith("strut/models/"))
			path = "strut/models/" + path;

		if (!path.endsWith(".strutmodel"))
			path = path + ".strutmodel";

		return given.withPath(path);
	}

	private static int[] pair(JsonObject json, String key, int a, int b) {
		if (!json.has(key) || !json.get(key).isJsonArray() || json.getAsJsonArray(key).size() < 2)
			return new int[] {a, b};

		return new int[] {json.getAsJsonArray(key).get(0).getAsInt(), json.getAsJsonArray(key).get(1).getAsInt()};
	}
}
