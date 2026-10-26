package com.sandymandy.strut.event.binding;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading a binding file. The fixtures are the shapes a consumer mod's real
 * {@code keyframe_events} files use, expressed in the new format
 */
class BindingSetParserTest {
	private static final Identifier SET = Identifier.of("mymod", "creatures/ava");
	private static final Identifier MESSAGES = Identifier.of("mymod", "messages");

	private final List<String> warnings = new ArrayList<>();
	private final EventBindingActions actions = new EventBindingActions();

	private BindingSet parse(String json) {
		if (!this.actions.isRegistered(MESSAGES))
			this.actions.register(MESSAGES, (ctx, config) -> {});

		return BindingSetParser.parse(SET, JsonParser.parseString(json).getAsJsonObject(), this.actions, "ava.json", this.warnings::add);
	}

	@Test
	@DisplayName("fixed sounds, all of which play, are read in order")
	void fixedSounds() {
		BindingSet set = parse("{\"bindings\": {\"pound\": {\"sounds\": [\"mymod:misc.impact\", \"mymod:misc.bedrustle\"]}}}");

		assertEquals(List.of(Identifier.of("mymod", "misc.impact"), Identifier.of("mymod", "misc.bedrustle")),
				set.get("pound").sounds());
		assertTrue(this.warnings.isEmpty(), this.warnings.toString());
	}

	@Test
	@DisplayName("one key holds several actions at once - a fixed thump and a random cry, as danceFastMSG1 does")
	void severalActionsInOneKey() {
		BindingSet set = parse("""
				{"bindings": {"danceFastMSG1": {
				  "sounds": ["mymod:misc.impact"],
				  "random_sounds": ["mymod:ava.ahh", "mymod:ava.mmm"],
				  "mymod:messages": ["Hello"]
				}}}""");

		Binding binding = set.get("danceFastMSG1");

		assertEquals(1, binding.sounds().size());
		assertEquals(2, binding.randomSounds().size());
		assertEquals(1, binding.custom().size());
		assertTrue(binding.custom().containsKey(MESSAGES));
		assertTrue(this.warnings.isEmpty(), this.warnings.toString());
	}

	@Test
	@DisplayName("a random pool takes bare ids (weight 1), weighted ids, and an explicit silence in place of the minecraft:empty padding trick")
	void weightedPoolWithSilence() {
		BindingSet set = parse("""
				{"bindings": {"cry": {"random_sounds": [
				  "mymod:imp.cry",
				  {"sound": "mymod:imp.haa", "weight": 2},
				  {"none": true, "weight": 3}
				]}}}""");

		List<WeightedSound> pool = set.get("cry").randomSounds();

		assertEquals(new WeightedSound(Identifier.of("mymod", "imp.cry"), 1), pool.get(0));
		assertEquals(new WeightedSound(Identifier.of("mymod", "imp.haa"), 2), pool.get(1));
		assertEquals(new WeightedSound(null, 3), pool.get(2));
		assertTrue(this.warnings.isEmpty(), this.warnings.toString());
	}

	@Test
	@DisplayName("keys are lower-cased, so a table and a clip that disagree on capitals still meet")
	void keysAreLowerCased() {
		BindingSet set = parse("{\"bindings\": {\"doggyFastMSG1\": {\"sounds\": [\"mymod:x\"]}}}");

		assertNotNull(set.get("doggyfastmsg1"));
		assertNotNull(set.get("DOGGYFASTMSG1"), "lookup lower-cases too");
		assertTrue(set.bindings().containsKey("doggyfastmsg1"));
	}

	@Test
	@DisplayName("the consumer mod's old un-namespaced actions warn and are skipped: messages must become a namespaced action a mod registers")
	void legacyMessagesKeyWarns() {
		BindingSet set = parse("{\"bindings\": {\"bjiMSG1\": {\"sounds\": [\"mymod:mmm\"], \"messages\": [\"What are you...\"]}}}");

		assertEquals(1, set.get("bjimsg1").sounds().size(), "the sound still made it");
		assertTrue(set.get("bjimsg1").custom().isEmpty());
		assertEquals(1, this.warnings.size(), this.warnings.toString());
		assertTrue(this.warnings.get(0).contains("\"messages\"") && this.warnings.get(0).contains("namespaced"), this.warnings.get(0));
		assertTrue(this.warnings.get(0).contains("ava.json"), "names the file: " + this.warnings.get(0));
		assertTrue(this.warnings.get(0).contains("bjiMSG1"), "names the key as written: " + this.warnings.get(0));
	}

	@Test
	@DisplayName("a namespaced action nobody registered warns, naming it")
	void unregisteredActionWarns() {
		BindingSet set = parse("{\"bindings\": {\"a\": {\"other:thing\": 1}}}");

		assertTrue(set.get("a").custom().isEmpty());
		assertEquals(1, this.warnings.size());
		assertTrue(this.warnings.get(0).contains("other:thing"), this.warnings.get(0));
	}

	@Test
	@DisplayName("a registered custom action keeps its JSON exactly as given")
	void customActionKeepsItsJson() {
		BindingSet set = parse("{\"bindings\": {\"a\": {\"mymod:messages\": {\"lines\": [\"x\", \"y\"], \"n\": 3}}}}");

		assertEquals("{\"lines\":[\"x\",\"y\"],\"n\":3}", set.get("a").custom().get(MESSAGES).toString());
	}

	@Test
	@DisplayName("a sound id without a namespace is refused - a bare word must not quietly mean minecraft:")
	void bareSoundIdIsRefused() {
		BindingSet set = parse("{\"bindings\": {\"a\": {\"sounds\": [\"giggle\", \"mymod:ok\"]}}}");

		assertEquals(List.of(Identifier.of("mymod", "ok")), set.get("a").sounds());
		assertEquals(1, this.warnings.size());
		assertTrue(this.warnings.get(0).contains("needs a namespace"), this.warnings.get(0));
	}

	@Test
	@DisplayName("bad pool entries are skipped one at a time, and a bad weight falls back to 1")
	void badPoolEntries() {
		BindingSet set = parse("""
				{"bindings": {"a": {"random_sounds": [
				  "mymod:good",
				  {"sound": "mymod:a", "none": true},
				  {"weight": 2},
				  {"sound": "mymod:b", "weight": 0},
				  {"sound": "mymod:c", "weight": "heavy"},
				  42
				]}}}""");

		List<WeightedSound> pool = set.get("a").randomSounds();

		assertEquals(List.of(new WeightedSound(Identifier.of("mymod", "good"), 1), new WeightedSound(Identifier.of("mymod", "b"), 1),
				new WeightedSound(Identifier.of("mymod", "c"), 1)), pool);
		assertEquals(5, this.warnings.size(), this.warnings.toString());
	}

	@Test
	@DisplayName("a file with no bindings object, a non-object key, or a bad key each warn and never throw")
	void malformedFiles() {
		assertTrue(parse("{}").bindings().isEmpty());
		assertTrue(this.warnings.get(0).contains("\"bindings\" object"));

		this.warnings.clear();

		BindingSet set = parse("{\"bindings\": {\"ok\": {\"sounds\": [\"mymod:x\"]}, \"notobject\": 5, \"bad key!\": {}}}");

		assertEquals(1, set.bindings().size());
		assertNotNull(set.get("ok"));
		assertEquals(2, this.warnings.size(), this.warnings.toString());
	}

	@Test
	@DisplayName("sounds that is not an array warns, and the rest of the key still loads")
	void wrongShapeIsIsolated() {
		BindingSet set = parse("{\"bindings\": {\"a\": {\"sounds\": \"mymod:x\", \"random_sounds\": [\"mymod:y\"]}}}");

		assertTrue(set.get("a").sounds().isEmpty());
		assertEquals(1, set.get("a").randomSounds().size());
		assertEquals(1, this.warnings.size());
	}

	@Test
	@DisplayName("a key that is empty of actions is kept - it is how a character says a cue is silent for her")
	void emptyBindingIsKept() {
		BindingSet set = parse("{\"bindings\": {\"pound\": {}}}");

		assertNotNull(set.get("pound"));
		assertTrue(set.get("pound").isEmpty());
	}
}
