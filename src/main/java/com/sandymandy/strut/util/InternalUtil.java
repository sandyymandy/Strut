package com.sandymandy.strut.util;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.component.ComponentType;
import net.minecraft.component.ComponentMapImpl;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Equipment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.client.StrutRenderProvider;
import com.sandymandy.strut.renderer.StrutArmorRenderer;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Internal-only class to store common code mostly for implementation purposes.
 * <p>
 * Shouldn't be used by dependent mods
 */
@ApiStatus.Internal
public class InternalUtil {
    /**
     * Attempt to render a Strut {@link StrutArmorRenderer armor piece} for the given slot
     * <p>
     * This is typically only called by an internal mixin
     *
     * @return true if the armor piece was a Strut armor piece and rendered
     * @param <T> The entity that is rendering its armor
     */
    public static <T extends LivingEntity, M extends BipedEntityModel<T>, A extends BipedEntityModel<T>> boolean tryRenderStrutArmorPiece(MatrixStack poseStack, VertexConsumerProvider bufferSource, T entity, ItemStack stack, EquipmentSlot equipmentSlot, M parentModel, A baseModel,
                                                                                                                                  float partialTick, int packedLight, float limbSwing, float limbSwingAmount, float lerpedTickCount, float netHeadYaw, float headPitch,
                                                                                                                                  BiConsumer<A, EquipmentSlot> partVisibilitySetter) {
        final Item item = stack.getItem();

        if (!(item instanceof Equipment equipable) || equipable.getSlotType() != equipmentSlot)
            return false;

        final BipedEntityModel<?> strutModel = StrutRenderProvider.of(item).getStrutArmorRenderer(entity, stack, equipmentSlot, baseModel);

        if (strutModel == null)
            return false;

        parentModel.copyStateTo(baseModel);
        partVisibilitySetter.accept(baseModel, equipmentSlot);

        if (strutModel instanceof StrutArmorRenderer<?> strutArmorRenderer)
            strutArmorRenderer.prepForRender(entity, stack, equipmentSlot, baseModel, bufferSource, partialTick, limbSwing, limbSwingAmount, netHeadYaw, headPitch);

        baseModel.copyStateTo((A)strutModel);
        strutModel.render(poseStack, null, packedLight, OverlayTexture.DEFAULT_UV, Color.WHITE.argbInt());

        return true;
    }

    /**
     * Perform an {@link Object#equals(Object)} check on two {@link ComponentMapImpl}s,
     * ignoring any Strut stack ids that may be present.
     * <p>
     * This is typically only called by an internal mixin
     */
    public static boolean areComponentsMatchingIgnoringStrutId(ComponentMapImpl map1, ComponentMapImpl map2) {
        final ComponentType<Long> stackId = StrutConstants.STACK_ANIMATABLE_ID_COMPONENT.get();
        boolean patched = false;

        if (map1.contains(stackId)) {
            ComponentMapImpl prevMap = map1;
            boolean copyOnWrite = prevMap.copyOnWrite;
            (map1 = map1.copy()).remove(stackId);
            map1.copyOnWrite = copyOnWrite;
            patched = true;
        }

        if (map2.contains(stackId)) {
            ComponentMapImpl prevMap = map2;
            boolean copyOnWrite = prevMap.copyOnWrite;
            (map2 = map2.copy()).remove(stackId);
            map2.copyOnWrite = copyOnWrite;
            patched = true;
        }

        return patched && Objects.equals(map1, map2);
    }
}
