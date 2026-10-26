package com.sandymandy.strut.armour;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * How one item (or a whole kind of item) looks when worn: a cell of the set's atlas, a tint, or a model of its own
 * <pre>{@code
 * { "match": { "materials": ["minecraft:diamond"] }, "column": 1 }
 * { "match": { "materials": ["minecraft:leather"] }, "column": 6, "tint": "dye", "undyed": "#A06540" }
 * { "match": { "items": ["mymod:crown"] }, "model": "mymod:armour/crown", "texture": "mymod:textures/armour/crown.png" }
 * { "column": 0 }
 * }</pre>
 * The more exactly a look names an item the more it wins: the item's id, then a tag it is in, then its armour material, then a look that matches everything.
 * {@code "set"} limits a look to wearers of that armour set (a column of one atlas is not a column of another). Items are never matched by their names
 * <ul>
 * <li>{@code column}, {@code row}: the cell of the set's atlas ({@link ArmourSet#uvOffset})</li>
 * <li>{@code tint}: {@code "none"}, {@code "dye"} (the item's dye colour, {@code undyed} if it has none) or a colour such as {@code "#A06540"}</li>
 * <li>{@code model}, {@code texture}: a model and a texture of the look's own in place of the set's; with a {@code model} the {@code column} is not applied
 * unless it is given</li>
 * <li>{@code overlay_column}, {@code overlay_row}: a second cell of the atlas drawn over the first, untinted (leather's buckles and stitching, which
 * dye does not colour)</li>
 * <li>{@code slots}: limits the look to these slots</li>
 * </ul>
 */
public record ArmourLook(Identifier id, Set<Identifier> items, Set<Identifier> tags, Set<Identifier> materials, @Nullable Identifier set, Set<String> slots,
						 int column, int row, Tint tint, int undyed, @Nullable Identifier model, @Nullable Identifier texture, @Nullable Integer overlayColumn, int overlayRow) {
	/** How a worn piece is coloured */
	public enum Tint {
		NONE, DYE, FIXED
	}

	public ArmourLook {
		items = Set.copyOf(items);
		tags = Set.copyOf(tags);
		materials = Set.copyOf(materials);
		slots = Set.copyOf(slots);
	}

	/**
	 * How exactly this look names an item: 3 by id, 2 by tag, 1 by material, 0 for a look that matches everything; -1 if it does not match
	 *
	 * @param itemId the item's id
	 * @param inTag whether the item is in a tag
	 * @param materialId the item's armour material, or null if it has none
	 * @param setId the armour set of the wearer
	 * @param slot the slot worn
	 */
	public int specificity(Identifier itemId, Predicate<Identifier> inTag, @Nullable Identifier materialId, Identifier setId, String slot) {
		if (this.set != null && !this.set.equals(setId))
			return -1;

		if (!this.slots.isEmpty() && !this.slots.contains(slot))
			return -1;

		boolean constrained = !this.items.isEmpty() || !this.tags.isEmpty() || !this.materials.isEmpty();

		if (!constrained)
			return 0;

		if (this.items.contains(itemId))
			return 3;

		for (Identifier tag : this.tags)
			if (inTag.test(tag))
				return 2;

		if (materialId != null && this.materials.contains(materialId))
			return 1;

		return -1;
	}

	/** The colour (packed ARGB) the piece is tinted: white for none; {@code dyeRgb} is the item's dye colour or -1 if it has none */
	public int tintFor(int dyeRgb) {
		return switch (this.tint) {
			case NONE -> 0xFFFFFFFF;
			case DYE -> 0xFF000000 | (dyeRgb >= 0 ? dyeRgb : this.undyed);
			case FIXED -> 0xFF000000 | this.undyed;
		};
	}

	/**
	 * Reads a look from its JSON; a problem is reported to {@code warn}
	 */
	public static ArmourLook parse(Identifier id, JsonObject json, Consumer<String> warn) {
		Set<Identifier> items = new HashSet<>();
		Set<Identifier> tags = new HashSet<>();
		Set<Identifier> materials = new HashSet<>();

		if (json.has("match") && json.get("match").isJsonObject()) {
			JsonObject match = json.getAsJsonObject("match");

			ids(match, "items", items, id, warn);
			ids(match, "tags", tags, id, warn);
			ids(match, "materials", materials, id, warn);
		}

		Set<String> slots = new HashSet<>();

		if (json.has("slots") && json.get("slots").isJsonArray())
			for (JsonElement slot : json.getAsJsonArray("slots"))
				slots.add(slot.getAsString());

		Identifier set = json.has("set") ? Identifier.tryParse(json.get("set").getAsString()) : null;
		Identifier model = json.has("model") ? ArmourSet.modelId(json.get("model").getAsString()) : null;
		Identifier texture = json.has("texture") ? Identifier.tryParse(json.get("texture").getAsString()) : null;
		int undyed = json.has("undyed") ? colour(json.get("undyed").getAsString(), 0xFFFFFF) : 0xFFFFFF;
		Tint tint = Tint.NONE;

		if (json.has("tint")) {
			String text = json.get("tint").getAsString();

			if (text.equalsIgnoreCase("dye")) {
				tint = Tint.DYE;
			}
			else if (!text.equalsIgnoreCase("none")) {
				tint = Tint.FIXED;
				undyed = colour(text, 0xFFFFFF);
			}
		}

		return new ArmourLook(id, items, tags, materials, set, slots, json.has("column") ? json.get("column").getAsInt() : 0, json.has("row") ? json.get("row").getAsInt() : 0,
				tint, undyed, model, texture, json.has("overlay_column") ? json.get("overlay_column").getAsInt() : null, json.has("overlay_row") ? json.get("overlay_row").getAsInt() : 0);
	}

	private static void ids(JsonObject match, String key, Set<Identifier> into, Identifier lookId, Consumer<String> warn) {
		if (!match.has(key) || !match.get(key).isJsonArray())
			return;

		JsonArray array = match.getAsJsonArray(key);

		for (JsonElement element : array) {
			Identifier parsed = Identifier.tryParse(element.getAsString());

			if (parsed == null)
				warn.accept(lookId + ": \"" + element.getAsString() + "\" is not an id");
			else
				into.add(parsed);
		}
	}

	/** {@code "#A06540"} or {@code "A06540"} as RGB; {@code fallback} if it is not a colour */
	static int colour(String text, int fallback) {
		String hex = text.startsWith("#") ? text.substring(1) : text;

		try {
			return Integer.parseInt(hex, 16) & 0xFFFFFF;
		}
		catch (NumberFormatException ex) {
			return fallback;
		}
	}
}
