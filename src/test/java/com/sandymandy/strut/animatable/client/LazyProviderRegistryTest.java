package com.sandymandy.strut.animatable.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How an item's renderers are registered and built
 * <p>
 * Renderers need the client's dispatchers, which do not exist when items are registered, so a registration stores a recipe and
 * the first render builds it. These rules are what stand between "one line in StrutClient" and a renderer built too early,
 * built every frame, or lost when the second half of an item is registered
 */
class LazyProviderRegistryTest {
	private final LazyProviderRegistry<String, String, Integer> registry = new LazyProviderRegistry<>();

	@Test
	@DisplayName("registering builds nothing: the recipe runs on first use")
	void nothingBuiltAtRegistration() {
		AtomicInteger built = new AtomicInteger();

		this.registry.registerItem("staff", () -> "renderer " + built.incrementAndGet());

		assertEquals(0, built.get(), "the client's dispatchers do not exist yet at registration");
		assertEquals("renderer 1", this.registry.item("staff"));
	}

	@Test
	@DisplayName("a renderer is built once and reused on every later call")
	void builtOnce() {
		AtomicInteger built = new AtomicInteger();

		this.registry.registerItem("staff", () -> "renderer " + built.incrementAndGet());

		for (int i = 0; i < 50; i++)
			assertEquals("renderer 1", this.registry.item("staff"));

		assertEquals(1, built.get());
	}

	@Test
	@DisplayName("an unregistered key, or a half never registered, answers null instead of failing")
	void unregisteredIsNull() {
		this.registry.registerItem("staff", () -> "item");

		assertNull(this.registry.item("other"));
		assertNull(this.registry.armor("other"));
		assertNull(this.registry.armor("staff"), "registered as an item only");
		assertFalse(this.registry.has("other"));
		assertTrue(this.registry.has("staff"));
	}

	@Test
	@DisplayName("the item and armor halves of one key are independent, whichever is registered first")
	void halvesAreIndependent() {
		AtomicInteger items = new AtomicInteger();
		AtomicInteger armors = new AtomicInteger();

		this.registry.registerArmor("helmet", () -> 100 + armors.incrementAndGet());

		assertEquals(101, this.registry.armor("helmet"));

		// Registering the item half afterwards must not lose or rebuild the armor renderer already built
		this.registry.registerItem("helmet", () -> "item " + items.incrementAndGet());

		assertEquals("item 1", this.registry.item("helmet"));
		assertEquals(101, this.registry.armor("helmet"));
		assertEquals(1, armors.get());
		assertEquals(1, this.registry.size(), "one key, two halves");
	}

	@Test
	@DisplayName("registering a key's item again replaces the recipe and discards a renderer built from the old one")
	void reRegistrationReplaces() {
		this.registry.registerItem("staff", () -> "old");

		assertEquals("old", this.registry.item("staff"));

		this.registry.registerItem("staff", () -> "new");

		assertEquals("new", this.registry.item("staff"));
	}

	@Test
	@DisplayName("many keys keep their own renderers")
	void manyKeys() {
		for (int i = 0; i < 20; i++) {
			int n = i;

			this.registry.registerItem("k" + i, () -> "item" + n);
			this.registry.registerArmor("k" + i, () -> n);
		}

		for (int i = 0; i < 20; i++) {
			assertEquals("item" + i, this.registry.item("k" + i));
			assertEquals(i, this.registry.armor("k" + i));
		}

		assertEquals(20, this.registry.size());
	}
}
