package com.sandymandy.strut.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import com.sandymandy.strut.util.InternalUtil;

/**
 * Injection into the render point for armour on HumanoidModels (Players, Zombies, etc) to defer to Strut item-armor rendering as applicable
 * <p>
 * Does nothing if Strut has nothing to handle for the given arguments
 */
@Mixin(ArmorFeatureRenderer.class)
public abstract class HumanoidArmorLayerMixin<T extends LivingEntity, M extends BipedEntityModel<T>, A extends BipedEntityModel<T>> {
    @Shadow
    protected abstract void setVisible(A baseModel, EquipmentSlot equipmentSlot);

    @WrapWithCondition(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/entity/LivingEntity;FFFFFF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/entity/feature/ArmorFeatureRenderer;renderArmor(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/entity/EquipmentSlot;ILnet/minecraft/client/render/entity/model/BipedEntityModel;)V"))
    public boolean strut$wrapArmorPieceRender(ArmorFeatureRenderer<T, M, A> renderLayer, MatrixStack poseStack, VertexConsumerProvider bufferSource, T entity, EquipmentSlot equipmentSlot, int packedLight, A baseModel,
                                                 MatrixStack poseStack2, VertexConsumerProvider bufferSource2, int packedLight2, T entity2, float limbSwing, float limbSwingAmount, float partialTick, float lerpedTickCount, float netHeadYaw, float headPitch) {
        return !InternalUtil.tryRenderStrutArmorPiece(poseStack, bufferSource, entity, entity.getEquippedStack(equipmentSlot), equipmentSlot, renderLayer.getContextModel(), baseModel, partialTick, packedLight, limbSwing, limbSwingAmount, lerpedTickCount, netHeadYaw, headPitch, this::setVisible);
    }
}