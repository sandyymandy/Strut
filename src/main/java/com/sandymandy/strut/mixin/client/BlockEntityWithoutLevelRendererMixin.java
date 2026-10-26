package com.sandymandy.strut.mixin.client;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.item.BuiltinModelItemRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.sandymandy.strut.animatable.client.StrutRenderProvider;

/**
 * Injection into the BEWLR rendering point to defer to Strut item rendering if applicable
 * <p>
 * Cancels the remainder of the render call if Strut renders, otherwise does nothing
 */
@Mixin(BuiltinModelItemRenderer.class)
public class BlockEntityWithoutLevelRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    public void strut$renderStrutItem(ItemStack stack, ModelTransformationMode displayContext, MatrixStack poseStack, VertexConsumerProvider bufferSource, int packedLight, int packedOverlay, CallbackInfo ci) {
        final BuiltinModelItemRenderer strutRenderer = StrutRenderProvider.of(stack).getStrutItemRenderer();

        if (strutRenderer != null) {
            strutRenderer.render(stack, displayContext, poseStack, bufferSource, packedLight, packedOverlay);

            ci.cancel();
        }
    }
}
