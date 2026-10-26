package com.sandymandy.strut.service;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumerProvider;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.renderer.*;

/**
 * Loader-agnostic service interface for Strut's various events
 */
public interface StrutEvents {
    /**
     * Fire the Block.Pre event
     */
    boolean fireBlockPreRender(StrutBlockRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight);
    /**
     * Fire the Block.Post event
     */
    void fireBlockPostRender(StrutBlockRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight);

    /**
     * Fire the Armor.Pre event
     */
    boolean fireArmorPreRender(StrutArmorRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight);
    /**
     * Fire the Armor.Post event
     */
    void fireArmorPostRender(StrutArmorRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight);

    /**
     * Fire the Entity.Pre event
     */
    boolean fireEntityPreRender(StrutEntityRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight);
    /**
     * Fire the Entity.Post event
     */
    void fireEntityPostRender(StrutEntityRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight);

    /**
     * Fire the ReplacedEntity.Pre event
     */
    boolean fireReplacedEntityPreRender(StrutReplacedEntityRenderer<?, ?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight);
    /**
     * Fire the ReplacedEntity.Post event
     */
    void fireReplacedEntityPostRender(StrutReplacedEntityRenderer<?, ?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight);

    /**
     * Fire the Item.Pre event
     */
    boolean fireItemPreRender(StrutItemRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight);
    /**
     * Fire the Item.Post event
     */
    void fireItemPostRender(StrutItemRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight);

    /**
     * Fire the Object.Pre event
     */
    boolean fireObjectPreRender(StrutObjectRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight);
    /**
     * Fire the Object.Post event
     */
    void fireObjectPostRender(StrutObjectRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight);
}
