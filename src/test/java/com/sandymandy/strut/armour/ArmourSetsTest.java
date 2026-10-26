package com.sandymandy.strut.armour;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmourSetsTest {
	private static final Identifier CREATURE = Identifier.of("mymod", "creature");
	private static final Identifier AVA = Identifier.of("mymod", "ava");

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	/** The models the "folders" hold: mymod:armour/ava has the four, anything else is empty */
	private static final Function<Identifier, Map<String, Identifier>> FILES = folder -> {
		Map<String, Identifier> found = new LinkedHashMap<>();

		if (folder.equals(AVA.withPath("armour/ava"))) {
			for (String slot : List.of("head", "chest", "legs", "feet"))
				found.put(slot, Identifier.of("mymod", "strut/models/armour/ava/" + slot + ".strutmodel"));
		}

		return found;
	};

	private static ArmourSets.Result resolve(Map<Identifier, JsonObject> raw) {
		return ArmourSets.resolve(raw, FILES, message -> {});
	}

	private static Map<Identifier, JsonObject> files() {
		Map<Identifier, JsonObject> raw = new LinkedHashMap<>();

		raw.put(CREATURE, json("{\"texture\": \"mymod:textures/armor/armor.png\", \"texture_size\": [4096, 4096], \"column\": [72, 0]}"));
		raw.put(AVA, json("{\"extends\": \"mymod:creature\"}"));

		return raw;
	}

	@Test
	void aSetFindsItsModelsInTheFolderNamedForItsFileAndTakesTheRestFromWhatItExtends() {
		ArmourSets.Result result = resolve(files());
		ArmourSet ava = result.sets().get(AVA);

		assertTrue(result.problems().isEmpty(), result.problems().toString());
		assertNotNull(ava);
		assertEquals(Identifier.of("mymod", "strut/models/armour/ava/chest.strutmodel"), ava.model("chest"));
		assertEquals(Identifier.of("mymod", "textures/armor/armor.png"), ava.texture());
		assertEquals(4096, ava.textureWidth());
		assertEquals(72, ava.cellWidth());
		assertTrue(result.templates().contains(CREATURE), "a set with no models is a template, not a problem");
		assertNull(result.sets().get(CREATURE));
	}

	@Test
	void anExplicitModelsFolderIsUsedAndAnEmptyOneIsAProblem() {
		Map<Identifier, JsonObject> raw = files();

		raw.put(Identifier.of("mymod", "mia"), json("{\"extends\": \"mymod:creature\", \"models\": \"mymod:armour/ava\"}"));
		raw.put(Identifier.of("mymod", "ghost"), json("{\"extends\": \"mymod:creature\", \"models\": \"mymod:armour/nobody\"}"));

		ArmourSets.Result result = resolve(raw);

		assertNotNull(result.sets().get(Identifier.of("mymod", "mia")));
		assertEquals(1, result.problems().size());
		assertTrue(result.problems().get(0).contains("mymod:armour/nobody"), result.problems().get(0));
	}

	@Test
	void aSlotNamedInTheSetWinsOverTheFile() {
		Map<Identifier, JsonObject> raw = files();

		raw.put(AVA, json("{\"extends\": \"mymod:creature\", \"slots\": {\"head\": \"mymod:armour/special_head\"}}"));

		assertEquals(Identifier.of("mymod", "strut/models/armour/special_head.strutmodel"), resolve(raw).sets().get(AVA).model("head"));
	}

	@Test
	void extendsThatLoopOrMissAreProblems() {
		Map<Identifier, JsonObject> raw = new LinkedHashMap<>();

		raw.put(CREATURE, json("{\"extends\": \"mymod:ava\"}"));
		raw.put(AVA, json("{\"extends\": \"mymod:creature\"}"));
		raw.put(Identifier.of("mymod", "lost"), json("{\"extends\": \"mymod:nothing\"}"));

		assertEquals(3, resolve(raw).problems().size());
	}

	private static Map<Identifier, WearerProfile> wearers(String text) {
		WearerProfile profile = WearerProfile.parse(Identifier.of("mymod", "ava"), json(text), message -> {});

		return Map.of(profile.id(), profile);
	}

	@Test
	void aWearerWithItsModelsPassesAndOneMissingASlotOrASetNamesWhatToAdd() {
		ArmourSets.Result result = resolve(files());
		Map<Identifier, WearerProfile> fine = wearers("{\"armour_set\": \"mymod:ava\", \"slots\": {\"chest\": {\"covers\": [\"pouchL\"]}}}");

		assertTrue(ArmourSets.validate(result, fine, Map.of()).isEmpty());

		List<String> noSet = ArmourSets.validate(result, wearers("{\"armour_set\": \"mymod:nobody\"}"), Map.of());

		assertEquals(1, noSet.size());
		assertTrue(noSet.get(0).contains("strut/models/armour/nobody/"), noSet.get(0));

		Map<Identifier, JsonObject> raw = files();

		raw.put(Identifier.of("mymod", "mia"), json("{\"extends\": \"mymod:creature\", \"slots\": {\"head\": \"mymod:armour/h\"}}"));

		List<String> noChest = ArmourSets.validate(resolve(raw), wearers("{\"armour_set\": \"mymod:mia\", \"slots\": {\"chest\": {\"covers\": [\"pouchL\"]}}}"), Map.of());

		assertEquals(1, noChest.size());
		assertTrue(noChest.get(0).contains("chest.strutmodel"), noChest.get(0));
	}

	@Test
	void aRegisteredWearerWithoutAProfileOrAModelFileNamedForNoSlotIsAProblem() {
		ArmourSets.Result result = resolve(files());

		assertEquals(1, ArmourSets.validate(result, Map.of(), Map.of(Identifier.of("mymod", "creature_entity"), AVA)).size());

		Map<Identifier, JsonObject> raw = files();

		raw.put(AVA, json("{\"extends\": \"mymod:creature\", \"slots\": {\"hat\": \"mymod:armour/hat\"}}"));

		List<String> unknown = ArmourSets.validate(resolve(raw), wearers("{\"armour_set\": \"mymod:ava\"}"), Map.of());

		assertEquals(1, unknown.size());
		assertTrue(unknown.get(0).contains("hat"), unknown.get(0));
	}
}
