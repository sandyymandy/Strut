package com.sandymandy.strut.armour;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
 * Works the armour sets out of their files: a set may {@code extend} another and say only what differs, and its slot models are the files of a folder, named for
 * the slot they are worn in
 * <pre>{@code
 * assets/mymod/strut/armour/sets/creature.json      { "texture": "mymod:textures/armor/armor.png", "texture_size": [4096, 4096], "column": [72, 0] }
 * assets/mymod/strut/armour/sets/ava.json      { "extends": "mymod:creature" }
 * assets/mymod/strut/models/armour/ava/        head.strutmodel, chest.strutmodel, legs.strutmodel, feet.strutmodel
 * }</pre>
 * {@code "models"} names the folder ({@code mymod:armour/ava}, under {@code strut/models}); without it the folder is {@code <namespace>:armour/<file name of the set>}.
 * A set with a {@code slots} object still names its models one by one, which wins over a file of the folder. A set with no models and no texture of its own is a
 * template, there to be extended
 */
public final class ArmourSets {
	/** The slots of every wearer ({@link StrutArmour#SLOTS}, kept here so the files can be checked without the game running) */
	static final List<String> SLOTS = List.of("head", "chest", "legs", "feet", "body");

	private ArmourSets() {}

	/**
	 * The sets that are complete, and the ids of the templates
	 *
	 * @param sets the usable sets
	 * @param templates the sets that only exist to be extended
	 * @param problems what is wrong, each a sentence for the person who made the files
	 */
	public record Result(Map<Identifier, ArmourSet> sets, Set<Identifier> templates, List<String> problems) {}

	/**
	 * @param raw the files of the sets, by id
	 * @param discover the models in the folder given (as a path under {@code strut/models}), by the slot named for the file; empty if there is no such folder
	 * @param warn gets what is wrong with a file that can be read on
	 */
	public static Result resolve(Map<Identifier, JsonObject> raw, Function<Identifier, Map<String, Identifier>> discover, Consumer<String> warn) {
		Map<Identifier, ArmourSet> sets = new LinkedHashMap<>();
		Set<Identifier> templates = new HashSet<>();
		List<String> problems = new ArrayList<>();

		for (Map.Entry<Identifier, JsonObject> entry : raw.entrySet()) {
			Identifier id = entry.getKey();
			JsonObject merged = merged(id, raw, new ArrayList<>(), problems);

			if (merged == null)
				continue;

			// the models folder: given, or named for the file
			Identifier folder = null;
			boolean explicit = false;

			if (merged.has("models")) {
				folder = folderId(merged.get("models").getAsString());
				explicit = true;

				if (folder == null) {
					problems.add("Armour set " + id + ": \"models\" is not an id: " + merged.get("models"));

					continue;
				}
			}
			else {
				folder = Identifier.of(id.getNamespace(), "armour/" + id.getPath());
			}

			Map<String, Identifier> found = discover.apply(folder);
			JsonObject slots = merged.has("slots") && merged.get("slots").isJsonObject() ? merged.getAsJsonObject("slots") : new JsonObject();

			if (explicit && found.isEmpty() && slots.size() == 0) {
				problems.add("Armour set " + id + ": its models folder " + folder + " (assets/" + folder.getNamespace() + "/strut/models/" + folder.getPath()
						+ "/) has no .strutmodel files in it. Put a model for each slot in it, named head, chest, legs and feet");

				continue;
			}

			if (found.isEmpty() && slots.size() == 0) {
				templates.add(id);

				continue;
			}

			// the files of the folder, then the models named one by one over them
			JsonObject all = new JsonObject();

			for (Map.Entry<String, Identifier> model : found.entrySet())
				all.addProperty(model.getKey(), model.getValue().toString());

			for (Map.Entry<String, JsonElement> own : slots.entrySet())
				all.add(own.getKey(), own.getValue());

			merged.add("slots", all);

			ArmourSet set = ArmourSet.parse(id, merged, warn);

			if (set == null)
				problems.add("Armour set " + id + " cannot be used (see the warning above it in the log): a set that has models needs a \"texture\" too, of its own or from the set it extends");
			else
				sets.put(id, set);
		}

		return new Result(sets, templates, problems);
	}

	/**
	 * What is wrong between the wearers and the sets they wear: a wearer that names a set that does not exist, a slot a wearer covers that its set has no model for,
	 * a model file that is no slot's name
	 *
	 * @param wearers the wearer profiles, extended ones merged
	 * @param registered the profile each registered entity type is given ({@link StrutArmour#wearer}), by the type's id
	 */
	public static List<String> validate(Result result, Map<Identifier, WearerProfile> wearers, Map<Identifier, Identifier> registered) {
		List<String> problems = new ArrayList<>();
		Set<String> known = new HashSet<>(SLOTS);

		for (WearerProfile profile : wearers.values())
			known.addAll(profile.covers().keySet());

		for (ArmourSet set : result.sets().values()) {
			for (String slot : set.slots().keySet()) {
				if (!known.contains(slot)) {
					problems.add("Armour set " + set.id() + ": the model " + set.slots().get(slot) + " is named for a slot that does not exist. The slots are " + String.join(", ", SLOTS)
							+ " (rename the file, or the wearer that has the slot lists it under \"slots\")");
				}
			}
		}

		for (Map.Entry<Identifier, Identifier> entry : registered.entrySet()) {
			WearerProfile profile = wearers.get(entry.getValue());

			if (profile == null)
				problems.add("Entity " + entry.getKey() + " is registered as an armour wearer with the profile " + entry.getValue() + ", but there is no file assets/" + entry.getValue().getNamespace()
						+ "/strut/armour/wearers/" + entry.getValue().getPath() + ".json");
			else if (profile.armourSet() == null)
				problems.add("Entity " + entry.getKey() + " is an armour wearer (profile " + entry.getValue() + ") but names no \"armour_set\"");
		}

		for (WearerProfile profile : wearers.values()) {
			Identifier setId = profile.armourSet();

			if (setId == null)
				continue;

			ArmourSet set = result.sets().get(setId);

			if (set == null) {
				problems.add("Wearer " + profile.id() + " wears the armour set " + setId + ", which has no models: " + (result.templates().contains(setId)
						? "the set file exists but only as a template (nothing to wear); give it a models folder"
						: "put the models in assets/" + setId.getNamespace() + "/strut/models/armour/" + setId.getPath() + "/ (head, chest, legs, feet .strutmodel) or write the set file assets/"
						+ setId.getNamespace() + "/strut/armour/sets/" + setId.getPath() + ".json"));

				continue;
			}

			for (String slot : profile.covers().keySet()) {
				if (set.model(slot) == null)
					problems.add("Wearer " + profile.id() + " covers the " + slot + " slot but its armour set " + setId + " has no " + slot + " model. Add " + slot + ".strutmodel to the set's models folder");
			}
		}

		return problems;
	}

	/** A set's file with the sets it extends merged under it; null if the chain loops or names a set that is not there (reported to {@code problems}) */
	@Nullable
	private static JsonObject merged(Identifier id, Map<Identifier, JsonObject> raw, List<Identifier> chain, List<String> problems) {
		if (chain.contains(id)) {
			problems.add("Armour set " + id + ": the sets it extends loop (" + chain + ")");

			return null;
		}

		chain.add(id);

		JsonObject own = raw.get(id).deepCopy();

		if (!own.has("extends"))
			return own;

		Identifier parentId = Identifier.tryParse(own.get("extends").getAsString());

		if (parentId == null || !raw.containsKey(parentId)) {
			problems.add("Armour set " + id + " extends " + own.get("extends") + ", which is not a set file (assets/<namespace>/strut/armour/sets/<name>.json)");

			return null;
		}

		JsonObject base = merged(parentId, raw, chain, problems);

		if (base == null)
			return null;

		// the folder of models belongs to the set it is named in: a set extended for its texture does not lend its models
		base.remove("models");

		JsonObject baseSlots = base.has("slots") && base.get("slots").isJsonObject() ? base.getAsJsonObject("slots") : null;

		for (Map.Entry<String, JsonElement> entry : own.entrySet()) {
			if (entry.getKey().equals("extends"))
				continue;

			if (entry.getKey().equals("slots") && baseSlots != null && entry.getValue().isJsonObject()) {
				for (Map.Entry<String, JsonElement> slot : entry.getValue().getAsJsonObject().entrySet())
					baseSlots.add(slot.getKey(), slot.getValue());
			}
			else {
				base.add(entry.getKey(), entry.getValue());
			}
		}

		return base;
	}

	/** {@code ns:armour/ava} (optionally with the {@code strut/models/} in front) as the folder under {@code strut/models}; null if it is not an id */
	@Nullable
	public static Identifier folderId(String text) {
		Identifier given = Identifier.tryParse(text);

		if (given == null)
			return null;

		String path = given.getPath();

		if (path.startsWith("strut/models/"))
			path = path.substring("strut/models/".length());

		while (path.endsWith("/"))
			path = path.substring(0, path.length() - 1);

		return given.withPath(path);
	}
}
