package com.sandymandy.strut.physics.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashSet;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import com.sandymandy.strut.physics.collision.AutoColliders;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.solver.ChainParams;
import com.sandymandy.strut.physics.solver.ClothParams;
import com.sandymandy.strut.physics.solver.RagdollParams;
import com.sandymandy.strut.physics.solver.SpringParams;

/**
 * A model's physics tuning, read from JSON: the same text whether it comes from the model file's {@code strut_physics:physics} extension section or from a
 * resource-pack sidecar at {@code assets/<namespace>/strut/physics/<model path>.json}
 * <pre>
 * {
 *   "defaults": { "inertia": 1 },
 *   "bones": {
 *     "pouchL": { "preset": "jiggle-soft", "frequency": 1.7, "damping": 0.24, "length": 0.15,
 *                "limit": { "x": [-20, 20], "y": [-20, 20], "z": [-20, 20] } },
 *     "belly": { "preset": "jiggle-firm", "translate": true, "maxTranslate": 1.5, "weight": 0.8 }
 *   }
 * }
 * </pre>
 * Keys: {@code preset}, {@code frequency} (Hz), {@code damping} (ratio), {@code inertia}, {@code gravity}, {@code rotate}, {@code translate},
 * {@code tip} ([x, y, z]), {@code length}, {@code limit} (degrees per axis), {@code maxTranslate} (model units), {@code translateScale}, {@code weight},
 * {@code collidesWith} (layers the mass is pushed out of: {@code other}, {@code players}, {@code mobs}; none by default), {@code radius} (blocks) {@code squish} (0 to 1) and {@code localRotation} (true: the spring's rotation is turned about the bone's own axes as it is posed, which is what a bone with a rotation of its own needs; false, the default, adds it to the bone's rotation angles, which is the same for a bone with none).
 * A sidecar layers over the section, bone by bone, so a resource pack can retune a model without re-exporting it. Anything it does not understand is
 * reported in {@link #warnings()} and skipped, never thrown
 */
public final class PhysicsConfig {
	private final Map<String, SpringParams> bones;
	private final Map<String, ChainParams> chains;
	private final Map<String, ClothParams> cloth;
	private final List<Collider> colliders;
	private final List<String> warnings;
	private final AutoColliders.Options auto;
	private final RagdollParams ragdoll;
	private final Map<String, BoneFilter> filters;

	/**
	 * Which of its own model's bones a spring bone or chain collides with: {@code only} (when not empty) are the only bones whose shapes it feels, {@code ignore}
	 * are left out. Names may start or end with {@code *}. Written as {@code "collideBones"} and {@code "ignoreBones"} on the bone or chain
	 */
	public record BoneFilter(List<String> only, List<String> ignore) {
		public static final BoneFilter NONE = new BoneFilter(List.of(), List.of());

		public BoneFilter {
			only = List.copyOf(only);
			ignore = List.copyOf(ignore);
		}

		/** Whether a shape on {@code bone} is felt */
		public boolean allows(String bone) {
			if (this.ignore.stream().anyMatch(p -> AutoColliders.matches(p, bone)))
				return false;

			return this.only.isEmpty() || this.only.stream().anyMatch(p -> AutoColliders.matches(p, bone));
		}
	}

	private PhysicsConfig(Map<String, SpringParams> bones, Map<String, ChainParams> chains, Map<String, ClothParams> cloth, List<Collider> colliders, List<String> warnings,
			AutoColliders.Options auto, RagdollParams ragdoll, Map<String, BoneFilter> filters) {
		this.cloth = cloth;
		this.filters = filters;
		this.ragdoll = ragdoll;
		this.auto = auto;
		this.bones = bones;
		this.chains = chains;
		this.colliders = colliders;
		this.warnings = warnings;
	}

	/** The chains (hair, tails, ears) by name, in file order */
	public Map<String, ChainParams> chains() {
		return this.chains;
	}

	/** The cloths (a skirt, a cape) by name, in file order */
	public Map<String, ClothParams> cloth() {
		return this.cloth;
	}

	/** The bone filter of a spring bone, chain or cloth by its name (never null) */
	public BoneFilter filter(String part) {
		return this.filters.getOrDefault(part, BoneFilter.NONE);
	}

	/** The ragdoll skeleton, or null when the model has none */
	public RagdollParams ragdoll() {
		return this.ragdoll;
	}

	/** How collision shapes are made from the model's own geometry, or null for the default (by need) */
	public AutoColliders.Options auto() {
		return this.auto;
	}

	/** The collision shapes on bones, in file order */
	public List<Collider> colliders() {
		return this.colliders;
	}

	/** The spring bones by bone name, in file order */
	public Map<String, SpringParams> bones() {
		return this.bones;
	}

	public List<String> warnings() {
		return this.warnings;
	}

	public boolean isEmpty() {
		return this.bones.isEmpty() && this.chains.isEmpty() && this.cloth.isEmpty() && this.ragdoll == null;
	}

	static final SpringParams BASE = new SpringParams(2f, 0.25f, 1f, 0f, true, false, new Vector3f(0, -1, 0), 0.15f, null, 0f, 1f, 1f, Set.of(), 0.1f, 0f);

	private static final Map<String, SpringParams> PRESETS = new LinkedHashMap<>();

	static {
		// a consumer mod's jiggle: per-step stiffness 0.18 to 0.4 and damping 0.2 to 0.4 at 25 steps per second are 1.7 to 2.5 Hz, damping ratio 0.24 to 0.32
		PRESETS.put("jiggle-soft", new SpringParams(1.7f, 0.24f, 1f, 0f, true, false, new Vector3f(0, -1, 0), 0.15f, null, 0f, 1f, 1f, Set.of(), 0.1f, 0f));
		PRESETS.put("jiggle-firm", new SpringParams(2.5f, 0.32f, 1f, 0f, true, false, new Vector3f(0, -1, 0), 0.15f, null, 0f, 1f, 1f, Set.of(), 0.1f, 0f));
		PRESETS.put("hair", new SpringParams(2.2f, 0.3f, 1f, 9.8f, true, false, new Vector3f(0, -1, 0), 0.3f, null, 0f, 1f, 1f, Set.of(), 0.1f, 0f));
		PRESETS.put("tail", new SpringParams(1.6f, 0.2f, 1f, 9.8f, true, false, new Vector3f(0, -1, 0), 0.4f, null, 0f, 1f, 1f, Set.of(), 0.1f, 0f));
		PRESETS.put("ear", new SpringParams(3.5f, 0.2f, 1f, 4f, true, false, new Vector3f(0, 1, 0), 0.2f, null, 0f, 1f, 1f, Set.of(), 0.1f, 0f));
		PRESETS.put("soft-bone", new SpringParams(2.2f, 0.28f, 1f, 0f, true, false, new Vector3f(0, -1, 0), 0.15f, null, 1.5f, 1f, 1f, Set.of("other", "players", "mobs", "body"), 0.1f, 0.3f));
	}

	/** The names {@code "preset"} accepts */
	public static Set<String> presetNames() {
		return PRESETS.keySet();
	}

	public static PhysicsConfig empty() {
		return new PhysicsConfig(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>(), null, null, new LinkedHashMap<>());
	}

	/**
	 * Reads {@code json}; a malformed document gives an empty config with the reason in its warnings
	 */
	public static PhysicsConfig parse(String json, String source) {
		List<String> warnings = new ArrayList<>();
		Map<String, SpringParams> bones = new LinkedHashMap<>();
		Map<String, ChainParams> chains = new LinkedHashMap<>();
		Map<String, ClothParams> cloth = new LinkedHashMap<>();
		List<Collider> colliders = new ArrayList<>();
		AutoColliders.Options[] auto = {null};
		RagdollParams[] ragdoll = {null};
		Map<String, BoneFilter> filters = new LinkedHashMap<>();

		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			JsonObject defaults = root.has("defaults") ? root.getAsJsonObject("defaults") : new JsonObject();

			for (String key : root.keySet()) {
				if (!key.equals("defaults") && !key.equals("bones") && !key.equals("version") && !key.equals("chains") && !key.equals("cloth") && !key.equals("flesh") && !key.equals("colliders") && !key.equals("autoColliders") && !key.equals("ragdoll"))
					warnings.add(source + ": unknown top-level key \"" + key + "\"");
			}

			if (root.has("bones")) {
				for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("bones").entrySet()) {
					SpringParams params = BASE;

					params = apply(params, defaults, source + " defaults", warnings);
					params = apply(params, entry.getValue().getAsJsonObject(), source + " bone \"" + entry.getKey() + "\"", warnings);
					bones.put(entry.getKey(), params);
					readFilter(entry.getKey(), entry.getValue().getAsJsonObject(), source + " bone \"" + entry.getKey() + "\"", warnings, filters);
				}
			}

			if (root.has("chains")) {
				for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("chains").entrySet()) {
					readChain(entry.getKey(), entry.getValue().getAsJsonObject(), source, warnings, chains);
					readFilter(entry.getKey(), entry.getValue().getAsJsonObject(), source + " chain \"" + entry.getKey() + "\"", warnings, filters);
				}
			}

			if (root.has("cloth")) {
				for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("cloth").entrySet()) {
					readCloth(entry.getKey(), entry.getValue().getAsJsonObject(), source, warnings, cloth, false);
					readFilter(entry.getKey(), entry.getValue().getAsJsonObject(), source + " cloth \"" + entry.getKey() + "\"", warnings, filters);
				}
			}

			if (root.has("flesh")) {
				for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("flesh").entrySet()) {
					readCloth(entry.getKey(), entry.getValue().getAsJsonObject(), source, warnings, cloth, true);
					readFilter(entry.getKey(), entry.getValue().getAsJsonObject(), source + " flesh \"" + entry.getKey() + "\"", warnings, filters);
				}
			}

			if (root.has("ragdoll"))
				ragdoll[0] = readRagdoll(root.get("ragdoll"), source + " ragdoll", warnings);

			if (root.has("autoColliders"))
				auto[0] = readAuto(root.get("autoColliders"), source + " autoColliders", warnings);

			if (root.has("colliders")) {
				int i = 0;

				for (JsonElement element : root.getAsJsonArray("colliders"))
					readCollider(i++, element.getAsJsonObject(), source, warnings, colliders);
			}
		}
		catch (RuntimeException ex) {
			warnings.add(source + ": not usable as physics JSON (" + ex.getMessage() + ")");
			bones.clear();
			chains.clear();
			cloth.clear();
			colliders.clear();
			auto[0] = null;
			ragdoll[0] = null;
		}

		return new PhysicsConfig(bones, chains, cloth, colliders, warnings, auto[0], ragdoll[0], filters);
	}

	/**
	 * Layers {@code overlay} over this config, bone by bone: a bone named in the overlay is replaced whole, the others stay
	 */
	public PhysicsConfig overlaid(PhysicsConfig overlay) {
		Map<String, SpringParams> merged = new LinkedHashMap<>(this.bones);
		Map<String, ChainParams> mergedChains = new LinkedHashMap<>(this.chains);
		Map<String, ClothParams> mergedCloth = new LinkedHashMap<>(this.cloth);
		List<Collider> mergedColliders = new ArrayList<>(this.colliders);
		List<String> warnings = new ArrayList<>(this.warnings);

		merged.putAll(overlay.bones);
		mergedChains.putAll(overlay.chains);
		mergedCloth.putAll(overlay.cloth);
		mergedColliders.addAll(overlay.colliders);
		warnings.addAll(overlay.warnings);

		return new PhysicsConfig(merged, mergedChains, mergedCloth, mergedColliders, warnings, overlay.auto != null ? overlay.auto : this.auto,
				overlay.ragdoll != null ? overlay.ragdoll : this.ragdoll, mergedFilters(overlay));
	}

	private Map<String, BoneFilter> mergedFilters(PhysicsConfig overlay) {
		Map<String, BoneFilter> merged = new LinkedHashMap<>(this.filters);

		// a part the overlay replaces takes the overlay's filter, or none
		overlay.bones.keySet().forEach(merged::remove);
		overlay.chains.keySet().forEach(merged::remove);
		overlay.cloth.keySet().forEach(merged::remove);
		merged.putAll(overlay.filters);

		return merged;
	}

	private static void readFilter(String part, JsonObject object, String where, List<String> warnings, Map<String, BoneFilter> filters) {
		List<String> only = new ArrayList<>();
		List<String> ignore = new ArrayList<>();

		try {
			if (object.has("collideBones"))
				object.getAsJsonArray("collideBones").forEach(e -> only.add(e.getAsString()));

			if (object.has("ignoreBones"))
				object.getAsJsonArray("ignoreBones").forEach(e -> ignore.add(e.getAsString()));
		}
		catch (RuntimeException ex) {
			warnings.add(where + ": \"collideBones\" and \"ignoreBones\" must be lists of bone names (" + ex.getMessage() + ")");

			return;
		}

		if (!only.isEmpty() || !ignore.isEmpty())
			filters.put(part, new BoneFilter(only, ignore));
	}

	/**
	 * {@code "ragdoll": {"bones": [...], "radius": 0.07, "gravity": 24, "damping": 0.6, "friction": 0.35, "minJointAngle": 25, "parents": {"legR": "upperBody"}, "tips": {"head": [0, 0.3, 0]}}}:
	 * the bones that make the skeleton (each one's parent is the nearest bone above it in the list), and how it behaves. A bone with no child in the list ends
	 * at {@code tips[bone]} (blocks, in the bone's space) or, by default, at the far corner of its geometry
	 */
	private static RagdollParams readRagdoll(JsonElement value, String where, List<String> warnings) {
		try {
			JsonObject o = value.getAsJsonObject();
			List<String> bones = new ArrayList<>();
			float radius = 0.07f;
			float gravity = 24f;
			float damping = 0.6f;
			float friction = 0.35f;
			float minAngle = 25f;
			Map<String, Vector3f> tips = new LinkedHashMap<>();
			Map<String, String> parents = new LinkedHashMap<>();

			for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
				switch (entry.getKey()) {
					case "parents" -> entry.getValue().getAsJsonObject().entrySet().forEach(e -> parents.put(e.getKey(), e.getValue().getAsString()));
					case "bones" -> entry.getValue().getAsJsonArray().forEach(e -> bones.add(e.getAsString()));
					case "radius" -> radius = Math.max(0.005f, entry.getValue().getAsFloat());
					case "gravity" -> gravity = entry.getValue().getAsFloat();
					case "damping" -> damping = clamp01(entry.getValue().getAsFloat());
					case "friction" -> friction = clamp01(entry.getValue().getAsFloat());
					case "minJointAngle" -> minAngle = Math.max(0f, Math.min(170f, entry.getValue().getAsFloat()));
					case "tips" -> {
						for (Map.Entry<String, JsonElement> tip : entry.getValue().getAsJsonObject().entrySet())
							tips.put(tip.getKey(), vec(tip.getValue().getAsJsonArray()));
					}
					default -> warnings.add(where + ": unknown key \"" + entry.getKey() + "\"");
				}
			}

			if (bones.size() < 2) {
				warnings.add(where + ": needs a \"bones\" list of at least two bones; skipped");

				return null;
			}

			return new RagdollParams(bones, radius, gravity, damping, friction, (float)Math.toRadians(minAngle), parents, tips);
		}
		catch (RuntimeException ex) {
			warnings.add(where + ": cannot be read (" + ex.getMessage() + "); skipped");

			return null;
		}
	}

	/**
	 * {@code "autoColliders": false} turns the shapes made from the model's own geometry off, {@code true} forces them on, and an object tunes them:
	 * {@code maxCubes} (a bone with more cubes, or a mesh, becomes one low-poly proxy), {@code exclude} (bone name patterns, {@code *} at either end),
	 * {@code layer} and {@code maxShapes}. Absent: on when a part collides with {@code body} and no collider is authored
	 */
	private static AutoColliders.Options readAuto(JsonElement value, String where, List<String> warnings) {
		AutoColliders.Options d = AutoColliders.Options.DEFAULT;

		try {
			if (value.isJsonPrimitive())
				return new AutoColliders.Options(value.getAsBoolean(), d.maxCubes(), d.exclude(), d.layer(), d.maxShapes());

			JsonObject o = value.getAsJsonObject();
			Boolean enabled = o.has("enabled") ? Boolean.valueOf(o.get("enabled").getAsBoolean()) : null;
			int maxCubes = d.maxCubes();
			int maxShapes = d.maxShapes();
			String layer = d.layer();
			List<String> exclude = new ArrayList<>();
			Map<String, String> modes = new LinkedHashMap<>();
			String precision = d.precision();

			for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
				switch (entry.getKey()) {
					case "enabled" -> {}
					case "precision" -> {
						precision = entry.getValue().getAsString();

						if (!precision.equals("mesh") && !precision.equals("proxy"))
							warnings.add(where + ": unknown precision \"" + precision + "\" (mesh, proxy)");
					}
					case "maxCubes" -> maxCubes = Math.max(0, entry.getValue().getAsInt());
					case "maxShapes" -> maxShapes = Math.max(1, entry.getValue().getAsInt());
					case "layer" -> layer = entry.getValue().getAsString();
					case "exclude" -> entry.getValue().getAsJsonArray().forEach(e -> exclude.add(e.getAsString()));
					case "bones" -> {
						for (Map.Entry<String, JsonElement> bone : entry.getValue().getAsJsonObject().entrySet()) {
							String mode = bone.getValue().getAsString();

							if (mode.equals("off") || mode.equals("exact") || mode.equals("simple") || mode.equals("auto"))
								modes.put(bone.getKey(), mode);
							else
								warnings.add(where + ": bone \"" + bone.getKey() + "\": unknown mode \"" + mode + "\" (auto, off, exact, simple)");
						}
					}
					default -> warnings.add(where + ": unknown key \"" + entry.getKey() + "\"");
				}
			}

			return new AutoColliders.Options(enabled, maxCubes, exclude, layer, maxShapes, modes, precision);
		}
		catch (RuntimeException ex) {
			warnings.add(where + ": cannot be read (" + ex.getMessage() + "); using the default");

			return null;
		}
	}

	private static SpringParams apply(SpringParams start, JsonObject object, String where, List<String> warnings) {
		float[] frequency = {start.frequency()};
		float[] damping = {start.dampingRatio()};
		float[] inertia = {start.inertia()};
		float[] gravity = {start.gravity()};
		boolean[] rotate = {start.rotate()};
		boolean[] translate = {start.translate()};
		Vector3f tip = new Vector3f(start.tip());
		float[] length = {start.length()};
		float[][][] limit = {start.rotationLimit()};
		float[] maxTranslate = {start.translationLimit()};
		float[] translateScale = {start.translationScale()};
		float[] weight = {start.weight()};
		Set<String>[] collides = new Set[] {start.collidesWith()};
		float[] radius = {start.radius()};
		float[] squish = {start.squish()};
		boolean[] localRotation = {start.localRotation()};

		// A preset replaces the starting values before any explicit key is read, whatever order the keys are in
		if (object.has("preset")) {
			String name = object.get("preset").getAsString();
			SpringParams preset = PRESETS.get(name);

			if (preset == null) {
				warnings.add(where + ": unknown preset \"" + name + "\" (known: " + String.join(", ", PRESETS.keySet()) + ")");
			}
			else {
				frequency[0] = preset.frequency();
				damping[0] = preset.dampingRatio();
				inertia[0] = preset.inertia();
				gravity[0] = preset.gravity();
				rotate[0] = preset.rotate();
				translate[0] = preset.translate();
				tip.set(preset.tip());
				length[0] = preset.length();
				limit[0] = preset.rotationLimit();
				maxTranslate[0] = preset.translationLimit();
				translateScale[0] = preset.translationScale();
				weight[0] = preset.weight();
				collides[0] = preset.collidesWith();
				radius[0] = preset.radius();
				squish[0] = preset.squish();
			}
		}

		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			String key = entry.getKey();
			JsonElement value = entry.getValue();

			try {
				switch (key) {
					case "preset" -> {}
					case "frequency" -> frequency[0] = positive(value.getAsFloat(), key, where, warnings, frequency[0]);
					case "damping" -> damping[0] = Math.max(0f, value.getAsFloat());
					case "inertia" -> inertia[0] = clamp01(value.getAsFloat());
					case "gravity" -> gravity[0] = value.getAsFloat();
					case "rotate" -> rotate[0] = value.getAsBoolean();
					case "translate" -> translate[0] = value.getAsBoolean();
					case "tip" -> {
						JsonArray a = value.getAsJsonArray();
						Vector3f t = new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());

						if (t.lengthSquared() < 1e-8f)
							warnings.add(where + ": \"tip\" must not be the zero vector; kept " + tip);
						else
							tip.set(t.normalize());
					}
					case "length" -> length[0] = positive(value.getAsFloat(), key, where, warnings, length[0]);
					case "limit" -> limit[0] = readLimit(value.getAsJsonObject(), where, warnings);
					case "maxTranslate" -> maxTranslate[0] = Math.max(0f, value.getAsFloat());
					case "translateScale" -> translateScale[0] = value.getAsFloat();
					case "weight" -> weight[0] = clamp01(value.getAsFloat());
					case "collidesWith" -> {
						Set<String> layers = new LinkedHashSet<>();

						value.getAsJsonArray().forEach(e -> layers.add(e.getAsString()));
						collides[0] = layers;
					}
					case "radius" -> radius[0] = Math.max(0f, value.getAsFloat());
					case "squish" -> squish[0] = clamp01(value.getAsFloat());
					case "localRotation" -> localRotation[0] = value.getAsBoolean();
					case "collideBones", "ignoreBones" -> {}
					default -> warnings.add(where + ": unknown key \"" + key + "\"");
				}
			}
			catch (RuntimeException ex) {
				warnings.add(where + ": \"" + key + "\" has a value that cannot be read (" + ex.getMessage() + ")");
			}
		}

		return new SpringParams(frequency[0], damping[0], inertia[0], gravity[0], rotate[0], translate[0], tip, length[0], limit[0], maxTranslate[0], translateScale[0], weight[0], collides[0], radius[0], squish[0], localRotation[0]);
	}

	private static final Map<String, JsonObject> CHAIN_PRESETS = new LinkedHashMap<>();

	static {
		CHAIN_PRESETS.put("hair", JsonParser.parseString("{\"frequency\": 3, \"damping\": 0.3, \"gravity\": 9.8, \"radius\": 0.05, \"maxAngle\": 50, \"tipLength\": 0.15}").getAsJsonObject());
		CHAIN_PRESETS.put("tail", JsonParser.parseString("{\"frequency\": 1.6, \"damping\": 0.2, \"gravity\": 9.8, \"radius\": 0.06, \"maxAngle\": 70, \"tipLength\": 0.2}").getAsJsonObject());
		CHAIN_PRESETS.put("ear", JsonParser.parseString("{\"frequency\": 3.5, \"damping\": 0.2, \"gravity\": 4, \"radius\": 0.04, \"maxAngle\": 40, \"tipLength\": 0.1}").getAsJsonObject());
	}

	/**
	 * Keys: {@code bones} (the bone names from the root down, each the child of the one before), {@code preset} ({@code hair}, {@code tail}, {@code ear}),
	 * {@code frequency}, {@code damping}, {@code inertia}, {@code gravity}, {@code radius} (blocks), {@code maxAngle} (degrees), {@code tipLength} (blocks),
	 * {@code weight}, {@code friction}, {@code collidesWith} (layer names; default {@code ["body", "world"]}, {@code world} being blocks)
	 */
	private static void readChain(String name, JsonObject object, String source, List<String> warnings, Map<String, ChainParams> chains) {
		String where = source + " chain \"" + name + "\"";
		List<String> bones = new ArrayList<>();

		if (!object.has("bones") || object.getAsJsonArray("bones").size() < 1) {
			warnings.add(where + ": needs a \"bones\" list of at least one bone; skipped");

			return;
		}

		object.getAsJsonArray("bones").forEach(e -> bones.add(e.getAsString()));

		JsonObject merged = new JsonObject();

		if (object.has("preset")) {
			JsonObject preset = CHAIN_PRESETS.get(object.get("preset").getAsString());

			if (preset == null)
				warnings.add(where + ": unknown chain preset \"" + object.get("preset").getAsString() + "\" (known: " + String.join(", ", CHAIN_PRESETS.keySet()) + ")");
			else
				preset.entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
		}

		object.entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));

		float frequency = 3f;
		float damping = 0.3f;
		float inertia = 1f;
		float gravity = 9.8f;
		float radius = 0.05f;
		float maxAngle = 0f;
		float tipLength = 0.15f;
		float weight = 1f;
		Set<String> collides = new LinkedHashSet<>(List.of("body", "world"));
		float friction = 0.2f;

		for (Map.Entry<String, JsonElement> entry : merged.entrySet()) {
			JsonElement v = entry.getValue();

			try {
				switch (entry.getKey()) {
					case "bones", "preset", "collideBones", "ignoreBones" -> {}
					case "frequency" -> frequency = positive(v.getAsFloat(), "frequency", where, warnings, frequency);
					case "damping" -> damping = Math.max(0f, v.getAsFloat());
					case "inertia" -> inertia = clamp01(v.getAsFloat());
					case "gravity" -> gravity = v.getAsFloat();
					case "radius" -> radius = Math.max(0f, v.getAsFloat());
					case "maxAngle" -> maxAngle = (float)Math.toRadians(Math.max(0f, v.getAsFloat()));
					case "tipLength" -> tipLength = positive(v.getAsFloat(), "tipLength", where, warnings, tipLength);
					case "weight" -> weight = clamp01(v.getAsFloat());
					case "friction" -> friction = clamp01(v.getAsFloat());
					case "collidesWith" -> {
						collides.clear();
						v.getAsJsonArray().forEach(e -> collides.add(e.getAsString()));
					}
					default -> warnings.add(where + ": unknown key \"" + entry.getKey() + "\"");
				}
			}
			catch (RuntimeException ex) {
				warnings.add(where + ": \"" + entry.getKey() + "\" has a value that cannot be read (" + ex.getMessage() + ")");
			}
		}

		chains.put(name, new ChainParams(bones, frequency, damping, inertia, gravity, radius, maxAngle, tipLength, weight, collides, friction));
	}

	private static final Map<String, JsonObject> CLOTH_PRESETS = new LinkedHashMap<>();
	private static final Map<String, JsonObject> FLESH_PRESETS = new LinkedHashMap<>();

	static {
		CLOTH_PRESETS.put("cloth", JsonParser.parseString("{\"stiffness\": 0.9, \"bend\": 0.15, \"damping\": 0.6, \"gravity\": 9.8, \"inertia\": 0.6}").getAsJsonObject());
		FLESH_PRESETS.put("flesh-soft", JsonParser.parseString("{\"anchor\": 10, \"damping\": 2.5, \"volume\": 0.6, \"inertia\": 0.9}").getAsJsonObject());
		FLESH_PRESETS.put("flesh-firm", JsonParser.parseString("{\"anchor\": 25, \"damping\": 5, \"volume\": 0.4, \"inertia\": 0.8}").getAsJsonObject());
		FLESH_PRESETS.put("jelly", JsonParser.parseString("{\"anchor\": 4, \"damping\": 1.2, \"volume\": 0.9, \"inertia\": 1.0, \"gravity\": 1.5}").getAsJsonObject());
		CLOTH_PRESETS.put("cloth-silk", JsonParser.parseString("{\"stiffness\": 0.8, \"bend\": 0.0, \"damping\": 0.4, \"gravity\": 7, \"inertia\": 0.7}").getAsJsonObject());
		CLOTH_PRESETS.put("cloth-stiff", JsonParser.parseString("{\"stiffness\": 1.0, \"bend\": 0.6, \"damping\": 1.5, \"gravity\": 9.8, \"anchor\": 3, \"inertia\": 0.5}").getAsJsonObject());
	}

	/**
	 * {@code "cloth": {"skirt": {"bone": "skirt", "preset": "cloth", "pinHeight": 0.12}}}: the vertices of the bone's mesh, simulated. Keys: {@code bone}
	 * (required), {@code mesh} (which of the bone's meshes, by order; default every one), {@code preset} ({@code cloth}, {@code cloth-silk}, {@code cloth-stiff}),
	 * {@code stiffness}, {@code bend}, {@code damping}, {@code inertia}, {@code gravity}, {@code radius} (blocks), {@code pinHeight} (the share of the mesh's
	 * height, from the top, sewn to the bone), {@code anchor}, {@code weight}, {@code friction}, {@code iterations}, {@code maxParticles} and {@code collidesWith}
	 * (layer names; default {@code ["body", "world"]})
	 */
	private static void readCloth(String name, JsonObject object, String source, List<String> warnings, Map<String, ClothParams> cloth, boolean flesh) {
		String where = source + (flesh ? " flesh \"" : " cloth \"") + name + "\"";

		if (!object.has("bone")) {
			warnings.add(where + ": needs a \"bone\" whose mesh is the cloth; skipped");

			return;
		}

		JsonObject merged = new JsonObject();

		Map<String, JsonObject> presets = flesh ? FLESH_PRESETS : CLOTH_PRESETS;

		if (object.has("preset")) {
			JsonObject preset = presets.get(object.get("preset").getAsString());

			if (preset == null)
				warnings.add(where + ": unknown " + (flesh ? "flesh" : "cloth") + " preset \"" + object.get("preset").getAsString() + "\" (known: " + String.join(", ", presets.keySet()) + ")");
			else
				preset.entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
		}

		object.entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));

		String bone = object.get("bone").getAsString();
		int mesh = -1;
		float stiffness = flesh ? 0.95f : 0.9f;
		float bend = flesh ? 0f : 0.15f;
		float damping = flesh ? 3f : 0.6f;
		float inertia = flesh ? 0.9f : 0.6f;
		float gravity = flesh ? 2f : 9.8f;
		float radius = 0.02f;
		float pinHeight = flesh ? 0f : 0.1f;
		float anchor = flesh ? 12f : 0f;
		float weight = 1f;
		float friction = 0.3f;
		int iterations = flesh ? 4 : 6;
		int maxParticles = 600;
		float volume = flesh ? 0.6f : 0f;
		float[] softCenter = null;
		float softRadius = 0f;
		List<String> carry = new ArrayList<>();
		// flesh is pressed by other bodies and players by default: it is already part of the body it belongs to
		Set<String> collides = new LinkedHashSet<>(flesh ? List.of("other", "players", "mobs", "world") : List.of("body", "world"));

		for (Map.Entry<String, JsonElement> entry : merged.entrySet()) {
			JsonElement v = entry.getValue();

			try {
				switch (entry.getKey()) {
					case "bone", "preset", "collideBones", "ignoreBones" -> {}
					case "mesh" -> mesh = Math.max(-1, v.getAsInt());
					case "stiffness" -> stiffness = clamp01(v.getAsFloat());
					case "bend" -> bend = clamp01(v.getAsFloat());
					case "damping" -> damping = Math.max(0f, v.getAsFloat());
					case "inertia" -> inertia = clamp01(v.getAsFloat());
					case "gravity" -> gravity = v.getAsFloat();
					case "radius" -> radius = Math.max(0f, v.getAsFloat());
					case "pinHeight" -> pinHeight = clamp01(v.getAsFloat());
					case "anchor" -> anchor = Math.max(0f, v.getAsFloat());
					case "weight" -> weight = clamp01(v.getAsFloat());
					case "friction" -> friction = clamp01(v.getAsFloat());
					case "iterations" -> iterations = Math.max(1, Math.min(32, v.getAsInt()));
					case "maxParticles" -> maxParticles = Math.max(3, v.getAsInt());
					case "carry" -> {
						carry.clear();
						v.getAsJsonArray().forEach(e -> carry.add(e.getAsString()));
					}
					case "volume" -> volume = clamp01(v.getAsFloat());
					case "softRadius" -> softRadius = Math.max(0f, v.getAsFloat());
					case "softCenter" -> softCenter = new float[] {v.getAsJsonArray().get(0).getAsFloat(), v.getAsJsonArray().get(1).getAsFloat(), v.getAsJsonArray().get(2).getAsFloat()};
					case "collidesWith" -> {
						collides.clear();
						v.getAsJsonArray().forEach(e -> collides.add(e.getAsString()));
					}
					default -> warnings.add(where + ": unknown key \"" + entry.getKey() + "\"");
				}
			}
			catch (RuntimeException ex) {
				warnings.add(where + ": \"" + entry.getKey() + "\" has a value that cannot be read (" + ex.getMessage() + ")");
			}
		}

		cloth.put(name, new ClothParams(bone, mesh, stiffness, bend, damping, inertia, gravity, radius, pinHeight, anchor, weight, collides, friction, iterations, maxParticles, volume, softCenter, softRadius, carry));
	}

	/**
	 * A collision shape on a bone: {@code {"bone": "head", "shape": "sphere", "center": [x, y, z], "radius": r}}, {@code "capsule"} with {@code "from"},
	 * {@code "to"} and {@code "radius"}, or {@code "box"} with {@code "center"} and {@code "size"}. Positions are relative to the bone pivot, in model units;
	 * {@code "layer"} (default {@code "body"}) is the group a chain names in {@code collidesWith}
	 */
	private static void readCollider(int index, JsonObject object, String source, List<String> warnings, List<Collider> colliders) {
		String where = source + " collider #" + index;

		try {
			String bone = object.get("bone").getAsString();
			String kind = object.get("shape").getAsString();
			String layer = object.has("layer") ? object.get("layer").getAsString() : "body";

			switch (kind) {
				case "sphere" -> colliders.add(new Collider(bone, Collider.Shape.SPHERE, vec(object.getAsJsonArray("center")), new Vector3f(), object.get("radius").getAsFloat(), layer));
				case "capsule" -> colliders.add(new Collider(bone, Collider.Shape.CAPSULE, vec(object.getAsJsonArray("from")), vec(object.getAsJsonArray("to")),
						object.get("radius").getAsFloat(), layer));
				case "box" -> colliders.add(new Collider(bone, Collider.Shape.BOX, vec(object.getAsJsonArray("center")), vec(object.getAsJsonArray("size")), 0f, layer,
						object.has("rotation") ? vec(object.getAsJsonArray("rotation")) : new Vector3f()));
				default -> warnings.add(where + ": unknown shape \"" + kind + "\" (sphere, capsule, box); skipped");
			}
		}
		catch (RuntimeException ex) {
			warnings.add(where + ": cannot be read (" + ex.getMessage() + "); skipped");
		}
	}

	private static Vector3f vec(JsonArray a) {
		return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
	}

	private static float[][] readLimit(JsonObject object, String where, List<String> warnings) {
		float[][] limit = new float[3][];
		String[] axes = {"x", "y", "z"};

		for (int i = 0; i < 3; i++) {
			if (!object.has(axes[i]))
				continue;

			JsonArray range = object.getAsJsonArray(axes[i]);
			float min = (float)Math.toRadians(range.get(0).getAsFloat());
			float max = (float)Math.toRadians(range.get(1).getAsFloat());

			if (min > max) {
				warnings.add(where + ": limit " + axes[i] + " has min above max; swapped");

				float t = min;

				min = max;
				max = t;
			}

			limit[i] = new float[] {min, max};
		}

		for (String key : object.keySet()) {
			if (!key.equals("x") && !key.equals("y") && !key.equals("z"))
				warnings.add(where + ": unknown limit axis \"" + key + "\"");
		}

		return limit;
	}

	private static float positive(float value, String key, String where, List<String> warnings, float fallback) {
		if (value > 0f && Float.isFinite(value))
			return value;

		warnings.add(where + ": \"" + key + "\" must be above 0; kept " + fallback);

		return fallback;
	}

	private static float clamp01(float value) {
		return Math.max(0f, Math.min(1f, value));
	}

	/** For tests and tools: call {@code each} for every bone */
	void forEachBone(Consumer<Map.Entry<String, SpringParams>> each) {
		this.bones.entrySet().forEach(each);
	}
}
