package com.sandymandy.strut.service;

import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.model.StrutModel;

/**
 * Loader-agnostic service interface for clientside functionalities
 */
public interface StrutClient {
    /**
     * Helper method for retrieving an (ideally) cached instance of the armor model for a given Item
     * <p>
     * If no custom model applies to this item, the {@code defaultModel} is returned
     */
    @NotNull
    <T extends LivingEntity & StrutAnimatable> BipedEntityModel<?> getArmorModelForItem(T animatable, ItemStack stack, EquipmentSlot slot, BipedEntityModel<LivingEntity> defaultModel);

    /**
     * Helper method for retrieving an (ideally) cached instance of the StrutModel for the given item
     *
     * @return The StrutModel for the item, or null if not applicable
     */
    @Nullable
    StrutModel<?> getStrutModelForItem(ItemStack item);

    /**
     * Helper method for retrieving an (ideally) cached instance of the StrutModel for the given item's armor renderer
     *
     * @return The StrutModel for the item, or null if not applicable
     */
    @Nullable
    StrutModel<?> getStrutModelForArmor(ItemStack armour);
}
