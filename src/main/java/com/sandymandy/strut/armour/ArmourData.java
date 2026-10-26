package com.sandymandy.strut.armour;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sandymandy.strut.StrutConstants;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The armour sets, wearer profiles and looks the client has loaded, read from the resource packs whenever resources reload
 * <p>
 * Files live at {@code assets/<ns>/strut/armour/sets/<path>.json}, {@code .../wearers/<path>.json} and {@code .../looks/<path>.json}; the id of each is
 * {@code <ns>:<path>}. Being resource-pack assets a pack can add or replace any of them, with nothing to install on the server
 */
public final class ArmourData {
	private static final Logger LOGGER = LogManager.getLogger("Strut");
	private static final String ROOT = "strut/armour";

	private static volatile Map<Identifier, ArmourSet> sets = Map.of();
	private static volatile Map<Identifier, WearerProfile> profiles = Map.of();
	private static volatile List<ArmourLook> looks = List.of();

	private ArmourData() {}

	/** Called once from the client entrypoint */
	public static void init() {
		ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public Identifier getFabricId() {
				return Identifier.of(StrutConstants.MODID, "armour");
			}

			@Override
			public void reload(ResourceManager manager) {
				load(manager);
			}
		});
	}

	@Nullable
	public static ArmourSet set(@Nullable Identifier id) {
		return id == null ? null : sets.get(id);
	}

	/** The profile, with what it extends merged in, or null if there is none of that id */
	@Nullable
	public static WearerProfile profile(@Nullable Identifier id) {
		if (id == null)
			return null;

		WearerProfile found = profiles.get(id);

		return found == null ? null : withDefaultSet(found.resolve(profiles::get), sets.keySet());
	}

	/** A profile that names no set wears the set of its own name, if there is one: {@code ava.json} in wearers and in sets go together */
	@Nullable
	private static WearerProfile withDefaultSet(@Nullable WearerProfile profile, Set<Identifier> setIds) {
		if (profile == null || profile.armourSet() != null || !setIds.contains(profile.id()))
			return profile;

		return new WearerProfile(profile.id(), null, profile.id(), profile.covers(), profile.trims(), profile.glint(), profile.bones(), profile.fit());
	}

	/** The slot models in a folder under {@code strut/models}: the .strutmodel files directly in it, by file name (the slot they are worn in) */
	private static Map<String, Identifier> discover(ResourceManager manager, Identifier folder) {
		String directory = "strut/models/" + folder.getPath();
		Map<String, Identifier> found = new TreeMap<>();

		for (Identifier file : manager.findResources(directory, id -> id.getNamespace().equals(folder.getNamespace()) && id.getPath().endsWith(".strutmodel")).keySet()) {
			String name = file.getPath().substring(directory.length() + 1);

			if (!name.contains("/"))
				found.put(name.substring(0, name.length() - ".strutmodel".length()), file);
		}

		return found;
	}

	/**
	 * The look that fits an item best for a wearer of {@code set} in {@code slot}, or null if no look matches (a set with a default look always has one)
	 */
	@Nullable
	public static ArmourLook lookFor(Identifier itemId, Predicate<Identifier> inTag, @Nullable Identifier materialId, Identifier set, String slot) {
		return best(looks, itemId, inTag, materialId, set, slot);
	}

	/** {@link #lookFor} over given looks: the most specific that matches, the first of those by id */
	@Nullable
	static ArmourLook best(List<ArmourLook> candidates, Identifier itemId, Predicate<Identifier> inTag, @Nullable Identifier materialId, Identifier set, String slot) {
		ArmourLook best = null;
		int bestScore = -1;

		for (ArmourLook look : candidates) {
			int score = look.specificity(itemId, inTag, materialId, set, slot);

			// a look for this set outranks one for any set of the same specificity
			if (score >= 0 && look.set() != null)
				score = score * 2 + 1;
			else if (score >= 0)
				score = score * 2;

			if (score > bestScore) {
				best = look;
				bestScore = score;
			}
		}

		return best;
	}

	private static void load(ResourceManager manager) {
		Map<Identifier, ArmourSet> loadedSets = new HashMap<>();
		Map<Identifier, WearerProfile> loadedProfiles = new HashMap<>();
		List<ArmourLook> loadedLooks = new ArrayList<>();
		Consumer<String> warn = message -> LOGGER.warn("[Strut armour] {}", message);

		Map<Identifier, JsonObject> rawSets = new LinkedHashMap<>();

		read(manager, "sets", rawSets::put);
		read(manager, "wearers", (id, json) -> loadedProfiles.put(id, WearerProfile.parse(id, json, warn)));
		read(manager, "looks", (id, json) -> loadedLooks.add(ArmourLook.parse(id, json, warn)));

		ArmourSets.Result resolved = ArmourSets.resolve(rawSets, folder -> discover(manager, folder), warn);

		loadedSets.putAll(resolved.sets());

		Map<Identifier, WearerProfile> resolvedProfiles = new HashMap<>();

		for (WearerProfile profile : loadedProfiles.values()) {
			WearerProfile whole = profile.resolve(loadedProfiles::get);

			if (whole == null)
				warn.accept(profile.id() + ": the profiles it extends loop");
			else
				resolvedProfiles.put(profile.id(), withDefaultSet(whole, loadedSets.keySet()));
		}

		// a wearer whose set or models are missing stops the game starting, saying what to add
		List<String> problems = new ArrayList<>(resolved.problems());

		problems.addAll(ArmourSets.validate(resolved, resolvedProfiles, StrutArmour.registeredWearers()));

		if (!problems.isEmpty()) {
			StringBuilder message = new StringBuilder("Strut armour: ").append(problems.size()).append(" problem(s) with the armour files:");

			for (String problem : problems)
				message.append("\n  - ").append(problem);

			throw new IllegalStateException(message.toString());
		}

		loadedLooks.sort(Comparator.comparing(look -> look.id().toString()));

		sets = Map.copyOf(loadedSets);
		profiles = Map.copyOf(loadedProfiles);
		looks = List.copyOf(loadedLooks);

		LOGGER.info("[Strut armour] loaded {} armour set(s), {} wearer profile(s), {} look(s)", loadedSets.size(), loadedProfiles.size(), loadedLooks.size());
	}

	private static void read(ResourceManager manager, String kind, BiConsumer<Identifier, JsonObject> into) {
		String directory = ROOT + "/" + kind;

		for (Map.Entry<Identifier, Resource> entry : manager.findResources(directory, id -> id.getPath().endsWith(".json")).entrySet()) {
			Identifier file = entry.getKey();
			String path = file.getPath().substring(directory.length() + 1, file.getPath().length() - ".json".length());

			try (Reader reader = entry.getValue().getReader()) {
				JsonElement root = JsonParser.parseReader(reader);

				if (root.isJsonObject())
					into.accept(Identifier.of(file.getNamespace(), path), root.getAsJsonObject());
				else
					LOGGER.warn("[Strut armour] {} is not a JSON object; skipped", file);
			}
			catch (Exception ex) {
				LOGGER.warn("[Strut armour] could not read {}: {}", file, ex.toString());
			}
		}
	}
}
