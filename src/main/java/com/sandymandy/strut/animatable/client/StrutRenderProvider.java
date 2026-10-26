package com.sandymandy.strut.animatable.client;

import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.item.BuiltinModelItemRenderer;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.animatable.StrutItem;
import com.sandymandy.strut.renderer.StrutArmorRenderer;
import com.sandymandy.strut.renderer.StrutItemRenderer;

/**
 * Internal interface for safely providing a custom item and armor renderer instances at runtime
 * <p>
 * This can be safely instantiated as a new anonymous class inside your {@link Item} class
 * <p>
 * This is a functional equivalent to Forge's {@code IClientItemExtensions}. If on Forge, either can be used
 *
 * @see com.sandymandy.strut.renderer.StrutItemRenderer StrutItemRenderer
 * @see com.sandymandy.strut.renderer.StrutArmorRenderer StrutArmorRenderer
 */
public interface StrutRenderProvider {
    StrutRenderProvider DEFAULT = new StrutRenderProvider() {};

    /**
     * Get the StrutRenderProvider instance for the given ItemStack, if applicable
     *
     * @param itemStack The ItemStack to get the provider instance for
     * @return The StrutRenderProvider instance for this stack, or a defaulted empty instance if not defined
     */
    static StrutRenderProvider of(ItemStack itemStack) {
        return of(itemStack.getItem());
    }

    /**
     * Get the StrutRenderProvider instance for the given Item, if applicable
     *
     * @param item The ItemStack to get the provider instance for
     * @return The StrutRenderProvider instance for this item, or a defaulted empty instance if not defined
     */
    static StrutRenderProvider of(Item item) {
        // Registered with StrutClient.item / StrutClient.armor: no createStrutRenderer override needed
        StrutRenderProvider registered = StrutRenderProviders.providerFor(item);

        if (registered != null)
            return registered;

        if (item instanceof StrutItem strutItem)
            return (StrutRenderProvider)strutItem.getRenderProvider();

        return DEFAULT;
    }

    /**
     * Get the cached {@link BuiltinModelItemRenderer} instance for this provider.
     * <p>
     * Normally this would be an instance of {@link StrutItemRenderer}
     *
     * @return The cached BEWLR instance for this provider, or null if not applicable
     */
    @Nullable
    default BuiltinModelItemRenderer getStrutItemRenderer() {
        return null;
    }

    /**
     * Get the cached {@link StrutArmorRenderer} instance for this provider.
     * <p>
     * Normally this would be an instance of {@link StrutArmorRenderer}
     *
     * @param livingEntity The entity currently wearing the item, if applicable
     * @param itemStack The ItemStack for this provider
     * @param equipmentSlot The slot the ItemStack is currently in, if applicable
     * @param original The base BipedEntityModel (usually the default vanilla armor model), if applicable
     * @return The cached BipedEntityModel instance for this provider, or null if not applicable
     */
    @Nullable
    default <T extends LivingEntity> BipedEntityModel<?> getStrutArmorRenderer(@Nullable T livingEntity, ItemStack itemStack, @Nullable EquipmentSlot equipmentSlot, @Nullable BipedEntityModel<T> original) {
        return null;
    }
}