package com.sandymandy.strut.animatable.client;

import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.renderer.StrutArmorRenderer;
import com.sandymandy.strut.renderer.StrutItemRenderer;
import net.minecraft.client.render.item.BuiltinModelItemRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Where {@code StrutClient.item} and {@code StrutClient.armor} record how to render an item
 * <p>
 * This is what replaces the {@code createStrutRenderer} override with its anonymous {@link StrutRenderProvider}: the
 * registration is one call, and the provider the game asks for is derived from it. {@link StrutRenderProvider#of(Item)} looks
 * here first and falls back to the old override, so items written the old way keep working
 */
public final class StrutRenderProviders {
	private static final LazyProviderRegistry<Item, BuiltinModelItemRenderer, StrutArmorRenderer<?>> REGISTRY = new LazyProviderRegistry<>();

	/** One provider per registered item, so asking for it every frame allocates nothing */
	private static final Map<Item, StrutRenderProvider> PROVIDERS = new ConcurrentHashMap<>();

	private StrutRenderProviders() {}

	public static void registerItem(Item item, Supplier<? extends BuiltinModelItemRenderer> recipe) {
		REGISTRY.registerItem(item, recipe);
	}

	public static void registerArmor(Item item, Supplier<? extends StrutArmorRenderer<?>> recipe) {
		REGISTRY.registerArmor(item, recipe);
	}

	/**
	 * A provider that answers from the registry, or null if {@code item} was never registered through {@code StrutClient}
	 */
	@Nullable
	public static StrutRenderProvider providerFor(Item item) {
		if (!REGISTRY.has(item))
			return null;

		return PROVIDERS.computeIfAbsent(item, key -> new StrutRenderProvider() {
			@Nullable
			@Override
			public BuiltinModelItemRenderer getStrutItemRenderer() {
				return REGISTRY.item(item);
			}

			@Nullable
			@Override
			public <T extends LivingEntity> BipedEntityModel<?> getStrutArmorRenderer(@Nullable T livingEntity, ItemStack itemStack,
																					  @Nullable EquipmentSlot equipmentSlot, @Nullable BipedEntityModel<T> original) {
				return REGISTRY.armor(item);
			}
		});
	}

	/**
	 * Helper for {@code StrutClient}: the recipe for the default item renderer of {@code item}
	 */
	public static <T extends Item & StrutAnimatable> Supplier<StrutItemRenderer<T>> itemRecipe(T item, @Nullable StrutModel<T> model) {
		return () -> model != null ? new StrutItemRenderer<>(model) : new StrutItemRenderer<>(item);
	}
}
