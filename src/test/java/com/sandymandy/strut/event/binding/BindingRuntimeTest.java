package com.sandymandy.strut.event.binding;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.sandymandy.strut.event.client.ClientEventContext;
import com.sandymandy.strut.event.type.EventAuthority;
import com.sandymandy.strut.event.type.ParsedEvent;
import com.sandymandy.strut.event.builtin.StrutBuiltinEvents;
import com.sandymandy.strut.event.dispatch.EventStats;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fallback-chain lookup and carrying a binding out - the behaviour behind "one clip, a
 * different voice per character"
 */
class BindingRuntimeTest {
	private static final Identifier AVA = Identifier.of("mymod", "creatures/ava");
	private static final Identifier DEFAULT = Identifier.of("mymod", "creatures/default");
	private static final Identifier ACTION = Identifier.of("mymod", "messages");

	private BindingSets sets;
	private EventBindingActions actions;
	private BindingRuntime runtime;
	private List<String> played;

	@BeforeEach
	void setUp() {
		this.sets = new BindingSets();
		this.actions = new EventBindingActions();
		this.runtime = new BindingRuntime(this.sets, this.actions);
		this.played = new ArrayList<>();
	}

	private static Identifier snd(String path) {
		return Identifier.of("mymod", path);
	}

	private static Binding binding(List<Identifier> sounds, List<WeightedSound> pool) {
		return new Binding(sounds, pool, Map.of());
	}

	private static BindingSet set(Identifier id, String key, Binding binding) {
		return new BindingSet(id, Map.of(key, binding));
	}

	private void load(BindingSet... loaded) {
		Map<Identifier, BindingSet> map = new LinkedHashMap<>();

		for (BindingSet set : loaded)
			map.put(set.id(), set);

		this.sets.set(map);
	}

	private static ClientEventContext context(long sequence, long loop, int index) {
		return new ClientEventContext(null, 1, new UUID(5, 6), "base", sequence, loop, index, 100.0, 100,
				new ParsedEvent<>(StrutBuiltinEvents.MARKER, "cue"), EventAuthority.CLIENT, false);
	}

	private boolean run(String key, float volume, float pitch, List<Identifier> chain) {
		return this.runtime.run(context(1, 0, 0), key, volume, pitch, chain,
				(sound, vol, pit) -> this.played.add(sound.getPath() + "@" + vol + "/" + pit));
	}

	@Test
	@DisplayName("every fixed sound of a binding plays")
	void fixedSoundsAllPlay() {
		load(set(AVA, "pound", binding(List.of(snd("impact"), snd("bedrustle")), List.of())));

		assertTrue(run("pound", 1, 1, List.of(AVA)));
		assertEquals(List.of("impact@1.0/1.0", "bedrustle@1.0/1.0"), this.played);
	}

	@Test
	@DisplayName("the payload's volume and pitch multiply every sound the binding plays")
	void volumeAndPitchMultiply() {
		load(set(AVA, "pound", binding(List.of(snd("a")), List.of(new WeightedSound(snd("b"), 1)))));

		run("pound", 0.5f, 2f, List.of(AVA));

		assertEquals(List.of("a@0.5/2.0", "b@0.5/2.0"), this.played);
	}

	@Test
	@DisplayName("a random pool plays exactly one entry, and the same firing always picks the same one")
	void randomPoolIsDeterministic() {
		load(set(AVA, "cry", binding(List.of(), List.of(new WeightedSound(snd("ahh"), 1), new WeightedSound(snd("mmm"), 1)))));

		run("cry", 1, 1, List.of(AVA));

		assertEquals(1, this.played.size());

		String first = this.played.get(0);

		for (int i = 0; i < 5; i++) {
			this.played.clear();
			run("cry", 1, 1, List.of(AVA));
			assertEquals(List.of(first), this.played, "same entity, controller, sequence, loop and index - same pick");
		}
	}

	@Test
	@DisplayName("different firings pick differently, so the pool is actually used")
	void differentFiringsVary() {
		load(set(AVA, "cry", binding(List.of(), List.of(new WeightedSound(snd("ahh"), 1), new WeightedSound(snd("mmm"), 1)))));
		Set<String> seen = new HashSet<>();

		for (long sequence = 0; sequence < 40; sequence++) {
			this.played.clear();
			this.runtime.run(context(sequence, 0, 0), "cry", 1, 1, List.of(AVA), (s, v, p) -> this.played.add(s.getPath()));
			seen.addAll(this.played);
		}

		assertEquals(Set.of("ahh", "mmm"), seen);
	}

	@Test
	@DisplayName("silence in the pool plays nothing, but the binding's fixed sounds still do")
	void silenceInThePool() {
		load(set(AVA, "k", binding(List.of(snd("fixed")), List.of(new WeightedSound(null, 1)))));

		assertTrue(run("k", 1, 1, List.of(AVA)));
		assertEquals(List.of("fixed@1.0/1.0"), this.played);
	}

	@Test
	@DisplayName("the first set in the chain that defines a key supplies the whole binding - nothing is merged")
	void firstSetWinsWholesale() {
		load(set(AVA, "pound", binding(List.of(snd("ava_pound")), List.of())),
				set(DEFAULT, "pound", binding(List.of(snd("default_pound"), snd("default_extra")), List.of())));

		run("pound", 1, 1, List.of(AVA, DEFAULT));

		assertEquals(List.of("ava_pound@1.0/1.0"), this.played, "the default's sounds do not also play");
	}

	@Test
	@DisplayName("a key the first set lacks falls through to the next - a character overrides only what is hers")
	void fallsThroughTheChain() {
		load(set(AVA, "cry", binding(List.of(snd("ava_moan")), List.of())),
				set(DEFAULT, "pound", binding(List.of(snd("default_pound")), List.of())));

		run("pound", 1, 1, List.of(AVA, DEFAULT));

		assertEquals(List.of("default_pound@1.0/1.0"), this.played);
	}

	@Test
	@DisplayName("a character can silence a cue by defining it empty, instead of inheriting the default")
	void emptyBindingSilencesACue() {
		load(set(AVA, "pound", binding(List.of(), List.of())),
				set(DEFAULT, "pound", binding(List.of(snd("default_pound")), List.of())));

		assertTrue(run("pound", 1, 1, List.of(AVA, DEFAULT)));
		assertTrue(this.played.isEmpty());
	}

	@Test
	@DisplayName("a chain naming a set that is not loaded skips it and carries on to the next")
	void missingSetIsSkipped() {
		load(set(DEFAULT, "pound", binding(List.of(snd("default_pound")), List.of())));

		run("pound", 1, 1, List.of(AVA, DEFAULT));

		assertEquals(List.of("default_pound@1.0/1.0"), this.played);
	}

	@Test
	@DisplayName("an unbound key plays nothing, reports false, and is counted so a mistyped key can be found")
	void unboundKeyIsCounted() {
		load(set(AVA, "pound", binding(List.of(snd("x")), List.of())));
		long before = EventStats.unboundKeys();

		assertFalse(run("nonesuch", 1, 1, List.of(AVA)));
		assertTrue(this.played.isEmpty());
		assertEquals(before + 1, EventStats.unboundKeys());
	}

	@Test
	@DisplayName("key lookup is case-insensitive")
	void lookupIsCaseInsensitive() {
		load(set(AVA, "doggyfastmsg1", binding(List.of(snd("x")), List.of())));

		assertTrue(run("doggyFastMSG1", 1, 1, List.of(AVA)));
	}

	@Test
	@DisplayName("a mod's action runs with the JSON it was given, alongside the sounds")
	void customActionRuns() {
		List<String> got = new ArrayList<>();

		this.actions.register(ACTION, (ctx, config) -> got.add(config.getAsString()));
		load(set(AVA, "k", new Binding(List.of(snd("x")), List.of(), Map.of(ACTION, (JsonElement)new JsonPrimitive("Hihi~")))));

		run("k", 1, 1, List.of(AVA));

		assertEquals(List.of("Hihi~"), got);
		assertEquals(1, this.played.size(), "the sound played too");
	}

	@Test
	@DisplayName("a throwing action does not stop the sounds, or the other actions")
	void failingActionIsIsolated() {
		List<String> ran = new ArrayList<>();
		Identifier second = Identifier.of("mymod", "second");

		this.actions.register(ACTION, (ctx, config) -> {
			throw new IllegalStateException("boom");
		});
		this.actions.register(second, (ctx, config) -> ran.add("second"));

		Map<Identifier, JsonElement> custom = new LinkedHashMap<>();

		custom.put(ACTION, new JsonPrimitive(1));
		custom.put(second, new JsonPrimitive(2));
		load(set(AVA, "k", new Binding(List.of(snd("x")), List.of(), custom)));

		assertTrue(run("k", 1, 1, List.of(AVA)));
		assertEquals(1, this.played.size());
		assertEquals(List.of("second"), ran);
	}

	@Test
	@DisplayName("the engine's own namespace is reserved for its actions, and an action id cannot be taken twice")
	void actionRegistrationRules() {
		assertThrows(IllegalArgumentException.class, () -> this.actions.register(Identifier.of("strut", "sounds"), (c, j) -> {}));

		this.actions.register(ACTION, (c, j) -> {});

		assertThrows(IllegalStateException.class, () -> this.actions.register(ACTION, (c, j) -> {}));
	}

	@Test
	@DisplayName("loading replaces every set, so one a pack removed stops being found")
	void reloadReplaces() {
		load(set(AVA, "pound", binding(List.of(snd("x")), List.of())));
		load(set(DEFAULT, "other", binding(List.of(snd("y")), List.of())));

		assertTrue(this.sets.get(AVA).isEmpty());
		assertTrue(this.sets.get(DEFAULT).isPresent());
		assertEquals(1, this.sets.size());
	}
}
