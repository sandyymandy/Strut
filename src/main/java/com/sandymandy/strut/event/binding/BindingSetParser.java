package com.sandymandy.strut.event.binding;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Reads one binding file's JSON into a {@link BindingSet}
 * <pre>
 * {
 *   "bindings": {
 *     "pound":      { "sounds": ["mymod:misc.impact"] },
 *     "thumpmsg1":   { "sounds": ["mymod:thump"],
 *                     "random_sounds": ["mymod:ahh", { "sound": "mymod:mmm", "weight": 2 }, { "none": true, "weight": 3 }],
 *                     "mymod:messages": ["Hello"] }
 *   }
 * }
 * </pre>
 * Every problem is a message naming the file and the key, and skips only what is wrong: one bad sound must
 * not silence a character, and a bad file must not stop a resource reload.
 */
public final class BindingSetParser {
	private static final Pattern KEY = Pattern.compile("[a-z0-9_./-]+");

	private BindingSetParser() {}

	/**
	 * @param source A name for the file, for messages
	 */
	public static BindingSet parse(Identifier id, JsonObject root, EventBindingActions actions, String source, Consumer<String> warn) {
		Map<String, Binding> bindings = new LinkedHashMap<>();
		JsonElement table = root.get("bindings");

		if (table == null || !table.isJsonObject()) {
			warn.accept(source + ": expected a \"bindings\" object mapping each key to what it does; this file binds nothing");

			return new BindingSet(id, bindings);
		}

		for (Map.Entry<String, JsonElement> entry : table.getAsJsonObject().entrySet()) {
			String key = entry.getKey().toLowerCase(Locale.ROOT);
			String where = source + " key \"" + entry.getKey() + "\"";

			if (!KEY.matcher(key).matches()) {
				warn.accept(where + ": not a valid binding key (letters, digits, _ . / - only); skipped");

				continue;
			}

			if (!entry.getValue().isJsonObject()) {
				warn.accept(where + ": expected an object of actions; skipped");

				continue;
			}

			if (bindings.containsKey(key))
				warn.accept(where + ": duplicates another key once lower-cased; the later one wins");

			bindings.put(key, parseBinding(entry.getValue().getAsJsonObject(), actions, where, warn));
		}

		return new BindingSet(id, bindings);
	}

	private static Binding parseBinding(JsonObject object, EventBindingActions actions, String where, Consumer<String> warn) {
		List<Identifier> sounds = new ArrayList<>();
		List<WeightedSound> randomSounds = new ArrayList<>();
		Map<Identifier, JsonElement> custom = new LinkedHashMap<>();

		for (Map.Entry<String, JsonElement> action : object.entrySet()) {
			switch (action.getKey()) {
				case "sounds" -> parseSounds(action.getValue(), sounds, where, warn);
				case "random_sounds" -> parseRandomSounds(action.getValue(), randomSounds, where, warn);
				default -> parseCustom(action.getKey(), action.getValue(), actions, custom, where, warn);
			}
		}

		return new Binding(sounds, randomSounds, custom);
	}

	private static void parseSounds(JsonElement value, List<Identifier> out, String where, Consumer<String> warn) {
		if (!value.isJsonArray()) {
			warn.accept(where + " \"sounds\": expected an array of sound ids; skipped");

			return;
		}

		for (JsonElement element : value.getAsJsonArray()) {
			Identifier sound = soundId(element, where + " \"sounds\"", warn);

			if (sound != null)
				out.add(sound);
		}
	}

	private static void parseRandomSounds(JsonElement value, List<WeightedSound> out, String where, Consumer<String> warn) {
		String here = where + " \"random_sounds\"";

		if (!value.isJsonArray()) {
			warn.accept(here + ": expected an array; skipped");

			return;
		}

		for (JsonElement element : (JsonArray)value) {
			if (element.isJsonPrimitive()) {
				Identifier sound = soundId(element, here, warn);

				if (sound != null)
					out.add(new WeightedSound(sound, 1));

				continue;
			}

			if (!element.isJsonObject()) {
				warn.accept(here + ": each entry is a sound id or an object; skipped one");

				continue;
			}

			JsonObject entry = element.getAsJsonObject();
			int weight = weightOf(entry, here, warn);
			boolean silence = entry.has("none") && entry.get("none").isJsonPrimitive() && entry.get("none").getAsBoolean();
			boolean hasSound = entry.has("sound");

			if (silence == hasSound) {
				warn.accept(here + ": an entry names either \"sound\" or \"none\": true, not both or neither; skipped one");

				continue;
			}

			if (silence) {
				out.add(new WeightedSound(null, weight));
			}
			else {
				Identifier sound = soundId(entry.get("sound"), here, warn);

				if (sound != null)
					out.add(new WeightedSound(sound, weight));
			}
		}
	}

	private static int weightOf(JsonObject entry, String where, Consumer<String> warn) {
		if (!entry.has("weight"))
			return 1;

		JsonElement weight = entry.get("weight");

		if (weight.isJsonPrimitive() && weight.getAsJsonPrimitive().isNumber()) {
			int value = weight.getAsInt();

			if (value >= 1)
				return value;
		}

		warn.accept(where + ": a weight is a whole number of at least 1; using 1");

		return 1;
	}

	private static void parseCustom(String actionKey, JsonElement value, EventBindingActions actions, Map<Identifier, JsonElement> out,
									String where, Consumer<String> warn) {
		if (actionKey.indexOf(':') < 0) {
			warn.accept(where + ": unknown action \"" + actionKey + "\" - besides sounds and random_sounds, an action is a namespaced id "
					+ "such as mymod:" + actionKey + ", registered by the mod that owns it; skipped");

			return;
		}

		Identifier id = Identifier.tryParse(actionKey);

		if (id == null) {
			warn.accept(where + ": \"" + actionKey + "\" is not a valid action id; skipped");

			return;
		}

		if (!actions.isRegistered(id)) {
			warn.accept(where + ": no action " + id + " is registered - is the mod that provides it installed? skipped");

			return;
		}

		out.put(id, value);
	}

	/**
	 * A sound id, which must carry its namespace: a bare word would otherwise quietly mean {@code minecraft:}
	 */
	@Nullable
	private static Identifier soundId(JsonElement element, String where, Consumer<String> warn) {
		if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
			warn.accept(where + ": a sound is written as a namespaced id string; skipped one");

			return null;
		}

		String text = primitive.getAsString();
		Identifier id = text.indexOf(':') >= 0 ? Identifier.tryParse(text) : null;

		if (id == null) {
			warn.accept(where + ": \"" + text + "\" is not a valid sound id (needs a namespace, like mymod:my.sound); skipped one");

			return null;
		}

		return id;
	}
}
