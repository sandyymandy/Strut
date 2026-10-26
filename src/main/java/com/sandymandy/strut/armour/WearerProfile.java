package com.sandymandy.strut.armour;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sandymandy.strut.animation.attachment.BoneFit;
import java.util.Arrays;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What a model that can wear armour says about it: which set of armour models it wears, and which of its own bones each slot hides while it is worn
 * <pre>{@code
 * {
 *   "extends": "mymod:creature_base",
 *   "armour_set": "mymod:creature",
 *   "slots": {
 *     "chest": { "covers": ["pouchL", "pouchR"] },
 *     "legs":  { "covers": ["belly"] }
 *   }
 * }
 * }</pre>
 * A profile may {@code extend} another and say only what differs: its {@code armour_set} replaces the parent's if it gives one, and the covers of a slot it
 * lists replace the parent's for that slot. The bare and the dressed model of a character name the same set, so one set of armour fits both
 *
 * @param id the profile's id
 * @param parent the profile this one extends, or null
 * @param armourSet the set worn, or null if the parent's is
 * @param covers the wearer's bones hidden while a slot is worn, by slot name
 * @param bones which bone of the wearer a bone of a worn model follows, by the worn bone's name, when it is not named as the wearer's is
 * @param fit how a worn model's bones are moved, turned or scaled to fit this wearer, by the worn bone's name
 * @param glint {@code false} for a wearer whose enchanted pieces do not shine; null if the profile does not say, as for trims
 * @param trims {@code false} for a wearer that shows no trims even where its set has them; null if the profile does not say (its parent does, else they show)
 */
public record WearerProfile(Identifier id, @Nullable Identifier parent, @Nullable Identifier armourSet, Map<String, List<String>> covers, @Nullable Boolean trims, @Nullable Boolean glint, Map<String, String> bones,
						Map<String, BoneFit> fit) {
	public WearerProfile {
		covers = Map.copyOf(covers);
		bones = Map.copyOf(bones);
		fit = Map.copyOf(fit);
	}

	/** This profile with what it extends merged in: a profile that stands alone, or null if the chain of parents loops */
	@Nullable
	public WearerProfile resolve(Function<Identifier, WearerProfile> lookup) {
		return resolve(lookup, new HashSet<>());
	}

	@Nullable
	private WearerProfile resolve(Function<Identifier, WearerProfile> lookup, Set<Identifier> seen) {
		if (!seen.add(this.id))
			return null;

		if (this.parent == null)
			return this;

		WearerProfile parentProfile = lookup.apply(this.parent);

		if (parentProfile == null)
			return this;

		WearerProfile base = parentProfile.resolve(lookup, seen);

		if (base == null)
			return null;

		Map<String, List<String>> merged = new LinkedHashMap<>(base.covers);
		Map<String, String> mergedBones = new LinkedHashMap<>(base.bones);
		Map<String, BoneFit> mergedFit = new LinkedHashMap<>(base.fit);

		merged.putAll(this.covers);
		mergedBones.putAll(this.bones);
		mergedFit.putAll(this.fit);

		return new WearerProfile(this.id, null, this.armourSet != null ? this.armourSet : base.armourSet, merged, this.trims != null ? this.trims : base.trims,
				this.glint != null ? this.glint : base.glint, mergedBones, mergedFit);
	}

	/**
	 * Reads a profile from its JSON; a problem is reported to {@code warn}
	 */
	public static WearerProfile parse(Identifier id, JsonObject json, Consumer<String> warn) {
		Identifier parent = json.has("extends") ? Identifier.tryParse(json.get("extends").getAsString()) : null;
		Identifier set = json.has("armour_set") ? Identifier.tryParse(json.get("armour_set").getAsString()) : null;

		if (json.has("armour_set") && set == null)
			warn.accept(id + ": \"armour_set\" is not an id: " + json.get("armour_set"));

		Map<String, List<String>> covers = new LinkedHashMap<>();

		if (json.has("slots") && json.get("slots").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("slots").entrySet()) {
				List<String> bones = new ArrayList<>();

				if (entry.getValue().isJsonObject() && entry.getValue().getAsJsonObject().has("covers"))
					for (JsonElement bone : entry.getValue().getAsJsonObject().getAsJsonArray("covers"))
						bones.add(bone.getAsString());

				covers.put(entry.getKey(), bones);
			}
		}

		Boolean trims = json.has("trims") ? json.get("trims").getAsBoolean() : null;

		Boolean glint = json.has("glint") ? json.get("glint").getAsBoolean() : null;

		Map<String, String> bones = new LinkedHashMap<>();

		if (json.has("bones") && json.get("bones").isJsonObject())
			for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("bones").entrySet())
				bones.put(entry.getKey(), entry.getValue().getAsString());

		Map<String, BoneFit> fit = new LinkedHashMap<>();

		if (json.has("fit") && json.get("fit").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("fit").entrySet()) {
				if (!entry.getValue().isJsonObject()) {
					warn.accept(id + ": fit for " + entry.getKey() + " is not an object");

					continue;
				}

				JsonObject object = entry.getValue().getAsJsonObject();

				fit.put(entry.getKey(), new BoneFit(triple(object, "offset", 0f), triple(object, "rotation", 0f), triple(object, "scale", 1f)));
			}
		}

		return new WearerProfile(id, parent, set, covers, trims, glint, bones, fit);
	}

	/** {@code key} of {@code object} as three numbers (a single number is all three); {@code fallback} for each if it is not there */
	private static float[] triple(JsonObject object, String key, float fallback) {
		float[] values = {fallback, fallback, fallback};

		if (!object.has(key))
			return values;

		JsonElement element = object.get(key);

		if (element.isJsonArray() && element.getAsJsonArray().size() >= 3) {
			for (int i = 0; i < 3; i++)
				values[i] = element.getAsJsonArray().get(i).getAsFloat();
		}
		else if (element.isJsonPrimitive()) {
			Arrays.fill(values, element.getAsFloat());
		}

		return values;
	}

	/** Whether this wearer shows trims where its set has them: true unless it says not */
	public boolean showsTrims() {
		return this.trims == null || this.trims;
	}

	/** Whether enchanted pieces shine on this wearer where their set has a glint: true unless it says not */
	public boolean showsGlint() {
		return this.glint == null || this.glint;
	}

	/** The bones hidden while {@code slot} is worn, empty if none */
	public List<String> coversOf(String slot) {
		return this.covers.getOrDefault(slot, List.of());
	}
}
