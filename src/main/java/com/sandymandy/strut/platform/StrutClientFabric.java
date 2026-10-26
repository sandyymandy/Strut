package com.sandymandy.strut.platform;

import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.client.StrutRenderProvider;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.renderer.StrutArmorRenderer;
import com.sandymandy.strut.renderer.StrutRenderer;
import com.sandymandy.strut.service.StrutClient;

/**
 * Fabric service implementation for clientside functionalities
 */
public class StrutClientFabric implements StrutClient {
    /**
     * Helper method for retrieving an (ideally) cached instance of the armor model for a given Item
     * <p>
     * If no custom model applies to this item, the {@code defaultModel} is returned
     */
    @NotNull
    @Override
    public <T extends LivingEntity & StrutAnimatable> BipedEntityModel<?> getArmorModelForItem(T animatable, ItemStack stack, EquipmentSlot slot, BipedEntityModel<LivingEntity> defaultModel) {
        return StrutRenderProvider.of(stack).getStrutArmorRenderer(animatable, stack, slot, defaultModel) instanceof StrutArmorRenderer<?> strutArmorRenderer ? strutArmorRenderer : defaultModel;
    }

    /**
     * Helper method for retrieving an (ideally) cached instance of the StrutModel for the given item
     *
     * @return The StrutModel for the item, or null if not applicable
     */
    @Nullable
    @Override
    public StrutModel<?> getStrutModelForItem(ItemStack item) {
        if (StrutRenderProvider.of(item).getStrutItemRenderer() instanceof StrutRenderer<?> strutItemRenderer)
            return strutItemRenderer.getStrutModel();

        return null;
    }

    /**
     * Helper method for retrieving an (ideally) cached instance of the StrutModel for the given item's armor renderer
     *
     * @return The StrutModel for the item, or null if not applicable
     */
    @Nullable
    @Override
    public StrutModel<?> getStrutModelForArmor(ItemStack armour) {
        if (StrutRenderProvider.of(armour).getStrutArmorRenderer(null, armour, null, null) instanceof StrutArmorRenderer<?> armorRenderer)
            return armorRenderer.getStrutModel();

        return null;
    }
}
