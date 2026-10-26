package com.sandymandy.strut.event;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumerProvider;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.renderer.*;
import com.sandymandy.strut.service.StrutEvents;

/**
 * Fabric service implementation for Strut's various events
 */
public class StrutEventsFabric implements StrutEvents {
    /**
     * Fire the {@link StrutRenderEvent.Block.Pre} event
     */
    @Override
    public boolean fireBlockPreRender(StrutBlockRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
        return StrutRenderEvent.Block.Pre.EVENT.invoker().handle(new StrutRenderEvent.Block.Pre(renderer, poseStack, model, bufferSource, partialTick, packedLight));
    }

    /**
     * Fire the {@link StrutRenderEvent.Block.Post} event
     */
    @Override
    public void fireBlockPostRender(StrutBlockRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
        StrutRenderEvent.Block.Post.EVENT.invoker().handle(new StrutRenderEvent.Block.Post(renderer, poseStack, model, bufferSource, partialTick, packedLight));
    }

    /**
     * Fire the {@link StrutRenderEvent.Armor.Pre} event
     */
    @Override
    public boolean fireArmorPreRender(StrutArmorRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
        return StrutRenderEvent.Armor.Pre.EVENT.invoker().handle(new StrutRenderEvent.Armor.Pre(renderer, poseStack, model, bufferSource, partialTick, packedLight));
    }

    /**
     * Fire the {@link StrutRenderEvent.Armor.Post} event
     */
    @Override
    public void fireArmorPostRender(StrutArmorRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
        StrutRenderEvent.Armor.Post.EVENT.invoker().handle(new StrutRenderEvent.Armor.Post(renderer, poseStack, model, bufferSource, partialTick, packedLight));
    }

    /**
     * Fire the {@link StrutRenderEvent.Entity.Pre} event
     */
    @Override
    public boolean fireEntityPreRender(StrutEntityRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
        return StrutRenderEvent.Entity.Pre.EVENT.invoker().handle(new StrutRenderEvent.Entity.Pre(renderer, poseStack, model, bufferSource, partialTick, packedLight));
    }

    /**
     * Fire the {@link StrutRenderEvent.Entity.Post} event
     */
    @Override
    public void fireEntityPostRender(StrutEntityRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
        StrutRenderEvent.Entity.Post.EVENT.invoker().handle(new StrutRenderEvent.Entity.Post(renderer, poseStack, model, bufferSource, partialTick, packedLight));
    }

    /**
     * Fire the {@link StrutRenderEvent.ReplacedEntity.Pre} event
     */
    @Override
    public boolean fireReplacedEntityPreRender(StrutReplacedEntityRenderer<?, ?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
        return StrutRenderEvent.ReplacedEntity.Pre.EVENT.invoker().handle(new StrutRenderEvent.ReplacedEntity.Pre(renderer, poseStack, model, bufferSource, partialTick, packedLight));
    }

    /**
     * Fire the {@link StrutRenderEvent.ReplacedEntity.Post} event
     */
    @Override
    public void fireReplacedEntityPostRender(StrutReplacedEntityRenderer<?, ?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
        StrutRenderEvent.ReplacedEntity.Post.EVENT.invoker().handle(new StrutRenderEvent.ReplacedEntity.Post(renderer, poseStack, model, bufferSource, partialTick, packedLight));
    }

    /**
     * Fire the {@link StrutRenderEvent.Item.Pre} event
     */
    @Override
    public boolean fireItemPreRender(StrutItemRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
        return StrutRenderEvent.Item.Pre.EVENT.invoker().handle(new StrutRenderEvent.Item.Pre(renderer, poseStack, model, bufferSource, partialTick, packedLight));
    }

    /**
     * Fire the {@link StrutRenderEvent.Item.Post} event
     */
    @Override
    public void fireItemPostRender(StrutItemRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
        StrutRenderEvent.Item.Post.EVENT.invoker().handle(new StrutRenderEvent.Item.Post(renderer, poseStack, model, bufferSource, partialTick, packedLight));
    }

    /**
     * Fire the {@link StrutRenderEvent.Object.Pre} event
     */
    @Override
    public boolean fireObjectPreRender(StrutObjectRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
        return StrutRenderEvent.Object.Pre.EVENT.invoker().handle(new StrutRenderEvent.Object.Pre(renderer, poseStack, model, bufferSource, partialTick, packedLight));
    }

    /**
     * Fire the {@link StrutRenderEvent.Object.Post} event
     */
    @Override
    public void fireObjectPostRender(StrutObjectRenderer<?> renderer, MatrixStack poseStack, BakedStrutModel model, VertexConsumerProvider bufferSource, float partialTick, int packedLight) {
       StrutRenderEvent.Object.Post.EVENT.invoker().handle(new StrutRenderEvent.Object.Post(renderer, poseStack, model, bufferSource, partialTick, packedLight));
    }
}
