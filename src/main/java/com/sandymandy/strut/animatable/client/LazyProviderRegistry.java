package com.sandymandy.strut.animatable.client;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Remembers, per key, how to build an item renderer and an armor renderer, and builds each <b>once, on first use</b>
 * <p>
 * Renderers cannot be built when the item is registered: they need the client's block-entity dispatcher and model loader, which
 * do not exist yet. So a registration stores a recipe (a {@link Supplier}) and the first render asks for the result. The item
 * and armor recipes of one key are independent - registering the second does not disturb the first, or a renderer already built
 * <p>
 * Generic and free of Minecraft types so the rules are tested without a game; {@link StrutRenderProviders} is the
 * item-specific wrapper
 *
 * @param <K> The key, an {@code Item} in the game
 * @param <I> The item renderer type
 * @param <A> The armor renderer type
 */
public final class LazyProviderRegistry<K, I, A> {
	private static final class Entry<I, A> {
		@Nullable
		Supplier<? extends I> itemRecipe;
		@Nullable
		I item;
		@Nullable
		Supplier<? extends A> armorRecipe;
		@Nullable
		A armor;
	}

	private final Map<K, Entry<I, A>> entries = new HashMap<>();

	/**
	 * Registers how to build {@code key}'s item renderer. A second registration for the same key replaces the first, and discards
	 * a renderer already built from it
	 */
	public synchronized void registerItem(K key, Supplier<? extends I> recipe) {
		Entry<I, A> entry = this.entries.computeIfAbsent(key, k -> new Entry<>());

		entry.itemRecipe = recipe;
		entry.item = null;
	}

	public synchronized void registerArmor(K key, Supplier<? extends A> recipe) {
		Entry<I, A> entry = this.entries.computeIfAbsent(key, k -> new Entry<>());

		entry.armorRecipe = recipe;
		entry.armor = null;
	}

	/**
	 * The item renderer for {@code key}, built on first call, or null if none was registered
	 */
	@Nullable
	public synchronized I item(K key) {
		Entry<I, A> entry = this.entries.get(key);

		if (entry == null || entry.itemRecipe == null)
			return null;

		if (entry.item == null)
			entry.item = entry.itemRecipe.get();

		return entry.item;
	}

	@Nullable
	public synchronized A armor(K key) {
		Entry<I, A> entry = this.entries.get(key);

		if (entry == null || entry.armorRecipe == null)
			return null;

		if (entry.armor == null)
			entry.armor = entry.armorRecipe.get();

		return entry.armor;
	}

	public synchronized boolean has(K key) {
		return this.entries.containsKey(key);
	}

	public synchronized int size() {
		return this.entries.size();
	}
}
