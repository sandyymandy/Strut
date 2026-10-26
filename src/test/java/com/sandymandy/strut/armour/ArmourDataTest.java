package com.sandymandy.strut.armour;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmourDataTest {
	private static final List<String> WARNINGS = new ArrayList<>();

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	private static Identifier id(String text) {
		return Identifier.of("mod", text);
	}

	private static ArmourLook look(String name, String text) {
		return ArmourLook.parse(id(name), json(text), WARNINGS::add);
	}

	@Test
	void aSetReadsItsModelsAndTurnsAColumnIntoAUvOffset() {
		ArmourSet set = ArmourSet.parse(id("creature"), json("{\"slots\": {\"head\": \"mod:armour/head\", \"chest\": \"mod:geo/armour/chest.strutmodel\"},"
				+ "\"texture\": \"mod:textures/armor/armor.png\", \"texture_size\": [4096, 4096], \"column\": [72, 0]}"), WARNINGS::add);

		assertNotNull(set);
		assertEquals(id("strut/models/armour/head.strutmodel"), set.model("head"), "the model id is the one the game loads it as");
		assertEquals(id("strut/models/armour/chest.strutmodel"), set.model("chest"));
		assertNull(set.model("feet"));
		assertEquals(0.017578125f, set.uvOffset(1, 0)[0], 1e-9f, "the column a consumer mod's atlas uses for diamond: 72 / 4096");
		assertEquals(0.017578125f * 6, set.uvOffset(6, 0)[0], 1e-6f);
	}

	@Test
	void aSetWithoutSlotsOrTextureIsRejected() {
		assertNull(ArmourSet.parse(id("bad"), json("{\"texture\": \"mod:t.png\"}"), WARNINGS::add));
		assertNull(ArmourSet.parse(id("bad"), json("{\"slots\": {\"head\": \"mod:h\"}}"), WARNINGS::add));
	}

	@Test
	void aProfileExtendsAnotherAndOnlySaysWhatDiffers() {
		WearerProfile base = WearerProfile.parse(id("base"), json("{\"armour_set\": \"mod:creature\", \"slots\": {\"chest\": {\"covers\": [\"pouchL\", \"pouchR\"]},"
				+ "\"legs\": {\"covers\": [\"belly\"]}}}"), WARNINGS::add);
		WearerProfile imp = WearerProfile.parse(id("imp"), json("{\"extends\": \"mod:base\", \"slots\": {\"legs\": {\"covers\": [\"belly\", \"tail\"]}}}"), WARNINGS::add);
		Map<Identifier, WearerProfile> all = Map.of(base.id(), base, imp.id(), imp);
		WearerProfile resolved = imp.resolve(all::get);

		assertEquals(id("creature"), resolved.armourSet(), "the set comes from the parent");
		assertEquals(List.of("pouchL", "pouchR"), resolved.coversOf("chest"));
		assertEquals(List.of("belly", "tail"), resolved.coversOf("legs"), "a slot it lists replaces the parent's");
		assertEquals(List.of(), resolved.coversOf("head"));
	}

	@Test
	void aLoopOfParentsResolvesToNothing() {
		WearerProfile a = WearerProfile.parse(id("a"), json("{\"extends\": \"mod:b\"}"), WARNINGS::add);
		WearerProfile b = WearerProfile.parse(id("b"), json("{\"extends\": \"mod:a\"}"), WARNINGS::add);
		Map<Identifier, WearerProfile> all = Map.of(a.id(), a, b.id(), b);

		assertNull(a.resolve(all::get));
	}

	@Test
	void theLookThatNamesTheItemMostExactlyWins() {
		Identifier set = id("creature");
		ArmourLook anything = look("any", "{\"column\": 9}");
		ArmourLook material = look("material", "{\"match\": {\"materials\": [\"minecraft:diamond\"]}, \"column\": 1}");
		ArmourLook tag = look("tag", "{\"match\": {\"tags\": [\"mod:shiny\"]}, \"column\": 2}");
		ArmourLook item = look("item", "{\"match\": {\"items\": [\"minecraft:diamond_helmet\"]}, \"column\": 3}");
		List<ArmourLook> all = List.of(anything, material, tag, item);
		Identifier helmet = Identifier.ofVanilla("diamond_helmet");
		Identifier diamond = Identifier.ofVanilla("diamond");

		assertEquals(3, ArmourData.best(all, helmet, t -> true, diamond, set, "head").column());
		assertEquals(2, ArmourData.best(all, Identifier.ofVanilla("diamond_boots"), t -> t.equals(id("shiny")), diamond, set, "feet").column());
		assertEquals(1, ArmourData.best(all, Identifier.ofVanilla("diamond_boots"), t -> false, diamond, set, "feet").column());
		assertEquals(9, ArmourData.best(all, Identifier.ofVanilla("stick"), t -> false, null, set, "head").column(), "an item nothing names gets the look that matches everything");
	}

	@Test
	void aLookForOneSetIsNotUsedForAnother() {
		ArmourLook forKobold = look("imp", "{\"set\": \"mod:imp\", \"match\": {\"materials\": [\"minecraft:iron\"]}, \"column\": 4}");
		ArmourLook forAll = look("all", "{\"match\": {\"materials\": [\"minecraft:iron\"]}, \"column\": 3}");
		List<ArmourLook> all = List.of(forAll, forKobold);
		Identifier iron = Identifier.ofVanilla("iron");

		assertEquals(4, ArmourData.best(all, Identifier.ofVanilla("iron_helmet"), t -> false, iron, id("imp"), "head").column(), "the look for this set outranks the general one");
		assertEquals(3, ArmourData.best(all, Identifier.ofVanilla("iron_helmet"), t -> false, iron, id("creature"), "head").column());
	}

	@Test
	void aLookCanBeLimitedToSlotsAndNothingIsMatchedByName() {
		ArmourLook helmets = look("helm", "{\"slots\": [\"head\"], \"match\": {\"materials\": [\"minecraft:iron\"]}, \"column\": 5}");
		Identifier iron = Identifier.ofVanilla("iron");

		assertNull(ArmourData.best(List.of(helmets), Identifier.ofVanilla("iron_boots"), t -> false, iron, id("creature"), "feet"));
		assertNull(ArmourData.best(List.of(helmets), Identifier.of("mod", "iron_helmet"), t -> false, null, id("creature"), "head"), "an item called iron_helmet but with no iron material is not iron");
	}

	@Test
	void tintIsDyeFixedOrNone() {
		ArmourLook leather = look("leather", "{\"tint\": \"dye\", \"undyed\": \"#A06540\"}");
		ArmourLook red = look("red", "{\"tint\": \"#FF0000\"}");
		ArmourLook plain = look("plain", "{}");

		assertEquals(0xFFA06540, leather.tintFor(-1), "undyed leather is brown");
		assertEquals(0xFF123456, leather.tintFor(0x123456), "dyed leather is the dye");
		assertEquals(0xFFFF0000, red.tintFor(0x123456));
		assertEquals(0xFFFFFFFF, plain.tintFor(0x123456));
	}

	@Test
	void trimsAreOptionalAndFilledInFromThePatternAndMaterial() {
		ArmourSet plain = ArmourSet.parse(id("plain"), json("{\"slots\": {\"head\": \"mod:h\"}, \"texture\": \"mod:t.png\"}"), WARNINGS::add);
		ArmourSet trimmed = ArmourSet.parse(id("trimmed"), json("{\"slots\": {\"head\": \"mod:h\"}, \"texture\": \"mod:t.png\","
				+ "\"trims\": {\"texture\": \"mod:textures/trims/{pattern}.png\", \"colours\": {\"mod:ruby\": \"#FF0000\", \"minecraft:gold\": \"#010203\"}}}"), WARNINGS::add);

		assertTrue(!plain.hasTrims(), "a set that says nothing about trims has none");
		assertNull(plain.trimTextureFor(Identifier.ofVanilla("coast")));
		assertTrue(trimmed.hasTrims());
		assertEquals(Identifier.of("mod", "textures/trims/coast.png"), trimmed.trimTextureFor(Identifier.of("mod", "coast")));
		assertEquals(0xFF0000, trimmed.trimColour(Identifier.of("mod", "ruby")), "a material the set colours");
		assertEquals(0x010203, trimmed.trimColour(Identifier.ofVanilla("gold")), "the set's colour over the vanilla one");
		assertEquals(0xE3D4C4, trimmed.trimColour(Identifier.ofVanilla("quartz")), "a vanilla material is built in");
		assertEquals(0xFFFFFF, trimmed.trimColour(Identifier.of("mod", "unknown")));
	}

	@Test
	void aWearerCanTurnTrimsOffAndItsChildInheritsThat() {
		WearerProfile base = WearerProfile.parse(id("base"), json("{\"armour_set\": \"mod:creature\", \"trims\": false}"), WARNINGS::add);
		WearerProfile child = WearerProfile.parse(id("child"), json("{\"extends\": \"mod:base\"}"), WARNINGS::add);
		WearerProfile other = WearerProfile.parse(id("other"), json("{\"armour_set\": \"mod:creature\"}"), WARNINGS::add);
		Map<Identifier, WearerProfile> all = Map.of(base.id(), base, child.id(), child, other.id(), other);

		assertTrue(!child.resolve(all::get).showsTrims());
		assertTrue(other.resolve(all::get).showsTrims(), "trims show unless the profile says not");
	}

	@Test
	void animalArmourIsOneSlotCalledBody() {
		ArmourSet horse = ArmourSet.parse(id("horse"), json("{\"slots\": {\"body\": \"mod:armour/horse_body\"}, \"texture\": \"mod:t.png\", \"texture_size\": [64, 64], \"column\": [16, 0]}"), WARNINGS::add);
		WearerProfile profile = WearerProfile.parse(id("horse"), json("{\"armour_set\": \"mod:horse\", \"slots\": {\"body\": {\"covers\": [\"saddle_pad\"]}}}"), WARNINGS::add);
		ArmourLook anyArmour = look("any", "{\"match\": {\"materials\": [\"minecraft:iron\"]}, \"column\": 2}");
		ArmourLook carpet = look("carpet", "{\"match\": {\"tags\": [\"minecraft:wool_carpets\"]}, \"column\": 5}");

		assertEquals(id("strut/models/armour/horse_body.strutmodel"), horse.model("body"));
		assertNull(horse.model("chest"), "an animal's set has no other slot, so nothing is drawn for them");
		assertEquals(List.of("saddle_pad"), profile.coversOf("body"));
		assertEquals(2, ArmourData.best(List.of(anyArmour, carpet), Identifier.ofVanilla("iron_horse_armor"), t -> false, Identifier.ofVanilla("iron"), horse.id(), "body").column());
		assertEquals(5, ArmourData.best(List.of(anyArmour, carpet), Identifier.ofVanilla("white_carpet"), t -> t.equals(Identifier.ofVanilla("wool_carpets")), null, horse.id(), "body").column(),
				"a llama's carpet is its body slot too, matched by tag");
	}

	@Test
	void glintIsOnByDefaultAtTheGamesScaleAndTheSetCanChangeOrDisableIt() {
		String base = "{\"slots\": {\"head\": \"mod:h\"}, \"texture\": \"mod:t.png\", \"texture_size\": [4096, 4096], \"column\": [72, 0]";
		ArmourSet vanilla = ArmourSet.parse(id("a"), json(base + "}"), WARNINGS::add);
		ArmourSet off = ArmourSet.parse(id("b"), json(base + ", \"glint\": false}"), WARNINGS::add);
		ArmourSet custom = ArmourSet.parse(id("c"), json(base + ", \"glint\": {\"texture\": \"mod:g.png\", \"strength\": 0.25, \"repeat\": 2}}"), WARNINGS::add);

		assertEquals(Identifier.ofVanilla("textures/misc/enchanted_glint_armor.png"), vanilla.glint().texture());
		assertEquals(0.16f, vanilla.glint().repeat(), "the scale the game draws armour glint with: a coarse sheen, not a fine pattern that averages to a flat colour");
		assertNull(off.glint().texture(), "no texture means no glint");
		assertEquals(Identifier.of("mod", "g.png"), custom.glint().texture());
		assertEquals(0.25f, custom.glint().strength());
		assertEquals(2f, custom.glint().repeat());
	}

	@Test
	void aWearerCanTurnGlintOffAndItsChildInheritsThat() {
		WearerProfile base = WearerProfile.parse(id("base"), json("{\"armour_set\": \"mod:creature\", \"glint\": false}"), WARNINGS::add);
		WearerProfile child = WearerProfile.parse(id("child"), json("{\"extends\": \"mod:base\"}"), WARNINGS::add);
		Map<Identifier, WearerProfile> all = Map.of(base.id(), base, child.id(), child);

		assertTrue(!child.resolve(all::get).showsGlint());
		assertTrue(WearerProfile.parse(id("x"), json("{}"), WARNINGS::add).showsGlint());
	}

	@Test
	void aLookCanNameAnOverlayCell() {
		ArmourLook leather = look("leather", "{\"match\": {\"materials\": [\"minecraft:leather\"]}, \"column\": 6, \"overlay_column\": 8, \"overlay_row\": 1}");

		assertEquals(8, leather.overlayColumn());
		assertEquals(1, leather.overlayRow());
		assertNull(look("plain", "{\"column\": 1}").overlayColumn(), "no overlay unless a look names one");
	}

	@Test
	void aProfileGivesTheBoneMapAndFitOfAWearerAndItsChildrenAddTo() {
		WearerProfile base = WearerProfile.parse(id("base"), json("{\"armour_set\": \"mod:creature\", \"bones\": {\"armorHead\": \"head\", \"armorBody\": \"upperBody\"},"
				+ "\"fit\": {\"armorHead\": {\"offset\": [0, 2, 0], \"scale\": 1.1}}}"), WARNINGS::add);
		WearerProfile child = WearerProfile.parse(id("child"), json("{\"extends\": \"mod:base\", \"bones\": {\"armorBody\": \"torso\"}}"), WARNINGS::add);
		Map<Identifier, WearerProfile> all = Map.of(base.id(), base, child.id(), child);
		WearerProfile resolved = child.resolve(all::get);

		assertEquals("head", resolved.bones().get("armorHead"));
		assertEquals("torso", resolved.bones().get("armorBody"), "the child's own mapping wins");
		assertEquals(2f, resolved.fit().get("armorHead").offset()[1]);
		assertEquals(1.1f, resolved.fit().get("armorHead").scale()[2], 1e-6f, "a single number scales every axis");
		assertEquals(0f, resolved.fit().get("armorHead").rotation()[0]);
	}
}
